package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LDAC 诊断链路的协议级回归。
 *
 * 只碰纯 Kotlin 的 [FiilMate] / [FiilModels] / [FiilStatus]，不依赖 Android 运行时；
 * 真正的解析逻辑在 `FiilRfcommController`（依赖 `android.util.Log`），JVM 单测跑不了，
 * 所以这里守的是**协议常量与组帧**这一层 —— 一旦常量写错，链路上层全都白做。
 */
class LdacDiagnosticsProtocolTest {

    /**
     * 诊断用到的 cmdId 必须与 `cid=39` 查询表一致。
     *
     * 这些值来自 `docs/RESEARCH_2026-11_F046_LDAC.md` §2.5 的实测回包表，
     * 改动其中任何一个都会让诊断面板读到别的字段（而且是静默读错，不是报错）。
     */
    @Test
    fun `ldac query ids match the measured table`() {
        assertEquals(140, FiilMate.Query.LICENSE_SLOT_LEFT)
        assertEquals(141, FiilMate.Query.LICENSE_SLOT_RIGHT)
        assertEquals(142, FiilMate.Query.LDAC_UNLOCK)
        assertEquals(143, FiilMate.Query.LDAC_SWITCH)
        assertEquals(144, FiilMate.Query.CODEC)
        assertEquals(254, FiilMate.Query.CAPABILITY)
        // 写入侧（与查询侧是两套编号，别混）
        assertEquals(13, FiilMate.Param.LDAC_SWITCH)
        assertEquals(12, FiilMate.Param.LDAC_ACTIVATE)
    }

    /**
     * 140 的**同号不同义**是有意为之，不是笔误。
     *
     * F046 上 140 是 license 槽；F049/F051/F053 上是 `getWindAdaptive`。
     * 这条断言的作用是拦住「顺手把 LICENSE_SLOT_LEFT 改成别的号」——
     * 那样 F049 的抗风噪查询就再也发不出去了，而且没人会注意到。
     */
    @Test
    fun `license slot shares id 140 with wind adaptive on purpose`() {
        assertEquals(FiilMate.Query.WIND_ADAPTIVE, FiilMate.Query.LICENSE_SLOT_LEFT)
        // 分流依据是机型，不是号段
        assertTrue(FiilModels.hasLdacLicenseSlot(46))
        assertTrue(FiilModels.supportsWindAdaptive(49))
        assertFalse(FiilModels.hasLdacLicenseSlot(49))
    }

    /**
     * 高位置 1 的 cmdId（140..254）必须能原样走完「组帧 → 解帧」。
     *
     * `cmdId` 是 signed byte，142/143/144/254 全都 > 127。组帧时漏了 `and 0xFF`
     * 或者解帧时漏了 `and 0xFF`，这些命令就会变成负数（-114/-113/...），
     * 所有 `when (commandId) { LDAC_UNLOCK -> ... }` 分支永远不命中。
     */
    @Test
    fun `high bit query ids survive build and parse`() {
        val ids = intArrayOf(140, 141, 142, 143, 144, 254)
        for (id in ids) {
            val bytes = FiilMate.buildFrame(FiilMate.Cid.QUERY, id)
            assertEquals("cmdId=$id 的查询帧应为 7 字节（无 payload）", 7, bytes.size)
            assertEquals("cmdId=$id 必须按无符号字节写入", id and 0xFF, bytes[5].toInt() and 0xFF)

            val frame = FiilMate.parse(bytes) ?: error("cmdId=$id 组出的帧解不回来")
            assertEquals("cmdId=$id 解帧后编号必须一致", id, frame.commandId)
            assertEquals(FiilMate.Cid.QUERY, frame.classifyId)
            // 查询请求本身不带 payload；`dispatchMate` 判断「有没有回答案」靠的正是这一点。
            assertTrue("cmdId=$id 的查询请求不应带 payload", frame.payload.isEmpty())
        }
    }

    /**
     * 耳机「不认识这条查询」时的回包形态：`xx 27 02 00 00`（plen=0，没有 cmdId）。
     *
     * 这种空帧**不是**「值为 0」。旧实现把 `ldacUnlocked` 直接置 false，
     * 于是「问不到」被当成「未解锁」，用户在官方 App 里白跑一趟。
     */
    @Test
    fun `empty response frame has no payload and no command id`() {
        val empty = byteArrayOf(0x00, 0x27, 0x02, 0x00, 0x00)
        val frame = FiilMate.parse(empty) ?: error("空回包应能解成帧")
        assertEquals(5, frame.size)
        assertEquals(0, frame.commandId)
        assertTrue(frame.payload.isEmpty())
    }

    /** 142 的「未知」与「未解锁」是两件事，状态模型必须能分开表达。 */
    @Test
    fun `unlock known is independent from unlocked`() {
        val notReported = FiilStatus()
        assertFalse("默认必须是「没读到」", notReported.ldacUnlockKnown)
        assertFalse(notReported.ldacUnlocked)

        val reportedNotUnlocked = notReported.copy(ldacUnlockKnown = true, ldacUnlocked = false)
        assertTrue(reportedNotUnlocked.ldacUnlockKnown)
        assertFalse(reportedNotUnlocked.ldacUnlocked)

        // 实测出现过 142=00 而 LDAC 仍在工作，所以这两个字段不能合成一个布尔。
        val reportedUnlocked = notReported.copy(ldacUnlockKnown = true, ldacUnlocked = true)
        assertTrue(reportedUnlocked.ldacUnlockKnown)
        assertTrue(reportedUnlocked.ldacUnlocked)
    }
}
