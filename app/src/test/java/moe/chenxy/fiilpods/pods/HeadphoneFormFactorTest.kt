package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「哪些型号是头戴式」这张表。
 *
 * ## 为什么必须有测试
 *
 * 融合设备中心的耳机卡片图标只有两种形态可渲染：
 *  - 头戴式 → `circulate_device_headset_headphones`
 *  - 其余 → `circulate_headset_icon`
 *
 * 判定完全靠 [FiilModels.isHeadphones]，而那张集合是**手工整理的**。第一版就是
 * 这么挂的：按产品线直觉只写了 Diva 系 + Wireless，漏掉 Key Max（earType 46）——
 * 恰好是用户手上那台，于是他看到的就是「还是普通蓝牙耳机图标」。
 *
 * 所以这里把每个型号的形态逐个钉死，并附上判定依据的出处。以后谁觉得「顺手
 * 加一个 / 删一个」，测试会立刻告诉他对不对。
 *
 * ## 判定依据（都是公开可查的产品事实，不是猜的）
 *
 * | earType | 型号 | 形态 | 出处 |
 * |---|---|---|---|
 * | 2 | FIIL Wireless | 头戴包耳 | 快科技《fiil 蓝牙无线款》「头戴包耳的 fiil」 |
 * | 5 | Diva Pro | 头戴 | Diva 系 |
 * | 8 | Diva | 头戴 | 新浪众测「FIIL 家的头戴式无线降噪耳机 Diva」 |
 * | 46 | Key Max | 头戴 | IT之家「上架了 FIIL Key Max 深度主动降噪头戴式蓝牙耳机」 |
 * | 247 | Diva2 | 头戴 | Diva 系 |
 * | 250 | Diva2 Pro | 头戴 | Diva 系 |
 *
 * 其余全部为真无线入耳/半入耳（CC / Key / T / CG 系）、开放式（GS 系、Atom）
 * 或音箱（F042 Aviator，故意不列入耳机体系）。
 */
class HeadphoneFormFactorTest {

    @Test
    fun `earType 46 Key Max is headphones`() {
        // 这条单独拎出来：它是第一版漏掉的那个，也是用户手上那台。
        // 一旦这个断言红了，用户会立刻在融合设备中心看到错误图标。
        assertTrue("FIIL Key Max 是头戴式（IT之家/52audio 拆解均如此描述）", FiilModels.isHeadphones(46))
    }

    @Test
    fun `all Diva family models are headphones`() {
        // Diva 全系都是包耳式头戴：Diva(8) / Diva Pro(5) / Diva2(247) / Diva2 Pro(250)
        for (earType in listOf(5, 8, 247, 250)) {
            assertTrue("earType=$earType 属于 Diva 头戴系", FiilModels.isHeadphones(earType))
        }
    }

    @Test
    fun `FIIL Wireless is headphones`() {
        assertTrue(FiilModels.isHeadphones(2))
    }

    @Test
    fun `true wireless and open wear models are not headphones`() {
        // 真无线入耳/半入耳：CC 系(21/28/30/37/39)、Key 系(38/41/49)、T 系(17/22/26/27/29/35)、CG 系(31/33)
        // 开放式：GS 系(43/45/48)、Atom(47)
        val inEar = listOf(17, 21, 22, 26, 27, 28, 29, 30, 31, 33, 35, 37, 38, 39, 41, 43, 45, 47, 48, 49)
        for (earType in inEar) {
            assertFalse("earType=$earType 不是头戴式", FiilModels.isHeadphones(earType))
        }
    }

    @Test
    fun `the two headphone sets are exactly the same six`() {
        // 把集合的**全体**钉住，不只是抽查几个：
        // 多塞一个进去（比如误把 Gs 当开放式头戴）或删掉一个，这里都会红。
        val expected = setOf(2, 5, 8, 46, 247, 250)
        val actual = (0..300).filter { FiilModels.isHeadphones(it) }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `unknown earType is never headphones`() {
        // 型号未知一律按普通耳机处理：猜错成头戴的视觉违和感远大于猜错成入耳，
        // 而且融合中心对未识别的 FIIL 设备本来就会走默认分支。
        for (earType in listOf(-1, 0, 9999)) {
            assertFalse("earType=$earType", FiilModels.isHeadphones(earType))
        }
    }

    @Test
    fun `every headphone model has an official product image`() {
        // 头戴型号必须都在图片映射表里，否则详情页/通知栏会退成兜底图。
        for (earType in listOf(2, 5, 8, 46, 247, 250)) {
            assertTrue("earType=$earType 应有官方产品图", PodImages.hasImage(earType))
        }
    }
}
