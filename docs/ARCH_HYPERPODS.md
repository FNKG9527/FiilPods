# HyperPods AAP — 架构解剖（最小可用骨架参考）

> 只读分析对象：`<repo>\refs\HyperPods`（单 commit `9796d94`）
> 代码根：`app\src\main\java\moe\chenxy\hyperpods\`
> 许可：GPL-3.0-or-later（`README.md:25`）。上游 AAP 协议定义参考 kavishdevar/aln（`README.md:21`）。

## 0. 三条必须先纠正的预期

| 预期 | 实际 |
|---|---|
| `assets/xposed_init` 指向 libxposed / `IXposedHookLoadPackage` | **不是**。用 **YukiHookAPI 1.3.1** + KSP 生成 `HookEntry_YukiHookXposedInit`（`assets/xposed_init` 单行） |
| 有 `BluetoothGatt` / `BluetoothLeScanner` / `onCharacteristicChanged` | **完全没有**（全仓 grep 零命中）。README `:15` 明写 "BLE listen is removed until we should support the other pods" |
| 电池从 GATT 特征值解析 | 电池从 **L2CAP CoC socket（PSM 0x1001）** 读到的 AAP 原始帧按偏移解析 |

**因此本仓最有价值的骨架 = Xposed 入口 + L2CAP socket 建连/握手/收帧 + 按长度与魔数分派 + 偏移解析电池。** GATT 那套要另找参照。

## 1. 模块骨架

### 1.1 构建层

| 文件 | 关键行 | 内容 |
|---|---|---|
| `settings.gradle.kts` | `:16-18` | 仓库含 `https://api.xposed.info/`（拿 `de.robv.android.xposed:api`）、`s01.oss.sonatype.org`（YukiHook）、jitpack |
| `settings.gradle.kts` | `:23` | `org.gradle.toolchains.foojay-resolver-convention` 0.8.0 |
| `gradle\libs.versions.toml` | `:2-14` | agp 8.13.0 / kotlin 2.2.20 / ksp 2.2.20-2.0.3 / compose-plugin 1.9.0 / **xposedApi 82** / **yukihookApi 1.3.1** / **yukonga-miuix 0.5.1** / haze 1.6.10 |
| `gradle\libs.versions.toml` | `:21-22` | `org.lsposed.lsplugin.apksign:1.4`（从 gradle.properties 读签名）、`lsplugin:resopt:1.6` |
| `app\build.gradle.kts` | `:13-18` | `apksign { storeFileProperty = "KEYSTORE_FILE" … }` — 私钥不进仓 |
| `app\build.gradle.kts` | `:21-29` | namespace/applicationId `moe.chenxy.hyperpods`；compileSdk **36**；minSdk **35**；targetSdk 36；versionName `2.0.2-AAP-W-HyperOS3` |
| `app\build.gradle.kts` | `:61-69` | Java/Kotlin toolchain **22** |
| `app\build.gradle.kts` | `:91-96` | `externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }` |
| `app\build.gradle.kts` | `:106-108` | `compileOnly(libs.xposedApi)` + `implementation(libs.yukihookApi)` + `ksp(libs.yukihookKsp)` ← 三件套是 YukiHook 的标准写法 |
| `gradle.properties` | `:7` | `ksp.useKSP2=true` |
| `proguard-rules.pro` | — | `-repackageclasses` / `-allowaccessmodification` / `-overloadaggressively`（release 混淆极激进） |

### 1.2 AndroidManifest.xml（42 行，零权限）

- `:4-11` `<application>`：`extractNativeLibs="false"`、`enableOnBackInvokedCallback="true"`
- `:13-21` 唯一 Activity `.MainActivity`，`launchMode="singleTask"`，`exported="true"`，intent-filter action **`chen.action.hyperpods.show_airpods_ui`** ← SystemUI 侧点击设备卡片时用它拉起本 App 的 Compose 详情页
- `:23-25` `meta-data xposedmodule = "true"`
- `:28-30` `xposeddescription = "Apple AirPods Support for Xiaomi HyperOS."`
- `:34-36` `xposedminversion = 93`
- `:37-39` `xposedscope = @array/xposedscope`
- `res\values\arrays.xml:3-8` scope = `com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.android.systemui`（`com.android.settings` 已注释）
- 注意：**没有声明任何 `<uses-permission>`**——因为代码全部跑在被注入进程（蓝牙栈 / SystemUI）里，继承宿主权限，故 `@SuppressLint("MissingPermission")` 遍地。

### 1.3 三份入口文件（这是最容易踩坑的地方）

| 路径 | 内容（原文） |
|---|---|
| `assets\xposed_init` | `moe.chenxy.hyperpods.hook.HookEntry_YukiHookXposedInit` ← **KSP 生成的类**，不是你写的类 |
| `resources\META-INF\yukihookapi_init` | `moe.chenxy.hyperpods.hook.HookEntry` |
| `assets\native_init` | `libhyperpods_hook.so` |

`hook\HookEntry.kt:9-19`：

```kotlin
@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {
    override fun onHook() = YukiHookAPI.encase {
        loadApp("com.android.systemui",  SystemUIPluginHook)
        loadApp("com.android.bluetooth", HeadsetStateDispatcher)
        loadApp("com.xiaomi.bluetooth",  MiBluetoothToastHook)
    }
    override fun onInit() = configs { isDebug = BuildConfig.DEBUG }
}
```

`assets\native_init` 是 **LSPosed 的 native hook 入口**：`cpp\hyperpods.cpp:150-154` 导出
`NativeOnModuleLoaded native_init(const NativeAPIEntries *entries)`，回填 `hook_func = entries->hook_func` 并返回 `on_library_loaded` 回调。签名见 `cpp\hook_base.h:9-15`（`version / hook_func / unhook_func`）。

### 1.4 MainActivity + Compose + MIUIX

`MainActivity.kt:14-34` 只有 34 行，是理想的「最小壳」：

```kotlin
class MainActivity : ComponentActivity() {
  override fun onCreate(...) {
    setContent {
      val darkMode = isSystemInDarkTheme()
      DisposableEffect(darkMode) {
        enableEdgeToEdge(                                   // :22
          statusBarStyle     = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkMode },
          navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkMode })
        window.isNavigationBarContrastEnforced = false       // :27 “Xiaomi moment, this code must be here”
        onDispose {}
      }
      App()                                                  // :32
    }
  }
}
```

`ui\Theme.kt:10-23`：`AppTheme` 只包一层 `MiuixTheme(colors = lightColorScheme() / darkColorScheme())`，`colorMode` 0=跟随系统 / 1=亮 / 2=暗。
`ui\App.kt:7-11`：`App() = AppTheme { MainUI() }`。
`themes.xml`：`Theme.HyperPods` 继承 `android:Theme.Material.Light.NoActionBar`（Compose 全接管，无需 AppCompat）。

MIUIX 用法（`top.yukonga.miuix.kmp`）：`Scaffold` / `TopAppBar` / `SmallTopAppBar`（`maxWidth > 840.dp` 切换，`MainUI.kt:211`）/ `NavigationBar` / `HorizontalPager` / `Card` / `LazyColumn` / `SuperSwitch` / `BasicComponent` / `Text` / `Box` / `Surface` / `MiuixScrollBehavior` / `MiuixTheme.colorScheme` / `MiuixIcons.{Settings,Info}`。毛玻璃用 **haze**：`hazeChild(hazeState){ style = hazeStyle; blurRadius = 25.dp; noiseFactor = 0f }`（`MainUI.kt:216-222`）。

## 2. `hook\` 5 个文件 — 逐 target

| 文件 | 目标包 | 目标类.方法 | 作用 |
|---|---|---|---|
| `HookEntry.kt` | — | — | 见 §1.3；唯一 `loadApp` 分发点 |
| `SystemUIPluginHook.kt` (49 行) | `com.android.systemui` | `com.android.systemui.shared.plugins.PluginInstance#loadPlugin`（`:24-26`） | `after` 里取 `getPackage()`，仅当 == `miui.systemui.plugin` 时，反射挖出 `mPluginFactory` → `mClassLoaderFactory` → `get()` 得到**插件 ClassLoader**，缓存后 `loadPluginHooker(DeviceCardHook)`（`:13-20`）。这是「MIUI 把控制中心拆成独立 APK 插件」后必须两段式注入的解法 |
| `DeviceCardHook.kt` (108 行) | 同上（跑在插件 CL） | `miui.systemui.controlcenter.panel.main.MainPanelController#onCreate`（`:42-48`）<br>`miui.systemui.devicecenter.devices.DeviceInfoWrapper#performClicked(Context)`（`:56-60`） | ① 保存 panel 实例，`hidePanel()` 调 `exitOrHide()`（`:50-54`）② `before` 里取 `getDeviceInfo().getId()/getDeviceType()`，只处理 `deviceType == "third_headset"`（`:66`）③ 广播 `ACTION_GET_PODS_MAC` 到 `com.android.bluetooth` 拿 MAC（`:81-85`），自旋等待 10×50ms（`:87-90`）④ `mac == id` 则 `startActivity(chen.action.hyperpods.show_airpods_ui)` + `hidePanel()` + `this.result = null` 吞掉原点击（`:92-100`） |
| `HeadsetStateDispatcher.kt` (96 行) | `com.android.bluetooth` | `com.android.bluetooth.a2dp.A2dpService#handleConnectionStateChanged`（3 参，`:38-42`） | **总开关**。`after` 取 `args[2]=currState / args[1]=fromState / args[0]=device`，`instance` 转 `ContextWrapper` 取 context 与 `mHandler` 投递（`:51`）。`isPods(device)` 用 UUID 白名单判定（`:22-25`、`:87-94`）。连上 → `StatusBarManager.setIconVisibility("wireless_headset", true)`（`:63`）→ `nativeGetHookResult()` 失败则 Toast 报错返回（`:65-73`）→ `L2CAPController.connectPod(context, device, prefs)`（`:74`）。断开 → icon false + `L2CAPController.disconnectedPod`（`:76-79`） |
| `MiBluetoothToastHook.kt` (258 行) | `com.xiaomi.bluetooth` | `com.android.bluetooth.ble.app.MiuiBluetoothNotification` 的 **2 参构造**（`:183-186`） | 寄生在小米蓝牙自己的通知对象上：构造后拿 `mContext`（`:189`），注册 `RECEIVER_NOT_EXPORTED` 广播接收器（`:248-252`）收三个自定义 action（`sendstrongtoast` / `updatepodsnotification` / `cancelpodsnotification`），从而**借用 `com.xiaomi.bluetooth` 的进程身份**发强提示与常驻通知。内含 `CASE_MP4_BASE64`（`:33`，一整段 base64 mp4）落地到 `filesDir/my_internal_files/airpods_gen3_case.mp4` 供强提示播放（`:36-54`）；`createPodsNotification`（`:88-168`）用 `context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")` 复用小米文案资源 |

UUID 白名单（`HeadsetStateDispatcher.kt:22-25`）：
`74ec2172-0bad-4d01-8f77-997b2be0722a`、`2a72e02b-7b99-778f-014d-ad0b7221ec74`（后者是前者的字节反序）。

## 3. `pods\` 2 个文件 — 真正的「BLE 监听」替代品

### 3.1 `L2CAPController.kt`（596 行，`object` 单例）

**建连**（`connectPod`，`:313-381`）：

1. `:314-318` 存 context / device / `mPrefsBridge`（YukiHook 的跨进程 SharedPreferences 桥），`updateFeatureToggle()` 读两个开关。
2. `:320-325` `registerReceiver(..., Context.RECEIVER_EXPORTED)` 监听 `ACTION_ANC_SELECT` / `ACTION_PODS_UI_INIT` / `ACTION_EAR_DETECTION_SWITCH_CHANGED` / `ACTION_GET_PODS_MAC`。
3. `:336` `uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")`。
4. `:338-348` **反射造 socket**，PSM 硬编码 `0x1001`，`type = 3`（L2CAP CoC）：

```kotlin
// Android ≤15
BluetoothSocket::class.java.getDeclaredConstructor(
    IntType, BooleanType, BooleanType, BluetoothDevice::class.java, IntType, ParcelUuid::class.java
).newInstance(3, true, true, device, 0x1001, uuid)
// Android 16 "Baklava" 参数顺序变了 → catch NoSuchMethodException 回退
BluetoothSocket::class.java.getDeclaredConstructor(
    BluetoothDevice::class.java, IntType, BooleanType, BooleanType, IntType, ParcelUuid::class.java
).newInstance(device, 3, true, true, 0x1001, uuid)
```

5. `:350-380` `Dispatchers.IO` 协程：`delay(500)` → `socket.connect()` → **握手三段，每段后 `delay(200)`**：

```
write(Enums.HANDSHAKE)             // 16 B
write(Enums.SET_SPECIFIC_FEATURES) // 14 B
write(Enums.REQUEST_NOTIFICATIONS) // 10 B
```

6. `:367-379` 读循环：`ByteArray(1024)`，`read() > 0` → `handleAirPodsPacket(buffer.copyOfRange(0, bytesRead))`；`== -1` → `socket.close()`。

**分派**（`handleAirPodsPacket`，`:261-283`）：按顺序试 4 个 `isXxxData()`，全部**先判长度再判魔数前缀**：

```kotlin
EarDetection.isEarDetectionData               // size==8  , startsWith 04 00 04 00 06
ANC.isANCData                                 // size==11 , startsWith 04 00 04 00 09 00 0D
BatteryNotification.isBatteryData             // size==22 , data[0..5] == 04 00 04 00 04 00
ConversationalAwareness.isConversationalData   // size==10 , startsWith 04 00 04 00 4B 00 02 00
```

**其它能力**（都是往同一 socket 写）：`setANCMode(Int)` `:410-431`（先 `setOffListeningMode(mode==1)` 才能让 Pro2 出 OFF）、`setCAEnabled` `:433`、`setOffListeningMode` `:438`、`setAdaptiveStrength` `:443`、`setPressSpeed` `:449`、`setPressAndHoldDuration` `:455`、`setNoiseCancellationWithOnePod` `:461`、`setVolumeControl` `:467`、`setVolumeSwipeSpeed` `:473`、`setToneVolume` `:479`、`setCaseChargingSounds` `:485`、`setName` `:564`、`setPVEnabled` `:574`、`setLoudSoundReduction` `:585`、`sendPacket(String)` `:404`（hex 字符串调试口）。

**回写系统电池**（`:592-595`）—— 关键一行：

```kotlin
val service = XposedHelpers.getObjectField(mContext, "mAdapterService")   // context 是 A2dpService
XposedHelpers.callMethod(service, "setBatteryLevel", mDevice, level, false)
```

即 `com.android.bluetooth` 的 `AdapterService#setBatteryLevel(device, level, isSamsung)`，让系统状态栏电量图标跟着走。

**耳机检测联动**（`handleInEarStatusChanged`，`:149-197`）：双耳状态合成为 `inEar`（有一只在盒里则 OR，否则 AND），据此 `disconnectAudio()`/`connectAudio()`（`BluetoothProfile.HEADSET` + `MediaRouter2.transferTo(TYPE_BUILTIN_SPEAKER / TYPE_BLUETOOTH_A2DP)`，`:491-562`），并用 `MediaControl.sendPause/sendPlay` 暂停/恢复媒体。

### 3.2 `Packets.kt`（250 行）— **AAP 帧构造，全部字节在此**

`Enums`（`:7-28`）里所有 `ByteArray` 都是 **Kotlin 端拼好的完整帧**（含 4 字节前缀）。`Capabilities`（`:186-205`）是能力/模式常量。

## 4. AAP 帧字节布局（从本仓逆推）

### 4.1 通用头部

```
偏移  0  1  2  3  | 4  5 | 6 ...            | 末尾 3B
     04 00 04 00  | op 00| body             | 00 00 00 (SUFFIX，多数设置类帧)
     └─PREFIX────┘  └opcode┘
```

- `PREFIX = 04 00 04 00`（`Enums.PREFIX`，`:11`）
- `SETTINGS = 09 00`（`Enums.SETTINGS`，`:12`）
- `SUFFIX = 00 00 00`（`Enums.SUFFIX`，`:13`）
- **opcode 即 capabilities 标识**：`0x0D`=降噪、`0x28`=对话感知、`0x06`=入耳检测、`0x4D`=specific features、`0x4B`=对话感知通知、`0x04`=电池通知、`0x0F`=通知订阅过滤器

### 4.2 握手三连（**顺序不可换，每帧间隔 200ms**）

| 帧 | 长度 | 字节 |
|---|---|---|
| `HANDSHAKE` `:15` | 16 | `00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00`（注意首字节是 `00` 不是 `04`） |
| `SET_SPECIFIC_FEATURES` `:17-18` | 14 | `04 00 04 00 4D 00 FF 00 00 00 00 00 00 00` |
| `REQUEST_NOTIFICATIONS` `:19` | 10 | `04 00 04 00 0F 00 FF FF FF FF` |

### 4.3 通知帧（设备 → 手机）

**电池，22 字节**（`isBatteryData` `:131-137`；`setBattery` `:139-156`）

```
idx  0  1  2  3  4  5 │ 6  │ 7   8   9  10 │11│12  13  14  15 │16│17  18  19  20 │21
     04 00 04 00 04 00│ ?? │ Lc  ??  Lv Ls │??│ Rc  ??  Rv Rs │??│ Cc  ??  Cv Cs │??
     └─PREFIX─┘ └op──┘       └─ 组件组 #1 ──┘  └─ #2 ────────┘  └─ #3 ────────┘
```

- 每组件组 **5 字节，stride=5**，组基址 = 7 / 12 / 17：
  `[base]=组件 id, [base+2]=电量 %(0-100), [base+3]=状态`；`[base+1]` 与 `[base+4]` 代码从不读取（保留/未知）
- 组件 id（`BatteryComponent` `:30-34`）：**LEFT=4, RIGHT=2, CASE=8**
- 状态（`BatteryStatus` `:36-41`）：**1=CHARGING, 2=NOT_CHARGING, 3=NEED_AGAIN, 4=DISCONNECTED**
- 三元组顺序**不固定**：`getBattery()` `:158-162` 按 `first.component == LEFT` 决定是否交换 → 说明第一组可能是 L 也可能是 R
- **粘滞语义**（`setBattery` `:140-155`）：若某组状态是 4/3，则**保留上一帧**的 component+level，只更新 status（避免瞬断导致电量归 0）；case 额外要求上一帧本身是「已连接」才粘滞
- 解析范例（L=4,R=2,C=8；L 87% 未充电、R 65% 充电中、盒 40% 未充电）：
  `04 00 04 00 04 00 ?? 04 ?? 57 02 ?? 02 ?? 41 01 ?? 08 ?? 28 02 ??`（`??` 段代码不读，实测值待抓包确认）

**入耳检测，8 字节**（`:74-93`）

```
04 00 04 00 06  ??  L  R
                   └──┘ status[0],status[1]
```
值（`EarDetectionStatus` `:68-72`）：**IN_EAR=0x00, OUT_OF_EAR=0x01, IN_CASE=0x02**（`0x03` 在 `L2CAPController.kt:163` 被当作"等同在盒"兜底）

**降噪状态，11 字节**（`:95-124`）

```
04 00 04 00 09 00 0D  S  00 00 00        S = data[7]
```
`1=OFF, 2=ON, 3=TRANSPARENCY, 4=ADAPTIVE`（`:113-122`）——与下行设置帧**同构**，所以能直接回显。

**对话感知，10 字节**（`:165-183`）：前缀 `04 00 04 00 4B 00 02 00`，`status = data[9]`。

### 4.4 下行控制帧（手机 → 设备，11 字节模板）

```
04 00 04 00 09 00 <op> <val> 00 00 00
```

| op | 语义 | val | 源码 |
|---|---|---|---|
| `0x0D` | 降噪模式 | 1=OFF 2=ON 3=TRANSPARENCY 4=ADAPTIVE | `Enums` `:20-24` |
| `0x28` | 对话感知 | 1=ON 2=OFF（**注意与降噪相反**） | `:25-26` / `Capabilities.ConversationAwareness` `:201-204` |
| `0x34` | 关闭 OFF 模式（Pro2 才有 OFF） | 1=enable 2=disable | `:438-441` |
| `0x2E` | 自适应强度 | strength | `:443-447` |
| `0x17` | 按压速度 | speed | `:449-453` |
| `0x18` | 长按时长 | speed | `:455-459` |
| `0x1B` | 单耳开降噪 | 1/2 | `:461-465` |
| `0x25` | 音量控制 | 1/2 | `:467-471` |
| `0x23` | 音量滑动速度 | speed | `:473-477` |
| `0x1F` | 提示音音量 | volume，且 **[8]=0x50 固定** | `:479-483` |
| `0x26` | 个性化音量 | 1/2 | `:574-583`（跟随一个 30B TLV 包） |

**非 11 字节的异形帧**：
- 改名 `:564-572`：`04 00 04 00 1A 00 01 <len> 00` + UTF-8 名字字节
- 盒充电提示音 `:485-489`：`12 3A 00 01 00 08 <00|01>`（7B，无 `04 00 04 00` 前缀）
- 响度降低 `:585-590`：`52 1B 00 00` / `52 1B 00 01`（4B）
- 个性化音量 TLV `:579`：`04 00 04 00 17 00 00 00 10 00 12 00 08 E6 05 10 02 42 0B 08 50 10 02 1A 05 02 32 00 00 00`（30B，Protobuf-like）

### 4.5 长按动作位图（`LongPressPackets`，`:207-250`，33 项）

全部同构：`04 00 04 00 09 00 1A <MASK> 00 00 00`（11 B），只变第 7 字节 `<MASK>`（4 bit，取值域 0x03…0x0F）。按 `ENABLE/DISABLE_<模式>_FROM_<A>_AND_<B>` 的命名，`<A>_AND_<B>` 是触发长按时**当前所处的**两种模式，`<MASK>` 则是"允许轮转到的模式集合"。

**⚠️ 但枚举名自相矛盾，不能当规范用**：33 项只用了 11 个不同 mask（`0x03,05,06,07,09,0A,0B,0C,0D,0E,0F`），且大量重名——`0x0E` 对应 5 项（`:210 :225 :234 :243 :246`）；`0x07` 对应 4 项（`:215 ENABLE_OFF_FROM_TRANSPARENCY_AND_ANC` / `:224 ENABLE_TRANSPARENCY_FROM_OFF_AND_ANC` / `:233 ENABLE_ANC_FROM_OFF_AND_TRANSPARENCY` / `:237 DISABLE_ADAPTIVE_FROM_EVERYTHING`）；`0x0B` 4 项；`0x0D` 4 项。同一 mask 既被当"ENABLE_X"又被当"DISABLE_Y_FROM_EVERYTHING"，明显有复制粘贴/生成错误。

可确认的结构只有两条：
1. **2-模式项**（`FROM_A_AND_B`，24 项）只用 6 个「恰有两位为 1」的 mask：`0x03(bit0,1) / 0x05(bit0,2) / 0x06(bit1,2) / 0x09(bit0,3) / 0x0A(bit1,3) / 0x0C(bit2,3)` — 正好是 4 选 2 的全排列，说明 4 bit 就是 4 种降噪模式。
2. **`*_FROM_EVERYTHING` / `*_OFF_DISABLED` 项**用 3~4 位 mask：`0x0F`=ENABLE_EVERYTHING、`0x0E`/`0x0C`/`0x07` 等为"从全集中删掉某模式"。

移植建议：**不要按名字套公式，直接把这张表当查表数据**，或改用单模式指令（§4.4 的 `0x0D`）绕开该字段。

完整 mask 序列（按源码顺序，`LongPressPackets.kt` 行号）：

```
:208 0x0F  :210 0x0E  :211 0x0C  :212 0x06  :213 0x0A
:215 0x07  :216 0x0B  :217 0x0D
:219 0x0B  :220 0x03  :221 0x0A  :222 0x09
:224 0x07  :225 0x0E  :226 0x0D
:228 0x0D  :229 0x05  :230 0x0C  :231 0x09
:233 0x07  :234 0x0E  :235 0x0B
:237 0x07  :238 0x05  :239 0x06  :240 0x03
:242 0x0D  :243 0x0E  :244 0x0B
:246 0x0E  :247 0x0A  :248 0x06  :249 0x0C
```

## 5. `ui\` 6+2 个文件

| 文件 | 行 | 职责 |
|---|---|---|
| `App.kt` | 11 | `AppTheme { MainUI() }` |
| `MainUI.kt` | 375 | **状态中枢**。`HorizontalPager(pageCount=2)` + 两个独立 `MiuixScrollBehavior`（每页一个，`:76-90`）；`BroadcastReceiver`（`:119-153`）订阅 5 个 action → 更新 `ancMode / earDetectionParams / batteryParams / mainTitle / canShowDetailPage`；首帧 `registerReceiver(... EXPORTED)` 后发 `ACTION_PODS_UI_INIT` 拉全量状态（`:155-166`）；`setAncMode` 用 `restoreAncJob` 做 1s 乐观回滚（`:168-182`）；顶部 `BoxWithConstraints` 在 `>840.dp` 时换 `SmallTopAppBar`（`:211`）。**p0 is MainActivity → finish()**：断连即自杀（`:147-149`） |
| `PodDetailPage.kt` | 80 | `LazyColumn(height = getWindowSize().height.dp)` + `Card{ PodStatus }` + `AncSwitch` + `Card{ SuperSwitch(耳机检测) + AnimatedVisibility(SuperSwitch(切扬声器)) }` |
| `WaitingPodsPage.kt` | 20 | 居中一行 `R.string.waiting_for_pod` |
| `AboutPage.kt` | 123 | Logo + 作者 / GitHub / 爱发电跳转（`BasicComponent`）；`Icon`/`InputField`/`SearchBar` 是未清理的 import |
| `Theme.kt` | 23 | 见 §1.4 |
| `components\PodStatus.kt` | 281 | **纯手绘电池**：`BatteryIcon` 用 `Canvas` + `drawRoundRect`，宽度 = `size.width * level/100f`（`:53`），充电 `0xFF34C759` / >30% 灰 / 低电 `0xFFFF3B30`（`:54-58`）；`Pod/Case/CaseWithPods` 三种排布；`PodStatus` 用两层 `Crossfade` 在「耳机+盒」「盒中双耳」「仅耳机」间切换（`:234-274`）。**注意硬编码 `airpods_pro_*` 图，未走 `Constants.Icons`** |
| `components\AncSwitch.kt` | 164 | 自绘四段滑动开关：`switchWidth = 82.dp`，`animateDpAsState` + `spring(0.78f, StiffnessLow)` 位移 `0/1/2/3 × switchWidth`，白色滑块带 `shadow(10.dp)`；下面一行是 `off / NC / transparency / adaptive` 文案 |

**注入到系统 UI 的元素**：没有自绘 View 注入 SystemUI。做法是 ① 在控制中心插件里 hook `DeviceInfoWrapper.performClicked`，把「第三方耳机」卡片的点击**改写成拉起本 App 的 Activity**（`DeviceCardHook.kt:92-100`）；② 借用小米蓝牙进程发 MIUI 强提示与常驻通知（§6.2）。真正的系统 UI 元素只有 `StatusBarManager.setIconVisibility("wireless_headset", …)` 控制的那颗状态栏图标。

## 6. `utils\`

### 6.1 `utils\data\` 8 个文件（**包名是 `moe.chenxy.hyperpods.utils.miuiStrongToast.data`，目录却不是**）

| 文件 | 类型 | 字段 |
|---|---|---|
| `BatteryParams.kt` | `@Serializable @Parcelize` | `PodParams(battery:Int, isCharging:Boolean, isConnected:Boolean, rawStatus:Int)`；`BatteryParams(left/right/case: PodParams?)`；`EarDetectionParams(left/right: Byte = IN_CASE)` |
| `HyperPodsAction.kt` | `object` 常量 | 10 个 action，见下 |
| `HyperPodsPrefsKey.kt` | `object` 常量 | `ear_detection` / `ear_detection_switch_speaker` / `personlized_volume`(原文拼错) / `conversation_awareness` / `loud_sound_reduction` / `adjust_volume_by_swiper` / `case_charging_sound` / `adaptive_audio_level` / `long_press_mode_left|right` |
| `IconParams.kt` | `@Serializable` | `category / iconFormat / iconResName / iconType` ← 强提示左侧图元 |
| `TextParams.kt` | `@Serializable` | `text / textColor` |
| `Left.kt` / `Right.kt` | `@Serializable` | 各含 `iconParams + textParams`（左右对称的两个类，纯为 JSON 序列化） |
| `StringToastBean.kt` | `@Serializable` | `left: Left? / right: Right?` → 整体 `Json.encodeToString` 成 `param` 字符串 |

Action 表（`HyperPodsAction.kt:4-13`）：`ACTION_PODS_UI_INIT`、`_PODS_CONNECTED`、`_PODS_DISCONNECTED`、`_PODS_BATTERY_CHANGED`、`_ANC_SELECT`、`_PODS_ANC_CHANGED`、`_EAR_DETECTION_STATUS_CHANGED`、`_EAR_DETECTION_SWITCH_CHANGED`、`_GET_PODS_MAC`、`_PODS_MAC_RECEIVED`。
⚠️ **`ACTION_GET_PODS_MAC` 与 `ACTION_PODS_MAC_RECEIVED` 的字符串常量完全相同**（都是 `chen.action.hyperpods.get_pods_mac`，`:12-13`）——靠 `intent.setPackage()` 区分方向，属隐患，移植时应改掉。

### 6.2 `utils\miuiStrongToast\` — HyperOS 强提示

**`StringToastBundle.kt`（47 行，⚠️ 无 `package` 声明，靠 `import StringToastBundle` 引入）**
`Builder` 逐项 put 出 10 个键（`:34-43`）：

```
package_name, strong_toast_category, target(PendingIntent), param(JSON),
duration(默认 2500L), level(0f), rapid_rate(0f), charge, string_toast_charge_flag(0),
status_bar_strong_toast(默认 "show_custom_strong_toast")
```

**`MiuiStrongToastUtil.kt`（209 行）— 唯一的 HyperOS 类：`android.app.StatusBarManager`**

调用点（三处，`:52-55` / `:79-83` / `:125-128`）：

```kotlin
val service = context.getSystemService(Context.STATUS_BAR_SERVICE)   // "statusbar"
service.javaClass.getMethod("setStatus",
        Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
    .invoke(service, 1, "strong_toast_action", bundle)
```

- `setStatus(1, "strong_toast_action", bundle)` —— HyperOS `StatusBarManager` 的隐藏方法；`1` 是状态位，"strong_toast_action" 是强提示槽位名
- 可用性门禁：`isHyperOS` = `getprop ro.mi.os.version.code` 非空（`SystemApisUtils.kt:327-330`，走 `Runtime.exec("getprop …")`）；非 HyperOS 直接 return（`showStringToast` 例外，降级为 `Toast`）
- 五种 `StrongToastCategory`（`:201-207`）：`video_text` / `video_bitmap_intent` / `text_bitmap` / `text_bitmap_intent` / `video_text_text_video`
- `Category`（`:188-193`）`raw/drawable/file/mipmap`；`FileType`（`:195-199`）`mp4/png/svg`
- **桥接设计**（关键）：`MiuiStrongToastUtil` 里的 `show/update/cancel` 三个 `…ByMiuiBt` 方法本身**什么也不做，只发广播**（`:147-186`），目标包写死 `com.xiaomi.bluetooth`；真正的 `setStatus` 在 `MiBluetoothToastHook` 注入到小米蓝牙进程后才执行 —— 因为 statusbar 服务只认小米蓝牙这个 uid。**这是全仓最值得抄的一个模式：`StatusBarManager` 类服务无法跨 uid 调用时，把真实调用点搬到宿主进程里。**

## 7. 原生层 `cpp\`（3 文件，是为 L2CAP 让路）

`L2CAPController` 想开 **L2CAP CoC**，但 Android 蓝牙栈的 `l2c_fcr_chk_chan_modes()` 会以「Peer does not support our desired channel types」为由拒绝非标准 PSM。所以：

- `hyperpods.cpp:127-148` `on_library_loaded`：`name.ends_with("libbluetooth_jni.so")` 时读取 `/proc/self/maps` 拿基址（`:10-31`），用 `findStr` 定字符串（`:33-58`），`isMtk` 由是否存在 `vendor/mediatek/proprietary/packages/modules` 判定（`:130-133`）
- `findFunction`（`:69-125`）做 **ARM64 指令模式匹配**：找 `0x91` 高字节 = `ADD` 指令、`offset_hex == ((str-base)&0xfff)*4 + 1`、寄存器 == `0x8`，再验 `addr+0x10` 处第二条 ADD（寄存器 `0x63`）；命中后回退 `isMtk ? 0x84 : 0x44` 得到函数入口
- 替换函数（`:60-67`）**无条件 `return 1`** —— 即"永远认为对端支持所需信道模式"
- JNI 出口（`:156-160`）：`Java_moe_chenxy_hyperpods_hook_HeadsetStateDispatcher_nativeGetHookResult` → 返回 `isHookSuccess`，供 `HeadsetStateDispatcher.kt:65-73` 判断是否要弹「hook 失败，此版本不可用」
- `headset_nativeGetHookResult` 的 `external fun` 声明在 `HeadsetStateDispatcher.kt:32`，`System.loadLibrary("hyperpods_hook")` 在 `:36`（CMake `project("hyperpods_hook")`，`CMakeLists.txt:13,28`；C++23，`:14`）

## 8. 移植 checklist（做同类模块时的最小可用集）

1. **构建**：`compileOnly xposedApi` + `implementation yukihookApi` + `ksp yukihookKsp`，`assets/xposed_init` 填 **KSP 生成的 `Xxx_YukiHookXposedInit`**，`META-INF/yukihookapi_init` 填你的 `@InjectYukiHookWithXposed` 对象。
2. **Manifest**：`xposedmodule/xposeddescription/xposedminversion/xposedscope` 四条 meta-data，scope 用 string-array；零权限是正常的。
3. **入口**：`onHook() = YukiHookAPI.encase { loadApp(pkg, hooker) }`，一个包一个 `YukiBaseHooker`。
4. **拿 context**：hook 类本身 `implements ContextWrapper` 的服务，直接 `this.instance as ContextWrapper`；拿不到就用 `YukiHookAPI` 的 `appContext`。
5. **跨进程状态**：`prefs`（YukiHookPrefsBridge）+ 显式 `setPackage()` 的广播 + `Parcelable`（`@Parcelize`）在 extras 里传。
6. **L2CAP**：反射 `BluetoothSocket` 6 参构造，`type=3, psm=0x1001, uuid=74ec2172-…`；握手 `HANDSHAKE → SET_SPECIFIC_FEATURES → REQUEST_NOTIFICATIONS`，帧间隔 200ms；读循环 1024B。
7. **分帧**：**先判 `size`，再判魔数前缀**——AAP 没有长度字段。
8. **电池取值**：`data[7]/[9]/[10]`、`[12]/[14]/[15]`、`[17]/[19]/[20]`，`4/3` 状态要保留旧值。
9. **回写系统**：`XposedHelpers.callMethod(adapterService, "setBatteryLevel", device, level, false)`。
10. **强提示**：把 `StatusBarManager#setStatus(1, "strong_toast_action", bundle)` 放进目标 uid 的宿主进程里跑。

## 9. 已知缺陷 / 别照抄的地方

- `HyperPodsAction.kt:12-13` 发/收两个 action 字符串相同。
- `utils\data\` 目录与包名不符；`StringToastBundle.kt` 在默认包。
- `PodStatus.kt:103,109,150,177` 硬编码 `airpods_pro_*`，`Constants.Icons` / `defaultIcons`（17 张图）基本没被用上。
- `MediaControl.kt` 与部分 `utils` 代码带 `Matthias Urhahn / someone5678` 的 GPL 头，说明是从上游 fork —— 整仓 GPL-3.0，二次分发需同协议。
- `DeviceCardHook.kt:87-90` 在 `before` 里 `Thread.sleep` 自旋等广播，最坏 500ms 阻塞 SystemUI 主线程。
- `getCaseMp4Uri` 每次调用都 `delete()` + 重写 base64（`MiBluetoothToastHook.kt:190-196` 在构造 hook 里先删），属权宜实现。
