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

/**
 * Resolve the protocol family from the current model. Unknown models retain the
 * previous Mate behavior; only a known non-Mate model selects GAIA.
 */
fun ancDialectForEarType(earType: Int): AncWireDialect =
    if (earType > 0 && !FiilModels.isMateDialect(earType)) AncWireDialect.GAIA else AncWireDialect.MATE

/** Encode a semantic selection for the connected headset's ANC wire protocol. */
fun NoiseControlMode.toWireMode(earType: Int): Int {
    val semantic = when (this) {
        NoiseControlMode.OFF -> AncSemantic.OFF
        NoiseControlMode.NOISE_CANCELLATION,
        NoiseControlMode.NOISE_CANCELLATION_SMART,
        NoiseControlMode.NOISE_CANCELLATION_LIGHT,
        NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
        NoiseControlMode.NOISE_CANCELLATION_DEEP,
        NoiseControlMode.ADAPTIVE -> AncSemantic.NC
        NoiseControlMode.TRANSPARENCY -> AncSemantic.TRANSPARENCY
        NoiseControlMode.LISTEN -> AncSemantic.LISTEN
        NoiseControlMode.WIND -> AncSemantic.WIND
    }
    return AncWireCodec.wireFromSemantic(ancDialectForEarType(earType), semantic)
}

/** Decode the raw ANC value carried by [FiilStatus]. Null means unknown, not OFF. */
fun ancModeFromWire(isMate: Boolean, value: Int): NoiseControlMode? =
    when (AncWireCodec.semanticFromWire(if (isMate) AncWireDialect.MATE else AncWireDialect.GAIA, value)) {
        AncSemantic.OFF -> NoiseControlMode.OFF
        AncSemantic.NC -> NoiseControlMode.NOISE_CANCELLATION
        AncSemantic.TRANSPARENCY -> NoiseControlMode.TRANSPARENCY
        AncSemantic.LISTEN -> NoiseControlMode.LISTEN
        AncSemantic.WIND -> NoiseControlMode.WIND
        null -> null
    }

/** Map a raw state for the Oppo-compatible UI without treating invalid input as OFF. */
fun ancPublishStatus(raw: Int, isMate: Boolean): Int? =
    AncWireCodec.oppoStatusFromWire(if (isMate) AncWireDialect.MATE else AncWireDialect.GAIA, raw)

/**
 * 协议线值 → MIUI 系统侧三态（0 关 / 1 降噪 / 2 通透）。
 *
 * 宿主（设置页耳机详情、系统耳机服务）只认这三档，FIIL 的「监听 / 风中」在系统语义上
 * 都属于「让外界声音进来」，归到 2。未知线值返回 null —— 调用方必须自己决定怎么显示，
 * 不能把它当成「关」。
 *
 * 存在的理由：`2` 这个线值在 Mate 上是「开放（通透）」、在 GAIA 上却是「监听」，
 * 直接用 `when (raw)` 写死会在 GAIA 机型上把「监听」显示成「通透」。
 */
fun ancMiuiStateFromWire(dialect: AncWireDialect, raw: Int): Int? =
    when (AncWireCodec.semanticFromWire(dialect, raw)) {
        AncSemantic.OFF -> 0
        AncSemantic.NC -> 1
        AncSemantic.TRANSPARENCY, AncSemantic.LISTEN, AncSemantic.WIND -> 2
        null -> null
    }

/** [ancMiuiStateFromWire] 的 earType 重载：已知型号查表，未知型号保守按 Mate。 */
fun ancMiuiStateFromWire(earType: Int, raw: Int): Int? =
    ancMiuiStateFromWire(ancDialectForEarType(earType), raw)

/**
 * 「循环切换降噪模式」按钮的下一档线值（关 → 降噪 → 通透 → 关）。
 *
 * 必须按**语义**推进，不能拿线值 `+1`：GAIA 的 raw 2 是「监听」、raw 3 才是「开启」，
 * 线值自增会从「监听」直接跳到「关闭」，用户永远切不到「通透」。
 * 未知线值（含 `-1` / 越界）从「降噪」重新开始。
 */
fun ancCycleWire(dialect: AncWireDialect, raw: Int): Int {
    val next = when (AncWireCodec.semanticFromWire(dialect, raw)) {
        AncSemantic.NC -> AncSemantic.TRANSPARENCY
        AncSemantic.TRANSPARENCY, AncSemantic.LISTEN, AncSemantic.WIND -> AncSemantic.OFF
        else -> AncSemantic.NC
    }
    return AncWireCodec.wireFromSemantic(dialect, next)
}
