package moe.chenxy.fiilpods.pods

/**
 * 电量回包解析。
 *
 * 单独抽成纯函数，是因为「头戴到底该读哪个字节」这件事已经错过两次：
 * 第一版只读 `payload[1]` 还同时写给左右耳，充电盒永远不更新；第二版改成
 * 三段解析后，头戴的 `[0, 85, 0]` 又被读成「左 0 / 右 85 / 盒 0」。
 * 这类错误没法靠真机回归兜住（每次都要连设备），必须有单测钉死。
 */
internal object BatteryPayload {

    /**
     * Mate 电量 payload。
     *
     * - 头戴式（F046 Key Max 等）：只有 `payload[1]` 是电量，`payload[0]` 是保留位，
     *   `payload[2]` 恒为 0。官方 `F046HomeFragment.java:623-625` 也是 `m(buff[1])`。
     * - 双耳式：`[0]=左`、`[1]=右`、`[2]=盒`；255 表示该路不可用。
     *
     * @return (左/整体, 右, 盒)，不可用的那一路为 -1。
     */
    fun parseMate(payload: ByteArray, headphones: Boolean): Triple<Int, Int, Int> {
        if (payload.isEmpty()) return Triple(-1, -1, -1)

        fun level(index: Int): Int {
            if (index >= payload.size) return -1
            val v = payload[index].toInt() and 0xFF
            return if (v == 255) -1 else v
        }

        if (headphones) {
            // 极少数固件只回一个字节，那就只能当整体值。
            return Triple(level(if (payload.size >= 2) 1 else 0), -1, -1)
        }
        if (payload.size == 1) {
            // 合并值固件：左右耳同值，比丢掉整帧好。
            return Triple(level(0), level(0), -1)
        }
        return Triple(level(0), level(1), if (payload.size >= 3) level(2) else -1)
    }
}
