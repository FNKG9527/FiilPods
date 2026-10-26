package moe.chenxy.fiilpods.hook

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
