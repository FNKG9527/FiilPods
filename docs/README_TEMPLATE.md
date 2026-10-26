<div align="center">

<img src="docs/assets/logo.svg" alt="fiilpod Logo" width="120" />

# fiilpod

**为 Xiaomi HyperOS 设备提供系统级 FIIL 耳机控制**

</div>

<div align="center">

![Release](https://img.shields.io/badge/Release-2.1.0-4B8BF5?style=flat-square)
![Downloads](https://img.shields.io/github/downloads/FNKG9527/FiilPods/total?style=flat-square&color=34C759)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Framework](https://img.shields.io/badge/Framework-LSPosed-FF6B6B?style=flat-square)
![ROM](https://img.shields.io/badge/ROM-澎湃OS%203%20%7C%204-FF6900?style=flat-square)

</div>

<div align="center">

**[English](README_EN.md)** ｜ **简体中文**

</div>

---

## 📖 项目简介

**fiilpod** 是一个基于 **Xposed / LSPosed** 的系统级模块，把 FIIL 真无线耳机接入小米 HyperOS 的原生耳机体验。

它不弹自己的悬浮窗、不抢前台 —— 而是**注入系统蓝牙进程**，直接接管 FIIL 耳机的私有协议链路（经典蓝牙 SPP 与 BLE GATT），把电量、降噪、EQ、空间音频这些状态翻译成 MIUI/HyperOS 认识的形式，最终呈现在**系统蓝牙设置页、控制中心超级岛、融合设备中心**这些系统原生位置里。

界面与设置项对标 [OPPOPods](https://github.com/1812z/OppoPods) 2.2.0 —— 只是控制的耳机换成了 FIIL。

> 🔍 **想知道它是怎么实现的？** 注入架构、连接链路、协议分流、设置页注入、超级岛联动，全部拆解在
> **👉 [点击了解实现详情 →](docs/IMPLEMENTATION.md)**

---

## 🧭 文档导航

| 文档 | 内容 |
|---|---|
| 🚀 **[使用说明](#-使用说明)** | 安装、启用、重启作用域、连接耳机 |
| 💡 **[实现原理详解](docs/IMPLEMENTATION.md)** | 注入架构 / 双传输层 / 协议族分流 / 设置页注入 / 系统级联动 |
| 📡 **[协议字节表](docs/PROTOCOL_FIIL.md)** | UUID 全表、GAIA 190 条命令、Mate cid/cmd 对照 |
| 🧪 **[真机回归记录](docs/DEVICE_TEST.md)** | 19 项用例的实测结果 |
| 🐞 **[缺陷根因档案](docs/IMPLEMENTATION.md#七相关文档)** | 九处连接根因、跨进程闪退、模式切换失效、v2.1 三处修复 |
| 📝 **[更新日志](CHANGELOG.md)** | 1.0.0 → 2.1.0 的完整变更 |

---

## ✨ 核心功能模块

### 🎧 耳机功能

- **降噪控制（MAF 五态）** —— 关闭 / 降噪 / 开放 / 兼听 / 风中。档位数量随机型自适应（F051 CC3 仅前三档，F045/F047/F048 无 ANC）。
- **自适应开关** —— 环境自适应降噪、自适应抗风噪、耳道自适应降噪、自适应 EQ、云感调音，按机型能力显示。
- **EQ 均衡器** —— 12 组官方预设 + **10 段自定义图形 EQ**，可拖动滑块逐个频段调整并下发至耳机。
- **影院模式（空间音频）** —— F046 Key Max 等支持空间音频的机型可一键开关。
- **蓝牙畅连（多点连接）** —— 同时连接两台设备，切换时自动重连不中断。
- **低延迟模式** —— 音乐 / 游戏 / 视频三档（GAIA 机型为三档，Mate 机型为音乐 / 游戏两档）。
- **语音提示音量** —— 高 / 中 / 低 / 静音四档，控制耳机提示音的大小。
- **佩戴检测** —— 摘下暂停、戴上续播（F051 / F053）。
- **电量显示** —— 左耳 / 右耳 / 充电盒三路电量，随通知帧实时刷新。
- **耳机操作** —— 寻找耳机（播放提示音）、音量加减、播放 / 暂停 / 上一首 / 下一首。

### ⚙️ 系统集成

- **设置页集成** —— 系统「设置 → 蓝牙 → FIIL 耳机」页面出现与小米自家耳机一致的电池卡片与降噪切换；下方额外挂载自建控制卡，承载宿主没有的影院模式 / 低延迟 / 蓝牙畅连 / 自定义 EQ 入口；右上角「⋮」菜单直达模块控制台。
- **超级岛支持** —— 电量变化与降噪切换时在状态栏顶部弹出 HyperOS 强提示，并常驻超级岛显示「当前模式 · 电量」；断开时自动收起。
- **融合设备中心** —— 下拉控制中心的耳机卡片可显示电量、切换降噪模式，状态与 App 双向同步。
- **型号自适应** —— 按 `earType` 识别 50+ 款 FIIL 耳机，自动决定档位数量、EQ 预设与可用开关。
- **型号伪装** —— 可自定义上报给系统的设备标识，让系统按指定型号渲染原生卡片。
- **三路品牌识别** —— 名称关键字 → MAC 前缀（`B0:F1:A3` / `FF:F1:A3`）→ 已知地址表；蓝牙名被改得面目全非也能认出。

### 📱 模块功能

- **快捷弹窗** —— 从通知或快捷方式拉起紧凑控制面板：电量 → 降噪 → 低延迟 → 「更多 / 完成」。竖屏三卡纵排，横屏分栏。
- **快捷跳转** —— 通知点击行为、弹窗「更多」按钮行为均可自定义（模块 / 弹窗 / 系统设置页）。
- **自定义耳机图片** —— 可为耳机详情页与超级岛替换本地图片。
- **手动选择型号** —— 蓝牙名被改、老机型不在型号表时，可在控制台手动指定型号并立即切换协议族。
- **协议调试页** —— 实时查看 TX / RX 原始字节，支持过滤、复制、导出，排查连接与指令问题的利器。
- **主题与语言** —— 强调色、深浅色、多语言、隐藏桌面图标。

---

## 💻 系统要求

| 项目 | 要求 |
|---|---|
| **设备** | Xiaomi / Redmi 系列（需运行 MIUI 或 HyperOS） |
| **系统** | HyperOS 2 / 3 / 4（Android 15 及以上，`minSdk 35`） |
| **框架** | LSPosed（libxposed **API 102**，需支持新版 API） |
| **权限** | 蓝牙连接 / 扫描权限；模块需勾选 4 个作用域 |
| **架构** | arm64-v8a |

> ⚠️ 非小米设备、Android 14 及以下、旧版 LSPosed（API 100/101）均不受支持。

---

## 🚀 使用说明

**1. 安装 APK**

下载 [Release 页面](https://github.com/FNKG9527/FiilPods/releases/latest) 的 `FiilPods-v2.1.0-release.apk`，直接安装（日常使用装 release 版即可，`debug` 版体积更大但带完整日志）。

> 📦 三个版本（1.0.0 / 2.0.0 / 2.1.0）包名与签名一致，可直接覆盖安装，无需卸载。历史版本见 [Releases](https://github.com/FNKG9527/FiilPods/releases)。

**2. 在 LSPosed 中启用**

打开 LSPosed 管理器 → 模块 → 勾选 **fiilpod**，并确认作用域包含以下四项：

```
com.android.bluetooth
com.android.settings
com.xiaomi.bluetooth
com.milink.service
```

**3. 重启作用域**

启用后**必须重启作用域内的进程**（LSPosed 管理器里长按模块选「重启作用域」），或直接重启手机。否则蓝牙进程里跑的仍是旧代码。

**4. 连接耳机**

打开系统蓝牙，连接你的 FIIL 耳机。连接成功后：

- 下拉控制中心，可见融合设备中心的耳机卡片
- 进入「设置 → 蓝牙 → 你的耳机」，可见电量与降噪控制卡片
- 打开 fiilpod App，可见完整的耳机详情页

> 🔧 **遇到问题？** 设置页 → 日志级别设为「详细」→ 耳机页右上角图标进入**协议调试页**，可看 TX/RX 原始字节、复制、导出。提 Issue 时附上这段日志（已脱敏）能大幅加快定位。

---

## 🔬 深度技术说明

### 双传输层：SPP 与 BLE GATT

FIIL 耳机有**两条完全独立**的控制通道，帧格式相同，但可靠性差异很大：

| 传输层 | UUID | 适用性 | 说明 |
|---|---|---|---|
| **经典蓝牙 SPP**（RFCOMM） | `00001101-0000-1000-8000-00805F9B34FB`<br>`d52b47bc-2dea-42c4-b26d-20252fceeeb3` | 老机型主通道 | 标准 SPP 与 FIIL 自定义 UUID 并存；部分机型只有自定义 UUID 可用 |
| **BLE GATT** | 写入 `0000ff17-...`<br>通知 `0000ff18-...` | 全机型可用 | 不占用音频链路，手机端更稳定 |

模块默认优先 SPP，**静默探测 3.5 秒无回包即自动降级到 GATT**，并在两条通道间自动翻转重试。这个设计来自实测：`createRfcommSocketToServiceRecord()` 只创建 socket 对象、不做 SDP —— 创建成功不等于建链成功，必须先 `connect()` 才知道这条通道通不通。

### 四套协议栈

FIIL 不同芯片方案用了四套完全不同的命令集，模块按首帧自动识别：

| 协议 | 帧头 | 命令空间 | 覆盖机型 |
|---|---|---|---|
| **GAIA** | `FF 01/F1 … 02 34` | 16 位命令号（190 条） | 老款全系 |
| **Mate** | `00 <cid> 01 00 …` | 小整数 classifyId | F045 ~ F053 |
| **Airoha** | 自定义包 | Race 命令 | F042 / CC Pro2 |
| **豆包** | `55 AA` | 状态机帧 | 部分新机型 |

### Mate 帧结构（F045 ~ F053）

```
┌─────┬─────┬──────┬────────┬────────────┬─────────┬─────────┐
│ seq │ cid │ type │ 保留   │ 长度字段   │ cmdId   │ payload │
└─────┴─────┴──────┴────────┴────────────┴─────────┴─────────┘
  1B    1B    1B     1B      1B(可省)      1B(可省)   NB
```

三个容易踩的坑，都写进了模块的单元测试：

1. **`seq` 是滚动计数器，不是固定 `0x00`** —— 早期实现按 `0x00` 硬匹配，导致 34 个回包里有 31 个被当成杂散字节丢弃。
2. **`type` 有三种取值** —— `0x01` 请求 / `0x02` 应答 / `0x03` 通知。只接受 `0x01` 会把所有耳机应答全丢掉。
3. **GET 与 SET 是两套语义** —— GET 固定 `cid=39`、`cmdId` 填查询码；SET 则是 `cid=命令族`、`cmdId=0`、payload 直接跟在长度字段后。混用会导致命令「发送成功但不生效」。

### 连接初始化：setUnlock 握手

Mate 机型建立 RFCOMM 链路后，**必须先发一条解锁帧**，否则耳机对所有查询保持静默：

```
00 27 01 00 04 FF 02 02 34
 │  │  │  │  │  │  └──┴─ 0x02 0x34（GAIA 厂商标识）
 │  │  │  │  │  └──────── cmdId = 0xFF
 │  │  │  │  └─────────── 长度字段
 │  │  │  └────────────── 保留
 │  │  └───────────────── type = 0x01（请求）
 │  └──────────────────── cid = 39
 └─────────────────────── seq
```

这条握手在官方 App 里对应 `setUnlock()`，触发时机是「蓝牙已连接」事件后的第一帧。少了它，链路明明建起来了，耳机却一个字都不回。

---

## 🙏 致谢 (Acknowledgments)

本项目的界面形态、设置项组织与系统注入思路，大量参考了以下开源项目：

- **[OPPOPods](https://github.com/1812z/OppoPods)** —— **本项目的原型与主要借鉴对象**。UI 布局、设置页结构、三 Tab 底部导航、超级岛与融合设备中心的实现范式均移植自此项目，在此向作者致以最诚挚的感谢。
- **[fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol)** —— 本项目的协议与实现参考来源。FIIL 私有协议的帧格式、命令号与握手流程分析参考了该项目的工作。
- **[libxposed](https://github.com/libxposed/api)** —— 现代化的 Xposed API 实现。
- **[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)** —— 提供 HyperOS 风格的 Compose 组件。

同时也感谢 [FIIL](https://www.fiil.com/) 做出了这些耳机，以及所有提供测试反馈的用户。

---

## 📚 更多文档

| 文档 | 内容 |
|---|---|
| 💡 [实现原理详解](docs/IMPLEMENTATION.md) | 注入架构 / 双传输层 / 协议族分流 / 设置页注入 / 系统级联动 |
| 📡 [协议字节表](docs/PROTOCOL_FIIL.md) | UUID 全表、GAIA 190 条命令、Mate cid/cmd 对照、未确认项清单 |
| 🧪 [真机回归记录](docs/DEVICE_TEST.md) | 19 项用例的实测结果与未覆盖项 |
| 🔍 [诊断档案](docs/IMPLEMENTATION.md#七相关文档) | 九处连接根因、跨进程闪退、模式切换失效、v2.1 三处修复 |
| 🏗 [架构分析](docs/ARCH_OPPOPODS.md) | OppoPods 架构拆解（UI 移植依据） |
| ✅ [验收报告](docs/ACCEPTANCE_REPORT.md) | 6 角色联合结项验收（72/100，「可内测」） |
| 📝 [更新日志](CHANGELOG.md) | 1.0.0 → 2.1.0 完整变更 |
| 🤝 [贡献指南](CONTRIBUTING.md) · [安全策略](SECURITY.md) | 提 PR 与漏洞上报 |

---

## 📄 许可证

本项目基于 **GPL-3.0** 许可证开源，详见 [LICENSE](LICENSE)。

```
Copyright (C) 2026 fiilpod contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

> **免责声明**
>
> 本项目仅供学习与个人使用，与 FIIL 官方无任何关联。模块通过逆向分析实现，可能因固件升级而失效。
> 刷机与使用 Xposed 模块存在风险，请自行评估并承担后果。
> 请勿将本项目用于商业用途。

---

<div align="center">

**Made with ❤️ for FIIL × HyperOS**

[⬆ 回到顶部](#fiilpod)

</div>
