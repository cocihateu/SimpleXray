# Differences from Upstream

**English** | **[中文](./upstream-differences_CN.md)**

This document compares the current fork against the frozen upstream snapshot `4c78901`. It describes architecture and behavior only; build/core version pins are maintained in [`version.properties`](../version.properties), [`gradle/libs.versions.toml`](../gradle/libs.versions.toml), [`gradle/wrapper/gradle-wrapper.properties`](../gradle/wrapper/gradle-wrapper.properties), and [`.github/workflows/verify.yml`](../.github/workflows/verify.yml). The bundled zashboard version and archive hash are pinned in [`tools/fetch-zashboard.ps1`](../tools/fetch-zashboard.ps1).

| Area | Upstream snapshot (`4c78901`) | Personal Fork |
|-|-|-|
| **Process & Configuration Delivery** | Separate Xray subprocess receives its configuration through stdin | Single-process Android app layer (UI + `VpnService`) drives an independent Xray child process over stdin; native Xray TUN uses the JNI launcher, while Hev/SimpleTUN use `ProcessBuilder` and forward traffic through their own SOCKS5 path. APK is `arm64-v8a` only |
| **Traffic & IPC** | `hev-socks5-tunnel` reads the Android VPN fd and forwards to Xray's local SOCKS5 inbound; statistics use a dynamic loopback gRPC port | Data plane is selected in Settings among Xray TUN, Hev, and SimpleTUN; service state and logs use in-memory `VpnStateHub`, while statistics use a dynamic `127.0.0.1` gRPC port |
| **Node Import** | JSON configurations, `vless://` links, and `simplexray://config/` links | Clipboard/SAF imports add nodes to the current group: supported share links and Base64 subscriptions, Xray JSON outbounds, Mihomo YAML/JSON proxies, and supported sing-box JSON outbounds. These entry points do not replace global settings or rules |
| **Rule Files** | Embedded `geoip.dat` and `geosite.dat` files, with local replacement and URL updates for these two files | Retains the standard rule-file management and adds arbitrary custom `.dat` files, `ext:` file references, per-file update URLs, validation, and background updates |
| **Configuration Assembly** | JSON formatting with removal of `log.access` and `log.error` | Separate global, group, and structured-rule stores are assembled into runtime Xray JSON, then adapted for Android inbounds, managed DNS, bootstrap resolution, logging, statistics, and group forwarding |
| **Build System** | Legacy `ndkBuild` (`Android.mk`) and standard Gradle configuration | CMake (`CMakeLists.txt`) with Android 16 KB page-alignment linker options, Version Catalogs, and Plugins DSL. Toolchain and dependency versions are not duplicated here |
| **UI & Layout** | Standard Material 3 UI | Xiaomi HyperOS / MIUI-inspired UI implemented with `compose-miuix-ui`, with adaptive layouts for phones and large screens, NavigationRail support, and Android 12+ dynamic colors |
| **Persistence & Communication** | ContentProvider-backed `SharedPreferences` and `Gson` | Direct lightweight `SharedPreferences` with `kotlinx.serialization`; UI and background service communicate reactively via in-memory `StateFlow` / `SharedFlow` |
| **Core Components** | Xray-core launcher with `hev-socks5-tunnel` as the only TUN backend | Xray-core launcher plus selectable Hev (C/lwIP), SimpleTUN (in-tree Zig), and native Xray TUN backends; external component revisions are pinned in `version.properties` |
| **ABI Packaging** | `arm64-v8a` and `x86_64` split APKs, plus a universal APK | `arm64-v8a` APK only |
| **TUN Backend Setting** | No Xray TUN backend setting | `Xray TUN`, `SimpleTUN`, and `Hev Socks5 Tunnel` selector, defaulting to `Hev Socks5 Tunnel` |

## Current workflow and compatibility

- **Configuration** manages local/subscription sources and their owned nodes; **Proxies** handles member selection, measurements, and runtime group settings. **Settings → Rules** edits ordered structured rules and opens rule resources. The add/overflow menus and exact import formats are described in [Configuration](./configuration.md) and [Node imports](./xray-imports.md).
- Rules directly target groups, nodes, or built-ins. `GLOBAL` keeps using the default group; `DIRECT`/`direct` and `REJECT`/`block` are unchanged. Old alias targets migrate once to their groups, and renames maintain related targets. There is no alias editor or API. See [Rules](./rules.md).
- The full-configuration and Lua editor pages are removed, not the existing data. First-run store initialization retains legacy global/node/group data and converts supported old rule patterns; unsupported scripted logic may need manual rule adjustment.
- DNS has query strategy, DoH bootstrap, global servers, split servers, and hosts entries without resetting saved defaults. DNS saves reload a running core; inbound sniffing `routeOnly` changes require a core restart.
- The APK embeds zashboard at `http://127.0.0.1:<port>/ui/` (default `9090`). Static assets need no API authentication or external hosted page; controller HTTP/WebSocket operations require the secret. The App launcher supplies setup in a browser fragment; typing the URL requires backend and secret setup on first use. The listener and service must run, and live data requires the core. See [Clash API](./clash-api.md).
- Fallback groups combine real-traffic observation, multi-destination strong failures, paired health checks, queued signals and progressive cooldowns. Xray accepts ordered local SOCKS connections; real responses and health checks provide remote failure evidence. Nested checks resolve child alternatives first, and measurement queue time stays outside the network timeout. Demotion closes stagnant connections while active downloads continue. Selection and health-order changes both start backup recovery; results apply to new streams. URL-test groups retain their independent schedule.

---

[Back to project README](./README.md)
