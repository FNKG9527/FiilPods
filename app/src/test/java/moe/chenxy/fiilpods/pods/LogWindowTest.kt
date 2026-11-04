package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 钉死「导出日志」的时间窗与体积裁剪规则。
 *
 * 为什么值得单独测：导出文件是给开发者看现场的唯一材料，裁剪逻辑一旦悄悄
 * 把最新那段丢掉（或者反过来把一周前的日志全塞进去），问题会被误判成
 * 「日志里看不出来」，而这种错永远不会有人报回来 —— 所以用固定时间戳把它锁住。
 *
 * 注意：这里的格式化器**故意不设时区**，与 [LogWindow.parseEpochMs] 保持一致
 * （它也走默认时区）。设成 UTC 会让「格式化」与「解析」用两套时区，测试在任何
 * 非 UTC 的机器上都会假失败。
 */
class LogWindowTest {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        isLenient = false
    }

    private fun line(epochMs: Long, message: String = "hello"): String {
        return "${stamp.format(Date(epochMs))} I/RFCOMM $message"
    }

    /** 一个任意的基准时刻，避开 1970 让时间戳看起来像真实日志。 */
    private val base = 1_760_000_000_000L

    private fun at(offsetMs: Long) = base + offsetMs

    @Test
    fun `lines older than the window are dropped and counted`() {
        val lines = listOf(
            line(at(-90 * 60_000L), "old"),
            line(at(-30 * 60_000L), "keep-1"),
            line(at(0), "keep-2"),
        )
        val selection = LogWindow.select(lines, sinceEpochMs = at(-60 * 60_000L), maxBytes = 1_000_000)

        assertEquals(listOf("keep-1", "keep-2"), selection.lines.map { it.substringAfter("RFCOMM ") })
        assertEquals(1, selection.droppedByAge)
        assertEquals(0, selection.droppedBySize)
        assertEquals(0, selection.droppedUnparsable)
    }

    @Test
    fun `a line exactly at the window start is kept`() {
        val boundary = at(-60 * 60_000L)
        val selection = LogWindow.select(listOf(line(boundary)), sinceEpochMs = boundary, maxBytes = 1_000_000)

        assertEquals(1, selection.lines.size)
        assertEquals(0, selection.droppedByAge)
    }

    @Test
    fun `lines without a parsable stamp are dropped and counted separately`() {
        val lines = listOf(
            "  continuation of a half-written line",
            "",
            line(at(0), "good"),
        )
        val selection = LogWindow.select(lines, sinceEpochMs = at(-60 * 60_000L), maxBytes = 1_000_000)

        assertEquals(1, selection.lines.size)
        assertEquals(2, selection.droppedUnparsable)
        assertEquals(0, selection.droppedByAge)
    }

    @Test
    fun `size cap keeps the newest lines`() {
        val lines = (0 until 10).map { line(at(it * 1_000L), "n$it") }
        val perLine = LogWindow.lineBytes(lines.first())

        val selection = LogWindow.select(
            lines,
            sinceEpochMs = at(-60 * 60_000L),
            maxBytes = perLine * 3,
        )

        assertEquals(listOf("n7", "n8", "n9"), selection.lines.map { it.substringAfter("RFCOMM ") })
        assertEquals(7, selection.droppedBySize)
        assertTrue("统计的字节数不能超过上限", selection.bytes <= perLine * 3)
    }

    @Test
    fun `a single oversized line is still kept so the export is never empty`() {
        // 一行超长（比如某次回包被完整打印）时限流不应把它也丢掉，
        // 否则 maxBytes 配得过小时会出现「有日志但导出为空」。
        val huge = line(at(0), "x".repeat(4096))
        val selection = LogWindow.select(listOf(huge), sinceEpochMs = 0L, maxBytes = 64)

        assertEquals(1, selection.lines.size)
        assertEquals(0, selection.droppedBySize)
    }

    @Test
    fun `empty input yields an empty selection with zero counters`() {
        val selection = LogWindow.select(emptyList(), sinceEpochMs = 0L, maxBytes = 1024)

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.bytes)
        assertNull(selection.firstEpochMs())
        assertNull(selection.lastEpochMs())
    }

    @Test
    fun `first and last epoch come from the kept lines`() {
        val lines = listOf(line(at(-10 * 60_000L)), line(at(0)))
        val selection = LogWindow.select(lines, sinceEpochMs = at(-60 * 60_000L), maxBytes = 1_000_000)

        assertEquals(at(-10 * 60_000L), selection.firstEpochMs())
        assertEquals(at(0), selection.lastEpochMs())
    }

    @Test
    fun `parseEpochMs rejects lines shorter than the fixed stamp width`() {
        // 落盘被截半时留下的残行就是这种：开头还有时间戳的前几位，但不完整。
        assertNull(LogWindow.parseEpochMs("2026-11-04 20:00"))
        assertNull(LogWindow.parseEpochMs(""))
        assertNull(LogWindow.parseEpochMs("I/RFCOMM no stamp at all here"))
        assertEquals(23, LogWindow.STAMP_WIDTH)
    }

    @Test
    fun `parseEpochMs round-trips a well-formed stamp`() {
        val epoch = at(0)
        assertEquals(epoch, LogWindow.parseEpochMs(line(epoch)))
    }

    @Test
    fun `lineBytes counts the trailing newline and uses utf-8`() {
        // ASCII 一行 = 长度 + 1；中文按 UTF-8 三字节算。
        assertEquals("abc".length + 1, LogWindow.lineBytes("abc"))
        assertEquals("日志".toByteArray(Charsets.UTF_8).size + 1, LogWindow.lineBytes("日志"))
    }
}
