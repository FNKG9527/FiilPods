package moe.chenxy.fiilpods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import kotlinx.serialization.json.Json
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
        private const val CHANNEL_ID = "fiilpods_battery"
    // v2.1 新渠道：旧渠道建成 IMPORTANCE_MIN 后重要性不可变，静默通知在 HyperOS 会被折叠不显示，
    // 必须换新 id 才能拿到 IMPORTANCE_LOW（见 updateNotification 的说明）。
    private const val NOTIFICATION_CHANNEL_ID = "fiilpods_battery_v2"
        private const val NOTIFICATION_ID = 10003
        private const val LOW_BATTERY = 20

        /** 超级岛的去重 id：同一个 id 重复下发只更新内容，不会在岛上叠出多条。 */
        private const val NOTIFY_ID_STRONG = "fiil_pods_battery"

        /** 通知点击目标：模块的半透明弹层。 */
        private const val POPUP_ACTIVITY = "moe.chenxy.fiilpods.PopupActivity"
    }

    private var appContext: Context? = null
    private var receiverRegistered = false
    private var lastToastAt = 0L

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
        // 挂到小米蓝牙的通知服务上拿 Context —— 这个类在进程启动早期就会被创建
        runCatching {
            val cls = findClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
            val ctor = findConstructorByParamCount(cls.name, 2) ?: return
            hookConstructorAfter(ctor) { param ->
                val ctx = getObjectField(param.instance, "mContext") as? Context ?: return@hookConstructorAfter
                appContext = ctx.applicationContext ?: ctx
                registerReceiver(ctx)
            }
        }.onFailure { logW("hook MiuiBluetoothNotification skipped", it) }

        // 兜底：直接 hook 进程入口拿 Context
        runCatching {
            val m = findMethodByParamCount(
                "com.android.bluetooth.btservice.AdapterService",
                "onCreate",
                0,
            ) ?: return
            hookAfter(m) { param ->
                val ctx = param.instance as? Context ?: return@hookAfter
                appContext = ctx.applicationContext ?: ctx
                registerReceiver(ctx)
            }
        }.onFailure { logW("hook AdapterService skipped", it) }
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
            logI("receiver registered in xiaomi.bluetooth")
        }.onFailure { logW("register receiver failed", it) }
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
        showBatteryToast(ctx, snapshot, name)
        updateNotification(ctx, snapshot, name)
        // 「焦点通知」模式下的岛与通知走另一条通道：
        // 用户设置里「超级岛 = 模块」时才有意义（官方模式由系统蓝牙自己弹）。
        showModuleFocusIsland(ctx, snapshot, address)
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
        val leftIcon = IconParams(
            category = ToastIconCategory.DRAWABLE,
            iconFormat = ToastIconFormat.SVG,
            iconResName = if (left?.connected == true) "ic_earphone_left" else "ic_earphone_left_off",
            iconType = 1,
        )
        val rightIcon = IconParams(
            category = ToastIconCategory.DRAWABLE,
            iconFormat = ToastIconFormat.SVG,
            iconResName = if (right?.connected == true) "ic_earphone_right" else "ic_earphone_right_off",
            iconType = 1,
        )

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

        val category = if (case?.connected == true) {
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
    // 常驻通知（下拉通知栏的三路电量）
    // ------------------------------------------------------------------

    private fun updateNotification(ctx: Context, snapshot: BatterySnapshot, name: String) {
        runCatching {
            val nm = ctx.getSystemService("notification") as NotificationManager
            // ⚠️ 渠道重要性一旦创建就**不可修改**（改代码重装也不行，系统会沿用旧值）。
            // 早期版本用的是 `fiilpods_battery` + IMPORTANCE_MIN，而 IMPORTANCE_MIN 的通知
            // 在 HyperOS 上会被塞进「静默通知」并**默认折叠**，用户在下拉栏里根本看不到 ——
            // 这就是用户 m26286 问题 3「设置页开了焦点通知，通知栏里什么也没有」的直接原因。
            // 所以换成新渠道 id + IMPORTANCE_LOW（有声音栏但不吵），旧渠道留着不影响。
            nm.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "FIIL 耳机电量", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )

            val lines = buildList {
                snapshot.left?.takeIf { it.connected }?.let { add("左耳：${it.battery}%${if (it.charging) " ⚡" else ""}") }
                snapshot.right?.takeIf { it.connected }?.let { add("右耳：${it.battery}%${if (it.charging) " ⚡" else ""}") }
                snapshot.case?.takeIf { it.connected }?.let { add("充电盒：${it.battery}%${if (it.charging) " ⚡" else ""}") }
            }
            val content = if (lines.isEmpty()) "未连接" else lines.joinToString(" | ")

            // 点通知要弹的是**半透明弹层**（PopupActivity），不是完整控制台。
            // 显式 setClassName 而不是只发 action：蓝牙进程里拿不到模块的 Intent 解析结果，
            // 且 PopupActivity 的 intent-filter 只认 action，不带 package 会解析失败。
            val openIntent = Intent(FiilAction.ACTION_SHOW_PODS_UI).apply {
                setClassName(FiilConfig.MODULE_PACKAGE, POPUP_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = android.app.PendingIntent.getActivity(
                ctx, 0, openIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val builder = Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(name)
                .setContentText(content)
                .setWhen(0L)
                .setContentIntent(pending)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)

            nm.notify(NOTIFICATION_ID, builder.build())
            logD("notification updated: $content")
        }.onFailure { logW("update notification failed", it) }
    }

    private fun cancelNotification(ctx: Context?) {
        val c = ctx ?: appContext ?: return
        runCatching {
            val nm = c.getSystemService("notification") as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
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
}
