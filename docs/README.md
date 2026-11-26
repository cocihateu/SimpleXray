# LucX

<div align="center">
<img src="images/lineal.svg" alt="LucX icon" width="150">

**English** | **[中文](./README_CN.md)**

<img src="https://app.fossa.com/api/projects/git%2Bgithub.com%2FReRokutosei%2FSimpleXray.svg?type=shield" alt="FOSSA Status" width="150">

<sub>Android 14+ (API 34) | arm64-v8a | MPL-2.0</sub>
</div>

LucX is a personal fork of [SimpleXray](https://github.com/lhear/SimpleXray), built around one Xray proxy core, a shared node/group workspace, and native configuration controls. It offers Xray TUN, Hev TUN, and Simple TUN backends. The installed name and application ID are **LucX** and **`com.LucX`**; the Kotlin/model namespace remains `com.simplexray.re`.

> [!NOTE]
> This fork is maintained for personal use and experimentation. Issues and pull requests are not accepted, and no support is provided. Use upstream or maintain your own fork if you need a supported client.

## Main Features

- **Groups and nodes** — local or subscription groups, manual selection, URL tests, nested fallback groups, front proxies, and per-node destination DNS strategy.
- **Native editing** — node forms with advanced outbound JSON, structured routing rules, DNS, and inbound controls.
- **Offline panel** — bundled zashboard served by the local Clash-compatible controller, without an external dashboard website.
- **Rule resources** — `geoip.dat`, `geosite.dat`, custom `.dat` files, `ext:` references, update URLs, and background updates.
- **Miuix interface** — five bottom tabs, shared 30 dp rounded cards on Dashboard/Proxies/Configuration, phone/tablet content sizing, Light/Dark/System themes, and dynamic colors.
- **Android integration** — per-app proxy controls, local SOCKS5 access, network handover handling, and 16 KB native-library page alignment.
- **Benchmark tooling** — headless test suites, report/chart generation, and published device datasets.

See [Differences from Upstream](./upstream-differences.md) for the structural comparison.

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

## Pages and Navigation

| Bottom tab | Purpose |
| --- | --- |
| Dashboard / 仪表盘 | Start/stop, readiness, exit IP/location, rule/global/direct mode, traffic, and memory. Refresh looks up exit information, not node latency. |
| Proxies / 代理 | Select group members, inspect the active node, search, expand/collapse group cards, measure HTTP latency, and open proxy group settings. |
| Configuration / 配置 | Create local/subscription groups, import or edit nodes, and refresh subscriptions. |
| Logs / 日志 | Saved-log level, access/DNS log switches, search, export, and clear. |
| Settings / 设置 | General, TUN, Inbounds, Rules, DNS, Panel, and About categories. |

Configuration's **+** menu offers clipboard/file node imports, manual node creation, and group creation. Its overflow menu opens group management, renaming/subscription settings, and subscription refresh. Node cards open editing and offer copy/delete actions. Proxy group settings, opened from Proxies, owns member ordering, selection strategy, timeout/recovery settings, front proxy, and the default-group action.

Settings → Rules opens the native rule editor; its **Rule resources** action opens the DAT manager. Rules and Rule resources keep Settings selected. Group management keeps Configuration selected; proxy group settings keeps Proxies selected. Navigation remains a bottom bar on wide screens, with width constraints on applicable content rather than a separate navigation rail.

## Nodes and Configuration

- Clipboard/file imports and subscription URLs accept supported share links, base64 subscriptions, Xray/sing-box outbound JSON, and Mihomo YAML nodes. Unsupported protocol/plugin combinations are reported. Import support is conversion-specific, not a promise to implement every feature of the source format. See [Native Imports](./xray-imports.md).
- Forms cover VLESS, VMess, Trojan, Shadowsocks, SOCKS, and HTTP, with transport, TLS/REALITY, destination DNS strategy, and applicable Shadowsocks UDP-over-TCP/simple-obfs options. Advanced JSON edits one complete Xray outbound. Form edits retain unrelated fields and additional server/user entries.
- Xray handles the proxy protocols and routing. Kotlin relays implement the supported UDP-over-TCP and simple-obfs adaptations; LucX does not run a second Mihomo core or package `libmihomo.so`.
- The app does not offer a full-configuration import/editor or a Lua import/editor. Node import can extract outbounds from a configuration document; it does not install that document as the active global configuration. DNS, inbounds, groups, and rules have their own controls.
- On initialization, stores can read existing full configurations, group profiles, and supported Lua routing patterns to seed the workspaces. This is migration support, not a Lua execution/editor workflow; arbitrary Lua logic cannot be translated faithfully and migrated rules should be reviewed.

Runtime configuration is assembled from the global settings, groups, and structured rules, adapted for Android, and fed to Xray through stdin. See [Configuration Overrides and Removals](./configuration.md).

## Rules and Resources

The native rule editor manages type, matching content, target, enabled state, and ordering, including drag reordering. It supports domain/exact/suffix/keyword matches, IP CIDR, GeoIP, GeoSite, destination ports, network, catch-all, and raw Xray matching objects. JSON import **replaces the current rule list**; export writes structured JSON. YAML node imports are separate from this rule workflow.

Targets are `GLOBAL` (the default group), `direct`, `block`, or an actual group/node name. To route through a fallback group, select that group rather than its physical primary. There is no routing-alias setting. Loading older workspace data rewrites stored alias references to their owning groups before discarding the aliases.

Settings → Rules → Rule resources manages the bundled `geoip.dat`/`geosite.dat` and custom `.dat` files. Custom resources support `ext:<file>:<tag>` references and independent update URLs; downloads are validated before installation. Missing bundled GEO files can be restored or downloaded. GitHub downloads require network access and may need an active proxy.

## Group Selection and Fallback

Manual groups retain saved selections. URL-test groups run initial measurements asynchronously after session readiness and repeat them on their configured schedule. Proxies and group-node pages provide explicit HTTP measurements; Dashboard refresh is only an exit-information lookup.

The displayed fallback member is the group default. Even when destinations share a local UDP endpoint, one can switch to a backup while the others keep their healthy upstreams. A failed health check while a peer succeeds, or strong failures on three distinct hosts within 60 seconds, can demote a member for the group. See [fallback and recovery](./configuration.md#fallback-and-recovery).

Cleanup preserves transfers making progress and healthy idle sessions. Before the first reply the relay can replay a first flight of at most 256 KiB on another upstream; an established stream on a failed path needs the application to reconnect.

HTTP latency covers one proxied HEAD exchange through response headers, including connection setup. The native UI and zashboard share the measurement implementation.

## TUN, DNS, and Logs

- **Hev TUN** is the default C/lwIP backend; **Simple TUN** is the in-tree Zig backend. Both forward the VPN interface to Xray's local SOCKS5 inbound. **Xray TUN** receives the Android VPN descriptor through the JNI launcher.
- Simple TUN is IPv4-only, uses unauthenticated SOCKS5, and fixes MTU at 1500. Hev and Xray TUN retain the IPv6 option. See [SimpleTUN Architecture](./simpletun_spec.md).
- Settings → TUN → Bypass LAN excludes private ranges from the VPN and applies matching IPv4 exclusions to the registered system HTTP proxy. Reconnect to apply changes. Apps choosing their own proxy must manage their own exclusions; a core `IPCIDR` rule to `direct` instead forwards through the core.
- Settings → DNS manages global/per-domain resolvers, hosts, query strategy, and the DoH bootstrap DNS IP (initially `223.5.5.5`). The app manages the TUN DNS receiving address. Saving DNS settings can restart the running core; application-managed encrypted DNS is separate.
- The app and service share one Android process and publish state/logs through in-memory flows. Xray is a child process; status and traffic statistics use plaintext gRPC on a dynamically allocated loopback port.
- App log saving and panel log display independently filter the same core stream. Changing their filter levels does not restart Xray. The core generates Debug logs for immediate viewing, so a lower saved-log level reduces writes rather than all logging work. Access/DNS switches apply at the next core start; saved logs remain available after shutdown.
- The foreground notification shows the current main proxy node or Direct in direct mode. Dashboard memory shows application/core RSS, including app memory while the core is stopped. The manifest declares `ACCESS_LOCAL_NETWORK` for the Android 17 local-network permission model.

## Bundled zashboard and Local API

1. Open **Settings → Panel**, enable the controller, and choose its port (default `9090`). The controller is disabled by default.
2. Start the service and wait for the connected state.
3. Choose **Open bundled zashboard**. The browser opens `http://127.0.0.1:<port>/ui/` on the **same loopback listener and port** as the API. The launcher supplies the current controller connection details and secret in the URL fragment.

The static UI is bundled in the APK and can load without contacting an external dashboard site. It is not available while the service/controller is stopped. `/` and `/ui` redirect to `/ui/`. Opening the page manually does not supply API credentials: on the first manual connection, enter the controller address/port and the secret copied from Panel. Static GET/HEAD requests are public; API/WebSocket access still requires the secret. Treat the launch URL and copied secret as credentials.

Controller enable/port/secret changes restart only the API listener while the service is starting or connected; with the service stopped, they are saved for its next start. The panel exposes group selection, latency, connections, and independently filtered logs. See [Clash API](./clash-api.md).

Build assets live in `app/src/main/assets/zashboard/`. [`tools/fetch-zashboard.ps1`](../tools/fetch-zashboard.ps1) downloads the pinned **v3.30.0** distribution, checks its SHA-256, and installs it with the bundled notices. Offline UI assets do not make proxy traffic, subscriptions, or exit-IP lookups work without a network.

## Benchmark and Tooling

- `tools/benchmark.py` provides throughput, idle-memory, bufferbloat, stability, weak-network, CPS, and QUIC suites through a debug-only headless `BenchmarkService`.
- The ADB helper still targets the old `com.simplexray.re.debug` package and launcher component, not `com.LucX`; it needs alignment before it can drive the current APK. See [tooling prerequisites and limitations](../tools/README.md).
- Chart and report generation are included. Published datasets remain historical measurements, not verification of the current build: [Snapdragon 8 Elite Gen 5](./benchmark/8-elite-gen-5/report/benchmark_report.md) and [Snapdragon 778G](./benchmark/778g/report/benchmark_report.md).

## Known Issues

### Stale local SOCKS5 connections after network changes

Clients such as Telegram may retain a TCP connection to `127.0.0.1:<socksPort>` after its upstream transport becomes stale. Shorter outbound keepalive/user-timeout settings help detect blackholed transports, but cannot guarantee that every client recreates its local connection. Reconnect the client, toggle the VPN/core, or use a client with its own liveness detection. Existing encrypted streams may also require an application retry after failover.

## Build Locally

See [Building from Source](./building.md) for provisioning and signing. The build uses **minSdk 34**, **targetSdk 37**, and compile platform **`android-37.2`**; only `arm64-v8a` is packaged. The local PowerShell helper expects the prepared toolchain under `%LOCALAPPDATA%\SimpleXrayBuild` (JDK 25/21, Gradle 9.9.1, Android SDK/NDK, and CMake 3.22.1) plus its persistent local signing credentials.

Before assembling, prepare `third_party/xray-prebuilt/arm64-v8a/libxray.so`, build SimpleTUN with `zig build android` in `third_party/simpletun`, and supply the GEO/dashboard assets. Plain Gradle does **not** download Xray or build the Zig archive. From the project root, with those prerequisites prepared:

```powershell
.\tools\fetch-zashboard.ps1
.\tools\build-groups-apk.ps1
```

The LucX branch builds **1.0.0-68**, version code **10001068**, as defined in `app/build.gradle.kts`. The helper produces a persistently signed debug APK at **`app/build/outputs/apk/debug/LucX-arm64-v8a.apk`**. Prepare the toolchain and private signing credentials as described in the build guide, and reuse the existing signing key for upgrades.

To build the debug APK directly, use `gradlew.bat :app:assembleDebug` on Windows or `./gradlew :app:assembleDebug` on Unix in the configured environment. The separate, optional `:app:testDebugUnitTest` task uses test sources that still reference older APIs. Device-dependent VPN/fallback behavior still requires real-device verification.

## Documentation

- [Building from Source](./building.md)
- [Native Imports](./xray-imports.md)
- [Clash API](./clash-api.md)
- [Configuration Overrides and Removals](./configuration.md)
- [Differences from Upstream](./upstream-differences.md)
- [SimpleTUN Architecture Specification](./simpletun_spec.md)
- [Benchmark Reports](./benchmark/8-elite-gen-5/report/benchmark_report.md)
- [Changelog](../CHANGELOG.md)

## Upstream and Third-Party

Built on [Xray-core](https://github.com/XTLS/Xray-core), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), [compose-miuix-ui](https://github.com/compose-miuix-ui/miuix), and [zashboard](https://github.com/Zephyruso/zashboard), and derived from [SimpleXray](https://github.com/lhear/SimpleXray). The [scripta](https://github.com/YuKongA/scripta) source tree remains in `third_party/`, but the current app does not include its full-config/Lua editor UI.

Application icon assets use free Cookie Icons provided by [Magnific](https://www.magnific.com).

## Privacy and License

See the [Privacy Policy](./PrivacyPolicy_EN.md), [Disclaimer](./Disclaimer_EN.md), and [Open Source Licenses](./third_party_licenses.md).

Unless otherwise stated, this project is distributed under the Mozilla Public License 2.0 (MPL-2.0). See [`LICENSE`](../LICENSE) for the full text.
