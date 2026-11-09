package moe.chenxy.fiilpods.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import moe.chenxy.fiilpods.CrashHandler
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.pods.LogWindow
import moe.chenxy.fiilpods.pods.RfcommLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 诊断日志导出。
 *
 * 用户点设置页「导出日志」后，把「排查一次问题真正需要看的东西」拼成**一个 txt**：
 *
 * | 区段 | 内容 | 为什么要有 |
 * |---|---|---|
 * | 元信息 | 时间、应用/版本号、包名、机型、Android/API、ABI、指纹 | 复现环境 |
 * | 配置快照 | 日志级别、伪设备 ID、超级岛模式、点击行为、自动游戏模式…… | 「是不是设置问题」一秒排除 |
 * | 崩溃记录 | 最近一次自捕获堆栈（如果有） | 闪退不需要用户再截图 |
 * | 运行日志 | 协议收发帧、hook 决策、状态变化 | 真正定位问题的部分 |
 * | 尾部统计 | 行数、字节数、丢弃了多少行及原因 | 让「日志不全」变成可解释的事实 |
 *
 * ## 时间窗与上限（这两个数是刻意定的）
 *
 * - **时间窗 60 分钟**（[WINDOW_MINUTES]）：从「出问题」到「用户想起来导出」，
 *   一小时足够覆盖；再放宽会把上一次断连/上一次切模式的噪声也带进来，
 *   反而更难读。窗口从**导出时刻**往回算，不依赖任何启动时间。
 * - **上限 2 MiB**（[MAX_BYTES]）：约 2 万 5 千行，远高于一小时正常使用的量
 *   （30 秒心跳 + 少量帧 ≈ 几百行）。设成上限是为了兜住「日志级别=调试 且
 *   反复重连」这种病态情况——那种场景一小时能刷出几十 MB，直接导出会把
 *   手机卡住、文件也没法发出去。**超限时丢最旧的，保留最新的**，因为越靠后
 *   越接近出问题的时刻。
 *
 * 落盘位置：`Android/data/<包名>/files/logs/`，文件名带时间戳，不覆盖历史导出。
 */
object LogExporter {

    /** 时间窗：导出时刻往回 [WINDOW_MINUTES] 分钟。 */
    const val WINDOW_MINUTES = 60

    /** 日志区段的字节上限：2 MiB。 */
    const val MAX_BYTES = 2 * 1024 * 1024

    const val DIR_NAME = "logs"

    private const val LOG_FILE_NAME = "fiil_module_log.txt"

    private const val CRASH_TAIL_CHARS = 16_000

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    data class Result(
        val file: File,
        val bytes: Long,
        val logLines: Int,
        val windowStart: Long,
        val windowEnd: Long,
        val droppedByAge: Int,
        val droppedBySize: Int,
        val droppedUnparsable: Int,
        /** 日志级别为「关闭」时为 true —— 导出文件里只有元信息，没有运行日志。 */
        val logLevelOff: Boolean,
    ) {
        val shareText: String
            get() = "${file.absolutePath} (${bytes} B, $logLines 行)"
    }

    /**
     * 生成导出文件。**必须在后台线程调用**（会读几百 KB 文件）。
     *
     * 整个过程包在 `runCatching` 里：导出失败不该让设置页崩掉，调用方拿
     * `Result.failure` 去提示即可。
     */
    fun export(context: Context, nowMs: Long = System.currentTimeMillis()): kotlin.Result<Result> =
        runCatching { exportOrThrow(context.applicationContext, nowMs) }

    private fun exportOrThrow(context: Context, nowMs: Long): Result {
        val windowStartMs = nowMs - TimeUnit.MINUTES.toMillis(WINDOW_MINUTES.toLong())
        val logLevel = ConfigManager.logLevel()

        val raw = RfcommLog.readFile(context).orEmpty()
        val allLines = if (raw.isEmpty()) emptyList() else raw.split('\n').filter { it.isNotEmpty() }
        val selection = LogWindow.select(allLines, windowStartMs, MAX_BYTES)

        val body = buildString {
            appendHeader(context, nowMs, windowStartMs, logLevel)
            appendConfig(context)
            appendCrash(context)
            appendLog(selection, logLevel, allLines.size)
            appendFooter(selection, allLines.size, logLevel)
        }

        val dir = outputDir(context)
        dir.mkdirs()
        val file = File(dir, "FiilPods-log-${fileStamp.format(Date(nowMs))}.txt")
        file.writeText(body)

        return Result(
            file = file,
            bytes = file.length(),
            logLines = selection.lines.size,
            windowStart = windowStartMs,
            windowEnd = nowMs,
            droppedByAge = selection.droppedByAge,
            droppedBySize = selection.droppedBySize,
            droppedUnparsable = selection.droppedUnparsable,
            logLevelOff = logLevel <= ConfigManager.LOG_LEVEL_OFF,
        )
    }

    /** 导出目录：优先外部私有目录（用户能用文件管理器/USB 拿到），失败退回内部。 */
    fun outputDir(context: Context): File =
        context.getExternalFilesDir(DIR_NAME)
            ?: File(context.filesDir, DIR_NAME)

    private fun StringBuilder.appendHeader(
        context: Context,
        nowMs: Long,
        windowStartMs: Long,
        logLevel: Int,
    ) {
        appendLine("===== FiilPods 日志导出 =====")
        appendLine("导出时间 : ${stamp.format(Date(nowMs))} (${zoneLabel()})")
        appendLine("时间窗   : 最近 $WINDOW_MINUTES 分钟")
        appendLine("           从 ${stamp.format(Date(windowStartMs))}")
        appendLine("           到 ${stamp.format(Date(nowMs))}")
        appendLine("单段上限 : ${humanBytes(MAX_BYTES.toLong())}（超出丢最旧的行）")
        appendLine("日志级别 : ${logLevelLabel(logLevel)} ($logLevel)")
        appendLine()
        appendLine("应用     : ${appLabel(context)}")
        appendLine("包名     : ${context.packageName}")
        appendLine("设备     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI      : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        appendLine("指纹     : ${Build.FINGERPRINT}")
        appendLine()
    }

    private fun StringBuilder.appendConfig(context: Context) {
        // 直接读一次磁盘上的 prefs 再取快照：`ConfigManager.current()` 是进程内缓存，
        // 若这时还没人调过 refreshFromPrefs（例如从崩溃恢复后直接进设置页导出），
        // 快照会静默退回默认值，反而误导排障。读失败才退回缓存。
        val config = runCatching {
            val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            ConfigManager.refreshFromPrefs(prefs)
        }.getOrElse { ConfigManager.current() }
        appendLine("--- 配置快照 ---")
        appendLine("伪设备 ID      : ${config.fakeDeviceId}")
        appendLine("超级岛模式     : ${config.islandMode} (${islandModeLabel(config.islandMode)})")
        appendLine("超级岛时机     : ${config.islandShowTimings.sorted()}")
        appendLine("焦点通知       : ${config.focusNotification}")
        appendLine("通知点击行为   : ${config.notificationClickAction}")
        appendLine("更多点击行为   : ${config.moreClickAction}")
        appendLine("融合卡片能力   : ${config.milinkCardFeatures.sorted()}")
        appendLine("自动游戏模式   : ${config.autoGameMode}")
        appendLine("日志级别       : ${config.logLevel}")
        appendLine()

        val logFile = File(context.filesDir, LOG_FILE_NAME)
        if (logFile.isFile) {
            appendLine("落盘日志文件   : $LOG_FILE_NAME (${humanBytes(logFile.length())})")
            appendLine()
        }
    }

    private fun StringBuilder.appendCrash(context: Context) {
        appendLine("--- 崩溃记录 ---")
        val crash = runCatching { CrashHandler.readLatest(context) }.getOrNull()
        if (crash.isNullOrBlank()) {
            appendLine("(无)")
        } else {
            // 堆栈可能很长，只留尾部：异常类型和最深几层帧在最后，前面是重复的框架帧。
            val text = if (crash.length > CRASH_TAIL_CHARS) {
                "…(前 ${crash.length - CRASH_TAIL_CHARS} 字符已省略)\n" +
                    crash.takeLast(CRASH_TAIL_CHARS)
            } else {
                crash
            }
            appendLine(text.trimEnd())
        }
        appendLine()
    }

    private fun StringBuilder.appendLog(
        selection: LogWindow.Selection,
        logLevel: Int,
        totalLines: Int,
    ) {
        appendLine("--- 运行日志 ---")
        when {
            logLevel <= ConfigManager.LOG_LEVEL_OFF -> {
                appendLine("(日志级别为「关闭」，本次没有记录任何运行日志)")
                appendLine("开启方法：设置页 → 日志级别 → 基本 或 调试，然后复现一次问题再来导出。")
            }

            totalLines == 0 -> {
                appendLine("(还没有写入任何日志)")
                appendLine("如果刚安装完：由于 LSPosed 只在进程启动时注入，需要关开一次蓝牙或重启手机。")
            }

            selection.isEmpty -> {
                appendLine("(最近 $WINDOW_MINUTES 分钟内没有日志)")
                appendLine("文件里共有 $totalLines 行，但都早于时间窗。")
            }

            else -> {
                val from = selection.firstEpochMs()?.let { stamp.format(Date(it)) } ?: "?"
                val to = selection.lastEpochMs()?.let { stamp.format(Date(it)) } ?: "?"
                appendLine("窗口内 ${selection.lines.size} 行，从 $from 到 $to")
                appendLine()
                selection.lines.forEach { appendLine(it) }
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendFooter(
        selection: LogWindow.Selection,
        totalLines: Int,
        logLevel: Int,
    ) {
        appendLine("--- 统计 ---")
        appendLine("落盘总行数   : $totalLines")
        appendLine("本次导出行数 : ${selection.lines.size}")
        appendLine("日志字节数   : ${humanBytes(selection.bytes.toLong())}")
        if (selection.droppedByAge > 0) appendLine("早于时间窗丢弃: ${selection.droppedByAge} 行")
        if (selection.droppedBySize > 0) {
            appendLine("超出大小上限丢弃: ${selection.droppedBySize} 行（保留最新的）")
        }
        if (selection.droppedUnparsable > 0) {
            appendLine("残缺行丢弃   : ${selection.droppedUnparsable} 行（没有可解析的时间戳）")
        }
        if (logLevel < ConfigManager.LOG_LEVEL_DEBUG) {
            appendLine("提示         : 日志级别不是「调试」，协议层细节（每一帧的收发）没有记全；")
            appendLine("               复现前把它调到「调试」能拿到最完整的信息。")
        }
        appendLine()
        appendLine("===== 导出结束 =====")
    }

    private fun appLabel(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.applicationInfo?.loadLabel(context.packageManager) ?: "FiilPods"} " +
            "${info.versionName} (versionCode ${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun zoneLabel(): String {
        val zone = TimeZone.getDefault()
        val offsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(offsetMinutes)
        return "%s GMT%s%02d:%02d".format(Locale.US, zone.id, sign, abs / 60, abs % 60)
    }

    private fun islandModeLabel(mode: Int): String = when (mode) {
        ConfigManager.ISLAND_MODE_NONE -> "关闭"
        ConfigManager.ISLAND_MODE_OFFICIAL -> "官方超级岛"
        ConfigManager.ISLAND_MODE_MODULE -> "模块自绘"
        else -> "未知"
    }

    private fun logLevelLabel(level: Int): String = when (level) {
        ConfigManager.LOG_LEVEL_OFF -> "关闭"
        ConfigManager.LOG_LEVEL_BASIC -> "基本"
        ConfigManager.LOG_LEVEL_DEBUG -> "调试"
        else -> "未知"
    }

    fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    /**
     * 造一个「分享出去」的 Intent。
     *
     * 走 [FileProvider]：Android 7 起 `file://` 直接抛 `FileUriExposedException`。
     * 调用方负责 `startActivity(Intent.createChooser(...))`，并处理
     * `ActivityNotFoundException`（真机上如果用户没有任何可分享的应用）。
     */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, LogFileProvider.AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
