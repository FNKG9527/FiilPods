## 这个 PR 做了什么

<!-- 一句话说清改动 -->

## 关联 Issue

Closes #

## 改动类型

- [ ] `feat` 新功能
- [ ] `fix` 缺陷修复
- [ ] `docs` 文档
- [ ] `refactor` 重构
- [ ] `perf` 性能
- [ ] `test` 测试
- [ ] `build` / `ci` 工程

## 改动范围

- [ ] 协议层（`pods/FiilMate.kt`、`pods/FiilGaia.kt`、`pods/FIILProtocol.kt`）
- [ ] 控制器（`pods/FiilRfcommController.kt`、`pods/FiilGatt.kt`）
- [ ] Hook（`hook/`）
- [ ] UI（`ui/`）
- [ ] 文档（`docs/`、`README.md`、`CHANGELOG.md`）
- [ ] 构建配置

## 验证清单

> **必填**：请贴出实际执行的命令与结果，不要只说「测过了」。

- [ ] `./gradlew :app:compileDebugKotlin` 通过
- [ ] `./gradlew :app:testDebugUnitTest` 通过（当前 49 项）
- [ ] 若涉及协议字节：已附逆向来源（jadx 路径+行号 或 PC 抓包十六进制）
- [ ] 若涉及协议字节：已在 `docs/PROTOCOL_FIIL.md` 同步更新
- [ ] 若涉及新机型：已在 `pods/FiilModels.kt` 的 `NAME_RULES` / `MATE_EAR_TYPES` 登记
- [ ] 新增日志不含完整 MAC / 密钥

## 真机验证

| 项 | 值 |
|---|---|
| 耳机型号 | |
| 手机型号 / Android | |
| LSPosed 版本 | |
| 实测结果 | |

<!-- 未在真机验证的改动请显式写「仅静态验证」，不要写成「已可用」 -->

## 截图 / 日志

<!-- 有 UI 改动请附图；有协议改动请附 TX/RX 十六进制 -->
