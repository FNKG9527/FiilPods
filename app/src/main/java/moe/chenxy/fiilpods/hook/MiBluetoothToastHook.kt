package moe.chenxy.fiilpods.hook

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import com.xzakota.hyper.notification.focus.FocusNotification
import kotlinx.serialization.json.Json
import moe.chenxy.fiilpods.R
import moe.chenxy.fiilpods.utils.PodImageLoader
import moe.chenxy.fiilpods.utils.SystemApisUtils
import moe.chenxy.fiilpods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.fiilpods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.fiilpods.pods.BatterySnapshot
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilStatus
import moe.chenxy.fiilpods.pods.IconParams
import moe.chenxy.fiilpods.pods.PodBattery
import moe.chenxy.fiilpods.pods.StringToastBean
import moe.chenxy.fiilpods.pods.StringToastBundle
import moe.chenxy.fiilpods.pods.StrongToastCategory
import moe.chenxy.fiilpods.pods.TextParams
import moe.chenxy.fiilpods.pods.ToastIconCategory
import moe.chenxy.fiilpods.pods.ToastIconFormat
import moe.chenxy.fiilpods.pods.ToastSide
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.PodParams

/**
 * 注入 com.xiaomi.bluetooth。
 *
 * HyperOS 的状态栏只认「小米蓝牙」这个 uid 发来的强提示与蓝牙通知，
 * 所以真正调用 StatusBarManager#setStatus 的地方必须落在这个进程里。
 *
 * 做三件事：
 *  1. 注册广播接收器，接收蓝牙进程（com.android.bluetooth）发来的电量变化
 *  2. 发 HyperOS 强提示（超级岛）：左耳电量 + 右耳电量
 *  3. 维护一条常驻蓝牙通知（下拉通知栏可见三路电量）
 */
@SuppressLint("MissingPermission", "WrongConstant")
class MiBluetoothToastHook(entry: HookEntry, classLoader: ClassLoader) :
    HookContext(entry, classLoader) {

    companion object {
        private const val NOTIFICATION_ID = 10003
        private const val LOW_BATTERY = 20

        /** 超级岛的去重 id：同一个 id 重复下发只更新内容，不会在岛上叠出多条。 */
        private const val NOTIFY_ID_STRONG = "fiil_pods_battery"

        /** 通知点击目标：模块的半透明弹层。 */
        private const val POPUP_ACTIVITY = "moe.chenxy.fiilpods.PopupActivity"

        /**
         * 焦点通知渠道前缀。
         *
         * HyperOS 的「蓝牙耳机卡片」是系统设置 / 系统蓝牙按**每台设备一个渠道**
         * 渲染的，渠道 id 固定约定为 `BTHeadset<MAC>`（见
         * `com.android.settings.bluetooth.MiuiHeadsetFragment` 与
         * `com.android.bluetooth.ble.app.MiuiBluetoothNotification`）。
         * 用模块自己的 `fiilpods_battery*` 渠道发出来的只是一条**普通通知**，
         * 系统不会把它归到耳机卡片里，也不会当成蓝牙通知接管 ——
         * 这就是「设置页开了焦点通知，通知栏里没有耳机控制卡片」的根因。
         */
        private const val CHANNEL_PREFIX = "BTHeadset"

        /**
         * 落盘「最后一次通知 tag」用的私有 prefs / key。
         *
         * 只在本 hook 进程（com.xiaomi.bluetooth）内使用，与模块的 remote prefs 无关 ——
         * 后者是模块设置的单向镜像，不该往里写运行时状态。
         */
        private const val NOTIFICATION_PREFS = "fiilpods_notification_state"
        private const val KEY_NOTIFICATION_TAG = "last_tag"

        /** 焦点通知里「断开」按钮的广播 action（与系统蓝牙原生一致）。 */
        private const val ACTION_HEADSET_DISCONNECT = "com.android.bluetooth.headset.notification"

        /** 焦点通知被划掉时通知系统的 action（拼写沿用 AOSP：`cancle`）。 */
        private const val ACTION_HEADSET_CANCEL = "com.android.bluetooth.headset.notification.cancle"

        /** 焦点通知里携带 BluetoothDevice 的 extra key（系统侧约定）。 */
        private const val EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE"

        /** 通知 Action 的图标 id，与原生耳机通知保持一致。 */
        private const val ICON_DISCONNECT = 285737079

        /** 小米蓝牙里那个「耳机状态 + 通知」融合类（HyperOS 4 仍在）。 */
        private const val CLS_MIUI_BLUETOOTH_NOTIFICATION =
            "com.android.bluetooth.ble.app.MiuiBluetoothNotification"

        /**
         * 耳机服务真实的宿主类。
         *
         * HyperOS 4 里 `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService`
         * 由 `com.android.bluetooth` 通过 `bindService` 拉起的独立进程（`com.xiaomi.bluetooth`）
         * 承载，manifest 里 action = `miui.bluetooth.mible.BluetoothHeadsetService`。
         * 它的 `onCreate` 会经 `W1()` → `q2()` 走到 `new MiuiBluetoothNotification(...)`。
         */
        private const val CLS_BLUETOOTH_HEADSET_SERVICE =
            "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
    }

    private var appContext: Context? = null
    private var receiverRegistered = false
    private var lastToastAt = 0L

    /**
     * 落盘诊断日志。
     *
     * 为什么必须有：这台机器上 `logcat` 的 `main` 环形缓冲区只有 64 KiB，
     * 而且长期处于「已满、0 B readable」状态，事后抓取拿不到任何东西；
     * LSPosed 的 `verbose.log` 也不记录模块内部的 `Log.i`。
     * 所以凡是「hook 到底装没装 / 接收器到底注册没注册」这类必须先确认的事实，
     * 一律同时写进 `getExternalFilesDir` 下的文件，方便用户直接导出来定位。
     */
    private var logFile: java.io.File? = null
    private var logLines = 0

    private fun appendLog(msg: String) {
        logI(msg)
        runCatching {
            val f = logFile ?: run {
                val ctx = appContext ?: return@runCatching
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                java.io.File(dir, "bluetooth_notification_debug.log")
                    .also { logFile = it }
            }
            // 只保留最近 400 行，避免无限增长。
            if (logLines > 400) {
                runCatching { f.writeText("") }
                logLines = 0
            }
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts  $msg\n")
            logLines++
        }
    }

    /**
     * 最近一次电量广播里的设备 MAC。
     *
     * 焦点通知的渠道 id 是 `BTHeadset<MAC>`，断开时要用同一个 id 才能把卡片撤干净，
     * 而断开广播里没有电量 snapshot，所以这里单独记一份地址。
     *
     * **注意**：这个字段只在进程内存里。进程被回收后重启、还没收到过电量广播时它是空的，
     * 这时若耳机断开就会 cancel 一个不存在的 tag，旧卡片（常驻、用户划不掉）永久残留。
     * 所以真正用于 cancel 的 tag 由 [rememberNotificationTag] 落盘、[restoredNotificationTag] 读回。
     */
    private var lastAddress: String = ""

    /** 内存缓存：最后一次成功发出的通知 tag（渠道 id）。 */
    private var lastNotificationTag: String = ""

    /**
     * 最近一次完整状态。超级岛要显示「电量 + 当前 MAF 模式」，
     * 但电量广播里没有模式，模式广播里没有电量，所以两边都要留一份。
     */
    private var lastStatus: FiilStatus? = null

    override fun onHook() {
        // 早期版本在这里直接 `if (!focusNotification()) return`（照抄 OppoPods 的门控）。
        // 但那样会有一个真实缺陷：用户在设置页里先关后开「焦点通知」时，
        // xiaomi.bluetooth 进程是在「关闭」状态下启动的 —— 接收器压根没挂，
        // 再打开开关也不会生效，必须等蓝牙进程重启。用户 m26286 的
        // 「开了焦点通知但通知栏里没有」就踩在这条路径上。
        // 因此改成：**总是挂接收器**，把开关判断下沉到真正要显示的那一刻
        // （`onBatteryChanged` → `showBatteryToast` / `updateNotification` / `showModuleFocusIsland`）。
        if (!runCatching { moe.chenxy.fiilpods.config.ConfigManager.focusNotification() }.getOrDefault(true)) {
            logI("focus notification currently disabled; receiver still mounted so re-enabling takes effect immediately")
        }

        // ── 通道 1（主）：Application.attach ────────────────────────────────
        // 这是唯一「必然发生且最早」的时机：`com.xiaomi.bluetooth` 是被
        // `com.android.bluetooth` 通过 `bindService` 拉起的独立进程，它的
        // `Application` 类在 manifest 里声明为 `com.android.bluetooth.ble.MiuiApplication`，
        // 进程一起来就会走 `Application#attach(Context)`。
        //
        // 为什么必须换成这条通道（对照 HuaweiPods 的注释与本次逆向）：
        // 旧的构造器 hook 命中的是 `MiuiBluetoothNotification(Context, Looper)`
        // 这个重载，而它**只在** `MiuiApplication` 的一条被门控的分支里被 new：
        //   `MiuiApplication.smali:1507-1511  isMemoryTrimEnabled() == 0 → 直接跳过`
        // 而 `isMemoryTrimEnabled()` 读的是 `Settings.Global.getInt(cr, "mibt_memory_trim", 0)`，
        // 真机上该键**不存在**（`settings get global mibt_memory_trim` → null），
        // 于是恒等于 0 → 这条分支永远不执行 → 构造器 hook 永远不被触发 →
        // 接收器从未注册 → 电量广播没人收 → 通知栏里始终没有那张卡片。
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            hookAfter(attach) { param ->
                val app = param.instance as? Application ?: return@hookAfter
                doRegister("Application.attach", app, app)
            }
        }.onFailure {
            logW("hook Application.attach skipped", it)
            appendLog("hook Application.attach SKIPPED: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 通道 2：BluetoothHeadsetService.onCreate ───────────────────────
        // 真正的「耳机服务」入口。本次逆向确认：
        //   `BluetoothHeadsetService.smali:19152`（onCreate 内）→ `W1()`（:6453）
        //   → `new BluetoothHeadsetService$$ExternalSyntheticLambda3`
        //   → `Lj0/a;->b(Runnable)` 投递到子线程 → `q2()`（:11085）
        //   → `:11305 :cond_3 new MiuiBluetoothNotification`（用 `(Looper, BluetoothHeadsetService)` 那个构造器）。
        // 也就是说通知对象是在这个 Service 起来之后异步创建的。
        // 这条通道本身不依赖任何 Settings 开关（`if-eqz v4, :cond_3` 的跳转方向
        // 说明 isMemoryTrimEnabled()==0 时**反而**落到创建分支），所以它一定会建。
        runCatching {
            val m = findMethodByParamCount(CLS_BLUETOOTH_HEADSET_SERVICE, "onCreate", 0)
                ?: return@runCatching
            hookAfter(m) { param ->
                val svc = param.instance as? Context ?: return@hookAfter
                doRegister("BluetoothHeadsetService.onCreate", svc, svc.applicationContext ?: svc)
            }
        }.onFailure {
            logW("hook BluetoothHeadsetService skipped", it)
            appendLog("hook BluetoothHeadsetService SKIPPED: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 通道 3：两个构造器（补挂 appContext，不依赖通道 1/2 的时序）──
        // 旧实现用 `findConstructorByParamCount(名, 2)` 取「第一个」2 参构造器，
        // 拿到的只是 `(Context, Looper)`。而真机实际走的是
        // `(Looper, BluetoothHeadsetService)`。两个都挂，避免再次押错重载。
        runCatching {
            val cls = findClass(CLS_MIUI_BLUETOOTH_NOTIFICATION)
            cls.declaredConstructors.forEach { ctor ->
                @Suppress("UNCHECKED_CAST")
                val signature = ctor.parameterTypes.joinToString(",") { it.simpleName }
                hookConstructorAfter(ctor) { param ->
                    val ctx = getObjectField(param.instance, "mContext") as? Context
                        ?: return@hookConstructorAfter
                    doRegister("ctor($signature)", ctx, ctx.applicationContext ?: ctx)
                }
            }
        }.onFailure {
            logW("hook MiuiBluetoothNotification ctors skipped", it)
            appendLog("hook MiuiBluetoothNotification ctors SKIPPED: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 幂等注册：第一条通道成功后就 `receiverRegistered = true`，
     * 后面的通道进来只会补 `appContext`（因为 `appContext` 可能被后续
     * 更「正确」的 Context 覆盖 —— Service 的 `applicationContext` 比
     * Service 自身更适合长期持有）。
     */
    private fun doRegister(channel: String, raw: Context, app: Context) {
        if (appContext == null || appContext !== raw) {
            appContext = app
        }
        appendLog("channel=$channel registered=${receiverRegistered}")
        registerReceiver(app)
    }

    // ------------------------------------------------------------------
    // 广播
    // ------------------------------------------------------------------

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(FiilAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(FiilAction.ACTION_PODS_CONNECTED)
            addAction(FiilAction.ACTION_PODS_DISCONNECTED)
            addAction(FiilAction.ACTION_PODS_ANC_CHANGED)
            addAction(FiilAction.ACTION_STATE)
        }
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        FiilAction.ACTION_PODS_BATTERY_CHANGED -> {
                            // 进入该分支说明 intent?.action 非空，Kotlin 已智能转换成非空。
                            val snapshot = BatterySnapshot.fromExtras(intent) ?: return
                            val name = intent.getStringExtra(FiilAction.EXTRA_DEVICE_NAME) ?: "FIIL"
                            val address = intent.getStringExtra(FiilAction.EXTRA_ADDRESS).orEmpty()
                            appendLog(
                                "battery rx name=$name addr=${address.take(8)}… " +
                                    "L=${snapshot.left?.battery} R=${snapshot.right?.battery} " +
                                    "C=${snapshot.case?.battery}",
                            )
                            onBatteryChanged(c ?: appContext, snapshot, name, address)
                        }
                        FiilAction.ACTION_PODS_CONNECTED -> {
                            val name = intent.getStringExtra(FiilAction.EXTRA_DEVICE_NAME) ?: "FIIL"
                            showToast(c ?: appContext, "已连接 $name", Color.parseColor("#4CAF50"))
                        }
                        FiilAction.ACTION_PODS_DISCONNECTED -> {
                            lastStatus = null
                            cancelNotification(c ?: appContext)
                            // 目标第五阶段：断开后必须清掉超级岛，否则岛上会留一条
                            // 已断开的耳机电量，直到 duration 到期才消失。
                            hideStrongToast(c ?: appContext)
                        }
                        FiilAction.ACTION_PODS_ANC_CHANGED -> {
                            // MAF 变化：只记模式，电量沿用上次（超级岛要两者同时显示）。
                            val mode = intent.getIntExtra(FiilAction.EXTRA_STATUS, -1)
                            val prev = lastStatus
                            if (prev != null) {
                                lastStatus = prev.copy(ancMode = mode)
                            } else {
                                lastStatus = FiilStatus.EMPTY.copy(
                                    connected = true,
                                    ancMode = mode,
                                    earType = -1,
                                )
                            }
                        }
                        FiilAction.ACTION_STATE -> {
                            // 完整状态快照：这里是唯一能同时拿到电量 + 模式 + earType 的地方。
                            intent.getStringExtra(FiilAction.EXTRA_JSON)
                                ?.let { lastStatus = FiilStatus.fromJson(it) }
                        }
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            appendLog("receiver registered in xiaomi.bluetooth")
        }.onFailure {
            logW("register receiver failed", it)
            appendLog("register receiver FAILED: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun onBatteryChanged(ctx: Context?, snapshot: BatterySnapshot, name: String, address: String) {
        if (ctx == null) return
        // 「焦点通知」总开关在这里生效：关掉时既不弹提示、也不刷常驻通知，
        // 还要把已经显示的常驻通知撤掉（否则用户关掉开关后那条通知会一直挂着）。
        val focusEnabled = runCatching {
            moe.chenxy.fiilpods.config.ConfigManager.focusNotification()
        }.getOrDefault(true)
        if (!focusEnabled) {
            cancelNotification(ctx)
            return
        }
        if (address.isNotEmpty()) lastAddress = address
        // 三路全都没连上（耳机都放回盒里、或刚摘下充电）时，必须把常驻卡片撤掉，
        // 而不是刷成一张写着「未连接」的卡片一直挂在那里。
        //
        // 历史缺陷：`FiilRfcommController.publishState()` 的电量段是**无条件**带齐三个
        // `EXTRA_*_CONNECTED` 的（见 FiilRfcommController.kt:1936-1945），所以
        // `BatterySnapshot.fromExtras()` 在全断开时依然返回非 null；`updateNotification()`
        // 再把它渲染成 content="未连接"（MiBluetoothToastHook.kt:541）。结果是用户把耳机
        // 收回盒里之后，通知栏里那张耳机卡片永远显示「未连接」——既没用又占位。
        // 真正该做的是跟断开广播一样撤干净，所以这里提前 return。
        if (!snapshot.hasAny()) {
            logD("battery snapshot has no connected parts; cancelling notification")
            cancelNotification(ctx)
            return
        }
        // 超级岛两条通道互斥（对齐 refs/OppoPods 的 registerNotificationReceiver）：
        //  - OFFICIAL：走宿主「官方强提示」通道（showBatteryToast → StatusBarManager#setStatus），
        //    由 SystemUI 用系统耳机资源渲染，也就是用户在系统里见过的那个样子；
        //  - MODULE：走模块自制岛（FocusIslandUtil），此时**不能**再发官方强提示，
        //    否则两条岛同时下发会互相顶掉，表现为「岛上电量一闪就空」。
        //  - NONE：两条都不发，只留常驻焦点通知。
        val islandMode = runCatching {
            moe.chenxy.fiilpods.config.ConfigManager.islandMode()
        }.getOrDefault(moe.chenxy.fiilpods.config.ConfigManager.ISLAND_MODE_OFFICIAL)
        when (islandMode) {
            moe.chenxy.fiilpods.config.ConfigManager.ISLAND_MODE_MODULE ->
                showModuleFocusIsland(ctx, snapshot, address)

            moe.chenxy.fiilpods.config.ConfigManager.ISLAND_MODE_OFFICIAL ->
                showBatteryToast(ctx, snapshot, name)

            else -> Unit
        }
        updateNotification(ctx, snapshot, name, address)
    }

    /**
     * 按用户设置把电量推到「模块自制超级岛」（`FocusIslandUtil`）。
     *
     * 为什么需要这段：v2.0.0 之前 `FocusIslandUtil.showBatteryIsland()` 在任何地方
     * **都没有调用点** —— 用户在设置页打开「焦点通知」并把超级岛来源选成「模块」，
     * 通知栏里什么都不会出现（用户 m26286 问题 3）。
     *
     * 分流规则对齐 OppoPods 的 `MiBluetoothToastHook.registerNotificationReceiver()`：
     *  - `ISLAND_MODE_MODULE` → `FocusIslandUtil.showBatteryIsland(...)`（画自己的岛）
     *  - `ISLAND_MODE_OFFICIAL` / `ISLAND_MODE_NONE` → 不额外画（官方模式系统自己弹）
     *
     * 注意：`FocusIslandUtil` 依赖 `PodImageLoader` 解出左右耳图标位图；
     * 用户没配过自定义图时它会回落到内置 PNG，不会返回 null。
     * 任何异常都只记日志，绝不让通知链路崩掉。
     */
    private fun showModuleFocusIsland(ctx: Context, snapshot: BatterySnapshot, address: String) {
        val cfg = runCatching { moe.chenxy.fiilpods.config.ConfigManager.current() }.getOrNull() ?: return
        if (!cfg.focusNotification) return
        if (cfg.islandMode != moe.chenxy.fiilpods.config.ConfigManager.ISLAND_MODE_MODULE) return
        val sp = runCatching { prefs }.getOrNull() ?: return
        val params = BatteryParams(
            left = snapshot.left?.let {
                PodParams(
                    battery = it.battery.coerceAtLeast(0),
                    isCharging = it.charging,
                    isConnected = it.connected,
                    rawStatus = it.battery.coerceAtLeast(0),
                )
            },
            right = snapshot.right?.let {
                PodParams(
                    battery = it.battery.coerceAtLeast(0),
                    isCharging = it.charging,
                    isConnected = it.connected,
                    rawStatus = it.battery.coerceAtLeast(0),
                )
            },
            case = snapshot.case?.let {
                PodParams(
                    battery = it.battery.coerceAtLeast(0),
                    isCharging = it.charging,
                    isConnected = it.connected,
                    rawStatus = it.battery.coerceAtLeast(0),
                )
            },
        )
        runCatching {
            moe.chenxy.fiilpods.utils.FocusIslandUtil.showBatteryIsland(ctx, sp, params, address)
        }.onFailure { logW("module focus island failed", it) }
    }

    // ------------------------------------------------------------------
    // 强提示（超级岛）
    // ------------------------------------------------------------------

    /**
     * 当前降噪模式的短文案（超级岛左侧显示）。
     *
     * 目标要求超级岛「显示电量与当前 MAF 模式」，所以模式必须和电量一起上岛。
     * GAIA 与 Mate 的档位语义不同（Mate 的 2=开放、3=兼听；GAIA 的 2=监听、3=开放），
     * 所以按 `isMate` 分流到两套文案函数，不能共用。
     */
    private fun currentModeLabel(): String {
        val s = lastStatus ?: return "FIIL"
        if (!s.connected || s.ancMode < 0) return s.modelName.ifBlank { "FIIL" }
        return if (s.isMate) FiilModels.mateMafLabel(s.ancMode) else FiilModels.ancLabel(s.ancMode)
    }

    private fun showBatteryToast(ctx: Context, snapshot: BatterySnapshot, name: String) {
        // 防抖：3 秒内不重复弹
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 3000) return
        lastToastAt = now

        val left = snapshot.left
        val right = snapshot.right
        val case = snapshot.case

        // 左侧=`模式 · 左耳电量`，右侧=右耳电量；没有右耳时右侧退回盒电量。
        // 这样气泡/岛上同时能看到「当前 MAF 模式」与「电量」，符合目标第三阶段第 3 条。
        val modeLabel = currentModeLabel()
        val leftBattery = batteryText(left)
        val leftText = TextParams(
            text = if (leftBattery.isEmpty()) modeLabel else "$modeLabel · $leftBattery",
            textColor = batteryColor(left),
            turnAnim = true,
        )
        val rightBattery = batteryText(right)
        val rightText = TextParams(
            text = rightBattery.ifEmpty {
                case?.takeIf { it.connected }?.let { "盒 ${it.battery}%" } ?: ""
            },
            textColor = batteryColor(right),
            turnAnim = true,
        )
        // ----------------------------------------------------------------
        // Bug 3 根因：图标资源名根本不存在
        //
        // 旧实现传的是 `ic_earphone_left` / `ic_earphone_left_off` 这类名字，
        // 而它们**既不在模块的 res 里，也不在 com.xiaomi.bluetooth 的 res 里**。
        // 强提示的 `IconParams.category = drawable` 是按 bundle 里的
        // `package_name`（这里固定 "com.xiaomi.bluetooth"）去宿主资源表查的，
        // 查不到 → SystemUI 解析图标失败 → 整条强提示静默丢弃，
        // 用户看到的就是「超级岛不显示电量」。
        //
        // 系统蓝牙 APK（BluetoothExtension.apk）里真正存在的是这组资源：
        //   res/raw/earphone_left.mp4 / earphone_right.mp4
        //   res/raw/earphone_left_no_inear.mp4 / earphone_right_no_inear.mp4
        //   res/raw/single_battery_earphone.mp4
        //   res/drawable/earphone.png
        // 官方气泡就是把这些 mp4 拷到 filesDir，再用
        // `content://com.xiaomi.bluetooth.fileprovider/internal_files/<name>.mp4`
        // 交给 SystemUI 播放的（见 file_paths.xml 的 `files-path name="internal_files"`）。
        // 本模块的 hook 就跑在 com.xiaomi.bluetooth 进程里，所以 `ctx.resources`
        // 正是宿主资源表、`ctx.filesDir` 正是 provider 映射的目录，可以直接照做。
        // ----------------------------------------------------------------
        val leftClip = officialClipUri(
            ctx,
            if (left?.connected == true) "earphone_left_inear" else "earphone_left_no_inear",
        )
        val rightClip = officialClipUri(
            ctx,
            if (right?.connected == true) "earphone_right_inear" else "earphone_right_no_inear",
        )
        val useVideo = leftClip != null && rightClip != null
        val leftIcon = if (useVideo) {
            IconParams(ToastIconCategory.RAW, ToastIconFormat.MP4, leftClip, 1)
        } else {
            // 兜底：宿主确实带 `res/drawable/earphone.png`，比不存在的
            // `ic_earphone_*` 靠谱；连它都拿不到时下面会把 category 降级。
            IconParams(ToastIconCategory.DRAWABLE, ToastIconFormat.PNG, "earphone", 1)
        }
        val rightIcon = if (useVideo) {
            IconParams(ToastIconCategory.RAW, ToastIconFormat.MP4, rightClip, 1)
        } else {
            IconParams(ToastIconCategory.DRAWABLE, ToastIconFormat.PNG, "earphone", 1)
        }

        val bean = StringToastBean(
            left = ToastSide(iconParams = leftIcon, textParams = leftText),
            right = ToastSide(iconParams = rightIcon, textParams = rightText),
        )
        val json = runCatching { Json.encodeToString(StringToastBean.serializer(), bean) }.getOrNull()
            ?: return

        // 岛上版本：图标改文字型（iconType=0），并让数字带翻转动效 ——
        // 与 `app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt:137-149` 的 islandPayload 处理一致。
        val islandBean = StringToastBean(
            left = ToastSide(
                iconParams = leftIcon.copy(iconType = 0),
                textParams = leftText.copy(viewFlags = null, turnAnim = true),
            ),
            right = ToastSide(
                iconParams = rightIcon.copy(iconType = 0),
                textParams = rightText.copy(viewFlags = null, turnAnim = true),
            ),
        )
        val islandJson = runCatching {
            Json.encodeToString(StringToastBean.serializer(), islandBean)
        }.getOrNull()

        val category = if (useVideo) {
            // 只有真的拿到两段 mp4 才敢用视频分类，否则 SystemUI 会等视频源而渲染空白。
            StrongToastCategory.VIDEO_TEXT_TEXT_VIDEO
        } else {
            StrongToastCategory.TEXT_BITMAP
        }

        val bundle = StringToastBundle.build {
            setPackageName("com.xiaomi.bluetooth")
            setStrongToastCategory(category)
            setDuration(4000)
            setParam(json)
            // 关键：只设 param 只会弹一次气泡，不会驻留到超级岛。
            setIslandParam(islandJson)
            setNotifyId(NOTIFY_ID_STRONG)
        }
        runCatching {
            val sbm = ctx.getSystemService("statusbar") ?: return@runCatching
            sbm.javaClass.getMethod(
                "setStatus",
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
            ).invoke(sbm, 1, "strong_toast_action", bundle)
            logI("strong toast shown: ${batteryText(left)} / ${batteryText(right)}")
        }.onFailure { logW("strong toast failed", it) }
    }

    private fun batteryText(p: PodBattery?): String = when {
        p?.connected != true -> ""
        p.charging -> "${p.battery}%"
        else -> "${p.battery}%"
    }

    private fun batteryColor(p: PodBattery?): Int = when {
        p?.charging == true -> Color.parseColor("#4CAF50")
        (p?.battery ?: 100) <= LOW_BATTERY -> Color.parseColor("#E53935")
        else -> Color.WHITE
    }

    /** 简易文字强提示（连接/断开用）。 */
    fun showToast(ctx: Context?, text: String, color: Int) {
        val c = ctx ?: appContext ?: return
        runCatching {
            val bean = StringToastBean(
                left = ToastSide(textParams = TextParams(text, color)),
            )
            val json = Json.encodeToString(StringToastBean.serializer(), bean)
            val bundle = StringToastBundle.build {
                setPackageName("com.xiaomi.bluetooth")
                setStrongToastCategory(StrongToastCategory.TEXT_BITMAP_INTENT)
                setParam(json)
            }
            val sbm = c.getSystemService("statusbar") ?: return@runCatching
            sbm.javaClass.getMethod(
                "setStatus",
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
            ).invoke(sbm, 1, "strong_toast_action", bundle)
        }.onFailure { logW("showToast failed", it) }
    }

    // ------------------------------------------------------------------
    // 常驻通知（HyperOS 蓝牙耳机卡片 / 焦点通知）
    // ------------------------------------------------------------------

    /**
     * 刷新通知栏里的耳机卡片。
     *
     * **根因（用户问题 2：设置页开了「焦点通知」，通知栏里却没有耳机控制卡片）**
     *
     * 旧实现用模块自建的渠道 `fiilpods_battery_v2`（IMPORTANCE_LOW）发一条普通通知：
     * ```kotlin
     * nm.notify(NOTIFICATION_ID, Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID) /* ... */ .build())
     * ```
     * 这条通知在 HyperOS 上永远进不了「蓝牙耳机」那一格，原因是三个硬性契约都对不上：
     *
     *  1. **渠道 id** —— HyperOS 的耳机卡片是按**每台设备一个渠道**渲染的，id 必须是
     *     `BTHeadset<MAC>`。系统设置侧的 `MiuiHeadsetFragment` 与系统蓝牙侧的
     *     `com.android.bluetooth.ble.app.MiuiBluetoothNotification` 都按这个 id 建渠道；
     *     模块用私有 id，系统根本不会把这条通知识别成耳机通知；
     *  2. **焦点通知 extras** —— 卡片的内容完全来自 `miui.focus.*` Bundle
     *     （`FocusNotification.buildV3`）。只有 `setContentTitle/Text` 的普通通知
     *     没有这类 extras，HyperOS 只会把它当作一条普通文本通知；
     *  3. **notifyAsUser** —— 蓝牙通知必须发到 `UserHandle.ALL`，否则在
     *     桌面/多用户/息屏（AOD）场景下不可见，也就不会升格成耳机卡片。
     *
     * 因此这里整条链路改成与系统原生一致的形态：`BTHeadset<MAC>` 渠道 +
     * `FocusNotification.buildV3` extras + `notifyAsUser` + Action 按钮 + AOD 注入。
     *
     * @param ctx      宿主（`com.xiaomi.bluetooth`）Context
     * @param snapshot 最近一次三路电量
     * @param name     设备显示名
     * @param address  设备 MAC；为空时说明状态广播里没有地址，直接放弃（无法组渠道 id）
     */
    private fun updateNotification(ctx: Context, snapshot: BatterySnapshot, name: String, address: String) {
        if (address.isEmpty()) {
            logW("update notification skipped: empty address")
            return
        }
        runCatching {
            val nm = ctx.getSystemService("notification") as NotificationManager
            // 渠道 id 必须与系统原生完全一致，否则耳机卡片不会认领这条通知。
            val channelId = "$CHANNEL_PREFIX$address"
            nm.createNotificationChannel(
                NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    setAllowBubbles(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )

            // 三路电量正文：系统原生用「盒 / 左耳 / 右耳」三段拼接，盒电量单独一行。
            val lines = buildList {
                snapshot.case?.takeIf { it.connected }?.let { add("充电盒：${it.battery}%${if (it.charging) " ⚡" else ""}") }
                snapshot.left?.takeIf { it.connected }?.let { add("左耳：${it.battery}%${if (it.charging) " ⚡" else ""}") }
                snapshot.right?.takeIf { it.connected }?.let { add("右耳：${it.battery}%${if (it.charging) " ⚡" else ""}") }
            }
            val content = if (lines.isEmpty()) "未连接" else lines.joinToString("  ")

            // 焦点通知右侧要一张设备图：优先用户自定义的充电盒图，回落到模块内置 PNG。
            // 注意必须用**模块**的 resources 解码（hook 跑在宿主进程里，
            // ctx.resources 是宿主资源表，拿不到 moe.chenxy.fiilpods 的 drawable）。
            val moduleContext = runCatching {
                ctx.createPackageContext(FiilConfig.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
            val headsetBitmap = runCatching {
                PodImageLoader.loadBoxBitmap(ctx, prefs, address)
            }.getOrNull()
                ?: moduleContext?.let {
                    runCatching { BitmapFactory.decodeResource(it.resources, R.drawable.img_box) }.getOrNull()
                }
            val headsetIcon = headsetBitmap?.let { Icon.createWithBitmap(it) }

            val device = bluetoothDeviceOf(ctx, address)

            // 点通知 → 模块半透明弹层（PopupActivity）。
            // 显式 setClassName 而不是只发 action：宿主的包管理器解析不到模块的 intent-filter，
            // 而 PopupActivity 收到 DEVICE extra 后能直接定位到对应耳机。
            val openIntent = Intent(FiilAction.ACTION_SHOW_PODS_UI).apply {
                setClassName(FiilConfig.MODULE_PACKAGE, POPUP_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                device?.let { putExtra(EXTRA_DEVICE, it) }
                putExtra("bluetoothaddress", address)
                putExtra("device_name", name)
            }
            val contentPending = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // 「断开」按钮：与系统原生一致，发 com.android.bluetooth.headset.notification，
            // btData 里带 Device、disconnect="1"、identifier=渠道 id。
            val btData = Bundle().apply { device?.let { putParcelable("Device", it) } }
            val disconnectIntent = Intent(ACTION_HEADSET_DISCONNECT).apply {
                putExtra("btData", btData)
                putExtra("disconnect", "1")
                identifier = channelId
            }
            val disconnectPending = PendingIntent.getBroadcast(
                ctx, 0, disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val disconnectLabel = moduleContext?.getString(R.string.notification_btn_disconnect) ?: "断开"
            val disconnectAction = Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_delete),
                disconnectLabel,
                disconnectPending,
            ).build()

            // 「降噪循环」按钮：蓝牙进程侧 BluetoothProcessHook 已注册 ACTION_CYCLE_ANC
            // （见 hook/BluetoothProcessHook.kt 的 ACTION_CYCLE_ANC 分支），
            // 每点一次就在 OFF → 降噪 → 通透 之间轮转。
            val ancCycleIntent = Intent(OppoPodsAction.ACTION_CYCLE_ANC).apply {
                setPackage("com.android.bluetooth")
                identifier = channelId
                putExtra("device_name", name)
            }
            val ancPending = PendingIntent.getBroadcast(
                ctx, 1, ancCycleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ancLabel = moduleContext?.getString(R.string.cycle_anc) ?: "切换降噪"
            val ancAction = Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_lock_silent_mode),
                ancLabel,
                ancPending,
            ).build()

            // 划掉通知 → 通知系统解除绑定（AOSP 里这个 action 的拼写就是 cancle）。
            val deletePending = device?.let {
                PendingIntent.getBroadcast(
                    ctx, 0,
                    Intent(ACTION_HEADSET_CANCEL).putExtra(EXTRA_DEVICE, it),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

            val focusExtras = buildFocusExtras(
                ctx = ctx,
                headsetIcon = headsetIcon,
                name = name,
                contentText = content,
                snapshot = snapshot,
                withButtons = true,
                ancAction = ancAction,
                ancLabel = ancLabel,
                disconnectAction = disconnectAction,
                disconnectLabel = disconnectLabel,
            )

            val builder = Notification.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setWhen(0L)
                .setTicker(name)
                // 必须是 0，不能是 -1。
                //
                // 历史缺陷：这里原先写的是 `setDefaults(-1)`，注释说「-1 = 不发出任何默认提醒」。
                // 但 `Notification.DEFAULT_ALL` 的定义就是 `~0`，即 **-1** —— 传 -1 等于把
                // 声音|震动|灯 三个默认位全开，跟注释的意思正好相反。
                // 现在靠渠道的 `setSound(null,null)` + `enableVibration(false)` 压住了实际声音，
                // 但一旦哪天渠道重要性被改成 HIGH（或用户在系统里手动调高），这张常驻卡片
                // 就会每次电量刷新都响一声。传 0 才是「不申请任何默认提醒」。
                .setDefaults(0)
                .setContentTitle(name)
                .setContentText(content)
                .setContentIntent(contentPending)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // 不设 setOngoing：ongoing 的通知系统不允许用户划掉，那样下面的
                // setDeleteIntent（划掉 → 断开耳机）就永远触发不了，是死代码。
                // 参考实现 refs/OppoPods/.../MiBluetoothToastHook.kt:228 同样只设
                // deleteIntent、不设 ongoing。
                .addAction(disconnectAction)
            deletePending?.let { builder.setDeleteIntent(it) }
            focusExtras?.let { builder.addExtras(it) }

            // 必须 notifyAsUser(..., UserHandle.ALL)：蓝牙通知在 AOD/多用户下由 ALL 分发，
            // 用普通 notify 只会发到当前用户，息屏与通知卡片都不显示。
            nm.notifyAsUser(channelId, NOTIFICATION_ID, builder.build(), SystemApisUtils.getUserAllUserHandle())
            // 记下来：断开时要用同一个 tag 才能撤干净（进程重启后靠落盘那份）。
            rememberNotificationTag(ctx, channelId)
            logD("focus notification updated: channel=$channelId content=$content")
        }.onFailure { logW("update notification failed", it) }
    }

    /**
     * 组 FocusNotification 的 `miui.focus.*` extras（HyperOS 耳机卡片的全部内容来源）。
     *
     * 与 `refs/OppoPods` 的 `createPodsNotification` 保持同一形态：
     *  - `iconTextInfo` 管**下拉栏卡片**（左图右文）；
     *  - `island` 管**超级岛**（左图 + 右文，岛左块按原生只放图）；
     *  - `textButton` 放两个实体按钮（降噪循环 / 断开）。
     *
     * 之后再往 `miui.focus.param` 的 `param_v2` 里补 `aodTitle` / `aodPic` —— 这是
     * 息屏显示（AOD）时显示电量的唯一途径，不注入则息屏下卡片是空的。
     */
    private fun buildFocusExtras(
        ctx: Context,
        headsetIcon: Icon?,
        name: String,
        // 名字不能叫 `content`：`iconTextInfo { content = ... }` / `textInfo { content = ... }`
        // 的 receiver 属性名就是 `content`，而 Kotlin 里外层形参会**遮蔽** receiver 成员，
        // 于是 `content = content` 被解析成给 val 形参赋值 → "val cannot be reassigned" 编译错误。
        contentText: String,
        snapshot: BatterySnapshot,
        withButtons: Boolean,
        ancAction: Notification.Action?,
        ancLabel: String?,
        disconnectAction: Notification.Action?,
        disconnectLabel: String?,
    ): Bundle? {
        val icon = headsetIcon ?: return null
        val extras = runCatching {
            FocusNotification.buildV3 {
                val logo = createPicture("key_headset", icon)
                enableFloat = true
                updatable = true
                ticker = name
                tickerPic = logo

                iconTextInfo {
                    animIconInfo {
                        type = 0
                        src = logo
                    }
                    title = name
                    content = contentText
                }

                island {
                    islandProperty = 1
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = logo
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            textInfo {
                                title = name
                                content = contentText
                            }
                        }
                    }
                }

                if (withButtons && ancAction != null && disconnectAction != null) {
                    textButton {
                        addActionInfo {
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel ?: ""
                        }
                        addActionInfo {
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel ?: ""
                        }
                    }
                }
            }
        }.onFailure { logW("build focus extras failed", it) }.getOrNull() ?: return null

        // AOD（息屏显示）：原生耳机卡片的息屏内容取自 param_v2.aodTitle / aodPic，
        // 不写这两个字段时息屏只显示一个空卡片。
        runCatching {
            val aodParts = buildList {
                snapshot.left?.takeIf { it.connected }?.let { add("L ${it.battery}%") }
                snapshot.right?.takeIf { it.connected }?.let { add("R ${it.battery}%") }
                snapshot.case?.takeIf { it.connected }?.let { add("盒 ${it.battery}%") }
            }
            if (aodParts.isEmpty()) return@runCatching
            val json = org.json.JSONObject(extras.getString("miui.focus.param") ?: "{}")
            val paramV2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
            paramV2.put("aodTitle", aodParts.joinToString(" | "))
            paramV2.put("aodPic", "key_headset")
            json.put("param_v2", paramV2)
            extras.putString("miui.focus.param", json.toString())
        }.onFailure { logW("inject aod params failed", it) }

        logD("focus extras built for $name")
        return extras
    }

    /**
     * 从 MAC 还原 [BluetoothDevice]。
     *
     * `MiBluetoothToastHook` 跑在 `com.xiaomi.bluetooth` 进程里，拿不到蓝牙进程的
     * `FiilRfcommController.connectedDevice()`，所以只能本地 `getRemoteDevice(address)`。
     * 这条路径依赖 BLUETOOTH_CONNECT（宿主自己持有，无需模块申请）。
     */
    private fun bluetoothDeviceOf(ctx: Context, address: String): BluetoothDevice? = runCatching {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter?.getRemoteDevice(address)
    }.getOrNull()

    /**
     * 记住「本次真正发出去的通知 tag」，并落盘。
     *
     * 落盘的原因见 [lastAddress] 的注释：进程重启后内存字段是空的，而卡片是常驻通知、
     * 用户划不掉 —— 只有下次连上耳机才会被同一个 tag 覆盖，断开期间就一直挂在那儿。
     * 这里用 hook 进程自己的私有 prefs（不是模块的 remote prefs，那是只读语义）。
     */
    private fun rememberNotificationTag(ctx: Context?, tag: String) {
        lastNotificationTag = tag
        val c = ctx ?: appContext ?: return
        runCatching {
            c.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOTIFICATION_TAG, tag)
                .apply()
        }.onFailure { logW("persist notification tag failed", it) }
    }

    /** 读回 [rememberNotificationTag] 落盘的 tag；读不到返回空串。 */
    private fun restoredNotificationTag(ctx: Context?): String {
        if (lastNotificationTag.isNotEmpty()) return lastNotificationTag
        val c = ctx ?: appContext ?: return ""
        return runCatching {
            c.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NOTIFICATION_TAG, "").orEmpty()
        }.getOrDefault("")
    }

    private fun cancelNotification(ctx: Context?) {
        val c = ctx ?: appContext ?: return
        runCatching {
            val nm = c.getSystemService("notification") as NotificationManager
            // 优先用落盘/内存里的真实 tag；拿不到才退回 lastAddress 推导，
            // 避免进程重启后 cancel 了一个不存在的 tag、把旧卡片留在通知栏。
            val tag = restoredNotificationTag(c)
                .ifEmpty { lastAddress.takeIf { it.isNotEmpty() }?.let { "$CHANNEL_PREFIX$it" }.orEmpty() }
            if (tag.isNotEmpty()) {
                // 渠道 id 与 notifyAsUser 一致，否则 cancel 掉的是另一个 tag，卡片会残留。
                nm.cancelAsUser(tag, NOTIFICATION_ID, SystemApisUtils.getUserAllUserHandle())
            }
            // 兜底一：清掉旧版本用私有渠道发出去的那条（升级后不会自己消失）。
            nm.cancel(NOTIFICATION_ID)
            // 兜底二：按 id 扫一遍活动通知。tag 逻辑失效时（换了用户 / 别的进程发的）
            // 这是唯一能兜住常驻卡片的手段。
            runCatching {
                nm.activeNotifications.orEmpty()
                    .filter { it.id == NOTIFICATION_ID }
                    .forEach { nm.cancel(it.tag, it.id) }
            }
            lastNotificationTag = ""
            runCatching {
                c.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_NOTIFICATION_TAG).apply()
            }
        }.onFailure { logW("cancel notification failed", it) }
    }

    /**
     * 主动收起超级岛/强提示。
     *
     * 系统侧约定：只带 `package_name` + `status_bar_strong_toast=hide_strong_toast`。
     * 见 `app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt:170-179`。没有这一步，断开后
     * 岛上会残留一条旧的耳机电量。
     */
    private fun hideStrongToast(ctx: Context?) {
        val c = ctx ?: appContext ?: return
        runCatching {
            val bundle = StringToastBundle.hide("com.xiaomi.bluetooth")
            val sbm = c.getSystemService("statusbar") ?: return@runCatching
            sbm.javaClass.getMethod(
                "setStatus",
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
            ).invoke(sbm, 1, "strong_toast_action", bundle)
            logI("strong toast hidden")
        }.onFailure { logW("hide strong toast failed", it) }
    }

    /**
     * 把宿主自带的官方耳机动画抠出来，转成 SystemUI 能读的 content:// URI。
     *
     * HyperOS 的强提示图标如果走 `category = raw / iconFormat = mp4`，SystemUI 会
     * 用 ContentResolver 打开这个 URI 播动画。合法来源只有宿主自己的 FileProvider：
     * `com.xiaomi.bluetooth.fileprovider`（见 BluetoothExtension.apk 的
     * `res/xml/file_paths.xml`，其中 `<files-path name="internal_files" path="."/>`
     * 正是把 `context.filesDir` 映射成 `/internal_files/`）。
     *
     * 所以这里做两件事：
     *  1. 用 `ctx.resources`（hook 跑在宿主进程里，拿到的就是宿主资源表）把
     *     `res/raw/<name>.mp4` 拷到 `ctx.filesDir/<name>.mp4`；
     *  2. 拼出 `content://com.xiaomi.bluetooth.fileprovider/internal_files/<name>.mp4`，
     *     并把读权限授予 `com.android.systemui`。
     *
     * 资源名有两种：`earphone_left_inear`（戴着）与 `earphone_left_no_inear`（没戴），
     * 宿主里两张都有；也保留了去掉 `_inear` 的退化名（`earphone_left`），
     * 这样换 ROM 命名漂移时还能命中一次。
     *
     * @return 可直接塞进 [IconParams.iconResName] 的 URI；宿主资源缺失时返回 null，
     *         调用方必须能接受 null 并降级到 drawable。
     */
    private fun officialClipUri(ctx: Context, name: String): String? {
        val file = java.io.File(ctx.filesDir, "$name.mp4")
        if (!file.exists() || file.length() == 0L) {
            val pkg = ctx.packageName // hook 进程里 = "com.xiaomi.bluetooth"
            val resId = listOf(name, name.removeSuffix("_inear"))
                .distinct()
                .firstNotNullOfOrNull { candidate ->
                    runCatching { ctx.resources.getIdentifier(candidate, "raw", pkg) }
                        .getOrDefault(0)
                        .takeIf { it != 0 }
                } ?: return null
            val copied = runCatching {
                ctx.resources.openRawResource(resId).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(true, false)
            }.onFailure { logW("copy official clip $name failed", it) }.isSuccess
            if (!copied) return null
        }
        val uri = "content://com.xiaomi.bluetooth.fileprovider/internal_files/$name.mp4"
        runCatching {
            ctx.grantUriPermission(
                "com.android.systemui",
                android.net.Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { logD("grantUriPermission skipped: ${it.message}") }
        return uri
    }
}
