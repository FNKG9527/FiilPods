package moe.chenxy.fiilpods.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
     * 跨进程日志转发。
     *
     * 不做权限校验的理由：本 Provider 本来就是 `exported=true`，能被任何应用调到；
     * 但 `appendLog` 只追加文本、且写入目标固定为模块自己的 `filesDir`，
     * 调不坏别的东西。最坏情况是别人往里灌垃圾日志——用户导出时看得见，删得掉。
     *
     * 返回 `Bundle` 里带 `accepted` 行数，便于宿主进程判断是否真的写进去了。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_APPEND_LOG) return null
        val ctx = context ?: return null
        val lines = extras?.getStringArray(KEY_LINES) ?: return null
        if (lines.isEmpty()) return null
        RfcommLog.appendBridgedLines(ctx, lines.toList())
        return Bundle().apply { putInt(KEY_ACCEPTED, lines.size) }
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
        /** `call()` 的方法名：追加若干行日志。 */
        const val METHOD_APPEND_LOG = "appendLog"

        /** `extras` 里的键：待追加的行（String[]）。 */
        const val KEY_LINES = "lines"

        /** 返回值的键：实际接收到的行数。 */
        const val KEY_ACCEPTED = "accepted"
    }
}
