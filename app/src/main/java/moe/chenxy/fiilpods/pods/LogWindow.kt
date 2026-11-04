package moe.chenxy.fiilpods.pods

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志时间窗筛选（纯函数，不碰 Android，方便单元测试）。
 *
 * 为什么要有它：模块的落盘日志是一条不断追加的文本，用户点「导出」时看到的
 * 应该是**出问题那一小段时间**，而不是几百 KB 里翻。这里负责三件事：
 *
 *  1. 按时间戳丢掉太旧的记录（默认最近 60 分钟，见 [LogExporter.WINDOW_MINUTES]）；
 *  2. 按字节上限从**最新的往回**保留，超出的部分丢掉并记账；
 *  3. 无法解析时间戳的残行（例如日志被截半时留下的半行）单独计数后丢掉，
 *     不让它们污染导出文件。
 *
 * 时间戳格式与 [RfcommLog] 落盘格式严格一致：`yyyy-MM-dd HH:mm:ss.SSS`。
 * 解析用 [ParsePosition] 而不是 `parse()` 的「容忍尾部垃圾」行为，
 * 避免 `2026-11-04 20:00:00.123` 被错解析成别的东西。
 */
object LogWindow {

    /** 一行日志里时间戳的固定宽度（`2026-11-04 20:00:00.123` = 23 字符）。 */
    const val STAMP_WIDTH = 23

    data class Selection(
        /** 选中要写进导出文件的原始行，保持原顺序。 */
        val lines: List<String>,
        /** 因为早于时间窗而被丢掉的行数。 */
        val droppedByAge: Int,
        /** 因为超出字节上限而被丢掉的行数（从最旧的开始丢）。 */
        val droppedBySize: Int,
        /** 时间戳无法解析、被丢掉的行数。 */
        val droppedUnparsable: Int,
        /** 选中行的总字节数（含换行）。 */
        val bytes: Int,
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()

        /** 选中的第一行的时间戳（毫秒），没有行时为 null。 */
        fun firstEpochMs(): Long? = lines.firstOrNull()?.let { LogWindow.parseEpochMs(it) }

        fun lastEpochMs(): Long? = lines.lastOrNull()?.let { LogWindow.parseEpochMs(it) }
    }

    /**
     * 解析一行开头的 `yyyy-MM-dd HH:mm:ss.SSS`。
     *
     * 返回 null 表示这行没有可用的时间戳（残行、或不是本模块写的行）。
     */
    fun parseEpochMs(line: String): Long? {
        if (line.length < STAMP_WIDTH) return null
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        format.isLenient = false
        val position = ParsePosition(0)
        val parsed = runCatching { format.parse(line, position) }.getOrNull() ?: return null
        if (position.index != STAMP_WIDTH) return null
        return parsed.time
    }

    /**
     * 从 [lines] 里挑出 `epochMs >= sinceEpochMs` 的部分，再按 [maxBytes] 从新到旧截断。
     *
     * [maxBytes] 是**选中行本身的字节数**上限，不含调用方之后追加的头部/尾部。
     */
    fun select(lines: List<String>, sinceEpochMs: Long, maxBytes: Int): Selection {
        if (lines.isEmpty()) return Selection(emptyList(), 0, 0, 0, 0)

        var droppedByAge = 0
        var droppedUnparsable = 0
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            val epoch = parseEpochMs(line)
            if (epoch == null) {
                droppedUnparsable++
                continue
            }
            if (epoch < sinceEpochMs) {
                droppedByAge++
                continue
            }
            kept.add(line)
        }

        if (kept.isEmpty()) return Selection(emptyList(), droppedByAge, 0, droppedUnparsable, 0)

        // 从最新往回装，装不下就停：保证留下的是「最后那段时间」而不是随便一段。
        val limit = if (maxBytes <= 0) Int.MAX_VALUE else maxBytes
        var bytes = 0
        var start = kept.size
        while (start > 0) {
            val cost = lineBytes(kept[start - 1])
            if (bytes + cost > limit && bytes > 0) break
            bytes += cost
            start--
            if (bytes >= limit) break
        }
        val droppedBySize = start
        return Selection(kept.subList(start, kept.size), droppedByAge, droppedBySize, droppedUnparsable, bytes)
    }

    /** 一行写进文件后的字节数：UTF-8 编码 + 1 个换行。 */
    fun lineBytes(line: String): Int = line.toByteArray(Charsets.UTF_8).size + 1

    /** 把 `yyyy-MM-dd HH:mm:ss.SSS` 前缀格式化成给人看的时间，解析不了就原样返回。 */
    fun stampOf(line: String): String? {
        val ms = parseEpochMs(line) ?: return null
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return format.format(Date(ms))
    }
}
