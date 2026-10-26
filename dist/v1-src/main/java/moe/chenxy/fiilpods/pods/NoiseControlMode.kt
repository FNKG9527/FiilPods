package moe.chenxy.fiilpods.pods

/**
 * 噪声控制模式（UI 语义层）。
 *
 * 前 8 个取值与 OppoPods 的 `NoiseControlMode` **完全一致**，因此从 OppoPods 移植过来的
 * UI 代码不需要改动；末尾追加 FIIL 独有的两个档位（[LISTEN] 兼听、[WIND] 风中），
 * 它们只出现在 FIIL 的 Mate 五态机型上。
 */
enum class NoiseControlMode {
    OFF,
    NOISE_CANCELLATION,
    NOISE_CANCELLATION_SMART,
    NOISE_CANCELLATION_LIGHT,
    NOISE_CANCELLATION_MEDIUM,
    NOISE_CANCELLATION_DEEP,
    ADAPTIVE,
    TRANSPARENCY,

    /** FIIL 兼听（MAF 3）。仅 Mate 五态机型。 */
    LISTEN,

    /** FIIL 风中（MAF 4）。仅 Mate 五态机型。 */
    WIND,
}

/** 是否属于降噪这一类（降噪档位在 UI 上有额外样式）。 */
fun NoiseControlMode.isNoiseCancellation(): Boolean {
    return when (this) {
        NoiseControlMode.NOISE_CANCELLATION,
        NoiseControlMode.NOISE_CANCELLATION_SMART,
        NoiseControlMode.NOISE_CANCELLATION_LIGHT,
        NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
        NoiseControlMode.NOISE_CANCELLATION_DEEP -> true
        else -> false
    }
}

/** 佩戴状态。FIIL 的线值由 FiilMate / FiilGaia 解析后填入。 */
enum class WearState(val value: Int) {
    DISCONNECTED(0x00),
    IN_CASE(0x04),
    REMOVED(0x05),
    WEARING(0x07);

    companion object {
        fun fromValue(value: Int): WearState? = entries.firstOrNull { it.value == value }
    }
}

/** 三路佩戴状态。 */
data class WearStatus(
    val left: WearState? = null,
    val right: WearState? = null,
    val case: WearState? = null,
)

/** FIIL MAF 线值（0~4）↔ UI 语义枚举。 */
fun NoiseControlMode.toMafMode(): Int = when (this) {
    NoiseControlMode.OFF -> 0
    NoiseControlMode.NOISE_CANCELLATION,
    NoiseControlMode.NOISE_CANCELLATION_SMART,
    NoiseControlMode.NOISE_CANCELLATION_LIGHT,
    NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
    NoiseControlMode.NOISE_CANCELLATION_DEEP -> 1
    NoiseControlMode.TRANSPARENCY -> 2
    NoiseControlMode.LISTEN -> 3
    NoiseControlMode.WIND -> 4
    NoiseControlMode.ADAPTIVE -> 1
}

/** FIIL MAF 线值（0~4）→ UI 语义枚举。 */
fun mafModeToNoiseControl(value: Int): NoiseControlMode = when (value) {
    1 -> NoiseControlMode.NOISE_CANCELLATION
    2 -> NoiseControlMode.TRANSPARENCY
    3 -> NoiseControlMode.LISTEN
    4 -> NoiseControlMode.WIND
    else -> NoiseControlMode.OFF
}
