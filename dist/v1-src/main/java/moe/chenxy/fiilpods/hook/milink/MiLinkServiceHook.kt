package moe.chenxy.fiilpods.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.hook.HookContext
import moe.chenxy.fiilpods.hook.HookEntry
import moe.chenxy.fiilpods.pods.BatterySnapshot
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilMate
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilStatus

/**
 * HyperOS「融合设备中心」（下拉控制中心里的耳机卡片）的伪造层。
 *
 * 融合设备中心跑在 `com.milink.service` 进程里，通过
 * `com.xiaomi.mxbluetoothsdk`（MxBluetoothSDK）向系统查询耳机属性：
 * 设备 ID、电量、降噪状态、空间音频、游戏模式。这套 SDK 只认白名单里的
 * 米系/大厂私有协议，FIIL 不在其中，所以卡片要么不出现，要么全空白。
 *
 * 我们不实现小米协议，而是**在 SDK 的取值点上拦截**：把 `checkIsMiTWS`
 * 谎报成 1、把 `getDeviceId` 换成自己的伪 ID、把电量/降噪/空间音频的
 * getter 换成读自己的状态。系统那一层不需要改动，卡片会以为自己在跟
 * 一台正经的米 TWS 打交道。
 *
 * 数据来源：`com.android.bluetooth` 进程里的
 * [moe.chenxy.fiilpods.pods.FiilRfcommController] 通过 [FiilAction] 广播
 * 发出状态；本 Hook 在 milink 进程注册同名接收器并落盘 SharedPreferences
 * （跨进程，所以每次 getter 都要 `loadState()`），再在 getter 里回填。
 *
 * 反向控制：用户在卡片上点降噪/通透/空间音频/游戏模式，SDK 会调
 * `openAnc` / `setMiAudioEffect` / `setFindRing` 这些方法，我们 hookBefore
 * 直接短路，同时把 [FiilAction] 广播发给蓝牙进程，由真正的 SPP 链路下发。
 */
@SuppressLint("MissingPermission")
class MiLinkServiceHook(
    entry: HookEntry,
    classLoader: ClassLoader,
) : HookContext(entry, classLoader) {

    companion object {
        const val PREFS_NAME = "fiilpods_milink_state"

        private const val CLS_MX_SERVICE = "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        private const val CLS_MX_MANAGER = "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        private const val CLS_PROFILE_CONTEXT = "com.miui.headset.runtime.ProfileContext"
        private const val CLS_ANC_BATTERY = "com.miui.headset.runtime.AncBatteryController"
        private const val CLS_ANC_MODEL = "com.miui.headset.runtime.AncBatteryModel"
        private const val CLS_HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"

        /** MIUI 认为「寻找耳机已开启」的返回值：0 = 关，100 = 开。 */
        private const val RING_ON = 100

        /** MIUI 侧空间音频三态。 */
        const val SPATIAL_OFF = 0
        const val SPATIAL_FIXED = 1
        const val SPATIAL_HEAD_TRACKING = 2

        /** 同伴 [MiLinkSpatialAudioHook] 需要访问宿主状态，用单例回引。 */
        @Volatile
        internal var current: MiLinkServiceHook? = null
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var currentAddress: String? = null

    private val knownAddresses = linkedSetOf<String>()
    private var registered = false

    @Volatile
    internal var lastAncBatteryController: Any? = null
    @Volatile
    internal var lastProfileContext: Any? = null

    @Volatile
    private var ancMode: Int = 0
    @Volatile
    private var cinemaMode: Boolean = false
    @Volatile
    private var lowLatency: Boolean = false
    @Volatile
    private var leftBattery: Int = -1
    @Volatile
    private var rightBattery: Int = -1
    @Volatile
    private var caseBattery: Int = -1
    @Volatile
    private var leftCharging: Boolean = false
    @Volatile
    private var rightCharging: Boolean = false
    @Volatile
    private var caseCharging: Boolean = false
    @Volatile
    private var earType: Int = -1
    @Volatile
    private var deviceName: String? = null

    private var gameModeIcon: Drawable? = null

    private val spatialHook by lazy { MiLinkSpatialAudioHook(this) }

    override fun onHook() {
        current = this
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        spatialHook.install()
        hookGameModeCard()
        logI("milink hooks installed")
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private fun hookContextEntry() {
        listOf(CLS_MX_SERVICE, CLS_MX_MANAGER).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) { param ->
                    val ctx = param.args[0] as? Context ?: return@hookBefore
                    registerStatusReceiver(ctx)
                }
            }.onFailure { logW("$className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        listOf(CLS_MX_MANAGER, CLS_MX_SERVICE).forEach { className ->
            hookDeviceResult(className, "checkIsMiTWS") { 1 }
            hookDeviceResult(className, "getDeviceId") { fakeDeviceId() }
            hookDeviceResult(className, "getBatteryLevel") { 1 }
            hookDeviceResult(className, "getAncState") { miLinkAncState() }
            hookDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookDeviceResult(className, "getWearStatus") { "0,0" }
            hookDeviceResult(className, "isLeAudio") { false }
            // 卡片上的三个降噪按钮：点下去不许 SDK 自己发厂商命令。
            hookAncCommand(className, "openAnc", FiilMate.Maf.NOISE_CANCEL, 1)
            hookAncCommand(className, "closeAnc", FiilMate.Maf.OFF, 0)
            hookAncCommand(className, "openTransparent", FiilMate.Maf.OPEN, 2)
        }
        listOf(CLS_MX_MANAGER, CLS_MX_SERVICE).forEach { className ->
            hookAddressResult(className, "isMiTWS") { true }
            hookAddressResult(className, "isSupportAudioSwitch") { miLinkSwitchState() }
            hookAddressResult(className, "getRingFindState") { false }
        }
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookDeviceResult(CLS_PROFILE_CONTEXT, "getDeviceId") { fakeDeviceId() }
        hookDeviceResult(CLS_PROFILE_CONTEXT, "getBatteryLevel") { miLinkBatteryLevels() }
        hookDeviceResult(CLS_ANC_BATTERY, "getDeviceId") { fakeDeviceId() }
        hookDeviceResult(CLS_ANC_BATTERY, "getAncState") { miLinkAncState() }
        hookDeviceResult(CLS_ANC_BATTERY, "getBatteryLevelCache") { miLinkBatteryLevels() }
        hookDeviceResult(CLS_ANC_BATTERY, "getHeadsetPropertyBlock") { batteryPercent() }
        hookDeviceResult(CLS_ANC_BATTERY, "getFindRingState") { miLinkGameModeState() }
        hookAddressResult(CLS_ANC_BATTERY, "getSwitchState") { miLinkSwitchState() }

        hookAncStateBlock()

        hookHeadsetInfoNoArg("getDeviceId") { fakeDeviceId() }
        hookHeadsetInfoNoArg("component3") { fakeDeviceId() }
        hookHeadsetInfoNoArg("getPowers") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkAncState() }
        hookHeadsetInfoNoArg("component5") { miLinkAncState() }
        hookHeadsetInfoNoArg("getSwitchState") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("getFindRingState") { miLinkGameModeState() }
        hookHeadsetInfoNoArg("component11") { miLinkGameModeState() }
        hookHeadsetInfoNoArg("getBatteryLevel") { batteryPercent() }
    }

    // ------------------------------------------------------------------
    // Hook 辅助
    // ------------------------------------------------------------------

    /** 伪设备 ID：让系统认为这是一台支持米 TWS 的耳机。 */
    fun fakeDeviceId(): String = FiilConfig.FAKE_DEVICE_ID

    /** hook 形如 `xxx(BluetoothDevice)` 的查询方法，命中我们的耳机时改写返回值。 */
    fun hookDeviceResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isFiilDevice(device)) return@hookAfter
                cacheOwner(className, param.instance)
                captureContext(param.instance)
                param.setResult(result())
                if (className == CLS_ANC_BATTERY && methodName == "getHeadsetPropertyBlock") {
                    notifyPropertyChanged(param.instance, device, UPDATE_BATTERY)
                }
            }
        }.onFailure { logW("$className.$methodName(BluetoothDevice) skipped", it) }
    }

    fun hookAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) { param ->
                val address = param.args[0] as? String ?: return@hookAfter
                if (!isFiilAddress(address)) return@hookAfter
                param.setResult(result())
            }
        }.onFailure { logW("$className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, maf: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isFiilDevice(device)) return@hookBefore
                cacheOwner(className, param.instance)
                captureContext(param.instance)
                ancMode = maf
                sendAncSelect(maf)
                notifyPropertyChanged(param.instance, device, UPDATE_ANC)
                param.setResult(result)
            }
        }.onFailure { logW("$className.$methodName command skipped", it) }
    }

    /**
     * `AncBatteryController.setAncStateBlock(BluetoothDevice, int)`：
     * 控制中心拖 ANC 滑块走这条路。MIUI 三态映射到 FIIL MAF。
     */
    private fun hookAncStateBlock() {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            hookBefore(findMethod(CLS_ANC_BATTERY, "setAncStateBlock", BluetoothDevice::class.java, intType)) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isFiilDevice(device)) return@hookBefore
                lastAncBatteryController = param.instance
                captureContext(param.instance)
                val mode = param.args[1] as? Int ?: return@hookBefore
                ancMode = mafFromMiLink(mode)
                sendAncSelect(ancMode)
                notifyPropertyChanged(param.instance, device, UPDATE_ANC)
                notifyPropertyChanged(param.instance, device, UPDATE_BATTERY)
                param.setResult(miLinkAncState())
            }
        }.onFailure { logW("setAncStateBlock skipped", it) }
    }

    fun hookHeadsetInfoNoArg(methodName: String, result: () -> Any) {
        runCatching {
            val method = findMethodByParamCount(CLS_HEADSET_INFO, methodName, 0) ?: return
            hookAfter(method) { param ->
                if (!isTargetHeadsetInfo(param.instance)) return@hookAfter
                param.setResult(result())
            }
        }.onFailure { logW("HeadsetInfo.$methodName skipped", it) }
    }

    // ------------------------------------------------------------------
    // 状态接收
    // ------------------------------------------------------------------

    private fun registerStatusReceiver(ctx: Context) {
        if (registered) return
        appContext = ctx.applicationContext ?: ctx
        val filter = IntentFilter().apply {
            addAction(FiilAction.ACTION_PODS_CONNECTED)
            addAction(FiilAction.ACTION_PODS_DISCONNECTED)
            addAction(FiilAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(FiilAction.ACTION_PODS_ANC_CHANGED)
            addAction(FiilAction.ACTION_STATE)
        }
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    runCatching { handleBroadcast(intent) }
                }
            }, filter, Context.RECEIVER_EXPORTED)
            registered = true
            logI("milink status receiver registered")
        }.onFailure { logW("register receiver failed", it) }

        // 主动向蓝牙进程要一次状态，避免控制中心先于连接出现空白。
        runCatching {
            ctx.sendBroadcast(Intent(FiilAction.ACTION_PODS_UI_INIT).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    private fun handleBroadcast(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            FiilAction.ACTION_PODS_CONNECTED -> {
                currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                deviceName = intent.getStringExtra(FiilAction.EXTRA_DEVICE_NAME) ?: deviceName
                currentAddress?.let { knownAddresses.add(it.uppercase()) }
                saveState()
            }
            FiilAction.ACTION_PODS_DISCONNECTED -> {
                currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                leftBattery = -1; rightBattery = -1; caseBattery = -1
                leftCharging = false; rightCharging = false; caseCharging = false
                saveState()
                notifyPanel(UPDATE_BATTERY)
            }
            FiilAction.ACTION_PODS_BATTERY_CHANGED -> {
                currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                val snapshot = BatterySnapshot.fromExtras(intent)
                if (snapshot != null && snapshot.hasAny()) {
                    leftBattery = snapshot.left?.takeIf { it.connected }?.battery ?: -1
                    rightBattery = snapshot.right?.takeIf { it.connected }?.battery ?: -1
                    caseBattery = snapshot.case?.takeIf { it.connected }?.battery ?: -1
                    leftCharging = snapshot.left?.charging == true
                    rightCharging = snapshot.right?.charging == true
                    caseCharging = snapshot.case?.charging == true
                }
                currentAddress?.let { knownAddresses.add(it.uppercase()) }
                saveState()
                notifyPanel(UPDATE_BATTERY)
            }
            FiilAction.ACTION_PODS_ANC_CHANGED -> {
                ancMode = intent.getIntExtra(FiilAction.EXTRA_STATUS, ancMode)
                saveState()
                notifyPanel(UPDATE_ANC)
            }
            FiilAction.ACTION_STATE -> {
                val status = FiilStatus.fromJson(intent.getStringExtra(FiilAction.EXTRA_JSON))
                if (!status.connected) return
                currentAddress = status.address ?: currentAddress
                deviceName = status.name ?: deviceName
                earType = status.earType
                ancMode = status.ancMode
                cinemaMode = status.cinemaMode
                lowLatency = status.lowLatency
                leftBattery = status.batteryLeft
                rightBattery = status.batteryRight
                caseBattery = status.batteryCase
                currentAddress?.let { knownAddresses.add(it.uppercase()) }
                saveState()
                notifyPanel(UPDATE_BATTERY)
            }
        }
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    /**
     * FIIL MAF 五态 → MIUI 三态。
     *
     * MIUI 的系统侧只有三档（关 / 降噪 / 通透），FIIL 的「开放」「兼听」「风中」
     * 都归到「通透」——它们在系统语义上都属于「让外界声音进来」。
     */
    fun miLinkAncState(): Int = when (ancMode) {
        FiilMate.Maf.NOISE_CANCEL -> 1
        FiilMate.Maf.OPEN, FiilMate.Maf.LISTEN, FiilMate.Maf.WIND -> 2
        else -> 0
    }

    /** MIUI 三态 → FIIL MAF。系统只发这三档，其余档位只能在模块自己的界面里选。 */
    private fun mafFromMiLink(mode: Int): Int = when (mode) {
        1 -> FiilMate.Maf.NOISE_CANCEL
        2 -> FiilMate.Maf.OPEN
        else -> FiilMate.Maf.OFF
    }

    /**
     * 系统要的电量列表：`[盒, 左, 右, 盒充电, 左充电, 右充电]`。
     * 未连接用 -1 而不是 0 —— 0 会在卡片上显示成「0%」。
     */
    fun miLinkBatteryLevels(): List<Int> {
        loadState()
        return listOf(
            caseBattery,
            leftBattery,
            rightBattery,
            if (caseCharging) 1 else 0,
            if (leftCharging) 1 else 0,
            if (rightCharging) 1 else 0,
        )
    }

    /** 卡片正面的总电量：取左右耳较低者，都不可用时回落到盒电量。 */
    fun batteryPercent(): Int {
        loadState()
        val ear = listOf(leftBattery, rightBattery).filter { it >= 0 }
        return when {
            ear.isNotEmpty() -> ear.min()
            caseBattery >= 0 -> caseBattery
            else -> 0
        }
    }

    /** 空间音频/游戏模式面板是否可用：有连接且有 earType。 */
    fun miLinkSwitchState(): Int {
        loadState()
        return if (earType > 0 && FiilModels.isSppSupported(earType)) 1 else 0
    }

    fun miLinkGameModeState(): Int {
        loadState()
        if (!gameModePanelEnabled()) return -1
        return if (lowLatency) RING_ON else 0
    }

    fun miLinkSpatialMode(): Int {
        loadState()
        if (!spatialPanelEnabled()) return -1
        return if (cinemaMode) SPATIAL_HEAD_TRACKING else SPATIAL_OFF
    }

    fun miLinkAudioEffectState(): Int {
        loadState()
        return if (!spatialPanelEnabled()) -1 else if (cinemaMode) SPATIAL_HEAD_TRACKING else SPATIAL_OFF
    }

    fun miLinkDeviceSpatialType(): Int = if (spatialPanelEnabled()) 1 else 0

    fun spatialPanelEnabled(): Boolean {
        loadState()
        return earType > 0 && FiilModels.supportsCinema(earType)
    }

    fun gameModePanelEnabled(): Boolean {
        loadState()
        return earType > 0 && FiilModels.supportsMateMaf(earType)
    }

    fun updateCinema(on: Boolean) {
        cinemaMode = on
        saveState()
    }

    fun updateGameMode(on: Boolean) {
        lowLatency = on
        saveState()
    }

    // ------------------------------------------------------------------
    // 反向控制
    // ------------------------------------------------------------------

    fun sendAncSelect(maf: Int, fallback: Context? = null) {
        val ctx = fallback ?: appContext ?: run {
            logW("sendAncSelect skipped: no context, maf=$maf")
            return
        }
        runCatching {
            ctx.sendBroadcast(Intent(FiilAction.ACTION_ANC_SELECT).apply {
                putExtra(FiilAction.EXTRA_STATUS, maf)
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { logW("sendAncSelect failed", it) }
    }

    fun sendCommand(
        cmd: String,
        flag: Boolean? = null,
        value: Int? = null,
        fallback: Context? = null,
    ) {
        val ctx = fallback ?: appContext ?: return
        runCatching {
            ctx.sendBroadcast(Intent(FiilAction.ACTION_COMMAND).apply {
                putExtra(FiilAction.EXTRA_CMD, cmd)
                if (flag != null) putExtra(FiilAction.EXTRA_FLAG, flag)
                if (value != null) putExtra(FiilAction.EXTRA_VALUE, value)
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { logW("sendCommand($cmd) failed", it) }
    }

    // ------------------------------------------------------------------
    // 游戏模式卡片 / 状态通知
    // ------------------------------------------------------------------

    /**
     * 把融合中心里「寻找耳机」那张卡片改写成「游戏模式 已开启/已关闭」。
     *
     * 文案来自 `circulate_headset_control_audio_find_earphone` 这个资源，
     * 我们不改资源，而是在 `SynergyView.setTitle(int)` 返回前直接把
     * View 上的文字换掉。点击仍然走系统原本的「寻找耳机」回调，
     * 我们在 `setFindRing` 里把它接管成游戏模式。
     */
    private fun hookGameModeCard() {
        val synergyView = listOf(
            "com.miui.circulate.world.sticker.ui.SynergyView",
            "com.miui.circulate.world.sticker.p067ui.SynergyView",
        ).firstNotNullOfOrNull { runCatching { findClass(it) }.getOrNull() } ?: return

        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            val method = synergyView.getDeclaredMethod("setTitle", intType).apply { isAccessible = true }
            hookBefore(method) { param ->
                val view = param.instance as? View ?: return@hookBefore
                val resId = param.args[0] as? Int ?: return@hookBefore
                if (viewResourceName(view, view.id) != "mi_audio_ringing_view") return@hookBefore
                if (!gameModePanelEnabled()) return@hookBefore
                val sourceName = viewResourceName(view, resId)
                if (sourceName != "circulate_headset_control_audio_find_earphone" &&
                    sourceName != "circulate_headset_control_audio_stop_find_earphone"
                ) {
                    return@hookBefore
                }
                setSynergyTitle(view, "游戏模式")
                setSynergySubtitle(view, if (lowLatency) "已开启" else "已关闭")
                setSynergyIcon(view, loadGameModeIcon(view))
                param.setResult(null)
            }
        }.onFailure { logW("SynergyView.setTitle skipped", it) }

        // 卡片点击 → 切游戏模式（= 低延迟）。
        runCatching {
            hookBefore(findMethod(CLS_ANC_BATTERY, "setFindRing", BluetoothDevice::class.java, intType)) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isFiilDevice(device) || !gameModePanelEnabled()) return@hookBefore
                lastAncBatteryController = param.instance
                captureContext(param.instance)
                lowLatency = (param.args[1] as? Int ?: 0) != 0
                sendCommand("lowLatencyMode", value = if (lowLatency) 1 else 0)
                saveState()
                notifyPropertyChanged(param.instance, device, UPDATE_GAME_MODE)
                param.setResult(RING_ON)
            }
        }.onFailure { logW("AncBatteryController.setFindRing skipped", it) }
    }

    private fun viewResourceName(view: View, id: Int): String? {
        if (id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun setSynergyTitle(view: View, title: CharSequence) {
        val method = view.javaClass.methods.firstOrNull {
            it.name == "setTitle" && it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java))
        }
        if (method != null) {
            runCatching { method.invoke(view, title) }
            return
        }
        findChildByName(view, "item_title")?.let { (it as? TextView)?.text = title }
    }

    private fun setSynergySubtitle(view: View, text: CharSequence) {
        (findChildByName(view, "item_subtitle") as? TextView)?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun setSynergyIcon(view: View, drawable: Drawable?) {
        drawable ?: return
        (findChildByName(view, "item_icon") as? ImageView)?.setImageDrawable(drawable)
    }

    private fun findChildByName(view: View, name: String): View? {
        val packageName = runCatching { view.resources.getResourcePackageName(view.id) }
            .getOrDefault(view.context.packageName)
        val id = view.resources.getIdentifier(name, "id", packageName)
        return id.takeIf { it != 0 }?.let(view::findViewById)
    }

    private fun loadGameModeIcon(view: View): Drawable? {
        gameModeIcon?.let { return it }
        return runCatching {
            view.context.createPackageContext(FiilConfig.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                .getDrawable(R.drawable.ic_launcher_foreground)
                ?.also { gameModeIcon = it }
        }.getOrNull()
    }

    /**
     * 通知控制中心刷新某一块内容。
     *
     * `headsetPropertyChangeListener` 是个 `(BluetoothDevice, Int) -> Unit`
     * 的私有字段，只能反射取出来再 `invoke`。
     * updateType：4 电量 / 8 降噪 / 9 空间音频 / 10 游戏模式。
     */
    fun notifyPropertyChanged(controller: Any?, device: BluetoothDevice?, updateType: Int) {
        if (controller == null || device == null) return
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }
            .getOrNull() ?: return
        runCatching { callMethod(listener, "invoke", device, updateType) }
    }

    /** 广播里没带 device 对象，用地址反查。 */
    private fun notifyPanel(updateType: Int) {
        val controller = lastAncBatteryController ?: return
        val device = currentDevice() ?: return
        notifyPropertyChanged(controller, device, updateType)
    }

    private fun currentDevice(): BluetoothDevice? {
        val address = currentAddress ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 身份判定
    // ------------------------------------------------------------------

    fun isFiilDevice(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isFiilAddress(address)) return true
        // 用户可能把蓝牙名改成任意值，这时靠 FIIL 自己的 MAC 前缀兜底
        // （FIIL+ 的 OTA 页就是用 `startsWith("B0:F1:A3")` 判定自家设备）。
        if (address != null && FiilConfig.isFiilAddress(address)) {
            knownAddresses.add(address.uppercase())
            currentAddress = address
            deviceName = runCatching { device.name ?: device.alias }.getOrNull()
            return true
        }
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (FiilConfig.isFiilName(name)) {
            if (address != null) {
                knownAddresses.add(address.uppercase())
                currentAddress = address
            }
            deviceName = name
            return true
        }
        return false
    }

    fun isFiilAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownAddresses
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { name ->
            val address = runCatching { callMethod(info, name) as? String }.getOrNull()
            if (address != null && isFiilAddress(address)) return true
        }
        return false
    }

    fun isTargetAncBatteryModel(model: Any?): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
        return device != null && isFiilDevice(device)
    }

    fun cacheOwner(className: String, owner: Any?) {
        when (className) {
            CLS_ANC_BATTERY -> lastAncBatteryController = owner
            CLS_PROFILE_CONTEXT -> lastProfileContext = owner
        }
    }

    fun captureContext(owner: Any?) {
        val captured = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastAncBatteryController, "context") as? Context }.getOrNull()
            ?: return
        appContext = captured.applicationContext ?: captured
    }

    /** 供 [MiLinkSpatialAudioHook] 用：它不继承 HookContext，拿不到 protected 的 logW。 */
    fun logInstallerWarn(message: String, t: Throwable? = null) = logW(message, t)

    // ------------------------------------------------------------------
    // 持久化（跨进程读，每次 getter 都要 loadState）
    // ------------------------------------------------------------------

    private fun saveState() {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("address", currentAddress)
                .putString("name", deviceName)
                .putInt("anc", ancMode)
                .putBoolean("cinema", cinemaMode)
                .putBoolean("low_latency", lowLatency)
                .putInt("ear_type", earType)
                .putInt("left_battery", leftBattery)
                .putInt("right_battery", rightBattery)
                .putInt("case_battery", caseBattery)
                .putBoolean("left_charging", leftCharging)
                .putBoolean("right_charging", rightCharging)
                .putBoolean("case_charging", caseCharging)
                .apply()
        }
    }

    private fun loadState() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentAddress = prefs.getString("address", currentAddress) ?: currentAddress
            deviceName = prefs.getString("name", deviceName) ?: deviceName
            ancMode = prefs.getInt("anc", ancMode)
            cinemaMode = prefs.getBoolean("cinema", cinemaMode)
            lowLatency = prefs.getBoolean("low_latency", lowLatency)
            earType = prefs.getInt("ear_type", earType)
            leftBattery = prefs.getInt("left_battery", leftBattery)
            rightBattery = prefs.getInt("right_battery", rightBattery)
            caseBattery = prefs.getInt("case_battery", caseBattery)
            leftCharging = prefs.getBoolean("left_charging", leftCharging)
            rightCharging = prefs.getBoolean("right_charging", rightCharging)
            caseCharging = prefs.getBoolean("case_charging", caseCharging)
            currentAddress?.let { knownAddresses.add(it.uppercase()) }
        }
    }
}

/** `headsetPropertyChangeListener` 的 updateType 取值。 */
internal const val UPDATE_BATTERY = 4
internal const val UPDATE_ANC = 8
internal const val UPDATE_SPATIAL = 9
internal const val UPDATE_GAME_MODE = 10
