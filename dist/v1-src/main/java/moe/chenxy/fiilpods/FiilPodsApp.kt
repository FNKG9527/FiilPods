package moe.chenxy.fiilpods

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 应用入口：把 LSPosed 的 XposedService 暴露给 UI 层。
 *
 * 与 OppoPods 的 OppoPodsApp 完全对应，只是包名换成了 moe.chenxy.fiilpods。
 * UI 层（`ui/MainUI.kt`）用 `FiilPodsApp.xposedService` 判断模块服务是否已连接、
 * 以及把耳机图片写进 LSPosed 作用域内的私有目录（PodImagePrefs 需要它）。
 */
class FiilPodsApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        // 最先安装崩溃捕获：UI 层是整体移植来的，还没在真机跑过，
        // 一旦首帧崩溃必须能落盘堆栈，否则用户只能干等。
        CrashHandler.install(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.d(TAG, "LSPosed service bound api=${service.apiVersion} framework=${service.frameworkName}/${service.frameworkVersionCode}")
        xposedService = service
        notifyListeners(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            Log.d(TAG, "LSPosed service died")
            xposedService = null
            notifyListeners(null)
        }
    }

    private fun notifyListeners(service: XposedService?) {
        listeners.forEach { it(service) }
    }

    companion object {
        private const val TAG = "FiilPods-App"

        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}