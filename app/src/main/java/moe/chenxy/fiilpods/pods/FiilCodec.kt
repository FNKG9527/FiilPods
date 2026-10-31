package moe.chenxy.fiilpods.pods

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import moe.chenxy.fiilpods.utils.miuiStrongToast.data.OppoPodsAction

/**
 * LDAC 蓝牙编解码器的读写封装。
 *
 * ## 为什么不能用普通 App 的公开 API
 * 「切换 LDAC」在 AOSP 里走的是 `BluetoothA2dp.setCodecConfigPreference()`，
 * 参数类型 `BluetoothCodecConfig` / `BluetoothCodecStatus` 都是 `@SystemApi`，
 * 普通应用既拿不到类也过不了权限检查（`BLUETOOTH_PRIVILEGED`）。
 *
 * ## 本模块的做法（真开关，不是假开关）
 * 本模块本身就跑在 **`com.android.bluetooth` 进程内**（LSPosed scope 含该包），
 * 所以可以直接反射调用该进程里 `A2dpService` 的真实方法：
 *
 * | 用途 | 反射目标 | 源码依据（Android 17 反编译） |
 * |---|---|---|
 * | 取服务实例 | `AdapterService.sAdapterService` → `getA2dpService()` | `AdapterService.java:290` / `:1396` |
 * | 读当前编解码器 | `A2dpService.getCodecStatus(BluetoothDevice)` | `A2dpService.java:1282` |
 * | 读候选列表 | `BluetoothCodecStatus.getCodecs()` | 与 `setCodecConfigPreference` 配套 |
 * | 写编解码器 | `A2dpService.setCodecConfigPreference(BluetoothDevice, BluetoothCodecConfig)` | `A2dpService.java:1303` |
 * | 用户偏好 | `A2dpService.setUserCodecStatus(BluetoothDevice, String, int)` | `A2dpService.java:1830` |
 *
 * LDAC 的 `codecType` 常量是 **4**（`BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC`），
 * 依据 `A2dpService.java:1807 if (i == 4) str = LDAC_CODEC;` 与 `:1091 codecType == 4`。
 *
 * ## 降级策略
 * 反射失败（厂商裁剪 / 老 ROM / 非蓝牙进程）=**不显示开关**，
 * 绝不渲染一个点了没反应或状态对不上的假开关。
 * 用户仍可从「原始 Hex」页或系统蓝牙设置手动切换。
 */
object FiilCodec {

    private const val TAG = "FiilPods-Codec"

    /** `BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC`。依据 `A2dpService.java:1091/1807`。 */
    const val CODEC_TYPE_LDAC = 4

    /** `BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC`。关 LDAC 时的首选回落目标。 */
    private const val CODEC_TYPE_AAC = 2

    /** `BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC`。关 LDAC 时的最后保底。 */
    private const val CODEC_TYPE_SBC = 0

    /** `BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST`。 */
    private const val CODEC_PRIORITY_HIGHEST = 1000

    /** `A2dpService.LDAC_CODEC`（用户偏好用的名字，不是枚举值）。`A2dpService.java:158`。 */
    private const val LDAC_CODEC_NAME = "LDAC"

    /** 用户偏好状态：1 = 允许。`A2dpService.java:413/433/436`。 */
    private const val USER_CODEC_STATUS_ALLOWED = 1

    /** 上一次查询/写入的结果缓存，避免每次广播都反射一遍。 */
    @Volatile
    private var lastSupported: Boolean? = null

    // ------------------------------------------------------------------
    // 服务实例
    // ------------------------------------------------------------------

    /**
     * 反射取 `com.android.bluetooth.a2dp.A2dpService` 单例。
     *
     * 两条路，按新→旧：
     * 1. `AdapterService.sAdapterService.getA2dpService()`（Android 14+ 起 A2dpService
     *    不再是独立静态单例；Android 17 反编译见 `AdapterService.java:1396`）。
     * 2. `A2dpService.sA2dpService` 静态字段（Android 13 及更早）。
     */
    /**
     * 候选 ClassLoader，按「最可能成功」排序。
     *
     * 为什么不能只用一个：`com.android.bluetooth.a2dp.A2dpService` 与
     * `btservice.AdapterService` 都是 **com.android.bluetooth 这个 APK 里的类**，
     * 只有宿主 APK 的 `PathClassLoader` 能看到它们。
     *
     * 常见错配（本模块真实踩过）：
     *  - 传 `BluetoothDevice::class.java.classLoader` → **null**（framework 类由
     *    boot classloader 加载），`Class.forName(name, false, null)` 只用 bootstrap，
     *    必然 `ClassNotFoundException`；
     *  - 传 `ClassLoader.getSystemClassLoader()` → 同样是 bootstrap 系，看不到 APK 类。
     *
     * 所以这里把「调用方给的」「线程上下文」「模块自身」都试一遍，
     * 任何一个能加载到目标类就用它 —— 让上层不必再关心 classloader 从哪来。
     */
    private fun candidateClassLoaders(preferred: ClassLoader?): List<ClassLoader> {
        val out = ArrayList<ClassLoader>(4)
        preferred?.let { out.add(it) }
        runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let { out.add(it) }
        runCatching { FiilCodec::class.java.classLoader }.getOrNull()?.let { out.add(it) }
        runCatching { BluetoothDevice::class.java.classLoader }.getOrNull()?.let { out.add(it) }
        return out.distinct()
    }

    /** 按 [candidateClassLoaders] 顺序尝试加载，第一个成功即返回。 */
    private fun loadClassOrNull(name: String, preferred: ClassLoader?): Pair<Class<*>, ClassLoader>? {
        for (cl in candidateClassLoaders(preferred)) {
            val cls = runCatching { Class.forName(name, false, cl) }.getOrNull()
            if (cls != null) return cls to cl
        }
        return null
    }

    private fun a2dpServiceOrNull(classLoader: ClassLoader?): Any? {
        // 路线 1：AdapterService
        runCatching {
            val cl = loadClassOrNull("com.android.bluetooth.btservice.AdapterService", classLoader)
                ?.second ?: return@runCatching null
            val adapterCls = Class.forName("com.android.bluetooth.btservice.AdapterService", false, cl)
            val field = adapterCls.getDeclaredField("sAdapterService").apply { isAccessible = true }
            val adapter = field.get(null) ?: return@runCatching null
            val getA2dp = adapterCls.getMethod("getA2dpService").apply { isAccessible = true }
            val result = getA2dp.invoke(adapter)
            // 返回 Optional<A2dpService>
            val opt = result as? java.util.Optional<*>
            opt?.orElse(null)?.let { return it }
        }.onFailure { Log.d(TAG, "a2dp via AdapterService failed: ${it.message}") }

        // 路线 2：A2dpService.sA2dpService
        runCatching {
            val cl = loadClassOrNull("com.android.bluetooth.a2dp.A2dpService", classLoader)
                ?.second ?: return@runCatching null
            val a2dpCls = Class.forName("com.android.bluetooth.a2dp.A2dpService", false, cl)
            val field = a2dpCls.getDeclaredField("sA2dpService").apply { isAccessible = true }
            return field.get(null)
        }.onFailure { Log.d(TAG, "a2dp via sA2dpService failed: ${it.message}") }

        return null
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 从 `BluetoothCodecStatus` 里读当前选中的 codecType。 */
    private fun currentCodecType(status: Any): Int? = runCatching {
        val config = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
        config.javaClass.getMethod("getCodecType").invoke(config) as? Int
    }.getOrNull()

    /** 从 `BluetoothCodecStatus.getCodecs()` 里找 codecType==4 的那一项。 */
    private fun ldacCandidateOrNull(status: Any): Any? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = status.javaClass.getMethod("getCodecs").invoke(status) as? List<Any?> ?: return null
        list.firstOrNull { cfg ->
            cfg != null &&
                (cfg.javaClass.getMethod("getCodecType").invoke(cfg) as? Int) == CODEC_TYPE_LDAC
        }
    }.getOrNull()

    /**
     * 关 LDAC 时该切到哪个编解码器。
     *
     * 不能简单取「第一个非 LDAC」—— `getCodecs()` 的顺序不保证，AOSP 实测常见 SBC
     * 排在 AAC 前面，于是「关掉 LDAC」会顺带把音质砸到 SBC。这里按音质优先级挑：
     * AAC → 其余非 SBC 里 codecPriority 最高者 → SBC → 最后才是任意非 LDAC。
     */
    private fun nonLdacFallbackOrNull(status: Any): Any? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = status.javaClass.getMethod("getCodecs").invoke(status) as? List<Any?> ?: return null
        val candidates = list.filterNotNull().filter { codecTypeOf(it) != CODEC_TYPE_LDAC }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { codecTypeOf(it) == CODEC_TYPE_AAC }
            ?: candidates.filter { codecTypeOf(it) != CODEC_TYPE_SBC }
                .maxByOrNull { codecPriorityOf(it) }
            ?: candidates.firstOrNull { codecTypeOf(it) == CODEC_TYPE_SBC }
            ?: candidates.first()
    }.getOrNull()

    /** 读 `BluetoothCodecConfig.getCodecType()`；反射失败按 -1 处理（必然不等于任何真实类型）。 */
    private fun codecTypeOf(config: Any): Int = runCatching {
        config.javaClass.getMethod("getCodecType").invoke(config) as? Int ?: -1
    }.getOrDefault(-1)

    /** 读 `BluetoothCodecConfig.getCodecPriority()`；失败按最低优先。 */
    private fun codecPriorityOf(config: Any): Int = runCatching {
        config.javaClass.getMethod("getCodecPriority").invoke(config) as? Int ?: -1
    }.getOrDefault(-1)

    /**
     * 查询 LDAC 的真实可用性与开关状态。
     *
     * @return `null` = 本机/本 ROM 无法判定（不要渲染开关）；
     *         `CodecState.supported == false` = 耳机/手机组合不支持 LDAC；
     *         `CodecState.enabled` = 当前 A2DP 是否正跑在 LDAC 上。
     */
    fun query(device: BluetoothDevice?, classLoader: ClassLoader? = null): CodecState? {
        if (device == null) return null
        val a2dp = a2dpServiceOrNull(classLoader) ?: return null
        val status = runCatching {
            a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(a2dp, device)
        }.getOrNull() ?: return null

        val current = currentCodecType(status) ?: return null
        val ldacCandidate = ldacCandidateOrNull(status)
        val supported = ldacCandidate != null
        lastSupported = supported
        return CodecState(supported = supported, enabled = current == CODEC_TYPE_LDAC)
    }

    data class CodecState(val supported: Boolean, val enabled: Boolean)

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 真正切换 LDAC。
     *
     * 两步走，与 `A2dpService.java:542-543`（多设备场景的官方实现）一致：
     * ```
     * setUserCodecStatus(device, "LDAC", 1);       // 记下用户偏好
     * setCodecConfigPreference(device, ldacCfg);   // 下发配置
     * ```
     *
     * @param enable true = 切到 LDAC；false = 退回当前可用的最高优先级非 LDAC 编解码器。
     * @return true = 已成功下发；false = 前置条件不满足（会由调用方决定是否引导用户去系统页）。
     */
    fun setLdac(device: BluetoothDevice?, enable: Boolean, classLoader: ClassLoader? = null): Boolean {
        if (device == null) {
            Log.w(TAG, "setLdac: no device")
            return false
        }
        val a2dp = a2dpServiceOrNull(classLoader) ?: run {
            Log.w(TAG, "setLdac: A2dpService unavailable")
            return false
        }
        val status = runCatching {
            a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(a2dp, device)
        }.getOrNull() ?: run {
            Log.w(TAG, "setLdac: getCodecStatus returned null")
            return false
        }

        // 1) 记用户偏好 —— 某些 ROM 的 codec 选择器只认这个
        runCatching {
            a2dp.javaClass.getMethod(
                "setUserCodecStatus",
                BluetoothDevice::class.java, String::class.java, Int::class.javaPrimitiveType,
            ).invoke(
                a2dp, device, LDAC_CODEC_NAME,
                if (enable) USER_CODEC_STATUS_ALLOWED else 0,
            )
        }.onFailure { Log.d(TAG, "setUserCodecStatus failed: ${it.message}") }

        // 2) 选定目标配置
        val target = if (enable) {
            ldacCandidateOrNull(status) ?: run {
                Log.w(TAG, "setLdac: LDAC not in supported codec list")
                return false
            }
        } else {
            // 关 LDAC = 挑一个音质上更合理的非 LDAC 候选（AAC 优先），保持其余参数不变
            nonLdacFallbackOrNull(status) ?: run {
                Log.w(TAG, "setLdac: no non-LDAC codec to fall back to")
                return false
            }
        }

        // 3) 用「同一份参数的格式化副本」下发。
        //    不能直接把读回来的对象丢回去：AOSP 里 setCodecConfigPreference 期望
        //    的是「用户期望值」，部分机型对 readBack 对象里的 codecSpecific 会二次校正。
        val newConfig = runCatching { rebuildConfig(target) }.getOrNull() ?: target

        return runCatching {
            a2dp.javaClass.getMethod(
                "setCodecConfigPreference",
                BluetoothDevice::class.java, newConfig.javaClass,
            ).invoke(a2dp, device, newConfig)
            Log.i(TAG, "setLdac($enable) dispatched, codecType=${codecTypeOf(newConfig)}")
            true
        }.onFailure {
            Log.w(TAG, "setCodecConfigPreference failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    /** 用 9 参构造器重建一份 `BluetoothCodecConfig`（codec 参数沿用源对象，优先级拉到最高）。 */
    private fun rebuildConfig(src: Any): Any? = runCatching {
        val cls = src.javaClass
        // 每个 getter 都可能返回 null（不同 ROM 的字段初始化时机不同）。
        // 以前写成 `fun read(name: String): Any`，invoke 的 Any? 结果会被插入非空断言，
        // 任一项为 null 就 NPE，被外层 runCatching 吞成「重建失败」——功能上退化成
        // 用原对象下发，但真实失败原因被掩盖了。这里显式返 Any? 并在调用处判空。
        fun read(name: String): Any? = runCatching { cls.getMethod(name).invoke(src) }.getOrNull()
        val ctor = cls.constructors.firstOrNull {
            it.parameterTypes.size == 9 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return null
        ctor.newInstance(
            read("getCodecType") as? Int ?: return null,
            CODEC_PRIORITY_HIGHEST,
            read("getSampleRate") as? Int ?: return null,
            read("getBitsPerSample") as? Int ?: return null,
            read("getChannelMode") as? Int ?: return null,
            read("getCodecSpecific1") as? Long ?: 0L,
            read("getCodecSpecific2") as? Long ?: 0L,
            read("getCodecSpecific3") as? Long ?: 0L,
            read("getCodecSpecific4") as? Long ?: 0L,
        )
    }.getOrNull()

    // ------------------------------------------------------------------
    // 兜底：引导用户去系统设置
    // ------------------------------------------------------------------

    /**
     * 反射失败时的引导路径。
     *
     * ## 为什么这里改成「发广播」而不是直接 `startActivity`
     *
     * 本方法唯一的调用点是 `BluetoothProcessHook`，也就是跑在 `com.android.bluetooth`
     * 进程里。那是**后台进程**，Android 10+ 对后台进程启动 Activity 有硬性限制：
     * `startActivity` 不抛异常，但 Activity 根本不会显示 —— 用户点开 LDAC 失败后
     * 什么反应都没有，连 Toast 都可能因为进程无窗口而被丢弃。
     *
     * 正确做法是把「跳设置页」这件事交回模块 App（它在前台，不受该限制）。
     * 蓝牙进程只发 [OppoPodsAction.ACTION_OPEN_CODEC_SETTINGS]，由 `MainUI` 收到后
     * 自己拉起系统页面。
     *
     * 历史缺陷：候选里原先还有 `BluetoothCodecPreferenceController` —— 那是个
     * `PreferenceController`（设置页内部的控制器类），**不是 Activity**，`setClassName`
     * 必然抛 `ActivityNotFoundException`。已删除。
     *
     * @return 是否成功把请求交出去（不代表页面已打开）
     */
    fun requestOpenCodecSettings(context: Context): Boolean = runCatching {
        context.sendBroadcast(
            Intent(OppoPodsAction.ACTION_OPEN_CODEC_SETTINGS).apply {
                setPackage(FiilConfig.MODULE_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
        Log.i(TAG, "requested module to open codec settings")
        true
    }.onFailure { Log.w(TAG, "request open codec settings failed", it) }.getOrDefault(false)

    /**
     * 真正拉起系统「蓝牙音频编解码器」页。
     *
     * **只能在模块 App 进程（前台）调用** —— 蓝牙进程调它会被后台启动限制拦掉，
     * 那边请用 [requestOpenCodecSettings]。
     */
    fun openCodecSettings(context: Context): Boolean {
        val candidates = listOf(
            "com.android.settings" to "com.android.settings.bluetooth.BluetoothCodecActivity",
        )
        for ((pkg, cls) in candidates) {
            val ok = runCatching {
                context.startActivity(
                    Intent().apply {
                        setClassName(pkg, cls)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val fallback = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrDefault(false)
        runCatching {
            Toast.makeText(
                context,
                // 字面量而非 R.string：release 开了 shrinkResources，单点引用的字符串会被剥掉。
                    "需在系统设置中开启 LDAC",
                Toast.LENGTH_LONG,
            ).show()
        }
        return fallback
    }

    // ------------------------------------------------------------------
    // 状态广播
    // ------------------------------------------------------------------

    /**
     * 把 LDAC 状态发回模块 UI。
     *
     * 注意 `context` 在蓝牙进程里，所以必须 `setPackage(MODULE_PACKAGE)`，
     * 否则会像 v1 的 `publishState()` 那样绕回蓝牙进程自己。
     */
    fun publishState(
        context: Context?,
        device: BluetoothDevice?,
        classLoader: ClassLoader? = null,
    ) {
        val ctx = context ?: return
        val state = query(device, classLoader)
        runCatching {
            ctx.sendBroadcast(
                Intent(OppoPodsAction.ACTION_PODS_LDAC_CHANGED).apply {
                    setPackage(FiilConfig.MODULE_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(OppoPodsAction.EXTRA_LDAC_SUPPORTED, state?.supported == true)
                    putExtra(OppoPodsAction.EXTRA_LDAC_ENABLED, state?.enabled == true)
                    putExtra(OppoPodsAction.EXTRA_LDAC_KNOWN, state != null)
                }
            )
        }.onFailure { Log.w(TAG, "publishState failed", it) }
    }

    /** 诊断用：返回一段可直接贴进日志的实现能力描述。 */
    fun describe(): String = "ldac{supported=$lastSupported, api=${Build.VERSION.SDK_INT}, bt=${BluetoothAdapter.getDefaultAdapter() != null}}"
}
