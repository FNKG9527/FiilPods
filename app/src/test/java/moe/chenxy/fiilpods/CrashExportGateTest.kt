package moe.chenxy.fiilpods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃页也必须能导出诊断日志，但重复点按不能同时启动多个大文件导出任务。
 */
class CrashExportGateTest {
    @Test
    fun `only one crash log export can run until it finishes`() {
        val gate = CrashExportGate()

        assertTrue(gate.tryBegin())
        assertTrue(gate.isExporting())
        assertFalse(gate.tryBegin())

        gate.finish()

        assertFalse(gate.isExporting())
        assertTrue(gate.tryBegin())
    }
}
