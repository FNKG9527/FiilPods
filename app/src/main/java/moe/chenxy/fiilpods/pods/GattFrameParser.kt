package moe.chenxy.fiilpods.pods

/** BLE 与 SPP 使用同一套帧；通道不决定协议族。 */
enum class FrameDialect {
    MATE,
    GAIA;

    companion object {
        /** `isMate=true` → MATE，否则 GAIA；供需要在两者间旁路转换的控制器使用。 */
        fun fromMate(isMate: Boolean): FrameDialect = if (isMate) MATE else GAIA
    }
}

data class GattFrameBatch(
    val mate: List<FiilMate.Frame> = emptyList(),
    val gaia: List<FiilGaia.Frame> = emptyList(),
    val remaining: Int = 0,
    /** 本次 [GattFrameParser.accept] 新确认的协议族；初始预设/未变化均为 null。 */
    val newlyDetectedDialect: FrameDialect? = null,
)

/**
 * 可测试的有状态拆包器；输入为只读数据，不依赖 Android BluetoothGatt。
 *
 * [initialDialect] 未知时，只有收到完整、已验证的 GAIA 帧才锁定 GAIA。不能把尚未
 * 收全的 `FF 01/F1 ... 02 34` 前缀交给 Mate drain：Mate 在非法头处会逐字节重同步，
 * 从而丢掉 GAIA 的 SOF；但也不能只看 0xFF，因为 Mate 的 seq 合法范围包含 0xFF。
 */
class GattFrameParser(initialDialect: FrameDialect?) {
    private val pending = ByteArray(MAX_PENDING_BYTES)
    private var length = 0

    @Volatile
    var detectedDialect: FrameDialect? = initialDialect
        private set

    /** 原子地换协议族并抛弃上一轮连接残留的半帧。 */
    @Synchronized
    fun reset(dialect: FrameDialect?) {
        detectedDialect = dialect
        length = 0
    }

    /** 保留当前协议选择，仅丢弃半帧（连接/断开边界使用）。 */
    @Synchronized
    fun clear() {
        length = 0
    }

    @Synchronized
    fun accept(bytes: ByteArray): GattFrameBatch {
        append(bytes)
        val before = detectedDialect
        val batch = when (detectedDialect) {
            FrameDialect.MATE -> mateBatch()
            FrameDialect.GAIA -> gaiaBatch()
            null -> unknownBatch()
        }
        length = batch.remaining
        return batch.copy(
            newlyDetectedDialect = detectedDialect?.takeIf { before == null && it != before },
        )
    }

    private fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // GATT notify 正常远小于缓冲上限；极端异常包不能让蓝牙 binder 线程因
        // IllegalArgumentException 崩溃。优先保留最新字节，让下次有效帧重新同步。
        if (bytes.size >= pending.size) {
            bytes.copyInto(pending, 0, bytes.size - pending.size, bytes.size)
            length = pending.size
            return
        }
        if (length + bytes.size > pending.size) {
            length = 0
        }
        bytes.copyInto(pending, length)
        length += bytes.size
    }

    private fun mateBatch(): GattFrameBatch =
        FiilMate.drain(pending, length).let { GattFrameBatch(mate = it.first, remaining = it.second) }

    private fun gaiaBatch(): GattFrameBatch =
        FiilGaia.drain(pending, length).let { GattFrameBatch(gaia = it.first, remaining = it.second) }

    private fun unknownBatch(): GattFrameBatch {
        // 一旦完整合法 GAIA 帧出现，协议族就确定；其余同批尾帧也由 Gaia drain 消费。
        if (FiilGaia.parse(pending, 0, length) != null) {
            detectedDialect = FrameDialect.GAIA
            return gaiaBatch()
        }
        // 仅在前缀通过 VERSION/VENDOR 的渐进校验时保留；避免把 Mate seq=FF 误当 GAIA。
        if (hasPossibleIncompleteGaiaPrefix()) {
            return GattFrameBatch(remaining = length)
        }
        // 不是可验证的 GAIA 前缀，保留既有 Mate 默认和重同步行为。
        return mateBatch()
    }

    /**
     * 判定缓冲起点是否可能是还没收全的 GAIA 帧。任何已到达的认证字段不匹配都会
     * 回到 Mate 的既有重同步路径；长度字段 255 同样不是 FIIL 的合法 GAIA 载荷。
     */
    private fun hasPossibleIncompleteGaiaPrefix(): Boolean {
        if (length == 0 || byteAt(0) != FiilGaia.SOF) return false
        if (length >= 2 && byteAt(1) !in setOf(FiilGaia.VERSION_LEGACY, FiilGaia.VERSION_NEW)) return false
        if (length >= 4 && byteAt(3) > FiilGaia.MAX_PAYLOAD) return false
        if (length >= 5 && byteAt(4) != (FiilGaia.VENDOR ushr 8)) return false
        if (length >= 6 && byteAt(5) != (FiilGaia.VENDOR and 0xFF)) return false
        // 完整 header 但 payload 尚缺也必须原样保留，等下一 notify 续上。
        return true
    }

    private fun byteAt(index: Int): Int = pending[index].toInt() and 0xFF

    private companion object {
        // 保留多帧通知，同时远大于一帧 262 字节的 GAIA 上限。
        const val MAX_PENDING_BYTES = FiilGaia.MAX_FRAME * 4
    }
}
