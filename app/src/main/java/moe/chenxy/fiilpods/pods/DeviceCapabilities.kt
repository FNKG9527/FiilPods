package moe.chenxy.fiilpods.pods

/**
 * 设备能力集 —— 数据结构与 OppoPods 的 `pods/DeviceCapabilities.kt` **完全一致**，
 * 这样从 OppoPods 移植过来的 UI 层（首页卡片、耳机详情页、设置页）不用改一行。
 *
 * 唯一的区别在 [detectDeviceCapabilities]：OppoPods 按 productId / 型号注册表判断，
 * 我们按 FIIL+ 逆向出来的 `earType` 机型表（[FiilModels]）判断。
 */
data class DeviceCapabilities(
    val adaptiveSupported: Boolean,
    val spatialAudioSupported: Boolean,
    val spatialSoundSwitchSupported: Boolean,
    val ancImplementation: AncImplementation,
    val dualDeviceSupported: Boolean = false,
    val customEqSupported: Boolean = false,
    val eqPresets: List<EqPresetInfo> = emptyList(),
    val customEqFrequencies: List<Int> = emptyList(),
    val customEqMaxPresets: Int = 0,
    val gameModeFeatureId: Int = GameModeFeature.LOW_LATENCY,
    val gameModeSupported: Boolean = false,
)

/** EQ 预设项。id 是 FIIL 的预设索引，name 是显示名。 */
@kotlinx.serialization.Serializable
data class EqPresetInfo(
    val id: Int,
    val name: String,
    val modeType: Int = -1,
)

/** 可编辑的自定义 EQ（十段增益）。 */
@kotlinx.serialization.Serializable
data class EqDevicePreset(
    val id: Int,
    val name: String,
    val selected: Boolean = false,
    val minValue: Int = -6,
    val maxValue: Int = 6,
    val frequencies: List<Int> = emptyList(),
    val gains: List<Int> = emptyList(),
)

/** FIIL 十段 EQ 的频点（Hz），与 APP 的 `customize_eq` 数组一致。 */
object EqDefaults {
    val FREQUENCIES: List<Int> = listOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
}

/** ANC 实现风格（OppoPods UI 用它决定开关语义，FIIL 一律 STANDARD）。 */
enum class AncImplementation {
    STANDARD,
    COMPATIBLE,
}

/** 游戏模式对应的特性 ID。 */
object GameModeFeature {
    const val LOW_LATENCY = 0x06
    const val DUAL_DEVICE_CONNECTION = 0x11
    const val FREE4_SPATIAL_SOUND = 0x1B
    const val MAIN = 0x28
}

/**
 * FIIL 机型能力判定。
 *
 * 数据来源是 FIIL+ 3.5.2 逆向结论（见 `docs/PROTOCOL_FIIL.md` §5 与 [FiilModels]）：
 *  - `MATE_EAR_TYPES`（45/46/47/48/49/51/53）走 Mate 帧，五态 MAF、影院、十段 EQ、自适应开关齐全；
 *  - `EXTENDED_ANC` 里的 GAIA 机型有五态 ANC（关闭/降噪/监听/开放/风中）；
 *  - `BLE_ONLY`（39/40/42）不走 SPP，本模块不接管。
 *
 * @param earType 耳机型号编号；-1 表示还没识别出来
 */
fun detectDeviceCapabilities(earType: Int): DeviceCapabilities {
    val known = earType > 0
    val mate = known && FiilModels.isMateDialect(earType)
    return DeviceCapabilities(
        adaptiveSupported = known && FiilModels.supportsAnyAdaptive(earType),
        spatialAudioSupported = known && FiilModels.supportsCinema(earType),
        spatialSoundSwitchSupported = known && FiilModels.supportsCinema(earType),
        ancImplementation = AncImplementation.STANDARD,
        dualDeviceSupported = known && FiilModels.supportsMultipoint(earType),
        customEqSupported = mate,
        eqPresets = if (mate) {
            FiilModels.MATE_EQ_PRESETS.mapIndexed { index, name -> EqPresetInfo(index, name) }
        } else {
            FiilModels.EQ_PRESETS.mapIndexed { index, name -> EqPresetInfo(index, name) }
        },
        customEqFrequencies = if (mate) EqDefaults.FREQUENCIES else emptyList(),
        customEqMaxPresets = if (mate) 5 else 0,
        gameModeFeatureId = GameModeFeature.LOW_LATENCY,
        gameModeSupported = known,
    )
}

/**
 * 兼容重载：OppoPods 的 UI 传的是 (context, deviceName, productId)。
 * FIIL 侧没有 productId，直接用型号名反查 earType。
 */
@Suppress("UNUSED_PARAMETER")
fun detectDeviceCapabilities(
    context: android.content.Context? = null,
    deviceName: String,
    productId: String? = null,
): DeviceCapabilities = detectDeviceCapabilities(FiilModels.earTypeOfName(deviceName))
