package moe.chenxy.fiilpods.hook.milink

import android.bluetooth.BluetoothDevice

/**
 * 融合设备中心里的「空间音频 / 影院模式」子模块。
 *
 * 与 [MiLinkServiceHook] 分开放，是因为这一族的 hook 点集中在
 * `com.miui.headset.runtime` 的音频相关类上，混在一起会让主类太长，
 * 也不方便单独排查——空间音频在米家体系里是一整套独立的
 * `setMiAudioEffect` / `setHeadTracking` / `setAudioEffectState` 流程。
 *
 * FIIL 只有「影院模式」开/关两态（Mate 机型 cid=50），没有头动跟踪，
 * 所以 MIUI 的头动跟踪（9/11）统一映射成「开」，回读也只会回开/关。
 */
internal class MiLinkSpatialAudioHook(private val hook: MiLinkServiceHook) {

    fun install() {
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        hookCirculateHeadsetServiceInfo()
    }

    private fun hookMxBluetoothRuntime() {
        listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
        ).forEach { className ->
            hook.hookDeviceResult(className, "getSpatialMode") { hook.miLinkSpatialMode() }
            hookSpatialCommand(className, "setSpatialMode")
        }
    }

    private fun hookHeadsetRuntimeDisplay() {
        hook.hookDeviceResult(
            "com.miui.headset.runtime.AncBatteryController",
            "getMiAudioEffect",
        ) { hook.miLinkSpatialMode() }

        hookSpatialStateBlock()
        hookDeviceSpatialTypeModel()
        hookSpatialCallbacks()
        hookProfileSpatialEffect()
        hookProfileAudioEffectState()

        hook.hookHeadsetInfoNoArg("getAudioEffectState") { hook.miLinkAudioEffectState() }
        hook.hookHeadsetInfoNoArg("component10") { hook.miLinkAudioEffectState() }
    }

    /**
     * `CirculateServiceInfo.setHeadsetId(String, int)`：系统发现我们的伪设备 ID
     * 时，如果空间音频面板不可用，就把 `headset_switch_state` 置 0，
     * 让卡片不显示那个开关——否则用户会看到一个点了没反应的按钮。
     */
    private fun hookCirculateHeadsetServiceInfo() {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            hook.hookAfter(
                hook.findMethod(
                    "com.miui.circulate.api.service.CirculateServiceInfo",
                    "setHeadsetId",
                    String::class.java,
                    intType,
                ),
            ) { param ->
                val headsetId = param.args[0] as? String ?: return@hookAfter
                val address = hook.getObjectField(param.instance, "deviceId") as? String
                if (address != null && !hook.isFiilAddress(address) && headsetId != hook.fakeDeviceId()) {
                    return@hookAfter
                }
                if (hook.spatialPanelEnabled()) return@hookAfter
                val properties = hook.getObjectField(param.instance, "serviceProperties") ?: return@hookAfter
                val bundle = hook.callMethod(properties, "getAll") as? android.os.Bundle
                    ?: return@hookAfter
                bundle.putInt("headset_switch_state", 0)
            }
        }.onFailure { hook.logInstallerWarn("CirculateServiceInfo.setHeadsetId skipped", it) }
    }

    /** 控制中心的空间音频开关：`setSpatialMode(BluetoothDevice, int)`。 */
    private fun hookSpatialCommand(className: String, methodName: String) {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            hook.hookBefore(hook.findMethod(className, methodName, BluetoothDevice::class.java, intType)) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isFiilDevice(device)) return@hookBefore
                hook.cacheOwner(className, param.instance)
                hook.captureContext(param.instance)
                val miLinkMode = param.args[1] as? Int ?: return@hookBefore
                applySpatialMode(miLinkMode, param.instance, device)
                param.setResult(1)
            }
        }.onFailure { hook.logInstallerWarn("$className.$methodName spatial command skipped", it) }
    }

    /** 系统设置里「空间音频」开关走 `setMiAudioEffect`；「头动跟踪」走 `setHeadTracking`。 */
    private fun hookSpatialStateBlock() {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.AncBatteryController",
                    "setMiAudioEffect",
                    BluetoothDevice::class.java,
                    intType,
                ),
            ) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isFiilDevice(device)) return@hookBefore
                hook.cacheOwner("com.miui.headset.runtime.AncBatteryController", param.instance)
                hook.captureContext(param.instance)
                val miLinkMode = param.args[1] as? Int ?: return@hookBefore
                applySpatialMode(miLinkMode, param.instance, device)
                param.setResult(null)
            }
        }.onFailure { hook.logInstallerWarn("AncBatteryController.setMiAudioEffect skipped", it) }

        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.AncBatteryController",
                    "setHeadTracking",
                    BluetoothDevice::class.java,
                ),
            ) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isFiilDevice(device)) return@hookBefore
                hook.cacheOwner("com.miui.headset.runtime.AncBatteryController", param.instance)
                hook.captureContext(param.instance)
                applySpatialMode(MiLinkServiceHook.SPATIAL_HEAD_TRACKING, param.instance, device)
                param.setResult(100)
            }
        }.onFailure { hook.logInstallerWarn("AncBatteryController.setHeadTracking skipped", it) }
    }

    private fun applySpatialMode(miLinkMode: Int, owner: Any?, device: BluetoothDevice) {
        val on = miLinkMode != MiLinkServiceHook.SPATIAL_OFF
        hook.updateCinema(on)
        hook.sendCommand("cinema", on)
        notifySpatialUiChanged(owner, device)
    }

    /** `AncBatteryModel` 里缓存了一个 `deviceSpatialType`，不拦会导致开关自己弹回去。 */
    private fun hookDeviceSpatialTypeModel() {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            val getter = hook.findMethodByParamCount(
                "com.miui.headset.runtime.AncBatteryModel",
                "getDeviceSpatialType",
                0,
            ) ?: return
            hook.hookAfter(getter) { param ->
                if (!hook.isTargetAncBatteryModel(param.instance)) return@hookAfter
                param.setResult(hook.miLinkDeviceSpatialType())
            }
        }.onFailure { hook.logInstallerWarn("AncBatteryModel.getDeviceSpatialType skipped", it) }

        runCatching {
            hook.hookAll(
                hook.findMethod(
                    "com.miui.headset.runtime.AncBatteryModel",
                    "setDeviceSpatialType",
                    intType,
                ),
            ) { param ->
                if (!hook.isTargetAncBatteryModel(param.instance)) return@hookAll
                setField(param.instance, "deviceSpatialType", hook.miLinkDeviceSpatialType())
            }
        }.onFailure { hook.logInstallerWarn("AncBatteryModel.setDeviceSpatialType skipped", it) }
    }

    /**
     * SDK 会通过 `mmaCallback` 回报空间音频状态；不拦的话，
     * 它会把我们伪造的值覆盖回「不支持」。
     */
    private fun hookSpatialCallbacks() {
        val intType = Int::class.javaPrimitiveType ?: return
        listOf("onDeviceSpatialType", "onReportSpatialState").forEach { methodName ->
            runCatching {
                hook.hookBefore(
                    hook.findMethod(
                        "com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1",
                        methodName,
                        BluetoothDevice::class.java,
                        intType,
                    ),
                ) { param ->
                    val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                    if (!hook.isFiilDevice(device)) return@hookBefore
                    notifySpatialUiChanged(param.instance, device)
                    param.setResult(null)
                }
            }.onFailure { hook.logInstallerWarn("mmaCallback.$methodName skipped", it) }
        }
    }

    /** `ProfileContext.getAudioSpatialEffectState` 是设置页空间音频开关的取值源。 */
    private fun hookProfileSpatialEffect() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(
                    "com.miui.headset.runtime.ProfileContext",
                    "getAudioSpatialEffectState",
                    BluetoothDevice::class.java,
                ),
            ) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookAfter
                if (!hook.isFiilDevice(device)) return@hookAfter
                hook.cacheOwner("com.miui.headset.runtime.ProfileContext", param.instance)
                hook.captureContext(param.instance)
                param.setResult(hook.miLinkSpatialMode())
            }
        }.onFailure { hook.logInstallerWarn("ProfileContext.getAudioSpatialEffectState skipped", it) }
    }

    /** `ProfileContext.setAudioEffectState(BluetoothDevice, String, int)`：真正写设置的那条。 */
    private fun hookProfileAudioEffectState() {
        val intType = Int::class.javaPrimitiveType ?: return
        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.ProfileContext",
                    "setAudioEffectState",
                    BluetoothDevice::class.java,
                    String::class.java,
                    intType,
                ),
            ) { param ->
                val device = param.args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isFiilDevice(device)) return@hookBefore
                hook.cacheOwner("com.miui.headset.runtime.ProfileContext", param.instance)
                hook.captureContext(param.instance)
                val state = param.args[2] as? Int ?: return@hookBefore
                applySpatialMode(state, param.instance, device)
                param.setResult(null)
            }
        }.onFailure { hook.logInstallerWarn("ProfileContext.setAudioEffectState skipped", it) }
    }

    private fun notifySpatialUiChanged(owner: Any?, device: BluetoothDevice) {
        listOf(owner, hook.lastAncBatteryController, hook.lastProfileContext)
            .distinctBy { it?.javaClass?.name }
            .forEach { target ->
                hook.notifyPropertyChanged(target, device, UPDATE_SPATIAL)
                hook.notifyPropertyChanged(target, device, UPDATE_BATTERY)
            }
    }

    /** 反射写私有字段；`HookContext` 只提供了读，写在这里就地实现。 */
    private fun setField(instance: Any?, name: String, value: Any?): Boolean = runCatching {
        var cls: Class<*>? = instance?.javaClass ?: return false
        while (cls != null) {
            val field = cls.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(instance, value)
                return true
            }
            cls = cls.superclass
        }
        false
    }.getOrDefault(false)
}
