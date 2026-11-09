package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression coverage for the Android-facing auto-game session adapter. */
class AutoGameModeRuntimeTest {
    @Test
    fun `game foreground remains inert until a confirmed music baseline arrives`() {
        val runtime = AutoGameModeRuntime()
        runtime.setEnabled(true)

        assertNull(runtime.onForegroundClassification(AutoGameModePolicy.Classification.GAME))
        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.MUSIC),
        )
    }

    @Test
    fun `manual low latency action owns the session before any automation write`() {
        val runtime = AutoGameModeRuntime()
        runtime.setEnabled(true)
        runtime.markManualAction()

        assertNull(runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.MUSIC))
        assertNull(runtime.onForegroundClassification(AutoGameModePolicy.Classification.GAME))
    }

    @Test
    fun `non game restores music only after the auto game command is confirmed`() {
        val runtime = AutoGameModeRuntime()
        runtime.setEnabled(true)
        runtime.onForegroundClassification(AutoGameModePolicy.Classification.GAME)

        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.MUSIC),
        )
        assertNull(runtime.onForegroundClassification(AutoGameModePolicy.Classification.NON_GAME))
        assertEquals(
            AutoGameModePolicy.LowLatency.MUSIC,
            runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.GAME),
        )
    }

    @Test
    fun `disabling resets the baseline and requires a fresh confirmation after re-enable`() {
        val runtime = AutoGameModeRuntime()
        runtime.setEnabled(true)
        runtime.onForegroundClassification(AutoGameModePolicy.Classification.GAME)
        runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.MUSIC)

        runtime.setEnabled(false)
        runtime.setEnabled(true)

        assertNull(runtime.onForegroundClassification(AutoGameModePolicy.Classification.GAME))
        assertEquals(
            AutoGameModePolicy.LowLatency.GAME,
            runtime.onConfirmedLowLatency(AutoGameModePolicy.LowLatency.MUSIC),
        )
    }
}
