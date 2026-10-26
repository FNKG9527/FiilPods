package moe.chenxy.fiilpods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * FIIL 耳机的 **BLE GATT** 传输层（Mate 协议族，F045~F053）。
 *
 * ## 为什么需要它
 *
 * 传统上 FIIL 耳机走经典蓝牙 SPP（RFCOMM）。但实测 FIIL Key Max
 * （`B0:F1:AA:BB:CC:DD`，固件 3.0.1.1）发现：
 *
 * 1. **标准 SPP（`00001101-...`）能 `connect()` 成功，但耳机一声不响**
 *    —— 所有查询零回包，看起来像「已连接但没数据」。
 * 2. **自定义 UUID（`d52b47bc-2dea-42c4-b26d-20252fceeeb3`）能连上也能收到回包**，
 *    但 Android 侧 RFCOMM 会反复被耳机踢掉（`scn:6 opened=21 closed=0 leaked=21`）。
 * 3. **BLE GATT 通道稳定、且 Mate 帧格式与 SPP 完全一致**
 *    —— 这是本文件存在的理由。
 *
 * ## 协议参数（PC 端 Bleak 直连实测确认）
 *
 * | 项 | 值 |
 * |---|---|
 * | 写入特征 | `0000ff17-0000-1000-8000-00805f9b34fb`（Write Without Response） |
 * | 通知特征 | `0000ff18-0000-1000-8000-00805f9b34fb`（Notify） |
 * | 帧格式 | 与 SPP 完全相同，见 [FiilMate.buildFrame] |
 * | 握手 | 必须先发 `setUnlock`（cid=39 / cmd=0xFF / payload `02 34`），否则耳机静默 |
 *
 * 通知开关（CCCD `00002902-...`）实测**不写也能收到通知**——小米/Android 的
 * `setCharacteristicNotification(true)` 已经足够；但为了兼容其他 ROM，这里仍尝试写。
 *
 * ## 线程模型
 *
 * 所有 GATT 回调都在 binder 线程；写入走一个独立写线程 + 队列，
 * 因为 `writeCharacteristic` 在 Android 上必须串行（并发会返回 false）。
 */
object FiilGatt {

    private const val TAG = "FiilPods-GATT"

    /** 写入特征：App → 耳机。 */
    val CHAR_WRITE: UUID = UUID.fromString("0000ff17-0000-1000-8000-00805F9B34FB")

    /** 通知特征：耳机 → App。 */
    val CHAR_NOTIFY: UUID = UUID.fromString("0000ff18-0000-1000-8000-00805F9B34FB")

    /** 标准 CCCD，用于打开 Notify。 */
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val WRITE_GAP_MS = 25L
    private const val CONNECT_TIMEOUT_MS = 12_000L

    // ------------------------------------------------------------------
    // 对外状态（与 FiilRfcommController 的对外字段同构）
    // ------------------------------------------------------------------

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var connecting: Boolean = false
        private set

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var stopping: Boolean = false

    private val writeQueue = LinkedBlockingQueue<ByteArray>(64)

    /** 收到一帧时回调（在 binder 线程，调用方需自行切线程）。 */
    @Volatile
    var onFrame: ((FiilMate.Frame) -> Unit)? = null

    /** 连接状态变化（true=已连上可用，false=断开）。 */
    @Volatile
    var onStateChanged: ((Boolean) -> Unit)? = null

    /** 收到任意字节（给静默探测用）。 */
    @Volatile
    var onBytes: (() -> Unit)? = null

    // 收包缓冲：GATT 通知可能把一帧拆成多包，也可能一包多帧。
    private val rxBuf = ByteArray(1024)
    private var rxLen = 0

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /**
     * 连接一台设备。**异步**，结果通过 [onStateChanged] 回调。
     *
     * @return false 表示参数/环境不允许（蓝牙关、没有 adapter）；true 只代表「已发起」。
     */
    fun connect(context: Context, device: BluetoothDevice): Boolean {
        appContext = context.applicationContext
        if (isConnected || connecting) {
            Log.i(TAG, "connect skipped: connected=$isConnected connecting=$connecting")
            return false
        }
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
        }.getOrNull()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "connect aborted: adapter unavailable")
            return false
        }

        connecting = true
        stopping = false
        rxLen = 0
        Log.i(TAG, "gatt connecting to ${device.address}")

        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
        if (g == null) {
            connecting = false
            Log.w(TAG, "connectGatt returned null")
            return false
        }
        gatt = g
        startWriter()
        return true
    }

    /** 主动断开。 */
    fun disconnect() {
        stopping = true
        connecting = false
        val was = isConnected
        isConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        writeQueue.clear()
        rxLen = 0
        if (was) {
            Log.i(TAG, "gatt disconnected")
            runCatching { onStateChanged?.invoke(false) }
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
            if (g == null) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "gatt connected (status=$status), discovering services")
                    // 部分 ROM 上 discoverServices 需要一点延迟，否则返回 false。
                    g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "gatt disconnected (status=$status)")
                    isConnected = false
                    connecting = false
                    writeChar = null
                    runCatching { g.close() }
                    if (!stopping) runCatching { onStateChanged?.invoke(false) }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
            if (g == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed: $status")
                disconnect()
                return
            }
            var w: BluetoothGattCharacteristic? = null
            var n: BluetoothGattCharacteristic? = null
            for (svc in g.services) {
                for (ch in svc.characteristics) {
                    when (ch.uuid) {
                        CHAR_WRITE -> w = ch
                        CHAR_NOTIFY -> n = ch
                    }
                }
            }
            if (w == null || n == null) {
                Log.w(TAG, "required characteristics missing (write=${w != null} notify=${n != null})")
                // 打印一下实际的服务表，方便以后遇到新机型时排查
                for (svc in g.services) {
                    Log.w(TAG, "  service ${svc.uuid}")
                    for (ch in svc.characteristics) Log.w(TAG, "    char ${ch.uuid}")
                }
                disconnect()
                return
            }

            writeChar = w
            // 优先 Write Without Response：耳机固件对 write-with-response 常常回 GATT_BUSY
            val props = w.properties
            writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            // 打开通知
            runCatching {
                g.setCharacteristicNotification(n, true)
                val cccd = n.getDescriptor(CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "enable notify failed: ${it.message}") }

            isConnected = true
            connecting = false
            Log.i(TAG, "gatt ready (writeType=$writeType)")
            runCatching { onStateChanged?.invoke(true) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleRx(value)
        }

        // Android 12 及以下的旧签名
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            handleRx(v)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write status=$status")
            }
        }
    }

    /** 把一包通知数据塞进重组缓冲并按 [FiilMate] 切帧。 */
    @Synchronized
    private fun handleRx(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        runCatching { onBytes?.invoke() }
        if (rxLen + chunk.size > rxBuf.size) {
            Log.w(TAG, "rx overflow, reset (rxLen=$rxLen chunk=${chunk.size})")
            rxLen = 0
        }
        System.arraycopy(chunk, 0, rxBuf, rxLen, chunk.size)
        rxLen += chunk.size

        val (frames, remain) = FiilMate.drain(rxBuf, rxLen)
        // drain 已把剩余字节搬到缓冲区头部
        rxLen = remain
        if (rxLen < 0 || rxLen > rxBuf.size) rxLen = 0

        for (f in frames) {
            runCatching { onFrame?.invoke(f) }
                .onFailure { Log.w(TAG, "frame callback failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 入队一帧原始字节。 */
    fun send(bytes: ByteArray) {
        if (!isConnected) return
        if (!writeQueue.offer(bytes)) {
            writeQueue.poll()
            writeQueue.offer(bytes)
        }
    }

    /**
     * 发一帧 Mate 数据。
     *
     * 帧结构见 [FiilMate.buildFrame]；与 SPP 通道用的是同一套编码，
     * 所以 [FiilRfcommController.sendMate] 可以原样转发到本方法。
     */
    fun sendMate(classifyId: Int, commandId: Int, payload: ByteArray = ByteArray(0)) {
        send(FiilMate.buildFrame(classifyId, commandId, payload))
    }

    private fun startWriter() {
        val t = writerThread
        if (t != null && t.isAlive) return
        val thread = Thread({
            while (!stopping) {
                val bytes = try {
                    writeQueue.take()
                } catch (e: InterruptedException) {
                    break
                }
                val g = gatt ?: continue
                val ch = writeChar ?: continue
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(ch, bytes, writeType)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            ch.writeType = writeType
                            ch.value = bytes
                            g.writeCharacteristic(ch)
                        }
                    }
                    Thread.sleep(WRITE_GAP_MS)
                } catch (t2: Throwable) {
                    Log.w(TAG, "gatt write failed: ${t2.message}")
                }
            }
        }, "FiilPods-GattWriter").apply {
            isDaemon = true
            start()
        }
        writerThread = thread
    }
}
