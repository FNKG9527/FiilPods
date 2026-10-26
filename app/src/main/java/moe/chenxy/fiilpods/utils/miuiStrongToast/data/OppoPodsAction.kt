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
    const val EXTRA_LOW_LATENCY_MODE = "mode"

    const val EXTRA_EQ_ENTRIES_JSON = "eq_entries_json"
}
