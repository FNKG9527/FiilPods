# HuaweiPods 架构技术规格（主模板参考）

> 参考仓库：`<repo>\refs\HuaweiPods`（Nshpiter/HuaweiPods，GPL-3.0）
> 版本：`1.9.1` / versionCode `21`；LSPosed API **102**（正式版）/ **101**（debug 抓包版）
> 本文所有路径均相对于仓库根目录；行号来自当前 checkout 实测。

---

## 0. 一句话架构

模块 = **一个 APK 两种身份**：既是普通 Compose 应用（模块 UI / 弹窗 / 设置 / 超级岛），又是注入
`com.android.bluetooth`、`com.android.settings`、`com.milink.service`、`com.xiaomi.bluetooth`、
`com.huawei.smartaudio` 五个系统进程的 Xposed 模块。**协议写入全部发生在 `com.android.bluetooth`
（拥有 RFCOMM/L2CAP socket 的那个进程）**，其余进程通过广播 + `HuaweiPodsAction` 契约把"想做什么"
交给蓝牙进程执行，再把结果广播回去。

```
┌─────────────────────── 模块进程 moe.chenxy.huaweipods ───────────────────────┐
│ MainActivity / PopupActivity (Compose)   ConfigManager (LSPosed remote prefs) │
│   │ 发送 ACTION_* 广播（带 nonce/address/route）     ▲ 写 remote prefs        │
│   ▼                                                  │                        │
│ ──────────────── Binder (XposedService) ─────────────┘                        │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │ 广播（IdentitySharing / setPackage）
┌──────────────▼──────────────┐   ┌──────────────────────────────┐
│ com.android.bluetooth       │   │ com.android.settings          │
│  HeadsetStateDispatcher     │   │  SettingsHeadsetHook          │
│  HuaweiHfpController ───────┼──▶│  (MiuiHeadsetActivity 注入)   │
│  (RFCOMM/L2CAP 写入口)      │   │                               │
│  BluetoothUpstreamHeadsetHook│  ├──────────────────────────────┤
└──────────────┬──────────────┘   │ com.milink.service            │
               │                  │  MiLinkServiceHook (融合设备中心)│
               │                  ├──────────────────────────────┤
               │                  │ com.xiaomi.bluetooth          │
               │                  │  MiBluetoothToastHook         │
               │                  ├──────────────────────────────┤
               │                  │ com.huawei.smartaudio         │
               ▼                  │  SmartAudioFreeClip2BridgeHook│
        Huawei 耳机 (RFCOMM)      └──────────────────────────────┘
```

---

## 1. 模块引导（Bootstrap）

### 1.1 入口注册：libxposed API 接口（**不是** `assets/xposed_init`）

| 文件 | 内容 | 说明 |
|---|---|---|
| `app/src/main/resources/META-INF/xposed/java_init.list` | `moe.chenxy.huaweipods.hook.HookEntry` | debug/默认变体入口 |
| `app/src/release/resources/META-INF/xposed/java_init.list` | `moe.chenxy.huaweipods.hook.Api102HookEntry` | release 变体入口 |
| `app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=101` / `targetApiVersion=101` / `staticScope=true` | |
| `app/src/release/resources/META-INF/xposed/module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `autoHotReload=true` | **关键差异** |
| `app/src/{main,release,debug}/resources/META-INF/xposed/scope.list` | `com.android.bluetooth` `com.android.settings` `com.huawei.smartaudio` `com.milink.service` `com.xiaomi.bluetooth` | 五个作用域 |

`HookEntry` 继承 `io.github.libxposed.api.XposedModule`，**没有** `assets/xposed_init`、**没有** `IXposedHookLoadPackage`，
classloader 从 `XposedModule` 的回调参数拿：`param.defaultClassLoader`。

### 1.2 `hook/HookEntry.kt`（274 行）

| 符号 | 行号 | 行为 |
|---|---|---|
| `open class HookEntry : XposedModule()` | 约 20 | 基类 |
| `onPackageLoaded` | 23–26 | 仅 `param.isFirstPackage` 时继续，然后 `installHooksForPackage(param.packageName, param.defaultClassLoader)` |
| `installHooksForPackage(pkg, cl)` | 34–69 | 初始化 `ModuleResourceResolver` → 记录 `loadedPackages` → `BuildVariantHooks.installForPackage` → 按包名分派 |
| `canRestorePackageHooks` | 72–89 | API 102 热重载前置检查（校验包名、classloader，并真实读取一次 `getRemotePreferences(ConfigManager.PREFS_NAME).contains(PREF_KEY_CONFIG_JSON)`） |
| `loadHook(name, hook, cl, pkg, strictInstall)` | 98–160 | 取 `RemotePreferences`（`runCatching`，非 strict 时失败就静默返回）→ `hook.attach(...)` → 顺带 `LowLatencyPrefs.attachHookPreferences` / `HuaweiDeviceRouteResolver.init` / `ConfigManager.init` → 注册 `OnSharedPreferenceChangeListener`（`PREF_KEY_CONFIG_JSON` 刷新配置；`DeviceRoutePrefs.isBindingKey` 刷新绑定）→ `hook.onHook()`，抛异常则回滚 |
| `installHook(executable, hookIds)` | 163–167 | 默认实现：`hook(executable).intercept { interceptor(it) }`（API 102 用 `setId`） |
| `createHotReloadState` / `restoreLoadedGenerationState` / `canCloseLoadedGeneration` / `closeLoadedGeneration` | 174–231 | 热重载状态机；`Companion HOT_RELOAD_PACKAGE_KEY = "host_package"` |

分派矩阵（`installHooksForPackage` 内）：

| 目标包 | 加载的 HookContext |
|---|---|
| `com.android.bluetooth` | `HeadsetStateDispatcher`（namespace `bluetooth-headset-state`）+ `BluetoothUpstreamHeadsetHook`（`bluetooth-upstream`） |
| `com.android.settings` | `SettingsHeadsetHook`（`settings-headset`） |
| `com.milink.service` | `MiLinkServiceHook`（`milink-service`） |
| `com.xiaomi.bluetooth` | `MiBluetoothToastHook`（`mi-bluetooth-toast`）+ `BluetoothUpstreamHeadsetHook` |
| `com.huawei.smartaudio` | 由 `BuildVariantHooks` 决定（见 1.4） |

### 1.3 `app/src/release/java/.../hook/Api102HookEntry.kt`（261 行）

热重载专用子类：

- `onHotReloading(37–84)`：只允许「唯一已加载包 == 本应用包名」，且 `canRestorePackageHooks && canCloseLoadedGeneration`
  才 `param.setSavedInstanceState(createHotReloadState(packageName))` 并 `closeLoadedGeneration()`。
- `onHotReloaded(86–193)`：用 `param.oldHookHandles` 按 `handle.id` 重建 `replacementHandlesById` →
  `installHooksForPackage(pkg, cl, requireComplete = true)` → 恢复状态 → 卸载剩余旧 hook。
- `installHook(197–235)`：若存在同 `stable hookId` 的旧 handle → `oldHandle.replaceHook(hooker)`；
  失败则退化为 `hook(executable).setId(hookId).intercept(hooker)`。

> **陷阱**：`hookId` 必须稳定，否则热重载会重复挂载。`HookContext` 用
> `HuaweiPods/$namespace/$kind/$sha256Prefix/$occurrence` 生成（见 1.5）。

### 1.4 `BuildVariantHooks`（源集切换）

- `app/src/debug/java/moe/chenxy/huaweipods/hook/BuildVariantHooks.kt`（20 行）：
  `supportsPackage(pkg) = SmartAudioCaptureTarget.isAllowedSender(pkg)`；
  `installForPackage` 额外挂 `ai-life-capture`（`AiLifeCaptureHook`，协议抓包向导）+ `smart-audio-freeclip2-bridge`。
- `app/src/release/java/.../BuildVariantHooks.kt`（15 行）：只对 `com.huawei.smartaudio` 挂
  `smart-audio-freeclip2-bridge`（`SmartAudioFreeClip2BridgeHook`）。
- debug 变体还有自己的 `app/src/debug/AndroidManifest.xml`（39 行）：`queries` 加 `com.huawei.smartaudio`；
  新增 `.debugcapture.DebugCaptureActivity`（`exported=true`, `singleTask`）、
  `<activity-alias .LauncherActivity>`（覆盖 `android:label`）、
  `.debugcapture.CaptureEventReceiver`（action `moe.chenxy.huaweipods.debugcapture.action.CAPTURE_EVENT`）、
  `.debugcapture.CaptureFileProvider`（`${applicationId}.debugcapture.files`）。

### 1.5 `hook/HookContext.kt`（339 行）——共享 Hook 基建

```
abstract class HookContext {
    lateinit var module / appClassLoader / prefs / packageName
    abstract fun onHook()
    open fun onClose() / onCanClose() / onSaveHotReloadState(Bundle) / onRestoreHotReloadState(Bundle)
    fun attach(owner, namespace, classLoader, hostPackageName, remotePrefs)   // 建 StableHookIdGenerator
    fun registerCloseAction(...)  /  fun close()  // 幂等
}
```

要点：

- `currentApplicationOrNull()`：反射 `android.app.ActivityThread.currentApplication`。
- `runOnMainThreadBlocking(timeoutMs = 2000)`：`CountDownLatch` 同步到主线程（Settings/MiLink 的 UI 注入全用它）。
- 查找器：`findClass` / `findMethod` / `findConstructor` / `findMethodByParamCount` / `findConstructorByParamCount`。
- Hook 注册：`hookAfter` / `hookBefore` / `hookConstructorAfter`，全部经
  `owner.installHook(method, hookIds.next(kind, method))`。
- `StableHookIdGenerator.next()`：`"HuaweiPods/$namespace/$kind/" + sha256("declaringClass#methodName(paramTypes):returnType")[0..16字节hex] + "/$occurrence"`。
- `object Log`：受 `ConfigManager.logLevel()`（`LOG_LEVEL_OFF=0 / BASIC=1 / DEBUG=2`）门控。
- `class HookParam(chain, initialResult)`：`args` / `instance` / `hasResult` / `result` / `proceedWithArgs()`。
- 顶层 `getObjectField` / `setObjectField` / `callMethod`：沿父类链查找。

**Hook 模板**（全仓库统一）：

```kotlin
object SomeHook : HookContext() {
    override fun onHook() { runCatching { hookBefore(findMethod(...)) { ... } }
        .onFailure { Log.w(TAG, "hook X skipped", it) } }   // 静默降级，绝不 crash 宿主
    override fun onCanClose(): Boolean = runOnMainThreadBlocking { }
}
```

### 1.6 构建配置

`app/build.gradle.kts`：

| 项 | 值 |
|---|---|
| `namespace` / `applicationId` | `moe.chenxy.huaweipods` |
| `compileSdk` | 37（`compileSdkMinor = 0`） |
| `minSdk` | **34**（Android 14，1.9.1 从 35 降下来以兼容 HyperOS 2） |
| `targetSdk` | 36 |
| `versionCode` / `versionName` | 21 / `"1.9.1"` |
| Java toolchain | `JavaVersion.VERSION_23`；`kotlin { jvmToolchain(23) }` |
| 插件 | `agp-app`、`lsplugin-resopt 1.6`（`org.lsposed.lsplugin.resopt`）、`kotlinSerialization`、`parcelize`、`compose-compiler` |
| debug 依赖 | `debugCompileOnly(libs.libxposedApi)` + `debugImplementation(libs.libxposedService)` → API 101 |
| release 依赖 | `releaseCompileOnly(libs.libxposedApi102)` + `releaseImplementation(libs.libxposedService102)` → API 102（`resolutionStrategy.force`，122–127 行） |
| UI 依赖 | Compose BOM + `miuix 0.9.2`（ui/preference/icons/blur/navigation3-ui）+ `navigation3 1.1.2` |
| 超级岛 | `com.xzakota.hyper.notification:focus-api:1.4` |

`gradle/libs.versions.toml`：`agp=9.1.0`、`focusApi=1.4`、`kotlin=2.4.0`、`ksp=2.3.6`、`coreKtx=1.19.0`、
`androidx-activity-compose=1.13.0`、`composeBom=2026.05.01`、`kotlinxSerializationJson=1.11.0`、
`libxposedApi=101.0.1` / `libxposedService=101.0.0` / `libxposedApi102=102.0.0` / `libxposedService102=102.0.0`、
`miuix=0.9.2`、`navigation3=1.1.2`。

`gradle.properties`：`preciseShrinking=true`、`enableAppCompileTimeRClass=true`、`useAndroidX=true`、
`ksp.useKSP2=true`、jvmargs `-Xmx4g`。

### 1.7 `AndroidManifest.xml`（`app/src/main/`，83 行）

| 行 | 组件 | 作用 |
|---|---|---|
| 9–15 | `<queries>` 五个目标包 | Android 11+ 包可见性 |
| 45–53 | `.MainActivity` `singleTask` 巨大 `configChanges`，intent-filter `de.robv.android.xposed.category.MODULE_SETTINGS` | **LSPosed 模块设置入口** |
| 54–63 | `.PopupActivity` theme `Theme.HuaweiPods.Popup`、`taskAffinity=""`、`excludeFromRecents`，action `chen.action.huaweipods.show_pods_ui` | 蓝牙页弹出的遥控小窗 |
| 64–71 | `.LowLatencyStateReceiver`（`exported=true`，**permission `android.permission.BLUETOOTH_PRIVILEGED`**） | 蓝牙进程回传低时延写入结果 |
| 72–76 | `.config.PodImageProvider` authority `${applicationId}.podimages` | 把耳机图片跨进程喂给 SystemUI/设置（`binder` 打不开模块资源） |
| 77–80 | `.smartaudio.SmartAudioImageDownloadJobService`（`BIND_JOB_SERVICE`） | 下载「智慧音频」官方配色图 |
| 权限 | `INTERNET` `ACCESS_NETWORK_STATE` `BLUETOOTH_CONNECT` `BLUETOOTH_SCAN` | |

---

## 2. Hook 架构

### 2.1 作用域 × 设备门控矩阵

所有 Hook **两层门控**：

1. **包级**：`HookEntry` 只对 `scope.list` 里的 5 个包调用 `loadHook`；每包一个（或多个）`HookContext`。
2. **设备级**：`HookContext` 内部统一先算 `route = HuaweiDeviceRouteResolver.resolve(address, name)`，
   再查 `HuaweiDeviceCapabilities`（`DeviceCapabilities.kt`）的能力位。**任何写操作前必须
   `route.supportsXxx == true`**，未知机型走 `HUAWEI_DEVICE_ROUTE_UNSUPPORTED` 只读路径。

典型 gate（`SettingsHeadsetHook.kt`）：
`isHuaweiPod(device)` → `DeviceRoutePrefs.find()` / `detectHuaweiDeviceRoute(name)` → `isHuaweiDeviceRouteEnabled(route)`。
`pods/DeviceCapabilities.kt:283-285` 的 `enabledHuaweiDeviceRoutes` / `isHuaweiDeviceRouteEnabled` 是总闸。

### 2.2 `hook/` 目录逐文件

| 文件 | 行数 | 目标包 | Hook 的类.方法 | 作用 |
|---|---|---|---|---|
| `HookEntry.kt` | 274 | — | — | Xposed 入口 + 分派 + 热重载 |
| `HookContext.kt` | 339 | — | — | 共享基建（见 1.5） |
| `HeadsetStateDispatcher.kt` | 490 | `com.android.bluetooth` | `com.android.bluetooth.hfp.HeadsetService` / `BluetoothHeadset` 的连接状态与 AT 命令通路；`HuaweiHfpController` 的启动器 | 侦测 HFP 连接 → 拉起 `HuaweiHfpController` 会话 → 解析 `+HUAWEIBATTERY=` AT 上报 → 广播电量 |
| `BluetoothUpstreamHeadsetHook.kt` | **1511** | `com.android.bluetooth` + `com.xiaomi.bluetooth` | 上游（AOSP/小米）蓝牙协议栈的 ANC / 空间音频 / 设备信息服务代理 | 让系统蓝牙"以为"华为耳机支持其原生 ANC 接口，从而复用系统 UI |
| `BluetoothUpstreamAncPolicy.kt` | 152 | — | — | 上游 ANC 值域映射策略 |
| `BluetoothHeadsetHostPolicy.kt` | 48 | — | — | 宿主能力判定 |
| `SettingsHeadsetHook.kt` | **3442** | `com.android.settings` | `MiuiHeadsetActivity.onCreate`、`MiuiHeadsetActivityPlugin.onCreate`、`MiuiHeadsetFragment.{refreshStatus,handleConnectMmaFailed,updateAncUi,checkAISupport,startAssist,...}`、`HeadsetIDConstants.{checkSupport,isTWS01Headset,isK77sHeadset,isBleMmaConnect}`、`tws.MiuiHeadsetBattery.onBatteryChanged` | 见 §5.2 |
| `SettingsHeadsetPolicy.kt` | 141 | — | — | `settingsHeadsetUiPolicy(route)`：`showAnc/showTransparency/showGestureConfiguration`；`isNativeSettingsAncLevelControl()`；`freeBuds7SettingsAncIndex(3→0,1→1,0→2)`（131–137 行）；`freeBuds7SettingsTransparencyLevel(2→0)`（139–141 行） |
| `SettingsAncUiPolicy.kt` | 81 | — | — | 原生 ANC 控件识别 / 显隐 |
| `HuaweiAncSubModeSelectorView.kt` | 218 | — | — | 自绘"降噪档位"选择器（原生档位条被替换时插入） |
| `HuaweiFreeClip2AudioControlsView.kt` | 359 | — | — | FreeClip 2 空间音频/音效卡片（View，注入设置页） |
| `FreeClip2AudioUiPolicy.kt` | 99 | — | — | 该卡片的显隐/去重 |
| `MiBluetoothToastHook.kt` | 681 | `com.xiaomi.bluetooth` | 小米蓝牙的服务端连接/提示路径 | 把华为耳机伪装成小米生态设备，复用小米蓝牙弹窗 |
| `SmartAudioFreeClip2BridgeHook.kt` | 614 | `com.huawei.smartaudio` | 智慧音频 App 的 GATT/服务端 EQ 通道 | 借官方 App 已建好的连接写 EQ（见 §6） |
| `SmartAudioImageIdentityHook.kt` | 169 | `com.huawei.smartaudio` | 官方设备图资源身份 | 抓官方图片 URL 供模块缓存 |
| `milink/MiLinkServiceHook.kt` | **6422** | `com.milink.service` | 见 2.3 | 融合设备中心 |
| `milink/MiLinkLowLatencyQuickCardPolicy.kt` | 49 | — | — | 低时延快捷卡：`HIDDEN_STATE=-1 / DISABLED_STATE=0 / ENABLED_STATE=103`；`isHostSupported(name) = name == "legacy"` |
| `milink/FreeBuds7MiLinkSpatialPolicy.kt` | 53 | — | — | `0=OFF / 1=FIXED / 2=HEAD_TRACKING`；运行时 `HEAD_TRACKING→11` |
| `milink/FreeClip2MiLinkUiPolicy.kt` | 61 | — | — | 中文/英文标签分类（"噪声控制" "沉浸声" "头部追踪"…） |

### 2.3 `hook/milink/MiLinkServiceHook.kt`（6422 行）— 融合设备中心

`object MiLinkServiceHook : HookContext()`（762 行）。`onHook()`（946–965）依次安装：

```
hookMiLinkMediaVolumeChanges()            // 媒体音量回调
hookMiLinkVolumeProgressChanges()         // SeekBar 进度
hookMiLinkHeadsetIconWrites()             // 耳机图标写入
hookContextEntry()                        // MxBluetoothService/MxBluetoothManager.getInstanceForIsMiTWS(Context) → registerStatusReceiver
hookMxBluetoothRuntime()                  // 见下表
hookMiLinkAudioGlassesClassification()    // wd.a / ce.l.a(wd.a)
hookHeadsetRuntimeDisplay()               // ProfileContext.getDeviceId 等
hookHeadsetCirculationExperiment()        // 4278
hookWindowsHeadsetCirculationCapability() // 4495
hookWindowsHeadsetBondState()             // 4525
hookCirculatePlusHeadsetAncCard()         // 1850
hookCirculatePlusFreeClip2AudioEffectApi()// 2260
hookFreeBuds7SpatialRuntime()             // 2227
hookCirculatePlusFreeClip2AudioEffectCard()// 2336
hookLowLatencyQuickCard()                 // 1574
currentApplicationOrNull()?.let(::registerStatusReceiver)
recreateVisibleMiLinkActivities()         // API 102 不重放 Fragment 生命周期 → 主动 recreate
```

`hookMxBluetoothRuntime()`（1381–1415）覆盖
`com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager` 与 `...service.MxBluetoothService`：

| 方法 | 返回值 |
|---|---|
| `checkIsMiTWS(String)` / `checkIsMiTWS(BluetoothDevice)` | `1` |
| `getDeviceId` | `ConfigManager.fakeDeviceId()`（默认 `01010607`） |
| `getBatteryLevel` | `1` |
| `getAncState` | `miLinkAncRuntimeState()`（真实状态） |
| `getDeviceRunInfo` | `0` |
| `getWearStatus` | `"0,0"` |
| `isLeAudio` | `false` |
| `openAnc` / `closeAnc` / `openTransparent` | `hookAncCommand(..., 2/1, 3)` 拦下并转成华为协议 |
| `isMiTWS` / `isSupportAudioSwitch` / `getRingFindState` | `true` / 真实 / `false` |

### 2.4 融合中心卡片适配表（版本嗅探式）

`MiLinkAncHostSpec`（222–233 行）与 `miLinkAncHostSpecs`（240–265）：

| adapterName | cardClassName | titleIdName(s) | selectCardId | heightMethod | displayValueOrder |
|---|---|---|---|---|---|
| `legacy` | `com.miui.circulateplus.world.headset.j` | `anc_card_title` | `null` | `B` | `OFF_NOISE_TRANSPARENCY` |
| `hyperos4-v18` | `com.miui.circulateplus.world.headset.r` | `anc_card_text` / `anc_card_title` | `anc_select_card` | `W` | `NOISE_TRANSPARENCY_OFF` |

`resolveMiLinkAncHostSpec`（274–293）：OS2 v16 若「无原高度方法、有 `u()`、有 `j.x(int)`」→
切 `hyperos2-v16`，`heightMethodName="u"`、`refreshMethodNames={"x"}`、`displayValueOrder=NOISE_TRANSPARENCY_OFF`。
`hookCirculatePlusHeadsetAncCard`（1850）**对每个兼容 spec 分别绑定**（注释 1882–1885：只挂第一个会漏掉
HyperOS 4 实际创建的卡片）。

音效卡片 spec（310 起）：`legacy` = `...world.headset.w0` `renderMethodName="o"` `HEAD_TRACKING_FIRST`；
`hyperos4-v18` = `...world.headset.h1` `renderMethodName="w"` `FIXED_FIRST`。

---

## 3. 多机型适配（`pods/` 37 文件）

### 3.1 机型注册表：`pods/DeviceCapabilities.kt`（313 行）

`DeviceCapabilities.kt:3-22` 实测 **18 个枚举成员 = 17 条真机 route + `UNSUPPORTED`**：

```kotlin
enum class HuaweiDeviceRoute {          // 第 3-22 行
    HUAWEI_FREEBUDS3,      HUAWEI_FREEBUDS4E,   HUAWEI_FREEBUDS5,   HUAWEI_FREEBUDS5I,
    HUAWEI_FREEBUDS_SE4_ANC, HUAWEI_FREEBUDS6I, HUAWEI_FREEBUDS6,   HUAWEI_FREEBUDS_PRO3,
    HUAWEI_FREEBUDS_PRO4,  HUAWEI_FREEBUDS_PRO5, HUAWEI_FREEBUDS7I,  HUAWEI_FREEBUDS7,
    HUAWEI_FREECLIP,       HUAWEI_FREECLIP2,     HUAWEI_FREEARC,
    HUAWEI_EYEWEAR,        HUAWEI_EYEWEAR2,
    UNSUPPORTED,
}
```

> 口径说明（易混淆）：`pods/` 目录共 **37 个 .kt 文件**；枚举 **17 条真机 route**；
> 已确认协议身份的 `modelId` 白名单 **15 条**（`HuaweiDeviceInfoRoutePolicy`）。
> 「19 款机型」是产品侧按耳塞/眼镜品类合并配色与典藏版后的宣传口径，代码里不存在第 19 个枚举。
> `HUAWEI_FREEBUDS_PRO4` 在 `routeCapabilities` 里**没有**对应条目（未适配，恒为 UNSUPPORTED 行为）。

`data class HuaweiDeviceCapabilities` 的 12 个能力位（全部默认 `false`）：

```
displayName, aliases: Set<String>, supportsAnc, supportsTransparency,
supportsAncStateReadback, supportsDiscreteAncLevels, supportsAncDirectionDial,
supportsRfcommBattery, supportsBackgroundBatteryRefresh, supportsGestureConfiguration,
supportsLowLatencyControl, supportsWindNoiseReduction, hasChargingCase,
usesReportedEarbudAvailability
```

`private val routeCapabilities = linkedMapOf(...)`（41–206 行）就是**唯一注册表**。
名称归一化：`deviceName.lowercase().filter { it.isLetterOrDigit() }`（312–313）。
工具函数：`encode/decodeHuaweiDeviceRouteForBroadcast`（287–301）、
`detectHuaweiDeviceRoute` / `detectKnownHuaweiDeviceRoute`（303–310）、
`enabledHuaweiDeviceRoutes` / `isHuaweiDeviceRouteEnabled`（283–285）。

### 3.2 三路识别（优先级从高到低）

1. **协议身份**（最可信）：`HuaweiDeviceInfoIdentityParser.parse(stream)` 解出 `modelId`，
   经 `HuaweiDeviceInfoRoutePolicy.isCompatible(route, modelId)` 白名单确认。
   白名单（37 行）：`000141`=FreeBuds5、`000145`=5i、`000169`=SE4 ANC、`000135`=4E、`000153`=6i、
   `000155`=6、`000149`=Pro3、`00016D`=Pro5、`000163`=7i、`000174`=7、`000167`=FreeClip2、
   `000173`=FreeClip2 典藏版、`00015D`=FreeArc、`000139`=Eyewear(3)、`00014F`=Eyewear2。
2. **持久绑定**：`config/DeviceRoutePrefs.find(prefs, address)`，键 `device_route_v1_<12位HEX>`。
   `bindIfAbsent` 只填空绑定、不覆盖用户手选（54–57 行注释）。
3. **名称/别名**：`routeCapabilities[route].aliases` 归一化匹配（`resolveBoundOrNamedRoute`）。

`pods/HuaweiDeviceRouteResolver.kt`（69 行）：内存 `sessionBindings: ConcurrentHashMap<String, HuaweiDeviceRoute>`
+ 持久 prefs；`BluetoothDevice.huaweiDeviceRoute()` 扩展 = `name ?: alias`。

`pods/HuaweiDeviceRouteProbePolicy.kt`（85 行）：跨进程**探测**契约——
`REQUEST_SENDER_PACKAGE="moe.chenxy.huaweipods"`、`RESULT_SENDER_PACKAGE="com.android.bluetooth"`、
`MIN_PROBE_INTERVAL_MS=15_000`；`session()` 校验地址正则 + UUID nonce + `generation>0`；
`mayProbeInBluetoothProcess = bonded && systemConnected`。

### 3.3 新增一款机型的步骤（照 FreeBuds 7 的落地顺序）

1. **抓包**：debug 版（API 101 + `ai-life-capture`）只读监听 `com.huawei.smartaudio` 的
   GATT/RFCOMM/L2CAP 流量，用 `operation.*.start` / `operation.*.done` 打点（见 §9b）。
2. **确认身份**：拿到 `modelId`（`5A 00 xx 00 01 07` 的 TLV `0x02`）与配色 → 写
   `docs/<MODEL>_ADAPTATION.md`，登记 `HuaweiDeviceInfoRoutePolicy` 白名单。
3. **加路由 + 能力位**：`DeviceCapabilities.kt` 加 enum 成员与 `routeCapabilities[route] = HuaweiDeviceCapabilities(...)`，
   填 `displayName` / `aliases` / 能力位。
4. **协议常量**：`HuaweiAncPackets.kt` 加帧；`HuaweiAncLevelProfile.kt` 加档位表（若档位与已有表不同）。
5. **控制器**：新建 `Huawei<Model>Controller.kt`（参考 `HuaweiFreeBuds7Controller.kt` 330 行），
   或复用 `HuaweiHfpController` + 能力位分支。
6. **UI**：`ui/components/<Model>Controls.kt` + `PodDetailPage.kt` 的 `when(route)` 分支；
   若支持 EQ → `HuaweiEqualizerCodec` 的 `builtInPresetIds(route)` / `customWriteOperation(route)` / `supportsStateRead(route)` 三个表加一项。
7. **系统页注入**：`SettingsHeadsetPolicy` 的 `settingsHeadsetUiPolicy(route)` 输出沿用能力位；
   新原生控件若不同 → 加 `isNativeSettingsAncLevelControl` 规则。
8. **融合中心**：若宿主卡片类名变化 → 加 `MiLinkAncHostSpec` / `MiLinkAudioEffectHostSpec` 条目（版本嗅探会自动挑）。
9. **CI**：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`；单测放 `src/test`。

### 3.4 机型选择 UI 流

`ui/pages/DeviceModelPickerPolicy.kt`（22 行）：`filterDeviceModelRoutes(routes, query)` 以
`displayName` 去掉非字母数字后做子串匹配；`compactDeviceModelName()` 去掉 `"HUAWEI "` 前缀。
`ui/pages/DevicePickerPage.kt`（593 行）承载选择器；`ui/MainUI.kt` 中
`showDevicePicker` / `MainTab.Earphones` 决定何时进入（`MainUI.kt:311–345, 465, 713`）。
手选后写 `DeviceRoutePrefs.bind(...)`（本地 + `XposedService.getRemotePreferences` 双写）。
首次连接走 `ui/pages/OnboardingPage.kt`（1340 行）+ `OnboardingPolicy.kt`。

---

## 4. 协议层（字节级）

### 4.1 通用帧格式

所有华为帧共用：

```
+--------+---------+------------------+---------+---------------+-----------+--------+
| 0x5A   | 0x00    | payloadLen (u16  | service | message/      | TLV body  | CRC16  |
| magic  | version | 小端，含 service)| (0x01/  | command       |           | (大端) |
|        |         |                  | 0x2B…)  |               |           |        |
+--------+---------+------------------+---------+---------------+-----------+--------+
```

- `HEADER_SIZE = 5`（`HuaweiEqualizerCodec.kt`），`payloadLen` **包含 service 字节**（`body.size + 1`）。
- CRC：**CRC16-XMODEM**（poly `0x1021`，init `0x0000`），**大端**两字节落尾。
- `pods/HuaweiAncPackets.kt`（108 行）里全部是十进制字节字面量 + 手工算好的 CRC；
  `private fun packet(vararg values: Int) = values.map(Int::toByte).toByteArray()`。

### 4.2 命令表（`HuaweiAncPackets.kt`，全部实机抓包确认）

| 常量 | 完整帧（hex） | 说明 |
|---|---|---|
| `huaweiBatteryQuery` | `5A 00 09 00 01 08 01 00 02 00 03 00 FB B9` | 电量查询 |
| `huaweiDeviceInfoQuery` | `5A 00 21 00 01 07` + TLV `01..0C,0F,18,19` 各 `00` 长度 + `DF F3` | 设备身份查询（见 4.3） |
| `modernFreeBudsCurrentStateQuery` | `5A 00 05 00 2B 2A 01 00 42 7E` | 当前 ANC 状态回读 |
| `freeBuds3Enabled(false/true)` | `5A0006002B04010100 6821` / `…0101 01 7800` | FreeBuds 3 开关 |
| `modernFreeBudsEnabled(false/true)` | `5A 00 07 00 2B 04 01 02 00 00 D2 2D` / `…02 01 FF FF EC` | 现代机型 ANC 开关 |
| `modernAncLevels[0xFF]` | `5A 00 07 00 2B 04 01 02 01 FF FF EC` | 开启（等价 enabled=true） |
| `modernAncLevels[0x01]` | `5A 00 07 00 2B 04 01 02 01 01 F1 3D` | 轻度 |
| `modernAncLevels[0x00]` | `5A 00 07 00 2B 04 01 02 01 00 E1 1C` | 均衡 |
| `modernAncLevels[0x02]` | `5A 00 07 00 2B 04 01 02 01 02 C1 5E` | 深度 |
| `modernAncLevels[0x03]` | `5A 00 07 00 2B 04 01 02 01 03 D1 7F` | 智慧动态 |
| `transparencyModes[0xFF]` | `5A 00 07 00 2B 04 01 02 02 FF AA BF` | 通透（默认子模式） |
| `transparencyModes[0x01]` | `5A 00 07 00 2B 04 01 02 02 01 A4 6E` | 通透-标准 |
| `transparencyModes[0x02]` | `5A 00 07 00 2B 04 01 02 02 02 94 0D` | 通透-人声 |
| `transparencyModes[0x04]` | `5A 00 07 00 2B 04 01 02 02 04 F4 CB` | 通透-增强人声（Pro5） |
| `freeBuds3Levels[0..8]` | `5A 00 06 00 2B 08 01 01 0N` + CRC | FreeBuds3 方向拨盘 9 档 |

> **注意**：`0x2B 0x04 0x01 0x02` 这 4 字节是「ANC 写」的固定前缀；关/开用 `…01 02 00 00` / `…01 02 01 FF`，
> 与「档位写」共用同一条命令、仅末字节语义不同 —— 这是全仓库最容易踩混的地方。

### 4.3 设备身份帧解析（`pods/HuaweiDeviceInfoIdentity.kt`，149 行）

```
FRAME_MAGIC=0x5A  FRAME_VERSION=0x00  DEVICE_INFO_SERVICE=0x01  DEVICE_INFO_COMMAND=0x07
FRAME_PREFIX_BYTES=6  FRAME_CRC_BYTES=2
TYPE_MODEL_CODE=0x02  TYPE_MODEL=0x0A  TYPE_MODEL_PREFIX=0x0F  TYPE_SUB_MODEL=0x19
MAX_MODEL_VALUE_BYTES=64  MAX_STREAM_BYTES=64*1024
COMMON_RESOURCE_MODEL_ID="00000A"
modelValueRegex = ^([A-Z0-9]+)-([0-9A-F]{6})$
```

`parse(stream)` 拆帧后要求 `identities.singleOrNull()`（**必须只有一个**，防止多耳机串台）。
`parseFrame` 校验：magic/version/service/command → CRC16-XMODEM → `modelCode == modelId.takeLast(4)` →
`declaredModelPrefix == model 串里的前缀` → `modelId != "00000A"` → `subModelId` 为 1 字节。
输出 `HuaweiDeviceInfoIdentity(modelId, subModelId)`。

### 4.4 电量帧（`pods/HuaweiBatteryParser.kt`，73 行）

AT 上报文本，正则：

```kotlin
"""(?:AT)?\+?(?:UPDATE)?HUAWEIBATTERY\s*[=:]\s*([0-9,\s]+)"""
```

TLV 键：`2`=左耳电量、`3`=左耳充电、`4`=右耳电量、`5`=右耳充电、`6`=仓电量、`7`=仓充电。
`payloadValues()` 若首数字 `count>0` 且 `size >= count*2+1`，则视为「计数+载荷」丢弃首项。
电量 `0..100` 才接受；`Result` 要求至少一路非空。

### 4.5 ANC 档位映射（`pods/HuaweiAncLevelProfile.kt`，124 行）

```kotlin
data class HuaweiAncLevelOption(level: HuaweiAncLevel, protocolValue: Int, miuiValue: Int)
```

| 表 | ADAPTIVE | LIGHT | BALANCED | DEEP | 备注 |
|---|---|---|---|---|---|
| `freeBuds6iAncOptions` | `0x03` | `0x01` | `0x00` | `0x02` | miui 同值 |
| `pro3AndFreeBuds7iAncOptions` | `0x01` | `0x00` | `0x02` | `0x03` | **miui 值 03/01/00/02，与协议值不同！** |
| `freeBuds5AncOptions` | — | `0x01` | `0x00` | — | 仅 3 档 |
| `freeBudsPro5AncOptions` | = 6i 表 | | | | |
| `freeBuds4eAncOptions` | — | `0x01` | `0x00` | — | 2 档 |
| `freeBudsSe4AncOptions` | = 6i 四档 | | | | |

`HuaweiDeviceRoute.ancLevelOptions` 映射：5/6/7→5 表、4E→4e 表、SE4ANC→se4、5i/6i→6i、PRO3/7i→pro3And7i、PRO5→pro5。
`defaultTransparencySubMode`：6i/PRO5→`0x02`，PRO3/7i→`0xFF`。
`transparencySubModes`：6i={01,02}、PRO5={01,02,04}、PRO3={FF,01}、7i={FF}、7={02}，其余空。
`validateAncState(route, state)` 丢弃与能力不符的回读。

**FreeBuds 7 的降噪/通透在设置页是"原生三节点滑条"**（`SettingsHeadsetPolicy.kt:131-141`）：

```kotlin
freeBuds7SettingsAncIndex(3→0, 1→1, 0→2)        // 智慧动态/轻度/均衡 → 0/1/2
freeBuds7SettingsTransparencyLevel(2→0)          // 模板认 0200，华为上报 0202
```

### 4.6 传输层：`pods/HuaweiL2capAncController.kt`（686 行）

名义「L2CAP」，实际是 **RFCOMM 串行通道**（`TAG = "HuaweiPods-L2cap"`），是**整个蓝牙进程唯一的写出口**。
核心 API：

- `requestRawPacketOnce(context, device, route, packet, description, responseWindowMs, responseComplete, onComplete, onResponse)`
- `sendRawPacket(...)` / `sendRawPacketOnce(...)`（`keepSocket` 参数决定是否复用）

服务约束：`pods/HuaweiRfcommServicePolicy.kt`（16 行）把
`{0x1108,0x110A,0x110B,0x110C,0x110D,0x110E,0x110F,0x1112,0x111E,0x111F}`
（HFP/HSP/A2DP/AVRCP/…）**排除**为控制候选 —— 避免抢走音频链路。

响应解析：`pods/HuaweiRfcommResponseParser.kt`（248 行）按 §4.1 帧格式切帧 + 校验 CRC。
日志：`pods/RfcommLog.kt`（74 行）。

### 4.7 FreeBuds 7 命令表（`pods/HuaweiFreeBuds7Controller.kt`，330 行）

`enum class FreeBuds7Setting(queryHex, offHex, onHex, featureId)`（172–191 行）：

| 设置 | query | off | on | featureId |
|---|---|---|---|---|
| `ADAPTIVE_VOLUME` | `5A0008002BB401010202003619` | `5A0009002BB401010202010013E1` | `5A0009002BB401010202010103C0` | `0x02` |
| `HEAD_MOTION` | `5A0006002BB401010B289B` | `…0B 02 01 00 E096` | `…0B 02 01 01 F0B7` | `0x0B` |
| `DROP_REMINDER` | `5A0008002BB40101070200DDE9` | `…07 02 01 00 AFA4` | `…07 02 01 01 BF85` | `0x07` |
| `SINGLE_EAR_ANC` | `5A0008002BB40101050200B389` | `…05 02 01 00 42CC` | `…05 02 01 01 52ED` | `0x05` |
| `AUTO_SWITCH` | `5A0005002B2F0100A98E` | `5A0006002B2E01010037C4` | `5A0006002B2E01010127E5` | — |
| `HIGH_QUALITY`（Hi-Res） | `5A0005002BA30101F…` 实为 `5A0005002BA30101F794` | `5A0006002BA2010100A5CE` | `5A0006002BA2010101B5EF` | — |
| `LOW_LATENCY` | `5A0005002B6C0200B820` | `5A0006002B6C010100B430` | `5A0006002B6C010101A411` | — |
| `WEAR_DETECTION` | `5A0005002B110100772A` | `5A0006002B10010100B977` | `5A0006002B10010101A956` | — |
| `CASE_SOUND` | `5A0007002BB1020003007FAB` | `5A0006002BB101010025B5` | `5A0006002BB10101013594` | — |
| `DOUBLE_TAP` | `5A000700012001000200E897` | `5A000900011F0101FF0201FFCFEE` | `5A000900011F010101020101FC28` | — |
| `SWIPE_VOLUME` | `5A0007002B1F01000200328A` | `5A0009002B1E0101FF0202FFC8C8` | `5A0009002B1E0101000202009D9B` | — |
| `PINCH_PLAY/NEXT/PREVIOUS` | `""` | `""` | `""` | 走 `2B93` 捏合槽位 |

捏合（`pinch()`）：`FreeBudsPro3GestureToggle.{MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS}`，
query = `freeBuds7Frame([0x2B, 0x93, 1, 1, slot, 2, 1, 2])`，
写 = `HuaweiGestureController.buildFreeBudsPro3GestureTogglePacket(toggle, enabled)`。

`writeAndVerify()`（153–168 行）——**全仓库最重要的写-验范式**：

```kotlin
requestPacket(context, device, packet + query, matches) { response ->
    if (matches(response)) complete(true)
    else requestPacket(context, device, query, matches) { complete(matches(it)) }   // 只重询一次
}
```

注释原文：「Keep the RFCOMM connection open until the setting's reply arrives, including after the
initial handshake. **ACKs alone never satisfy this predicate.**」

`FreeBuds7` 其它读取帧：`tripleQuery() = 5A0007000126010002002512`、
`spatialQuery() = 5A000A002BB4010118020003009B3F`。

### 4.8 其它机型控制器

| 文件 | 行数 | 覆盖 |
|---|---|---|
| `HuaweiHfpController.kt` | **2570** | 主会话大脑：连接生命周期、ANC/EQ/手势/电量/低时延广播收发、后台电量刷新、通知 |
| `HuaweiGestureController.kt` | 884 | 双击/三击/捏合/滑动（左右耳分侧） |
| `HuaweiL2capAncController.kt` | 686 | RFCOMM 写通道 |
| `HuaweiFreeClip2Controller.kt` | 590 | FreeClip 2 空间音频/音效/头部动作 |
| `HuaweiFreeBudsPro5Controller.kt` | 432 | Pro5 |
| `HuaweiFreeBuds7iController.kt` | 423 | 7i |
| `FreeBuds3StateSnapshot.kt` | 433 | FreeBuds3 重启后状态快照（TTL 10 min） |
| `HuaweiFreeBuds5Controller.kt` | 336 | 5 / 5i / SE4 ANC |
| `HuaweiFreeBuds7Controller.kt` | 330 | 7 |
| `HuaweiEqualizerCodec.kt` | 271 | EQ 编解码（见 §6） |
| `HuaweiRfcommResponseParser.kt` | 248 | 拆帧 + CRC |
| `HuaweiWearDetectionController.kt` | 172 | 佩戴检测 |
| `FreeClip2AudioStateTracker.kt` | 159 | FreeClip2 音频状态机 |
| `HuaweiWindNoiseReductionController.kt` | 149 | 抗风噪 |
| `HuaweiEqualizerController.kt` | 120 | EQ 门面 |
| `HuaweiAncLevelProfile.kt` | 124 | 档位表 |
| `HuaweiAncPackets.kt` | 108 | 帧常量 |
| `HuaweiSharedControlClient.kt` | 97 | App→蓝牙共享通道客户端 |
| `HuaweiSharedControlHost.kt` | 89 | 蓝牙侧服务端 |
| `HuaweiEqualizerPresetTransport.kt` | 97 | EQ 预设跨进程序列化 |
| `HuaweiDeviceRouteProbePolicy.kt` | 85 | 身份探测策略 |
| `HuaweiLowLatencyController.kt` | 74 | 低时延写 |
| `RfcommLog.kt` | 74 | 串口日志 |
| `HuaweiBatteryParser.kt` | 73 | 电量 AT 解析 |
| `HuaweiDeviceRouteResolver.kt` | 69 | 路由解析 |
| `HuaweiSharedControlPolicy.kt` | 69 | 共享通道操作白名单 |
| `SmartAudioFreeClip2BridgePolicy.kt` | 45 | SmartAudio 桥策略 |
| `PodState.kt` | 42 | `NoiseControlMode{UNKNOWN(0),OFF(1),NC(2),TRANSPARENCY(3)}`、`HuaweiAncLevel{ADAPTIVE,LIGHT,BALANCED,DEEP}`、`HuaweiTransparencyMode{STANDARD,VOICE}` |
| `HuaweiDeviceInfoRoutePolicy.kt` | 37 | modelId 白名单 |
| `LowLatencyWritePolicy.kt` | 35 | 手动写优先于自动写 |
| `HuaweiEqualizerPresetPolicy.kt` | 28 | `FIRST_CUSTOM_ID` / `LAST_CUSTOM_ID` / `nextAvailableId` |
| `BatteryIslandTriggerPolicy.kt` | 25 | 岛屿触发节流 |
| `HuaweiDeviceInfoPublishSession.kt` | 21 | 身份发布会话 |
| `HuaweiRfcommServicePolicy.kt` | 16 | 拒绝 HFP/A2DP UUID |

### 4.9 `smartaudio/` —— 官方图片与 EQ 旁路

| 文件 | 行数 | 作用 |
|---|---|---|
| `SmartAudioImageCache.kt` | 386 | 在**模块进程**下载并缓存「智慧音频」官方设备图；固定华为 CDN；超时 10s/45s；最大解码 4096px / 16.7MP；归一化上限 4.19MP；`VISIBLE_ALPHA_THRESHOLD=8`；失败永远回退内置图 |
| `OfficialSmartAudioArchive.kt` | 208 | 官方资源归档/索引 |
| `OfficialSmartAudioResource.kt` | 156 | 资源模型 |
| `OfficialImageIdentityBridge.kt` | 118 | 蓝牙进程 ↔ 模块的身份桥 |
| `SmartAudioImageBoundsPolicy.kt` | 58 | 图片尺寸约束 |
| `SmartAudioResourceIdentity.kt` | 57 | 资源身份 |
| `SmartAudioImageDownloadJobService.kt` | 48 | `JobScheduler` 承载下载 |
| `SmartAudioImageJobPolicy.kt` | 22 | Job 参数 |
| `OfficialImageCatalogPolicy.kt` | 17 | 目录白名单 |

**给 SystemUI/设置喂图**：`config/PodImageProvider.kt`（114 行）+ `PodImageProviderAccessPolicy.kt`：

```kotlin
imageConsumerPackages = {"com.android.bluetooth", "com.android.settings",
                          "com.milink.service", "com.xiaomi.bluetooth"}
mayOpenImage(callingPackage) = callingPackage == "moe.chenxy.huaweipods" || in 上述集合
maySubmitOfficialImageIdentity(pkg) = pkg == 模块包名 || pkg == "com.android.bluetooth"
```

（注释：Android 蓝牙与小米蓝牙共用 UID 时，Binder 只能可靠给出同 UID 的包集合。）

---

## 5. UI

### 5.1 文件清单（每行一句）

**`ui/pages/`（15）**

| 文件 | 行数 | 角色 |
|---|---|---|
| `HomePage.kt` | 296 | 模块首页（未连接时的引导与状态） |
| `EarphonesTabPage.kt` | 74 | 「耳机」Tab 容器，按 route 分发到各 Controls |
| `PodDetailPage.kt` | 626 | **耳机详情主页面**：电量/ANC/手势/低时延/EQ 全部入口 |
| `DevicePickerPage.kt` | 593 | 机型手动选择器 |
| `DeviceModelPickerPolicy.kt` | 22 | 选择器过滤/短名 |
| `SettingsPage.kt` | 208 | 模块设置（日志级别、岛屿、通知、点击行为） |
| `ThemeSettingsPage.kt` | 79 | 主题/强调色/悬浮底栏/毛玻璃 |
| `OnboardingPage.kt` | 1340 | 首次使用向导 |
| `OnboardingPolicy.kt` | 47 | 向导步进策略 |
| `AboutPage.kt` | 631 | 关于页 |
| `AboutReferencesPage.kt` | 148 | 参考资料/致谢 |
| `SponsorPage.kt` | 119 | 赞助 |
| `DocumentationPage.kt` | 261 | 内置 WebView 文档 |
| `DocumentationUrlPolicy.kt` | 37 | 文档 URL 白名单 |
| `DocumentationNavigationTracker.kt` | 52 | 关联异步 WebView 回调与最新主框架导航 |

**`ui/components/`（16 + 1 effect 子目录）**

| 文件 | 行数 | 角色 |
|---|---|---|
| `AncSwitch.kt` | 459 | 噪声控制主开关/模式切换（含三节点滑条） |
| `AppIcons.kt` | 148 | 自绘图标集 |
| `PodStatus.kt` | 212 | 电量/连接状态卡 |
| `FreeBuds4eControls.kt` | 154 | 4E 专用控制组 |
| `FreeBuds5Controls.kt` | 382 | 5 / 5i / SE4 ANC |
| `FreeBuds6Controls.kt` | 107 | 6 |
| `FreeBuds6iControls.kt` | 121 | 6i |
| `FreeBuds7Controls.kt` | 180 | 7（含 `refreshEqualizer()`，40 行） |
| `FreeBuds7iControls.kt` | **873** | 7i + **通用 `HuaweiEqualizerPreference`（312 行）** |
| `FreeBudsPro5Controls.kt` | 353 | Pro5 |
| `FreeClip2Controls.kt` | 631 | FreeClip 2（空间音频/音效） |
| `FreeArcControls.kt` | 117 | FreeArc |
| `HuaweiGestureControls.kt` | **1049** | 手势配置（双击/三击/捏合/滑动，左右耳） |
| `LowLatencyControl.kt` | 110 | 低时延开关 + 自动保持 |
| `WindNoiseReductionControl.kt` | 112 | 抗风噪 |
| `SmartAudioEqualizerClient.kt` | 105 | App 侧一次性 EQ 客户端（走 SmartAudio 官方连接，2.5s 超时） |
| `effect/Os3BackgroundEffect.kt` | 443 | HyperOS 3 风格背景模糊/流光 |

**`ui/dialogs/`（6）**

| 文件 | 行数 | 角色 |
|---|---|---|
| `AppUpdateDialogs.kt` | 269 | 更新检查/下载/安装对话框 |
| `PodImageConfigDialog.kt` | 470 | 自定义耳机图片配置 |
| `ReleaseNotesMarkdown.kt` | 194 | 更新说明 Markdown 渲染 |
| `RestartScopeDialog.kt` | 150 | 「重启作用域」提示（改配置后必现） |
| `LegacyOfficialImagePromptPolicy.kt` | 51 | FreeBuds 3 无法读配色 → 缺主图时提示一次 |
| `DialogLayout.kt` | 29 | 对话框通用排版 |

**其它**：`ui/App.kt`(125)、`ui/AppLocale.kt`(89)、`ui/Theme.kt`(46)、
`ui/MainUI.kt`(**1447**，NavDisplay + 三 Tab)、`ui/MainTabs.kt`(497)、`ui/MainTabsPagerState.kt`(65)、
`ui/MainBottomNavigation.kt`(81)、`ui/MainTab.kt`(22，`enum {Module, Earphones, About}`)。

### 5.2 深挖：把控制卡注入「系统蓝牙设备详情页」

**目标**：`com.android.settings` 的 `com.android.settings.bluetooth.MiuiHeadsetActivity`
（HyperOS 的耳机详情 Activity）与其 `MiuiHeadsetFragment`。

**为什么能注入**：HyperOS 设置页用「设备 ID」字符串判断要不要渲染完整的耳机面板。
模块把 `ConfigManager.fakeDeviceId()`（默认 `01010607`）冒充成「已支持的小米耳机 ID」。

步骤（`hook/SettingsHeadsetHook.kt`）：

1. **改 Intent**（`hookActivityEntry()`，299–330）：
   ```kotlin
   hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity",
                         "onCreate", Bundle::class.java)) {
       if (!isHuaweiPod(device)) return@hookBefore
       intent.putExtra("MIUI_HEADSET_SUPPORT", settingsSupport())
       intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
       intent.putExtra("DEVICE_ID", fakeDeviceId())
   }
   ```
   `MiuiHeadsetActivityPlugin.onCreate` 同样处理（317–330）。
   `getDeviceID` / `getSupport` 用 `hookActivityStringGetter` 再兜一层（332–342）。
2. **骗过支持性检查**（`hookSupportChecks()`，377–385）：
   `HeadsetIDConstants.checkSupport(String)` → `support.startsWith(fakeDeviceId()) || contains(...)`；
   `isTWS01Headset` → `it == fakeDeviceId()`；`isK77sHeadset` → `false`；
   `isBleMmaConnect` 两个重载均 hook（400–428）。
3. **服务代理拦截**（`hookServiceProxy()`，429 起）：对
   `hookProxyVoidDeviceCommand` / `hookProxyVoidDeviceNoop` / `hookProxyVoidDeviceStringNoop` /
   `hookProxyStringResult` / `hookProxyStringArgResult` / `hookProxyBooleanStringResult`
   统一 `result = null` 或伪造返回值，让设置页的 BLE/MMA 命令不会真的打到耳机（改由模块自己的协议层执行）。
4. **Fragment 状态接管**（`hookFragmentState()`，591 起）：hook
   `MiuiHeadsetFragment.refreshStatus(String,String)`、`handleConnectMmaFailed(String)`；
   `hookFragmentAncUiRender()`（755）hook `updateAncUi(String, boolean)` 之后重绘模块视图；
   `hookFragmentAncCommand()`（773）hook 原生命令方法之前截胡，`SettingsAncPendingGate` 去重。
5. **视图插入点**（`pruneFreeBudsUnsupportedViews(root)`，1930–1949）：
   - `restoreTrackedSettingsCapabilityViews(root)`：RecyclerView 复用条目，**先恢复上一轮裁剪**再重算。
   - `replaceSettingsHeaderImage(root)`：替换头图（走 `PodImageProvider` URI）。
   - `modeButtonContainer(root)`（2738）拿到原生「降噪/通透/关闭」按钮容器；
     `setSettingsCapabilityViewVisible(container, policy.showAnc)`。
   - `setSettingsRowsVisible(root, keywords, visible)`：按**精确文案**关键字隐藏原生行
     （`settingsEarTipFitKeywords` / `settingsNotificationDisplayKeywords` / `gestureEntryKeywords`，
     111 行起）。隐藏用 `bestHideTarget()` 折叠整个 RecyclerView item
     （`shouldCollapseSettingsRecyclerItem`）。
   - **品牌档位条替换**：`replaceHuaweiAncLevelsWithHuaweiDial(root)`（2244）：
     找到原生档位控件 → `parent.indexOfChild(anchor)` → `parent.addView(dial, index + 1, params)`（2389–2390）→
     再用 `HuaweiAncSubModeSelectorView(anchor.context) { subMode -> ... }`（2402/2433）追加档位选择器。
   - **FreeClip 2 音频卡**：`syncFreeClip2AudioControls(root, modeContainer)`（1946）→
     `HuaweiFreeClip2AudioControlsView(...)` + `tag = SETTINGS_FREECLIP2_AUDIO_CONTROLS_TAG`，
     `parent.addView(newControls, (index + 1).coerceAtMost(parent.childCount), MATCH_PARENT×WRAP_CONTENT)`（2025–2033）。
6. **时机与性能**：
   - `SETTINGS_REFRESH_INTERVAL_MS = 5_000`（96 行）周期刷新（162–172 的 `refreshRunnable`，
     仅当存在当前华为 Fragment 时才续跑，否则自我停止）。
   - `SETTINGS_SCROLL_SETTLE_MS = 180`（97 行）：**滚动中不做整树扫描**，
     `addOnScrollChangedListener` 里 `root.postDelayed(task, 180)`（1902–1927），
     且任务执行前再校验 `isAttachedToWindow` / `currentAddress` / `currentHuaweiRoute()` 未变。
   - 注释原文：「RecyclerView 滚动时会高频触发回调。整棵树的恢复、关键词扫描和布局裁剪都必须留到
     滚动停止后再做，否则会与每一帧 measure/layout 争用主线程，造成明显卡顿。」
7. **主题**：`isSettingsDarkMode(anchor.context)` 决定 `darkSurface`；
   模块**自己用 `ModuleResourceResolver`（`utils/ModuleResourceResolver.kt`，53 行）加载本 APK 资源**，
   不依赖宿主资源 id（`moduleString(context, resId, fallback)`）。
8. **生命周期**：`onCanClose() = runOnMainThreadBlocking { }`（188）保证只在主线程空转时卸载；
   `onSaveHotReloadState` 存 address/name/route/anc_confirmed（190–205）；
   `onClose`（207 起）清理所有 `refreshHandler` 回调、`pendingSettingsScrollPrunes`、
   `pendingSettingsInitialPrunes`、`observedSettingsRoots` 的 `ViewTreeObserver` 监听。
   API 102 不重放 Fragment 生命周期 → 类名含 `MiuiHeadsetActivity` 的页面直接 `recreate()`。
9. **「更多设置」入口**（`hookHeadsetMoreSettings()`，344–375）：
   `MiuiHeadsetFragment.checkAISupport()` 直接 `result = true`；
   `startAssist()` 截断后 `startActivity(Intent(ACTION_OPEN_DEVICE_SETTINGS).setClassName(模块包, MainActivity))`，
   带 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`。失败则 `Toast`。

### 5.3 深挖：超级岛（Super Island）

`utils/FocusIslandUtil.kt`（197 行），依赖 `com.xzakota.hyper.notification:focus-api:1.4`：

```
CHANNEL_ID = "huaweipods_focus_island"     CHANNEL_NAME = "HuaweiPods Battery"
NOTIFICATION_ID = 10086
```

- 前置：`ConfigManager.superIslandEnabled()` && 至少一只耳机在线。
- **不传资源，传 Bitmap**：`PodImageLoader.loadIslandLeftBitmap/RightBitmap` →
  `Icon.createWithBitmap(...)`。原因：SystemUI 进程加载不到模块资源。
- 结构：
  ```kotlin
  FocusNotification.buildV3 {
      island {
          islandProperty = 1
          islandTimeout = NotificationPresentationPolicy.batteryIslandTimeoutSeconds(persistent)
          bigIslandArea {
              imageTextInfoLeft  { type = 1; picInfo { type = 1 } }
              imageTextInfoRight { type = 2 }
          }
          shareData { title / content / shareContent }
      }
  }
  enableFloat = !persistent || activeAddress != address
  isShowNotification = false            // 只上岛，不落通知栏
  ```
- 常驻（`persistentIslandEnabled`，默认 **false**）与临时（`TEMPORARY_ISLAND_TIMEOUT_SECONDS`）两档；
  每次电量上报刷新，断开时 `cancelBatteryIsland()`；热重载 `closeForHotReload()`。
- 早于/独立于岛的还有 `utils/miuiStrongToast/MiuiStrongToastUtil.kt`（157 行）：
  通过 `StatusBarManager.setStatus(1, "strong_toast_action", bundle)` 反射发 HyperOS 强提示；
  非 HyperOS 降级为普通 `Toast`。`StringToastBundle` 用 kotlinx.serialization 打包 JSON。

### 5.4 深挖：融合设备中心（MiLink）

见 §2.3 / §2.4。要点：**纯 View 层接管**，不是 Compose——在 MiLink 的 `HeadSetsDetail`
（`com.miui.circulateplus.world.headset.HeadSetsDetail`）里替换/新增 Android `View`，
并通过 hook `MxBluetoothManager` / `AncBatteryController` 的读取方法来"喂"状态。

### 5.5 设置入口（三种）

1. **LSPosed 模块设置**：`MainActivity` intent-filter `de.robv.android.xposed.category.MODULE_SETTINGS`。
2. **系统蓝牙页「更多设置」**：`SettingsHeadsetHook` 的 `startAssist()` 截胡（见 5.2 step 9）。
3. **桌面 / 通知点击**：`<activity-alias .LauncherActivity>` → `MainActivity`；
   通知点击行为由 `ConfigManager.notificationClickAction`
   （`NOTIFICATION_CLICK_MODULE_POPUP=0 / SYSTEM_SETTINGS=1 / SMART_AUDIO=2`）决定；
   `PopupActivity` 通过 `chen.action.huaweipods.show_pods_ui` 被蓝牙进程拉起。

---

## 6. 自定义 EQ

### 6.1 数据模型（`pods/HuaweiEqualizerCodec.kt`，271 行）

```kotlin
object HuaweiEqualizerCodec {
    const val BAND_COUNT = 10
    val GAIN_RANGE = -60..60            // 协议单位；FreeBuds7 每单位 0.2 dB
    const val HEADER_SIZE = 5
    const val CRC_SIZE = 2
    const val CUSTOM_RECORD_SIZE = 36
    const val CUSTOM_NAME_BYTES = 24
    const val MAX_WRITE_NAME_BYTES = 32
}

data class HuaweiEqualizerState(
    supported: Boolean, selectedId: Int, builtInIds: List<Int>,
    bandCount: Int, selectedName: String?, selectedGains: List<Int>?,
    customPresets: List<HuaweiEqualizerPreset>,
) { val isCustom get() = selectedId in 0x64..0x66 }       // 100..102

data class HuaweiEqualizerPreset(id: Int, name: String, gains: List<Int>)
```

**UI 单位换算**（`ui/components/FreeBuds7iControls.kt:326-330`）：

```kotlin
val gainStep  = if (route == HUAWEI_FREEBUDS7) 5 else 10   // 0.2dB vs 0.1dB 每单位
val gainLimit = gainStep * 6                                // 官方编辑器上限 ±6 dB
```

### 6.2 读（状态查询）

`QUERY = 5A 00 05 00 2B 4A 02 00 8C 46`（service `0x2B`, cmd `0x4A`, TLV `02 00`）。

`parseState(stream)` 只接受 `frame[4]==0x2B && frame[5]==0x4A` 的帧，TLV：

| type | 含义 |
|---|---|
| `0x01` | 支持标志（必须 `== 0x01`） |
| `0x02` | 当前选中 id |
| `0x03` | 内置预设 id 列表 |
| `0x05` | 段数 |
| `0x06` | 当前曲线（**有符号字节**） |
| `0x07` | 当前名称（UTF-8，NUL 补齐） |
| `0x08` | 自定义预设（每条 36 字节：id `0x64..0x66` + 段数 + 10 gain + 24 字节名） |

### 6.3 写（自定义曲线）

`buildCustomPacket(gains, presetName, operationValue, presetId = 0x64..0x66)` 的 body：

```
2B 49 01 01 <presetId> 02 01 <BAND_COUNT=0x0A> 05 01 <operationValue>
03 0A <10 gains> 04 <nameLen> <name>
```

`frame()` 前置 `5A 00 <len LE u16 = body.size + 1>`，尾接 CRC16-XMODEM（大端）。

`buildBuiltInPresetPacket(route, presetId)`：body 仅 `2B 49 01 01 <presetId>`。
**每机型允许的内置 id 不同**：

| route | 允许的内置 preset id |
|---|---|
| `4E` | `{1,2,3}` |
| `SE4_ANC` | `{01,02,03,09}` |
| `6i` | `{01,02,03,09}` |
| `6` | `{00,02,03,09}` |
| `FREEARC` | `{01,0A,02,03,09}` |
| `7` | `{02,05,09,0D,0E,0F,10}` |
| `PRO5` | `{02,05,09,0D,0E,0F,10,11}` |
| 其它 | `null`（不可写） |

`customWriteOperation(route)`：`SE4_ANC=0x01`、`6i=0x01`、`PRO5=0x01`、`7=0x01`、
`7i=0x00`、`FREEARC=0x01`，其它 `null`。
`supportsStateRead(route) = {5, 4E, SE4_ANC, 6i, 6, PRO5, 7i, 7, FREECLIP2, FREEARC}`。

### 6.4 三条写入路径（`pods/HuaweiEqualizerController.kt`，120 行）

```
requestState() / setCustom() / setBuiltInPreset()
  ├─ 校验 isTarget(route)（DeviceRoutePrefs.resolve == route）
  ├─ 若 usesSharedConnection（仅模块 App 包名 + HuaweiSharedControlPolicy.supports(route)）
  │     → HuaweiSharedControlClient（广播 + nonce + 8s 超时，见 §8）
  ├─ 否则 → HuaweiL2capAncController.requestRawPacketOnce / sendRawPacketOnce
  └─ FreeBuds 7 自定义 EQ 特判 → HuaweiFreeBuds7Controller.setCustomEqualizer()
```

共享通道操作 id（`HuaweiSharedControlPolicy.kt`）：
`EQ_READ="eq_read"`、`EQ_PRESET="eq_preset"`、`EQ_CUSTOM="eq_custom"`、
`WIND_READ="wind_read"`、`WIND_SET="wind_set"`、`LOW_LATENCY_SET="low_latency_set"`；
仅 `FREEBUDS6I` / `FREEBUDS_SE4_ANC` 支持。`EQ_CUSTOM` 的成功判据：
`selectedId == 0x64 && gains 全等 && name 全等`。

第四条：**借官方 App 的连接**——`ui/components/SmartAudioEqualizerClient.kt`（105 行）+
`hook/SmartAudioFreeClip2BridgeHook.kt`：
发 `ACTION_SMART_AUDIO_FREECLIP2_EQ_SET` 到 `com.huawei.smartaudio`，
`RESULT_TIMEOUT_MS = 2_500`，nonce 匹配 + `setPackage` 定向。

### 6.5 UI

`ui/components/FreeBuds7iControls.kt` 的 `HuaweiEqualizerPreference(...)`（312 行）：

- key 前缀 `"huawei_eq_${address.uppercase()}_"`，曲线存 `prefix + "equalizer"`（CSV）。
- `editable = HuaweiEqualizerCodec.customWriteOperation(route) != null || route == FREECLIP2`。
- `requestOnMount = true` → `DisposableEffect` 里 `HuaweiEqualizerController.requestState(...)`，
  回调里同步 `gains` / `editing` / 落盘 prefs。
- 三个自定义槽位 `FIRST_CUSTOM_ID..LAST_CUSTOM_ID`（`HuaweiEqualizerPresetPolicy`），
  默认名走 `R.string.freeclip2_custom_effect_default_name`（「自定义音效 N」）。
- 编辑器 `openPresetEditor(preset)`：优先编辑当前选中的自定义槽，否则 `nextAvailableId()`。
- `refreshEqualizer()` 在 `FreeBuds7Controls.kt:40` 暴露给详情页刷新。

**预设 vs 自定义**：内置预设是「一条 id」，写 `2B490101<id>` 即完成；自定义是「id + 10 段曲线 + 名称」，
写完整 TLV（`02/05/03/04`），且必须回读 `0x06/0x07` 一致才算成功。

---

## 7. 低时延自动保持

**结论：不是 hook，不是 Service，是「蓝牙进程内、RFCOMM 会话建立后的一次延迟写」。**

链路：

```
UI 开关 (ui/components/LowLatencyControl.kt:59 / FreeBuds6Controls.kt:79 / FreeBuds5Controls.kt:151 / FreeClip2Controls.kt:197)
  → LowLatencyPrefs.setDesired(prefs, xposedService, address, route, enabled)
       ├─ 写本地 prefs（键 freebuds5_<ADDR>_low_latency / freeclip2_<ADDR>_low_latency）
       └─ 同时写 LSPosed remote prefs（同一 PREFS_NAME "huaweipods_settings"）
  → 蓝牙进程读到该键（LowLatencyPrefs.desiredForHook / isAutoApplyEnabled）
```

连接后自动重放（`pods/HuaweiHfpController.kt`）：

| 符号 | 行号 |
|---|---|
| `LOW_LATENCY_AUTO_APPLY_DELAY_MS = 2_500L` | 42 |
| `LOW_LATENCY_AUTO_APPLY_RETRY_DELAY_MS = 2_500L` | 43 |
| `LOW_LATENCY_AUTO_APPLY_MAX_ATTEMPTS = 2` | 44 |
| `lowLatencyAutoApplyRunnable = Runnable { applyAutoLowLatency() }` | 129–131 |
| 调用点 1（HFP 连接建立分支） | 260 |
| 调用点 2（`sendConnected()` 之后） | 416 |
| `scheduleAutoLowLatency()` | 1462–1485 |
| `applyAutoLowLatency()` | 1487–1538 |
| `cancelAutoLowLatency()` | 1548–1552 |
| `setLowLatency(requestedEnabled)`（手动路径） | 1562–1614 |

`scheduleAutoLowLatency()` 的门（1462–1479）：

```kotlin
if (lowLatencyWritePolicy.autoToken() == null) return                 // 手动写进行中 → 让路
if (!LowLatencyPrefs.isAutoApplyEnabled(address, route)) { cancel; return }
if (lowLatencyAppliedGeneration == sessionGeneration) return          // 本次会话已写过
mainHandler.postDelayed(lowLatencyAutoApplyRunnable, 2_500L)
```

`applyAutoLowLatency()` 成功 → `lowLatencyAppliedGeneration = request.generation`；
失败且 `attempt < 2` → 2.5s 后重试一次；否则放弃并 `Log.w`。

**并发保护**（`pods/LowLatencyWritePolicy.kt`，35 行）：revision 计数 + `pending` 标志，
`beginManual()` / `isCurrent(token)` / `finishManual(token)` / `autoToken()` / `canAutoWrite(token)` /
`resetSession()`。会话开始（153/469/1357 行）都 `resetSession()`。

**结果回传**：蓝牙进程 `sendLowLatencyState(effectiveEnabled, writeSuccess)`
→ 广播 `chen.action.huaweipods.huawei_low_latency_changed`
→ `LowLatencyStateReceiver`（38 行，**受 `android.permission.BLUETOOTH_PRIVILEGED` 保护**）
→ 校验 `EXTRA_HUAWEI_LOW_LATENCY_WRITE_SUCCESS == true` 且 route 支持 → `LowLatencyPrefs.setDesired(...)`。

**协议帧**（`pods/HuaweiLowLatencyController.kt`，74 行）：

```
DISABLED: 5A 00 06 00 2B 6C 01 01 00 B4 30
ENABLED : 5A 00 06 00 2B 6C 01 01 01 A4 11
```

**已知限制（写进 release notes 的）**：
协议**没有可靠回读**，通用路径下「写成功」只代表链路 ACK，不代表耳机真的进入低时延；
FreeBuds 6 / 7 有专属面板支持 `2B6C/02` 状态回读（`5A0005002B6C0200B820`）。
FreeBuds 7 的 `FreeBuds7Setting.LOW_LATENCY` 即走 `writeAndVerify`。

---

## 8. 配置 / 持久化 / 跨进程

### 8.1 存储

| 类 | 行数 | 键/内容 |
|---|---|---|
| `config/ConfigManager.kt` | 376 | `PREFS_NAME="huaweipods_settings"`；`PREF_KEY_CONFIG_JSON="config_json"` 存 `@Serializable AppConfig`（11 个字段：fakeDeviceId / logLevel / islandMode / superIslandEnabled / persistentIslandEnabled / persistentNotificationEnabled / lockscreenNotificationEnabled / notificationClickAction / moreClickAction / milinkLowLatencyCardEnabled）；另有扁平镜像键（`PREF_KEY_*`）供 remote prefs 直接读 |
| `config/DeviceRoutePrefs.kt` | 228 | `device_route_v1_<12HEX>` → route 名；`bind` / `bindIfAbsent` / `syncWithRemote` |
| `config/LowLatencyPrefs.kt` | 162 | `freebuds5_<ADDR>_low_latency`、`freeclip2_<ADDR>_low_latency`、`low_latency_auto_v1_<12HEX>`；正则白名单键 |
| `config/PodImagePrefs.kt` | 373 | `EarphonePref(address,name,box/left/rightImagePath, cloudModelId, cloudSubModelId, cloud*ImagePath, lastConnectedAt)`；`AUTHORITY` |
| `config/AppLifecyclePrefs.kt` | 161 | 启动决策（版本升级/首次启动） |
| `config/NotificationPresentationPolicy.kt` | 48 | `effectiveIslandMode()` / `batteryIslandTimeoutSeconds(persistent)` / `TEMPORARY_ISLAND_TIMEOUT_SECONDS` |
| `config/PodImageChangeNotifier.kt` | 29 | 图片变更广播 |

### 8.2 三个跨进程通道

**(a) LSPosed RemotePreferences（首选，双向、强一致）**

- 模块侧：`HuaweiPodsApp` 实现 `XposedServiceHelper.OnServiceListener`，
  `onServiceBind` 拿到 `xposedService`，把 `DeviceRoutePrefs.syncWithRemote` /
  `LowLatencyPrefs.syncWithRemote` / `PodImagePrefs.syncSnapshotToRemote` 全量推给远程。
- 蓝牙/设置侧：`HookEntry.loadHook` 里 `module.getRemotePreferences(ConfigManager.PREFS_NAME)`，
  注册 `OnSharedPreferenceChangeListener`：`config_json` 变 → `ConfigManager.refreshFromPrefs`；
  绑定键变 → 刷新 route bindings。
- 约束：两侧 `PREFS_NAME` **必须同名**（`ConfigManager.kt:25` 注释原文）。

**(b) 广播契约 `utils/miuiStrongToast/data/HuaweiPodsAction.kt`（116 行）**

前缀 `PREFIX = "chen.action.huaweipods"`，约 60 个 action，可分组：

| 组 | 代表 action |
|---|---|
| UI | `ACTION_SHOW_PODS_UI`、`ACTION_OPEN_DEVICE_SETTINGS`、`ACTION_SEND_STRONG_TOAST` |
| 电量/ANC | `ACTION_PODS_BATTERY_CHANGED`、`ACTION_PODS_ANC_CHANGED`、`ACTION_ANC_SELECT`、`ACTION_HUAWEI_ANC_LEVEL_SET/CHANGED/REFRESH` |
| 手势 | `ACTION_HUAWEI_GESTURE_*` |
| 空间音频 | `ACTION_HUAWEI_SPATIAL_*` |
| FreeClip2 | `ACTION_FREECLIP2_AUDIO_SET/CHANGED`、`ACTION_SMART_AUDIO_FREECLIP2_*` |
| EQ | `ACTION_HUAWEI_EQUALIZER_PRESET_SET` / `_REFRESH` / `_CHANGED` |
| 低时延 | `ACTION_HUAWEI_LOW_LATENCY_SET` / `_CHANGED` |
| 共享控制 | `ACTION_SHARED_CONTROL_REQUEST` / `_RESULT` |
| 身份探测 | `ACTION_DEVICE_ROUTE_PROBE_REQUEST` / `_RESULT` |
| 配置/图片 | `ACTION_CONFIG_CHANGED`、`ACTION_POD_IMAGES_CHANGED` |

关键 extras：`EXTRA_DEVICE_ROUTE="device_route"`、`EXTRA_ROUTE_PROBE_{ADDRESS,GENERATION,NONCE,MODEL_ID,SUB_MODEL_ID}`、
`EXTRA_HUAWEI_EQUALIZER_SELECTED_ID/_CONFIRMED`。辅助扩展：`IntentFilter.addHuaweiPodsAction(s)`。

**安全要点**：所有请求带 `nonce`（UUID）；接收方校验 `sentFromPackage` / `getPackage()`；
`HuaweiSharedControlClient` 只接受 `sentFromPackage == "com.android.bluetooth"`、
address/route 匹配、payload ≤ 64 KiB。低时延回传广播受 `BLUETOOTH_PRIVILEGED` 保护。

**(c) ContentProvider（大对象：图片）**

`config/PodImageProvider.kt`（114 行），authority `${applicationId}.podimages`，
`PodImageProviderAccessPolicy` 白名单四个消费包；另有
`SmartAudioImageCache.PROVIDER_METHOD_RECORD_IDENTITY = "record_smart_audio_resource_identity"` 自定义方法。

**(d) 共享控制通道（走蓝牙进程已有的 socket）**

`HuaweiSharedControlClient`（App 侧）↔ `HuaweiSharedControlHost`（蓝牙侧，89 行）：
App 发 `ACTION_SHARED_CONTROL_REQUEST`（`setPackage("com.android.bluetooth")`、
`FLAG_RECEIVER_FOREGROUND`），蓝牙进程用**已经建好的** RFCOMM 连接执行，再回 `ACTION_SHARED_CONTROL_RESULT`。
8 秒超时（`Handler(Looper.getMainLooper())` + `postDelayed`）。
目的：避免 App 侧再建一条 socket 与官方 App/系统抢连接。

---

## 9. Release notes 扫描（`.github/release-notes/`，11 个文件 1.5.0 → 1.9.1）

### 1.9.1（最新）

- 新增 **FreeBuds 6**（识别/官方配色图/电量/三档降噪 + 回读/部分音效/自适应音量/佩戴/掉落提醒/盒提示音/低时延回读与重连自动应用）。
- 新增 **FreeClip 2 典藏版**（独立 product identity `000173` + 配色图；点头/摇头来电动作；"嘈杂环境大音量" + 回读；盒提示音，不支持自定义声音）。
- **HyperOS 2 兼容**：`minSdk` 35 → **34**；兼容旧版蓝牙/蓝牙扩展/融合中心接口。
- 通知优先显示系统蓝牙里的设备名；新增**默认关闭**的「电量超级岛常驻」。
- SE4 ANC / 6i 音效控制复用蓝牙进程连接（减少连接冲突）。
- 修复：SE4 ANC 等从模块操作低时延**重复建链**；**自动开启与手动关闭的执行顺序冲突**（旧请求/回调覆盖新操作 → 即 `LowLatencyWritePolicy` 的由来）；FreeClip 2 典藏版重连后低时延不再自动开启（**过滤旧版融合中心退出详情时误触发的关闭请求** —— `hookLowLatencyQuickCard` 里过滤 `setFindRing` 的 cleanup 调用，见 1584–1604 注释）；修正部分融合中心版本打开典藏版详情后**界面自行消失**（跨版本误调私有高度方法）。

### 1.9.0

- 新增 **FreeBuds 7** 全量适配（识别/官方图/三路电量/ANC + 三档/空间音频三态/双击三击捏合滑动/佩戴/低时延/高清音质/8 种官方音效 + 10 段 EQ）。
- 系统详情页用**原生三节点滑条**；融合中心接空间音频状态回读与控制。
- 修复：7 空间音频写失败时融合中心接受耳机回报的实际状态并允许立即重试；
  旧版融合卡片重复刷新降噪高亮**反复触发触感反馈**（→ 现在用 `SettingsAncPendingGate` + `guardAsUiOnly` 去重）。
- 验证范围声明：仅特定固件 + HyperOS 3 / MiLink 17.2.6 实机验证。

### 1.8.3

- 修复模块弹窗内低时延写入成功后**未保存**（HyperOS 4 上 FreeBuds 6i 重开弹窗开关回弹）。
  现在由**应用进程**保存（蓝牙进程只负责写 + 广播回执）。
- 写失败时弹窗回到已保存值，不把临时点击当生效。
- **对保存结果的广播接收器加 `android.permission.BLUETOOTH_PRIVILEGED`**，防普通应用伪造写入成功。
- 明确：HyperOS 4 上融合中心低时延快捷卡仍只支持已验证的**旧版宿主**
  （→ `MiLinkLowLatencyQuickCardPolicy.isHostSupported(name) = name == "legacy"` 的直接来源）。

**版本特定陷阱汇总**

1. **HyperOS 4 融合中心低时延快捷卡不可用**（只认 `legacy` 宿主）。要在模块弹窗/详情页操作。
2. **API 102 不重放 Fragment 生命周期** → 改配置后必须 `recreate()` 可见页面（Settings/MiLink 都有）。
3. **MiLink 有 `:ui` 子进程** → "重启作用域"必须重启整个 scope，不能只重启主进程。
4. **HyperOS 2 与 HyperOS 3/4 的融合中心卡片类名/方法名都不同** → 用 `MiLinkAncHostSpec` 版本嗅探，
   别硬编码 `j` / `r`。
5. **`2B6C/03` 是能力查询，不是状态**；**ACK 不等于状态** —— 只有带匹配 feature 的状态帧才算成功。
6. **FreeBuds 7 自定义 EQ 单位是 0.2 dB**（其它机型 0.1 dB），UI 步长必须分叉。
7. **FreeBuds 7 空间音频运行时 id：`HEAD_TRACKING = 11`**（显示域 0/1/2）。
8. 升级后若低时延自动保持此前被关过，需**手动再开一次**（配置迁移不覆盖 false）。

---

## 9b. `docs/` 适配方法论

`docs/` 结构：`index.md`、`guide/getting-started.md`、`support/index.md`、
`DEBUG_CAPTURE_GUIDE.md`（7909 B）、`FREEBUDS6_ADAPTATION.md`（2330 B）、
`FREEBUDS7_ADAPTATION.md`（8694 B）、`FREECLIP2_COLLECTOR_ADAPTATION.md`（3696 B）+ vitepress 配置。

### 9b.1 抓包方法论（`DEBUG_CAPTURE_GUIDE.md`）

- debug 版锁定 **LSPosed API 101**，只 hook `com.huawei.smartaudio`，**只观察 GATT/RFCOMM/L2CAP 流量，
  绝不修改/阻断/重放**。
- 向导打点：`operation.*.start` / `operation.*.done` 把「单变量改变」夹在两个标记之间。
- 导出 ZIP = `metadata.json` + `events.jsonl` + `README.txt`。
- **维护者分析五步**：
  1. 用标记切出每个操作窗口；
  2. 对比「关 → 开」或「档位 A → 档位 B」的 **TX 帧差**；
  3. 确认对应 RX 回包 / 状态通知 / 校验和；
  4. **第二轮重抓**，把稳定字段与计数器/时间戳分开；
  5. 才把命令加进该机型的 route；未知机型保持**只读、不可写**。
- 明确非目标：固件升级、恢复出厂、解绑、真实通话/录音、健康/位置/通知数据、查找耳机响铃。
- `protocol_event_count == 0` 说明作用域没生效（只有标记没有事件）。

### 9b.2 单机型适配文档范式（以 FreeBuds 7 为例）

每份 `<MODEL>_ADAPTATION.md` 固定包含：

1. **环境**：机型、系统、HyperOS 版本、LSPosed 版本、耳机固件、智慧音频版本、MiLink 版本、
   product/modelId、配色 code、codename。
2. **能力清单**：逐项写「命令 + 回读命令 + 值域」。
   例：ANC `2B04` 写 / `2B2A` 读；智慧动态 `3` / 轻度 `1` / 均衡 `0`（无深度）；
   通透回读 subMode `2`（系统模板码 `0200`）；空间音频 `2BB4` feature `18`：off `0` / fixed `2` / tracking `1`；
   智慧功能 `2BB4` feature `02/0B/07/05`；自动切换 `2B2E/2B2F`；Hi-Res `2BA2/2BA3`；
   低时延/佩戴/盒提示音 `2B6C` / `2B10,11` / `2BB1`；双击/滑动/三击 `011F,20` / `2B1E,1F` / `0125,26`；
   媒体捏合 `2B92,93`（slot 0/1/2, context `02`）；EQ `2B49,4A`（自定义保存 operation `01`，0.2 dB/单位）。
3. **坑位**：ACK 不算状态；Hi-Res 与空间音频可能互斥，切完要刷新相关状态；
   MiLink 显示域 `off 0 / fixed 1 / tracking 2`，运行时 tracking `11`。
4. **验证范围**：明确"哪些功能没验过"，不夸大。
5. **CI 命令**。

FreeBuds 6 的补充教训：**不要复用 6i 的通透/四档 ANC/音效 id**，6 是独立 route；
默认音效不是 6i 的 `01` 而是 `00`；`2B6C/03` 是能力查询不是状态。

FreeClip 2 典藏版教训：**product identity `000173` vs 普通 `000167`**；
配色 code `00` 银 / `05` 蓝；头控 TLV `03`=点头、`04`=摇头，action `00/01/02` = 无/接听/拒接；
`2B87` 的正确含义是「嘈杂环境提高媒体音量」（不是「音质优先」），回读 `2B88`。

---

## 10. 给 fiil_mod 的迁移清单（可复用骨架）

| 可直接复用 | 需替换 |
|---|---|
| `HookEntry` + `HookContext` + `StableHookIdGenerator` + 热重载状态机 | `java_init.list` 里的入口类名 |
| `SettingsHeadsetHook` 的注入范式（Intent 改包 → 支持性检查 → 服务代理 → Fragment 接管 → 视图插入 → 滚动节流 → 清理） | 目标 Activity/Fragment 类名与 `fakeDeviceId` |
| `MiLinkServiceHook` 的「宿主 spec 表 + 版本嗅探」结构 | 卡片类名/方法名/值域顺序 |
| `HuaweiL2capAncController` 的串行写通道 + 响应窗口 + `writeAndVerify` 范式 | 帧格式、CRC 算法、service/command 字节 |
| `HuaweiEqualizerCodec` 的 TLV 编解码骨架 + `supportsStateRead/customWriteOperation` 表 | 段数、增益范围、preset id 表 |
| `LowLatencyPrefs` + `LowLatencyWritePolicy` + 「连接后延迟重放」 | 低时延帧 |
| `FocusIslandUtil`（`Icon.createWithBitmap` 绕开资源） | 频道名/通知 id |
| `PodImageProvider` + `PodImageProviderAccessPolicy`（跨进程大对象） | authority |
| `HuaweiPodsAction` 广播契约 + nonce + 发件人校验 | action 前缀与 extras |
| 抓包方法论（debug 变体只读 + 打点 + 双轮验证） | 目标 App 包名 |

> **三条硬纪律**：① 任何写操作前先过能力位，未知机型只读；
> ② ACK 不是状态，必须有匹配 feature 的回读帧；
> ③ 宿主 UI 注入只在主线程、且只在滚动/布局稳定后执行，卸载时完整清理监听器与回调。
