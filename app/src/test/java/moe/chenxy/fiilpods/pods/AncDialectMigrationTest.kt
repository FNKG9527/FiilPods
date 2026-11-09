package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AncDialectMigrationTest {
    @Test
    fun `sender resolves known GAIA without treating unknown ear types as GAIA`() {
        assertEquals(AncWireDialect.GAIA, ancDialectForEarType(39))
        assertEquals(AncWireDialect.MATE, ancDialectForEarType(46))
        assertEquals(AncWireDialect.MATE, ancDialectForEarType(-1))
        assertEquals(AncWireDialect.MATE, ancDialectForEarType(0))
    }

    @Test
    fun `semantic sender encodes transparency and listen for the connected dialect`() {
        assertEquals(3, NoiseControlMode.TRANSPARENCY.toWireMode(39))
        assertEquals(2, NoiseControlMode.LISTEN.toWireMode(39))
        assertEquals(2, NoiseControlMode.TRANSPARENCY.toWireMode(46))
        assertEquals(3, NoiseControlMode.LISTEN.toWireMode(46))

        // Preserve the established conservative behavior until earType is known.
        assertEquals(2, NoiseControlMode.TRANSPARENCY.toWireMode(-1))
        assertEquals(3, NoiseControlMode.LISTEN.toWireMode(-1))
    }

    @Test
    fun `inbound raw ANC uses the actual JSON dialect`() {
        assertEquals(NoiseControlMode.LISTEN, ancModeFromWire(isMate = false, value = 2))
        assertEquals(NoiseControlMode.TRANSPARENCY, ancModeFromWire(isMate = false, value = 3))
        assertEquals(NoiseControlMode.TRANSPARENCY, ancModeFromWire(isMate = true, value = 2))
        assertEquals(NoiseControlMode.LISTEN, ancModeFromWire(isMate = true, value = 3))
    }

    @Test
    fun `Oppo publisher cache includes the dialect and never publishes unknown state`() {
        assertEquals(9, ancPublishStatus(raw = 2, isMate = false))
        assertEquals(3, ancPublishStatus(raw = 3, isMate = false))
        assertNull(ancPublishStatus(raw = -1, isMate = false))
        assertNull(ancPublishStatus(raw = 5, isMate = false))
    }

    @Test
    fun `unknown raw state stays unknown instead of turning ANC off`() {
        listOf(false, true).forEach { isMate ->
            assertNull(ancModeFromWire(isMate, -1))
            assertNull(ancModeFromWire(isMate, 5))
        }
    }

    @Test
    fun `canonical semantic values retain the existing MAF representation`() {
        assertEquals(1, NoiseControlMode.NOISE_CANCELLATION.toMafMode())
        assertEquals(2, NoiseControlMode.TRANSPARENCY.toMafMode())
        assertEquals(3, NoiseControlMode.LISTEN.toMafMode())
        assertEquals(4, NoiseControlMode.WIND.toMafMode())
        assertEquals(NoiseControlMode.TRANSPARENCY, mafModeToNoiseControl(2))
        assertEquals(NoiseControlMode.LISTEN, mafModeToNoiseControl(3))
    }

    @Test
    fun `MIUI three state mapping follows the dialect instead of raw ordering`() {
        // Mate: 1=NC 2=透明 3=兼听 4=风中；GAIA: 1=NC 2=监听 3=透明。
        assertEquals(1, ancMiuiStateFromWire(AncWireDialect.MATE, 1))
        assertEquals(2, ancMiuiStateFromWire(AncWireDialect.MATE, 2))
        assertEquals(2, ancMiuiStateFromWire(AncWireDialect.MATE, 3))
        assertEquals(2, ancMiuiStateFromWire(AncWireDialect.MATE, 4))
        assertEquals(0, ancMiuiStateFromWire(AncWireDialect.MATE, 0))

        assertEquals(0, ancMiuiStateFromWire(AncWireDialect.GAIA, 0))
        assertEquals(1, ancMiuiStateFromWire(AncWireDialect.GAIA, 1))
        assertEquals(2, ancMiuiStateFromWire(AncWireDialect.GAIA, 2))
        // GAIA 的 3 是「透明」，在 MIUI 三态里同样只能是 2；关键是不能退化成 0/关。
        assertEquals(2, ancMiuiStateFromWire(AncWireDialect.GAIA, 3))
    }

    @Test
    fun `MIUI three state mapping never turns an unknown value into off`() {
        listOf(AncWireDialect.MATE, AncWireDialect.GAIA).forEach { dialect ->
            assertNull(ancMiuiStateFromWire(dialect, -1))
            assertNull(ancMiuiStateFromWire(dialect, 5))
            assertNull(ancMiuiStateFromWire(dialect, 0x7f))
        }
    }

    @Test
    fun `MIUI three state mapping picks the dialect from the ear type`() {
        // 46 是 Mate 方言，39 是 GAIA 方言；线值 2 在两边的 MIUI 语义里都是「通透/监听」→ 2。
        assertEquals(1, ancMiuiStateFromWire(46, 1))
        assertEquals(1, ancMiuiStateFromWire(39, 1))
        // 未知道型号保守按 Mate 处理，与 ancDialectForEarType 的约定一致。
        assertEquals(2, ancMiuiStateFromWire(-1, 2))
        assertEquals(2, ancMiuiStateFromWire(0, 2))
    }

    @Test
    fun `cycle button walks OFF to NC to transparency per dialect`() {
        // Mate：0(关) → 1(降噪) → 2(透明) → 0
        assertEquals(1, ancCycleWire(AncWireDialect.MATE, 0))
        assertEquals(2, ancCycleWire(AncWireDialect.MATE, 1))
        assertEquals(0, ancCycleWire(AncWireDialect.MATE, 2))
        // Mate 的「兼听 / 风中」也回到关，不能因为线值更大就跳出三态。
        assertEquals(0, ancCycleWire(AncWireDialect.MATE, 3))
        assertEquals(0, ancCycleWire(AncWireDialect.MATE, 4))

        // GAIA：0 → 1 → 3(透明) → 0；raw 2 是「监听」，下一档必须是关。
        assertEquals(1, ancCycleWire(AncWireDialect.GAIA, 0))
        assertEquals(3, ancCycleWire(AncWireDialect.GAIA, 1))
        assertEquals(0, ancCycleWire(AncWireDialect.GAIA, 3))
        assertEquals(0, ancCycleWire(AncWireDialect.GAIA, 2))
    }

    @Test
    fun `cycle button restarts at NC when the current state is unknown`() {
        listOf(AncWireDialect.MATE, AncWireDialect.GAIA).forEach { dialect ->
            assertEquals(1, ancCycleWire(dialect, -1))
            assertEquals(1, ancCycleWire(dialect, 9))
        }
    }
}
