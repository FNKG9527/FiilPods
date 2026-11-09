package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.config.ConfigManager

enum class IslandTrigger(val configKey: Int?) {
    CONNECTED(ConfigManager.ISLAND_SHOW_TIMING_CONNECTED),
    WEARING(ConfigManager.ISLAND_SHOW_TIMING_WEARING),
    REMOVED(ConfigManager.ISLAND_SHOW_TIMING_REMOVED),
    IN_CASE(ConfigManager.ISLAND_SHOW_TIMING_IN_CASE),
}

/** 空集是原有默认配置，保持“连接时显示”，而不是误当“全部显示”。 */
class IslandTimingPolicy(private val timings: Set<Int>) {
    fun allows(trigger: IslandTrigger): Boolean =
        timings.contains(trigger.configKey) || timings.isEmpty() && trigger == IslandTrigger.CONNECTED

    /**
     * 当前协议没有可靠的「入盒」位。
     *
     * BatterySnapshot.fromExtras() 只要任一 connected extra 存在就会构造三侧对象，
     * 而断开/静默探测 teardown 也会形成全 disconnected 快照。因此不能把这类快照
     * 猜成入盒，宁可暂不弹岛，等待耳机协议提供明确的 case transition。
     */
    @Suppress("UNUSED_PARAMETER")
    fun triggerForCase(snapshot: BatterySnapshot): IslandTrigger? = null

    /** 根据「已知的上次佩戴状态 → 本次状态」判断真实佩戴事件，心跳不重弹。 */
    fun triggerForWear(previousWearLeft: Boolean?, previousWearRight: Boolean?, status: FiilStatus): IslandTrigger? {
        // FiilStatus 的 Boolean 默认 false，没有“本侧已上报”标记；首帧或半帧不能当事件。
        if (previousWearLeft == null || previousWearRight == null) return null

        // 必须先判断 true → false。两侧同时摘下同样是一次真实的 REMOVED，
        // 不能被当前全 false 的状态短路。
        if ((previousWearLeft && !status.wearLeft) || (previousWearRight && !status.wearRight)) {
            return IslandTrigger.REMOVED.takeIf { allows(it) }
        }

        // 只有已知两侧此前均未佩戴、现在至少一侧佩戴，才是 WEARING。
        if (!previousWearLeft && !previousWearRight && (status.wearLeft || status.wearRight)) {
            return IslandTrigger.WEARING.takeIf { allows(it) }
        }
        return null
    }
}
