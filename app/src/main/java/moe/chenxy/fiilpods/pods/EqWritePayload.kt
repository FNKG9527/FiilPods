package moe.chenxy.fiilpods.pods

/** Mate 自定义 EQ 的命令 3 请求体。reserved 必须来自最近一次真实回包。 */
object EqWritePayload {
    fun build(bands: ByteArray, type: Int, reserved: Int, expectedCount: Int): ByteArray {
        require(bands.size == expectedCount) {
            "自定义 EQ 应为 $expectedCount 段，实际 ${bands.size} 段"
        }
        val out = ByteArray(bands.size + 2)
        out[0] = reserved.toByte()
        out[1] = type.toByte()
        bands.copyInto(out, 2)
        return out
    }
}
