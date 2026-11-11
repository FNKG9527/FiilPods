package moe.chenxy.fiilpods.pods

/**
 * 全模块的广播契约。
 *
 * 分三类：
 *  - 蓝牙进程 → 设置进程/UI：状态广播（耳机状态、电量、ANC）
 *  - 设置进程 → 蓝牙进程：控制请求（切 ANC、刷新状态）
 *  - UI → 蓝牙进程：用户操作
 *
 * extras 键名保持与 HyperOS 设置页的既有约定一致（left_battery 等），
 * 不要随意改 —— SettingsHook 的 batteryStatusFromExtras 依赖它们。
 */
object FiilAction {

    // ---- 蓝牙进程 → 其它进程 ----
    /** 连接/断开。extras: address, device_name, connected */
    const val ACTION_PODS_CONNECTED = "io.github.fnkg9527.fiilpods.action.PODS_CONNECTED"
    const val ACTION_PODS_DISCONNECTED = "io.github.fnkg9527.fiilpods.action.PODS_DISCONNECTED"

    /** 电量变化。extras: address, device_name, left_battery/left_charging/left_connected, right_*, case_* */
    const val ACTION_PODS_BATTERY_CHANGED = "io.github.fnkg9527.fiilpods.action.PODS_BATTERY_CHANGED"

    /** ANC 档位变化。extras: address, status(Int: 原始线值 0~4，语义随机型：GAIA 2=监听/3=开放，Mate 2=开放/3=兼听，4 都是风中) */
    const val ACTION_PODS_ANC_CHANGED = "io.github.fnkg9527.fiilpods.action.PODS_ANC_CHANGED"

    /** 完整状态 JSON 快照（模块自己的 UI 用）。extras: json */
    const val ACTION_STATE = "io.github.fnkg9527.fiilpods.action.STATE_CHANGED"

    /**
     * 一次控制操作没有生效。extras: cmd, value, status(操作前的值)
     *
     * 蓝牙进程在「发出命令 → 超时未收到确认回包 → 状态值也未变」时发出，
     * UI 收到后必须给出可见提示，不能静默失败（目标第五阶段要求）。
     */
    const val ACTION_OP_FAILED = "io.github.fnkg9527.fiilpods.action.OP_FAILED"

    // ---- 其它进程 → 蓝牙进程 ----
    /** 请求立即回发一次状态。 */
    const val ACTION_REFRESH_STATUS = "io.github.fnkg9527.fiilpods.action.REFRESH_STATUS"

    /** 设置页/UI 初始化时请求状态。 */
    const val ACTION_PODS_UI_INIT = "io.github.fnkg9527.fiilpods.action.PODS_UI_INIT"

    /** 用户选了一个 ANC 档位。extras: status(Int) */
    const val ACTION_ANC_SELECT = "io.github.fnkg9527.fiilpods.action.ANC_SELECT"

    /** 通用命令通道（模块 UI → 蓝牙进程）。extras: cmd, value, flag */
    const val ACTION_COMMAND = "io.github.fnkg9527.fiilpods.action.COMMAND"

    /** 请求当前状态快照。 */
    const val ACTION_REQUEST_STATE = "io.github.fnkg9527.fiilpods.action.REQUEST_STATE"

    /** 设置项变更（设备 ID、开关等），各进程据此重读配置。 */
    const val ACTION_CONFIG_CHANGED = "io.github.fnkg9527.fiilpods.action.CONFIG_CHANGED"

    /** 蓝牙进程里模块服务是否存活（设置页用来判断能否显示卡片）。 */
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE =
        "io.github.fnkg9527.fiilpods.action.MODULE_BLUETOOTH_SERVICE_ALIVE"

    // ---- 模块自己的 UI ----
    const val ACTION_SHOW_PODS_UI = "io.github.fnkg9527.fiilpods.action.SHOW_PODS_UI"

    // ---- extras 键 ----
    const val EXTRA_JSON = "json"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_STATUS = "status"
    const val EXTRA_CMD = "cmd"
    const val EXTRA_VALUE = "value"
    const val EXTRA_FLAG = "flag"
    /** 自适应开关类别（[FiilRfcommController.AdaptiveKind.name]，仅 cmd=adaptive 使用）。 */
    const val EXTRA_ADAPTIVE_KIND = "adaptive_kind"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_LEFT_BATTERY = "left_battery"
    const val EXTRA_LEFT_CHARGING = "left_charging"
    const val EXTRA_LEFT_CONNECTED = "left_connected"
    const val EXTRA_RIGHT_BATTERY = "right_battery"
    const val EXTRA_RIGHT_CHARGING = "right_charging"
    const val EXTRA_RIGHT_CONNECTED = "right_connected"
    const val EXTRA_CASE_BATTERY = "case_battery"
    const val EXTRA_CASE_CHARGING = "case_charging"
    const val EXTRA_CASE_CONNECTED = "case_connected"

    /** 自定义 EQ 的 10 段增益（IntArray，0~255，128 = 0 dB）。 */
    const val EXTRA_EQ_BANDS = "eq_bands"

    /** 低延迟档位（Int：0 音乐 / 1 游戏 / 2 视频）。 */
    const val EXTRA_LOW_LATENCY_MODE = "low_latency_mode"

    /** 影院（空间音频）开关。 */
    const val EXTRA_CINEMA = "cinema"

    /** 多点连接开关。 */
    const val EXTRA_MULTIPOINT = "multipoint"

    /** 协议族标记（Boolean：true = Mate 帧，F045~F053）。 */
    const val EXTRA_IS_MATE = "is_mate"

    /**
     * 当前连接耳机的型号码（Int，`FiilModels` 的 earType；未知为 -1 或缺省）。
     *
     * 加它是因为常驻通知的**耳机图案要跟着型号走**：通知进程
     * （`com.xiaomi.bluetooth`）与蓝牙进程不是同一个，拿不到控制器里的
     * `earType`，只能靠广播带过来。只加在
     * [ACTION_PODS_BATTERY_CHANGED] 上 —— 那是通知唯一的数据来源。
     */
    const val EXTRA_EAR_TYPE = "ear_type"

    /** 需要接收状态广播的系统包。 */
    val STATUS_TARGETS = listOf(
        "com.android.settings",
        "com.android.bluetooth",
        "com.xiaomi.bluetooth",
        // 融合设备中心（下拉控制中心的耳机卡片）
        "com.milink.service",
    )
}
