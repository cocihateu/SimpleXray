# SimpleXray (Personal Fork)

<div align="center">
<img src="images/lineal.svg" alt="SimpleXray icon" width="150">

**English** | **[中文](./README_CN.md)**

<img src="https://app.fossa.com/api/projects/git%2Bgithub.com%2FReRokutosei%2FSimpleXray.svg?type=shield" alt="FOSSA Status" width="150">

<sub>Android 14+ (API 34) | arm64-v8a | MPL-2.0</sub>
</div>

SimpleXray Groups uses one Xray proxy core with Xray TUN, Hev TUN, and Simple TUN options. Its five main pages are Dashboard (仪表盘), Proxies (代理), Configuration (配置), Logs (日志), and Settings (设置). Proxies handles runtime selection and HTTP latency; Configuration owns group creation, node editing, group settings, and the structured Rules subpage. Settings provides structured DNS, inbound, log, and local Clash API controls. Imports convert supported node formats into native Xray outbounds and report skipped protocol or plugin combinations, with validation specific to each import path. See [native imports](./xray-imports.md) and [Clash API](./clash-api.md).

This repository is a personal fork of [SimpleXray](https://github.com/lhear/SimpleXray). The Groups branch combines a global configuration with a shared node and group workspace, rule-file management, and a Miuix-based interface.

> [!NOTE]
> This fork is maintained for personal use and experimentation. Issues and pull requests are not accepted, and no support is provided. If you need a maintained client, use upstream or fork this repository and build it through CI.

## Why This Fork

- **Xray core** — Xray TUN, Hev TUN, and Simple TUN backends.
- **Unified groups** — manual selection, scheduled URL tests, nested fallback groups, editable rule bindings, and per-node destination DNS strategy.
- **Configuration workflow** — JSON/YAML imports, six-protocol node forms with advanced JSON, structured rule editing, one-way Android sanitization, log controls, and runtime statistics injection.
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

### Pages and Editing

| Page | Purpose |
| --- | --- |
| Dashboard / 仪表盘 | Service readiness, exit information, rule/global/direct mode, traffic, memory, and zashboard access. |
| Proxies / 代理 | All groups as rounded cards, initially collapsed, with member/active-leaf summaries, Selector/Fallback/URLTest labels, selection, search, expand/collapse, and group HTTP measurement. Stopped fallback groups show their primary; other groups retain saved selections. |
| Configuration / 配置 | Create local or subscription groups, update subscriptions, open each group's node and settings pages, and open Rules. Group settings owns the default-group action. |
| Logs / 日志 | Saved-log level, search, export, and clear. |
| Settings / 设置 | DNS, inbounds, TUN, logging, rule assets, and the local Clash API. |

The node page owns clipboard/file imports and add/edit/delete controls. Forms cover VLESS, VMess, Trojan, Shadowsocks, SOCKS, and HTTP; transport, TLS/REALITY, destination DNS strategy, and Shadowsocks UoT/simple-obfs controls are exposed where applicable. Both flat `settings` and legacy server/user arrays are supported. Form changes preserve existing extra fields and additional entries; advanced JSON exposes the complete outbound, including protocols outside the six-form list.

Rules is a Configuration subpage with structured type, matching content, target, enabled state, ordering, and import/export controls. YAML remains an import format; rule management uses structured controls and JSON export. Group-node, group-settings, and Rules routes retain Configuration as the selected navigation page.

### Xray-core

- Xray-core runs as an independent child process and receives its configuration through stdin. No intermediate configuration file is written to disk.
- Hev owns the Android VPN file descriptor and forwards traffic to Xray's loopback SOCKS5 inbound.
- Core readiness is published as soon as the group session is prepared. The first URL-test round starts asynchronously in the background, followed by scheduled measurements and the configured result trust duration. Fallback primaries receive recovery checks after a working backup starts the countdown; active-traffic stalls can also trigger confirmation. Explicit proxy-page and dashboard measurements are user initiated.
- Core status and traffic statistics are queried over plaintext gRPC on a dynamically allocated `127.0.0.1` port. Standard output and error are streamed to the UI and may be exported.

### Configuration

- Complete JSON and YAML configurations, imported through SAF or the clipboard (`.json`, `.yaml`, `.yml`).
- A one-way sanitizer removes desktop/root-oriented fields and adapts inbounds, routing rules, DNS bootstrap hosts, logging, and outbound transport settings for Android. See [Configuration Overrides and Removals](./configuration.md).
- App saving and dashboard display independently filter one core log stream. Log-level changes and browser reconnects preserve the running core and business connections. The core continuously produces Debug logs for immediate viewing; App Warning primarily reduces file writes, while detailed generation still consumes processing resources. `Auto` follows the global level and defaults to `warning`. Access/DNS switches apply at the next core start. The Logs tab supports search, export, and clear, with saved entries retained after shutdown. Ingestion filters the exact Shadowsocks deprecation notice and preserves connection warnings and errors.
- The built-in [scripta](https://github.com/YuKongA/scripta) editor supports syntax highlighting, search and replace, undo/redo, and a quick-symbol bar.

### TUN Backend

Settings selects Hev TUN, Simple TUN, or Xray TUN. Hev and Simple TUN forward the Android VPN interface to the local SOCKS entry; Xray TUN receives its VPN descriptor directly. Simple TUN uses IPv4, while Hev and Xray TUN retain the IPv6 option.

### Routing and Fallback

Each group has an editable list of routing targets. A structured rule `outboundTag` matching one of these names enters that group. Legacy Lua rules are migrated during initial loading. Internal measurements use a dedicated authenticated inbound and a measurement copy of each physical outbound with `targetStrategy: AsIs`. Business relay redials retain the selected node's configured `targetStrategy`. Measurement copies preserve transport, front-proxy, and simple-obfs settings.

Existing workspaces assign a unique physical primary's rule name to its fallback group during migration. Groups sharing a primary use explicit bindings in the group editor.

The relay applies the group's timeout to each initial connection attempt, including its final candidate. A working backup response starts the primary recovery interval. A successful scheduled primary check returns new connections to primary. Established streams retain their current transport; application retries create connections using the current group selection.

HTTP latency uses two HEAD requests through the selected node. The first prepares the connection; the second measures request writes through response headers. Each measurement has an isolated connection pool, and each node has its own dispatcher. The supplied timeout covers setup and both requests. Native UI and zashboard use the same measured request RTT.

The first-response deadline starts when client payload is available, so idle browser preconnections remain idle. Initial payload replay retains up to 256 KiB; larger transfers stay on their current transport. Retrying application payload before a response can repeat an operation at the destination, while TLS handshake replay occurs before application requests. Relay waits for initial health-check readiness are bounded by the group's timeout. Connection telemetry covers sessions owned by the fallback relay.

For response-watched established TCP streams, an upload arms a response wait using the group timeout. Downstream progress clears the wait, with ongoing socket writes retaining confirmation coverage. Expiry triggers a real HTTP check through that leaf's `AsIs` measurement outbound, bounded separately by the group timeout. A current negative result closes the stalled stream; group selection changes only while its path and measurement state remain current. Sibling streams on an already failed node can close after their own confirmation. A healthy confirmation keeps the stream open; a still-blocked write retains timed checks. Idle streams remain passive. Established UDP sessions retain their existing transport-error behavior.

Existing encrypted streams require an application reconnect to use another node; their established transport remains tied to its original peer. A destination-specific block can coexist with a successful check to the health URL, so this mechanism confirms health-path failures rather than guaranteeing recovery for every destination or application. A working backup response starts primary recovery timing, and successful recovery changes subsequent connections.

### Local Clash API Lifecycle

During the first measurement round, URL-test groups place measured healthy members first and then try pending members in configured order. Setup and first-response deadlines apply to each attempt. Once measurements finish, latency and result expiry determine the candidates.

When enabled, the local API starts during service startup, before the first round of group health checks. Core readiness is reported immediately after session setup; initial URL tests run in the background. Candidate waits for initial measurement readiness remain bounded by the group timeout. Enable, port, and secret changes during startup or connected operation restart only the API listener; Xray and business streams stay running. With the service stopped, settings are saved for its next start. zashboard exposes live connections and independently filtered logs through this listener.

Settings opens zashboard through `ZashboardLauncher`, which reads the current API port and secret each time. Setup credentials reside in the browser URL fragment for the local controller. Local API requests retain bearer/token authentication. Secret access is serialized across initial callers. The dashboard keeps only outbound mode, status, speed, and memory cards.

Domain-based DoH endpoints receive an exact-name bootstrap resolver in the assembled runtime configuration. Settings → DNS exposes the bootstrap DNS IP, initially `223.5.5.5`; runtime queries use direct TCP to this IP on port 53. Saving applies it to the running core, and standalone measurements use the same preference. Explicit hosts mappings and terminal exact-name DNS rules pointing to independent resolvers retain priority. The DoH URL and ordinary DNS policies remain intact.

System DNS entering the TUN now uses the same global DNS/DoH resolver. The App manages the internal receiving address, replacing manual TUN DNS fields. Standard server-list configurations enable native parallel queries; equivalent-policy upstreams race for the first valid result while domain policy priorities remain in effect. The native DNS outbound resolves A/AAAA and returns empty successful responses for other record types. Application-managed encrypted DNS and explicit DNS scripts retain their own behavior.

### Rule Files & Scripts

- Import and replace the standard `geoip.dat` and `geosite.dat` files.
- Import arbitrary custom `.dat` files and reference entries with `ext:<file>:<tag>`.
- Configure independent update URLs per custom rule file. Downloads run in the background and are validated before installation.
- **Lua Scripts**: Import `.lua` scripts (supported since Xray-core v26.10.10+), referencable directly via relative paths (e.g. `"script": "routing.lua"`).

### Android Integration

- The Android app layer (UI + `TProxyService`) runs in a single process. Service state and log streams are delivered through in-memory `StateFlow` / `SharedFlow`.
- The dashboard presents a green readiness check, separate upload/download speed panels, and application/core resident-memory totals. Memory uses RSS for both processes and refreshes with the visible dashboard; app memory remains visible with the core stopped.
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
