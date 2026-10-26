package moe.chenxy.fiilpods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机型能力门控的运行级测试。
 *
 * 这些表决定「某个机型上要不要显示某个控件、要不要发某条命令」，
 * 一旦判错，用户点了没反应或者下发到不支持的命令，属于功能性缺陷。
 */
class ModelCapabilityTest {

    /** Mate 方言机型 = F045~F053，必须走第二套命令空间。 */
    @Test
    fun mateDialectCoversF045ToF053() {
        val mateTypes = setOf(45, 46, 47, 48, 49, 51, 53)
        for (t in mateTypes) {
            assertTrue("earType=$t 应判为 Mate", FiilModels.isMateDialect(t))
        }
        // 边界：这几个不是 Mate
        for (t in listOf(1, 28, 39, 42, 44, 50, 52, 54, -1, 0)) {
            assertFalse("earType=$t 不应判为 Mate", FiilModels.isMateDialect(t))
        }
    }

    /**
     * 影院模式只有 F046 支持。
     *
     * 逆向依据：`F046StyleFragment` 有 setSpatialAudio，其它 *StyleFragment 没有。
     */
    @Test
    fun cinemaOnlyForF046() {
        assertTrue(FiilModels.supportsCinema(46))
        for (t in listOf(45, 47, 48, 49, 51, 53, 1, 28, 39)) {
            assertFalse("earType=$t 不应支持影院", FiilModels.supportsCinema(t))
        }
    }

    /**
     * MAF 五态：F046/F049/F053 五档，F051 只有三档（无风中/兼听）。
     */
    @Test
    fun mafFiveStateExcludesF051() {
        assertTrue(FiilModels.supportsMateMaf(46))
        assertTrue(FiilModels.supportsMateMaf(49))
        assertTrue(FiilModels.supportsMateMaf(53))
        assertTrue(FiilModels.supportsMateMaf(51))

        assertTrue("F046 应为五档", FiilModels.supportsMateMafFive(46))
        assertTrue("F049 应为五档", FiilModels.supportsMateMafFive(49))
        assertTrue("F053 应为五档", FiilModels.supportsMateMafFive(53))
        assertFalse("F051 不是五档", FiilModels.supportsMateMafFive(51))
    }

    /** 佩戴检测只有 F051 / F053 支持。 */
    @Test
    fun wearDetectOnlyF051AndF053() {
        assertTrue(FiilModels.supportsWearDetect(51))
        assertTrue(FiilModels.supportsWearDetect(53))
        for (t in listOf(45, 46, 47, 48, 49, 1, 28, 39, 42)) {
            assertFalse("earType=$t 不应支持佩戴检测", FiilModels.supportsWearDetect(t))
        }
    }

    /**
     * 自适应开关的机型覆盖 —— 这四个开关是最后补的，最容易判错。
     *
     * 依据：`f.java`(F049) / `g.java`(F051) / `h.java`(F053) 里各自存在哪些 setXxx。
     */
    @Test
    fun adaptiveSwitchesGatedByEarType() {
        // 环境自适应降噪：F049/F051/F053
        assertTrue(FiilModels.supportsNoiseAdaptive(49))
        assertTrue(FiilModels.supportsNoiseAdaptive(51))
        assertTrue(FiilModels.supportsNoiseAdaptive(53))
        assertFalse(FiilModels.supportsNoiseAdaptive(46))

        // 自适应抗风噪：只有 F049
        assertTrue(FiilModels.supportsWindAdaptive(49))
        assertFalse(FiilModels.supportsWindAdaptive(51))
        assertFalse(FiilModels.supportsWindAdaptive(53))

        // 耳道自适应：F049/F053
        assertTrue(FiilModels.supportsEarAdaptive(49))
        assertTrue(FiilModels.supportsEarAdaptive(53))
        assertFalse(FiilModels.supportsEarAdaptive(51))

        // EQ 自适应：F049/F051/F053
        assertTrue(FiilModels.supportsEqAdaptive(49))
        assertTrue(FiilModels.supportsEqAdaptive(51))
        assertTrue(FiilModels.supportsEqAdaptive(53))

        // 云感调音：只有 F049
        assertTrue(FiilModels.supportsTuning(49))
        assertFalse(FiilModels.supportsTuning(51))
        assertFalse(FiilModels.supportsTuning(53))
    }

    /** F046 一个自适应开关都没有，UI 上整块必须隐藏。 */
    @Test
    fun f046HasNoAdaptiveSwitches() {
        assertFalse(FiilModels.supportsNoiseAdaptive(46))
        assertFalse(FiilModels.supportsWindAdaptive(46))
        assertFalse(FiilModels.supportsEarAdaptive(46))
        assertFalse(FiilModels.supportsEqAdaptive(46))
        assertFalse(FiilModels.supportsTuning(46))
        assertFalse(FiilModels.supportsAnyAdaptive(46))
    }

    /** BLE-only 机型不能走 SPP（连了也没用）。 */
    @Test
    fun bleOnlyModelsHaveNoSpp() {
        for (t in listOf(39, 40, 42)) {
            assertFalse("earType=$t 是 BLE-only，不支持 SPP", FiilModels.isSppSupported(t))
        }
        for (t in listOf(1, 28, 45, 46, 49, 51, 53)) {
            assertTrue("earType=$t 应支持 SPP", FiilModels.isSppSupported(t))
        }
    }

    /** 型号名推断：真实设备名必须能识别出型号。 */
    @Test
    fun earTypeOfNameRecognisesRealDeviceNames() {
        // FIIL 系设备名的真实形态（来自 APP 的 BluetoothDevice.getName()）
        // 39 = CC Pro2（strings.xml:786 fiil_cc_pro2），46 = Key Max（strings.xml:798 fiil_f046）
        assertEquals(39, FiilModels.earTypeOfName("FIIL CC Pro2"))
        assertEquals(46, FiilModels.earTypeOfName("FIIL Key Max"))
        // 2 = Wireless（strings.xml 的 fill_wireless 值就是 "FIIL Wireless"）
        assertEquals(2, FiilModels.earTypeOfName("FIIL Wireless"))
        // 名字里带后缀/大小写混合也要认
        assertEquals(39, FiilModels.earTypeOfName("fiil cc pro2"))
        // 完全无关的名字应该返回 -1（走 MAC OUI 兜底）
        assertEquals(-1, FiilModels.earTypeOfName("Galaxy Buds3"))
        assertEquals(-1, FiilModels.earTypeOfName(null))
        assertEquals(-1, FiilModels.earTypeOfName(""))
    }

    /**
     * 全部可识别型号的「显示名 → earType」必须能反查回自己。
     *
     * 这是最容易漏的回归：新增型号时只往 NAMES 加了名字、忘了加 NAME_RULES，
     * 用户看到的型号识别就会静默失效（表现为「认不出耳机型号」）。
     */
    @Test
    fun everySelectableModelRoundTripsThroughName() {
        for (t in FiilModels.SELECTABLE_MODELS) {
            val name = FiilModels.nameOf(t)
            assertEquals(
                "型号 $t 的显示名 \"$name\" 反查失败（NAME_RULES 缺规则）",
                t,
                FiilModels.earTypeOfName(name),
            )
        }
    }

    /** 型号名不能出现空串或占位符。 */
    @Test
    fun nameOfAlwaysReturnsNonEmpty() {
        assertTrue(FiilModels.nameOf(46).isNotBlank())
        assertTrue(FiilModels.nameOf(9999).isNotBlank())   // 未知型号走兜底
        assertTrue(FiilModels.nameOf(-1).isNotBlank())
    }

    /** ANC 文案：GAIA 三/五档必须覆盖 0..4，且不重复。 */
    @Test
    fun ancLabelCoversAllModes() {
        val labels = (0..4).map { FiilModels.ancLabel(it) }
        assertTrue("ANC 文案不应为空", labels.all { it.isNotBlank() })
        assertEquals("ANC 文案不应重复：$labels", labels.size, labels.toSet().size)
        assertEquals("关闭", labels[0])
    }

    /** EQ 预设与自定义档的索引关系不能错位。 */
    @Test
    fun eqPresetsAreConsistent() {
        assertTrue(FiilModels.EQ_PRESETS.isNotEmpty())
        assertTrue(FiilModels.MATE_EQ_PRESETS.isNotEmpty())
        // matePresetType(index) = index + 3，第一项必须是 3
        assertEquals(3, FiilModels.matePresetType(0))
        // 预设编号是 3..14 连续区间，最后一项 = 3 + (n - 1)
        assertEquals(
            3 + FiilModels.MATE_EQ_PRESETS.size - 1,
            FiilModels.matePresetType(FiilModels.MATE_EQ_PRESETS.size - 1),
        )
        // MATE_EQ_CUSTOM(15) 是「自定义」单独占的编号，不参与预设序列，必须紧接其后
        assertEquals(15, FiilModels.MATE_EQ_CUSTOM)
        assertEquals(FiilModels.MATE_EQ_CUSTOM, FiilModels.matePresetType(FiilModels.MATE_EQ_PRESETS.size))
    }

    /** 手动选型的候选列表不能为空，且每个都必须能取到名字。 */
    @Test
    fun selectableModelsAreUsable() {
        assertTrue(FiilModels.SELECTABLE_MODELS.isNotEmpty())
        for (t in FiilModels.SELECTABLE_MODELS) {
            assertTrue("earType=$t 没有名字", FiilModels.nameOf(t).isNotBlank())
            assertTrue("earType=$t 的标签为空", FiilModels.selectableLabel(t).isNotBlank())
        }
        // 不能有重复项（UI 下拉列表会显示重复）
        assertEquals(
            "SELECTABLE_MODELS 有重复：${FiilModels.SELECTABLE_MODELS}",
            FiilModels.SELECTABLE_MODELS.size,
            FiilModels.SELECTABLE_MODELS.toSet().size,
        )
    }

    /** EQ 频段标签数量必须等于协议段数。 */
    @Test
    fun eqBandLabelsMatchProtocolSegmentCount() {
        // 段数按机型不同（arrays.xml 各机型 customize_eq 数组长度）：
        //  F042/F043 = 10、F045 = 5、F049 = 8、默认 = 10
        assertEquals(10, FiilModels.eqBandLabels(42).size)
        assertEquals(10, FiilModels.eqBandLabels(43).size)
        assertEquals(5, FiilModels.eqBandLabels(45).size)
        assertEquals(8, FiilModels.eqBandLabels(49).size)
        assertEquals(10, FiilModels.eqBandLabels(46).size)
        assertEquals(10, FiilModels.eqBandLabels(53).size)
        // 任何机型都不能超过协议上限
        for (t in FiilModels.SELECTABLE_MODELS + listOf(9999, -1, 0)) {
            val n = FiilModels.eqBandLabels(t).size
            assertTrue("earType=$t 段数 $n 超过上限", n in 1..FiilMate.EQ_BAND_COUNT)
        }
        // 未知机型也要给出一组可用标签
        assertTrue(FiilModels.eqBandLabels(9999).isNotEmpty())
        assertTrue(FiilModels.eqBandLabels(-1).isNotEmpty())
    }

    /** [FiilMate.defaultBands] 必须按机型段数返回，不能恒 10 段。 */
    @Test
    fun defaultBandsFollowModelSegmentCount() {
        assertEquals(5, FiilMate.defaultBands(45).size)
        assertEquals(8, FiilMate.defaultBands(49).size)
        assertEquals(10, FiilMate.defaultBands(46).size)
        assertEquals(10, FiilMate.defaultBands(-1).size)
        // 全平 = 128（0 dB）
        assertTrue(FiilMate.defaultBands(49).all { it == 128 })
    }

    /** 多点连接：只要走 SPP 就应该支持（Mate 与 GAIA 都有该功能）。 */
    @Test
    fun multipointAvailableOnAllSppModels() {
        assertTrue(FiilModels.supportsMultipoint(1))
        assertTrue(FiilModels.supportsMultipoint(46))
        assertFalse(FiilModels.supportsMultipoint(39))   // BLE-only
        assertFalse(FiilModels.supportsMultipoint(-1))   // 未知型号
    }
}
