package moe.chenxy.fiilpods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.pods.NoiseControlMode
import moe.chenxy.fiilpods.pods.PodImages
import moe.chenxy.fiilpods.pods.WearStatus
import moe.chenxy.fiilpods.ui.components.AncSwitch
import moe.chenxy.fiilpods.ui.components.PodStatus
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
    /** LDAC 开关状态：-1=未知/不支持（不渲染），0=关，1=开。 */
    ldacState: Int = -1,
    onLdacChange: (Boolean) -> Unit = {},
    onToneVolumeChange: (Int) -> Unit = {},
    onLowLatencyModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean = false,
    spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    equalizerVisible: Boolean = false,
    dualDeviceSupported: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
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
                    onLdacChange = onLdacChange,
                    onToneVolumeChange = onToneVolumeChange,
                    onLowLatencyModeChange = onLowLatencyModeChange,
                    spatialAudioSupported = spatialAudioSupported,
                    spatialSoundSupported = spatialSoundSupported,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    equalizerVisible = equalizerVisible,
                    dualDeviceSupported = dualDeviceSupported,
                    onOpenEqualizer = onOpenEqualizer,
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
            onLdacChange = onLdacChange,
            onToneVolumeChange = onToneVolumeChange,
            onLowLatencyModeChange = onLowLatencyModeChange,
            spatialAudioSupported = spatialAudioSupported,
            spatialSoundSupported = spatialSoundSupported,
            adaptiveModeEnabled = adaptiveModeEnabled,
            equalizerVisible = equalizerVisible,
            dualDeviceSupported = dualDeviceSupported,
            onOpenEqualizer = onOpenEqualizer,
            bottomContentPadding = bottomContentPadding
        )
    }
}

@Composable
private fun rememberPodImagePainter(path: String?, earType: Int) = remember(path, earType) {
    // 优先级：用户自定义的产品图（path 指向 PodImagePrefs 存的文件）> 型号产品图
    path?.let {
        runCatching { BitmapFactory.decodeFile(it) }
            .getOrNull()
            ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
} ?: painterResource(
    // earType 未知（-1）时 PodImages 会落到 fiil_unknow；
    // 但这里更希望保持旧的万能盒子图，因为 fiil_unknow 是「官方也不认识这型号」的兜底，
    // 用在本模块里会让人误以为识别失败。所以只在确实识别出型号时才换图。
    if (earType > 0) PodImages.drawableOf(earType) else R.drawable.img_box,
)

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
    /** LDAC 开关状态：-1=未知/不支持（不渲染），0=关，1=开。 */
    ldacState: Int = -1,
    onLdacChange: (Boolean) -> Unit = {},
    onToneVolumeChange: (Int) -> Unit = {},
    onLowLatencyModeChange: (Int) -> Unit = {},
    spatialAudioSupported: Boolean,
    spatialSoundSupported: Boolean,
    adaptiveModeEnabled: Boolean,
    equalizerVisible: Boolean,
    dualDeviceSupported: Boolean,
    onOpenEqualizer: () -> Unit,
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
            if (ldacState >= 0) {
                // LDAC 是真开关：底层走 A2dpService.setCodecConfigPreference(cid 不在耳机侧，
                // 而是系统蓝牙协议栈)，状态由 CODEC_CONFIG_CHANGED 回读。
                // ldacState<0 表示本 ROM 无法判定，直接不渲染 —— 宁可没有，也不给假开关。
                //
                // ⚠️ 标题/摘要用「字面量拼接」而不是 stringResource(R.string.xxx)：
                // R8 + shrinkResources 会把只被这一处引用的资源当成无用资源删掉（实测
                // release APK 里 ldac_codec / ldac_summary / ldac_unsupported 三个字符串
                // 连同资源 ID 一起消失，而 dex 里字段引用还在，运行时就是 Resources$NotFound），
                // 所以这里改成从源码常量取文本，彻底摆脱对资源 ID 的依赖。
                SwitchPreference(
                    title = LDAC_TITLE,
                    summary = if (ldacState > 0) LDAC_ENABLED else LDAC_SUMMARY,
                    checked = ldacState > 0,
                    onCheckedChange = onLdacChange
                )
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
