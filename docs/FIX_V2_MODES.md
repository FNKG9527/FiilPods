# v2.0.0 —— 「电量能显示、模式切不了」与相关缺陷的修复记录

> 对应问题（用户原话）：
> 「目前debug 电量可以正常显示 任何无法通过模块进行切换模式 但是使用耳机实体按键切换模式
> 软件可以识别到模式的变化就是无法通过软件切换 外加能通过软件调整语言提示音量这个可以生效
> 双设备链接功能正常 怎么通过软件抓取这个软件的日志？」

这份文档记录 v2.0.0 修的 6 个真实缺陷，每一条都有源码位置与判定依据。

---

## 0. 一句话结论

**接收链路是通的，发送链路是断的。**

耳机实体按键切换 → 耳机主动推 `cid=40 NOTIFY` → 模块收到并更新界面（所以「软件能识别到模式变化」）。
模块点按钮 → `setAnc()` 组的是 **GAIA 帧**（`FF 01 00 01 02 34 02 01 xx`）→ 但 Key Max 是
**Mate 协议族**，只认 `cid=44` 的 Mate 帧 → 耳机把 GAIA 帧直接丢弃，连个错误回包都没有。

语音提示音量、畅连之所以能生效，是因为它们恰好走的是 Mate `cid=1` 那条路（v1 实现里
已经用了 `sendMate`），没有被这个缺陷覆盖到。

---

## 1. 缺陷 #1 —— `sendMate()` 没有 GATT 分支（最致命）

**位置**：`app/src/main/java/moe/chenxy/fiilpods/pods/FiilRfcommController.kt:1480`

**现象**：模块通过 **BLE GATT**（`ff17` 写 / `ff18` 收）连上耳机时，`sendMate()` 把帧丢进
`writeQueue`（那是 SPP 的 socket 写队列），而 GATT 通道其实走 `FiilGatt.send()`。
结果：**所有 Mate 命令一个字节都没真正发出去。**

**为什么 v1 没被发现**：v1 的整条真机验证都在 PC 端（Python/Bleak 与 C#/RFCOMM）上做的，
模块自己的 Android 侧「Android 侧零实机验证」被列为 P1-1 风险 —— 这条就在风险的盲区里。

**修法**：

```kotlin
// GATT 与 SPP 的帧编码完全一致，只有传输方式不同 —— 见 FiilGatt。
if (usingGatt) {
    FiilGatt.send(frame)
} else {
    enqueue(frame)
}
```

同一处也补了 `RfcommLog.d(appContext, "RFCOMM/TX", "MATE " + hex)`，方便在调试页看到每一帧。

---

## 2. 缺陷 #2 —— `setAnc()` / `setEq()` / `set3D()` 无条件按 GAIA 组帧

**位置**：`pods/FiilRfcommController.kt:1544`（`setAnc`）、`:1563`（`setEq`）

**判定依据**（逆向一手源码）：

| 命令 | GAIA 命令号 | Mate 语义 |
|---|---|---|
| 降噪 | `SET_ANC = 0x0201` = 513 | `cid=44`（`SET_MAF`），`cmdId=0`，payload `{mode}` |
| EQ | `SET_EQ = 0x0203` = 515 | `cid=32`，`cmdId=0`，payload `[保留][类型][N段]` |
| 3D 音效 | `SET_3D = 0x0204` = 516 | 无对应命令（Mate 的「影院」是 `cid=50`） |

来源：`<fiil_analysis>\jadx_out\sources\com\fengeek\main\i\d\f.java` 的 setter 全表
（详见 `PROTOCOL_FIIL.md` §3.12）。

**修法**：`setAnc()` 改成按 `isMateDialect && FiilModels.supportsMateMaf(earType)` 分流到
`sendMate(FiilMate.Cid.SET_MAF, 0, byteArrayOf(mode))`；`setEq()` 在 Mate 且没有 EQ 曲线时
打明确警告而不是「安静地发一帧废帧」。

「是否支持 cid=44」这一层门控是必要的：只有 **F046 / F049 / F051 / F053** 实现了 `setMAF`
（`F045` GS Lite / `F047` Atom / `F048` GS Links 本身不带降噪，命令类里连 `setMAF` 都没有）。
见 `FiilModels.supportsMateMaf()`。

---

## 3. 缺陷 #3 —— ANC 档位显示错位（兼听→自适应、风中→深度降噪）

**位置**：`pods/OppoCompatBridge.kt:101` 的 `ancStatusForUi()`

**机制**：`OppoCompatBridge` 把 FIIL 的 MAF 值翻译成 OppoPods UI 认识的状态码。
早期实现把「兼听」映射成 **4**、「风中」映射成 **8**，而 UI 侧 `MainUI.kt` 的
`when(status)` 里 4 = `ADAPTIVE`、8 = `NOISE_CANCELLATION_DEEP` —— 那是 OPPO 的档位语义。

结果：功能其实是对的（耳机确实切到了兼听/风中），但**界面上高亮的是错的按钮**，
用户会以为「切错了」。

**修法**：改用 **9 / 10** 两个在 OPPO 语义里没有定义的码，并在 `MainUI.kt:261-262` 加对应分支：

```kotlin
// FIIL 专有档位：9=兼听、10=风中。
// 不能复用 4/8 —— 那两个是 OPPO 的 ADAPTIVE / DEEP，
// 复用会让「兼听」高亮成「自适应」（见 OppoCompatBridge 注释）。
9 -> NoiseControlMode.LISTEN
10 -> NoiseControlMode.WIND
```

`AncSwitch.kt:147-150` 也补了 `LISTEN` / `WIND` 的图标与文案（`fiil_maf_listen` / `fiil_maf_wind`）。

---

## 4. 缺陷 #4 —— `onGattReady()` 无条件认定 Mate

**位置**：`pods/FiilRfcommController.kt:579`

**背景**：`F042` / `CC Pro2` / `CC nano`（earType 39/40/42）在 `FiilModels.BLE_ONLY` 里 ——
**它们只有 BLE，SPP 连不上**，但走的仍然是 **GAIA 帧**。

**缺陷**：`onGattReady()` 写死 `isMateDialect = true`。等于给这些机型发它们完全不认识的
Mate 帧 —— 表现是「连上了、电量有、其他全无响应」，比连不上更难查。

**修法**：

```kotlin
isMateDialect = if (earType > 0) FiilModels.isMateDialect(earType) else true
```

型号已知就按型号判；型号未知（`earType == -1`，用户改过蓝牙名或不在 `NAME_RULES` 里）才退回
`true` —— 因为 GATT 这条通道上 Mate 机型占多数，且 `handshake()` 在型号未知时本来就两套协议
各打一发，会由首个回包自行定论。

---

## 5. 缺陷 #5 —— 调试页日志通道整条是死的

**位置**：`hook/BluetoothProcessHook.kt:292-294`、`:402-420`

**现象**：`RfcommDebugPage.kt:86` 一进页面就发 `ACTION_RFCOMM_LOG_CONNECT`，退出时发
`ACTION_RFCOMM_LOG_DISCONNECT`，清空时发 `ACTION_RFCOMM_LOG_CLEAR`。
但蓝牙进程的 `registerReceiver` **只注册了命令类 action，这三个一个都没注册** ——
用户点「开始」不会有任何日志，会以为「日志功能没做」。

**修法**：补注册三个 action，并把分发接到 `RfcommLog`：

| action | 处理 |
|---|---|
| `ACTION_RFCOMM_LOG_CONNECT` | `RfcommLog.setListener(ctx, true)` —— 开始把 TX/RX 帧推给 UI |
| `ACTION_RFCOMM_LOG_DISCONNECT` | `RfcommLog.setListener(ctx, false)` |
| `ACTION_RFCOMM_LOG_CLEAR` | `RfcommLog.clear(ctx)` |
| `ACTION_RFCOMM_DEBUG_SEND` | `FiilRfcommController.sendRawHex(hex)`，失败写日志 |

---

## 6. 缺陷 #6 —— 缺「发送原始 Hex」入口

**位置**：`ui/pages/RfcommDebugPage.kt:183-190`、`pods/FiilRfcommController.kt:1506`

**为什么需要**：逆向阶段最快的手段就是「发一串字节看耳机认不认」，不必改代码重装模块。
这也是 PC 端探针 `app.py` 的核心工具，移植时漏了。

**实现**：

```kotlin
fun sendRawHex(hex: String): Boolean   // 过滤空白/-/:，长度必须偶数，逐字符解析失败即拒
```

不经过任何组帧逻辑，用户输入什么就发什么。

---

## 7. 日志怎么取（三条路）

### 7.1 模块内调试页（推荐）

设置页 → 齿轮 Tab → 最下方 **「RFCOMM 调试」** → 点「开始」→ 复现问题 → 点「复制全部」。

日志行格式：

```
[12:34:56.789] RX  MATE 00 27 02 00 03 0C 01 01
[12:34:56.812] TX  MATE 00 2C 01 00 03 00 01
[12:34:57.001] INFO connected via BLE-GATT addr=... earType=46 mate=true
```

### 7.2 崩溃堆栈文件

```
/sdcard/Android/data/moe.chenxy.fiilpods/files/crash_last.txt
```

`CrashHandler.install()` 在 `FiilPodsApp.onCreate()` 第一行装上，捕获后写 `filesDir` +
`getExternalFilesDir(null)` + `Log.e`，并**仍然调用 previous handler**（不吞异常，
系统「应用已停止」照常弹出）。

### 7.3 App 内崩溃页

`MainActivity.onCreate()` 先 `CrashHandler.readLatest()`，非空则 `setContentView(buildCrashView(text))`
并直接 return —— **完全绕开 Compose / MIUIX**，避免崩溃点就在这两层里。页面带
「复制堆栈」与「清除并重试」两个按钮。

### ⚠️ 不要依赖 adb logcat

实测机 Redmi K90 Pro Max（Android 17 / HyperOS V816）：

```
ro.logd.size = 64K
persist.debug.dpis.global_log_enabled = 0
logcat -g → main: 64 KiB (63 KiB consumed, 0 B readable)
```

`logcat -d -b all -t 3000` 返回 **0 行**。这台机器上 logcat 取证是走不通的，所以才把
日志做进 App 里。

---

## 8. 构建与验证

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | **EXIT=0** |
| `:app:testDebugUnitTest` | **EXIT=0，49 项全绿**（`ModelCapabilityTest` 16 + `ProtocolFrameTest` 27 + `AddressMaskTest` 6） |
| `:app:assembleDebug` | **EXIT=0** → 35,697,303 B（16:16:37） |
| `:app:assembleRelease` | **EXIT=0** → 3,965,095 B（16:19:16） |
| versionCode / versionName | **2 / 2.0.0** |
| dex 关键串实测 | `UI_STATUS_LISTEN` ✓、`sendRawHex` ✓、`chen.action.oppopods.rfcomm_log_connect` ✓、`chen.action.oppopods.rfcomm_debug_send` ✓、`FiilPods-GATT` ✓、`maskAddress` ✓、明文 `B0:F1:A3:A8:7A` **已消失** ✓ |

产物同时归档到 `dist\`：

```
dist\FiilPods-v1.0.0-debug.apk     48,444,399 B
dist\FiilPods-v1.0.0-release.apk    3,948,715 B
dist\FiilPods-v2.0.0-debug.apk     35,697,303 B
dist\FiilPods-v2.0.0-release.apk    3,965,095 B
```

v1 与 v2 包名、签名都相同，可互相覆盖安装。

### 构建踩坑（本轮新增）

**Gradle daemon 被 OOM 杀掉**：

```
JVM crash log found: file:///<repo>/hs_err_pid4152.log
Native memory allocation (malloc) failed to allocate 1383376 bytes
```

这台机器 15.7 GB 物理内存，构建时只剩 2.5 GB 空闲，daemon 堆 2 GB + metaspace 768 MB
再加上 Kotlin 编译守护进程，直接把 native heap 撑爆。解决：

```powershell
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m'
& 'D:\gradle-9.4.1\bin\gradle.bat' -q --no-daemon :app:assembleDebug
```

注意 `--no-daemon` 与 `-Dorg.gradle.jvmargs=...` 的写法：**直接跟在 `gradle.bat` 后面会被当成
任务名**（报 `Cannot locate tasks that match '.gradle.jvmargs=...'`），必须放 `GRADLE_OPTS`。

---

## 9. 顺带修掉的验收 P0-1（日志泄露 MAC）

结项验收报告的 P0-1 是「日志明文打印蓝牙 MAC，4 处」。v2 一并修掉，且因为 v2 新增了
启动补偿枚举与 GATT 兜底，实际打日志的点位从 4 处涨到 5 处 —— 全部改走
`FiilConfig.maskAddress()`：

| 位置 | 改后 |
|---|---|
| `hook/BluetoothProcessHook.kt:169` | `adopt already-connected FIIL device ($source): ${FiilConfig.maskAddress(device.address)} ...` |
| `hook/BluetoothProcessHook.kt:217` | `FIIL device connected: ${FiilConfig.maskAddress(device.address)} ...` |
| `hook/SettingsHook.kt:167` | `patched $className intent for ${FiilConfig.maskAddress(device?.address)}` |
| `pods/FiilRfcommController.kt:492` | `connected to ${FiilConfig.maskAddress(device.address)} ...` |
| `pods/FiilRfcommController.kt:559` | `gatt connect launched to ${FiilConfig.maskAddress(device.address)} ...` |
| `pods/FiilRfcommController.kt:584`（RfcommLog） | `connected via BLE-GATT addr=${FiilConfig.maskAddress(device.address)} ...` |
| `pods/FiilGatt.kt:138` | `gatt connecting to ${FiilConfig.maskAddress(device.address)}` |

脱敏规则（`pods/FiilConfig.kt` 新增 `fun maskAddress(address: String?): String`）：

```
B0:F1:AA:BB:CC:DD  ->  B0:F1:**:**:**
```

保留前两段（OUI 高两字节）是因为排障要靠它判断芯片厂商，且正好与
`FIIL_OUI_PREFIXES` 的厂商判定对齐；后四段全部打码。null / 空串 / 段数不足
返回 `"?"` 或 `"**"`，不抛异常。

新增 `app/src/test/java/moe/chenxy/fiilpods/pods/AddressMaskTest.kt`（6 个用例）把这个规则钉死，
其中 `neverEmitsTheOriginalTail()` 会断言脱敏结果里不含原始后四段的任何一段。

release dex 实测：明文 `B0:F1:A3:A8:7A` **已不在包内**，`maskAddress` 在包内。

> 这与验收报告里「P0-1 待修」的结论不冲突 —— 报告是 v1 的验收结论，v2 修了它，
> 但**验收报告本身未回写**（那是 v1 的历史记录，保持原样更可信）。

---

## 10. v2 仍未解决的问题

| 项 | 状态 | 说明 |
|---|---|---|
| v2 的 MAF 切换真机验证 | **未验证** | 需用户装机后点一次 MAF 五档，看耳机是否真的变 |
| GAIA 老机型的 `setAnc` 分流 | 仅静态推导 | 无对应硬件（FIIL 老款） |
| Mate 自定义 EQ 写入 | 仍不生效 | Key Max 上 `SET_EQ(cid=32)` 回 ACK 但读回不变，v1 已记录，v2 未改 |
| 自适应四个开关 | 仅静态验证 | `cid=1` 的 `cmdId` 14/15/16/17，无真机确认 |
| 佩戴检测 | 仅静态验证 | `SET_WEAR(263)` 线值 1=开 / 2=关（不是 0/1），仅 F051/F053 支持 |
| 融合设备中心 hook 失效时的静默吞异常（验收 P0-2） | **未修** | `MiLinkServiceHook.kt` 31 处 `runCatching`，失败只写日志，用户无感知 |
