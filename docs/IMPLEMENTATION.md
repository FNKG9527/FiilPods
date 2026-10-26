# 💡 实现原理解析

> 本文回答一个问题：**fiilpod 到底是怎么做到的？**
>
> 如果你只想看「怎么用」，请回到 [README](../README.md)；如果你想知道「每一处功能背后注入了什么、发了什么字节」，本文是入口。

---

## 📑 快速索引

| 你想了解 | 跳到 |
|---|---|
| 模块是怎么被加载进系统进程的 | [一、注入架构](#一注入架构) |
| 耳机是怎么连上的（两条通道） | [二、连接链路](#二连接链路spp--ble-gatt) |
| 降噪 / EQ / 空间音频怎么下发 | [三、耳机功能实现](#三耳机功能实现) |
| 系统设置页那张卡片是怎么塞进去的 | [四、设置页注入](#四设置页注入) |
| 超级岛与融合设备中心怎么联动 | [五、系统级联动](#五系统级联动) |
| 弹窗、图片、字体这些怎么做的 | [六、模块功能实现](#六模块功能实现) |
| 完整协议字节表 | [PROTOCOL_FIIL.md](PROTOCOL_FIIL.md) |
| 各类缺陷的根因记录 | [FIX_V2_1.md](FIX_V2_1.md) · [FIX_V2_MODES.md](FIX_V2_MODES.md) · [FIX_NOT_CONNECTED.md](FIX_NOT_CONNECTED.md) · [FIX_CRASH_PARCELABLE.md](FIX_CRASH_PARCELABLE.md) |

---

## 一、注入架构

fiilpod 是一个 **LSPosed 模块**，基于 [libxposed](https://github.com/libxposed/api) **API 102** 的 `XposedModule` / `XposedService` 新接口，不是老式的 `IXposedHookLoadPackage`。

### 1.1 入口

```
META-INF/xposed/module.prop   →  minApiVersion=102  targetApiVersion=102  staticScope=true
META-INF/xposed/java_init.list →  moe.chenxy.fiilpods.hook.HookEntry
assets/xposed_init            →  同上（兼容旧加载器）
```

`HookEntry : XposedModule()` 的 `onPackageLoaded(param)` 在**每个作用域进程**被调用一次：

```kotlin
override fun onPackageLoaded(param: PackageLoadedParam) {
    if (!param.isFirstPackage) return
    installHooksForPackage(param.packageName, param.defaultClassLoader)
}
```

### 1.2 四个作用域各干一件事

| 作用域进程 | Hook 类 | 职责 |
|---|---|---|
| `com.android.bluetooth` | `BluetoothProcessHook` | **核心**：持有 RFCOMM / GATT 连接，收发协议帧，广播状态 |
| `com.android.settings` | `SettingsHook` | 在蓝牙详情页注入原生卡片与自建控制卡 |
| `com.xiaomi.bluetooth` | `MiBluetoothToastHook` | 超级岛强提示、焦点通知 |
| `com.milink.service` | `MiLinkServiceHook` | 融合设备中心耳机卡片联动 |

> **为什么必须拆成四个进程？** 这四类 UI 各自跑在不同进程里，跨进程只能靠广播或 binder。把连接对象放在蓝牙进程，是因为只有它拿得到 `BluetoothAdapter`（`BLUETOOTH_CONNECT` 系统权限也在它手上）。

### 1.3 远端偏好：让用户设置穿透到系统进程

模块 App 里改的「焦点通知开关」「超级岛来源」写在模块自己的 SharedPreferences 里，系统进程读不到。所以 `HookEntry` 用 LSPosed 的 `getRemotePreferences()` 把同一份配置映射进每个注入进程：

```kotlin
// HookEntry.kt
private fun prepareRemotePrefs(): SharedPreferences? =
    runCatching { getRemotePreferences(ConfigManager.PREFS_NAME) }.getOrNull()

// HookContext.kt —— 每个 Hook 装配时注入
fun attach(prefs: SharedPreferences?, hostPackage: String): HookContext {
    this.packageName = hostPackage
    if (prefs != null) {
        this.prefs = prefs
        ConfigManager.init(prefs)
        prefs.registerOnSharedPreferenceChangeListener { sp, _ -> ConfigManager.refreshFromPrefs(sp) }
    }
    return this
}
```

**这是 v2.1.0 修的一个真缺陷**：早期版本完全没有这段，导致用户在设置页把「超级岛来源」改成「模块」后，蓝牙进程里的 `ConfigManager` 仍是默认值，焦点通知永远不弹。详见 [FIX_V2_1.md](FIX_V2_1.md#问题-3焦点通知不显示)。

---

## 二、连接链路：SPP 与 BLE GATT

### 2.1 两条通道并存的现实原因

FIIL 耳机同时开了两套私有通道：

| 通道 | UUID | 特点 |
|---|---|---|
| 标准 SPP | `00001101-0000-1000-8000-00805F9B34FB` | 老机型主通道 |
| 自定义 SPP | `d52b47bc-2dea-42c4-b26d-20252fceeeb3` | F045~F053 专属 |
| BLE GATT | 写 `0000ff17-...` / 通知 `0000ff18-...` | 全机型可用，不占音频链路 |

帧格式在三条通道上**完全一致**，所以协议层对传输层无感。

### 2.2 一个必须知道的坑：socket 创建成功 ≠ 建链成功

```kotlin
// ❌ 错误写法（v1 的 bug）
val sock = device.createRfcommSocketToServiceRecord(UUID_SPP)
return sock          // 创建成功就返回 → 但根本没连

// ✅ 正确写法（openSocket）
val candidates = listOf(
    device.createRfcommSocketToServiceRecord(UUID_SPP)        to false,
    device.createRfcommSocketToServiceRecord(UUID_MATE)       to true,
    createRfcommSocket(1) /* 反射兜底 */                       to false,
)
for ((sock, isCustom) in candidates) {
    try { sock.connect(); return sock }        // 真正建链在这里
    catch (e: IOException) { sock.close() }    // 失败就换下一条
}
```

`createRfcommSocketToServiceRecord()` 只创建一个 socket **对象**，不做 SDP 查询、不报错。必须先 `connect()` 才知道这条通道通不通。v1 因为「创建成功就 return」，Mate 机型永远卡在标准 SPP 上 —— 那条通道能连上，但耳机一句话都不回。

### 2.3 静默探测与自动降级

连上之后不代表通了。模块会起一个 3.5 秒的探测器：

```
连接成功 → 发 setUnlock + 查询帧 → 3.5 秒内没收到任何字节？
    ├─ 是 → 判定「连错通道」→ 断开 → 换另一条通道重连（最多 2 次）
    └─ 否 → 标记 lastRxAt，进入正常收发
```

如果 SPP 三条全失败，自动降级到 BLE GATT。GATT 也静默，再翻回来。完整状态机见 `pods/FiilRfcommController.kt` 的 `scheduleSilenceProbe()`。

### 2.4 启动补偿枚举（最容易漏的一环）

`A2dpService.handleConnectionStateChanged` 只在状态**变化**时回调。如果模块是在耳机已经连好之后才被 LSPosed 加载（很常见：先连耳机、再启用模块，或升级后蓝牙进程重启），这个回调永远不会来。

所以 `BluetoothProcessHook` 在进程启动后主动扫三次（0ms / 1.5s / 5s）：

```kotlin
// 反射拿系统 A2dpService 的已连接列表
private fun enumerateA2dpConnected(ctx: Context): List<BluetoothDevice> {
    val adapterService = /* 反射 sAdapterService 或 getA2dpService() */
    val a2dp = /* 多级兜底：getA2dpService() → sA2dpService 字段 → AdapterService 实例方法 */
    return a2dp.javaClass.getMethod("getConnectedDevices").invoke(a2dp) as List<BluetoothDevice>
}
```

> Android 17 上 `A2dpService.sA2dpService` 静态字段已被移除，必须走 `AdapterService.getA2dpService()`。这段三级兜底就是为跨版本兼容写的。详见 [FIX_NOT_CONNECTED.md](FIX_NOT_CONNECTED.md)。

---

## 三、耳机功能实现

所有耳机控制最终都收敛到 `pods/FIILProtocol.kt` 的一组语义方法，由它按协议族分流到 `FiilMate`（F045~F053）或 `FiilGaia`（老机型）。

### 3.1 协议族自动识别

```kotlin
// FiilRfcommController.readLoop → 收到第一帧后判定
val isMate = frame.size > 2 && frame[1] == 0x27.toByte()   // cid=39
// 同时用机型表兜底：FiilModels.MATE_EAR_TYPES = setOf(45,46,47,48,49,51,53)
```

判定不确定时（`earType == -1`），`handshake()` 会**两套协议各发一发**，谁先回包就定谁。

### 3.2 降噪（MAF 五态）

| 协议 | cid | cmdId | payload |
|---|---|---|---|
| Mate SET | `44` | `0` | `[状态]` |
| Mate GET | `39` | `12` | `[]` |
| GAIA SET | `0x0201` | — | `[状态]` |

状态线值：`0`=关闭 / `1`=降噪 / `2`=开放 / `3`=兼听 / `4`=风中。

下发后**必须等回读**：耳机处理需要约 0.3~1.5 秒，SET 的 ack 立刻返回并不代表生效。模块的 `pendingOps` 表会在 2.5 秒内等状态通知帧，超时则回退 UI 并提示。

### 3.3 EQ

两组数据：12 组预设（`cid=32`）+ 10 段自定义图形 EQ。

自定义 EQ 的 payload 布局是 `[保留][类型][10 段增益]`，每段一字节，`0x00`=0dB，`0xFA`=-6dB（补码）。频段固定为 `31 / 63 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k` Hz。

> ⚠️ 不同机型的段数不同（F046 是 10 段，F042 是 10 段但频点不同，F045 是 5 段，F049 是 8 段）。段数由 `FiilModels` 按 `earType` 决定，写错段数会导致 EQ 不生效而非报错。

### 3.4 空间音频（影院模式）

`cid=50`，`cmdId=0`，payload `[0 或 1]`。只有 `earType == 46`（Key Max）等少数机型支持。

### 3.5 低延迟

Mate 机型是 `cid=37`、`cmdId=0`、payload `[档位]`；GAIA 机型走 16 位命令号。三档线值：`0`=音乐 / `1`=游戏 / `2`=视频。

### 3.6 语音提示音量

`cid=1`、`cmdId=3`、payload `[档位]`。**线值与直觉相反**：`0`=最响（高）、`3`=静音。

### 3.7 蓝牙畅连（多点）

`cid=1`、`cmdId=6`、payload `[动作码]`。切换时会**主动断开 BLE 链路**（固件行为），模块为此专门做了重连补偿：检测到由畅连切换引发的断链后，2 秒内自动重连。

---

## 四、设置页注入

这是整个项目里**技术难度最高**的一块。

### 4.1 为什么不用 Compose

`SettingsHook` 跑在 `com.android.settings` 进程里，由模块的 ClassLoader 加载。Compose / Miuix 需要 Resources、字体、主题，而这些在宿主进程里**互相不可见**，跨 ClassLoader 加载必然崩。

所以 `SettingsControlCard.kt` 全部用 **framework 原生控件**：

```kotlin
LinearLayout / TextView / Switch / GradientDrawable / HorizontalScrollView
```

### 4.2 注入点

```kotlin
// SettingsHook.kt
findMethod(CLS_DEVICE_PROFILES, "onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
    .hookAfter { /* param.result 是整棵 View 树 */ }
```

拿到 View 树后：

1. **BFS 找竖直容器**：`findVerticalContainer(root)` —— 必须钻过 `ScrollView`，否则 `addView` 会抛 `ScrollView can host only one direct child`
2. **幂等挂载**：用 `View.generateViewId()` 生成 id 并在 `findViewById` 查重。**不能用 `findViewWithTag`** —— 它只比对普通 tag，keyed tag 永远查不到，页面重建时卡片会重复叠加
3. **延迟重试**：`onCreateView` 刚 inflate 完时容器常常还是空的，所以在 `onServiceConnected` 里再挂一次

### 4.3 回声防护

`Switch.setOnCheckedChangeListener` 在代码里赋值 `isChecked` 时**同样会回调**。不屏蔽就会「回填 → 下发 → 回包 → 再回填」死循环：

```kotlin
private class BindingFlag { var active: Boolean = false }

fun bind(card: View?, status: FiilStatus?) {
    flag.active = true
    try { switch.isChecked = status.cinemaMode }
    finally { flag.active = false }
}

switch.setOnCheckedChangeListener { _, checked ->
    if (flag.active) return@setOnCheckedChangeListener   // 回填时忽略
    dispatch("cinema", if (checked) 1 else 0, null)
}
```

---

## 五、系统级联动

### 5.1 超级岛 / 强提示

两条通道，互不干扰：

```kotlin
// 通道一：HyperOS 强提示 toast（StatusBarManager 反射）
val sbManager = context.getSystemService("statusbar")
val m = sbManager.javaClass.getMethod("setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
m.invoke(sbManager, 1, "strong_toast_action", bundle)
```

```kotlin
// 通道二：焦点通知（标准 Notification，常驻超级岛）
// FocusIslandUtil.kt
val channel = "fiilpods_battery_v2"       // ⚠️ 渠道重要性创建后不可修改，改配置要换 id
NotificationChannel(channel, "耳机电量", IMPORTANCE_LOW).apply { /* ... */ }
nm.notify(10086, builder.build())
```

`island_param` 是一个独立 extras key，与 `param` 并列 —— 早期实现把两者混用，导致强提示能弹但超级岛不显示。

### 5.2 融合设备中心（`com.milink.service`）

注入控制中心的耳机卡片。核心是 hook 两个方法：

```kotlin
hookAncCommand(className, methodName, maf, result)   // 用户点卡片切降噪
hookAncStateBlock()                                   // 耳机主动上报降噪状态
```

两者都做三件事：改成员 `ancMode` → 广播给蓝牙进程下发命令 → 通知控制中心刷新 `UPDATE_ANC`。

**v2.1.0 修的坑**：只做上面三件事的话，控制中心重绘时读到的仍是内存里的旧字段，用户要**再点一次**图标才对。所以补了 `syncAncModel()`：

```kotlin
// 按 ROM 字段名漂移依次回写
for (name in listOf("ancState", "bluetoothAncState", "miLinkAncState")) {
    runCatching { setObjectField(model, name, newValue) }
}
notifyPropertyChanged(controller, device, UPDATE_ANC)   // 二次推送
```

### 5.3 状态如何从蓝牙进程传到 UI

UI 是从 OppoPods 整份移植的，只认 `chen.action.oppopods.*` 那套广播契约。而 FIIL 侧原本用 `moe.chenxy.fiilpods.action.*`。

`pods/OppoCompatBridge.kt` 负责翻译：

```
FiilStatus ──► publishBattery / publishAnc / publishWear / publishGameMode / publishSpatial / publishEq / ...
                        │
                        └─► Intent("chen.action.oppopods.xxx").setPackage("moe.chenxy.fiilpods")
```

> **踩过的坑**：这里跑在蓝牙进程里，`ctx.packageName` 是 `com.android.bluetooth`。早期写 `.setPackage(ctx.packageName)` 会把广播绕回蓝牙进程自己，UI 一条都收不到，界面永远显示未连接。

---

## 六、模块功能实现

| 功能 | 实现方式 | 文件 |
|---|---|---|
| **快捷弹窗** | 独立 Activity，`Theme.FiilPods.Popup` 半透明主题；竖屏三卡纵排 / 横屏 0.6:0.4 分栏 | `PopupActivity.kt` |
| **超级岛卡片图片** | 本地 PNG 存在 `filesDir/pods/`，通过自定义 `ContentProvider` 暴露 `content://` URI | `config/PodImageProvider.kt` |
| **协议调试页** | 环形缓冲区记录所有 TX/RX，由蓝牙进程写入、调试页通过广播拉取 | `pods/RfcommLog.kt` + `ui/pages/RfcommDebugPage.kt` |
| **手动选型** | 写入 `FiilConfig` 偏好（含「MAC → earType」地址绑定），下次连接直接生效 | `pods/FiilConfig.kt` |
| **主题 / 语言** | Miuix `ThemeController` + 自建 `AppLocale`（`createConfigurationContext`） | `ui/Theme.kt` / `ui/AppLocale.kt` |

### 6.1 跨进程通信的两种方式

| 方式 | 用途 | 注意 |
|---|---|---|
| **广播** | UI ↔ 蓝牙进程的命令与状态 | 必须 `.setPackage()` 显式指定，否则可能被拦或绕回自己 |
| **远端 SharedPreferences** | 配置穿透 | 只在 `HookEntry` 里初始化，后续通过变更监听刷新 |

### 6.2 为什么不用 AIDL

AIDL 需要在宿主进程里注册 service，且每次系统升级都可能被 ROM 改签名。广播虽然慢一点（毫秒级），但**跨 ROM 版本稳定**，且模块侧不需要额外权限。

---

## 七、相关文档

| 文档 | 内容 |
|---|---|
| [PROTOCOL_FIIL.md](PROTOCOL_FIIL.md) | 完整协议字节表：UUID、帧格式、190 条 GAIA 命令、Mate cid/cmd 全表 |
| [DEVICE_TEST.md](DEVICE_TEST.md) | 真机回归记录（19 项用例） |
| [FIX_NOT_CONNECTED.md](FIX_NOT_CONNECTED.md) | 「检测不到已连接耳机」的九处根因 |
| [FIX_CRASH_PARCELABLE.md](FIX_CRASH_PARCELABLE.md) | 跨进程 Parcelable 闪退的三层修复 |
| [FIX_V2_MODES.md](FIX_V2_MODES.md) | 「电量能看但模式切不了」的六个缺陷 |
| [FIX_V2_1.md](FIX_V2_1.md) | 设备中心图标滞后 / 服务心跳 / 焦点通知三处修复 |
| [ARCH_OPPOPODS.md](ARCH_OPPOPODS.md) | OppoPods 架构分析（移植依据） |
| [ACCEPTANCE_REPORT.md](ACCEPTANCE_REPORT.md) | 6 角色联合验收报告 |

---

<div align="center">

[⬆ 回到 README](../README.md)

</div>
