package moe.chenxy.fiilpods.pods

import org.json.JSONArray
import org.json.JSONObject

/** 耳机状态快照，UI 与 Hook 之间通过 JSON 传递。 */
data class FiilStatus(
    val connected: Boolean = false,
    val address: String? = null,
    val name: String? = null,
    val earType: Int = -1,
    val ancMode: Int = -1,
    val batteryLeft: Int = -1,
    val batteryRight: Int = -1,
    val batteryCase: Int = -1,
    val wearLeft: Boolean = false,
    val wearRight: Boolean = false,
    val eqIndex: Int = -1,
    val is3D: Boolean = false,
    /**
     * 低延迟档位原始线值：0=音乐（默认）/ 1=游戏 / 2=视频。
     *
     * 逆向依据：`FiilCommandUtil.setLowLatency(int i2)` 把入参直接当成 1 字节 payload
     * 下发（`getSendBuffer(VENDOR_FF, FFCOMMAND_SetLowLatency, false, (byte) i2)`），
     * 而 `firstCCPro2SetFragment.I(int temp, boolean)` 对 0/1/2 分别调
     * `FiilManager.setLowLatency(0/1/2)`，文案为「音乐模式(默认)/游戏模式/视频模式」。
     */
    val lowLatencyLevel: Int = 0,
    /** 联的是不是 Mate 协议族（F045~F053）。决定 UI 上影院模式/多点是否可用。 */
    val isMate: Boolean = false,
    /** 影院模式（空间音频），仅 Mate 机型可读。 */
    val cinemaMode: Boolean = false,
    /** 蓝牙畅连（多点连接）开关。 */
    val multipoint: Boolean = false,
    /** 自定义 EQ 的 10 段增益原始字节（0~255，128 约等于 0 dB）。空数组表示还没读到。 */
    val eqBands: IntArray = IntArray(0),
    /**
     * 佩戴检测开关（摘下暂停 / 戴上播放）。
     *
     * 只有 [FiilModels.supportsWearDetect] 为真的机型（F051 Key Pro2 / F053 CC Pro3）可读写，
     * 其余机型该值无意义，UI 也不显示开关。
     */
    val wearDetect: Boolean = false,
    // ---- 自适应开关（Mate cid=1 组的独立开关，与 MAF 档位并列）----
    // 每个都按 earType 门控，不支持时对应字段恒为 false 且 UI 不渲染。
    /** 环境自适应降噪（cid=1 cmd=16）。 */
    val noiseAdaptive: Boolean = false,
    /** 自适应抗风噪（cid=1 cmd=17）。 */
    val windAdaptive: Boolean = false,
    /** 耳道自适应降噪（cid=1 cmd=15）。 */
    val earAdaptive: Boolean = false,
    /** 自适应 EQ 调节（cid=1 cmd=14）。 */
    val eqAdaptive: Boolean = false,
    /** 云感调音（cid=1 cmd=8）。 */
    val tuning: Boolean = false,
    /**
     * 当前 earType 是否来自用户手动指定（而非蓝牙名/回包自动推断）。
     *
     * UI 据此在「机型」一栏显示「手动」标记，并允许一键恢复自动识别。
     */
    val earTypeOverridden: Boolean = false,
    /**
     * 语音提示音量档位（内置提示音 + 语音播报）。
     *
     * 线值：0=音量高、1=音量中、2=音量低、3=静音 —— **与「越大越响」的直觉相反**
     * （`F046HomeFragment.java:524-538 setVolume(int)` 逐档写死）。
     * -1 表示尚未读到 / 该机型不支持。
     */
    val toneVolume: Int = -1,
) {
    val modelName: String get() = FiilModels.nameOf(earType)

    /** 综合电量：优先取左右耳较低值，其次充电盒。 */
    val displayBattery: Int
        get() = when {
            batteryLeft >= 0 && batteryRight >= 0 -> minOf(batteryLeft, batteryRight)
            batteryLeft >= 0 -> batteryLeft
            batteryRight >= 0 -> batteryRight
            batteryCase >= 0 -> batteryCase
            else -> -1
        }

    /** 低延迟当前档位（三档：音乐 / 游戏 / 视频）。 */
    val lowLatencyMode: FIILProtocol.LowLatencyMode
        get() = FIILProtocol.LowLatencyMode.fromWire(lowLatencyLevel)

    /** 低延迟是否处于「非默认」档位。用于兼容旧调用方与通知文案。 */
    val lowLatency: Boolean get() = lowLatencyLevel != 0

    fun toJson(): String = JSONObject().apply {
        put("connected", connected)
        put("address", address ?: "")
        put("name", name ?: "")
        put("earType", earType)
        put("ancMode", ancMode)
        put("batteryLeft", batteryLeft)
        put("batteryRight", batteryRight)
        put("batteryCase", batteryCase)
        put("wearLeft", wearLeft)
        put("wearRight", wearRight)
        put("eqIndex", eqIndex)
        put("is3D", is3D)
        put("lowLatencyLevel", lowLatencyLevel)
        put("isMate", isMate)
        put("cinemaMode", cinemaMode)
        put("multipoint", multipoint)
        put("eqBands", JSONArray().also { arr -> eqBands.forEach { arr.put(it) } })
        put("wearDetect", wearDetect)
        put("noiseAdaptive", noiseAdaptive)
        put("windAdaptive", windAdaptive)
        put("earAdaptive", earAdaptive)
        put("eqAdaptive", eqAdaptive)
        put("tuning", tuning)
        put("earTypeOverridden", earTypeOverridden)
        put("toneVolume", toneVolume)
    }.toString()

    companion object {
        val EMPTY = FiilStatus()

        fun fromJson(json: String?): FiilStatus {
            if (json.isNullOrEmpty()) return EMPTY
            return runCatching {
                val o = JSONObject(json)
                FiilStatus(
                    connected = o.optBoolean("connected", false),
                    address = o.optString("address").takeIf { it.isNotEmpty() },
                    name = o.optString("name").takeIf { it.isNotEmpty() },
                    earType = o.optInt("earType", -1),
                    ancMode = o.optInt("ancMode", -1),
                    batteryLeft = o.optInt("batteryLeft", -1),
                    batteryRight = o.optInt("batteryRight", -1),
                    batteryCase = o.optInt("batteryCase", -1),
                    wearLeft = o.optBoolean("wearLeft", false),
                    wearRight = o.optBoolean("wearRight", false),
                    eqIndex = o.optInt("eqIndex", -1),
                    is3D = o.optBoolean("is3D", false),
                    lowLatencyLevel = o.optInt("lowLatencyLevel", 0),
                    isMate = o.optBoolean("isMate", false),
                    cinemaMode = o.optBoolean("cinemaMode", false),
                    multipoint = o.optBoolean("multipoint", false),
                    eqBands = o.optJSONArray("eqBands")?.let { arr ->
                        IntArray(arr.length()) { arr.optInt(it, 128) }
                    } ?: IntArray(0),
                    wearDetect = o.optBoolean("wearDetect", false),
                    noiseAdaptive = o.optBoolean("noiseAdaptive", false),
                    windAdaptive = o.optBoolean("windAdaptive", false),
                    earAdaptive = o.optBoolean("earAdaptive", false),
                    eqAdaptive = o.optBoolean("eqAdaptive", false),
                    tuning = o.optBoolean("tuning", false),
                    earTypeOverridden = o.optBoolean("earTypeOverridden", false),
                    toneVolume = o.optInt("toneVolume", -1),
                )
            }.getOrDefault(EMPTY)
        }
    }
}
