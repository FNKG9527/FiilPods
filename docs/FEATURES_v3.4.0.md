# v3.4.0 功能补齐说明（触控 / 按键 / EQ 预设库 / 融合中心图标）

本轮把审计报告里三块「协议已明确、模块没写」的能力补齐，并修正融合中心图标
不区分头戴/双耳的问题。

## 1. 触控灵敏度（GAIA）

- 命令：`GetTouchSensitivity = 1919`、`SetTouchSensitivity = 1918`，沿用
  legacy 版本位（`newProtocol = false`），与官方 `FiilCommandUtil` 一致。
- 写：payload 两字节，`[0]` 是左右（1=左、0=右），`[1]` 是档位线值
  （低 `44`、中 `37`、高 `73`）。`TouchKeyPayload.sensitivityPayload(ear, level)`
  负责 clamp 与侧别转换。
- 读：回包 `payload[1]` 是右耳、`payload[2]` 是左耳（官方解析顺序如此），
  `TouchKeyPayload.parseSensitivity` 转回 0/1/2，未知线值给 -1。
- UI：`TouchKeysPage` 在非 Mate 机型显示左右两个下拉；Mate 机型没有该命令，
  不显示灵敏度和按键页。
- 状态：`FiilStatus.touchSensitivityLeft/Right`；`refreshAll` 的 GAIA 分支
  会主动查询。

## 2. 按键自定义（Mate）

- 读：`Cid.QUERY (39)` + `Query.KEY (5)`，无 payload。回包首字节 `0x1E`
  （十进制 30）后接 10 组 `[keyId, type, value]`；`TouchKeyPayload.parseKeyActions`
  返回长度 11 的数组（下标即按键号 1..10，缺省 -1）。
- 写：`Cid.SET_KEY (34)`，`cmdId` 为按键号，payload 一个字节操作码。
  白名单：0 无操作、2 语音助手、3 上一首、4 下一首、5 音量加、6 音量减、
  7 播放/暂停、8 低延时模式。其他值不下发。
- UI：`TouchKeysPage` 在 Mate 机型显示 10 个按键下拉；配置尚未读回时显示
  「读取中」，避免把 -1 当成「无操作」展示。
- 状态：`FiilStatus.keyActions`；`refreshAll` 的 Mate 分支主动查询。

## 3. EQ 预设库（完整版）

设备端只有**一个**自定义 EQ 槽，且上报的自定义档固定 `id=0`，与内置预设
索引 0 冲突，旧页面会把设备档当成内置档过滤掉。本轮处理：

- `ConfigManager.SavedEqPreset(id, name, frequencies, gains)`，`AppConfig.eqPresets`
  持久化；本地档 id 一律为负数，避免与内置 0..N 冲突。
- 保存：新档 id = 当前最小本地 id − 1；`ACTION_EQ_PRESET_SAVE` 同时写设备槽。
- 应用：点击负数档只写设备槽（`id` 仍随包带回，设备忽略）；点击内置档走
  原有 `ACTION_EQ_PRESET_SET`。
- 删除：先从本地库移除；若删的是当前应用档，清空 active 并发
  `ACTION_EQ_PRESET_DELETE`，设备恢复平直。
- 页面合并：`devicePresets = 设备上报 + 本地库`，`selectedId` 优先本地 active。

## 4. 融合中心图标

- 宿主 `getHeadsetType(BluetoothDevice)` 的返回值决定卡片图标：
  `7` → `circulate_device_headset_headphones`，`3` → `circulate_headset_icon`
  （资源 id 已在宿主 `R$drawable` 中核实）。
- 模块侧 `headsetTypeIcon()` 的取值顺序：
  `earType > 0` → 地址绑定里保存的 earType（`FiilConfig.readEarTypeForAddress`）
  → 设备名推断（`FiilModels.earTypeOfName`）→ 普通。
- 目的：卡片如果比 `ACTION_STATE` 先到，不会先返回「普通」并被宿主缓存。

## 验证

- `:app:testDebugUnitTest`：22 suite / 174 tests / 0 failures。
  新增 `TouchKeyPayloadTest` 10 条，覆盖左右与档位线值、解析顺序、短包、
  带/不带 `0x1E` 前缀、未知线与越界。
- `:app:compileDebugKotlin --offline`：通过。
- 真机回归建议：
  1. 头戴（Key Max）：设备详情页不应出现「触控与按键」中的灵敏度项，按键页
     应能读回 10 组配置；改 key 3 应在官方 App 里看到「双击语音」变化。
  2. GAIA 双耳：左右灵敏度分别改低/中/高，重进页面应保持。
  3. EQ：保存两档本地预设，切内置再切回本地档，设备声音应跟随；删除当前档
     应回到平直。
  4. 融合中心：头戴显示头戴图标；双耳显示普通耳机图标；断连重连后仍正确。

## 未做（明确保留）

- 设备端多 EQ 槽：协议没有「按 id 存多档」的能力，本地库是唯一的多档来源。
- Mate 的触控灵敏度：官方协议族没有该命令，不做伪实现。
- Key Max 之外的按键差异：操作码按官方统一表下发，固件是否接受取决于机型。
