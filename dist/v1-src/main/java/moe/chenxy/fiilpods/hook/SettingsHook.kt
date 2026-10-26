package moe.chenxy.fiilpods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import moe.chenxy.fiilpods.pods.BatterySnapshot
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilStatus
import java.util.WeakHashMap

/**
 * 注入 com.android.settings 的 HyperOS 耳机详情页。
 *
 * 技术路线（与「往页面塞 View」完全不同）：
 *  1. **伪造 Intent extras** —— 让宿主以为这是它支持的耳机型号
 *  2. **伪造判定返回值** —— checkSupport / isBleMmaConnect / getSupport 全部强制 true
 *  3. **吞掉 MMA 服务代理** —— 页面所有真实请求打到这里，我们返回假数据
 *  4. **反射回填** —— updateAtUiInfo / updateAncUi / refreshStatus 把我们的数据灌进去
 *
 * 页面本身仍是宿主原生 UI，我们只负责喂数据，所以不存在 Compose 跨 ClassLoader 的问题。
 *
 * ## 参照 OppoPods 的对应关系
 * 整条路线照搬 `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt`
 * （773 行），逐段对应如下：
 *
 * | 本文件 | OppoPods 对应 |
 * |---|---|
 * | `hookActivityEntry()` | `hookActivityEntry()`（改 `MIUI_HEADSET_SUPPORT` / `DEVICE_ID` / `COME_FROM`） |
 * | `hookSupportChecks()` | `hookSupportChecks()`（`checkSupport` / `isTWS01Headset` 恒 true） |
 * | `hookServiceProxy()` | `hookServiceProxy()`（`IMiuiHeadsetService$Stub$Proxy` 全方法伪造） |
 * | `hookBatteryView()` | `hookBatteryView()`（`MiuiHeadsetBattery.onBatteryChanged` 改写） |
 * | `hookFragmentState()` | `hookFragmentState()`（`onCreateView` / `onServiceConnected` / `refreshStatus`） |
 * | `injectFragmentStatus()` | `injectFragmentStatus()`（`updateAtUiInfo` + `updateAncUi`） |
 * | `SettingsControlCard` | **无对应** —— OppoPods 只复用宿主 UI，本卡片是本项目为 FIIL 私有能力新增的 |
 *
 * 布局与控件层级参照 `refs/OppoPods` 的 `MiuiHeadsetFragment` 同款宿主页面，
 * 额外卡片见 [SettingsControlCard] 的注释。
 */
@SuppressLint("MissingPermission")
class SettingsHook(entry: HookEntry, classLoader: ClassLoader) : HookContext(entry, classLoader) {

    companion object {
        private const val PREFS_NAME = "fiilpods_settings_state"
        private const val CLS_ACTIVITY = "com.android.settings.bluetooth.MiuiHeadsetActivity"
        private const val CLS_ACTIVITY_PLUGIN = "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin"
        private const val CLS_ID_CONSTANTS = "com.android.settings.bluetooth.HeadsetIDConstants"
        private const val CLS_FRAGMENT = "com.android.settings.bluetooth.MiuiHeadsetFragment"
        private const val CLS_BATTERY = "com.android.settings.bluetooth.tws.MiuiHeadsetBattery"
        private const val CLS_SERVICE_IFACE = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
        private const val CLS_SERVICE_PROXY = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"

        /** 模块自己的完整控制台（设置页菜单跳转目标）。 */
        private const val CLS_MODULE_MAIN = "moe.chenxy.fiilpods.MainActivity"

        /**
         * 溢出菜单项 id。取一个远离宿主的任意值即可：
         * 宿主菜单项 id 由它自己的 `R.id.*` 生成，模块菜单 id 只要不撞上就行，
         * 这里用 ASCII "FP" 凑一个高位值，实际碰撞概率可忽略。
         */
        private const val MODULE_MENU_ID = 0x4650_0001

        /** 菜单项标题。用字面量而不是 R.string：跨 ClassLoader 拿不到模块资源。 */
        private const val MENU_TITLE = "FiilPods 高级设置"

        /** MIUI 设置页的 ANC 档位码。 */
        private const val LEVEL_OFF = "0000"
        private const val LEVEL_MEDIUM = "0100"
        private const val LEVEL_LIGHT = "0101"
        private const val LEVEL_DEEP = "0102"
        private const val LEVEL_SMART = "0103"
        private const val LEVEL_TRANSPARENCY = "0200"

        /** 设置页支持的全部档位（顺序即页面显示顺序）。 */
        private const val LEVELS_ALL = "0100;0101;0102;0103;0200;0201"
    }

    private val knownAddresses = linkedSetOf<String>()
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()

    /** fragment → 我们注入的控制卡片 View（弱引用，页面销毁自动回收）。 */
    private val controlCards = WeakHashMap<Any, android.view.View>()

    /**
     * 最近一次收到的完整状态。
     *
     * 控制卡片上的影院 / 低延迟 / 多点 / EQ 这些 FIIL 私有能力，宿主页面
     * 完全不知道，只能由这条快照提供。为 null 时卡片按「未连接」渲染。
     */
    private var currentStatus: FiilStatus? = null

    private var context: Context? = null
    private var receiverRegistered = false
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatterySnapshot = BatterySnapshot()
    private var currentAnc: Int = -1
    private var currentEarType: Int = -1

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (headsetFragments.keys.any { isFiilFragment(it) }) {
                requestBluetoothStatus("settings-periodic")
                refreshHandler.postDelayed(this, FiilConfig.SETTINGS_REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                logD("periodic refresh stopped")
            }
        }
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    override fun onHook() {
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookBatteryView()
        hookFragmentState()
        hookModuleEntry()
    }

    private fun fakeDeviceId(): String = FiilConfig.FAKE_DEVICE_ID
    private fun fakeSupport(): String = FiilConfig.FAKE_SUPPORT

    // ------------------------------------------------------------------
    // 1. Activity 入口：改 Intent
    // ------------------------------------------------------------------

    private fun hookActivityEntry() {
        patchActivityIntent(CLS_ACTIVITY, writeComeFrom = true)
        patchActivityIntent(CLS_ACTIVITY_PLUGIN, writeComeFrom = false)

        hookStringGetter(CLS_ACTIVITY, "getDeviceID") { fakeDeviceId() }
        hookStringGetter(CLS_ACTIVITY, "getSupport") { fakeSupport() }
    }

    private fun patchActivityIntent(className: String, writeComeFrom: Boolean) {
        runCatching {
            val onCreate = findMethod(className, "onCreate", Bundle::class.java)
            hookBefore(onCreate) { param ->
                val activity = param.instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(activity, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.deviceExtra()
                if (!isFiilPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())
                intent.putExtra("DEVICE_ID", fakeDeviceId())
                if (writeComeFrom) {
                    intent.putExtra(
                        "COME_FROM",
                        intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS",
                    )
                }
                logI("patched $className intent for ${device?.address}")
            }
        }.onFailure { logW("hook $className skipped", it) }
    }

    private fun hookStringGetter(className: String, methodName: String, value: () -> String) {
        runCatching {
            val m = findMethodByParamCount(className, methodName, 0) ?: return
            hookAfter(m) { param ->
                val device = runCatching {
                    getObjectField(param.instance ?: return@hookAfter, "mDevice") as? BluetoothDevice
                }.getOrNull()
                if (!isFiilPod(device)) return@hookAfter
                param.setResult(value())
            }
        }.onFailure { logW("hook $className.$methodName skipped", it) }
    }

    // ------------------------------------------------------------------
    // 2. 静态判定表
    // ------------------------------------------------------------------

    private fun hookSupportChecks() {
        hookStringStatic(CLS_ID_CONSTANTS, "checkSupport") { it }
        hookStringStatic(CLS_ID_CONSTANTS, "isTWS01Headset") { it }
        hookStringStatic(CLS_ID_CONSTANTS, "isK77sHeadset") { false }
        hookBleMmaConnect(Context::class.java)
        runCatching { hookBleMmaConnect(findClass(CLS_SERVICE_IFACE)) }
            .onFailure { logW("isBleMmaConnect(service) skipped", it) }
    }

    /**
     * 所有「参数是一个设备 id 字符串」的静态判定统一走这里：
     * 只有值等于（或以之开头的）伪造 id 时才改写返回值，避免污染真实小米耳机。
     */
    private fun hookStringStatic(className: String, methodName: String, result: (String) -> Any) {
        runCatching {
            val m = findMethod(className, methodName, String::class.java)
            hookAfter(m) { param ->
                val value = param.args.getOrNull(0) as? String ?: return@hookAfter
                val id = fakeDeviceId()
                if (value != id && !value.startsWith(id)) return@hookAfter
                param.setResult(result(value))
            }
        }.onFailure { logW("hook $className.$methodName(String) skipped", it) }
    }

    private fun hookBleMmaConnect(serviceType: Class<*>) {
        val m = findMethod(
            CLS_ID_CONSTANTS,
            "isBleMmaConnect",
            Context::class.java,
            BluetoothDevice::class.java,
            String::class.java,
        )
        // 两个重载参数不同，这里按实际类型分别 hook
        runCatching {
            hookAfter(m) { param ->
                val device = param.args.getOrNull(1) as? BluetoothDevice
                val deviceId = param.args.getOrNull(2) as? String
                if (deviceId == fakeDeviceId() || isFiilPod(device)) {
                    param.setResult(true)
                }
            }
        }.onFailure { logW("isBleMmaConnect(Context) skipped", it) }

        if (serviceType == Context::class.java) return
        runCatching {
            val m2 = findMethod(
                CLS_ID_CONSTANTS,
                "isBleMmaConnect",
                serviceType,
                BluetoothDevice::class.java,
                String::class.java,
            )
            hookAfter(m2) { param ->
                val device = param.args.getOrNull(1) as? BluetoothDevice
                val deviceId = param.args.getOrNull(2) as? String
                if (deviceId == fakeDeviceId() || isFiilPod(device)) {
                    param.setResult(true)
                }
            }
        }.onFailure { logW("isBleMmaConnect(service) skipped", it) }
    }

    // ------------------------------------------------------------------
    // 3. 跨进程 MMA 服务代理
    // ------------------------------------------------------------------

    private fun hookServiceProxy() {
        hookProxyBefore(CLS_SERVICE_PROXY, "checkSupport", BluetoothDevice::class.java) { param ->
            val device = param.args.getOrNull(0) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            param.setResult(fakeSupport())
        }
        hookProxyBefore(CLS_SERVICE_PROXY, "getDeviceInfo", BluetoothDevice::class.java) { param ->
            val device = param.args.getOrNull(0) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            param.setResult(fakeSupport())
        }
        hookProxyBefore(CLS_SERVICE_PROXY, "isSupportAudioSwitch", BluetoothDevice::class.java) { param ->
            val device = param.args.getOrNull(0) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            param.setResult("1")
        }
        hookProxyBefore(
            CLS_SERVICE_PROXY,
            "setCommonCommand",
            Int::class.java,
            String::class.java,
            BluetoothDevice::class.java,
        ) { param ->
            val device = param.args.getOrNull(2) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            param.setResult(if (param.args.getOrNull(0) == 102) "0" else "1")
        }
        // 这些调用直接吞掉，返回 null 即可
        listOf(
            "connect" to arrayOf<Class<*>>(BluetoothDevice::class.java),
            "getDeviceConfig" to arrayOf<Class<*>>(BluetoothDevice::class.java),
            "getCommonConfig" to arrayOf<Class<*>>(BluetoothDevice::class.java, String::class.java),
        ).forEach { (name, types) ->
            hookProxyBefore(CLS_SERVICE_PROXY, name, *types) { param ->
                val device = param.args.filterIsInstance<BluetoothDevice>().firstOrNull()
                if (isFiilPod(device)) param.setResult(null)
            }
        }
        listOf("isMiTWS", "checkIsMiTWS").forEach { name ->
            hookProxyBefore(CLS_SERVICE_PROXY, name, String::class.java) { param ->
                val address = param.args.getOrNull(0) as? String
                if (isFiilAddress(address)) param.setResult(true)
            }
        }
        hookProxyBefore(CLS_SERVICE_PROXY, "getRingFindState", String::class.java) { param ->
            val address = param.args.getOrNull(0) as? String
            if (isFiilAddress(address)) param.setResult(false)
        }
        hookProxyBefore(
            CLS_SERVICE_PROXY,
            "changeAncMode",
            Int::class.java,
            BluetoothDevice::class.java,
        ) { param ->
            val device = param.args.getOrNull(1) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            val miMode = param.args.getOrNull(0) as? Int ?: return@hookProxyBefore
            applyAncFromSettingsMode(miMode)
            param.setResult(null)
        }
        hookProxyBefore(
            CLS_SERVICE_PROXY,
            "changeAncLevel",
            String::class.java,
            BluetoothDevice::class.java,
        ) { param ->
            val device = param.args.getOrNull(1) as? BluetoothDevice
            if (!isFiilPod(device)) return@hookProxyBefore
            val level = param.args.getOrNull(0) as? String ?: return@hookProxyBefore
            applyAncFromSettingsLevel(level)
            param.setResult(null)
        }
    }

    private fun hookProxyBefore(
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
        block: (HookParam) -> Unit,
    ) {
        runCatching {
            val m = findMethod(className, methodName, *paramTypes)
            hookBefore(m, block)
        }.onFailure { logW("hook proxy $methodName skipped", it) }
    }

    // ------------------------------------------------------------------
    // 4. 电量控件
    // ------------------------------------------------------------------

    private fun hookBatteryView() {
        runCatching {
            val ctor = findConstructorByParamCount(CLS_BATTERY, 4) ?: return
            hookConstructorAfter(ctor) { param ->
                val device = param.args.getOrNull(0) as? BluetoothDevice ?: return@hookConstructorAfter
                val ctx = param.args.getOrNull(1) as? Context
                registerStatusReceiver(ctx)
                if (!isFiilPod(device)) return@hookConstructorAfter
                val view = param.instance ?: return@hookConstructorAfter
                batteryViews[view] = device
                requestBluetoothStatus("battery-init")
                updateBatteryView(view)
            }
        }.onFailure { logW("hook MiuiHeadsetBattery ctor skipped", it) }

        runCatching {
            val m = findMethod(CLS_BATTERY, "onBatteryChanged", String::class.java)
            hookBefore(m) { param ->
                val device = batteryViews[param.instance ?: return@hookBefore]
                if (!isFiilPod(device)) return@hookBefore
                // 吞掉系统自己的电池字符串，改用我们的三路数值
                param.setResult(null)
                updateBatteryView(param.instance)
            }
        }.onFailure { logW("hook MiuiHeadsetBattery.onBatteryChanged skipped", it) }
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
        }
    }

    private fun updateBatteryView(view: Any?) {
        val v = settingsBatteryValues()
        callMethod(view, "onBatteryChanged", v[0], v[1], v[2])
    }

    // ------------------------------------------------------------------
    // 5. Fragment 状态机
    // ------------------------------------------------------------------

    private fun hookFragmentState() {
        runCatching {
            val m = findMethodByParamCount(CLS_FRAGMENT, "onCreateView", 3) ?: return
            hookAfter(m) { param ->
                val fragment = param.instance ?: return@hookAfter
                registerStatusReceiver(runCatching {
                    getObjectField(fragment, "mActivity") as? Context
                }.getOrNull())
                if (!isFiilFragment(fragment)) return@hookAfter
                headsetFragments[fragment] = true
                requestBluetoothStatus("fragment-create")
                startPeriodicRefresh()
                injectFragmentStatus(fragment)
                attachControlCard(fragment, param.result)
            }
        }.onFailure { logW("hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            val m = findMethodByParamCount(CLS_FRAGMENT, "onServiceConnected", 0) ?: return
            hookAfter(m) { param ->
                val fragment = param.instance ?: return@hookAfter
                if (!isFiilFragment(fragment)) return@hookAfter
                headsetFragments[fragment] = true
                requestBluetoothStatus("service-connected")
                startPeriodicRefresh()
                injectFragmentStatus(fragment)
                retryAttachControlCard(fragment)
            }
        }.onFailure { logW("hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        // 宿主连不上 MMA 时会走这两个失败分支，我们把它吞掉并塞入自己的数据
        runCatching {
            val m = findMethod(CLS_FRAGMENT, "refreshStatus", String::class.java, String::class.java)
            hookBefore(m) { param ->
                val key = param.args.getOrNull(0) as? String
                val fragment = param.instance ?: return@hookBefore
                if (isFiilFragment(fragment) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    injectFragmentStatus(fragment)
                    param.setResult(null)
                }
            }
        }.onFailure { logW("hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            val m = findMethod(CLS_FRAGMENT, "handleConnectMmaFailed", String::class.java)
            hookBefore(m) { param ->
                val fragment = param.instance ?: return@hookBefore
                if (isFiilFragment(fragment)) {
                    injectFragmentStatus(fragment)
                    param.setResult(null)
                }
            }
        }.onFailure { logW("hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!)
    }

    private fun hookFragmentAncCommand(methodName: String, vararg paramTypes: Class<*>) {
        runCatching {
            val m = findMethod(CLS_FRAGMENT, methodName, *paramTypes)
            hookBefore(m) { param ->
                val fragment = param.instance ?: return@hookBefore
                if (!isFiilFragment(fragment)) return@hookBefore
                // 第二个参数 false = 只是回显 UI，不能真的下发，否则回声
                val updateDevice = param.args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val handled = when (methodName) {
                    "updateAncMode" -> {
                        applyAncFromSettingsMode(param.args.getOrNull(0) as? Int ?: -1); true
                    }
                    "updateAncLevel" -> {
                        applyAncFromSettingsLevel(param.args.getOrNull(0) as? String ?: ""); true
                    }
                    else -> false
                }
                if (!handled) return@hookBefore
                callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
                injectFragmentStatus(fragment)
                param.setResult(null)
            }
        }.onFailure { logW("hook MiuiHeadsetFragment.$methodName skipped", it) }
    }

    // ------------------------------------------------------------------
    // 5.4 控制卡片：把 FIIL 私有能力（影院 / 低延迟 / 多点 / EQ）挂进详情页
    // ------------------------------------------------------------------

    /**
     * 目标第三阶段 1「系统蓝牙详情页注入控制卡片」的落地。
     *
     * 宿主页面由 MIUI 的 MMA 协议驱动，只认电量与 ANC，**不认识**
     * 影院模式 / 低延迟三档 / 多点连接 / 自定义 EQ —— 这些是 FIIL 私有能力。
     * 所以除了把宿主的电量与 ANC 喂成我们的值，还要额外挂一张我们自己的卡片。
     *
     * 控件全部用 framework 类（见 [SettingsControlCard]），因为这段代码跑在
     * `com.android.settings` 进程里，无法加载模块的 Compose/MIUIX。
     */
    private fun attachControlCard(fragment: Any, rootView: Any?) {
        attachControlCardTo(fragment, rootView as? android.view.View)
    }

    /**
     * 卡片挂载的公共入口，允许 `rootView` 为空。
     *
     * 两处调用点拿到的 View  Timing 不一样：
     *  · `onCreateView` 的返回值是**刚 inflate 完**的树，宿主的 `LinearLayout` 里可能
     *    还一个子 View 都没有 —— 这时 [SettingsControlCard.attach] 找不到可插入的
     *    容器，返回 null。
     *  · `onServiceConnected` 时宿主已经把内容填好了，`fragment.getView()` 能拿到
     *    完整树。
     * 所以两处都调，`attach` 自己按 id 幂等，重复调用只会命中「已存在 → 返回 null」。
     */
    private fun attachControlCardTo(fragment: Any, view: android.view.View?) {
        if (view == null) return
        runCatching {
            val card = SettingsControlCard.attach(
                rootFragmentView = view,
                dispatch = { cmd, value, flag -> dispatchCardCommand(cmd, value, flag) },
                openModule = { launchModuleUi() },
            )
            if (card != null) {
                controlCards[fragment] = card
                logI("control card attached")
            }
            refreshControlCard(fragment)
        }.onFailure { logW("attach control card failed", it) }
    }

    /** `onServiceConnected` 补挂：此时 `fragment.getView()` 才是完整页面。 */
    private fun retryAttachControlCard(fragment: Any) {
        if (controlCards[fragment] != null) return
        val view = runCatching { callMethod(fragment, "getView") }.getOrNull()
        attachControlCardTo(fragment, view as? android.view.View)
    }

    /** 把最新状态灌进卡片（没有卡片时静默跳过）。 */
    private fun refreshControlCard(fragment: Any?) {
        val card = controlCards[fragment ?: return] ?: return
        runCatching { SettingsControlCard.bind(card, currentStatus) }
            .onFailure { logW("bind control card failed", it) }
    }

    private fun refreshControlCards() {
        controlCards.keys.toList().forEach { refreshControlCard(it) }
    }

    /**
     * 卡片上的操作 → 蓝牙进程。
     *
     * 走的是和模块 UI 完全相同的 [FiilAction.ACTION_COMMAND] 通道，
     * 所以协议细节与失败回退逻辑只需在蓝牙进程里实现一份。
     */
    private fun dispatchCardCommand(cmd: String, value: Int?, flag: Boolean?) {
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(FiilAction.ACTION_COMMAND).apply {
                    putExtra(FiilAction.EXTRA_CMD, cmd)
                    value?.let { putExtra(FiilAction.EXTRA_VALUE, it) }
                    flag?.let { putExtra(FiilAction.EXTRA_FLAG, it) }
                    setPackage("com.android.bluetooth")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
            logD("card command: $cmd value=$value flag=$flag")
        }.onFailure { logW("dispatch card command failed", it) }
    }

    // ------------------------------------------------------------------
    // 5.5 设置集成：蓝牙详情页「更多」菜单 → 模块完整控制页
    // ------------------------------------------------------------------

    /**
     * 目标第三阶段第 5 条「设置集成：系统蓝牙设置中可进入模块的完整控制页面」。
     *
     * 做法：往宿主页面的**溢出菜单**（ActionBar 右上角「⋮」）里加一项，
     * 点它跳模块自己的 [MainActivity] 完整控制台。
     *
     * 为什么用菜单而不是塞 View：
     *  · `android.view.Menu` / `MenuItem` 都是 framework 类，宿主进程里直接可用；
     *  · 标题用字面量字符串，**不需要**模块 Resources（跨 ClassLoader 拿不到 R.string）；
     *  · 不动宿主的布局树，HyperOS 改版只会影响「菜单还在不在」，不会崩。
     *
     * 宿主页面不一定是 Activity 自己创建菜单（Settings 的详情页多以
     * `SettingsActivity` + Fragment 组合承载），所以 Activity 与 Fragment 两处都挂，
     * 命中哪个都行，未命中只是少个入口，不影响其它 Hook。
     */
    private fun hookModuleEntry() {
        // Fragment 版：onCreateOptionsMenu(Menu, MenuInflater)
        runCatching {
            val cls = findClass(CLS_FRAGMENT)
            cls.declaredMethods
                .filter { it.name == "onCreateOptionsMenu" && it.parameterTypes.size == 2 }
                .forEach { m ->
                    m.isAccessible = true
                    hookAfter(m) { param ->
                        val fragment = param.instance ?: return@hookAfter
                        if (!isFiilFragment(fragment)) return@hookAfter
                        addModuleMenuItem(param.args.getOrNull(0))
                    }
                    logI("module menu entry hooked on $CLS_FRAGMENT.on${m.name}")
                }
        }.onFailure { logW("hook fragment menu skipped", it) }

        // Activity 版：宿主自己可能没覆写 onCreateOptionsMenu（详情页由 Fragment 承载），
        // 所以既试宿主的两个 Activity 类，也兜底挂 framework 的 Activity#onCreateOptionsMenu，
        // 但必须严格判定「当前这个 Activity 就是耳机详情页」，否则会在整个设置里到处长菜单项。
        val activityClasses = listOf(CLS_ACTIVITY, CLS_ACTIVITY_PLUGIN)
        activityClasses.forEach { className ->
            runCatching {
                findClass(className).declaredMethods
                    .filter { it.name == "onCreateOptionsMenu" && it.parameterTypes.size == 1 }
                    .forEach { m ->
                        m.isAccessible = true
                        hookAfter(m) { param ->
                            if (currentAddress == null) return@hookAfter
                            addModuleMenuItem(param.args.getOrNull(0))
                        }
                        logI("module menu entry hooked on $className.on${m.name}")
                    }
            }.onFailure { logW("hook $className menu skipped", it) }
        }

        // framework 兜底：只在「这个 Activity 确实是我们注入的耳机详情页」时生效。
        runCatching {
            val m = findMethod("android.app.Activity", "onCreateOptionsMenu", android.view.Menu::class.java)
            hookAfter(m) { param ->
                if (currentAddress == null) return@hookAfter
                val activity = param.instance ?: return@hookAfter
                if (!isHeadsetDetailActivity(activity)) return@hookAfter
                addModuleMenuItem(param.args.getOrNull(0))
            }
            logI("module menu entry hooked on framework Activity")
        }.onFailure { logW("hook framework activity menu skipped", it) }

        // 点击：Activity 与 Fragment 的 onOptionsItemSelected(MenuItem) / onMenuItemSelected(int, MenuItem)
        listOf(CLS_ACTIVITY, CLS_FRAGMENT).forEach { className ->
            runCatching {
                val cls = findClass(className)
                cls.declaredMethods
                    .filter {
                        (it.name == "onOptionsItemSelected" && it.parameterTypes.size == 1) ||
                            (it.name == "onMenuItemSelected" && it.parameterTypes.size == 2)
                    }
                    .forEach { m ->
                        m.isAccessible = true
                        hookBefore(m) { param ->
                            val item = param.args.lastOrNull() as? android.view.MenuItem
                                ?: return@hookBefore
                            if (item.itemId != MODULE_MENU_ID) return@hookBefore
                            launchModuleUi()
                            param.setResult(true)
                        }
                    }
            }.onFailure { logW("hook $className menu click skipped", it) }
        }

        // framework 兜底：宿主没覆写 onOptionsItemSelected 时，点击会落到 Activity 基类。
        // 这里只认我们自己的菜单 id，所以不需要额外的页面判定 —— 别的页面根本没有这个 id。
        runCatching {
            val m = findMethod("android.app.Activity", "onOptionsItemSelected", android.view.MenuItem::class.java)
            hookBefore(m) { param ->
                val item = param.args.getOrNull(0) as? android.view.MenuItem ?: return@hookBefore
                if (item.itemId != MODULE_MENU_ID) return@hookBefore
                launchModuleUi()
                param.setResult(true)
            }
            logI("module menu click hooked on framework Activity")
        }.onFailure { logW("hook framework activity menu click skipped", it) }
    }

    /** 把「FiilPods 高级设置」加进宿主的溢出菜单（幂等）；返回是否真的加进去了。 */
    private fun addModuleMenuItem(menu: Any?): Boolean {
        val m = menu as? android.view.Menu ?: return false
        if (m.findItem(MODULE_MENU_ID) != null) return true
        return runCatching {
            m.add(android.view.Menu.NONE, MODULE_MENU_ID, android.view.Menu.NONE, MENU_TITLE)
            logD("module menu item added")
            true
        }.getOrElse {
            logW("add module menu item failed", it)
            false
        }
    }

    /** 跳模块自己的完整控制台。 */
    private fun launchModuleUi() {
        val ctx = context ?: return
        runCatching {
            ctx.startActivity(
                Intent().apply {
                    setClassName(FiilConfig.MODULE_PACKAGE, CLS_MODULE_MAIN)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(FiilAction.EXTRA_ADDRESS, currentAddress)
                    putExtra(FiilAction.EXTRA_DEVICE_NAME, currentName)
                },
            )
            logI("module ui launched from settings")
        }.onFailure { logW("launch module ui failed", it) }
    }

    // ------------------------------------------------------------------
    // 6. 数据回填
    // ------------------------------------------------------------------

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            val payload = "${settingsAncMode()}|$LEVELS_ALL|${settingsBatteryString()}|00"
            callMethod(fragment, "updateAtUiInfo", payload)
            callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
            val address = runCatching {
                (getObjectField(fragment ?: return, "mDevice") as? BluetoothDevice)?.address
            }.getOrNull()
            if (address != null) {
                callMethod(fragment, "refreshStatus", address, settingsRefreshPayload())
            }
        }.onFailure { logW("inject fragment status failed", it) }
    }

    /** MIUI 的 ANC 三段式：0=关 / 1=降噪 / 2=通透。 */
    private fun settingsAncMode(): String = when (currentAnc) {
        1 -> "1"
        2 -> "2"
        else -> "0"
    }

    private fun settingsAncLevel(): String = when (currentAnc) {
        1 -> LEVEL_DEEP
        2 -> LEVEL_TRANSPARENCY
        3 -> LEVEL_TRANSPARENCY
        4 -> LEVEL_TRANSPARENCY
        0 -> LEVEL_OFF
        else -> LEVEL_OFF
    }

    private fun settingsBatteryString(): String = settingsBatteryValues().joinToString(",")

    private fun settingsBatteryValues(): List<Int> {
        loadState()
        return currentBattery.values()
    }

    /**
     * 16 段刷新载荷。宿主用逗号分隔解析，位置敏感：
     * [0]=左 [1]=右 [2]=盒 [7]=ANC level [8]=是否已连接 [11]/[13]/[14]=保留位
     */
    private fun settingsRefreshPayload(): String {
        val b = settingsBatteryString().split(",")
        val values = MutableList(16) { "" }
        values[0] = b.getOrNull(0).orEmpty()
        values[1] = b.getOrNull(1).orEmpty()
        values[2] = b.getOrNull(2).orEmpty()
        values[7] = settingsAncLevel()
        values[8] = if (currentAddress != null) "true" else "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    // ------------------------------------------------------------------
    // 7. ANC 反向映射（设置页 → 耳机）
    // ------------------------------------------------------------------

    private fun applyAncFromSettingsMode(miMode: Int) {
        val mode = when (miMode) {
            1 -> 1
            2 -> 2
            else -> 0
        }
        setAnc(mode)
    }

    private fun applyAncFromSettingsLevel(level: String) {
        val mode = when {
            level.startsWith("0103") -> 1
            level.startsWith("0102") -> 1
            level.startsWith("0101") -> 1
            level.startsWith("0100") -> 1
            level.startsWith("02") -> 2
            else -> 0
        }
        setAnc(mode)
    }

    private fun setAnc(mode: Int) {
        currentAnc = mode
        val ctx = context ?: return
        ctx.sendBroadcast(
            Intent(FiilAction.ACTION_ANC_SELECT).apply {
                putExtra(FiilAction.EXTRA_STATUS, mode)
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
        saveState(ctx)
        updateFragments()
    }

    // ------------------------------------------------------------------
    // 8. 广播与状态
    // ------------------------------------------------------------------

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        loadState()
        val filter = IntentFilter().apply {
            addAction(FiilAction.ACTION_PODS_CONNECTED)
            addAction(FiilAction.ACTION_PODS_DISCONNECTED)
            addAction(FiilAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(FiilAction.ACTION_PODS_ANC_CHANGED)
            addAction(FiilAction.ACTION_STATE)
            addAction(FiilAction.ACTION_CONFIG_CHANGED)
        }
        runCatching {
            context?.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        FiilAction.ACTION_PODS_CONNECTED -> {
                            currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                            currentName = intent.getStringExtra(FiilAction.EXTRA_DEVICE_NAME) ?: currentName
                            currentAddress?.let { knownAddresses.add(it.uppercase()) }
                        }
                        FiilAction.ACTION_PODS_DISCONNECTED -> {
                            currentAddress = null
                            currentBattery = BatterySnapshot()
                            // 断开后卡片要立刻变成「未连接」，否则上面还挂着上一次的电量
                            currentStatus = currentStatus?.copy(connected = false)
                            refreshControlCards()
                        }
                        FiilAction.ACTION_PODS_BATTERY_CHANGED -> {
                            currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                            BatterySnapshot.fromExtras(intent)?.let { currentBattery = it }
                            currentAddress?.let { knownAddresses.add(it.uppercase()) }
                            saveState(context)
                            updateBatteryViews()
                            updateFragments()
                        }
                        FiilAction.ACTION_PODS_ANC_CHANGED -> {
                            currentAddress = intent.getStringExtra(FiilAction.EXTRA_ADDRESS) ?: currentAddress
                            currentAnc = intent.getIntExtra(FiilAction.EXTRA_STATUS, currentAnc)
                            currentAddress?.let { knownAddresses.add(it.uppercase()) }
                            saveState(context)
                            updateFragments()
                        }
                        FiilAction.ACTION_STATE -> {
                            val status = FiilStatus.fromJson(intent.getStringExtra(FiilAction.EXTRA_JSON))
                            currentStatus = status
                            currentAddress = status.address
                            currentName = status.name
                            currentEarType = status.earType
                            currentAnc = status.ancMode
                            currentBattery = BatterySnapshot.fromStatus(status)
                            status.address?.let { knownAddresses.add(it.uppercase()) }
                            saveState(context)
                            updateBatteryViews()
                            updateFragments()
                            refreshControlCards()
                        }
                        FiilAction.ACTION_CONFIG_CHANGED -> updateFragments()
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            requestBluetoothStatus("receiver-register")
        }.onFailure { logW("register status receiver failed", it) }
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        listOf(FiilAction.ACTION_PODS_UI_INIT, FiilAction.ACTION_REFRESH_STATUS).forEach { action ->
            runCatching {
                ctx.sendBroadcast(
                    Intent(action).apply {
                        setPackage("com.android.bluetooth")
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                )
            }
        }
        logD("requested status: $reason")
    }

    private fun startPeriodicRefresh() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, FiilConfig.SETTINGS_REFRESH_INTERVAL_MS)
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isFiilFragment(fragment)) injectFragmentStatus(fragment)
        }
        refreshControlCards()
    }

    // ------------------------------------------------------------------
    // 9. 设备识别
    // ------------------------------------------------------------------

    private fun isFiilPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isFiilAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        // 名字可能被用户改成任意值 → 用 FIIL 自己的 MAC 前缀兜底
        // （FIIL+ OTA 页的 `startsWith("B0:F1:A3")` 判定）。
        if (address != null && FiilConfig.isFiilAddress(address)) {
            knownAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
            return true
        }
        if (!FiilConfig.isFiilName(name)) return false
        if (address != null) {
            knownAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return true
    }

    private fun isFiilAddress(address: String?): Boolean {
        if (address == null) return false
        val n = address.uppercase()
        return n == currentAddress?.uppercase() || n in knownAddresses
    }

    /**
     * 判断某个 Activity 是不是被我们伪造出来的耳机详情页。
     *
     * 只有它为真时才往 framework `Activity#onCreateOptionsMenu` 里加菜单项 ——
     * 否则整个设置 App 的每一页都会多出「FiilPods 高级设置」，
     * 那是明显的越权注入。
     */
    private fun isHeadsetDetailActivity(activity: Any): Boolean {
        val name = activity.javaClass.name
        if (name == CLS_ACTIVITY || name == CLS_ACTIVITY_PLUGIN) return true
        // 宿主可能用子类承载，那就看 Intent 里有没有我们的伪造设备 id
        val intent = runCatching {
            callMethod(activity, "getIntent") as? Intent
        }.getOrNull() ?: return false
        val deviceId = runCatching {
            intent.getStringExtra("MIUI_HEADSET_SUPPORT")
                ?: intent.getStringExtra("miui_headset_support")
        }.getOrNull()
        return deviceId == fakeSupport() || deviceId?.startsWith(fakeDeviceId()) == true
    }

    /** Fragment 复用后 mDevice 可能为 null，只能靠伪造的 id 认。 */
    private fun isFiilFragment(fragment: Any?): Boolean {
        if (fragment == null) return false
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        if (isFiilPod(device)) return true
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        return deviceId == fakeDeviceId() || support?.startsWith(fakeDeviceId()) == true
    }

    @Suppress("DEPRECATION")
    private fun Intent.deviceExtra(): BluetoothDevice? =
        runCatching { getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java) }
            .getOrNull()
            ?: runCatching { getParcelableExtra<BluetoothDevice>("android.bluetooth.device.extra.DEVICE") }
                .getOrNull()

    // ------------------------------------------------------------------
    // 10. 持久化（设置页可能是冷启动直接进来的）
    // ------------------------------------------------------------------

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("ear_type", currentEarType)
            .putInt("anc", currentAnc)
            .putInt("left_battery", currentBattery.left?.battery ?: -1)
            .putBoolean("left_connected", currentBattery.left?.connected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: -1)
            .putBoolean("right_connected", currentBattery.right?.connected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: -1)
            .putBoolean("case_connected", currentBattery.case?.connected == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        if (currentBattery.hasAny()) return
        val savedAddress = prefs.getString("address", null) ?: return
        currentAddress = savedAddress
        currentName = prefs.getString("name", currentName)
        currentEarType = prefs.getInt("ear_type", currentEarType)
        currentAnc = prefs.getInt("anc", currentAnc)
        knownAddresses.add(savedAddress.uppercase())
        currentBattery = BatterySnapshot(
            left = PodBatteryFromPrefs(prefs, "left"),
            right = PodBatteryFromPrefs(prefs, "right"),
            case = PodBatteryFromPrefs(prefs, "case"),
        )
    }

    private fun PodBatteryFromPrefs(
        prefs: android.content.SharedPreferences,
        key: String,
    ): moe.chenxy.fiilpods.pods.PodBattery = moe.chenxy.fiilpods.pods.PodBattery(
        battery = prefs.getInt("${key}_battery", -1),
        charging = false,
        connected = prefs.getBoolean("${key}_connected", false),
    )

    @Suppress("unused")
    private fun modelNameOf(earType: Int): String = FiilModels.nameOf(earType)
}
