package moe.chenxy.fiilpods.hook

import moe.chenxy.fiilpods.config.ConfigManager

/**
 * 全局日志门面 —— 与 OppoPods 的同名对象**完全一致**的 API（v/i/d/w/e + Throwable 重载），
 * 这样从 OppoPods 移植过来的 UI 与工具代码不需要改 import。
 *
 * 日志等级由设置页的「日志级别」控制（[ConfigManager.logLevel]）：
 *  - `LOG_LEVEL_BASIC`：只输出 i/w/e
 *  - `LOG_LEVEL_DEBUG`：额外输出 v/d
 */
object Log {
    fun v(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        android.util.Log.i(tag, message)
    }

    fun i(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        android.util.Log.i(tag, message)
    }

    fun d(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        android.util.Log.i(tag, message)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        android.util.Log.e(tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        android.util.Log.e(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        android.util.Log.e(tag, message, throwable)
    }
}
