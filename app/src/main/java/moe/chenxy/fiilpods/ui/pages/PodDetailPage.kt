package moe.chenxy.fiilpods.ui.pages

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.pods.NoiseControlMode
import moe.chenxy.fiilpods.pods.WearStatus
import moe.chenxy.fiilpods.ui.components.AncSwitch
import moe.chenxy.fiilpods.ui.components.PodStatus
import moe.chenxy.fiilpods.ui.components.rememberPodImagePainter
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false,
    gameModeSupported: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    dualDeviceConnection: Boolean = false,
    onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    /** 语音提示音量线值 0=高 1=中 2=低 3=静音，-1 表示还没读到。 */
    toneVolume: Int = -1,
    lowLatencyMode: Int = -1,
    /** LDAC 开关状态：-1=未知（开关仍显示，初值当关），0=关，1=开。 */
    ldacState: Int = -1,
    /** 该机型是否支持耳机侧 LDAC 开关（[moe.chenxy.fiilpods.pods.FiilModels.supportsLdac]）。 */
    ldacSupported: Boolean = false,
    /** 是否收到过耳机侧 LDAC 状态回包；没收到过时摘要显示「--」而不是「关」。 */
    ldacKnown: Boolean = false,
    /** 耳机侧 LDAC 是否已解锁/授权；未授权时摘要提示可直接开启（实测无需授权）。 */
    ldacUnlocked: Boolean = false,
    onLdacChange: (Boolean) -> Unit = {},
    /**
     * 用户点了「写入授权码」。非 null 时说明该机型协议栈里有 `setActivationLdac`。
     *
     * 只有 F046 / F048 两族为真（[moe.chenxy.fiilpods.pods.FiilModels.supportsLdacLicense]）。
     */
    onWriteLicense: (() -> Unit)? = null,
    onToneVolumeChange: (Int) -> Unit = {},
    onLowLatencyModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean = false,
    spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    equalizerVisible: Boolean = false,
    dualDeviceSupported: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    onOpenTouchKeys: () -> Unit = {},
    boxImagePath: String? = null,
    /**
     * 当前耳机的 earType（`-1` = 未知）。
     *
     * 用户没自定义过产品图时（[boxImagePath] 为空），详情页按这个值
     * 回落到 FIIL+ 逆向出来的对应型号产品图，而不是一刀切显示通用的 `img_box`。
     */
    earType: Int = -1,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath, earType),
                    contentDescription = "Earphones",
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    batteryParams = batteryParams,
                    wearStatus = wearStatus,
                    ancMode = ancMode,
                    onAncModeChange = onAncModeChange,
                    smartAncLevel = smartAncLevel,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
                    gameMode = gameMode,
                    gameModeSupported = gameModeSupported,
                    onGameModeChange = onGameModeChange,
                    spatialAudioMode = spatialAudioMode,
                    onSpatialAudioModeChange = onSpatialAudioModeChange,
                    dualDeviceConnection = dualDeviceConnection,
                    onDualDeviceConnectionChange = onDualDeviceConnectionChange,
                    toneVolume = toneVolume,
                    lowLatencyMode = lowLatencyMode,
                    ldacState = ldacState,
                    ldacSupported = ldacSupported,
                    ldacKnown = ldacKnown,
                    ldacUnlocked = ldacUnlocked,
                    onLdacChange = onLdacChange,
                    onWriteLicense = onWriteLicense,
                    onToneVolumeChange = onToneVolumeChange,
                    onLowLatencyModeChange = onLowLatencyModeChange,
                    spatialAudioSupported = spatialAudioSupported,
                    spatialSoundSupported = spatialSoundSupported,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    equalizerVisible = equalizerVisible,
                    dualDeviceSupported = dualDeviceSupported,
                    onOpenEqualizer = onOpenEqualizer,
                    onOpenTouchKeys = onOpenTouchKeys,
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Image(
                painter = rememberPodImagePainter(boxImagePath, earType),
                contentDescription = "Earphones",
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        podControlItems(
            batteryParams = batteryParams,
            wearStatus = wearStatus,
            ancMode = ancMode,
            onAncModeChange = onAncModeChange,
            smartAncLevel = smartAncLevel,
            transparencyVocalEnhancement = transparencyVocalEnhancement,
            onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange,
            gameMode = gameMode,
            gameModeSupported = gameModeSupported,
            onGameModeChange = onGameModeChange,
            spatialAudioMode = spatialAudioMode,
            onSpatialAudioModeChange = onSpatialAudioModeChange,
            dualDeviceConnection = dualDeviceConnection,
            onDualDeviceConnectionChange = onDualDeviceConnectionChange,
            toneVolume = toneVolume,
            lowLatencyMode = lowLatencyMode,
            ldacState = ldacState,
            ldacSupported = ldacSupported,
            ldacKnown = ldacKnown,
            ldacUnlocked = ldacUnlocked,
            onLdacChange = onLdacChange,
            onWriteLicense = onWriteLicense,
            onToneVolumeChange = onToneVolumeChange,
            onLowLatencyModeChange = onLowLatencyModeChange,
            spatialAudioSupported = spatialAudioSupported,
            spatialSoundSupported = spatialSoundSupported,
            adaptiveModeEnabled = adaptiveModeEnabled,
            equalizerVisible = equalizerVisible,
            dualDeviceSupported = dualDeviceSupported,
            onOpenEqualizer = onOpenEqualizer,
            onOpenTouchKeys = onOpenTouchKeys,
            bottomContentPadding = bottomContentPadding
        )
    }
}

private fun LazyListScope.podControlItems(
    batteryParams: BatteryParams,
    wearStatus: WearStatus,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode?,
    transparencyVocalEnhancement: Boolean,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    gameMode: Boolean,
    gameModeSupported: Boolean,
    onGameModeChange: (Boolean) -> Unit,
    spatialAudioMode: Int,
    onSpatialAudioModeChange: (Int) -> Unit,
    dualDeviceConnection: Boolean,
    onDualDeviceConnectionChange: (Boolean) -> Unit,
    toneVolume: Int = -1,
    lowLatencyMode: Int = -1,
    /** LDAC 开关状态：-1=未知（开关仍显示，初值当关），0=关，1=开。 */
    ldacState: Int = -1,
    /** 该机型是否支持耳机侧 LDAC 开关（[moe.chenxy.fiilpods.pods.FiilModels.supportsLdac]）。 */
    ldacSupported: Boolean = false,
    /** 是否收到过耳机侧 LDAC 状态回包；没收到过时摘要显示「--」而不是「关」。 */
    ldacKnown: Boolean = false,
    /** 耳机侧 LDAC 是否已解锁/授权；未授权时摘要提示可直接开启（实测无需授权）。 */
    ldacUnlocked: Boolean = false,
    onLdacChange: (Boolean) -> Unit = {},
    /**
     * 用户点了「写入授权码」。非 null 时说明该机型协议栈里有 `setActivationLdac`。
     *
     * 只有 F046 / F048 两族为真（[moe.chenxy.fiilpods.pods.FiilModels.supportsLdacLicense]）。
     */
    onWriteLicense: (() -> Unit)? = null,
    onToneVolumeChange: (Int) -> Unit = {},
    onLowLatencyModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    equalizerVisible: Boolean,
    dualDeviceSupported: Boolean,
    onOpenEqualizer: () -> Unit,
    onOpenTouchKeys: () -> Unit,
    bottomContentPadding: Dp
) {
    val spatialAudioValues = listOf(
        ConfigManager.SPATIAL_AUDIO_OFF,
        ConfigManager.SPATIAL_AUDIO_FIXED,
        ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING,
    )

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = batteryParams,
                wearStatus = wearStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            AncSwitch(
                ancStatus = ancMode,
                onAncModeChange = onAncModeChange,
                smartAncLevel = smartAncLevel,
                adaptiveModeEnabled = adaptiveModeEnabled,
                transparencyVocalEnhancement = transparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            if (gameModeSupported) {
                SwitchPreference(
                    title = stringResource(R.string.game_mode),
                    summary = stringResource(R.string.game_mode_summary),
                    checked = gameMode,
                    onCheckedChange = onGameModeChange
                )
            }
            if (spatialAudioSupported) {
                val spatialAudioOptions = listOf(
                    stringResource(R.string.off),
                    stringResource(R.string.spatial_audio_fixed),
                    stringResource(R.string.spatial_audio_head_tracking),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.spatial_audio),
                    summary = stringResource(R.string.spatial_audio_summary),
                    items = spatialAudioOptions,
                    selectedIndex = spatialAudioValues.indexOf(spatialAudioMode).coerceAtLeast(0),
                    onSelectedIndexChange = { onSpatialAudioModeChange(spatialAudioValues[it]) }
                )
            }
            if (spatialSoundSupported) {
                SwitchPreference(
                    title = stringResource(R.string.spatial_sound),
                    summary = stringResource(if (spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF) R.string.enabled else R.string.off),
                    checked = spatialAudioMode != ConfigManager.SPATIAL_AUDIO_OFF,
                    onCheckedChange = {
                        onSpatialAudioModeChange(if (it) ConfigManager.SPATIAL_AUDIO_FIXED else ConfigManager.SPATIAL_AUDIO_OFF)
                    }
                )
            }
            if (equalizerVisible) {
                BasicComponent(
                    title = stringResource(R.string.eq_preset_title),
                    summary = stringResource(R.string.eq_preset_summary),
                    onClick = onOpenEqualizer,
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }
            BasicComponent(
                title = stringResource(R.string.touch_keys_title),
                summary = stringResource(R.string.touch_keys_summary),
                onClick = onOpenTouchKeys,
                endActions = {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                    )
                },
            )
            if (toneVolume >= 0) {
                // 线值 0=高 1=中 2=低 3=静音（与直觉相反，0 最响）。
                val toneOptions = listOf(
                    stringResource(R.string.tone_volume_high),
                    stringResource(R.string.tone_volume_mid),
                    stringResource(R.string.tone_volume_low),
                    stringResource(R.string.tone_volume_mute),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.tone_volume),
                    summary = stringResource(R.string.tone_volume_summary),
                    items = toneOptions,
                    selectedIndex = toneVolume.coerceIn(0, 3),
                    onSelectedIndexChange = { onToneVolumeChange(it) }
                )
            }
            if (lowLatencyMode >= 0) {
                // FIIL 固件是真正的三档（0=音乐 1=游戏 2=视频），
                // 不像 OppoPods 只有「游戏模式」开/关，所以单独给一个档位选择器。
                val latencyOptions = listOf(
                    stringResource(R.string.low_latency_music),
                    stringResource(R.string.low_latency_game),
                    stringResource(R.string.low_latency_video),
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.low_latency_mode),
                    summary = stringResource(R.string.low_latency_summary),
                    items = latencyOptions,
                    selectedIndex = lowLatencyMode.coerceIn(0, 2),
                    onSelectedIndexChange = { onLowLatencyModeChange(it) }
                )
            }
            if (ldacSupported) {
                // LDAC 是**耳机侧开关**（cid=1 cmdId=13 写 / cid=39 cmdId=143 读）。
                //
                // ⚠ 显示条件从「ldacState >= 0」改成「支持该机型」（v2.2.2 起）：
                // 旧条件要求「已经从系统 A2DP 读到 LDAC 能力」，可耳机不开 LDAC 就
                // 不会在 A2DP 握手时声明 support，于是永远读不到 → 开关永远不显示
                // → 用户永远没有入口打开它。这是个自锁，必须在**能力层**判断。
                //
                // 状态语义：
                //  · ldacKnown=false（还没收到 143 回包）→ 摘要显示「读取中」，开关可点；
                //  · ldacUnlocked=false（耳机未授权）→ **仍然可点**，只是提示授权缺失；
                //  · 否则按 ldacState 显示开/关。
                //
                // ⚠ v3.4.3：旧摘要是「需先在 FIIL+ 官方 App 里解锁高清音频」，但那个
                // 官方 App 的激活服务器（newservice.fiil.com）早已 NXDOMAIN，等于把用户
                // 指向一条死路。真机实测（Key Max / F046）证明 142（授权）与 143（开关）
                // 是两条独立路径：142=00 时开 LDAC 依然生效（143 翻 1、144=02）。
                // 所以这里改成「授权缺失但可直接开」的表述，不再阻止操作。
                //
                // ⚠️ 标题/摘要用「字面量常量」而不是 stringResource(R.string.xxx)：
                // R8 + shrinkResources 会把只被这一处引用的资源当成无用资源删掉（实测
                // release APK 里 ldac_codec / ldac_summary / ldac_unsupported 三个字符串
                // 连同资源 ID 一起消失，而 dex 里字段引用还在，运行时就是 Resources$NotFound），
                // 所以这里从源码常量取文本，彻底摆脱对资源 ID 的依赖。
                val ldacSummary = when {
                    !ldacUnlocked -> LDAC_UNLICENSED
                    !ldacKnown -> "正在读取耳机状态…"
                    ldacState > 0 -> LDAC_ENABLED
                    else -> LDAC_SUMMARY
                }
                SwitchPreference(
                    title = LDAC_TITLE,
                    summary = ldacSummary,
                    checked = ldacState > 0,
                    onCheckedChange = onLdacChange
                )
                // 「写入授权码」只在协议栈真有 setActivationLdac 的机型上出现
                // （F046 / F048，见 FiilModels.supportsLdacLicense）。
                //
                // 为什么还要这个入口：官方激活服务器 newservice.fiil.com 已 NXDOMAIN，
                // 用户手里若还留着自己耳机的授权码（以前从官方 App 抄下来的），
                // 这是唯一还能把它写回去的地方。
                if (onWriteLicense != null) {
                    BasicComponent(
                        title = LDAC_LICENSE_TITLE,
                        summary = if (ldacUnlocked) LDAC_LICENSE_SUMMARY_UNLOCKED
                        else LDAC_LICENSE_SUMMARY_LOCKED,
                        onClick = onWriteLicense,
                        endActions = {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowRight,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
            if (dualDeviceSupported) {
                SwitchPreference(
                    title = stringResource(R.string.dual_device_connection),
                    summary = stringResource(if (dualDeviceConnection) R.string.enabled else R.string.off),
                    checked = dualDeviceConnection,
                    onCheckedChange = onDualDeviceConnectionChange
                )
            }
        }
    }
    item {
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}


/**
 * LDAC 开关的界面文案（字面量，不走 `R.string`）。
 *
 * 原因见 `podControlItems` 里那段注释：release 构建开启了 `isShrinkResources = true`，
 * 只被单个位置引用的字符串资源会被当作无用资源剥掉，而反射／间接引用仍会去查资源 ID，
 * 结果就是 `Resources$NotFoundException`。文案量很小（4 条），直接内联最稳。
 */
internal const val LDAC_TITLE: String = "LDAC 编解码器"
internal const val LDAC_SUMMARY: String = "启用高解析蓝牙音频（需耳机与手机同时支持）"
internal const val LDAC_UNSUPPORTED: String = "当前耳机不支持 LDAC"
internal const val LDAC_ENABLED: String = "已启用"

/**
 * 耳机侧 `142`（LDAC 授权）为 `00` 时的摘要。
 *
 * 不再写「需先在 FIIL+ 官方 App 里解锁」——官方激活服务器 `newservice.fiil.com`
 * 已经 NXDOMAIN，那句话等于把用户指向死路。真机实测（FIIL Key Max / F046 / 固件 3.0.1.1）
 * 表明授权与开关是两条独立路径，未授权时开关照样生效。
 */
internal const val LDAC_UNLICENSED: String = "耳机未记录授权信息，但可直接开启（实测无需授权）"

/**
 * 「写入 LDAC 授权码」入口的文案。
 *
 * 官方激活服务器 `newservice.fiil.com` 已 NXDOMAIN，这是把手里的旧授权码
 * 写回耳机的唯一入口。只有 F046 / F048 两族会显示（协议栈里有 `setActivationLdac`）。
 *
 * ⚠ 摘要里必须写清「写错会清掉已有授权」——这条命令的副作用不可逆，
 * 真机实测过（写 `00+UUID` 把已解锁的耳机写成了 142=00，且无法恢复）。
 */
internal const val LDAC_LICENSE_TITLE: String = "写入 LDAC 授权码"
internal const val LDAC_LICENSE_SUMMARY_UNLOCKED: String =
    "耳机已记录授权信息；仍可手动覆盖（写错会清掉授权，慎用）"
internal const val LDAC_LICENSE_SUMMARY_LOCKED: String =
    "官方激活服务器已下线；若你留有本机授权码，可在此写入"
