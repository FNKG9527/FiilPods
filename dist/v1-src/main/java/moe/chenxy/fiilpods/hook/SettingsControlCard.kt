package moe.chenxy.fiilpods.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilStatus

/**
 * 注入系统蓝牙详情页的 FIIL 控制卡片。
 *
 * ## 为什么不用 Compose / MIUIX
 * 这段代码运行在 `com.android.settings` 进程里。模块的 ClassLoader 与宿主的
 * ClassLoader 互相看不见对方的资源表，跨 ClassLoader 加载模块自己的 Compose 类
 * 会因 `CompositionLocal` / 资源解析而崩。**只能使用 framework 控件**：
 * `LinearLayout` / `TextView` / `Switch` / `GradientDrawable`，全部来自 `android.*`。
 *
 * ## 对应目标
 * 「第三阶段 1. 系统蓝牙详情页：顶部电量行，下方依次为 MAF 模式切换行、
 * 影院模式开关、低延迟模式选择、蓝牙畅连开关、自定义 EQ 入口」。
 * 宿主自己的页面只能显示电量与 ANC（它的 SDK 只认 MIUI 的 MMA 协议），
 * 影院 / 低延迟三档 / 多点 / EQ 这些 FIIL 私有能力必须由本卡片承载。
 *
 * ## 与 OppoPods 的对应关系
 * OppoPods 的 `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt`
 * **只复用宿主原生页面**，没有自建卡片，所以本文件在参考仓库里没有同名对应物。
 * 视觉与交互是照它的 Compose 版本逐项对齐的：
 *
 * | 本卡片控件 | OppoPods 对应 |
 * |---|---|
 * | 顶部电量行（左/右/盒） | `ui/pages/PodDetailPage.kt:227` 的 `PodStatus`，实现在 `ui/components/PodStatus.kt`（同一行三段，未连接显示 —） |
 * | 「调噪模式」胶囊行 | `ui/pages/PodDetailPage.kt:239` 的 `AncSwitch`，胶囊定义在 `ui/components/AncSwitch.kt`（横向胶囊、选中填色 `0xFF3482FF`） |
 * | 低延迟三档胶囊 | `ui/components/AncSwitch.kt` 同款胶囊，仅档位数不同（FIIL 是三档） |
 * | 影院模式开关行 | `ui/pages/PodDetailPage.kt:255` 的 `SwitchPreference`（标题左、开关右） |
 * | 蓝牙畅连开关行 | `ui/pages/PodDetailPage.kt:300` 的 `SwitchPreference`（同一张卡内） |
 * | 自定义 EQ 入口行 | `ui/pages/PodDetailPage.kt:287` 的 `BasicComponent`（右侧 `ArrowRight`） |
 *
 * 唯一不能照搬的是 `AncSwitch.kt` 展开的「降噪强度四档 / 人声增强两档」——
 * FIIL 的 MAF 协议没有强度分级（见 `docs/PROTOCOL_FIIL.md` §3.5），多画只会是假控件。
 */
internal object SettingsControlCard {

    /**
     * 卡片 View id。
     *
     * 用 `View.generateViewId()` 而不是常量：它保证与宿主 `R.id`（`0x7F......`）不冲突。
     * 幂等判重必须走 **id**（`findViewById`）而不是 tag ——
     * `View.findViewWithTag()` 只比对普通 tag，拿 keyed tag 去查永远查不到，
     * 那会让卡片在页面重建时重复叠加。
     */
    private val CARD_ID: Int = View.generateViewId()

    private const val ACCENT = 0xFF3482FF.toInt()
    private const val CHIP_BG_UNSELECTED = 0x14000000
    private const val CARD_BG = 0x0D000000

    /**
     * 「正在程序化写入控件」的标志。
     *
     * Switch 的 `setOnCheckedChangeListener` 在代码里赋值 `isChecked` 时**也会触发**
     * （只有 `setChecked` 之外没有区别，两者都回调），所以回填状态时必须屏蔽回调，
     * 否则每次刷新都会把同一个开关命令重新下发一遍，形成「回填 → 下发 → 回包 → 再回填」的回声循环。
     */
    private class BindingFlag {
        var active: Boolean = false
    }

    /** 卡片内所有需要随状态刷新的控件引用。 */
    private class Holder(
        val title: TextView,
        val battery: TextView,
        val mafChips: LinearLayout,
        val latencyChips: LinearLayout,
        val cinemaRow: LinearLayout,
        val cinemaSwitch: Switch,
        val multipointRow: LinearLayout,
        val multipointSwitch: Switch,
        val flag: BindingFlag,
        /** 下发通道，MAF 档位行重建时需要重新绑定点击。 */
        val dispatch: (String, Int?, Boolean?) -> Unit,
    ) {
        var mafButtons: List<Pair<TextView, Int>> = emptyList()
        var latencyButtons: List<Pair<TextView, Int>> = emptyList()
        var builtMafEarType: Int = Int.MIN_VALUE
    }

    /**
     * 在宿主的页面容器里挂上控制卡片（幂等）。
     *
     * @param rootFragmentView `MiuiHeadsetFragment.onCreateView` 的返回值
     * @param dispatch `(cmd, value, flag)` → 转成 ACTION_COMMAND 广播
     * @param openModule 点「打开均衡器」时跳到模块自己的完整控制台
     * @return 新增的卡片 View；已存在或找不到容器时返回 null
     */
    fun attach(
        rootFragmentView: View?,
        dispatch: (String, Int?, Boolean?) -> Unit,
        openModule: () -> Unit,
    ): View? {
        val root = rootFragmentView as? ViewGroup ?: return null
        if (root.findViewById<View>(CARD_ID) != null) return null

        val container = findVerticalContainer(root) ?: return null
        val card = build(container.context, dispatch, openModule)
        card.id = CARD_ID
        container.addView(card)
        return card
    }

    /** 把新的状态灌进已经挂好的卡片。 */
    fun bind(card: View?, status: FiilStatus?) {
        val holder = card?.tag as? Holder ?: return
        val s = status ?: FiilStatus.EMPTY

        holder.flag.active = true
        try {
            val model = if (s.earType > 0) s.modelName else "FIIL 耳机"
            holder.title.text = if (s.connected) "$model · 已连接" else "$model · 未连接"

            val battery = batteryText(s)
            holder.battery.text = battery
            holder.battery.visibility = if (battery.isEmpty()) View.GONE else View.VISIBLE

            val earType = s.earType
            if (holder.builtMafEarType != earType) {
                rebuildMafRow(holder, earType)
                holder.builtMafEarType = earType
            }
            holder.mafButtons.forEach { (chip, mode) ->
                styleChip(chip, selected = s.ancMode == mode)
            }
            holder.latencyButtons.forEach { (chip, wire) ->
                styleChip(chip, selected = s.lowLatencyLevel == wire)
            }

            val cinemaSupported = earType > 0 && FiilModels.supportsCinema(earType)
            holder.cinemaRow.visibility = if (cinemaSupported) View.VISIBLE else View.GONE
            holder.cinemaSwitch.isChecked = s.cinemaMode
            holder.cinemaSwitch.isEnabled = s.connected

            val multipointSupported = earType > 0 && FiilModels.supportsMultipoint(earType)
            holder.multipointRow.visibility = if (multipointSupported) View.VISIBLE else View.GONE
            holder.multipointSwitch.isChecked = s.multipoint
            holder.multipointSwitch.isEnabled = s.connected
        } finally {
            holder.flag.active = false
        }
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    private fun build(
        ctx: Context,
        dispatch: (String, Int?, Boolean?) -> Unit,
        openModule: () -> Unit,
    ): LinearLayout {
        val flag = BindingFlag()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            background = rounded(ctx, CARD_BG, 18)
        }

        val title = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor(ctx))
            text = "FIIL 耳机"
        }
        card.addView(title)

        val battery = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor(ctx))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        card.addView(battery)

        val (mafRow, mafChips) = buildChipRow(ctx, "调噪模式")
        card.addView(mafRow)

        val cinemaSwitch = newSwitch(ctx, dispatch, flag, "cinema")
        val cinemaRow = buildSwitchRow(ctx, "影院模式", cinemaSwitch)
        card.addView(cinemaRow)

        val (latencyRow, latencyChips) = buildChipRow(ctx, "低延迟")
        card.addView(latencyRow)
        val latencyButtons = listOf(
            "音乐" to 0,
            "游戏" to 1,
            "视频" to 2,
        ).map { (label, wire) ->
            val chip = newChip(ctx, label, dispatch, "lowLatencyMode", wire, null)
            latencyChips.addView(chip)
            chip to wire
        }

        val multipointSwitch = newSwitch(ctx, dispatch, flag, "multipoint")
        val multipointRow = buildSwitchRow(ctx, "蓝牙畅连", multipointSwitch)
        card.addView(multipointRow)

        val eqRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        eqRow.addView(
            TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor(ctx))
                text = "自定义 EQ"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        eqRow.addView(newActionChip(ctx, "打开均衡器", openModule))
        card.addView(eqRow)

        val holder = Holder(
            title = title,
            battery = battery,
            mafChips = mafChips,
            latencyChips = latencyChips,
            cinemaRow = cinemaRow,
            cinemaSwitch = cinemaSwitch,
            multipointRow = multipointRow,
            multipointSwitch = multipointSwitch,
            flag = flag,
            dispatch = dispatch,
        )
        holder.latencyButtons = latencyButtons
        card.tag = holder
        return card
    }

    /**
     * 重建 MAF 档位行。
     *
     * 档位数量随机型变化（3 档 / 5 档 / Mate 四态），且 `earType` 是连接后才拿到的，
     * 所以卡片先建行、拿到状态再填芯片。行容器始终存在，只换里面的芯片。
     */
    private fun rebuildMafRow(holder: Holder, earType: Int) {
        holder.mafChips.removeAllViews()
        val mate = FiilModels.isMateDialect(earType)
        val modes = when {
            mate && FiilModels.supportsMateMafFive(earType) -> listOf(0, 1, 2, 3, 4)
            mate && FiilModels.supportsMateMaf(earType) -> listOf(0, 1, 2, 3)
            FiilModels.supportsExtendedAnc(earType) -> listOf(0, 1, 2, 3, 4)
            else -> listOf(0, 1, 2)
        }
        holder.mafButtons = modes.map { mode ->
            val label = if (mate) FiilModels.mateMafLabel(mode) else FiilModels.ancLabel(mode)
            val chip = newChip(holder.mafChips.context, label, holder.dispatch, "anc", mode, null)
            holder.mafChips.addView(chip)
            chip to mode
        }
    }

    // ------------------------------------------------------------------
    // 控件工厂
    // ------------------------------------------------------------------

    /**
     * 一行「标题 + 横向可滚动的芯片组」。
     *
     * 芯片用 [HorizontalScrollView] 兜底：5 档 MAF 在 360dp 宽的屏上放不下，
     * 直接排会挤成省略号或者被裁掉。
     */
    private fun buildChipRow(ctx: Context, label: String): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        row.addView(
            TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor(ctx))
                text = label
            }
        )
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        row.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }
        )
        return row to chips
    }

    private fun buildSwitchRow(ctx: Context, label: String, switch: Switch): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            addView(
                TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(textColor(ctx))
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(switch)
        }

    /**
     * 开关控件。
     *
     * 监听器里先查 [BindingFlag]：回填状态时 `isChecked = x` 同样会触发回调，
     * 不屏蔽就会把刚读回来的值当成用户操作再下发一次。
     */
    private fun newSwitch(
        ctx: Context,
        dispatch: (String, Int?, Boolean?) -> Unit,
        flag: BindingFlag,
        cmd: String,
    ): Switch = Switch(ctx).apply {
        setOnCheckedChangeListener { _, checked ->
            if (flag.active) return@setOnCheckedChangeListener
            dispatch(cmd, null, checked)
        }
    }

    private fun newChip(
        ctx: Context,
        text: String,
        dispatch: (String, Int?, Boolean?) -> Unit,
        cmd: String,
        value: Int?,
        flag: Boolean?,
    ): TextView = newActionChip(ctx, text) { dispatch(cmd, value, flag) }

    private fun newActionChip(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
            isClickable = true
            setOnClickListener { onClick() }
            styleChip(this, false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(ctx, 8) }
        }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = rounded(
            chip.context,
            if (selected) ACCENT else CHIP_BG_UNSELECTED,
            16,
        )
        chip.setTextColor(if (selected) Color.WHITE else textColor(chip.context))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 找到页面里承载内容的纵向 LinearLayout。
     *
     * HyperOS 的耳机详情页是 `ScrollView → LinearLayout(vertical) → 若干块`。
     * ScrollView 只允许一个子 View，直接往里 addView 会抛
     * `IllegalStateException: ScrollView can host only one direct child`，
     * 所以必须往下钻一层。用 BFS 找第一个「已填充」的纵向 LinearLayout。
     *
     * 要求 `childCount >= 1` 是为了跳过尚未填充的空容器；万一整页都还没填充，
     * 这里会返回 null，由 [SettingsHook] 在 `onServiceConnected` 时再试一次。
     */
    private fun findVerticalContainer(root: ViewGroup): LinearLayout? {
        val queue = ArrayDeque<ViewGroup>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node is LinearLayout &&
                node.orientation == LinearLayout.VERTICAL &&
                node.childCount >= 1 &&
                node.id != CARD_ID
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                (node.getChildAt(i) as? ViewGroup)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun batteryText(status: FiilStatus): String {
        fun part(label: String, value: Int): String? =
            if (value >= 0) "$label $value%" else null
        return listOfNotNull(
            part("左", status.batteryLeft),
            part("右", status.batteryRight),
            part("盒", status.batteryCase),
        ).joinToString("   ")
    }

    private fun rounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(color)
        }

    private fun textColor(ctx: Context): Int {
        val value = TypedValue()
        if (!ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            return 0xFF1A1A1A.toInt()
        }
        if (value.resourceId != 0) {
            runCatching { ctx.resources.getColor(value.resourceId, ctx.theme) }
                .onSuccess { return it }
        }
        return value.data
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
