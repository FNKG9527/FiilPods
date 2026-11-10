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
     * 读按键配置回包：首字节可能是组数 0x1E(30)，其后 10 组 × 3 字节
     * `(keyId, type, value)`。返回下标 1..10 的动作码，未出现的 key 保持 -1。
     */
    fun parseKeyActions(payload: ByteArray): IntArray? {
        val start = if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 30) 1 else 0
        if (payload.size - start < 30) return null
        val parsed = IntArray(11) { -1 }
        var off = start
        repeat(10) {
            val keyId = payload[off].toInt() and 0xFF
            val action = payload[off + 2].toInt() and 0xFF
            if (keyId in 1..10) parsed[keyId] = action
            off += 3
        }
        return parsed
    }
}
