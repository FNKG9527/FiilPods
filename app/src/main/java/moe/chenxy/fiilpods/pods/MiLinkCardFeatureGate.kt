package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.config.ConfigManager

/** Pure capability and user-selection gate for MiLink control panels. */
object MiLinkCardFeatureGate {
    fun gameModePanelEnabled(earType: Int, features: Set<Int>): Boolean =
        ConfigManager.MILINK_CARD_GAME_MODE in features &&
            earType > 0 && FiilModels.supportsMateMaf(earType)

    fun spatialPanelEnabled(earType: Int, features: Set<Int>): Boolean =
        ConfigManager.MILINK_CARD_SPATIAL_AUDIO in features &&
            earType > 0 && FiilModels.supportsCinema(earType)
}
