# FiilPods v3.2.0 · 设置页「导出日志」

> 版本：`versionCode 10` / `versionName 3.2.0` / 应用名 `FiilPods 3.2`
> 日期：2026-11-04
> 相对上一版：只新增「导出日志」，不改任何 hook、不改协议、不改界面结构。

---

## 一、要解决的问题

报 bug 时最常发生的事是：用户描述「它不灵了」，开发者手里什么都没有。
本模块恰恰是最难拿日志的那类软件——

1. **协议日志写在另一个进程**。`FiilRfcommController` 跑在 `com.android.bluetooth`
   里（只有那个进程持有长期连接和 `BLUETOOTH_CONNECT`），它的 `filesDir` 是
   `/data/data/com.android.bluetooth/files/`，模块 GUI 读不到。

   ```
   模块进程    → /data/data/io.github.fnkg9527.fiilpods/files/fiil_module_log.txt
   蓝牙进程    → /data/data/com.android.bluetooth/files/fiil_module_log.txt
   ```

   换句话说：不加跨进程桥，导出文件里**一帧协议日志都不会有**，
   而真正要看的恰恰全在蓝牙进程那边。

2. **logcat 不可用**。HyperOS 上 `ro.logd.size=64K`，且 `persist.debug.dpis.global_log_enabled`
   常被关掉，`logcat` 读到的常是 `0 B readable`。用户也没法自己抓。

3. **原来的落盘行没有日期**。格式是 `HH:mm:ss.SSS`，跨天以后无法按时间窗筛选——
   昨天的 `20:00` 会被当成今天的 `20:00`。

---

## 二、做了什么

### 2.1 跨进程日志桥

非模块进程（蓝牙进程）写日志时不再直接落盘，改为**攒批转发**：

| 参数 | 值 | 理由 |
|---|---|---|
| 攒批行数 | 20 行 | 协议帧密度高，每帧一次 binder 会拖慢收发链路 |
| 攒批时间 | 1000 ms | 上一条兜底，低流量时也不会憋住 |
| 通道 | `PodImageProvider.call("appendLog")` | 见下 |

**为什么用 ContentProvider 而不是广播**：广播只有在模块 UI 进程活着时才有人接——
用户还没打开 App 时产生的那段日志会静默丢掉，而那恰恰是最有价值的一段。
`ContentResolver.call()` 会把目标进程拉起来，语义是「写进了就存下了」。

桥断了（极少数情况，例如 provider 被禁用）就**退回写自己的 `filesDir`**，
宁可落在读不到的地方，也不能丢行。

### 2.2 落盘行加日期

```diff
- 20:00:00.123 I/RFCOMM/TX 00 27 01 00 04 FF 02 02 34
+ 2026-11-04 20:00:00.123 I/RFCOMM/TX 00 27 01 00 04 FF 02 02 34
```

时间戳固定 23 字符宽（`LogWindow.STAMP_WIDTH`），解析用 `ParsePosition` 而不是
`parse()` 的「容忍尾部垃圾」行为——后者会把 `2026-11-04 20:00:00.1234` 静默读成
`…123`。

### 2.3 时间窗与体积裁剪（`pods/LogWindow.kt`）

纯函数，不依赖 Android，因此可以单测。

| 参数 | 值 | 理由 |
|---|---|---|
| 时间窗 | **60 分钟** | 从「出问题」到「想起来导出」，一小时够用；放宽会把上一次断连/上一次切模式的噪声带进来 |
| 上限 | **2 MiB** | 约 2.5 万行，远高于一小时正常使用（30 秒心跳 + 少量帧 ≈ 几百行）；兜住「调试级别 + 反复重连」的病态情况 |
| 超限策略 | 丢最旧、保留最新 | 越靠后越接近出问题的时刻 |
| 残行 | 单独计数后丢弃 | 落盘被截半时留下的半行不能污染导出文件 |

一个刻意的边界：**单行超过上限时仍然保留**。否则把上限配小会出现
「明明有日志，导出却是空的」这种最容易被误判成 bug 的输出。

### 2.4 导出文件结构（`utils/LogExporter.kt`）

一个 txt，五个区段：

| 区段 | 内容 | 解决什么 |
|---|---|---|
| 元信息 | 导出时间、时间窗、应用/版本、包名、机型、Android/API、ABI、指纹 | 复现环境 |
| 配置快照 | 日志级别、伪设备 ID、超级岛模式与时机、点击行为、融合卡片能力、自动游戏模式、落盘文件大小 | 「是不是设置问题」一秒排除 |
| 崩溃记录 | 最近一次自捕获堆栈，超长只留尾部 16,000 字符 | 闪退不需要用户再截图 |
| 运行日志 | 时间窗内的协议帧、hook 决策、状态变化 | 真正定位问题的部分 |
| 统计 | 总行数、导出行数、字节数、各原因丢弃了多少行 | 让「日志不全」变成**可解释的事实** |

运行日志区段分四种情况给四种提示，而不是一律留空：

- 日志级别 = 关闭 → 告知怎么打开
- 一行都还没写 → 提示 LSPosed 只在进程启动时注入，需关开一次蓝牙
- 窗口内为空但文件里有内容 → 说清「共 N 行，但都早于时间窗」
- 正常 → 打印窗口起止与实际行数

### 2.5 分享走 FileProvider

Android 7 起把 `file://` 交给别的应用会抛 `FileUriExposedException`，必须用
`content://` + 临时授权。

- `utils/LogFileProvider.kt`：`FileProvider` 子类，authority 做成编译期常量
  `io.github.fnkg9527.fiilpods.logs`。
- `res/xml/log_export_paths.xml`：同时声明 `external-files-path` 与 `files-path`。
  只声明一条时，另一条路径上的文件会让 `getUriForFile` 抛
  `IllegalArgumentException: Failed to find configured root`。
- manifest 里 `exported="false"`：外部应用拿不到日志文件。

---

## 三、改动清单

新增：

| 文件 | 行数 | 作用 |
|---|---|---|
| `app/src/main/java/moe/chenxy/fiilpods/pods/LogWindow.kt` | 113 | 时间窗/体积裁剪（纯函数） |
| `app/src/main/java/moe/chenxy/fiilpods/utils/LogExporter.kt` | 295 | 拼装导出文件、分享 Intent |
| `app/src/main/java/moe/chenxy/fiilpods/utils/LogFileProvider.kt` | 19 | FileProvider + authority 常量 |
| `app/src/main/res/xml/log_export_paths.xml` | 20 | FileProvider 路径声明 |
| `app/src/test/java/moe/chenxy/fiilpods/pods/LogWindowTest.kt` | 142 | 10 项单测 |

修改：

| 文件 | 改什么 |
|---|---|
| `pods/RfcommLog.kt` | 行格式加日期；模块进程直写/宿主进程攒批转发；`appendBridgedLines()` 入口 |
| `config/PodImageProvider.kt` | 新增 `call()` 处理 `appendLog` |
| `ui/pages/SettingsPage.kt` | 新增「导出日志」卡片（后台线程导出 → Toast → 分享） |
| `AndroidManifest.xml` | 注册 `LogFileProvider` |
| `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` | 6 条文案；应用名改为 `FiilPods 3.2` |
| `app/build.gradle.kts` | `versionCode 9 → 10`，`versionName 3.1.0 → 3.2.0` |
| `app/src/test/.../ModulePackageTest.kt` | 增加 `LogFileProvider.AUTHORITY` 断言 |

---

## 四、产物与校验

构建方式（本机 16 GB 内存，串行 clean 会在 R8 阶段 OOM，必须分步）：

```
gradle clean
gradle :app:testDebugUnitTest
gradle :app:assembleRelease -x lintVitalRelease
gradle :app:assembleDebug
```

公共参数：`--console=plain --no-daemon --max-workers=1`
`-Pkotlin.compiler.execution.strategy=in-process`
`-Dorg.gradle.jvmargs=-Xmx1800m -XX:MaxMetaspaceSize=640m -XX:ReservedCodeCacheSize=256m`

| 产物 | 大小 | SHA-256 |
|---|---|---|
| `FiilPods-v3.2.0-release.apk` | 4,561,252 B | `DD138A80D1D6FA12612F303E5E69D4DEC66AE272A88389455F9AA1D7272213C3` |
| `FiilPods-v3.2.0-debug.apk` | 35,740,754 B | `8C9D177871011ACAAC52E3B00CD19DCE598660B96895CD28D7C398A0DD849B4A` |

`aapt2 dump badging`：

```
package: name='io.github.fnkg9527.fiilpods' versionCode='10' versionName='3.2.0'
application-label:'FiilPods 3.2'
```

单元测试：**suites=7 tests=78 failures=0 errors=0 skipped=0**（上一版 68 项，新增 10 项）。

APK 内容核验：

- release 113 entries（3.1.0 为 112，多出的是 `res/xml/log_export_paths.xml`）、29 个 webp
- merged manifest 两个 provider：`…fiilpods.podimages`、`…fiilpods.logs`
- debug 包资源名未混淆，可见 `string/export_log`（6 条）与 `xml/log_export_paths`
- dex 字符串命中：`appendLog`、`FiilPods-log-`、`io.github.fnkg9527.fiilpods.logs`、`fiil_module_log.txt`

---

## 五、真机验收清单

装完后**必须关开一次蓝牙**（LSPosed 只在进程启动时注入），然后：

1. 设置页出现「导出日志」卡片，摘要写明「最近 60 分钟（上限 2 MB）」。
2. 日志级别调「调试」→ 连耳机 → 操作几次降噪 → 日志级别留在调试。
3. 点「导出日志」→ 卡片摘要变「正在导出…」→ 完成后 Toast 报行数与体积 → 弹分享面板。
4. 分享到任意可读文本的地方，确认文件里有：
   - 头部「导出时间 / 时间窗 / 日志级别 / 机型」齐全
   - 「--- 运行日志 ---」下面有 `I/RFCOMM/TX …` 这类协议帧（**这是本次最关键的一条**：
     上一版这些行写在蓝牙进程里，导出文件里一行都没有）
   - 末段统计里的行数与时间起止合理
5. 把日志级别调「关闭」再导出一次 → 运行日志区段应显示「本次没有记录任何运行日志」
   并给出打开方法，而不是空白。
6. 断开耳机等 61 分钟以上再导出 → 应显示「最近 60 分钟内没有日志，文件里共有 N 行」。
7. 文件位置：`Android/data/io.github.fnkg9527.fiilpods/files/logs/`，多次导出文件不覆盖。
8. 没有安装任何可分享应用的情况下点导出 → 只出现 Toast，不崩。

---

## 六、已知限制

- **日志桥依赖 `PodImageProvider`（`exported=true`）**。理论上同机其他应用也能向它
  append 行。落盘文件本身有 512 KB 截半上限，且只有模块自己会读，因此按低风险接受。
  要收紧需要给该 provider 加签名级 `android:permission`。
- **导出内容只覆盖模块自己记的日志**，不含系统蓝牙栈的日志——后者在本机上读不到
  （`ro.logd.size=64K`）。
- 导出目录在 `Android/data/` 下，Android 11+ 的文件管理器可能拒绝进入；
  此时用分享面板保存到别处即可。
