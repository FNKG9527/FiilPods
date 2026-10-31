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
import moe.chenxy.fiilpods.config.ConfigManager
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

        /**
         * 官方投递 `headsetPropertyChangeListener` 的那个工具类。
         *
         * `_dev/milink_out/sources/com/miui/headset/runtime/DiscoveryKt.java`：
         * `getSYSTEM_BROADCAST_HANDLER()` 返回一条名为 `system_broadcast`
         * 的独立线程上的 Handler。官方所有属性变更通知都投到它上面。
         */
        private const val CLS_DISCOVERY_KT = "com.miui.headset.runtime.DiscoveryKt"

        /**
         * 属性变更通知的投递延迟，对齐官方
         * `AncBatteryController.OP_UPDATE_DELAY = 25`（`:26`）。
         *
         * 这个 25ms 不是随便定的：官方在 `setAncStateBlock` 改完模型后
         * 立刻投一个「稍后通知」，等调用方（binder 线程上的
         * `ProfileImpl.updateHeadsetMode`）先把返回值、缓存、UI 状态全部落定，
         * 再让面板重绘。同步直调会在调用方还没写完时就抢先读一遍旧值。
         */
        private const val PROPERTY_CHANGED_DELAY_MS = 25L

        /**
         * 兜底重发的延迟。
         *
         * 部分 ROM 上 `ProfileImpl` 在拿到 `setAncStateBlock` 的返回值后
         * 会用自己的缓存再覆盖一次面板状态，25ms 那次刷新就被盖回旧值 ——
         * 表现为「还是卡住，再点一次才对」。补一次较晚的重发把最终值钉住。
         * 350ms 取自实测：面板一次完整重绘含图标淡入在这一量级内结束。
         */
        private const val PROPERTY_CHANGED_RETRY_MS = 350L

        /**
         * ANC 切换后的「结算窗口」。窗口内收到的 `ACTION_STATE` 若带着与目标值
         * 不一致的 `ancMode`，按过期的旧快照丢弃，不覆盖当前值。
         *
         * 取值依据：耳机回包 `MAF_STATUS` 实测 1~2 秒到达（用户日志 `19:49:24`
         * 发命令、`19:49:26.467` 收到 cid=0x0C 回报，约 2.1s）；蓝牙进程
         * `publishState()` 的心跳周期是 30s，A2DP / 佩戴 / 电量事件会额外触发。
         * 取 3s 覆盖最坏的回包延迟，同时远小于心跳周期，不会长期屏蔽真实状态。
         */
        private const val ANC_SETTLE_MS = 3_000L

        /** MIUI 认为「寻找耳机已开启」的返回值：0 = 关，100 = 开。 */
        private const val RING_ON = 100

        /**
         * MIUI 操作类 API 的「成功」返回码。**全系统统一是 100**。
         *
         * 两个容易混淆的地方：
         *  · `AncBatteryController.setAncStateBlock` 返回的是**操作结果码**
         *    （100 成功 / 201 请求失败 / 202 超时 / 206 目标不匹配 /
         *     207 不支持降噪 / 208 非法档位 / 209~212 佩戴状态不允许），
         *    不是 ANC 状态值。回一个 0/1/2 会被当成失败码。
         *  · `MxBluetoothManager.openAnc` / `closeAnc` / `openTransparent`
         *    返回的是**成功标志**，1 = 已下发，0 = 没发出去。
         *    两者语义不同，但都不是「降噪模式」本身。
         */
        private const val OP_OK = 100

        /** `MxBluetoothManager` 侧「命令已下发」。 */
        private const val OP_DISPATCHED = 1

        /**
         * `MxBluetoothManager.getWearStatus(BluetoothDevice)` 的返回值：
         * **单值字符串**，取值来自 `com.miui.headset.api.WearStatus`。
         *
         * ```java
         * // _dev/milink_out/.../api/WearStatus.java:14-29
         * AllWorn   = "1";
         * LeftWorn  = "2";
         * RightWorn = "3";
         * NotWorn   = "0";
         * Error     = "-1";
         * ```
         *
         * ## 为什么不能回 `"0,0"`（旧实现的 Bug）
         *
         * `AncBatteryController.isSupportOpAnc`（`:510-577`）拿它做
         * `wearStatus.hashCode()` 的 switch：只认 `48/49/50/51`（"0"/"1"/"2"/"3"）
         * 与 `1444`（"-1"），**其余值一律 fall through 到 `return 212`**。
         * `"0,0"` 的 hashCode 是 `48256`，匹配不到任何 case，
         * 于是每一次 ANC 切换都被判成 212（佩戴状态不合法）直接失败 ——
         * 这正是「融合设备中心切降噪没反应 / 状态不刷新」的另一半根因。
         *
         * 回 `"1"`（AllWorn）→ `isSupportOpAnc` 返回 100（成功），
         * 走完 `mxBluetoothManager.openAnc/closeAnc/openTransparent` 后被我们的
         * [hookAncCommand] 拦下，转成 FIIL 降噪指令。
         */
        private const val WEAR_ALL_WORN = "1"

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

    /**
     * 刚下发给耳机、尚未被回包确认的 ANC 目标值；-1 表示没有待确认的切换。
     *
     * 配合 [pendingAncAt] 构成一个「结算窗口」：窗口内收到的 `ACTION_STATE` 如果
     * 带着跟目标值不一样的 `ancMode`，一律当作**过期的旧值**丢弃。
     *
     * 为什么需要这个保护（「切换时图标闪一下旧值」的成因）：
     *  1. 用户在融合中心点「通透」→ 这里把 `ancMode` 置为目标值并立刻通知界面，
     *     界面马上显示「通透」；
     *  2. 同一时刻向蓝牙进程发出 `ACTION_ANC_SELECT`，蓝牙进程要把它编成 FIIL
     *     命令发出去，**耳机回包 MAF_STATUS 需要 1~2 秒**；
     *  3. 在这个窗口内，蓝牙进程任何一次 `publishState()`（电量心跳、A2DP 事件、
     *     连接刷新）都会广播 `ACTION_STATE`。若那条广播是在 `setAnc()` 落地之前
     *     抓的快照，里面就是**旧值**；
     *  4. 本分支原来无条件执行 `ancMode = status.ancMode`，于是目标值被旧值盖掉，
     *     紧接着的 `notifyPanel(UPDATE_BATTERY)` 让界面带着旧值重绘 → 闪回旧模式；
     *  5. 等耳机回包真的到了，`ancMode` 才被纠正回目标值 → 又闪回新模式。
     * 用户看到的就是「通透 → 闪降噪 → 闪通透」。
     */
    @Volatile
    private var pendingAncTarget: Int = -1

    /** [pendingAncTarget] 的下发时刻（`elapsedRealtime` 毫秒）。 */
    @Volatile
    private var pendingAncAt: Long = 0L
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

    /** 主线程 Handler：控制中心的重绘必须回到主线程做，否则会刷在错误的时机上。 */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 官方 `DiscoveryKt.getSYSTEM_BROADCAST_HANDLER()` 的缓存。
     *
     * 懒解析一次即可：这条 Handler 是 `DiscoveryKt` 的 static final 字段，
     * 进程生命周期内不会变。为 null 表示宿主里拿不到（版本漂移），
     * 此时 [systemBroadcastHandler] 回落到 [mainHandler]。
     */
    @Volatile
    private var systemBroadcastHandler: android.os.Handler? = null
    @Volatile
    private var systemBroadcastHandlerResolved = false

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
            hookDeviceResult(className, "getWearStatus") { WEAR_ALL_WORN }
            hookDeviceResult(className, "isLeAudio") { false }
            // 卡片上的三个降噪按钮：点下去不许 SDK 自己发厂商命令。
            // ⚠️ 这三个方法返回的是**成功标志**（1 = 已下发），不是 ANC 状态。
            // `AncBatteryController.setAncStateBlock` 的判断是
            // `if (iCloseAnc != 1) return 201 /* request failed */`，
            // 任何非 1 的返回值都会让整次降噪切换被判定为失败。
            hookAncCommand(className, "openAnc", FiilMate.Maf.NOISE_CANCEL, OP_DISPATCHED)
            hookAncCommand(className, "closeAnc", FiilMate.Maf.OFF, OP_DISPATCHED)
            hookAncCommand(className, "openTransparent", FiilMate.Maf.OPEN, OP_DISPATCHED)
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

        // ------------------------------------------------------------------
        // ⚠️ 这几行是「融合中心切模式后图标卡旧值」的直接病根，不能删。
        //
        // `DiscoveryImpl.assembleHeadsetInfo()`（18.2 smali `:556`，逐行核对过
        // 调用点）构造 `HeadsetInfo` 时，走的**全部是 `ProfileContext` 的 getter**：
        //
        // ```smali
        // :582 invoke-virtual ProfileContext;->getDeviceId(BluetoothDevice)String
        // :590 invoke-virtual ProfileContext;->getBatteryLevel(BluetoothDevice)List
        // :598 invoke-virtual ProfileContext;->getAncState(BluetoothDevice)I      // ← mode
        // :602 invoke-virtual ProfileContext;->getVolume()I
        // :610 invoke-virtual ProfileContext;->getDeviceType(BluetoothDevice)I
        // :620 invoke-virtual ProfileContext;->getSwitchState(String)I
        // :628 invoke-virtual ProfileContext;->getAudioSpatialEffectState(BluetoothDevice)I
        // :636 invoke-virtual ProfileContext;->getFindRingState(BluetoothDevice)I
        // :640 new-instance HeadsetInfo
        // ```
        //
        // 也就是说 `HeadsetInfo.mode` 是 `ProfileContext.getAncState` 的返回值。
        // 只 hook `AncBatteryController.getAncState` 是**不够**的：那一层只覆盖
        // 了「有人直接问控制器」的场景，而真正装配卡片数据的是 `ProfileContext`
        // 这一层。漏掉它 → `mode` 保留宿主原值（第三方耳机那条策略分支给 -1）
        // → 卡片按旧图标重绘 → 必须退出页面重进（重进走另一条会读
        // `HeadsetInfo.getMode()` 的路径，那条我们有 hook）才显示新图标。
        //
        // 症状「退出再进就对了 / 点两次才对」正是这两条路径不一致的指纹。
        // ------------------------------------------------------------------
        hookDeviceResult(CLS_PROFILE_CONTEXT, "getAncState") { miLinkAncState() }
        hookDeviceResult(CLS_PROFILE_CONTEXT, "getFindRingState") { miLinkGameModeState() }
        hookDeviceResult(CLS_PROFILE_CONTEXT, "getAudioSpatialEffectState") { miLinkSpatialMode() }
        hookAddressResult(CLS_PROFILE_CONTEXT, "getSwitchState") { miLinkSwitchState() }

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

    /**
     * 伪设备 ID：让系统认为这是一台支持米 TWS 的耳机。
     *
     * ⚠️ 必须走 [moe.chenxy.fiilpods.config.ConfigManager]，**不能**直接读
     * [FiilConfig.FAKE_DEVICE_ID] 常量。设置页里那个「伪装耳机型号」输入框
     * 改的就是 ConfigManager 里的值；这里若还读常量，用户改完等于没改
     * ——「伪装耳机型号」会退化成一个只有 UI、没有效果的假功能。
     *
     * ConfigManager 在 milink 进程里由 [HookContext.attach] 灌入远端 prefs，
     * 取不到时 ConfigManager 自己会回落到 `DEFAULT_FAKE_DEVICE_ID`，
     * 与这里的兜底值一致，因此是安全的。
     */
    fun fakeDeviceId(): String = runCatching {
        ConfigManager.fakeDeviceId()
    }.getOrDefault(FiilConfig.FAKE_DEVICE_ID)

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
                // 同 hookAncStateBlock：开结算窗口，防止旧值快照回灌导致界面闪回。
                markAncPending(maf)
                // 必须落盘：loadState() 每次取值都会从 prefs 覆盖内存字段，
                // 不写回去的话，miLink 进程一重启就退回旧模式，
                // 表现成「设过通透，过一阵又变回降噪」。
                saveState()
                sendAncSelect(maf)
                syncAncModel(param.instance, device, maf)
                // ⚠️ 不能只传 `param.instance`：这里 hook 的是
                // `MxBluetoothService` / `MxBluetoothManager`，这两个类
                // **没有** `headsetPropertyChangeListener` 字段。
                // `notifyPropertyChanged` 第一步就是反射取那个字段，
                // 取不到直接 return —— 只传 `param.instance` 等于通知永远发不出去，
                // 界面当然不会刷新。真正持有 listener 的是
                // `AncBatteryController`（[lastAncBatteryController]）。
                // 两个都发：宿主版本漂移时哪个有效都不会漏。
                notifyPropertyChanged(lastAncBatteryController, device, UPDATE_ANC)
                notifyPropertyChanged(param.instance, device, UPDATE_ANC)
                param.setResult(result)
            }
        }.onFailure { logW("$className.$methodName command skipped", it) }
    }

    /**
     * `AncBatteryController.setAncStateBlock(BluetoothDevice, int)`：
     * 控制中心拖 ANC 滑块走这条路。MIUI 三态映射到 FIIL MAF。
     *
     * ## 为什么这里必须 `setResult(OP_OK)` 而不是 `miLinkAncState()`
     *
     * 这个方法的返回值是**操作结果码**，不是降噪状态。反编译原文
     * `_dev/milink_out/sources/com/miui/headset/runtime/AncBatteryController.java:968-1085`：
     *
     * ```java
     * public final int setAncStateBlock(BluetoothDevice bluetoothDevice, int i10) {
     *     ...
     *     if (iIsSupportOpAnc != 100) return iIsSupportOpAnc;   // 207/208/209..212
     *     ...
     *     if (iCloseAnc != 1) return 201;                       // request failed
     *     ...
     *     notifyPropertyChanged(ancBatteryModel.getBluetoothDevice(), 8, OP_UPDATE_DELAY);
     *     return 100;                                           // ← 唯一的成功码
     * }
     * ```
     *
     * 而调用方 `ProfileImpl.updateHeadsetMode`（`:2979-3050`）也是拿 100 判成功：
     *
     * ```java
     * int ancState = ProfileContext.INSTANCE.setAncState(device, i10);
     * // :3048
     * if (ancState == 100) { log("Success"); } else { log(映射表[ancState]); }
     * return ancState;
     * ```
     *
     * 旧实现 `setResult(miLinkAncState())` 回的是 0/1/2 —— 调用方读作
     * 「207 不支持降噪 / 201 请求失败 / 202 超时」，于是**判定设置失败、
     * 不刷新 UI**，用户看到的就是「切了降噪/通透，图标还是旧的，要重进页面」。
     * 这正是 Bug 4 的核心：状态其实早就写进模型了，只是返回值谎报失败。
     *
     * ## 为什么还要自己补通知（返回值之外的第二半）
     *
     * 光把返回值改对还不够：官方那条刷新通知写在 `setAncStateBlock` **函数体
     * 最后一行**（`:1083 notifyPropertyChanged(dev, 8, OP_UPDATE_DELAY)`），
     * 我们 hookBefore + `setResult` 让函数体整个不执行，它自然也不会发。
     * 所以下面必须自己补 —— 且必须走 [notifyPropertyChanged] 的**异步投递**，
     * 时序才对得上官方。
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
                // 开结算窗口：接下来 ANC_SETTLE_MS 内收到的 ACTION_STATE 若带旧值，
                // 一律丢弃，避免界面「先闪旧模式再闪新模式」。
                markAncPending(ancMode)
                // 同 hookAncCommand：不落盘的话，本次切模式只在 miLink 进程内存里有效，
                // 进程一重启 loadState() 就把旧值读回来了。
                saveState()
                sendAncSelect(ancMode)
                syncAncModel(param.instance, device, ancMode)
                // 8 = HeadsetModeChanged，官方同款；4 = 电量，模式切换后卡片
                // 会连电量一起重排，一并刷新避免出现半旧半新的卡片。
                notifyPropertyChanged(param.instance, device, UPDATE_ANC)
                notifyPropertyChanged(param.instance, device, UPDATE_BATTERY)
                // 100 = success。回 0/1/2 会被 ProfileImpl 判成失败。
                param.setResult(OP_OK)
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
            // 设置页改了「伪装耳机型号」后要立刻生效：milink 进程里
            // getDeviceId() 的注入值直接决定控制中心认不认这台耳机。
            addAction(FiilAction.ACTION_CONFIG_CHANGED)
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
                // 耳机自己按实体键切模式也会走到这里：同样要把模型字段刷成新值，
                // 否则控制中心的下拉卡片会停在旧档位上。
                currentDevice()?.let { dev ->
                    syncAncModel(lastAncBatteryController, dev, ancMode)
                }
                notifyPanel(UPDATE_ANC)
            }
            FiilAction.ACTION_STATE -> {
                val status = FiilStatus.fromJson(intent.getStringExtra(FiilAction.EXTRA_JSON))
                if (!status.connected) return
                currentAddress = status.address ?: currentAddress
                deviceName = status.name ?: deviceName
                earType = status.earType
                // ⚠️ 这里**不能**无条件采纳 status.ancMode。
                //
                // 蓝牙进程的 publishState() 是随时触发的（电量心跳、A2DP 事件、连接
                // 状态刷新），它打包的是 `FiilRfcommController.ancMode` 的**当前快照**。
                // 用户刚在融合中心点完「通透」时，我们这一侧已经把目标值写进模型并
                // 通知了界面（界面显示通透），但耳机回包 MAF_STATUS 要 1~2 秒才到；
                // 这个窗口内收到的 ACTION_STATE 很可能携带的仍是旧值（降噪）。
                // 一旦采纳，界面就会先闪回降噪，等回包到了再闪回通透 —— 正是用户
                // 报告的「闪一下原来的模式再闪一下通透」。
                //
                // 处理：结算窗口内，只接受与目标值一致的 ancMode；不一致的当作
                // 过期快照丢弃（不覆盖、也不重绘）。窗口结束后回到正常采纳逻辑，
                // 所以耳机真的切换失败时（或不支持时），外部值最终仍会赢。
                val pending = pendingAncTarget
                val inWindow = pending >= 0 &&
                    android.os.SystemClock.elapsedRealtime() - pendingAncAt < ANC_SETTLE_MS
                if (!inWindow) {
                    ancMode = status.ancMode
                    pendingAncTarget = -1
                } else if (status.ancMode == pending) {
                    // 回包确认了目标值：提前结算，让后续状态恢复正常采纳。
                    ancMode = status.ancMode
                    pendingAncTarget = -1
                } else {
                    logD("ACTION_STATE anc=${status.ancMode} ignored, pending=$pending")
                }
                cinemaMode = status.cinemaMode
                lowLatency = status.lowLatency
                leftBattery = status.batteryLeft
                rightBattery = status.batteryRight
                caseBattery = status.batteryCase
                currentAddress?.let { knownAddresses.add(it.uppercase()) }
                saveState()
                notifyPanel(UPDATE_BATTERY)
            }
            FiilAction.ACTION_CONFIG_CHANGED -> {
                // 设置页改了「伪装耳机型号」：重读远端 prefs 后把控制中心整块重建一次，
                // 否则 getDeviceId() 的注入值还是旧的，控制中心会把耳机当成未知设备。
                refreshConfigFromPrefs()
                notifyPanel(UPDATE_BATTERY)
                notifyPanel(UPDATE_ANC)
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
     *
     * ## 必须异步投递（Bug 1 的真正根因）
     *
     * 旧实现直接在当前线程同步 `invoke` —— 那是在 `ProfileImpl.updateHeadsetMode`
     * → `ProfileContext.setAncState` → 本 hook 的调用栈**内部**。此时调用方
     * 还没拿到返回值，更没走完它自己的「写回缓存 → 推给 UI」那一步；
     * 我们抢先推的这次刷新，随后被调用方的旧值覆盖，界面就停在原图标上。
     * 「退出页面重进」能好，是因为重进走的是另一条完整重建路径；
     * 「点第二次才对」是因为第二次时上一次的值已经落定。
     *
     * 官方真机是怎么做的（`AncBatteryController.java:597-604`）：
     *
     * ```java
     * private final void notifyPropertyChanged(BluetoothDevice dev, int type, long delay) {
     *     DiscoveryKt.getSYSTEM_BROADCAST_HANDLER().postDelayed(() -> {
     *         this.headsetPropertyChangeListener.invoke(dev, Integer.valueOf(type));
     *     }, delay);
     * }
     * ```
     *
     * 也就是说官方**永远是 postDelayed 到独立的 `system_broadcast` 线程**，
     * 从不同步直调；`setAncStateBlock` 末尾用的延迟是 `OP_UPDATE_DELAY = 25`。
     * 官方那条 25ms 通知写在 `setAncStateBlock` 函数体最后
     * （`AncBatteryController.java:1083`），而我们的 hook 在 hookBefore 里
     * `setResult(OP_OK)` 直接把它整段跳过了 —— 所以必须自己补，
     * 且补的方式必须和官方一致：投到同一个 Handler、同样延迟。
     */
    fun notifyPropertyChanged(controller: Any?, device: BluetoothDevice?, updateType: Int) {
        if (controller == null || device == null) return
        val handler = obtainSystemBroadcastHandler() ?: return
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }
            .getOrNull() ?: return
        val fire = Runnable { runCatching { callMethod(listener, "invoke", device, updateType) } }
        // 主投递：对齐官方的 25ms，落在 system_broadcast 线程，等调用方栈退出后再刷。
        handler.postDelayed(fire, PROPERTY_CHANGED_DELAY_MS)
        // 兜底重发：部分 ROM 上 ProfileImpl 会在之后再用自己的缓存覆盖一次，
        // 25ms 那次刷完又被盖回旧值，于是表现为「还是卡住」。
        // 再补一次较晚的重发，确保最终落定在新值上。
        if (updateType == UPDATE_ANC || updateType == UPDATE_BATTERY) {
            handler.postDelayed(fire, PROPERTY_CHANGED_RETRY_MS)
        }
    }

    /**
     * 官方 `DiscoveryKt.getSYSTEM_BROADCAST_HANDLER()` 拿到的那个 Handler。
     *
     * 反编译原文（`_dev/milink_out/sources/com/miui/headset/runtime/DiscoveryKt.java:14`）：
     *
     * ```java
     * private static final HandlerEx SYSTEM_BROADCAST_HANDLER =
     *         new HandlerThreadEx("system_broadcast").getHandler();
     * ```
     *
     * 它跑在 `system_broadcast` 这条独立线程上，正是官方投递
     * `headsetPropertyChangeListener` 的地方。拿不到时（宿主版本漂移 /
     * 类被裁掉）回落到本进程主线程 Handler —— 异步性质一致，刷新照样生效。
     */
    private fun obtainSystemBroadcastHandler(): android.os.Handler? {
        if (!systemBroadcastHandlerResolved) {
            systemBroadcastHandlerResolved = true
            systemBroadcastHandler = runCatching {
                callStaticMethod(CLS_DISCOVERY_KT, "getSYSTEM_BROADCAST_HANDLER")
            }.getOrNull() as? android.os.Handler
            if (systemBroadcastHandler == null) {
                logW("system_broadcast handler unavailable, falling back to main looper")
            }
        }
        return systemBroadcastHandler ?: mainHandler
    }

    /**
     * 记下刚下发、尚未确认的 ANC 目标值，开启 [ANC_SETTLE_MS] 结算窗口。
     *
     * 在**两个**下发点调用：[hookAncStateBlock]（融合中心 / 原生耳机页走这条）
     * 与 [hookAncCommand]（耳机自己按实体键或 MxBluetoothService 主动切换走这条）。
     * 不覆盖这两条路径的话，另一条路径仍会把旧值带回来。
     */
    private fun markAncPending(target: Int) {
        pendingAncTarget = target
        pendingAncAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 广播里没带 device 对象，用地址反查。 */
    private fun notifyPanel(updateType: Int) {
        val controller = lastAncBatteryController ?: return
        val device = currentDevice() ?: return
        notifyPropertyChanged(controller, device, updateType)
    }

    /**
     * 把降噪档位回写进 `AncBatteryModel`，供控制中心重绘时读到新值。
     *
     * ## `AncBatteryModel` 只有一个可写状态字段
     *
     * 反编译原文（`AncBatteryModel.java`，仅 3900 字节）的字段全集：
     *
     * ```java
     * private volatile int ancState;                          // get/setAncState
     * private volatile List<Integer> battery;                 // get/setBattery
     * private final BluetoothDevice bluetoothDevice;          // 构造后不变
     * ```
     *
     * 旧实现还写了 `bluetoothAncState` 与 `miLinkAncState` —— 这两个字段
     * **在宿主里根本不存在**，[setObjectField] 沿继承链找不到就静默跳过，
     * 等于什么都没写。它们是从别的 ROM 版本抄来的臆测字段名，已删除。
     *
     * ## 通知不在这里发
     *
     * 三个调用方（[hookAncCommand]、[hookAncStateBlock]、
     * [handleBroadcast] 的 ANC 分支）都会在回写完紧接着调
     * [notifyPropertyChanged] / [notifyPanel]；在那里统一做**异步投递**，
     * 既对齐官方的 25ms 时序，也避免这里重复投递造成刷新风暴。
     */
    private fun syncAncModel(owner: Any?, device: BluetoothDevice?, maf: Int) {
        if (owner == null || device == null) return
        val state = miLinkAncStateFrom(maf)
        val models = buildList {
            runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull()?.let { add(it) }
            runCatching { getObjectField(lastAncBatteryController, "ancBatteryModel") }
                .getOrNull()?.let { add(it) }
            runCatching { getObjectField(lastProfileContext, "ancBatteryModel") }
                .getOrNull()?.let { add(it) }
        }
        models.distinctBy { it.javaClass.name }.forEach { model ->
            if (!isTargetAncBatteryModel(model)) return@forEach
            setObjectField(model, "ancState", state)
        }
        // 记录本次回写是否真的落到了模型上。
        //
        // 为什么值得单独记一笔：18.2 起 ANC 读写改成了策略模式，
        // `AncBatteryController.ancBatteryModel` 只在「小米自家设备」那条
        // 分支里被赋值（`Lvg/b;->i().get(deviceId) != null`）；FIIL 走的是
        // `ThirdPartyHeadsetStrategy`，这个字段很可能是 null。
        // 没有模型可写时，图标只能靠 `ProfileContext.getAncState` 的 hook
        // 撑住 —— 两者都在才算稳妥，只有一个就必须能看出是哪个缺了。
        if (models.isEmpty()) {
            logD("syncAncModel: no ancBatteryModel on host, relying on ProfileContext hook")
        }
    }

    /** 供 [syncAncModel] 用：不依赖成员字段 ancMode，直接按传入 maf 算。 */
    fun miLinkAncStateFrom(maf: Int): Int = when (maf) {
        FiilMate.Maf.NOISE_CANCEL -> 1
        FiilMate.Maf.OPEN, FiilMate.Maf.LISTEN, FiilMate.Maf.WIND -> 2
        else -> 0
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
