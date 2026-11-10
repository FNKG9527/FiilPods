package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「头戴式只有一档整体电量」这条数据链。
 *
 * ## 真机 bug
 *
 * FIIL Key Max（earType=46）的电量回包 payload 是 `[0, 85, 0]`：
 * `[0]` 是保留位、`[1]` 才是整体电量、`[2]` 恒为 0。旧代码按
 * 「左 / 右 / 盒」三段解析，于是在 UI 上显示成「左 0% / 右 85% / 盒 0%」。
 *
 * 官方 `F046HomeFragment.java:623-625` 的 `setElectricity(buff)` 只做
 * `m(buff[1])`，可以确认头戴没有分耳语义。
 *
 * 这些断言不连真机也能跑，覆盖三层：
 *  1. [BatteryPayload.parseMate] 的字节解析；
 *  2. [FiilStatus.displayBattery] 的聚合；
 *  3. [BatterySnapshot] 是否把右耳/盒正确置空。
 */
class BatteryFormFactorTest {

    // ---- 1. Mate payload 解析 ----

    @Test
    fun `headphone payload 0 85 0 reads overall from byte 1`() {
        val (left, right, case) = BatteryPayload.parseMate(byteArrayOf(0, 85, 0), headphones = true)
        assertEquals("头戴的整体电量在 payload[1]", 85, left)
        assertEquals("头戴没有右耳这一路", -1, right)
        assertEquals("头戴没有盒这一路", -1, case)
    }

    @Test
    fun `headphone single byte payload still yields a value`() {
        val (left, right, case) = BatteryPayload.parseMate(byteArrayOf(85), headphones = true)
        assertEquals(85, left)
        assertEquals(-1, right)
        assertEquals(-1, case)
    }

    @Test
    fun `headphone 255 means unavailable`() {
        val (left, _, _) = BatteryPayload.parseMate(byteArrayOf(0, 255.toByte(), 0), headphones = true)
        assertEquals(-1, left)
    }

    @Test
    fun `tws payload keeps three independent values`() {
        val (left, right, case) = BatteryPayload.parseMate(byteArrayOf(80, 60, 40), headphones = false)
        assertEquals(80, left)
        assertEquals(60, right)
        assertEquals(40, case)
    }

    @Test
    fun `same bytes mean different things for tws and headphones`() {
        // 同一段 [0, 85, 0]，双耳读成「左 0 / 右 85 / 盒 0」，头戴读成「整体 85」。
        // 这正是修复前后行为差异的最小复现。
        val tws = BatteryPayload.parseMate(byteArrayOf(0, 85, 0), headphones = false)
        assertEquals(Triple(0, 85, 0), tws)
        val hp = BatteryPayload.parseMate(byteArrayOf(0, 85, 0), headphones = true)
        assertEquals(Triple(85, -1, -1), hp)
    }

    @Test
    fun `tws unavailable channels stay 255 to -1`() {
        val (left, right, case) = BatteryPayload.parseMate(
            byteArrayOf(255.toByte(), 60, 255.toByte()),
            headphones = false,
        )
        assertEquals(-1, left)
        assertEquals(60, right)
        assertEquals(-1, case)
    }

    @Test
    fun `tws merged single-value firmware duplicates to both ears`() {
        val (left, right, case) = BatteryPayload.parseMate(byteArrayOf(90), headphones = false)
        assertEquals(90, left)
        assertEquals(90, right)
        assertEquals(-1, case)
    }

    @Test
    fun `empty payload parses to unknown everywhere`() {
        assertEquals(Triple(-1, -1, -1), BatteryPayload.parseMate(ByteArray(0), headphones = true))
        assertEquals(Triple(-1, -1, -1), BatteryPayload.parseMate(ByteArray(0), headphones = false))
    }

    // ---- 2. FiilStatus 聚合 ----

    @Test
    fun `headphone status reports overall battery`() {
        val s = FiilStatus(
            connected = true,
            earType = 46,
            batteryLeft = 85,
            batteryRight = -1,
            batteryCase = -1,
        )
        assertTrue(s.headphones)
        assertEquals(85, s.displayBattery)
    }

    @Test
    fun `tws status still takes the lower ear`() {
        val s = FiilStatus(
            connected = true,
            earType = 39,
            batteryLeft = 80,
            batteryRight = 60,
            batteryCase = 40,
        )
        assertFalse(s.headphones)
        assertEquals(60, s.displayBattery)
    }

    @Test
    fun `tws single ear falls back to the connected side`() {
        val s = FiilStatus(
            connected = true,
            earType = 39,
            batteryLeft = 80,
            batteryRight = -1,
            batteryCase = 40,
        )
        assertEquals(80, s.displayBattery)
    }

    // ---- 3. BatterySnapshot 置空 ----

    @Test
    fun `headphone snapshot drops right and case`() {
        val snapshot = BatterySnapshot.fromStatus(
            FiilStatus(
                connected = true,
                earType = 46,
                batteryLeft = 85,
                batteryRight = -1,
                batteryCase = -1,
            )
        )
        assertTrue(snapshot.headphones)
        assertEquals(85, snapshot.left?.battery)
        assertNull("头戴没有右耳", snapshot.right)
        assertNull("头戴没有盒", snapshot.case)
        // 系统设置页仍按三路编码读：未连接就是 255。
        assertEquals(listOf(85, 255, 255), snapshot.values())
    }

    @Test
    fun `tws snapshot keeps three channels`() {
        val snapshot = BatterySnapshot.fromStatus(
            FiilStatus(
                connected = true,
                earType = 39,
                batteryLeft = 80,
                batteryRight = 60,
                batteryCase = 40,
            )
        )
        assertFalse(snapshot.headphones)
        assertEquals(80, snapshot.left?.battery)
        assertEquals(60, snapshot.right?.battery)
        assertEquals(40, snapshot.case?.battery)
    }
}
