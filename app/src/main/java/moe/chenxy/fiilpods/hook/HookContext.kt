package moe.chenxy.fiilpods.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 极简 Hook 上下文。
 *
 * 与参考仓库 HuaweiPods 的完整实现相比，这里去掉了热重载、关闭代际、
 * 稳定 HookId 等机制 —— 本模块的作用域只有三个系统包，且 Hook 点很少，
 * 保持简单比可热重载更重要。
 */
abstract class HookContext(
    protected val entry: HookEntry,
    protected val classLoader: ClassLoader,
) {
    protected val TAG: String get() = this::class.java.simpleName

    /**
     * 由 [HookEntry.loadHook] 注入的「模块设置」远端 SharedPreferences。
     *
     * **为什么必须有它**：Hook 跑在 `com.android.bluetooth` / `com.xiaomi.bluetooth`
     * / `com.milink.service` 等系统进程里，这些进程读不到模块 App 自己的
     * `SharedPreferences`。不注入的话 [moe.chenxy.fiilpods.config.ConfigManager]
     * 在这些进程里永远是构造时的默认值 —— 用户在设置页关掉焦点通知、
     * 或把超级岛切到「模块」模式，Hook 侧完全看不到，行为不会变。
     *
     * 历史坑：v2.0.0 之前这里没有 prefs，导致「设置页开启焦点通知后通知栏无通知」
     * （用户 m26286 问题 3）。LSPosed 的 `getRemotePreferences()` 是跨进程读
     * 模块设置的唯一正确通道。
     */
    lateinit var prefs: SharedPreferences

    /** 当前被注入的宿主包名，仅用于日志与诊断。 */
    lateinit var packageName: String

    abstract fun onHook()

    protected fun logD(msg: String) = entry.log(android.util.Log.DEBUG, TAG, msg)
    protected fun logI(msg: String) = entry.log(android.util.Log.INFO, TAG, msg)
    protected fun logW(msg: String, t: Throwable? = null) =
        entry.log(android.util.Log.WARN, TAG, msg, t)

    protected fun logE(msg: String, t: Throwable? = null) =
        entry.log(android.util.Log.ERROR, TAG, msg, t)

    /** 执行安装并返回自身，供 HookEntry 链式调用。 */
    fun install(): HookContext {
        runCatching { onHook() }.onFailure {
            entry.log(android.util.Log.ERROR, TAG, "onHook() failed", it)
        }
        return this
    }

    /**
     * 注入模块设置（远端 SP）并初始化 [moe.chenxy.fiilpods.config.ConfigManager]。
     *
     * 必须在 [install] 之前调用 —— 各 Hook 的 `onHook()` 里大量依赖
     * `ConfigManager.focusNotification()` / `islandMode()` 等判定，
     * 晚一步就会读到默认值，表现为「设置改了不生效」。
     *
     * prefs 为 null 时（`getRemotePreferences` 不可用）静默跳过，
     * ConfigManager 保持默认值，功能降级但不崩。
     */
    fun attach(prefs: SharedPreferences?, hostPackage: String): HookContext {
        this.packageName = hostPackage
        if (prefs != null) {
            this.prefs = prefs
            runCatching {
                moe.chenxy.fiilpods.config.ConfigManager.init(prefs)
                prefs.registerOnSharedPreferenceChangeListener { sp, key ->
                    if (key == moe.chenxy.fiilpods.config.ConfigManager.PREF_KEY_CONFIG_JSON ||
                        key == moe.chenxy.fiilpods.config.ConfigManager.PREF_KEY_FOCUS_NOTIFICATION ||
                        key == moe.chenxy.fiilpods.config.ConfigManager.PREF_KEY_ISLAND_MODE
                    ) {
                        runCatching { moe.chenxy.fiilpods.config.ConfigManager.refreshFromPrefs(sp) }
                    }
                }
            }.onFailure {
                entry.log(android.util.Log.WARN, TAG, "ConfigManager.init failed", it)
            }
        }
        return this
    }
    // ---------------- 反射工具 ----------------

    fun findClass(name: String): Class<*> = Class.forName(name, false, classLoader)

    fun findMethod(
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>,
    ): Method = findClass(className).getDeclaredMethod(methodName, *paramTypes).apply {
        isAccessible = true
    }

    /** 按参数个数找方法（应对混淆后签名变动）。 */
    fun findMethodByParamCount(className: String, methodName: String, count: Int): Method? =
        runCatching {
            findClass(className).declaredMethods
                .firstOrNull { it.name == methodName && it.parameterTypes.size == count }
                ?.apply { isAccessible = true }
        }.getOrNull()

    fun findConstructor(className: String, vararg paramTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*paramTypes).apply { isAccessible = true }

    /** 按参数个数找构造器（宿主版本漂移时的兜底）。 */
    fun findConstructorByParamCount(className: String, count: Int): Constructor<*>? =
        runCatching {
            findClass(className).declaredConstructors
                .firstOrNull { it.parameterTypes.size == count }
                ?.apply { isAccessible = true }
        }.getOrNull()

    /** 静态方法调用。 */
    fun callStaticMethod(className: String, name: String, vararg args: Any?): Any? = runCatching {
        val cls = findClass(className)
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        m.isAccessible = true
        m.invoke(null, *args)
    }.getOrNull()

    /** proceed 之后执行，可改写返回值。 */
    fun hookAfter(method: Method, block: (HookParam) -> Unit) {
        entry.installHook(method, "$TAG#after:${method.name}") { chain ->
            val result = chain.proceed()
            val param = HookParam(chain, result)
            runCatching { param.apply(block) }
            if (param.hasResult) param.result else result
        }
    }

    fun hookAll(method: Method, block: (HookParam) -> Unit) {
        entry.installHook(method, "$TAG#${method.name}") { chain ->
            val result = runCatching { chain.proceed() }.getOrElse { throw it }
            runCatching { HookParam(chain, result).apply(block) }
            result
        }
    }

    fun hookBefore(method: Method, block: (HookParam) -> Unit) {
        entry.installHook(method, "$TAG#before:${method.name}") { chain ->
            val param = HookParam(chain, null)
            runCatching { param.apply(block) }
            if (param.hasResult) param.result else chain.proceed()
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: (HookParam) -> Unit) {
        entry.installHook(constructor, "$TAG#ctor:${constructor.declaringClass.simpleName}") { chain ->
            val result = chain.proceed()
            runCatching { HookParam(chain, result).apply(block) }
            result
        }
    }

    fun getObjectField(instance: Any?, name: String): Any? = runCatching {
        var cls: Class<*>? = instance?.javaClass ?: return null
        while (cls != null) {
            val f: Field? = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(instance)
            }
            cls = cls.superclass
        }
        null
    }.getOrNull()

    fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? = runCatching {
        val m = (instance?.javaClass ?: return null).methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        m.isAccessible = true
        m.invoke(instance, *args)
    }.getOrNull()

    /**
     * 反射写对象字段（含继承链）。
     *
     * 移植自 `refs/OppoPods/.../hook/HookContext.kt:132 setObjectField`。
     * 融合设备中心需要它：`AncBatteryModel` 里有 `spatialState` / `deviceSpatialType`
     * 这类**纯内存字段**，只调 `headsetPropertyChangeListener` 刷新还不够 ——
     * 系统重绘时会重新读模型字段，不回写就永远显示上一次的值
     * （用户 m26286 问题 1：设备中心切模式后图标要再点一次才更新）。
     */
    fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
        if (instance == null) return
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            val done = runCatching {
                cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            }.isSuccess
            if (done) return
            cls = cls.superclass
        }
    }
}

/** 一次 Hook 调用的参数视图。 */
class HookParam(
    private val chain: XposedInterface.Chain,
    initialResult: Any?,
) {
    val args: List<Any?> get() = chain.args
    val instance: Any? get() = chain.thisObject
    var result: Any? = initialResult
        private set
    var hasResult: Boolean = false
        private set

    fun setResult(value: Any?) {
        result = value
        hasResult = true
    }

    fun proceed(): Any? = chain.proceed()
}
