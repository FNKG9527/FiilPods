# dist — 历史版本归档

本目录存放各版本的**构建产物索引**与**源码快照**。

> ⚠️ APK 文件不入 git（仓库体积考虑），请到 [Releases](https://github.com/FNKG9527/FiilPods/releases) 下载。

## 目录结构

```
dist/
├── v1-src/          v1.0.0 完整源码快照（冻结，不再修改）
└── README.md        本文件
```

## 各版本产物

| 版本 | 产物 | 大小 | 下载 |
|---|---|---|---|
| **v3.0.0**（最新） | `FiilPods-v3.0.0-release.apk` | 4.33 MB | [Release v3.0.0](https://github.com/FNKG9527/FiilPods/releases/tag/v3.0.0) |
| v3.0.0 | `FiilPods-v3.0.0-debug.apk` | 34.03 MB | 同上（排查问题用，含完整日志） |
| v2.2.3 | `FiilPods-v2.2.3-release.apk` | 4.33 MB | [Release v2.2.3](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.3) | `FiilPods-v2.2.3-release.apk` | 4.33 MB | [Release v2.2.3](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.3) |
| v2.2.3 | `FiilPods-v2.2.3-debug.apk` | 34.03 MB | 同上（排查问题用，含完整日志） |
| v2.2.2 | `FiilPods-v2.2.2-release.apk` | 4.33 MB | [Release v2.2.2](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.2) |
| v2.2.2 | `FiilPods-v2.2.2-debug.apk` | 34.03 MB | 同上 |
| v2.2.1 | `FiilPods-v2.2.1-release.apk` | 4.33 MB | [Release v2.2.1](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.1) |
| v2.2.1 | `FiilPods-v2.2.1-debug.apk` | 34.02 MB | 同上 |
| v2.2.0 | `FiilPods-v2.2.0-release.apk` | 4.36 MB | [Release v2.2.0](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.0) |
| v2.2.0 | `FiilPods-v2.2.0-debug.apk` | 34.15 MB | 同上 |
| v2.1.0 | `FiilPods-v2.1.0-release.apk` | 3.88 MB | [Release v2.1.0](https://github.com/FNKG9527/FiilPods/releases/tag/v2.1.0) |
| v2.1.0 | `FiilPods-v2.1.0-debug.apk` | 34.06 MB | 同上 |
| v2.0.0 | `FiilPods-v2.0.0-release.apk` | 3.78 MB | [Release v2.0.0](https://github.com/FNKG9527/FiilPods/releases/tag/v2.0.0) |
| v2.0.0 | `FiilPods-v2.0.0-debug.apk` | 34.04 MB | 同上 |
| v1.0.0 | `FiilPods-v1.0.0-release.apk` | 3.77 MB | [Release v1.0.0](https://github.com/FNKG9527/FiilPods/releases/tag/v1.0.0) |
| v1.0.0 | `FiilPods-v1.0.0-debug.apk` | 46.20 MB | 同上 |

八个版本的**包名与签名完全一致**（V2 签名 SHA-256 `605996dcb03fd9b06c9b7cde6398750a2e063d822bf43be227549bc431138d34`），可直接覆盖安装，无需卸载。

### 2.2.x / 3.0.0 的 SHA-256

| 版本 | release | debug |
|---|---|---|
| v3.0.0 | `E5B3F1ECF1E71AE1DEBE7C79D8247BB3101DDD990DE19663101C470ACF771B16` | `C6F651778A26EA0E9BED2DA78DF17DCCFD8F4E33B6BDD21ABF078647CA6ECA5C` |

| v2.2.3 | `F23714F9500DD7CE1EC801305BD0AA03DA70AC03A2821729C731423F4C89844C` | `F1D886C65EF46ABFF1B8DA90BF1352A03524A171DDA225A019F68E8A187624C2` |
| v2.2.2 | `897726447F172F307AC8F518314BA778CCFE12CFB646D3D3D806603421C3C24C` | `4C8C33073650AF4EDCD354DD4823389C4E504C76C2F5C9BAB8E9814190CD3324` |
| v2.2.1 | `BA45F8140C37CC4406BAC0D713F619B28B58AF2E35AA12C7B4533E77FE6D2FAF` | `D7EF9DFF19E3AA77EA1A7C779B5BF8326E2DF2FA1DE6E7F721E605455CEEC3E1` |
| v2.2.0 | `BA6E4FDB75A4111F816498D053E45C09A2FC322ECC08835E6A0560952EC5477D` | `644B14FE58E48C324AC5CD9A8081E90430778D64D01FD4EF13E9F6833FECB040` |


## 为什么保留 v1-src

v1.0.0 是一个**完整可用的历史形态**：那时还没有引入 `FiilGatt`（BLE GATT 传输层）、还没有 `RfcommLog`（模块内日志）、UI 也还是自建的 Compose 界面而非移植的 OppoPods 形态。

保留它的价值：

1. **回归参照** —— 出现「v2 能连、v1 能连但 v2 不能」这类问题时，可直接对比两版差异
2. **最小实现** —— 想读懂这个模块在做什么，v1 比 v2 小得多（26 个 Kotlin 文件 vs 73 个）
3. **用户要求** —— 原始需求明确要求「原始版本不要删除 直接构建第二个版本」

v1-src 是**冻结快照**，不接受修改。如需基于 v1 修复，请新建分支。

## 构建这些产物

```bash
# 需要 JDK 21 + Android SDK (compileSdk 37)
./gradlew :app:assembleRelease    # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

release 开启 R8 混淆 + 资源压缩，所以只有 ~3.9 MB；debug 不混淆，因此 34–46 MB。这是正常的。
