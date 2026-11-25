# Structured Traffic Rules

LucX edits traffic rules under **Settings → Rules**. Rules are ordered, first-match business-routing entries stored in `rules_workspace.json`. The page provides add/edit/delete, target selection, enable/disable, long-press drag ordering, up/down controls, and JSON import/export. **Rule resources** opens management of bundled and custom `.dat` files.

## Rule Types

Each rule has `id`, `type`, `payload`, `target`, and `enabled`. Disabled rows remain saved but are not compiled into business routing.

| Type | Payload | Xray matching field |
| --- | --- | --- |
| `Domain` | Exact hostname | `domain: ["full:..."]` |
| `DomainSuffix` | Domain and its subdomains | `domain: ["domain:..."]` |
| `DomainKeyword` | Hostname substring | `domain: ["keyword:..."]` |
| `GeoSite` | Geosite category | `domain: ["geosite:..."]` |
| `IPCIDR` | IP address or CIDR | `ip` array |
| `GeoIP` | GeoIP category/country | `ip: ["geoip:..."]` |
| `DstPort` | Port expression, such as `443` or `1000-2000` | `port` |
| `Network` | `tcp` or `udp` | `network` |
| `Match` | Empty | `network: "tcp,udp"` |
| `Xray` | JSON object of matching conditions | Preserves the supplied fields |

Domain, geodata, and IP list types accept comma- or newline-separated values. `Xray` supports compound conditions, regular expressions, and custom resource references, for example `{"network":"udp","port":"443"}` or `{"domain":["ext:custom.dat:list"]}`. Different fields in a compound rule retain Xray's conjunction semantics. The App supplies `type: field`, `ruleTag`, and the selected outbound; the payload is not a node or whole configuration.

Put specific rules before `Match`. A newly added rule is inserted before the first `Match` row. Ordering matters even if another `Match` exists later.

## Targets and Modes

| Target | Meaning |
| --- | --- |
| `GLOBAL` | The workspace's selected default group. |
| Group name | That group's runtime strategy and selected member. |
| Node name | That physical node, rather than its owning group's strategy. |
| `direct` / `DIRECT` | Built-in direct target. |
| `block` / `REJECT` | Built-in reject target. |

Reserved built-in names resolve first; otherwise a group takes precedence over a same-named node. The native target picker lists `GLOBAL`, `direct`, `block`, groups, and nodes, with duplicate names shown once. Rename a same-named node if a rule needs to address it separately from the group. Unknown targets remain in stored rules but cannot be compiled into runtime routing, so update rules after deleting their targets.

- **Rule mode** compiles enabled rules in their saved order, followed by the default-group catch-all.
- **Global mode** sends business traffic to the default group without evaluating the saved business rules.
- **Direct mode** uses the direct target instead.

The App-managed DNS interception and internal measurement routes remain ahead of business routing in all three modes. A user rule does not override this port-53 interception.

Set the default group in **Proxies → Group settings → Save and set as default group**, or select a workspace group through the Clash API `GLOBAL` selector. This also changes the **last enabled `Match`** row to target `GLOBAL`. Specific rules are untouched; an earlier catch-all still takes precedence. If no explicit default is valid, the first workspace group is used.

## Import and Export

The document picker accepts JSON in these forms:

- An array of structured rules or Xray field rules.
- An object containing a `rules` array.
- An Xray object containing `routing.rules`.
- A single structured rule or Xray rule object.

**A successful import replaces the entire rule list.** Importing `routing.rules` reads only rules; it does not import nodes, DNS, inbounds, or other configuration sections. Export writes a JSON object with `version` and `rules`. The rule importer uses JSON, not YAML or Lua.

Compound Xray rules remain a single `Xray` row. Domain rules containing `regexp:` retain that expression in an `Xray` payload. Simpler domain/IP arrays may become multiple rows with the same target. Use valid Xray matching criteria; successful JSON parsing alone does not validate every core-specific field.

## Applying Changes

Rules, enablement, mode, and default-group changes are applied while running through Xray's routing API. Required relay outbounds are added before routing replacement. The Rules page reports application success or failure. Node content and relay-topology changes may reload the core instead.

Group and node business targets, including direct/reject, use the shared local relay. Measurement traffic has separate internal entries. The built-in `GLOBAL`, direct, and reject targets remain available independently of source-group management.

## Existing Data and Renames

Renaming a group updates its group references, front-proxy references, default selection, and rules targeting that group. Renaming a node updates its node references and rules that actually target the node; rules using a same-named group remain attached to that group.

Older stored routing-alias targets are rewritten **once** to their owning group names before the alias data is dropped. The migration covers explicit stored aliases and unambiguous previously inferred primary-node targets, preserves reserved built-in targets, and uses workspace/rule schema markers to avoid repeated rewriting. Current rules target groups or nodes directly; there is no routing-alias editor or API.

On first initialization without a rule workspace, the App can recover rules from existing global/legacy Xray data and supported legacy Lua patterns. This is data migration, not a script editor or a general Lua interpreter. Unsupported scripted logic can produce a migration notice and fallback rules; review the resulting structured list before relying on it.

See [configuration behavior](./configuration.md) for DNS interception, sniffing, and restart requirements, and [Clash API](./clash-api.md) for rule inspection and enablement endpoints.
