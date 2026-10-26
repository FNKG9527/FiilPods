package moe.chenxy.fiilpods

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自捕获。
 *
 * 背景：本模块的 UI 层是从 OppoPods 整体移植过来的，移植后**从未在真机上跑过**。
 * 用户反馈「点开闪退」，但 adb 连的是另一台旧机、logcat 缓冲区又被 ROM 关掉了，
 * 拿不到堆栈。所以这里在进程级装一个兜底 handler，把堆栈落盘到三个位置，
 * 无论应用处于什么状态（甚至是 ContentProvider 初始化阶段）都能留下证据：
 *
 *  1. `filesDir/crash_last.txt`                    —— 应用私有目录，永远可写
 *  2. `getExternalFilesDir(null)/crash_last.txt`   —— `/sdcard/Android/data/moe.chenxy.fiilpods/files/`，用户可直接取
 *  3. Log.e                                         —— 万一 logcat 可用
 *
 * 落盘后**仍然把异常交给系统默认 handler**，保证行为与未安装时一致（系统照常弹「应用已停止」），
 * 不改变任何异常语义，也不吞异常。
 */
object CrashHandler {

    private const val TAG = "FiilPods-Crash"
    private const val FILE_NAME = "crash_last.txt"

    @Volatile
    private var installed = false

    /** 最近一次被捕获的崩溃文本，供 Activity 直接显示在界面上（用户截图即可）。 */
    @Volatile
    var lastCrashText: String? = null
        private set

    fun install(context: Context) {
        if (installed) return
        installed = true

        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val text = buildReport(app, thread, throwable)
            lastCrashText = text
            runCatching { write(app, text) }
            runCatching { Log.e(TAG, text) }

            // 交回系统默认处理，保持「应用已停止」的原生行为。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return buildString {
            appendLine("===== FiilPods crash =====")
            appendLine("time    : $time")
            appendLine("thread  : ${thread.name}")
            appendLine("device  : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("version : ${appVersion(context)}")
            appendLine()
            append(sw.toString())
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun write(context: Context, text: String) {
        val targets = buildList {
            add(File(context.filesDir, FILE_NAME))
            context.getExternalFilesDir(null)?.let { add(File(it, FILE_NAME)) }
        }
        targets.forEach { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(text)
            }
        }
    }

    /** 供 Activity 展示：返回最近一次崩溃文本，没有则 null。 */
    fun readLatest(context: Context): String? {
        lastCrashText?.let { return it }
        val candidates = buildList {
            add(File(context.filesDir, FILE_NAME))
            context.getExternalFilesDir(null)?.let { add(File(it, FILE_NAME)) }
        }
        for (f in candidates) {
            val t = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    /** 用户确认看过之后调用来清空，避免下次启动又弹。 */
    fun clear(context: Context) {
        lastCrashText = null
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        runCatching { context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).delete() } }
    }
}
