package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.config.ConfigManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计缺陷的协议级回归：不依赖 Android/蓝牙进程。 */
class ProtocolAuditRepairTest {
    @Test
    fun `GATT routes Gaia frames instead of Mate only`() {
        val bytes = FiilGaia.buildFrame(FiilCommands.GET_BATTERY_LEVEL, false, byteArrayOf(0, 1, 2))
        val parser = GattFrameParser(FrameDialect.GAIA)
        val result = parser.accept(bytes)
        assertEquals(1, result.gaia.size)
        assertEquals(FiilCommands.GET_BATTERY_LEVEL, result.gaia.single().command)
        assertEquals(0, result.remaining)
    }

    @Test
    fun `GATT keeps split Mate frame until complete`() {
        val bytes = FiilMate.buildFrame(FiilMate.Cid.QUERY, FiilMate.Query.BATTERY, byteArrayOf(80))
        val parser = GattFrameParser(FrameDialect.MATE)
        assertTrue(parser.accept(bytes.copyOf(3)).mate.isEmpty())
        val result = parser.accept(bytes.copyOfRange(3, bytes.size))
        assertEquals(80, result.mate.single().byteAt(0))
        assertEquals(0, result.remaining)
    }

    @Test
    fun `unknown GATT parser preserves fragmented Gaia header until dialect is known`() {
        val bytes = FiilGaia.buildFrame(FiilCommands.GET_EARTYPE, false, byteArrayOf(0, 46))
        val parser = GattFrameParser(null)

        // 5-byte 前缀还缺 vendor/cmd，绝不能交给 Mate drain 消掉 0xFF SOF。
        val first = parser.accept(bytes.copyOf(5))
        assertTrue(first.mate.isEmpty())
        assertTrue(first.gaia.isEmpty())
        assertEquals(5, first.remaining)
        assertNull(parser.detectedDialect)

        val result = parser.accept(bytes.copyOfRange(5, bytes.size))
        assertEquals(FrameDialect.GAIA, parser.detectedDialect)
        assertEquals(FiilCommands.GET_EARTYPE, result.gaia.single().command)
        assertEquals(0, result.remaining)
    }

    @Test
    fun `EQ write preserves reserved byte read from device`() {
        val bands = ByteArray(10) { 128.toByte() }
        val actual = EqWritePayload.build(bands, FiilModels.MATE_EQ_CUSTOM, 10, 10)
        assertEquals(10, actual[0].toInt())
        assertEquals(15, actual[1].toInt())
        assertArrayEquals(bands, actual.copyOfRange(2, actual.size))
    }

    @Test
    fun `productId identifies capabilities when device name is renamed`() {
        val caps = detectDeviceCapabilities(null, "用户改过的名字", "46")
        assertTrue(caps.spatialAudioSupported)
        assertTrue(caps.customEqSupported)
    }

    @Test
    fun `authoritative productId wins over a conflicting model-like display name`() {
        // 名称可能来自缓存、别名或用户改名；协议 GET_EARTYPE=46 才是连接设备真值。
        // CC Pro2 的名字会反查成 39（GAIA），而 46 是 Key Max（Mate + 影院/EQ）。
        val caps = detectDeviceCapabilities(null, "FIIL CC Pro2", "46")
        assertTrue(caps.spatialAudioSupported)
        assertTrue(caps.customEqSupported)
    }

    @Test
    fun `default island timing only shows on connection`() {
        val policy = IslandTimingPolicy(emptySet())
        assertTrue(policy.allows(IslandTrigger.CONNECTED))
        assertFalse(policy.allows(IslandTrigger.WEARING))
        assertFalse(policy.allows(IslandTrigger.REMOVED))
    }

    @Test
    fun `wearing transition needs a known previous state`() {
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_WEARING))
        val current = FiilStatus(wearLeft = true, wearRight = true)

        assertNull(policy.triggerForWear(null, null, current))
        assertEquals(
            IslandTrigger.WEARING,
            policy.triggerForWear(false, false, current),
        )
    }

    @Test
    fun `removing one ear triggers only after known wearing baseline`() {
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_REMOVED))
        val current = FiilStatus(wearLeft = false, wearRight = true)

        assertEquals(
            IslandTrigger.REMOVED,
            policy.triggerForWear(true, true, current),
        )
        // 两边同时摘下仍是一次真实的「摘下」边沿，不能被 all-false 短路吞掉。
        assertEquals(
            IslandTrigger.REMOVED,
            policy.triggerForWear(true, true, FiilStatus(wearLeft = false, wearRight = false)),
        )
        assertNull(policy.triggerForWear(null, true, current))
    }

    @Test
    fun `all disconnected battery snapshot does not infer in case`() {
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_IN_CASE))
        val snapshot = BatterySnapshot(
            left = PodBattery(battery = 80, connected = false),
            right = PodBattery(battery = 80, connected = false),
            case = PodBattery(battery = 60, connected = false),
        )

        assertNull(policy.triggerForCase(snapshot))
    }

    @Test
    fun `case trigger remains unavailable without an explicit case transition`() {
        val policy = IslandTimingPolicy(setOf(ConfigManager.ISLAND_SHOW_TIMING_IN_CASE))
        val connected = BatterySnapshot(
            left = PodBattery(battery = 80, connected = true),
            right = PodBattery(battery = 80, connected = true),
        )
        val disconnected = connected.copy(
            left = PodBattery(battery = 80, connected = false),
            right = PodBattery(battery = 80, connected = false),
        )

        assertNull(policy.triggerForCase(connected))
        assertNull(policy.triggerForCase(disconnected))
    }
}
