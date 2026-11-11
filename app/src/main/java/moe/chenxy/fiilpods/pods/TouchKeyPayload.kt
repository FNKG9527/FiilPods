package moe.chenxy.fiilpods.pods

/**
 * 触控灵敏度与按键配置的线值编解码。
 *
 * 纯函数放在这里，方便单测；协议来源见 [FiilRfcommController] 里的注释：
 * - GAIA `FFCOMMAND_SetTouchSensitivity=1918` / `GetTouchSensitivity=1919`；
 * - Mate `cid=34 cmdId=keyId payload={action}` / `cid=39 cmdId=5`。
 */
internal object TouchKeyPayload {

    /** 低/中/高灵敏度线值（官方 `setTouchSensitivity` 的 44/37/73）。 */
    val LEVEL_BYTES = intArrayOf(44, 37, 73)

    /** 官方按键动作码：无操作、语音助手、上一首、下一首、音量加、音量减、播放/暂停、低延时。 */
    val ACTION_CODES = setOf(0, 2, 3, 4, 5, 6, 7, 8)

    /** 线值 → 0/1/2；不认识返回 -1。 */
    fun levelOf(wire: Int): Int = LEVEL_BYTES.indexOf(wire)

    /**
     * 官方把左右耳塞进同一条写命令：`payload[0]` 1=左、0=右；
     * `payload[1]` 是 [LEVEL_BYTES] 之一。
     */
    fun sensitivityPayload(ear: Int, level: Int): ByteArray {
        val side = if (ear == 0) 1 else 0
        return byteArrayOf(side.toByte(), LEVEL_BYTES[level.coerceIn(0, LEVEL_BYTES.lastIndex)].toByte())
    }

    /**
     * 读灵敏度回包：`payload[1]=右耳`、`payload[2]=左耳`（官方解析顺序如此），
     * 返回 (left, right) 的档位。不足 3 字节视为无效回包。
     */
    fun parseSensitivity(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size < 3) return null
        val right = levelOf(payload[1].toInt() and 0xFF)
        val left = levelOf(payload[2].toInt() and 0xFF)
        return left to right
    }

    /**
     * 读按键配置回包。
     *
     * 布局是「若干组 × 3 字节」的 `(keyId, type, value)`，动作码在每组**第 3 个字节**；
     * 首字节若是 0x1E(30) 则是后续字节数（PC 实测 payload = `1E 01 01 07 02 01 07 ...`）。
     *
     * ⚠️ 门槛不是固定 30：官方各机型 Fragment 的 `onRead` 判长度不一致 ——
     * `F045/F047/F048/F046` 用 `buff.length >= 30`（10 组），
     * `F049/F051/F053` 用 `buff.length >= 24`（只有 8 组）。
     * 旧实现硬要求 30，短包直接返回 null，于是这类机型永远停在「读取中」。
     *
     * 因此这里按「能读出多少组就读多少组」解析：最少 [MIN_GROUP_BYTES] 字节（8 组），
     * 首字节是 0x1E 时跳过计数位，否则按无计数位处理；两种起始位置都试，
     * 取解出有效 key 更多的那种（并列时优先带计数位的布局）。
     *
     * @return 下标 1..10 的动作码，没出现的 key 保持 -1；连 8 组都拼不出来时返回 null。
     */
    fun parseKeyActions(payload: ByteArray): IntArray? {
        if (payload.size < MIN_GROUP_BYTES) return null
        val hasCountByte = (payload[0].toInt() and 0xFF) == COUNT_PREFIX
        // 带计数位优先；两种都试，避免个别固件把计数位省掉、或首字节恰好也等于 30。
        val starts = if (hasCountByte) intArrayOf(1, 0) else intArrayOf(0, 1)
        var best: IntArray? = null
        var bestKnown = -1
        for (start in starts) {
            val groups = (payload.size - start) / GROUP_BYTES
            if (groups < MIN_GROUPS) continue
            val parsed = IntArray(KEY_COUNT + 1) { -1 }
            repeat(groups.coerceAtMost(KEY_COUNT)) { index ->
                val off = start + index * GROUP_BYTES
                val keyId = payload[off].toInt() and 0xFF
                val action = payload[off + 2].toInt() and 0xFF
                if (keyId in 1..KEY_COUNT) parsed[keyId] = action
            }
            val known = parsed.count { it >= 0 }
            if (known > bestKnown) {
                best = parsed
                bestKnown = known
            }
        }
        return best
    }

    /** 一组 `(keyId, type, value)` 的字节数。 */
    private const val GROUP_BYTES = 3

    /** 机型按键表最多 10 个键。 */
    private const val KEY_COUNT = 10

    /** 官方最短回包是 8 组（`F049/F051/F053` 的 `buff.length >= 24`）。 */
    private const val MIN_GROUPS = 8
    private const val MIN_GROUP_BYTES = MIN_GROUPS * GROUP_BYTES

    /** 计数位：PC 实测 payload 首字节 0x1E(=30) 表示其后还有 30 字节。 */
    private const val COUNT_PREFIX = 30
}
