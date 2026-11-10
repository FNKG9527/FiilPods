package moe.chenxy.fiilpods.pods

import android.content.Intent

/** 单路电量（左耳 / 右耳 / 充电盒）。 */
data class PodBattery(
    val battery: Int = 0,
    val charging: Boolean = false,
    val connected: Boolean = false,
)

/** 三路电量快照。头戴式只有 [left] 这一路整体电量。 */
data class BatterySnapshot(
    val left: PodBattery? = null,
    val right: PodBattery? = null,
    val case: PodBattery? = null,
    /** 头戴式：UI 只显示一路「整体电量」，不显示左右耳/盒。 */
    val headphones: Boolean = false,
) {
    fun hasAny(): Boolean =
        left?.connected == true || right?.connected == true || case?.connected == true

    /** 系统设置页约定的三路编码：未连接 = 255，充电中 = 值 | 128。 */
    fun values(): List<Int> = listOf(encode(left), encode(right), encode(case))

    fun csv(): String = values().joinToString(",")

    companion object {
        fun encode(p: PodBattery?): Int {
            if (p?.connected != true) return 255
            val v = p.battery.coerceIn(0, 100)
            return if (p.charging) v or 128 else v
        }

        fun fromExtras(intent: Intent): BatterySnapshot? {
            if (!intent.hasExtra(FiilAction.EXTRA_LEFT_CONNECTED) &&
                !intent.hasExtra(FiilAction.EXTRA_RIGHT_CONNECTED) &&
                !intent.hasExtra(FiilAction.EXTRA_CASE_CONNECTED)
            ) {
                return null
            }
            val headphones = FiilModels.isHeadphones(
                intent.getIntExtra(FiilAction.EXTRA_EAR_TYPE, -1)
            )
            return BatterySnapshot(
                left = PodBattery(
                    intent.getIntExtra(FiilAction.EXTRA_LEFT_BATTERY, 0),
                    intent.getBooleanExtra(FiilAction.EXTRA_LEFT_CHARGING, false),
                    intent.getBooleanExtra(FiilAction.EXTRA_LEFT_CONNECTED, false),
                ),
                // 头戴没有右耳/盒：留 null，别让下游把「0%」当成真实读数画出来。
                right = if (headphones) null else PodBattery(
                    intent.getIntExtra(FiilAction.EXTRA_RIGHT_BATTERY, 0),
                    intent.getBooleanExtra(FiilAction.EXTRA_RIGHT_CHARGING, false),
                    intent.getBooleanExtra(FiilAction.EXTRA_RIGHT_CONNECTED, false),
                ),
                case = if (headphones) null else PodBattery(
                    intent.getIntExtra(FiilAction.EXTRA_CASE_BATTERY, 0),
                    intent.getBooleanExtra(FiilAction.EXTRA_CASE_CHARGING, false),
                    intent.getBooleanExtra(FiilAction.EXTRA_CASE_CONNECTED, false),
                ),
                headphones = headphones,
            )
        }

        fun fromStatus(status: FiilStatus): BatterySnapshot = BatterySnapshot(
            left = PodBattery(status.batteryLeft.coerceAtLeast(0), false, status.batteryLeft >= 0),
            right = if (status.headphones) null
            else PodBattery(status.batteryRight.coerceAtLeast(0), false, status.batteryRight >= 0),
            case = if (status.headphones) null
            else PodBattery(status.batteryCase.coerceAtLeast(0), false, status.batteryCase >= 0),
            headphones = status.headphones,
        )
    }
}
