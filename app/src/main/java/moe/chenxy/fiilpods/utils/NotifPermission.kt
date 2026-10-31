package moe.chenxy.fiilpods.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import moe.chenxy.fiilpods.hook.Log

/**
 * Android 13+ 的 `POST_NOTIFICATIONS` 运行时授权。
 *
 * ## 为什么必须有这个文件（Bug 2 的根因之二）
 * Bug 2 的现象是「打开『焦点通知』开关，通知栏里什么都不出现」。
 * 之前 `AndroidManifest.xml` 里连 `POST_NOTIFICATIONS` 都没声明，
 * 于是 `NotificationManager.notify()` 在 API 33+ 上被系统**静默丢弃**
 * —— 不抛异常、不报错、日志里也看不出来，所以这个 Bug 一直很隐蔽。
 *
 * 只补 manifest 声明还不够：`POST_NOTIFICATIONS` 是 **dangerous** 级权限，
 * 必须在运行时向用户申请，否则默认是拒绝的。
 *
 * 全部走 framework 原生 API（`Context.checkSelfPermission` /
 * `Activity.shouldShowRequestPermissionRationale`），不额外引入
 * androidx.core 依赖，避免 shrinkResources 把间接引用的类裁掉。
 */
object NotifPermission {

    private const val TAG = "FiilPods-Notif"

    /** 这个 SDK 版本起 `POST_NOTIFICATIONS` 才是运行时权限。 */
    private const val API_TIRAMISU = Build.VERSION_CODES.TIRAMISU

    /** 是否已经拿到通知权限（< 33 恒为 true）。 */
    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < API_TIRAMISU) return true
        return runCatching {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 在 Activity 里确保通知权限。
     *
     * 注意：必须在 `onCreate()` 里、且在 Activity 进入 STARTED 之前调用
     * （`registerForActivityResult` 的硬性要求），所以调用点在
     * `MainActivity.onCreate()` 的 `super.onCreate()` 之后、`setContent` 之前。
     * 整个流程用 `runCatching` 包住 —— 权限申请失败绝不能让主界面崩掉。
     *
     * @return true = 已有权限（无需弹窗）；false = 已发起申请，结果异步返回
     */
    fun ensure(activity: Activity): Boolean {
        if (granted(activity)) return true
        // registerForActivityResult 只存在于 ComponentActivity（androidx.activity），
        // 不是 android.app.Activity 的成员 —— 必须显式转型，否则编译报
        // "Unresolved reference 'registerForActivityResult' on receiver of type 'Activity'"。
        val host = activity as? ComponentActivity ?: return true
        return runCatching {
            val shouldAsk = runCatching {
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            }.getOrDefault(false)
            Log.i(TAG, "requesting POST_NOTIFICATIONS (rationale=$shouldAsk)")
            host.registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ok: Boolean ->
                Log.i(TAG, "POST_NOTIFICATIONS result=$ok")
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
            false
        }.onFailure {
            Log.w(TAG, "request POST_NOTIFICATIONS failed", it)
        }.getOrDefault(true)
    }
}
