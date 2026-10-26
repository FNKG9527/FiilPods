# v2.1.0 修复记录 —— 用户上机反馈的三条问题

> 版本：**v2.1.0（versionCode 3，2026-10-26）**
> 基线：v2.0.0（versionCode 2）修好了「模块无法切换 MAF 模式」
> 本文件记录 v2.0.0 上机后用户报的三条问题的根因、修法与验证证据。

---

## 零、用户原话

> 「目前问题如下：
> 1：在设备中心里面切换模式时候 虽然能切换 但是图标还是会显示在原来的功能图标里面 等在进行点击一次才能切换到对于的图标上面
> 2：模块页面 模块服务未响应 但功能能够正常使用
> 3：在设置页面开启焦点通知无法在通知栏里面显示通知」

同一轮日志里还确认了 v2.0.0 的 MAF 修复**已成功**：

```
18:02:28.695 D/RFCOMM/TX MATE  00 27 01 00 04 FF 02 02 34     ← setUnlock
18:02:28.696 D/RFCOMM/TX MATE  00 27 01 00 02 02 00           ← GET_VERSION
...
18:03:07.xxx D/RFCOMM/TX MATE  00 2C 01 00 02 01 xx           ← setMAF（cid=44, cmdId=0）
18:03:07.xxx D/RFCOMM/RX MATE  xx 28 03 00 03 0C 01 xx        ← 耳机主动推送 cid=40 确认新档位
```

---

## 一、问题 1：设备中心图标滞后一拍

### 根因

`hook/milink/MiLinkServiceHook.kt` 的两个 hook 点（`hookAncCommand` L218-231、`hookAncStateBlock` L237-253）
都只做了三件事：

1. 改本类的成员 `ancMode`
2. `sendAncSelect(maf)` —— 让蓝牙进程把命令下发到耳机
3. `notifyPropertyChanged(instance, device, UPDATE_ANC)` —— 通知控制中心「去刷新一下」

**少了第四步：回写 `AncBatteryModel` 的内存字段。**

控制中心收到 `UPDATE_ANC` 后重绘时，会重新读 `AncBatteryModel` 的 `ancState` 等字段。
模型里还是旧的，所以图标不动。用户再点一次时——上一次点击走的广播路径已经（在别处）把模型刷新过了——
才终于显示正确。这就是「再点一次才对」的成因。

### 对照参照

`refs/OppoPods/.../hook/milink/MiLinkServiceHook.kt:586-593` 的 `syncSpatialModel()`：

```kotlin
val model = runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull() ?: return
if (!isTargetAncBatteryModel(model)) return
runCatching { setObjectField(model, "spatialState", spatialMode) }
runCatching { setObjectField(model, "deviceSpatialType", miLinkDeviceSpatialType()) }
```

空间音频是「先回写模型字段再刷 UI」，ANC 走的是同一条路，OppoPods 只是把 ANC 的模型回写放在了
别处（它的 `AncBatteryModel` 由 `RfcommController` 侧统一维护）。我们的架构不同，必须自己补。

### 修法

**新增 `HookContext.setObjectField(instance, fieldName, value)`**（`hook/HookContext.kt`）：
沿继承链 `getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)`，
命中即返回，全程 `runCatching`。

**新增 `MiLinkServiceHook.syncAncModel(owner, device, maf)`**：
- 从 `owner.ancBatteryModel` 与 `lastAncBatteryController.ancBatteryModel` 各取一次，
  `distinctBy { javaClass.name }` 去重（同一个对象可能被两处引用）
- 每个模型先 `isTargetAncBatteryModel(model)` 校验，再依次尝试三个字段名：
  `ancState` / `bluetoothAncState` / `miLinkAncState`（不同 ROM 版本命名有漂移，
  `setObjectField` 找不到字段时静默跳过，不会崩）
- 最后 `notifyPropertyChanged(owner, device, UPDATE_ANC)`
  + `mainHandler.post { notifyPropertyChanged(lastAncBatteryController, device, UPDATE_ANC) }`
  （post 一次是因为面板的 listener 可能挂在主线程的消息队列里）

**接入三处**：
1. `hookAncCommand()`：`sendAncSelect(maf)` 之后
2. `hookAncStateBlock()`：`sendAncSelect(ancMode)` 之后
3. `handleBroadcast()` 的 `FiilAction.ACTION_PODS_ANC_CHANGED` 分支（耳机实体键切换也走这里）

**新增 `miLinkAncStateFrom(maf)`**：不依赖成员字段 `ancMode`，按传入参数算 MIUI 的 ANC 状态码
（`NOISE_CANCEL→1`、`OPEN/LISTEN/WIND→2`、`else→0`，与既有 `miLinkAncState()` 同映射）。

---

## 二、问题 2：模块页误报「模块服务未响应」

### 根因

`ui/pages/HomePage.kt:134`：

```kotlin
val serviceTimeout = active && !bluetoothServiceResponsive
```

`bluetoothServiceResponsive` 只在收到
`OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE` 时置真，
并且 `ui/MainUI.kt:461-464` 用 75 秒窗口校验。

而发送端 `hook/BluetoothProcessHook.kt` 的 `announceAlive(ctx)`（L235）
**只在 `AdapterService.onCreate` 时被调用一次**（L188）。

于是：模块装好后蓝牙进程先起（发了 ALIVE，但那时 App 还没开，没人听）——
用户再打开 App，就一条也收不到，75 秒后必然显示「模块服务未响应」，
而实际功能全都正常（这正好对应用户说的「但功能能够正常使用」）。

### 对照参照

`refs/OppoPods/.../hook/HeadsetStateDispatcher.kt:62-68`：
在 `ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 分支里**每次都回发** ALIVE，不是只发一次。

### 修法

`hook/BluetoothProcessHook.kt` 的 `handleBroadcast()` 里，
`ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 分支开头加：

```kotlin
announceAlive(this@BluetoothProcessHook.hostContext ?: return@runCatching)
```

UI 每 30 秒发一次 `UI_INIT` + `REFRESH_STATUS`（`ui/MainUI.kt:446-452`），
修复后心跳自然维持，不会再超时。

---

## 三、问题 3：设置页开焦点通知后通知栏无通知

这条是三个独立缺陷叠加，缺一不可。

### 3a（最根本）`HookEntry` 从未注入 LSPosed 远端 prefs

**OppoPods 的正确范式**（`refs/OppoPods/.../hook/HookEntry.kt` 的 `loadHook()`）：

```kotlin
hook.prefs = getRemotePreferences("oppopods_settings")
ConfigManager.init(hook.prefs)
hook.prefs.registerOnSharedPreferenceChangeListener { sp, key ->
    if (key == ConfigManager.PREF_KEY_CONFIG_JSON) ConfigManager.refreshFromPrefs(sp)
}
```

**我方原状**：`installHooksForPackage()` 只 `when(packageName) { ... Hook(this, classLoader).install() }`，
没有 `getRemotePreferences`、没有 `ConfigManager.init`、没有变更监听。

后果：四个被注入的进程（`com.android.bluetooth` / `com.android.settings` /
`com.xiaomi.bluetooth` / `com.milink.service`）里的 `ConfigManager` **永远是 `AppConfig()` 默认值**。
用户在设置页改「焦点通知」「超级岛来源」，对 Hook 侧完全无效。

**修法**：

`hook/HookContext.kt` 新增：

```kotlin
lateinit var prefs: SharedPreferences
lateinit var packageName: String

fun attach(prefs: SharedPreferences?, hostPackage: String): HookContext
```

`attach()` 里设 `packageName`；prefs 非空时 `this.prefs = prefs` + `ConfigManager.init(prefs)`
+ `registerOnSharedPreferenceChangeListener`（监听 `PREF_KEY_CONFIG_JSON`、
`PREF_KEY_FOCUS_NOTIFICATION`、`PREF_KEY_ISLAND_MODE` 时调 `ConfigManager.refreshFromPrefs(sp)`）。

`hook/HookEntry.kt` 新增 `prepareRemotePrefs()`：

```kotlin
private fun prepareRemotePrefs(): SharedPreferences? = runCatching {
    getRemotePreferences(ConfigManager.PREFS_NAME)
}.onFailure { log(android.util.Log.WARN, TAG, "getRemotePreferences(...) unavailable", it) }.getOrNull()
```

四个 Hook 全部 `.also { it.attach(prefs, packageName) }.install()`。

### 3b `FocusIslandUtil.showBatteryIsland()` 全工程零调用点

`utils/FocusIslandUtil.kt`（133 行）本身是完整的：建 channel `oppopods_focus_island` →
`FocusNotification.buildV3` → `Notification.Builder(...).addExtras(extras)` →
`nm.notify(10086)` → 4 秒后 `nm.cancel`。

但全模块 grep 的结果是：**只有定义处，没有任何调用点**。
用户在设置页把「超级岛来源」选成「模块」，通知栏里什么都不会出现。

**修法**：`hook/MiBluetoothToastHook.kt` 新增 `showModuleFocusIsland(ctx, snapshot, address)`，
在 `onBatteryChanged()` 里调用：

```kotlin
val cfg = ConfigManager.current()
if (!cfg.focusNotification) return
if (cfg.islandMode != ConfigManager.ISLAND_MODE_MODULE) return
FocusIslandUtil.showBatteryIsland(ctx, prefs, params, address)
```

`BatterySnapshot` 的三个 `PodBattery?` 转成 `BatteryParams` 的 `PodParams`
（`battery` / `isCharging` / `isConnected` / `rawStatus`）。

分流规则对齐 OppoPods 的 `MiBluetoothToastHook.registerNotificationReceiver()`：
`ISLAND_MODE_MODULE` → 画自己的岛；`ISLAND_MODE_OFFICIAL` → 系统自己弹；`ISLAND_MODE_NONE` → 不弹。

### 3c 通知渠道是 `IMPORTANCE_MIN`，HyperOS 会折叠

```kotlin
NotificationChannel(CHANNEL_ID, "FIIL 耳机电量", NotificationManager.IMPORTANCE_MIN)
```

`IMPORTANCE_MIN` 的通知在 HyperOS 上会被塞进「静默通知」分区并**默认折叠**，
下拉栏里看不见。

**而且渠道重要性一旦创建就不可修改**——改代码重装也没用，系统会沿用旧值。

**修法**：换成新渠道 id `fiilpods_battery_v2` + `IMPORTANCE_LOW`（有声音栏但不吵），
`setSound(null, null)` + `enableVibration(false)` + `setShowBadge(false)`。

### 3d 时序陷阱：进程在「关闭」状态下启动 → 再打开也不生效

原 `onHook()` 照抄 OppoPods 写了：

```kotlin
if (!ConfigManager.focusNotification()) {
    logI("focus notification disabled, skip MiBluetoothToastHook")
    return
}
```

问题：若 `xiaomi.bluetooth` 进程是在「焦点通知=关」的状态下启动的，接收器压根没挂；
用户之后打开开关，进程不会重启，**仍然一条都不收**。

**修法**：改为**总是挂接收器**，把开关判断下沉到真正要显示的那一刻：

```kotlin
if (!ConfigManager.focusNotification()) {
    logI("focus notification currently disabled; receiver still mounted so re-enabling takes effect immediately")
}
```

`onBatteryChanged()` 开头统一门控：

```kotlin
val focusEnabled = runCatching { ConfigManager.focusNotification() }.getOrDefault(true)
if (!focusEnabled) {
    cancelNotification(ctx)   // 关掉时顺带撤掉已显示的常驻通知
    return
}
```

---

## 四、验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | **EXIT=0** |
| `:app:testDebugUnitTest` | **EXIT=0，49 项全绿**（ModelCapabilityTest 16 + ProtocolFrameTest 27 + AddressMaskTest 6） |
| `:app:assembleDebug :app:assembleRelease` | **EXIT=0** |
| `FiilPods-v2.1.0-debug.apk` | 35,713,691 B（34.1 MB） |
| `FiilPods-v2.1.0-release.apk` | 4,063,403 B（3.88 MB） |
| 版本 | `versionCode 3` / `versionName 2.1.0` |
| 签名 | V2 SHA-256 `605996dc…38d34`，与 v1.0.0 / v2.0.0 **完全一致**，可直接覆盖安装 |
| xposed 元数据 | `assets/xposed_init` = `moe.chenxy.fiilpods.hook.HookEntry`；`module.prop` min/target API = 102；`scope.list` 四条齐全；`PodImageProvider` 已声明 |

**release dex 标记实测（`Contains` 全 True）**：
`syncAncModel`、`announceAlive`、`prepareRemotePrefs`、`showModuleFocusIsland`、
`fiilpods_battery_v2`、`oppopods_focus_island`、`receiver still mounted`。

---

## 五、构建踩坑（本次新增）

**Gradle daemon 在 16 GB 机器上被 OOM 杀掉**：

```
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (mmap) failed to map 308281344 bytes. Error detail: G1 virtual space
```

`gradle.properties` 是 `-Xmx2g -XX:MaxMetaspaceSize=768m`，构建 release 时
（R8 + Compose 编译 + Kotlin 编译）峰值超过物理内存。

**可用解法**（本次采用）：

```powershell
& 'D:\gradle-9.4.1\bin\gradle.bat' --offline --no-daemon -q `
  "-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" `
  :app:assembleRelease
```

要点：
- `--no-daemon` 让 JVM 构建完即退出，不常驻
- `-Dorg.gradle.jvmargs=...` 覆盖 `gradle.properties` 里的 `-Xmx2g`
- 先 `gradle --stop` 清掉已有 daemon
- **不要**加 `--offline` 跑 release 的 `produceReleaseComposeMapping`（会失败）；
  但本项目 `compose-group-mapping` 已进缓存，`--offline` 可用

---

## 六、仍未完成

| 项 | 状态 |
|---|---|
| 三条修复的真机回归 | **未验证**（等用户装机反馈） |
| 融合设备中心静默吞异常（v1 验收 P0-2） | **未修** —— `MiLinkServiceHook` 里 31 处 `runCatching`、11 处 `onFailure` 只写日志，用户侧无可见反馈 |
| Mate 自定义 EQ 写入 | Key Max 上仍不生效（v1 已记录） |
| GAIA 老机型 / 自适应开关 | 仅静态验证，无对应硬件 |
| 超级岛电量刷新频率 | 依赖蓝牙侧 `ACTION_PODS_BATTERY_CHANGED`，无独立定时器 |
