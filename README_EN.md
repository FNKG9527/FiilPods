<div align="center">

<img src="docs/assets/logo.svg" alt="fiilpod Logo" width="120" />

# fiilpod

**System-level FIIL earbuds control for Xiaomi HyperOS**

</div>

<div align="center">

![Release](https://img.shields.io/badge/Release-2.2.1-4B8BF5?style=flat-square)
![Downloads](https://img.shields.io/github/downloads/FNKG9527/FiilPods/total?style=flat-square&color=34C759)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Framework](https://img.shields.io/badge/Framework-LSPosed-FF6B6B?style=flat-square)
![ROM](https://img.shields.io/badge/ROM-HyperOS%203%20%7C%204-FF6900?style=flat-square)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)

</div>

<div align="center">

[![Star](https://img.shields.io/github/stars/FNKG9527/FiilPods?style=flat-square&color=yellow)](https://github.com/FNKG9527/FiilPods/stargazers)
[![Fork](https://img.shields.io/github/forks/FNKG9527/FiilPods?style=flat-square&color=orange)](https://github.com/FNKG9527/FiilPods/network/members)
[![Issues](https://img.shields.io/github/issues/FNKG9527/FiilPods?style=flat-square&color=red)](https://github.com/FNKG9527/FiilPods/issues)
[![Last commit](https://img.shields.io/github/last-commit/FNKG9527/FiilPods?style=flat-square)](https://github.com/FNKG9527/FiilPods/commits/main)

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
| 📝 **[Changelog](CHANGELOG.md)** | 1.0.0 → 2.2.1, complete history |
| ⚖️ **[Third-party & borrowed code](THIRD_PARTY_LICENSES.md)** | Dependency licenses, exactly which open-source projects were ported and where, protocol sources |

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

## 🔗 Related projects

This module is one part of a toolchain built around reverse-engineering FIIL earbuds. Besides the Android module
here, the same author has open-sourced three more repositories — protocol notes settle into tools, and the tools feed back into the module:

| Repository | What it is | Language / License |
|---|---|---|
| **[FiilPods](https://github.com/FNKG9527/FiilPods)** | **This project.** System-level FIIL earbud control for HyperOS (Xposed/LSPosed) | Kotlin · GPL-3.0 |
| **[fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol)** | Reverse-engineering notes on the FIIL control protocol: frame layouts for the GAIA / Mate / Doubao command spaces, 190-command table, on-device verification logs | Docs · CC-BY-SA-4.0 |
| **[fiil-pods-pc](https://github.com/FNKG9527/fiil-pods-pc)** | Windows desktop console over BLE GATT: tkinter GUI, packaged with PyInstaller + Inno Setup | Python · GPL-3.0 |
| **[fiil-ldac-unlock](https://github.com/FNKG9527/fiil-ldac-unlock)** | LDAC offline activation toolkit — how to re-enable LDAC on a device you already own after the vendor servers went down | Python · GPL-3.0 |

**How they relate:**

- **fiil-headphone-protocol** is the source of truth at the protocol layer. The frame format, the `cid`/`cmdId`
  table and the 190-command GAIA table in `docs/PROTOCOL_FIIL.md` corroborate against it; when the question is
  "what exactly should this command send", cross-checking both is more reliable than trusting either alone.
- **fiil-ldac-unlock** solves "how do I turn LDAC on", and the module's `pods/FIILProtocol.kt`
  `setLdacSwitch()` / `requestLdacState()` share its lineage (`cid=1 cmdId=13` to write, `cid=39 cmdId=143` to read).
  That is where the v2.2.2 conclusion about "the LDAC switch never shows up" was settled.
- **fiil-pods-pc** goes over BLE GATT; this module falls back to that same GATT channel when Bluetooth resources are
  held by the classic audio link and the BLE advertisement disappears. Both hit the same pitfalls.

> All three are independently usable and do not depend on this module; this module does not depend on them to run.
> They merely share the same body of knowledge.

---

## 🙏 Acknowledgments

The UI shape, settings organisation and system-injection approach come from reading and porting the open-source
projects below. **What exactly was borrowed, and to what depth, is written out item by item** — this is not
boilerplate; it is an obligation under GPL-3.0, and it helps anyone following the same path.

### UI and project skeleton

- **[OppoPods](https://github.com/1812z/OppoPods)** (201★, Kotlin) — **the origin and primary reference for this project.**
  The port covers **the entire UI layer and settings skeleton**: three-tab bottom navigation, the
  `MainTabsScaffold` page organisation, detail-page card layout, settings-page grouping, the `PopupActivity`
  form, `ConfigManager`'s `@Serializable AppConfig` plus flat mirror keys (so LSPosed remote prefs can read them
  directly), the `OppoPodsAction` broadcast contract, the `utils/miuiStrongToast` (Smart Island / focus
  notification) wrapper, and the `PodImagePrefs` custom-earbud-image system.
  `docs/ARCH_OPPOPODS.md` is a section-by-section architecture teardown of it (the porting blueprint), with line
  numbers against the current checkout.
  **This project's package is `moe.chenxy.fiilpods`, one word away from OppoPods' `moe.chenxy.oppopods`,
  because the whole project was forked from it and then reshaped.**
  The protocol layer, hook points and device identification are completely different — OppoPods speaks OPPO's
  VDID/VDSF/OESF/VDSP private AT, FIIL speaks GAIA's 190 commands and Mate's `cid`/`cmdId`.
- **[OppoPods (Leaf-lsgtky fork)](https://github.com/Leaf-lsgtky/OppoPods)** — an active fork where some newer ideas
  (e.g. the LE Audio approach in `OPPOLeaConnect`) first appear.
- **[HyperPods](https://github.com/Art-Chen/HyperPods)** (103★, Kotlin, GPL-3.0) — a reference for a different way of
  organising things: Smart Island multi-form switching, notification-channel naming strategies. It is also listed
  on the module's About page.

### Hook infrastructure and reverse-engineering method

- **[HuaweiPods](https://github.com/Nshpiter/HuaweiPods)** (36★, Kotlin, GPL-3.0) — **the main template for the hook
  infrastructure.** The simplified `HookContext` (heat reload, shutdown generations and stable HookIds removed),
  `HookEntry`'s `META-INF/xposed/java_init.list` entry declaration, **the LSPosed remote-prefs injection timing**
  (hooks run inside `com.android.bluetooth` / `com.xiaomi.bluetooth` / `com.milink.service`, which cannot read the
  module app's own `SharedPreferences`, so `HookEntry.loadHook` must inject them), and the "one APK, two identities"
  overall shape (a Compose app that also injects into five system processes) all come from reading it.
  `docs/ARCH_HUAWEIPODS.md` is a section-by-section specification of it.
  When v2.2.0 fixed "focus notification does nothing", `MiBluetoothToastHook` moved to `Application.attach` as the
  primary channel; that idea was confirmed against HuaweiPods' comments plus this round's reverse engineering — the
  old constructor hook targeted an overload gated behind a branch in `MiuiApplication` that never executes on device.
- **[OPPOLeaConnect](https://github.com/Leaf-lsgtky/OPPOLeaConnect)** (Java, GPL-3.0) — LE Audio / LC3 connection
  optimisation. The "OPPO LE Audio settings" page removed in v2.2.1 was an empty shell copied over from OppoPods;
  one of the deciding factors was that it only applies to OPPO's private AT family and has nothing to do with FIIL.

### Dependencies

- **[libxposed/api](https://github.com/libxposed/api)** (734★, Apache-2.0) — modern Xposed API. This module targets
  **API 102** (`compileOnly(libs.libxposedApi)`) and needs an LSPosed build that supports the new API.
- **[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)** (1,539★, Apache-2.0) — HyperOS-style Compose
  components (`Card`/`Scaffold`/`SwitchPreference`/`OverlayDialog`/`MiuixTheme`). All five of the module's
  `miuix`, `miuix.preference`, `miuix.icons`, `miuix.blur` and `miuix.nav` artifacts come from here.

### Protocol and reverse-engineering artifacts

- **[fiil-headphone-protocol](https://github.com/FNKG9527/fiil-headphone-protocol)** (CC-BY-SA-4.0) — see "Related
  projects" above; the source of truth for frame formats, command IDs and the handshake flow.
- **Static reverse engineering of the official FIIL+ 3.5.2 app** — the other independent source for the protocol
  layer: the 190-command GAIA table, Mate's `cid`/`cmdId` mapping and the `setUnlock` handshake timing in
  `docs/PROTOCOL_FIIL.md` all come from analysing the official APK and verifying it command by command on a real
  device. The repository **contains no APKs and no decompiled artifacts**, only conclusions and redacted logs.
  The v2.2.3 icon fix was settled along the same path: `BluetoothServiceClient.getHeadsetType`'s three-statement
  body, the Xiaomi six-digit code checks in `vg/a.b(String)`, and the packed-switch icon table in `a0.smali`, all
  cited with `file:line` (see section 17 of `docs/RELEASE_v2.2.3.md`).
- **MiLink (`com.milink.service`) 18.2.0.12** — the system-side implementation behind HyperConnect and Smart Island,
  and the object of investigation for questions like "why doesn't the icon follow the model" and "why does the icon
  stick to the old value when switching modes".

### Special thanks

- **[FIIL](https://www.fiil.com/)** — for making these earbuds. Without the hardware, none of this exists.
- **Everyone who provided testing feedback** — most v2.2.x bugs were found by you on real devices, including that
  v2.2.3 first-pass mistake where Key Max was missed from the over-ear list.

> ⚠️ If any attribution above is wrong or missing, please open an issue and it will be corrected immediately.
> The complete third-party inventory, with an item-by-item list of where each ported piece landed, is in
> **[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)**. Full licence texts for each project live in their own
> repositories; this project is GPL-3.0.

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
