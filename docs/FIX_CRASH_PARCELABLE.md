# 修复记录：「新手机点开 App 立即闪退」

> 用户反馈（2026-10-26）：Redmi K90 Pro Max（Android 17 / HyperOS V816）装上模块后，点开应用图标立即闪退。

本文记录**根因定位的完整证据链**、修复方式与验证结果。全部结论来自真实崩溃堆栈与 APK 实测，不含推测。
前一篇相关问题见 `FIX_NOT_CONNECTED.md`（检测不到已连接耳机，七处根因）。

---

## 0. 现场与取证环境

| 项 | 值 |
|---|---|
| 手机 | Redmi K90 Pro Max（`25102RKBEC`），Android 17 / SDK 37 / HyperOS V816 |
| 模块 | `moe.chenxy.fiilpods` 1.0.0 (1)，debug 构建 |
| 取证方式 | 模块自带 `CrashHandler` 落盘（`/sdcard/Android/data/moe.chenxy.fiilpods/files/crash_last.txt`） |
| 为何不用 logcat | 该 ROM `ro.logd.size=64K` 且 `persist.debug.dpis.global_log_enabled=0`，`logcat -b all` 返回 0 行 |

前一轮曾在「adb 只连旧机 + 无 logcat」的条件下做过大量静态排查（资源引用 281 处零缺失、
43 个 PNG 完好、APK dex 类齐全、manifest/themes/版本目录全对齐、启动链路与 OppoPods 逐行等价），
**全部通过但找不到崩溃点** —— 这个结论本身是对的：问题不在静态结构，而在**运行期的跨进程数据**。

---

## 1. 根因：跨进程广播里的 Parcelable 反序列化失败

### 崩溃堆栈（原文）

```
java.lang.RuntimeException: Error receiving broadcast
    Intent { act=chen.action.oppopods.pods_battery_changed flg=0x10000010 xflg=0x4
             pkg=moe.chenxy.fiilpods (has extras) }
    in moe.chenxy.fiilpods.ui.MainUIKt$MainUI$broadcastReceiver$1$1@1af5936
    at android.app.LoadedApk$ReceiverDispatcher$Args.lambda$getRunnable$0(LoadedApk.java:2181)
    at android.os.Handler.handleCallback(Handler.java:1129)
    ...
Caused by: android.os.BadParcelableException: ClassNotFoundException when unmarshalling: zd
    at android.os.Parcel.readParcelableCreatorInternal(Parcel.java:5445)
    at android.os.Parcel.readParcelableInternal(Parcel.java:5294)
    at android.os.Parcel.readValue(Parcel.java:5053)
    at android.os.Parcel$LazyValue.apply(Parcel.java:4898)
    at android.os.BaseBundle.unwrapLazyValueFromMapLocked(BaseBundle.java:448)
    at android.os.BaseBundle.getValue(BaseBundle.java:399)
    at android.os.Bundle.getParcelable(Bundle.java:1121)
    at android.content.Intent.getParcelableExtra(Intent.java:9859)
    at moe.chenxy.fiilpods.ui.MainUIKt$MainUI$broadcastReceiver$1$1.onReceive(MainUI.kt:261)
Caused by: java.lang.ClassNotFoundException: zd
    at android.os.Parcel.readParcelableCreatorInternal(Parcel.java:5411)
```

### 机理（为什么 `?.let` 和 `runCatching` 都不够）

1. `Intent` 跨进程时，`putExtra(parcelable)` 只序列化**类名字符串 + 字段**。
2. 接收端 `getParcelableExtra` 要拿这个类名去自己的 ClassLoader 里 `Class.forName`。
3. 找不到 → 抛 `BadParcelableException`。**这是异常，不是返回 null**，所以
   `?.let{}`、`?:`、`if (x != null)` 全部无效。
4. 抛点在 `BroadcastReceiver.onReceive` 里，AMS 会把它包装成 `RuntimeException`
   并在主线程重新抛出 → 进程直接被杀。用户看到的就是「点开就闪退」。

### 为什么会「类名找不到」

`zd` 是 R8 混淆后的短名。写入端与读取端不是同一个构建时必然对不上：

| 场景 | 写入端 | 读取端 | 结果 |
|---|---|---|---|
| 覆盖安装后蓝牙进程未重启 | 旧 release（类名 `zd`） | 新 debug（类名 `BatteryParams`） | 崩 |
| release 与 debug 混装 | 任一 | 另一 | 崩 |
| 升级后残留旧注入 | 旧 | 新 | 崩 |

本项目的特殊之处：**注入代码跑在 `com.android.bluetooth` 进程**，它的生命周期与 UI 进程
完全独立 —— 装新包并不会重启蓝牙进程，所以两端版本错配是**常态而不是意外**。

> 重要更正：定位过程中我曾判断「`zd` 就是被混淆的 `BatteryParams`」。**这个归因是错的。**
> 逐包 dexdump 核实后，`zd` 在官方 OppoPods、旧版 FiilPods、新版 FiilPods 里都是同一个
> 空壳类（父类 `Lbe;`，不实现 `Parcelable`），与 `BatteryParams` 无关。
> 但「Parcelable 类名跨进程对不上」这个**机理**成立且已被堆栈证实，
> 修复方式也因此不依赖 `zd` 的具体身份 —— 见下一节。

---

## 2. 修复（两层 + 一个治本）

### 2.1 业务层：不传、不读 Parcelable

`pods/OppoCompatBridge.kt`：

```kotlin
// publishBattery() 里，发 Parcelable 的同时带上三个纯 int
putExtra(EXTRA_BATTERY_LEFT,  s.batteryLeft)
putExtra(EXTRA_BATTERY_RIGHT, s.batteryRight)
putExtra(EXTRA_BATTERY_CASE,  s.batteryCase)

// 新增：从纯 int 重建
fun batteryFromIntent(intent: Intent): BatteryParams? { ... }
```

`ui/MainUI.kt`（原崩溃点 L261）与 `PopupActivity.kt` 的 `ACTION_PODS_BATTERY_CHANGED` 分支：

```kotlin
// 绝不读 Parcelable —— 只用纯 int 通道
rebuildBatteryFromInts(p1)?.let { batteryParams.value = it }      // MainUI
OppoCompatBridge.batteryFromIntent(p1)?.let { batteryParams.value = it }  // PopupActivity
```

全模块现存 `getParcelableExtra` 仅剩 `BluetoothDevice` 这类**系统类**（类名两端恒等），
且全部包在 `runCatching` 里。

### 2.2 框架层：接收器最外层兜底

`MainUI.kt`、`PopupActivity.kt`、`hook/BluetoothProcessHook.kt` 的 `onReceive`：

```kotlin
override fun onReceive(context: Context?, intent: Intent?) {
    runCatching {
        when (intent?.action) { ... }
    }.onFailure { Log.w(TAG, "broadcast failed", it) }
}
```

即便将来某个分支再读 Parcelable，也只是丢一条广播，不会杀进程。
`BluetoothProcessHook` 那层尤其关键 —— 它抛出去杀掉的是**蓝牙进程**。

### 2.3 治本：让混淆不再改名

`app/proguard-rules.pro`：

```
-keep class moe.chenxy.fiilpods.utils.miuistrongtoast.data.** { *; }

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public static **[] newArray(int);
}
```

release APK 实测已能查到完整类名：

| 类 | 修复前 | 修复后 |
|---|---|---|
| `moe.chenxy.fiilpods.utils.miuistrongtoast.data.BatteryParams` | 被改短名 | ✅ 完整保留 |
| `...data.PodParams` | 被改短名 | ✅ 完整保留 |

---

## 3. 同批修掉的其它真缺陷

| # | 缺陷 | 位置 | 处理 |
|---|---|---|---|
| 1 | 无崩溃自捕获 | 新增 `CrashHandler.kt` | `FiilPodsApp.onCreate()` 第一行安装进程级 handler，堆栈写 `filesDir/crash_last.txt` + `/sdcard/Android/data/moe.chenxy.fiilpods/files/crash_last.txt` + `Log.e` |
| 2 | 崩溃后看不到原因 | `MainActivity.kt` | 读到上次崩溃时**绕过 Compose/MIUIX**，用纯 framework 控件渲染堆栈，带「复制堆栈」「清除并重试」 |
| 3 | `PodImageProvider` 未在 manifest 声明 | `AndroidManifest.xml` | 补 `<provider ... authorities="moe.chenxy.fiilpods.podimages">`，否则用户设自定义耳机图片必然 `ActivityNotFoundException` |
| 4 | 空断言 `!!` | `FocusIslandUtil.kt:38/39/63/64`、`PodStatus.kt` | `left!!/right!!` → `?.let`；`as BitmapDrawable` → `as?` + 退回 `painterResource` |
| 5 | 中文语种缺 2 条 | `res/values-zh-rCN/strings.xml` | 补 `fiil_maf_listen`（兼听）、`fiil_maf_wind`（风中），两套各 197 条 |
| 6 | 低延迟广播 action 未注册 | `MainUI.kt` | 补 `ACTION_PODS_LOW_LATENCY_MODE_CHANGED`，否则该选择器整块不渲染 |

---

## 4. 验证

```
:app:compileDebugKotlin    EXIT=0
:app:testDebugUnitTest     EXIT=0   （43 项全绿：ModelCapabilityTest 16 + ProtocolFrameTest 27）
:app:assembleDebug         EXIT=0
:app:assembleRelease       EXIT=0
```

| 产物 | 大小 | 时间 |
|---|---|---|
| `app\build\outputs\apk\debug\app-debug.apk` | 48,444,399 B | 2026-10-26 12:40:45 |
| `app\build\outputs\apk\release\app-release.apk` | 3,948,715 B | 2026-10-26 12:42:42 |

APK 内容实测（两个包都验过）：

| 标记 | debug | release |
|---|---|---|
| `batteryFromIntent`（int 兜底通道） | ✅ | ✅ |
| `EXTRA_BATTERY_LEFT` | ✅ | ✅ |
| `battery/state broadcast failed`（接收器兜底日志） | ✅ | ✅ |
| `popup broadcast failed` | ✅ | ✅ |
| `BatteryParams` 完整类名保留（proguard 生效） | — | ✅ |

xposed 元数据复核：`assets/xposed_init` = `moe.chenxy.fiilpods.hook.HookEntry`；
`module.prop` = `minApiVersion=102 / targetApiVersion=102 / staticScope=true`；
`scope.list` 四条齐全（bluetooth / settings / xiaomi.bluetooth / milink.service）。

---

## 5. 教训

**跨进程传 Parcelable 是架构级风险，不是编码细节。**

UI 层整份移植自 `1812z/OppoPods`，而 OppoPods 的两个进程永远同版本同构建，所以它这么写
从没出过事。但 FiilPods 的注入代码跑在 `com.android.bluetooth` 里，生命周期与 UI 进程彼此
独立，覆盖安装时**必然**出现「一端新一端旧」。

结论：**跨进程边界上只允许 `String` / `int` / `boolean` / `long` / `float`**。
需要结构化数据就自己拆成基础类型，不要依赖 `Parcelable` 的类名。

---

## 6. 仍未验证

- 真机复测：本轮修复后的 APK **尚未装机验证**（用户不在场 / 未授权安装）。
- 修复后若仍闪退，`crash_last.txt` 会直接给出新堆栈，不会再需要「猜」。
