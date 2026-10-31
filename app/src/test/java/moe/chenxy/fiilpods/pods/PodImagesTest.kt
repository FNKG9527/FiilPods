package moe.chenxy.fiilpods.pods

import moe.chenxy.fiilpods.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 earType → 产品图的映射，防止以后动 [FiilModels] 或换图标时把对应关系改坏。
 *
 * 这些断言不是「测实现」，而是把从 FIIL+ 逆向出来的**事实**钉住：
 * 型号对应关系来自 `assets/header_list_zh.json` 的 `headsetModel` 字段，
 * 一旦有人凭感觉「顺手整理」那张表，这里会立刻红。
 */
class PodImagesTest {

    @Test
    fun `unknown earType falls back to fiil_unknow`() {
        assertEquals(R.drawable.fiil_unknow, PodImages.FALLBACK)
        // 未连接 / 型号未知
        assertEquals(R.drawable.fiil_unknow, PodImages.drawableOf(-1))
        // 不存在的 earType
        assertEquals(R.drawable.fiil_unknow, PodImages.drawableOf(9999))
        assertNull(PodImages.drawableOrNull(9999))
        assertTrue(!PodImages.hasImage(9999))
    }

    @Test
    fun `drawableOf never returns zero`() {
        // painterResource(0) 会直接抛异常崩掉界面，所以任何输入都必须给出一张真图。
        for (earType in -5..400) {
            assertNotEquals("earType=$earType", 0, PodImages.drawableOf(earType))
        }
    }

    @Test
    fun `every mapped earType is a model this module actually knows`() {
        // 映射表里出现一个 FiilModels 不认识的 earType，说明两边编号体系已经漂移了。
        for (earType in PodImages.knownEarTypes) {
            assertNotEquals(
                "earType=$earType 不在 FiilModels.NAMES 里",
                "FIIL 耳机",
                FiilModels.nameOf(earType),
            )
        }
    }

    @Test
    fun `mapped drawable is never the fallback`() {
        // 表里塞兜底图等于没配，UI 会以为这型号有产品图。
        for (earType in PodImages.knownEarTypes) {
            assertNotEquals(
                "earType=$earType 不应该指向兜底图",
                PodImages.FALLBACK,
                PodImages.drawableOf(earType),
            )
        }
    }

    @Test
    fun `key models map to the image the official app uses`() {
        // 逐条对照 header_list_zh.json，这几处最容易被人「顺手改成更像的那个」。
        assertEquals(R.drawable.fiil_earphones_key_max, PodImages.drawableOf(46))
        assertEquals(R.drawable.fiil_earphones_key_pro, PodImages.drawableOf(41))
        assertEquals(R.drawable.fiil_earphones_key, PodImages.drawableOf(38))
        // F049 用的是 800×800 精修版 _1，不是同名的系列头图
        assertEquals(R.drawable.fiil_earphones_key_pro2_1, PodImages.drawableOf(49))
        // F043 官方指向 gs1；fiil_earphones_gs 是系列头图
        assertEquals(R.drawable.fiil_earphones_gs1, PodImages.drawableOf(43))
        assertEquals(R.drawable.fiil_earphones_cc3, PodImages.drawableOf(51))
        assertEquals(R.drawable.fiil_earphones_atom, PodImages.drawableOf(47))
    }

    @Test
    fun `t1_x has an image even though the official mapper forgot it`() {
        // FIIL+ 的 ImageResMapper 漏登记 t1_x 这个键，但资源文件是存在的。
        // 本模块补上它 —— 这是对官方行为的**有意修正**，若被谁「对齐官方」删掉，这里会红。
        assertEquals(R.drawable.fiil_earphones_t1_x, PodImages.drawableOf(22))
    }

    @Test
    fun `diva family keeps four distinct images`() {
        // header_list_zh.json 里 Diva/Diva2 共用 F008、Diva Pro/Diva2 Pro 共用 F005，
        // 只靠 headsetModel 会把四台耳机压成两张图。本模块用独立的 earType 区分。
        val divas = listOf(8, 5, 247, 250).map { PodImages.drawableOf(it) }
        assertEquals("Diva 系列四张图应互不相同", 4, divas.toSet().size)
    }

    @Test
    fun `drawableOfName works for a connected device name`() {
        assertEquals(R.drawable.fiil_earphones_key_max, PodImages.drawableOfName("FIIL Key Max"))
        // 名字认不出来时落兜底，不能返回 0
        assertEquals(PodImages.FALLBACK, PodImages.drawableOfName("Some Unknown Headset"))
        assertEquals(PodImages.FALLBACK, PodImages.drawableOfName(null))
        assertNotNull(PodImages.drawableOfName(""))
    }
}
