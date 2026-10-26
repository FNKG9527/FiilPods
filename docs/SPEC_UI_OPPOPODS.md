# OppoPods — HyperOS 蓝牙设置注入 / UI 实现规格

> 只读分析对象：`<repo>\refs\OppoPods`（单 commit `c201e25 Release 2.2.0`）
> 代码根 `S` = `app\src\main\java\moe\chenxy\oppopods\`；资源根 `RES` = `app\src\main\res\`
> 本文所有 `xxx.kt:NN` 均相对上述两个根，行号来自当前快照，可直接 grep 校验。
> 技术栈：Kotlin 2.4.20 / AGP 9.1.1 / Compose BOM 2026.09.00 / MIUIX 0.9.4 / libxposed **api 102** / focus-api 1.4（`gradle\libs.versions.toml:1-14`）。

---

## 0. 先纠正四个会直接误导实现的预期

| 预期 | 实际（带证据） |
|---|---|
| 设置页里插入了 Compose UI（`ComposeView` / `ComposeViewFactory`） | **不存在**。全仓 grep `ComposeView` = 0 命中；`setContent` 仅 2 处：`MainActivity.kt:28`、`PopupActivity.kt:76`。设置页渲染的始终是 MIUI 自己的 View 树 |
| `SettingsHeadsetHook` 在 `com.android.settings` 里生效 | **该 hook 根本没被加载**。`hook\HookEntry.kt:26` 整行被注释；`RES\values\arrays.xml:3-7` 与 `app\src\main\resources\META-INF\xposed\scope.list` 都只有 `com.android.bluetooth` / `com.milink.service` / `com.xiaomi.bluetooth`，无 `com.android.settings` |
| 通过 `IMiuiHeadsetService$Stub.onTransact` 拦截 Binder | 代码写了但**从未调用**：`hook\BluetoothUpstreamHeadsetHook.kt:413` 定义 `hookMiuiHeadsetBinder()`，全仓无任何调用点；`:41-44` 的 `onHook()` 只调 `hookHeadsetServiceBinder()` 与 `hookNotificationBatteryUpstream()` |
| 弹窗是悬浮窗（`TYPE_APPLICATION_OVERLAY` + 圆角 + 模糊） | **不是**。`PopupActivity` 是普通 Activity（`PopupActivity.kt:57`），主题 `Theme.OppoPods.Popup` 只是 `windowIsTranslucent` + 透明背景 + 无 dim（`RES\values\themes.xml:5-11`），圆角/毛玻璃全部由 MIUIX `OverlayDialog` 自带（`PopupActivity.kt:304-314`） |

**结论**：真正让「系统蓝牙详情页看起来支持 OPPO 耳机」的，是**在 `com.android.bluetooth` 进程里把 Binder 服务端的方法返回值全部伪造**，页面自己用 MIUI 原生布局去渲染，再靠一串 **分号/逗号拼接的 payload 字符串** 把电量与 ANC 灌进 Fragment 与 Battery View。这一条是移植 fiil 时最关键的判断：**不要去找 Compose 注入点，要找的是「服务端伪造 + payload 回灌」两条链路。**

---

## 1. 系统蓝牙详情页注入

### 1.1 模块装载链路

| 位置 | 事实 |
|---|---|
| `app\src\main\resources\META-INF\xposed\java_init.list` | 单行 `moe.chenxy.oppopods.hook.HookEntry` |
| `app\src\main\resources\META-INF\xposed\module.prop` | `minApiVersion=101` / `targetApiVersion=101` / `staticScope=true` |
| `app\src\main\assets\xposed_init` | 单行 `moe.chenxy.oppopods.hook.HookEntry_YukiHookXposedInit` ← **陈旧残留**：全仓不存在该类（YukiHook 时代遗留），真实入口是 `java_init.list` |
| `hook\HookEntry.kt:12` | `class HookEntry : XposedModule()`（libxposed 102 风格，非 YukiHook） |
| `hook\HookEntry.kt:18` | `if (!param.isFirstPackage) return` |
| `hook\HookEntry.kt:20-32` | 分派：`com.android.bluetooth` → `OppoLeAudioHook()` + `HeadsetStateDispatcher` + `BluetoothUpstreamHeadsetHook()`；`com.milink.service` → `MiLinkServiceHook`；`com.xiaomi.bluetooth` → `MiBluetoothToastHook` + `BluetoothUpstreamHeadsetHook()` |
| `hook\HookEntry.kt:35-51` | `loadHook()`：`hook.prefs = getRemotePreferences("oppopods_settings")`（L40），`ConfigManager.init(hook.prefs)`（L42），注册 `OnSharedPreferenceChangeListener`，仅当 `key == ConfigManager.PREF_KEY_CONFIG_JSON` 时 `refreshFromPrefs`（L44-46），最后 `hook.onHook()`（L50） |

### 1.2 被 hook 的 Activity / Fragment / Plugin 类（`hook\SettingsHeadsetHook.kt`，773 行，`object SettingsHeadsetHook : HookContext()`，L19）

> 注意：本节代码**当前未被装载**（见 §0）。但它是唯一完整实现了「设置页侧」改造的参考实现，移植时要么把它接回 `com.android.settings` scope，要么在蓝牙侧复刻其效果。其余字段：`TAG = "OppoPods-Settings"`（L20）、`PREFS_NAME = "oppopods_milink_state"`（L21）、`SETTINGS_REFRESH_INTERVAL_MS = 3_000L`（L22）、`batteryViews: WeakHashMap<Any, BluetoothDevice>`（L24）、`headsetFragments: WeakHashMap<Any, Boolean>`（L25）。

`onHook()`（L51-57）顺序：`hookActivityEntry()` → `hookSupportChecks()` → `hookServiceProxy()` → `hookBatteryView()` → `hookFragmentState()`。

| 目标类 | 成员 | 行 | 处理 |
|---|---|---|---|
| `com.android.settings.bluetooth.MiuiHeadsetActivity` | `onCreate(Bundle)` | L61-72 | `hookBefore`：从 intent 取 `android.bluetooth.device.extra.DEVICE`（L65），`isOppoPod` 命中后改写 intent：`MIUI_HEADSET_SUPPORT`=`fakeSupport()`（L68）、`COME_FROM` 缺省补 `"MIUI_BLUETOOTH_SETTINGS"`（L69）、`DEVICE_ID`=`fakeDeviceId()`（L70） |
| 同上 | `getDeviceID()` / `getSupport()` | L73-74, L92-102 | `hookAfter`，0 参重载；从 `mDevice` 字段取设备（L95），OPPO 设备则 `result = fakeDeviceId() / fakeSupport()` |
| `com.android.settings.bluetooth.MiuiHeadsetActivityPlugin` | `onCreate(Bundle)` | L78-88 | 同 Activity，但**不写 `COME_FROM`**（L85-86 只写 SUPPORT + DEVICE_ID） |
| `com.android.settings.bluetooth.HeadsetIDConstants` | `checkSupport(String)` | L105-107, L114-125 | `startWith(fakeDeviceId())` 或 `contains` → true；L120 先判 `value != deviceId && !value.startsWith(deviceId)` 才放行 |
| 同上 | `isTWS01Headset(String)` | L108 | `it == fakeDeviceId()` |
| 同上 | `isK77sHeadset(String)` | L109 | 恒 `false` |
| 同上 | `isBleMmaConnect(Context, BluetoothDevice, String)` | L129-137 | `deviceId == fakeDeviceId() \|\| isOppoPod(device)` → `result = true` |
| 同上 | `isBleMmaConnect(IMiuiHeadsetService, BluetoothDevice, String)` | L143-152 | 同上（服务类由 `findClass("com.android.bluetooth.ble.app.IMiuiHeadsetService")` 取得，L143） |
| `...tws.MiuiHeadsetBattery` | 4 参构造 | L269-280 | `hookConstructorAfter`，`args[0]` = device、`args[1]` = Context；命中则登记 `batteryViews[instance] = device`（L275）、`requestBluetoothStatus("battery-init")`、立刻 `updateBatteryView(instance)` |
| 同上 | `onBatteryChanged(String)` | L283-290 | `result = null` 吞掉原调用，改为 `updateBatteryView(instance)` |
| `com.android.settings.bluetooth.MiuiHeadsetFragment` | `onCreateView`（3 参） | L295-304 | `hookAfter`：登记 fragment、`requestBluetoothStatus("fragment-create")`、`startPeriodicRefresh()`、`injectFragmentStatus(instance)` |
| 同上 | `onServiceConnected()` | L307-315 | 同上流程 |
| 同上 | `refreshStatus(String,String)` | L318-328 | key 以 `"MMA_CONNECTION_FAILED"` 开头时吞掉并 `injectFragmentStatus` |
| 同上 | `handleConnectMmaFailed(String)` | L331-339 | 同上吞掉 |
| 同上 | `updateAncMode(int, boolean)` | L341-343, L350-367 | 映射为 OPPO ANC 后 `sendOppoAnc` + `sendAncChanged` + `callMethod(instance,"updateAncUi", settingsAncLevel(), false)`（L361）+ `injectFragmentStatus`；`args[1]` 为 `false` 时不动设备（L355-356） |
| 同上 | `updateAncLevel(String, boolean)` | L344-347 | 同上，级别码走 `oppoAncFromLevelCommand` |

**设备识别**（`isOppoPod`，L489-501）：`device.address` 命中 `isOppoAddress`（L534-537：与 `currentAddress` 相等或在 `knownOppoAddresses` 内）**或** `device.name/alias` 含 `"oppo"`（`ignoreCase = true`）。`isOppoFragment`（L481-487）额外接受 `mDeviceId == fakeDeviceId()` 与 `mSupport.startsWith(fakeDeviceId())`。

### 1.3 `IMiuiHeadsetService$Stub$Proxy` 逐方法拦截表（`hookServiceProxy()`，L156-179）

代理类名硬编码：`com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy`（L157）。所有拦截均为 `hookBefore` 直接 `this.result = ...`（即不真正跨进程调用）。

| # | 方法签名 | 工具函数 | 行 | 返回 / 行为 |
|---|---|---|---|---|
| 1 | `checkSupport(BluetoothDevice): String` | `hookProxyStringResult` | L158 | `fakeSupport()`；带调用计数器 `proxyCheckSupportCalls`（L186） |
| 2 | `getDeviceInfo(): String`（0 参） | `hookProxyStringArgResult` | L159 | `fakeSupport()` |
| 3 | `isSupportAudioSwitch(BluetoothDevice): String` | 同上 | L160 | `"1"` |
| 4 | `setCommonCommand(int, String, BluetoothDevice): String` | 同上 | L161-164 | `command == 102 → "0"`，否则 `"1"` |
| 5 | `connect(BluetoothDevice): void` | `hookProxyVoidDeviceNoop` | L165 | 吞掉（`result = null`） |
| 6 | `getDeviceConfig(BluetoothDevice): void` | 同上 | L166 | 吞掉；计数 `proxyGetDeviceConfigCalls`（L243） |
| 7 | `getCommonConfig(BluetoothDevice, String): void` | `hookProxyVoidDeviceStringNoop` | L167 | 吞掉；计数 `proxyGetCommonConfigCalls`（L257） |
| 8 | `isMiTWS(String): boolean` | `hookProxyBooleanStringResult` | L168 | `true` |
| 9 | `checkIsMiTWS(String): boolean` | 同上 | L169 | `true` |
| 10 | `getRingFindState(String): boolean` | 同上 | L170 | `false` |
| 11 | `changeAncMode(int, BluetoothDevice): void` | `hookProxyVoidDeviceCommand` | L171-174 | `oppoAncFromSettings(miMode)`（L597-603：1→2 降噪、2→3 通透、else 1 关），随后 `currentAnc = mode` + `sendOppoAnc` + `sendAncChanged` + `result = null` |
| 12 | `changeAncLevel(String, BluetoothDevice): void` | 同上 | L175-178 | `oppoAncFromLevelCommand(level)`（L625-632）：`02xx` → 只改 `currentAnc = 3` 并转发人声增强，返回 `null` 表示不写 result（L629）；其余走 `oppoAncFromLevel`（L605-616） |

匹配逻辑细节：`hookProxyStringArgResult`（L195-208）用 `args.firstOrNull { it is BluetoothDevice }` 与 `args.firstOrNull { it is String }` 双重判定，`isOppoPod(device) || isOppoAddress(address)` 任一成立即拦截（L200）。`hookProxyBooleanStringResult`（L210-221）只看 `args[0] as String` 地址。

### 1.4 Binder 服务端伪造（`hook\BluetoothUpstreamHeadsetHook.kt`，895 行，`class BluetoothUpstreamHeadsetHook : HookContext()`，L24）

`onHook()`（L41-44）= `hookHeadsetServiceBinder()` + `hookNotificationBatteryUpstream()`。`DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"`（L26）。

**绑定入口**（`hookHeadsetServiceBinder()`，L126-154）：
- `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService.onBind(Intent)` → `hookAfter` 拿到返回的 Binder 实例，`installHeadsetBinderHooks(binder.javaClass)`（L131-135）
- `BluetoothHeadsetService.onCreate()` → 仅 `registerStatusReceiver`（L139-142）
- 另外直接按类名加载 `BinderC6776v`（混淆名）与 `v` 两个 Binder 实现类（L148-153），`hookedBinderClasses` 去重（L217）

**`installHeadsetBinderHooks()`（L215-296）逐方法表**：

| 方法（含混淆别名） | 参数 | 行 | 行为 |
|---|---|---|---|
| `checkSupport` | `BluetoothDevice` | L221-229 | `result = fakeSupport()`，并记 `lastOppoDevice` |
| `getDeviceInfo` | `String` | L231 | `fakeSupport()` |
| `isSupportAudioSwitch` / `mo19775z1` / `z1` | `String` | L232 | `"1"`（L313-329 按候选名 `firstOrNull { method(name, String::class) 可解析 }` 择一） |
| `isMiTWS` / `mo19771O0` / `O0` | `String` | L233 | `true` |
| `checkIsMiTWS` / `mo19766B` / `B` | `String` | L234 | `true` |
| `getRingFindState` / `mo19772m0` / `m0` | `String` | L235 | `false` |
| `setCommonCommand` | `int, String, BluetoothDevice` | L238-253 | `102 → "1"`、`123 → "4"`、else `"1"`；随后 `sendRealStatus(device, "setCommonCommand:$command")` |
| `connect` | `BluetoothDevice` | L255 | 吞掉 + `sendRealStatus` + 350ms 后补一次刷新（L308） |
| `getDeviceConfig` | `BluetoothDevice` | L256 | 同上 |
| `getCommonConfig` | `BluetoothDevice, String` | L257, L349-363 | 同上（多一个 String 参数） |
| `changeAncMode` | `int, BluetoothDevice` | L365-378 | 吞掉；`sendOppoAnc(oppoAncFromMiuiMode(mode))`（L767-773：1→2、2→3、else 1） |
| `changeAncLevel` | `String, BluetoothDevice` | L380-393 | 吞掉；`sendOppoAncLevel(level)`（L788-800：`0201`→通透+人声增强开、`0200`→通透+人声增强关、否则按 `oppoAncFromMiuiLevel` 0103→5/0101→6/0100→7/0102→8/01*→7/02*→3/else 1，L775-786） |
| `register` | `IMiuiHeadsetCallback` | L263-273 | 吞掉回调注册（`result = null`），存入 `callbacks[asBinder()]`，立刻 + 350ms 后推进状态 |
| `registerCallbackDevice` | `callback, BluetoothDevice` | L274-285 | 同上 |
| `unregister` | `callback, BluetoothDevice` | L286-293 | 从 `callbacks` 移除 |

**死代码**：`hookMiuiHeadsetBinder()`（L413-429）+ `handleTransaction()`（L431-458）实现了完整的 `onTransact` 事务码分派（1 checkSupport / 2 register / 3 unregister / 4 connect / 9 changeAncMode / 10 changeAncLevel / 11 getDeviceInfo / 12 getDeviceConfig / 14 setCommonCommand / 15 getCommonConfig / 16 registerCallbackDevice / 18 isMiTWS / 19 checkIsMiTWS / 20 isSupportAudioSwitch / 24 getRingFindState），但无调用点。若要拦截 **Settings 进程直连的系统服务**（而非自己进程内的 Binder 对象），这条路才是正解。

### 1.5 真实 UI 是怎么「插进」设置页的 —— 不存在 Compose 注入

**没有任何 `ComposeView`/`ComposeViewFactory`/`ViewGroup.addView`/`LayoutInflater.inflate` 在 hook 目录中出现**（grep 全空）。替代机制共三条，函数名如下：

1. **`injectFragmentStatus(fragment)`**（`hook\SettingsHeadsetHook.kt:464-479`）— 直接调 MIUI Fragment 自己的方法：
   ```
   payload = "${settingsAncMode()}|0100;0101;0102;0103;0200;0201|${settingsBatteryString()}|00"   // L466
   callMethod(fragment, "updateAtUiInfo", payload)                                                // L468
   callMethod(fragment, "updateAncUi", settingsAncLevel(), false)                                 // L469
   callMethod(fragment, "refreshStatus", device.address, settingsRefreshPayload())                // L475
   ```
   `settingsAncMode()`（L558-565）返回 `"1"`(ANC)/`"2"`(通透)/`"0"`(关)；`settingsAncLevel()`（L567-578）返回 `0103/0101/0100/0102/0201/0200/0000`；`settingsBatteryString()`（L539-541）= 左,右,盒 三值。

2. **`updateBatteryView(view)`**（L450-454）— `callMethod(view, "onBatteryChanged", values[0], values[1], values[2])`，把电量直接写进 `MiuiHeadsetBattery` 的 3 参重载。

3. **Binder 侧 `sendRealStatus(address, reason)`**（`hook\BluetoothUpstreamHeadsetHook.kt:638-655`）— 主线程遍历所有已捕获回调，`callMethod(callback, "refreshStatus", address, payload)`（L647）。payload 由 `realRefreshPayload()`（L657-678）产生，最终落到 `RfcommController.miuiRefreshPayload(battery, anc, transparencyVocalEnhancement)`。

**payload 编码规范（16 字段，两处实现必须一致）**：

| 索引 | 值 | `pods\RfcommController.kt:364-394` | `hook\SettingsHeadsetHook.kt:580-595` |
|---|---|---|---|
| 0 | 左耳 | `miuiBatteryValue(left)` | 同 |
| 1 | 右耳 | `miuiBatteryValue(right)` | 同 |
| 2 | 盒 | `miuiBatteryValue(case)` | 同 |
| 3-6 | 空 | `""` | `""` |
| 7 | ANC 级别码 | `miuiAncLevel(anc, tvE)` | `settingsAncLevel()` |
| 8 | `"true"` | 固定 `"true"` | 这里写 `"false"` ← **两侧不一致** |
| 9,10 | 空 | `""` | `""` |
| 11,13,14 | `"00"` | 固定 | 固定 |
| 12,15 | 空 | `""` | `""` |
| 分隔 | `","` | `joinToString(",")` | 同 |

单值编码 `miuiBatteryValue()`（L377-381）/ `batteryValue()`（SettingsHeadsetHook L552-556）：**未连接 → `"255"`；连接 → `level`；充电 → `level or 128`**（即 128 位为充电标志位）。

### 1.6 设备名 / 电量 / ANC 状态回推到设置页 —— 广播与 extras

**动作常量**（`utils\miuiStrongToast\data\OppoPodsAction.kt:3-43`，全部 `chen.action.oppopods.*`）关键项：
`ACTION_PODS_UI_INIT`(L4) `ACTION_PODS_CONNECTED`(L7) `ACTION_PODS_DISCONNECTED`(L8) `ACTION_PODS_BATTERY_CHANGED`(L12) `ACTION_PODS_WEAR_STATUS_CHANGED`(L13) `ACTION_ANC_SELECT`(L14) `ACTION_PODS_ANC_CHANGED`(L15，字符串字面值其实是 `...pods_anc_select`) `ACTION_REFRESH_STATUS`(L18) `ACTION_GAME_MODE_SET`(L19) `ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET`(L21) `ACTION_SPATIAL_AUDIO_SET`(L23) `ACTION_EQ_PRESET_SET`(L25) `ACTION_CYCLE_ANC`(L32) `ACTION_CONFIG_CHANGED`(L41)。

**电量 extras**（`pods\RfcommController.kt:572-582` `Intent.putBatteryExtras`）：
`left_battery`(Int) `left_charging`(Bool) `left_connected`(Bool) `right_battery` `right_charging` `right_connected` `case_battery` `case_charging` `case_connected`；另有 Parcelable 形式 `status` = `BatteryParams`。
**佩戴 extras**（L584-588）：`left_wear_status` / `right_wear_status` / `case_wear_status`（Int，`-1` 表示无；取值见 `pods\Packets.kt:163-172`：`DISCONNECTED(0x00)` `IN_CASE(0x04)` `REMOVED(0x05)` `WEARING(0x07)`）。
**基础 extras**（L560-563）：`address`(String)、`device_name`(String)；连接事件另带 `product_id`（`hook\MiLinkServiceHook.kt:209`）。

**三端广播路由表**（`setPackage` 决定进程）：

| 发送方 | action | 目标包 | 行 |
|---|---|---|---|
| `RfcommController.sendExternalPodsStatusBroadcast` | 变量 | `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings` | `RfcommController.kt:558` |
| `SettingsHeadsetHook.requestBluetoothStatus` | `ACTION_PODS_UI_INIT` + `ACTION_REFRESH_STATUS` | `com.android.bluetooth` | `SettingsHeadsetHook.kt:424-433` |
| `SettingsHeadsetHook.sendAncChanged` | `ACTION_PODS_ANC_CHANGED` | `BuildConfig.APPLICATION_ID`, `com.android.settings`, `com.milink.service` | L669-678 |
| `SettingsHeadsetHook.sendOppoAnc` | `ACTION_ANC_SELECT` (`status`) | `com.android.bluetooth` | L634-644 |
| `MiLinkServiceHook.sendAncChanged` | `ACTION_PODS_ANC_CHANGED` | `APPLICATION_ID`, `com.milink.service`, `com.android.settings` | `MiLinkServiceHook.kt:341-350` |
| `Upstream.sendOppoAnc` | `ACTION_ANC_SELECT` + `ACTION_PODS_ANC_CHANGED` + `ACTION_REFRESH_STATUS` | `bluetooth` / `APPLICATION_ID` / `bluetooth` | `BluetoothUpstreamHeadsetHook.kt:802-823` |
| `MainUI.sendBluetoothModuleBroadcast` | 变量 | `com.android.bluetooth` **和** `com.xiaomi.bluetooth` | `ui\MainUI.kt:1024-1032` |
| `MainUI.broadcastConfigChanged` | `ACTION_CONFIG_CHANGED` | 指定单包 | `ui\MainUI.kt:1050-1056` |

**设置页周期刷新**：`SETTINGS_REFRESH_INTERVAL_MS = 3_000L`，`refreshRunnable` 仅在存在活跃 OPPO Fragment 时自我续期（`SettingsHeadsetHook.kt:22, L39-49, L435-441`）。**蓝牙侧回推延迟**：吞掉调用后统一 `350L` 补推（`BluetoothUpstreamHeadsetHook.kt:271, L284, L308, L360`）。

**状态持久化**：`oppopods_milink_state` SharedPreferences，键 `address / name / anc / game_mode / spatial_audio_mode / left_battery / left_charging / left_connected / right_* / case_*`（`MiLinkServiceHook.kt:640-658` save、L660-689 load；`SettingsHeadsetHook.kt:716-733` / L735-766 多一个 `transparency_vocal_enhancement`）。这是**跨进程/跨重启的真值来源**——hook 进程被杀后 getter 仍能返回上次状态。

---

## 2. 电量行 + ANC 状态组件

### 2.1 `ui\components\PodStatus.kt`（240 行）

**签名**（L43-49）：
```kotlin
@Composable
fun PodStatus(
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    modifier: Modifier = Modifier,
    compact: Boolean = false
)
```

**结构**（L53-90）：单个 `Row(fillMaxWidth, verticalAlignment = CenterVertically)`，三列等分 `Modifier.weight(1f)`，列间插 2 根竖分隔条。

| 元素 | 取值 | 行 |
|---|---|---|
| 分隔条颜色 | 深色 `Color(0xFF333333)` / 浅色 `Color(0xFFEEEEEE)`（`isSystemInDarkTheme()`） | L50 |
| 分隔条高度 | `compact ? 40.dp : 56.dp` | L51 |
| 分隔条宽度 | `0.5.dp` | L66, L79 |
| 三列顺序 | 左耳 → 右耳 → 耳机盒 | L57-89 |
| 列标题 | `R.string.batt_left_pod`="左" / `batt_right_pod`="右" / `pod_case`="耳机盒"（`RES\values-zh-rCN\strings.xml:63-65`） | L58, L71, L84 |

**`BatteryColumn`**（L93-149）内部纵向排布：
1. `Row`：标签 `fontSize = 16.sp`、`FontWeight.Bold`（L124-128）+ `WearStatusIcon`（`padding(start = 2.dp)`，L131）
2. `Spacer(4.dp)`（L134）
3. 电量文本 `"$level%"` 或 `"-"`，`fontSize = 13.sp`，`color = Color.Gray`（L135-139）
4. 电池图标 `Modifier.size(24.dp)`（L140-146）

**标签对齐技巧**（L112-113）：`if (label.length < 3) label.padEnd(3, '\u3000')` —— 用**表意空格 U+3000** 把「左/右」补齐到「耳机盒」的宽度，保证三列基线一致。这是个可直接复用的细节。

**图标映射**（`getBatteryIconRes`，L170-210）：按 10 档，`level<=10 → 1`、`<=20 → 2` … `else → 10`，非充电用 `R.drawable.common_1..common_10`，充电用 `R.drawable.charge_1..charge_10`（资源在 `RES\drawable-nodpi` 与 `RES\drawable-night-nodpi`，各 10+10 张 PNG）。

**掉线兜底**（L104-110）：`var lastKnownLevel by remember { mutableIntStateOf(100) }`，`level > 0` 时更新；断连时文本显示 `"-"` 但**图标仍用 `lastKnownLevel`** 避免闪空。

**佩戴图标**（`WearStatusIcon`，L151-168）：`Box(18.dp)` 内 `Icon(14.dp, tint = Color.Gray)`；`WEARING → AppIcons.Contacts`、`REMOVED → AppIcons.RemoveContact`、其余 `null`（不渲染，但仍占 18dp 位）。

**主题感知取图**（`themedPainterResource`，L217-240）：当系统 `uiMode` 与 App 内主题 `uiMode` 不一致时，用 `createConfigurationContext` 造一个 Resources 再取图（L227-233），位图走 `BitmapPainter`。**AncSwitch 里有一份同名函数但多处理矢量图**（见 §2.2），两处重复。

### 2.2 `ui\components\AncSwitch.kt`（369 行）

**签名**（L49-58）：
```kotlin
@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    compact: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: ((Boolean) -> Unit)? = null
)
```

**它不是分段控件、不是胶囊按钮、也不是 ListItem**，而是**一行等分「图标在上 + 文字在下」的大按钮**（`AncButton`，L274-329），选中态用图标换图 + 文字变色表达；下面按需再叠一条 MIUIX `TabRowWithContour` 子级分段条。

**主行模式顺序**（L70-113，`Row` + 每个 `Modifier.weight(1f)`）：

| 序 | 标签 | 文案来源 | 图标 off/on | 行 |
|---|---|---|---|---|
| 1 | 降噪 | `R.string.noise_cancellation_title`="降噪" | `ic_openanc_off` / `ic_openanc_on` | L74-82 |
| 2 | 自适应（**条件显示**） | `adaptive_title`="自适应" | `ic_adaptive_off` / `ic_adaptive_on` | L84-94 |
| 3 | 通透 | `transparency_title`="通透" | `ic_transparent_off` / `ic_transparent_on` | L95-103 |
| 4 | 关闭 | `off`="关闭" | `ic_closeanc_off` / `ic_closeanc_on` | L104-112 |

判定：`isSelected = ancStatus.isNoiseCancellation()`（L78，覆盖 5 个降噪子态，`pods\Packets.kt:138-147`）；`ADAPTIVE`/`TRANSPARENCY`/`OFF` 用 `==` 精确比对。

**降噪强度子行**（仅 `ancStatus.isNoiseCancellation()` 时出现，L115-150）：顺序 **智能 → 轻度 → 中度 → 深度**（`noise_cancellation_smart` / `_light` / `_medium` / `_deep`，L129-135）。`assistHighlightedIndex`（L140）用于「当前是 SMART 模式但用户上次数值是 LIGHT/MEDIUM/DEEP」时，把那个档位以 `primary.copy(alpha = 0.72f)` 作**辅助高亮**（L213-228 两种高亮层级）。

**透传子行**（仅在 `TRANSPARENCY` 且回调非空时，L152-168）：两个 Tab —— **通透 / 人声增强**（`transparency_title` / `transparency_vocal_enhancement`），选中索引由 `transparencyVocalEnhancement` 决定。

**尺寸表**（L59-64）：

| 量 | compact=false | compact=true |
|---|---|---|
| 纵向 padding（整体 Column） | 16.dp | 8.dp |
| tab minWidth / maxWidth | 0.dp / 98.dp | 0.dp / 72.dp |
| tab height | 45.dp | 38.dp |
| tab itemSpacing | 9.dp | 4.dp |
| tab outerPadding | 12.dp | 8.dp |
| 子行 topPadding | 16.dp | 8.dp |
| `AncButton` box | 60.dp | 40.dp |
| 图标（选/未选） | 60 / 48.dp | 40 / 32.dp |
| 标签字号 | 14.sp | 12.sp |

**动画**（`ANIM_DURATION = 300`，L47）：
- 图标：`Crossfade(targetState = isSelected, animationSpec = tween(300))`（L304-307）—— 淡入淡出换图，不是缩放
- 文字色：`animateColorAsState(..., tween(300))`（L288-292），`primary` ↔ `onBackground`
- 按下反馈：`Modifier.pressable(interactionSource, indication = SinkFeedback())` + `clickable(indication = null)`（L297-298）—— **必须关掉 clickable 自带的 ripple，否则和 SinkFeedback 双触发**

**子行实现**（`AncStrengthTabRow`，L172-238）：`BoxWithConstraints` 判断 `maxWidth >= 480.dp` 时宽度收敛到 `fillMaxWidth(0.8f)`（L194），**用空字符串喂 `TabRowWithContour` 只借它的轮廓与滑块指示器**（L196-205），再叠一层自绘 `Row` 放真文字（L207-236）——因为 MIUIX 那个组件的 tab 文本样式不可控。`ResponsiveAncTabRow`（L240-272）是同一套逻辑的简化版（不叠层，直接用组件自带文本）。

---

## 3. 快捷弹窗 `PopupActivity.kt`（491 行）

### 3.1 窗口设置（关键：它就是普通 Activity）

| 项 | 值 | 位置 |
|---|---|---|
| 基类 | `class PopupActivity : ComponentActivity()` | L57 |
| 注册 | `android:exported="true"`、`taskAffinity=""`、`excludeFromRecents="true"`、`theme=@style/Theme.OppoPods.Popup` | `app\src\main\AndroidManifest.xml:45-54` |
| intent-filter | action `chen.action.oppopods.show_pods_ui` + `DEFAULT` | Manifest L50-53 |
| 主题 | 父 `android:Theme.Material.Light.NoActionBar` + `windowIsTranslucent=true`、`windowBackground=@android:color/transparent`、`windowNoTitle=true`、`backgroundDimEnabled=false`、`colorBackgroundCacheHint=@null` | `RES\values\themes.xml:5-11` |
| 语言覆盖 | `attachBaseContext` 里 `AppLocale.apply(newBase, prefs.getInt("app_language", SYSTEM))` | L58-62 |
| 实际"浮层" | MIUIX `OverlayDialog`（`title`=设备名, `show`, `backgroundColor`, `onDismissRequest`, `onDismissFinished`） | L304-314 |
| 背景色 | 深色 `Color(0xFF1A1A1A)` / 浅色 `Color(0xFFF7F7F7)` | L300 |
| 外层容器 | `Scaffold(containerColor = Color.Transparent)` | L303 |
| **代码中不存在的** | window type、LayoutParams、FLAG_BLUR_BEHIND、圆角半径、模糊半径 | 全文件无 |

**结论**：圆角/毛玻璃/点击外部关闭**全部由 `OverlayDialog` 默认行为提供**，模块没有自己调 `window.setLayout` 或 `setBackgroundBlurRadius`。若移植时要自定义尺寸，需在该 Dialog 内部约束，而不是改 Window。

### 3.2 触发链

```
com.xiaomi.bluetooth 进程  MiBluetoothToastHook.createPodsNotification()
   └─ PendingIntent.getActivity(... Intent("chen.action.oppopods.show_pods_ui")
        .setClassName("moe.chenxy.oppopods","moe.chenxy.oppopods.PopupActivity")
        .putExtra("android.bluetooth.device.extra.DEVICE", device)
        .putExtra("bluetoothaddress", address)
        .putExtra("device_name", alias))                      # hook\MiBluetoothToastHook.kt:124-134
        → 作为焦点通知的 setContentIntent()                    # L227
   └─ 点击通知 → PopupActivity                             # Manifest:50-53
```

**入口守卫**（L67-74）：读 `notificationClickAction`，若 ≠ `NOTIFICATION_CLICK_MODULE_POPUP`（=0，`config\ConfigManager.kt:47`），立刻 `openNotificationTarget()` 后 `finish()`——即弹窗本身是「点击通知默认行为」，另外两个候选是 `openSystemSettings()` 与 `openHeyTapOrModule()`（L95-101）。

**数据流**（L179-258）：
- 注册 6 个 action 的 `BroadcastReceiver`（L221-229）：`ANC_CHANGED` / `BATTERY_CHANGED` / `CONNECTED` / `DISCONNECTED` / `GAME_MODE_CHANGED` / `TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED`
- `ANC status Int → NoiseControlMode` 映射表在 L185-195（1 关 / 2 降噪 / 3 通透 / 4 自适应 / 5-8 四档降噪）
- 广播 `ACTION_PODS_UI_INIT` + `ACTION_REFRESH_STATUS` 拉初值（L231-238）
- **500ms 超时兜底**：没收到 `CONNECTED` 也把弹窗弹出来（L247-250）
- **15s 轮询**：`while(true) { delay(15_000); 广播 REFRESH_STATUS }`（L251-257）

### 3.3 竖屏布局顺序（`PortraitPopupBody`，L346-402）

`Column(fillMaxWidth)`，卡片之间 `Spacer(12.dp)`：

| 序 | 内容 | 参数 | 行 |
|---|---|---|---|
| 1 | `Card` → `PodStatus` | `padding(horizontal = 12.dp, vertical = 16.dp)` | L360-365 |
| 2 | `Spacer(12.dp)` | | L366 |
| 3 | `Card` → `AncSwitch` | 默认尺寸（非 compact），传 `adaptiveModeEnabled` / `transparencyVocalEnhancement` | L367-375 |
| 4 | `Spacer(12.dp)` | | L376 |
| 5 | `Card` → `SwitchPreference` **低延迟开关** | `title = R.string.game_mode`="游戏模式"、`summary = game_mode_summary`="低延迟音频模式"（`RES\values-zh-rCN\strings.xml:11-12`） | L377-384 |
| 6 | `Spacer(16.dp)` | | L385 |
| 7 | `Row` 两个 `TextButton(weight(1f))`，`spacedBy(12.dp)` | 左 **更多**（`R.string.more`）→ `onMore`；右 **完成**（`R.string.done`）→ `onDone` | L386-400 |

### 3.4 横屏布局（`LandscapePopupBody`，L404-491）

外层 `Row(fillMaxWidth().widthIn(min = 560.dp).height(240.dp), spacedBy(10.dp))`，左右 **0.60 / 0.40** 分栏：

- 左栏（`weight(0.60f)`，`verticalArrangement = Center`）：`Card{PodStatus(compact=true, padding 10/10)}` → `Spacer(8.dp)` → `Card{AncSwitch(compact=true)}`（L424-448）
- 右栏（`weight(0.40f)`）：**游戏模式卡片本身即开关** —— `Card(onClick = { onGameModeChange(!gameMode) }, pressFeedbackType = Sink)`，开启时 `color = primary` + 白字（L455-476）；`Spacer(6.dp)` → `TextButton(更多)` → `Spacer(6.dp)` → `TextButton(完成)`（L477-488）

**"更多"跳转**（`openMoreTarget`，L103-109）：`MORE_CLICK_HEYTAP(0)` → 优先 `com.heytap.headset`，缺失回落本模块；`MORE_CLICK_SYSTEM_SETTINGS(1)` → 拉起 `MiuiHeadsetActivity`；`else`(`MORE_CLICK_MODULE`=2) → `MainActivity`。系统设置跳转的 extras 与 `MainUI.openSystemHeadsetSettings()`（`ui\MainUI.kt:610-620`）逐字段一致：`DEVICE` / `bluetoothaddress` / `MIUI_HEADSET_SUPPORT` / `COME_FROM="MIUI_BLUETOOTH_SETTINGS"` / `DEVICE_ID` / `FLAG_ACTIVITY_NEW_TASK`。

---

## 4. 超级岛 / 焦点通知

存在**两条互斥路径**，由 `ConfigManager.islandMode()` 选择（`0` 不显示 / `1` 官方 / `2` 模块，`config\ConfigManager.kt:40-42`）：

### 4.1 模块自制岛 —— `utils\FocusIslandUtil.kt`（131 行）

- API：`com.xzakota.hyper.notification.focus.FocusNotification`（L12 import），构建器 `FocusNotification.buildV3 { ... }`（L67）
- 常量：`CHANNEL_ID = "oppopods_focus_island"`、`NOTIFICATION_ID = 10086`、`ISLAND_TIMEOUT_SECONDS = 3`、`DISMISS_DELAY_MS = 4000L`（L19-23）
- 签名（L25-30）：`fun showBatteryIsland(context: Context, prefs: SharedPreferences, batteryParams: BatteryParams, address: String): Boolean`
- 前置校验（L32-36）：**左右耳至少一只 `isConnected`**，否则 `return false`（盒电量不参与判断）
- 图标：`PodImageLoader.loadIslandLeftBitmap/RightBitmap`（L41-42）→ 用户自定义图，回落到 `R.drawable.img_left` / `img_right`，再回落 `img_box`（`utils\PodImageLoader.kt:66-86`）；两者任一为 null 直接失败（L44-47）→ `Icon.createWithBitmap`（L50-51）
- 字段设置（L67-108）：

| 域 | 值 | 行 |
|---|---|---|
| `enableFloat` | `true` | L71 |
| `ticker` | `"OppoPods"` | L72 |
| `tickerPic` | `picLeft` | L73 |
| `isShowNotification` | **`false`**（只出岛，通知栏不留条目） | L75 |
| `island.islandProperty` | `1` | L77 |
| `bigIslandArea.imageTextInfoLeft` | `type=1`，`picInfo{type=1,pic=picLeft}`，`textInfo{title=leftText, content="%"}` | L79-89 |
| `bigIslandArea.imageTextInfoRight` | `type=2`，`picInfo{type=1,pic=picRight}`，`textInfo{title=rightText, content="%"}` | L90-100 |
| `shareData` | `title="OppoPods"`，`content = "L: xx%  R: yy%"`（`joinToString("  ")`） | L102-106 |

- **没有进度条 / progress 字段**——岛内用电量数字 + 图标表达，不存在线性进度。
- 通知本体（L110-116）：`Notification.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)`，`addExtras(extras)`；`nm.notify(10086, ...)`（L118）；**`Handler(mainLooper).postDelayed { nm.cancel(10086) }` 4000ms 后自动撤**（L120-122）。
- 通道（L54-60）：`IMPORTANCE_DEFAULT` + `setSound(null,null)` + `enableVibration(false)` + `setAllowBubbles(true)`。

### 4.2 官方岛劫持 —— `BluetoothUpstreamHeadsetHook` 内联

`hookNotificationBatteryUpstream()`（L46-124）在 `com.android.bluetooth` 进程里改 MIUI 自己的强提示：

| 挂钩 | 行 | 行为 |
|---|---|---|
| `MiuiBluetoothNotificationApi.showNewConnectedToast(int,int,int,int,BluetoothDevice,String)` | L50-80 | 命中 OPPO 设备则用自己的电量覆盖 `args[1]/args[2]`（左右耳）与 `args[3]`（佩戴态），再转发给真实 `MiuiBluetoothNotification.showConnectedToast(...)`（L69-78） |
| `MiuiBluetoothNotification.invokeStatusBar(Context,String,Bundle)` | L89-102 | 若 `bundle.getString("notifyId") == "headset_wear_notification"`（判定见 L723-725）且岛模式为 `NONE`/`MODULE` → `result = null` **整个吞掉官方岛**（L92-98）；否则 `patchHeadsetWearIslandBundle` |
| `MiuiBluetoothNotification.updateParameters(C4705R2)` | L108-122 | 反射改私有字段 `f18107b`(左电量) `f18108c`(右电量) `f18109d`(佩戴态)，字段名来自 L110-118 |

**岛内文本补丁**（`patchHeadsetWearIslandBundle` L710-721 + `patchIslandJson` L727-737 + `putTextParams` L739-749）：只在 `islandMode == OFFICIAL` 时生效（L713）；把 `"param"` 与 `"island_param"` 两个 key 的 JSON 里的 `left`/`right` 节点重写为
```json
"textParams": { "text": "85%", "textColor": -1, "turnAnim": true }
```
（`text` 带 `%` 后缀，L715-716；`textColor=-1` 即白色；`turnAnim=true` 开启数字翻牌动画）。

**官方强提示的另一种发送方式**（`utils\miuiStrongToast\MiuiStrongToastUtil.kt`）：不走 NotificationCompat，而是反射 `StatusBarManager.setStatus(1, "strong_toast_action", bundle)`（L52-55 / L159-163 / L179-183）。`showOfficialConnectToast`（L117-168）构造 `StringToastBundle`：`setPackageName("com.xiaomi.bluetooth")`、`setStrongToastCategory(VIDEO_TEXT_TEXT_VIDEO)`、`setDuration(5000)`、`setParam(toastJson)`、`setIslandParam(islandJson)`、**`setNotifyId("headset_wear_notification")`**（L150-158）。视频素材用官方 `earphone_left_inear` / `earphone_right_inear` / `*_no_inear`（L126-127）。隐藏走 `hideOfficialToast`（L170-185），且 5s 内不重复发（L172-173）。

### 4.3 配置开关（`config\ConfigManager.kt` + `ui\pages\SettingsPage.kt`）

| 配置 | 键 / 默认值 | 定义 | UI |
|---|---|---|---|
| 焦点通知总开关 | `focusNotification: Boolean = true` | ConfigManager.kt:19 | `SettingsPage.kt:208-213` `SwitchPreference`（"焦点通知"） |
| 岛模式 | `islandMode: Int = ISLAND_MODE_OFFICIAL(1)` | ConfigManager.kt:13, L40-42 | `SettingsPage.kt:254-260` 下拉（不显示/官方/模块） |
| 模块岛触发时机 | `islandShowTimings: Set<Int> = emptySet()`；`0 连接时 / 1 佩戴时 / 2 摘下时 / 3 放回盒中时` | ConfigManager.kt:14, L43-46 | `SettingsPage.kt:261-268`，**仅 `islandMode == MODULE` 时显示**（L261），多选不折叠（`collapseOnSelection = false`） |
| 点击通知行为 | `notificationClickAction: Int = 0(模块弹窗)` | ConfigManager.kt:15, L47-49 | `SettingsPage.kt:226-232` |
| 点击更多行为 | `moreClickAction: Int = 2(模块)` | ConfigManager.kt:16, L50-52 | `SettingsPage.kt:244-250`，**仅当 clique==MODULE_POPUP 时显示**（L234） |

**总开关的硬门槛**：`MiBluetoothToastHook.onHook()` 开头 `if (!ConfigManager.focusNotification()) return`（`hook\MiBluetoothToastHook.kt:43-46`）—— 关闭时**连整个焦点通知钩子都不装**，不只是不显示。运行期还二次校验：刷新通知前再判一次并主动 `cancelNotification`（L274-277）。

**岛显示触发**（`pods\RfcommController.kt:598-625`）：比较佩戴态变化，`islandShowTimingForChange` 映射 `WEARING→1 / REMOVED→2 / IN_CASE→3`（L618-625），`shouldShowIsland`（L610-616）在 OFFICIAL 下**只认「连接时」**，MODULE 下才看用户集合。

---

## 5. 融合设备中心 / MiLink

### 5.1 入口与载入（`hook\miLink\MiLinkServiceHook.kt`，690 行，`object MiLinkServiceHook : HookContext()`，L28）

`onHook()`（L46-52）顺序：`hookContextEntry()` → `hookMxBluetoothRuntime()` → `hookHeadsetRuntimeDisplay()` → `spatialAudioHook.hookCirculateHeadsetServiceInfo()` → `hookGameModeCard()`。
状态容器：`knownOppoAddresses`（L31）、`currentAddress/currentName/currentBattery/currentAnc/currentGameMode`（L35-39）、`currentSpatialAudioMode`（L40）、`lastAncBatteryController` / `lastProfileContext`（L41-42）、`gameModeIcon` 缓存（L43）、`spatialAudioHook`（L44）。持久化用 **独立** prefs：`PREFS_NAME = "oppopods_milink_state"`（L30）。

**它并没有"替换/添加一张设备卡片"**，而是：让 HyperOS 自己的卡片**以为**对面是台小米耳机 —— 通过伪造 `getDeviceId` / `getBatteryLevel` / `getAncState` 等 getter，再主动调用 MIUI 的 `headsetPropertyChangeListener` 让卡片重绘。

### 5.2 `com.milink.service` 进程内被 hook 的类（全量）

| 类 | 成员 | 行 | 用途 |
|---|---|---|---|
| `com.xiaomi.mxbluetoothsdk.service.MxBluetoothService`、`...manager.MxBluetoothManager` | `getInstanceForIsMiTWS(Context)` | L54-65 | 借首次调用拿到 `Context` → `registerStatusReceiver` |
| 同上（两个类） | `checkIsMiTWS(BluetoothDevice)` | L73 | 恒 `1` |
| | `getDeviceId(BluetoothDevice)` | L74 | `fakeDeviceId()` |
| | `getBatteryLevel(BluetoothDevice)` | L75 | 恒 `1`（真实电量走别的 getter） |
| | `getAncState(BluetoothDevice)` | L76 | `miLinkAncState()` |
| | `getDeviceRunInfo(BluetoothDevice)` | L77 | 恒 `0` |
| | `getWearStatus(BluetoothDevice)` | L78 | `"0,0"` |
| | `isLeAudio(BluetoothDevice)` | L79 | `false` |
| | `openAnc(BT)` / `closeAnc(BT)` / `openTransparent(BT)` | L80-82 | `result = 2 / 1 / 3`，同时 `currentAnc = 1 / 0 / 2` 并广播 |
| | `isMiTWS(String)` / `isSupportAudioSwitch(String)` / `getRingFindState(String)` | L85-87 | `true` / `miLinkSwitchState()` / `false` |
| | `getSpatialMode(BT)` / `setSpatialMode(BT,int)` | `MiLinkSpatialAudioHook.kt:14-15` | 见 5.3 |
| `com.miui.headset.runtime.ProfileContext` | `getDeviceId(BT)` / `getBatteryLevel(BT)` | L93-94 | `fakeDeviceId()` / `miLinkBatteryLevels()` |
| | `getAudioSpatialEffectState(BT)` | `MiLinkSpatialAudioHook.kt:134-141` | `miLinkSpatialMode()` |
| | `setAudioEffectState(BT,String,int)` | `...:146-158` | 吞掉并转成 OPPO 空间音频广播 |
| `com.miui.headset.runtime.AncBatteryController` | `getDeviceId` / `getBatteryLevel` / `getAncState` / `getBatteryLevelCache` / `getFindRingState` / `getSwitchState` | L95-100 | 依次 `fakeDeviceId()` / `miLinkBatteryLevels()` / `miLinkAncState()` / `miLinkBatteryLevels()` / `miLinkGameModeState()` / `miLinkSwitchState()` |
| | `getHeadsetPropertyBlock(BT)` | L98, L123-125 | 返回 `batteryPercentForMiLink()` 并触发 `notifyHeadsetPropertyChanged(..., 4)` 重绘 |
| | `setAncStateBlock(BT,int)` | L157-175 | 把 MIUI 的 1/2/3 转 OPPO，广播，并连发 updateType `8` 与 `4` 两次刷新 |
| | `setMiAudioEffect(BT,int)` | `MiLinkSpatialAudioHook.kt:66-78` | 吞掉 + 空间音频同步 |
| | `setHeadTracking(BT)` | `...:81-92` | `result = 100`，强制 `HEAD_TRACKING` |
| | `setFindRing(BT,int)` | L459-469 | 游戏模式开关，`result = 100` |
| `com.miui.headset.runtime.AncBatteryModel` | `getDeviceSpatialType()` / `setDeviceSpatialType(int)` | `...:97-108` | `miLinkDeviceSpatialType()`（L419-421：面板可用返回 `1` 否则 `0`），并回写字段 `deviceSpatialType` |
| `com.miui.headset.runtime.AncBatteryController$mmaCallback$1` | `onDeviceSpatialType(BT,int)` / `onReportSpatialState(BT,int)` | `...:113-129` | 吞掉底层回报，改为推当前 UI 值 |
| `com.miui.headset.api.HeadsetInfo` | `getDeviceId` `component3` `getPowers` `component4` `getMode` `component5` `getSwitchState` `component8` `getFindRingState` `component11` `getAudioEffectState` `component10` | L103-112 + `...:28-29` | 同名 `componentN` 是 Kotlin data class 反编译后的属性读法，**每对必须一起 hook**，否则卡片会一会儿小米一会儿 OPPO |
| `com.miui.circulate.api.service.CirculateServiceInfo` | `setHeadsetId(String,int)` | `MiLinkSpatialAudioHook.kt:34-44` | 面板未启用时把 `serviceProperties.getAll()` 里的 `headset_switch_state` 强制写 `0`（关闭官方开关） |

### 5.3 空间音频 hook（`hook\miLink\MiLinkSpatialAudioHook.kt`，161 行，`internal class MiLinkSpatialAudioHook(private val hook: MiLinkServiceHook)`，L11）

| 方法 | 行 | 内容 |
|---|---|---|
| `hookMxBluetoothRuntime(classes)` | L12-17 | 对 MxBluetooth 两个类调 `getSpatialMode` / `setSpatialMode` |
| `hookHeadsetRuntimeDisplay()` | L19-30 | `AncBatteryController.getMiAudioEffect`；`setMiAudioEffect`；`setHeadTracking`；`getDeviceSpatialType`；`setDeviceSpatialType`；两个 mmaCallback；`ProfileContext.getAudioSpatialEffectState` / `setAudioEffectState`；`HeadsetInfo.getAudioEffectState` / `component10` |
| `hookCirculateHeadsetServiceInfo()` | L32-44 | 见上表 |
| `hookSpatialCommand` | L46-62 | `setSpatialMode(BT,int)`：`oppoSpatialFromMiLink` → 更新 + 三条广播 + `notifySpatialUiChanged` + `result = 1` |
| `hookSpatialStateBlock` | L64-93 | `setMiAudioEffect` 写 `result = null`；`setHeadTracking` 写 `result = 100` |
| `hookSpatialCallbacks` | L111-130 | 两个 callback 均 `result = null` |

**空间音频数值映射**（`MiLinkServiceHook.kt`）：内部三态 `SPATIAL_AUDIO_OFF(0) / FIXED(1) / HEAD_TRACKING(2)`（`ConfigManager.kt:53-55`）；
`miLinkSpatialMode()`（L387-395）：`HEAD_TRACKING → (deviceSpatialType==1 ? 11 : 9)`、`FIXED → 1`、`OFF → 0`，面板禁用时 `-1`；
`oppoSpatialFromMiLink()`（L397-403）：`9,11 → HEAD_TRACKING`、`1 → FIXED`、else `OFF`。

### 5.4 卡片内容改写（游戏模式）

`hookGameModeCard()`（L457-490）做两件事：
1. `AncBatteryController.setFindRing(BT,int)` → 把「查找耳机」按钮改造成游戏模式开关（L459-469）。
2. **`SynergyView.setTitle(int)`**（L477-488）—— 类名按 `com.miui.circulate.world.sticker.ui.SynergyView` 与 `...stickers.p067ui.SynergyView` 两个候选探测（L472-475）。命中条件双重：
   - `viewResourceName(view, view.id) == "mi_audio_ringing_view"`（L480）
   - `viewResourceName(view, resId) ∈ {"circulate_headset_control_audio_find_earphone", "circulate_headset_control_audio_stop_find_earphone"}`（L482-483）
   
   命中后改写三处子 View：`setSynergyTitle(view, "游戏模式")`（L484）、`setSynergySubtitle(view, 已开启/已关闭)`（L485）、`setSynergyIcon(view, loadGameModeIcon(view))`（L486，图标从模块包 `R.drawable.ic_game_mode` 借 `createPackageContext` 取，L532-539）。子 View 通过**资源 id 名字**查找：`item_title` / `item_subtitle` / `item_icon`（L510, L514, L522, L525-530）。`setTitle` 优先走 `setTitle(CharSequence)` 重载，不存在才退化到 `item_title` 文本（L502-511）。

**这是"替换卡片"的真实手法**：不是新建卡片，而是**按资源 id 认领 MIUI 已有的那张卡**，改它的标题/副标题/图标，同时把 `result = null` 让原逻辑不执行。

### 5.5 数据喂给卡片（`MiLinkServiceHook.kt`）

- `miLinkBatteryLevels()`（L296-309）：返回 **6 元 List**，顺序 `[盒, 左, 右, 盒充电, 左充电, 右充电]`，每项 `isConnected? level : -1`（`batteryValue` L319-322）、充电标志 `0/1`（`chargingValue` L324-326）
- `batteryPercentForMiLink()`（L311-317）：取**左右耳中已连接者的最小值**，无则 `0`
- `miLinkAncState()`（L279-286）：`2,5,6,7,8 → 1`（降噪）、`3 → 2`（通透）、else `0`（关）
- `miLinkSwitchState()`（L423-425）：面板可用 `1` 否则 `0`
- `miLinkGameModeState()`（L452-455）：未启用 `-1`，开启 `103`，关闭 `0`
- 能力判定 `panelCapabilities()`（L541-546）= `detectDeviceCapabilities(context, backendDeviceName() ?: currentName, snapshot.productId ?: currentProductId)`

### 5.6 主动触发卡片重绘

`notifyHeadsetPropertyChanged(controller, device, updateType)`（L602-608）：反射取字段 `headsetPropertyChangeListener`，`callMethod(listener, "invoke", device, updateType)`。已用到的 `updateType`：**4**（电量/属性块）、**8**（ANC）、**9**（空间音频）、**10**（配置/游戏模式）。
`notifySpatialUiChanged`（L574-584）一次调用会对 `owner / lastAncBatteryController / lastProfileContext` 三个目标（去重后）各发 `9` + `4`，并通过 `audioEffectListener.invoke(state)` 单独通知空间音频监听者（L595-600）。`syncSpatialModel`（L586-593）直接写 model 的 `spatialState` 与 `deviceSpatialType` 字段。

---

## 6. 设置集成 / 模块内完整控制页

### 6.1 是否存在「从系统蓝牙设置进入模块完整页」的入口

**不存在。** 全仓 `startActivity` 目标只有四处：

| 起点 | 目标 | 行 |
|---|---|---|
| `ui\MainUI.kt:582-589` | `Settings.ACTION_BLUETOOTH_SETTINGS`（或开启蓝牙） | 模块 → 系统 |
| `ui\MainUI.kt:597-621` | `com.android.settings.bluetooth.MiuiHeadsetActivity`（带 5 个 fake extras） | 模块 → 系统详情页 |
| `hook\MiBluetoothToastHook.kt:124-134` | `moe.chenxy.oppopods.PopupActivity`（作为通知 contentIntent） | 系统 → 模块弹窗 |
| `PopupActivity.kt:111-113 / 115-122` | `MainActivity` 或 `com.heytap.headset` | 模块内 → 模块主页 |

即：**系统侧唯一的模块入口是「点焦点通知 → 弹窗 → 更多 → 模块主页」**，中间隔两次点击。`AppLocale`/`App`/`MainUI` 全链路都没有 `ACTION_VIEW` 或自定义 scheme 接收器。模块只注册了 `de.robv.android.xposed.category.MODULE_SETTINGS`（`AndroidManifest.xml:40-43`），那是给 LSPosed 管理器用的。

**移植启示**：若要实现「系统蓝牙详情页 → 完整控制页」，本仓**没有可抄的通路**，必须自建（例如让 `changeAncLevel` 收到特定 level 串时 `startActivity` 模块页，或让 `MainUI` 注册一个 `chen.action.*.open_ui` 的 exported Activity 并由 hook 侧 `Context.startActivity`）。最接近的既有模式是 `PopupActivity` 的 exported + 自定义 action 组合（Manifest:45-54）。

### 6.2 模块内页面导航

| 层 | 内容 | 行 |
|---|---|---|
| 入口 | `MainActivity : ComponentActivity` → `setContent { App(...) }`；`enableEdgeToEdge` 透明状态/导航栏；`window.isNavigationBarContrastEnforced = false` | `MainActivity.kt:18-51` |
| 主题包裹 | `App()` → `AppLocale.Provider` → `AppTheme(colorSchemeMode, accentMode)` → `MainUI(backStack,...)`；初始栈 `mutableStateListOf<NavKey>(Screen.Main)` | `ui\App.kt:30-48` |
| 屏幕枚举 | `@Serializable sealed interface Screen : NavKey`：`Main / About / Theme / LeAudio / Equalizer / RfcommDebug` | `ui\MainUI.kt:84-98` |
| 渲染 | `NavDisplay(backStack, onBack, content = navContent)`；栈深 1 时 `onBack` 直接 `finish()` | `ui\MainUI.kt:989-999` |
| Tab | `MainTab { Module, Earphones, Settings }` | `ui\MainTab.kt:11-15` |
| Tab→页面 | `MainTabsScaffold`（`ui\MainTabs.kt:63`）；`MainTab.Module → ModuleTabPage`（:211）、`Earphones → EarphonesTabShell`（:224）、`Settings → SettingsTabPage`（:264） | — |
| Earphones 页切换 | `EarphonesTabPage` 用 `AnimatedContent(targetState = showEarphoneDetail)`，`true → PodDetailPage`、`false → DevicePickerPage` | `ui\pages\EarphonesTabPage.kt:55-104`（PodDetailPage 调用 L61-89） |
| 底栏 | `MainBottomNavigation`：`floating` 用 `FloatingNavigationBar`（圆角 `50.dp`），否则 `NavigationBar(showDivider=false)`；`blur` 时 `textureBlur(backdrop, shape)` + `color = Transparent` | `ui\MainBottomNavigation.kt:17-62`（模糊 L26-33, L38） |
| 页面底部留白 | `overlayBottomBar ? 104.dp : 28.dp` | `ui\MainUI.kt:145-146` |

**与 system 设置页联动的命令面**（`ui\MainUI.kt`，全部 `setPackage("com.android.bluetooth")` + `FLAG_RECEIVER_FOREGROUND`）：`setAncMode`（L417-435，`status` 1..8）、`setGameMode`（L437-445，`enabled`）、`setTransparencyVocalEnhancement`（L447-455）、`setEqPreset`（L499-507，`preset`）、`saveEqPreset`（L509-528，`id/name/frequencies/gains/min_value/max_value`）、`deleteEqPreset`（L530-540，`eq_entries_json`）、`setDualDeviceConnection`（L542-550）、`refreshStatus`（L623-630）。**注意：这些是"模块 → 蓝牙进程"的请求，不是系统向模块下发。**

### 6.3 `ui\pages\PodDetailPage.kt`（312 行）— 从上到下的区块顺序

**签名**（L47-74，全部有默认值，可直接复用）：
```kotlin
@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    batteryParams: BatteryParams,
    wearStatus: WearStatus = WearStatus(),
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    smartAncLevel: NoiseControlMode? = null,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: (Boolean) -> Unit = {},
    gameMode: Boolean = false, gameModeSupported: Boolean = false, onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = ConfigManager.SPATIAL_AUDIO_OFF, onSpatialAudioModeChange: (Int) -> Unit = {},
    dualDeviceConnection: Boolean = false, onDualDeviceConnectionChange: (Boolean) -> Unit = {},
    spatialAudioSupported: Boolean = false, spatialSoundSupported: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    equalizerVisible: Boolean = false, dualDeviceSupported: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    boxImagePath: String? = null,
)
```

**竖屏（portrait）顺序**（L143-182）：
1. 耳机盒大图 —— `Image(fillMaxWidth(0.7f), padding vertical 16.dp, ContentScale.FillWidth)`，画源 `rememberPodImagePainter(boxImagePath)`（L185-192：优先 `BitmapFactory.decodeFile`，失败回落 `R.drawable.img_box`）（L148-157）
2. `podControlItems(...)`（L159-181）

**`podControlItems` 内部顺序**（L194-312）：

| # | 卡片 | 内容 | 行 |
|---|---|---|---|
| 1 | `Card(padding horizontal 12.dp)` → `PodStatus` | `padding(12.dp, 16.dp)` | L223-233 |
| 2 | `Card(padding horizontal 12.dp, vertical 12.dp)` → `AncSwitch` | 传 `smartAncLevel` / `adaptiveModeEnabled` / `transparencyVocalEnhancement` | L235-248 |
| 3 | 一张 `Card(padding horizontal 12.dp)` **内含 5 个条件项，按下列固定顺序** | ① `gameModeSupported` → `SwitchPreference(游戏模式 / 低延迟音频模式)`（L254-261）<br>② `spatialAudioSupported` → `OverlayDropdownPreference(空间音频)`，选项 **关闭 / 固定 / 头部跟踪**（L262-275，值域 L217-221）<br>③ `spatialSoundSupported` → `SwitchPreference(空间音效)`，summary 随状态切 `enabled`/`off`，勾选即写 `SPATIAL_AUDIO_FIXED`（L276-285）<br>④ `equalizerVisible` → `BasicComponent(大师调音 / 选择大师调音预设)` + `MiuixIcons.Basic.ArrowRight` 尾箭头，`onClick = onOpenEqualizer`（L286-298）<br>⑤ `dualDeviceSupported` → `SwitchPreference(双设备连接)`（L299-306） | L250-308 |
| 4 | `Spacer(height = bottomContentPadding)` | | L309-311 |

**横屏（landscape）**（L75-141）：`Row` 两栏 —— 左栏居中放图（`fillMaxWidth(0.82f)`、`widthIn(max = 360.dp)`）+ 设备名 `22.sp Bold`（L91-104）；右栏 `LazyColumn(fillMaxSize, contentPadding top 12.dp, Arrangement.Center)` 只渲 `podControlItems`（L107-138）。`return` 提前结束（L140）。

### 6.4 `ui\pages\EqualizerPage.kt`（396 行）— EQ UI

**签名**（L46-59）：
```kotlin
@Composable
fun EqualizerPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    builtInPresets: List<EqPresetInfo>,
    devicePresets: List<EqDevicePreset>,
    selectedId: Int,
    customEqSupported: Boolean,
    customEqFrequencies: List<Int>,
    customEqMaxPresets: Int,
    onSelectPreset: (Int) -> Unit,
    onSavePreset: (Int, String, List<Int>, List<Int>, Int, Int) -> Unit,
    onDeletePreset: (EqDevicePreset) -> Unit,
)
```

**页面结构**（`LazyColumn`，L68-119，`contentPadding` top `+12.dp` / bottom `+20.dp`）：

| 区块 | 内容 | 行 |
|---|---|---|
| `SmallTitle("推荐")` | `R.string.eq_recommended`="推荐" | L75 |
| `Card`（每条一个 `EqCheckboxRow`） | 内置预设：标题 + 描述 + 右侧 `Checkbox`；描述由 `presetDescription()`（L352-381）按 `modeType` 26/27/28/29/30/34 或名称关键字映射 | L76-87 |
| `SmallTitle("自定义")` | `R.string.eq_custom`="自定义" | L89 |
| `Card` | ① 自定义预设行 `CustomEqRow`：点击标题=编辑、右侧 `IconButton(MiuixIcons.Delete)`=删除、再右 `Checkbox`=选中（L92-100, L185-219）<br>② `customEqSupported` 时追加 `AddEqualizerRow("添加均衡器" / "创建一个自定义均衡器预设")`，`enabled = canCreate`（L101-116, L221-249） | L90-118 |

**「还能加几个」判定**（L60-63）：`customPresets = devicePresets.filter { it.id !in builtInIds && it.name.isNotBlank() }`；`canCreate = customEqSupported && (customEqMaxPresets <= 0 || customPresets.size < customEqMaxPresets)`。

**编辑弹窗**（`EqualizerWindowDialog`，L251-288）：MIUIX `WindowDialog`，标题优先用预设名、否则 `R.string.eq_new`="新建预设"；底部两按钮 `取消` / `保存`（`ButtonDefaults.textButtonColorsPrimary()`）。

**EQ 滑块本体**（`EqualizerSliders`，L324-350）—— 这是整个 EQ 界面的核心：

| 属性 | 值 | 行 |
|---|---|---|
| 方向 | **竖向** `VerticalSlider`（MIUIX 组件） | L338-345 |
| 滑块高度 | `Modifier.height(180.dp)` | L344 |
| 值域 | `valueRange = 0f..1f`，真实增益由线性映射还原：`gain = (lower + t * (upper - lower)).roundToInt()` | L339-343 |
| 频段数 | **动态**，来自 `preset.frequencies`（长度可变，`take(32)` 上限见 `pods\Packets.kt:78`） | L334 |
| 默认频段（设备未上报时） | `EqDefaults.FREQUENCIES = [62, 250, 1000, 4000, 8000, 16000]` → **6 段** | `pods\DeviceCapabilities.kt:38` |
| 增益范围 | `EqDevicePreset.minValue = -6` / `maxValue = +6`（dB） | `pods\DeviceCapabilities.kt:31-32` |
| 每列布局 | 上：增益文本（正数带 `+`，`color = primary`）→ 中：滑块 → 下：频率标签 `12.sp` | L336-347 |
| 列分布 | `Row(Arrangement.SpaceEvenly)`，每列 `weight(1f)`、水平居中 | L329-336 |
| 频率格式化 | `>=1000 且能被 1000 整除` → `"${v/1000}k"`，否则原样（如 `62`、`4k`、`16k`） | L395-396 |
| 规范化 | `normalized(fallbackFrequencies)`（L383-393）：frequencies 空则回退设备值再回退默认；gains 按索引补 0 并 `coerceIn(lower, upper)` | — |

**新建预设的初值**（L106-115）：`frequencies = customEqFrequencies.ifEmpty { EqDefaults.FREQUENCIES }`，`gains = frequencies.map { 0 }`，`name = "自定义 ${customPresets.size + 1}"`，`id = 0`（0 表示新建，由 `onSavePreset` 的 id 参数区分）。

**删除确认**（L290-322）：`WindowDialog`，文案 `R.string.eq_delete_confirm`="确定删除"%1$s"吗？"，按钮 `取消` / `确定`（primary）。

**入口挂载**：`ui\MainUI.kt:912-949` —— `entry<Screen.Equalizer>`，`Scaffold` + `TopAppBar(title/largeTitle = eq_preset_title, navigationIcon = Back)`，页面参数由 `displayCapabilities`（`eqPresets` / `customEqFrequencies` / `customEqMaxPresets` / `customEqSupported`）注入（L938-946）。进入方式：`onOpenEqualizer = { backStack.add(Screen.Equalizer) }`（`ui\MainUI.kt:701`），触发点即 PodDetailPage 的「大师调音」行。

---

## 7. 配置 / 状态管线

### 7.1 两条 `getRemotePreferences` 通路

| 侧 | 代码 | 行 |
|---|---|---|
| 模块进程取"远端"prefs（LSPosed 提供的共享存储） | `hook.prefs = getRemotePreferences("oppopods_settings")` | `hook\HookEntry.kt:40` |
| 模块写入并同步到远端 | `service?.getRemotePreferences(PREFS_NAME)?.let { writePrefs(it, normalized) }`，service 为 null 时只 `Log.w` 不抛错 | `config\ConfigManager.kt:163-173`（L168-171） |
| 服务绑定 | `OppoPodsApp : Application(), XposedServiceHelper.OnServiceListener`，`onServiceBind` 里 `xposedService = service` 并 `notifyListeners` | `OppoPodsApp.kt:9-31` |
| prefs 名 | `PREFS_NAME = "oppopods_settings"` | `ConfigManager.kt:25` |

**注意**：hook 侧**只读**，靠 `registerOnSharedPreferenceChangeListener` 监听 `config_json` 一个键（`HookEntry.kt:43-49`）；模块侧**双写**（本地 `prefs` + 远端 `remotePrefs`），`writePrefs` 用 `.commit()` 而非 `.apply()`（L187），保证跨进程立即可见。

### 7.2 配置 JSON schema（`config\ConfigManager.kt:9-21`，`@Serializable data class AppConfig`）

```kotlin
@Serializable
data class AppConfig(
    val fakeDeviceId: String = "01010607",             // DEFAULT_FAKE_DEVICE_ID (L36)
    val logLevel: Int = 1,                             // LOG_LEVEL_BASIC (L38)
    val islandMode: Int = 1,                           // ISLAND_MODE_OFFICIAL (L41)
    val islandShowTimings: Set<Int> = emptySet(),      // 0..3 (L43-46)
    val notificationClickAction: Int = 0,              // MODULE_POPUP (L47)
    val moreClickAction: Int = 2,                      // MORE_CLICK_MODULE (L52)
    val milinkCardFeatures: Set<Int> = setOf(0, 1),    // GAME_MODE, SPATIAL_AUDIO (L56-58)
    val autoGameMode: Boolean = false,
    val focusNotification: Boolean = true,
    val leAudio: LeAudioConfig = LeAudioConfig(),
)
```

**Json 配置**（L60-63）：`ignoreUnknownKeys = true`、`encodeDefaults = true`（→ 未知字段安全，升级不炸）。

**存储布局**（`writePrefs`，L175-188）：`config_json` 存完整 JSON 字符串，**同时**把每个字段平铺成独立键（`fake_device_id` / `log_level` / `island_mode` / `island_show_timings`(StringSet) / `notification_click_action` / `more_click_action` / `milink_card_features`(StringSet) / `auto_game_mode` / `focus_notification`）。
**读取优先级**（`readConfig`，L190-234）：JSON 打底 → 平铺键逐字段覆盖（用 `Int.MIN_VALUE` / `null` 作"未设置"哨兵，L192-199）→ `normalized()`（L236-245）统一 clamp。**兼容迁移**：`prefs.getBoolean("open_heytap", false)` 为真时强制 `moreClickAction = MORE_CLICK_HEYTAP`（L209）。

**派生值**：
- `fakeSupport()` = `"${fakeDeviceId()},000000000000000010000000"`（L102）← 这就是塞给 MIUI 的 `MIUI_HEADSET_SUPPORT` / `getDeviceInfo` / `getSupport` 的返回值格式
- `fakeDeviceId()` = `fakeDeviceId.normalizedFakeDeviceId()`，空串回落 `"01010607"`（L84, L247）
- 各 `updateXxx(prefs, service?, value)` 均走 `current().copy(...)` → `save()` → 双写（L104-153）

**日志分级**：`Log`（`hook\HookContext.kt:63-106`）按 `ConfigManager.logLevel()` 过滤 —— `v/d` 需 ≥DEBUG(2)，`i/w/e` 需 ≥BASIC(1)，`OFF(0)` 全静音。全部经 `module?.log(android.util.Log.INFO/ERROR, ...)` 打到 LSPosed 日志，**不写 logcat**。

### 7.3 `HookContext` 反射 API 面（`hook\HookContext.kt`，156 行）

```kotlin
abstract class HookContext {                                   // L10
    lateinit var module: XposedModule                          // L11
    lateinit var appClassLoader: ClassLoader                   // L12
    lateinit var prefs: SharedPreferences                      // L13
    lateinit var packageName: String                            // L14

    abstract fun onHook()                                       // L16
    fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()   // L18
    fun fakeSupport(): String = ConfigManager.fakeSupport()     // L20
    fun refreshConfig()                                         // L22-24
    fun findClass(name: String): Class<*>                       // L26  Class.forName(name, false, appClassLoader)
    fun findMethod(className, methodName, vararg parameterTypes): Method        // L28-29  isAccessible = true
    fun findConstructor(className, vararg parameterTypes): Constructor<*>       // L31-32
    fun findMethodByParamCount(className, methodName, paramCount): Method       // L34-36  按参数个数猜重载（应对混淆）
    fun findConstructorByParamCount(className, paramCount): Constructor<*>      // L38-40
    fun hookAfter(method: Method, block: HookParam.() -> Unit)                  // L42-47
    fun hookBefore(method: Method, block: HookParam.() -> Unit)                 // L49-54
    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit)  // L56-60
}
```

`HookParam`（L108-118）：
```kotlin
class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args         // L109
    val instance: Any? = chain.thisObject     // L110
    var hasResult = false; private set        // L111-112
    var result: Any? = initialResult          // L113  赋值即置 hasResult=true
        set(value) { hasResult = true; field = value }   // L114-117
}
```

**三种 hook 语义（必须理解，否则会写错）**：
- `hookBefore`（L49-54）：先跑 block，**`if (param.hasResult) param.result else chain.proceed()`**（L52）—— 也就是说**只要 block 里碰过 `result` 就完全跳过原方法**。这就是本仓满地 `this.result = null` 用来"吞掉 void 方法"的原理。
- `hookAfter`（L42-47）：先 `chain.proceed()` 拿返回值，再让 block 改写 `param.result`（L45）—— 改不到 `chain` 已返回的值，但 `hookAfter` 的 block 内 `result = x` 会作为最终返回值传出。
- `hookConstructorAfter`（L56-60）：构造完成后执行，`result` 是新建实例。

**顶层反射工具（非类成员，`import moe.chenxy.oppopods.hook.getObjectField` 等直接用）**：
- `getObjectField(instance: Any?, fieldName: String): Any?`（L120-130）—— 沿 `superclass` 链 `getDeclaredField`，全链找不到抛 `NoSuchFieldException`
- `setObjectField(instance: Any?, fieldName: String, value: Any?)`（L132-143）—— 同上
- `callMethod(instance: Any?, methodName: String, vararg args: Any?): Any?`（L145-155）—— **只按「方法名 + 参数个数」匹配**（L149），不校验参数类型；找不到抛 `NoSuchMethodException`。这个宽松匹配是本仓能在混淆环境下工作的关键，但同名同参数量重载会命中错误的那个。

---

## 8. 移植 fiil 时的取舍清单（基于上述证据）

1. **不要在 `com.android.settings` 里找注入点。** 本仓把 `SettingsHeadsetHook` 整个注释掉（`HookEntry.kt:26`）仍能工作，说明只要蓝牙服务端 getter 全部伪造，MIUI 设置页会自己渲染出"支持 ANC"的界面。
2. **必须先有「状态缓存 + 广播」这两件基础设施**，否则 hook 进程冷启动时 getter 拿不到电量会返回 0，卡片会闪成 0%。参考 `oppopods_milink_state` 的 14 个键（`MiLinkServiceHook.kt:640-658`）。
3. **`IMiuiHeadsetService$Stub$Proxy` 的方法集合必须完整覆盖**（§1.3 的 12 个），漏一个就会出现"有降噪没电量"这类半残状态；且**服务端（§1.4）与客户端（§1.3）的返回值要保持一致** —— 本仓已有一处不一致：`setCommonCommand(102)` 客户端返 `"0"`、服务端返 `"1"`（`SettingsHeadsetHook.kt:163` vs `BluetoothUpstreamHeadsetHook.kt:245`）。
4. **`f18107b/f18108c/f18109d` 这类混淆字段名是版本相关的硬编码**（`BluetoothUpstreamHeadsetHook.kt:110-118`），换 HyperOS 版本必挂；应改成"按类型扫描字段"或直接 hook 方法。同理 `BinderC6776v`、`mo19771O0`、`C4705R2` 都是混淆名。
5. **payload 两侧实现已漂移**（§1.5 索引 8：`"true"` vs `"false"`），移植时应只保留一份 `miuiRefreshPayload`。
6. **UI 侧可以整包复用**：`PodStatus` / `AncSwitch` / `PopupActivity` / `PodDetailPage` / `EqualizerPage` 五个文件与 OPPO 协议无关，只需要把 `BatteryParams` / `NoiseControlMode` / `DeviceCapabilities` 三个模型换成 fiil 的。
