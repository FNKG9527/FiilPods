package moe.chenxy.fiilpods.hook

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import moe.chenxy.fiilpods.hook.milink.MiLinkServiceHook
import java.lang.reflect.Executable

/**
 * FiilPods 的 Xposed 入口。
 *
 * 作用域：
 *  - com.android.bluetooth：拿 A2dpService 上下文、把电量回写系统、挂 RFCOMM 控制器
 *  - com.android.settings：在「蓝牙 → 已配对设备」里注入 FIIL 入口
 *  - com.xiaomi.bluetooth：强提示（HyperOS 只认小米蓝牙 uid）
 *  - com.milink.service：融合设备中心（下拉控制中心的耳机卡片）
 */
open class HookEntry : XposedModule() {

    companion object {
        const val TAG = "FiilPods-HookEntry"
        const val PKG_BLUETOOTH = "com.android.bluetooth"
        const val PKG_SETTINGS = "com.android.settings"
        const val PKG_MI_BLUETOOTH = "com.xiaomi.bluetooth"
        const val PKG_MILINK = "com.milink.service"
    }

    init {
        // 诊断锚点：直接走 android.util.Log（不依赖 libxposed 的 log 通道，
        // 后者在部分 LSPosed 版本里只写框架自己的日志文件，logcat 看不到）。
        // 只要这行出现，就证明模块被 LSPosed 加载并实例化成功了。
        android.util.Log.e(TAG, "=== FiilPods module INSTANTIATED (libxposed 102) ===")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 诊断锚点：每个作用域包加载都打一遍，且带 isFirstPackage 状态。
        android.util.Log.e(
            TAG,
            "=== onPackageLoaded pkg=${param.packageName} first=${param.isFirstPackage} ===",
        )
        if (!param.isFirstPackage) return
        installHooksForPackage(param.packageName, param.defaultClassLoader)
    }

    private fun installHooksForPackage(packageName: String, classLoader: ClassLoader) {
        log(android.util.Log.INFO, TAG, "install hooks for $packageName")
        runCatching {
            when (packageName) {
                PKG_BLUETOOTH -> BluetoothProcessHook(this, classLoader).install()
                PKG_SETTINGS -> SettingsHook(this, classLoader).install()
                PKG_MI_BLUETOOTH -> MiBluetoothToastHook(this, classLoader).install()
                PKG_MILINK -> MiLinkServiceHook(this, classLoader).install()
            }
        }.onFailure {
            log(android.util.Log.ERROR, TAG, "install failed for $packageName", it)
        }
    }

    /** 统一的 hook 包装：把拦截器挂到方法上，任何异常都不允许冒泡到宿主。 */
    internal fun installHook(
        executable: Executable,
        hookId: String,
        interceptor: (XposedInterface.Chain) -> Any?,
    ): XposedInterface.HookHandle? = runCatching {
        hook(executable).intercept { chain -> interceptor(chain) }
    }.onFailure {
        log(android.util.Log.ERROR, TAG, "hook $hookId failed", it)
    }.getOrNull()

    internal fun hookId(description: String): String = description

    internal fun appContextOrNull(): Context? =
        runCatching { activityThreadApplication() }.getOrNull()

    private fun activityThreadApplication(): Context? {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null)
        return at.getMethod("getApplication").invoke(current) as? Context
    }
}
