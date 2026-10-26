package moe.chenxy.fiilpods.hook

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import moe.chenxy.fiilpods.pods.FIILProtocol
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilMate
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilRfcommController
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction

/**
 * 注入 com.android.bluetooth 进程。
 *
 * 职责：
 *  1. 监听 A2DP 连接状态，识别 FIIL 耳机并拉起 RFCOMM 控制链路
 *  2. 把耳机状态通过广播发给设置页与超级岛
 *  3. 接收设置页 / 模块 UI 下发的控制命令，转成 GAIA 帧发给耳机
 *
 * 这里是全模块唯一真正持有蓝牙 socket 的地方 —— 因为只有蓝牙进程
 * 才被授予 BLUETOOTH_CONNECT 并且不会被系统的权限校验拦下。
 */
class BluetoothProcessHook(entry: HookEntry, classLoader: ClassLoader) :
    HookContext(entry, classLoader) {

    private var receiverRegistered = false
    private var hostContext: Context? = null

    override fun onHook() {
        val ctx = entry.appContextOrNull()
        if (ctx == null) {
            logW("no application context yet, controller deferred")
        } else {
            hostContext = ctx
            FiilRfcommController.start(ctx)
            registerReceiver(ctx)
            adoptAlreadyConnected(ctx)
        }
        installAdapterServiceHook()
        installA2dpStateHook()
    }

    /**
     * 补偿接管**已经连上**的 FIIL 耳机。
     *
     * 为什么必须有这一步：`A2dpService.handleConnectionStateChanged` 只在状态**变化**时触发。
     * 而模块被 LSPosed 加载的时刻，耳机往往早就连好了（用户先连耳机、后重启蓝牙进程 /
     * 后启用模块 / 模块升级后进程重启），此时那个回调不会再发生，
     * 控制器就永远停在「未连接、无状态」，UI 也就检测不到耳机。
     *
     * 实现上不依赖 A2dpService 实例（onHook 时它可能还没构造），
     * 直接走 `BluetoothAdapter.bondedDevices` + `A2DP` profile 的
     * `getConnectionState()` 判断「已配对且 A2DP 已连接」。这条路径在蓝牙进程内
     * 一定拿得到（进程本身持有 BLUETOOTH_CONNECT）。
     *
     * 另外做一次延迟重试：进程刚起来时 A2DP profile service 可能还没 ready，
     * 立刻查会返回 DISCONNECTED，所以 1.5s / 5s 各再补一次。
     */
    private fun adoptAlreadyConnected(ctx: Context) {
        scheduleAdopt(ctx, delayMs = 0L)
        scheduleAdopt(ctx, delayMs = 1_500L)
        scheduleAdopt(ctx, delayMs = 5_000L)
    }

    private fun scheduleAdopt(ctx: Context, delayMs: Long) {
        val runnable = Runnable {
            runCatching { adoptAlreadyConnectedNow(ctx) }
                .onFailure { logW("adopt already-connected failed", it) }
        }
        if (delayMs <= 0L) {
            runnable.run()
            return
        }
        runCatching {
            Handler(android.os.Looper.getMainLooper()).postDelayed(runnable, delayMs)
        }.onFailure { logW("schedule adopt failed", it) }
    }

    private fun adoptAlreadyConnectedNow(ctx: Context) {
        // 已经连着就不用重复接管了
        if (FiilRfcommController.isConnected) return
        val adapter = runCatching {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        }.getOrNull() ?: return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return

        // 主路径：枚举系统自己认定的「当前已连接 A2DP 设备」。
        // 同步返回，比 getProfileProxy 的异步回调可靠 —— 后者在蓝牙进程刚起来时经常还没 ready。
        // 各版本入口不同，见 enumerateA2dpConnected() 里的多级兜底。
        val adopted = runCatching { enumerateA2dpConnected(ctx) }.getOrDefault(emptyList())
        logI("adopt: a2dp-connected=${adopted.size} fiil=${adopted.count { isFiil(it) }}")
        adopted.filter { isFiil(it) }.forEach { connectIfIdle(ctx, it, "a2dp-service") }

        // 兜底：A2dpService 还没构造出来时（极早期），退回已配对枚举。
        // 只在连「一个都还没接管」且确实有 FIIL 已配对设备时才动，
        // 避免蓝牙关着 / 耳机没连时也去建 socket（那会失败并打一堆错误日志）。
        if (FiilRfcommController.isConnected || adopted.isNotEmpty()) return
        runCatching {
            val fiilPaired = FiilRfcommController.findPairedFiilDevices()
            if (fiilPaired.isEmpty()) return@runCatching
            val a2dpOn = adapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) ==
                android.bluetooth.BluetoothProfile.STATE_CONNECTED
            if (!a2dpOn) return@runCatching
            logI("adopt fallback: a2dp state=CONNECTED, paired FIIL=${fiilPaired.size}")
            connectIfIdle(ctx, fiilPaired.first(), "a2dp-bonded")
        }.onFailure { logW("bonded enumerate failed", it) }
    }

    /**
     * 反射枚举「当前已连接的 A2DP 设备」。
     *
     * 各 Android 版本入口不一样，必须多级兜底：
     *  - **Android 15**：`com.android.bluetooth.a2dp.A2dpService` 有
     *    `public static A2dpService sA2dpService;`（`A2dpService.java:182`）
     *    + `public List<BluetoothDevice> getConnectedDevices()`（`:748`）
     *  - **Android 17**：静态字段**已删除**，改为
     *    `AdapterService.sAdapterService`（`AdapterService.java:290`）
     *    → `Optional getA2dpService()`（`:1396`，实为 `getStartedProfile(2, A2dpService.class)`）
     *    → `Optional.get()` → `getConnectedDevices()`（`A2dpService.java:821`，返回裸 `List`）
     *
     * 任一级拿不到就退下一级，最后返回空表（调用方会走 bonded 兜底）。
     */
    private fun enumerateA2dpConnected(ctx: Context): List<BluetoothDevice> {
        val a2dpCls = runCatching {
            Class.forName("com.android.bluetooth.a2dp.A2dpService", false, classLoader)
        }.getOrNull() ?: return emptyList()

        val instance = a2dpServiceInstance(a2dpCls) ?: return emptyList()
        val method = runCatching { a2dpCls.getMethod("getConnectedDevices") }.getOrNull()
            ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (runCatching { method.invoke(instance) }.getOrNull() as? List<BluetoothDevice>)
            .orEmpty()
    }

    /** 取 `A2dpService` 单例：先试 Android 15 的静态字段，再试 Android 17 的 AdapterService 入口。 */
    private fun a2dpServiceInstance(a2dpCls: Class<*>): Any? {
        // ① Android 15 及更早：A2dpService.sA2dpService
        runCatching { a2dpCls.getField("sA2dpService").get(null) }
            .getOrNull()
            ?.let { return it }

        // ② Android 17：AdapterService.sAdapterService.getA2dpService() → Optional
        val adapterCls = runCatching {
            Class.forName("com.android.bluetooth.btservice.AdapterService", false, classLoader)
        }.getOrNull() ?: return null
        val adapter = runCatching { adapterCls.getField("sAdapterService").get(null) }
            .getOrNull() ?: return null
        val optional = runCatching {
            adapterCls.getMethod("getA2dpService").invoke(adapter)
        }.getOrNull() ?: return null
        return runCatching {
            val present = optional.javaClass.getMethod("isPresent").invoke(optional) as Boolean
            if (!present) null else optional.javaClass.getMethod("get").invoke(optional)
        }.getOrNull()
    }

    private fun connectIfIdle(ctx: Context, device: BluetoothDevice, source: String) {
        if (FiilRfcommController.isConnected) return
        logI("adopt already-connected FIIL device ($source): ${device.address} ${device.name}")
        FiilRfcommController.start(ctx)
        // connect() 是异步的（内部起 FiilPods-Connect 线程），
        // 这里可能跑在主线程（Handler 回调 / onReceive），同步建链会把蓝牙进程卡到 ANR。
        FiilRfcommController.connect(device)
    }

    private fun installAdapterServiceHook() {
        runCatching {
            val onCreate = findMethodByParamCount(
                "com.android.bluetooth.btservice.AdapterService",
                "onCreate",
                0,
            ) ?: return
            hookAfter(onCreate) { param ->
                val ctx = param.instance as? Context ?: return@hookAfter
                hostContext = ctx
                FiilRfcommController.start(ctx)
                registerReceiver(ctx)
                announceAlive(ctx)
                // 蓝牙进程刚起来：把此刻已经连着的耳机接管过来
                adoptAlreadyConnected(ctx)
            }
        }.onFailure { logW("AdapterService.onCreate hook skipped", it) }
    }

    private fun installA2dpStateHook() {
        val method = findMethodByParamCount(
            "com.android.bluetooth.a2dp.A2dpService",
            "handleConnectionStateChanged",
            3,
        )
        if (method == null) {
            logE("A2dpService#handleConnectionStateChanged not found")
            return
        }
        hookAll(method) { param ->
            val device = param.args.getOrNull(0) as? BluetoothDevice ?: return@hookAll
            val oldState = param.args.getOrNull(1) as? Int ?: return@hookAll
            val newState = param.args.getOrNull(2) as? Int ?: return@hookAll
            if (oldState == newState) return@hookAll
            val ctx = (param.instance as? ContextWrapper)?.baseContext ?: hostContext ?: return@hookAll
            val handler = getObjectField(param.instance, "mHandler") as? Handler
            val task = {
                registerReceiver(ctx)
                when (newState) {
                    BluetoothHeadset.STATE_CONNECTED -> {
                        if (isFiil(device)) {
                            logI("FIIL device connected: ${device.address} ${device.name}")
                            FiilRfcommController.start(ctx)
                            FiilRfcommController.connect(device)
                        }
                    }
                    BluetoothHeadset.STATE_DISCONNECTED,
                    BluetoothHeadset.STATE_DISCONNECTING -> {
                        if (device.address == FiilRfcommController.deviceAddress) {
                            FiilRfcommController.disconnect("a2dp-disconnected")
                        }
                    }
                }
            }
            if (handler != null) handler.post(task) else task()
        }
    }

    /** 广播「本模块蓝牙侧服务已就绪」，设置页据此决定是否注入卡片。 */
    private fun announceAlive(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(
                Intent(FiilAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                    setPackage("com.android.settings")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
        // 移植过来的 UI 只认 OppoPods 那套 action 名，它靠这条广播判断
        // 「模块服务已连接」，收不到就会一直显示服务离线。
        runCatching {
            ctx.sendBroadcast(
                Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                    setPackage(FiilConfig.MODULE_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        hostContext = ctx
        val filter = IntentFilter().apply {
            addAction(FiilAction.ACTION_REQUEST_STATE)
            addAction(FiilAction.ACTION_PODS_UI_INIT)
            addAction(FiilAction.ACTION_REFRESH_STATUS)
            addAction(FiilAction.ACTION_COMMAND)
            addAction(FiilAction.ACTION_ANC_SELECT)
            addAction(FiilAction.ACTION_CONFIG_CHANGED)
            // ---- OppoPods 兼容契约 ----
            // UI 层是从 1812z/OppoPods 整份移植过来的，它只发 chen.action.oppopods.*，
            // 不认识上面那套 moe.chenxy.fiilpods.action.*。两套都要接，否则界面点了没反应。
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_ANC_SELECT)
            addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
            addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
            addAction(OppoPodsAction.ACTION_EQ_PRESET_SET)
            addAction(OppoPodsAction.ACTION_EQ_PRESET_SAVE)
            addAction(OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET)
            addAction(OppoPodsAction.ACTION_TONE_VOLUME_SET)
            addAction(OppoPodsAction.ACTION_LOW_LATENCY_MODE_SET)
            addAction(OppoPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_CYCLE_ANC)
            addAction(OppoPodsAction.ACTION_PODS_UI_CLOSED)
        }
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    // 整个分发必须包在 runCatching 里。
                    //
                    // 理由：广播 extras 里的 Parcelable 是按「类名字符串」反序列化的。
                    // 如果发送方与接收方不是同一个构建（覆盖安装后旧进程还在、
                    // release 与 debug 混装、LSPosed 作用域里的残留旧模块），
                    // 类名对不上就会抛 BadParcelableException —— 这是**异常**，
                    // 不是返回 null，`?.let` 挡不住。而这里跑在 com.android.bluetooth
                    // 主线程上，抛出去就是蓝牙进程崩溃。
                    // 历史事故：ACTION_CONNECT_POD_REQUEST 的 device 取值把蓝牙进程打挂。
                    runCatching {
                        when (intent.action) {
                        FiilAction.ACTION_REQUEST_STATE,
                        FiilAction.ACTION_PODS_UI_INIT,
                        FiilAction.ACTION_REFRESH_STATUS,
                        // OppoPods 契约里 UI 拉起/手动刷新也走这两个 action
                        OppoPodsAction.ACTION_PODS_UI_INIT,
                        OppoPodsAction.ACTION_REFRESH_STATUS -> {
                            // 界面主动来问 → 先强制全量重发一次，再顺手补连接
                            FiilRfcommController.publishState(force = true)
                            if (!FiilRfcommController.isConnected) {
                                FiilRfcommController.findPairedFiilDevices().firstOrNull()?.let {
                                    FiilRfcommController.connect(it)
                                }
                            }
                        }
                        OppoPodsAction.ACTION_PODS_UI_CLOSED -> Unit
                        FiilAction.ACTION_COMMAND -> handleCommand(intent)
                        FiilAction.ACTION_ANC_SELECT -> {
                            FiilRfcommController.setAnc(intent.getIntExtra(FiilAction.EXTRA_STATUS, 0))
                        }
                        FiilAction.ACTION_CONFIG_CHANGED -> logI("config changed")
                        // ---- OppoPods 兼容契约（UI 层是原封移植的） ----
                        OppoPodsAction.ACTION_ANC_SELECT -> {
                            // UI 送的是 FIIL MAF 线值 0~4
                            FiilRfcommController.setAnc(intent.getIntExtra("status", 0))
                        }
                        OppoPodsAction.ACTION_CYCLE_ANC -> {
                            FiilRfcommController.setAnc(
                                (FiilRfcommController.ancMode + 1).let { if (it > 2) 0 else it }
                            )
                        }
                        OppoPodsAction.ACTION_GAME_MODE_SET -> {
                            // 游戏模式 ↔ 低延迟：开 → GAME(1)，关 → MUSIC(0)
                            val on = intent.getBooleanExtra("enabled", false)
                            FIILProtocol.setLowLatencyMode(
                                if (on) FIILProtocol.LowLatencyMode.GAME
                                else FIILProtocol.LowLatencyMode.MUSIC
                            )
                        }
                        OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                            // 空间音频 ↔ FIIL 影院模式：非 0 视为开
                            FIILProtocol.setCinemaMode(
                                intent.getIntExtra("mode", 0) != 0
                            )
                        }
                        OppoPodsAction.ACTION_EQ_PRESET_SET -> {
                            FIILProtocol.setEQPreset(intent.getIntExtra("preset", 0))
                        }
                        OppoPodsAction.ACTION_EQ_PRESET_SAVE -> {
                            // UI 的拖杆是 ±6 dB，FIIL 线值是 0~255（128 = 0 dB，±12 dB 满量程）
                            val gains = intent.getIntegerArrayListExtra("gains")
                            if (gains != null) {
                                val expect =
                                    FiilModels.eqBandLabels(FiilRfcommController.earType).size
                                if (gains.size == expect) {
                                    val raw = ByteArray(gains.size) { i ->
                                        (128 + gains[i] * 128 / 12).coerceIn(0, 255).toByte()
                                    }
                                    FIILProtocol.writeEQ(raw)
                                } else {
                                    logW("eq save ignored: got=${gains.size} expect=$expect")
                                }
                            }
                        }
                        OppoPodsAction.ACTION_DUAL_DEVICE_CONNECTION_SET -> {
                            FIILProtocol.setMultipoint(intent.getBooleanExtra("enabled", false))
                        }
                        OppoPodsAction.ACTION_TONE_VOLUME_SET -> {
                            FIILProtocol.setToneVolume(intent.getIntExtra("level", 0))
                        }
                        OppoPodsAction.ACTION_LOW_LATENCY_MODE_SET -> {
                            // 线值直通：0=音乐 1=游戏 2=视频（cid=37, cmdId=0）
                            FIILProtocol.setLowLatencyMode(
                                FIILProtocol.LowLatencyMode.fromWire(
                                    intent.getIntExtra(OppoPodsAction.EXTRA_LOW_LATENCY_MODE, 0),
                                ),
                            )
                        }
                        OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                            @Suppress("DEPRECATION")
                            val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>("device")
                            device?.let { FiilRfcommController.connect(it) }
                        }
                        OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                            FiilRfcommController.disconnect("user-request")
                        }
                    }
                    }.onFailure {
                        // 绝不能让它冒到蓝牙进程主线程 —— 那里没人接，直接 ANR/崩溃。
                        logW("broadcast dispatch failed: ${intent.action}", it)
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED)
            logI("command receiver registered")
        }.onFailure {
            receiverRegistered = false
            logE("register receiver failed", it)
        }
    }

    private fun publishNow(ctx: Context?) {
        val target = ctx ?: hostContext ?: return
        FiilRfcommController.publishState()
    }

    private fun handleCommand(intent: Intent) {
        when (val cmd = intent.getStringExtra(FiilAction.EXTRA_CMD)) {
            "anc" -> FIILProtocol.setMAFMode(intent.getIntExtra(FiilAction.EXTRA_VALUE, 0))
            "eq" -> FIILProtocol.setEQPreset(intent.getIntExtra(FiilAction.EXTRA_VALUE, 0))
            "eqBands" -> {
                // 段数按机型：F045=5、F049=8、其余 10。旧代码硬性要求 10 段，
                // 在 5/8 段机型上会把合法请求直接丢弃；在 10 段机型上又会放过超长数组。
                val arr = intent.getIntArrayExtra(FiilAction.EXTRA_EQ_BANDS)
                val expect = FiilModels.eqBandLabels(FiilRfcommController.earType).size
                if (arr != null && arr.size == expect) {
                    FIILProtocol.writeEQ(ByteArray(arr.size) { arr[it].toByte() })
                } else {
                    logW("eqBands ignored: got=${arr?.size} expect=$expect")
                }
            }
            "eqCustom" -> FIILProtocol.setEQCustom(
                intent.getIntArrayExtra(FiilAction.EXTRA_EQ_BANDS)
                    ?: FiilMate.defaultBands(FiilRfcommController.earType),
            )
            "3d" -> FIILProtocol.set3DAudio(intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false))
            "cinema" ->
                FIILProtocol.setCinemaMode(intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false))
            "multipoint" ->
                FIILProtocol.setMultipoint(intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false))
            "wear" ->
                FIILProtocol.setWearDetect(intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false))
            "adaptive" -> {
                val kindName = intent.getStringExtra("adaptive_kind")
                val kind = runCatching {
                    FiilRfcommController.AdaptiveKind.valueOf(kindName.orEmpty())
                }.getOrNull()
                if (kind == null) {
                    logW("adaptive ignored: kind=$kindName")
                } else {
                    FIILProtocol.setAdaptiveSwitch(
                        kind,
                        intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false),
                    )
                }
            }
            "lowLatency" -> {
                // 兼容旧调用：布尔值按「开=游戏档」
                val on = intent.getBooleanExtra(FiilAction.EXTRA_FLAG, false)
                FIILProtocol.setLowLatencyMode(
                    if (on) FIILProtocol.LowLatencyMode.GAME else FIILProtocol.LowLatencyMode.MUSIC
                )
            }
            "lowLatencyMode" -> {
                // 传的是协议线值（0=音乐 / 1=游戏 / 2=视频），不是枚举序号
                val wire = intent.getIntExtra(FiilAction.EXTRA_VALUE, 0)
                FIILProtocol.setLowLatencyMode(FIILProtocol.LowLatencyMode.fromWire(wire))
            }
            "setEarType" -> {
                // 手动选择型号（0 = 恢复自动识别）。落盘 + 立即生效。
                FiilRfcommController.setEarTypeOverride(intent.getIntExtra(FiilAction.EXTRA_VALUE, 0))
            }
            "volumeUp" -> FIILProtocol.volumeUp()
            "volumeDown" -> FIILProtocol.volumeDown()
            "find" -> FIILProtocol.findDevice()
            "play" -> FIILProtocol.mediaPlay()
            "pause" -> FIILProtocol.mediaPause()
            "next" -> FIILProtocol.mediaNext()
            "prev" -> FIILProtocol.mediaPrev()
            "connect" -> {
                val addr = intent.getStringExtra(FiilAction.EXTRA_ADDRESS)
                FiilRfcommController.findPairedFiilDevices()
                    .firstOrNull { addr == null || it.address == addr }
                    ?.let { FiilRfcommController.connect(it) }
            }
            "disconnect" -> FiilRfcommController.disconnect("user-request")
            "refresh" -> FIILProtocol.refreshAll()
            else -> logW("unknown cmd=$cmd")
        }
    }

    private fun isFiil(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        // 名字可能被用户改成任意值，所以要带上 MAC 前缀兜底。
        return FiilConfig.isFiilDevice(name, runCatching { device.address }.getOrNull())
    }
}
