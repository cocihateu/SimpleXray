# Configuration and Runtime Behavior

**English** | **[中文](./configuration_CN.md)**

LucX manages global settings, node groups, and traffic rules separately. At startup it assembles them, applies Android preferences, and adds the internal routing and statistics entries. The resulting Xray JSON is passed through stdin (`-config stdin:`).

## Pages

| Page | Purpose |
| --- | --- |
| Dashboard | Start/stop the service; choose rule, global, or direct mode; view status, exit information, speed, and memory. |
| Proxies | Select group members, measure HTTP latency, and open runtime group settings. |
| Configuration | Manage local/subscription sources and their owned nodes. |
| Logs | Search, export, or clear saved logs; select the App saving level and access/DNS log options. |
| Settings | General, TUN, Inbounds, Rules, DNS, Panel, and About. |

### Configuration: sources and nodes

Group tabs and horizontal swipes switch the displayed group; this browsing position is independent of the default routing group. The add menu offers **Import from clipboard**, **Import from file**, **Add node manually**, and **New group**. The overflow menu offers **Group management**, **Rename group** for local groups or **Subscription settings** for subscription groups, and **Update all subscriptions** when subscriptions exist. Long-press a tab to edit its name or subscription URL. Group headers provide measurement and, for subscriptions, refresh.

Node lists show the nodes owned by that group, not all members referenced by its proxy strategy. Tap a node to edit it; its menu offers copy and delete. Imports add nodes to the current group without replacing global DNS, inbounds, or traffic rules. See [supported imports](./xray-imports.md).

### Proxies: runtime group settings

Cards start collapsed and show the group strategy, selected member, and active leaf when running. Expand a card to select a member, measure the group, or open **Group settings**. Search and expand/collapse actions are in the top bar.

Group settings owns manual selection, fallback, and URL-test strategies; member references and ordering; health URL/timing; front proxy; and **Save and set as default group**. Members can be nodes, other groups, `direct`, or `block`. An empty explicit member list uses the group's owned nodes. A front proxy forms the path device → front proxy → group node → destination.

Manual selections are saved even while stopped. Automatic groups allow temporary member selection while running. Historical latency values are not proof that a node is currently connected. Measurements can also run while stopped, using a temporary Xray session.

`GLOBAL` uses the selected default group; `DIRECT`/`direct` and `REJECT`/`block` remain built-in targets. Choosing a default group also points the last enabled `Match` rule at `GLOBAL`, without changing specific rule targets. See [traffic rules](./rules.md) for target resolution and rename/migration behavior.

### Fallback and recovery

- Healthy fallback members use real traffic as their failure signal. URL-test groups retain their independent measurement schedule.
- TCP and UDP enter Xray through ordered local SOCKS connections, with up to three candidate attempts. The group setting bounds local connection setup. Xray's SOCKS reply acknowledges local acceptance; remote health decisions use real traffic and health-URL results.
- The first successful payload write starts an RTT-based 4–10 second observation window. TCP expiry reports one strong suspect signal; UDP silence reports one weak signal for comparative health checking. The stream stays open for policy verification, and a slow request through a single member can keep waiting. The first real response ends observation and clears destination evidence outside an active cooldown. Actual resets, refusals, broken pipes and I/O timeouts are strong signals; EOF is a weak signal.
- Strong failures from three distinct destination hosts within 60 seconds demote a member even while its health URL responds. UDP silence contributes solely to comparative health checking. Other suspect events compare the current member and a peer. Nested fallback checks resolve child alternatives before escalating to an ancestor, and results belong to the current child route. Ancestors consume child results completed during their evaluation while subsequent queued checks continue independently. Measurement timeouts start after a measurement slot becomes available.
- Traffic-triggered strong and weak checks use 3-second and 15-second minimum gaps. Strong signals arriving during a check or its gap schedule another check. Ancestor branch evaluation refreshes older child results once for its current evaluation. State owners and generation tokens keep queued work bound to its current member.
- Demotion cools a member for 30, 60, 120, 240, then at most 300 seconds. Repeated failures during one cooldown retain its deadline. Real replies preserve active cooldowns; a real reply after five stable minutes reduces the backoff level. Candidate order follows alive members outside cooldown, alive members in cooldown, other members outside cooldown, then remaining members. Manual member selection clears that member's failure state.
- Selecting a backup, including a switch caused by health-result ordering, starts the configured primary-recovery interval. Recovery checks use members whose cooldown has elapsed; successful recovery applies to new connections.
- Demotion samples matching live connections for two seconds and closes those whose received-byte counter stays unchanged. Active downloads keep their transport. Before the first response, an I/O failure or a response timeout after selection of another leaf permits bounded replay. A real response takes priority over a pending retry. Established TCP/TLS sessions reconnect through the application after a stalled transport closes.

## Storage and Editing

| Storage | Contents |
| --- | --- |
| `global_config.json` | DNS, inbounds, logging, and other global Xray settings. |
| `groups_workspace.json` | Node outbounds, source URLs, strategies, ordered references, front proxies, and the default group. |
| `rules_workspace.json` | Ordered structured rules with IDs, types, payloads, targets, and enabled state. |

Node forms cover VLESS, VMess, Trojan, Shadowsocks, SOCKS, and HTTP. They expose credentials, `targetStrategy`, transport, and applicable TLS/REALITY settings. Shadowsocks also offers native UDP, UoT v1/v2, and simple-obfs HTTP/TLS. Forms edit the original outbound while preserving extra fields; **Advanced JSON** edits the complete node outbound, including other supported native protocols. It is not a whole-configuration editor.

Settings → Rules provides structured editing, enable/disable, drag or up/down ordering, and JSON import/export. **Rule resources** opens the `.dat` resource manager. Rule import replaces the rule list; node import is a separate operation.

There is no full-configuration or Lua editor in the current UI. First initialization can recover global settings, nodes, groups, and supported rules from existing legacy data. Removing those editor pages does not delete that data. Legacy Lua handling is a one-time conversion of supported patterns, not arbitrary script execution; review any migration notice and the resulting rules.

## Settings Sections

| Section | Controls |
| --- | --- |
| General | Theme, colors, icon, recent-app visibility, and keep-awake behavior. |
| TUN | Per-app VPN policy, disable-VPN mode, backend, MTU, IPv6, and LAN bypass. |
| Inbounds | SOCKS endpoint/credentials, system HTTP proxy, and sniffing `routeOnly`. |
| Rules | Traffic rules and the Rule resources child page. |
| DNS | Query strategy, DoH bootstrap address, global/split DNS servers, and hosts mappings. |
| Panel | Local controller enable switch, port, secret generation/copy, and bundled zashboard launcher. |
| About | App/core information and update controls. |

Access/DNS logging switches and the App saving level are on **Logs**, not in a separate Settings category.

## DNS and Sniffing

Settings → DNS lists **Query strategy · queryStrategy** (`UseIP`, `UseIPv4`, `UseIPv6`), **DoH bootstrap DNS**, **Global DNS**, **Split DNS servers**, then **Static mappings · hosts**. Global DNS edits entries without domain conditions; split DNS edits entries with nonempty `domains`. Both views update the same `dns.servers` list and retain its order.

Server forms expose address (IP / DoH / DoT), optional port, query strategy (inherit global or one of the three strategies), `expectIPs`, and `clientIp`. Split servers also expose domain lines and **Only handle matching domains** (`skipFallback`). Hosts entries accept a domain or match expression and one or more target IPs/domains. Reorganizing these settings does not reset existing server entries or change saved defaults.

A fresh global configuration uses `UseIP` with `https://1.1.1.1/dns-query` and `223.5.5.5`. Runtime enables native `enableParallelQuery` for server-list DNS configurations; domain selection and result filtering still apply. The DoH bootstrap preference defaults to `223.5.5.5`: endpoint hostnames receive exact-name bootstrap resolution through direct TCP port 53. Existing explicit hosts and suitable terminal bootstrap rules take priority. Saving DNS settings or the bootstrap address automatically reloads a running core.

The VPN advertises internal DNS receiving address `198.18.0.2`, plus `fc00::2` when IPv6 is enabled on Hev/Xray TUN. App TCP/UDP port-53 traffic entering TUN, SOCKS, or HTTP client inbounds is routed to Xray's DNS outbound before business rules. A/AAAA queries use the global resolver; other record types receive successful empty answers under the core's default DNS-outbound policy. Application-managed encrypted DoH/DoT is not converted into this port-53 path.

**Inbounds → routeOnly** defaults to `false` and applies to runtime SOCKS, HTTP, and Xray TUN sniffing:

- Enabled: a sniffed hostname is used for routing, while the original connection destination is retained.
- Disabled: sniffing can also replace the connection destination with that hostname. The selected node's `targetStrategy` then determines destination resolution.
- This is not a DNS interception switch. **Restart the service to apply a change**; opening a new connection in the existing core is not enough.
- Runtime configuration adds `full:cloudflare-ech.com` to `domainsExcluded`, preventing the browser's shared outer ECH name from replacing the website destination. Existing exclusions, `routeOnly`, and node `targetStrategy` remain intact; TLS certificate verification is not relaxed.

## TUN and Local Inbounds

| Backend | Behavior |
| --- | --- |
| Hev Socks5 Tunnel (default) | Forwards VPN traffic to the local SOCKS inbound; supports the configured IPv6 and SOCKS authentication options. |
| Xray TUN | Uses the Android VPN descriptor in an Xray `tun` inbound; fills missing MTU/name/sniffing settings. |
| SimpleTUN | Uses IPv4, MTU 1500, and an unauthenticated SOCKS path. IPv6 and MTU editing are disabled for this backend. |

The primary SOCKS inbound uses the App address and port. Only loopback literals are accepted; username and password must both be set or both be empty. SimpleTUN removes primary SOCKS authentication for its no-auth path. The system HTTP proxy uses an existing HTTP inbound's actual endpoint, or an injected local inbound if none exists. Missing/wildcard non-TUN listen addresses are restricted to loopback.

LAN bypass affects VPN route exclusions and the system HTTP proxy's IPv4 exclusion list. Applications using their own proxy settings retain their own proxy policy. Reconnect after changing TUN, LAN bypass, or inbound preferences.

## Runtime Overrides and Applying Changes

| Area | Runtime behavior |
| --- | --- |
| Rule assets | The App manages bundled/custom `.dat` files, validation, downloads, and update schedules under Rule resources; core `geodata` management is not used. |
| Logging | Removes `log.error`; access logging uses `none` when disabled; `dnsLog` follows the App switch. The main core emits Debug for independent consumer filtering. |
| APIs/statistics | Installs loopback `StatsService`, `RoutingService`, and `HandlerService`, outbound upload/download statistics, and internal business/measurement routing. |
| TCP proxy transports | Supplies missing keepalive/timeout settings (`tcpKeepAliveIdle=15`, `tcpKeepAliveInterval=3`, `tcpUserTimeout=15000`, `TCP_KEEPCNT=3`) without replacing explicit values. |
| Node measurement | Uses HTTP HEAD request RTT through measurement outbounds with `targetStrategy: AsIs`; business outbounds keep the node's saved strategy. |

Rules, rule enablement, routing mode, and default-group selection apply through the Xray routing API. Node changes and changes to group tags/front proxies or relay targets reload the core. DNS saves also reload it. Changing the local controller's enable state, port, or secret restarts only that listener while the service is starting or running; stopped-service changes are saved for the next start.

App log level changes affect subsequent saved entries immediately, without reloading the core. `Auto` uses the global log level, defaulting to `warning`; `None` pauses saving. Saved logs survive service stops. The dashboard's separate log level defaults to `error`; an explicit `/logs?level=...` overrides it for that connection. Access/DNS log switches require the next core start. Lower saving levels do not stop the core from generating Debug output.

## Bundled zashboard

The APK includes **zashboard v3.30.0**. Enable Settings → Panel's controller, start the service, wait for connection, and choose **Open bundled zashboard**. It opens `http://127.0.0.1:<port>/ui/` (default port `9090`); `/` redirects there. Static page files are available without API authentication, but HTTP/WebSocket controller operations still require the secret.

The App launcher passes backend settings and the secret in the browser fragment. If you type the local URL manually, configure backend `http://127.0.0.1:<port>` and paste the secret copied from Panel on first use. After changing the port or secret, reopen from the App or update the saved backend. HTML, scripts, and styles are bundled and need no hosted webpage or network download. The local listener serves the page only while enabled and running with the service; live statistics and controls also require the running core. See [Clash API](./clash-api.md).

---

[Back to project README](./README.md)
