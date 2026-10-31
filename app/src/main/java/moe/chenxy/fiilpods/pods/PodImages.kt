package moe.chenxy.fiilpods.pods

import androidx.annotation.DrawableRes
import moe.chenxy.fiilpods.R

/**
 * earType → 耳机产品图。
 *
 * 图片全部取自 FIIL+ 3.5.2 的 `res/drawable/fiil_earphones_*.png`
 * （已转 webp 放进 `res/drawable-nodpi/`），
 * 型号对应关系来自同 APK 的 `assets/header_list_zh.json`：
 * 该文件的 `headsetList[].headsetModel`（形如 `F046`）去掉前两位就是 earType，
 * 与 [FiilModels] 的编号体系同源（反编译 `EarPhoneModel.getEarID()` 即
 * `Integer.parseInt(getHeadsetModel().substring(2))`）。
 *
 * 官方共 26 个型号出图；本模块的 [FiilModels] 覆盖 43 个 earType，
 * 因此部分老型号（Driifter / Carat / Bestie / Runner 等）没有官方产品图，
 * 一律落 [R.drawable.fiil_unknow]（即 FIIL+ 自己的兜底图）。
 */
object PodImages {

    /**
     * earType → 产品图资源。
     *
     * 说明几处「不直观」的对应：
     *  - 43（GS）用的是 `fiil_earphones_gs1`：`header_list_zh.json` 里 F043 的
     *    `imgUrl` 就是 `fiil_earphones_gs1`，`fiil_earphones_gs` 反而是 GS 系列的
     *    系列头图，不能拿来做单品图。
     *  - 49（Key Pro2）用 `fiil_earphones_key_pro2_1`（800×800 精修版），
     *    同名的 `fiil_earphones_key_pro2` 是系列头图。
     *  - 22（T1 X）有 `fiil_earphones_t1_x.jpg` 实体文件，但 FIIL+ 的
     *    `ImageResMapper` 漏登记了这个键，官方的列表里因此永远显示兜底图；
     *    这里补上，本模块能正确显示。
     *  - 42（Aviator）官方只有 `fiil_speaker_aviator`（音箱类图），沿用。
     *  - 247（Diva2）/250（Diva2 Pro）在 `header_list_zh.json` 里与
     *    Diva/Diva Pro 共用 `F008`/`F005`，但图片是分开的，这里按模块自己的
     *    earType 分别指向 diva2 / diva2_pro。
     */
    private val DRAWABLES: Map<Int, Int> = mapOf(
        // CC 系列
        51 to R.drawable.fiil_earphones_cc3,
        39 to R.drawable.fiil_earphones_cc_pro2,
        37 to R.drawable.fiil_earphones_cc_nano,
        28 to R.drawable.fiil_earphones_cc_pro,
        30 to R.drawable.fiil_earphones_cc2,
        21 to R.drawable.fiil_earphones_cc,
        // GS 系列
        43 to R.drawable.fiil_earphones_gs1,
        45 to R.drawable.fiil_earphones_gs_lite,
        48 to R.drawable.fiil_earphones_gs_links,
        // Key 系列
        49 to R.drawable.fiil_earphones_key_pro2_1,
        46 to R.drawable.fiil_earphones_key_max,
        41 to R.drawable.fiil_earphones_key_pro,
        38 to R.drawable.fiil_earphones_key,
        // Atom
        47 to R.drawable.fiil_earphones_atom,
        // CG 系列
        33 to R.drawable.fiil_earphones_cg_pro,
        31 to R.drawable.fiil_earphones_cg,
        // T 系列
        35 to R.drawable.fiil_earphones_t2_pro,
        27 to R.drawable.fiil_earphones_t1_pro,
        29 to R.drawable.fiil_earphones_t1_lite,
        26 to R.drawable.fiil_earphones_t1_xs,
        22 to R.drawable.fiil_earphones_t1_x,
        17 to R.drawable.fiil_earphones_t1,
        // Diva 系列（头戴）
        8 to R.drawable.fiil_earphones_diva,
        5 to R.drawable.fiil_earphones_diva_pro,
        247 to R.drawable.fiil_earphones_diva2,
        250 to R.drawable.fiil_earphones_diva2_pro,
        // FIIL 系列
        2 to R.drawable.fiil_earphones_wireless,
        // Aviator（官方归类为 speaker，图仍是该型号唯一的产品图）
        42 to R.drawable.fiil_speaker_aviator,
    )

    /** 兜底图：FIIL+ 自己也是这张。 */
    @DrawableRes
    val FALLBACK: Int = R.drawable.fiil_unknow

    /** 有官方产品图的 earType 数量（用于自检日志/测试）。 */
    val knownEarTypes: Set<Int> get() = DRAWABLES.keys

    /** earType → 产品图；未收录的型号返回兜底图，不会返回 0。 */
    @DrawableRes
    fun drawableOf(earType: Int): Int = DRAWABLES[earType] ?: FALLBACK

    /** earType → 产品图；未收录返回 null（调用方想自己决定回落方案时用）。 */
    @DrawableRes
    fun drawableOrNull(earType: Int): Int? = DRAWABLES[earType]

    /** 该 earType 是否有官方产品图（不是兜底图）。 */
    fun hasImage(earType: Int): Boolean = DRAWABLES.containsKey(earType)

    /**
     * 型号名 → 产品图。
     *
     * UI 侧拿不到 earType 时（例如只收到蓝牙名）可以用这个：
     * 先用 [FiilModels.earTypeOfName] 把名字推成 earType，再查表。
     */
    @DrawableRes
    fun drawableOfName(name: String?): Int = drawableOf(FiilModels.earTypeOfName(name))
}
