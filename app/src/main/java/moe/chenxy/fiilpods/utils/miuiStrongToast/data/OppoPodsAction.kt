package moe.chenxy.fiilpods.utils.miuiStrongToast.data

object OppoPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.oppopods.ui_init"
    const val ACTION_PODS_UI_CLOSED = "chen.action.oppopods.ui_closed"
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE = "chen.action.oppopods.module_bluetooth_service_alive"
    const val ACTION_PODS_CONNECTED = "chen.action.oppopods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.oppopods.pods_disconnected"
    const val ACTION_CONNECT_POD_REQUEST = "chen.action.oppopods.connect_pod_request"
    const val ACTION_DISCONNECT_POD_REQUEST = "chen.action.oppopods.disconnect_pod_request"
    const val ACTION_PODS_CONNECTION_STATE_CHANGED = "chen.action.oppopods.pods_connection_state_changed"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.oppopods.pods_battery_changed"
    const val ACTION_PODS_WEAR_STATUS_CHANGED = "chen.action.oppopods.pods_wear_status_changed"
    const val ACTION_ANC_SELECT = "chen.action.oppopods.anc_select"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.oppopods.pods_anc_select"
    const val ACTION_GET_PODS_MAC = "chen.action.oppopods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.oppopods.get_pods_mac"
    const val ACTION_REFRESH_STATUS = "chen.action.oppopods.refresh_status"
    const val ACTION_GAME_MODE_SET = "chen.action.oppopods.game_mode_set"
    const val ACTION_PODS_GAME_MODE_CHANGED = "chen.action.oppopods.pods_game_mode_changed"
    const val ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET = "chen.action.oppopods.transparency_vocal_enhancement_set"
    const val ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED = "chen.action.oppopods.pods_transparency_vocal_enhancement_changed"
    const val ACTION_SPATIAL_AUDIO_SET = "chen.action.oppopods.spatial_audio_set"
    const val ACTION_PODS_SPATIAL_AUDIO_CHANGED = "chen.action.oppopods.pods_spatial_audio_changed"
    const val ACTION_EQ_PRESET_SET = "chen.action.oppopods.eq_preset_set"
    const val ACTION_PODS_EQ_PRESET_CHANGED = "chen.action.oppopods.pods_eq_preset_changed"
    const val ACTION_EQ_PRESET_SAVE = "chen.action.oppopods.eq_preset_save"
    const val ACTION_EQ_PRESET_DELETE = "chen.action.oppopods.eq_preset_delete"
    const val ACTION_PODS_SMART_ANC_LEVEL_CHANGED = "chen.action.oppopods.pods_smart_anc_level_changed"
    const val ACTION_DUAL_DEVICE_CONNECTION_SET = "chen.action.oppopods.dual_device_connection_set"
    const val ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED = "chen.action.oppopods.pods_dual_device_connection_changed"

    /**
     * 语音提示音量（FIIL 专有，cid=1 cmd=3）。
     *
     * OppoPods 本身没有这个开关，是 FIIL 耳机独有的「内置提示音 + 语音播报音量」。
     * 沿用同一套 `chen.action.oppopods.*` 命名，让 UI 层不必为它开特例。
     */
    const val ACTION_TONE_VOLUME_SET = "chen.action.oppopods.tone_volume_set"
    const val ACTION_PODS_TONE_VOLUME_CHANGED = "chen.action.oppopods.pods_tone_volume_changed"
    const val ACTION_CYCLE_ANC = "chen.action.oppopods.cycle_anc"
    const val ACTION_AUTO_GAME_MODE_CHANGED = "chen.action.oppopods.auto_game_mode_changed"
    const val ACTION_RFCOMM_LOG_CONNECT = "chen.action.oppopods.rfcomm_log_connect"
    const val ACTION_RFCOMM_LOG_DISCONNECT = "chen.action.oppopods.rfcomm_log_disconnect"
    const val ACTION_RFCOMM_LOG_CLEAR = "chen.action.oppopods.rfcomm_log_clear"
    const val ACTION_RFCOMM_LOG = "chen.action.oppopods.rfcomm_log"
    const val ACTION_RFCOMM_DEBUG_SEND = "chen.action.oppopods.rfcomm_debug_send"
    // Adaptive模式开关状态变更广播，用于跨进程同步偏好设置（App → com.android.bluetooth / com.xiaomi.bluetooth）
    const val ACTION_ADAPTIVE_MODE_CHANGED = "chen.action.oppopods.adaptive_mode_changed"
    const val ACTION_CONFIG_CHANGED = "chen.action.oppopods.config_changed"

    // ---- FIIL 专有：低延迟三档（音乐 / 游戏 / 视频）----
    // 移植来的 OppoPods UI 原本只有一个「游戏模式」布尔开关，
    // 但 FIIL Mate 机型固件是真正的三档（cid=37, cmdId=0，线值 0=音乐 1=游戏 2=视频），
    // 所以另加一对 action 承载「第几档」，而不是「开/关」。
    const val ACTION_LOW_LATENCY_MODE_SET = "chen.action.oppopods.low_latency_mode_set"
    const val ACTION_PODS_LOW_LATENCY_MODE_CHANGED = "chen.action.oppopods.pods_low_latency_mode_changed"

    // ---- LDAC 编解码器开关（v2.2.0 新增）----
    // 这是真开关：蓝牙进程内反射 A2dpService.setCodecConfigPreference 切 LDAC，
    // 并通过 CODEC_CONFIG_CHANGED 回读真实状态。不是假的「点了跳设置页」。
    const val ACTION_LDAC_SET = "chen.action.oppopods.ldac_set"
    const val ACTION_PODS_LDAC_CHANGED = "chen.action.oppopods.pods_ldac_changed"
    const val EXTRA_LDAC_ENABLED = "ldac_enabled"
    const val EXTRA_LDAC_SUPPORTED = "ldac_supported"
    const val EXTRA_LDAC_KNOWN = "ldac_known"

    // ---- 耳机侧 LDAC（v2.2.2 新增）----
    // 上面三个 `ldac_*` 与下面三个 `headset_ldac_*` 是**两条独立来源**，接收端要分开读：
    //  · `ldac_*`         = 手机侧 A2DP 视角（FiilCodec 反射 A2dpService.getCodecStatus()），
    //                       回答「这条链路当前协商成了哪个编码器」。
    //  · `headset_ldac_*` = 耳机侧视角（RFCOMM cid=39 cmdId=143 开关 / 142 解锁），
    //                       回答「耳机自己开没开 LDAC」。
    // 耳机没开 LDAC 时，A2DP 握手根本不会声明 LDAC 能力，`ldac_*` 永远是
    // known=false —— 这正是用户报「控制页找不到 LDAC 开关」的根因：判定「该不该
    // 显示开关」用错了来源。现在显示与否由机型能力 + 耳机侧状态决定。
    const val EXTRA_HEADSET_LDAC_ENABLED = "headset_ldac_enabled"
    const val EXTRA_HEADSET_LDAC_UNLOCKED = "headset_ldac_unlocked"
    const val EXTRA_HEADSET_LDAC_KNOWN = "headset_ldac_known"

    // 蓝牙进程 → 模块 App：请求把用户带到系统「蓝牙音频编解码器」页。
    //
    // 为什么必须绕这一圈：`com.android.bluetooth` 是后台进程，Android 10+
    // 对后台进程 `startActivity` 有硬性限制，调用会被系统直接丢弃（不抛异常，
    // 用户看不到任何反应）。模块 App 自己在前台，由它来拉起页面才有效。
    // 蓝牙进程只负责发这条广播，不再自己 startActivity。
    const val ACTION_OPEN_CODEC_SETTINGS = "chen.action.oppopods.open_codec_settings"

    const val EXTRA_LOW_LATENCY_MODE = "mode"

    // ---- 触控灵敏度 / 按键自定义（v3.4.0）----
    // 触控灵敏度只有 GAIA 有；按键自定义只有 Mate 有（协议层会再判一次方言）。
    const val ACTION_TOUCH_SENSITIVITY_SET = "chen.action.oppopods.touch_sensitivity_set"
    const val ACTION_KEY_ACTION_SET = "chen.action.oppopods.key_action_set"

    /** 0=左耳，1=右耳。 */
    const val EXTRA_TOUCH_EAR = "ear"

    /** 0=低，1=中，2=高。 */
    const val EXTRA_TOUCH_LEVEL = "level"

    const val EXTRA_KEY_ID = "key_id"
    const val EXTRA_KEY_ACTION = "action"

    const val EXTRA_EQ_ENTRIES_JSON = "eq_entries_json"
}
