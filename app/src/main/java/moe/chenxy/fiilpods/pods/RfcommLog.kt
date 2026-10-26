package moe.chenxy.fiilpods.pods

import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块内自建日志通道（v2 新增）。
 *
 * 为什么不用 logcat：
 *  - HyperOS 上 `persist.debug.dpis.global_log_enabled` 常被关掉，`logcat` 里
 *    根本读不到蓝牙进程的输出（实测 `ro.logd.size=64K` 且 `0 B readable`）；
 *  - 就算能读，用户也没法自己抓。
 *
 * 所以改成「模块自己记 + 广播给 UI + 落盘」，用户在设置页把「日志级别」调到
 * **调试** 后，进入「RFCOMM 调试」页就能看到每一帧的收发字节，还能导出成文件
 * 直接发给我们。
 *
 * 三层输出：
 *  1. 环形缓冲 [MAX_RECENT_LOGS] 条，进入调试页时整体重放；
 *  2. 实时广播 [OppoPodsAction.ACTION_RFCOMM_LOG] 给模块自己的 UI 进程；
 *  3. 落盘到 `filesDir/fiil_module_log.txt`，超过 [MAX_FILE_BYTES] 自动截半。
 *
 * **进程安全**：本对象同时被 `com.android.bluetooth`（控制器）和模块 UI 进程使用。
 * 跨进程时环形缓冲各自独立，但落盘路径一致（都用同一个 app 的 filesDir，
 * 蓝牙进程有读权限）；广播才是 UI 真正看到数据的通道。
 */
object RfcommLog {

    private const val TAG = "FiilPods-Log"
    private const val MAX_RECENT_LOGS = 400
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val FILE_NAME = "fiil_module_log.txt"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val recentLogs = ArrayDeque<Entry>()

    /** 用户是否打开了调试页（决定要不要发实时广播，避免无谓唤醒 UI 进程）。 */
    @Volatile
    private var enabled = false

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val time: String,
    )

    fun setEnabled(value: Boolean, context: Context? = null) {
        enabled = value
        if (value) {
            context?.let { replayRecent(it) }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun clear(context: Context? = null) {
        synchronized(recentLogs) { recentLogs.clear() }
        context?.let { ctx ->
            runCatching {
                File(ctx.filesDir, FILE_NAME).delete()
            }.onFailure { Log.w(TAG, "clear log file failed: ${it.message}") }
        }
    }

    fun d(context: Context?, tag: String, message: String) = log(context, "D", tag, message)
    fun i(context: Context?, tag: String, message: String) = log(context, "I", tag, message)
    fun w(context: Context?, tag: String, message: String) = log(context, "W", tag, message)
    fun e(context: Context?, tag: String, message: String) = log(context, "E", tag, message)

    /** 十六进制转储，用于收发帧。`00 27 01 00 04 FF 02 02 34`。 */
    fun hex(bytes: ByteArray, limit: Int = 64): String {
        if (bytes.isEmpty()) return "(empty)"
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            val v = bytes[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v).uppercase(Locale.US))
        }
        if (bytes.size > n) sb.append(" …(+").append(bytes.size - n).append(')')
        return sb.toString()
    }

    private fun log(context: Context?, level: String, tag: String, message: String) {
        // 等级门控：设置页「日志级别」= 关闭 时完全不记，避免用户无感时白耗 IO。
        if (ConfigManager.logLevel() <= ConfigManager.LOG_LEVEL_OFF) return
        val entry = Entry(level, tag, message, timeFormat.format(Date()))
        synchronized(recentLogs) {
            recentLogs.addLast(entry)
            while (recentLogs.size > MAX_RECENT_LOGS) recentLogs.removeFirst()
        }
        // 落盘与广播都不允许影响主链路：这里是发帧/收帧路径，抛异常会把蓝牙栈带崩。
        val ctx = context ?: return
        runCatching { appendToFile(ctx, entry) }
            .onFailure { Log.w(TAG, "append log failed: ${it.message}") }
        if (isEnabled()) {
            runCatching { ctx.sendRfcommLog(entry) }
                .onFailure { Log.w(TAG, "broadcast log failed: ${it.message}") }
        }
    }

    private fun replayRecent(context: Context) {
        synchronized(recentLogs) { recentLogs.toList() }.forEach {
            runCatching { context.sendRfcommLog(it) }
        }
    }

    private fun appendToFile(context: Context, entry: Entry) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.length() > MAX_FILE_BYTES) {
            // 超限就保留后半段，避免无限增长（模块跑一整天能写几十 MB）。
            val keep = file.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
            file.writeText(keep)
        }
        file.appendText("${entry.time} ${entry.level}/${entry.tag} ${entry.message}\n")
    }

    /** 读取落盘日志（供 UI 导出/查看）。 */
    fun readFile(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * 注意：**必须** `setPackage(模块自己的包名)`。
     *
     * 控制器跑在 `com.android.bluetooth` 进程里，`Context.packageName` 是
     * `com.android.bluetooth`；用它做主包名的话日志会绕回蓝牙进程自己，
     * 模块 UI 一条都收不到（这正是 v1 `publishState` 踩过的坑）。
     */
    private fun Context.sendRfcommLog(entry: Entry) {
        val intent = Intent(OppoPodsAction.ACTION_RFCOMM_LOG).apply {
            setPackage(FiilConfig.MODULE_PACKAGE)
            putExtra("level", entry.level)
            putExtra("tag", entry.tag)
            putExtra("message", entry.message)
            putExtra("time", entry.time)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        sendBroadcast(intent)
    }
}
