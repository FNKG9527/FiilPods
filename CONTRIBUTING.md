# 贡献指南 · Contributing

感谢你对 FiilPods 的关注！本文档说明如何提交 Issue 与 Pull Request。

## 行为准则

参与本项目即表示你同意遵守 [Contributor Covenant 2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/)。
请保持技术讨论的礼貌与建设性。

## 提交 Issue

提问前请先搜索[已有 Issue](https://github.com/FNKG9527/FiilPods/issues) 与 [README](README.md)、[docs/](docs/)，
避免重复。

请使用仓库提供的 Issue 模板（`.github/ISSUE_TEMPLATE/`），并尽量附上：

| 信息 | 获取方式 |
|---|---|
| 模块版本 | 设置页「关于」或 `adb shell dumpsys package moe.chenxy.fiilpods \| grep versionName` |
| 耳机型号 | 设置页或耳机包装 |
| 手机型号 / Android 版本 | 设置 → 关于手机 |
| LSPosed 版本 | LSPosed 管理器首页 |
| 复现步骤 | 越具体越好 |
| 模块日志 | 设置 → 需开启「日志级别=详细」，安装 debug 版后从 `导出日志` 复制 |

**注意**：提交日志前请自行脱敏（MAC 地址、序列号等）。本仓库的 `FiilConfig.maskAddress()` 已经会对日志里的
地址做 `B0:F1:**:**:**` 形式的脱敏，但仍建议人工过一遍。

## 提交 Pull Request

### 分支模型

| 分支 | 用途 |
|---|---|
| `main` | 稳定分支，只接受来自 `develop` 的合并 |
| `develop` | 开发分支，功能分支合入此处 |
| `feat/<简述>` | 新功能 |
| `fix/<简述>` | 缺陷修复 |
| `docs/<简述>` | 仅文档 |

### 提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```
<type>(<scope>): <subject>

<body>

<footer>
```

`type` 取值：`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`。

示例：

```
fix(protocol): Mate 帧首字节按 seq 滚动计数器解析

原实现把 buf[0] 当固定 0x00 校验，导致 34 个回包里有 31 个被丢弃。
真机样本 01 27 01 00 02 82 00 已证明 buf[0] 是滚动 seq。

Closes #12
```

### 代码要求

1. **构建通过**：`./gradlew :app:compileDebugKotlin` 无错误
2. **单元测试通过**：`./gradlew :app:testDebugUnitTest`（当前 49 项）
3. **协议改动必须附证据**：任何新增/修改的指令字节，必须在 `docs/PROTOCOL_FIIL.md`
   里标注逆向来源（jadx 路径+行号，或 PC 端抓包十六进制），否则不予合并
4. **禁止臆造指令**：未经实机验证的字节一律进「原始 Hex」通道，不进语义化 UI
5. **日志脱敏**：新增日志不得打印完整 MAC / 密钥

### 提交流程

```bash
git clone https://github.com/FNKG9527/FiilPods.git
cd FiilPods
git checkout -b fix/your-topic develop

# 改代码 + 测试
./gradlew :app:testDebugUnitTest

git commit -m "fix(scope): 说明"
git push origin fix/your-topic
```

然后在 GitHub 上向 `develop` 发起 Pull Request，填写 PR 模板。

## 开发环境

| 项 | 版本 |
|---|---|
| JDK | 21（Corretto / Temurin 均可） |
| Gradle | 9.4.1（仓库自带 wrapper） |
| Android SDK | compileSdk 37 / build-tools 37.0.0 |
| Kotlin | 2.4.20 |
| AGP | 9.1.1 |

> ⚠️ 低内存机器（16 GB 及以下）构建 release 时 R8 容易 OOM，
> 用 `--no-daemon -Dorg.gradle.jvmargs=-Xmx1400m` 可绕过，详见 `docs/FIX_V2_1.md`。

## 许可

提交 PR 即表示你同意以 [GPL-3.0](LICENSE) 授权你的贡献。
