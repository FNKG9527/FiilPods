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
                            onBatteryChanged(c ?: appContext, snapshot, name)
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

    private fun onBatteryChanged(ctx: Context?, snapshot: BatterySnapshot, name: String) {
        if (ctx == null) return
        showBatteryToast(ctx, snapshot, name)
        updateNotification(ctx, snapshot, name)
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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FIIL 耳机电量", NotificationManager.IMPORTANCE_MIN)
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

            val builder = Notification.Builder(ctx, CHANNEL_ID)
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
