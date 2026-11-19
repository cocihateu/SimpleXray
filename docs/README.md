# SimpleXray (Personal Fork)

<div align="center">
<img src="images/lineal.svg" alt="SimpleXray icon" width="150">

**English** | **[中文](./README_CN.md)**

<img src="https://app.fossa.com/api/projects/git%2Bgithub.com%2FReRokutosei%2FSimpleXray.svg?type=shield" alt="FOSSA Status" width="150">

<sub>Android 14+ (API 34) | arm64-v8a | MPL-2.0</sub>
</div>

SimpleXray Groups uses Xray as its proxy core and Hev as its Android TUN backend. The proxy page manages local and subscription groups; settings owns global DNS, inbounds, routing, Lua scripts, and the local Clash API. Imports convert supported node formats into native Xray outbounds and report skipped protocol or plugin combinations. See [native imports](./xray-imports.md) and [Clash API](./clash-api.md).

This repository is a personal fork of [SimpleXray](https://github.com/lhear/SimpleXray). The Groups branch combines a global configuration with a shared node and group workspace, rule-file management, and a Miuix-based interface.

> [!NOTE]
> This fork is maintained for personal use and experimentation. Issues and pull requests are not accepted, and no support is provided. If you need a maintained client, use upstream or fork this repository and build it through CI.

## Why This Fork

- **Xray + Hev** — a single proxy core and Android TUN backend.
- **Unified groups** — manual selection, scheduled URL tests, nested fallback groups, editable rule bindings, and per-node destination DNS strategy.
- **Complete configuration workflow** — JSON/YAML import, in-app editor, one-way Android sanitization, log controls, and runtime statistics injection.
- **Rule-file management** — built-in `geoip.dat`/`geosite.dat`, arbitrary custom `.dat` files, `ext:` references, per-file update URLs, and background updates.
- **Miuix interface** — Xiaomi HyperOS/MIUI-inspired Compose UI with adaptive phone and tablet layouts, NavigationRail, Light/Dark/System themes, and Android 12+ dynamic colors.
- **Android integration** — per-app proxy controls, direct local SOCKS5 inbound, network handover handling, and 16 KB page-alignment support.
- **Benchmark tooling** — headless benchmark runner, publication dashboards, and device benchmark datasets.
- **Platform baseline** — Android 14+ and `arm64-v8a` only.

The full structural comparison is in [Differences from Upstream](./upstream-differences.md).

## UI Preview

### Phone

<div align="center">
  <img src="./images/mobile_01.webp" alt="Mobile UI 1" width="48%">
  <img src="./images/mobile_02.webp" alt="Mobile UI 2" width="48%">
  <br>
  <img src="./images/mobile_03.webp" alt="Mobile UI 3" width="48%">
  <img src="./images/mobile_04.webp" alt="Mobile UI 4" width="48%">
</div>

### Tablet

<div align="center">
  <img src="./images/table_01.webp" alt="Tablet UI 1" width="48%">
  <img src="./images/table_02.webp" alt="Tablet UI 2" width="48%">
  <br>
  <img src="./images/table_03.webp" alt="Tablet UI 3" width="48%">
  <img src="./images/table_04.webp" alt="Tablet UI 4" width="48%">
</div>

## Features

### Xray-core

- Xray-core runs as an independent child process and receives its configuration through stdin. No intermediate configuration file is written to disk.
- Hev owns the Android VPN file descriptor and forwards traffic to Xray's loopback SOCKS5 inbound.
- URL-test groups measure their members on a configured schedule and honor the configured result trust duration. Fallback primaries receive recovery checks only after a working backup starts the countdown. Explicit dashboard measurements are user initiated.
- Core status and traffic statistics are queried over plaintext gRPC on a dynamically allocated `127.0.0.1` port. Standard output and error are streamed to the UI and may be exported.

### Configuration

- Complete JSON and YAML configurations, imported through SAF or the clipboard (`.json`, `.yaml`, `.yml`).
- A one-way sanitizer removes desktop/root-oriented fields and adapts inbounds, routing rules, DNS bootstrap hosts, logging, and outbound transport settings for Android. See [Configuration Overrides and Removals](./configuration.md).
- Error, access, and DNS logs are controlled separately. The `Auto` log level maps to `warning` when no explicit level is configured.
- The built-in [scripta](https://github.com/YuKongA/scripta) editor supports syntax highlighting, search and replace, undo/redo, and a quick-symbol bar.

### TUN Backend

Hev supplies the TUN-to-SOCKS data path. Historical SimpleTUN sources and benchmark reports remain reference material for the upstream project.

### Routing and Fallback

Each group has an editable list of routing targets. A Lua return value or JSON rule `outboundTag` matching one of these names enters that group. Internal measurements and relay redials use dedicated authenticated inbounds that select the physical outbound directly. Each physical outbound retains its own `targetStrategy`.

Existing workspaces assign a unique physical primary's rule name to its fallback group during migration. Groups sharing a primary use explicit bindings in the group editor.

The relay applies the group's timeout to each initial connection attempt, including its final candidate. A working backup response starts the primary recovery interval. A successful scheduled primary check returns new connections to primary. Established streams retain their current transport; application retries create connections using the current group selection.

The first-response deadline starts when client payload is available, so idle browser preconnections remain idle. Initial payload replay retains up to 256 KiB; larger transfers stay on their current transport. Retrying application payload before a response can repeat an operation at the destination, while TLS handshake replay occurs before application requests. Connection telemetry covers sessions owned by the fallback relay.

### Rule Files & Scripts

- Import and replace the standard `geoip.dat` and `geosite.dat` files.
- Import arbitrary custom `.dat` files and reference entries with `ext:<file>:<tag>`.
- Configure independent update URLs per custom rule file. Downloads run in the background and are validated before installation.
- **Lua Scripts**: Import `.lua` scripts (supported since Xray-core v26.10.10+), referencable directly via relative paths (e.g. `"script": "routing.lua"`).

### Android Integration

- The Android app layer (UI + `TProxyService`) runs in a single process. Service state and log streams are delivered through in-memory `StateFlow` / `SharedFlow`.
- Per-app proxy filtering, direct SOCKS5 access at `127.0.0.1:<socksPort>`, network handover callbacks, and adaptive layout for phones and tablets.
- **Target SDK 37 (Android 17)**: Declares `ACCESS_LOCAL_NETWORK` (categorized by the OS as "Nearby devices") solely to comply with Android 17 local routing and LAN bypass requirements.

### Benchmark and Tooling

- `tools/benchmark.py` drives throughput, idle-memory, bufferbloat, stability, weak-network, CPS, and QUIC suites through a headless `BenchmarkService`.
- Chart and report generation are included. Results: [Snapdragon 8 Elite Gen 5](./benchmark/8-elite-gen-5/report/benchmark_report.md) and [Snapdragon 778G](./benchmark/778g/report/benchmark_report.md).

## Known Issues

### Telegram may retain a stale local SOCKS5 connection

When Android changes its default network, an existing TCP connection from a client to `127.0.0.1:<socksPort>` can remain open while its Xray upstream transport has already become stale. The official Telegram client has been observed to reuse that loopback connection indefinitely. Routing through the TUN interface and clients with their own liveness detection recover normally.

Workarounds:

- Toggle the VPN or Xray core off and on.
- Reconnect the network interface.
- Use a client that recreates its SOCKS5 connection after network changes.

This is client-side stale-connection reuse, not a listener or Xray-core defect. SimpleXray injects shorter outbound keepalive and TCP user-timeout values to detect blackholed upstream transports faster, but it does not forcibly close otherwise healthy loopback sessions.

## Quick Start

Prerequisites are summarized in the [building guide](./building.md). The Groups workflow supplies the Xray Android prebuilt before Gradle packages the app.

```bash
git clone --recursive https://github.com/ReRokutosei/SimpleXray.git
cd SimpleXray

mkdir -p app/src/main/assets
wget https://github.com/XTLS/Xray-rules-dat/releases/latest/download/geoip-lite.dat -O app/src/main/assets/geoip.dat
wget https://github.com/XTLS/Xray-rules-dat/releases/latest/download/geosite.dat -O app/src/main/assets/geosite.dat

./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/simplexray-arm64-v8a.apk`. Release signing and CI details are documented in [Building from Source](./building.md).

## Documentation

- [Building from Source](./building.md)
- [Configuration Overrides and Removals](./configuration.md)
- [Differences from Upstream](./upstream-differences.md)
- [SimpleTUN Architecture Specification](./simpletun_spec.md)
- [Benchmark Reports](./benchmark/8-elite-gen-5/report/benchmark_report.md)
- [Changelog](../CHANGELOG.md)

## Upstream and Third-Party

Built on [Xray-core](https://github.com/XTLS/Xray-core), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix), and [scripta](https://github.com/YuKongA/scripta), and derived from the upstream [SimpleXray](https://github.com/lhear/SimpleXray) project.

Application icon assets use free Cookie Icons provided by [Magnific](https://www.magnific.com).

## Privacy and License

See the [Privacy Policy](./PrivacyPolicy_EN.md), [Disclaimer](./Disclaimer_EN.md), and [Open Source Licenses](./third_party_licenses.md).

Unless otherwise stated, this project is distributed under the Mozilla Public License 2.0 (MPL-2.0). See [`LICENSE`](../LICENSE) for the full text.
