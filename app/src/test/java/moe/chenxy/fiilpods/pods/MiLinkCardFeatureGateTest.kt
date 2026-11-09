package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.config.ConfigManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions for the existing MiLink card-selection setting. */
class MiLinkCardFeatureGateTest {
    private val both = setOf(
        ConfigManager.MILINK_CARD_GAME_MODE,
        ConfigManager.MILINK_CARD_SPATIAL_AUDIO,
    )

    @Test
    fun `selected features remain visible when the model supports them`() {
        assertTrue(MiLinkCardFeatureGate.gameModePanelEnabled(46, both))
        assertTrue(MiLinkCardFeatureGate.spatialPanelEnabled(46, both))
    }

    @Test
    fun `deselecting one feature hides only that MiLink panel`() {
        val gameOnly = setOf(ConfigManager.MILINK_CARD_GAME_MODE)
        val spatialOnly = setOf(ConfigManager.MILINK_CARD_SPATIAL_AUDIO)

        assertTrue(MiLinkCardFeatureGate.gameModePanelEnabled(46, gameOnly))
        assertFalse(MiLinkCardFeatureGate.spatialPanelEnabled(46, gameOnly))
        assertFalse(MiLinkCardFeatureGate.gameModePanelEnabled(46, spatialOnly))
        assertTrue(MiLinkCardFeatureGate.spatialPanelEnabled(46, spatialOnly))
    }

    @Test
    fun `feature selection never bypasses model capability`() {
        assertTrue(MiLinkCardFeatureGate.gameModePanelEnabled(49, both))
        assertFalse(MiLinkCardFeatureGate.spatialPanelEnabled(49, both))
        assertFalse(MiLinkCardFeatureGate.gameModePanelEnabled(-1, both))
    }
}
