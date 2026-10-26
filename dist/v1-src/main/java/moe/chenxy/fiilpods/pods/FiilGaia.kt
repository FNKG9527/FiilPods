package moe.chenxy.fiilpods.pods

/**
 * FIIL GAIA 协议帧编解码。
 *
 * 帧结构（来自 com/fiil/sdk/command/FiilCommandUtil.java:517 getSendBuffer）：
 *
 *   [0] SOF        = 0xFF
 *   [1] VERSION    = 0x01（老协议） / 0xF1（新协议）
 *   [2] FLAGS      = 0x00（App 侧永远不置位）
 *   [3] LENGTH     = payload 字节数（不含帧头，最大 254）
 *   [4] VENDOR_H   = 0x02
 *   [5] VENDOR_L   = 0x34         → vendor 固定 0x0234 = 564
 *   [6] CMD_H
 *   [7] CMD_L
 *   [8..] PAYLOAD
 *
 * 回包方向：耳机回 ACK 时把 CMD 的最高位置 1，
 * 因此 cmdRaw and 0x8000 != 0 表示这是一条 ACK，
 * 真实命令号 = cmdRaw and 0x7FFF。
 */
object FiilGaia {

    const val SOF = 0xFF
    const val VERSION_LEGACY = 0x01
    const val VERSION_NEW = 0xF1
    const val VENDOR: Int = 0x0234
    const val HEADER_SIZE = 8
    const val MAX_PAYLOAD = 254
    const val MAX_FRAME = HEADER_SIZE + MAX_PAYLOAD

    const val ACK_FLAG = 0x8000
    const val CMD_MASK = 0x7FFF

    /** 组装一条完整帧。 */
    fun buildFrame(cmd: Int, newProtocol: Boolean, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "GAIA payload too large: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = SOF.toByte()
        out[1] = (if (newProtocol) VERSION_NEW else VERSION_LEGACY).toByte()
        out[2] = 0
        out[3] = payload.size.toByte()
        out[4] = (VENDOR ushr 8).toByte()
        out[5] = VENDOR.toByte()
        out[6] = (cmd ushr 8).toByte()
        out[7] = cmd.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun buildFrame(cmd: Int, newProtocol: Boolean, single: Byte): ByteArray =
        buildFrame(cmd, newProtocol, byteArrayOf(single))

    /** 解析结果。 */
    data class Frame(
        val commandRaw: Int,
        val command: Int,
        val isAck: Boolean,
        val isNewProtocol: Boolean,
        val payload: ByteArray,
        val size: Int,
    )

    /**
     * 从 [buf] 的 [offset] 位置尝试解析一帧。
     * 返回 null 表示「此处不是一帧」；返回 Frame 时 size 为消耗的字节数。
     */
    fun parse(buf: ByteArray, offset: Int, length: Int): Frame? {
        if (length - offset < HEADER_SIZE) return null
        if (buf[offset].toInt() and 0xFF != SOF) return null
        val version = buf[offset + 1].toInt() and 0xFF
        if (version != VERSION_LEGACY && version != VERSION_NEW) return null
        val vendor = ((buf[offset + 4].toInt() and 0xFF) shl 8) or (buf[offset + 5].toInt() and 0xFF)
        if (vendor != VENDOR) return null
        val payloadLen = buf[offset + 3].toInt() and 0xFF
        val total = HEADER_SIZE + payloadLen
        if (length - offset < total) return null
        val raw = ((buf[offset + 6].toInt() and 0xFF) shl 8) or (buf[offset + 7].toInt() and 0xFF)
        val payload = buf.copyOfRange(offset + HEADER_SIZE, offset + total)
        return Frame(
            commandRaw = raw,
            command = raw and CMD_MASK,
            isAck = (raw and ACK_FLAG) != 0,
            isNewProtocol = version == VERSION_NEW,
            payload = payload,
            size = total,
        )
    }

    /**
     * 流式解帧：C 端 SPP 是字节流，一次 read 可能拿到半帧或多帧。
     * 这里按「找 SOF → 校验 VERSION/VENDOR → 读 LENGTH」的方式重同步。
     */
    fun drain(buffer: ByteArray, length: Int): Pair<List<Frame>, Int> {
        val frames = ArrayList<Frame>(2)
        var pos = 0
        while (pos < length) {
            // 重同步到下一个疑似 SOF
            if (buffer[pos].toInt() and 0xFF != SOF) {
                pos++
                continue
            }
            // 头未收全：保留剩余字节
            if (length - pos < HEADER_SIZE) break
            val version = buffer[pos + 1].toInt() and 0xFF
            val vendor = ((buffer[pos + 4].toInt() and 0xFF) shl 8) or (buffer[pos + 5].toInt() and 0xFF)
            if ((version != VERSION_LEGACY && version != VERSION_NEW) || vendor != VENDOR) {
                // 假 SOF（payload 内也可能出现 0xFF），跳过这一个字节继续找
                pos++
                continue
            }
            val payloadLen = buffer[pos + 3].toInt() and 0xFF
            val total = HEADER_SIZE + payloadLen
            if (length - pos < total) break
            val f = parse(buffer, pos, length) ?: run { pos++; null } ?: continue
            frames.add(f)
            pos += total
        }
        // 把未消费的尾巴搬到开头
        val remain = length - pos
        if (remain > 0 && pos > 0) System.arraycopy(buffer, pos, buffer, 0, remain)
        return frames to remain
    }

    /** 一位 payload 的常用取法：回包解析统统是 payload[1]（少数 payload[2]）。 */
    fun byteAt(frame: Frame, index: Int, default: Int = 0): Int =
        if (frame.payload.size > index) frame.payload[index].toInt() and 0xFF else default

    /**
     * 电量。App 里 analysisGetBatteryLevel（GaiaSdkCommand.java:724）的实现是：
     *   Integer.parseInt(Integer.toHexString(bArr[2])) + 1
     * 也就是把十进制串当十六进制解析后再 +1 —— 这是 App 的既定行为，
     * 为了和官方 App 显示一致，这里原样复刻。
     */
    fun parseBatteryLevel(frame: Frame): Int? {
        val b = frame.payload
        if (b.size < 3) return null
        val raw = b[2].toInt() and 0xFF
        if (raw == 0) return 0
        val hex = raw.toString(16)
        return runCatching { hex.toInt(16) + 1 }.getOrDefault(raw)
    }

    /** 充电盒电量：直接取 payload[1]，无 +1。 */
    fun parseBoxBatteryLevel(frame: Frame): Int? {
        val b = frame.payload
        if (b.size < 2) return null
        return b[1].toInt() and 0xFF
    }

    /** 佩戴状态：左在 payload[2]，右在 payload[1]。 */
    fun parseWear(frame: Frame): Pair<Boolean, Boolean>? {
        val b = frame.payload
        if (b.size < 3) return null
        return (b[2].toInt() != 0) to (b[1].toInt() != 0)
    }
}
