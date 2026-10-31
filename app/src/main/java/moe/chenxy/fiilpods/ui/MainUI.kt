package moe.chenxy.fiilpods.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavKey
import moe.chenxy.fiilpods.FiilPodsApp
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.config.PodImagePrefs
import moe.chenxy.fiilpods.config.PodImageResource
import moe.chenxy.fiilpods.pods.NoiseControlMode
import moe.chenxy.fiilpods.pods.FiilCodec
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.OppoCompatBridge
import moe.chenxy.fiilpods.pods.EqDevicePreset
import moe.chenxy.fiilpods.pods.WearState
import moe.chenxy.fiilpods.pods.WearStatus
import moe.chenxy.fiilpods.pods.detectDeviceCapabilities
import moe.chenxy.fiilpods.ui.pages.AboutPage
import moe.chenxy.fiilpods.ui.pages.EqualizerPage
import moe.chenxy.fiilpods.ui.pages.RfcommDebugPage
import moe.chenxy.fiilpods.ui.pages.ThemeSettingsPage
import moe.chenxy.fiilpods.utils.RootManager
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Main : Screen
    @Serializable
    data object About : Screen
    @Serializable
    data object Theme : Screen
    @Serializable
    data object Equalizer : Screen
    @Serializable
    data object RfcommDebug : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    backStack: NavBackStack,
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val wearStatus = remember { mutableStateOf(WearStatus()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    /** Smart-mode current auto-applied NC level (LIGHT/MEDIUM/DEEP), or null. */
    val smartAncLevel = remember { mutableStateOf<NoiseControlMode?>(null) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val dualDeviceConnection = remember { mutableStateOf(false) }
    /** 语音提示音量（FIIL 专有，cid=1 cmd=3，线值 0=高 1=中 2=低 3=静音，-1=未知）。 */
    val toneVolume = remember { mutableStateOf(-1) }
    /** 低延迟档位（FIIL 专有，cid=37 cmd=0，线值 0=音乐 1=游戏 2=视频，-1=未知）。 */
    val lowLatencyMode = remember { mutableStateOf(-1) }
    /**
     * LDAC 开关三态：`-1` 未知/本 ROM 反射不到 A2dpService（不渲染开关）、`0` 支持但未启用、`1` 已启用。
     *
     * 用 Int 而不是 Boolean 的原因：蓝牙进程里的 [moe.chenxy.fiilpods.pods.FiilCodec] 要反射
     * `A2dpService.getCodecStatus(BluetoothDevice)` 才能知道耳机支不支持 LDAC；某些 ROM /
     * 还没连上耳机时会返回 null。这种情况下必须能把开关整个藏掉，否则就是一个「点了不起作用」的假开关。
     */
    val ldacState = remember { mutableStateOf(-1) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var hasAutoOpenedConnectedDevice by remember { mutableStateOf(false) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(FiilPodsApp.xposedService) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    var connectedDeviceAddress by remember { mutableStateOf("") }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
    var hookConnectionState by remember { mutableStateOf("disconnected") }
    var pendingOpenEarphonesAfterPickerLoaded by remember { mutableStateOf(false) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    val backgroundColor = appBackground()
    val overlayBottomBar = floatingBottomBar.value || blurBottomBar.value
    val pageBottomContentPadding = if (overlayBottomBar) 104.dp else 28.dp
    val backdrop = if (blurBottomBar.value) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }

    // Auto game mode preference (persisted)
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val appConfig = remember { ConfigManager.refreshFromPrefs(prefs) }
    val autoGameMode = remember { mutableStateOf(appConfig.autoGameMode) }
    val milinkCardFeatures = remember { mutableStateOf(appConfig.milinkCardFeatures) }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.islandMode) }
    val focusNotification = remember { mutableStateOf(appConfig.focusNotification) }
    val islandShowTimings = remember { mutableStateOf(appConfig.islandShowTimings) }
    val spatialAudioMode = remember { mutableStateOf(prefs.getInt("spatial_audio_mode", ConfigManager.SPATIAL_AUDIO_OFF)) }
    val eqPreset = remember { mutableStateOf(-1) }
    val eqDevicePresets = remember { mutableStateOf<List<EqDevicePreset>>(emptyList()) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.load(prefs)) }
    val productId = remember { mutableStateOf<String?>(null) }
    /**
     * 当前连接耳机的 earType（-1 = 未连接 / 未知）。
     *
     * 与 [productId] 同源：蓝牙进程把 `FiilStatus.earType` 塞进 `product_id` extra。
     * 这里单独留着 Int 版本，是因为主页要拿它查
     * [moe.chenxy.fiilpods.pods.PodImages] 显示对应型号的产品图；
     * 每次都从 String 里 `toIntOrNull()` 既啰嗦，又容易在某条分支上漏同步。
     */
    val connectedEarType = remember { mutableIntStateOf(-1) }

    val canShowDetailPage = hookConnected.value
    val showEarphoneDetail = canShowDetailPage && !showDevicePicker
    val displayBattery = batteryParams.value
    val displayWearStatus = wearStatus.value
    val displayAnc = ancMode.value
    val displayGameMode = gameMode.value
    val displayTransparencyVocalEnhancement = transparencyVocalEnhancement.value
    val displayDualDeviceConnection = dualDeviceConnection.value
    val displayTitle = mainTitle.value.takeIf { it.isNotBlank() && hookConnected.value } ?: mainTitle.value
    val displayCapabilities = detectDeviceCapabilities(
        context = context,
        deviceName = displayTitle,
        productId = productId.value,
    )

    LaunchedEffect(displayTitle, displayCapabilities) {
        Log.i(
            "OppoPods",
            "capability check: deviceName='$displayTitle', adaptive=${displayCapabilities.adaptiveSupported}, spatial=${displayCapabilities.spatialAudioSupported}, spatialSoundSwitch=${displayCapabilities.spatialSoundSwitchSupported}"
        )
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    LaunchedEffect(canShowDetailPage) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (canShowDetailPage) MainTab.Earphones else MainTab.Module
            hasAppliedDefaultTab = true
        }
    }

    LaunchedEffect(hookConnectionState) {
        if (hookConnectionState == "error") {
            connectingDeviceAddress = null
            pendingOpenEarphonesAfterPickerLoaded = false
            showConnectErrorDialog = true
            showDevicePicker = true
        }
    }

    LaunchedEffect(pendingOpenEarphonesAfterPickerLoaded, connectingDeviceAddress, hookConnected.value) {
        if (pendingOpenEarphonesAfterPickerLoaded && connectingDeviceAddress == null && hookConnected.value) {
            withFrameNanos { }
            pendingOpenEarphonesAfterPickerLoaded = false
            showDevicePicker = false
        }
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                // 兜底：任何一条广播分支里抛的异常都不能冒到主线程。
                //
                // 2026-10-26 真实崩溃：`BadParcelableException: ClassNotFoundException
                // when unmarshalling: zd` 从 onReceive 里抛出，被 AMS 包装成
                // RuntimeException → UI 进程被直接杀掉（用户看到的就是「点开闪退」）。
                // 即便业务代码已经改成不读 Parcelable，也必须在接收器最外层再兜一层，
                // 因为 extras 的解包是惰性的 —— 其它分支将来若读任何 Parcelable 都可能复发。
                runCatching {
                when (p1?.action) {
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            5 -> NoiseControlMode.NOISE_CANCELLATION_SMART
                            6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
                            7 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
                            8 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
                            // FIIL 专有档位：9=兼听、10=风中。
                            // 不能复用 4/8 —— 那两个是 OPPO 的 ADAPTIVE / DEEP，
                            // 复用会让「兼听」高亮成「自适应」（见 OppoCompatBridge 注释）。
                            9 -> NoiseControlMode.LISTEN
                            10 -> NoiseControlMode.WIND
                            else -> NoiseControlMode.OFF
                        }
                    }

                    OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED -> {
                        val ord = p1.getIntExtra("ordinal", -1)
                        smartAncLevel.value = NoiseControlMode.entries.getOrNull(ord)
                    }

                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        // 绝不读 Parcelable —— 只用纯 int 通道。
                        //
                        // 原因（2026-10-26 真实崩溃）：
                        //   java.lang.RuntimeException: Error receiving broadcast ...
                        //   Caused by: android.os.BadParcelableException:
                        //       ClassNotFoundException when unmarshalling: zd
                        //   at MainUI.kt:261  p1.getParcelableExtra("status", ...)
                        //
                        // 蓝牙进程里注入的是 release 代码（R8 混淆），putExtra 写入的类名是
                        // 混淆后的短名；UI 进程是另一个构建，ClassLoader 里根本没有那个名字，
                        // 反序列化必然抛异常。而这个异常发生在 BroadcastReceiver.onReceive，
                        // 会被 AMS 包装成 RuntimeException 直接杀掉 UI 进程 —— 也就是「点开就闪退」。
                        //
                        // 关键点：`runCatching` 也救不了 —— 异常在 Bundle 取值的 lazy unwrap
                        // 里抛出，某些 ROM 上还会被 AMS 在外层重新抛出。唯一可靠的做法是
                        // **压根不碰 Parcelable**：OppoCompatBridge 已在同一条广播里带了
                        // 三个纯 int（EXTRA_BATTERY_LEFT/RIGHT/CASE），用它重建即可。
                        rebuildBatteryFromInts(p1)?.let { batteryParams.value = it }
                    }

                    OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED -> {
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        wearStatus.value = WearStatus(
                            left = wearStateFromExtra(p1.getIntExtra("left_wear_status", -1)),
                            right = wearStateFromExtra(p1.getIntExtra("right_wear_status", -1)),
                            case = wearStateFromExtra(p1.getIntExtra("case_wear_status", -1))
                        )
                    }

                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        transparencyVocalEnhancement.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        spatialAudioMode.value = p1.getIntExtra("mode", ConfigManager.SPATIAL_AUDIO_OFF)
                    }

                    OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED -> {
                        eqPreset.value = p1.getIntExtra("preset", -1)
                        eqDevicePresets.value = runCatching {
                            Json.decodeFromString(
                                ListSerializer(EqDevicePreset.serializer()),
                                p1.getStringExtra(OppoPodsAction.EXTRA_EQ_ENTRIES_JSON).orEmpty(),
                            )
                        }.getOrDefault(emptyList())
                    }

                    OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED -> {
                        dualDeviceConnection.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_TONE_VOLUME_CHANGED -> {
                        toneVolume.value = p1.getIntExtra("level", -1)
                    }

                    OppoPodsAction.ACTION_PODS_LOW_LATENCY_MODE_CHANGED -> {
                        lowLatencyMode.value = p1.getIntExtra(
                            OppoPodsAction.EXTRA_LOW_LATENCY_MODE, -1,
                        )
                    }

                    OppoPodsAction.ACTION_PODS_LDAC_CHANGED -> {
                        // 三态折叠：只要「状态未知」或「不支持」，一律落成 -1，让详情页把开关整个藏掉。
                        //
                        // 历史 Bug（用户报「控制页找不到 LDAC 开关」）：publishState 只在编码器
                        // 状态**发生变化**时才有回调，用户第一次进详情页时什么都没变，于是 UI
                        // 永远收不到这条广播、ldacState 一直停在 -1、if (ldacState >= 0) 不成立。
                        // 修法是让 UI_INIT / REFRESH_STATUS 也主动发一次（见 BluetoothProcessHook）。
                        val known = p1.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_KNOWN, false)
                        val supported = p1.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_SUPPORTED, false)
                        val enabled = p1.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_ENABLED, false)
                        ldacState.value = if (!known || !supported) -1 else if (enabled) 1 else 0
                    }

                    OppoPodsAction.ACTION_OPEN_CODEC_SETTINGS -> {
                        // 蓝牙进程（后台）没法拉起 Activity，跳页请求转到这里执行。
                        // 模块 App 此刻在前台，不受后台启动限制，才能真正把用户送到
                        // 系统「蓝牙音频编解码器」页。
                        runCatching { FiilCodec.openCodecSettings(context) }
                            .onFailure { Log.w("FiilPods-MainUI", "open codec settings failed", it) }
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        val shouldOpenEarphones = connectingDeviceAddress != null || !hasAutoOpenedConnectedDevice
                        connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                        productId.value = p1.getStringExtra("product_id") ?: productId.value
                        // 主页的型号图靠这个值。蓝牙进程在 publishConnection 里把
                        // `FiilStatus.earType` 塞成 product_id，这里随手解析成 Int 存下。
                        // 解析失败（比如老版本蓝牙进程没塞这个 extra）就回退用设备名推断，
                        // 再不行落 -1，主页那张「当前耳机」卡片直接不渲染。
                        connectedEarType.intValue = p1.getStringExtra("product_id")
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: FiilModels.earTypeOfName(deviceName)
                        connectingDeviceAddress = null
                        mainTitle.value = deviceName ?: ""
                        earphonePrefs.value = PodImagePrefs.upsertConnected(
                            prefs = prefs,
                            service = xposedService,
                            address = connectedDeviceAddress,
                            name = deviceName.orEmpty(),
                        )
                        hookConnected.value = true
                        hookConnectionState = "connected"
                        if (shouldOpenEarphones) {
                            selectedTab = MainTab.Earphones
                            hasAppliedDefaultTab = true
                            hasAutoOpenedConnectedDevice = true
                            showDevicePicker = false
                            pendingOpenEarphonesAfterPickerLoaded = false
                        }
                        Log.i("OppoPods", "pod connected via hook: $deviceName")
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                        hookConnectionState = p1.getStringExtra("state") ?: hookConnectionState
                        if (hookConnectionState == "disconnected") {
                            connectedDeviceAddress = ""
                            productId.value = null
                            connectedEarType.intValue = -1
                            mainTitle.value = ""
                            hookConnected.value = false
                            hasAutoOpenedConnectedDevice = false
                        } else if (hookConnected.value) {
                            connectedDeviceAddress = p1.getStringExtra("address") ?: connectedDeviceAddress
                            p1.getStringExtra("device_name")?.let {
                                mainTitle.value = it
                                earphonePrefs.value = PodImagePrefs.upsertConnected(prefs, xposedService, connectedDeviceAddress, it)
                            }
                        }
                    }

                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        connectedDeviceAddress = ""
                        productId.value = null
                        connectedEarType.intValue = -1
                        hookConnectionState = "disconnected"
                        hookConnected.value = false
                        hasAutoOpenedConnectedDevice = false
                        // 断开后必须把 LDAC 状态打回「未知」。
                        // 否则开关会带着上一台耳机的状态留在界面上：用户点它时
                        // `setLdacEnabled()` 会先乐观更新成 1/0，再向蓝牙进程发
                        // `ACTION_LDAC_SET`；蓝牙进程此时 `connectedDevice()` 返回 null，
                        // 只能回一个 known=false —— 界面会闪一下再消失，观感是「点了没用」。
                        // 打回 -1 后 `PodDetailPage` 的 `if (ldacState >= 0)` 直接不渲染这一项，
                        // 断开态下不再出现一个必然无效的开关。
                        ldacState.value = -1
                    }

                    OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE -> {
                        lastBluetoothServiceAliveMs = SystemClock.elapsedRealtime()
                        bluetoothServiceResponsive = true
                    }

                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        bluetoothState = readBluetoothState(context)
                    }
                }
                }.onFailure {
                    android.util.Log.w("FiilPods", "battery/state broadcast failed", it)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = { service ->
            xposedService = service
        }
        FiilPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_TONE_VOLUME_CHANGED)
            // 缺这一行时，低延迟广播虽然发得出去但 UI 收不到，
            // lowLatencyMode 永远停在 -1，详情页的「低延迟模式」选择器整块不渲染。
            addAction(OppoPodsAction.ACTION_PODS_LOW_LATENCY_MODE_CHANGED)
            // 同理：LDAC 的编解码器状态由蓝牙进程反射 A2dpService 后回推，
            // 不注册这个 action，ldacState 永远 -1，详情页的 LDAC 开关整块不渲染。
            addAction(OppoPodsAction.ACTION_PODS_LDAC_CHANGED)
            // 蓝牙进程（后台）不能拉起 Activity，LDAC 反射失败时它发这条广播，
            // 由本进程（前台）代为打开系统编解码器页。
            addAction(OppoPodsAction.ACTION_OPEN_CODEC_SETTINGS)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_PODS_UI_INIT)

        onDispose {
            sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_PODS_UI_CLOSED)
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            FiilPodsApp.removeServiceListener(serviceListener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_PODS_UI_INIT)
            sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_REFRESH_STATUS)
            delay(30_000L)
        }
    }

    LaunchedEffect(selectedTab, hookConnected.value) {
        sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_PODS_UI_INIT)
        if (selectedTab == MainTab.Module || hookConnected.value) {
            sendBluetoothModuleBroadcast(context, OppoPodsAction.ACTION_REFRESH_STATUS)
        }
    }

    LaunchedEffect(lastBluetoothServiceAliveMs) {
        while (true) {
            bluetoothServiceResponsive = lastBluetoothServiceAliveMs > 0L &&
                    SystemClock.elapsedRealtime() - lastBluetoothServiceAliveMs <= 75_000L
            delay(5_000L)
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        ancMode.value = mode
        // FIIL 用 MAF 线值 0~4（关闭/降噪/开放/兼听/风中），不是 OPPO 的 1~8 状态码。
        val status = when (mode) {
            NoiseControlMode.OFF -> 0
            NoiseControlMode.NOISE_CANCELLATION -> 1
            NoiseControlMode.TRANSPARENCY -> 2
            NoiseControlMode.LISTEN -> 3
            NoiseControlMode.WIND -> 4
            NoiseControlMode.ADAPTIVE,
            NoiseControlMode.NOISE_CANCELLATION_SMART,
            NoiseControlMode.NOISE_CANCELLATION_LIGHT,
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
            NoiseControlMode.NOISE_CANCELLATION_DEEP -> 1
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        gameMode.value = enabled
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        transparencyVocalEnhancement.value = enabled
        Intent(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun clearPodConnectionState() {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        connectedDeviceAddress = ""
        mainTitle.value = ""
        batteryParams.value = BatteryParams()
        wearStatus.value = WearStatus()
        ancMode.value = NoiseControlMode.OFF
        hookConnected.value = false
        hookConnectionState = "disconnected"
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        connectingDeviceAddress = device.address
        pendingOpenEarphonesAfterPickerLoaded = false
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
        hookConnectionState = "connecting"
        Intent(OppoPodsAction.ACTION_CONNECT_POD_REQUEST).apply {
            putExtra("device", device)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        spatialAudioMode.value = normalizedMode
        prefs.edit().putInt("spatial_audio_mode", normalizedMode).apply()
        Intent(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            this.putExtra("mode", normalizedMode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setEqPreset(preset: Int) {
        eqPreset.value = preset
        Intent(OppoPodsAction.ACTION_EQ_PRESET_SET).apply {
            this.putExtra("preset", preset)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int,
        maxValue: Int,
    ) {
        Intent(OppoPodsAction.ACTION_EQ_PRESET_SAVE).apply {
            putExtra("id", id)
            putExtra("name", name)
            putIntegerArrayListExtra("frequencies", ArrayList(frequencies))
            putIntegerArrayListExtra("gains", ArrayList(gains))
            putExtra("min_value", minValue)
            putExtra("max_value", maxValue)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun deleteEqPreset(entry: EqDevicePreset) {
        Intent(OppoPodsAction.ACTION_EQ_PRESET_DELETE).apply {
            putExtra(
                OppoPodsAction.EXTRA_EQ_ENTRIES_JSON,
                Json.encodeToString(ListSerializer(EqDevicePreset.serializer()), listOf(entry)),
            )
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setDualDeviceConnection(enabled: Boolean) {
        dualDeviceConnection.value = enabled
        Intent(OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setToneVolume(level: Int) {
        toneVolume.value = level
        Intent(OppoPodsAction.ACTION_TONE_VOLUME_SET).apply {
            this.putExtra("level", level)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    /** 低延迟三档：0=音乐 1=游戏 2=视频（cid=37, cmdId=0）。 */
    fun setLowLatencyMode(mode: Int) {
        lowLatencyMode.value = mode
        // 同步「游戏模式」开关：只有 GAME 档算开。
        gameMode.value = mode == 1
        Intent(OppoPodsAction.ACTION_LOW_LATENCY_MODE_SET).apply {
            putExtra(OppoPodsAction.EXTRA_LOW_LATENCY_MODE, mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    /**
     * LDAC 开关：**真开关**，不是「点了跳系统设置页」。
     *
     * 真正的切换动作发生在蓝牙进程 [moe.chenxy.fiilpods.hook.BluetoothProcessHook] 里：
     * 它收到 [OppoPodsAction.ACTION_LDAC_SET] 后反射 `A2dpService.setCodecConfigPreference(...)`
     * 把编解码器切成 LDAC，再用 `getCodecStatus(...)` 回读真实状态，最后发
     * [OppoPodsAction.ACTION_PODS_LDAC_CHANGED] 把三态回推给这里。
     *
     * 为什么必须发给 `com.android.bluetooth`：A2dpService 只存在于蓝牙进程，模块 App 自己
     * 那份 FiilCodec 反射不到（不同进程、不同 ClassLoader），发给自己是无效的。
     * 同时发给 `com.xiaomi.bluetooth` 是因为 HyperOS 上耳机卡片偶发由该进程代理刷新。
     */
    fun setLdacEnabled(enabled: Boolean) {
        // 乐观更新：开关立刻跟手，真值以回读广播为准（失败会被 publishState 改回去）。
        ldacState.value = if (enabled) 1 else 0
        for (pkg in arrayOf("com.android.bluetooth", "com.xiaomi.bluetooth")) {
            Intent(OppoPodsAction.ACTION_LDAC_SET).apply {
                putExtra(OppoPodsAction.EXTRA_LDAC_ENABLED, enabled)
                setPackage(pkg)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    fun onDeviceDisconnect(device: BluetoothDevice) {
        connectingDeviceAddress = null
        pendingOpenEarphonesAfterPickerLoaded = false
        if (device.address == connectedDeviceAddress) {
            hookConnected.value = false
            hookConnectionState = "disconnected"
            connectedDeviceAddress = ""
            mainTitle.value = ""
        }
        Intent(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST).apply {
            putExtra("device", device)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun onConnectedDeviceClick() {
        if (connectedDeviceAddress.isBlank() && mainTitle.value.isBlank()) return
        pendingOpenEarphonesAfterPickerLoaded = false
        hookConnected.value = true
        hookConnectionState = "connected"
        showDevicePicker = false
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        showDevicePicker = true
    }

    fun openBluetoothSettings() {
        val action = if (bluetoothState.enabled) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun openDevicePicker() {
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    @SuppressLint("MissingPermission")
    fun openSystemHeadsetSettings() {
        val address = connectedDeviceAddress
        if (address.isBlank()) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val device = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull()
        if (device == null) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", device)
            putExtra("bluetoothaddress", device.address)
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun refreshStatus() {
        if (hookConnected.value) {
            context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    fun savePodImages(
        address: String,
        name: String,
        images: Map<PodImageResource, Uri?>,
        clearedImages: Set<PodImageResource>,
    ) {
        earphonePrefs.value = PodImagePrefs.saveImages(context, prefs, xposedService, address, name, images, clearedImages)
    }

    fun savePodImageBytes(address: String, name: String, images: Map<PodImageResource, ByteArray>) {
        earphonePrefs.value = PodImagePrefs.saveImageBytes(context, prefs, xposedService, address, name, images)
    }

    fun restartScopes(packages: List<String>) {
        if (packages.isEmpty() || restartingScopes) return
        restartingScopes = true
        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                RootManager.restartPackages(packages)
            }
            restartingScopes = false
            showRestartScopeDialog = false
            if (success && "com.android.bluetooth" in packages) {
                clearPodConnectionState()
            }
            Toast.makeText(
                context,
                if (success) R.string.restart_scope_success else R.string.restart_scope_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val navContent: NavEntryBuilder.() -> Unit = {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                backdrop = backdrop,
                backgroundColor = backgroundColor,
                overlayBottomBar = overlayBottomBar,
                pageBottomContentPadding = pageBottomContentPadding,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothState.enabled,
                bondedDeviceCount = bluetoothState.bondedCount,
                onBluetoothStatusClick = { openBluetoothSettings() },
                onPairedBluetoothClick = { openDevicePicker() },
                showEarphoneDetail = showEarphoneDetail,
                mainTitle = mainTitle.value,
                displayTitle = displayTitle,
                connectedEarType = connectedEarType.intValue,
                connectedDeviceName = mainTitle.value,
                displayBattery = displayBattery,
                displayWearStatus = displayWearStatus,
                displayAnc = displayAnc,
                onAncModeChange = { setAncMode(it) },
                smartAncLevel = smartAncLevel.value,
                displayTransparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = { setTransparencyVocalEnhancement(it) },
                displayGameMode = displayGameMode,
                gameModeSupported = displayCapabilities.gameModeSupported,
                onGameModeChange = { setGameMode(it) },
                spatialAudioMode = spatialAudioMode.value,
                onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                equalizerVisible = displayCapabilities.eqPresets.isNotEmpty() ||
                    displayCapabilities.customEqSupported,
                dualDeviceSupported = displayCapabilities.dualDeviceSupported,
                onOpenEqualizer = { backStack.add(Screen.Equalizer) },
                displayDualDeviceConnection = displayDualDeviceConnection,
                onDualDeviceConnectionChange = { setDualDeviceConnection(it) },
                toneVolume = toneVolume.value,
                onToneVolumeChange = { setToneVolume(it) },
                lowLatencyMode = lowLatencyMode.value,
                onLowLatencyModeChange = { setLowLatencyMode(it) },
                ldacState = ldacState.value,
                onLdacChange = { setLdacEnabled(it) },
                spatialAudioSupported = displayCapabilities.spatialAudioSupported,
                spatialSoundSupported = displayCapabilities.spatialSoundSwitchSupported,
                adaptiveModeEnabled = displayCapabilities.adaptiveSupported,
                earphonePrefs = earphonePrefs.value,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectErrorDialog = showConnectErrorDialog,
                onDeviceSelected = { onDeviceSelected(it) },
                onConnectedDeviceClick = { onConnectedDeviceClick() },
                onDeviceDisconnect = { onDeviceDisconnect(it) },
                onDismissConnectError = { showConnectErrorDialog = false },
                desktopIconHidden = desktopIconHidden,
                onDesktopIconHiddenChange = {
                    desktopIconHidden.value = it
                    setLauncherIconHidden(context, it)
                },
                logLevel = logLevel,
                onLogLevelChange = {
                    logLevel.value = it
                    ConfigManager.updateLogLevel(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandMode = islandMode,
                onIslandModeChange = {
                    islandMode.value = it
                    ConfigManager.updateIslandMode(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                focusNotification = focusNotification,
                onFocusNotificationChange = {
                    focusNotification.value = it
                    ConfigManager.updateFocusNotification(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandShowTimings = islandShowTimings,
                onIslandShowTimingsChange = {
                    islandShowTimings.value = it
                    ConfigManager.updateIslandShowTimings(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                autoGameMode = autoGameMode,
                onAutoGameModeChange = {
                    autoGameMode.value = it
                    ConfigManager.updateAutoGameMode(prefs, xposedService, it)
                    Intent(OppoPodsAction.ACTION_AUTO_GAME_MODE_CHANGED).apply {
                        setPackage("com.android.bluetooth")
                        putExtra("enabled", it)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        context.sendBroadcast(this)
                    }
                },
                milinkCardFeatures = milinkCardFeatures,
                onMilinkCardFeaturesChange = {
                    milinkCardFeatures.value = it
                    ConfigManager.updateMilinkCardFeatures(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.milink.service")
                },
                notificationClickAction = notificationClickAction,
                onNotificationClickActionChange = {
                    notificationClickAction.value = it
                    ConfigManager.updateNotificationClickAction(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                moreClickAction = moreClickAction,
                onMoreClickActionChange = {
                    moreClickAction.value = it
                    ConfigManager.updateMoreClickAction(prefs, xposedService, it)
                },
                onOpenRfcommDebug = { backStack.add(Screen.RfcommDebug) },
                fakeDeviceId = fakeDeviceId,
                onFakeDeviceIdChange = {
                    fakeDeviceId.value = it
                    ConfigManager.updateFakeDeviceId(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.android.settings")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                onOpenTheme = { backStack.add(Screen.Theme) },
                onOpenAbout = { backStack.add(Screen.About) },
                showRestartScopeDialog = showRestartScopeDialog,
                restartingScopes = restartingScopes,
                onShowRestartScopeDialog = { showRestartScopeDialog = true },
                onDismissRestartScopeDialog = { showRestartScopeDialog = false },
                onRestartScopes = { restartScopes(it) },
                onBackToDevicePicker = { backToDevicePicker() },
                onOpenSystemHeadsetSettings = { openSystemHeadsetSettings() },
                onSavePodImages = { address, name, images, clearedImages ->
                    savePodImages(address, name, images, clearedImages)
                },
                onSavePodImageBytes = { address, name, images -> savePodImageBytes(address, name, images) },
            )
        }
        entry<Screen.About> {
            val aboutScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.about),
                        largeTitle = stringResource(R.string.about),
                        scrollBehavior = aboutScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    AboutPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(aboutScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                    )
                }
            }
        }
        entry<Screen.Theme> {
            val themeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.theme_title),
                        largeTitle = stringResource(R.string.theme_title),
                        scrollBehavior = themeScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    ThemeSettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(themeScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accentMode = accentMode,
                        onAccentModeChange = onAccentModeChange,
                        floatingBottomBar = floatingBottomBar,
                        onFloatingBottomBarChange = onFloatingBottomBarChange,
                        blurBottomBar = blurBottomBar,
                        onBlurBottomBarChange = onBlurBottomBarChange,
                    )
                }
            }
        }
        entry<Screen.Equalizer> {
            val equalizerScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.eq_preset_title),
                        largeTitle = stringResource(R.string.eq_preset_title),
                        scrollBehavior = equalizerScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { padding ->
                EqualizerPage(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .overScrollVertical()
                        .nestedScroll(equalizerScrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = pageBottomContentPadding,
                    ),
                    builtInPresets = displayCapabilities.eqPresets,
                    devicePresets = eqDevicePresets.value,
                    selectedId = eqPreset.value,
                    customEqSupported = displayCapabilities.customEqSupported,
                    customEqFrequencies = displayCapabilities.customEqFrequencies,
                    customEqMaxPresets = displayCapabilities.customEqMaxPresets,
                    onSelectPreset = ::setEqPreset,
                    onSavePreset = ::saveEqPreset,
                    onDeletePreset = ::deleteEqPreset,
                )
            }
        }
        entry<Screen.RfcommDebug> {
            val rfcommScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var clearRfcommLogsRequest by remember { mutableIntStateOf(0) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = "RFCOMM 调试",
                        largeTitle = "RFCOMM 调试",
                        scrollBehavior = rfcommScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { clearRfcommLogsRequest++ }) {
                                Icon(imageVector = MiuixIcons.Delete, contentDescription = "Clear logs")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    RfcommDebugPage(
                        modifier = Modifier.nestedScroll(rfcommScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(0.dp),
                        clearRequest = clearRfcommLogsRequest,
                    )
                }
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        },
        content = navContent,
    )
}

@Composable
fun appBackground(): Color = MiuixTheme.colorScheme.surface

private data class BluetoothSummary(
    val enabled: Boolean,
    val bondedCount: Int,
)

private fun readBluetoothState(context: Context): BluetoothSummary {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return runCatching {
        BluetoothSummary(
            enabled = adapter?.isEnabled == true,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
        )
    }.getOrDefault(BluetoothSummary(enabled = false, bondedCount = 0))
}

private fun wearStateFromExtra(value: Int): WearState? {
    return WearState.fromValue(value)
}

/**
 * 从 `ACTION_PODS_BATTERY_CHANGED` 的纯 int extras 重建电量。
 *
 * 背景：`status` 那条 Parcelable 在两端模块版本不一致时会抛 BadParcelableException
 * （典型场景：蓝牙进程里还跑着 R8 混淆过的旧 release 注入代码，写进去的类名是 `zd`，
 * 而新装的 debug UI 里没有 `zd`）。为了不让界面既崩又空，发送端会同时带上
 * `battery_left/right/case` 三个 int，这里用它们重建。
 *
 * 三个 key 全缺失时返回 null（表示这条广播不带兜底通道，保持上一次显示）。
 */
private fun rebuildBatteryFromInts(intent: Intent): BatteryParams? {
    if (!intent.hasExtra(OppoCompatBridge.EXTRA_BATTERY_LEFT) &&
        !intent.hasExtra(OppoCompatBridge.EXTRA_BATTERY_RIGHT) &&
        !intent.hasExtra(OppoCompatBridge.EXTRA_BATTERY_CASE)
    ) {
        return null
    }
    fun pod(level: Int): PodParams = PodParams(
        battery = level.coerceAtLeast(0),
        isCharging = false,
        isConnected = level >= 0,
        rawStatus = level.coerceAtLeast(0),
    )
    return BatteryParams(
        left = pod(intent.getIntExtra(OppoCompatBridge.EXTRA_BATTERY_LEFT, -1)),
        right = pod(intent.getIntExtra(OppoCompatBridge.EXTRA_BATTERY_RIGHT, -1)),
        case = pod(intent.getIntExtra(OppoCompatBridge.EXTRA_BATTERY_CASE, -1)),
    )
}

private fun sendBluetoothModuleBroadcast(context: Context, action: String) {
    listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { packageName ->
        Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }
}

private fun isLauncherIconHidden(context: Context): Boolean {
    val component = ComponentName(context, "moe.chenxy.fiilpods.LauncherActivity")
    val state = context.packageManager.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val component = ComponentName(context, "moe.chenxy.fiilpods.LauncherActivity")
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

/**
 * 通知指定进程「配置变了」，让它重读一次远端 SharedPreferences。
 *
 * ⚠️ 这里必须**同时**发两个 action：
 * - [OppoPodsAction.ACTION_CONFIG_CHANGED]（`chen.action.oppopods.config_changed`）
 * - [FiilAction.ACTION_CONFIG_CHANGED]（`moe.chenxy.fiilpods.action.CONFIG_CHANGED`）
 *
 * 历史上两个常量各写了一半：发送端用 OppoPodsAction 的版本，而
 * `BluetoothProcessHook` / `SettingsHook` / `MiLinkServiceHook` 三个接收端注册的都是
 * FiilAction 的版本 —— 两边字符串永远不相等，于是配置热更新这条链路**从来没通过**，
 * 用户改完设置必须重启进程（或重启手机）才生效。两个都发是最小改动且不可能再错配。
 */
private fun broadcastConfigChanged(context: Context, packageName: String) {
    for (action in arrayOf(
        OppoPodsAction.ACTION_CONFIG_CHANGED,
        moe.chenxy.fiilpods.pods.FiilAction.ACTION_CONFIG_CHANGED,
    )) {
        Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }
}
