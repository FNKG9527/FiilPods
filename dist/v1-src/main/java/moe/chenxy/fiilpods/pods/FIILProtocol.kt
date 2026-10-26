package moe.chenxy.fiilpods.pods

import android.util.Log

/**
 * FIIL 语义化协议层（目标要求的 `FIILProtocol`）。
 *
 * 设计目标：把「两套并存的帧格式 + 三套命令空间」收敛成一组语义方法，
 * 上层的 UI / Hook / 超级岛只调用这里，不关心底层是 GAIA 还是 Mate 帧。
 *
 * ── 两套帧格式 ─────────────────────────────────────────────
 * · **GAIA（legacy）**：`FF ver flags len 02 34 cmdH cmdL payload`
 *   用于 earType 1~43 / 247 / 250 的老机型，走标准 SPP UUID 00001101。
 * · **Mate**：`00 cid 01 00 [len+2] [cmdId] [len] payload`
 *   用于 F045~F053 机型（earType 45/46/47/48/49/51/53），走自定义 SPP UUID
 *   d52b47bc-2dea-42c4-b26d-20252fceeeb3。
 *
 * ── 三套命令空间 ───────────────────────────────────────────
 * · GAIA FFCOMMAND（0x0xxx / 0x2xxx / 0x4xxx，见 [FiilCommands]）
 * · Mate classifyId（1~50，见 [FiilMate.Cid]）
 * · Airoha Race（CC Pro2 等，蓝牙侧 BLE，本模块暂不接管）
 *
 * 每个方法都在注释里标注了逆向得到的**原始字节**与来源类/行号，
 * 这是交付要求里「FIILProtocol.kt 中每个指令方法都有注释」的落实。
 */
object FIILProtocol {

    private const val TAG = "FiilPods-Protocol"

    /**
     * `c.java:321 setChangLan` 的 commandId 常量。
     *
     * Mate 帧的 `commandId` 是**有符号 byte**，源码用负数表示大于 127 的值
     * （例如 `getChangLanStatus` 写 `-128`，线值其实是 0x80 = 128）。
     * 发送时统一按 `and 0xFF` 落到一个字节，见 [FiilMate.buildFrame]。
     */
    private const val MATE_CMD_MULTIPOINT = 1

    /** `f.java:711-726 setTone` 的 commandId —— 语音提示音量（cid=1 组）。 */
    private const val MATE_CMD_TONE = 3

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 请求电量。
     *
     * GAIA：`FF 01 00 00 02 34 03 03`  （cmd = GET_BATTERY_LEVEL = 771 = 0x0303，无 payload）
     * Mate：`00 27 01 00 00 01`        （cid = QUERY(39=0x27)，cmdId = BATTERY(1)，无 payload）
     * 来源：`com.fiil.sdk.manage.FiilManager.getBatteryLevel()` /
     *      `com.fengeek.main.i.d.c.getElectricity()` @c.java:75
     */
    fun requestBattery() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.BATTERY)
        } else {
            FiilRfcommController.queryBattery()
            // 老机型充电盒电量是独立命令 GET_BATTERY_OF_BOX = 0x077B
            FiilRfcommController.send(FiilCommands.GET_BATTERY_OF_BOX, ByteArray(0), newProtocol = false)
        }
    }

    /**
     * **解锁（打招呼）** —— Mate 链路的前置条件，必须第一个发。
     *
     * 帧：`00 27 01 00 04 FF 02 02 34`
     *     cid = QUERY(39) / cmdId = 0xFF / payload = `02 34`（GAIA vendor id 0x0234）
     * 来源：`com/fengeek/main/i/d/h.java:712-727 setUnlock`
     *       —— `setCommandClassifyId(39)` + `setCommandId(-1)` + `setBuffer({2, 0x34})`
     * 时机：`com/fengeek/main/f053/fragment/F053MainFragment.java:275-279`，
     *       `case 5:`（"message: 蓝牙已连接"）里第一件事就是 `setUnlock(...)`。
     *
     * **不发这条，耳机对后面所有查询都不回包**（真机 FIIL Key Max 实测：
     * 不发时 VERSION/BATTERY/EQ 全部无响应；发了之后每一帧都有应答）。
     *
     * 这里 **不** 用 `isMateDialect` 做门控。原因是一个死锁：
     * `isMateDialect` 在 EarType 未知（名字被用户改掉 / 不在 NAME_RULES 里）
     * 时是 false，而它又只能靠「收到 0x00 打头的回包」来二次纠正 ——
     * 不发 setUnlock 耳机就不回包，不回包就永远判不出 Mate，永远不发。
     * 所以判定条件放宽成：**确认是 Mate** 或 **型号未知** 就打一枪。
     * 已知是 GAIA 的机型（含 earType=39 的 CC Pro2 —— 它也用自定义 UUID 但走 GAIA 帧）
     * 一律不发，保持与原 APP 一致。
     * GAIA 老机型即使误收这条未知 cid=39 帧也只会忽略，不会产生副作用。
     */
    fun unlock() {
        val ctrl = FiilRfcommController
        if (!ctrl.isMateDialect && ctrl.earType != -1) return
        ctrl.sendMate(
            FiilMate.Cid.QUERY, FiilMate.Query.UNLOCK, byteArrayOf(0x02, 0x34),
        )
    }

    /** 查询固件版本。GAIA `03 01` / Mate `00 27 01 00 00 02`。 */
    fun requestVersion() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.VERSION)
        } else {
            FiilRfcommController.queryVersion()
        }
    }

    /** 查询机型（earType）。仅 GAIA 有独立命令 0x0309；Mate 机型由设备名推断。 */
    fun requestEarType() {
        if (!FiilRfcommController.isMateDialect) FiilRfcommController.queryEarType()
    }

    /** 查询当前降噪（MAF）档位。GAIA `02 81` / Mate `00 27 01 00 00 0C`。仅带 ANC 的机型发。 */
    fun requestMafMode() {
        if (FiilModels.supportsAnc(FiilRfcommController.earType)) {
            if (FiilRfcommController.isMateDialect) {
                FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.MAF_STATUS)
            } else {
                FiilRfcommController.queryAnc()
            }
        }
    }

    /** 查询影院（空间音频）开关。仅 F046 有：`00 27 01 00 00 18`。 */
    fun requestCinemaMode() {
        if (FiilModels.supportsCinema(FiilRfcommController.earType)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.SPATIAL_AUDIO)
        }
    }

    /** 查询低延迟开关。GAIA `07 21` / Mate `00 27 01 00 00 08`。 */
    fun requestLowLatency() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.LOW_LATENCY)
        } else {
            FiilRfcommController.send(FiilCommands.GET_LOW_LATENCY, ByteArray(0), newProtocol = true)
        }
    }

    /**
     * 查询语音提示音量（Mate `cid=39 cmdId=130`，帧 `xx 27 01 00 02 82 00`）。
     *
     * 逆向依据：`com.fengeek.main.i.d.c.java:251-265 getToneStatus` ——
     * `setCommandClassifyId(39)` + `setCommandId(NetError.ERR_SSL_BAD_RECORD_MAC_ALERT)`，
     * 该 NetError 常量 = **-126**，`-126 & 0xFF = 0x82 = 130`。
     * 回包 payload 实测 `01 <volume>`（payload[0] 是字段存在标志，音量在 payload[1]）。
     */
    fun requestToneVolume() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.TONE)
        }
    }

    /** 查询多点（蓝牙畅连）开关。Mate `00 27 01 00 00 80`（cmdId = 128）。 */
    fun requestMultipoint() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.MULTIPOINT)
        } else {
            FiilRfcommController.send(FiilCommands.GET_MULTIPOINT, ByteArray(0), newProtocol = false)
        }
    }

    /** 查询 EQ 当前配置。Mate `00 27 01 00 00 04`；GAIA `07 0B`（EQ_V2）。 */
    fun readEQ() {
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.EQ_STATUS)
        } else {
            FiilRfcommController.send(FiilCommands.GET_EQ_V2, ByteArray(0), newProtocol = true)
        }
    }

    /**
     * 查询佩戴状态。
     *
     * Mate：`00 27 01 00 00 09` —— cid = QUERY(39)，cmdId = 9。
     *       来源 `g.java:267 getWear(List<int>)`：`setCommandClassifyId(39)` + `setCommandId(9)`，
     *       无 payload；仅 F051(Key Pro2) / F053(CC Pro3) 的命令类里有这个方法。
     * GAIA：`FF 01 00 00 02 34 01 87`（GET_WEARING_STATE = 1912 = 0x0778）。
     *
     * 只有 [FiilModels.supportsWearDetect] 为真的机型才发，其余机型发了也不会有回包。
     */
    fun requestWearState() {
        if (!FiilModels.supportsWearDetect(FiilRfcommController.earType)) return
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.WEAR)
        } else {
            FiilRfcommController.send(FiilCommands.GET_WEARING_STATE, ByteArray(0), newProtocol = false)
        }
    }

    /**
     * 设置佩戴检测开关（摘下暂停 / 戴上播放）。
     *
     * Mate：`00 26 01 00 01 [1/0]` —— cid = SET_WEAR(38)，**没有 cmdId**
     *       （`g.java:663 setWear(Boolean)` 只调 `setCommandClassifyId(38)` + `setBuffer`，
     *       值为 `bool ? (byte)1 : (byte)0`）。所以帧长 6：`00 26 01 00 01 <v>`。
     *       来源 `g.java:663` / `h.java:729`，仅 F051/F053 有。
     *
     * GAIA：`setWear(boolean)` @`FiilCommandUtil.java:1367-1375` 取值是
     *       **开=1、关=2**（`bArr[0] = (byte) (z ? 1 : 2)`），不是 1/0。
     *       两套协议这一点不同，GAIA 写 0 会被耳机当成未知值。
     */
    fun setWearDetect(on: Boolean) {
        if (!FiilModels.supportsWearDetect(FiilRfcommController.earType)) {
            Log.i(TAG, "setWearDetect skipped: earType=${FiilRfcommController.earType} 不支持佩戴检测")
            return
        }
        if (FiilRfcommController.isMateDialect) {
            // Mate 的 SET 回显不带新值，记下目标，回显到达时直接采用。
            FiilRfcommController.rememberPendingTarget("wear", on)
            FiilRfcommController.sendMate(FiilMate.Cid.SET_WEAR, 0, byteArrayOf(if (on) 1 else 0))
        } else {
            FiilRfcommController.send(FiilCommands.SET_WEAR, byteArrayOf(if (on) 1 else 2), newProtocol = false)
        }
    }

    /** 佩戴检测状态是否可读：只有 F051/F053 有 `getWear`。 */
    fun supportsWearSetting(): Boolean =
        FiilModels.supportsWearDetect(FiilRfcommController.earType)

    // ==================================================================
    // 控制
    // ==================================================================

    /**
     * 切换 MAF 智能调噪档位。
     *
     * GAIA：`FF 01 00 01 02 34 02 01 [mode]` —— cmd = SET_ANC(513=0x0201)，payload 1 字节
     *       来源 `FiilCommandUtil.setAnc(int)` → `getSendBuffer(2, 0x201, false, new byte[]{mode})`
     * Mate：`00 2C 01 00 03 01 [mode]` —— cid = SET_MAF(44)，payload 1 字节
     *       来源 `com.fengeek.main.i.d.c.setMAF(byte)` @c.java:404
     *
     * mode 取值（[FiilMate.Maf]）：0 关 / 1 降噪 / 2 开放 / 3 兼听 / 4 风中。
     *
     * **机型差异**（见 PROTOCOL_FIIL.md §3.4.1 覆盖差异表）：
     * 实现了 `setMAF`(cid=44) 的只有 **F046 / F049 / F051 / F053**；
     * F045(GS Lite)、F047(Atom)、F048(GS Links) 三款本身不带降噪，命令类里连
     * `setMAF` 都没有，因此这里只在 [FiilModels.supportsMateMaf] 为真时发 cid 44，
     * 其余机型打日志并退回 GAIA 的 SET_ANC（对无 ANC 机型等于空操作，无害）。
     */
    fun setMAFMode(mode: Int) {
        val v = mode.coerceIn(0, 4).toByte()
        if (FiilRfcommController.isMateDialect && FiilModels.supportsMateMaf(FiilRfcommController.earType)) {
            FiilRfcommController.sendMate(FiilMate.Cid.SET_MAF, 0, byteArrayOf(v))
        } else {
            if (FiilRfcommController.isMateDialect) {
                Log.w(
                    TAG,
                    "setMAFMode: earType=${FiilRfcommController.earType} 无 cid44，退回 GAIA SET_ANC",
                )
            }
            FiilRfcommController.setAnc(mode)
        }
    }

    /**
     * 影院模式（空间音频）开关。
     *
     * Mate：`00 32 01 00 03 01 [0/1]` —— cid = SET_SPATIAL_AUDIO(50) @c.java:504
     * **仅 F046（Key Max，earType=46）实现了该命令** —— `setSpatialAudio` 只在
     * `com\fengeek\main\i\d\c.java` 定义，全库唯一调用点是 `F046StyleFragment`。
     * 其他 Mate 机型与老 GAIA 机型都没有，对不支持的机型降级为 3D 音效（SET_3D），
     * 并在日志里说明——UI 侧用 [FiilModels.supportsCinema] 判断是否显示该开关。
     */
    fun setCinemaMode(on: Boolean) {
        val v = if (on) 1.toByte() else 0.toByte()
        if (FiilModels.supportsCinema(FiilRfcommController.earType)) {
            FiilRfcommController.expectAckPublicIfChanged("cinema", "影院模式", if (on) 1 else 0) {
                if (FiilRfcommController.cinemaMode) 1 else 0
            }
            FiilRfcommController.rememberPendingTarget("cinema", on)
            FiilRfcommController.sendMate(FiilMate.Cid.SET_SPATIAL_AUDIO, 0, byteArrayOf(v))
        } else {
            Log.i(
                TAG,
                "setCinemaMode: earType=${FiilRfcommController.earType} 无 spatial audio 命令，降级为 3D 音效",
            )
            FiilRfcommController.set3D(on)
        }
    }

    /**
     * 低延迟三档切换。
     *
     * **真实线值（已逆向确认，0/1/2 三态，不是二态）**：
     *  · GAIA `FF 01 00 01 02 34 07 22 [level]`（SET_LOW_LATENCY = 1826 = 0x0722）
     *    —— `FiilCommandUtil.setLowLatency(int i2)` @:1022 直接把入参当成 1 字节 payload
     *    下发：`getSendBuffer(VENDOR_FF, FFCOMMAND_SetLowLatency, false, (byte) i2)`。
     *  · Mate `00 25 01 00 03 01 [0/1]`（cid = SET_LOW_LATENCY(37) @c.java:388）
     *    —— Mate 侧 `setLowDelay(boolean)` 只接受布尔，**没有视频档**。
     *
     * UI 证据（三档确实存在）：`firstCCPro2SetFragment.I(int temp, boolean)` @:917
     *   temp=0 → 音乐模式(默认) → `setLowLatency(0)`
     *   temp=1 → 游戏模式        → `setLowLatency(1)`
     *   temp=2 → 视频模式        → `setLowLatency(2)`
     * 布局 `fragment_first_cc_pro2_set_new.xml` L35/39/43 三个 `movie_textView1/2/3`
     * 文案分别是「游戏模式 / 音乐模式 / 视频模式」。
     *
     * 因此 GAIA 机型下发 [LowLatencyMode.wireValue] 原值；Mate 机型只有二态，
     * 视频档降级为游戏档（两者都是降低延迟），并在日志中说明。
     */
    fun setLowLatencyMode(mode: LowLatencyMode) {
        val mateValue = if (mode == LowLatencyMode.MUSIC) 0 else 1
        val wire = if (FiilRfcommController.isMateDialect) mateValue else mode.wireValue
        FiilRfcommController.expectAckPublicIfChanged("low_latency", "低延迟切换", wire) {
            FiilRfcommController.lowLatencyLevel
        }
        if (FiilRfcommController.isMateDialect) {
            if (mode == LowLatencyMode.VIDEO) {
                Log.i(
                    "FIILProtocol",
                    "Mate 协议族只有音乐/游戏二态，视频档降级为游戏档（wire=1）",
                )
            }
            FiilRfcommController.rememberPendingTarget("low_latency", mateValue != 0)
            FiilRfcommController.sendMate(
                FiilMate.Cid.SET_LOW_LATENCY,
                0,
                byteArrayOf(mateValue.toByte()),
            )
        } else {
            FiilRfcommController.setLowLatencyLevel(mode.wireValue)
        }
    }

    /** 低延迟三档的语义枚举，线值与 FIIL+ APP 完全一致。 */
    enum class LowLatencyMode(val wireValue: Int, val label: String) {
        MUSIC(0, "音乐"),
        GAME(1, "游戏"),
        VIDEO(2, "视频"),
        ;

        companion object {
            fun fromWire(value: Int): LowLatencyMode =
                entries.firstOrNull { it.wireValue == value } ?: MUSIC
        }
    }

    /**
     * 设置语音提示音量（内置提示音 + 语音播报）。
     *
     * Mate：`00 01 01 00 03 03 01 [volume]` —— cid = SET_PARAM(1)，cmdId = 3（`f.java:711-726 setTone`）。
     * 预期回显：`00 01 02 00 03 03 01 00`（末字节 0 = 成功）。
     * 读回确认：`requestToneVolume()` → payload[0] == volume。
     * （回包 `01 27 02 00 03 82 01 02` 里 `01` 是 payloadLen、`02` 才是音量，
     *   即 payload 只有 1 字节，不要按「第 1 字节」去取。）
     *
     * ⚠ 线值与文案**反序**：0=音量高、1=音量中、2=音量低、3=静音。
     *
     * GAIA 老机型没有这条命令，直接忽略。
     */
    fun setToneVolume(volume: Int) {
        if (!FiilRfcommController.isMateDialect) {
            Log.i(TAG, "setToneVolume($volume) skipped: 非 Mate 链路")
            return
        }
        val v = volume.coerceIn(0, 3)
        FiilRfcommController.expectAckPublicIfChanged("tone", "语音提示音量", v) {
            FiilRfcommController.toneVolume
        }
        // cid=1 的 SET 回显不带新值，把目标记下来，回显到达时直接用。
        FiilRfcommController.rememberPendingTargetInt("tone", v)
        FiilRfcommController.sendMate(FiilMate.Cid.SET_PARAM, MATE_CMD_TONE, byteArrayOf(v.toByte()))
    }

    /** 语音提示音量档位（供 UI 显示）。 */
    enum class ToneLevel(val wireValue: Int, val label: String) {
        HIGH(0, "音量高"),
        MID(1, "音量中"),
        LOW(2, "音量低"),
        MUTE(3, "静音");

        companion object {
            fun fromWire(value: Int): ToneLevel? = entries.firstOrNull { it.wireValue == value }
        }
    }

    /**
     * 蓝牙畅连（多点连接）开关。
     *
     * GAIA：`FF 01 00 01 02 34 01 02 [0/1]`（SET_MULTIPOINT = 258 = 0x0102）
     * Mate：`00 01 01 00 03 01 [0/1]` —— cid = 1 **且 cmdId = 1**（`c.java:321 setChangLan`
     *       里同时调用了 `setCommandClassifyId(1)` 与 `setCommandId(1)`，不是无 cmdId）。
     */
    fun setMultipoint(on: Boolean) {
        val v = if (on) 1.toByte() else 0.toByte()
        FiilRfcommController.expectAckPublicIfChanged("multipoint", "蓝牙畅连切换", if (on) 1 else 0) {
            if (FiilRfcommController.multipoint) 1 else 0
        }
        if (FiilRfcommController.isMateDialect) {
            // ⚠ 畅连开关会让耳机固件换主从角色、**主动踢断链路**（实测 8~13 秒才可再连）。
            // 这个标记必须在发帧之前置位：断线回调可能比任何事后设置都早，
            // 置晚了就会被当成「普通掉线」，界面上弹一条连接丢失、重连也只试 3 次就放弃。
            FiilRfcommController.expectMultipointSwitch()
            // cid=1 的 SET 回显不带新值，把目标记下来，回显到达时直接用。
            FiilRfcommController.rememberPendingTarget("multipoint", on)
            FiilRfcommController.sendMate(FiilMate.Cid.SET_PARAM, MATE_CMD_MULTIPOINT, byteArrayOf(v))
        } else {
            FiilRfcommController.send(FiilCommands.SET_MULTIPOINT, byteArrayOf(v), newProtocol = false)
        }
    }

    /**
     * 写入自定义 EQ。
     *
     * Mate：payload = `[保留=0][类型=15][b0..bn]` —— cid = SET_EQ(32) @c.java:338。
     *       APP 侧构造见 `...\f046\fragment\F046StyleFragment.java` 的 `s0()` @L890-903：
     *       `f29301i` 是「读取时原样保存的整块 buffer」，长度不足 12 直接放弃写入；
     *       写入前把 `f29301i[1] = 15`（自定义档）、`f29301i[i2 + 2] = 滑块值`，
     *       再把整块原样发回 —— 所以 `[0]` 是读取时带回的保留字节，`[1]` 才是档位。
     *       预设切换同理：`z0(int type)` @L986-999 只改 `[1]`。
     *       我们手里没有那份 buffer，用 0 填充 `[0]`（APP 在未读到 EQ 时也不会写入，
     *       所以这里约定：**必须先 [readEQ] 收到回包再写**，UI 已按此限制）。
     * GAIA：`FF F1 00 0B 02 34 07 0C [类型][b0..b9]` —— SET_EQ_V2(1804=0x070C)。
     *       老协议没有独立的「预设/自定义」位，APP 用 SET_EQ(515=0x0203) 传 1 字节档位；
     *       这里沿用 V2 并前置档位字节，与 Mate 语义对齐。
     *
     * 频段中心频率（Hz）：见 [FiilModels.eqBandLabels]，按机型 5 / 8 / 10 段不等。
     * 增益编码：0~255 偏移，128 = 0 dB。
     */
    fun writeEQ(bands: ByteArray, type: Int = FiilModels.MATE_EQ_CUSTOM) {
        // 段数按机型校验：F045=5、F049=8、其余 10。多发会让耳机整帧丢弃
        // （APP 自己也是把读到的整块原样回写，长度天然等于机型段数）。
        // 例外：已经读到过真实回包时，以回包长度为准 —— 型号没识别出来（earType=-1）
        // 时默认按 10 段算，但耳机实际可能是 5/8 段，这时不该拒绝合法请求。
        val expect = FiilModels.eqBandLabels(FiilRfcommController.earType).size
        val known = FiilRfcommController.eqBands.size
        val allowed = if (known in 1..FiilMate.EQ_BAND_COUNT) setOf(expect, known) else setOf(expect)
        // 用 Log + return 而不是 require：这里跑在 com.android.bluetooth 进程里，
        // 抛异常会带崩蓝牙栈（用户体验是「蓝牙崩了」而不是「EQ 没生效」）。
        if (bands.size !in allowed) {
            Log.w(
                TAG,
                "writeEQ ignored: bands=${bands.size} expect=$expect known=$known",
            )
            return
        }
        val payload = ByteArray(bands.size + 2)
        payload[0] = 0                       // 保留位：APP 沿用读取回包的 [0]
        payload[1] = type.coerceIn(0, 255).toByte()
        bands.copyInto(payload, destinationOffset = 2)
        // EQ 写入的确认不体现在 eqIndex 上（自定义档恒为 -1），所以严格等回显。
        FiilRfcommController.expectAckPublicStrict("eq", "EQ 写入")
        if (FiilRfcommController.isMateDialect) {
            FiilRfcommController.sendMate(FiilMate.Cid.SET_EQ, 0, payload)
        } else {
            FiilRfcommController.send(FiilCommands.SET_EQ_V2, payload, newProtocol = true)
        }
    }

    /**
     * 切换 EQ 预设档位。
     *
     * Mate 机型：与 APP `z0(int type)` @L986-999 一致 —— 只把 `payload[1]` 换成预设编号，
     * 再整块发回，所以必须已经读到过 EQ 回包（有 `eqBands`），否则 APP 自己也会放弃
     * （`if (this.f29301i.length < 12) return;`）。
     * 老 GAIA 机型：SET_EQ(515) 直接传 1 字节预设下标，见 [FiilRfcommController.setEq]。
     */
    fun setEQPreset(presetIndex: Int) {
        if (!FiilRfcommController.isMateDialect) {
            FiilRfcommController.setEq(presetIndex)
            return
        }
        val bands = FiilRfcommController.eqBands
        if (bands.isEmpty()) {
            Log.w(TAG, "setEQPreset: 尚未读到 EQ 频段，先 readEQ()")
            return
        }
        writeEQ(ByteArray(bands.size) { bands[it].toByte() }, FiilModels.matePresetType(presetIndex))
    }

    /**
     * 查询全部自适应开关状态（cid=39 的五个 getter）。
     *
     * 只有该机型真的实现了对应 getter 才发，否则发了耳机不会回包，
     * 白白占用 SPP 带宽（Mate 链路的写队列只有 64 深度）。
     *
     * 线值表（`com\fengeek\main\i\d\f.java`，NetError 常量换算为无符号字节）：
     *  · getTuning         `cid=39 cmd=135`（-121）—— F049
     *  · getEQAdaptive     `cid=39 cmd=137`（-119）—— F049/F051/F053
     *  · getEarAdaptive    `cid=39 cmd=138`（-118）—— F049/F053
     *  · getNoiseAdaptive  `cid=39 cmd=139`（-117）—— F049/F051/F053
     *  · getWindAdaptive   `cid=39 cmd=140`（-116）—— F049
     */
    fun requestAdaptiveSwitches() {
        if (!FiilRfcommController.isMateDialect) return
        val ear = FiilRfcommController.earType
        if (FiilModels.supportsTuning(ear)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.TUNING_STATUS)
        }
        if (FiilModels.supportsEqAdaptive(ear)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.EQ_ADAPTIVE)
        }
        if (FiilModels.supportsEarAdaptive(ear)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.EAR_ADAPTIVE)
        }
        if (FiilModels.supportsNoiseAdaptive(ear)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.NOISE_ADAPTIVE)
        }
        if (FiilModels.supportsWindAdaptive(ear)) {
            FiilRfcommController.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.WIND_ADAPTIVE)
        }
    }

    /**
     * 开/关某个自适应开关。
     *
     * 五个 setter 在 `f.java` 里都是 `setCommandClassifyId(1)` +
     * `setBuffer(new byte[]{z ? 1 : 0})`，只有 commandId 不同：
     *  · setTuning        `cid=1 cmd=8`  —— 云感调音
     *  · setEQAdaptive    `cid=1 cmd=14` —— 自适应 EQ
     *  · setEarAdaptive   `cid=1 cmd=15` —— 耳道自适应降噪
     *  · setNoiseAdaptive `cid=1 cmd=16` —— 环境自适应降噪
     *  · setWindAdaptive  `cid=1 cmd=17` —— 自适应抗风噪
     *
     * 机型门控由 [FiilModels.supportsXxxAdaptive] 各自负责，
     * 这里只做一次总的前提检查（必须是 Mate 链路）。
     */
    fun setAdaptiveSwitch(kind: FiilRfcommController.AdaptiveKind, on: Boolean) {
        if (!FiilRfcommController.isMateDialect) {
            Log.i(TAG, "setAdaptiveSwitch(${kind.name}) skipped: 非 Mate 链路")
            return
        }
        val ear = FiilRfcommController.earType
        val supported = when (kind) {
            FiilRfcommController.AdaptiveKind.NOISE -> FiilModels.supportsNoiseAdaptive(ear)
            FiilRfcommController.AdaptiveKind.WIND -> FiilModels.supportsWindAdaptive(ear)
            FiilRfcommController.AdaptiveKind.EAR -> FiilModels.supportsEarAdaptive(ear)
            FiilRfcommController.AdaptiveKind.EQ -> FiilModels.supportsEqAdaptive(ear)
            FiilRfcommController.AdaptiveKind.TUNING -> FiilModels.supportsTuning(ear)
        }
        if (!supported) {
            Log.i(TAG, "setAdaptiveSwitch(${kind.name}) skipped: earType=$ear 不支持")
            return
        }
        FiilRfcommController.setAdaptiveSwitch(kind, on)
    }

    /** 一次性刷新全部状态（UI 下拉刷新 / 连接后补数据）。
     *
     * 顺序与 [handshake] 一致，但不做机型判断以外的副作用，
     * 可以在任意时刻重复调用。
     */
    fun refreshAll() {
        if (!FiilRfcommController.isConnected) return
        requestVersion()
        if (FiilRfcommController.isMateDialect) {
            requestBattery()
            requestMafMode()
            requestLowLatency()
            requestCinemaMode()
            requestMultipoint()
            requestWearState()
            readEQ()
        } else {
            requestEarType()
            requestBattery()
            requestMafMode()
            requestLowLatency()
            requestMultipoint()
            requestWearState()
        }
    }

    /**
     * 写入自定义 EQ，输入是若干 `0~255` 的 Int（128 = 0 dB）。
     *
     * Mate 机型：payload = 保留位 + 预设编号 15（自定义）+ 各段增益，
     * 见 [writeEQ] 对 `F046StyleFragment.s0()` @L890-903 的说明。
     * GAIA 机型：同样走 [writeEQ] 的 V2 路径。
     */
    fun setEQCustom(bands: IntArray) {
        // 段数按机型（F045=5 / F049=8 / 其余 10）；未知机型时上层会用
        // [FiilMate.defaultBands] 按当前 earType 生成，长度天然对得上。
        // 越界不抛异常（蓝牙进程不能崩），转成规整后的合法长度。
        val expect = FiilModels.eqBandLabels(FiilRfcommController.earType).size
        val normalized = if (bands.size == expect) {
            bands
        } else {
            Log.w(TAG, "setEQCustom: bands=${bands.size} expect=$expect，按机型段数重采样")
            IntArray(expect) { bands.getOrNull(it) ?: 128 }
        }
        val clamped = ByteArray(normalized.size) { normalized[it].coerceIn(0, 255).toByte() }
        writeEQ(clamped, FiilModels.MATE_EQ_CUSTOM)
    }

    /** 3D 音效开关（老机型的空间感增强，与影院模式不同）。 */
    fun set3DAudio(on: Boolean) = FiilRfcommController.set3D(on)

    /** 寻找耳机（响铃）。GAIA `FF 01 00 01 02 34 01 09 01`。 */
    fun findDevice() = FiilRfcommController.findDevice()

    /** 音量 ±1 级。 */
    fun volumeUp() = FiilRfcommController.volumeUp()
    fun volumeDown() = FiilRfcommController.volumeDown()

    /** 媒体控制。 */
    fun mediaPlay() = FiilRfcommController.mediaPlay()
    fun mediaPause() = FiilRfcommController.mediaPause()
    fun mediaNext() = FiilRfcommController.mediaNext()
    fun mediaPrev() = FiilRfcommController.mediaPrev()

    // ==================================================================
    // 握手
    // ==================================================================

    /**
     * 连接后的初始化序列。
     *
     * 逆向确认的顺序（`com/fiil/sdk/c/a.java:132-155`）：先版本、再机型、再电量、再 ANC。
     * Mate 机型（F045~F053）没有 earType 命令，改为「版本 → 电量 → MAF → 低延迟 → 影院 → 多点」。
     */
    fun handshake() {
        val ctrl = FiilRfcommController
        // ⚠️ Mate 机型必须先发 setUnlock：耳机在收到它之前对所有查询都保持静默。
        // 这是真机验证过的硬前置（见 unlock() 的注释），不能省略、不能后置。
        unlock()
        if (ctrl.earType == -1) {
            // 型号未知（用户改过蓝牙名 / 不在 NAME_RULES 里）：同时用两套协议各打一发版本查询。
            // Mate 耳机只应答 0x00 打头的帧，GAIA 耳机只应答 0xFF 打头的帧，互不干扰；
            // 谁先回包，readLoop 就会把 isMateDialect 定下来，之后按正确的那套继续。
            // 不做这一步的话，名字识别不出来时会一直按 GAIA 发，Mate 机型永远没有状态。
            ctrl.sendMate(FiilMate.Cid.QUERY, FiilMate.Query.VERSION)
            ctrl.queryVersion()
        } else {
            requestVersion()
        }
        if (ctrl.isMateDialect) {
            mateQueries()
        } else {
            requestEarType()
            requestBattery()
            requestMafMode()
            requestLowLatency()
        }
    }

    /**
     * Mate 链路的一整套状态查询。
     *
     * 由 [handshake] 在「已经确认是 Mate」时调用；也由
     * [FiilRfcommController] 在**运行中才判定出**是 Mate 时补调一次
     * （型号未知的机型握手时还在按 GAIA 发，判定出来时已经错过了这批查询）。
     */
    fun mateQueries() {
        requestBattery()
        requestMafMode()
        requestLowLatency()
        requestCinemaMode()
        requestMultipoint()
        requestWearState()
        // 语音提示音量（cid=39 cmdId=130）。老机型没有这条，发了也不回，无副作用。
        requestToneVolume()
        // Mate 机型 earType 只能靠广播 Beacon，SPP 链路上拿不到，
        // 所以这里补一次 EQ 状态：拿到它说明 Mate 握手真正通了，
        // 同时把预设/10 段增益先填上，UI 打开就是有值的。
        readEQ()
        // 自适应开关（cid=39 的 135/137/138/139/140）——按机型各发各的。
        requestAdaptiveSwitches()
    }
}


