# 安全策略 · Security Policy

## 支持范围

| 版本 | 是否维护 |
|---|---|
| 2.1.x | ✅ 当前版本，接受安全修复 |
| 2.0.x | ⚠️ 仅接受严重问题修复 |
| 1.0.x | ❌ 已归档，不再维护 |

## 报告安全漏洞

**请不要通过公开 Issue 报告安全漏洞。**

请使用 GitHub 的私密漏洞报告功能：

👉 https://github.com/FNKG9527/FiilPods/security/advisories/new

或在仓库页面点击 **Security → Report a vulnerability**。

报告中请包含：

- 漏洞类型与影响范围
- 复现步骤（越详细越好）
- 受影响的版本号
- 若可能，附上修复建议

我会在 **7 个工作日内** 确认收到，并在修复发布后致谢（除非你要求匿名）。

## 安全边界说明

本项目是一个 **LSPosed 模块**，会注入以下系统进程：

| 进程 | 用途 |
|---|---|
| `com.android.bluetooth` | 建立与耳机的 RFCOMM/BLE 链路、收发协议帧 |
| `com.android.settings` | 在蓝牙详情页注入控制卡片 |
| `com.xiaomi.bluetooth` | 接管超级岛与通知 |
| `com.milink.service` | 融合设备中心卡片联动 |

因此以下问题属于**已知设计**，不算漏洞：

- 模块会读取蓝牙设备列表（仅用于识别 FIIL 耳机）
- 模块会在蓝牙详情页插入自己的 View
- 模块会发送/接收 `moe.chenxy.fiilpods.action.*` 广播（全部显式 `setPackage`，非隐式广播）

以下属于**真实安全问题**，请务必报告：

- 未显式指定包名的隐式广播泄露耳机状态
- 日志中明文输出完整 MAC 地址或配对信息
- `PodImageProvider` 路径穿越（`../`）
- Xposed 作用域被扩展到无关应用
- 未校验外部 Intent 的 extra 导致任意代码执行

## 已知待修项

| 编号 | 问题 | 状态 |
|---|---|---|
| P0-1 | `FiilRfcommController` 等 4 处日志打印完整设备地址 | ✅ v2.0.0 已用 `maskAddress()` 修复 |
| P0-2 | `MiLinkServiceHook` 中 31 处 `runCatching` 静默吞异常，失败无用户可见反馈 | 🔲 待修 |

## 免责声明

本项目与 FIIL（斐耳）及其关联公司**没有任何隶属关系**，未获得任何官方授权。
"FIIL"、"FIIL Key Max" 等商标归其各自所有者。
本项目仅供个人学习与自用设备管理，请勿用于商业分发。
