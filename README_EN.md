<div align="center">

<img src="docs/assets/logo.svg" alt="fiilpod Logo" width="120" />

# fiilpod

**System-level FIIL earbuds control for Xiaomi HyperOS**

</div>

<div align="center">

![Release](https://img.shields.io/badge/Release-2.1.0-4B8BF5?style=flat-square)
![Downloads](https://img.shields.io/github/downloads/FNKG9527/FiilPods/total?style=flat-square&color=34C759)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Framework](https://img.shields.io/badge/Framework-LSPosed-FF6B6B?style=flat-square)
![ROM](https://img.shields.io/badge/ROM-HyperOS%203%20%7C%204-FF6900?style=flat-square)

</div>

<div align="center">

**English** ｜ **[简体中文](README.md)**

</div>

---

## 📖 Introduction

**fiilpod** is a system-level **Xposed / LSPosed** module that brings FIIL true-wireless earbuds into Xiaomi HyperOS's native audio experience.

It doesn't spawn its own floating window or grab the foreground — it **injects into the system Bluetooth process**, speaks FIIL's private protocols directly (classic Bluetooth SPP and BLE GATT), and translates battery, ANC, EQ and spatial-audio state into forms that MIUI/HyperOS understands. Everything surfaces in **native system locations**: the Bluetooth settings page, the Control Center's Smart Island, and the HyperConnect device center.

The UI and settings mirror [OPPOPods](https://github.com/1812z/OppoPods) 2.2.0 — only the earbuds being controlled are FIIL.

> 🔍 **Want to know how it works?** Injection architecture, connection links, protocol routing, settings-page injection, Smart Island integration — all dissected in
> **👉 [Read the implementation deep-dive →](docs/IMPLEMENTATION.md)**

---

## 🧭 Documentation

| Document | Contents |
|---|---|
| 🚀 **[Usage](#-usage)** | Install, enable, restart scope, connect |
| 💡 **[Implementation](docs/IMPLEMENTATION.md)** | Injection / dual transport / protocol routing / settings injection |
| 📡 **[Protocol tables](docs/PROTOCOL_FIIL.md)** | UUIDs, 190 GAIA commands, Mate cid/cmd |
| 🧪 **[Device test log](docs/DEVICE_TEST.md)** | 19 on-device test cases |
| 📝 **[Changelog](CHANGELOG.md)** | 1.0.0 → 2.1.0 |

> The protocol documentation is currently Chinese-only.

---

## ✨ Features

### 🎧 Earbuds

- **ANC / MAF (5 states)** — Off / Noise Cancelling / Open / Listen / Wind. The number of states adapts per model (F051 CC3 has 3, F045/F047/F048 have none).
- **Adaptive switches** — ambient adaptive ANC, adaptive wind noise reduction, ear-canal adaptive ANC, adaptive EQ, cloud tuning.
- **EQ** — 12 official presets + a **10-band custom graphic EQ**.
- **Cinema mode (spatial audio)** — F046 Key Max and other supported models.
- **Dual connection (multipoint)** — two devices at once, with automatic reconnect on switch.
- **Low latency** — Music / Game / Video.
- **Voice prompt volume** — High / Medium / Low / Mute.
- **Wear detection** — pause on removal, resume on wear (F051 / F053).
- **Battery** — left / right / case, refreshed from notification frames.

### ⚙️ System integration

- **Settings page integration** — a native battery and ANC card inside "Settings → Bluetooth → your earbuds", plus an extra custom card for cinema mode, low latency, multipoint and custom EQ.
- **Smart Island** — HyperOS strong toasts on battery/ANC changes, plus a persistent island showing "current mode · battery".
- **HyperConnect device center** — pull down Control Center to see battery and switch ANC; state stays in sync with the app.
- **Model adaptation** — 50+ FIIL models recognised by `earType`.
- **Model spoofing** — customise the identifier reported to the system.
- **Triple brand detection** — name keywords → MAC prefix (`B0:F1:A3` / `FF:F1:A3`) → known address table.

### 📱 Module

- **Quick popup** — compact control panel from the notification or a shortcut.
- **Custom earbud images**, **manual model selection**, **protocol debug page** (raw TX/RX hex), theme and language.

---

## 💻 Requirements

| Item | Requirement |
|---|---|
| **Device** | Xiaomi / Redmi (running MIUI or HyperOS) |
| **OS** | HyperOS 2 / 3 / 4 (Android 15+, `minSdk 35`) |
| **Framework** | LSPosed with libxposed **API 102** |
| **Permissions** | Bluetooth connect/scan; 4 Xposed scopes |
| **ABI** | arm64-v8a |

> ⚠️ Non-Xiaomi devices, Android 14 and below, and older LSPosed (API 100/101) are **not supported**.

---

## 🚀 Usage

**1. Install the APK**

Grab `FiilPods-v2.1.0-release.apk` from the [Releases page](https://github.com/FNKG9527/FiilPods/releases/latest).

> 📦 All three versions (1.0.0 / 2.0.0 / 2.1.0) share the same package name and signature, so they install over each other without uninstalling.

**2. Enable in LSPosed**

LSPosed Manager → Modules → check **fiilpod**, and make sure these four scopes are selected:

```
com.android.bluetooth
com.android.settings
com.xiaomi.bluetooth
com.milink.service
```

**3. Restart the scope**

You **must restart the scoped processes** (long-press the module in LSPosed Manager → "Restart scope") or reboot. Otherwise the Bluetooth process keeps running the old injected code.

**4. Connect your earbuds**

Turn on system Bluetooth and connect. Then:

- Control Center shows the HyperConnect earbud card
- "Settings → Bluetooth → your earbuds" shows the battery and ANC card
- The fiilpod app shows the full detail page

> 🔧 **Troubleshooting?** Settings → log level "Verbose" → the icon in the top-right of the earbuds page opens the **protocol debug page** with raw TX/RX bytes, copy and export.

---

## 🔬 Technical notes

### Dual transport: SPP and BLE GATT

| Transport | UUID | Notes |
|---|---|---|
| Classic SPP | `00001101-0000-1000-8000-00805F9B34FB`<br>`d52b47bc-2dea-42c4-b26d-20252fceeeb3` | Primary on older models |
| BLE GATT | write `0000ff17-...`<br>notify `0000ff18-...` | Available on all models, doesn't occupy the audio link |

The frame format is identical across both, so the protocol layer is transport-agnostic. The module prefers SPP and **falls back to GATT after 3.5 s of silence**.

### Four protocol stacks

| Protocol | Frame header | Command space | Models |
|---|---|---|---|
| **GAIA** | `FF 01/F1 … 02 34` | 16-bit command IDs (190) | Older lineup |
| **Mate** | `00 <cid> 01 00 …` | Small classifyIds | F045 ~ F053 |
| **Airoha** | custom packet | Race commands | F042 / CC Pro2 |
| **Doubao** | `55 AA` | state machine | some newer models |

### Mate frame layout (F045 ~ F053)

```
┌─────┬─────┬──────┬────────┬────────────┬─────────┬─────────┐
│ seq │ cid │ type │ rsvd   │ len field  │ cmdId   │ payload │
└─────┴─────┴──────┴────────┴────────────┴─────────┴─────────┘
```

Three traps, all covered by unit tests:

1. **`seq` is a rolling counter, not a fixed `0x00`** — early builds matched `0x00` and dropped 31 of 34 replies.
2. **`type` has three values** — `0x01` request / `0x02` response / `0x03` notify.
3. **GET and SET use different semantics** — GET keeps `cid=39` and puts the query code in `cmdId`; SET uses `cid=command family` with `cmdId=0`.

### setUnlock handshake

Mate models **must send an unlock frame first**, or the earbuds stay completely silent:

```
00 27 01 00 04 FF 02 02 34
```

This corresponds to `setUnlock()` in the official app and fires right after the "Bluetooth connected" event.

---

## 🙏 Acknowledgments

The UI, settings organisation and system-injection approach draw heavily on these open-source projects:

- **[OPPOPods](https://github.com/1812z/OppoPods)** — **the origin and primary reference for this project.** The UI layout, settings structure, three-tab bottom navigation, Smart Island and HyperConnect implementations were ported from it. Sincere thanks to the author.
- **[fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol)** — protocol and implementation reference. The FIIL frame format, command IDs and handshake analysis build on this work.
- **[libxposed](https://github.com/libxposed/api)** — modern Xposed API.
- **[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)** — HyperOS-style Compose components.

And thanks to [FIIL](https://www.fiil.com/) for making these earbuds, and to everyone who provided testing feedback.

---

## 📄 License

Licensed under **GPL-3.0**. See [LICENSE](LICENSE).

> **Disclaimer**
>
> This project is for learning and personal use only, and is not affiliated with FIIL. It is built through reverse engineering and may break with firmware updates.
> Flashing and using Xposed modules carries risk — evaluate and accept it yourself.
> Commercial use is not permitted.

---

<div align="center">

**Made with ❤️ for FIIL × HyperOS**

[⬆ Back to top](#fiilpod)

</div>
