package moe.chenxy.fiilpods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FIIL 耳机 RFCOMM（经典蓝牙 SPP）连接控制器。
 *
 * 关键结论（来自对 FIIL+ 3.5.2 的逆向）：
 *  - 除 F042 / CC Pro2 等新机型外，FIIL 耳机的主力链路是 **经典蓝牙 SPP**，
 *    服务端 UUID = 00001101-0000-1000-8000-00805F9B34FB。
 *  - CC Pro2（earType=39）走自定义 UUID d52b47bc-2dea-42c4-b26d-20252fceeeb3。
 *  - 一个 socket、一个 InputStream、一个 OutputStream，帧按 GAIA 格式收发。
 *
 * 本类被设计成跑在 com.android.bluetooth 进程里（BLUETOOTH_PRIVILEGED 权限体系下），
 * 因此它天然持有已配对的 BluetoothDevice 引用，不需要扫描。
 */
@SuppressLint("MissingPermission")
object FiilRfcommController {

    private const val TAG = "FiilPods-RFCOMM"

    const val ACTION_STATE = "moe.chenxy.fiilpods.action.STATE_CHANGED"
    const val ACTION_COMMAND = "moe.chenxy.fiilpods.action.COMMAND"
    const val ACTION_REQUEST_STATE = "moe.chenxy.fiilpods.action.REQUEST_STATE"

    const val EXTRA_JSON = "json"

    /** 标准 SPP 服务 UUID，FIIL 老机型用这个。 */
    val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** CC Pro2（earType=39）用的自定义 UUID。 */
    val UUID_CC_PRO2: UUID = UUID.fromString("d52b47bc-2dea-42c4-b26d-20252fceeeb3")

    private const val MAX_FRAME = 512
    private const val RECONNECT_DELAY_MS = 5000L
    private const val WRITE_GAP_MS = 30L

    /**
     * 意外断线后的自动重连次数。
     *
     * 目标第二阶段要求「断线重连时恢复状态同步」。SPP 链路比 A2DP 脆弱得多：
     * 耳机放回仓、跨房间走过、手机切飞行模式，A2DP 仍显示已连接而 SPP socket 已断。
     * 这种情况下 `A2dpService` 不会回调，没有主动重连就永远失联。
     * 3 次 × 5s 覆盖「掏出耳机等我重连」的常见窗口，再多就变成无意义耗电。
     */
    private const val RECONNECT_ATTEMPTS = 3

    /**
     * 连上后多久还没收到任何字节，就判定「通道连错了」。
     *
     * 正常握手（setUnlock → VERSION）在蓝牙链路上一两百毫秒就有回包，
     * 3.5s 留足重传余量；再长会让用户明显感觉「打开了但半天没数据」。
     */
    private const val SILENCE_TIMEOUT_MS = 3500L

    /**
     * 畅连（多点）切换后耳机**必然**踢掉链路时的重连退避表（毫秒）。
     *
     * 这不是故障：耳机在切换主从角色，BLE/SPP 通道会被固件主动断开，
     * 实测需要 8~13 秒才能重新连上（PC 端 Bleak 实测结论）。
     * 所以这里用「2s → 3s → 5s → 8s → 12s」的退避重试，前后覆盖约 30s。
     */
    private val MULTIPOINT_SEAL_DELAYS_MS = longArrayOf(2000L, 3000L, 5000L, 8000L, 12000L)

    /** 用户/系统主动断开的原因：这些不触发自动重连。 */
    private val MANUAL_DISCONNECT_REASONS = setOf(
        "user-request", "stop", "a2dp-disconnected", "reconnect", "multipoint-switch",
    )

    private val started = AtomicBoolean(false)

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var readerThread: Thread? = null

    /**
     * 最近一次收到耳机字节的时间（`System.currentTimeMillis()`），0 表示本轮连接还没收到过。
     *
     * 用途见 [scheduleSilenceProbe]：Mate 机型在标准 SPP 上「连得上但一声不响」，
     * 光看 `connect()` 成功判断不出连错通道，必须靠「连上后一直没数据」来识别。
     */
    @Volatile
    private var lastRxAt: Long = 0L

    /**
     * 下一次连接优先走哪条 RFCOMM 通道；`null` 表示按机型推断（不干预）。
     *
     * 只有在 [scheduleSilenceProbe] 判定「连上但零回包」时才会写成明确值，
     * 用来把候选顺序翻转过来；一旦新通道真的收到数据（[lastRxAt] 被写），这个覆盖就清掉。
     */
    @Volatile
    private var preferCustomNext: Boolean? = null

    /**
     * 静默探测定时器：连上后一段时间内一个字节都没收到，就主动断开重连。
     *
     * 重连时 `openSocket` 会按 [connectedViaCustomUuid] 换到另一条通道（见 [connectBlocking]），
     * 这样即使机型判断错了，也能在几秒内自己纠正过来，而不必等用户来报「连上了但没数据」。
     */
    @Volatile
    private var silenceProbe: Thread? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var reconnectThread: Thread? = null

    /**
     * 连接临界区。
     *
     * `connect()` 内部会走到 `BluetoothSocket.connect()`，这是**阻塞调用**，
     * 标准 SPP 失败时通常要 1~3 秒才抛异常，逐路重试（标准 SPP → 自定义 UUID →
     * 反射 channel 1）累计能到 10 秒以上。
     *
     * 调用点里至少有三处跑在 com.android.bluetooth 的**主线程**上：
     *  - `registerReceiver()` 的 onReceive（模块 UI 每次刷新状态都会触发）
     *  - `A2dpService.handleConnectionStateChanged` 里的 `handler.post`（主 Looper）
     *  - `adoptAlreadyConnected` 的 0ms / 1500ms / 5000ms 三个 Handler 回调
     *
     * 主线程被卡住 = 蓝牙进程 ANR = 系统杀掉并重启蓝牙 = 控制器持有的 socket
     * 与状态全部丢失，表现为「检测不到已连接的耳机」。所以连接必须挪到后台线程，
     * 并用这把锁保证同一时刻只有一条连接在跑（否则多线程同时 connect 会互相
     * 覆盖 socket / output 字段，读到半初始化的状态）。
     */
    private val connectLock = Any()

    /** 是否已有一次后台连接在跑，避免 UI 反复刷新时堆积连接。 */
    @Volatile
    private var connectInFlight = false

    /** 串行执行阻塞式 connect 的后台线程，按需创建。 */
    @Volatile
    private var connectThread: Thread? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var deviceAddress: String? = null
        private set

    @Volatile
    var deviceName: String? = null
        private set

    /**
     * 厂商原始蓝牙名（`BluetoothDevice.getName()`），**不是**用户别名。
     *
     * 型号推断必须用它：用户可能把名字改成「我的耳机」，别名里就再也读不出型号了。
     * 而 [deviceName] 是给用户看的显示名（优先别名），不能拿来推断型号。
     */
    @Volatile
    var deviceOriginalName: String? = null
        private set

    /** 已识别到的 earType（来自 GET_EARTYPE 回包）。 */
    @Volatile
    var earType: Int = -1
        private set

    /**
     * 当前 earType 是否来自用户手动指定（而不是自动推断）。
     *
     * 手动指定时，GAIA 的 GET_EARTYPE 回包**不会**覆盖它 ——
     * 用户选 F046 而耳机回了 39，说明是同一个链接名下的另一只耳机，
     * 这种情况下尊重用户选择比纠正更有用（目标第五阶段：未验证型号允许手动选择）。
     */
    @Volatile
    var earTypeOverridden: Boolean = false
        private set

    /** 当前降噪档位（原始线值，语义随协议不同：GAIA 2=监听 3=开放；Mate 2=开放 3=兼听，4 两边都是风中）。 */
    @Volatile
    var ancMode: Int = -1
        private set

    /** 左 / 右 / 盒电量，-1 表示未知。 */
    @Volatile
    var batteryLeft: Int = -1
        private set

    @Volatile
    var batteryRight: Int = -1
        private set

    @Volatile
    var batteryCase: Int = -1
        private set

    @Volatile
    var wearLeft: Boolean = false
        private set

    @Volatile
    var wearRight: Boolean = false
        private set

    @Volatile
    var eqIndex: Int = -1
        private set

    @Volatile
    var is3D: Boolean = false
        private set

    /**
     * 低延迟档位原始线值：0=音乐（默认）/ 1=游戏 / 2=视频。
     *
     * 逆向依据：`FiilCommandUtil.setLowLatency(int i2)`（@:1022）把入参原样当 1 字节
     * payload 下发；`firstCCPro2SetFragment.I(int temp, boolean)`（@:917）对 0/1/2
     * 分别下发并显示「音乐模式(默认) / 游戏模式 / 视频模式」。
     */
    @Volatile
    var lowLatencyLevel: Int = 0
        private set

    /** 低延迟是否处于非默认档（兼容旧调用方）。 */
    val lowLatency: Boolean get() = lowLatencyLevel != 0

    /** 影院模式（空间音频），仅 Mate 协议族（F045~F053）支持。 */
    @Volatile
    var cinemaMode: Boolean = false
        private set

    /** 蓝牙畅连（多点连接）开关。 */
    @Volatile
    var multipoint: Boolean = false
        private set

    /**
     * 语音提示音量档位（内置提示音 + 语音播报）。
     *
     * 线值：0=音量高、1=音量中、2=音量低、3=静音。**与「越大越响」的直觉相反**
     * （`F046HomeFragment.java:524-538 setVolume(int)` 逐档写死）。
     * -1 表示尚未读到（GAIA 老机型没有这条命令）。
     */
    @Volatile
    var toneVolume: Int = -1
        private set

    /**
     * 是否在期待「畅连切换导致的断链」。
     *
     * 畅连开关下发后，耳机**一定会**踢掉链路（固件在换主从角色，不是故障）。
     * 必须在**发送之前**置位，否则断线回调可能早于任何事后设置。
     * 断线处理看到它为 true 时：
     *  · 不广播「连接丢失」这种吓人的状态，改广播「切换中」；
     *  · 走 [MULTIPOINT_SEAL_DELAYS_MS] 的专用退避重连，而不是普通的 3 次 × 5s。
     */
    @Volatile
    private var reconnectAfterMultipoint: Boolean = false

    /** 畅连切换后的专用重连线程。 */
    @Volatile
    private var multipointReconnectThread: Thread? = null

    /** 自定义 EQ 的 10 段原始字节；空数组表示还没读到。 */
    @Volatile
    var eqBands: IntArray = IntArray(0)
        private set

    /**
     * 佩戴检测开关（摘下暂停 / 戴上播放）。
     *
     * 只有 F051(Key Pro2) / F053(CC Pro3) 的命令类里有 `setWear`/`getWear`，
     * 其余机型该值恒为 false 且 UI 不显示开关。
     */
    @Volatile
    var wearDetect: Boolean = false
        private set

    // ---- 自适应开关状态（cid=1 组的独立开关，与 MAF 档位并列）----
    // 只在支持该开关的机型上被写入；不支持的机型永不发查询，回包也不会来。

    /** 环境自适应降噪（cid=1 cmd=16，F049/F051/F053）。 */
    @Volatile
    var noiseAdaptive: Boolean = false
        private set

    /** 自适应抗风噪（cid=1 cmd=17，仅 F049）。 */
    @Volatile
    var windAdaptive: Boolean = false
        private set

    /** 耳道自适应降噪（cid=1 cmd=15，F049/F053）。 */
    @Volatile
    var earAdaptive: Boolean = false
        private set

    /** 自适应 EQ 调节（cid=1 cmd=14，F049/F051/F053）。 */
    @Volatile
    var eqAdaptive: Boolean = false
        private set

    /** 云感调音（cid=1 cmd=8，仅 F049）。 */
    @Volatile
    var tuning: Boolean = false
        private set

    /**
     * 当前链路是不是 Mate 协议族（F045~F053 机型）。
     *
     * 判定依据（逆向结论）：
     *  · earType ∈ [FiilModels.BLE_ONLY] 系列之外的 45/46/47/48/49/51/53 走
     *    自定义 SPP UUID `d52b47bc-...` + Mate 帧；
     *  · 且确实是从自定义 UUID 建链成功的（老机型也有 earType 猜错的可能）。
     *
     * 所以这里用「连上后收到的第一帧是 0x00 打头」来判定，比单看 earType 稳。
     */
    @Volatile
    var isMateDialect: Boolean = false
        private set

    /** 建链时用的是哪个 UUID，用于事后判定协议族。 */
    @Volatile
    var connectedViaCustomUuid: Boolean = false
        private set

    /**
     * 当前这条链路是不是 **BLE GATT**（而不是经典蓝牙 SPP）。
     *
     * FIIL Key Max 实测：SPP 标准通道连上后完全静默、自定义 UUID 通道能被耳机反复踢掉，
     * 只有 BLE GATT（ff17/ff18）稳定——帧格式与 SPP 完全相同，所以上层协议层无感。
     * 见 [FiilGatt]。
     */
    @Volatile
    var usingGatt: Boolean = false
        private set

    /** 已收到的完整帧数，用来判断「发了查询但耳机没理我」。 */
    @Volatile
    private var rxFrameCount: Int = 0

    private val writeQueue = LinkedBlockingQueue<ByteArray>(64)
    private val writeLock = Any()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        writerThread = Thread({ writerLoop() }, "FiilPods-Writer").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "controller started")
    }

    fun stop() {
        started.set(false)
        disconnect("stop")
    }

    /**
     * 连接一副耳机 —— **异步、非阻塞**。
     *
     * 这是给蓝牙进程里那些跑在主线程的钩子用的（A2DP 状态回调、广播 onReceive、
     * adopted 定时任务）。真正的建链在 [connectBlocking] 里，被丢到 `FiilPods-Connect`
     * 后台线程执行，因此调用方立即返回，不会把蓝牙进程主线程卡住。
     *
     * 同一时刻只允许一次连接在跑（[connectInFlight]）：UI 每次刷新都会发
     * `REQUEST_STATE`，旧代码里那条路径会同步 `connect()`，一旦耳机不在身边
     * 每次刷新都阻塞十几秒，最终把蓝牙进程拖到 ANR。
     */
    fun connect(device: BluetoothDevice) {
        if (started.get().not()) {
            // 控制器还没 start（拿不到 Context）、没有 writer 线程时连上也发不出帧。
            // 直接丢弃，等 AdapterService.onCreate / onReceive 里的 start() 再来一次。
            Log.w(TAG, "connect ignored: controller not started")
            return
        }
        if (isConnected) return
        if (connectInFlight) {
            Log.i(TAG, "connect skipped: another attempt in flight")
            return
        }
        connectInFlight = true
        val thread = Thread({
            try {
                connectBlocking(device)
            } catch (t: Throwable) {
                Log.w(TAG, "async connect failed: ${t.message}")
            } finally {
                connectInFlight = false
            }
        }, "FiilPods-Connect").apply {
            isDaemon = true
            start()
        }
        connectThread = thread
    }

    /**
     * 同步建链，**会阻塞**（最长约 10 秒：三条 RFCOMM 通道逐条 `connect()`）。
     *
     * 只允许后台线程调用 —— 目前只有 [connect] 起的 `FiilPods-Connect` 线程
     * 和补连线程会进来。蓝牙进程主线程绝对不能直接调。
     *
     * 返回是否连上；失败不抛异常。
     */
    fun connectBlocking(device: BluetoothDevice): Boolean {
        synchronized(connectLock) {
            if (isConnected) disconnect("reconnect")
            return try {
            // 型号必须在开socket之前算出来：它决定先连哪条通道。
            // 型号来源按优先级：
            //   ① 手动指定（用户选过型号）
            //   ② 地址绑定（同一只耳机上次识别出的型号；名字被改也能认出来）
            //   ③ 蓝牙原始名推断
            //   ④ -1 未知，等回包纠正
            val manual = FiilConfig.readEarTypeOverride(appContext)
            val bound = FiilConfig.readEarTypeForAddress(appContext, device.address)
            val nameType = FiilModels.earTypeOfName(device.name)
            val guessEarType = when {
                manual > 0 -> manual
                bound > 0 -> bound
                else -> nameType
            }
            // Mate 机型（F045~F053）只认自定义 UUID 通道；
            // 标准 SPP 也能连上，但连上后耳机一声不响，看起来就像「已连接但没数据」。
            // 实测证据（Redmi K50 Ultra / Android 15 + FIIL Key Max）：
            //   19:59:05 标准 SPP scn:1 连上 → 之后所有查询零回包；
            //   20:01:37 自定义 UUID scn:6 连上 → setUnlock 后每帧都有应答。
            // 所以 Mate 与「型号未知」都先试自定义 UUID，老 GAIA 机型仍先试标准 SPP。
            // preferCustomNext 是上一条通道「连上但零回包」后留下的纠偏指令，优先级最高。
            val preferCustom = preferCustomNext
                ?: (guessEarType <= 0 || FiilModels.isMateDialect(guessEarType))
            // openSocket 内部已逐个 connect()，返回的就是连上的那个
            // 三条 RFCOMM 通道全失败时不再直接放弃，先退回 BLE GATT 再试一次 ——
            // FIIL Key Max 实测 SPP 三条都不稳定，只有 GATT 好用。
            val sock = try {
                openSocket(device, preferCustom)
            } catch (t: Throwable) {
                Log.w(TAG, "all rfcomm channels failed (${t.message}), falling back to BLE GATT")
                return connectViaGatt(device)
            }
            usingGatt = false
            socket = sock
            output = sock.outputStream
            deviceAddress = device.address
            // 显示名（通知 / 超级岛 / 融合设备中心）优先取系统蓝牙设置里的**别名**：
            // 用户在设置里改过名字后，getName() 仍是厂商原始名，getAlias() 才是用户看到的名字。
            // 目标第五阶段要求「名称优先读取系统蓝牙设置中的设备名，型号识别仍用原始信息」，
            // 所以下面 earType 的推断继续用 device.name。
            deviceName = preferredDisplayName(device)
            deviceOriginalName = runCatching { device.name }.getOrNull()
            // 型号已在 openSocket 之前算好（它决定先连哪条通道），这里只是落值。
            earType = guessEarType
            earTypeOverridden = manual > 0
            if (earType > 0 && manual <= 0) {
                // 名字推断出的型号也顺手落一份地址绑定，下次改名后仍能认回同一个型号。
                FiilConfig.writeEarTypeForAddress(appContext, device.address, earType)
            }
            isMateDialect = FiilModels.isMateDialect(earType)
            isConnected = true
            publishState()
            readerThread = Thread({ readLoop(sock) }, "FiilPods-Reader").apply {
                isDaemon = true
                start()
            }
            // 握手：先要版本，再要机型 —— 与 com/fiil/sdk/c/a.java:132-135 的顺序一致
            // Mate 机型（F045~F053）没有 earType 命令，握手序列由 FIILProtocol 判断
            FIILProtocol.handshake()
            // 连上不等于连对：Mate 机型在标准 SPP 上同样能 connect() 成功，但耳机一声不响。
            // 挂一个探测线程，若迟迟收不到任何字节就换通道重连。
            scheduleSilenceProbe(device.address)
            Log.i(TAG, "connected to ${FiilConfig.maskAddress(device.address)} mate=$isMateDialect custom=$connectedViaCustomUuid")
            RfcommLog.i(
                appContext, TAG,
                "connected via SPP addr=${FiilConfig.maskAddress(device.address)} earType=$earType " +
                    "mate=$isMateDialect custom=$connectedViaCustomUuid name=$deviceName",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed: ${t.message}")
            cleanupSocket()
            false
        }
        }
    }

    /**
     * 走 **BLE GATT** 建链（FIIL Key Max 等 Mate 机型实测唯一稳定的通道）。
     *
     * 与 [connectBlocking] 的关系：两条链路互斥。
     * - SPP 通道（标准 SPP / 自定义 UUID）能连上就先用，见 [openSocket]；
     * - 若 SPP 全部 `connect()` 失败，或连上后 [SILENCE_TIMEOUT_MS] 内零回包，
     *   就调用本方法改走 BLE。
     *
     * 本方法是**异步**的：先设好状态与回调，然后交给 [FiilGatt] 去连，
     * 连上/断开由 `FiilGatt.onStateChanged` 回调驱动。
     *
     * @return true 表示已发起（不代表已连上）
     */
    private fun connectViaGatt(device: BluetoothDevice): Boolean {
        val ctx = appContext ?: run {
            Log.w(TAG, "gatt connect aborted: no context")
            return false
        }
        // 先把自己从 SPP 那边摘干净，避免两条链路同时 ready 抢同一个状态机
        runCatching { readerThread?.interrupt() }
        runCatching { socket?.close() }
        socket = null
        output = null
        readerThread = null
        writeQueue.clear()

        FiilGatt.onStateChanged = { up ->
            if (up) {
                onGattReady(device)
            } else {
                onGattLost()
            }
        }
        FiilGatt.onFrame = { frame -> dispatchMate(frame) }
        FiilGatt.onBytes = {
            lastRxAt = System.currentTimeMillis()
            if (preferCustomNext != null) preferCustomNext = null
        }
        val launched = FiilGatt.connect(ctx, device)
        if (launched) {
            deviceAddress = device.address
            deviceName = preferredDisplayName(device)
            deviceOriginalName = runCatching { device.name }.getOrNull()
            val manual = FiilConfig.readEarTypeOverride(appContext)
            val bound = FiilConfig.readEarTypeForAddress(appContext, device.address)
            val nameType = FiilModels.earTypeOfName(device.name)
            earType = when {
                manual > 0 -> manual
                bound > 0 -> bound
                else -> nameType
            }
            earTypeOverridden = manual > 0
            Log.i(TAG, "gatt connect launched to ${FiilConfig.maskAddress(device.address)} earType=$earType")
        }
        return launched
    }

    /** BLE GATT 就绪：补上与 SPP 完全一样的握手与状态查询。 */
    private fun onGattReady(device: BluetoothDevice) {
        if (isConnected) return
        usingGatt = true
        connectedViaCustomUuid = true
        // ⚠️ 不能无条件置 true。
        //
        // F042 / CC Pro2 / CC nano（earType 39/40/42）**只有 BLE**，SPP 连不上，
        // 但它们走的仍是 **GAIA 帧**（见 FiilModels.BLE_ONLY 与 PROTOCOL_FIIL.md §1.1）。
        // 早期实现这里写死 `isMateDialect = true`，等于给这些机型发它们完全不认识的
        // Mate 帧 —— 表现是「连上了、电量有、其他全无响应」，比连不上更难查。
        //
        // 正确做法：型号已知就按型号判；型号未知（earType == -1，蓝牙名被改过 / 不在
        // NAME_RULES 里）才退回 true —— 因为 GATT 这条通道上 Mate 机型占多数，
        // 且 handshake() 在型号未知时本来就两套协议各打一发，会由首个回包自行定论。
        isMateDialect = if (earType > 0) FiilModels.isMateDialect(earType) else true
        isConnected = true
        Log.i(TAG, "gatt channel ready, handshaking (earType=$earType mate=$isMateDialect)")
        RfcommLog.i(
            appContext, TAG,
            "connected via BLE-GATT addr=${FiilConfig.maskAddress(device.address)} earType=$earType mate=$isMateDialect",
        )
        publishState()
        runCatching { FIILProtocol.handshake() }
            .onFailure { Log.w(TAG, "gatt handshake failed: ${it.message}") }
        // GATT 上耳机若一条都不回，说明这条通道也不对（个别机型 ff17/ff18 只读不写），
        // 挂同一个静默探测，让它回到 SPP 再试一次。
        scheduleSilenceProbe(device.address)
    }

    /** BLE GATT 掉了：清状态；非主动断开时让上层补连。 */
    private fun onGattLost() {
        if (!usingGatt) return
        usingGatt = false
        val addr = deviceAddress
        Log.i(TAG, "gatt channel lost (addr=$addr)")
        RfcommLog.w(appContext, TAG, "gatt channel lost addr=$addr")
        isConnected = false
        publishState()
        runCatching { silenceProbe?.interrupt() }
        silenceProbe = null
        if (addr != null && addr.isNotEmpty()) scheduleReconnect(addr, RECONNECT_ATTEMPTS)
    }

    fun disconnect(reason: String) {
        Log.i(TAG, "disconnect: $reason")
        // 探测线程只服务于「当前这一轮连接」，任何断开都要先让它退出，否则它会去重连一条已经关掉的连接。
        runCatching { silenceProbe?.interrupt() }
        silenceProbe = null
        val addr = deviceAddress
        val wasConnected = isConnected
        val manual = reason in MANUAL_DISCONNECT_REASONS
        // 畅连切换引起的断线：不是故障，走专用退避重连，且不计入普通补连。
        val multipointSwitch = reconnectAfterMultipoint
        if (manual) {
            // 主动断开要同时取消排队中的补连，否则用户刚点「断开」，
            // 5 秒后那个还在 sleep 的补连线程醒来又把连接拉回去。
            // 不能打断自己：补连线程内部若调用到 disconnect()，interrupt 会让
            // 它自己的 sleep 立刻抛 InterruptedException 而提前退出循环。
            val t = reconnectThread
            if (t != null && t !== Thread.currentThread()) runCatching { t.interrupt() }
            reconnectThread = null
            // 用户主动断开时，畅连后的自动重连也必须停 —— 否则刚说断开又自己连回来。
            if (reason == "user-request" || reason == "stop") cancelMultipointReconnect()
        }
        cleanupSocket()
        publishState()
        // 畅连切换：耳机固件换主从角色，等它重新可连后自动接上。
        if (multipointSwitch && !manual) {
            Log.i(TAG, "multipoint switch caused disconnection — scheduling dedicated reconnect")
            scheduleMultipointReconnect(addr.orEmpty())
            return
        }
        // 意外断线（socket 自己没了）自动补连；主动断开不重连。
        if (wasConnected && addr != null && !manual) {
            scheduleReconnect(addr, RECONNECT_ATTEMPTS)
        }
    }

    /**
     * 畅连切换后的专用重连：退避 [MULTIPOINT_SEAL_DELAYS_MS]，成功即重跑完整握手。
     *
     * 与普通 [scheduleReconnect] 的区别：
     *  · 重试窗口更长（约 30s vs 15s），因为固件换主从角色实测要 8~13 秒；
     *  · 期间会广播 `state="connecting"`，让 UI 显示「切换中」而不是「已断开」；
     *  · 成功后必须重跑 [FIILProtocol.handshake]（[connectBlocking] 内部会做），
     *    并把状态全量重发一次，否则界面上还是切换前的旧值。
     */
    private fun scheduleMultipointReconnect(address: String) {
        if (address.isEmpty()) return
        val running = multipointReconnectThread
        if (running != null && running.isAlive) return
        multipointReconnectThread = Thread({
            try {
                for ((index, delay) in MULTIPOINT_SEAL_DELAYS_MS.withIndex()) {
                    Thread.sleep(delay)
                    if (!started.get()) return@Thread
                    if (isConnected) return@Thread
                    Log.i(
                        TAG,
                        "multipoint reconnect ${index + 1}/${MULTIPOINT_SEAL_DELAYS_MS.size} -> $address",
                    )
                    publishSwitchingState()
                    val adapter = appContext
                        ?.getSystemService(android.bluetooth.BluetoothManager::class.java)
                        ?.adapter ?: return@Thread
                    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                        ?: return@Thread
                    if (runCatching { connectBlocking(device) }.getOrDefault(false)) {
                        reconnectAfterMultipoint = false
                        Log.i(TAG, "multipoint switch reconnect succeeded — refreshing state")
                        runCatching { FIILProtocol.mateQueries() }
                        publishState(force = true)
                        return@Thread
                    }
                }
                Log.w(TAG, "multipoint switch reconnect exhausted")
                reconnectAfterMultipoint = false
                broadcastOpFailed("蓝牙畅连切换后重连", -1)
            } catch (_: InterruptedException) {
                // 用户主动断开时被打断，正常退出
            }
        }, "FiilPods-MultipointReconnect").apply {
            isDaemon = true
            start()
        }
    }

    /** 取消畅连切换后的自动重连（用户主动断开时调用）。 */
    private fun cancelMultipointReconnect() {
        reconnectAfterMultipoint = false
        val t = multipointReconnectThread
        if (t != null && t !== Thread.currentThread()) runCatching { t.interrupt() }
        multipointReconnectThread = null
    }

    /**
     * 标记「接下来这次断开是畅连切换引起的」。
     *
     * **必须在 `sendMate` 之前调用** —— 断线回调可能比任何事后设置都早。
     */
    fun expectMultipointSwitch() {
        reconnectAfterMultipoint = true
    }

    /** 畅连切换中的状态广播：让 UI 显示「切换中」而不是「已断开」。 */
    private fun publishSwitchingState() {
        val ctx = appContext ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(FiilAction.ACTION_STATE)
                    .setPackage(FiilConfig.MODULE_PACKAGE)
                    .putExtra(FiilAction.EXTRA_JSON, snapshotJson())
            )
        }
        runCatching { OppoCompatBridge.publishSwitching(ctx, force = false) }
    }

    /**
     * 后台补连。每 5s 试一次，最多 [attempts] 次。
     *
     * 放在独立线程而不是 Handler 上，是因为 `connectBlocking()` 会阻塞在 `socket.connect()`，
     * 而调用方可能正是蓝牙进程里的 A2DP handler 线程 —— 不能把它卡住。
     *
     * 用 while 循环而不是「失败后递归调用自己」：递归时本线程仍然 alive，
     * 入口处的 `reconnectThread?.isAlive` 守卫会直接把续期请求丢掉，只重试一次。
     */
    private fun scheduleReconnect(address: String, attempts: Int) {
        if (attempts <= 0) return
        if (reconnectThread?.isAlive == true) return
        reconnectThread = Thread({
            var left = attempts
            while (left > 0 && started.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (isConnected) return@Thread
                val adapter = appContext
                    ?.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter ?: return@Thread
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                    ?: return@Thread
                Log.i(TAG, "reconnect attempt ($left left) -> $address")
                if (runCatching { connectBlocking(device) }.getOrDefault(false)) return@Thread
                left--
            }
        }, "FiilPods-Reconnect").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 依次尝试三种 socket，返回第一个**真正连上**的。
     *
     * 这里必须把 `connect()` 放进循环 —— `createRfcommSocketToServiceRecord()`
     * 只是按某个 UUID 建一个 socket 对象，**不做 SDP 查询也不报错**；
     * 真正的 SDP 查找和 RFCOMM 建链发生在 `connect()`。
     * 所以「创建成功」不等于「这条路通」：
     *  - 老机型只有标准 SPP（00001101），自定义 UUID 会连接失败；
     *  - Mate 机型（F045~F053）**只有**自定义 UUID（真机 FIIL Key Max 的
     *    SDP 记录里 services=1，就是 d52b47bc-…），标准 SPP 会连接失败。
     * 旧实现「创建成功就 return、连接失败就整体失败」等于把 Mate 机型永久挡在门外。
     */
    private fun openSocket(device: BluetoothDevice, customFirst: Boolean): BluetoothSocket {
        val candidates = ArrayList<Pair<BluetoothSocket, Boolean>>(3)
        // 顺序由调用方按机型决定，见 connectBlocking 里的 preferCustom 注释。
        // 标准 SPP 与自定义 UUID 都要建出来做候选，只是「谁先 connect」不同。
        val standard = {
            runCatching { device.createRfcommSocketToServiceRecord(UUID_SPP) }
                .onSuccess { candidates.add(it to false) }
        }
        val custom = {
            runCatching { device.createRfcommSocketToServiceRecord(UUID_CC_PRO2) }
                .onSuccess { candidates.add(it to true) }
        }
        if (customFirst) {
            custom()
            standard()
        } else {
            standard()
            custom()
        }
        runCatching {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            candidates.add((m.invoke(device, 1) as BluetoothSocket) to false)
        }
        if (candidates.isEmpty()) error("no rfcomm socket available")

        var last: Throwable? = null
        for ((sock, custom) in candidates) {
            try {
                sock.connect()
                connectedViaCustomUuid = custom
                return sock
            } catch (t: Throwable) {
                last = t
                runCatching { sock.close() }
            }
        }
        throw last ?: IllegalStateException("all rfcomm channels failed")
    }

    private fun cleanupSocket() {
        // BLE 通道也要一起收掉：SPP 与 GATT 是互斥的两条链路，
        // 任何一侧触发清理都必须把另一侧关掉，否则会留下一个「看着已连接、其实收不到数据」的影子。
        //
        // 顺序很重要：必须先清 usingGatt 再 disconnect，因为 FiilGatt.disconnect() 会同步回调
        // onStateChanged(false) → onGattLost()，那里靠 usingGatt 判断是否要补连；
        // 反过来的话每次主动断开都会触发一次多余的重连。
        usingGatt = false
        runCatching { FiilGatt.disconnect() }
        isConnected = false
        runCatching { readerThread?.interrupt() }
        runCatching { socket?.close() }
        socket = null
        output = null
        readerThread = null
        writeQueue.clear()
        earType = -1
        ancMode = -1
        batteryLeft = -1
        batteryRight = -1
        batteryCase = -1
        wearLeft = false
        wearRight = false
        eqIndex = -1
        is3D = false
        lowLatencyLevel = 0
        cinemaMode = false
        multipoint = false
        eqBands = IntArray(0)
        wearDetect = false
        noiseAdaptive = false
        windAdaptive = false
        earAdaptive = false
        eqAdaptive = false
        tuning = false
        isMateDialect = false
        connectedViaCustomUuid = false
        // 断线后所有未确认的操作都作废：不能再因为它们超时而弹「未生效」，
        // 用户看到的是连接已断开，不该再叠一条操作失败提示。
        pendingOps.clear()
        pendingTargets.clear()
        lastRxAt = 0L
    }

    /**
     * 连上后盯一段时间，若一个字节都没收到就判定「连错了通道」，换通道重连一次。
     *
     * 为什么需要它：Mate 机型（F045~F053）的数据只在自定义 UUID 的 RFCOMM 通道上，
     * 但它对标准 SPP 的 SDP 也有响应，`connect()` 同样返回成功 —— 于是会出现
     * 「状态栏显示已连接、模块却收不到电量/降噪/EQ」这种最难排查的形态。
     * 实测 FIIL Key Max 在标准 SPP 上就是完全静默（scn:1 连上后零字节）。
     *
     * 判定窗口取 [SILENCE_TIMEOUT_MS]：正常握手 200ms 内就该有第一帧回包，
     * 留 3.5s 余量足够覆盖蓝牙重传；超过还没数据只能是通道不对。
     */
    private fun scheduleSilenceProbe(address: String) {
        runCatching { silenceProbe?.interrupt() }
        lastRxAt = 0L
        val probe = Thread({
            try {
                Thread.sleep(SILENCE_TIMEOUT_MS)
                val rx = lastRxAt
                if (!isConnected) return@Thread
                if (rx != 0L) return@Thread
                // 一个字节都没收到：把当前通道标记为「不通」，下次 openSocket 换另一条。
                val wasCustom = connectedViaCustomUuid
                Log.w(
                    TAG,
                    "no data in ${SILENCE_TIMEOUT_MS}ms on " +
                        (if (wasCustom) "custom-uuid" else "standard-spp") + " channel, switching",
                )
                val wasGatt = usingGatt
                disconnect("silent-channel")
                // SPP 静默 → 换 BLE GATT 再试；GATT 也静默 → 回 SPP 翻转 UUID 顺序。
                if (!wasGatt) {
                    val dev = runCatching {
                        (appContext?.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                            as? android.bluetooth.BluetoothManager)?.adapter?.getRemoteDevice(address)
                    }.getOrNull()
                    if (dev != null && connectViaGatt(dev)) {
                        preferCustomNext = null
                        return@Thread
                    }
                }
                // 记录本次通道失败，让重连时的候选顺序翻转。
                preferCustomNext = !wasCustom
                if (address.isNotEmpty()) scheduleReconnect(address, 2)
            } catch (_: InterruptedException) {
                // disconnect() 主动打断，正常退出
            }
        }, "FiilPods-SilenceProbe").apply { isDaemon = true }
        silenceProbe = probe
        probe.start()
    }

    // ------------------------------------------------------------------
    // 收
    // ------------------------------------------------------------------

    private fun readLoop(sock: BluetoothSocket) {
        val input: InputStream = try {
            sock.inputStream
        } catch (t: Throwable) {
            Log.w(TAG, "open input failed: ${t.message}")
            disconnect("input-failed")
            return
        }
        val buf = ByteArray(MAX_FRAME * 2)
        var carried = 0
        while (started.get() && isConnected && socket === sock) {
            val n = try {
                input.read(buf, carried, buf.size - carried)
            } catch (e: IOException) {
                if (isConnected) Log.i(TAG, "read closed: ${e.message}")
                break
            }
            if (n <= 0) continue
            // 记下「这条通道真的收到东西了」，静默探测据此判断是否连错了通道。
            lastRxAt = System.currentTimeMillis()
            // 收到数据就说明通道选对了，把之前的纠偏标记清掉，避免下次连接被它带偏。
            if (preferCustomNext != null) preferCustomNext = null
            val total = carried + n
            // 原始字节全量落到模块日志 —— 排查「发了没回 / 回了没解析」时，这一行最关键：
            // 有 TX 无 RX 说明耳机没应答（协议族或通道不对）；有 RX 但界面没变说明解析/分发错了。
            runCatching {
                RfcommLog.d(
                    appContext, "RFCOMM/RX",
                    RfcommLog.hex(buf, total).let { if (total > 64) "$it (total=$total)" else it },
                )
            }

            // 先判断协议族：**不能用首字节**——Mate 帧首字节是 seq 滚动计数器
            // （0x00~0xFF 都可能），只有 GAIA 帧首字节才是固定的 0xFF。
            // 判据改为「cid 非 0 + type 合法 + reserved 为 0」，见 FiilMate.looksLikeMate()。
            // 只在还没确定时判一次，避免把中途的错误数据当成切换信号。
            if (earType == -1 && !isMateDialect && total >= FiilMate.HEADER_SIZE) {
                if (FiilMate.looksLikeMate(buf, total)) {
                    isMateDialect = true
                    Log.i(TAG, "detected Mate dialect (custom=${connectedViaCustomUuid})")
                    // 型号未知时握手阶段是「两套协议各打一发」，
                    // 那批 Mate 状态查询还没发过，这里补上，否则连上了也没有电量/MAF/EQ。
                    runCatching { FIILProtocol.mateQueries() }
                        .onFailure { Log.w(TAG, "mate catch-up queries failed: ${it.message}") }
                }
            }

            carried = if (isMateDialect) {
                val (frames, remain) = FiilMate.drain(buf, total)
                for (f in frames) dispatchMate(f)
                if (remain >= buf.size) 0 else remain
            } else {
                val (frames, remain) = FiilGaia.drain(buf, total)
                for (f in frames) dispatch(f)
                if (remain >= buf.size) 0 else remain
            }
        }
        if (socket === sock) disconnect("read-loop-exit")
    }

    // ------------------------------------------------------------------
    // Mate 帧分派（F045~F053，第三套命令空间）
    // ------------------------------------------------------------------

    private fun dispatchMate(frame: FiilMate.Frame) {
        rxFrameCount++
        Log.v(
            TAG,
            "rx-mate cid=%d cmd=%d len=%d".format(frame.classifyId, frame.commandId, frame.payload.size),
        )
        when (frame.classifyId) {
            // 耳机主动上报（cid=40）。只收不发：全库 setCommandClassifyId 从不传 40，
            // 而各机型 MainFragment 的 notify 分支全部判 40（详见 FiilMate.Cid.NOTIFY）。
            // 这条通道承载的就是「用户在耳机上按键切 ANC」「摘下耳机」「电量变化」等
            // 手机没主动查询也要知道的事件 —— 不处理它，模块状态会和耳机实际状态脱节。
            FiilMate.Cid.NOTIFY -> applyMateNotify(frame)
            FiilMate.Cid.QUERY -> when (frame.commandId) {
                // 电量：回包 buff[1] 就是百分比（F046HomeFragment.setElectricity → m(buff[1])）
                FiilMate.Query.BATTERY -> applyMateBattery(frame)
                // EQ 状态：`[0]`=保留位 `[1]`=当前预设类型 `[2..n]`=各段增益。
                //
                // 依据（一手源码，`...\jadx_out\sources\com\fengeek\main\f046\fragment\F046StyleFragment.java`）：
                //  · 读取 `onRead(byte[] buff)` @L153/L219：`buff.length >= 12` 才收，`f29301i = buff`，
                //    再把 `list = buff[1..]` 交给 `setEq(list)`；
                //  · `setEq(List<Byte> list)` @L1031：`m(list.get(0).byteValue(), list)` —— 取的是 `buff[1]`；
                //  · `m(int value, List<Byte> customeEq)` @L708：档位判定用 `value`（即 `buff[1]`），
                //    增益循环 `customeEq.get(i2)` 从 i2=1 起 → `buff[2]` 起；
                //  · 写入 `s0()` @L899-901：`f29301i[1] = 15`、`f29301i[i2 + 2] = 滑块值`，
                //    然后整块 12 字节发出去 —— 证明 `[0]` 不参与档位判定。
                // 所以 payload 布局是 `[保留][类型][N 段增益]`，共 12 字节（10 段）。
                // F045 是 5 段（读校验 >= 7）、F049 是 8 段（读校验 >= 7，较宽松），
                // 因此段数按实际长度算：count = payload.size - 2。
                //
                // 线值是预设编号 3~14（`n()` @L765 构造 `i2 + 3`，15 = 自定义，见 s0() @L899），
                // UI 用的是 0 起的列表下标，所以要减 3；自定义统一归到 -1（无选中项）。
                FiilMate.Query.EQ_STATUS -> {
                    if (frame.payload.size >= 3) {
                        val type = frame.byteAt(1, -1)
                        eqIndex = when {
                            type in 3..14 -> type - 3
                            type == FiilModels.MATE_EQ_CUSTOM -> -1
                            else -> type
                        }
                        val count = frame.payload.size - 2
                        eqBands = IntArray(count) { frame.byteAt(it + 2, 128) }
                        ackOp(KEY_EQ)
                        publishState()
                    }
                }
                FiilMate.Query.LOW_LATENCY -> {
                    // Mate 侧只有布尔语义（setLowDelay(boolean)），回包 1 即游戏档
                    lowLatencyLevel = if (frame.byteAt(0, 0) == 1) 1 else 0
                    ackOp(KEY_LOW_LATENCY)
                    publishState()
                }
                FiilMate.Query.MAF_STATUS -> {
                    val v = frame.byteAt(0, -1)
                    if (v in 0..4) {
                        ancMode = v
                        ackOp(KEY_ANC)
                        publishState()
                    }
                }
                FiilMate.Query.SPATIAL_AUDIO -> {
                    cinemaMode = frame.byteAt(0, 0) == 1
                    ackOp(KEY_CINEMA)
                    publishState()
                }
                FiilMate.Query.MULTIPOINT -> {
                    multipoint = frame.byteAt(0, 0) == 1
                    ackOp(KEY_MULTIPOINT)
                    publishState()
                }
                // 语音提示音量：回包实测 `01 27 02 00 03 82 01 02`，逐字节拆开是
                // `seq=01 | cid=27 | type=02 | rsv=00 | lenField=03 | cmd=82 | payloadLen=01 | payload=02`
                // —— 长度字段 0x03 减 2 = payloadLen 1，**payload 只有 1 字节**，音量就在 payload[0]。
                // （早前注释把 payloadLen 误读成「字段存在标志」，据此写了「音量在 payload[1]」，
                //   靠 `else byteAt(0)` 的容错才侥幸读对。这里改回真实偏移。）
                FiilMate.Query.TONE -> {
                    val v = frame.byteAt(0, -1)
                    if (v in 0..3) {
                        toneVolume = v
                        ackOp(KEY_TONE)
                        publishState()
                    }
                }
                FiilMate.Query.VERSION -> Log.i(TAG, "mate fw version len=${frame.payload.size}")
                // 佩戴检测状态：`00 27 01 00 01 09 <0/1>` —— g.java:267 getWear()
                // 回包 payload[0]==1 表示已开启。仅 F051/F053 有这条查询。
                // 注意与 NOTIFY cmd=9（佩戴**状态**：摘下/戴上）是两回事：
                // 这里问的是「摘戴检测功能开没开」，通知说的是「当前戴没戴」。
                FiilMate.Query.WEAR -> {
                    if (frame.payload.isNotEmpty()) {
                        wearDetect = frame.byteAt(0, 0) == 1
                        publishState()
                    }
                }
                // ---- 自适应开关状态回读（cid=39）----
                // 五个 getter 在 f/g/h.java 里都是 `setCommandClassifyId(39)` +
                // `setCommandId(NetError.ERR_*)`，把 NetError 常量换算成无符号字节：
                //   getTuning         → ERR_SOCKS_CONNECTION_HOST_UNREACHABLE  = -121 → 135
                //   getEQAdaptive     → ERR_HOST_RESOLVER_QUEUE_TOO_LARGE      = -119 → 137
                //   getEarAdaptive    → ERR_CONNECTION_TIMED_OUT               = -118 → 138
                //   getNoiseAdaptive  → ERR_BAD_SSL_CLIENT_AUTH_CERT           = -117 → 139
                //   getWindAdaptive   → ERR_CERT_ERROR_IN_SSL_RENEGOTIATION    = -116 → 140
                // 回包 payload[0]==1 为开（与 setXxx 的 1/0 线值一致）。
                FiilMate.Query.TUNING_STATUS -> applyAdaptiveFlag(frame, AdaptiveKind.TUNING)
                FiilMate.Query.EQ_ADAPTIVE -> applyAdaptiveFlag(frame, AdaptiveKind.EQ)
                FiilMate.Query.EAR_ADAPTIVE -> applyAdaptiveFlag(frame, AdaptiveKind.EAR)
                FiilMate.Query.NOISE_ADAPTIVE -> applyAdaptiveFlag(frame, AdaptiveKind.NOISE)
                FiilMate.Query.WIND_ADAPTIVE -> applyAdaptiveFlag(frame, AdaptiveKind.WIND)
                else -> Unit
            }
            // cid=1 是「通用设置组」，真正的动作由 commandId 区分。
            // 绝不能只看 cid 就当作多点回包：自适应开关的写命令也走 cid=1
            // （cmdId 8/14/15/16/17），否则一次「开自适应降噪」的 ACK
            // 会被误当成多点回显，把 multipoint 翻过去。
            FiilMate.Cid.SET_PARAM -> applySetParam(frame)
            // 其余设置类回显（0 字节 payload 表示成功）。
            // 与 cid=1 同理：这些回显**不带新状态**，不能用取反推定
            // （连点两次会与真实状态错位，回显丢了再发一次就翻反）。
            // 一律取 [pendingTargets] 里发送时记下的目标值。
            FiilMate.Cid.SET_MAF,
            FiilMate.Cid.SET_EQ,
            FiilMate.Cid.SET_LOW_LATENCY,
            FiilMate.Cid.SET_SPATIAL_AUDIO,
            FiilMate.Cid.SET_WEAR -> {
                val ok = frame.payload.isEmpty() || frame.byteAt(0, 0) == 0
                Log.i(TAG, "mate set cid=${frame.classifyId} ok=$ok")
                if (ok) {
                    when (frame.classifyId) {
                        FiilMate.Cid.SET_SPATIAL_AUDIO -> {
                            cinemaMode = pendingTargets.remove(KEY_CINEMA) ?: !cinemaMode
                            ackOp(KEY_CINEMA)
                        }
                        FiilMate.Cid.SET_LOW_LATENCY -> {
                            val target = pendingTargets.remove(KEY_LOW_LATENCY)
                            lowLatencyLevel = if (target != null) {
                                if (target) 1 else 0
                            } else {
                                if (lowLatencyLevel == 0) 1 else 0
                            }
                            ackOp(KEY_LOW_LATENCY)
                        }
                        FiilMate.Cid.SET_WEAR -> {
                            wearDetect = pendingTargets.remove(KEY_WEAR) ?: !wearDetect
                            ackOp(KEY_WEAR)
                        }
                        FiilMate.Cid.SET_MAF -> ackOp(KEY_ANC)
                        FiilMate.Cid.SET_EQ -> ackOp(KEY_EQ)
                    }
                    publishState()
                }
            }
            else -> Unit
        }
    }

    /**
     * 处理 cid=1（[FiilMate.Cid.SET_PARAM]）通用设置组的回包。
     *
     * 这一组下挂多个开关，只能靠 commandId 分辨：
     *  · cmdId=1  畅连（多点）
     *  · cmdId=8  云感调音
     *  · cmdId=14 自适应 EQ
     *  · cmdId=15 耳道自适应降噪
     *  · cmdId=16 环境自适应降噪
     *  · cmdId=17 自适应抗风噪
     *
     * **这些帧全部是「我们刚发的 SET 的回显」，不是状态回读**——
     * 状态回读走的是 cid=39（见 [dispatchMate] 的 QUERY 分支）。
     * 所以不能用 `payload[0]` 当新状态：关操作的回显也是 `[0]`，
     * 若按值解释，会把刚打开的开关错记成关闭。
     * 这里一律取发送时记下的目标值（[setAdaptiveSwitch] 写入）。
     */
    private fun applySetParam(frame: FiilMate.Frame) {
        val ok = frame.payload.isEmpty() || frame.byteAt(0, 0) == 0
        if (!ok) {
            Log.i(TAG, "cid=1 cmd=${frame.commandId} echo not ok: ${frame.payload.size}B")
            return
        }
        when (frame.commandId) {
            FiilMate.Param.MULTIPOINT -> {
                multipoint = pendingTargets.remove(KEY_MULTIPOINT) ?: !multipoint
                ackOp(KEY_MULTIPOINT)
                publishState()
            }
            // 语音提示音量：cid=1 的 SET 回显不带新值，取发送时记下的目标值。
            FiilMate.Param.TONE -> {
                val target = pendingTargetsInt.remove(KEY_TONE)
                if (target != null || toneVolume < 0) {
                    toneVolume = target ?: toneVolume
                    ackOp(KEY_TONE)
                    publishState()
                }
            }
            FiilMate.Param.TUNING -> applyAdaptiveEcho(AdaptiveKind.TUNING)
            FiilMate.Param.EQ_ADAPTIVE -> applyAdaptiveEcho(AdaptiveKind.EQ)
            FiilMate.Param.EAR_ADAPTIVE -> applyAdaptiveEcho(AdaptiveKind.EAR)
            FiilMate.Param.NOISE_ADAPTIVE -> applyAdaptiveEcho(AdaptiveKind.NOISE)
            FiilMate.Param.WIND_ADAPTIVE -> applyAdaptiveEcho(AdaptiveKind.WIND)
            else -> Log.i(TAG, "cid=1 cmd=${frame.commandId} unknown")
        }
    }

    /** cid=1 设置回显：应用发送时记下的目标值，没有记录才退化为取反。 */
    private fun applyAdaptiveEcho(kind: AdaptiveKind) {
        val target = pendingTargets.remove(kind.ackKey)
        setAdaptive(kind, target ?: !adaptiveValue(kind))
        ackOp(kind.ackKey)
        publishState()
    }

    /**
     * 处理 cid=40（[FiilMate.Cid.NOTIFY]）的耳机主动上报。
     *
     * 与 [FiilMate.Cid.QUERY] 的区别：QUERY 是「我们问了，耳机答」，
     * NOTIFY 是「我们没问，耳机主动说」。命令号同号不同义，所以必须分开写。
     * 依据：各机型 `*MainFragment` 的 notify 分支（F046:299/303/308/317、F051:300~343）。
     */
    private fun applyMateNotify(frame: FiilMate.Frame) {
        when (frame.commandId) {
            // 电量，payload[1] 是百分比（F046HomeFragment:299 → setElectricity(buff) → m(buff[1])）
            FiilMate.Notify.BATTERY -> applyMateBattery(frame)

            // 播放状态，payload[0]（F046MainFragment:303 → playState(buff[0])）
            FiilMate.Notify.PLAY_STATE -> Log.i(TAG, "mate notify play=${frame.byteAt(0, -1)}")

            // 低延迟，payload[0]==1（F046MainFragment:308 → setDeferState）
            FiilMate.Notify.LOW_LATENCY -> {
                if (frame.payload.isNotEmpty()) {
                    lowLatencyLevel = if (frame.byteAt(0, 0) == 1) 1 else 0
                    ackOp(KEY_LOW_LATENCY)
                    publishState()
                }
            }

            // 佩戴，payload[0]==1（F051MainFragment:316 → setWearState(buff[0]==1)）
            // Mate 机型只有单值，不像 GAIA 那样能左右分开，所以两侧一起置位。
            FiilMate.Notify.WEAR -> {
                if (frame.payload.isNotEmpty()) {
                    val wearing = frame.byteAt(0, 0) == 1
                    wearLeft = wearing
                    wearRight = wearing
                    publishState()
                }
            }

            // 降噪模式，payload[0]（F046MainFragment:317 / F051MainFragment:343 → setMafModeUI）
            //
            // F051/F053 原代码在这里做了 6→4、5→3 的映射（:344-349）：
            // 说明新机型在 MAF 五态之外还有 5/6 两个档位，需归一化到本模块的 0~4。
            FiilMate.Notify.MAF -> {
                if (frame.payload.isNotEmpty()) {
                    val raw = frame.byteAt(0, -1)
                    val v = when (raw) {
                        // 与 FIIL+ 自己的归一化完全一致（F051MainFragment.java:344-349、
                        // F049/F053MainFragment.java:344-349 三处同构）：
                        // 6 → 4（风中）、5 → 3（兼听）。
                        6 -> FiilMate.Maf.WIND
                        5 -> FiilMate.Maf.LISTEN
                        else -> raw
                    }
                    if (v in 0..4) {
                        ancMode = v
                        ackOp(KEY_ANC)
                        publishState()
                    }
                }
            }

            // 畅连（多点）设备列表 / 连接结果：只服务于 FIIL+ 自己的多连接页面，
            // 本模块没有对应 UI，仅记录日志便于比对。
            FiilMate.Notify.MULTIPOINT_LIST,
            FiilMate.Notify.MULTIPOINT_RESULT ->
                Log.i(TAG, "mate notify multipoint cmd=${frame.commandId} len=${frame.payload.size}")

            else -> Log.v(TAG, "mate notify unhandled cmd=${frame.commandId}")
        }
    }

    /**
     * 应用一条自适应开关的**状态回读**（cid=39）。
     *
     * 回包 payload[0]==1 为开。五个开关的线值一致（`setXxx` 也是 1/0），
     * 但命令号完全不同，所以只能靠调用方传入 [kind] 区分。
     *
     * 注意与 [applyAdaptiveEcho] 的分工：这里是「问了才答」的真实状态，
     * 可以直接用 payload；那边是「SET 的回显」，不带新值，只能用目标值。
     */
    private fun applyAdaptiveFlag(frame: FiilMate.Frame, kind: AdaptiveKind) {
        if (frame.payload.isEmpty()) {
            // 查询回了空包：协议异常，不改状态，但撤销等待避免误报失败。
            Log.w(TAG, "adaptive query ${kind.name} returned empty payload")
            ackOp(kind.ackKey)
            return
        }
        setAdaptive(kind, frame.byteAt(0, 0) == 1)
        ackOp(kind.ackKey)
        publishState()
    }

    private fun adaptiveValue(kind: AdaptiveKind): Boolean = when (kind) {
        AdaptiveKind.NOISE -> noiseAdaptive
        AdaptiveKind.WIND -> windAdaptive
        AdaptiveKind.EAR -> earAdaptive
        AdaptiveKind.EQ -> eqAdaptive
        AdaptiveKind.TUNING -> tuning
    }

    private fun setAdaptive(kind: AdaptiveKind, value: Boolean) {
        when (kind) {
            AdaptiveKind.NOISE -> noiseAdaptive = value
            AdaptiveKind.WIND -> windAdaptive = value
            AdaptiveKind.EAR -> earAdaptive = value
            AdaptiveKind.EQ -> eqAdaptive = value
            AdaptiveKind.TUNING -> tuning = value
        }
    }

    /**
     * 供 [FIILProtocol] 调用的自适应开关入口。
     *
     * @param kind  开关种类
     * @param on    目标状态
     */
    fun setAdaptiveSwitch(kind: AdaptiveKind, on: Boolean) {
        if (!isConnected) {
            Log.w(TAG, "setAdaptiveSwitch(${kind.name}) ignored: not connected")
            return
        }
        if (!isMateDialect) {
            // 这套开关只存在于 Mate 命令空间（cid=1 组），GAIA 机型没有对应命令。
            Log.w(TAG, "setAdaptiveSwitch(${kind.name}) ignored: not a Mate device")
            return
        }
        val current = adaptiveValue(kind)
        // 已经是目标值就不发了 —— 耳机不会回包，发了会白等 2.5s 再误报失败。
        if (current == on) {
            Log.i(TAG, "setAdaptiveSwitch(${kind.name}=$on) skipped: already in that state")
            return
        }
        expectAckIfChanged(kind.ackKey, kind.label, if (on) 1 else 0) {
            if (adaptiveValue(kind)) 1 else 0
        }
        val (cid, cmd) = when (kind) {
            AdaptiveKind.NOISE -> FiilMate.Cid.SET_PARAM to FiilMate.Param.NOISE_ADAPTIVE
            AdaptiveKind.WIND -> FiilMate.Cid.SET_PARAM to FiilMate.Param.WIND_ADAPTIVE
            AdaptiveKind.EAR -> FiilMate.Cid.SET_PARAM to FiilMate.Param.EAR_ADAPTIVE
            AdaptiveKind.EQ -> FiilMate.Cid.SET_PARAM to FiilMate.Param.EQ_ADAPTIVE
            AdaptiveKind.TUNING -> FiilMate.Cid.SET_PARAM to FiilMate.Param.TUNING
        }
        pendingTargets[kind.ackKey] = on
        sendMate(cid, cmd, byteArrayOf(if (on) 1 else 0))
    }

    /** [applyAdaptiveFlag] 的下标映射：[kind] → (cid=39 下的查询命令号)。 */
    internal fun adaptiveQueryCommand(kind: AdaptiveKind): Int = when (kind) {
        AdaptiveKind.NOISE -> FiilMate.Query.NOISE_ADAPTIVE
        AdaptiveKind.WIND -> FiilMate.Query.WIND_ADAPTIVE
        AdaptiveKind.EAR -> FiilMate.Query.EAR_ADAPTIVE
        AdaptiveKind.EQ -> FiilMate.Query.EQ_ADAPTIVE
        AdaptiveKind.TUNING -> FiilMate.Query.TUNING_STATUS
    }

    private fun applyMateBattery(frame: FiilMate.Frame) {
        val v = frame.byteAt(1, -1).let { if (it == 255) -1 else it }
        if (v >= 0) {
            batteryLeft = v
            batteryRight = v
            publishState()
        }
    }

    private fun dispatch(frame: FiilGaia.Frame) {
        Log.v(TAG, "rx cmd=0x%04X ack=%s len=%d".format(frame.commandRaw, frame.isAck, frame.payload.size))
        when (frame.command) {
            FiilCommands.GET_EARTYPE -> {
                val v = FiilGaia.byteAt(frame, 1, -1)
                // 手动指定优先：用户既然选了型号，就不要被回包改回去。
                if (v > 0 && !earTypeOverridden) {
                    earType = v
                    // 这是耳机自己报的型号，最可信 —— 顺手建立「地址 → 型号」绑定，
                    // 下次即使用户把蓝牙名改掉，也能靠地址认出同一只耳机（目标第五阶段）。
                    FiilConfig.writeEarTypeForAddress(appContext, deviceAddress, v)
                }
                publishState()
            }
            FiilCommands.GET_ANC,
            FiilCommands.GET_MAF,
            FiilCommands.SET_ANC,
            FiilCommands.SET_MAF,
            FiilCommands.SET_MAF_V2,
            FiilCommands.GET_MAF_V2 -> handleAnc(frame)
            FiilCommands.GET_BATTERY_LEVEL, FiilCommands.GET_BATTERY_LEVEL_V2 ->
                FiilGaia.parseBatteryLevel(frame)?.let {
                    // 老协议回包里 payload[1] 是左右耳的打包位，这里先按单值处理
                    if (batteryLeft < 0) batteryLeft = it
                    batteryRight = it
                    publishState()
                }
            FiilCommands.GET_BATTERY_OF_BOX ->
                FiilGaia.parseBoxBatteryLevel(frame)?.let {
                    batteryCase = it
                    publishState()
                }
            FiilCommands.GET_WEAR, FiilCommands.GET_WEARING_STATE ->
                FiilGaia.parseWear(frame)?.let { (l, r) ->
                    wearLeft = l
                    wearRight = r
                    publishState()
                }
            FiilCommands.GET_EQ, FiilCommands.GET_EQ_V2, FiilCommands.SET_EQ, FiilCommands.SET_EQ_V2 -> {
                val v = FiilGaia.byteAt(frame, 1, -1)
                if (v >= 0) eqIndex = v
                ackOp(KEY_EQ)
                publishState()
            }
            FiilCommands.GET_3D, FiilCommands.SET_3D -> {
                is3D = FiilGaia.byteAt(frame, 1, 0) != 0
                ackOp(KEY_3D)
                publishState()
            }
            FiilCommands.GET_LOW_LATENCY, FiilCommands.SET_LOW_LATENCY -> {
                // GAIA 三档：payload[1] 就是 0/1/2，与 setLowLatency(int) 对称
                lowLatencyLevel = FiilGaia.byteAt(frame, 1, 0).coerceIn(0, 2)
                ackOp(KEY_LOW_LATENCY)
                publishState()
            }
            FiilCommands.GET_VERSION -> Log.i(TAG, "fw version reply len=${frame.payload.size}")
            else -> Unit
        }
    }

    private fun handleAnc(frame: FiilGaia.Frame) {
        val v = FiilGaia.byteAt(frame, 1, -1)
        if (v in 0..4) {
            ancMode = v
            ackOp(KEY_ANC)
            publishState()
        }
    }

    // ------------------------------------------------------------------
    // 发
    // ------------------------------------------------------------------

    private fun enqueue(bytes: ByteArray) {
        if (!isConnected) return
        // 队列满时丢弃最旧的一条，避免阻塞调用方
        if (!writeQueue.offer(bytes)) {
            writeQueue.poll()
            writeQueue.offer(bytes)
        }
    }

    private fun writerLoop() {
        while (started.get()) {
            val bytes = try {
                writeQueue.take()
            } catch (e: InterruptedException) {
                break
            }
            val out = output ?: continue
            synchronized(writeLock) {
                try {
                    out.write(bytes)
                    out.flush()
                    Thread.sleep(WRITE_GAP_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "write failed: ${t.message}")
                    disconnect("write-failed")
                }
            }
        }
    }

    /**
     * 发一帧 GAIA 数据（老机型 / F042 系列）。
     *
     * ⚠️ v2 修复：旧实现**没有 GATT 分支**，`usingGatt` 时照样 `enqueue()`，
     * 而 `writerLoop` 里 `output == null` 会 `continue` —— 帧被静默吞掉，
     * 调用方只会看到「点了没反应」+ 2.5s 后超时提示。
     *
     * 两类机型都会走到这里：
     *  - F042 / CC Pro2 / CC nano（earType 39/40/42）**只有 BLE**，SPP 根本连不上，
     *    必然走 GATT，而它们的帧格式仍是 GAIA —— 必须从 ff17 发出去；
     *  - 任何机型在 RFCOMM 三路全失败后降级到 GATT 时，同理。
     *
     * 所以这里与 [sendMate] 对齐：GATT 模式直接写 ff17。
     * 至于 Mate 机型**故意**不该发的 GAIA 帧，由调用方（[setAnc]/[set3D]）按
     * 协议族分流，不在这里静默丢弃 —— 静默丢帧是最难查的一类缺陷。
     */
    fun send(cmd: Int, payload: ByteArray = ByteArray(0), newProtocol: Boolean = true) {
        val frame = FiilGaia.buildFrame(cmd, newProtocol, payload)
        Log.v(TAG, "tx cmd=0x%04X %s".format(cmd, frame.joinToString(" ") { "%02X".format(it) }))
        RfcommLog.d(appContext, "RFCOMM/TX", "GAIA " + RfcommLog.hex(frame))
        // GATT 与 SPP 的帧编码完全一致，只有传输方式不同 —— 见 FiilGatt。
        if (usingGatt) {
            FiilGatt.send(frame)
        } else {
            enqueue(frame)
        }
    }

    /**
     * 发一帧 Mate 数据（F045~F053 第三套命令空间）。
     *
     * 与 `com/fengeek/main/i/b.java:237 writeMate(...)` 字节一致：
     * `00 cid 01 00 [len+2] [cmdId] [len] payload`，cmdId=0 时不写该字节。
     */
    fun sendMate(classifyId: Int, commandId: Int, payload: ByteArray = ByteArray(0)) {
        val frame = FiilMate.buildFrame(classifyId, commandId, payload)
        Log.v(TAG, "tx-mate cid=$classifyId cmd=$commandId %s".format(
            frame.joinToString(" ") { "%02X".format(it) }
        ))
        RfcommLog.d(appContext, "RFCOMM/TX", "MATE " + RfcommLog.hex(frame))
        // GATT 与 SPP 的帧编码完全一致，只有传输方式不同 —— 见 FiilGatt。
        if (usingGatt) {
            FiilGatt.send(frame)
        } else {
            enqueue(frame)
        }
    }

    fun queryVersion() = send(FiilCommands.GET_VERSION, ByteArray(0), newProtocol = false)
    fun queryEarType() = send(FiilCommands.GET_EARTYPE, ByteArray(0), newProtocol = false)

    /**
     * 原样把十六进制串写到耳机（调试页「发送」框用）。
     *
     * 用途：验证某条指令耳机到底认不认。不经过任何组帧逻辑，
     * 用户输入什么就发什么 —— 排查协议差异时这是最快的手段，
     * 不必改代码重装模块。
     *
     * @return 是否成功写入（未连接或格式非法返回 false）
     */
    fun sendRawHex(hex: String): Boolean {
        val cleaned = hex.filter { !it.isWhitespace() && it != '-' && it != ':' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) {
            RfcommLog.w(appContext, TAG, "sendRawHex: bad hex length (${cleaned.length})")
            return false
        }
        val bytes = ByteArray(cleaned.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                RfcommLog.w(appContext, TAG, "sendRawHex: bad hex char at ${i * 2}")
                return false
            }
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        if (!isConnected) {
            RfcommLog.w(appContext, TAG, "sendRawHex ignored: not connected")
            return false
        }
        RfcommLog.i(appContext, TAG, "sendRawHex " + RfcommLog.hex(bytes))
        if (usingGatt) {
            FiilGatt.send(bytes)
        } else {
            enqueue(bytes)
        }
        return true
    }
    fun queryBattery() = send(FiilCommands.GET_BATTERY_LEVEL, ByteArray(0), newProtocol = false)
    fun queryAnc() = send(FiilCommands.GET_ANC, ByteArray(0), newProtocol = false)

    /**
     * 切换降噪档位：传原始线值，由协议层按机型决定走 GAIA SET_ANC 还是 Mate cid=44。
     *
     * ⚠️ v2 修复：旧实现无条件发 GAIA `SET_ANC(0x0201)`。Key Max 这类 Mate 机型
     * 只认 `cid=44`，GAIA 帧发过去耳机连收都不收 —— 这就是「模块点了切不了模式、
     * 但耳机按键切换模块能看见」的根因（接收走 cid=40 NOTIFY，正常；发送走错协议族）。
     */
    fun setAnc(mode: Int) {
        expectAckIfChanged(KEY_ANC, "降噪切换", mode) { ancMode }
        if (isMateDialect && FiilModels.supportsMateMaf(earType)) {
            RfcommLog.i(appContext, TAG, "setAnc -> MATE cid=44 mode=$mode")
            sendMate(FiilMate.Cid.SET_MAF, 0, byteArrayOf(mode.toByte()))
        } else {
            RfcommLog.i(appContext, TAG, "setAnc -> GAIA SET_ANC mode=$mode mate=$isMateDialect")
            send(FiilCommands.SET_ANC, byteArrayOf(mode.toByte()), newProtocol = false)
        }
    }

    /**
     * 切换 EQ 预设（索引含义见 FiilModels.eqPresets）。
     *
     * ⚠️ v2 修复：Mate 机型没有 `SET_EQ(0x0203)` 这条 GAIA 命令，
     * 必须用 `cid=32` + `[保留][类型][10 段增益]`。真正的 Mate 实现见
     * [FIILProtocol.setEQPreset]（它还需要已读到 EQ 曲线才能回写），
     * 这里只是兜底：Mate 且没有曲线时给出明确日志，而不是安静地发一帧废帧。
     */
    fun setEq(index: Int) {
        if (isMateDialect) {
            // 走到这里说明协议层没有接管（正常路径是 FIILProtocol.setEQPreset）。
            RfcommLog.w(
                appContext, TAG,
                "setEq($index) on Mate dialect: GAIA SET_EQ is not supported, ignored",
            )
            return
        }
        expectAckIfChanged(KEY_EQ, "EQ 切换", index) { eqIndex }
        send(FiilCommands.SET_EQ, byteArrayOf(index.toByte()), newProtocol = false)
    }

    fun set3D(on: Boolean) {
        val wanted = if (on) 1 else 0
        expectAckIfChanged(KEY_3D, "3D 音效开关", wanted) { if (is3D) 1 else 0 }
        if (isMateDialect) {
            // Mate 没有独立的 3D 开关；能表达「空间感」的只有影院模式（cid=50）。
            RfcommLog.w(
                appContext, TAG,
                "set3D($on) on Mate dialect: no GAIA SET_3D, use cinema(cid=50) instead",
            )
            return
        }
        send(FiilCommands.SET_3D, byteArrayOf(wanted.toByte()), newProtocol = false)
    }

    /**
     * 低延迟三档切换（GAIA 链路）。
     *
     * 线值 0=音乐（默认）/ 1=游戏 / 2=视频，与 `FiilCommandUtil.setLowLatency(int)` 一致：
     * 它把入参原样当成 1 字节 payload 下发，三档在
     * `firstCCPro2SetFragment.I(int temp, boolean)` @:917 里分别对应
     * `setLowLatency(0)/setLowLatency(1)/setLowLatency(2)`。
     */
    fun setLowLatencyLevel(level: Int) {
        val wanted = level.coerceIn(0, 2)
        expectAckIfChanged(KEY_LOW_LATENCY, "低延迟切换", wanted) { lowLatencyLevel }
        send(FiilCommands.SET_LOW_LATENCY, byteArrayOf(wanted.toByte()), newProtocol = true)
    }

    /** 兼容旧调用方：Boolean 形态，true 等同游戏档(1)。 */
    fun setLowLatency(on: Boolean) = setLowLatencyLevel(if (on) 1 else 0)

    /** 音量 ±1 级。 */
    /**
     * 音量加 / 减。
     *
     * ⚠️ v2 修复：Mate 机型不用 GAIA 的 UP_VOLUME/DOWN_VOLUME(0x0205/0x0206)，
     * 而是 `cid=33`(SET_MUSIC) + `cmdId=1` + payload `{1}` / `{0}`
     * （来源 `com/fengeek/main/i/d/f.java` 的 `setAddVolume` / `setReduceVolume`）。
     */
    fun volumeUp() {
        if (isMateDialect) {
            sendMate(FiilMate.Cid.SET_MUSIC, 1, byteArrayOf(1))
        } else {
            send(FiilCommands.UP_VOLUME, ByteArray(0), newProtocol = false)
        }
    }

    fun volumeDown() {
        if (isMateDialect) {
            sendMate(FiilMate.Cid.SET_MUSIC, 1, byteArrayOf(0))
        } else {
            send(FiilCommands.DOWN_VOLUME, ByteArray(0), newProtocol = false)
        }
    }

    /** 寻找耳机（响铃）。 */
    fun findDevice() = send(FiilCommands.SET_SEARCH, byteArrayOf(1), newProtocol = false)

    /**
     * 媒体播放控制。
     *
     * ⚠️ v2 修复：Mate 机型**没有** GAIA 的 PALY/PAUSE/NEXT/PREVIOUS(0x2001~0x2004)，
     * 它们走 `cid=33`(SET_MUSIC) + cmdId 区分动作（来源 `com/fengeek/main/i/d/f.java`）：
     *  · cmdId=2 播放（setMusicStatus(true)）
     *  · cmdId=3 停止（setMusicStatus(false)）
     *  · cmdId=4 上一首（setPreviousMusic）
     *  · cmdId=5 下一首（setNextMusic）
     * 早期实现无论什么机型都发 GAIA 帧，Mate 机型上这几个按钮等于按了没反应。
     */
    fun mediaPlay() = sendMedia(2, FiilCommands.PALY)

    fun mediaPause() = sendMedia(3, FiilCommands.PAUSE)

    fun mediaNext() = sendMedia(5, FiilCommands.NEXT)

    fun mediaPrev() = sendMedia(4, FiilCommands.PREVIOUS)

    private fun sendMedia(mateCmdId: Int, gaiaCmd: Int) {
        if (isMateDialect) {
            sendMate(FiilMate.Cid.SET_MUSIC, mateCmdId)
        } else {
            send(gaiaCmd, ByteArray(0), newProtocol = true)
        }
    }

    // ------------------------------------------------------------------
    // 状态广播给 UI
    // ------------------------------------------------------------------

    /**
     * 手动指定耳机型号（目标第二阶段 / 第五阶段）。
     *
     * 落盘到模块自己的 SharedPreferences，下次连接时 [connect] 会优先采用。
     * 传 -1 表示恢复自动识别。
     *
     * 切换型号会同时改写协议族（GAIA ↔ Mate），所以必须重连一次才能按新协议握手；
     * 这里只做「存 + 立刻生效到当前会话」，重连交给调用方（UI 提示用户）。
     */
    fun setEarTypeOverride(value: Int) {
        FiilConfig.writeEarTypeOverride(appContext, value)
        if (value > 0) {
            earType = value
            earTypeOverridden = true
            // 手动指定也写一条地址绑定：下次蓝牙名再被改、甚至覆盖项被清掉，
            // 这台耳机仍能靠地址认回用户选的型号（目标第五阶段「原始信息+地址绑定」）。
            FiilConfig.writeEarTypeForAddress(appContext, deviceAddress, value)
        } else {
            earTypeOverridden = false
            // 恢复自动识别时先看地址绑定，再退回原始名推断 —— 不能用 deviceName，
            // 那是用户可见别名（可能被改成「我的耳机」），推断型号一定失败。
            val bound = FiilConfig.readEarTypeForAddress(appContext, deviceAddress)
            earType = if (bound > 0) bound else FiilModels.earTypeOfName(deviceOriginalName)
        }
        isMateDialect = FiilModels.isMateDialect(earType)
        Log.i(TAG, "earType override -> $value (effective=$earType mate=$isMateDialect)")
        publishState()
    }

    fun snapshotJson(): String = snapshot().toJson()

    /** 当前状态的强类型快照 —— 供 OppoPods 兼容桥翻译成 UI 认识的广播。 */
    fun snapshot(): FiilStatus = FiilStatus(
        connected = isConnected,
        address = deviceAddress,
        name = deviceName,
        earType = earType,
        ancMode = ancMode,
        batteryLeft = batteryLeft,
        batteryRight = batteryRight,
        batteryCase = batteryCase,
        wearLeft = wearLeft,
        wearRight = wearRight,
        eqIndex = eqIndex,
        is3D = is3D,
        lowLatencyLevel = lowLatencyLevel,
        isMate = isMateDialect,
        cinemaMode = cinemaMode,
        multipoint = multipoint,
        eqBands = eqBands,
        wearDetect = wearDetect,
        noiseAdaptive = noiseAdaptive,
        windAdaptive = windAdaptive,
        earAdaptive = earAdaptive,
        eqAdaptive = eqAdaptive,
        tuning = tuning,
        earTypeOverridden = earTypeOverridden,
        toneVolume = toneVolume,
    )

    /** 上一次广播出去的连接/电量/ANC，用来做变化检测，避免刷屏。 */
    private var lastPublishedConnected: Boolean? = null
    private var lastPublishedBattery: String? = null
    private var lastPublishedAnc: Int? = null
    // ------------------------------------------------------------------
    // 操作确认（目标第五阶段：切换失败不能静默）
    // ------------------------------------------------------------------
    //
    // FIIL 的 GAIA / Mate 协议并不对每条 SET 命令都回 ACK：
    //  · GAIA 老机型会回一条同 cmd 的帧（`isAck` 或普通回显）；
    //  · Mate 机型在 `dispatchMate` 里按 `cid == SET_*` 且 payload 空/首字节 0 判成功。
    // 但两类链路上都存在「发出去石沉大海」的可能（机型不支持、耳机休眠、链路半断）。
    // 所以这里在发送时登记一条 pending 记录，超时后比对状态是否真的变了，
    // 没变就发 ACTION_OP_FAILED，让 UI 给用户一个可见的回退提示。

    /** 等待确认的命令，key = 命令标识（GAIA cmd 或 `mate:<cid>`）。 */
    private val pendingOps = java.util.concurrent.ConcurrentHashMap<String, PendingOp>()

    /**
     * 已知「协议不回新值」的开关：登记用户点的目标值，回显到达时直接采用。
     *
     * cid=1 的 SET 回显（空 payload 或 `[0]`）不带新状态，
     * 若按 payload 解释「关」会把刚打开的开错记成关；
     * 若按取反推定，在「连点两次」时会与真实状态错位。
     * 所以发送时把目标值记在这里，回显到达时取用并清除。
     */
    private val pendingTargets = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 已知「协议不回新值」的**整数**目标值（语音提示音量这类多档枚举）。
     *
     * 与 [pendingTargets] 同理，只是值域不是布尔：cid=1 cmd=3 的回显
     * `00 01 02 00 03 03 01 00` 末尾是「成功标志」，不携带新档位，
     * 所以要靠发送时记下的目标值来回填状态。
     */
    private val pendingTargetsInt = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 供 [FIILProtocol] 登记「协议不回新值」的操作目标值。 */
    fun rememberPendingTarget(key: String, value: Boolean) {
        pendingTargets[key] = value
    }

    /** 供 [FIILProtocol] 登记整数型「协议不回新值」的操作目标值（语音提示音量）。 */
    fun rememberPendingTargetInt(key: String, value: Int) {
        pendingTargetsInt[key] = value
    }

    private class PendingOp(
        val key: String,
        val label: String,
        val beforeValue: Int,
        val readValue: () -> Int,
    )

    /** 命令确认超时。取 2.5s：FIIL+ 自己的 CommandQueue 也是 3s 超时。 */
    private const val OP_TIMEOUT_MS = 2500L

    // 待确认操作的键。GAIA 与 Mate 用不同键，避免两套命令空间串号。
    private const val KEY_ANC = "anc"
    private const val KEY_EQ = "eq"
    private const val KEY_3D = "3d"
    private const val KEY_LOW_LATENCY = "low_latency"
    private const val KEY_CINEMA = "cinema"
    private const val KEY_MULTIPOINT = "multipoint"
    private const val KEY_TONE = "tone"
    private const val KEY_WEAR = "wear"
    private const val KEY_TUNING = "tuning"
    private const val KEY_EQ_ADAPTIVE = "eq_adaptive"
    private const val KEY_EAR_ADAPTIVE = "ear_adaptive"
    private const val KEY_NOISE_ADAPTIVE = "noise_adaptive"
    private const val KEY_WIND_ADAPTIVE = "wind_adaptive"

    /** 自适应开关的五种，用于 [applyAdaptiveFlag] 与 UI 广播共用一套映射。 */
    enum class AdaptiveKind(val ackKey: String, val label: String) {
        NOISE(KEY_NOISE_ADAPTIVE, "环境自适应降噪"),
        WIND(KEY_WIND_ADAPTIVE, "自适应抗风噪"),
        EAR(KEY_EAR_ADAPTIVE, "耳道自适应降噪"),
        EQ(KEY_EQ_ADAPTIVE, "自适应 EQ"),
        TUNING(KEY_TUNING, "云感调音"),
    }

    private val opHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 登记一次待确认的操作，超时未确认则广播失败。
     *
     * @param key     用于和回包匹配的键
     * @param label   给用户看的操作名（"降噪切换" 等）
     * @param read    当前值的读取器，超时时用它判断状态是否已变
     */
    private fun expectAck(key: String, label: String, read: () -> Int) {
        val op = PendingOp(key, label, runCatching(read).getOrDefault(-1), read)
        pendingOps[key] = op
        opHandler.postDelayed({
            if (pendingOps.remove(key) == null) return@postDelayed   // 已确认
            val now = runCatching(read).getOrDefault(-1)
            if (now == op.beforeValue) {
                Log.w(TAG, "op not confirmed: $label (value still $now)")
                broadcastOpFailed(op.label, now)
            }
        }, OP_TIMEOUT_MS)
    }

    /**
     * 与 [expectAck] 相同，但「目标值已等于当前值」时直接跳过。
     *
     * 这是必须的：协议回包只会在**状态真的变化**时才带上新值，
     * 若用户点的就是当前档位，耳机可能根本不回包，此时若仍然等待确认，
     * 2.5s 后就会误报「未生效」。所以「无变化 = 无需等待」。
     */
    private fun expectAckIfChanged(key: String, label: String, wanted: Int, read: () -> Int) {
        val current = runCatching(read).getOrDefault(-1)
        if (current == wanted) return
        expectAck(key, label, read)
    }

    /** [expectAckIfChanged] 的公开包装，供 [FIILProtocol] 使用。 */
    fun expectAckPublicIfChanged(key: String, label: String, wanted: Int, read: () -> Int) {
        expectAckIfChanged(key, label, wanted, read)
    }

    /**
     * 严格等待回显：超时未收到任何回包就直接判失败，不做「值有没有变」的兜底。
     *
     * 用于 EQ 写入这类**回包不体现在某个标量状态上**的操作：
     * 写自定义 EQ 时 `eqIndex` 恒为 -1（自定义），若按值不变判失败会误报。
     */
    private fun expectAckStrict(key: String, label: String) {
        pendingOps[key] = PendingOp(key, label, Int.MIN_VALUE) { Int.MAX_VALUE }
        opHandler.postDelayed({
            if (pendingOps.remove(key) == null) return@postDelayed
            Log.w(TAG, "op not confirmed (strict): $label")
            broadcastOpFailed(label, -1)
        }, OP_TIMEOUT_MS)
    }

    /** [expectAckStrict] 的公开包装，供 [FIILProtocol] 使用。 */
    fun expectAckPublicStrict(key: String, label: String) {
        expectAckStrict(key, label)
    }

    /** 某个命令收到了回包，撤销对应的待确认记录。 */
    private fun ackOp(key: String) {
        pendingOps.remove(key)
    }

    /**
     * 供 [FIILProtocol] 使用的公开包装。
     *
     * 协议层里那些「能自行决定走 GAIA 还是 Mate」的设置方法都在 FIILProtocol 中，
     * 它们需要登记待确认操作，但 `expectAck` 是私有实现细节，所以这里开一个窄口。
     */
    fun expectAckPublic(key: String, label: String, read: () -> Int) {
        expectAck(key, label, read)
    }

    private fun broadcastOpFailed(label: String, value: Int) {
        val ctx = appContext ?: return
        broadcastToTargets(ctx, FiilAction.ACTION_OP_FAILED) {
            putExtra(FiilAction.EXTRA_CMD, label)
            putExtra(FiilAction.EXTRA_STATUS, value)
        }
        // 模块自己的 UI 也要收到（它不在 STATUS_TARGETS 里）。
        // 注意必须显式指定 MODULE_PACKAGE：这里的 ctx 是**蓝牙进程**的 Context，
        // ctx.packageName == "com.android.bluetooth"，用它会绕回蓝牙进程自己。
        runCatching {
            ctx.sendBroadcast(
                Intent(FiilAction.ACTION_OP_FAILED)
                    .setPackage(FiilConfig.MODULE_PACKAGE)
                    .putExtra(FiilAction.EXTRA_CMD, label)
                    .putExtra(FiilAction.EXTRA_STATUS, value)
            )
        }
    }

    /** 主动推一次完整状态（供命令入口按需触发）。 */
    fun publishState(force: Boolean = false) {
        val ctx = appContext ?: return
        val json = snapshotJson()

        // 1) 模块自己的 UI
        // 必须用 MODULE_PACKAGE 而不是 ctx.packageName —— ctx 是蓝牙进程的 Context，
        // 它的 packageName 是 com.android.bluetooth。历史上这里写成 ctx.packageName，
        // 导致状态广播全部绕回蓝牙进程本身，模块 UI（moe.chenxy.fiilpods 进程）
        // 一条也收不到，表现就是「耳机已连接但界面一直显示未连接」。
        runCatching {
            ctx.sendBroadcast(
                Intent(FiilAction.ACTION_STATE)
                    .setPackage(FiilConfig.MODULE_PACKAGE)
                    .putExtra(FiilAction.EXTRA_JSON, json)
            )
        }

        // 2) 系统侧：连接状态
        if (force || lastPublishedConnected != isConnected) {
            lastPublishedConnected = isConnected
            broadcastToTargets(
                ctx,
                if (isConnected) FiilAction.ACTION_PODS_CONNECTED
                else FiilAction.ACTION_PODS_DISCONNECTED,
            ) {
                putExtra(FiilAction.EXTRA_ADDRESS, deviceAddress.orEmpty())
                putExtra(FiilAction.EXTRA_DEVICE_NAME, deviceName.orEmpty())
                putExtra(FiilAction.EXTRA_CONNECTED, isConnected)
            }
        }

        // 3) 系统侧：电量
        val batteryKey = "$batteryLeft/$batteryRight/$batteryCase"
        if (force || lastPublishedBattery != batteryKey) {
            lastPublishedBattery = batteryKey
            broadcastToTargets(ctx, FiilAction.ACTION_PODS_BATTERY_CHANGED) {
                putExtra(FiilAction.EXTRA_ADDRESS, deviceAddress.orEmpty())
                putExtra(FiilAction.EXTRA_DEVICE_NAME, deviceName.orEmpty())
                putExtra(FiilAction.EXTRA_LEFT_BATTERY, batteryLeft.coerceAtLeast(0))
                putExtra(FiilAction.EXTRA_LEFT_CONNECTED, batteryLeft >= 0)
                putExtra(FiilAction.EXTRA_RIGHT_BATTERY, batteryRight.coerceAtLeast(0))
                putExtra(FiilAction.EXTRA_RIGHT_CONNECTED, batteryRight >= 0)
                putExtra(FiilAction.EXTRA_CASE_BATTERY, batteryCase.coerceAtLeast(0))
                putExtra(FiilAction.EXTRA_CASE_CONNECTED, batteryCase >= 0)
            }
        }

        // 4) 系统侧：ANC
        if (force || lastPublishedAnc != ancMode) {
            lastPublishedAnc = ancMode
            broadcastToTargets(ctx, FiilAction.ACTION_PODS_ANC_CHANGED) {
                putExtra(FiilAction.EXTRA_ADDRESS, deviceAddress.orEmpty())
                putExtra(FiilAction.EXTRA_STATUS, ancMode)
            }
        }
        // 5) OppoPods 兼容契约：移植过来的 UI 只认这套广播
        if (force) OppoCompatBridge.resetCache()
        runCatching { OppoCompatBridge.publish(ctx, snapshot(), force) }
            .onFailure { Log.w(TAG, "compat bridge publish failed", it) }
    }

    private inline fun broadcastToTargets(
        ctx: Context,
        action: String,
        fill: Intent.() -> Unit,
    ) {
        FiilAction.STATUS_TARGETS.forEach { pkg ->
            runCatching {
                ctx.sendBroadcast(
                    Intent(action).setPackage(pkg).apply(fill).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                )
            }
        }
    }

    /** 枚举已配对的 FIIL 设备（通过名字前缀判断，因为 SDP 里没有品牌字段）。 */
    fun findPairedFiilDevices(): List<BluetoothDevice> {
        val adapter = appContext
            ?.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter
            ?: return emptyList()
        return adapter.bondedDevices.orEmpty().filter {
            // 三个都要看：
            //  ① MAC 前缀（FIIL 自己的 OTA 页就是用它判定自家设备，最稳）；
            //  ② 原始名 —— 用户改过别名后 alias 可能不含 "FIIL"，但原始名仍有；
            //  ③ 别名 —— 厂商把原始名改成营销名时，别名可能是用户自己起的 "FIIL XX"。
            FiilConfig.isFiilAddress(it.address) ||
                isFiilDevice(it.name) ||
                isFiilDevice(preferredDisplayName(it))
        }
    }

    /**
     * 品牌判定。
     *
     * 唯一实现放在 [FiilConfig]，这里只是转发 ——
     * 之前本文件与 [FiilConfig] 各存一份 token 表，改一处漏一处的风险很高。
     * 传 address 时会额外按 FIIL 的 MAC 前缀判定，覆盖「用户把蓝牙名改掉」的情况。
     */
    fun isFiilDevice(name: String?, address: String? = null): Boolean =
        FiilConfig.isFiilDevice(name, address)

    /**
     * 用户可见的设备名：优先系统蓝牙设置里的别名（`getAlias()`），
     * 没有别名才退回厂商原始名（`getName()`）。
     *
     * 目标第五阶段：「通知/超级岛的名称优先读取系统蓝牙设置中的设备名」。
     * 型号识别**不能**用这个 —— 用户可能把名字改成「我的耳机」，那样就认不出型号了，
     * 所以 [FiilModels.earTypeOfName] 的入参一律传 `device.name`。
     */
    fun preferredDisplayName(device: BluetoothDevice): String? {
        val alias = runCatching { device.alias }.getOrNull()
        return if (!alias.isNullOrBlank()) alias else device.name
    }

    fun deviceSdkInt(): Int = Build.VERSION.SDK_INT
}
