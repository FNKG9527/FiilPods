# HyperOS「蓝牙设备详情页」注入规格（从 OppoPods 逆推）

> 目标：让一个没读过原仓库的人，能凭本文把等效的「设置页耳机卡片」移植到别的品牌（本文以 FIIL 为例）。
> 参考实现（只读，勿改）：
> - `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt`（773 行）
> - `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/HookContext.kt`（156 行）
> - `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/HeadsetStateDispatcher.kt`（98 行）
>
> **先读这一句，否则后面全白读**：原实现**没有**往设置页塞任何 View，**没有**自定义 View / ComposeView，**没有**替换 PreferenceController，也**没有** hook RecyclerView.Adapter。它做的是**「伪造 Intent 参数 + 反射调用宿主私有方法 + 吞掉/改写宿主方法返回值」**——把设置页骗成「这是一台已被系统支持的耳机」，再用宿主的原生 UI 渲染我们的数据。第 2、4 节会把这条结论钉死，并给出「若真要塞卡片」的等价做法。

---

## 1. 宿主包与目标类

### 1.1 作用域（scope.list）

`app/src/main/resources/META-INF/xposed/scope.list:1-3`：

| 包名 | 用途 |
|---|---|
| `com.android.bluetooth` | 真正持有 socket 与状态（第 7 节）；广播的目标包 |
| `com.android.settings` | 设备详情页宿主（本节） |
| `com.xiaomi.bluetooth` | 通知/超级岛（本文不展开） |

注意 `moe.chenxy.fiilpods` 已经把这 3 个包写进 `app/src/main/resources/META-INF/xposed/scope.list` 与 `app/src/main/res/values/strings.xml:42-46` 的 `xposedscope` 数组，**但 `app/src/main/java/moe/chenxy/fiilpods/` 目前只有 `FiilPodsApp.kt` / `FiilGaia.kt` / `FiilCommands.kt`，还没有任何 hook 类**——第 9 节清单里第一条就是补这个。

### 1.2 settings 侧被 hook 的类（全部 `com.android.settings.bluetooth.*`）

| 类 | 角色 | 是否必须 |
|---|---|---|
| `MiuiHeadsetActivity` | 详情页 Activity 本体；`getDeviceID()`/`getSupport()` 两个 0 参字符串 getter 决定「这台设备支不支持」 | 必须 |
| `MiuiHeadsetActivityPlugin` | 分屏/插件形态的同一页面（HyperOS 折叠屏、平板分栏） | 建议 |
| `MiuiHeadsetFragment` | 真正的 UI 与状态机；`updateAtUiInfo(String)`/`updateAncUi(String,boolean)`/`refreshStatus(String,String)` 是注入入口 | 必须 |
| `tws.MiuiHeadsetBattery` | 电量控件（4 参构造，`args[0]=BluetoothDevice`、`args[1]=Context`） | 必须 |
| `HeadsetIDConstants` | 静态判定表：`checkSupport(String)`、`isTWS01Headset(String)`、`isK77sHeadset(String)`、`isBleMmaConnect(...)` | 必须 |
| `IMiuiHeadsetService$Stub$Proxy` | **跨进程**的 MMA 服务代理；页面所有真实请求都从这里出去 | 必须 |
| `com.android.bluetooth.ble.app.IMiuiHeadsetService` | 上面 Proxy 的接口类（只在 `isBleMmaConnect` 重载里当成参数类型用） | 必须 |

`com.android.settings` 在 `HookEntry.kt:26` 里是**注释掉的**：

```kotlin
//"com.android.settings" -> loadHook(SettingsHeadsetHook, param.defaultClassLoader, param.packageName)
```

也就是说原仓库**没有在设置进程里启用这条链**（只有 `com.android.bluetooth` / `com.milink.service` / `com.xiaomi.bluetooth` 三条是活的）。移植时必须把这一行取消注释，否则整套逻辑永远不执行。这是第一个坑。

### 1.3 类是怎么被发现的

`HookContext` 提供 5 个查找原语，全部走**按名字 + 按签名**，不做 dex 扫描、不做字符串常量搜索：

| 原语 | 实现 | 何时用 |
|---|---|---|
| `findClass(name)` | `Class.forName(name, false, appClassLoader)` | 类名稳定 |
| `findMethod(cls, name, vararg types)` | `getDeclaredMethod` + `isAccessible = true` | 签名稳定（绝大多数） |
| `findConstructor(cls, vararg types)` | `getDeclaredConstructor` | 同上 |
| `findMethodByParamCount(cls, name, n)` | `declaredMethods.first { name 匹配 && 参数个数 == n }` | **混淆/版本漂移兜底** |
| `findConstructorByParamCount(cls, n)` | `declaredConstructors.first { 参数个数 == n }` | 同上 |

原实现里用「按参数个数」而不是按类型的只有 4 处，都是因为签名跨版本不稳：`MiuiHeadsetFragment.onCreateView(3)`、`MiuiHeadsetFragment.onServiceConnected(0)`、`MiuiHeadsetBattery` 构造（4 参）、`MiuiHeadsetActivity.getDeviceID/getSupport`（0 参）。

**没有任何一处用 `getIdentifier` / 资源 id 反查来发现设置页的类**。资源 id 反查只在 MiLink 里出现过（`MiLinkServiceHook.kt:497-530` 的 `viewResourceName` / `findChildByName`），与 settings 页面无关。

**混淆名的硬编码兜底**：`BluetoothUpstreamHeadsetHook.kt:86` 里 `C4705R2`、`:149-150` 里 `BinderC6776v` 与 `v`——这些是 R8 混淆后的名字，属于「按已知混淆名列表逐个试 `findClassOrNull`」。settings 侧没有这种兜底，**移植到其它 ROM 版本时要自己补一份候选名列表**。

---

## 2. 注入技术：被 hook 的方法签名全表

### 2.1 结论先行

| 候选技术 | 原实现是否使用 |
|---|---|
| 往现有 ViewGroup `addView()` 一个自定义 View | ❌ 完全没有（全仓 `addView` 零命中于 hook 路径） |
| 替换 `PreferenceController` | ❌ 无 |
| hook `RecyclerView.Adapter` / `onBindViewHolder` | ❌ 无 |
| 用 `miuix` 的 Preference 体系插条目 | ❌ 无 |
| **伪造 Intent extras 让宿主自己升级 UI** | ✅ 主手段 |
| **反射调用宿主私有方法回填数据** | ✅ 主手段 |
| **hook 方法返回值 / 吞掉回调** | ✅ 主手段 |
| **hook Toast/通知的入参（上游）** | ✅ 在 `com.android.bluetooth` 侧 |

所以「详情页卡片」在视觉上是**宿主自己的原生 UI**，我们只负责喂数据。这和「注入一个 View」是两种完全不同的工程量：前者零渲染代码，代价是**必须让 `checkSupport` 类判定返回 true**，否则宿主根本不画那个卡片。

### 2.2 完整签名表（owner / 方法名 / 参数 / 时机 / 动作）

记法：`before` = `hookBefore`（可吞掉原调用并直接给 result），`after` = `hookAfter`（可改 result），`ctor` = `hookConstructorAfter`。

**A. Activity 入口（`SettingsHeadsetHook.kt:59-102`）**

| owner | 方法 | 参数 | 时机 | 动作 |
|---|---|---|---|---|
| `MiuiHeadsetActivity` | `onCreate` | `Bundle` | before | `registerStatusReceiver(activity)`；取 `intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE")`；若是我们的设备 → `putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())`、`putExtra("COME_FROM", 原值 ?: "MIUI_BLUETOOTH_SETTINGS")`、`putExtra("DEVICE_ID", fakeDeviceId())` |
| `MiuiHeadsetActivityPlugin` | `onCreate` | `Bundle` | before | 同上，但**不写 `COME_FROM`** |
| `MiuiHeadsetActivity` | `getDeviceID` | `()` | after | 读实例字段 `mDevice`；命中则 `result = fakeDeviceId()` |
| `MiuiHeadsetActivity` | `getSupport` | `()` | after | 同上，`result = fakeSupport()` |

**B. 静态支持表（`:104-154`）**

| owner | 方法 | 参数 | 时机 | 动作 |
|---|---|---|---|---|
| `HeadsetIDConstants` | `checkSupport` | `String` | after | 值以 `fakeDeviceId()` 开头或包含它 → `result = true` |
| `HeadsetIDConstants` | `isTWS01Headset` | `String` | after | 值 `== fakeDeviceId()` → `true` |
| `HeadsetIDConstants` | `isK77sHeadset` | `String` | after | **恒 `false`**（我们的设备不该被当成 K77s 走另一套 UI） |
| `HeadsetIDConstants` | `isBleMmaConnect` | `(Context, BluetoothDevice, String)` | after | 设备是目标 or deviceId 匹配 → `true` |
| `HeadsetIDConstants` | `isBleMmaConnect` | `(IMiuiHeadsetService, BluetoothDevice, String)` | after | 同上（接口类型用 `findClass` 拿到） |

**C. 跨进程 Proxy（`:156-265`）** —— 页面所有「查电量/查 ANC/下发命令」都打到这里

| 方法 | 参数 | 时机 | 伪造结果 |
|---|---|---|---|
| `checkSupport` | `(BluetoothDevice)` | before | `fakeSupport()` |
| `getDeviceInfo` | `()` | before | `fakeSupport()`（**见下方缺陷**） |
| `isSupportAudioSwitch` | `()` | before | `"1"`（**同上缺陷**） |
| `setCommonCommand` | `(Int, String, BluetoothDevice)` | before | `command == 102 ? "0" : "1"` |
| `connect` | `(BluetoothDevice)` | before | `result = null`（吞掉） |
| `getDeviceConfig` | `(BluetoothDevice)` | before | `result = null`（吞掉） |
| `getCommonConfig` | `(BluetoothDevice, String)` | before | `result = null`（吞掉） |
| `isMiTWS` | `(String)` | before | `true` |
| `checkIsMiTWS` | `(String)` | before | `true` |
| `getRingFindState` | `(String)` | before | `false` |
| `changeAncMode` | `(Int, BluetoothDevice)` | before | 翻译成内部模式后广播，`result = null` |
| `changeAncLevel` | `(String, BluetoothDevice)` | before | 同上（`02xx` 走通透分支，返回 `null` 不改 `currentAnc`） |

> **缺陷（移植时要修）**：`getDeviceInfo` 与 `isSupportAudioSwitch` 是用 `hookProxyStringArgResult(..., "getDeviceInfo")` **不带参数类型**调用的，即按 0 参 `getDeclaredMethod` 去查；而该 helper 内部是用 `args.firstOrNull { it is BluetoothDevice }` 判品牌的。即使查到，0 参调用下 `device == null && address == null` → `isOppo == false` → 直接 `return@hookBefore`，**这两条 hook 实际是死代码**。正确写法：给它们显式参数类型（`BluetoothDevice` 或 `String`），并按参数取设备/地址。

**D. 电量控件（`:267-291`）**

| owner | 成员 | 参数 | 时机 | 动作 |
|---|---|---|---|---|
| `tws.MiuiHeadsetBattery` | `<init>` | 4 参（`[0]=BluetoothDevice`, `[1]=Context`） | ctor | 注册广播；命中则登记进 `batteryViews`、拉一次状态、立刻 `updateBatteryView` |
| `tws.MiuiHeadsetBattery` | `onBatteryChanged` | `(String)` | before | 命中则 **`result = null` 吞掉原生回调**，改调 `onBatteryChanged(int,int,int)` |

`onBatteryChanged(int,int,int)` 是**我们自己反射调的**（`:450-454`），不是被 hook 的方法：`callMethod(view, "onBatteryChanged", left, right, case)`。

**E. Fragment 状态机（`:293-367`）**

| 方法 | 参数 | 时机 | 动作 |
|---|---|---|---|
| `onCreateView` | 3 参（`LayoutInflater, ViewGroup, Bundle`） | after | 登记 fragment、拉状态、**启动 3s 轮询**、`injectFragmentStatus` |
| `onServiceConnected` | `()` | after | 同上（不重复启动轮询） |
| `refreshStatus` | `(String, String)` | before | 仅当 `args[0].startsWith("MMA_CONNECTION_FAILED")` 时：先注入状态，再 `result = null` **吞掉失败** |
| `handleConnectMmaFailed` | `(String)` | before | 命中则注入状态 + `result = null` |
| `updateAncMode` | `(int, boolean)` | before | `args[1]==false` 时放行；否则翻译模式→广播→反射 `updateAncUi`→注入→`result = null` |
| `updateAncLevel` | `(String, boolean)` | before | 同上 |

`updateAncMode`/`updateAncLevel` 的第二个参数语义是 **updateDevice**：`false` 表示这是「回显 UI，不真的下发」，必须放行，否则会出现回声。

### 2.3 反射回填的三个私有入口

```
updateAtUiInfo(String)            // "ancMode|0100;0101;0102;0103;0200;0201|L,R,Case|00"
updateAncUi(String level, boolean updateDevice)
refreshStatus(String address, String csv16)
```

- `updateAtUiInfo` 的 payload 是**竖线分三段**：`settingsAncMode()`（`"0"/"1"/"2"`）、能力码列表（硬编码 6 个 `01xx/02xx`）、电量串、尾部 `"00"`（`SettingsHeadsetHook.kt:466`）。
- `refreshStatus` 的 payload 是**16 个逗号分隔字段**（`:580-595`）：`[0]=左 [1]=右 [2]=盒 [7]=ANC level [8]="false" [11]="00" [13]="00" [14]="00"`，其余留空。注意 `RfcommController.miuiRefreshPayload` 里 `[8]` 是 `"true"`，两处**不一致**，移植时统一。

---

## 3. 怎么认定「这是我们的设备」

三层，优先级从高到低：

1. **地址白名单**：`knownOppoAddresses: LinkedHashSet<String>`（大写）+ `currentAddress`。`:534-537` 的 `isOppoAddress` 就是 `normalized == currentAddress || normalized in knownOppoAddresses`。白名单由所有收到过的广播回填（`ACTION_PODS_CONNECTED` / `BATTERY` / `ANC` / `TRANSPARENCY` 四个分支都会 `knownOppoAddresses.add(address.uppercase())`）。
2. **名字包含**：`:489-501` 的 `isOppoPod`，先查地址，再 `device.name ?: device.alias` 里 `contains("oppo", ignoreCase = true)`；命中就顺手把地址塞进白名单。**这是最脆的一环**——换品牌必须改成 FIIL 的型号前缀。
3. **伪造设备 id**：`fakeDeviceId()`（默认 `"01010607"`，`ConfigManager.kt:36`，可在 UI 改），`fakeSupport() = "$fakeDeviceId,000000000000000010000000"`（`:102`，24 个十六进制字符的能力位图）。

Fragment 侧还有第四层 `isOppoFragment`（`:481-487`）：只要 `mDevice` 命中、或字段 `mDeviceId == fakeDeviceId`、或 `mSupport?.startsWith(fakeDeviceId)`，就算自己人。**这层是必要的**——Fragment 复用时 `mDevice` 可能为 null，只能靠 id 认。

**不靠 metadata**：全仓 `setMetadata` / `METADATA_*` 只出现在 `utils/SystemApisUtils.kt:18-46` 的定义处，**没有任何调用点**。模块从不往 `BluetoothDevice` 写 metadata，全部靠 hook 返回值伪造。

---

## 4. 自定义 View 类的真相

**不存在。** 三条硬证据：

1. `SettingsHeadsetHook.kt` 全文没有 `View` / `ViewGroup` / `LayoutInflater` / `ComposeView` / `setContent` 任何一处。
2. 对 `refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/` 全目录 grep `addView|ViewGroup|LayoutInflater|RecyclerView|ComposeView|setContent`，9 个命中**全部在模块自己的 App 内**：`MainActivity.kt:28`、`PopupActivity.kt:76` 的 `setContent {}`，以及 `FocusIslandUtil`/`MiBluetoothToastHook` 里的 `NotificationCompat.setContentTitle`。
3. 设置页里唯一被碰到的 View 是宿主的 `MiuiHeadsetBattery` 实例，而且只调它的方法（`onBatteryChanged`），不碰它的布局。

Compose 在模块里只用于**自己进程**的 Activity（`MainActivity`、`PopupActivity`），依赖来自 `app/build.gradle.kts` 的 compose-bom + miuix。它跑在 `moe.chenxy.oppopods` 进程，与注入到 `com.android.settings` 的代码**不是同一个 ClassLoader**，不能直接复用。

### 若确实要「塞一张自己的卡片」

三条可行路线，按风险从低到高：

| 路线 | 做法 | 代价 |
|---|---|---|
| A. 原生 UI 复用（原实现） | 只 hook 判定 + 回填数据 | 观感受宿主控制；换 ROM 就崩 |
| B. 反射取 `onCreateView` 的返回值（`result as ViewGroup`），`post {}` 里 `addView(自建 View)` | 用宿主 `LayoutInflater(context).inflate(模块资源)` 或 `ComposeView(context).apply { setContent{} }` | 需要模块资源能在宿主进程解析（第 8 节）；Compose 必须把 `androidx.compose.*` 一起塞进模块 APK 并在宿主 ClassLoader 里可见——**libxposed 下不可行**，见下 |
| C. 整页接管：`PopupActivity` 跳自己的界面 | `PopupActivity.kt:130-139` 已有现成写法 | 不是「注入」，是另开页面 |

**路线 B 里 Compose 的现实约束**：Compose 编译器生成的类在模块 APK 内，`com.android.settings` 的 ClassLoader 看不见模块的 dex。libxposed 不提供「把模块 dex 注入宿主 ClassLoader」的能力，所以 `ComposeView(context).setContent { ... }` 在宿主进程里**第一个 `Composable` 调用就会 `ClassNotFoundException`**。可行替代：
- 用纯 `android.view.View` + `Canvas`（无外部依赖，最稳）；
- 或用宿主已有的 Compose 运行时（HyperOS 设置本身就是 Compose 应用），反射调 `ComposeView.setContent` 并塞一个**已存在于宿主里的** `@Composable`——实际上拿不到，等于不可行。

**结论**：真要卡片，写 `android.view.View` 子类 + `onDraw` + `invalidate()`，或干脆走路线 C。

---

## 5. 生命周期与防泄漏

### 5.1 三条容器的键类型决定了泄漏行为

```kotlin
private val batteryViews = WeakHashMap<Any, BluetoothDevice>()   // :24
private val headsetFragments = WeakHashMap<Any, Boolean>()       // :25
```

- 键是**宿主 View / Fragment 实例**，弱引用。列表页销毁后对象被 GC，条目自动消失 → 不需要手动 `remove`，也不需要 `unregister`。
- 值是 `BluetoothDevice`（强引用），但它本身是系统对象，无害。
- `context`（`:26`）存的是 `context.applicationContext ?: ctx`（`:371`），**必须**取 application，否则持有 Activity → 必然泄漏。

### 5.2 3 秒轮询的启停

`:37-49` + `:435-441`：

```kotlin
refreshHandler = Handler(Looper.getMainLooper())
refreshRunnable = object : Runnable {
    override fun run() {
        if (headsetFragments.keys.any { isOppoFragment(it) }) {
            requestBluetoothStatus("settings-periodic")
            refreshHandler.postDelayed(this, 3_000L)     // 自续期
        } else refreshLoopStarted = false                // 自然停
    }
}
```

- 启动条件：`onCreateView` / `onServiceConnected` 命中且 `isOppoFragment`。
- 停止条件：**下一次 tick 时发现没有活着的 fragment**。所以最坏情况多跑 3 秒，可接受。
- `refreshLoopStarted` 是布尔闸，防止重复 `postDelayed`。
- 走主线程 Handler，`post` 的目标是 `com.android.settings` 的主 Looper。

### 5.3 广播接收器

`registerStatusReceiver(ctx)`（`:369-422`）用一个 `receiverRegistered` 布尔做全局单次注册，`Context.RECEIVER_EXPORTED`（因为发送方在蓝牙进程，跨进程）。注册的 6 个 action：`PODS_CONNECTED` / `PODS_DISCONNECTED` / `PODS_BATTERY_CHANGED` / `PODS_ANC_CHANGED` / `TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED` / `CONFIG_CHANGED`。

广播 → 更新 5 个字段（`currentAddress` / `currentName` / `currentBattery` / `currentAnc` / `currentTransparencyVocalEnhancement`）→ 持久化 → `updateBatteryViews()` + `updateFragments()`。

**泄漏点**：`receiverRegistered` 是进程级单例的布尔，模块在设置进程里只加载一次，所以不会重复注册；但如果热重载（第 6 节）重建了 `object`，旧的 receiver 不会被注销 → **每次热重载泄漏一个 receiver**。移植时必须把 `IntentFilter`/receiver 存进可通过 `HookHandle.unhook()` 回收的容器。

### 5.4 状态持久化（跨进程兜底）

`PREFS_NAME = "oppopods_milink_state"`（`:21`），`saveState`/`loadState`（`:716-766`）存 `address` / `name` / `anc` / `transparency_vocal_enhancement` / 三路 `*_battery` / `*_charging` / `*_connected`。

读的时候有个短路（`:745`）：`if (!hasSavedBattery && hasCurrentBattery()) return` —— 内存里已有电池就**不覆盖**，避免 prefs 的旧值盖掉新广播来的值。

**为什么需要它**：设置页可能是冷启动直接进来的（从快捷开关长按跳转），此时还没收到任何广播，`currentBattery` 是空的 → 卡片会显示 `255`（未知）。先读 prefs 能立刻画出上次的数值。

---

## 6. `HookContext` 契约与 libxposed 102 用法

### 6.1 基类实际形状（`HookContext.kt:10-61`）

```kotlin
abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader     // 没有 "classLoader" 这个字段
    lateinit var prefs: SharedPreferences
    lateinit var packageName: String
    abstract fun onHook()
}
```

对照题目列的「应有」成员：

| 期望 | 实际 | 说明 |
|---|---|---|
| `module` | ✅ | 由 `HookEntry.loadHook` 注入 |
| `classLoader` | ❌ | 只有 `appClassLoader`；命名不同，语义一致（宿主包的 `defaultClassLoader`） |
| `appClassLoader` | ✅ | `param.defaultClassLoader` |
| `packageName` | ✅ | `param.packageName` |
| `prefs` | ✅ | `getRemotePreferences("oppopods_settings")`（libxposed 的**远程** prefs，主进程写、宿主进程读） |
| `onHook()` | ✅ | 抽象，唯一实现点 |
| `close()` | ❌ | **不存在**。生命周期只有「进程活多久它就活多久」 |
| `saveHotReloadState()` | ❌ | **不存在**。热重载靠 API 102 的 `onHotReloading/onHotReloaded` 回调 + `HookHandle.replaceHook`，不用自定义状态保存 |

### 6.2 装配点（`HookEntry.kt:35-51`）

```kotlin
private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
    Log.module = this; hook.module = this
    hook.appClassLoader = classLoader; hook.packageName = packageName
    hook.prefs = getRemotePreferences("oppopods_settings")
    ConfigManager.init(hook.prefs)
    hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
    hook.onHook()
}
```

调用方是 `onPackageLoaded(param)`，且**只处理 `param.isFirstPackage`**（`:18`）——否则多用户/多 profile 会重复 hook。

### 6.3 三个 hook 原语 → `hook(...).intercept {}`

`HookBuilder` 在 API 102 里只有 4 个方法：`intercept(Hooker)`、`setPriority(int)`、`setExceptionMode(ExceptionMode)`、`setId(String)`。原实现只用了 `intercept`。

```kotlin
fun hookAfter(method: Method, block: HookParam.() -> Unit) {
    module.hook(method).intercept { chain ->
        val result = chain.proceed()                    // 先跑原逻辑
        HookParam(chain, result).apply(block).result     // 再让 block 改 result
    }
}

fun hookBefore(method: Method, block: HookParam.() -> Unit) {
    module.hook(method).intercept { chain ->
        val param = HookParam(chain, null).apply(block)
        if (param.hasResult) param.result else chain.proceed()   // 有 result 就吞掉原调用
    }
}

fun hookConstructorAfter(ctor: Constructor<*>, block: HookParam.() -> Unit) {
    module.hook(ctor).intercept { chain ->
        chain.proceed().also { HookParam(chain, it).apply(block) }
    }
}
```

`HookParam`（`:108-118`）是 3 个字段的壳：`args`（`chain.args`）、`instance`（`chain.thisObject`）、`result`（setter 里把 `hasResult` 置 true）。**`hasResult` 只在写 result 时变 true**，所以 `hookBefore` 里「不写 result」= 放行，这是全套代码的心跳。

### 6.4 hookId（`setId`）—— 原实现没用，但你该用

API 102 的 `setId(@Nullable String)` 语义（javadoc 原文）：

> An id is used for exclusively identifying a hook in the same module on the executable. A new hook with the same id in the same module on the executable will replace the old one atomically, and the old hook handle will be invalid. Hook ids are isolated between modules. The hook chain is snapshot based.

配套的 `HookHandle`：`getId()`、`unhook()`（幂等）、`replaceHook(Hooker)`（原子替换，保留 executable/priority/exceptionMode/id；热重载时从 `HotReloadedParam.getOldHookHandles()` 拿旧句柄再换）。

**原实现的问题**：`hookAfter`/`hookBefore` **没有返回值**，`HookHandle` 被丢弃。后果：
- 不能 `unhook()` → 热重载后旧 hook 残留，与新的叠加（`hookAfter` 会重复执行，`hookBefore` 会重复广播 AN C 命令）；
- 不能按 id 幂等重装。

**移植时的正确写法**（每个 hook 一条）：

```kotlin
module.hook(method).setId("settings/onBatteryChanged").intercept { chain -> ... }
```

id 建议用 `"<域>/<用途>"`，例如 `settings/activity-oncreate`、`settings/battery-onchanged`、`bt/a2dp-state`。这样 `onHotReloaded` 里能按 id 做差异清理。

### 6.5 日志

`Log`（`:63-106`）是 `object`，`var module: XposedModule?`，全部转发到 `module.log(priority, tag, msg[, throwable])`，并按 `ConfigManager.logLevel()`（`LOG_LEVEL_OFF=0/BASIC=1/DEBUG=2`）早退。**没有用 `android.util.Log`**，因为宿主进程的日志 tag 会被过滤；走 module 通道才能在 LSPosed 日志里看到。

---

## 7. `HeadsetStateDispatcher`：在蓝牙进程里做什么

`object HeadsetStateDispatcher : HookContext()`（`HeadsetStateDispatcher.kt:18-98`），被 `HookEntry` 在 `"com.android.bluetooth"` 分支加载（`HookEntry.kt:23`）。

### 7.1 两个 hook

| owner | 方法 | 参数 | 时机 | 动作 |
|---|---|---|---|---|
| `com.android.bluetooth.btservice.AdapterService` | `onCreate` | `()` | after | 拿 `instance as? Context` → `registerAppRequestReceiver`（幂等，`appRequestReceiverRegistered` 闸） |
| `com.android.bluetooth.a2dp.A2dpService` | `handleConnectionStateChanged` | `(BluetoothDevice, int fromState, int currState)` | after | 见下 |

`A2dpService` 那段是**整个模块的发动机**（`:30-53`）：

```kotlin
val device = args[0] as BluetoothDevice?
if (device == null || currState == fromState) return@hookAfter
val handler = getObjectField(instance, "mHandler") as Handler
handler.post {
    registerAppRequestReceiver(instance as ContextWrapper)
    if (!isOppoPod(device)) return@post
    statusBarManager.setIconVisibility("wireless_headset", currState == STATE_CONNECTED)
    if (currState == STATE_CONNECTED) RfcommController.connectPod(context, device, prefs)
    else if (currState in [STATE_DISCONNECTING, STATE_DISCONNECTED]) RfcommController.disconnectedPod(context, device)
}
```

要点：
- **必须 `handler.post`**，因为 `handleConnectionStateChanged` 跑在 Binder 线程，建 socket 不能在那。
- `mHandler` 是反射取的私有字段（`getObjectField` 沿父类链找）。
- `currState == fromState` 直接丢弃，避免重复触发。
- 图标 `"wireless_headset"`（`StatusBarManager.setIconVisibility`）是 MIUI 的私有图标名。

### 7.2 发布什么状态

`RfcommController` 解析出耳机回包后，**双发**两套广播（`RfcommController.kt:338-347` 与 `:556-570`）：

| 目标 | 方法 | 目标包 |
|---|---|---|
| 模块自己的 App | `sendAppStatusBroadcast` | `BuildConfig.APPLICATION_ID`，且**只在 `appUiActive` 窗口内发**（`isAppUiActive()` + 3 分钟超时） |
| 系统侧 | `sendExternalPodsStatusBroadcast` | `com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings` 逐个 `setPackage` |

电量广播 `ACTION_PODS_BATTERY_CHANGED` 的 extras（`:572-582`）：`left_battery` / `left_charging` / `left_connected`，右耳、充电盒同样三件套，外加 `address`、`device_name`。

`HeadsetStateDispatcher` 自己**不发布**状态，它只负责「A2DP 连上 → 建 RFCOMM」和「反向接收 App 的指令」：`ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` → 回 `ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE`；`ACTION_CONNECT_POD_REQUEST` / `ACTION_DISCONNECT_POD_REQUEST` → 带 `Parcelable` 设备直接连/断。

### 7.3 socket 电池与系统电池怎么「合流」

**结论：不合流，是「整段替换」。** 链路：

1. 蓝牙进程解析 GAIA 回包 → `handleBatteryChanged(BatteryParser.BatteryResult)`（`RfcommController.kt:397-429`）→ 组装 `BatteryParams(left/right/case)`，每路是 `PodParams(battery, isCharging, isConnected)`。充电盒有一次「记忆」：本次没回报就用 `lastKnownCaseBattery/lastKnownCaseCharging` 但 `isConnected=false`。
2. 广播出去（上表）。
3. 设置进程的 `SettingsHeadsetHook` 收到 → 存进 `currentBattery`。
4. 宿主 `MiuiHeadsetBattery.onBatteryChanged(String)`（系统自己的电池字符串）被 **`result = null` 吞掉**，改由我们反射调 `onBatteryChanged(int,int,int)`。

数值编码（`SettingsHeadsetHook.kt:552-556`，与 `RfcommController.miuiBatteryValue` 完全一致）：

```kotlin
fun batteryValue(p: PodParams?): Int {
    if (p?.isConnected != true) return 255          // 255 = 未知/未连接
    val v = p.battery.coerceIn(0, 100)
    return if (p.isCharging) v or 128 else v         // bit7 = 充电中
}
```

所以「合流」的真相是：**系统那条路被掐断，我们的值独占**。唯一的兜底是 `loadState()` 从 prefs 恢复（第 5.4 节），不是「两个来源取其一」。

**如果你确实需要合流**（比如 FIIL 只回报左右耳、盒子电量缺失）：在 `batteryValue` 里对「我们返回 255 的那一路」回落去读系统值——系统值就在被吞掉的 `onBatteryChanged(String)` 的 `args[0]` 里（形如 `"80,80,255"`），解析后按路合并即可。原实现把这个字符串**打印了但没解析**（`:285`），这是现成的钩子。

---

## 8. 资源加载

### 8.1 原实现的做法：没有 `addAssetPath`

全仓 grep `addAssetPath` / `ModuleResourceResolver` / `ResourcesLoader` / `createResources` / `invokeAssetManager` → **零命中**。

在宿主进程里取模块资源的唯一手法是 **`createPackageContext`**：

```kotlin
val moduleContext = context.createPackageContext(
    "moe.chenxy.oppopods", Context.CONTEXT_IGNORE_SECURITY)
BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
```

三处实例：`utils/PodImageLoader.kt:31-34` 与 `:55-58`、`hook/milink/MiLinkServiceHook.kt:534-538`（`getDrawable(R.drawable.ic_game_mode)`）、`hook/MiBluetoothToastHook.kt:114-118`。

**为什么能行**：模块 APK 已安装，`PackageManager` 能解析它的资源表；`R.drawable.xxx` 在模块编译期是常量 id，只要宿主进程里那个 `Resources` 对象是模块的，id 就对得上。**不需要** `addAssetPath`，也不需要把 `Resources` 塞进宿主的 `AssetManager`。

**代价**：`createPackageContext` 每次要跨进程问 PMS，**必须缓存**。`MiLinkServiceHook` 用 `gameModeIcon: Drawable?` 字段缓存；`PodImageLoader` 没缓存（每次解码都新建 context），属于可优化点。

### 8.2 字符串

设置页里我们**不读模块字符串**——所有文案都是宿主的（`miheadset_notification_*` 这类是 `com.xiaomi.bluetooth` 的资源，在 `MiBluetoothToastHook.kt:56-60` 用 `context.resources.getIdentifier(name, "string", "com.xiaomi.bluetooth")` 拿）。

模块自己的字符串（`app/src/main/res/values/strings.xml`，42 条中文）**只在模块自己的 Activity 里用**，因为它们随模块 Resources 走，注入代码拿不到——除非走 8.1 的 `createPackageContext`。

### 8.3 色值/尺寸

宿主主题色通过 `getIdentifier("system_notification_accent_color", "color", "android")` 取（`MiBluetoothToastHook.kt:60`）。要跟宿主深色模式，`ui/components/PodStatus.kt:221-232` 与 `AncSwitch.kt:339-350` 给了现成写法：`configuration.uiMode and UI_MODE_NIGHT_MASK` → `createConfigurationContext` 拿资源。这套只在模块自己的 Compose 里成立，**注入路径用不了**。

---

## 9. 移植到 FIIL 需要改哪几处

> 顺序即依赖顺序，前一项不做后面全废。

1. **开作用域**：`HookEntry.kt:26` 取消注释 → 让 `SettingsHeadsetHook` 在 `com.android.settings` 里真正加载。FIIL 侧目前 `app/src/main/java/moe/chenxy/fiilpods/` 连 `HookEntry.kt` 都还没有，先照 `refs/OppoPods/.../hook/HookEntry.kt:12-51` 建一个，并在 `app/src/main/assets/xposed_init` 写类名（现在写的是 `moe.chenxy.fiilpods.hook.HookEntry`，但文件不存在 → **当前必然启动失败**）。
2. **换品牌判定**：`SettingsHeadsetHook.isOppoPod`（`:489-501`）里的 `contains("oppo")` → FIIL 的型号前缀（`fiil` / `FIIL` / 具体型号名）。同时在 `HeadsetStateDispatcher.isOppoPod`（`:94-97`）改同一处逻辑，否则蓝牙进程根本不建 socket。
3. **换设备 id / support 位图**：`ConfigManager.DEFAULT_FAKE_DEVICE_ID = "01010607"`（`ConfigManager.kt:36`）与 `fakeSupport()` 的 24 位后缀（`:102`）。这两个值必须与 `HeadsetIDConstants.checkSupport/isTWS01Headset` 的判定口径一致，否则「支持」判定过不去。
4. **换协议层供数**：`RfcommController` 是 OPPO 私有 UUID（`0000079A-D102-11E1-9B23-00025B00A5A5`，`RfcommController.kt:104`）+ OPPO 帧格式。FIIL 已有 `pods/FiilGaia.kt`（FF 01/F1 … 0x0234 厂商号，8 字节头）与 `pods/FiilCommands.kt`，**只需保证对外仍产出同样的 3 路 `PodParams(battery, isCharging, isConnected)` 与 `anc: Int`**，第 4 节之后的所有 UI 代码不用改。
5. **改 ANC 码表**：`settingsAncLevel()`（`:567-578`）与 `settingsAncMode()`（`:558-565`）把内部 `anc:Int` 映射到 MIUI 的 `0103/0101/0100/0102/0200/0201`。FIIL 的 ANC 档位（`FiilCommands.GET_ANC=641 / SET_ANC=513`）要重新落到这 6 个码上；`oppoAncFromSettings`/`oppoAncFromLevel`（`:597-616`）是反向映射，必须成对改。
6. **改 extras/action 命名**：`OppoPodsAction` 的 38 个 `chen.action.oppopods.*` 常量（`utils/miuiStrongToast/data/OppoPodsAction.kt:4-42`）。FIIL 侧应新建 `FiilAction` 并统一前缀，**注意 `ACTION_PODS_BATTERY_CHANGED` 的 extras 键名（`left_battery` 等 9 个）不要改**——它们是 `SettingsHeadsetHook.batteryStatusFromExtras`（`:692-714`）的契约。
7. **修两个已知缺陷**：① 第 2.2 节 C 段的 `getDeviceInfo`/`isSupportAudioSwitch` 0 参死代码，改成带 `BluetoothDevice` 参数的重载；② `HookContext.hookBefore/hookAfter/hookConstructorAfter` 加返回值 `HookHandle` 并 `setId(...)`，为热重载留 `unhook()` 能力。
8. **补 `close()` 语义**：原 `HookContext` 没有 `close()`。移植时建议加 `open fun close() { handles.forEach { it.unhook() }; handles.clear() }`，并在 `onHotReloading` 里调用——否则设置进程热重载会叠加 hook（第 6.4 节）。
9. **给卡片 Decide**：如果 FIIL 机型在 HyperOS 上 `HeadsetIDConstants.checkSupport` 根本不认（大概率），第 2 节那套「骗宿主画原生 UI」会非常脆。建议同时保留路线 C（自建 `PopupActivity`/`MainActivity`，`app/src/main/AndroidManifest.xml:48-58` 已有 `PopupActivity` 与 `moe.chenxy.fiilpods.action.SHOW_PODS_UI`），设置页只做「跳转入口」。

---

### 附：一句话总结

`SettingsHeadsetHook` 不是「在设置页画卡片」，而是**「让设置页相信这台耳机是小米支持的型号，然后把它的电池/ANC 数据流换成我们的 socket 数据流」**——5 个伪造点（Intent extras、`getSupport`、`checkSupport`、`isBleMmaConnect`、MMA Proxy 返回值）+ 3 个回填点（`updateAtUiInfo`、`updateAncUi`、`onBatteryChanged(int,int,int)`），其余全是防泄漏与兜底。
