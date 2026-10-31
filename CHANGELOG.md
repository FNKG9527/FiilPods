# Changelog · 更新日志

本项目的所有重要变更都会记录在此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本 SemVer](https://semver.org/lang/zh-CN/)。

---

## [2.2.3] - 2026-10-31

修「图标不跟着耳机型号走」。两处独立的成因，都在**我们自己的 hook 上**，
跟小米官方的渲染逻辑无关：融合中心是把 FIIL 的假设备 ID 丢进了小米自家的编码判定表，
命不中恒返回 0；通知栏则是**根本没拿到 earType**，只能画通用图。

### Fixed

- **融合设备中心：不管接什么型号，图标永远是「普通蓝牙耳机」**

  链路两段，都在 `com.milink.service` 里：

  `BluetoothServiceClient.getHeadsetType(BluetoothDevice)`（`smali_classes2`，`:964`）
  方法体只有三句：取 `mxBluetoothManager` → `getDeviceId(device)` → `vg/a.b(String)` 拿 headset_type。
  而 `mxBluetoothManager.getDeviceId()` **早已被本项目 hook 成 `fakeDeviceId()`**
  （「伪装耳机型号」功能要用它），默认返回 `"01010607"`。

  `vg/a.b(String)` 是按**小米自家 6 位设备编码**做判定的：`n()`→7 头戴、`d()`→5、
  `e()`→6、`r()`→4、`s()`→2、`i()`→3、`k()`→8、`w()`→9，**全不命中 → 0**。
  `"01010607"` 是 FIIL 的假 ID，一条都不命中，所以恒返回 **0**。

  然后 `smali_classes3/.../world/utils/a0.smali:7` 的 `a(CirculateDeviceInfo)` 按
  `getInt("headset_type", 0)` 做 packed-switch：只有 **case 7** 才是
  `circulate_device_headset_headphones`（头戴式）；0 落到默认分支 → `circulate_headset_icon`。
  **这就是「融合中心永远是普通耳机图标」的完整成因。**

  修法：不伪造小米编码（那是小米的身份空间，改了会连带影响 `vg/a` 的其它判定，
  且用户还能自己改假 ID，一改又落空），改为直接挂
  `hookDeviceResult(CLS_BT_SERVICE_CLIENT, "getHeadsetType") { headsetTypeIcon() }`，
  按真实 earType 返回 **7（头戴）/ 3（普通）**，图标由官方 `a0.a()` 自己选 ——
  **用的就是小米官方那两张图**，与小米自家设备同一渲染路径，不会有「自己画一个像官方的图」的失真。

- **通知栏常驻卡片：右侧耳机图换型号也不变**

  `MiBluetoothToastHook.updateNotification()` 取图走的是
  `PodImageLoader.loadBoxBitmap()`，而它只有「用户自定义图 or `img_box.png`」两级，
  **没有型号这一级** —— 所以接 Diva、Key Max 还是 CC nano，右侧永远是同一张通用盒子图。

  更根本的是**通知进程拿不到 earType**：`updateNotification` 跑在 `com.xiaomi.bluetooth`，
  而 `earType` 是蓝牙进程 `FiilRfcommController` 的实例字段，两个进程不共享内存；
  `ACTION_PODS_BATTERY_CHANGED` 的 extras 里也从来没有这个键。

  修法三条：
  1. `pods/FiilAction.kt` 新增契约 `EXTRA_EAR_TYPE = "ear_type"`；
  2. `FiilRfcommController.publishState()` 把它随广播发出，
     并把去重键从 `"$left/$right/$case"` 改成 `"$left/$right/$case/$earType"`
     —— **这一步必须做**，否则「换同型号的另一台」或「多点切到同型号设备」时
     电量数值相同会被去重吞掉，新图案永远发不出去；
  3. 新增 `PodImageLoader.loadModelBoxBitmap()`，三级回落
     **自定义图 → 该型号官方产品图 → 通用图**；`earType <= 0` 时**跳过**型号那一级
     （`drawableOf()` 对未收录型号回落 `fiil_unknow`，那是「官方也不认识」的意思，
     摆进通知栏会让人误判设备）。

### Added

- `FiilModels.HEADPHONE_EAR_TYPES = setOf(2, 5, 8, 46, 247, 250)` 与
  `FiilModels.isHeadphones(earType)`。判定依据见文档 17.4 —— **第一版实现漏了
  Key Max（46）**，那恰好是用户手上那台，于是「改了没效果」。现在有测试钉死全集。

### Changed

- `versionCode` 6 → 7，`versionName` 2.2.2 → 2.2.3
- 应用名保持 `FiilPods 2.2 Fix` 不变
- 文档 `docs/RELEASE_v2.2.2.md` → `docs/RELEASE_v2.2.3.md`，新增第十七节
  （两处成因的 smali 取证、为何不改 `fakeDeviceId`、六款头戴型号表、
  三级回落的取样顺序、状态栏小图标为何故意不改），回归清单从 57 条扩到 64 条
  （58–64 专验图标，其中 63 是「装完必须关开蓝牙」，64 说明小图标不改是故意的）
- 新增 `HeadphoneFormFactorTest`（7 项）：把 6 款头戴钉死为全集
  （`(0..300).filter { isHeadphones(it) }` 必须**恰好等于** `setOf(2,5,8,46,247,250)`），
  并断言这 6 款都有官方产品图。少一个多一个都会红

### Known

- **状态栏最左侧的小图标仍是系统蓝牙图标，这是故意的**。Android 状态栏图标
  只取位图的 alpha 通道做单色着色；实测 31 张型号图里 27 张带 alpha，但都是
  「不透明彩色主体 + 透明背景」的产品照，压成剪影后 24dp 下就是认不出的色块
  （另外 4 张 `t1_lite`/`t1_x`/`t1_xs`/`fiil_unknow` 干脆没有 alpha，传进去是实心方块）。
  要按型号区分必须另做单色矢量剪影，不在本次范围。用户实际看到的是**大图**
  （`FocusNotification` 的 `iconTextInfo` / 岛），它已经跟着型号走了。
- 本轮构建遇到 R8 内存不足（`clean + test + assembleDebug + assembleRelease` 串行时
  `minifyReleaseWithR8` 两次因 G1 虚拟空间 mmap 失败崩溃，当时系统仅剩 ~2GB 空闲）。
  改为分步执行（test `-Xmx1400m` → assembleRelease `-Xmx1500m` → assembleDebug）后全部成功。
  非代码问题，是构建机内存约束。

---

## [2.2.2] - 2026-10-31

修「耳机详情页根本看不到 LDAC 开关」。**这不是 UI 遗漏，是一个自锁**：
LDAC 是耳机侧开关，而旧实现只查手机侧 A2DP 能力 —— 耳机不开就不显示开关，
不显示用户就没入口打开，于是永远不开。

### Fixed

- **详情页看不到 LDAC 开关**（用户第三次反馈）
  `PodDetailPage` 原来的渲染条件是 `if (ldacState >= 0)`，而 `ldacState` 只在
  `FiilCodec.query()` 返回 `supported=true` 时才会变成 0/1。`supported` 来自
  `A2dpService.getCodecStatus(device)` 里有没有 LDAC 那一项 —— 而 **FIIL 的 LDAC 是耳机侧开关**
  （官方 App 逆向：写 `cid=1 cmdId=13`、读开关 `cid=39 cmdId=143`、读解锁 `cid=39 cmdId=142`），
  耳机自己不开时 A2DP 握手根本不声明该编码器。于是：耳机不开 → A2DP 不声明 →
  `supported=false` → `ldacState` 恒为 -1 → 开关不渲染 → 没有入口打开 → 耳机永远不开。

  `git grep` 证实：`LDAC_SWITCH` / `LDAC_UNLOCK` 在 2.2.1 只命中 `FiilMate.kt` 的常量定义，
  **全项目零发送点** —— 这条命令一次都没发出去过。

  修法六点：
  1. `FiilModels.supportsLdac(earType)` 新增机型白名单 `{39, 46, 48, 49, 51, 53}`
     （依据官方 App 里所有 `setLdacSwitch(` 调用点）。
  2. 渲染条件从 `if (ldacState >= 0)` 改为 `if (ldacSupported)`，与协议层解耦。
  3. 状态改读耳机侧：`FiilMate.Param.LDAC_SWITCH = 13` / `LDAC_ACTIVATE = 12`（写），
     `FiilRfcommController` 新增 `ldacEnabled` / `ldacUnlocked` / `ldacKnown` 三个
     `@Volatile` 字段与两条 `Cid.QUERY` 回包分支、一条 `applySetParam` 回显分支
     （`cid=1` 回显不带新值，用 `rememberPendingTarget("ldac", on)` 记目标值）。
  4. 广播分两条来源：`ldac_*`（手机 A2DP 视角）与 `headset_ldac_*`（耳机侧视角），
     避免后到的覆盖先到的。
  5. 点击时双管齐下：先 `FIILProtocol.setLdacSwitch(want)` 让耳机开，再
     `FiilCodec.setLdac(...)` 改手机侧编码器偏好。**顺序不能反** ——
     耳机还不支持时 `setCodecConfigPreference` 会被系统拒掉。
     官方顺序照抄：开 LDAC 前先关低延迟（两者抢带宽）。
  6. `ACTION_PODS_UI_INIT` 心跳里补 `FIILProtocol.requestLdacState()`，
     保证第一次进详情页就能拿到耳机侧状态。

  `ldacState` 语义随之改变：以前 `-1` 表示「不支持、整块不渲染」，
  现在只表示「还没读到、开关仍显示、初值当关」。

### Added

- 详情页 LDAC 摘要三态提示：「需先在 FIIL+ 官方 App 里解锁高清音频」（`ldacUnlocked=false`）/
  「正在读取耳机状态…」（`ldacKnown=false`）/ 真值。未解锁时**开关不置灰**，
  允许用户直接点（可能已在官方 App 解锁但回包还没到）。

### Changed

- `versionCode` 5 → 6，`versionName` 2.2.1 → 2.2.2
- 应用名保持 `FiilPods 2.2 Fix` 不变
- 文档 `docs/RELEASE_v2.2.1.md` → `docs/RELEASE_v2.2.2.md`，新增第十六节（LDAC 自锁的
  逆向取证与六点修法），回归清单从 50 条扩到 57 条（51-57 专验 LDAC）

### Known

- Android 17 上 `A2dpService.sA2dpService` 静态字段已不存在（`FiilCodec` 的路线 2 失效），
  只剩 `AdapterService.sAdapterService` + `getA2dpService()` 的路线 1 可用。
  本轮复核确认路线 1 可用，未改代码。

---

## [2.2.1] - 2026-10-31

删除从 OppoPods 复制过来的「OPPO LE Audio」设置页。**仅此一项改动，不含功能修复。**

### Removed

- **「OPPO LE Audio」设置页**（整页）
  该页从 OppoPods 整体复制而来，在 fiil_mod 里只有 UI 壳：`LeAudioSettingsPage`（213 行）、
  `LeAudioConfig`（38 行）、`LeAudioDevicesDialog`（111 行）加上 84 条字符串，
  **hook 侧一行实现都没有** —— OppoPods 的 `LeAudioConnectionHook`（504 行）与
  `OppoLeAudioHook`（119 行）从未移植。FIIL 走 SPP/RFCOMM MATE 协议，
  与 OPPO 的 `VDID` / `VDSF` / `OESF` / `VDSP` 私有 AT 握手无关，
  所以这页对 FIIL 耳机纯属空转，开关拨了也没有任何东西被写进去。

  移除范围：3 个源文件、设置页的入口卡片、`MainUI` 的 `Screen.LeAudio` 导航与整个
  `entry` 块、`MainTabs` 的 4 处 `onOpenLeAudio` 形参声明与透传、
  `AppConfig.leAudio` 配置字段、`ConfigManager.updateLeAudio()`、
  `AppConfig.normalized()` / `changedFields()` 的相关分支、中英文字符串各 42 条。

  老用户 `prefs` 里已写入的 `leAudio` 字段由 `Json { ignoreUnknownKeys = true }`
  自动忽略，**无需数据迁移**。

### Changed

- `versionCode` 4 → 5，`versionName` 2.2.0 → 2.2.1
- 应用名保持 `FiilPods 2.2 Fix` 不变（用户要求版本号可动、名字不动）
- release APK 体积 4,567,512 B → 4,543,336 B（−24,176 B）

---

## [2.2.0] - 2026-10-31

在 v2.1.0 基线上重做用户提名的五个实机 Bug，并补两项新增能力。
**2.1.0 的整体架构保持不变**，全部改动是定点修补。测试设备：FIIL Key Max
（`earType=46`，Mate 方言 + SPP `gatt=false`）。

### Fixed

- **耳机控制页看不到 LDAC 开关**
  v2.1.0 完全没有 LDAC 支持（`git grep -i ldac` 只命中 `FiilMate.kt:320-321` 的
  `LDAC_UNLOCK=142` / `LDAC_SWITCH=143` 两个常量，没有任何读取或写入）。
  新增 `pods/FiilCodec.kt`（422 行）走反射读写 `A2dpService` 的 codec 状态：
  读用 `getCodecStatus()` 取 `currentCodecType`，写用 `setCodecConfigPreference()`
  切到 LDAC（`CODEC_TYPE_LDAC = 4`）并先 `setUserCodecStatus(..., 1)` 放行。
  桥接：`ACTION_LDAC_SET` / `ACTION_PODS_LDAC_CHANGED`，`PodDetailPage` 的
  `ldacState >= 0` 才渲染开关。

  **第四轮复核发现的 P0**：初版把 `device.javaClass.classLoader` 传给
  `FiilCodec`。`BluetoothDevice` 是 framework 类、由 boot classloader 加载，
  `getClassLoader()` 返回 **null**，而 `com.android.bluetooth.a2dp.A2dpService`
  在 com.android.bluetooth 这个 APK 里，boot classloader 看不到它 ——
  `Class.forName` 必抛 `ClassNotFoundException`，`query()` 恒为 null，
  `publishState` 发 `known=false`，`ldacState` 落 -1，**开关永远不渲染**。
  改为传 hook 自己的 `classLoader`（`param.defaultClassLoader`），
  并在 `FiilCodec` 里加 `candidateClassLoaders()` / `loadClassOrNull()` 兜底。

- **焦点通知开关无效、通知栏没有耳机控制卡片**
  `HyperOS 4` 上 `MiuiBluetoothNotification` 的构造条件变了：`MiuiApplication`
  里的创建被 `DeviceUtils.isMemoryTrimEnabled()`（读 `Settings.Global` 的
  `mibt_memory_trim`）门控，该值默认 `null` → 返回 0 → 那条路径永不执行。
  旧的 `findConstructorByParamCount(cls, 2)` 又恰好押中这个从不被调用的
  `(Context, Looper)` 重载。真机实际走的是 `BluetoothHeadsetService.onCreate`
  → `W1()` → `q2()` 里的 `(Looper, BluetoothHeadsetService)` 那个重载。
  改为三条通道全部注册：`Application.attach(Context)`（主）、
  `BluetoothHeadsetService.onCreate`、以及**两个构造器都挂**；
  `registerReceiver` 保持幂等。另加 `appendLog()` 落盘到
  `getExternalFilesDir(null)/bluetooth_notification_debug.log` ——
  这台机 logcat `main` 缓冲仅 64 KiB 且长期 `0 B readable`，没有落盘日志无法排查。

- **超级岛电量显示不出来**
  `FocusIslandUtil.showBatteryIsland()` 每次展示都 `postDelayed(4000ms) { cancel() }`，
  而电量更新很密（30 s 心跳 + 每次 MAF/佩戴/电量变化），后一条展示会被前一条的
  定时器提前撤掉。改为发令牌（`showToken`），撤岛回调只在自己仍是最后一任令牌时执行。

- **融合设备中心切降噪/通透后图标不刷新**
  ① `MiLinkServiceHook.setAncStateBlock` 的返回值改为 `OP_OK = 100`
  （逆向确认官方成功分支就是 `return 100`，`openAnc/closeAnc/openTransparent`
  成功返回 1），三个 ANC 命令方法同样返回 1，`getWearStatus` 返回 `"1"`。
  ② 通知投递时机：官方 `AncBatteryController.notifyPropertyChanged()` 是
  `DiscoveryKt.getSYSTEM_BROADCAST_HANDLER().postDelayed(..., 25ms)`
  （`OP_UPDATE_DELAY = 25`），而我们的 hook 在 `hookBefore` 里
  `param.setResult(100)` 把整段跳过、自己补的通知是**同步直调**，
  在调用方栈内投递，面板读到的是尚未落定的状态。
  改为反射取 `getSYSTEM_BROADCAST_HANDLER()` 后 `postDelayed(25ms)`，
  并在 350 ms 后再补发一次。
  ③ `MiLink 18.2` 把 ANC 读写改成**策略模式**：FIIL 属第三方耳机，
  `ThirdPartyHeadsetStrategy.getAncState()` 硬编码返回 -1、
  `setAncStateBlock()` 硬编码返回 205。补 `ProfileContext` 层 hook 把
  `getAncState` 接到本地 `ancMode`，绕开策略层的短路。
  ④ 切模式瞬间会「闪一下旧值再闪回来」：`HeadsetInfo.mode` 读的是
  `ProfileContext.getAncState`，设备写入耳机有 1.5–3 s 延迟，
  期间读回旧值。加待确认窗口，窗口内用本地值覆盖。

- **融合中心「更多设置」进原生小米耳机页后按钮无效**
  `SettingsHook` 里 `setAncStateBlock` / 通用命令方法的参数类型写成了
  `Int::class.java`（装箱 `Integer`），实际签名是 `int`（`int` 原始类型），
  `findMethod` 匹配不上，hook 从未装上。改为 `Int::class.javaPrimitiveType`。
  同时 `fakeDeviceId()` / `fakeSupport()` 从返回常量改为读 `ConfigManager`，
  `checkSupport` / `isTWS01Headset` / `isK77sHeadset` 三个 String 方法
  不再当成 boolean 用。

### Added

- **主页按连接型号显示耳机产品图**
  从 FIIL+ 3.5.2 逆向产物里取出 30 张官方产品图
  （`apktool_out/res/drawable/fiil_earphones_*.png`），
  型号对应关系取自 `assets/header_list_zh.json` 的 `headsetList[].imgUrl`，
  并用 `EarPhoneModel.getEarID()`（`Integer.parseInt(getHeadsetModel().substring(2))`）
  的源码证实 earType 与 `headsetModel` 同源。
  PNG 转 WebP 后 2,452,085 B → 498,288 B（20.3%）。
  新增 `pods/PodImages.kt`（28 条 `earType → @DrawableRes` 映射 + `fiil_unknow` 兜底）
  与 `HomePage` 的「当前耳机」卡片；`PodDetailPage` 在用户没设自定义图时
  也回落到型号图。28 条映射与 8 项单测钉死（含官方 `ImageResMapper` 漏登记的
  T1 X `earType=22`）。

- **连接/断开自激回路的刹车机制**
  v2.1.0 有两条无界回路：①`scheduleSilenceProbe` 在 3.5 s 内没收到字节就
  `disconnect("silent-channel")`，该 reason 不在 `MANUAL_DISCONNECT_REASONS` 里
  → 触发 `scheduleReconnect` → 8.5 s 一轮；②`readLoop` 退出
  `disconnect("read-loop-exit")` → 同样补连 → 5 s 一轮。
  `scheduleReconnect` 的 `attempts` 参数形同虚设：每次 `disconnect` 都新开一个
  `attempts=2` 的线程，`connectBlocking` 一成功立即 `return`，计数归零。
  加「记账 + 闸门 + 递增冷却」：`noteDisconnectHealth()` 按
  `everReceivedData && heldMs >= 8 s` 判定健康，不健康则 `consecutiveFailures++`，
  连败 3 次进冷却 60 s → 120 s → 240 s…封顶 15 min，冷却期 `connect()` 直接
  拒绝、`scheduleReconnect` / `scheduleMultipointReconnect` / `scheduleSilenceProbe`
  三条路径都提前退出。用户手动连接走 `connectUserInitiated()` 清空计数穿透限流；
  用户主动断开置 `userRequestedDisconnect`，自动路径一律连不回来。

### Changed

- **伪装耳机型号**保持可编辑并真正注入：`ConfigManager` 的
  `fakeDeviceId()` / `fakeSupport()` 全链路本就完整，本版只是把注入侧
  从返回硬编码常量改为读 `ConfigManager`，设置页的 TextField 改动即时生效。
- `versionCode` 3 → 4，`versionName` 2.1.0 → 2.2.0，应用名 `FiilPods` → `FiilPods 2.2 Fix`

---

## [2.1.0] - 2026-10-26

修复 v2.0.0 上机后用户反馈的三条问题。

### Fixed

- **融合设备中心切换降噪模式后图标要再点一次才更新**
  `MiLinkServiceHook.hookAncCommand()` / `hookAncStateBlock()` 只改了成员变量并通知控制中心刷新，
  没有回写 `AncBatteryModel` 的内存字段，控制中心重绘时读到的仍是旧值。
  新增 `syncAncModel()`，按 ROM 字段名漂移依次回写 `ancState` / `bluetoothAncState` /
  `miLinkAncState`，再二次推送 `UPDATE_ANC`。三处接入（含耳机实体键切换走的
  `ACTION_PODS_ANC_CHANGED` 分支）。

- **模块页误报「模块服务未响应」**
  `announceAlive()` 只在 `AdapterService.onCreate` 时发一次，App 后开就一条都收不到，
  75 秒窗口校验必然超时。改为 `UI_INIT` / `REFRESH_STATUS` 分支每次回发 ALIVE 心跳。

- **设置页开启焦点通知后通知栏无通知**（四个缺陷叠加）
  - `HookEntry` 从未注入 LSPosed 远端 prefs，四个被注入进程里的 `ConfigManager`
    永远是默认值，用户改设置对 Hook 侧无效。新增 `HookContext.attach()` 与
    `HookEntry.prepareRemotePrefs()`。
  - `FocusIslandUtil.showBatteryIsland()` 全工程零调用点，「超级岛 = 模块」这条分支从未接线。
    新增 `MiBluetoothToastHook.showModuleFocusIsland()` 按 `islandMode` 分流。
  - 通知渠道建成 `IMPORTANCE_MIN`，HyperOS 会折叠进「静默通知」；且渠道重要性创建后不可修改。
    换新渠道 id `fiilpods_battery_v2` + `IMPORTANCE_LOW`。
  - `onHook()` 里 `if (!focusNotification()) return` 导致进程在「关」状态下启动后
    再也收不到。改为总是挂接收器，开关判断下沉到显示时刻。

### Changed

- `versionCode` 2 → 3，`versionName` 2.0.0 → 2.1.0
- 补齐 `PodImageProvider` 的 `<provider>` 声明（移植时遗漏，用户设置自定义耳机图片会崩）
- 空断言降级：`getParcelableExtra("status")!!` 等 6 处改为安全调用
- `PodStatus.themedResources.getDrawable() as BitmapDrawable` 硬转改为 `runCatching`
- `values-zh-rCN/strings.xml` 补齐 `fiil_maf_listen`（兼听）、`fiil_maf_wind`（风中）

### Added

- `docs/FIX_V2_1.md`：本轮三条问题的根因、修法与验证记录（302 行）

---

## [2.0.0] - 2026-10-26

修复「电量能显示但模式切不了」，并补齐模块内日志抓取能力。**v1.0.0 的源码与产物完整保留。**

### Fixed

- **Mate 机型（Key Max 等）无法切换降噪模式**（六个真缺陷）
  1. `send()` 缺 GATT 分支：走 BLE GATT 时命令根本不发出去
  2. `setAnc()` 无条件发 GAIA 帧：Mate 机型只认 `cid=44` 的 Mate 帧，GAIA 帧被静默丢弃
  3. `setEq()` / `set3D()` 同样未做协议族分流
  4. `FiilMate.parse()` 把帧首字节当固定 `0x00`，实际是 **seq 滚动计数器** ——
     34 个回包里有 31 个被这一条丢掉
  5. `TONE` 回包解析偏移写错（真机 `01 27 02 00 03 82 01 02`，payload 从第 6 字节起）
  6. 调试页发的 `RFCOMM_LOG_CONNECT` / `DISCONNECT` / `CLEAR` 三个 action
     蓝牙进程根本没注册，日志通道从未接通

- **新手机点开 App 闪退**（`java.lang.RuntimeException: Error receiving broadcast`）
  根因是跨进程 Parcelable：`BatteryParams` 在蓝牙进程与 UI 进程里类名不一致
  （R8 混淆），反序列化抛 `ClassNotFoundException: zd`。
  三层修复：① 业务层彻底不读 Parcelable，改用纯 int extra 通道
  ② 接收器整体 `runCatching` 兜底 ③ proguard 加 keep 规则

### Added

- `pods/FiilMate.kt` 新增 `Query.UNLOCK = 255`（`setUnlock` 握手，帧 `00 27 01 00 04 FF 02 02 34`）
- `pods/RfcommLog.kt`：模块内环形日志缓冲 + 调试页实时查看 + 一键复制
- `RfcommDebugPage`：设置页新增入口，可看 TX/RX 原始字节、过滤、清空、导出
- `docs/FIX_V2_MODES.md`：v2.0.0 六个真缺陷的完整记录（294 行）
- `docs/FIX_CRASH_PARCELABLE.md`：闪退根因与三层修复记录

### Changed

- `versionCode` 1 → 2，`versionName` 1.0.0 → 2.0.0

---

## [1.0.0] - 2026-10-26

首个可用版本。把 FIIL 耳机接入 HyperOS 原生体验。

### Added

- **UI 完全对齐 OppoPods 2.2.0**（三 Tab 底栏：模块 / 耳机 / 设置）
  - 模块页：激活状态、蓝牙状态、配对设备数、系统/应用/Android/LSPosed 版本、构建时间、设备型号
  - 耳机页：多设备列表、电量卡片、降噪模式（MAF 五态）、音效预设、影院模式、蓝牙畅连
  - 设置页：主题、语言、日志级别、隐藏桌面图标、焦点通知、超级岛来源、互联卡片、伪装耳机型号
- **系统蓝牙详情页控制卡片**（`SettingsControlCard.kt`，纯 framework 控件，跨 ClassLoader 安全）
- **超级岛 / 强提示通知**（`MiBluetoothToastHook.kt` + `FocusIslandUtil`）
- **融合设备中心卡片联动**（`hook/milink/`，注入 `com.milink.service`）
- 双传输层：经典蓝牙 SPP（RFCOMM）+ BLE GATT（`ff17` 写 / `ff18` 通知）
- 支持 4 套协议栈：GAIA（老机型）/ Mate（Key Max 等）/ Airoha（F042/CC Pro2）/ 豆包 0x55AA
- 机型识别三级兜底：名称匹配 → MAC OUI 前缀（`B0:F1:A3` / `FF:F1:A3`）→ 地址绑定
- 49 项单元测试（`ModelCapabilityTest` 16 + `ProtocolFrameTest` 27 + `AddressMaskTest` 6）

### Known Issues

- `MiLinkServiceHook` 中 31 处 `runCatching` 静默吞异常，失败无用户可见反馈（记为 P0-2）
- Mate 机型自定义 EQ 写入不生效
- GAIA 老机型、自适应开关仅静态验证，无对应硬件

---

## 更早的历史

- `docs/ACCEPTANCE_REPORT.md` —— 6 角色联合结项验收报告（总分 72/100，结论「可内测、不可公开发布」）

---

## 版本对照

| 版本 | versionCode | 日期 | 关键变化 |
|---|---|---|---|
| 2.2.1 | 5 | 2026-10-31 | 删除 OPPO LE Audio 设置页（只有 UI 壳、零 hook 实现） |
| 2.2.0 | 4 | 2026-10-31 | 五个实机 Bug 修复 + 型号产品图 + 连接回路刹车 + 伪装型号真正注入 |
| 2.1.0 | 3 | 2026-10-26 | 三条上机反馈修复（设备中心图标 / 服务心跳 / 焦点通知） |
| 2.0.0 | 2 | 2026-10-26 | Mate 模式切换 6 缺陷 + 跨进程 Parcelable 闪退 + 日志抓取 |
| 1.0.0 | 1 | 2026-10-26 | 首个可用版本，UI 对齐 OppoPods 2.2.0 |

五个版本的签名一致（V2 SHA-256 `605996dc…38d34`），可直接覆盖安装。

### 各版本 release APK 校验值

| 版本 | 大小 | SHA-256 |
|---|---|---|
| 2.2.1 | 4,543,336 B | `BA45F8140C37CC4406BAC0D713F619B28B58AF2E35AA12C7B4533E77FE6D2FAF` |
| 2.2.0 | 4,567,512 B | `BA6E4FDB75A4111F816498D053E45C09A2FC322ECC08835E6A0560952EC5477D` |
| 2.1.0 | 4,063,403 B | 见 `dist/README.md` |
| 2.0.0 | 3,965,095 B | 见 `dist/README.md` |
| 1.0.0 | 3,948,715 B | 见 `dist/README.md` |

[2.2.1]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.1
[2.2.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.0
[2.1.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.1.0
[2.0.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v2.0.0
[1.0.0]: https://github.com/FNKG9527/FiilPods/releases/tag/v1.0.0
