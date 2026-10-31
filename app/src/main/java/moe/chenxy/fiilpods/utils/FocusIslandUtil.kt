package moe.chenxy.fiilpods.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.fiilpods.hook.Log
import moe.chenxy.fiilpods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.fiilpods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams

@SuppressLint("WrongConstant")
object FocusIslandUtil {
    private const val TAG = "FiilPods-FocusIsland"
    private const val CHANNEL_ID = "fiilpods_focus_island"
    private const val CHANNEL_NAME = "FiilPods Battery"
    private const val NOTIFICATION_ID = 10086
    private const val ISLAND_TIMEOUT_SECONDS = 3
    private const val DISMISS_DELAY_MS = 4000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 每次 [showBatteryIsland] 自增；延迟撤销的回调只在「自己仍是最新一次」时才真正撤销。
     *
     * 为什么必须有这个 token：
     * 旧实现每次展示都 `postDelayed(4s) { cancel() }`，而电量更新很密（30 秒心跳 +
     * 每次 MAF/佩戴/电量变化都会触发一次）。t=0 展示一次、t=2s 再展示一次，第一个
     * 回调会在 t=4s 把**第二条**通知撤掉 —— 用户看到的就是「超级岛上电量一闪就空」。
     * 加了 token 后，过期的回调直接放弃，只由最后一次展示负责收尾。
     */
    @Volatile
    private var showToken: Int = 0

    fun showBatteryIsland(
        context: Context,
        prefs: SharedPreferences,
        batteryParams: BatteryParams,
        address: String,
    ): Boolean {
        try {
            val leftConnected = batteryParams.left?.isConnected == true
            val rightConnected = batteryParams.right?.isConnected == true

            // Need at least one ear connected
            if (!leftConnected && !rightConnected) return false

            // 用 ?.let 而不是 !!：leftConnected 只保证 left 不为 null，
            // 但这一步之后对象可能被别的地方改过（BatteryParams 是 var），用 !! 有崩的风险。
            val leftText = batteryParams.left?.takeIf { leftConnected }?.let { "${it.battery}" } ?: "-"
            val rightText = batteryParams.right?.takeIf { rightConnected }?.let { "${it.battery}" } ?: "-"

            val leftBitmap = PodImageLoader.loadIslandLeftBitmap(context, prefs, address)
            val rightBitmap = PodImageLoader.loadIslandRightBitmap(context, prefs, address)

            if (leftBitmap == null || rightBitmap == null) {
                Log.e(TAG, "Failed to decode earphone icon bitmaps")
                return false
            }

            // 使用 createWithBitmap 直接嵌入图片数据，SystemUI 无需再访问模块资源
            val leftIcon = Icon.createWithBitmap(leftBitmap)
            val rightIcon = Icon.createWithBitmap(rightBitmap)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setAllowBubbles(true)
                }
            )

            val contentParts = mutableListOf<String>()
            batteryParams.left?.takeIf { leftConnected }?.let { contentParts.add("L: ${it.battery}%") }
            batteryParams.right?.takeIf { rightConnected }?.let { contentParts.add("R: ${it.battery}%") }
            // 单耳佩戴时补上盒子电量：另一侧是空的，盒子电量是用户唯一还能看到的数字。
            // 这里必须判「有一侧没连」，不能判 contentParts.isEmpty() —— 上面只要有任一侧
            // 连上就必然加过一项，isEmpty() 恒为 false，那段代码是死分支。
            if (!leftConnected || !rightConnected) {
                batteryParams.case?.takeIf { it.isConnected }?.let { contentParts.add("盒: ${it.battery}%") }
            }
            val contentText = contentParts.joinToString("  ")

            val extras = FocusNotification.buildV3 {
                val picLeft = createPicture("key_pic_left", leftIcon)
                val picRight = createPicture("key_pic_right", rightIcon)

                enableFloat = true
                ticker = "FiilPods"
                tickerPic = picLeft

                isShowNotification = false
                island {
                    islandProperty = 1
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picLeft
                            }
                            // 只在左耳真的连着时才挂 textInfo：
                            // 旧实现无论如何都写 title="-"、content="%"，
                            // 单耳/摘下一侧时岛上会出现一个刺眼的「-%」，
                            // 用户看到的就是「岛上电量是空的 / 是乱的」。
                            if (leftConnected) {
                                textInfo {
                                    title = leftText
                                    content = "%"
                                }
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            picInfo {
                                type = 1
                                pic = picRight
                            }
                            if (rightConnected) {
                                textInfo {
                                    title = rightText
                                    content = "%"
                                }
                            }
                        }
                    }
                    shareData {
                        title = "FiilPods"
                        content = contentText
                        shareContent = contentText
                    }
                }
            }

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("FiilPods")
                .setContentText(contentText)
                .setTicker("FiilPods")
                .addExtras(extras)
                .build()

            // 必须用 notifyAsUser(…, UserHandle.ALL)：
            // 本 hook 跑在 com.xiaomi.bluetooth 进程里，该进程的 NotificationManager
            // 默认只对「当前用户」可见；而超级岛/焦点通知的宿主 SystemUI 监听的是
            // ALL 用户的通知流。用普通 notify() 发出的通知在部分 HyperOS 版本上
            // 根本不会触发岛渲染 —— 这正是「岛是空的」的另一半原因。
            val user = runCatching { SystemApisUtils.getUserAllUserHandle() }.getOrNull()
            // 记下本次展示的序号：撤销回调只有在自己仍是最新一次时才动手。
            val token = ++showToken
            if (user != null) {
                nm.notifyAsUser(CHANNEL_ID, NOTIFICATION_ID, notification, user)
            } else {
                nm.notify(NOTIFICATION_ID, notification)
            }

            mainHandler.postDelayed({
                // 期间又有一次展示 → 这条回调过期，交给后来的那次负责撤销。
                // 没有这个判断时，密集刷新的电量会把刚弹出来的岛提前 4s 内撤掉。
                if (token != showToken) return@postDelayed
                try {
                    if (user != null) {
                        nm.cancelAsUser(CHANNEL_ID, NOTIFICATION_ID, user)
                    } else {
                        nm.cancel(NOTIFICATION_ID)
                    }
                } catch (_: Exception) {
                }
            }, DISMISS_DELAY_MS)

            Log.d(TAG, "Focus Island shown: L=$leftText% R=$rightText%")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Focus Island", e)
            return false
        }
    }
}
