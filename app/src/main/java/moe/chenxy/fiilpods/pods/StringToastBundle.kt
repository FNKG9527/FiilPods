package moe.chenxy.fiilpods.pods

import android.app.PendingIntent
import android.os.Bundle
import kotlinx.serialization.Serializable

/**
 * HyperOS 强提示（超级岛）的 JSON 载荷结构。
 *
 * 这是小米系统 UI 私有协议：StatusBarManager#setStatus(1, "strong_toast_action", bundle)
 * 的 bundle 里 `param` 字段是一段 JSON，描述左/右两块内容。
 * 字段名必须完全一致，改一个字母就解析失败。
 */
@Serializable
data class StringToastBean(
    var left: ToastSide? = null,
    var right: ToastSide? = null,
)

@Serializable
data class ToastSide(
    var iconParams: IconParams? = null,
    var textParams: TextParams? = null,
)

@Serializable
data class IconParams(
    var category: String? = null,
    var iconFormat: String? = null,
    var iconResName: String? = null,
    var iconType: Int = 0,
)

@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0,
    /**
     * 仅超级岛用：不设则岛上的数字不滚动；[turnAnim] = true 时配合使用。
     * 字段名与系统侧 JSON 键一一对应，改成别的名字系统会静默忽略。
     */
    var viewFlags: Int? = null,
    /** 超级岛上是否启用翻转动效（电量变化时数字翻一下）。 */
    var turnAnim: Boolean? = null,
)

/** 强提示的 Bundle 构造器。字段与系统侧一一对应，不可增删。 */
class StringToastBundle private constructor() {
    companion object {
        private var mBundle: Bundle = Bundle()

        fun clear() {
            mBundle = Bundle()
        }

        fun build(block: Builder.() -> Unit): Bundle {
            clear()
            return Builder().apply(block).onCreate()
        }

        /**
         * 「隐藏当前强提示」的 Bundle。
         *
         * 系统侧约定：只带 package_name + status_bar_strong_toast=hide_strong_toast，
         * 不带 param 就是「收起」。参考 `app/src/main/java/moe/chenxy/oppopods/utils/miuiStrongToast/MiuiStrongToastUtil.kt:170 hideOfficialToast()`。
         */
        fun hide(packageName: String): Bundle = Bundle().apply {
            putString("package_name", packageName)
            putString("status_bar_strong_toast", "hide_strong_toast")
        }
    }

    class Builder {
        private var packageName: String? = null
        private var stringToastCategory: String? = null
        private var target: PendingIntent? = null
        private var param: String? = null
        /**
         * 超级岛专用载荷。系统侧读 `island_param` 来渲染岛上的内容，
         * 与 `param`（一次性强提示气泡）是两个独立通道：
         * 只设 `param` 会弹气泡但**不上岛**，两个都设才会同时生效。
         */
        private var islandParam: String? = null
        /** 岛/气泡的去重 id。同一个 id 重复下发只会更新不会叠加。 */
        private var notifyId: String? = null
        private var duration: Long = 2500L
        private var level: Float = 0f
        private var rapidRate: Float = 0f
        private var charge: String? = null
        private var stringToastChargeFlag: Int = 0
        private var statusBarStrongToast: String? = "show_custom_strong_toast"

        fun setPackageName(packageName: String?) = apply { this.packageName = packageName }
        fun setStrongToastCategory(category: String) = apply { stringToastCategory = category }
        fun setTarget(target: PendingIntent?) = apply { this.target = target }
        fun setParam(param: String?) = apply { this.param = param }
        fun setIslandParam(param: String?) = apply { this.islandParam = param }
        fun setNotifyId(notifyId: String?) = apply { this.notifyId = notifyId }
        fun setDuration(duration: Long) = apply { this.duration = duration }
        fun setLevel(level: Float) = apply { this.level = level }
        fun setRapidRate(rapidRate: Float) = apply { this.rapidRate = rapidRate }
        fun setCharge(charge: String?) = apply { this.charge = charge }
        fun setStringToastChargeFlag(flag: Int) = apply { this.stringToastChargeFlag = flag }
        fun setStatusBarStrongToast(v: String?) = apply { this.statusBarStrongToast = v }

        fun onCreate(): Bundle = mBundle.apply {
            putString("package_name", packageName)
            putString("strong_toast_category", stringToastCategory)
            putParcelable("target", target)
            putString("param", param)
            putString("island_param", islandParam)
            putString("notifyId", notifyId)
            putLong("duration", duration)
            putFloat("level", level)
            putFloat("rapid_rate", rapidRate)
            putString("charge", charge)
            putInt("string_toast_charge_flag", stringToastChargeFlag)
            putString("status_bar_strong_toast", statusBarStrongToast)
        }
    }
}

/** 强提示内容类型。 */
object StrongToastCategory {
    const val VIDEO_TEXT = "video_text"
    const val VIDEO_BITMAP_INTENT = "video_bitmap_intent"
    const val TEXT_BITMAP = "text_bitmap"
    const val TEXT_BITMAP_INTENT = "text_bitmap_intent"
    const val VIDEO_TEXT_TEXT_VIDEO = "video_text_text_video"
}

object ToastIconCategory {
    const val RAW = "raw"
    const val DRAWABLE = "drawable"
    const val FILE = "file"
    const val MIPMAP = "mipmap"
}

object ToastIconFormat {
    const val MP4 = "mp4"
    const val PNG = "png"
    const val SVG = "svg"
}
