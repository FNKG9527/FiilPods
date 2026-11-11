package moe.chenxy.fiilpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.chenxy.fiilpods.config.PodImagePrefs
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.NoiseControlMode
import moe.chenxy.fiilpods.pods.OppoCompatBridge
import moe.chenxy.fiilpods.pods.detectDeviceCapabilities
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.ui.AppLocale
import moe.chenxy.fiilpods.ui.AppTheme
import moe.chenxy.fiilpods.ui.components.AncSwitch
import moe.chenxy.fiilpods.ui.components.PodStatus
import moe.chenxy.fiilpods.ui.components.rememberPodImagePainter
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

class PopupActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 弹层是「通知点击」的目标，也是最容易被用户看到的入口。
        // 通知权限若还没给，在这里补一次申请（同一个 App 只会弹一次系统框）。
        runCatching { moe.chenxy.fiilpods.utils.NotifPermission.ensure(this) }

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val appConfig = ConfigManager.refreshFromPrefs(prefs)
        val bluetoothDevice = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
        if (appConfig.notificationClickAction != ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
            openNotificationTarget(appConfig.notificationClickAction, bluetoothDevice)
            finish()
            return
        }

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                PopupContent(
                    onMore = {
                        val latestConfig = ConfigManager.refreshFromPrefs(prefs)
                        openMoreTarget(latestConfig.moreClickAction, bluetoothDevice)
                        finish()
                    },
                    onDone = { finish() }
                )
            }
        }
    }

    private fun openNotificationTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            ConfigManager.NOTIFICATION_CLICK_HEYTAP -> openHeyTapOrModule()
            else -> openModule()
        }
    }

    private fun openMoreTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.MORE_CLICK_HEYTAP -> openHeyTapOrModule()
            ConfigManager.MORE_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openModule() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openHeyTapOrModule() {
        val intent = packageManager.getLaunchIntentForPackage("com.heytap.headset")
        if (intent != null) {
            startActivity(intent)
        } else {
            openModule()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSystemSettings(bluetoothDevice: BluetoothDevice?) {
        if (bluetoothDevice == null) {
            openModule()
            return
        }
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            putExtra("bluetoothaddress", bluetoothDevice.address)
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { openModule() }
    }

    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        // BluetoothDevice 是系统的 @SystemApi/Hidden 类，理论上是稳定的；
        // 但仍然用 runCatching 兜住，避免任何 unmarshalling 异常把弹窗崩掉。
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra<BluetoothDevice>(key)
            }
        }.getOrNull()
    }
}

@Composable
private fun PopupContent(onMore: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode = remember { prefs.getInt("theme_mode", 0) }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val deviceName = remember { mutableStateOf("") }
    val productId = remember { mutableStateOf<String?>(null) }
    // 设备产品图的数据来源。弹层此前完全没有画过耳机图（只有三列电量小图标），
    // 这里补上：`earType` 决定官方型号图，`boxImagePath` 是用户自定义的盒图。
    val earType = remember { mutableIntStateOf(-1) }
    val boxImagePath = remember { mutableStateOf<String?>(null) }
    // 上一次解析过型号的地址，用来判断「是不是换了一副耳机」。
    val lastImageAddress = remember { mutableStateOf<String?>(null) }
    remember { ConfigManager.refreshFromPrefs(prefs) }
    val capabilities = detectDeviceCapabilities(
        context = context,
        deviceName = deviceName.value,
        productId = productId.value,
    )

    // 从广播里把「型号 + 自定义盒图路径」落到状态上。
    //
    // 型号来源按置信度回落：广播带的 earType（最权威）→ 地址绑定（FiilConfig 记录过
    // 这台耳机认出来的型号）→ 蓝牙名推断。三级都不中才保持 -1，此时画通用盒图。
    // 与 MiLink 图标（MiLinkServiceHook.headsetTypeIcon）用的是同一套回退链。
    fun applyDeviceImage(address: String?, type: Int) {
        // 换了另一副耳机时，先把上一副的型号清掉，避免「A 的头戴图挂在 B 上」。
        val addr = address?.takeIf { it.isNotBlank() }
        if (addr != null && addr != lastImageAddress.value) {
            lastImageAddress.value = addr
            earType.intValue = -1
        }
        val resolved = if (type > 0) {
            type
        } else {
            FiilConfig.readEarTypeForAddress(context, address).takeIf { it > 0 }
                ?: FiilModels.earTypeOfName(deviceName.value).takeIf { it > 0 }
                ?: -1
        }
        if (resolved > 0) earType.intValue = resolved
        // 自定义盒图按地址查；查不到就退回「最近连接的那副」，与通知栏取图一致。
        boxImagePath.value = PodImagePrefs.findOrLatest(prefs, address.orEmpty())?.boxImagePath
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                // 同 MainUI：接收器最外层兜住一切异常。
                // `BadParcelableException` 是在 Bundle 惰性解包时抛的，属于 Error 之外的
                // RuntimeException，普通 `?.let`/空判断都挡不住，只能在 onReceive 这一层接住。
                runCatching {
                when (p1?.action) {                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
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
                            // FIIL 专有档位（与 MainUI 保持一致）：9=兼听、10=风中。
                            9 -> NoiseControlMode.LISTEN
                            10 -> NoiseControlMode.WIND
                            else -> NoiseControlMode.OFF
                        }
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        // 同 MainUI：**绝不读 Parcelable**，只用纯 int 通道重建电量。
                        // 读 Parcelable 会在两端构建类名不一致时抛
                        // BadParcelableException → 广播接收器异常 → 进程被 AMS 杀掉。
                        OppoCompatBridge.batteryFromIntent(p1)?.let { batteryParams.value = it }
                        // 型号与电量同一条广播（FiilAction.EXTRA_EAR_TYPE），一起收。
                        applyDeviceImage(
                            address = p1.getStringExtra(FiilAction.EXTRA_ADDRESS),
                            type = p1.getIntExtra(FiilAction.EXTRA_EAR_TYPE, -1),
                        )
                    }
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        deviceName.value = p1.getStringExtra("device_name") ?: ""
                        productId.value = p1.getStringExtra("product_id") ?: productId.value
                        // product_id 就是 earType（见 OppoCompatBridge.publishConnection）。
                        applyDeviceImage(
                            address = p1.getStringExtra(FiilAction.EXTRA_ADDRESS),
                            type = productId.value?.toIntOrNull() ?: -1,
                        )
                        if (!showDialog.value) showDialog.value = true
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        productId.value = null
                        showDialog.value = false
                    }
                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }
                    OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        transparencyVocalEnhancement.value = p1.getBooleanExtra("enabled", false)
                    }
                }
                }.onFailure {
                    android.util.Log.w("FiilPods", "popup broadcast failed", it)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })

        onDispose {
            try { context.unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
        }
    }

    // Timeout fallback: show dialog even if no response within 500ms
    // Periodic refresh: poll earbuds every 15s while popup is open
    LaunchedEffect(Unit) {
        delay(500)
        if (!showDialog.value) showDialog.value = true

        while (true) {
            delay(15_000)
            context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
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
            putExtra("status", status)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        gameMode.value = enabled
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        transparencyVocalEnhancement.value = enabled
        Intent(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(containerColor = Color.Transparent) { _ ->
        OverlayDialog(
            title = deviceName.value.ifEmpty { stringResource(R.string.app_name) },
            show = showDialog.value,
            backgroundColor = dialogBgColor,
            onDismissRequest = {
                showDialog.value = false
            },
            onDismissFinished = {
                onDone()
            }
        ) {
            if (isLandscape) {
                LandscapePopupBody(
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    adaptiveModeEnabled = capabilities.adaptiveSupported,
                    earType = earType.intValue,
                    boxImagePath = boxImagePath.value,
                )
            } else {
                PortraitPopupBody(
                    batteryParams = batteryParams.value,
                    ancMode = ancMode.value,
                    gameMode = gameMode.value,
                    transparencyVocalEnhancement = transparencyVocalEnhancement.value,
                    onAncModeChange = ::setAncMode,
                    onGameModeChange = ::setGameMode,
                    onTransparencyVocalEnhancementChange = ::setTransparencyVocalEnhancement,
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                    adaptiveModeEnabled = capabilities.adaptiveSupported,
                    earType = earType.intValue,
                    boxImagePath = boxImagePath.value,
                )
            }
        }
    }
}

@Composable
private fun PortraitPopupBody(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true,
    earType: Int = -1,
    boxImagePath: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 设备产品图：型号官方图（用户自定义过则优先用自定义盒图）。
                // 竖屏宽度够，图放在电量上方，与主界面详情页保持同一视觉。
                // 用「限定高度 + Fit」而不是「限定宽度 + FillWidth」：产品图宽高比各不相同
                // （盒子图接近 2:1，头戴图偏方），固定高度能让不同型号的弹层高度保持一致，
                // Fit 也保证图不会被裁掉、不会画到卡片外面。
                Image(
                    painter = rememberPodImagePainter(boxImagePath, earType),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(120.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(8.dp))
                PodStatus(batteryParams, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            AncSwitch(
                ancStatus = ancMode,
                onAncModeChange = onAncModeChange,
                adaptiveModeEnabled = adaptiveModeEnabled,
                transparencyVocalEnhancement = transparencyVocalEnhancement,
                onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.game_mode),
                summary = stringResource(R.string.game_mode_summary),
                checked = gameMode,
                onCheckedChange = onGameModeChange
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LandscapePopupBody(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    gameMode: Boolean,
    transparencyVocalEnhancement: Boolean,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onGameModeChange: (Boolean) -> Unit,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    adaptiveModeEnabled: Boolean = true,
    earType: Int = -1,
    boxImagePath: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 560.dp)
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 横屏高度只有 240dp，产品图放左侧竖排旁，避免压掉电量与 ANC。
                    Image(
                        painter = rememberPodImagePainter(boxImagePath, earType),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .weight(0.34f)
                            .heightIn(max = 96.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PodStatus(
                        batteryParams,
                        modifier = Modifier.weight(0.66f),
                        compact = true
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                AncSwitch(
                    ancMode,
                    onAncModeChange = onAncModeChange,
                    compact = true,
                    adaptiveModeEnabled = adaptiveModeEnabled,
                    transparencyVocalEnhancement = transparencyVocalEnhancement,
                    onTransparencyVocalEnhancementChange = onTransparencyVocalEnhancementChange
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            val gameModeCardColor = if (gameMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer
            val gameModeTextColor = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = gameModeCardColor,
                    contentColor = gameModeTextColor
                ),
                pressFeedbackType = PressFeedbackType.Sink,
                showIndication = true,
                onClick = { onGameModeChange(!gameMode) },
                onLongPress = {}
            ) {
                Text(
                    text = stringResource(R.string.game_mode),
                    color = if (gameMode) Color.White else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
