package moe.chenxy.fiilpods.pods

/**
 * FIIL「Mate」帧编解码（第三套命令空间，F045~F053 机型使用）。
 *
 * 来源：`com/fengeek/main/i/b.java:237 writeMate(...)` —— 组包逻辑：
 * ```
 *   out[0] = 0x00
 *   out[1] = commandClassifyId            // 1 字节
 *   out[2] = 0x01
 *   out[3] = 0x00
 *   if (commandId != 0) {
 *       out[4] = payloadLen + 2
 *       out[5] = commandId
 *       out[6] = payloadLen
 *       out[7..] = payload
 *   } else {
 *       out[4] = payloadLen
 *       out[5..] = payload
 *   }
 * ```
 * 注意与 GAIA 帧（首字节 0xFF）完全不同：Mate 帧**没有** SOF/vendor/校验，
 * 直接裸写 SPP socket（`writeMate` 调 `write(byte[])`，不套 GAIA 外壳）。
 *
 * 回包解析对照 `com/fengeek/main/i/d/a.java:218 a(Message)`：
 * ```
 *   cid      = bArr[1]
 *   lenField = bArr[4]
 *   if (lenField <= 1) { 单字节状态帧; value = bArr[5]; cid = cid }
 *   else { cmdId = bArr[5]; len = bArr[6]; payload = bArr[7, 7+len) }
 * ```
 * 整帧长度 = 5 + lenField。
 */
object FiilMate {

    const val HEADER_SIZE = 5

    /**
     * 一帧 Mate 数据。
     *
     * @param seq 耳机侧滚动计数器（0~255）。**不能用于请求-响应匹配**：
     *   实测 TX seq=04 → RX seq=05，耳机用自己的计数器独立递增，
     *   RESPONSE 与 NOTIFY 共用同一个计数器。仅用于日志排查。
     */
    class Frame(
        val classifyId: Int,
        val commandId: Int,
        val payload: ByteArray,
        val size: Int,
        val seq: Int = 0,
    ) {
        fun byteAt(index: Int, default: Int = 0): Int =
            if (index in payload.indices) payload[index].toInt() and 0xFF else default

        fun boolAt(index: Int, default: Boolean = false): Boolean =
            if (index in payload.indices) (payload[index].toInt() and 0xFF) != 0 else default
    }

    /**
     * 组一帧。与 [com.fengeek.main.i.b.writeMate] 字节完全一致。
     *
     * @param classifyId 命令分类号（1~255）
     * @param commandId  子命令号，0 表示无子命令（此时帧里不出现该字节）
     */
    fun buildFrame(classifyId: Int, commandId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val cid = classifyId and 0xFF
        return if (commandId != 0) {
            val out = ByteArray(7 + payload.size)
            out[0] = 0x00
            out[1] = cid.toByte()
            out[2] = 0x01
            out[3] = 0x00
            out[4] = (payload.size + 2).toByte()
            out[5] = (commandId and 0xFF).toByte()
            out[6] = payload.size.toByte()
            System.arraycopy(payload, 0, out, 7, payload.size)
            out
        } else {
            val out = ByteArray(5 + payload.size)
            out[0] = 0x00
            out[1] = cid.toByte()
            out[2] = 0x01
            out[3] = 0x00
            out[4] = payload.size.toByte()
            System.arraycopy(payload, 0, out, 5, payload.size)
            out
        }
    }

    /**
     * 从缓冲区头部尝试解析一帧。
     * @return null 表示数据不足或不是 Mate 帧
     */
    fun parse(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Frame? {
        if (length < HEADER_SIZE) return null
        // ⚠️ 偏移 0 是 **seq 滚动计数器**，不是固定 0x00。
        // 早期实现写死 `!= 0x00 return null`，而真机 34 条回包里只有 3 条 seq=0，
        // 等于把 31/34 的应答当杂散字节丢掉 —— 这是「连上了但一条状态都读不到」的根因。
        // 实测（PC 端 Bleak，FIIL Key Max 固件 3.0.1.1）：
        //   TX seq=04 → RX seq=05，耳机用自己的计数器独立递增，RESPONSE 与 NOTIFY 共用。
        // 所以 seq 不能用于请求-响应匹配，也不能用于帧判别，只能原样透传。
        val seq = buffer[offset].toInt() and 0xFF
        val cid = buffer[offset + 1].toInt() and 0xFF
        if (cid == 0) return null
        // ⚠️ 偏移 2 是 **type**，不是固定值：
        //   0x01 = REQUEST（模块发出的）
        //   0x02 = RESPONSE（耳机应答，真机 FIIL Key Max 全部回包都是这个）
        //   0x03 = NOTIFY（耳机主动上报，如佩戴检测变化）
        // 早期实现写死 `!= 0x01 return null`，等于把所有耳机回包都当杂散字节丢掉 ——
        // 这是「连上了但检测不到耳机状态」的直接原因之一。
        val type = buffer[offset + 2].toInt() and 0xFF
        if (type != 0x01 && type != 0x02 && type != 0x03) return null
        if (buffer[offset + 3].toInt() and 0xFF != 0x00) return null

        val lenField = buffer[offset + 4].toInt() and 0xFF
        val total = HEADER_SIZE + lenField
        if (total > length) return null

        return if (lenField <= 1) {
            // 无 cmdId 的短帧：lenField=0 是空负载，lenField=1 是单字节状态
            // （SET 命令的应答就是 `xx cc 02 00 01 00`，payload[0]=00 表示成功）
            val payload = if (lenField == 1) byteArrayOf(buffer[offset + 5]) else ByteArray(0)
            Frame(cid, 0, payload, total, seq)
        } else {
            val cmdId = buffer[offset + 5].toInt() and 0xFF
            val payloadLen = buffer[offset + 6].toInt() and 0xFF
            if (7 + payloadLen > total) return null
            val payload = ByteArray(payloadLen)
            System.arraycopy(buffer, offset + 7, payload, 0, payloadLen)
            Frame(cid, cmdId, payload, total, seq)
        }
    }

    /**
     * 流式解帧：返回 (完整帧列表, 未消费字节数)。
     * 未消费部分已 memmove 回 buffer[0..)，调用方可直接继续读入。
     */
    fun drain(buffer: ByteArray, length: Int): Pair<List<Frame>, Int> {
        val frames = ArrayList<Frame>(4)
        var pos = 0
        while (pos < length) {
            // 关键区分：帧头不完整 → 需要更多数据，break 等下一轮；
            // 帧头完整但结构非法 → 跳过这个字节继续找，否则会永久卡死在这里。
            // 注意这里**不能**用首字节（seq）做重同步依据：seq 是滚动计数器，
            // 从 0 到 255 任意取值都可能出现。判别只靠 cid/type/reserved 三个字段。
            if (length - pos < HEADER_SIZE) break
            val cid = buffer[pos + 1].toInt() and 0xFF
            val type = buffer[pos + 2].toInt() and 0xFF
            val reserved = buffer[pos + 3].toInt() and 0xFF
            // type: 0x01=REQUEST / 0x02=RESPONSE / 0x03=NOTIFY（见 parse 的说明）
            val typeOk = type == 0x01 || type == 0x02 || type == 0x03
            if (cid == 0 || !typeOk || reserved != 0x00) {
                pos++
                continue
            }
            val lenField = buffer[pos + 4].toInt() and 0xFF
            // lenField=0 → 5 字节空帧；lenField=1 → 6 字节短帧（无 cmdId）；
            // 其余 → 5+lenField。必须分三种算，否则 SET 应答永远等不到。
            val need = when (lenField) {
                0 -> HEADER_SIZE
                1 -> HEADER_SIZE + 1
                else -> HEADER_SIZE + lenField
            }
            // 半帧：保留在缓冲区里等下一次 read/notify 拼起来，不能丢。
            if (length - pos < need) break
            val frame = parse(buffer, pos, need) ?: run { pos++; null }
            if (frame == null) continue
            frames.add(frame)
            pos += frame.size
        }
        val remain = length - pos
        if (remain > 0 && pos > 0) System.arraycopy(buffer, pos, buffer, 0, remain)
        return frames to remain
    }

    /**
     * 判断缓冲区头部是否是 Mate 帧（而不是 GAIA 帧）。
     *
     * ⚠️ 不能用首字节判别：Mate 帧首字节是 **seq 滚动计数器**（0x00~0xFF 都可能），
     * GAIA 帧首字节才是固定的 0xFF。真正的判据是「offset 2 是合法 type 且
     * offset 3 的 reserved 为 0」——GAIA 帧的 offset 2 是 payload 长度、
     * offset 3 起才是 payload，极难同时满足这两个条件。
     */
    fun looksLikeMate(buffer: ByteArray, length: Int): Boolean {
        if (length < HEADER_SIZE) return false
        val type = buffer[2].toInt() and 0xFF
        val reserved = buffer[3].toInt() and 0xFF
        return buffer[1].toInt() and 0xFF != 0 &&
            (type == 0x01 || type == 0x02 || type == 0x03) &&
            reserved == 0x00
    }

    // ------------------------------------------------------------------
    // classifyId 常量（全部来自 com/fengeek/main/i/d/c.java，逐条对照行号）
    // ------------------------------------------------------------------
    object Cid {
        /**
         * **通用设置组**（cid=1），真正的动作由 commandId 区分。
         *
         * 逆向时容易被「cid 就代表命令」误导，实际 cid=1 下挂了一整组开关，
         * 每个开关用自己的 commandId（见 [Param]）。线值统一是：
         * `00 01 01 00 03 <cmdId> 01 [0/1]`。
         *
         * 覆盖：畅连(1)、云感调音(8)、EQ 自适应(14)、耳道自适应(15)、
         *       环境自适应降噪(16)、自适应抗风噪(17)。
         */
        const val SET_PARAM = 1

        /** 语义上属于 [SET_PARAM] 组的畅连开关，保留以兼容旧引用。 */
        const val SET_MULTIPOINT = SET_PARAM

        /** EQ 写入，buffer=10 字节频段增益 —— c.java:338 setEQ */
        const val SET_EQ = 32

        /** 媒体播放控制 —— c.java:420 setMusicStatus */
        const val SET_MUSIC = 33

        /** 低延迟开关，buffer=[0/1] —— c.java:388 setLowDelay */
        const val SET_LOW_LATENCY = 37

        /** MAF（降噪模式）设置，buffer=[mode] —— c.java:404 setMAF */
        const val SET_MAF = 44

        /** 影院模式（空间音频）开关，buffer=[0/1] —— c.java:504 setSpatialAudio */
        const val SET_SPATIAL_AUDIO = 50

        /**
         * 佩戴检测开关，buffer=[0/1] —— `g.java:663` / `h.java:729` setWear（Bool）。
         *
         * 注意这条**没有 commandId**（只 setCommandClassifyId(38) + setBuffer），
         * 所以帧是 `00 26 01 00 01 [0/1]`。
         * 只有 F051(Key Pro2) / F053(CC Pro3) 的命令类里存在该方法。
         */
        const val SET_WEAR = 38

        /** 系统查询组（下面都是它的 cmdId）—— c.java:75~267 */
        const val QUERY = 39

        /**
         * 耳机**主动上报**通道（cid=40），只收不发。
         *
         * 依据：全库 `setCommandClassifyId(...)` 的实参只有 1/2/3/32/33/34/36/37/38/39/41/44，
         * **从不出现 40**；而各机型 `*MainFragment` 的 notify 分支全部判
         * `getCommandClassifyId() == 40`（F045:306..314、F046:299..317、F047:303..333、
         * F048:361..381、F049:303..343、F051:300..343、F053:300..343）。
         * 所以 40 是耳机自发推送的专用 classifyId：用户在耳机上按键改 ANC、
         * 摘下耳机、电量变化时，耳机用这条通道通知手机。
         *
         * 子命令号见 [Notify]。注意它与 [Query] **同号不同义**（如 cmd=7：
         * Query 是查 EQ 之外的媒体状态，Notify 是播放状态），必须分开处理。
         */
        const val NOTIFY = 40
    }

    /**
     * [Cid.NOTIFY]（cid=40）下的子命令号。全部来自各 `*MainFragment` 的 notify 分支。
     */
    object Notify {
        const val BATTERY = 1        // 电量，buff[1]=百分比（F046:299）
        const val PLAY_STATE = 7     // 播放状态，buff[0]（F046:303 → playState(buff[0])）
        const val LOW_LATENCY = 8    // 低延迟状态，buff[0]==1（F046:308 → setDeferState）
        const val WEAR = 9           // 佩戴状态，buff[0]==1（F051:316 / F053:316）
        const val MAF = 12           // 降噪模式，buff[0]（F046:317 / F051:343）
        const val MULTIPOINT_LIST = 136  // 畅连设备列表，type=0（F051:323）
        const val MULTIPOINT_RESULT = 137 // 畅连连接结果，type=1，buff[0]==2 表示超时（F051:333）
    }

    /**
     * [Cid.SET_PARAM]（cid=1）下的 commandId。
     *
     * 全部来自 `com\fengeek\main\i\d\f.java`（F049）/`g.java`（F051）/`h.java`（F053）
     * 的 setXxx 方法，逐条比对 `setCommandClassifyId(1)` + `setCommandId(N)`。
     * payload 一律是 1 字节 `[0/1]`。
     */
    object Param {
        const val MULTIPOINT = 1        // f.java setChangLan
        /**
         * 语音提示音量（内置提示音 + 语音播报）—— `f.java:711-726 setTone`。
         *
         * 源码：`setCommandClassifyId(1)` + `setCommandId(3)` + `setBuffer(new byte[]{b2})`。
         * 帧：`00 01 01 00 03 03 01 <volume>`；回显 `00 01 02 00 03 03 01 00`（末字节 0 = 成功）。
         *
         * ⚠ 线值与文案**反序**：0=音量高、1=音量中、2=音量低、3=静音
         * （`F046HomeFragment.java:524-538 setVolume(int)` 逐档写死）。
         */
        const val TONE = 3              // f.java setTone
        const val TUNING = 8            // f.java setTuning（云感调音）
        const val EQ_ADAPTIVE = 14      // f.java setEQAdaptive
        const val EAR_ADAPTIVE = 15     // f.java/h.java setEarAdaptive
        const val NOISE_ADAPTIVE = 16   // f.java/g.java/h.java setNoiseAdaptive
        const val WIND_ADAPTIVE = 17    // f.java setWindAdaptive
    }

    /** Cid.QUERY 下的子命令号。 */
    object Query {
        const val BATTERY = 1            // c.java:75  getElectricity
        const val VERSION = 2            // c.java:267 getVersion

        /**
         * **解锁/打招呼**，`h.java:712-727 setUnlock` 的 commandId（源码写 -1，线值 0xFF）。
         *
         * 这条是整个 Mate 链路的前置条件：**建链后不发它，耳机对所有查询都不回包**
         * （真机实测 FIIL Key Max：不发 setUnlock 时 VERSION/BATTERY/EQ 全无响应；
         * 发了之后每一帧都有应答）。APP 侧同样是「连接成功」后的第一帧就发它
         * （`F053MainFragment.java:275-279`，`case 5:` = "message: 蓝牙已连接"）。
         *
         * 帧：`00 27 01 00 04 FF 02 02 34`
         *     （cid=39 / cmdId=0xFF / payload=`02 34` = GAIA vendor id）
         */
        const val UNLOCK = 255           // h.java:712 setUnlock（-1 & 0xFF）
        const val EQ_STATUS = 4          // c.java:59  getEQStatus
        const val KEY = 5                // c.java:91  getKey
        const val RIGHT_UUID = 139       // c.java:219 getRightHeadsetUUID（-117 & 0xFF）
        const val MUSIC_STATUS = 7       // c.java:187 getMusicStatus
        const val LOW_LATENCY = 8        // c.java:155 getLowDelayStatus
        const val MAF_STATUS = 12        // c.java:171 getMAFStatus
        const val SPATIAL_AUDIO = 24     // c.java:235 getSpatialAudioStatus
        const val MULTIPOINT = 128       // c.java:27  getChangLanStatus（-128 & 0xFF）
        const val WEAR = 9               // g.java:278 / h.java getWear 的 commandId
        const val LDAC_UNLOCK = 142      // c.java:123（-114 & 0xFF）
        const val LDAC_SWITCH = 143      // c.java:107（-113 & 0xFF）
        const val CODEC = 144            // c.java:43  ((byte)-112)
        const val LEFT_UUID = 138        // c.java:139 ((byte)-118)
        const val TONE = 130             // c.java:251 ((byte)-126)

        // ---- 自适应开关查询（cid=39，命令类 f/g/h 独有）----
        //
        // 注意：138/139 在这里与 LEFT_UUID/RIGHT_UUID **数值撞车**。
        // 原因是一组常量来自不同机型命令类（c.java 是 F046 的类，f/g/h 是
        // F049/F051/F053 的类），同一 cmdId 在不同机型上语义不同。
        // 模块按 earType 精确门控（F046 不发自适应查询），因此不会误发。
        const val TUNING_STATUS = 135      // f.java getTuning（-121）
        const val EQ_ADAPTIVE = 137        // f/g/h.java getEQAdaptive（-119）
        const val EAR_ADAPTIVE = 138       // f/h.java getEarAdaptive（-118）
        const val NOISE_ADAPTIVE = 139     // f/g/h.java getNoiseAdaptive（-117）
        const val WIND_ADAPTIVE = 140      // f.java getWindAdaptive（-116）
    }

    /**
     * MAF 模式取值（`setMAF(byte)` 的 buffer[0]）。
     *
     * 取证：`F046StyleFragment.x0(int)` 的 5 个调用点是 0/1/2/3/4
     * （`F046StyleFragment.java:378/405/439/465/471/887/921/944/979`），
     * 而 `setMafModeUI(int)` @:1037 的图标绑定是
     * 0→f046_maf_off_select、1→f046_noise_select、2→f046_open_select、
     * 3→f046_listen_select、4→f046_wind_select。
     * 布局 `fragment_f49_style.xml:36-45` 的文案顺序同样是
     * 降噪/开放/风中/兼听/关闭 —— 所以 **2=开放、3=兼听、4=风中**。
     *
     * 注意：这里没有「自适应」档；FIIL 把整组功能叫「MAF 智能调噪」，
     * 智能指的是降噪算法本身，不是第 5 个档位。
     */
    object Maf {
        const val OFF = 0
        const val NOISE_CANCEL = 1
        const val OPEN = 2
        const val LISTEN = 3
        const val WIND = 4
    }

    /**
     * 自定义 EQ 的**最大**频段数量（10 段）。
     *
     * 注意这是上限而不是每台耳机都有的段数：F045 是 5 段、F049 是 8 段、其余默认 10 段，
     * 真实段数以 [FiilModels.eqBandLabels] 为准。写入时必须按机型段数发，
     * 多发会把 payload 撑长、耳机侧整帧丢弃（APP 自己也是把读到的整块原样回写）。
     */
    const val EQ_BAND_COUNT = 10

    /**
     * 全平（0 dB）的增益数组：128 是协议里的 0 dB 偏移量。
     *
     * **段数按机型**，不是固定 10 段。旧实现忽略 [earType] 恒返回 10 段，
     * 在 F045/F049 上作为兜底会发出超长 payload。
     */
    fun defaultBands(earType: Int = -1): IntArray =
        IntArray(FiilModels.eqBandLabels(earType).size) { 128 }
}
