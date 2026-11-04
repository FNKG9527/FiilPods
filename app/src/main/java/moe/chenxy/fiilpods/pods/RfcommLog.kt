package moe.chenxy.fiilpods.pods

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.util.Log
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.config.PodImagePrefs
import moe.chenxy.fiilpods.config.PodImageProvider
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块内自建日志通道。
 *
 * 为什么不用 logcat：
 *  - HyperOS 上 `persist.debug.dpis.global_log_enabled` 常被关掉，`logcat` 里
 *    根本读不到蓝牙进程的输出（实测 `ro.logd.size=64K` 且 `0 B readable`）；
 *  - 就算能读，用户也没法自己抓。
 *
 * 所以改成「模块自己记 + 广播给 UI + 落盘」，用户在设置页把「日志级别」调到
 * **调试** 后，进入「RFCOMM 调试」页就能看到每一帧的收发字节，
 * 也能在设置页「导出日志」里一次性拿到完整的 txt。
 *
 * 四层输出：
 *  1. 环形缓冲 [MAX_RECENT_LOGS] 条，进入调试页时整体重放；
 *  2. 实时广播 [OppoPodsAction.ACTION_RFCOMM_LOG] 给模块自己的 UI 进程；
 *  3. 落盘到模块自己的 `filesDir/fiil_module_log.txt`，超过 [MAX_FILE_BYTES] 截半；
 *  4. 跨进程转发：见下。
 *
 * ## 跨进程落盘（v3.2.0 新增）
 *
 * 本对象同时被 `com.android.bluetooth`（控制器所在进程，日志的主要来源）和
 * 模块 UI 进程使用。**两个进程的 `filesDir` 是不同目录**：
 *
 *  - 模块进程 → `/data/data/<模块>/files/fiil_module_log.txt`（导出功能读的就是它）
 *  - 蓝牙进程 → `/data/data/com.android.bluetooth/files/fiil_module_log.txt`（模块读不到）
 *
 * 也就是说，如果什么都不做，导出文件里**一帧协议日志都没有**——真正要看的
 * 恰恰全在蓝牙进程那边。所以非模块进程写日志时改成攒一批（[REMOTE_BATCH_LINES]
 * 行或 [REMOTE_BATCH_INTERVAL_MS] 毫秒）经 [PodImageProvider] 的
 * `call("appendLog")` 交给模块进程，由模块进程落到自己的文件里。
 *
 * 为什么不用广播：广播只有在模块 UI 进程活着时才有人接，用户还没打开 App
 * 时的日志会全丢；ContentProvider 的 `call` 会把模块进程拉起来，语义是「写
 * 进了就存下了」。
 *
 * 为什么不做成每行一次 binder：协议帧密度高，每帧一次跨进程调用会拖慢
 * 收发链路。攒批 + 时间窗后，正常使用下大约 1 秒最多一次。
 */
object RfcommLog {

    private const val TAG = "FiilPods-Log"
    private const val MAX_RECENT_LOGS = 400
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val FILE_NAME = "fiil_module_log.txt"

    /** 跨进程转发：攒够这么多行就立刻发。 */
    private const val REMOTE_BATCH_LINES = 20

    /** 跨进程转发：最多憋这么久发一次（毫秒）。 */
    private const val REMOTE_BATCH_INTERVAL_MS = 1_000L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val recentLogs = ArrayDeque<Entry>()

    /** 待转发的行（只在宿主进程里有内容）。 */
    private val pendingRemote = ArrayDeque<String>()
    private var lastRemoteFlush = 0L

    /** 用户是否打开了调试页（决定要不要发实时广播，避免无谓唤醒 UI 进程）。 */
    @Volatile
    private var enabled = false

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val time: String,
        /** 落盘用的完整时间戳；跨进程转发后仍能按时间窗筛选。 */
        val epochMs: Long = System.currentTimeMillis(),
    )

    fun setEnabled(value: Boolean, context: Context? = null) {
        enabled = value
        if (value) {
            context?.let { replayRecent(it) }
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * 打开调试页时把环形缓冲里的历史一次性补发过去。
     *
     * 只发广播、不转发落盘：这些行在产生时已经各自走过一次落盘路径（模块进程
     * 直接写，宿主进程攒批转给模块），重放只是为了让人一进调试页就看到上下文，
     * 再走一遍会把同样的行写两遍。
     */
    private fun replayRecent(context: Context) {
        val snapshot = synchronized(recentLogs) { recentLogs.toList() }
        if (snapshot.isEmpty()) return
        runCatching { snapshot.forEach { context.sendRfcommLog(it) } }
            .onFailure { Log.w(TAG, "replay recent logs failed: ${it.message}") }
    }

    fun clear(context: Context? = null) {
        synchronized(recentLogs) { recentLogs.clear() }
        synchronized(pendingRemote) { pendingRemote.clear() }
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
        val now = System.currentTimeMillis()
        val entry = Entry(level, tag, message, timeFormat.format(Date(now)), now)
        synchronized(recentLogs) {
            recentLogs.addLast(entry)
            while (recentLogs.size > MAX_RECENT_LOGS) recentLogs.removeFirst()
        }
        // 落盘与广播都不允许影响主链路：这里是发帧/收帧路径，抛异常会把蓝牙栈带崩。
        val ctx = context ?: return
        if (isModuleProcess(ctx)) {
            runCatching { appendLines(ctx, listOf(formatLine(entry))) }
                .onFailure { Log.w(TAG, "append log failed: ${it.message}") }
        } else {
            enqueueRemote(ctx, entry)
        }
        if (isEnabled()) {
            runCatching { ctx.sendRfcommLog(entry) }
                .onFailure { Log.w(TAG, "broadcast log failed: ${it.message}") }
        }
    }

    /**
     * 当前进程是不是模块自己。
     *
     * 用 `packageName` 而不是 `ApplicationInfo.uid` 判断：反 NiceName 场景下
     * 两者都可靠，但 `packageName` 不需要额外 IPC。
     */
    private fun isModuleProcess(context: Context): Boolean =
        context.packageName == FiilConfig.MODULE_PACKAGE

    /** `2026-11-04 20:00:00.123 I/RFCOMM/TX …` */
    private fun formatLine(entry: Entry): String =
        "${dateFormat.format(Date(entry.epochMs))} ${entry.time} ${entry.level}/${entry.tag} ${entry.message}"

    private fun enqueueRemote(context: Context, entry: Entry) {
        val shouldFlush = synchronized(pendingRemote) {
            pendingRemote.addLast(formatLine(entry))
            val now = System.currentTimeMillis()
            val flush = pendingRemote.size >= REMOTE_BATCH_LINES ||
                now - lastRemoteFlush >= REMOTE_BATCH_INTERVAL_MS
            if (flush) lastRemoteFlush = now
            flush
        }
        if (shouldFlush) flushRemote(context)
    }

    private fun flushRemote(context: Context) {
        val batch = synchronized(pendingRemote) {
            val list = pendingRemote.toList()
            pendingRemote.clear()
            list
        }
        if (batch.isEmpty()) return
        runCatching {
            val extras = Bundle().apply {
                putStringArray(PodImageProvider.KEY_LINES, batch.toTypedArray())
            }
            context.contentResolver.call(
                Uri.parse("content://${PodImagePrefs.AUTHORITY}/log"),
                PodImageProvider.METHOD_APPEND_LOG,
                null,
                extras,
            )
        }.onFailure {
            // 跨进程桥断了也不能丢当前进程的落盘：退回写自己的 filesDir。
            runCatching { appendLines(context, batch) }
            Log.w(TAG, "remote log bridge failed: ${it.message}")
        }
    }

    /**
     * 由 [PodImageProvider] 在**模块进程**里调用，把宿主进程转来的行落到模块自己的文件。
     *
     * 失败不能让 Provider 抛异常回宿主，否则一次写日志会把蓝牙进程的调用链带崩。
     */
    fun appendBridgedLines(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        runCatching { appendLines(context, lines) }
            .onFailure { Log.w(TAG, "append bridged lines failed: ${it.message}") }
    }

    private fun appendLines(context: Context, lines: List<String>) {
        if (!isModuleProcess(context)) {
            // 只有模块进程负责落盘；其他进程写进来的话路径不对，直接忽略。
            return
        }
        val file = File(context.filesDir, FILE_NAME)
        file.parentFile?.mkdirs()
        if (file.length() > MAX_FILE_BYTES) {
            // 超限保留后半段，避免无限增长（模块跑一整天能写几十 MB）。
            val keep = file.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
            file.writeText(keep)
        }
        val sb = StringBuilder(lines.sumOf { it.length + 1 })
        lines.forEach { sb.append(it).append('\n') }
        file.appendText(sb.toString())
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
