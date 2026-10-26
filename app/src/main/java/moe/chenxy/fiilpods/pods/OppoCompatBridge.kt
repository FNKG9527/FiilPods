package moe.chenxy.fiilpods.pods

import android.content.Context
import android.util.Log
import android.content.Intent
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.PodParams

/**
 * OppoPods 兼容广播桥 —— 让「原封不动移植过来的 OppoPods UI」能直接驱动 FIIL 耳机。
 *
 * ## 为什么需要它
 * UI 层是从 `1812z/OppoPods` 整份移植的，它只认 `chen.action.oppopods.*` 这套广播契约
 * （见 [OppoPodsAction]）。而 FIIL 侧原本用的是 `moe.chenxy.fiilpods.action.*`
 * （见 `pods/FiilAction.kt`）。两边各说各话，界面就会一直停在「已连接但没有任何数据」。
 *
 * 这个文件负责把 FIIL 的真实状态**翻译**成 OppoPods 的广播格式，单向补齐状态回传；
 * 反方向（UI → 蓝牙进程的命令）由 `BluetoothProcessHook` 统一接。
 *
 * ## 关键约束
 * 这里跑在 **com.android.bluetooth 进程**里，`ctx.packageName` 是 `com.android.bluetooth`。
 * 所以所有广播都必须显式 `setPackage(FiilConfig.MODULE_PACKAGE)`，
 * 否则会绕回蓝牙进程自己（历史上踩过这个坑）。
 */
object OppoCompatBridge {

    private const val TAG = "FiilPods-Compat"

    /**
     * 电量广播的「纯 int」兜底键。
     *
     * 这三个 key 是给 [OppoPodsAction.ACTION_PODS_BATTERY_CHANGED] 用的第二通道：
     * `status` 那条 Parcelable 万一因版本不一致反序列化失败，接收端仍可用这三个 int
     * 重建电量，不会崩、也不会空白。
     */
    const val EXTRA_BATTERY_LEFT = "battery_left"
    const val EXTRA_BATTERY_RIGHT = "battery_right"
    const val EXTRA_BATTERY_CASE = "battery_case"

    /**
     * 「兼听」「风中」在 OppoPods UI 里的状态码。
     *
     * ⚠️ 必须用 9 / 10，不能用 4 / 8。
     *
     * UI 侧 `MainUI.ACTION_PODS_ANC_CHANGED` 的 `when(status)` 把
     * 4 解释成 `ADAPTIVE`、8 解释成 `NOISE_CANCELLATION_DEEP` —— 那是 OPPO 的档位语义。
     * 早期实现把「兼听」映射成 4、「风中」映射成 8，结果界面上
     * 「兼听」会高亮成「自适应」、「风中」会高亮成「深度降噪」，
     * 用户看到的选中项与实际档位对不上（功能是对的，显示是错的）。
     *
     * 9 / 10 在 OPPO 语义里没有定义，MainUI 与 AncSwitch 里已按
     * `NoiseControlMode.LISTEN` / `WIND` 单独解析（见 `MainUI` 的 `when` 分支），
     * 因此不会再撞到任何 OPPO 档位。
     */
    const val UI_STATUS_LISTEN = 9
    const val UI_STATUS_WIND = 10

    /**
     * 从 `ACTION_PODS_BATTERY_CHANGED` 的纯 int extras 重建电量。
     *
     * 接收端（MainUI / PopupActivity）在 Parcelable 解不出来时调用它兜底。
     * 三个 key 全缺失时返回 null，表示这条广播没带兜底通道（保持上一次显示）。
     */
    fun batteryFromIntent(intent: Intent): BatteryParams? {
        if (!intent.hasExtra(EXTRA_BATTERY_LEFT) &&
            !intent.hasExtra(EXTRA_BATTERY_RIGHT) &&
            !intent.hasExtra(EXTRA_BATTERY_CASE)
        ) {
            return null
        }
        fun pod(level: Int): PodParams = PodParams(
            battery = level.coerceAtLeast(0),
            isCharging = false,
            isConnected = level >= 0,
            rawStatus = level.coerceAtLeast(0),
        )
        return BatteryParams(
            left = pod(intent.getIntExtra(EXTRA_BATTERY_LEFT, -1)),
            right = pod(intent.getIntExtra(EXTRA_BATTERY_RIGHT, -1)),
            case = pod(intent.getIntExtra(EXTRA_BATTERY_CASE, -1)),
        )
    }

    /** 上一次广播的值，用于变化检测，避免状态刷新时刷屏。 */
    private var lastAnc: Int = Int.MIN_VALUE
    private var lastBattery: String = ""
    private var lastWear: String = ""
    private var lastGameMode: Boolean? = null
    private var lastSpatial: Int? = null
    private var lastEq: Int? = null
    private var lastDual: Boolean? = null
    /** 语音提示音量（线值 0~3）。 */
    private var lastTone: Int? = null
    /** 低延迟档位（0=音乐 1=游戏 2=视频）。 */
    private var lastLatency: Int? = null
    private var lastConnected: Boolean? = null
    private var lastState: String = ""

    /** 状态里的 `ancMode` 是 MAF 线值 0~4；OppoPods UI 的 `status` 也按 1 基状态码解析。 */
    private fun ancStatusForUi(maf: Int): Int = when (maf) {
        1 -> 2   // 降噪
        2 -> 3   // 开放/通透
        3 -> UI_STATUS_LISTEN   // 兼听
        4 -> UI_STATUS_WIND     // 风中
        else -> 1 // 关闭
    }

    private fun send(ctx: Context, action: String, fill: Intent.() -> Unit) {
        runCatching {
            ctx.sendBroadcast(
                Intent(action)
                    .setPackage("moe.chenxy.fiilpods")
                    .apply(fill)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }.onFailure { Log.w(TAG, "broadcast $action failed", it) }
    }

    /**
     * 把一份完整状态翻译成 OppoPods 的广播序列。
     *
     * @param force true 时忽略变化检测，全部重发（UI 首次拉起、手动刷新用）
     */
    fun publish(ctx: Context, status: FiilStatus, force: Boolean = false) {
        publishConnection(ctx, status, force)
        publishAnc(ctx, status, force)
        publishBattery(ctx, status, force)
        publishWear(ctx, status, force)
        publishGameMode(ctx, status, force)
        publishSpatial(ctx, status, force)
        publishEq(ctx, status, force)
        publishDual(ctx, status, force)
        publishToneVolume(ctx, status, force)
        publishLowLatencyMode(ctx, status, force)
    }

    /**
     * 广播「畅连切换中」状态。
     *
     * 畅连开关一按，FIIL 耳机的固件就会主动踢断链路去换主从角色（实测 8~13 秒才可再连）。
     * 这不是故障，所以不能按普通掉线处理（否则界面会弹「连接丢失」）。
     * UI 侧 `hookConnectionState` 收到 `"connecting"` 会显示「连接中」而不是「已断开」。
     */
    fun publishSwitching(ctx: Context, force: Boolean = false) {
        if (!force && lastState == "connecting") return
        lastState = "connecting"
        send(ctx, OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            putExtra("state", "connecting")
        }
    }

    private fun publishConnection(ctx: Context, s: FiilStatus, force: Boolean) {
        if (force || lastConnected != s.connected) {
            lastConnected = s.connected
            if (s.connected) {
                send(ctx, OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("address", s.address.orEmpty())
                    putExtra("device_name", s.name.orEmpty())
                    // OppoPods 用 productId 查机型表；FIIL 没有 productId，
                    // 这里把 earType 塞进去，MainUI 的 detectDeviceCapabilities 会忽略它。
                    putExtra("product_id", s.earType.toString())
                }
                lastState = "connected"
                send(ctx, OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
                    putExtra("state", "connected")
                    putExtra("address", s.address.orEmpty())
                    putExtra("device_name", s.name.orEmpty())
                }
            } else {
                lastState = "disconnected"
                send(ctx, OppoPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
                    putExtra("state", "disconnected")
                }
                send(ctx, OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                    putExtra("address", s.address.orEmpty())
                }
            }
        }
    }

    private fun publishAnc(ctx: Context, s: FiilStatus, force: Boolean) {
        if (force || lastAnc != s.ancMode) {
            lastAnc = s.ancMode
            send(ctx, OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
                putExtra("address", s.address.orEmpty())
                putExtra("status", ancStatusForUi(s.ancMode))
            }
        }
    }

    private fun publishBattery(ctx: Context, s: FiilStatus, force: Boolean) {
        val key = "${s.batteryLeft}/${s.batteryRight}/${s.batteryCase}"
        if (!force && lastBattery == key) return
        lastBattery = key
        val params = BatteryParams(
            left = PodParams(
                battery = s.batteryLeft.coerceAtLeast(0),
                isCharging = false,
                isConnected = s.batteryLeft >= 0,
                rawStatus = s.batteryLeft.coerceAtLeast(0),
            ),
            right = PodParams(
                battery = s.batteryRight.coerceAtLeast(0),
                isCharging = false,
                isConnected = s.batteryRight >= 0,
                rawStatus = s.batteryRight.coerceAtLeast(0),
            ),
            case = PodParams(
                battery = s.batteryCase.coerceAtLeast(0),
                isCharging = false,
                isConnected = s.batteryCase >= 0,
                rawStatus = s.batteryCase.coerceAtLeast(0),
            ),
        )
        send(ctx, OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", s.address.orEmpty())
            putExtra("status", params)
            // 兜底通道：同时塞三个纯 int。
            //
            // `status` 是 Parcelable，跨进程时按「类名字符串」反序列化，一旦两端的
            // 模块版本不一致（典型：蓝牙进程里的注入代码是旧 release，UI 是刚覆盖
            // 安装的新 debug 包），类名对不上就会抛 BadParcelableException，把界面崩掉。
            // 而 String/Int/Float 这些基础类型没有这个问题，所以只要这三条 int
            // 在，接收端就永远能重建出电量，不依赖 Parcelable 能不能解出来。
            putExtra(EXTRA_BATTERY_LEFT, s.batteryLeft)
            putExtra(EXTRA_BATTERY_RIGHT, s.batteryRight)
            putExtra(EXTRA_BATTERY_CASE, s.batteryCase)
        }
    }

    private fun publishWear(ctx: Context, s: FiilStatus, force: Boolean) {
        val key = "${s.wearLeft}/${s.wearRight}"
        if (!force && lastWear == key) return
        lastWear = key
        send(ctx, OppoPodsAction.ACTION_PODS_WEAR_STATUS_CHANGED) {
            putExtra("address", s.address.orEmpty())
            putExtra("left_wear_status", s.wearLeft)
            putExtra("right_wear_status", s.wearRight)
            putExtra("case_wear_status", -1)
        }
    }

    /**
     * FIIL 的「低延迟」在 UI 上映射成「游戏模式」开关。
     * 线值 1=游戏 视为开启，0=音乐 / 2=视频 视为关闭。
     */
    private fun publishGameMode(ctx: Context, s: FiilStatus, force: Boolean) {
        val enabled = s.lowLatencyLevel == 1
        if (!force && lastGameMode == enabled) return
        lastGameMode = enabled
        send(ctx, OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    /** 影院模式 ↔ OppoPods 的「空间音频」。1 = 固定（开），0 = 关。 */
    private fun publishSpatial(ctx: Context, s: FiilStatus, force: Boolean) {
        val mode = if (s.cinemaMode) 1 else 0
        if (!force && lastSpatial == mode) return
        lastSpatial = mode
        send(ctx, OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            putExtra("mode", mode)
        }
    }

    private fun publishEq(ctx: Context, s: FiilStatus, force: Boolean) {
        if (!force && lastEq == s.eqIndex) return
        lastEq = s.eqIndex
        // 自定义 EQ：把 FIIL 的 0~255 原始值换算成 -6..+6 的 UI 增益。
        val gains = eqBandsToGains(s)
        val preset = EqDevicePreset(
            id = 0,
            name = "自定义",
            selected = s.eqIndex < 0,
            minValue = -6,
            maxValue = 6,
            frequencies = EqDefaults.FREQUENCIES,
            gains = gains,
        )
        val json = runCatching {
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    EqDevicePreset.serializer()
                ),
                listOf(preset),
            )
        }.getOrDefault("[]")
        send(ctx, OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED) {
            putExtra("preset", s.eqIndex)
            putExtra(OppoPodsAction.EXTRA_EQ_ENTRIES_JSON, json)
        }
    }

    private fun publishDual(ctx: Context, s: FiilStatus, force: Boolean) {
        if (!force && lastDual == s.multipoint) return
        lastDual = s.multipoint
        send(ctx, OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED) {
            putExtra("enabled", s.multipoint)
        }
    }

    /**
     * 语音提示音量（FIIL 专有）。
     *
     * `toneVolume` 是线值 0~3（0=高、3=静音，与直觉相反），-1 表示还没读到 ——
     * 这时不发广播，UI 保持 `--`。
     */
    private fun publishToneVolume(ctx: Context, s: FiilStatus, force: Boolean) {
        if (s.toneVolume < 0) return
        if (!force && lastTone == s.toneVolume) return
        lastTone = s.toneVolume
        send(ctx, OppoPodsAction.ACTION_PODS_TONE_VOLUME_CHANGED) {
            putExtra("level", s.toneVolume)
        }
    }

    /**
     * 低延迟三档（FIIL 专有）。
     *
     * `lowLatencyLevel` 是线值：0=音乐、1=游戏、2=视频。移植来的 UI 只有「游戏模式」
     * 布尔开关，所以那个开关仍走 [OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED]；
     * 这里额外发一条三档广播，供详情页新增的档位选择器使用。
     * `-1`（还没读到）时不发，UI 保持「未知」。
     */
    private fun publishLowLatencyMode(ctx: Context, s: FiilStatus, force: Boolean) {
        if (s.lowLatencyLevel < 0) return
        if (!force && lastLatency == s.lowLatencyLevel) return
        lastLatency = s.lowLatencyLevel
        send(ctx, OppoPodsAction.ACTION_PODS_LOW_LATENCY_MODE_CHANGED) {
            putExtra(OppoPodsAction.EXTRA_LOW_LATENCY_MODE, s.lowLatencyLevel)
        }
    }

    /**
     * FIIL 自定义 EQ 的原始字节是 0~255（128 = 0 dB，±12 dB 满量程）。
     * UI 的拖杆是 ±6 dB，所以这里按 6/12 缩放到一半量程。
     */
    fun eqBandsToGains(s: FiilStatus): List<Int> {
        val bands = s.eqBands
        if (bands.isEmpty()) return List(EqDefaults.FREQUENCIES.size) { 0 }
        val labels = FiilModels.eqBandLabels(if (s.earType > 0) s.earType else 0)
        val count = if (labels.isNotEmpty()) labels.size else bands.size
        val raw = IntArray(count) { i -> bands.getOrElse(i) { 128 } }
        return raw.map { v ->
            val db = (v - 128) * 12.0 / 128.0
            (db / 2.0).toInt().coerceIn(-6, 6)
        }
    }

    /** UI 手动刷新时把缓存清空，保证全量重发。 */
    fun resetCache() {
        lastAnc = Int.MIN_VALUE
        lastBattery = ""
        lastWear = ""
        lastGameMode = null
        lastSpatial = null
        lastEq = null
        lastDual = null
        lastTone = null
        lastLatency = null
        lastConnected = null
        lastState = ""
    }
}
