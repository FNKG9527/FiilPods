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
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A FIFO queue whose acknowledgements refer to queue records, not their string values.
 *
 * A pending batch stays in the queue until it is acknowledged. This prevents an
 * acknowledgement for an old `"same"` line from removing a newer equal line, and the
 * generation token makes acknowledgements from before [clear] harmless.
 */
class RemoteLogQueue<T>(
    private val maxPending: Int,
    private val maxBatch: Int,
) {
    class Batch<T> internal constructor(
        internal val generation: Long,
        internal val recordIds: List<Long>,
        val values: List<T>,
    )

    private data class Record<T>(
        val id: Long,
        val value: T,
        var fallbackWritten: Boolean = false,
    )

    private val records = ArrayDeque<Record<T>>()
    private var generation = 0L
    private var nextRecordId = 0L
    private var inFlight: Batch<T>? = null

    init {
        require(maxPending > 0) { "maxPending must be positive" }
        require(maxBatch > 0) { "maxBatch must be positive" }
    }

    /** Adds [value], or returns that newest value when the retained FIFO is full. */
    @Synchronized
    fun enqueue(value: T): List<T> {
        if (records.size >= maxPending) return listOf(value)
        records.addLast(Record(id = nextRecordId++, value = value))
        return emptyList()
    }

    /** Starts at most one in-flight FIFO batch; records remain queued until acknowledgement. */
    @Synchronized
    fun beginBatch(): Batch<T>? {
        if (inFlight != null || records.isEmpty()) return null
        val snapshot = records.take(minOf(maxBatch, records.size))
        return Batch(
            generation = generation,
            recordIds = snapshot.map { it.id },
            values = snapshot.map { it.value },
        ).also { inFlight = it }
    }

    /** Removes only the exact in-flight records represented by [batch]. */
    @Synchronized
    fun acknowledge(batch: Batch<T>): Boolean {
        if (!owns(batch)) return false
        repeat(batch.recordIds.size) { records.removeFirst() }
        inFlight = null
        return true
    }

    /**
     * Releases a failed batch without changing its FIFO position and returns records that have
     * not yet been written to the local fallback. A null result means the batch is stale.
     */
    @Synchronized
    fun reject(batch: Batch<T>): List<T>? {
        if (!owns(batch)) return null
        val fallback = records.take(batch.recordIds.size)
            .filterNot { it.fallbackWritten }
            .onEach { it.fallbackWritten = true }
            .map { it.value }
        inFlight = null
        return fallback
    }

    @Synchronized
    fun clear() {
        generation++
        records.clear()
        inFlight = null
    }

    @Synchronized
    fun pendingValues(): List<T> = records.map { it.value }

    @Synchronized
    fun pendingCount(): Int = records.size

    /** True only while [batch] belongs to the same queue generation. */
    @Synchronized
    fun isGenerationCurrent(batch: Batch<T>): Boolean = batch.generation == generation

    @Synchronized
    private fun owns(batch: Batch<T>): Boolean {
        if (batch.generation != generation || inFlight !== batch || records.size < batch.recordIds.size) {
            return false
        }
        return records.take(batch.recordIds.size).map { it.id } == batch.recordIds
    }
}

/**
 * Schedules a bounded remote log FIFO on one worker path. [send] and [fallback] are never
 * invoked by [enqueue]'s caller; a failed record is saved to the fallback at most once while
 * still being retried until it can be acknowledged remotely.
 */
class SerializedRemoteLogBridge(
    private val maxPending: Int,
    private val maxBatch: Int,
    private val flushIntervalMs: Long,
    private val executor: ScheduledExecutorService,
    private val send: (List<String>) -> Boolean,
    private val fallback: (List<String>) -> Unit,
) {
    private val queue = RemoteLogQueue<String>(maxPending = maxPending, maxBatch = maxBatch)
    private val stateLock = Any()
    private val dispatchLock = Any()
    private val overflowFallback = ArrayDeque<String>()
    private var overflowFallbackScheduled = false
    private var overflowFallbackGeneration = 0L
    private var scheduled: ScheduledFuture<*>? = null
    private var scheduleGeneration = 0L
    private var clearGeneration = 0L
    /** Holds new work until a clear's file barrier has completed on the worker. */
    private var clearBarrierGeneration: Long? = null
    private var running = false

    init {
        require(flushIntervalMs >= 0) { "flushIntervalMs must not be negative" }
    }

    fun enqueue(line: String) {
        val expedite = synchronized(stateLock) {
            // Capture and retain overflow while holding the same lock as clear(). Otherwise clear
            // could empty the fallback buffer between these two actions, then a pre-clear line
            // could be inserted afterward and written after the clear barrier.
            queue.enqueue(line).forEach { overflow ->
                if (overflowFallback.size < maxPending) overflowFallback.addLast(overflow)
            }
            scheduleOverflowFallbackLocked()
            queue.pendingCount() >= maxBatch
        }
        schedulePending(expedite)
    }

    fun clear() = clearThen {}

    /**
     * Invalidates queued batches immediately, then runs [afterInFlight] on the bridge's only
     * worker after a sender that already began has returned. A canonical-log clear uses this
     * barrier so a pre-clear append cannot recreate data after the clear itself.
     */
    fun clearThen(afterInFlight: () -> Unit) {
        val token = synchronized(stateLock) {
            clearGeneration++
            queue.clear()
            overflowFallback.clear()
            overflowFallbackScheduled = false
            overflowFallbackGeneration++
            scheduleGeneration++
            scheduled?.cancel(false)
            scheduled = null
            clearBarrierGeneration = clearGeneration
            clearGeneration
        }
        runCatching {
            executor.execute {
                // The single worker queues this behind any sender/fallback that already began.
                // A newer clear supersedes this barrier; work enqueued while it runs remains queued
                // and is scheduled only after the canonical file has been cleared.
                synchronized(dispatchLock) {
                    val current = synchronized(stateLock) { token == clearGeneration }
                    if (current) runCatching(afterInFlight)
                }
                val (shouldResume, expedite) = synchronized(stateLock) {
                    if (clearBarrierGeneration != token) return@synchronized false to false
                    clearBarrierGeneration = null
                    scheduleOverflowFallbackLocked()
                    (token == clearGeneration && queue.pendingCount() > 0) to
                        (queue.pendingCount() >= maxBatch)
                }
                if (shouldResume) schedulePending(expedite)
            }
        }.onFailure {
            synchronized(stateLock) {
                if (clearBarrierGeneration == token) clearBarrierGeneration = null
            }
        }
    }

    fun pendingCount(): Int = queue.pendingCount()

    private fun schedulePending(expedite: Boolean) {
        synchronized(stateLock) {
            if (clearBarrierGeneration != null || running || queue.pendingCount() == 0) return
            if (scheduled != null) {
                if (!expedite) return
                scheduled?.cancel(false)
                scheduled = null
            }
            scheduleLocked(if (expedite) 0L else flushIntervalMs)
        }
    }

    private fun scheduleLocked(delayMs: Long) {
        val token = ++scheduleGeneration
        scheduled = runCatching {
            executor.schedule({ flush(token) }, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun flush(token: Long) {
        val batch = synchronized(stateLock) {
            if (token != scheduleGeneration) return
            scheduled = null
            if (running) return
            val next = queue.beginBatch() ?: return
            running = true
            next
        }

        var retry = false
        try {
            val sent = synchronized(dispatchLock) {
                runCatching { send(batch.values) }.getOrDefault(false)
            }
            if (sent) {
                queue.acknowledge(batch)
            } else {
                val fallbackLines = queue.reject(batch)
                if (fallbackLines != null) {
                    retry = true
                    if (fallbackLines.isNotEmpty()) {
                        synchronized(dispatchLock) {
                            // A clear may have invalidated this batch between reject() and this
                            // worker turn. A non-empty queue is not sufficient here: it may contain
                            // only post-clear records. Preserve the rejected batch's generation.
                            if (queue.isGenerationCurrent(batch)) {
                                runCatching { fallback(fallbackLines) }
                            }
                        }
                    }
                }
            }
        } finally {
            synchronized(stateLock) {
                running = false
                if (clearBarrierGeneration == null && queue.pendingCount() > 0) {
                    val delay = when {
                        retry -> flushIntervalMs
                        queue.pendingCount() >= maxBatch -> 0L
                        else -> flushIntervalMs
                    }
                    scheduleLocked(delay)
                }
            }
        }
    }

    private fun scheduleOverflowFallbackLocked() {
        if (clearBarrierGeneration != null || overflowFallbackScheduled || overflowFallback.isEmpty()) return
        overflowFallbackScheduled = true
        val token = overflowFallbackGeneration
        runCatching { executor.execute { drainOverflowFallback(token) } }
            .onFailure { overflowFallbackScheduled = false }
    }

    private fun drainOverflowFallback(token: Long) {
        val lines = synchronized(stateLock) {
            if (token != overflowFallbackGeneration) return
            overflowFallbackScheduled = false
            val snapshot = overflowFallback.toList()
            overflowFallback.clear()
            snapshot
        }
        if (lines.isEmpty()) return
        synchronized(dispatchLock) {
            // Do not let a worker task that was queued before clear recreate discarded lines.
            if (synchronized(stateLock) { token == overflowFallbackGeneration }) {
                runCatching { fallback(lines) }
            }
        }
        synchronized(stateLock) { scheduleOverflowFallbackLocked() }
    }
}

/**
 * Bounded FIFO dispatcher for best-effort UI notifications.
 *
 * The caller only appends to a bounded queue. Delivery runs on [executor], so sending an Android
 * broadcast cannot stall RFCOMM callbacks or broadcast receivers that produce log entries.
 */
class SerializedAsyncDispatcher<T>(
    private val maxPending: Int,
    private val executor: Executor,
    private val deliver: (T) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var draining = false

    init {
        require(maxPending > 0) { "maxPending must be positive" }
    }

    /** Retains newest values when producers outrun the best-effort notification worker. */
    fun enqueue(value: T) {
        val startDrain = synchronized(lock) {
            if (pending.size >= maxPending) pending.removeFirst()
            pending.addLast(value)
            if (draining) false else {
                draining = true
                true
            }
        }
        if (startDrain) scheduleDrain()
    }

    fun enqueueAll(values: Iterable<T>) {
        var startDrain = false
        synchronized(lock) {
            values.forEach { value ->
                if (pending.size >= maxPending) pending.removeFirst()
                pending.addLast(value)
            }
            if (pending.isNotEmpty() && !draining) {
                draining = true
                startDrain = true
            }
        }
        if (startDrain) scheduleDrain()
    }

    fun clear() = synchronized(lock) { pending.clear() }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun scheduleDrain() {
        runCatching { executor.execute(::drain) }
            .onFailure {
                synchronized(lock) { draining = false }
            }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (pending.isEmpty()) {
                    draining = false
                    return
                }
                pending.removeFirst()
            }
            runCatching { deliver(next) }
        }
    }
}

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

    /** Never let unavailable Binder delivery retain an unbounded amount of RFCOMM traffic. */
    private const val REMOTE_MAX_PENDING_LINES = 200

    /** 20 valid Unicode lines of this size fit below the provider's 64 KiB UTF-8 limit. */
    private const val REMOTE_MAX_LINE_CHARS = 768

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val recentLogs = ArrayDeque<Entry>()

    /**
     * The host context is only used by the bridge worker. It is deliberately the application
     * context so the queue never retains a broadcast receiver or RFCOMM component instance.
     */
    @Volatile
    private var remoteContext: Context? = null

    private val remoteExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FiilPods-LogBridge").apply { isDaemon = true }
    }

    /**
     * One worker owns Binder calls and fallback writes. In particular, neither an RFCOMM callback
     * nor a broadcast receiver performs `ContentResolver.call()` inline.
     */
    private val remoteBridge = SerializedRemoteLogBridge(
        maxPending = REMOTE_MAX_PENDING_LINES,
        maxBatch = REMOTE_BATCH_LINES,
        flushIntervalMs = REMOTE_BATCH_INTERVAL_MS,
        executor = remoteExecutor,
        send = ::sendRemoteBatch,
        fallback = ::appendHostFallback,
    )

    private val fileWriteLock = Any()

    /** 用户是否打开了调试页（决定要不要发实时广播，避免无谓唤醒 UI 进程）。 */
    @Volatile
    private var enabled = false
    private val broadcastStateLock = Any()
    private var broadcastGeneration = 0L
    private val broadcastExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FiilPods-LogBroadcast").apply { isDaemon = true }
    }
    private val broadcastDispatcher = SerializedAsyncDispatcher<BroadcastDelivery>(
        maxPending = MAX_RECENT_LOGS,
        executor = broadcastExecutor,
        deliver = ::deliverBroadcast,
    )

    private data class BroadcastDelivery(
        val context: Context,
        val entry: Entry,
        val generation: Long,
    )

    data class Entry(
        val level: String,
        val tag: String,
        val message: String,
        val time: String,
        /** 落盘用的完整时间戳；跨进程转发后仍能按时间窗筛选。 */
        val epochMs: Long = System.currentTimeMillis(),
    )

    fun setEnabled(value: Boolean, context: Context? = null) {
        synchronized(broadcastStateLock) {
            enabled = value
            // Discard notifications queued for the prior UI lifetime. An already-running worker
            // checks this token before it invokes Android broadcast IPC.
            broadcastGeneration++
            broadcastDispatcher.clear()
        }
        if (value) context?.let(::replayRecent)
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
        enqueueBroadcasts(context.applicationContext, snapshot)
    }

    /**
     * Invalidates pending remote records immediately, then clears this process's local log file
     * only after a sender already in flight has finished. This preserves the existing per-process
     * clear behavior without expanding the exported Provider protocol beyond `appendLog`.
     */
    fun clear(context: Context? = null) {
        synchronized(recentLogs) { recentLogs.clear() }
        val app = context?.applicationContext
        remoteBridge.clearThen {
            if (app != null) deleteLogFile(app)
        }
    }

    fun d(context: Context?, tag: String, message: String) = log(context, "D", tag, message)
    fun i(context: Context?, tag: String, message: String) = log(context, "I", tag, message)
    fun w(context: Context?, tag: String, message: String) = log(context, "W", tag, message)
    fun e(context: Context?, tag: String, message: String) = log(context, "E", tag, message)

    /** 十六进制转储，用于收发帧。`00 27 01 00 04 FF 02 02 34`。 */
    fun hex(bytes: ByteArray, limit: Int = 64): String {
        if (bytes.isEmpty()) return "(empty)"
        val n = minOf(bytes.size, limit.coerceAtLeast(0))
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
        val entry = Entry(level, tag, message, synchronized(timeFormat) { timeFormat.format(Date(now)) }, now)
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
        enqueueBroadcast(ctx.applicationContext, entry)
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
    private fun formatLine(entry: Entry): String = synchronized(dateFormat) {
        "${dateFormat.format(Date(entry.epochMs))} ${entry.time} ${entry.level}/${entry.tag} ${entry.message}"
    }

    private fun enqueueRemote(context: Context, entry: Entry) {
        // This method is called on RFCOMM / broadcast paths. It only formats and enqueues; Binder
        // IPC and fallback file I/O are owned by [remoteBridge]'s dedicated worker.
        remoteContext = context.applicationContext
        remoteBridge.enqueue(formatLine(entry).boundedRemoteLine())
    }

    private fun String.boundedRemoteLine(): String {
        if (length <= REMOTE_MAX_LINE_CHARS) return this
        val end = if (this[REMOTE_MAX_LINE_CHARS - 1].isHighSurrogate()) {
            REMOTE_MAX_LINE_CHARS - 1
        } else {
            REMOTE_MAX_LINE_CHARS
        }
        return substring(0, end)
    }

    /** Enqueues a best-effort UI notification; Android broadcast IPC only happens on its worker. */
    private fun enqueueBroadcast(context: Context, entry: Entry) {
        synchronized(broadcastStateLock) {
            if (!enabled) return
            broadcastDispatcher.enqueue(
                BroadcastDelivery(context, entry, broadcastGeneration),
            )
        }
    }

    private fun enqueueBroadcasts(context: Context, entries: Iterable<Entry>) {
        synchronized(broadcastStateLock) {
            if (!enabled) return
            val generation = broadcastGeneration
            broadcastDispatcher.enqueueAll(entries.map { entry ->
                BroadcastDelivery(context, entry, generation)
            })
        }
    }

    /** Runs on [broadcastExecutor], not an RFCOMM callback or a broadcast receiver. */
    private fun deliverBroadcast(delivery: BroadcastDelivery) {
        val isCurrent = synchronized(broadcastStateLock) {
            enabled && delivery.generation == broadcastGeneration
        }
        if (!isCurrent) return
        runCatching { delivery.context.sendRfcommLog(delivery.entry) }
            .onFailure { Log.w(TAG, "broadcast log failed: ${it.message}") }
    }

    /** File mutation shared by append/clear paths; caller decides which process owns the file. */
    private fun deleteLogFile(context: Context): Boolean = runCatching {
        synchronized(fileWriteLock) {
            val file = File(context.filesDir, FILE_NAME)
            !file.exists() || file.delete()
        }
    }.onFailure { Log.w(TAG, "clear log file failed: ${it.message}") }
        .getOrDefault(false)

    /** Runs exclusively on [remoteExecutor], never on an RFCOMM or broadcast caller thread. */
    private fun sendRemoteBatch(lines: List<String>): Boolean {
        val context = remoteContext ?: return false
        return runCatching {
            val extras = Bundle().apply {
                putStringArray(PodImageProvider.KEY_LINES, lines.toTypedArray())
            }
            val response = context.contentResolver.call(
                Uri.parse("content://${PodImagePrefs.AUTHORITY}/log"),
                PodImageProvider.METHOD_APPEND_LOG,
                null,
                extras,
            )
            // `null`, a missing key, or a partial write are all failures: leave the exact queue
            // records pending so the FIFO can retry instead of silently dropping them.
            response != null && response.containsKey(PodImageProvider.KEY_ACCEPTED) &&
                response.getInt(PodImageProvider.KEY_ACCEPTED) == lines.size
        }.getOrElse {
            Log.w(TAG, "remote log bridge failed: ${it.message}")
            false
        }
    }

    /**
     * 由 [PodImageProvider] 在**模块进程**里调用，把宿主进程转来的行落到模块自己的文件。
     *
     * 失败不能让 Provider 抛异常回宿主，否则一次写日志会把蓝牙进程的调用链带崩。
     */
    fun appendBridgedLines(context: Context, lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        return runCatching {
            appendLines(context, lines)
            lines.size
        }.onFailure { Log.w(TAG, "append bridged lines failed: ${it.message}") }
            .getOrDefault(0)
    }

    /** Called only by [remoteBridge]'s worker after an unacknowledged remote batch. */
    private fun appendHostFallback(lines: List<String>) {
        // ⚠️ v3.3.1：这里以前写 `hostContext.filesDir/FILE_NAME`。
        // 但那是 com.android.bluetooth 的私有目录 —— 模块进程（另一个 UID）读不到，
        // 调试页导出的也只有模块自己的文件，结果这份「兜底日志」永远没人能看，
        // 只是白占蓝牙进程的存储。模块侧已经有有界队列 + Provider 重试，
        // 宿主拿不到 Provider 时只能丢弃；至少让丢弃这件事在 logcat 里可见。
        Log.w(TAG, "remote log bridge unavailable; dropped ${lines.size} line(s)")
    }

    private fun appendLines(context: Context, lines: List<String>) {
        check(isModuleProcess(context)) { "module log file belongs to the module process" }
        appendLinesToFile(File(context.filesDir, FILE_NAME), lines)
    }

    /**
     * Both module and host fallback files use the same strict byte cap. The fallback is bounded by
     * the remote queue as well, so a continuously unavailable provider cannot grow host storage.
     */
    private fun appendLinesToFile(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
            .toByteArray(StandardCharsets.UTF_8)
        val boundedPayload = utf8Tail(payload, MAX_FILE_BYTES.toInt())
        synchronized(fileWriteLock) {
            file.parentFile?.mkdirs()
            val retained = readUtf8Tail(file, MAX_FILE_BYTES.toInt() - boundedPayload.size)
            file.outputStream().use { output ->
                output.write(retained)
                output.write(boundedPayload)
            }
        }
    }

    /** Reads at most [maxBytes] tail bytes and skips an incomplete leading UTF-8 character. */
    private fun readUtf8Tail(file: File, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || !file.isFile) return ByteArray(0)
        val length = file.length()
        val count = minOf(length, maxBytes.toLong()).toInt()
        if (count == 0) return ByteArray(0)
        val bytes = ByteArray(count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(length - count)
            input.readFully(bytes)
        }
        return if (length > count) utf8TailFromCut(bytes) else bytes
    }

    /** Keeps a valid UTF-8 suffix by discarding leading continuation bytes after a raw cut. */
    private fun utf8Tail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        return utf8TailFromCut(bytes.copyOfRange(bytes.size - maxBytes, bytes.size))
    }

    private fun utf8TailFromCut(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
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
