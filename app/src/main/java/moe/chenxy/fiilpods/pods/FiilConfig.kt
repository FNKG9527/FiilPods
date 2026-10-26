package moe.chenxy.fiilpods.pods

import android.content.Context

/**
 * 模块级常量与品牌判定。
 *
 * 这些值直接决定 HyperOS 设置页「认不认」这台耳机：
 *  - [FAKE_DEVICE_ID] 是伪造给宿主的设备 ID，必须与 [FAKE_SUPPORT] 前缀一致
 *  - [FAKE_SUPPORT] 形如 `<id>,<24 位十六进制能力位图>`，
 *    宿主用它判断「支持 ANC / 支持双耳电量」等能力
 *
 * 采用与参考实现相同的 ID，是因为该 ID 对应的能力位在 HyperOS 各版本里
 * 表现最稳定；换成别的值可能导致设置页不画卡片。
 */
object FiilConfig {

    /** 伪造设备 ID（8 位十六进制）。 */
    const val FAKE_DEVICE_ID: String = "01010607"

    /** 伪造能力串：`<id>,<能力位图>`。 */
    const val FAKE_SUPPORT: String = "$FAKE_DEVICE_ID,000000000000000010000000"

    /** 模块包名。 */
    const val MODULE_PACKAGE: String = "moe.chenxy.fiilpods"

    /** 设置页刷新周期。 */
    const val SETTINGS_REFRESH_INTERVAL_MS: Long = 3_000L

    /**
     * 模块自己的配置存储（存在模块 App 的 data 目录，而不是宿主进程的）。
     *
     * 蓝牙进程里 `appContext` 是 `com.android.bluetooth` 的 Context，
     * 直接 `getSharedPreferences` 会把配置写进宿主的 data 目录 ——
     * 既不干净，宿主清数据时会丢。所以统一用
     * `createPackageContext(MODULE_PACKAGE, CONTEXT_IGNORE_SECURITY)` 拿模块的 Context 读写。
     */
    const val PREFS_NAME: String = "fiilpods_config"

    /**
     * 手动指定的 earType（型号）。
     *
     * 0 或不存在 = 自动识别（蓝牙名推断 + GAIA GET_EARTYPE 回包）。
     * >0 = 用户在 UI 里手动选的型号，优先于所有自动推断 ——
     * 这是目标第二阶段「首次连接时允许手动选择型号」与
     * 第五阶段「未验证型号允许手动选择」的落地点。
     */
    const val KEY_EAR_TYPE_OVERRIDE: String = "ear_type_override"

    /** 读模块自己的 Context；宿主变了也不影响。 */
    fun moduleContext(context: Context): Context? = runCatching {
        context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()

    /** 读手动型号；没设过返回 -1。 */
    fun readEarTypeOverride(context: Context?): Int {
        val ctx = context ?: return -1
        val prefs = moduleContext(ctx)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return -1
        return prefs.getInt(KEY_EAR_TYPE_OVERRIDE, -1)
    }

    /** 写手动型号；传 -1 表示恢复自动识别。 */
    fun writeEarTypeOverride(context: Context?, value: Int) {
        val ctx = context ?: return
        val prefs = moduleContext(ctx)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return
        prefs.edit().apply {
            if (value > 0) putInt(KEY_EAR_TYPE_OVERRIDE, value)
            else remove(KEY_EAR_TYPE_OVERRIDE)
        }.apply()
    }

    /**
     * 地址 → earType 的绑定记录（目标第五阶段「型号识别仍用原始信息+地址绑定」）。
     *
     * 为什么需要它：用户可能把蓝牙名改成「我的耳机」，`getName()` 拿到的原始名
     * 这时也可能被系统改写；而 MAC 地址是稳定的。只要这台耳机曾经成功识别出型号，
     * 就把 `地址 → earType` 记下来，下次同地址再连时直接复用，不必再靠名字猜。
     *
     * 键写成 `ear_type_for_<MAC>`，与型号覆盖项分开存，避免互相覆盖。
     */
    private const val KEY_EAR_TYPE_FOR_ADDRESS_PREFIX: String = "ear_type_for_"

    /** 读取某地址已绑定的 earType；没有记录返回 -1。 */
    fun readEarTypeForAddress(context: Context?, address: String?): Int {
        val addr = address?.uppercase()?.takeIf { it.isNotBlank() } ?: return -1
        val ctx = context ?: return -1
        val prefs = moduleContext(ctx)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return -1
        return prefs.getInt(KEY_EAR_TYPE_FOR_ADDRESS_PREFIX + addr, -1)
    }

    /** 记录某地址的 earType；传 -1 或非法地址则忽略。 */
    fun writeEarTypeForAddress(context: Context?, address: String?, earType: Int) {
        val addr = address?.uppercase()?.takeIf { it.isNotBlank() } ?: return
        val ctx = context ?: return
        if (earType <= 0) return
        val prefs = moduleContext(ctx)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return
        prefs.edit().putInt(KEY_EAR_TYPE_FOR_ADDRESS_PREFIX + addr, earType).apply()
    }

    /**
     * FIIL 的蓝牙 MAC 前缀（OUI）。
     *
     * 逆向依据：FIIL+ 自己在所有 OTA 页都用这个前缀做「是不是自家设备」的硬判定，
     * 例如 `<fiil_analysis>\jadx_out\sources\com\fengeek\main\base\BaseFragment.java:28`
     * 的 `device.getAddress().startsWith("B0:F1:A3")`（同一判定在 F039/F040/F041/F043/F045/
     * F046/F048/F049/F051/F053 的 OTA Activity 里重复出现，共 15 处）；
     * `F042HomeFragment.java:1014` 另有一处 `FF:F1:A3` 变体。
     *
     * 用途：名字被用户改掉、或改名成完全无关的词时，仍能靠地址认出这是 FIIL 耳机。
     * 只作为**补充**判定，不替代名字判定 —— 逆向里只能确认这两个前缀覆盖 FIIL 自家
     * 送测/量产批次，不能保证所有代工厂批次都用它。
     */
    private val FIIL_OUI_PREFIXES = listOf("B0:F1:A3", "FF:F1:A3")

    /** 按 MAC 前缀判定是否 FIIL 设备（大小写不敏感）。 */
    fun isFiilAddress(address: String?): Boolean {
        val a = address?.uppercase() ?: return false
        return FIIL_OUI_PREFIXES.any { a.startsWith(it) }
    }

    /** 名字里出现这些片段即判定为 FIIL 耳机。 */
    private val FIIL_TOKENS = listOf(
        "FIIL", "FIIL CC", "FIIL GS", "FIIL KEY", "FIIL T", "FIIL CARAT", "FIIL DIVA",
    )

    /**
     * 综合判定：名字命中，或 MAC 前缀命中。
     *
     * 两个都要试，因为：
     *  - 用户改了蓝牙名 → 名字失效，但 MAC 不变；
     *  - 少数代工厂批次的 OUI 不在上表 → MAC 失效，但名字还带 FIIL。
     */
    fun isFiilDevice(name: String?, address: String? = null): Boolean =
        isFiilName(name) || isFiilAddress(address)

    fun isFiilName(name: String?): Boolean {
        val n = name?.uppercase() ?: return false
        return FIIL_TOKENS.any { n.contains(it) }
    }

    /**
     * 把蓝牙地址打码，供日志使用。
     *
     * **为什么需要**：结项验收报告 P0-1 指出，模块有多处日志把蓝牙 MAC 明文打出去，
     * release 包里这些字符串也被实证残留。蓝牙 MAC 是可长期追踪的设备标识，
     * 不属于排障必需信息。
     *
     * 保留**前两段**（OUI 的高两字节，`B0:F1`）—— 排障时靠它判断是哪家芯片，
     * 也正好与 [FIIL_OUI_PREFIXES] 的厂商判定对齐；后四段全部打码。
     *
     * ```
     * B0:F1:AA:BB:CC:DD  ->  B0:F1:**:**:**
     * ```
     *
     * 传入 null / 空串 / 段数不足时返回 `"?"` 或 `"**"`，不抛异常。
     */
    fun maskAddress(address: String?): String {
        val a = address?.trim().orEmpty()
        if (a.isEmpty()) return "?"
        val parts = a.split(":")
        if (parts.size < 3) return "**"
        return parts.take(2).joinToString(":") + ":**:**:**"
    }
}
