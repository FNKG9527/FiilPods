# HuaweiPods 架构规格书

> 参考实现：`<repo>\refs\HuaweiPods`
> Kotlin · Jetpack Compose + MIUIX · libxposed API 102（debug 变体 API 101）· 19 个华为耳机型号
> 本文所有行号均对应该仓库当时快照；路径相对仓库根目录。
> 用途：作为新 Xposed 模块的架构模板。文中「➜ 移植要点」为给新模块的结论。

---

## 0. 目录与分层

```
app/src/main/java/moe/chenxy/huaweipods/
├── HookEntry / Api102HookEntry      hook/       Xposed 入口与包路由
├── HookContext + HookParam + Log    hook/       Hook 基类与反射工具
├── HeadsetStateDispatcher           hook/       com.android.bluetooth 的 A2DP 状态注入点
├── SettingsHeadsetHook  (167KB)     hook/       小米设置页 UI 注入（最大的文件）
├── BluetoothUpstreamHeadsetHook(71KB)hook/      上游蓝牙栈（小米/联发科）状态注入
├── MiBluetoothToastHook   (38KB)    hook/       com.xiaomi.bluetooth 弹窗
├── SmartAudioFreeClip2BridgeHook   hook/       华为智慧生活 App 桥接
├── HuaweiL2capAncController (28KB)  pods/       ★ RFCOMM 传输引擎（唯一 socket 持有者）
├── HuaweiHfpController     (115KB)  pods/       ★ 会话编排 + 状态缓存 + 广播路由
├── HuaweiAncPackets        (108行)  pods/       ★ 指令表（数据即协议）
├── HuaweiAncLevelProfile   (124行)  pods/       档位映射表（按机型隔离）
├── HuaweiRfcommResponseParser(248行)pods/       帧解析 / TLV
├── HuaweiDeviceInfoIdentity(149行)  pods/       DeviceInfo(01/07) 身份解析
├── HuaweiEqualizerCodec    (271行)  pods/       EQ 帧编解码 + CRC16/XMODEM
├── DeviceCapabilities      (12.8KB) pods/       ★ 机型枚举 + 能力位表
├── PodState                (42行)   pods/       状态词汇表
├── BatteryIslandTriggerPolicy       pods/       超级岛触发节流
├── config/  ConfigManager, DeviceRoutePrefs, NotificationPresentationPolicy, LowLatencyPrefs, PodImagePrefs
├── utils/   FocusIslandUtil, RfcommLog, ModuleResourceResolver, miuiStrongToast/
└── ui/      pages/DevicePickerPage, MainUI, Compose + MIUIX
```

`pods/` 层里按机型细分的 `HuaweiFreeBuds5/6/7/7i/Pro5/FreeClip2/...Controller.kt` 都**只**通过
`HuaweiL2capAncController.requestRawPacketOnce / sendRawPacketOnce` 收发（见
`pods/HuaweiFreeClip2Controller.kt:94,196,211,288,311`、`pods/HuaweiGestureController.kt:271,293,331,397,434,468`、
`pods/HuaweiEqualizerController.kt:28,68,96`），全仓库只有一处 `BluetoothSocket` 构造。

➜ **移植要点**：把「协议层（packets/parser）」「传输层（一个 object 持有 socket）」「会话层（一个 object 持有状态）」
「能力层（一张表）」四者彻底解耦，新机型只加表项，不碰传输层。

---

## 1. 模块启动（Bootstrap）

### 1.1 `XposedModule` 子类与包路由

`hook/HookEntry.kt`（274 行）：

```kotlin
// app/src/main/java/moe/chenxy/huaweipods/hook/HookEntry.kt:16
open class HookEntry : XposedModule() {
    private val TAG = "HuaweiPods-HookEntry"                       // :17
    private val lifecycleLock = Any()                              // :18
    private val loadedPackages = linkedMapOf<String, ClassLoader>()// :19
    private val loadedHooks = mutableListOf<LoadedHook>()          // :20
    private val strictInstall = ThreadLocal.withInitial { false }   // :21

    override fun onPackageLoaded(param: PackageLoadedParam) {       // :23
        if (!param.isFirstPackage) return                           // :24
        installHooksForPackage(param.packageName, param.defaultClassLoader)  // :25
    }
```

`onPackageLoaded` **只处理 `isFirstPackage`**（`:24`）——LSPosed 对同一进程的多次调用只会真正安装一次，
后续重复回调被丢弃，避免重复 hook 导致的原方法被二次包裹。

包路由在 `installHooksForPackage(packageName, classLoader, requireComplete = false)` `:34-69`：

```kotlin
ModuleResourceResolver.initialize(                              // :39-42
    moduleApplicationInfo = moduleApplicationInfo,
    hotReloadEnabled = apiVersion >= 102,
)
synchronized(lifecycleLock) { loadedPackages[packageName] = classLoader }  // :43-45
val previousStrict = strictInstall.get()                        // :47
strictInstall.set(requireComplete)                              // :48
try {
    BuildVariantHooks.installForPackage(this, packageName, classLoader)    // :50
    when (packageName) {                                        // :52
        "com.android.bluetooth" -> {                            // :53
            loadHook("bluetooth-headset-state", HeadsetStateDispatcher, classLoader, packageName)   // :54
            loadHook("bluetooth-upstream", BluetoothUpstreamHeadsetHook(), classLoader, packageName) // :55
        }
        "com.android.settings" ->                               // :57
            loadHook("settings-headset", SettingsHeadsetHook, classLoader, packageName)             // :58
        "com.milink.service" ->                                 // :59
            loadHook("milink-service", MiLinkServiceHook, classLoader, packageName)                 // :60
        "com.xiaomi.bluetooth" -> {                             // :61
            loadHook("mi-bluetooth-toast", MiBluetoothToastHook, classLoader, packageName)          // :62
            loadHook("bluetooth-upstream", BluetoothUpstreamHeadsetHook(), classLoader, packageName)// :63
        }
    }
} finally { strictInstall.set(previousStrict) }                 // :66-68
```

注意 `HeadsetStateDispatcher` 与 `MiLinkServiceHook` 是 **object（单例）**，而
`BluetoothUpstreamHeadsetHook()` / `SettingsHeadsetHook` / `MiBluetoothToastHook` 是 **class（new 实例）**——
object 只允许被同一个宿主进程装一次，跨进程首次加载即被分配；class 则每个进程一个实例。

`supportsPackage` `:91-96`：

```kotlin
private fun supportsPackage(packageName: String): Boolean =
    packageName == "com.android.bluetooth" ||
        packageName == "com.android.settings" ||
        packageName == "com.milink.service" ||
        packageName == "com.xiaomi.bluetooth" ||
        BuildVariantHooks.supportsPackage(packageName)
```

`BuildVariantHooks` 是变体注入点：`app/src/release/java/moe/chenxy/huaweipods/hook/BuildVariantHooks.kt:3-14`
只在 release 里挂 `com.huawei.smartaudio`（`loadHook("smart-audio-freeclip2-bridge", SmartAudioFreeClip2BridgeHook, …)` `:6-14`），
debug 变体另有自己的实现。➜ 新模块若需区分 debug 抓包/正式功能，用同样的 `src/{debug,release}/java` 同名 object 覆盖。

### 1.2 `getRemotePreferences` 与配置注入

`loadHook(namespace, hook, classLoader, packageName)` `:98-160` 是整个模块的「依赖注入点」，顺序不可乱：

```kotlin
Log.module = this                                                // :104
// API 102 may expose a read-only/temporarily unavailable proxy while the
// host process is starting.  Do not let that IPC failure crash the host.
val remotePrefs = runCatching {                                  // :107
    getRemotePreferences(ConfigManager.PREFS_NAME)               // :108
}.onFailure {
    log(android.util.Log.WARN, TAG, "get remote prefs failed for $packageName", it)  // :110
}.getOrNull() ?: if (strictInstall.get() == true) {              // :111
    throw IllegalStateException("Remote preferences unavailable for $packageName")   // :112
} else { return }                                                // :114

hook.attach(this, namespace, classLoader, packageName, remotePrefs)   // :117
LowLatencyPrefs.attachHookPreferences(remotePrefs)              // :118
HuaweiDeviceRouteResolver.init(remotePrefs)                     // :119
ConfigManager.init(remotePrefs)                                 // :124
val configListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->  // :125
    if (key == ConfigManager.PREF_KEY_CONFIG_JSON) ConfigManager.refreshFromPrefs(sp)  // :126-128
    if (DeviceRoutePrefs.isBindingKey(key)) HuaweiDeviceRouteResolver.refreshBindings()// :129-131
}
val listenerRegistered = runCatching {
    remotePrefs.registerOnSharedPreferenceChangeListener(configListener)  // :134
    true
}.getOrDefault(false)                                           // :133-138
...
try { hook.onHook() }                                           // :151-152
catch (throwable: Throwable) {                                  // :153
    synchronized(lifecycleLock) { loadedHooks.remove(loadedHook) }   // :154-156
    closeLoadedHook(loadedHook)                                 // :157
    throw throwable                                             // :158
}
```

三条硬性约束：

1. **`getRemotePreferences` 可能返回只读或临时不可用的代理**（`:105-106` 注释），必须 `runCatching` 包裹；
   `strictInstall == true`（热重载恢复路径）时才允许抛异常，否则**静默放弃该包**而不是让宿主进程崩溃。
2. `hook.attach(...)` 必须在 `onHook()` **之前**，因为 `onHook()` 里立刻要用 `findClass`（依赖 `appClassLoader`）和
   `prefs`。
3. `onHook()` 失败必须**回滚**已登记到 `loadedHooks` 的记录并 `closeLoadedHook`，再重新抛出。

`canRestorePackageHooks` `:72-89` 是热重载前的无副作用预检：

```kotlin
return runCatching {
    // 实际读取一次，不能只判断 RemotePreferences 代理对象非空。
    getRemotePreferences(ConfigManager.PREFS_NAME)
        .contains(ConfigManager.PREF_KEY_CONFIG_JSON)            // :83-84
    true
}.getOrDefault(false)                                            // :81-88
```

➜ **移植要点**：`PREFS_NAME` 必须与模块 App 侧 `context.getSharedPreferences(...)` **同名**
（`config/ConfigManager.kt:26` `const val PREFS_NAME = "huaweipods_settings"`，注释 `:25`
「LSPosed remote preferences and app preferences must use the same name.」）。
模块 App 侧通过 `HuaweiPodsApp : Application(), XposedServiceHelper.OnServiceListener` 拿到 `XposedService`
（`HuaweiPodsApp.kt:14`），`onCreate` 里 `XposedServiceHelper.registerListener(this)`（`:17`），
服务绑定后 `onServiceBind(service)`（`:22-42`）**单向**把本地状态推到 remote：

```kotlin
override fun onServiceBind(service: XposedService) {                 // :22
    xposedService = service                                          // :24
    runCatching {
        DeviceRoutePrefs.syncWithRemote(prefs = getSharedPreferences(ConfigManager.PREFS_NAME, MODE_PRIVATE), service = service)  // :26-29
        LowLatencyPrefs.syncWithRemote(...)                          // :30-33
        PodImagePrefs.syncSnapshotToRemote(...)                      // :34-37
    }.onFailure { error -> Log.w(TAG, "Unable to synchronize local pod images to remote preferences", error) }  // :38-40
    notifyListeners(service)                                         // :41
}
```

写回本身由 `ConfigManager.save(prefs, service, config)` 完成（`config/ConfigManager.kt:181-204`）：
先写本地 `prefs`，再 `service?.getRemotePreferences(PREFS_NAME)`（`:186-187`）。
`runCatching` 的理由同注入侧——LSPosed 的 `RemotePreferences` 可能是只读代理。

### 1.3 Hook 的类加载与 `XposedInterface.HookHandle`

API 101 路径 `HookEntry.kt:163-167`：

```kotlin
internal open fun installHook(
    executable: Executable,
    hookId: String,
    interceptor: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle = hook(executable).intercept { chain -> interceptor(chain) }
```

API 102 路径由 release 变体覆盖：`app/src/release/java/moe/chenxy/huaweipods/hook/Api102HookEntry.kt`（261 行）
`class Api102HookEntry : HookEntry()` `:21`，核心是 `installHook` `:196-235`：

```kotlin
@SuppressLint("XposedNewApi")
internal override fun installHook(executable, hookId, interceptor): XposedInterface.HookHandle {
    val hooker = XposedInterface.Hooker { chain -> interceptor(chain) }      // :202
    replacementHandlesById?.get(hookId)?.pollFirst()?.let { oldHandle ->     // :203-205
        return oldHandle.replaceHook(hooker)                                 // :208
    }
    return hook(executable).setId(hookId).intercept(hooker)                  // :225 / :232
}
```

即 **热重载时优先复用旧 HookHandle 并 `replaceHook`**，只有拿不到旧句柄时才新建。
`onHotReloading` `:37-84` 负责保存现场（`param.setSavedInstanceState(createHotReloadState(packageName))` `:64-69`）
并 `closeLoadedGeneration()` `:73`；`onHotReloaded` `:86-193` 重新
`installHooksForPackage(packageName, classLoader, requireComplete = true)` `:147` 并
`restoreLoadedGenerationState(savedState)` `:149`。

Hook ID 必须是**跨进程稳定**的，由 `hook/HookContext.kt:207` 的 `StableHookIdGenerator` 生成：

```kotlin
fun next(kind: String, executable: Executable): String {        // :214
    ... "HuaweiPods/$namespace/$kind/${key.sha256Prefix()}/$occurrence"   // :230
}
```

### 1.4 xposed 元数据

| 文件 | 内容 |
|---|---|
| `app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=101` / `targetApiVersion=101` / `staticScope=true` |
| `app/src/main/resources/META-INF/xposed/java_init.list` | `moe.chenxy.huaweipods.hook.HookEntry` |
| `app/src/release/resources/META-INF/xposed/module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `autoHotReload=true` |
| `app/src/release/resources/META-INF/xposed/java_init.list` | `moe.chenxy.huaweipods.hook.Api102HookEntry` |
| `app/src/{debug,release}/resources/META-INF/xposed/scope.list` | 5 行：`com.android.bluetooth`、`com.android.settings`、`com.huawei.smartaudio`、`com.milink.service`、`com.xiaomi.bluetooth` |

> `scope.list` **只存在于 `src/debug` 与 `src/release` 两个变体**，`src/main/resources/META-INF/xposed/` 下没有它
> （已核对：`src/main/resources/META-INF/xposed/` 只有 `module.prop` 与 `java_init.list`）。
> 这是有意的——LSPosed 要求「作用域清单必须存在于每个构建变体」，放在 `main` 里反而会与变体覆盖冲突。
> 新建工程若只有一个变体，把 `scope.list` 放 `src/main/resources/META-INF/xposed/` 也可以。

`staticScope=true` 表示作用域写死、不接受运行时动态加包；两套 `module.prop` 靠 `src/release/resources` 覆盖
`src/main/resources` 实现「debug 用 101 打日志、release 用 102 支持热重载」。

---

## 2. `HookContext` 基类

`hook/HookContext.kt`（339 行），`abstract class HookContext` `:19`。

### 2.1 字段与生命周期

```kotlin
abstract class HookContext {                                     // :19
    lateinit var module: XposedModule                            // :20
    lateinit var appClassLoader: ClassLoader                     // :21
    lateinit var prefs: SharedPreferences                        // :22
    lateinit var packageName: String                             // :23

    private lateinit var owner: HookEntry                        // :25
    private lateinit var hookIds: StableHookIdGenerator          // :26
    private lateinit var stateNamespace: String                  // :27
    private val closed = AtomicBoolean(false)                    // :28
    private val closeActionsLock = Any()                         // :29
    private val closeActions = mutableListOf<() -> Unit>()       // :30

    abstract fun onHook()                                        // :32
    protected open fun onClose() = Unit                           // :40
    protected open fun onCanClose(): Boolean = true               // :46
    protected open fun onSaveHotReloadState(outState: Bundle) = Unit   // :49
    protected open fun onRestoreHotReloadState(savedState: Bundle) = Unit  // :52
```

生命周期 API：

| 签名 | 行 | 语义 |
|---|---|---|
| `internal fun canClose(): Boolean` | `:54` | `!closed.get() && runCatching { onCanClose() }.getOrDefault(false)`；`onCanClose` 必须**无副作用** |
| `internal fun attach(owner: HookEntry, namespace: String, classLoader: ClassLoader, hostPackageName: String, remotePrefs: SharedPreferences)` | `:56` | 由 `HookEntry.loadHook` 调用，注入 module/loader/prefs/packageName，并初始化 `hookIds` |
| `internal fun saveHotReloadState(parent: Bundle)` | `:73` | 按 `stateNamespace` 建子 Bundle |
| `internal fun restoreHotReloadState(parent: Bundle)` | `:79` | 对应恢复 |
| `protected fun registerCloseAction(action: () -> Unit)` | `:84` | 注册需要释放的资源（Receiver、Handler callback、线程） |
| `internal fun close(): List<Throwable>` | `:100` | CAS 幂等；先 `onClose()` 再**逆序**执行 closeActions，返回收集到的异常 |
| `internal fun isClosedForTest(): Boolean` | `:114` | 测试用 |

### 2.2 反射与 Hook API（完整签名清单）

```kotlin
protected fun currentApplicationOrNull(): Application?                                    // :117
protected fun runOnMainThreadBlocking(timeoutMs: Long = DEFAULT_MAIN_THREAD_TIMEOUT_MS, action: () -> Unit): Boolean  // :125 (默认 2000L, :202)
fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()                                 // :151
fun fakeSupport(): String = ConfigManager.fakeSupport()                                   // :153
fun refreshConfig()                                                                        // :155
fun findClass(name: String): Class<*>                                                      // :159
fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method      // :161
fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*>    // :164
fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method // :167
fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*>        // :171
fun hookAfter(method: Method, block: HookParam.() -> Unit)                                 // :175
fun hookBefore(method: Method, block: HookParam.() -> Unit)                                // :182
fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit)         // :193
```

关键实现（`:159-173`）：全部走 `Class.forName(name, false, appClassLoader)`（`initialize=false`，避免宿主类的
静态初始化副作用），并统一 `isAccessible = true`：

```kotlin
fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)          // :159
fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
    findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }   // :161-162
fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
    findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
        .apply { isAccessible = true }                                                       // :167-169
```

**`hookAll` 在本仓库中不存在**（全仓库 grep 无匹配）。若新模块需要批量 hook 同名重载，
应自行在基类补一个 `hookAll(className, methodName, block)`：遍历 `declaredMethods.filter { it.name == methodName }`
逐个 `hookAfter`。这是本模板唯一缺失的 API。

`hookAfter/hookBefore/hookConstructorAfter` 的实现都做了 **closed 短路**，热重载关闭后不再执行业务回调（`:175-199`）：

```kotlin
fun hookAfter(method: Method, block: HookParam.() -> Unit) {
    owner.installHook(method, hookIds.next("after", method)) { chain ->
        val result = chain.proceed()
        if (closed.get()) result else HookParam(chain, result).apply(block).result   // :178
    }
}
fun hookBefore(method: Method, block: HookParam.() -> Unit) {
    owner.installHook(method, hookIds.next("before", method)) { chain ->
        if (closed.get()) { chain.proceed() } else {
            val param = HookParam(chain, null).apply(block)
            if (param.hasResult) param.result else chain.proceed()                   // :188
        }
    }
}
```

`HookParam` `:285-301`：

```kotlin
class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args          // :286
    val instance: Any? = chain.thisObject      // :287
    var hasResult = false; private set         // :288-289
    var result: Any? = initialResult           // :290
        set(value) { hasResult = true; field = value }   // :291-294
    fun proceedWithArgs(vararg newArgs: Any?): Any? = chain.proceed(newArgs.copyOf()).also { result = it }  // :296-300
}
```

### 2.3 顶层反射工具（不需要 HookContext 实例）

```kotlin
fun getObjectField(instance: Any?, fieldName: String): Any?              // :303
fun setObjectField(instance: Any?, fieldName: String, value: Any?)       // :315
fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any?  // :328
```

三者都**沿 superclass 链上溯**，因为 MIUI/上游栈常把字段声明在父类：

```kotlin
fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching { return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance) }
        cls = cls.superclass                                                     // :310
    }
    throw NoSuchFieldException(fieldName)                                        // :312
}
```

`callMethod` 按「方法名 + 参数个数」匹配（`:332`），不比对参数类型——因为宿主里同名同参数个数的重载极少，
而精确类型匹配在混淆后的类上极脆弱。

### 2.4 `Log` 包装

```kotlin
object Log {                                                    // :240
    @Volatile var module: XposedModule? = null                  // :241-242
    fun v/i/d/w/e(tag: String, message: String)                 // :244/:249/:254/:264/:274
    fun d/w/e(tag: String, message: String, throwable: Throwable)  // :259/:269/:279
}
```

`Log.module` 在 `HookEntry.loadHook` 第一次被赋值为 `this`（`HookEntry.kt:104`）；
级别过滤读 `ConfigManager.logLevel()`（`LOG_LEVEL_OFF/BASIC/DEBUG = 0/1/2`，`config/ConfigManager.kt:39-41`）。
`XposedModule.log(priority, tag, msg, throwable)` 是 libxposed 自带的原生日志出口（模块日志会进 LSPosed 管理器）。

---

## 3. RFCOMM / Socket 协议引擎

> 文件名 `HuaweiL2capAncController.kt` 是历史遗留：**它不使用 L2CAP，全部走 RFCOMM/SPP**。

文件：`app/src/main/java/moe/chenxy/huaweipods/pods/HuaweiL2capAncController.kt`（686 行，28.4 KB）。

### 3.1 常量与全局状态

```kotlin
@SuppressLint("MissingPermission")                              // :28
object HuaweiL2capAncController {                               // :29
    private const val TAG = "HuaweiPods-HuaweiAnc"              // :30
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 3_000L        // :31
    private const val RFCOMM_CONNECT_RETRY_DELAY_MS = 350L      // :32
    private const val RFCOMM_CONNECT_ATTEMPTS = 2               // :33
    private const val ROUTE_PROBE_CONNECT_TIMEOUT_MS = 2_000L   // :34
    private const val ROUTE_PROBE_RESPONSE_WINDOW_MS = 1_200L   // :35
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")  // :36

    private val executor = Executors.newSingleThreadExecutor()          // :38
    private val routeProbeExecutor = Executors.newSingleThreadExecutor()// :39
    private val submissionLock = Any()                                  // :40
    private val transportGeneration = HuaweiRfcommTransportGeneration() // :41
    private var socket: BluetoothSocket? = null                         // :42
    private var deviceAddress: String? = null                           // :43
    private var socketLabel: String? = null                             // :44
    private val mainHandler = Handler(Looper.getMainLooper())           // :45
    private val activeConnectSockets = ConcurrentHashMap.newKeySet<BluetoothSocket>()  // :46
    private val activeConnectThreads = ConcurrentHashMap.newKeySet<Thread>()           // :47
    private val activeOperations = AtomicInteger(0)                     // :48
    private val closing = AtomicBoolean(false)                          // :49
```

`SPP_UUID` 是 Bluetooth Serial Port 标准 UUID（`00001101-…`），华为耳机在该服务上暴露 AAM 控制通道。

### 3.2 在 `com.android.bluetooth` 进程内自建 socket

模块被注入到 `com.android.bluetooth`（system_server 之外的 `com.android.bluetooth` 进程，
其 uid 持有 `android.permission.BLUETOOTH_PRIVILEGED` / `BLUETOOTH_CONNECT`），因此可以直接调用
`BluetoothDevice.createRfcommSocketToServiceRecord()`——**这是普通 App 在 Android 12+ 上拿不到的权限**。
权限声明在 `app/src/main/AndroidManifest.xml:6-7`（`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`），
但真正生效的是宿主进程被授予的特权。

**候选连接策略** `socketCandidates(device)` `:515-537`（按顺序尝试，去重）：

```kotlin
private fun socketCandidates(device: BluetoothDevice): List<SocketCandidate> {
    val candidates = mutableListOf<SocketCandidate>()
    fun add(label: String, create: () -> BluetoothSocket) {
        if (candidates.none { it.label == label }) candidates += SocketCandidate(label, create)
    }

    add("secure-spp-$SPP_UUID")   { device.createRfcommSocketToServiceRecord(SPP_UUID) }        // :521
    add("insecure-spp-$SPP_UUID") { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) } // :522

    device.uuids.orEmpty()                                                       // :524
        .mapNotNull { it?.uuid }
        .filter { it != SPP_UUID && HuaweiRfcommServicePolicy.isControlCandidate(it) }  // :526
        .forEach { uuid ->
            add("secure-sdp-$uuid")   { device.createRfcommSocketToServiceRecord(uuid) }        // :528
            add("insecure-sdp-$uuid") { device.createInsecureRfcommSocketToServiceRecord(uuid) }// :529
        }

    (1..8).forEach { channel ->                                                  // :532
        add("secure-channel-$channel")   { hiddenChannelSocket(device, channel, secure = true) }  // :533
        add("insecure-channel-$channel") { hiddenChannelSocket(device, channel, secure = false) } // :534
    }
    return candidates
}
```

即四级回退：**① SDP 解析出的 SPP → ② SDP 解析出的其它非音频 UUID → ③ 硬编码 RFCOMM channel 1..8**。
第 ③ 级用反射调隐藏 API `:539-543`：

```kotlin
private fun hiddenChannelSocket(device: BluetoothDevice, channel: Int, secure: Boolean): BluetoothSocket {
    val methodName = if (secure) "createRfcommSocket" else "createInsecureRfcommSocket"   // :540
    val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)     // :541
    return method.invoke(device, channel) as BluetoothSocket                              // :542
}
```

`channel = 1` 就是从 SDP 数据库里读不到 SPP 记录时的经典兜底（`createRfcommSocket(1)`）。
华为耳机实际使用的就是 channel 1。

`HuaweiRfcommServicePolicy.isControlCandidate`（`pods/HuaweiRfcommServicePolicy.kt:6-16`）
排除音频类服务——**连接成功不代表拿到了控制通道**：

```kotlin
/** HFP/HSP/A2DP/AVRCP 服务不接受华为 AAM 数据帧，连接成功不能作为控制通道证据。 */   // :5
private val audioServiceIds = setOf(0x1108, 0x110A, 0x110B, 0x110C, 0x110D, 0x110E, 0x110F,
    0x1112, 0x111E, 0x111F)                                                          // :7-8
fun isControlCandidate(uuid: UUID): Boolean {
    val value = uuid.toString()
    if (!value.endsWith("-0000-1000-8000-00805f9b34fb") || !value.startsWith("0000")) return true  // :12
    val serviceId = value.substring(4, 8).toIntOrNull(16) ?: return true               // :13
    return serviceId !in audioServiceIds                                              // :14
}
```

### 3.3 线程模型

**没有常驻 reader 线程**——这是本引擎最重要的设计选择。全部 I/O 在**单线程 executor** 上串行执行，
响应通过「限时轮询 + 轮询窗口」同步收集：

| 组件 | 行 | 职责 |
|---|---|---|
| `executor`（newSingleThreadExecutor） | `:38` | **唯一**写/读线程；所有命令串行，天然无锁 |
| `routeProbeExecutor`（newSingleThreadExecutor） | `:39` | 独立的**一次性**探测线程，避免探测阻塞主通道 |
| `submissionLock` | `:40` | 只保护「提交」这一小段临界区（generation 校验 + `executeTracked`），不保护 I/O |
| `activeConnectSockets/Threads` | `:46-47` | 跟踪尚未 join 完的连接线程，用于热重载等待与 `canCloseForHotReload` |
| `activeOperations` | `:48` | 未完成的 executor 任务计数 |
| `mainHandler` | `:45` | 所有回调统一 post 回主线程（`:415-419`、`:653`） |
| `transportGeneration` | `:41`,`:678-686` | 代际号；`disconnect()` 使其自增，所有在途请求作废 |

任务提交 `executeTracked` `:660-675`：

```kotlin
private fun executeTracked(executorService: ExecutorService, block: () -> Unit) {
    check(!closing.get()) { "Huawei RFCOMM controller is closing" }   // :661
    activeOperations.incrementAndGet()                               // :662
    runCatching {
        executorService.execute {
            try { block() } finally { activeOperations.decrementAndGet() }   // :665-669
        }
    }.onFailure { activeOperations.decrementAndGet(); throw it }      // :671-674
}
```

**连接超时靠「临时线程 + join(timeout)」实现** `:545-585`——`BluetoothSocket.connect()` 本身没有超时参数，
Android 上可能阻塞 20s+：

```kotlin
private fun connectSocketWithTimeout(socket: BluetoothSocket, label: String,
                                     timeoutMs: Long = RFCOMM_CONNECT_TIMEOUT_MS): BluetoothSocket {
    val connected = AtomicBoolean(false); val failure = AtomicReference<Throwable?>(null)
    val thread = Thread({                                        // :552
        try {
            runCatching { socket.connect(); connected.set(true) }  // :555-556
                .onFailure { failure.set(it) }
        } finally {
            activeConnectThreads.remove(Thread.currentThread())   // :559
            activeConnectSockets.remove(socket)                   // :560
        }
    }, "HuaweiAnc-rfcomm-connect")                                // :562
    activeConnectSockets.add(socket); activeConnectThreads.add(thread)  // :563-564
    thread.start()                                                // :565
    try { thread.join(timeoutMs) }                                // :567
    catch (interrupted: InterruptedException) {
        runCatching { socket.close() }; Thread.currentThread().interrupt()
        throw CancellationException("Huawei RFCOMM connect interrupted").apply { initCause(interrupted) }  // :571-573
    }
    if (connected.get()) return socket                            // :576
    failure.get()?.let { runCatching { socket.close() }; throw it }   // :577-581
    runCatching { socket.close() }
    throw SocketTimeoutException("Huawei ANC RFCOMM connect timed out after ${timeoutMs}ms label=$label")  // :584
}
```

注意：超时后线程被**遗弃但仍存活**，因此必须 `socket.close()`（`:582`）让阻塞的 `connect()` 抛异常退出，
且线程集合（`:559-560` finally）保证 `canCloseForHotReload` 不会在它活着时返回 true。

### 3.4 写入与响应收集

`enqueueWrite(...)` `:351-447` 是唯一的写入路径，签名即能力面：

```kotlin
private fun enqueueWrite(
    context: Context, device: BluetoothDevice, route: HuaweiDeviceRoute,
    packet: ByteArray, description: String,
    keepSocket: Boolean = true,                       // :357  true=保持长连接；false=一次性
    onComplete: ((Boolean) -> Unit)? = null,          // :358
    responseWindowMs: Long = 0L,                      // :359  0=不读响应
    responseComplete: ((ByteArray) -> Boolean)? = null, // :360 可提前结束的判据
    onResponse: ((ByteArray) -> Unit)? = null,        // :361
    shouldWrite: () -> Boolean = { true },            // :362  写前最后一次否决权
)
```

流程（行号即顺序）：

```kotlin
val requestGeneration = transportGeneration.snapshot()               // :364
if (!isHuaweiDeviceRouteEnabled(route)) { notifyComplete(onComplete, false); return }  // :366-373
synchronized(submissionLock) {                                       // :376
    if (!transportGeneration.isCurrent(requestGeneration)) { ...; return@synchronized }  // :377-384
    executeTracked(executor) worker@{                                 // :385
        if (!transportGeneration.isCurrent(requestGeneration) || !shouldWrite()) { ... return@worker } // :386-393
        runCatching {
            val currentSocket = ensureSocket(device, requestGeneration)      // :396
            ensureCurrentTransportGeneration(requestGeneration)               // :397
            if (!shouldWrite()) { notifyComplete(onComplete, false); return@worker }  // :399-402
            currentSocket.outputStream.write(packet)                         // :403
            currentSocket.outputStream.flush()                               // :404
            ensureCurrentTransportGeneration(requestGeneration)               // :405
            RfcommLog.d(appContext, "RFCOMM/TX", "$description $hex")        // :407
            if (responseWindowMs > 0L && onResponse != null) {               // :408
                val response = collectSocketResponse(currentSocket, responseWindowMs, responseComplete)  // :409-413
                ensureCurrentTransportGeneration(requestGeneration)          // :414
                mainHandler.post {                                           // :415
                    if (transportGeneration.isCurrent(requestGeneration)) onResponse(response)  // :416-418
                }
            }
        }.onFailure {
            closeSocket()                                                // :427 ←任何异常都作废 socket
            if (it is CancellationException) { ... } else { logError(...) }  // :428-435
            notifyComplete(onComplete, false)                            // :436
        }.onSuccess {
            if (!keepSocket) closeSocket()                               // :438
            notifyComplete(onComplete, true, requestGeneration)          // :439
        }
    }
}
```

**响应收集** `collectSocketResponse(socket, timeoutMs, responseComplete)` `:596-625`：

```kotlin
val input = socket.inputStream; val output = ByteArrayOutputStream()
val buffer = ByteArray(4 * 1024)                                     // :603
val deadline = System.nanoTime() + timeoutMs * 1_000_000L            // :604
var lastReadAt = 0L
while (System.nanoTime() < deadline) {
    val available = runCatching { input.available() }.getOrDefault(0) // :607
    if (available > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, available))  // :609
        if (read > 0) {
            output.write(buffer, 0, read)                            // :611
            lastReadAt = System.nanoTime()                           // :612
            if (responseComplete?.invoke(output.toByteArray()) == true) break  // :613
        }
        continue
    }
    if (responseComplete == null && lastReadAt > 0L &&
        System.nanoTime() - lastReadAt >= 200_000_000L) break        // :617-621 ←200ms 静默即视为结束
    Thread.sleep(20L)                                                // :622
}
return output.toByteArray()
```

这是**唯一**的读路径。`available()` 轮询（20ms 间隔）+ 两种终止条件：
① 调用方提供 `responseComplete` 判据（解析器判定帧已完整）；
② 未提供判据时用「200ms 静默」启发式结束。

各业务的响应窗口值：

| API | 窗口 | 判据 | 行 |
|---|---|---|---|
| `requestBattery` | 1500 ms | 无（静默启发） | `:132-164`（`:143` 传 1500） |
| `requestDeviceInfoIdentity` | 1500 ms | `HuaweiDeviceInfoIdentityParser.parse(response) != null` | `:166-199` |
| `requestDeviceInfoIdentityRouteFree` | 1200 ms | 同上，独立线程 | `:201-255`（`:230-236`） |
| `requestAncState` | 1500 ms | `route::validateAncState`（`:274-277`） | `:257-296` |
| `requestRawPacketOnce` | 1000 ms（默认） | 调用方自定义 | `:298-322` |
| `sendRawPacketOnce` | 0 | 不读 | `:340-349` |

### 3.5 连接复用与「不与系统 SPP 抢通道」

`ensureSocket(device, requestGeneration)` `:460-507`：

```kotlin
ensureCurrentTransportGeneration(requestGeneration)                  // :464
val currentSocket = socket
if (currentSocket != null && deviceAddress == device.address) {      // :466
    Log.w(TAG, "Huawei ANC reusing RFCOMM socket label=$socketLabel device=${device.address}")  // :467
    return currentSocket                                             // :468
}
closeSocket()                                                        // :471
var lastFailure: Throwable? = null
for (candidate in socketCandidates(device)) {                        // :473
    repeat(RFCOMM_CONNECT_ATTEMPTS) { attempt ->                     // :474
        ensureCurrentTransportGeneration(requestGeneration)          // :475
        runCatching {
            val newSocket = connectSocketWithTimeout(candidate.create(), candidate.label)  // :481
            if (!transportGeneration.isCurrent(requestGeneration)) { // :482
                runCatching { newSocket.close() }; ensureCurrentTransportGeneration(requestGeneration)
            }
            socket = newSocket; deviceAddress = device.address; socketLabel = candidate.label  // :487-489
            return newSocket                                          // :491
        }.onFailure {
            if (it is CancellationException) throw it                 // :493
            lastFailure = it
            if (attempt + 1 < RFCOMM_CONNECT_ATTEMPTS) Thread.sleep(RFCOMM_CONNECT_RETRY_DELAY_MS)  // :500-502
        }
    }
}
throw lastFailure ?: IOException("No Huawei ANC RFCOMM candidate succeeded")  // :506
```

**避免与系统 SPP 连接冲突的三条机制**：

1. **单一 socket 持有 + 复用**（`:466-469`）：同一台设备在模块生命周期内只建一条 RFCOMM 链路。
   华为耳机固件对**第二条 SPP 连接**的处理是「踢掉旧的」，如果模块反复建链会打断系统/智慧生活 App 的连接，
   因此这里选择「一次建链、长期持有、按需 shutdown」。
2. **`keepSocket` 参数**（`:357`）：只有需要后续命令的调用传 `true`；
   `sendRawPacketOnce`（`:340-349`）与 `requestDeviceInfoIdentityRouteFree`（`:201-255`）用独立的**一次性** socket
   并在结束时 `probeSocket?.close()`（`:247`），绝不触碰主 socket。
3. **与华为智慧生活（`com.huawei.smartaudio`）的让位**——`HuaweiHfpController.kt` 里两条明确注释：
   - `:106-110`「Smart Audio accepted spatial write but readback was delayed; direct RFCOMM fallback suppressed to avoid stealing its control channel」
   - `:857-859`「Receiver acceptance only proves that the asynchronous official API was invoked… Once accepted, timeout must not open a second RFCOMM connection and evict Smart Audio.」

   即：**只要官方 App 的桥接通道接受了请求，即使回读超时也绝不降级到自己建链**，
   因为第二条连接会把官方 App 踢下线。

➜ **移植要点**：这条规则是整套设计里最容易做错的地方。新模块若对面也有官方 App 抢同一条 SPP，
必须实现同样的「一次建链 + 官方通道优先 + 超时不降级」三件套。

### 3.6 重连 / 重试策略

| 层级 | 策略 | 行 |
|---|---|---|
| 单候选 | 每个候选重试 `RFCOMM_CONNECT_ATTEMPTS = 2` 次，间隔 `350ms` | `:33,:474,:501` |
| 候选遍历 | 全候选列表线性遍历，任一成功即返回 | `:473-505` |
| 单次连接 | `connect()` 超时 `3000ms`（探测用 `2000ms`） | `:31,:34,:548` |
| 失败后果 | 任何 I/O 异常 → `closeSocket()` 作废 socket，下次调用重新走 `ensureSocket` | `:427` |
| 主动断开 | `disconnect(device)` 使 `transportGeneration` 自增，**所有**在途/排队请求立即作废 | `:449-458` |
| 会话切换 | `HuaweiHfpController.ensureSession` 在设备/机型变化时调 `HuaweiL2capAncController.disconnect(previousDevice)` | `HuaweiHfpController.kt:1318-1321` |
| 上层节流 | 电池 10s、ANC 750ms、DeviceInfo 60s（见 §6） | `HuaweiHfpController.kt:31-41` |
| 失败重试 | 无自动重连；由上层在下次轮询（30s 后台电池刷新）重新触发 | `HuaweiHfpController.kt:33` |

`disconnect` `:449-458`：

```kotlin
fun disconnect(device: BluetoothDevice? = null) {
    synchronized(submissionLock) {
        val invalidatedGeneration = transportGeneration.invalidate()      // :451
        Log.w(TAG, "Huawei ANC disconnect queued generation=$invalidatedGeneration device=${device?.address.orEmpty()}")
        executeTracked(executor) { closeSocket() }                        // :456
    }
}
```

代际对象 `:678-686`：

```kotlin
internal class HuaweiRfcommTransportGeneration {
    private val value = AtomicLong(0L)
    fun snapshot(): Long = value.get()                    // :681
    fun invalidate(): Long = value.incrementAndGet()      // :683
    fun isCurrent(snapshot: Long): Boolean = value.get() == snapshot   // :685
}
```

`ensureCurrentTransportGeneration` `:509-513` 在「每个可能慢的步骤之后」调用（`:397,:405,:414,:421,:464,:475`），
把迟到结果转成 `CancellationException("Stale Huawei RFCOMM transport generation")`（`:511`）。

### 3.7 热重载支持

```kotlin
/** 预检必须无副作用；正在执行系统蓝牙 I/O 时让框架回退为进程重启。 */     // :51
fun canCloseForHotReload(): Boolean =
    !closing.get() && activeOperations.get() == 0 &&
        activeConnectThreads.isEmpty() && activeConnectSockets.isEmpty()   // :52-56

fun closeForHotReload(): Boolean {                                       // :58
    closing.set(true)                                                    // :59
    synchronized(submissionLock) {
        transportGeneration.invalidate(); closeSocket()
        close activeConnectSockets; interrupt threads
        executor.shutdownNow(); routeProbeExecutor.shutdownNow()
    }
    mainHandler.removeCallbacksAndMessages(null)
    awaitTermination(1, SECONDS) x2; join(250L)
    return executorStopped && routeProbeStopped && connectThreadsStopped  // :58-83
}
```

`HeadsetStateDispatcher.onCanClose()` 把它串进门禁
（`hook/HeadsetStateDispatcher.kt:134-137`）：
`activeRouteProbe.get() == null && HuaweiL2capAncController.canCloseForHotReload() && OfficialImageIdentityBridge.canCloseForHotReload()`。

### 3.8 观测

`pods/RfcommLog.kt`（74 行）是给 UI 用的环形日志：`max 200` 条（`:12`），`ArrayDeque<Entry>`（`:14`），
`enabled` 由 `ConfigManager.logLevel()` 驱动；每条通过
`Intent(HuaweiPodsAction.ACTION_RFCOMM_LOG).setPackage(BuildConfig.APPLICATION_ID)` 广播到模块 App（`:63-73`）。
传输层的每个关键点都双写：`logInfo` `:635-639` = `Log.w` + `AndroidLog.i` + `RfcommLog.i`；
`logError` `:641-645` = `Log.e` + `AndroidLog.e` + `RfcommLog.e`。

---

## 4. 协议封装：把指令声明为数据

### 4.1 帧格式（所有华为控制帧统一）

```
+--------+--------+--------+--------+     +--------+--------+--------+--------+
| 0x5A   | 0x00   | len LO | len HI | ... | type   | length | value… | CRC16  |
+--------+--------+--------+--------+     +--------+--------+--------+--------+
  magic  version  payloadLength(LE)      TLV 段              XMODEM(hi, lo)
```

- `HEADER_SIZE = 5`（magic 2 + length 2 + service 1），`CHECKSUM_SIZE = 2`
  （`pods/HuaweiRfcommResponseParser.kt:8-9`）
- `payloadLength` 是 **little-endian**：`u8(offset+2) or (u8(offset+3) shl 8)`（同文件 `:191`）
- CRC 为 CRC-16/XMODEM：poly `0x1021`，init `0`，**高字节在前**
  （`pods/HuaweiEqualizerCodec.kt:228-238`、`pods/HuaweiDeviceInfoIdentity.kt:132-146`）
- TLV 段从 offset 6 开始：`type(u8) | length(u8) | value(length bytes)`
  （`HuaweiRfcommResponseParser.kt:202-219`）

CRC 实现（`pods/HuaweiEqualizerCodec.kt:228-238`）：

```kotlin
private fun crc16Xmodem(bytes: ByteArray): Int {
    var crc = 0
    bytes.forEach { byte ->
        crc = crc xor (byte.u8() shl 8)
        repeat(8) {
            crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
    }
    return crc
}
```

组帧 `frame(body)` `:209-220`：

```kotlin
private fun frame(body: ByteArray): ByteArray {
    val payloadLength = body.size + 1                       // :210 ←+1 是 service 字节
    require(payloadLength <= 0xFFFF)
    val withoutCrc = byteArrayOf(0x5A, 0x00,
        payloadLength.toByte(), (payloadLength shr 8).toByte()) + body   // :212-217
    val crc = crc16Xmodem(withoutCrc)                       // :218
    return withoutCrc + byteArrayOf((crc shr 8).toByte(), crc.toByte())  // :219
}
```

### 4.2 指令表 —— `pods/HuaweiAncPackets.kt`（108 行）

```kotlin
/** 仅包含已经从实机抓包中确认过的 Huawei RFCOMM 指令。 */      // :3
internal object HuaweiAncPackets {                              // :4
    private fun packet(vararg values: Int): ByteArray =         // :106-107
        values.map(Int::toByte).toByteArray()
```

**指令就是常量 ByteArray**，全部预先带好 CRC（不在运行时计算，因为都是抓包得来的定值）：

```kotlin
private val huaweiBatteryQuery =
    packet(0x5A, 0x00, 0x09, 0x00, 0x01, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0xFB, 0xB9)  // :5-6
private val huaweiDeviceInfoQuery = packet(                       // :7
    0x5A, 0x00, 0x21, 0x00, 0x01, 0x07,
    0x01,0x00, 0x02,0x00, 0x03,0x00, 0x04,0x00, 0x05,0x00,        // :9
    0x06,0x00, 0x07,0x00, 0x08,0x00, 0x09,0x00, 0x0A,0x00,        // :10
    0x0B,0x00, 0x0C,0x00, 0x0F,0x00, 0x18,0x00, 0x19,0x00,        // :11
    0xDF, 0xF3,                                                   // :12
)
private val modernFreeBudsCurrentStateQuery =
    packet(0x5A, 0x00, 0x05, 0x00, 0x2B, 0x2A, 0x01, 0x00, 0x42, 0x7E)   // :14-15
```

**布尔开关做成 `Map<Boolean, ByteArray>`**：

```kotlin
private val freeBuds3Enabled = mapOf(                             // :17
    false to packet(0x5A,0x00,0x06,0x00,0x2B,0x04,0x01,0x01,0x00,0x68,0x21),   // :18
    true  to packet(0x5A,0x00,0x06,0x00,0x2B,0x04,0x01,0x01,0x01,0x78,0x00),   // :19
)
private val modernFreeBudsEnabled = mapOf(                        // :21
    false to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x00,0x00,0xD2,0x2D),  // :22
    true  to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x01,0xFF,0xFF,0xEC),  // :23
)
```

**多档位做成 `Map<协议值, ByteArray>`**：

```kotlin
private val modernAncLevels = mapOf(                              // :25
    0xFF to modernFreeBudsEnabled.getValue(true),                 // :26  ←FF=「切换但不指定档位」的过渡令牌
    0x01 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x01,0x01,0xF1,0x3D),  // :27-28
    0x00 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x01,0x00,0xE1,0x1C),  // :29-30
    0x02 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x01,0x02,0xC1,0x5E),  // :31-32
    0x03 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x01,0x03,0xD1,0x7F),  // :33-34
)
private val transparencyModes = mapOf(                            // :36
    0xFF to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x02,0xFF,0xAA,0xBF),  // :37
    0x01 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x02,0x01,0xA4,0x6E),  // :38
    0x02 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x02,0x02,0x94,0x0D),  // :39
    0x04 to packet(0x5A,0x00,0x07,0x00,0x2B,0x04,0x01,0x02,0x02,0x04,0xF4,0xCB),  // :40
)
```

**老机型（FreeBuds 3）用 9 元素 List 表示「旋钮档位」**：

```kotlin
private val freeBuds3Levels = listOf(                             // :42
    packet(0x5A,0x00,0x06,0x00,0x2B,0x08,0x01,0x01,0x00,0x27,0x13),   // :43
    ... 共 9 条，第 8 字节 0x00..0x08 ...                             // :43-51
    packet(0x5A,0x00,0x06,0x00,0x2B,0x08,0x01,0x01,0x08,0xA6,0x1B),   // :51
)
```

**对外 API 全是「按机型路由 + 能力位门控」的纯函数**，永远 `.copyOf()`（`:58,:86,:92,:95,:98,:101,:104`，
防止调用方改到内部数组）：

```kotlin
fun enabled(route: HuaweiDeviceRoute, enabled: Boolean): ByteArray? = when {     // :54
    route == HuaweiDeviceRoute.HUAWEI_FREEBUDS3 -> freeBuds3Enabled[enabled]    // :55
    route.supportsAnc -> modernFreeBudsEnabled[enabled]                          // :56
    else -> null                                                                 // :57
}?.copyOf()

fun mode(route: HuaweiDeviceRoute, mode: NoiseControlMode, subMode: Int? = null): ByteArray? = when {  // :60
    !route.supportsAnc || mode == NoiseControlMode.UNKNOWN -> null               // :65
    route == HuaweiDeviceRoute.HUAWEI_FREEBUDS3 -> when (mode) {                 // :66
        NoiseControlMode.OFF -> freeBuds3Enabled[false]                          // :67
        NoiseControlMode.NOISE_CANCELLATION -> freeBuds3Enabled[true]            // :68
        NoiseControlMode.TRANSPARENCY, NoiseControlMode.UNKNOWN -> null          // :69-70
    }
    mode == NoiseControlMode.OFF -> modernFreeBudsEnabled[false]                 // :72
    mode == NoiseControlMode.NOISE_CANCELLATION && route.supportsDiscreteAncLevels -> {  // :73
        val selected = subMode ?: route.defaultAncSubMode                        // :74
        when {
            selected == 0xFF -> modernAncLevels[0xFF]                            // :76
            selected?.let(route::supportsAncSubMode) == true -> modernAncLevels[selected]  // :77
            else -> null                                                         // :78
        }
    }
    mode == NoiseControlMode.NOISE_CANCELLATION -> modernFreeBudsEnabled[true]   // :81
    mode == NoiseControlMode.TRANSPARENCY && route.supportsTransparency -> {      // :82
        transparencyModes[subMode ?: route.defaultTransparencySubMode ?: 0xFF]   // :83
    }
    else -> null                                                                 // :85
}?.copyOf()

fun level(route: HuaweiDeviceRoute, level: Int): ByteArray? =                     // :88
    freeBuds3Levels.takeIf { route.supportsAncDirectionDial }                    // :90
        ?.get(level.coerceIn(0, freeBuds3Levels.lastIndex))?.copyOf()            // :91-92

fun batteryQuery(route: HuaweiDeviceRoute): ByteArray? =                          // :94
    huaweiBatteryQuery.takeIf { route.supportsRfcommBattery }?.copyOf()          // :95
fun deviceInfoQuery(route: HuaweiDeviceRoute): ByteArray? =                       // :97
    huaweiDeviceInfoQuery.takeIf { route.supportsRfcommBattery }?.copyOf()       // :98
/** 用户点选未知设备时的 route-free 身份查询；响应仍必须经过严格 DeviceInfo 解析与白名单映射。 */  // :100
fun routeFreeDeviceInfoQuery(): ByteArray = huaweiDeviceInfoQuery.copyOf()        // :101
fun currentStateQuery(route: HuaweiDeviceRoute): ByteArray? =                     // :103
    modernFreeBudsCurrentStateQuery.takeIf { route.supportsAncStateReadback }?.copyOf()  // :104
```

➜ **这就是「新机型只加 profile」的机制**：加一个机型 = 在 `DeviceCapabilities.kt` 加一条能力记录 +
在 `HuaweiAncLevelProfile.kt` 加一个档位表 +（若指令不同）在 `HuaweiAncPackets.kt` 加一个 map 分支。
传输层、解析层、UI 层**零改动**。

### 4.3 档位映射表 —— `pods/HuaweiAncLevelProfile.kt`（124 行）

同一协议值在不同机型上语义不同，所以档位必须**三元组化**：

```kotlin
/**
 * 同一 ANC 协议值在不同耳机上可能代表不同语义，所有档位转换必须携带机型路由。   // :3-5
 */
internal data class HuaweiAncLevelOption(                          // :6
    val level: HuaweiAncLevel,        // 语义档位（ADAPTIVE/LIGHT/BALANCED/DEEP）
    val protocolValue: Int,           // 写进 RFCOMM 帧的值
    val miuiValue: Int,               // 广播给 MIUI/系统 UI 的值
)
```

**这是个绝妙的设计**：`protocolValue` ≠ `miuiValue`，因为华为协议和小米菜单的编号体系不一致。
例：Pro 3 / FreeBuds 7i 的 `ADAPTIVE` 协议值是 `0x01`，但小米菜单里是 `0x03`（`:23-28`）：

```kotlin
private val freeBuds6iAncOptions = listOf(                         // :16
    HuaweiAncLevelOption(HuaweiAncLevel.ADAPTIVE, protocolValue = 0x03, miuiValue = 0x03),  // :17
    HuaweiAncLevelOption(HuaweiAncLevel.LIGHT,    protocolValue = 0x01, miuiValue = 0x01),  // :18
    HuaweiAncLevelOption(HuaweiAncLevel.BALANCED, protocolValue = 0x00, miuiValue = 0x00),  // :19
    HuaweiAncLevelOption(HuaweiAncLevel.DEEP,     protocolValue = 0x02, miuiValue = 0x02),  // :20
)
private val pro3AndFreeBuds7iAncOptions = listOf(                  // :23
    HuaweiAncLevelOption(HuaweiAncLevel.ADAPTIVE, protocolValue = 0x01, miuiValue = 0x03),  // :24
    HuaweiAncLevelOption(HuaweiAncLevel.LIGHT,    protocolValue = 0x00, miuiValue = 0x01),  // :25
    HuaweiAncLevelOption(HuaweiAncLevel.BALANCED, protocolValue = 0x02, miuiValue = 0x00),  // :26
    HuaweiAncLevelOption(HuaweiAncLevel.DEEP,     protocolValue = 0x03, miuiValue = 0x02),  // :27
)
private val freeBuds5AncOptions = listOf(...)                      // :30-34 只有 3 档（无 DEEP）
private val freeBudsPro5AncOptions = freeBuds6iAncOptions          // :37 复用
private val freeBuds4eAncOptions = listOf(                         // :40
    HuaweiAncLevelOption(HuaweiAncLevel.LIGHT,    protocolValue = 0x01, miuiValue = 0x01),  // :41
    HuaweiAncLevelOption(HuaweiAncLevel.BALANCED, protocolValue = 0x00, miuiValue = 0x00),  // :42
)
private val freeBudsSe4AncOptions = listOf(...)                    // :46-51
```

路由分发 `ancLevelOptions` `:53-69`，以及四个转换函数：

```kotlin
internal val HuaweiDeviceRoute.ancLevelOptions: List<HuaweiAncLevelOption>   // :53
internal val HuaweiDeviceRoute.defaultAncSubMode: Int?                        // :71-75
internal fun HuaweiDeviceRoute.ancLevelOptionForProtocolValue(value: Int): HuaweiAncLevelOption?  // :77-78
internal fun HuaweiDeviceRoute.ancSubModeForMiuiLevel(value: Int): Int?       // :80-81
internal fun HuaweiDeviceRoute.miuiLevelForAncSubMode(value: Int): Int?       // :83-84
internal fun HuaweiDeviceRoute.supportsAncSubMode(value: Int): Boolean        // :86-87
```

通透子模式同理按机型隔离（`:93-112`），**回读校验**是最后一道闸门（`:114-124`）：

```kotlin
/** 丢弃与当前机型能力不一致的回读，避免把其他机型档位或通透状态写入会话。 */   // :114
internal fun HuaweiDeviceRoute.validateAncState(state: HuaweiAncState): HuaweiAncState? = when {  // :115
    !supportsAnc -> null                                                         // :116
    state.mode == NoiseControlMode.OFF -> HuaweiAncState(NoiseControlMode.OFF)   // :117
    state.mode == NoiseControlMode.NOISE_CANCELLATION -> when {                  // :118
        supportsDiscreteAncLevels -> state.takeIf { state.subMode?.let(::supportsAncSubMode) == true }  // :119
        else -> HuaweiAncState(NoiseControlMode.NOISE_CANCELLATION)              // :120
    }
    state.mode == NoiseControlMode.TRANSPARENCY -> state.takeIf { supportsTransparency }  // :122
    else -> null                                                                 // :123
}
```

### 4.4 响应解析

**`pods/HuaweiRfcommResponseParser.kt`（248 行）** —— 按 `service/command` 二元组分派：

```kotlin
private const val HEADER_SIZE = 5                                    // :8
private const val CHECKSUM_SIZE = 2                                  // :9
private const val BATTERY_SERVICE = 0x01                             // :10
private val BATTERY_COMMANDS = setOf(0x08, 0x27)                     // :11
private const val BATTERY_LEVELS = 0x02                              // :12  TLV type
private const val CHARGING_STATES = 0x03                             // :13
private const val EARBUD_CONNECTION_STATES = 0x05                    // :14
private const val ANC_SERVICE = 0x2B                                 // :15
private const val ANC_STATE_COMMAND = 0x2A                           // :16
private const val ANC_STATE = 0x01                                   // :17
```

```kotlin
fun parseBattery(stream: ByteArray, includeCase: Boolean = true,
                 useReportedEarbudAvailability: Boolean = false): BatteryParams? {   // :28
    var latestBattery: BatteryParams? = null
    frames(stream).forEach { frame ->
        if (frame.u8(4) != BATTERY_SERVICE || frame.u8(5) !in BATTERY_COMMANDS) return@forEach  // :35
        val fields = parseFields(frame, start = 6, endExclusive = frame.size - CHECKSUM_SIZE)   // :36
        val levels = fields[BATTERY_LEVELS] ?: return@forEach                       // :37
        ...
    }
    return latestBattery                                                            // :65
}

fun parseAncState(stream: ByteArray): HuaweiAncState? {                              // :68
    var latestState: HuaweiAncState? = null
    frames(stream).forEach { frame ->
        if (frame.u8(4) != ANC_SERVICE || frame.u8(5) != ANC_STATE_COMMAND) return@forEach     // :71
        val state = fields[ANC_STATE]?.takeIf { it.size == 2 } ?: return@forEach    // :73
        val mode = when (state.u8(1)) {                                             // :74
            0x00 -> NoiseControlMode.OFF; 0x01 -> NoiseControlMode.NOISE_CANCELLATION
            0x02 -> NoiseControlMode.TRANSPARENCY; else -> return@forEach
        }
        latestState = HuaweiAncState(mode = mode,
            subMode = state.u8(0).takeUnless { mode == NoiseControlMode.OFF })       // :80-83
    }
    return latestState
}
```

**流式重同步**（`frames` `:184-200`）——RFCOMM 是字节流，可能把多帧粘在一起，也可能从帧中间开始：

```kotlin
private fun frames(stream: ByteArray): Sequence<ByteArray> = sequence {
    var offset = 0
    while (offset + HEADER_SIZE <= stream.size) {
        if (stream.u8(offset) != 0x5A || stream.u8(offset + 1) != 0x00) { offset++; continue }  // :187-190
        val payloadLength = stream.u8(offset + 2) or (stream.u8(offset + 3) shl 8)              // :191
        val frameSize = HEADER_SIZE + payloadLength                                             // :192
        if (frameSize <= HEADER_SIZE || offset + frameSize > stream.size) { offset++; continue }// :193-196
        yield(stream.copyOfRange(offset, offset + frameSize))                                   // :197
        offset += frameSize                                                                     // :198
    }
}
```

裸 `scan` 不带 CRC 校验（解析层容忍，因为有些应答 CRC 字段被厂商写错）；
需要强校验的地方（DeviceInfo / EQ）另有 `hasValidCrc` 版本。

耳机在位判定有专门的归一化（`:221-237`）：

```kotlin
private fun ByteArray.podAt(index: Int, charging: ByteArray,
                            reportedConnected: Boolean? = null,
                            inferAvailabilityFromBattery: Boolean = true): PodParams? {
    val level = getOrNull(index)?.toInt()?.and(0xFF)?.takeIf { it in 0..100 } ?: return null  // :227
    val chargingValue = charging.getOrNull(index)?.toInt()?.and(0xFF) ?: 0                    // :228
    val isConnected = reportedConnected
        ?: if (inferAvailabilityFromBattery) level > 0 else true                              // :229-230
    return PodParams(battery = level,
        isCharging = isConnected && chargingValue != 0,                                        // :233
        isConnected = isConnected, rawStatus = chargingValue)                                 // :234-235
}
/** Pro 5 抓包里的 0x05 字段：0 为已出盒，1 为已收纳入盒。 */                                  // :239
private fun ByteArray.reportedConnectedAt(index: Int): Boolean? =
    when (getOrNull(index)?.toInt()?.and(0xFF)) { 0 -> true; 1 -> false; else -> null }        // :240-245
```

**`pods/HuaweiBatteryParser.kt`（73 行）** —— 另一条完全不同的电池路径：从 **AT 命令文本**里正则提取
（老机型通过 HFP 的 `AT+UPDATEHUAWEIBATTERY=…` 上报）：

```kotlin
object HuaweiBatteryParser {                                              // :7
    private val batteryPattern = Regex(
        """(?:AT)?\+?(?:UPDATE)?HUAWEIBATTERY\s*[=:]\s*([0-9,\s]+)""",    // :8-11
        RegexOption.IGNORE_CASE)
    data class Result(val battery: BatteryParams, val values: Map<Int, Int>)  // :13-16
    fun parse(text: String?): Result? { ... }                              // :18-44
    private const val BATTERY_LEFT = 2; private const val CHARGING_LEFT = 3   // :67-68
    private const val BATTERY_RIGHT = 4; private const val CHARGING_RIGHT = 5 // :69-70
    private const val BATTERY_CASE = 6; private const val CHARGING_CASE = 7   // :71-72
}
```

首数字是「对数」，`payloadValues` `:46-54` 据此切片，兼容两种上报格式。

**`pods/HuaweiDeviceInfoIdentity.kt`（149 行）** —— 机型身份（TLV 白名单 + 三重一致性）：

```kotlin
private const val DEVICE_INFO_SERVICE = 0x01                    // :14
private const val DEVICE_INFO_COMMAND = 0x07                    // :15
private const val FRAME_PREFIX_BYTES = 6                        // :16
private const val TYPE_MODEL_CODE = 0x02                        // :18
private const val TYPE_MODEL = 0x0A                             // :19
private const val TYPE_MODEL_PREFIX = 0x0F                      // :20
private const val TYPE_SUB_MODEL = 0x19                         // :21
private const val COMMON_RESOURCE_MODEL_ID = "00000A"           // :24
private val modelValueRegex = Regex("^([A-Z0-9]+)-([0-9A-F]{6})$")   // :25
```

```kotlin
/** RFCOMM 读取可能把相邻帧合并；只在全部有效 DeviceInfo 帧给出同一身份时接受结果。 */  // :27-29
fun parse(stream: ByteArray): HuaweiDeviceInfoIdentity? {                       // :30
    if (stream.isEmpty() || stream.size > MAX_STREAM_BYTES) return null         // :31
    val identities = frames(stream).mapNotNull(::parseFrame).distinct().toList()// :32-35
    return identities.singleOrNull()                                            // :36 ←多帧不一致=拒绝
}
```

`parseFrame` 的**三重交叉校验**（`:97-104`）是防误判的核心：

```kotlin
val confirmedModelId = modelId ?: return null                                   // :97
if (confirmedModelId == COMMON_RESOURCE_MODEL_ID ||                             // :99
    modelCode != confirmedModelId.takeLast(4) ||                                // :100 ←0x02 字段必须匹配型号后四位
    declaredModelPrefix != modelPrefixFromModel) {                              // :101 ←0x0F 声明前缀必须与 0x0A 内嵌前缀一致
    return null
}
```

**`pods/HuaweiEqualizerCodec.kt`（271 行）** —— 唯一需要**运行时组帧**的协议（因为档位是用户自定义的）：

```kotlin
const val BAND_COUNT = 10                        // :12
val GAIN_RANGE = -60..60                         // :13
private const val CUSTOM_RECORD_SIZE = 36        // :18
private const val CUSTOM_NAME_BYTES = 24         // :19
private const val MAX_WRITE_NAME_BYTES = 32      // :20
private val QUERY = hex("5A0005002B4A02008C46")  // :21
```

```kotlin
fun buildCustomPacket(gains: List<Int>, presetName: String,
                      operationValue: Int, presetId: Int = 0x64): ByteArray? {   // :56
    if (presetId !in 0x64..0x66 || operationValue !in 0..0xFF) return null        // :62
    if (gains.size != BAND_COUNT || gains.any { it !in GAIN_RANGE }) return null  // :63
    val nameBytes = presetName.trim().toByteArray(StandardCharsets.UTF_8)         // :64
    if (nameBytes.isEmpty() || nameBytes.size > MAX_WRITE_NAME_BYTES) return null // :65
    val body = buildList<Byte> {
        add(0x2B); add(0x49); add(0x01); add(0x01); add(presetId.toByte())        // :67-71
        add(0x02); add(0x01); add(BAND_COUNT.toByte())                            // :72-74
        add(0x05); add(0x01); add(operationValue.toByte())                        // :75-77
        add(0x03); add(BAND_COUNT.toByte()); gains.forEach { add(it.toByte()) }    // :78-80
        add(0x04); add(nameBytes.size.toByte()); addAll(nameBytes.toList())        // :81-83
    }.toByteArray()
    return frame(body)                                                            // :85
}
```

内置预设按机型白名单（`:94-124`）：`HUAWEI_FREEBUDS6I -> setOf(0x01,0x02,0x03,0x09)`（`:98`）、
`HUAWEI_FREEBUDS7 -> setOf(0x02,0x05,0x09,0x0D,0x0E,0x0F,0x10)`（`:101`）、
`HUAWEI_FREEBUDS_PRO5 -> setOf(0x02,0x05,0x09,0x0D,0x0E,0x0F,0x10,0x11)`（`:102-111`）、
其它机型 `else -> return null`（`:112`）。
写操作码也按机型（`:126-134`）：多数为 `0x01`，`HUAWEI_FREEBUDS7I -> 0x00`。

解析用**强制 CRC 校验**（`:185-207`）：`verifiedFrames` 对每个候选帧 `hasValidCrc()`（`:200`），
失败则 `offset++` 重试（`:204`）——比 battery/ANC 的裸 scan 严格一档。

### 4.5 新增一个机型的完整 checklist

1. `pods/DeviceCapabilities.kt:3-22` 加枚举值 → `:41-206` 加 `HuaweiDeviceCapabilities` 一条（含 `displayName`、`aliases`、
   全部 `supports*` 位）→ `:214-232` 加 `broadcastValueByRoute` 映射。
2. `pods/HuaweiAncLevelProfile.kt` 加档位表并接到 `:53-69` 的 `when`；若通透子模式不同，改 `:93-112`。
3. `pods/HuaweiAncPackets.kt` 若指令序列不同，加一个 map + 在 `enabled/mode/level` 里加分支。
4. `pods/HuaweiDeviceInfoRoutePolicy.kt:5-23` 若走 DeviceInfo 识别，加 `modelId -> route` 白名单。
5. `config/DeviceRoutePrefs.kt:153-172` 的 `storageId()` 加持久化 ID 字符串。
6. `ui/pages/DevicePickerPage.kt` **不用改**——`enabledHuaweiDeviceRoutes()` 自动带出。

---

## 5. 多机型适配

### 5.1 能力表 —— `pods/DeviceCapabilities.kt`（313 行，12.9 KB）

```kotlin
enum class HuaweiDeviceRoute {                      // :3
    HUAWEI_FREEBUDS3, HUAWEI_FREEBUDS4E, HUAWEI_FREEBUDS5, HUAWEI_FREEBUDS5I,
    HUAWEI_FREEBUDS_SE4_ANC, HUAWEI_FREEBUDS6I, HUAWEI_FREEBUDS6,
    HUAWEI_FREEBUDS_PRO3, HUAWEI_FREEBUDS_PRO4, HUAWEI_FREEBUDS_PRO5,
    HUAWEI_FREEBUDS7I, HUAWEI_FREEBUDS7,
    HUAWEI_FREECLIP, HUAWEI_FREECLIP2, HUAWEI_FREEARC,
    HUAWEI_EYEWEAR, HUAWEI_EYEWEAR2,
    UNSUPPORTED,                                    // :21
}

data class HuaweiDeviceCapabilities(                // :24
    val displayName: String,                        // :25
    val aliases: Set<String>,                       // :26
    val supportsAnc: Boolean = false,                        // :27
    val supportsTransparency: Boolean = false,               // :28
    val supportsAncStateReadback: Boolean = false,           // :29
    val supportsDiscreteAncLevels: Boolean = false,          // :30
    val supportsAncDirectionDial: Boolean = false,           // :31
    val supportsRfcommBattery: Boolean = false,              // :32
    val supportsBackgroundBatteryRefresh: Boolean = false,   // :33
    val supportsGestureConfiguration: Boolean = false,       // :34
    val supportsLowLatencyControl: Boolean = false,          // :35
    val supportsWindNoiseReduction: Boolean = false,         // :36
    val hasChargingCase: Boolean = false,                    // :37
    val usesReportedEarbudAvailability: Boolean = false,     // :38
)
```

**14 个布尔位就是全部行为分叉点**。表中每一条都是「实机确认」的产物，例：

```kotlin
private val routeCapabilities = linkedMapOf(                        // :41
    HuaweiDeviceRoute.HUAWEI_FREEBUDS3 to HuaweiDeviceCapabilities( // :42
        displayName = "HUAWEI FreeBuds 3",                          // :43
        aliases = setOf("huaweifreebuds3", "freebuds3"),            // :44
        supportsAnc = true,                                         // :45
        supportsAncDirectionDial = true,       // 老机型：档位是旋钮式（0..8）
        supportsGestureConfiguration = true,                        // :47
        hasChargingCase = true,                                     // :48
    ),
    HuaweiDeviceRoute.HUAWEI_FREEBUDS_SE4_ANC to HuaweiDeviceCapabilities(  // :82
        displayName = "HUAWEI FreeBuds SE 4 ANC",                   // :83
        aliases = setOf("huaweifreebudsse4anc", "freebudsse4anc"),  // :84
        supportsAnc = true, supportsTransparency = true,            // :85-86
        supportsAncStateReadback = true, supportsDiscreteAncLevels = true,   // :87-88
        supportsRfcommBattery = true, supportsLowLatencyControl = true,      // :89-90
    ),
    ...
)                                                                   // :206
```

派生产物：

```kotlin
private val normalizedAliasRoutes: Map<String, HuaweiDeviceRoute> = buildMap {  // :208
    routeCapabilities.forEach { (route, caps) ->
        caps.aliases.forEach { alias -> put(alias, route) }        // :210
    }
}
private val broadcastValueByRoute = mapOf(...)                      // :214-232
private val routeByBroadcastValue = broadcastValueByRoute.entries.associate { (r, v) -> v to r }  // :234-236
```

14 个扩展属性（`:238-281`）把表查询变成同义 API：

```kotlin
val HuaweiDeviceRoute.capabilities: HuaweiDeviceCapabilities?   // :238-239
val HuaweiDeviceRoute.displayName: String                        // :241-242  ?: "Unsupported"
val HuaweiDeviceRoute.isSupported: Boolean                       // :244-245  capabilities != null
val HuaweiDeviceRoute.supportsAnc: Boolean                       // :247-248
... （每个能力位一条，共 14 条，:247-281）
```

名称识别 `:303-313`：

```kotlin
fun detectHuaweiDeviceRoute(deviceName: String?): HuaweiDeviceRoute =       // :303
    detectKnownHuaweiDeviceRoute(deviceName).takeIf(::isHuaweiDeviceRouteEnabled)
        ?: HuaweiDeviceRoute.UNSUPPORTED                                    // :304-305
fun detectKnownHuaweiDeviceRoute(deviceName: String?): HuaweiDeviceRoute {   // :307
    val normalizedName = deviceName?.let(::normalizeDeviceName).orEmpty()   // :308
    return normalizedAliasRoutes[normalizedName] ?: HuaweiDeviceRoute.UNSUPPORTED  // :309
}
private fun normalizeDeviceName(deviceName: String): String =               // :312
    deviceName.lowercase().filter { it.isLetterOrDigit() }                   // :313
```

即 `"HUAWEI FreeBuds 5"` → `"huaweifreebuds5"` → 命中 alias。**注意没有 VID 参与**——
全仓库 grep `vid|vendorId|VENDOR_ID` 无匹配；蓝牙 SDP 不暴露 USB 式 VID/PID，所以识别完全靠
**MAC 绑定 > 名称别名 > DeviceInfo modelId**。

### 5.2 三层识别链

`pods/HuaweiDeviceRouteResolver.kt`（69 行）：

```kotlin
object HuaweiDeviceRouteResolver {                                  // :10
    @Volatile private var prefs: SharedPreferences? = null          // :11-12
    private val sessionBindings = ConcurrentHashMap<String, HuaweiDeviceRoute>()  // :13

    fun init(prefs: SharedPreferences) { this.prefs = prefs; sessionBindings.clear() }  // :15-18
    fun refreshBindings() { sessionBindings.clear() }                // :20-22

    fun resolve(address: String?, deviceName: String?): HuaweiDeviceRoute {      // :24
        val normalizedAddress = normalizeAddress(address)            // :25  trim + uppercase
        val persistedRoute = prefs?.let { DeviceRoutePrefs.find(it, normalizedAddress) }  // :26
        if (persistedRoute != null) {                                // :27 ① 持久化的 MAC 绑定优先
            val route = resolveBoundOrNamedRoute(persistedRoute, deviceName)      // :28
            if (route.isSupported && normalizedAddress != null) sessionBindings[normalizedAddress] = route
            else if (normalizedAddress != null) sessionBindings.remove(normalizedAddress)
            return route                                             // :34
        }
        val sessionRoute = normalizedAddress?.let(sessionBindings::get)          // :37-39 ② 本进程会话缓存
            ?.takeIf(::isHuaweiDeviceRouteEnabled)
        val route = resolveBoundOrNamedRoute(sessionRoute, deviceName)          // :40
        if (route.isSupported && normalizedAddress != null) sessionBindings[normalizedAddress] = route
        else if (normalizedAddress != null) sessionBindings.remove(normalizedAddress)
        return route
    }
    private fun normalizeAddress(address: String?): String? =
        address?.trim()?.takeIf(String::isNotEmpty)?.uppercase()      // :49-51
}
```

便捷扩展（所有 `pods/` 代码都用它）：

```kotlin
@SuppressLint("MissingPermission")
fun BluetoothDevice.huaweiDeviceRoute(): HuaweiDeviceRoute {         // :62
    val deviceName = runCatching { name?.takeIf(String::isNotBlank) ?: alias?.takeIf(String::isNotBlank) }.getOrNull()  // :63-66
    val deviceAddress = runCatching { address }.getOrNull()          // :67
    return resolveHuaweiDeviceRoute(deviceAddress, deviceName)       // :68
}
```

**第三层：DeviceInfo modelId 白名单** —— `pods/HuaweiDeviceInfoRoutePolicy.kt`（37 行）**完整清单**：

```kotlin
/** 仅收录已有实机 DeviceInfo 帧证明的 modelId；未知机型绝不靠名称或默认配色猜测。 */   // :3
internal object HuaweiDeviceInfoRoutePolicy {                       // :4
    private val routeByModelId = mapOf(                             // :5
        "000141" to HuaweiDeviceRoute.HUAWEI_FREEBUDS5,             // :6
        "000145" to HuaweiDeviceRoute.HUAWEI_FREEBUDS5I,            // :7
        "000169" to HuaweiDeviceRoute.HUAWEI_FREEBUDS_SE4_ANC,      // :8
        "000135" to HuaweiDeviceRoute.HUAWEI_FREEBUDS4E,            // :9
        "000153" to HuaweiDeviceRoute.HUAWEI_FREEBUDS6I,            // :10
        "000155" to HuaweiDeviceRoute.HUAWEI_FREEBUDS6,             // :11
        "000149" to HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO3,         // :12
        "00016D" to HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5,         // :13
        "000163" to HuaweiDeviceRoute.HUAWEI_FREEBUDS7I,            // :14
        "000174" to HuaweiDeviceRoute.HUAWEI_FREEBUDS7,             // :15
        "000167" to HuaweiDeviceRoute.HUAWEI_FREECLIP2,             // :16
        // FreeClip 2 典藏版：2026-10-11 DeviceInfo 与功能写入确认使用同一控制协议。  // :17
        "000173" to HuaweiDeviceRoute.HUAWEI_FREECLIP2,             // :18
        "00015D" to HuaweiDeviceRoute.HUAWEI_FREEARC,               // :19
        // HUAWEI Eyewear 3（Evian，协议产品名仍上报为 HUAWEI Eyewear）。            // :20
        "000139" to HuaweiDeviceRoute.HUAWEI_EYEWEAR,               // :21
        "00014F" to HuaweiDeviceRoute.HUAWEI_EYEWEAR2,              // :22
    )
    // 同一协议路由允许多个产品身份，目录兜底仍保留首先登记的普通版型号。  // :24
    private val modelIdByRoute = routeByModelId.entries.groupBy { it.value }
        .mapValues { (_, identities) -> identities.first().key }    // :25-26
    fun routeForModelId(modelId: String): HuaweiDeviceRoute? = routeByModelId[modelId]  // :28
    fun modelIdForRoute(route: HuaweiDeviceRoute): String? = modelIdByRoute[route]      // :30
    fun isCompatible(route: HuaweiDeviceRoute, modelId: String): Boolean                // :32
}
```

注意 `:16` 与 `:18` 两个 modelId 指向**同一个** route（FreeClip 2 标准版 / 典藏版），
而 `:25-26` 的反向表只保留**首先登记**的那个——所以「route → modelId」是有损的，只能用于展示，
**不能**用来做写指令的判据。

注意 `000173`（FreeClip 2 典藏版）是为了「实机验证过同一控制协议」才加进白名单的（`:17` 注释），
新机型也必须遵守这条纪律：**没有实机 DeviceInfo 帧就不能加**（`:3` 注释「未知机型绝不靠名称或默认配色猜测」）。

**冲突裁决**在 `config/DeviceRoutePrefs.kt:207-224`：

```kotlin
fun resolveBoundOrNamedRoute(boundRoute: HuaweiDeviceRoute?, deviceName: String?): HuaweiDeviceRoute  // :207
// 绑定的 route 与名称识别结果冲突 -> UNSUPPORTED（拒绝猜测，不选任何一个）
// 否则 bound ?: named ?: UNSUPPORTED
```

➜ 冲突时**返回 UNSUPPORTED 而不是任选其一**，这是安全默认值：宁可功能不出现，也不能对错误机型发指令。

### 5.3 用户手动覆盖模型

`ui/pages/DevicePickerPage.kt`（593 行）：

```kotlin
@Composable
fun DevicePickerPage(                                                // :96
    connectedDeviceName: String = "",
    connectedDeviceAddress: String = "",
    connectingDeviceAddress: String? = null,
    showConnectError: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    onDeviceSelected: (BluetoothDevice, HuaweiDeviceRoute) -> Unit,   // :103
    onConnectedDeviceClick: () -> Unit = {},
    onDismissConnectError: () -> Unit = {},
) {
    val routePrefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }  // :108-110
```

点击设备的处理（`:129-171`）——**先查绑定，再决定是否探测**：

```kotlin
fun selectDevice(device: BluetoothDevice) {
    val route = DeviceRoutePrefs.resolve(                            // :130
        prefs = routePrefs,
        address = runCatching { device.address }.getOrNull(),         // :132
        deviceName = runCatching { device.name ?: device.alias }.getOrNull(),  // :133
    )
    if (route.isSupported) { onDeviceSelected(device, route); return }  // :135-138
    val mayProbe = HuaweiDeviceRouteProbePolicy.mayRequest(           // :139
        bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
        audioDevice = device.isBluetoothAudioDevice(),
        requestAlreadyPending = pendingRouteProbe != null,
    )
    if (!mayProbe) { if (pendingRouteProbe == null) pendingRouteDevice = device; return }  // :145-148
    val generation = routeProbeGeneration.incrementAndGet()           // :149
    val session = HuaweiDeviceRouteProbePolicy.session(               // :150
        address = runCatching { device.address }.getOrNull(),
        generation = generation,
        nonce = UUID.randomUUID().toString(),                         // :153
    )
    ...
    val requestIntent = Intent(HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST).apply {  // :161
        putExtra(HuaweiPodsAction.EXTRA_ROUTE_PROBE_ADDRESS, session.address)      // :162
        putExtra(HuaweiPodsAction.EXTRA_ROUTE_PROBE_GENERATION, session.generation) // :163
        putExtra(HuaweiPodsAction.EXTRA_ROUTE_PROBE_NONCE, session.nonce)          // :164
        setPackage(HuaweiDeviceRouteProbePolicy.RESULT_SENDER_PACKAGE)             // :165 ←只发给 com.android.bluetooth
    }
```

**这是跨进程的"提问-回答"协议**：App 进程（无蓝牙特权，不能建 RFCOMM）发广播给蓝牙进程，
蓝牙进程用自己的 socket 探测 DeviceInfo，再把 modelId 带回来。三道防伪（`:199-235`）：

```kotlin
HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_RESULT -> {
    if (!HuaweiDeviceRouteProbePolicy.isTrustedResultSender(sentFromPackage)) return   // :200
    val pending = pendingRouteProbe ?: return                                          // :203
    ...
    if (!pending.session.matches(resultAddress, resultGeneration, resultNonce)) return  // :214
    val route = HuaweiDeviceRouteProbePolicy.resolveVerifiedRoute(                      // :217
        modelId = intent.getStringExtra(HuaweiPodsAction.EXTRA_ROUTE_PROBE_MODEL_ID),
        subModelId = intent.getStringExtra(HuaweiPodsAction.EXTRA_ROUTE_PROBE_SUB_MODEL_ID),
    )
    pendingRouteProbe = null
    val providerBoundRoute = DeviceRoutePrefs.find(routePrefs, pending.session.address) // :226
    if (route != null && providerBoundRoute == route) {                                 // :230
        routeRefreshToken++                                                             // :231
        onDeviceSelected(pending.device, route)                                          // :232
    } else { pendingRouteDevice = pending.device }                                      // :234
}
```

校验策略对象 `pods/HuaweiDeviceRouteProbePolicy.kt`（85 行）：

```kotlin
const val REQUEST_SENDER_PACKAGE = "moe.chenxy.huaweipods"    // :22
const val RESULT_SENDER_PACKAGE = "com.android.bluetooth"     // :23
const val MIN_PROBE_INTERVAL_MS = 15_000L                     // :24
private val addressRegex = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")         // :26
private val nonceRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")  // :27-29
```

```kotlin
fun mayRequest(bonded: Boolean, audioDevice: Boolean, requestAlreadyPending: Boolean): Boolean =   // :48
    bonded && audioDevice && !requestAlreadyPending                        // :52
/** 即使系统把设备标为 connected，未知 route 仍需进入点选识别，不能直接打开空详情。 */   // :54
fun shouldOpenConnectedDetails(connected: Boolean, resolvedRouteSupported: Boolean): Boolean =     // :55
    connected && resolvedRouteSupported                                    // :58
/** 蓝牙进程再次收紧到已配对且由系统确认仍连接的设备。 */                     // :60
fun mayProbeInBluetoothProcess(bonded: Boolean, systemConnected: Boolean): Boolean =              // :61
    bonded && systemConnected                                              // :64
/** 只接受实包表中已有的现代 modelId；老协议 FreeBuds 3 和未知值返回 null。 */ // :66
fun resolveVerifiedRoute(modelId: String?, subModelId: String?): HuaweiDeviceRoute? {              // :67
    if (subModelId?.matches(subModelIdRegex) != true) return null           // :71
    return modelId?.let { HuaweiDeviceInfoRoutePolicy.routeForModelId(it) } // :72
}
fun cooldownAllows(lastStartedAtMs: Long?, nowMs: Long): Boolean =                             // :75
    lastStartedAtMs == null || nowMs < lastStartedAtMs || nowMs - lastStartedAtMs >= MIN_PROBE_INTERVAL_MS
```

UI 超时看守 `:86-92`：`ROUTE_PROBE_UI_TIMEOUT_MS = 6_500L`（`:86`），`LaunchedEffect` `:183-190` 超时清空
`pendingRouteProbe` 并弹出**手动机型选择器** `DeviceModelPickerDialog`（调用 `:432`，声明 `:458`）：

```kotlin
val allRoutes = remember { enabledHuaweiDeviceRoutes() }          // :464
```

这正是「用户手动覆盖模型」的入口：探测失败时，列出全部 17 个机型让用户自己挑，
选中后 `DeviceRoutePrefs.bind(...)` 写入 MAC → route 绑定。

搜索/排序策略 `ui/pages/DeviceModelPickerPolicy.kt`：`filterDeviceModelRoutes` `:7-16`、
`compactDeviceModelName` `:18-19`（去掉 `"HUAWEI "` 前缀）、`normalizedModelSearchText` `:21-22`。

### 5.4 持久化 —— `config/DeviceRoutePrefs.kt`（228 行）

```kotlin
object DeviceRoutePrefs {
    private const val PREF_KEY_PREFIX = "device_route_v1_"                    // :10
    private val bluetoothAddressPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")  // :11
    private val bindingLock = Any()                                          // :12

    fun find(prefs: SharedPreferences, address: String?): HuaweiDeviceRoute?  // :14
    fun resolve(prefs: SharedPreferences?, address: String?, deviceName: String?): HuaweiDeviceRoute  // :21
    fun bind(prefs: SharedPreferences, service: XposedService?, address: String, route: HuaweiDeviceRoute)  // :30
    fun bind(prefs: SharedPreferences, address: String, route: HuaweiDeviceRoute): Boolean  // :46
    fun bindIfAbsent(...): Boolean                                            // :58 / :73
    fun syncWithRemote(prefs: SharedPreferences, service: XposedService?)     // :109
    fun isBindingKey(key: String?): Boolean                                   // :139-141
    private fun bindingKey(address: String?): String?                         // :143-146
    private fun normalizeAddress(address: String): String                     // :148-151
    private fun HuaweiDeviceRoute.storageId(): String                         // :153-172
    private fun routeFromStorage(value: String): HuaweiDeviceRoute?           // :174-193
    private fun validBindings(prefs: SharedPreferences): Map<String, HuaweiDeviceRoute>  // :195-204
}
```

`bindIfAbsent` 的注释说明了它的用途（`:54-57`）：

```kotlin
/**
 * 自动识别只允许填补空绑定或确认同一路由，绝不覆盖用户已经手选的不同路由。
 * 本地与远程写入和手动 bind 共用一把进程锁，封住 watchdog 后迟到 Binder 的竞态。
 */
```

`bindLocked` 的 `runCatching` 注释（`:88-89`）：

```kotlin
// 注入进程拿到的 LSPosed RemotePreferences 可能是只读实现。
// 绑定应由模块 App 主动完成，任何误用都不能让系统宿主进程崩溃。
```

`storageId()`（`:153-172`）把枚举映射成稳定字符串（`freebuds3`…`freebuds_pro5`、`freeclip2`、`eyewear2`、`unsupported`），
**枚举重命名不会破坏用户既有绑定**。

---

## 6. 状态管理

### 6.1 状态词汇表 —— `pods/PodState.kt`（42 行）

```kotlin
enum class NoiseControlMode(val broadcastStatus: Int) {              // :4
    UNKNOWN(0), OFF(1), NOISE_CANCELLATION(2), TRANSPARENCY(3);      // :5-10
    companion object { fun fromBroadcastStatus(status: Int): NoiseControlMode }  // :12-13
}
val NoiseControlMode.isKnown: Boolean              // :17
val NoiseControlMode.isNoiseCancellation: Boolean  // :19
val NoiseControlMode.isTransparency: Boolean       // :21

internal data class HuaweiAncState(val mode: NoiseControlMode, val subMode: Int? = null)  // :23-26
internal const val UNKNOWN_HUAWEI_ANC_SUBMODE = -1                 // :29

enum class HuaweiAncLevel { ADAPTIVE, LIGHT, BALANCED, DEEP }       // :32-37
enum class HuaweiTransparencyMode { STANDARD, VOICE }               // :39-42
```

`broadcastStatus` 的四个值 (`0/1/2/3`) **与小米系统广播使用同一套整数编码**，
这是整个模块能跟 MIUI 系统 UI 对话的前提。

### 6.2 会话状态机 —— `pods/HuaweiHfpController.kt`（2570 行，114.7 KB）

`object HuaweiHfpController` `:29`，是**唯一**的状态持有者。

**常量（全部带注释语义）** `:30-50`：

| 常量 | 值 | 行 | 含义 |
|---|---|---|---|
| `ANC_CONFIRM_DELAY_MS` | `450L` | `:31` | 写 ANC 后延迟回读确认 |
| `ANC_REFRESH_MIN_INTERVAL_MS` | `750L` | `:32` | ANC 回读最小间隔 |
| `BACKGROUND_BATTERY_REFRESH_INTERVAL_MS` | `30_000L` | `:33` | 后台电池轮询 30s |
| `GESTURE_CONFIRM_DELAY_MS` | `300L` | `:34` | |
| `EQUALIZER_CONFIRM_DELAY_MS` | `450L` | `:36` | |
| `FREECLIP2_SMART_AUDIO_BRIDGE_TIMEOUT_MS` | `1_500L` | `:39` | 官方桥接超时 |
| `DEVICE_INFO_REFRESH_MIN_INTERVAL_MS` | `60_000L` | `:41` | DeviceInfo 60s 节流 |
| `LOW_LATENCY_AUTO_APPLY_DELAY_MS` | `2_500L` | `:42` | |
| `LOW_LATENCY_AUTO_APPLY_MAX_ATTEMPTS` | `2` | `:44` | |
| `FREEBUDS3_SNAPSHOT_TTL_MS` | `10 * 60_000L` | `:45` | 老机型快照 10min |
| `CACHED_NOTIFICATION_RESTORE_DELAYS_MS` | `longArrayOf(400L, 1_200L)` | `:46` | 进程重启后两次尝试恢复通知 |
| `EXTRA_STATE_CACHED` | `"state_cached"` | `:47` | 广播里标记「这是缓存值」 |

**缓存字段** `:52-90`：

```kotlin
private var context: Context? = null                             // :52
private var device: BluetoothDevice? = null                      // :53
private var sessionRoute = HuaweiDeviceRoute.UNSUPPORTED          // :54
private var receiverRegistered = false                            // :55
@Volatile private var currentBattery: BatteryParams? = null       // :56-57
@Volatile private var currentBatteryIsCached = false              // :58-59
@Volatile private var currentAnc = HuaweiAncState(NoiseControlMode.UNKNOWN)  // :60-61
@Volatile private var currentAncIsCached = false                  // :62-63
private var currentAncLevel = 0                                   // :64
private var currentTransparencySubMode = 0xFF                     // :65
private var lastDispatchedAncLevel: Int? = null                   // :66  ←去重用
private var connectedBroadcastSent = false                        // :67  ←一次性广播闩
private var lastBatteryRequestAt = 0L                             // :68  ←以下全是节流时间戳
private var lastAncRequestAt = 0L                                 // :69
private var lastGestureStateRequestAt = 0L                        // :70
private var lastEqualizerStateRequestAt = 0L                      // :71
private var lastFreeClip2AudioStateRequestAt = 0L                 // :72
private var lastDeviceInfoRequestAt = 0L                          // :73
private var batteryRequestInFlight = false                        // :74  ←以下全是防重入
private var ancRequestInFlight = false                            // :75
private var deviceInfoRequestInFlight = false                     // :76
private var equalizerStateRequestInFlight = false                 // :77
private var deviceIdentityPublishInFlight = false                 // :78
private var deviceIdentityPublished = false                       // :79
private var deviceInfoQueryUnavailableLogged = false              // :80  ←日志只打一次
private var currentDeviceInfoIdentity: HuaweiDeviceInfoIdentity? = null  // :81
private var sessionGeneration = 0L                                // :82  ←会话代际
private val sessionStateLock = Any()                              // :89
private val mainHandler = Handler(Looper.getMainLooper())         // :90
```

**每个异步请求都是「时间戳 + inFlight 布尔」双闸门**。以 `requestPrivateBattery()` `:1412-1456` 为例：

```kotlin
private fun requestPrivateBattery() {
    val requestedRoute = sessionRoute                              // :1421
    batteryRequestInFlight = true                                  // :1423
    HuaweiL2capAncController.requestBattery(context, device, route, onBattery = { battery ->
        if (!isCurrentSession(requestedGeneration, address, route)) return@requestBattery  // :1429
        normalizeHuaweiPrivateBattery(...)                          // :1431
        currentBattery = ...; currentBatteryIsCached = false        // :1432-1433
        sendConnectionState("connected")                            // :1434
        sendConnected()                                             // :1435
        sendBattery(...)                                            // :1436
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(...)       // :1437
        maybeShowBatteryIsland(...)                                 // :1442
    }, onComplete = { batteryRequestInFlight = false })             // :1447-1454
}
```

节流判据 `now - lastBatteryRequestAt < 10_000L`（`:1418`）+ `batteryRequestInFlight`（`:1416`）+
`sessionRoute.supportsRfcommBattery`（`:1415`）。

**会话建立/切换** `ensureSession(context, device, route)` `:1308-1368`：

```kotlin
val previousDevice = this.device                                 // :1313
val deviceChanged = previousDevice != null && !previousDevice.address.equals(device.address, ignoreCase = true)  // :1314-1315
val routeChanged = sessionRoute.isSupported && sessionRoute != route      // :1316
val newSession = previousDevice == null || deviceChanged || routeChanged  // :1317
if (deviceChanged || routeChanged) {
    stopBackgroundBatteryRefresh()                                // :1319
    HuaweiL2capAncController.disconnect(previousDevice)           // :1320 ←换设备必须断旧 socket
}
if (newSession) {
    freeBuds3SnapshotStore.clearIfIdentityChanged(context, device.address, route)  // :1323
    currentBattery = null; currentBatteryIsCached = false         // :1324-1325
    currentAnc = HuaweiAncState(NoiseControlMode.UNKNOWN)         // :1326
    currentAncIsCached = false                                    // :1327
    currentAncLevel = if (route.supportsDiscreteAncLevels) route.defaultAncSubMode ?: 0 else 0  // :1328-1332
    currentTransparencySubMode = defaultTransparencySubMode(route) // :1333
    ... 全部 last*RequestAt = 0L / *InFlight = false ...           // :1336-1349
    batteryIslandTriggerPolicy.onNewSession()                     // :1355
    sessionGeneration++                                           // :1356
    lowLatencyWritePolicy.resetSession()                          // :1357
}
this.context = context.applicationContext ?: context              // :1363
this.device = device; sessionRoute = route                        // :1364-1365
registerReceiver()                                                // :1366
startBackgroundBatteryRefresh()                                   // :1367
```

`sessionGeneration` 是所有异步回调的**代际令牌**：`setAncMode` 里先 `val requestedGeneration = sessionGeneration`（`:579`），
回调里 `if (!isCurrentSession(requestedGeneration, requestedAddress, requestedRoute)) return@...`（`:595`）。

**连接进入** `connectPod(context, device)` `:397-423`：

```kotlin
fun connectPod(context: Context, device: BluetoothDevice) {
    val route = device.huaweiDeviceRoute()                        // :398
    if (!route.isSupported) { Log.w(TAG, "Huawei session skipped: unsupported device=${device.address}"); return }  // :399-402
    val restoredCachedBattery = synchronized(sessionStateLock) {   // :403
        ensureSession(context, device, route)                     // :404
        val restored = restoreFreeBuds3StateAfterProcessRestart(context, device, route)  // :405
        sendConnectionState("connecting")                         // :406
        sendConnected()                                           // :407
        restored?.battery?.let { sendBattery(it, cached = true) } // :408
        restored?.moduleAnc?.let { sendAnc(it, cached = true) }    // :409
        restored?.battery != null                                 // :410
    }
    if (restoredCachedBattery) scheduleCachedNotificationRestore()  // :412
    requestOfficialImageIdentity(force = true)                    // :413
    requestAncState(force = true)                                 // :414
    requestPrivateBattery()                                       // :415
    scheduleAutoLowLatency()                                      // :416
    if (route == HuaweiDeviceRoute.HUAWEI_FREECLIP2) requestFreeClip2AudioState(force = true)  // :417-419
    if (route == HuaweiDeviceRoute.HUAWEI_FREEBUDS6I) requestHuaweiEqualizerState(force = true) // :420-422
}
```

**顺序很重要**：先推送缓存状态（让 UI 立刻有内容），再并行发起真实查询。

**断开清理** `disconnectedPod(context, device)` `:425-476` —— 27 行逐字段重置，是整套状态管理的"反面教材清单"：

```kotlin
if (this.device?.address != device.address) return                 // :427  ←地址不匹配直接忽略（迟到回调）
stopBackgroundBatteryRefresh()                                     // :437
MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, device)// :438
sendAppBroadcast(HuaweiPodsAction.ACTION_PODS_DISCONNECTED) { putExtra("address", device.address) }   // :439-441
sendExternalBroadcast(HuaweiPodsAction.ACTION_PODS_DISCONNECTED) { ... }                              // :442-444
currentBattery = null; currentBatteryIsCached = false              // :445-446
currentAnc = HuaweiAncState(NoiseControlMode.UNKNOWN); currentAncIsCached = false  // :447-448
currentAncLevel = 0; currentTransparencySubMode = 0xFF; lastDispatchedAncLevel = null  // :449-451
connectedBroadcastSent = false                                     // :452
lastBatteryRequestAt = 0L ... lastDeviceInfoRequestAt = 0L         // :453-458
batteryRequestInFlight = false ... deviceIdentityPublishInFlight = false  // :459-463
deviceIdentityPublished = false; deviceInfoQueryUnavailableLogged = false  // :464-465
currentDeviceInfoIdentity = null                                   // :466
cancelAutoLowLatency(); sessionGeneration++                        // :467-468
lowLatencyWritePolicy.resetSession(); freeClip2AudioStateTracker.reset()  // :469-470
mainHandler.removeCallbacks(freeClip2AudioConfirmRunnable)         // :471
cancelPendingSmartAudioSpatialWrite(); cancelPendingSmartAudioAudioQuery()  // :472-473
this.device = null; this.context = null                            // :474-475
```

### 6.3 注入点：A2DP 连接状态

`hook/HeadsetStateDispatcher.kt`（490 行），`object HeadsetStateDispatcher : HookContext()` `:34`。

**为什么选 A2DP 而不是 HFP**：华为耳机的 A2DP 连接状态稳定可观测，而 HFP 的 `AT` 命令会被系统 HFP 栈吃掉。

```kotlin
override fun onHook() {                                            // :64
    acceptingCallbacks = true                                      // :65
    runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()?.let(::registerAppRequestReceiver)               // :66-69
    runCatching {
        hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {  // :71
            registerAppRequestReceiver(instance as? Context)       // :72
        }
    }.onFailure { Log.w("HuaweiPods", "AdapterService.onCreate hook skipped", it) }  // :74-76

    val a2dpClass = findClass("com.android.bluetooth.a2dp.A2dpService")   // :78
    val connectionMethod = listOf("handleConnectionStateChanged", "connectionStateChanged")  // :79
        .firstNotNullOfOrNull { name ->
            runCatching {
                a2dpClass.getDeclaredMethod(name, BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }                // :82-85
            }.getOrNull()
        } ?: throw NoSuchMethodException("A2dpService connection state callback")  // :87
    Log.i("HuaweiPods", "A2DP connection callback=${connectionMethod.name}")       // :88
    hookAfter(connectionMethod) {                                  // :89
        val currState = args[2] as Int                             // :90
        val fromState = args[1] as Int                             // :91
        val device = args[0] as BluetoothDevice?                   // :92
        val handler = getObjectField(instance, "mHandler") as Handler   // :93
        if (device == null || currState == fromState) return@hookAfter  // :94-96
        postTracked(handler) {                                     // :97
            runCatching {
                val normalizedAddress = device.address.uppercase() // :99
                if (currState == BluetoothHeadset.STATE_CONNECTED) connectedA2dpAddresses.add(normalizedAddress)  // :100-101
                else if (currState == BluetoothHeadset.STATE_DISCONNECTING ||
                         currState == BluetoothHeadset.STATE_DISCONNECTED) connectedA2dpAddresses.remove(normalizedAddress)  // :102-107
                val isHuawei = isHuaweiPod(device)             // :108
                val context = instance as ContextWrapper       // :110
                registerAppRequestReceiver(context)            // :111
                if (!isHuawei) return@runCatching              // :112
                if (currState == BluetoothHeadset.STATE_CONNECTED) {          // :114
                    connectedHuaweiA2dpAddresses.add(normalizedAddress)       // :115
                    setHeadsetIcon(context, visible = true, reason = "connected")  // :116
                    startIconWatchdog(context)                                // :117
                    HuaweiHfpController.connectPod(context, device)           // :118
                } else if (currState == ...DISCONNECTING || ...DISCONNECTED) {// :119
                    connectedHuaweiA2dpAddresses.remove(normalizedAddress)    // :120
                    if (connectedHuaweiA2dpAddresses.isEmpty()) {
                        stopIconWatchdog()                                    // :122
                        setHeadsetIcon(context, visible = false, reason = "disconnected")  // :123
                    }
                    HuaweiHfpController.disconnectedPod(context, device)      // :125
                }
            }.onFailure { Log.e("HuaweiPods", "A2DP state callback failed without interrupting Bluetooth", it) }  // :127-129
        }
    }
}
```

四个防御点：

1. `currState == fromState` 直接返回（`:94`）——A2DP 会重复回调相同状态。
2. 回调用 `postTracked(handler)` 派发到宿主的 `mHandler`（`:97`），**不在 hook 线程里做重活**，
   因为 `connectPod` 会建 socket。
3. `runCatching` 全包裹 + `onFailure` 只记日志（`:127-129`），注释明说「without interrupting Bluetooth」。
4. `isHuaweiPod` 用 `runCatching { device.huaweiDeviceRoute().isSupported }.getOrDefault(false)`（`hook/HeadsetStateDispatcher.kt:467`）。

`registerAppRequestReceiver` `:255-326` 的作用是「让模块 App 能反向下命令」：
`if (context == null || appRequestReceiverRegistered) return`（`:256`）保证每进程只注册一次，
receiver 用 `HuaweiPodsAction.canonical(action)` 分派（`:262`）：
`ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 回一个 `ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE` 心跳
（带 `EXTRA_MODULE_BUILD_ID`，`:265-269`），`ACTION_CONNECT_POD_REQUEST` 拿 `"device"` Parcelable 后
校验 `isDeviceConnected` 再 `HuaweiHfpController.connectPod`（`:271-288`），
`ACTION_DEVICE_ROUTE_PROBE_REQUEST` 走 `mayProbeInBluetoothProcess(bonded, systemConnected)`（`:343,:376,:400`）
并受 `ROUTE_PROBE_WATCHDOG_MS = 5_500L`（`:35,:368`）看守。
拒绝时调 `notifyRejectedDevice` `:442` 回一个 `ACTION_PODS_CONNECTION_STATE_CHANGED`
（extras：`address`/`device_name`/`state`/`reason`/`supported`，`:444-464`）。

图标看门狗 `HEADSET_ICON_WATCHDOG_MS = 2_500L`（`:36`）每 2.5s 重新断言耳机图标（`:53-62`），
因为小米系统会在某些时机把图标擦掉。

### 6.4 广播契约

所有 action 集中定义在 `utils/miuiStrongToast/data/HuaweiPodsAction.kt`（116 行）：

```kotlin
object HuaweiPodsAction {
    private const val PREFIX = "chen.action.huaweipods"           // :6
    const val EXTRA_DEVICE_ROUTE = "device_route"                 // :8
    ...
    const val ACTION_PODS_CONNECTED            = PREFIX + ".pods_connected"              // :57
    const val ACTION_PODS_DISCONNECTED         = PREFIX + ".pods_disconnected"           // :58
    const val ACTION_CONNECT_POD_REQUEST       = PREFIX + ".connect_pod_request"         // :59
    const val ACTION_DEVICE_ROUTE_PROBE_REQUEST = PREFIX + ".device_route_probe_request" // :60
    const val ACTION_DEVICE_ROUTE_PROBE_RESULT = PREFIX + ".device_route_probe_result"   // :61
    const val ACTION_PODS_CONNECTION_STATE_CHANGED = PREFIX + ".pods_connection_state_changed"  // :62
    const val ACTION_PODS_BATTERY_CHANGED      = PREFIX + ".pods_battery_changed"        // :63
    const val ACTION_ANC_SELECT                = PREFIX + ".anc_select"                  // :64
    const val ACTION_PODS_ANC_CHANGED          = PREFIX + ".pods_anc_select"             // :65
    const val ACTION_HUAWEI_ANC_LEVEL_SET      = PREFIX + ".huawei_anc_level_set"        // :66
    const val ACTION_HUAWEI_ANC_LEVEL_CHANGED  = PREFIX + ".huawei_anc_level_changed"    // :67
    const val ACTION_HUAWEI_ANC_REFRESH        = PREFIX + ".huawei_anc_refresh"          // :68
    ...
    const val ACTION_REFRESH_STATUS            = PREFIX + ".refresh_status"              // :75
    const val ACTION_CYCLE_ANC                 = PREFIX + ".cycle_anc"                   // :76
    const val ACTION_RFCOMM_LOG                = PREFIX + ".rfcomm_log"                  // :77
    const val ACTION_HUAWEI_LOW_LATENCY_SET    = PREFIX + ".huawei_low_latency_set"      // :90
    const val ACTION_HUAWEI_LOW_LATENCY_CHANGED= PREFIX + ".huawei_low_latency_changed"  // :91
    fun canonical(action: String?): String? = action                                      // :105
    fun matches(action: String?, expected: String): Boolean = canonical(action) == expected  // :107
}
fun IntentFilter.addHuaweiPodsAction(action: String): IntentFilter          // :110-112
fun IntentFilter.addHuaweiPodsActions(vararg actions: String): IntentFilter // :114-116
```

**两个发送通道**（`HuaweiHfpController.kt`）：

```kotlin
private fun sendAppBroadcast(action: String, fill: Intent.() -> Unit = {}) {   // :2325
    val ctx = context ?: return
    val currentDevice = device
    Intent(action).apply {
        putExtra("vendor", "huawei")                                          // :2329
        encodeHuaweiDeviceRouteForBroadcast(sessionRoute)?.let {
            putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }               // :2330-2332
        currentDevice?.let { putExtra("address", it.address); putExtra("device_name", it.name ?: it.alias ?: "") }  // :2333-2336
        fill()
        setPackage(BuildConfig.APPLICATION_ID)                                // :2338 ←只发给模块自己
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)                             // :2339
        ctx.sendBroadcast(this)
    }
}

private fun sendExternalBroadcast(action: String, fill: Intent.() -> Unit = {}) {  // :2344
    val ctx = context ?: return
    val currentDevice = device
    listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->  // :2347
        Intent(action).apply { ...; setPackage(targetPackage); addFlags(FLAG_RECEIVER_FOREGROUND); ctx.sendBroadcast(this) }
    }
}
```

即**同一份状态广播两遍**：一遍给模块 App（UI 用），一遍逐个包名给三个 Xiaomi 系统进程
（`setPackage` 保证不泄露给第三方）。

状态推送函数（全部 `private`，只由状态机调用）：

| 函数 | 行 | 发出的 action | extras |
|---|---|---|---|
| `sendConnected(force: Boolean = false)` | `:2162-2174` | `ACTION_PODS_CONNECTED` | `address`、`device_name`；`connectedBroadcastSent` 闩（`:2163`） |
| `sendConnectionState(state: String)` | `:2176-2184` | `ACTION_PODS_CONNECTION_STATE_CHANGED` | `address`、`device_name`、`state` |
| `sendBattery(battery: BatteryParams, cached: Boolean = currentBatteryIsCached)` | `:2186-2200` | `ACTION_PODS_BATTERY_CHANGED` | `status`(Parcelable) + 9 个扁平标量（`:2365-2375`）+ `state_cached` |
| `sendAnc(state: HuaweiAncState, cached: Boolean = currentAncIsCached)` | `:2202-2216` | `ACTION_PODS_ANC_CHANGED` | `status = mode.broadcastStatus`、可选 `submode`、`state_cached` |
| `sendAncLevel(level: Int)` | `:2218-2225` | `ACTION_HUAWEI_ANC_LEVEL_CHANGED` | `level` |

`putBatteryExtras` `:2365-2375` 把 `BatteryParams` 摊平，方便接收方不必解 Parcelable：
`left_battery/left_charging/left_connected`、`right_*`、`case_*` 共 9 个键。

### 6.5 接收：广播路由表

`HuaweiHfpController` 内部的匿名 `BroadcastReceiver`（`:223`+）同时充当**命令入口**。
注册于 `registerReceiver()` `:2131`，action 到处理逻辑的映射：

| action | 处理方法 | 行 |
|---|---|---|
| `ACTION_ANC_SELECT` | 直接设 ANC | `:289-299` |
| `ACTION_CYCLE_ANC` | 循环 OFF→NC→Transparency | `:300-312` |
| `ACTION_HUAWEI_ANC_LEVEL_SET` | `setAncLevel(level)`（`:1111`） | `:313-316` |
| `ACTION_HUAWEI_ANC_REFRESH` | `requestAncState(force=true)` | `:317-322` |
| `ACTION_HUAWEI_LOW_LATENCY_SET` | `setLowLatency(intent)`（`:1554`） | `:323-326` |
| `ACTION_HUAWEI_LEGACY_DEBUG_SEND` | `sendLegacyDebugHex`（`:1170`） | `:327-330` |
| `ACTION_HUAWEI_GESTURE_SET/REFRESH` | `setGesture`（`:1197`） | `:331-341` |
| `ACTION_FREECLIP2_AUDIO_SET` | `setFreeClip2Audio`（`:724`） | `:342-345` |
| `ACTION_HUAWEI_SPATIAL_SET/REFRESH` | `handleFreeBuds7Spatial`（`:670`） | `:346-351` |
| `ACTION_HUAWEI_EQUALIZER_PRESET_SET/REFRESH` | `setHuaweiEqualizerPreset`（`:1858`） | `:358-367` |
| `ACTION_SHARED_CONTROL_REQUEST` | `HuaweiSharedControlHost` | `:368-383` |
| `ACTION_SMART_AUDIO_FREECLIP2_RESULT/QUERY_RESULT/STATE` | 桥接结果 | `:384-392` |

所有入口都先过 `targetsCurrentSession(intent, requireAddress)` `:630`，防跨设备指令串台。

### 6.6 ANC 写-读-确认闭环

`setAncMode(status, subMode)` `:520-628` 是状态机最复杂的路径：

```kotlin
val targetMode = NoiseControlMode.fromBroadcastStatus(status)     // :537
if (!targetMode.isKnown()) { sendAnc(currentAnc); return }        // :538-542  ←未知值回吐当前状态
if (targetMode == TRANSPARENCY && !sessionRoute.supportsTransparency) { sendAnc(currentAnc); return }  // :543-547
if (targetMode == NOISE_CANCELLATION && sessionRoute.supportsDiscreteAncLevels &&
    subMode != null && !sessionRoute.supportsAncSubMode(subMode)) { sendAnc(currentAnc); return }      // :548-557
val rememberedSubMode = when (targetMode) {                       // :558
    NOISE_CANCELLATION -> subMode ?: currentAncLevel.takeIf { sessionRoute.supportsDiscreteAncLevels }
    TRANSPARENCY -> subMode ?: currentTransparencySubMode
    else -> null
}
val normalizedSubMode = normalizeHuaweiAncSubMode(route, targetMode, rememberedSubMode, currentAnc)  // :564-569
val commandSubMode = huaweiAncCommandSubMode(route, targetMode, normalizedSubMode, currentAnc)       // :571-576
val requestedGeneration = sessionGeneration                       // :579
// FF is a verified mode-transition token, not a semantic ANC level. The subsequent
// readback supplies the actual level retained by the earbuds.                 // :580-581
HuaweiL2capAncController.setAncMode(context, device, route, targetMode, commandSubMode) { success ->  // :587
    synchronized(sessionStateLock) {
        if (!isCurrentSession(requestedGeneration, requestedAddress, requestedRoute)) return@setAncMode // :595
        val supportsReadback = requestedRoute.supportsAncStateReadback                               // :596
        if (!success) {
            if (supportsReadback) scheduleAncConfirmation(requestedGeneration, requestedAddress, requestedRoute, previousState)  // :600
        }
    }
}
```

**关键洞察（`:580-581`）**：`0xFF` 是「切换模式但不指定档位」的过渡令牌，不是语义档位；
真正的档位必须靠随后的回读确定。这也是为什么 `modernAncLevels[0xFF]` 直接复用 `modernFreeBudsEnabled[true]`
（`HuaweiAncPackets.kt:26`）——**它们本来就是同一个包**。

`requestAncState(force, fallbackState)` `:1773-1818`：

```kotlin
if (HuaweiAncPackets.currentStateQuery(requestedRoute) == null) return false  // :1780 ←无回读能力直接放弃
if (ancRequestInFlight) ...                                      // :1781
if (now - lastAncRequestAt < ANC_REFRESH_MIN_INTERVAL_MS) ...     // :1783
HuaweiL2capAncController.requestAncState(...) { state ->          // :1788
    if (!isCurrentSession(...)) return@requestAncState            // :1793
    currentAnc = state; currentAncIsCached = false                // :1808-1810
    rememberAncSubMode(state)                                     // :1811
    sendAnc(); sendAncLevel()                                     // :1812-1814
}
```

### 6.7 AT 命令路径（老机型兜底）

```kotlin
fun handleAtCommand(context: Context, device: BluetoothDevice, text: String): BatteryParams? {  // :482
    val parsed = HuaweiBatteryParser.parse(text) ?: return null   // :487
    val route = device.huaweiDeviceRoute()                        // :488
    if (!route.isSupported) return null                           // :489-492
    ensureSession(context, device, route)                         // :494
    ... sendConnectionState / sendConnected / sendBattery / strongToast / island ...  // :510-514
}
```

### 6.8 热重载状态迁移

```kotlin
fun closeForHotReload() {                                          // :145
    unregister receiver; HuaweiL2capAncController.disconnect(device)  // :166
    device = null; context = null; sessionRoute = UNSUPPORTED
    currentBattery = null; currentAnc = HuaweiAncState(UNKNOWN); ...
}
fun saveHotReloadState(outState: Bundle)                          // :182-190 ←只写 Bundle 基本类型
fun hotReloadSessionAddress(savedState: Bundle): String?          // :192-193
fun restoreHotReloadState(savedState: Bundle)                     // :196-221
```

Bundle key：`HOT_RELOAD_ADDRESS_KEY` / `HOT_RELOAD_ROUTE_KEY` / `HOT_RELOAD_BATTERY_KEY`（`:48-50`），
后者是 `int array`（`:185` 附近），因为 `BatteryParams` 不是 Bundle 友好类型。
`HookContext.onSaveHotReloadState` 的注释约束（`hook/HookContext.kt:48`）：
「只允许写入 Framework Bundle 支持的基本类型、String 及其数组。」

---

## 7. Super Island + 通知

### 7.1 三层开关

`config/NotificationPresentationPolicy.kt`（48 行）是**纯策略对象**（无 Android 依赖，可单测）：

```kotlin
/** Pure policy shared by preferences and notification producers. */          // :3
internal object NotificationPresentationPolicy {
    const val TEMPORARY_ISLAND_TIMEOUT_SECONDS = 8                            // :5
    // 系统协议默认有效期为一小时；常驻使用半小时一次的静默续期，避免极大超时值溢出。  // :6
    const val PERSISTENT_ISLAND_TIMEOUT_SECONDS = 3_600                       // :7
    const val PERSISTENT_ISLAND_REFRESH_MS = 1_800_000L                       // :8  ←30min

    fun shouldKeepBatteryIsland(enabled: Boolean, effectiveMode: Int,
                                persistentNotificationEnabled: Boolean,
                                hasConnectedEarBattery: Boolean): Boolean =   // :10
        enabled && hasConnectedEarBattery && when (effectiveMode) {           // :15
            ConfigManager.ISLAND_MODE_MODULE -> true                          // :16
            ConfigManager.ISLAND_MODE_OFFICIAL -> persistentNotificationEnabled  // :17
            else -> false                                                     // :18
        }
    fun batteryIslandTimeoutSeconds(persistent: Boolean): Int =                // :21
        if (persistent) PERSISTENT_ISLAND_TIMEOUT_SECONDS else TEMPORARY_ISLAND_TIMEOUT_SECONDS
    fun normalizedIslandStyle(value: Int): Int = ...                          // :24-29
    fun resolveSuperIslandEnabled(explicitValue: Boolean?, storedValue: Boolean, storedMode: Int): Boolean  // :31
    fun effectiveIslandMode(enabled: Boolean, style: Int): Int =               // :41
        if (enabled) normalizedIslandStyle(style) else ConfigManager.ISLAND_MODE_NONE
    fun attachesOfficialIsland(effectiveMode: Int): Boolean =                  // :44
        effectiveMode == ConfigManager.ISLAND_MODE_OFFICIAL
    fun shouldPostPersistentNotification(enabled: Boolean): Boolean = enabled  // :47
}
```

模式常量（`config/ConfigManager.kt:42-44`）：`ISLAND_MODE_NONE=0` / `ISLAND_MODE_OFFICIAL=1` / `ISLAND_MODE_MODULE=2`。
`ConfigManager.islandMode()` `:80-83` 是 `effectiveIslandMode(superIslandEnabled, islandMode)` 的短路封装——
**开关与样式二选一即可关掉整个岛**。

### 7.2 触发节流 —— `pods/BatteryIslandTriggerPolicy.kt`（25 行）

```kotlin
/** 控制连接电量岛只在有效且合适的时机触发一次。 */                       // :3
internal class BatteryIslandTriggerPolicy(
    private val reconnectCooldownMs: Long = 30_000L,                     // :5
) {
    private val lastTriggerAtByAddress = mutableMapOf<String, Long>()     // :7
    private var sessionHandled = false                                    // :8

    @Synchronized fun onNewSession() { sessionHandled = false }           // :10-13

    @Synchronized
    fun shouldTrigger(address: String, hasConnectedEarBattery: Boolean, now: Long): Boolean {  // :16
        if (sessionHandled || !hasConnectedEarBattery) return false       // :17
        val key = address.uppercase()                                     // :18
        val lastTriggerAt = lastTriggerAtByAddress[key]                   // :19
        sessionHandled = true                                             // :20 ←先置位再判断冷却
        if (lastTriggerAt != null && now - lastTriggerAt < reconnectCooldownMs) return false  // :21
        lastTriggerAtByAddress[key] = now                                 // :22
        return true                                                       // :23
    }
}
```

**注意 `:20` 在冷却判断之前就把 `sessionHandled` 置 true**——这是有意的：一个会话内最多尝试一次，
即使被冷却拦下也不再重试（避免 30s 内反复弹岛）。

调用点 `HuaweiHfpController.maybeShowBatteryIsland` `:1751-1771`：

```kotlin
private fun maybeShowBatteryIsland(context: Context, battery: BatteryParams, device: BluetoothDevice) {
    if (ConfigManager.islandMode() != ConfigManager.ISLAND_MODE_MODULE) return   // :1756
    val hasConnectedEarBattery = left?.isConnected == true || right?.isConnected == true  // :1757
    if (!batteryIslandTriggerPolicy.shouldTrigger(address, hasConnectedEarBattery,
            SystemClock.elapsedRealtime())) return                              // :1758-1763
    runCatching { MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(context, battery, device) }  // :1764-1770
}
```

### 7.3 岛的构造 —— `utils/FocusIslandUtil.kt`（197 行）

`object FocusIslandUtil` `:20`，常量：

```kotlin
private const val TAG = "HuaweiPods-FocusIsland"       // :21
private const val CHANNEL_ID = "huaweipods_focus_island"  // :22
private const val CHANNEL_NAME = "HuaweiPods Battery"     // :23
private const val NOTIFICATION_ID = 10086                 // :24
fun closeForHotReload()                                    // :31
fun showBatteryIsland(context: Context, prefs: SharedPreferences,
                       batteryParams: BatteryParams, address: String): Boolean  // :36
fun cancelBatteryIsland(context: Context)                 // :172
private fun scheduleDismiss(notificationManager: NotificationManager, persistent: Boolean)  // :183
```

`showBatteryIsland` 的前置条件（`:42-69`）：

```kotlin
try {
    if (!ConfigManager.superIslandEnabled()) return false              // :43
    val persistent = ConfigManager.persistentIslandEnabled()           // :44
    val leftConnected = batteryParams.left?.isConnected == true        // :45
    val rightConnected = batteryParams.right?.isConnected == true      // :46
    // Need at least one ear connected
    if (!leftConnected && !rightConnected) return false                // :49
    val device = runCatching { context.getSystemService(BluetoothManager::class.java)
        ?.adapter?.getRemoteDevice(address) }.getOrNull()              // :51-53
    val leftBitmap  = PodImageLoader.loadIslandLeftBitmap(context, prefs, address)   // :59
    val rightBitmap = PodImageLoader.loadIslandRightBitmap(context, prefs, address)  // :60
    if (leftBitmap == null || rightBitmap == null) { Log.e(TAG, "Failed to decode earphone icon bitmaps"); return false }  // :62-65
    // 使用 createWithBitmap 直接嵌入图片数据，SystemUI 无需再访问模块资源   // :67
    val leftIcon  = Icon.createWithBitmap(leftBitmap)                  // :68
    val rightIcon = Icon.createWithBitmap(rightBitmap)                 // :69
```

`:67` 这条注释是一条**硬约束**：SystemUI 是独立进程，无法访问模块的 drawable 资源，
因此必须把 bitmap 通过 `Icon.createWithBitmap` 序列化进 Notification extras。

Channel 创建（`:71-85`）——注意 `IMPORTANCE_DEFAULT` + 静音 + 无振动 + 允许气泡：

```kotlin
nm.createNotificationChannel(
    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
        setSound(null, null)                                           // :74
        enableVibration(false)                                         // :75
        setAllowBubbles(true)                                          // :76
        setLockscreenVisibility(if (ConfigManager.lockscreenNotificationEnabled())
            Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_SECRET)  // :77-83
    })
```

岛体 `FocusNotification.buildV3 { ... }` `:92-135`（`com.xzakota.hyper.notification:focus-api`）：

```kotlin
val extras = FocusNotification.buildV3 {
    val picLeft  = createPicture("key_pic_left",  leftIcon)            // :93
    val picRight = createPicture("key_pic_right", rightIcon)           // :94
    island {
        islandProperty = 1                                             // ←1 = 大岛
        islandTimeout = NotificationPresentationPolicy.batteryIslandTimeoutSeconds(persistent)
        bigIslandArea {
            imageTextInfoLeft  { type = 1; picInfo { type = 1; pic = picLeft }
                                 textInfo { title = leftText;  content = "%" } }
            imageTextInfoRight { type = 2; picInfo { type = 2; pic = picRight }
                                 textInfo { title = rightText; content = "%" } }
        }
        shareData { title = displayName; content = contentText; shareContent = contentText }
    }
}
```

左右耳未连接时文本用 `"-"`（`:56-57`）。

组装通知 `:137-152`：

```kotlin
Notification.Builder(context, CHANNEL_ID)
    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
    ...
    .addExtras(extras)                                                 // :137-152
nm.notify(NOTIFICATION_ID, notification)                                // :154
scheduleDismiss(nm, persistent)                                         // :156
```

异常分级捕获 `:160-169`：`OutOfMemoryError` / `Exception` / `Throwable` 三层，
因为 bitmap 解码可能 OOM，而通知失败绝不能让蓝牙进程崩溃。

自动消失 `:182-196`：

```kotlin
private fun scheduleDismiss(notificationManager: NotificationManager, persistent: Boolean) {
    if (persistent) return                                              // 常驻不自动消失
    mainHandler.postDelayed(dismissRunnable,
        NotificationPresentationPolicy.TEMPORARY_ISLAND_TIMEOUT_SECONDS * 1_000L)  // :194
}
```

`TEMPORARY_ISLAND_TIMEOUT_SECONDS = 8`（`NotificationPresentationPolicy.kt:5`）与 `scheduleDismiss` 里的
`postDelayed` **必须与 `islandTimeout` 一致**，否则岛会被系统先撤或本地先撤，出现闪烁。

➜ **移植要点**：超级岛是「Notification + focus-api extras」而非独立 API。
新模块只需替换 `createPicture` 的 bitmap 与 `imageTextInfoLeft/Right` 的语义；
`islandProperty=1`、`islandTimeout`、`shareData` 三元组是协议必需项。

---

## 8. Gradle / Xposed 脚手架（可直接新建工程复刻）

### 8.1 `build.gradle.kts`（根，16 行）

```kotlin
// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // Upgrade KGP from AGP 9's default (2.2.10) to 2.3.20
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")   // :5
    }
}

plugins {
    alias(libs.plugins.agp.app) apply false          // :10
    alias(libs.plugins.agp.lib) apply false          // :11
}

tasks.register<Delete>("clean") { delete(layout.buildDirectory) }   // :14-16
```

`buildscript.classpath` 是必需的（`:4` 注释）：AGP 9 默认带的是 KGP 2.2.10，必须显式升到 2.3.20（`libs.versions.kotlin`），
否则 `compose-compiler` 与 Kotlin 版本不匹配。

### 8.2 `settings.gradle.kts`（34 行）

```kotlin
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")          // :1

pluginManagement {
    repositories {
        gradlePluginPortal(); google(); mavenCentral()      // :5-7
        maven("https://maven.aliyun.com/repository/gradle-plugin")   // :8
        maven("https://maven.aliyun.com/repository/google")          // :9
        maven("https://maven.aliyun.com/repository/central")         // :10
        maven("https://maven.aliyun.com/repository/public")          // :11
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)     // :16
    repositories {
        google(); mavenCentral()                                     // :18-19
        maven("https://api.xposed.info/")                            // :20 ←libxposed 仓库
        maven("https://s01.oss.sonatype.org/content/repositories/releases/")  // :21
        maven("https://jitpack.io")                                  // :22
        maven("https://maven.aliyun.com/repository/google")          // :23
        maven("https://maven.aliyun.com/repository/central")         // :24
        maven("https://maven.aliyun.com/repository/public")          // :25
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0") }  // :30

rootProject.name = "HuaweiPods"                                   // :33
include(":app")                                                   // :34
```

### 8.3 `gradle.properties`（7 行）

```properties
android.experimental.enableNewResourceShrinker.preciseShrinking=true   # :1
android.enableAppCompileTimeRClass=true                                # :2
android.useAndroidX=true                                               # :3

org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8  # :5

ksp.useKSP2=true                                                       # :7
```

`-Xmx4g` 是必需的：`SettingsHeadsetHook.kt`（167 KB）+ Compose 组合起来很容易撑爆默认 2g。

### 8.4 `gradle/libs.versions.toml`（45 行）

```toml
[versions]
agp = "9.1.0"                       # :2
focusApi = "1.4"                    # :3
kotlin = "2.4.0"                    # :4
ksp = "2.3.6"                       # :5
coreKtx = "1.19.0"                  # :6
androidx-activity-compose = "1.13.0" # :7
composeBom = "2026.05.01"           # :8
kotlinxSerializationJson = "1.11.0" # :9
libxposedApi = "101.0.1"            # :10  ←debug 用
libxposedService = "101.0.0"        # :11
libxposedApi102 = "102.0.0"         # :12  ←release 用
libxposedService102 = "102.0.0"     # :13
miuix = "0.9.2"                     # :14
navigation3 = "1.1.2"               # :15

[plugins]
agp-app = { id = "com.android.application", version.ref = "agp" }              # :18
agp-lib = { id = "com.android.library", version.ref = "agp" }                  # :19
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }                  # :20
lsplugin-resopt = { id = "org.lsposed.lsplugin.resopt", version = "1.6" }      # :21 ←资源混淆/裁剪
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }  # :22
parcelize = { id = "org.jetbrains.kotlin.plugin.parcelize" }                   # :23
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }  # :24

[libraries]
coreKtx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }           # :27
libxposedApi = { group = "io.github.libxposed", name = "api", version.ref = "libxposedApi" } # :28
libxposedService = { group = "io.github.libxposed", name = "service", version.ref = "libxposedService" }  # :29
libxposedApi102 = { group = "io.github.libxposed", name = "api", version.ref = "libxposedApi102" }         # :30
libxposedService102 = { group = "io.github.libxposed", name = "service", version.ref = "libxposedService102" }  # :31
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }  # :32
miuix = { group = "top.yukonga.miuix.kmp", name = "miuix-ui-android", version.ref = "miuix" }              # :39
miuix-preference = { group = "top.yukonga.miuix.kmp", name = "miuix-preference-android", version.ref = "miuix" }  # :40
miuix-icons   = { group = "top.yukonga.miuix.kmp", name = "miuix-icons-android",   version.ref = "miuix" }  # :41
miuix-blur    = { group = "top.yukonga.miuix.kmp", name = "miuix-blur-android",    version.ref = "miuix" }  # :42
miuix-navigation3-ui = { group = "top.yukonga.miuix.kmp", name = "miuix-navigation3-ui-android", version.ref = "miuix" }  # :43
navigation3-runtime = { group = "androidx.navigation3", name = "navigation3-runtime", version.ref = "navigation3" }  # :44
focus-api = { group = "com.xzakota.hyper.notification", name = "focus-api", version.ref = "focusApi" }     # :45
```

### 8.5 `app/build.gradle.kts`（127 行）

```kotlin
plugins {
    alias(libs.plugins.agp.app)              // :2
    alias(libs.plugins.lsplugin.resopt)      // :3
    alias(libs.plugins.kotlinSerialization)  // :4
    alias(libs.plugins.parcelize)            // :5
    alias(libs.plugins.compose.compiler)     // :6
}

val moduleBuildTimestamp = System.currentTimeMillis()   // :9
val moduleBuildIdBase = "b$moduleBuildTimestamp"        // :10

android {
    namespace = "moe.chenxy.huaweipods"     // :13
    compileSdk = 37                          // :14
    compileSdkMinor = 0                      // :15

    defaultConfig {
        applicationId = "moe.chenxy.huaweipods"   // :18
        // HyperOS 2 also ships on Android 14; the LSPosed API requirement is independent.  // :19
        minSdk = 34                          // :20
        targetSdk = 36                       // :21
        versionCode = 21                     // :22
        versionName = "1.9.1"                // :23
        buildConfigField("long", "BUILD_TIMESTAMP", moduleBuildTimestamp.toString())      // :24
    }

    buildTypes {
        debug {
            val moduleBuildId = "$moduleBuildIdBase-debug"                                // :29
            buildConfigField("String", "MODULE_BUILD_ID", "\"$moduleBuildId\"")           // :30
            manifestPlaceholders["moduleBuildId"] = moduleBuildId                         // :31
            isDebuggable = true; isMinifyEnabled = false; versionNameSuffix = "-debug"    // :32-34
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            val moduleBuildId = "$moduleBuildIdBase-release"                              // :41
            buildConfigField("String", "MODULE_BUILD_ID", "\"$moduleBuildId\"")           // :42
            manifestPlaceholders["moduleBuildId"] = moduleBuildId                         // :43
            isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false        // :44-46
            proguardFiles(...)
        }
    }

    dependenciesInfo.includeInApk = false    // :54  ←去掉签名块依赖信息
    buildFeatures { buildConfig = true; compose = true }   // :56-59

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"                                   // :63
            excludes += "/META-INF/**.version"                                        // :64
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"                    // :65
            excludes += "okhttp3/**"; excludes += "kotlin/**"; excludes += "org/**"    // :66-68
            excludes += "**.properties"; excludes += "**.bin"                          // :69-70
            excludes += "kotlin-tooling-metadata.json"                                 // :71
        }
    }
}

java   { toolchain { languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_23.majorVersion) } }  // :76-80
kotlin { jvmToolchain(JavaVersion.VERSION_23.majorVersion.toInt()) }                                   // :82-84

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")   // :87
}

dependencies {
    implementation(libs.coreKtx)                 // :91
    debugCompileOnly(libs.libxposedApi)          // :92  ←API 101 编译期
    debugImplementation(libs.libxposedService)   // :93  ←API 101 运行期
    releaseCompileOnly(libs.libxposedApi102)     // :94  ←API 102 编译期
    releaseImplementation(libs.libxposedService102)  // :95  ←API 102 运行期
    implementation(libs.kotlinx.serialization.json)  // :96
    testImplementation("junit:junit:4.13.2")     // :97

    // Compose
    implementation(platform(libs.compose.bom))   // :100
    implementation(libs.compose.ui)              // :101
    implementation(libs.compose.foundation)      // :102
    implementation(libs.compose.ui.tooling.preview)  // :103
    debugImplementation(libs.compose.ui.tooling)     // :104
    implementation(libs.androidx.activity.compose)   // :105

    // MIUIX
    implementation(libs.miuix)                   // :108
    implementation(libs.miuix.preference)        // :109
    implementation(libs.miuix.icons)             // :110
    implementation(libs.miuix.blur)              // :111
    implementation(libs.miuix.navigation3.ui)    // :112

    // Navigation3
    implementation(libs.navigation3.runtime)     // :115

    // HyperOS Focus Island API
    implementation(libs.focus.api)               // :118
}

configurations.matching { it.name.startsWith("release") }.configureEach {
    resolutionStrategy.force(                    // :122-127
        "io.github.libxposed:api:${libs.versions.libxposedApi102.get()}",
        "io.github.libxposed:service:${libs.versions.libxposedService102.get()}"
    )
}
```

**四个必须理解的脚手架点**：

1. `debugCompileOnly` + `debugImplementation` / `releaseCompileOnly` + `releaseImplementation`（`:92-95`）
   —— 这是「同一份源码编译出两个 API 版本」的关键。`compileOnly` 保证 `libxposed:api` 不被塞进 APK
   （Xposed 模块的 API 由框架在运行时提供），`implementation` 只塞 `service`（供模块 App 查询 LSPosed 服务）。
2. `configurations.matching { it.name.startsWith("release") }.configureEach { resolutionStrategy.force(...) }`
   （`:122-127`）—— 因为 `libs.libxposedApi102` 与间接依赖可能解析到 101，必须**对 release 变体的所有配置**强制降级到 102。
3. `manifestPlaceholders["moduleBuildId"]`（`:31,:43`）注入到 `AndroidManifest.xml:27-29` 的 `<meta-data>`，
   让模块 App 能判断「当前加载的是哪一次构建」，从而在热重载后丢弃过期缓存。
4. `packaging.resources.excludes` 里的 `kotlin/**`、`org/**`、`**.properties`（`:67-69`）
   —— 这些是 Kotlin 标准库的元数据，AGP 会把它们塞进 APK 导致体积膨胀且引发 `Duplicate class`。

### 8.6 `AndroidManifest.xml`（83 行，节选）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />           <!-- :4 -->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /><!-- :5 -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />  <!-- :6 -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />     <!-- :7 -->

    <queries>                                                                <!-- :9 -->
        <package android:name="com.android.bluetooth" />                     <!-- :10 -->
        <package android:name="com.android.settings" />                      <!-- :11 -->
        <package android:name="com.huawei.smartaudio" />                     <!-- :12 -->
        <package android:name="com.milink.service" />                        <!-- :13 -->
        <package android:name="com.xiaomi.bluetooth" />                      <!-- :14 -->
    </queries>

    <application android:name=".HuaweiPodsApp" ... android:theme="@style/Theme.HuaweiPods">  <!-- :17-25 -->
        <meta-data android:name="moe.chenxy.huaweipods.MODULE_BUILD_ID"
                   android:value="${moduleBuildId}" />                       <!-- :27-29 -->

        <activity-alias android:name=".LauncherActivity"                     <!-- :31 -->
            android:enabled="true" android:exported="true"
            android:targetActivity=".MainActivity">                          <!-- :38 -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />         <!-- :40 -->
                <category android:name="android.intent.category.LAUNCHER" /> <!-- :41 -->
            </intent-filter>
        </activity-alias>

        <activity android:name=".MainActivity"                               <!-- :45 -->
            android:launchMode="singleTask"                                  <!-- :46 -->
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|mnc|..."  <!-- :47 -->
            android:exported="true">                                         <!-- :48 -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />         <!-- :50 -->
                <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />  <!-- :51 -->
            </intent-filter>
        </activity>

        <activity android:name=".PopupActivity"                              <!-- :54 -->
            android:theme="@style/Theme.HuaweiPods.Popup"
            android:taskAffinity="" android:excludeFromRecents="true"
            android:exported="true">                                         <!-- :58 -->
            <intent-filter>
                <action android:name="chen.action.huaweipods.show_pods_ui" />  <!-- :60 -->
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <receiver android:name=".LowLatencyStateReceiver"                    <!-- :64-65 -->
            android:exported="true"
            android:permission="android.permission.BLUETOOTH_PRIVILEGED">    <!-- :67 -->
            <intent-filter>
                <action android:name="chen.action.huaweipods.huawei_low_latency_changed" />  <!-- :69 -->
            </intent-filter>
        </receiver>

        <provider android:name=".config.PodImageProvider"                    <!-- :72-73 -->
            android:authorities="${applicationId}.podimages"                 <!-- :74 -->
            android:exported="true" android:grantUriPermissions="false" />   <!-- :75-76 -->

        <service android:name=".smartaudio.SmartAudioImageDownloadJobService" <!-- :77-78 -->
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />       <!-- :80 -->
    </application>
</manifest>
```

三个关键点：

1. `de.robv.android.xposed.category.MODULE_SETTINGS`（`:51`）—— LSPosed 管理器点击模块「设置」时
   打开的就是带这个 category 的 Activity。
2. `PopupActivity` 的 `taskAffinity=""` + `excludeFromRecents="true"`（`:56-57`）—— 耳机弹窗不能进最近任务。
3. `LowLatencyStateReceiver` 用 `android.permission.BLUETOOTH_PRIVILEGED` 保护（`:67`）——
   该权限是 system 级，只有系统进程能发这个广播，等价于「只接受来自蓝牙栈的低延迟状态」。

### 8.7 新建工程的落地顺序

1. 复制 `settings.gradle.kts` / `gradle.properties` / `gradle/libs.versions.toml` / 根 `build.gradle.kts` / `app/build.gradle.kts`，
   把 `moe.chenxy.huaweipods` 全局替换成新包名（`namespace`、`applicationId`、`java_init.list` 里的类名）。
2. 复制 `hook/HookContext.kt` + `hook/HookEntry.kt`（这两份是**厂商无关**的通用设施，只需改 `TAG` 前缀和包路由的 `when`）。
3. 复制 `config/ConfigManager.kt` 骨架（`PREFS_NAME` 必须与 App 侧一致）+ `HuaweiPodsApp.kt` 的
   `XposedServiceHelper.OnServiceListener` 接线。
4. `src/main/resources/META-INF/xposed/` 三个文件 + `src/release/resources/META-INF/xposed/` 的 102 覆盖。
5. 写 `pods/<Vendor>Packets.kt`（指令表）+ `pods/<Vendor>ResponseParser.kt`（帧解析）+ `pods/<Vendor>Controller.kt`
   （传输，直接照抄 `HuaweiL2capAncController.kt`，只改包常量与 UUID 之外的候选策略）。
6. 写 `pods/DeviceCapabilities.kt`（能力表）+ `pods/<Vendor>DeviceRouteResolver.kt`。
7. 最后写 hook 层（`HeadsetStateDispatcher` 对应物），因为它的 `connectPod` 调用依赖前 6 步。

---

## 附录 A：关键数值速查

| 项 | 值 | 出处 |
|---|---|---|
| SPP UUID | `00001101-0000-1000-8000-00805F9B34FB` | `pods/HuaweiL2capAncController.kt:36` |
| 连接超时 | `3000ms` / 探测 `2000ms` | `:31,:34` |
| 连接重试 | `2` 次 × 候选，间隔 `350ms` | `:33,:32` |
| RFCOMM 通道回退 | channel `1..8`，secure+insecure | `:532-535` |
| 响应窗口 | 电池/ANC/DeviceInfo `1500ms`，探测 `1200ms`，raw `1000ms` | `:143,:1786,:230,:301` |
| 静默结束判据 | `200ms` | `:620` |
| 轮询间隔 | `20ms` | `:622` |
| 帧头 | `5A 00` + len(LE) + service | `pods/HuaweiRfcommResponseParser.kt:8` |
| CRC | CRC-16/XMODEM, poly `0x1021`, init `0` | `pods/HuaweiEqualizerCodec.kt:228-238` |
| 电池轮询 | `30s`；请求节流 `10s` | `HuaweiHfpController.kt:33,:1418` |
| ANC 回读节流 | `750ms`；确认延迟 `450ms` | `:32,:31` |
| DeviceInfo 节流 | `60s` | `:41` |
| 岛超时 | 临时 `8s` / 常驻 `3600s`（续期 `30min`） | `config/NotificationPresentationPolicy.kt:5,7,8` |
| 岛冷却 | `30s`，每会话一次 | `pods/BatteryIslandTriggerPolicy.kt:5` |
| 探测节流 | `15s` | `pods/HuaweiDeviceRouteProbePolicy.kt:24` |
| 探测 UI 超时 | `6500ms` | `ui/pages/DevicePickerPage.kt:86` |
| 图标看门狗 | `2500ms` | `hook/HeadsetStateDispatcher.kt:36` |
| RfcommLog 容量 | `200` 条 | `pods/RfcommLog.kt:12` |
| 通知 ID | `10086` | `utils/FocusIslandUtil.kt:24` |
| compileSdk / min / target | `37` / `34` / `36` | `app/build.gradle.kts:14,20,21` |
| Java toolchain | `23` | `app/build.gradle.kts:76-84` |

## 附录 B：本仓库缺失 / 需新模块自行补齐的点

1. **`hookAll` 不存在**——`HookContext` 只有 `hookBefore/hookAfter/hookConstructorAfter`。
   若需批量 hook 同名重载，需自行实现。
2. **无 VID/PID 识别**——全仓库无 `vid`/`vendorId` 匹配，识别只有 MAC 绑定 / 名称别名 / DeviceInfo modelId 三条路。
3. **A2DP 回调方法名靠尝试**（`HeadsetStateDispatcher.kt:79-87`）——`handleConnectionStateChanged` 与
   `connectionStateChanged` 二选一，不同 Android 版本方法名会变；新 ROM 需补候选名。
4. **无单元测试覆盖产物**——`testImplementation("junit:junit:4.13.2")` 在 `app/build.gradle.kts:97` 已就位，
   但纯策略类（`NotificationPresentationPolicy`、`BatteryIslandTriggerPolicy`、`HuaweiRcLevelProfile` 的转换函数）
   才是最适合单测的部分，新模块应优先补上。
