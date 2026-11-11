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
| **v3.4.2**（最新） | `FiilPods-v3.4.2-release.apk` | 4.45 MB | 本地构建，未上传 GitHub |
| v3.4.1 | `FiilPods-v3.4.1-release.apk` | 4.45 MB | 同上 |
| v3.4.0 | `FiilPods-v3.4.0-release.apk` | 4.45 MB | 同上 |
| v3.3.2 | `FiilPods-v3.3.2-release.apk` | 4.43 MB | 同上 |
| v3.3.1 | `FiilPods-v3.3.1-release.apk` | 4.43 MB | 同上 |
| v3.3.0 | `FiilPods-v3.3.0-release.apk` | 4.43 MB | 本地构建，未上传 GitHub |
| v3.3.0 | `FiilPods-v3.3.0-debug.apk` | 34.21 MB | 同上（排查问题用，含完整日志） |
| v3.2.0 | `FiilPods-v3.2.0-release.apk` | 4.35 MB | [Release v3.2.0](https://github.com/FNKG9527/FiilPods/releases/tag/v3.2.0) |
| v3.2.0 | `FiilPods-v3.2.0-debug.apk` | 34.09 MB | 同上（排查问题用，含完整日志） |
| v3.1.0 | `FiilPods-v3.1.0-release.apk` | 4.33 MB | [Release v3.1.0](https://github.com/FNKG9527/FiilPods/releases/tag/v3.1.0) |
| v3.1.0 | `FiilPods-v3.1.0-debug.apk` | 34.03 MB | 同上（排查问题用，含完整日志） |
| v3.0.0 | `FiilPods-v3.0.0-release.apk` | 4.33 MB | [Release v3.0.0](https://github.com/FNKG9527/FiilPods/releases/tag/v3.0.0) |
| v3.0.0 | `FiilPods-v3.0.0-debug.apk` | 34.03 MB | 同上（排查问题用，含完整日志） |
| v2.2.3 | `FiilPods-v2.2.3-release.apk` | 4.33 MB | [Release v2.2.3](https://github.com/FNKG9527/FiilPods/releases/tag/v2.2.3) |
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

所有版本共用同一张签名证书（V2 签名 SHA-256 `605996dcb03fd9b06c9b7cde6398750a2e063d822bf43be227549bc431138d34`，
即 Android 调试证书）。2026-11-12 用 `apksigner verify --print-certs` 复验 v3.4.2，digest 仍一致。

⚠️ **3.1.0 是例外**：为了上架 LSPosed 官方模块仓库，它的包名从 `moe.chenxy.fiilpods` 改成了
`io.github.fnkg9527.fiilpods`（LSPosed 要求 applicationId 反推的域名归提交者所有）。
包名不同即不同 App，**3.1.0 不能覆盖安装 3.0.0 及更早版本**，两者可以并存。
从 3.0.0 升级到 3.1.0 请先卸载旧版再装新版，然后重新在 LSPosed 里启用并勾选作用域。

### 3.3.x / 3.4.x 的 SHA-256

| 版本 | release |
|---|---|
| v3.4.2 | `4EED79CACDFE7F3A136AD906461C07A0C5131C8B15995E730141508F1C1C03A3` |
| v3.4.1 | `68A872703FC3C2B6E01BB546B7435735B2F33E0215A792426FE71FA54017030A` |
| v3.4.0 | `50D0FA0F78525FE9791839A069C648F94EFF88120FE94827C566F432C342F08B` |
| v3.3.2 | `10DC02B118E5190AFE51077B21BA6275F2F73C7E7378F43B5DC3EDD0141A5AB7` |
| v3.3.1 | `44C9D8C83B0DAC52F7A8D210A3C4FBFF6FF73DA606F4ABCF4B5B4C27EE8B98CC` |
| v3.3.0 | `29C9A7C2766DF6E50591154BC93C60E32B201BBB179CBD758B07D07B0895EBFA` |

### 2.2.x / 3.0.x 的 SHA-256

| 版本 | release | debug |
|---|---|---|
| v3.3.0 | `29C9A7C2766DF6E50591154BC93C60E32B201BBB179CBD758B07D07B0895EBFA` | `8F3DA1547417E7C4650A61053743B6868D9B46146330EA6BBE1C97CB0431A0B5` |
| v3.2.0 | `DD138A80D1D6FA12612F303E5E69D4DEC66AE272A88389455F9AA1D7272213C3` | `8C9D177871011ACAAC52E3B00CD19DCE598660B96895CD28D7C398A0DD849B4A` |
| v3.1.0 | `E9437CC3682F8152F080B46643174CDEF57278B211752CFABEC1ACCADD4E4FE0` | `68A296F989085AAA33F8C1A023BBB09B78EA63E98086FF4203DB19FE200EE42D` |
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
