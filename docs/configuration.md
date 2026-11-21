# Configuration Overrides and Removals

**English** | **[中文](./configuration_CN.md)**

SimpleXray Groups separates imported global configuration from the node/group workspace. Legacy SAF file import preserves the supplied bytes. The editor applies its formatting pipeline on save; runtime combines the workspace with global settings, applies the preference-aware sanitizer once, and then injects statistics and group routing.

Settings exposes structured DNS controls for `queryStrategy`, servers, and hosts. Inbounds and logs have dedicated categories. Configuration owns group creation, node management, group settings, and the Rules subpage; Proxies owns runtime selection and measurement.

At runtime the final configuration is always fed to Xray through stdin (`-config stdin:`), so a file named `config.json` in the working directory cannot shadow it.

The core receives the assembled workspace after preference overrides.

## Pages and Editors

The five main pages are Dashboard (仪表盘), Proxies (代理), Configuration (配置), Logs (日志), and Settings (设置). Group-node, group-settings, and Rules subpages keep Configuration selected in the parent navigation.

Proxies presents every group as a rounded card, initially collapsed, with its name, selected member/active leaf, Selector/Fallback/URLTest label, and expansion chevron. Expanded choices support runtime selection and actual HTTP latency. The topbar provides search, expand, collapse, and measure. Measurement targets the selected group or the first group and uses the existing single-job measurement flow. With the service stopped, fallback shows its first, primary member; selector and URL-test groups show saved choices. Stored measurements remain measurement history rather than live traffic.

Configuration lists local and subscription groups alongside a Rules entry. It owns group creation and subscription refresh. Each group opens separate node and settings pages. The node page owns clipboard/file import and add/edit/delete controls. Group settings edits source, strategy, primary and backup order, health/recovery timing, front proxy, and routing aliases, and provides Set as default. The default supplies global-mode and catch-all routing; specialized rules keep their own targets.

Node forms support VLESS, VMess, Trojan, Shadowsocks, SOCKS, and HTTP. They expose endpoint/credentials, `targetStrategy`, TCP/raw, WebSocket, gRPC, XHTTP/SplitHTTP, HTTPUpgrade, TLS and applicable REALITY fields. Shadowsocks also exposes native UDP, UoT v1/v2, and simple-obfs HTTP/TLS with Host. Form edits operate on the original outbound and preserve extra fields, additional servers/users, and advanced settings. Both flat `settings` fields and legacy `vnext`/`servers` arrays with user entries are supported. Advanced JSON edits the full outbound and provides the editor for other imported native protocols.

Rules uses the existing structured UI for type, matching content, target, enabled state, drag/up/down ordering, and import/export. Rules are stored in `rules_workspace.json` and exported as JSON. YAML serves as an import format for supported configurations and subscriptions; rule editing uses structured controls. Import validation depends on the input path; see [native imports](./xray-imports.md).

## Top-Level Fields

| Field | Action |
| --- | --- |
| `geodata` | Removed entirely; Geo data updates are managed by the app. |
| `log` | Created if missing. |
| `api` | Replaced at runtime with local `StatsService`, `RoutingService`, and `HandlerService` APIs. |
| `stats` | Replaced at runtime with an empty object. |
| `policy` | Replaced at runtime with a policy enabling outbound up/down statistics. |
| `inbounds` | Created if missing; see inbound rules below. |

## Log Block

| Field | Action |
| --- | --- |
| `log.error` | Removed. |
| `log.access` | Set to `"none"` when the app access log is disabled; removed when it is enabled. |
| `log.dnsLog` | Overwritten with the app DNS log preference. |
| `log.loglevel` | Fixed to `"debug"` at main core startup for the entire session, independently of App and dashboard levels. App `Auto` reads the global level for persistence, defaulting to `"warning"`. |

The same raw core stream feeds independent App persistence and dashboard filters. Changing the App level saves only SharedPreferences and updates the saving selector; it controls subsequent file writes and saved-log UI entries. App `warning` saves warning/error entries, and `none` pauses saving. Dashboard logging has a separate saved preference, initially `error`. An explicit `/logs?level=debug` filters that connection at Debug and takes precedence over the saved dashboard level. A connection omitting `level` dynamically reads `ClashApiPreferences.logLevel`, so `PATCH /configs` with `log-level` updates its filter while it stays connected.

All App/dashboard level changes and log stream open/close/reconnect operations cause zero `GlobalConfigStore.notifyModified` calls and zero core reloads. Detailed core generation still consumes CPU and pipe bandwidth even at App Warning; per-line `XrayLog` Logcat echo is removed. Access/DNS category switches apply at the next core start, and local persistence also applies the App threshold. Existing routing and DNS configuration reload behavior remains in place.

Saved logs remain across core restarts and shutdown, with the existing file capacity limits. The Logs tab between Configuration and Settings reads saved entries for search, export, and explicit clearing. Ingestion filters the exact Shadowsocks deprecation notice before file writes and dashboard emission; other warning/error messages retain their handling, and previously saved entries remain available.

## Geodata Block

| Field | Action |
| --- | --- |
| `geodata` | Removed entirely; rule asset lifecycle is managed exclusively by the app. |

Xray-core's built-in `geodata` block allows the core to download and update rule assets. On Android, this block is stripped by `ConfigSanitizer` for several reasons:
- **Lifecycle & Doze**: Core-driven background timers cannot reliably run under Android Doze mode or OEM background execution restrictions.
- **Proxy Routing**: Asset downloads often require routing through an active proxy tunnel (e.g. GitHub connectivity in restricted regions), which SimpleXray coordinates via its foreground VPN service and Android `WorkManager` (`GeoUpdateWorker`).
- **Asset Management**: SimpleXray handles atomic disk writes, custom `.dat` imports, fallback to bundled assets, and streaming Gzip (`.dat.gz`) decompression directly within the app's internal storage.

Configure rule files and update intervals directly in the app's **Settings > Rule Files** instead.

## Inbounds

| Field | Action |
| --- | --- |
| `tun` inbound | Preserved or injected for Xray TUN; Hev TUN and Simple TUN use the local SOCKS entry. |
| Primary SOCKS inbound (tag `socks-in`, or first SOCKS inbound) | `port` is replaced with the app SOCKS port; `listen` with the app SOCKS address (loopback only); `settings.auth` and `settings.accounts` are replaced when both app SOCKS credentials are configured. |
| Other inbounds with `listen` missing, empty, `::` or `0.0.0.0` | `listen` is replaced with `127.0.0.1`. |
| Missing SOCKS inbound | A default `socks-in` inbound is injected using the app SOCKS address/port, UDP, and configured authentication. |
| SOCKS, HTTP, and TUN `sniffing.routeOnly` | Set from Settings > Inbounds, default `false`. Changes apply on the next connection. |

## App SOCKS Settings

| Setting | Behavior |
| --- | --- |
| SOCKS inbound address | Only loopback literals are accepted (`127.0.0.0/8`, `::1`). The internal health probe and rule/update downloads connect to `127.0.0.1`, and startup fails with a visible error if a non-loopback value is persisted from an older version. |
| SOCKS username/password | Both must be set together or both left empty. Credentials containing line breaks are rejected. |
| Credentials imported from a config | When the selected config requires password auth on the primary SOCKS inbound and the app credentials are still empty, the first account is adopted into the app settings once per config. |
| Editing while the service is running | The value is saved, but the running core/backend keeps the old endpoint until the service is restarted; the UI shows a restart notice. |

## Routing Rules

| Field | Action |
| --- | --- |
| `routing.domainMatcher` | `mph` is changed to `hybrid`. |
| Rule `geosite` | Entries are moved into `domain` as `geosite:<entry>`; the original `geosite` key is removed. |
| Rule `geoip` | Entries are moved into `ip` as `geoip:<entry>`; the original `geoip` key is removed. |
| Rule `process` | Entries ending in `.exe` are removed; if nothing remains, `process` is removed. |
| Empty/invalid rules | Rules without any effective matching field are removed. |
| `geoip:private` with LAN bypass enabled | A top-priority `geoip:private` -> `direct` rule is injected if absent. |
| `geoip:private` with LAN bypass disabled | `geoip:private` entries are removed from direct rules; an empty `ip` array is removed, and rules that no longer have any effective matching field are removed entirely. |

## DNS and Outbounds

| Field | Action |
| --- | --- |
| `dns.hosts` | Created if missing. |
| `dns.enableParallelQuery` | Enabled for standard server-list configurations at runtime. DNS scripts retain their own scheduling. |
| `https://...alidns.com` DoH servers | Static hosts `223.5.5.5` and `223.6.6.6` are injected if absent. |
| Domain-based DoH endpoints | Runtime assembly adds exact-name bootstrap using direct TCP to the IP in Settings → DNS → DoH bootstrap DNS, initially `223.5.5.5`, on port 53. The preference is saved separately from the global Xray JSON and applies to the running core and standalone measurements. Explicit hosts and terminal exact-name rules pointing to independent resolvers retain priority. |
| Outbound `tlsSettings.echConfigList` starting with `http://` or `https://` | Removed. |
| TCP-based proxy outbounds (`vless`, `vmess`, `trojan`, `shadowsocks`, `socks`, `http`) | Missing `streamSettings.sockopt` values are injected: `tcpKeepAliveIdle=15`, `tcpKeepAliveInterval=3`, `tcpUserTimeout=15000`, plus custom sockopt `TCP_KEEPCNT=3` unless already present. Existing user values are not overwritten. |

Group health checks use the application's HTTP call deadline. Primary recovery uses the group's configured interval after a working fallback connection, while standby URL-test groups use their own interval and result trust duration.

Manual group latency measurements use HTTP round trips through Xray. All manual measurements, scheduled health checks, and primary recovery checks use dedicated outbound copies with `targetStrategy: AsIs`. Business connections retain the node's saved target strategy. Measurement copies retain transport, server-address DNS, and chain settings. With the VPN stopped, a temporary Xray process and group relay use the same measurement routing and close when measurement ends. The UI reports pending, measuring, successful latency, or failure.

Latency uses two HEAD requests through the selected node with an isolated connection pool. The first prepares the connection; the second measures request writes through response headers. Each node has its own dispatcher, and the supplied timeout covers setup and both requests. Native UI, scheduled group checks, standalone measurement, and zashboard share this request RTT method.

Fallback callbacks follow the selected ancestry of the responding node. An active backup retains priority until a new connection failure clears the selection. Recovery decisions and connection callbacks share the group-state lock, and measurements respect failures observed after their start time. TCP setup and first-response deadlines retain their existing behavior: first-response waiting starts with available client payload, and initial replay retains up to 256 KiB. Core readiness is published immediately after group-session setup, before the first URL-test round runs asynchronously in the background. Relay candidate waits for that measurement readiness remain bounded by the group timeout.

For response-watched established TCP streams, an upload arms a response wait using the group timeout. Downstream progress clears it, with ongoing writes retaining confirmation coverage. Expiry starts a real HTTP `AsIs` confirmation through that leaf, bounded separately by the same group timeout. A valid negative closes its stalled stream even after another connection has already switched the group; selection changes additionally require current plan, ancestry and measurement state. Newer healthy measurements supersede earlier failed checks. Healthy results preserve the stream, and idle streams remain passive. Established UDP sessions retain their existing transport-error behavior.

URL-test groups can attempt pending members in configured order during initial measurement, after already measured healthy members. Connection attempts retain setup/first-response deadlines. Failed or expired measurements retain their existing exclusion semantics.

An existing encrypted connection requires an application reconnect to use a new node. A selectively blocked destination can still pass the configured health URL, so recovery depends on the health path and application retry behavior. Backup responses start primary recovery timing, and a successful scheduled primary check returns subsequent connections to primary.

Structured rules live in `rules_workspace.json`. Legacy Lua rules are migrated during initial loading; runtime routing uses Xray JSON. Every business target enters a shared local relay, including individual nodes and direct traffic. Internal health checks select physical nodes through a dedicated entry. Rule edits use Xray `adrules`; topology changes restart the core.

Simple TUN uses IPv4 and SOCKS authentication mode `noauth`. Hev and Xray TUN retain their existing IPv6 and authenticated entry options.

TUN DNS is managed by the App; Settings exposes the global DNS/DoH upstreams. Android receives the internal DNS address `198.18.0.2` and, with IPv6 enabled on Hev/Xray TUN, `fc00::2`. Exact host routes keep these receiving addresses on the VPN, including IPv6 with LAN bypass enabled. Application UDP/TCP port 53 traffic arriving through TUN and client proxy inbounds is sent to Xray's DNS outbound before business routing. Core resolver traffic and internal node entries keep separate tags. Live rule edits and routing-mode changes retain this DNS rule.

The flow is application DNS → TUN → global resolver → answer; subsequent business connections follow routing rules. Xray's DNS outbound resolves A/AAAA using the global resolver and returns successful empty answers for other record types by default. Application-managed encrypted DoH/DoT retains its own destination.

The native parallel-query option starts all eligible upstream queries together. Adjacent upstreams with equivalent policies race for the first valid address result; later policy groups become eligible after preceding groups fail. Domain matching, skip/final rules, and result filters retain their meaning. Configure multiple upstreams with the same policy for concurrent availability. Explicit DNS scripts retain their own scheduling. DoH bootstrap handles endpoint hostnames separately.

The dashboard contains outbound mode, integrated status/exit information, network speed, and memory. Runtime selection lives on Proxies; node management and Set as default live in Configuration's group subpages. Settings contains the zashboard launcher. Memory displays App and core RSS with their sum and proportion bar, retaining the existing visible-dashboard sampling loop and shared-page accounting.

## Local Clash API Lifecycle

The enabled local API starts during service startup, before first-round group health checks. Core readiness is immediate after session setup; the initial URL-test batch runs in the background. API/core readiness and individual measurement availability therefore reflect separate states. Enable, port, and secret updates during startup or connected operation stop and start the API listener alone; the Xray process and business streams remain running. Stopped-service changes are persisted for the next start.

Settings launches zashboard through `ZashboardLauncher`, reading current API preferences at click time. Setup parameters reside in the browser fragment; local API authentication continues through bearer headers or WebSocket tokens. Secret access is synchronized across initial callers. API settings live-apply independently of opening zashboard. See [Clash API](./clash-api.md).

## Runtime Statistics Injection

| Field | Action |
| --- | --- |
| `api` | Replaced with `tag=api`, the app API listen address, and `services=["StatsService","RoutingService","HandlerService"]`. |
| `stats` | Replaced with an empty object. |
| `policy` | Replaced with a policy whose `system` enables `statsOutboundUplink` and `statsOutboundDownlink`. |
| HTTP inbound | When the app HTTP proxy is enabled, an existing HTTP inbound keeps its own endpoint and the system proxy follows its actual address/port; only when no HTTP inbound exists is `http-inbound` injected on `127.0.0.1:<httpPort>`. |

Fields not listed above are left unchanged.

---

[Back to project README](./README.md)
