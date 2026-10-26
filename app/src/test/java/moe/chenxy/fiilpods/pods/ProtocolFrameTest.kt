package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对真实协议编解码代码的 JVM 运行级测试。
 *
 * 这些类是纯 Kotlin（不依赖任何 Android 类），因此可以直接在 JVM 上执行，
 * 验证「组帧 → 字节序列 → 解帧 → 语义」这条链路真的能跑通，
 * 而不是只靠编译器说「能编译」。
 */
class ProtocolFrameTest {

    // ------------------------------------------------------------------
    // GAIA
    // ------------------------------------------------------------------

    /** 组帧的字节必须与 FIIL+ APP 的 getSendBuffer 逐字节一致。 */
    @Test
    fun gaiaBuildFrameMatchesAppByteLayout() {
        val frame = FiilGaia.buildFrame(FiilCommands.GET_BATTERY_LEVEL, newProtocol = false)
        // 0xFF | 0x01(老协议) | 0x00(flags) | 0x00(len) | 0x02 0x34(vendor) | 0x03 0x03(cmd)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x01, 0x00, 0x00, 0x02, 0x34, 0x03, 0x03),
            frame,
        )
        assertEquals(8, frame.size)
    }

    @Test
    fun gaiaNewProtocolUsesF1Version() {
        val frame = FiilGaia.buildFrame(FiilCommands.GET_BATTERY_LEVEL_V2, newProtocol = true)
        assertEquals(0xF1.toByte(), frame[1])
        assertEquals(0x07, frame[6].toInt() and 0xFF)
        assertEquals(0x08, frame[7].toInt() and 0xFF)
    }

    @Test
    fun gaiaPayloadIsCopiedAfterHeader() {
        val frame = FiilGaia.buildFrame(FiilCommands.SET_ANC, false, byteArrayOf(1))
        assertEquals(9, frame.size)
        assertEquals(1, frame[3].toInt())      // LENGTH = payload 长度
        assertEquals(1, frame[8].toInt())      // payload 从 [8] 开始
    }

    /** 回包的 ACK 位：cmdRaw 最高位为 1 时，真实命令号是 cmdRaw and 0x7FFF。 */
    @Test
    fun gaiaParseDetectsAckAndMasksCommand() {
        val ackCmd = FiilCommands.SET_ANC or FiilGaia.ACK_FLAG
        val raw = FiilGaia.buildFrame(ackCmd, false, byteArrayOf(1))
        val f = FiilGaia.parse(raw, 0, raw.size)
        assertNotNull(f)
        f!!
        assertTrue(f.isAck)
        assertEquals(FiilCommands.SET_ANC, f.command)
        assertEquals(ackCmd, f.commandRaw)
    }

    @Test
    fun gaiaParseRejectsBadSofOrVendor() {
        val good = FiilGaia.buildFrame(FiilCommands.GET_ANC, false)
        val badSof = good.copyOf().also { it[0] = 0x00 }
        assertNull(FiilGaia.parse(badSof, 0, badSof.size))

        val badVendor = good.copyOf().also { it[5] = 0x35 }
        assertNull(FiilGaia.parse(badVendor, 0, badVendor.size))

        val badVersion = good.copyOf().also { it[1] = 0x42 }
        assertNull(FiilGaia.parse(badVersion, 0, badVersion.size))
    }

    /** 半帧不能当成完整帧解析（流式读取必须等齐）。 */
    @Test
    fun gaiaParseRejectsTruncatedFrame() {
        val full = FiilGaia.buildFrame(FiilCommands.GET_ANC, false, byteArrayOf(1, 2, 3))
        assertNull(FiilGaia.parse(full, 0, full.size - 1))
    }

    /** 一次 read 拿到「两帧 + 半帧」时，drain 必须返回 2 帧并把尾巴留在缓冲区开头。 */
    @Test
    fun gaiaDrainHandlesMultipleFramesAndPartialTail() {
        val f1 = FiilGaia.buildFrame(FiilCommands.GET_ANC, false, byteArrayOf(2))
        val f2 = FiilGaia.buildFrame(FiilCommands.GET_EQ, false, byteArrayOf(3))
        val half = FiilGaia.buildFrame(FiilCommands.GET_3D, false, byteArrayOf(0))
        val buf = ByteArray(512)
        val stream = f1 + f2 + half.copyOfRange(0, 4)
        stream.copyInto(buf)

        val (frames, remain) = FiilGaia.drain(buf, stream.size)
        assertEquals(2, frames.size)
        assertEquals(FiilCommands.GET_ANC, frames[0].command)
        assertEquals(FiilCommands.GET_EQ, frames[1].command)
        assertEquals(4, remain)
        // 尾巴已被搬到 buffer 开头
        assertArrayEquals(half.copyOfRange(0, 4), buf.copyOfRange(0, 4))
    }

    /** 帧前的垃圾字节必须被重同步跳过（假 SOF 也要跳过）。 */
    @Test
    fun gaiaDrainResyncsOverGarbage() {
        val good = FiilGaia.buildFrame(FiilCommands.GET_ANC, false, byteArrayOf(1))
        val garbage = byteArrayOf(0x00, 0xFF.toByte(), 0x99.toByte(), 0x12)
        val buf = ByteArray(512)
        (garbage + good).copyInto(buf)
        val (frames, remain) = FiilGaia.drain(buf, garbage.size + good.size)
        assertEquals(1, frames.size)
        assertEquals(FiilCommands.GET_ANC, frames[0].command)
        assertEquals(0, remain)
    }

    /** 电量解析复刻 APP 的「十六进制串再 +1」行为。 */
    @Test
    fun gaiaParseBatteryLevelReplicatesAppMath() {
        // payload = [?, ?, 0x64] → raw=0x64=100 → hex "64" → parseInt("64",16)=100 → +1 = 101
        val b = byteArrayOf(0, 0, 0x64)
        val frame = FiilGaia.parse(
            FiilGaia.buildFrame(FiilCommands.GET_BATTERY_LEVEL, false, b), 0,
            FiilGaia.buildFrame(FiilCommands.GET_BATTERY_LEVEL, false, b).size,
        )!!
        assertEquals(101, FiilGaia.parseBatteryLevel(frame))
    }

    @Test
    fun gaiaParseBoxBatteryHasNoPlusOne() {
        val raw = FiilGaia.buildFrame(FiilCommands.GET_BATTERY_OF_BOX, false, byteArrayOf(0, 80))
        val f = FiilGaia.parse(raw, 0, raw.size)!!
        assertEquals(80, FiilGaia.parseBoxBatteryLevel(f))
    }

    /** 佩戴状态：左在 payload[2]，右在 payload[1]。 */
    @Test
    fun gaiaParseWearSwapsLeftAndRight() {
        val raw = FiilGaia.buildFrame(FiilCommands.GET_WEAR, false, byteArrayOf(0, 1, 0))
        val f = FiilGaia.parse(raw, 0, raw.size)!!
        val (left, right) = FiilGaia.parseWear(f)!!
        assertFalse("payload[2]=0 → 左未佩戴", left)
        assertTrue("payload[1]=1 → 右已佩戴", right)
    }

    @Test
    fun gaiaPayloadTooLargeIsRejected() {
        var threw = false
        try {
            FiilGaia.buildFrame(1, false, ByteArray(255))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("payload > 254 必须抛异常", threw)
    }

    // ------------------------------------------------------------------
    // Mate
    // ------------------------------------------------------------------

    /** 带 commandId 的帧：00 01 01 00 03 <cmd> 01 <val>。 */
    @Test
    fun mateBuildFrameWithCommandId() {
        val f = FiilMate.buildFrame(FiilMate.Cid.SET_PARAM, FiilMate.Param.MULTIPOINT, byteArrayOf(1))
        assertArrayEquals(
            byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x03, 0x01, 0x01, 0x01),
            f,
        )
    }

    /** 不带 commandId 的帧（佩戴检测）：00 26 01 00 01 <val>。 */
    @Test
    fun mateBuildFrameWithoutCommandId() {
        val f = FiilMate.buildFrame(FiilMate.Cid.SET_WEAR, 0, byteArrayOf(1))
        assertArrayEquals(
            byteArrayOf(0x00, 0x26, 0x01, 0x00, 0x01, 0x01),
            f,
        )
    }

    @Test
    fun mateRoundTripWithCommandId() {
        val payload = byteArrayOf(0x05, 0x06, 0x07)
        val raw = FiilMate.buildFrame(FiilMate.Cid.SET_EQ, 0x21, payload)
        val f = FiilMate.parse(raw)
        assertNotNull(f)
        f!!
        assertEquals(FiilMate.Cid.SET_EQ, f.classifyId)
        assertEquals(0x21, f.commandId)
        assertArrayEquals(payload, f.payload)
        assertEquals(raw.size, f.size)
    }

    @Test
    fun mateParseRejectsWrongMagic() {
        val raw = FiilMate.buildFrame(FiilMate.Cid.SET_MAF, 0, byteArrayOf(1))
        // 偏移 2 是 type，合法值只有 0x01(REQUEST) / 0x02(RESPONSE) / 0x03(NOTIFY)。
        // 0x02 是**耳机回包**，必须能解析 —— 早期把它当非法值拒掉，
        // 导致真机连上后所有应答都被丢弃（FIIL Key Max 实测回包全是 0x02）。
        val resp = raw.copyOf().also { it[2] = 0x02 }
        assertNotNull(FiilMate.parse(resp))
        val notify = raw.copyOf().also { it[2] = 0x03 }
        assertNotNull(FiilMate.parse(notify))
        // 真正非法的 type 必须拒
        val badType = raw.copyOf().also { it[2] = 0x09 }
        assertNull(FiilMate.parse(badType))
        val badReserved = raw.copyOf().also { it[3] = 0x09 }
        assertNull(FiilMate.parse(badReserved))
    }

    /** Mate 帧不能以 GAIA 的方式解析，反之亦然——这是分发的前提。 */
    @Test
    fun mateAndGaiaAreMutuallyExclusive() {
        val mate = FiilMate.buildFrame(FiilMate.Cid.SET_MAF, 0, byteArrayOf(1))
        val gaia = FiilGaia.buildFrame(FiilCommands.GET_ANC, false, byteArrayOf(1))
        assertNull(FiilGaia.parse(mate, 0, mate.size))
        assertNull(FiilMate.parse(gaia))
        assertTrue(FiilMate.looksLikeMate(mate, mate.size))
        assertFalse(FiilMate.looksLikeMate(gaia, gaia.size))
    }

    /** 单字节状态短帧：lenField=1。 */
    @Test
    fun mateParseSingleByteStatusFrame() {
        val raw = byteArrayOf(0x00, 0x28, 0x01, 0x00, 0x01, 0x02)  // cid=40(NOTIFY), len=1, value=2
        val f = FiilMate.parse(raw)
        assertNotNull(f)
        f!!
        assertEquals(FiilMate.Cid.NOTIFY, f.classifyId)
        assertEquals(0, f.commandId)
        assertEquals(1, f.payload.size)
        assertEquals(2, f.byteAt(0))
    }

    /** 一次 read 拿到两帧 + 半帧，drain 必须正确切分。 */
    @Test
    fun mateDrainHandlesMultipleFramesAndPartialTail() {
        val f1 = FiilMate.buildFrame(FiilMate.Cid.SET_MAF, 0, byteArrayOf(1))
        val f2 = FiilMate.buildFrame(FiilMate.Cid.SET_PARAM, FiilMate.Param.MULTIPOINT, byteArrayOf(1))
        val f3 = FiilMate.buildFrame(FiilMate.Cid.SET_SPATIAL_AUDIO, 0, byteArrayOf(0))
        val stream = f1 + f2 + f3.copyOfRange(0, 3)
        val buf = ByteArray(512)
        stream.copyInto(buf)

        val (frames, remain) = FiilMate.drain(buf, stream.size)
        assertEquals(2, frames.size)
        assertEquals(FiilMate.Cid.SET_MAF, frames[0].classifyId)
        assertEquals(FiilMate.Param.MULTIPOINT, frames[1].commandId)
        assertEquals(3, remain)
    }

    /** 杂散 0x00 不能让 drain 永久卡死。 */
    @Test
    fun mateDrainSkipsStrayZeroBytes() {
        val good = FiilMate.buildFrame(FiilMate.Cid.SET_MAF, 0, byteArrayOf(1))
        val buf = ByteArray(512)
        (byteArrayOf(0x00, 0x00) + good).copyInto(buf)
        val (frames, remain) = FiilMate.drain(buf, 2 + good.size)
        assertEquals(1, frames.size)
        assertEquals(0, remain)
    }

    /** MAF 五态线值必须是 0/1/2/3/4，且 2=开放 3=兼听 4=风中。 */
    @Test
    fun mateMafWireValuesMatchReverseEngineeredTable() {
        assertEquals(0, FiilMate.Maf.OFF)
        assertEquals(1, FiilMate.Maf.NOISE_CANCEL)
        assertEquals(2, FiilMate.Maf.OPEN)
        assertEquals(3, FiilMate.Maf.LISTEN)
        assertEquals(4, FiilMate.Maf.WIND)
        assertEquals("关闭", FiilModels.mateMafLabel(0))
        assertEquals("降噪", FiilModels.mateMafLabel(1))
        assertEquals("开放", FiilModels.mateMafLabel(2))
        assertEquals("兼听", FiilModels.mateMafLabel(3))
        assertEquals("风中", FiilModels.mateMafLabel(4))
    }

    /** 10 段 EQ 默认值全部是 128（0 dB 偏移量）。 */
    @Test
    fun mateDefaultEqBandsAreTenSegmentsAt128() {
        val bands = FiilMate.defaultBands(46)
        assertEquals(10, bands.size)
        assertArrayEquals(IntArray(10) { 128 }, bands)
    }

    /**
     * 语音提示音量 SET 帧：`00 01 01 00 03 03 01 <vol>`。
     *
     * 来源 `com.fengeek.main.i.d.f.java:711-726 setTone`（cid=1、cmdId=3、buffer={vol}）。
     * 线值与文案反序：0=高 1=中 2=低 3=静音。
     */
    @Test
    fun mateToneVolumeFrameMatchesReverseEngineeredLayout() {
        for (volume in 0..3) {
            val f = FiilMate.buildFrame(FiilMate.Cid.SET_PARAM, 3, byteArrayOf(volume.toByte()))
            assertArrayEquals(
                byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x03, 0x03, 0x01, volume.toByte()),
                f,
            )
        }
    }

    /**
     * 语音提示音量 GET 帧：`00 27 01 00 02 82 00`。
     *
     * cid=39（QUERY 组），cmdId=130（0x82，即 `-126 & 0xFF`）。
     * `buildFrame` 在 commandId != 0 时会写 (payloadLen+2) 与 cmdId、payloadLen 三段，
     * 所以 payload 为空时长度字段是 0x02、后面紧跟 `82 00`。真机实测 TX 即此 7 字节。
     */
    @Test
    fun mateToneVolumeQueryFrameIsCid39Cmd130() {
        assertEquals(39, FiilMate.Cid.QUERY)
        assertEquals(130, FiilMate.Query.TONE)
        val f = FiilMate.buildFrame(FiilMate.Cid.QUERY, FiilMate.Query.TONE)
        assertArrayEquals(
            byteArrayOf(0x00, 0x27, 0x01, 0x00, 0x02, 0x82.toByte(), 0x00),
            f,
        )
    }

    /**
     * 回包样本 `01 27 02 00 03 82 01 02` 应解析出音量档 2（音量低）。
     *
     * 逐字节：`seq=01 | cid=27 | type=02 | rsv=00 | lenField=03 | cmd=82 | payloadLen=01 | payload=02`
     * —— 长度字段 0x03 减 2 得 payloadLen 1，所以 **payload 只有 1 字节**，
     * 音量在 `payload[0]`。早前注释把 `01`（payloadLen）误读成「字段存在标志」，
     * 于是错误地以为音量在 `payload[1]`；这里用断言把正确偏移钉死。
     */
    @Test
    fun mateToneVolumeResponseParsesVolumeFromFirstPayloadByte() {
        val raw = byteArrayOf(
            0x01, 0x27, 0x02, 0x00, 0x03, 0x82.toByte(), 0x01, 0x02,
        )
        val frame = FiilMate.parse(raw)
        assertNotNull(frame)
        frame!!
        assertEquals(FiilMate.Cid.QUERY, frame.classifyId)
        assertEquals(FiilMate.Query.TONE, frame.commandId)
        assertEquals("payloadLen=1，payload 只有音量一字节", 1, frame.payload.size)
        assertEquals(0x02, frame.byteAt(0, -1))
    }

    /**
     * 回归：**首字节 seq 是滚动计数器，不能当帧判别依据**。
     *
     * 真机 34 条回包里只有 3 条 seq=0。早期实现写死 `buffer[0] != 0x00 → null`，
     * 等于丢掉 31/34 的应答 —— 这是「连上了但电量/ANC/EQ 全读不到」的根因。
     * 这里用真机抓到的 seq=00~07 逐条构造，要求**全部可解析**。
     */
    @Test
    fun mateParseAcceptsRollingSeq() {
        val realSamples = listOf(
            "00 27 02 00 03 FF 01 FF",
            "01 27 02 00 03 08 01 00",
            "02 25 02 00 01 00",
            "03 28 03 00 03 08 01 01",
            "04 27 02 00 03 08 01 01",
            "05 25 02 00 01 01",
            "06 27 02 00 05 01 03 00 50 00",
            "07 27 02 00 03 0C 01 01",
        )
        for (hex in realSamples) {
            val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
            val f = FiilMate.parse(bytes)
            assertNotNull("seq=${hex.take(2)} 必须可解析", f)
            assertEquals("seq 应原样透传", hex.take(2).toInt(16), f!!.seq)
        }
    }

    /**
     * 回归：`drain()` 必须能连续解出 seq 各不相同的多帧，
     * 且遇到半帧时保留而不是吞掉。
     */
    @Test
    fun drainHandlesRollingSeqAndPartialFrames() {
        // 三帧连发：seq=09 / 0A / 0B，长度各异
        val stream = (
            "09 27 02 00 03 FF 01 FF " +
                "0A 27 02 00 03 08 01 00 " +
                "0B 27 02 00 03 0C 01 01"
            ).split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val buf = ByteArray(stream.size + 8)
        System.arraycopy(stream, 0, buf, 0, stream.size)
        val (frames, remain) = FiilMate.drain(buf, stream.size)
        assertEquals("三帧都应解出", 3, frames.size)
        assertEquals(0x09, frames[0].seq)
        assertEquals(0x0A, frames[1].seq)
        assertEquals(0x0B, frames[2].seq)
        assertEquals(0, remain)

        // 半帧：只给前 4 字节，必须保留不丢
        val partial = byteArrayOf(0x0C, 0x27, 0x02, 0x00)
        val (none, left) = FiilMate.drain(partial.copyOf(partial.size), partial.size)
        assertTrue("半帧不应解出", none.isEmpty())
        assertEquals("半帧必须完整保留", 4, left)
    }
}
