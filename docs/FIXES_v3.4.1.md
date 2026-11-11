# v3.4.1 修复说明：自定义按键读不出来

> 现象：设备详情页 →「触控与按键」→ 按键自定义，无论等多久都停在
> 「正在读取按键设置…」，一个键都列不出来。
> 用户追问：这个按键是特定机型的自定义按键吗？

## 一、先回答「是不是特定机型的按键」

**不是。** `cid=39 / cmdId=5` 是 FIIL Mate 协议族（F045～F053 全部七个机型类）
**通用**的按键配置读取命令，官方每一台都发同一条：

```java
// com/fengeek/main/i/d/{b,c,d,e,f,g,h}.java 里的 getKey(...)
setCommandClassifyId(39);
setCommandId(5);
```

能不能**改**才取决于机型：官方各机型的 `*SetFragment` 只画自己开放的按键。

- `F046SetFragment`（Key Max，earType=46）只画了一个开关：
  「双击语音」，写入 `setKey((byte)3, (byte)(!on ? 2 : 0), new e())`
  （`F046SetFragment.java:191`，读取在 `:302`）。
- 其余 9 个按键在官方 App 里对该机型**不显示**，但读取命令仍然能拿到整张表。

所以正确的预期是：**读得到，但可写的项随机型而变**。旧的实现连「读」这一步都没走通。

## 二、三个断点

### 断点 1：握手路径从不发这条查询

`pods/FIILProtocol.kt` 有两条初始化路径：

| 路径 | 何时执行 | 是否发 `Query.KEY` |
| --- | --- | --- |
| `handshake()` → `mateQueries()` | 每次连上（`FiilRfcommController.kt:703`、`:829`） | ❌ 没有 |
| `refreshAll()` | 只有 GAIA 补票（`:787`）和调试命令 `"refresh"` | ✅ 有 |

常规路径是「连上 → handshake → 进页面」，走的是第一条。真机日志可以佐证：
`C:\Users\91499\Downloads\fiil_module_log.txt`（FIIL Key Max，earType=46，
mate=true）里发送的 Mate cmdId 统计为

```
01×4  02×4  04×4  08×4  0C×21  18×4  80×4  82×4  8E×402  8F×402
```

**没有 `05`** —— 整份日志里一次都没发过按键查询。

> 注意别被日志里的 `05 27 02 00 ...` 误导：那是 **RX 帧的 seq=05**，
> 不是 cmdId 5。Mate 帧头是 `00 | cid | type | rsv | lenField`，
> cmdId 在第 6 字节，seq 只在接收侧出现。

### 断点 2：短回包被直接判死

`pods/TouchKeyPayload.kt` 旧实现：

```kotlin
if (payload.size - start < 30) return null
```

但官方各机型判长度的门槛**不一致**：

| 机型类 | 门槛 | 组数 |
| --- | --- | --- |
| F045 / F046 / F047 / F048 | `buff.length >= 30` | 10 |
| F049 / F051 / F053 | `buff.length >= 24` | 8 |

（`F045SetFragment.java:66-80`、`F051SetFragment.java:88-102`、
`F046SetFragment.java:81-83`）

而且官方取值就是**连续三字节一组、取第 3 个字节**：
`buff[2]/buff[5]/buff[8]/buff[11]/buff[14]/buff[17]/buff[20]/buff[23]`
—— 步长 3、偏移 2，和紧凑布局完全一致，只是**只读前 8 组**。
所以「跳取偏移」不是另一种布局，是「只取 8 组」的同一种布局。

PC 实测回包（`fiil_pods_pc/docs/evidence/unverified_cmdid_20261025_014438.txt`）：

```
[TX] getKey 05 27 01 00 02 05 00
[RX] 05 27 02 00 20 05 1E 01 01 07 02 01 07 03 01 00 ... 0A 01 00  RESP cid=39 cmd=5 len=30
```

payload 首字节 `0x1E(=30)` 是「后面还有 30 字节」，其后 10 组 × 3 字节
`(keyId, type=01, value)`。带计数位时总长 31，老实现按 `size-start < 30`
判还能过；**短包（8 组）则必然返回 null**。

### 断点 3：解析失败静默

```kotlin
FiilMate.Query.KEY -> {
    TouchKeyPayload.parseKeyActions(frame.payload)?.let { ... }
}
```

`?.let` 一挡，失败什么都不发生：不更新状态、不打日志、不通知 UI。
UI 侧又是 `if (keyActions.size < 11) 显示「读取中」`，于是两个失败
（没发查询、解析失败）在界面上长得一模一样。

另外，`BluetoothProcessHook` 的 `ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS`
分支只做了 `publishState(force = true)`。**发布 ≠ 查询**：从没查到过的字段
播不出来，用户点进页面时补发的这两条广播并不能把按键拉出来。

## 三、本次改动

| 文件 | 改动 |
| --- | --- |
| `pods/TouchKeyPayload.kt` | `parseKeyActions` 改为按「能读几组读几组」解析：最少 8 组（24 B），带/不带首字节 `0x1E` 计数位两种起点都试，取解出有效 key 更多的那种；参数化为 `GROUP_BYTES=3 / KEY_COUNT=10 / MIN_GROUPS=8 / COUNT_PREFIX=30` |
| `pods/FIILProtocol.kt` | `mateQueries()` 末尾补 `FiilRfcommController.requestKeyConfig()`，让握手路径也发查询 |
| `hook/BluetoothProcessHook.kt` | `UI_INIT` / `REFRESH_STATUS` 分支在已连接时补协议查询：Mate 发 `requestKeyConfig()`，GAIA 发 `requestTouchSensitivity()` |
| `ui/pages/TouchKeysPage.kt` | 三态区分：未收到回包 →「正在读取」；收到但全 -1 →「该机型未上报按键表」；单键 -1 → 摘要显示「未知」 |
| `res/values*/strings.xml` | 新增 `key_config_unavailable`、`key_action_unknown` |
| `app/src/test/.../TouchKeyPayloadTest.kt` | 10 → 13 条：8 组短包、8 组带计数位、10 组完整、垃圾回包全 -1 |

## 四、验证

- `.\gradlew.bat :app:testDebugUnitTest --offline`：**22 suite / 178 tests /
  0 failures / 0 errors / 0 skipped**（`TouchKeyPayloadTest` 13 条全过）。
- `.\gradlew.bat :app:assembleRelease --offline`：见构建产物。

## 五、真机回归步骤

1. 装上 v3.4.1，连上耳机（Key Max 或任一 Mate 机型）。
2. 进「设备详情 → 触控与按键」。
3. 预期：不再长时间停在「正在读取」；要么列出按键表，要么明确显示
   「该机型未上报按键表」。
4. 若列出按键表：把某个键改成「上一首」，然后在耳机上按对应键验证。
   Key Max 只应生效 key 3（双击语音 0/2 两态）。
5. 若仍显示「正在读取」：抓 `RFCOMM 调试` 页日志，看有没有
   `tx-mate cid=39 cmd=5` 以及 `rx ... cid=39 cmd=5`；有发无回说明固件
   对该机型不响应这条查询（可在设置里手动改机型覆盖再试）。

## 六、仍然存在的限制

- 按键配置**只在 Mate 方言**可用；GAIA 机型（BLE-only、F042/CC Pro2/CC nano）
  的对应能力是 `SetCustomButtonFunction=1921` / `GetCustomButtonFunction=1922`、
  `SetCustomButtonFourHitFunction=1935` / `GetCustomButtonFourHitFunction=1936`，
  本次**未实现**。
- 触控灵敏度**只在 GAIA 方言**可用；Mate 机型没有该命令。
- 写入效果依赖固件：未开放的键可以读、可以改，但耳机可能不执行。
