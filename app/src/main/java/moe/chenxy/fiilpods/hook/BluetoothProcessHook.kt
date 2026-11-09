package moe.chenxy.fiilpods.hook

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import moe.chenxy.fiilpods.config.ConfigManager
import moe.chenxy.fiilpods.pods.AncSemantic
import moe.chenxy.fiilpods.pods.AncWireCodec
import moe.chenxy.fiilpods.pods.AutoGameModePolicy
import moe.chenxy.fiilpods.pods.AutoGameModeRuntime
import moe.chenxy.fiilpods.pods.FIILProtocol
import moe.chenxy.fiilpods.pods.FiilAction
import moe.chenxy.fiilpods.pods.FiilCodec
import moe.chenxy.fiilpods.pods.FiilConfig
import moe.chenxy.fiilpods.pods.FiilCommands
import moe.chenxy.fiilpods.pods.FiilGaia
import moe.chenxy.fiilpods.pods.FiilMate
import moe.chenxy.fiilpods.pods.FiilModels
import moe.chenxy.fiilpods.pods.FiilRfcommController
import moe.chenxy.fiilpods.pods.RfcommLog
import moe.chenxy.fiilpods.pods.ancCycleWire
import moe.chenxy.fiilpods.pods.ancDialectForEarType
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

    /**
     * The automatic game-mode work is deliberately owned by one background Handler.
     * Broadcast delivery and RFCOMM reader callbacks only enqueue here; neither can block on
     * UsageStats nor issue a synchronous socket operation.
     */
    private val autoGameThread = HandlerThread("FiilPods-AutoGame").apply { start() }
    private val autoGameHandler = Handler(autoGameThread.looper)
    private val autoGameLock = Any()
    private val autoGameRuntime = AutoGameModeRuntime()

    @Volatile
    private var autoGameConfigEnabled = false

    @Volatile
    private var autoGameConnectionActive = false

    private var autoGameEnabled = false
    private var autoGameGeneration = 0L
    private var autoGameLastUsageEventAt = 0L
    private val autoGameUsageEventsAtCursor = mutableSetOf<String>()
    private var autoGameUsagePoll: Runnable? = null
    private var autoGameLatencyProbe: Runnable? = null

    /** 用户在本连接内手动切过低延迟：本连接不再自动下发任何命令。 */
    private var autoGameManualSession = false

    /** 已下发但尚未被耳机状态帧确认的目标档位；null 表示没有在途命令。 */
    private var autoGameAwaitingMode: AutoGameModePolicy.LowLatency? = null

    /** 当前这轮状态探测已尝试次数，用来给「耳机不回包」兜底，避免无限轮询。 */
    private var autoGameLatencyProbeAttempts = 0

    /** 前台分类轮询间隔：太密会白耗电，太疏会让「退出游戏」迟滞数秒。 */
    private companion object {
        private const val AUTO_GAME_POLL_INTERVAL_MS = 3_000L
        private const val AUTO_GAME_LATENCY_PROBE_INTERVAL_MS = 1_200L
        private const val AUTO_GAME_LATENCY_PROBE_MAX = 12
        private const val AUTO_GAME_USAGE_LOOKBACK_MS = 10 * 60_000L
    }

    private data class AutoGameUsageEvent(
        val at: Long,
        val packageName: String,
        val className: String?,
    )

    override fun onHook() {
        if (packageName != HookEntry.PKG_BLUETOOTH) {
            logW("auto-game integration skipped outside ${HookEntry.PKG_BLUETOOTH}: $packageName")
            return
        }
        // Install RX/disconnect observers before any connection can start emitting status.
        installAutoGameModeHooks()
        val ctx = entry.appContextOrNull()
        if (ctx == null) {
            logW("no application context yet, controller deferred")
        } else {
            hostContext = ctx
            FiilRfcommController.start(ctx)
            registerReceiver(ctx)
            refreshAutoGameModeConfig()
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
        logI("adopt already-connected FIIL device ($source): ${FiilConfig.maskAddress(device.address)} ${device.name}")
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
                refreshAutoGameModeConfig()
                announceAlive(ctx)
                // 蓝牙进程刚起来：把此刻已经连着的耳机接管过来
                adoptAlreadyConnected(ctx)
            }
        }.onFailure { logW("AdapterService.onCreate hook skipped", it) }
    }

    /** Installs narrow controller observers for real low-latency status frames and disconnects. */
    private fun installAutoGameModeHooks() {
        installAutoGameHook("controller disconnect") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("disconnect", String::class.java)
                .apply { isAccessible = true }
            hookBefore(method) { onAutoGameDisconnected() }
        }
        // 连接建立的两种通道都要开新会话：SPP 走 connectBlocking 返回 true，
        // BLE-GATT 走 onGattReady。没有这一步，`autoGameConnectionActive` 永远为 false，
        // 自动游戏模式在「先启用开关、后连耳机」的常见顺序下完全不动。
        installAutoGameHook("SPP connected") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("connectBlocking", BluetoothDevice::class.java)
                .apply { isAccessible = true }
            hookAfter(method) { param ->
                if (param.result == true) onAutoGameConnected()
            }
        }
        installAutoGameHook("GATT connected") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("onGattReady", BluetoothDevice::class.java)
                .apply { isAccessible = true }
            hookAfter(method) { onAutoGameConnected() }
        }
        // GATT loss bypasses disconnect(String), so observe it separately to prevent a stale
        // baseline or AUTO ownership from crossing a BLE reconnection boundary.
        installAutoGameHook("GATT disconnect") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("onGattLost")
                .apply { isAccessible = true }
            hookAfter(method) { onAutoGameDisconnected() }
        }
        installAutoGameHook("Mate low-latency status") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("dispatchMate", FiilMate.Frame::class.java)
                .apply { isAccessible = true }
            hookAfter(method) { param ->
                val frame = param.args.firstOrNull() as? FiilMate.Frame ?: return@hookAfter
                confirmedAutoGameMode(frame)?.let(::onAutoGameLowLatencyConfirmed)
            }
        }
        installAutoGameHook("GAIA low-latency status") {
            val method = FiilRfcommController::class.java
                .getDeclaredMethod("dispatch", FiilGaia.Frame::class.java)
                .apply { isAccessible = true }
            hookAfter(method) { param ->
                val frame = param.args.firstOrNull() as? FiilGaia.Frame ?: return@hookAfter
                confirmedAutoGameMode(frame)?.let(::onAutoGameLowLatencyConfirmed)
            }
        }
    }

    private fun installAutoGameHook(label: String, install: () -> Unit) {
        runCatching(install).onFailure { logW("auto-game $label hook skipped", it) }
    }

    /**
     * Reads the remote preference every time. The action's extras are intentionally ignored:
     * only [ConfigManager.autoGameMode] after [refreshConfigFromPrefs] is authoritative.
     */
    private fun refreshAutoGameModeConfig() {
        refreshConfigFromPrefs()
        val enabled = ConfigManager.autoGameMode()
        synchronized(autoGameLock) {
            val wasEnabled = autoGameConfigEnabled
            autoGameConfigEnabled = enabled
            if (!enabled) {
                autoGameGeneration++
                cancelAutoGameTasksLocked()
                autoGameEnabled = false
                autoGameConnectionActive = false
                autoGameManualSession = false
                autoGameAwaitingMode = null
                // setEnabled(false) is intentionally idempotent and clears a stale baseline even
                // when this process already believed the feature was disabled.
                autoGameRuntime.setEnabled(false)
                autoGameLastUsageEventAt = 0L
                autoGameUsageEventsAtCursor.clear()
                logI("auto-game disabled")
                return
            }

            // An unchanged enabled preference must not revoke AUTO ownership or cancel an
            // in-flight confirmation merely because another setting changed.
            if (wasEnabled) return

            // A new enable is a session boundary: do not reuse a prior connection's manual
            // ownership, foreground cursor, or low-latency confirmation.
            autoGameRuntime.setEnabled(false)
            autoGameManualSession = false
            autoGameAwaitingMode = null
            autoGameLastUsageEventAt = 0L
            autoGameUsageEventsAtCursor.clear()
            autoGameRuntime.setEnabled(true)
            autoGameEnabled = true
            autoGameConnectionActive = FiilRfcommController.isConnected
            val generation = ++autoGameGeneration
            cancelAutoGameTasksLocked()
            if (autoGameConnectionActive) startAutoGameWorkLocked(generation)
            logI("auto-game enabled connected=$autoGameConnectionActive")
        }
    }

    /** Marks all manual low-latency ingress before writing its corresponding RFCOMM command. */
    private fun issueManualLowLatency(mode: FIILProtocol.LowLatencyMode) {
        synchronized(autoGameLock) {
            autoGameGeneration++
            cancelAutoGameTasksLocked()
            autoGameManualSession = true
            autoGameAwaitingMode = null
            autoGameRuntime.markManualAction()
            // Keep the ownership transition and the protocol write in one critical section so a
            // queued auto task cannot slip a write between them.
            FIILProtocol.setLowLatencyMode(mode)
        }
    }

    private fun onAutoGameDisconnected() {
        synchronized(autoGameLock) {
            autoGameGeneration++
            cancelAutoGameTasksLocked()
            autoGameRuntime.onDisconnected()
            autoGameEnabled = false
            autoGameConnectionActive = false
            autoGameManualSession = false
            autoGameAwaitingMode = null
            autoGameLastUsageEventAt = 0L
            autoGameUsageEventsAtCursor.clear()
        }
    }

    /**
     * 新连接建立时启动自动任务。
     *
     * 只在「自动游戏模式」已启用时动作；真正下发命令仍要等耳机回报档位，
     * 所以这里启动的是「先读」的探测链，不是写入。
     */
    private fun onAutoGameConnected() {
        synchronized(autoGameLock) {
            if (!autoGameConfigEnabled) return
            autoGameGeneration++
            cancelAutoGameTasksLocked()
            autoGameRuntime.onDisconnected()
            autoGameManualSession = false
            autoGameAwaitingMode = null
            autoGameLastUsageEventAt = 0L
            autoGameUsageEventsAtCursor.clear()
            autoGameEnabled = true
            autoGameConnectionActive = true
            autoGameRuntime.setEnabled(true)
            startAutoGameWorkLocked(autoGameGeneration)
        }
    }

    private fun confirmedAutoGameMode(frame: FiilMate.Frame): AutoGameModePolicy.LowLatency? {
        val wire = when {
            frame.classifyId == FiilMate.Cid.QUERY &&
                frame.commandId == FiilMate.Query.LOW_LATENCY && frame.payload.isNotEmpty() ->
                frame.byteAt(0, -1)
            frame.classifyId == FiilMate.Cid.NOTIFY &&
                frame.commandId == FiilMate.Notify.LOW_LATENCY && frame.payload.isNotEmpty() ->
                frame.byteAt(0, -1)
            else -> return null
        }
        return lowLatencyFromWire(wire)
    }

    private fun confirmedAutoGameMode(frame: FiilGaia.Frame): AutoGameModePolicy.LowLatency? {
        if (frame.command != FiilCommands.GET_LOW_LATENCY || !frame.isAck || frame.payload.size <= 1) {
            return null
        }
        return lowLatencyFromWire(FiilGaia.byteAt(frame, 1, -1))
    }

    private fun lowLatencyFromWire(wire: Int): AutoGameModePolicy.LowLatency? = when (wire) {
        0 -> AutoGameModePolicy.LowLatency.MUSIC
        1 -> AutoGameModePolicy.LowLatency.GAME
        2 -> AutoGameModePolicy.LowLatency.VIDEO
        else -> null
    }

    /** Reader callbacks enqueue this work; they never touch UsageStats or issue socket writes. */
    private fun onAutoGameLowLatencyConfirmed(mode: AutoGameModePolicy.LowLatency) {
        autoGameHandler.post {
            synchronized(autoGameLock) {
                if (!autoGameConfigEnabled || !FiilRfcommController.isConnected) return@synchronized
                autoGameConnectionActive = true
                if (!autoGameEnabled) {
                    autoGameRuntime.setEnabled(true)
                    autoGameEnabled = true
                }
                if (autoGameManualSession) return@synchronized

                val generation = autoGameGeneration
                if (autoGameAwaitingMode == mode) autoGameAwaitingMode = null
                issueAutoGameCommandLocked(
                    autoGameRuntime.onConfirmedLowLatency(mode),
                    generation,
                )
                if (autoGameUsagePoll == null) scheduleAutoGameUsagePollLocked(generation, 0L)
                if (!autoGameRuntime.hasConfirmedBaseline() || autoGameAwaitingMode != null) {
                    scheduleAutoGameLatencyProbeLocked(generation, 0)
                } else {
                    autoGameLatencyProbe?.let(autoGameHandler::removeCallbacks)
                    autoGameLatencyProbe = null
                    autoGameLatencyProbeAttempts = 0
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 自动游戏模式的运行时（全部在 autoGameLock 内调用）
    // ------------------------------------------------------------------

    /**
     * 取消所有待执行的自动任务。
     *
     * 必须在 [autoGameLock] 内调用：调用方已经持有锁，因此这里不改任何
     * 「是否启用」类状态，只撤回调并清计数，避免与调度中的 runnable 交叉。
     */
    private fun cancelAutoGameTasksLocked() {
        autoGameUsagePoll?.let(autoGameHandler::removeCallbacks)
        autoGameUsagePoll = null
        autoGameLatencyProbe?.let(autoGameHandler::removeCallbacks)
        autoGameLatencyProbe = null
        autoGameLatencyProbeAttempts = 0
    }

    /**
     * 启动一个连接会话的自动任务：先探一次耳机当前档位。
     *
     * 不直接开始轮询前台应用 —— 因为自动化必须等到「耳机真实回报过一次档位」
     * 才能判断基线（[AutoGameModeRuntime.hasConfirmedBaseline]）。在拿到基线前的
     * 写入可能把用户自己选的游戏模式误改回音乐，所以第一步永远是读。
     */
    private fun startAutoGameWorkLocked(generation: Long) {
        if (generation != autoGameGeneration) return
        scheduleAutoGameLatencyProbeLocked(generation, 0L)
    }

    /**
     * 下发一条自动低延迟命令。
     *
     * [mode] 为 null 表示策略判定「不该动」，此时什么都不发。
     * 该函数只能在 [autoGameLock] 内调用，且必须是 [autoGameHandler] 线程
     * （真正发字节会阻塞，蓝牙进程的主线程不能被占）。
     */
    private fun issueAutoGameCommandLocked(
        mode: AutoGameModePolicy.LowLatency?,
        generation: Long,
    ) {
        if (mode == null) return
        if (generation != autoGameGeneration) return
        if (autoGameManualSession) return
        // 已经是目标档位就不要重复下命令 —— 反复重发会把耳机切档的提示音刷屏。
        if (FiilRfcommController.lowLatencyLevel == mode.wireValueOf()) return
        autoGameAwaitingMode = mode
        RfcommLog.i(
            hostContext,
            TAG,
            "auto-game set low-latency ${mode.name} (wire=${mode.wireValueOf()})",
        )
        FIILProtocol.setLowLatencyMode(
            when (mode) {
                AutoGameModePolicy.LowLatency.MUSIC -> FIILProtocol.LowLatencyMode.MUSIC
                AutoGameModePolicy.LowLatency.GAME -> FIILProtocol.LowLatencyMode.GAME
                AutoGameModePolicy.LowLatency.VIDEO -> FIILProtocol.LowLatencyMode.VIDEO
            },
        )
    }

    /** 策略层的三态与线缆值一一对应；Mate 只有二态，VIDEO 在协议层会降级成 GAME。 */
    private fun AutoGameModePolicy.LowLatency.wireValueOf(): Int = when (this) {
        AutoGameModePolicy.LowLatency.MUSIC -> 0
        AutoGameModePolicy.LowLatency.GAME -> 1
        AutoGameModePolicy.LowLatency.VIDEO -> 2
    }

    /**
     * 安排下一次「前台应用分类」轮询。
     *
     * 轮询跑在 [autoGameHandler]，每次先校验 generation，连接重建后旧任务自动作废。
     */
    private fun scheduleAutoGameUsagePollLocked(generation: Long, delayMs: Long) {
        autoGameUsagePoll?.let(autoGameHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                autoGameHandler.post {
                    val classification = classifyForegroundApp()
                    synchronized(autoGameLock) {
                        if (generation != autoGameGeneration) return@synchronized
                        if (!autoGameConfigEnabled || !autoGameConnectionActive) return@synchronized
                        if (autoGameManualSession) return@synchronized
                        val command = autoGameRuntime.onForegroundClassification(classification)
                        issueAutoGameCommandLocked(command, generation)
                        if (classification != AutoGameModePolicy.Classification.UNKNOWN) {
                            autoGameUsagePoll = this
                            autoGameHandler.postDelayed(this, AUTO_GAME_POLL_INTERVAL_MS)
                        } else {
                            // 拿不到前台应用时不要停摆：下一次重新试，但拉长间隔，
                            // 免得在 UsageStats 不可用的机型上每 3 秒白跑一次。
                            autoGameUsagePoll = this
                            autoGameHandler.postDelayed(this, AUTO_GAME_POLL_INTERVAL_MS * 5)
                        }
                    }
                }
            }
        }
        autoGameUsagePoll = runnable
        autoGameHandler.postDelayed(runnable, delayMs)
    }

    /**
     * 安排一次「耳机低延迟档位」回读。
     *
     * 只有耳机真的回报过档位，[AutoGameModeRuntime] 才会建立基线并允许自动写入；
     * 因此这里带次数上限，避免耳机一直不回包时无限轮询。
     */
    private fun scheduleAutoGameLatencyProbeLocked(generation: Long, delayMs: Long) {
        autoGameLatencyProbe?.let(autoGameHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                autoGameHandler.post {
                    synchronized(autoGameLock) {
                        if (generation != autoGameGeneration) return@synchronized
                        if (!autoGameConfigEnabled || !FiilRfcommController.isConnected) {
                            return@synchronized
                        }
                        if (autoGameLatencyProbeAttempts >= AUTO_GAME_LATENCY_PROBE_MAX) {
                            RfcommLog.w(
                                hostContext,
                                TAG,
                                "auto-game gave up probing low-latency after " +
                                    "$autoGameLatencyProbeAttempts attempts",
                            )
                            autoGameLatencyProbe = null
                            return@synchronized
                        }
                        autoGameLatencyProbeAttempts++
                        FIILProtocol.requestLowLatency()
                        autoGameLatencyProbe = this
                        autoGameHandler.postDelayed(this, AUTO_GAME_LATENCY_PROBE_INTERVAL_MS)
                    }
                }
            }
        }
        autoGameLatencyProbe = runnable
        autoGameHandler.postDelayed(runnable, delayMs)
    }

    /**
     * 判定当前前台应用是否属于游戏。
     *
     * 只读 UsageStats，**任何不确定都返回 UNKNOWN**（fail closed）：拿不到 UsageStatsManager、
     * 没有授权、事件为空、包名解析失败、category 未声明 —— 一律 UNKNOWN，
     * 策略层对 UNKNOWN 不做任何动作，因此不会误切档位。
     */
    private fun classifyForegroundApp(): AutoGameModePolicy.Classification {
        val ctx = hostContext ?: return AutoGameModePolicy.Classification.UNKNOWN
        return runCatching {
            val manager = ctx.getSystemService(UsageStatsManager::class.java)
                ?: return AutoGameModePolicy.Classification.UNKNOWN
            val now = System.currentTimeMillis()
            val since = now - AUTO_GAME_USAGE_LOOKBACK_MS
            val events = manager.queryEvents(since, now) ?: return AutoGameModePolicy.Classification.UNKNOWN
            var lastForegroundPackage: String? = null
            var lastForegroundAt = 0L
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.eventType != UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    continue
                }
                // 只关心「当前」前台：取窗口内时间戳最新的那条前台事件即可。
                if (event.timeStamp < lastForegroundAt) continue
                lastForegroundAt = event.timeStamp
                lastForegroundPackage = event.packageName
            }
            if (lastForegroundPackage == null) return AutoGameModePolicy.Classification.UNKNOWN
            if (lastForegroundAt > autoGameLastUsageEventAt) {
                autoGameLastUsageEventAt = lastForegroundAt
            }
            categoryOf(ctx, lastForegroundPackage)
        }.getOrElse {
            logW("foreground classification failed", it)
            AutoGameModePolicy.Classification.UNKNOWN
        }
    }

    /** 读 [ApplicationInfo] 判定类别；解析不到包信息时按 UNKNOWN 处理。 */
    private fun categoryOf(ctx: Context, packageName: String): AutoGameModePolicy.Classification =
        runCatching {
            val info = ctx.packageManager.getApplicationInfo(packageName, 0)
            val isGame = info.category == ApplicationInfo.CATEGORY_GAME ||
                (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            if (isGame) {
                AutoGameModePolicy.Classification.GAME
            } else {
                AutoGameModePolicy.Classification.NON_GAME
            }
        }.getOrElse {
            // Android 11+ 的包可见性限制会让蓝牙进程查不到第三方应用的 ApplicationInfo。
            // 这种情况下不能猜「不是游戏」，只能如实返回 UNKNOWN。
            AutoGameModePolicy.Classification.UNKNOWN
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
                            logI("FIIL device connected: ${FiilConfig.maskAddress(device.address)} ${device.name}")
                            FiilRfcommController.start(ctx)
                            // A2DP 重新连上是「用户级」的真实事件（出仓自动回连、
                            // 用户在系统设置里手动连接），要解除上一条
                            // 「用户点过断开」的封锁，否则这里会被 connect() 挡掉。
                            FiilRfcommController.onExternalConnectEvent()
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
            // 不认识上面那套 io.github.fnkg9527.fiilpods.action.*。两套都要接，否则界面点了没反应。
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
            // ---- 模块内日志通道（v2 新增）----
            // 调试页一进来就发 CONNECT 打开实时推送，退出时发 DISCONNECT。
            // 这三个 action 不注册的话，用户在「RFCOMM 调试」页永远只能看到空白，
            // 等于日志设施白做 —— 之前就是漏了这一步。
            addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
            addAction(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
            addAction(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
            // 调试页的「发送」框：直接把用户输入的十六进制发到耳机，
            // 用来验证某条指令耳机到底认不认（不必改代码重装）。
            addAction(OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND)
            // ---- LDAC 编解码器开关 ----
            // UI 里那个 LDAC 开关不是耳机侧命令，而是必须由**蓝牙进程**反射
            // `A2dpService.setCodecConfigPreference()/setUserCodecStatus()` 才能改。
            // 模块自己的 App 进程没有这个权限、也拿不到 A2dpService 实例，
            // 所以 UI 只是把目标状态广播过来，真正的下发在这里。
            addAction(OppoPodsAction.ACTION_LDAC_SET)
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
                            // 「模块服务是否在线」必须**每次被问都回一次**。
                            //
                            // UI 侧 `MainUI.kt:461` 的 LaunchedEffect 用一个 75 秒窗口判断
                            // `bluetoothServiceResponsive`：超时就把首页卡片打成琥珀色的
                            // 「模块服务未响应」（`HomePage.kt:177`）。
                            // 而这个字段只有收到 ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE 才会置真。
                            //
                            // 历史缺陷：`announceAlive()` 只在 AdapterService.onCreate 时发一次
                            // （即蓝牙进程启动那一刻）。如果 App 是在那之后才被打开的——
                            // 最常见的情况——UI 一条 ALIVE 都收不到，于是「功能全好、首页却报未响应」。
                            // 对照 OppoPods 的实现（`refs/OppoPods/.../hook/HeadsetStateDispatcher.kt:62-68`）：
                            // 它在 UI_INIT / REFRESH_STATUS 分支里就是**原样回一发**。
                            announceAlive(this@BluetoothProcessHook.hostContext ?: return@runCatching)
                            // 界面主动来问 → 先强制全量重发一次，再顺手补连接
                            FiilRfcommController.publishState(force = true)
                            // LDAC 状态也必须在这里补一次。
                            //
                            // `FiilCodec.publishState` 只在「编码器状态真的变了」时被系统回调
                            // 顺带触发，而用户点开耳机详情页的**第一次**往往什么都没变过，
                            // 于是 UI 收不到 `ACTION_PODS_LDAC_CHANGED`，`ldacState` 一直是
                            // 初始值 -1，`PodDetailPage` 里 `if (ldacState >= 0)` 不成立，
                            // 整个 LDAC 开关**渲染都不渲染** —— 用户看到的就是「找不到入口」。
                            // 这里跟着 UI 的询问主动回读一次，状态就有着落了。
                            runCatching {
                                // ⚠️ 第三个参数必须是本 hook 自己的 `classLoader`
                                // （= LSPosed 给的 `param.defaultClassLoader`，即
                                // com.android.bluetooth 这个 APK 的 PathClassLoader），
                                // **不能**写 `device.javaClass.classLoader`。
                                //
                                // 原因：`BluetoothDevice` 是 framework 类，由 boot
                                // classloader 加载，`BluetoothDevice.class.getClassLoader()`
                                // 返回 **null**。而 `com.android.bluetooth.a2dp.A2dpService`
                                // / `btservice.AdapterService` 是 com.android.bluetooth
                                // 这个 APK 里的类，boot classloader 根本看不到它们。
                                // 传进去之后 `Class.forName(..., false, cl)` 必抛
                                // ClassNotFoundException，`a2dpServiceOrNull()` 返回 null，
                                // `query()` 返回 null，`publishState` 发出 known=false，
                                // UI 把 ldacState 落成 -1 —— LDAC 开关**永远不渲染**，
                                // 也就是 Bug 1 根本没被修好。
                                //
                                // 本文件里 `enumerateA2dpConnected()` / `a2dpServiceInstance()`
                                // 早就在用 `classLoader` 反射同一个类且实测可用，这里统一。
                                FiilCodec.publishState(
                                    this@BluetoothProcessHook.hostContext,
                                    FiilRfcommController.connectedDevice(),
                                    classLoader,
                                )
                            }.onFailure { logW("ldac publish skipped", it) }
                            // 耳机侧状态也要补一次（v2.2.2）。
                            //
                            // 上面 `FiilCodec.publishState` 查的是**手机 A2DP 视角**：
                            // 耳机没开 LDAC 时它只会返回 known=false。而详情页需要知道
                            // 「耳机自己开没开」（cid=39 cmdId=143）与「是否已解锁」（142），
                            // 才能显示开关的初值和提示文案。这里跟 UI 的询问一起去读。
                            runCatching { FIILProtocol.requestLdacState() }
                                .onFailure { logW("ldac query skipped", it) }
                            if (!FiilRfcommController.isConnected) {
                                // 这里是 UI 每 30s 一次的 UI_INIT 心跳顺手补连。
                                // connect() 内部会拒绝「冷却期内」与「用户刚点过断开」
                                // 的自动连接，所以不需要在这里额外判条件 —— 但**必须**
                                // 走 connect() 而不是绕过它，否则刹车形同虚设。
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
                        FiilAction.ACTION_CONFIG_CHANGED -> {
                            // 用户在设置页改了配置（超级岛模式 / 通知开关 / 伪装耳机型号）
                            // → 必须立刻重读远端 prefs。仅靠 HookContext.attach 里注册的
                            // OnSharedPreferenceChangeListener 不够：LSPosed 的远端
                            // SharedPreferences 在部分版本上只有「同一进程内写」才回调，
                            // App 跨进程改时监听器不触发，表现为「设置改了要重启手机才生效」。
                            // refreshAutoGameModeConfig 内部会先 refreshConfigFromPrefs()，
                            // 再按最新的「自动游戏模式」开关建立/拆除会话。
                            refreshAutoGameModeConfig()
                            logI("config changed")
                        }
                        // ---- OppoPods 兼容契约（UI 层是原封移植的） ----
                        OppoPodsAction.ACTION_ANC_SELECT -> {
                            // UI 送的是 FIIL MAF 线值 0~4
                            FiilRfcommController.setAnc(intent.getIntExtra("status", 0))
                        }
                        OppoPodsAction.ACTION_CYCLE_ANC -> {
                            // 循环的是**语义**三态（关 → 降噪 → 通透 → 关），不是线值 +1。
                            // 旧实现 `(ancMode + 1) % 3` 在 GAIA 机型上会从「监听」跳到
                            // 「关闭」（GAIA raw 2=监听、3=开启），语义完全错位。
                            val dialect = ancDialectForEarType(FiilRfcommController.earType)
                            FiilRfcommController.setAnc(ancCycleWire(dialect, FiilRfcommController.ancMode))
                        }
                        OppoPodsAction.ACTION_GAME_MODE_SET -> {
                            // 游戏模式 ↔ 低延迟：开 → GAME(1)，关 → MUSIC(0)
                            val on = intent.getBooleanExtra("enabled", false)
                            // 用户点开关是明确的手动意图：先占住归属，再发命令，
                            // 否则自动游戏模式可能在下一秒把档位改回去。
                            issueManualLowLatency(
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
                            issueManualLowLatency(
                                FIILProtocol.LowLatencyMode.fromWire(
                                    intent.getIntExtra(OppoPodsAction.EXTRA_LOW_LATENCY_MODE, 0),
                                ),
                            )
                        }
                        OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                            @Suppress("DEPRECATION")
                            val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>("device")
                            // 用户点「连接」是明确的重试意图，必须穿透
                            // FiilRfcommController 里的失败计数闸门与冷却期，
                            // 否则刹车一旦触发，用户在界面上怎么点都连不上。
                            device?.let { FiilRfcommController.connectUserInitiated(it) }
                        }
                        OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                            FiilRfcommController.disconnect("user-request")
                        }
                        // ---- 模块内日志通道（v2）----
                        // 用户进「RFCOMM 调试」页 → 打开实时推送并补发历史；
                        // 退出 → 关掉，避免长期广播唤醒 UI 进程白耗电。
                        OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT -> {
                            RfcommLog.setEnabled(true, context ?: hostContext)
                            // 补一条当前链路快照，用户一进页面就知道到底连没连上。
                            RfcommLog.i(
                                context ?: hostContext, TAG,
                                "debug page attached: connected=${FiilRfcommController.isConnected} " +
                                    "gatt=${FiilRfcommController.usingGatt} " +
                                    "earType=${FiilRfcommController.earType} " +
                                    "mate=${FiilRfcommController.isMateDialect}",
                            )
                        }
                        OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT -> {
                            RfcommLog.setEnabled(false)
                        }
                        OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR -> {
                            RfcommLog.clear(context ?: hostContext)
                        }
                        // 调试页「发送」框：用户输入什么十六进制就发什么，
                        // 用来验证某条指令耳机认不认（不必改代码重装）。
                        OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND -> {
                            val hex = intent.getStringExtra("hex").orEmpty()
                            val ok = FiilRfcommController.sendRawHex(hex)
                            if (!ok) {
                                RfcommLog.w(
                                    context ?: hostContext, TAG,
                                    "debug send rejected: hex=$hex connected=${FiilRfcommController.isConnected}",
                                )
                            }
                        }
                        // ---- LDAC 编解码器开关 ----
                        // 入参是用户想要的最终状态（true=启用 LDAC）。
                        // 下发失败时仍然要 publishState 回读一次，让 UI 把开关弹回真实状态，
                        // 而不是停在用户点下去的位置上骗人。
                        OppoPodsAction.ACTION_LDAC_SET -> {
                            val want = intent.getBooleanExtra(OppoPodsAction.EXTRA_LDAC_ENABLED, false)
                            // ---- 双管齐下 ----
                            //
                            // FIIL 的 LDAC 有**两个层面**，缺一不可：
                            //
                            // ① 耳机侧（真开关）：RFCOMM cid=1 cmdId=13 写 / cid=39 cmdId=143 读。
                            //    耳机自己不开 LDAC，手机这条链路就永远是 AAC/SBC —— 这才是
                            //    用户报「找不到 LDAC 开关」的根源：以前只做了 ②，耳机端没人
                            //    去打开它，于是 A2DP 永远不声明 LDAC，开关永远不显示。
                            //
                            // ② 手机侧（协商结果）：反射 A2dpService 把编码器偏好改成 LDAC。
                            //    耳机侧开了之后仍需要这一步，A2DP 才会真的切过去。
                            //
                            // 顺序很重要：先让耳机开，再改手机偏好。反过来的话手机侧改完
                            // 时耳机还不支持，`setCodecConfigPreference` 会被系统拒掉。
                            runCatching {
                                RfcommLog.i(context ?: hostContext, TAG, "ldac headset-side -> want=$want")
                                FIILProtocol.setLdacSwitch(want)
                            }.onFailure {
                                RfcommLog.w(
                                    context ?: hostContext, TAG,
                                    "ldac headset-side set failed: ${it.message}",
                                )
                            }
                            val device = FiilRfcommController.connectedDevice()
                            if (device == null) {
                                RfcommLog.w(
                                    context ?: hostContext, TAG,
                                    "ldac set ignored: no connected device",
                                )
                                // 关键：这里也必须回读一次状态。
                                //
                                // UI 那边 `setLdacEnabled` 是**乐观更新**（点下去立刻把开关
                                // 拨到目标位置），真值完全依赖蓝牙进程回推的
                                // `ACTION_PODS_LDAC_CHANGED`。如果这条分支只打日志就返回，
                                // 一次「离线时误点」就会让开关永久停在用户点的那一侧：
                                // 用户以为 LDAC 开了，实际上耳机根本没在跑 LDAC，
                                // 而且再怎么进页面都不会被纠正。
                                // publishState(null) 会发出 known=false，UI 收到后保持
                                // ldacState 的原值但 ldacKnown=false，摘要显示「读取中」而不是骗人。
                                FiilCodec.publishState(this@BluetoothProcessHook.hostContext, null, null)
                            } else {
                                // 同 UI_INIT 分支：必须是 hook 自己的 `classLoader`。
                                // `device.javaClass` 是 BluetoothDevice（framework 类，
                                // 由 boot classloader 加载），它的 classLoader 是 null，
                                // 用 null 去 forName("com.android.bluetooth.a2dp.A2dpService")
                                // 必失败 → setLdac 直接返回 false → 开关点了没反应。
                                val cl = classLoader
                                val ok = FiilCodec.setLdac(device, want, cl)
                                RfcommLog.i(
                                    context ?: hostContext, TAG,
                                    "ldac set -> want=$want ok=$ok",
                                )
                                // 反射改编码器配置会被系统拒（部分机型需要用户在开发者选项里
                                // 打开「蓝牙音频编解码器」才允许第三方进程改）。这时候把用户
                                // 送到系统编码器页面，总比默默失败好。
                                //
                                // 注意：不能在这里直接 startActivity。本 hook 跑在
                                // com.android.bluetooth（后台进程），Android 10+ 会静默丢弃
                                // 后台进程的 Activity 启动 —— 用户点了没反应，比不跳更糟。
                                // 改成发广播，由前台运行的模块 App 去拉起页面。
                                if (!ok) {
                                    val ctx = this@BluetoothProcessHook.hostContext
                                    if (ctx != null) FiilCodec.requestOpenCodecSettings(ctx)
                                }
                                FiilCodec.publishState(
                                    this@BluetoothProcessHook.hostContext,
                                    device,
                                    cl,
                                )
                            }
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
                val kindName = intent.getStringExtra(FiilAction.EXTRA_ADAPTIVE_KIND)
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
                issueManualLowLatency(
                    if (on) FIILProtocol.LowLatencyMode.GAME else FIILProtocol.LowLatencyMode.MUSIC
                )
            }
            "lowLatencyMode" -> {
                // 传的是协议线值（0=音乐 / 1=游戏 / 2=视频），不是枚举序号
                val wire = intent.getIntExtra(FiilAction.EXTRA_VALUE, 0)
                issueManualLowLatency(FIILProtocol.LowLatencyMode.fromWire(wire))
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
                    // 同 ACTION_CONNECT_POD_REQUEST：广播命令也是用户显式动作。
                    ?.let { FiilRfcommController.connectUserInitiated(it) }
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
