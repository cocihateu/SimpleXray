# Configuration Overrides and Removals

**English** | **[中文](./configuration_CN.md)**

SimpleXray Groups separates imported global configuration from the node/group workspace. Legacy SAF file import preserves the supplied bytes. The editor applies its formatting pipeline on save; runtime combines the workspace with global settings, applies the preference-aware sanitizer once, and then injects statistics and group routing.

Settings exposes structured DNS controls for `queryStrategy`, servers, and hosts. Inbounds and logs have dedicated categories; routing lives on the Rules page. Proxy groups are created with the Proxies page add button and managed through its overflow menu. Each group's add menu imports nodes into that group.

At runtime the final configuration is always fed to Xray through stdin (`-config stdin:`), so a file named `config.json` in the working directory cannot shadow it.

The core receives the assembled workspace after preference overrides.

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
| `log.loglevel` | Overwritten with the app log level; when set to `Auto` and missing/empty, set to `"warning"`. |

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
| `https://...alidns.com` DoH servers | Static hosts `223.5.5.5` and `223.6.6.6` are injected if absent. |
| Outbound `tlsSettings.echConfigList` starting with `http://` or `https://` | Removed. |
| TCP-based proxy outbounds (`vless`, `vmess`, `trojan`, `shadowsocks`, `socks`, `http`) | Missing `streamSettings.sockopt` values are injected: `tcpKeepAliveIdle=15`, `tcpKeepAliveInterval=3`, `tcpUserTimeout=15000`, plus custom sockopt `TCP_KEEPCNT=3` unless already present. Existing user values are not overwritten. |

Group health checks use the application's HTTP call deadline. Primary recovery uses the group's configured interval after a working fallback connection, while standby URL-test groups use their own interval and result trust duration.

Manual group latency measurements use HTTP round trips through Xray. With the VPN stopped, a temporary Xray process and group relay retain per-node DNS and chain settings; both close when measurement ends. The UI reports pending, measuring, successful latency, or failure.

Structured rules live in `rules_workspace.json`. Legacy Lua rules are migrated during initial loading; runtime routing uses Xray JSON. Every business target enters a shared local relay, including individual nodes and direct traffic. Internal health checks select physical nodes through a dedicated entry. Rule edits use Xray `adrules`; topology changes restart the core.

Simple TUN uses IPv4 and SOCKS authentication mode `noauth`. Hev and Xray TUN retain their existing IPv6 and authenticated entry options.

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
