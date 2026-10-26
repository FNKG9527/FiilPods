package moe.chenxy.fiilpods.pods

/**
 * earType → 型号名 与 各机型能力表。
 *
 * 映射来自 FIIL+ 3.5.2 的 MainActivity.java:1902 私有方法 x(Bundle) 与
 * 同文件 @2057-2079 的 earType 45~49 分支，型号文案取自
 * `res/values/strings.xml`（fiil_f045 … fiil_f053）。
 */
object FiilModels {

    /** earType → 显示名（已与 strings.xml 交叉核对）。 */
    private val NAMES: Map<Int, String> = mapOf(
        1 to "FIIL Music",
        2 to "FIIL Wireless",
        3 to "FIIL Bestie Music",
        4 to "Diva Wired",
        5 to "Diva Pro",
        6 to "Carat",
        7 to "Carat M",
        8 to "Diva",
        9 to "VOX",
        10 to "Driifter NC",
        11 to "Driifter Pro",
        15 to "Carat Lite Music",
        16 to "Driifter Music",
        17 to "T1 Music",
        18 to "Driifter DNC",
        19 to "Driifter DNC Plus",
        20 to "Runner",
        21 to "CC",
        22 to "T1 X",
        26 to "T1 XS",
        27 to "T1 Pro",
        28 to "CC Pro",
        29 to "T1 Lite",
        30 to "CC2",
        31 to "CG",
        33 to "CG Pro",
        35 to "T2 Pro",
        37 to "CC nano",
        38 to "Key",
        39 to "CC Pro2",
        40 to "Belt",
        41 to "Key Pro",
        42 to "Aviator",
        43 to "GS",
        45 to "GS Lite",
        46 to "Key Max",
        47 to "Atom",
        48 to "GS Links",
        49 to "Key Pro2",
        51 to "CC3",
        53 to "CC Pro3",
        247 to "Diva2",
        250 to "Diva2 Pro",
    )

    fun nameOf(earType: Int): String = NAMES[earType] ?: "FIIL 耳机"

    /**
     * 设备名 → earType 的推断表。
     *
     * FIIL 耳机的蓝牙名基本等于型号名（`FiilManager.getDeviceInfo().getName()`），
     * 这里按「长名优先」匹配，避免 "FIIL CC Pro3" 被 "FIIL CC" 抢先命中。
     * 未命中返回 -1，表示需要靠 GAIA 的 GET_EARTYPE(0x0309) 查询确定。
     */
    private val NAME_RULES: List<Pair<String, Int>> = listOf(
        // 顺序即优先级：越具体的写在越前面，泛化串（CC / GS / KEY / T1 / MUSIC）压到最后，
        // 否则 "FIIL CC Pro3" 会先命中 "CC"、"FIIL Bestie Music" 会先命中 "MUSIC"。
        "CC PRO3" to 53, "CC3" to 51, "KEY PRO2" to 49, "GS LINKS" to 48,
        "ATOM" to 47, "KEY MAX" to 46, "GS LITE" to 45,
        "CC PRO2" to 39, "CC PRO" to 28, "CC NANO" to 37, "CC2" to 30, "CC" to 21,
        "KEY PRO" to 41, "KEY" to 38,
        "T2 PRO" to 35, "T1 PRO" to 27, "T1 XS" to 26, "T1 X" to 22, "T1 LITE" to 29, "T1" to 17,
        "CG PRO" to 33, "CG" to 31,
        // 42 的真实商品名是 FIIL Aviator（strings.xml 的 fiil_box="FIIL Aviator"），
        // 保留 "BOX" 只为兼容早期曾用过的显示名。
        "BELT" to 40, "AVIATOR" to 42, "BOX" to 42, "GS" to 43,
        "DIVA WIRED" to 4, "DIVA2 PRO" to 250, "DIVA2" to 247,
        "DIVA PRO" to 5, "DIVA" to 8,
        "CARAT LITE" to 15, "CARAT M" to 7, "CARAT PRO" to 7, "CARAT" to 6,
        // Driifter 家族五项必须按长到短排列，否则 "DRIIFTER DNC Plus" 会被 "DRIIFTER" 吃掉。
        "DRIIFTER DNC PLUS" to 19, "DRIIFTER DNC" to 18,
        "DRIIFTER NC" to 10, "DRIIFTER PRO" to 11, "DRIIFTER" to 16,
        "RUNNER" to 20, "VOX" to 9,
        // 以下三条是兜底泛词，必须最后。
        "WIRELESS" to 2, "BESTIE" to 3, "MUSIC" to 1,
    )

    fun earTypeOfName(name: String?): Int {
        if (name.isNullOrBlank()) return -1
        val upper = name.uppercase()
        for ((pattern, earType) in NAME_RULES) {
            if (upper.contains(pattern)) return earType
        }
        return -1
    }

    /**
     * 走 **Mate 协议族**（自定义 SPP UUID d52b47bc-… + `00 cid 01 00 …` 帧）的机型。
     *
     * 逆向依据：主界面路由 `MainActivity.java:513-537` 把
     * F045/F046/F047/F048/F049/F051/F053 分别映射到 case 7/6/4/5/3/2/1，
     * 这些 case 全部实例化 `F0xxMainFragment`；而它们的连接代码
     * （`F045MainFragment.java:400`、`F046MainFragment.java:402`、…）统一调用
     * `com.fengeek.main.i.a.createRfcommSocketToServiceRecord(device, handler,
     *  UUID.fromString("d52b47bc-2dea-42c4-b26d-20252fceeeb3"), earType)`。
     */
    private val MATE_EAR_TYPES: Set<Int> = setOf(45, 46, 47, 48, 49, 51, 53)

    /** 走 BLE 私有 GATT（Airoha RACE 或 FIILPeripheral）的新机型，当前模块不接管。 */
    private val BLE_ONLY: Set<Int> = setOf(39, 40, 42)

    /** 支持开放 / 抗风噪档位的机型（5 档 MAF）。 */
    private val EXTENDED_ANC: Set<Int> = setOf(28, 30, 33, 35, 37, 38, 41, 45, 46, 47, 48, 49, 51, 53)

    /** Mate 帧协议（F045~F053 走自定义 SPP UUID + 小整数 classifyId 帧）。 */
    fun isMateDialect(earType: Int): Boolean = earType in MATE_EAR_TYPES

    /** 走 SPP 链路的机型（GAIA + Mate 都算）。 */
    fun isSppSupported(earType: Int): Boolean = earType !in BLE_ONLY

    /** CC Pro2（earType=39）走自定义 UUID 但仍是 GAIA 帧。 */
    fun usesCustomUuid(earType: Int): Boolean = earType == 39

    /**
     * 实际实现了 `setSpatialAudio`(cid=50) 的机型 —— **只有 F046（Key Max）**。
     *
     * 取证：`<fiil_analysis>\jadx_out\sources\com\fengeek\main\i\d\` 下
     * b~h.java 是 7 个机型命令类。逐类扫 `setSpatialAudio` 只有 `c.java` 命中；
     * 而各模块到命令类的绑定是（全量 grep `i.d.X.getInstance`）：
     * f045→b、f046→c、f047→d(+b)、f048→e、f049→f、f051→g、f053→h。
     * 所以影院模式只有 F046 真正可用，其余机型一律按不支持处理。
     */
    private val CINEMA_EAR_TYPES: Set<Int> = setOf(46)

    /**
     * 实现了 `setMAF`(cid=44) 的机型。
     *
     * 同一份扫描里 `setMAF|getMAF|setANC` 命中 b/f/g/h → 命令类 c/f/g/h，
     * 对应 earType = 46(f046/c)、49(f049/f)、51(f051/g)、53(f053/h)。
     * f045(b)、f047(d,b)、f048(e) 都没有降噪命令 —— 这几款本身也不带 ANC。
     */
    private val MAF_EAR_TYPES: Set<Int> = setOf(46, 49, 51, 53)

    /**
     * MAF 只做三档（关闭 / 降噪 / 开放）的机型 —— 只有 F051（CC3）。
     *
     * 取证：`F051StyleFragment.setMafModeUI(int)` @:800 只处理 0/1/2，
     * 布局 `fragment_f51_style.xml` 也只有「降噪 / 开放 / 关闭」三个标签；
     * 而 F046/F049/F053 的 `setMafModeUI` 与布局都是五档。
     */
    private val MAF_THREE_STATE: Set<Int> = setOf(51)

    /** MAF 五档（关闭 / 降噪 / 开放 / 兼听 / 风中）：F046、F049、F053。 */
    private val MAF_FIVE_STATE: Set<Int> = MAF_EAR_TYPES - MAF_THREE_STATE

    /**
     * 实现了佩戴检测（cid=38 `setWear` / cid=39 `getWear`）的机型。
     * 扫描 `setWear|getWear` 只命中 g/h → earType 51(f051)、53(f053)。
     */
    private val WEAR_DETECT: Set<Int> = setOf(51, 53)

    fun supportsExtendedAnc(earType: Int): Boolean = earType in EXTENDED_ANC

    /**
     * 是否真的带主动降噪（决定 UI 要不要显示「降噪模式」卡片）。
     *
     * Mate 机型的判据是命令类里有没有 `setMAF`（见 [MAF_FIVE_STATE]）：
     * **F045(GS Lite)、F047(Atom)、F048(GS Links) 三款本身就没有降噪**。
     * GAIA 机型沿用 [EXTENDED_ANC] 白名单 —— 老机型里非 ANC 款（如 T1 Lite 等）
     * 不在白名单内，同样不显示该卡片。
     */
    fun supportsAnc(earType: Int): Boolean =
        if (isMateDialect(earType)) earType in MAF_EAR_TYPES else earType in EXTENDED_ANC

    fun supportsCinema(earType: Int): Boolean = earType in CINEMA_EAR_TYPES

    /** 是否支持 MAF 控制（三档或五档）。 */
    fun supportsMateMaf(earType: Int): Boolean = earType in MAF_EAR_TYPES

    /** 是否支持五档 MAF（多出「兼听」「风中」两档）。 */
    fun supportsMateMafFive(earType: Int): Boolean = earType in MAF_FIVE_STATE

    fun supportsWearDetect(earType: Int): Boolean = earType in WEAR_DETECT

    // ------------------------------------------------------------------
    // 自适应开关（与 MAF 档位并列的独立开关，不是 MAF 的第 5 档）
    // ------------------------------------------------------------------
    //
    // 取证方式：对 `com\fengeek\main\i\d\` 下 f/g/h.java（F049/F051/F053 的命令类）
    // 逐类扫 `set(EQ|Ear|Noise|Wind)Adaptive` / `setTuning` 的真实方法声明：
    //   f.java：五个全有   g.java：EQ + Noise   h.java：EQ + Ear + Noise
    // 再与各机型布局核对（`apktool_out\res\layout\fragment_f{49,51,53}_style.xml`）：
    //   f49：环境自适应降噪 / 自适应抗风噪 / 云感调音 / 耳道自适应降噪 / 自适应EQ —— 五个
    //   f51：环境自适应降噪 / 自适应EQ —— 两个
    //   f53：耳道自适应降噪 / 环境自适应降噪 / 自适应EQ —— 三个
    // 完全吻合，且 F046 的 c.java 一个都没有。

    /** 环境自适应降噪（`setNoiseAdaptive` cid=1 cmd=16）：F049 / F051 / F053。 */
    private val ADAPTIVE_NOISE: Set<Int> = setOf(49, 51, 53)

    /** 自适应抗风噪（`setWindAdaptive` cid=1 cmd=17）：只有 F049 有。 */
    private val ADAPTIVE_WIND: Set<Int> = setOf(49)

    /** 耳道自适应降噪（`setEarAdaptive` cid=1 cmd=15）：F049 / F053。 */
    private val ADAPTIVE_EAR: Set<Int> = setOf(49, 53)

    /** 自适应 EQ 调节（`setEQAdaptive` cid=1 cmd=14）：F049 / F051 / F053。 */
    private val ADAPTIVE_EQ: Set<Int> = setOf(49, 51, 53)

    /** 云感调音（`setTuning` cid=1 cmd=8）：只有 F049 有。 */
    private val ADAPTIVE_TUNING: Set<Int> = setOf(49)

    fun supportsNoiseAdaptive(earType: Int): Boolean = earType in ADAPTIVE_NOISE
    fun supportsWindAdaptive(earType: Int): Boolean = earType in ADAPTIVE_WIND
    fun supportsEarAdaptive(earType: Int): Boolean = earType in ADAPTIVE_EAR
    fun supportsEqAdaptive(earType: Int): Boolean = earType in ADAPTIVE_EQ
    fun supportsTuning(earType: Int): Boolean = earType in ADAPTIVE_TUNING

    /** 该机型是否有任意一个自适应开关（UI 决定要不要渲染整张卡片）。 */
    fun supportsAnyAdaptive(earType: Int): Boolean =
        earType in ADAPTIVE_NOISE || earType in ADAPTIVE_WIND || earType in ADAPTIVE_EAR ||
            earType in ADAPTIVE_EQ || earType in ADAPTIVE_TUNING

    fun supportsMultipoint(earType: Int): Boolean = earType > 0 && isSppSupported(earType)

    /**
     * GAIA 机型的降噪档位 → 中文名。
     *
     * 文案取自 FIIL+ 自己的 UI（`heat_info_fragment\adjust_zao_fragment.java:509 setZaoInfo`）：
     * 0→「关」、1→「降噪」、2→「监听」、3→「开放」、4→「风中」。
     * 老机型（[EXTENDED_ANC] 之外）只有前三档。
     */
    fun ancLabel(mode: Int): String = when (mode) {
        0 -> "关闭"
        1 -> "降噪"
        2 -> "监听"
        3 -> "开放"
        4 -> "风中"
        else -> "未知"
    }

    /**
     * Mate 机型的 MAF 档位文案。
     *
     * 取自 `apktool_out\res\layout\fragment_f49_style.xml:41-45` 的五个标签，
     * 其顺序与 `F046StyleFragment.setMafModeUI(int)` @:1037 的图标依次对应：
     * 0 关闭(maf_off) / 1 降噪(noise) / 2 开放(open) / 3 兼听(listen) / 4 风中(wind)。
     *
     * 注意 FIIL 把这整块叫「MAF 智能调噪」——智能指算法，不是第 5 个档位；
     * 2..4 的语义与 GAIA 机型不同（GAIA 的 2 是监听、3 才是开放）。
     */
    fun mateMafLabel(mode: Int): String = when (mode) {
        0 -> "关闭"
        1 -> "降噪"
        2 -> "开放"
        3 -> "兼听"
        4 -> "风中"
        else -> "未知"
    }

    /**
     * EQ 预设名。
     *
     * GAIA 机型（老款）：下标即 FFCOMMAND_SET_EQ 的 payload（0~7）。
     * Mate 机型：payload 是「预设类型」编号，来自
     *   `F046StyleFragment.java:765` —— `i2 + 3`，即 3..14 对应 12 个预设，
     *   15 = 自定义；预设名来自 `res/values/arrays.xml:336 preset_sound_name`。
     */
    val EQ_PRESETS: List<String> = listOf(
        "原声", "低音增强", "人声", "高音增强", "流行", "摇滚", "古典", "自定义",
    )

    /** Mate 机型的 12 个预设（arrays.xml:337-348），编号 3~14。 */
    val MATE_EQ_PRESETS: List<String> = listOf(
        "Nostalgia", "Jazz", "Light Music", "Home Theater",
        "Metal", "Pop Music", "R&B", "Dance", "Rock", "Electronic",
        "Soft Bass", "New Age",
    )

    /** Mate 机型自定义 EQ 的编号（`F046StyleFragment.java:899 f29301i[1] = 15`）。 */
    const val MATE_EQ_CUSTOM = 15

    /**
     * 供 UI 手动选择型号的候选表。
     *
     * 只列**本模块真正实现了控制链路**的机型（SPP：GAIA 与 Mate 两族）。
     * BLE-only 的三款（CC Pro2 / Belt / Box，见 [BLE_ONLY]）不在其中 ——
     * 它们走 Airoha RACE / FIILPeripheral 私有 GATT，本模块不接管，
     * 列出来只会让用户选到一个点了没反应的型号。
     *
     * 顺序按「新机型在前」排，与 FIIL+ 自己的机型列表观感一致。
     */
    val SELECTABLE_MODELS: List<Int> = listOf(
        53, 51, 49, 48, 47, 46, 45,
        43, 41, 38, 37, 35, 33, 31, 30, 29, 28, 27, 26, 22, 21, 20, 17, 15, 9, 8, 7, 6, 5,
    )

    /** earType → 下拉框显示文案（`FIIL Key Max (46)` 形式，便于对照逆向文档）。 */
    fun selectableLabel(earType: Int): String = "${nameOf(earType)} ($earType)"

    /** Mate 机型预设下标 → 协议编号。 */
    fun matePresetType(index: Int): Int = index + 3


    /** 各机型自定义 EQ 的频段标签（Hz）。 */
    fun eqBandLabels(earType: Int): List<String> = when (earType) {
        // F042/F043（arrays.xml:118 f042_customize_eq）10 段
        42, 43 -> listOf("200", "280", "400", "550", "770", "1k", "2k", "4k", "8k", "12k")
        // F045（f045_customize_eq）5 段
        45 -> listOf("63", "250", "1k", "4k", "8k")
        // F049（f049_customize_eq）8 段
        49 -> listOf("125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        // 默认 10 段（arrays.xml:87 customize_eq）
        else -> listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    }
}
