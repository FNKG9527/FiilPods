package moe.chenxy.fiilpods.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import moe.chenxy.fiilpods.pods.RfcommLog
import java.io.File

/**
 * 模块自己的 ContentProvider。
 *
 * 两件事：
 *  1. `openFile` —— 把用户自定义的耳机图片以 `content://` 暴露给 UI 的 AsyncImage；
 *  2. `call("appendLog")` —— 让**别的进程**（`com.android.bluetooth` 等）把日志行
 *     交回模块进程落盘。见 [RfcommLog] 里的跨进程说明。
 *
 * 第 2 条是这个 Provider 存在的关键原因：日志主体产生在蓝牙进程，
 * 而蓝牙进程的 `filesDir` 模块读不到；`call` 是唯一既能跨进程、又能在
 * 模块进程没启动时把它拉起来的通道。
 */
class PodImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Cross-process log forwarding.
     *
     * This provider remains exported for image readers, but its write-like `appendLog` operation
     * accepts only this module or the platform Bluetooth package. Validate the caller *before*
     * materializing its array/list, then bound both line count and UTF-8 bytes before disk I/O.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_APPEND_LOG) return null
        val ctx = context ?: return null
        val callingUid = Binder.getCallingUid()
        val bluetoothUid = resolveBluetoothUid(ctx.packageManager)
        if (!isTrustedLogCaller(callingUid, ctx.applicationInfo.uid, bluetoothUid)) {
            throw SecurityException("log bridge is restricted to the module or Bluetooth UID")
        }

        val rawLines: Array<out String?> = extras?.getStringArray(KEY_LINES) ?: return null
        if (!isBoundedAppendPayload(rawLines, MAX_APPEND_LINES, MAX_APPEND_UTF8_BYTES)) return null
        // Bundle is Java/platform-typed: validation completed before this List allocation.
        val acceptedLines = rawLines.map(::checkNotNull)
        val accepted = RfcommLog.appendBridgedLines(ctx, acceptedLines)
        return Bundle().apply { putInt(KEY_ACCEPTED, accepted) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Pod images are read-only")
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val allowedNames = PodImagePrefs.load(prefs).flatMap { earphone ->
            PodImageResource.entries.mapNotNull { resource ->
                earphone.imagePath(resource)?.let { File(it).name }
            }
        }.toSet()
        if (fileName !in allowedNames) return null
        val dir = PodImagePrefs.imageDir(context)
        val file = File(dir, fileName).canonicalFile
        if (!file.path.startsWith(dir.canonicalPath) || !file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val MAX_APPEND_LINES = 20
        private const val MAX_APPEND_UTF8_BYTES = 64 * 1024

        /** `call()` 的方法名：追加若干行日志。 */
        const val METHOD_APPEND_LOG = "appendLog"

        /** `extras` 里的键：待追加的行（String[]）。 */
        const val KEY_LINES = "lines"

        /** 返回值的键：实际接收到的行数。 */
        const val KEY_ACCEPTED = "accepted"

        internal fun isTrustedLogCaller(callingUid: Int, moduleUid: Int, bluetoothUid: Int?): Boolean =
            callingUid == moduleUid || bluetoothUid == callingUid

        /**
         * Checks the incoming binder payload without converting it to a List or encoding a second
         * full copy. Each line acquires one newline byte because RfcommLog writes it that way.
         * Unpaired UTF-16 surrogates are rejected rather than silently replaced and undercounted.
         */
        internal fun isBoundedAppendPayload(
            lines: Array<out String?>,
            maxLines: Int,
            maxUtf8Bytes: Int,
        ): Boolean {
            if (lines.isEmpty() || lines.size > maxLines || maxUtf8Bytes < 0) return false
            var used = 0
            for (line in lines) {
                // A Binder Bundle is Java/platform-typed, so reject null before any list is made.
                if (line == null) return false
                // One incoming value must become exactly one physical log line. Otherwise a caller
                // could forge additional records (or escape the bounded line-count policy).
                if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return false
                // Reserve the terminating newline which RfcommLog writes for every accepted line.
                val remainingForLine = maxUtf8Bytes - used - 1
                val lineBytes = utf8BytesAtMost(line, remainingForLine) ?: return false
                used += lineBytes + 1
            }
            return true
        }

        private fun resolveBluetoothUid(packageManager: PackageManager): Int? = runCatching {
            packageManager.getApplicationInfo(BLUETOOTH_PACKAGE, 0).uid
        }.getOrNull()

        /** Returns null for invalid UTF-16 or when [maxBytes] would be exceeded. */
        private fun utf8BytesAtMost(value: String, maxBytes: Int): Int? {
            if (maxBytes < 0) return null
            var bytes = 0
            var index = 0
            while (index < value.length) {
                val char = value[index]
                val added = when {
                    char.code <= 0x7F -> 1
                    char.code <= 0x7FF -> 2
                    char.isHighSurrogate() -> {
                        if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return null
                        index++
                        4
                    }
                    char.isLowSurrogate() -> return null
                    else -> 3
                }
                if (added > maxBytes - bytes) return null
                bytes += added
                index++
            }
            return bytes
        }
    }
}
