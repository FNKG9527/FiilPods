package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `FiilConfig.maskAddress()` 的自检。
 *
 * 存在理由：结项验收报告 P0-1 指出模块日志曾明文打印蓝牙 MAC（release 包里字符串被实证残留）。
 * 修法是把所有打日志的地方改成走 `maskAddress()`，但「改了没改全」靠肉眼是看不出来的——
 * 这个测试把脱敏规则钉死，同时也能防止将来有人图省事又拼回 `device.address`。
 */
class AddressMaskTest {

    @Test
    fun masksLastFourSegments() {
        assertEquals("B0:F1:**:**:**", FiilConfig.maskAddress("B0:F1:AA:BB:CC:DD"))
    }

    @Test
    fun keepsOuiPrefixForDiagnostics() {
        // 排障时要靠 OUI 判断芯片厂商，所以前两段必须保留。
        val masked = FiilConfig.maskAddress("B0:F1:AA:BB:CC:DD")
        assertEquals("B0:F1", masked.substringBefore(":**"))
    }

    @Test
    fun lowerCaseInputIsPreservedAsGiven() {
        // 不做大小写转换：日志里原样反映系统给的值，避免掩盖数据来源问题。
        assertEquals("b0:f1:**:**:**", FiilConfig.maskAddress("b0:f1:aa:bb:cc:dd"))
    }

    @Test
    fun handlesNullAndBlank() {
        assertEquals("?", FiilConfig.maskAddress(null))
        assertEquals("?", FiilConfig.maskAddress(""))
        assertEquals("?", FiilConfig.maskAddress("   "))
    }

    @Test
    fun handlesMalformedInputWithoutThrowing() {
        assertEquals("**", FiilConfig.maskAddress("B0:F1"))
        assertEquals("**", FiilConfig.maskAddress("B0"))
        // 段数足够就照常脱敏，不因为多出来的段数报错。
        assertEquals("B0:F1:**:**:**", FiilConfig.maskAddress("B0:F1:AA:BB:CC:DD:FF:FF"))
    }

    @Test
    fun neverEmitsTheOriginalTail() {
        val raw = "B0:F1:AA:BB:CC:DD"
        val tail = raw.substringAfter("B0:F1:")
        val masked = FiilConfig.maskAddress(raw)
        // 后半段的任何一段都不能原样出现在脱敏结果里。
        tail.split(":").forEach { segment ->
            assert(!masked.contains(segment)) { "masked string leaked segment $segment: $masked" }
        }
    }
}
