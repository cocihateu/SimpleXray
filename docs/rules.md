# Traffic Rules and Workspace Store Architecture

## Overview

This document outlines the traffic rules architecture implemented for SimpleXray to replace legacy `routing.lua` scripting with reactive, structured rule configuration.

## Core Components

### 1. `TrafficRule` Data Model

`TrafficRule` is the primary unit of routing logic:

```kotlin
public data class TrafficRule(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val payload: String,
    val target: String,
    val enabled: Boolean = true
)
```

#### Supported Rule Types
- `Domain`: Exact domain matching (`full:<domain>`).
- `DomainSuffix`: Domain suffix matching (`domain:<domain>`).
- `DomainKeyword`: Substring/keyword matching (`keyword:<keyword>`).
- `GeoSite`: GeoSite category list reference (`geosite:<list>`).
- `IPCIDR`: Direct IP CIDR address or subnet matching (`ip` array).
- `GeoIP`: GeoIP country/region code matching (`geoip:<code/list>`).
- `DstPort`: Destination port matching.
- `Network`: Network layer protocol matching (`tcp`, `udp`).
- `Match`: Universal catch-all rule matching `tcp,udp`.
- `Xray`: Raw JSON object representing compound or specialized Xray routing criteria.
- Domain regular expressions retain their `regexp:` fields inside the `Xray` rule type.

#### Xray Conversion

Each `TrafficRule` can be converted into an Xray field rule via `rule.toXrayRule(outboundTag)`:
- Sets `"type": "field"`.
- Preserves rule ID in `"ruleTag"`.
- Emits proper domain/ip/port/network arrays.
- Targets select the shared business relay; `"GLOBAL"` selects the default group, and direct/block targets select their physical builtins.
- Compound match conditions retain their multi-field conjunction semantics inside `type = "Xray"`.

### 2. `RuleWorkspaceStore`

Manages disk persistence and reactive updates for rules in `rules_workspace.json`:

- `observe(): Flow<List<TrafficRule>>`: Emits the updated rule list on changes.
- `load(): List<TrafficRule>`: Reads the current persisted rule list from disk.
- `save(rules: List<TrafficRule>)`: Atomically writes the rule list to disk and updates the companion version.
- `update(transform: (List<TrafficRule>) -> List<TrafficRule>): List<TrafficRule>`: Atomic read-modify-write helper.
- `RuleWorkspaceStore.version: StateFlow<Long>`: Emits timestamps on every write.
- `migrationNotice: String?`: Readable property containing migration notices or warnings.
- Query filtering helpers: `activeRules()`, `ruleById(id)`, `rulesByTarget(target)`, and `rulesByType(type)`.

### 3. `LegacyLuaRuleImporter`

Automates migration from legacy `routing.lua` files:

- Extracts `geodata.BuildDomainMatcher` and `geodata.BuildIPMatcher` definitions.
- Translates conditions and return statements in `HandleRoute` into ordered `TrafficRule` rows.
- Preserves compound conditions (such as UDP + 443 QUIC blocking) via `Xray` rule payload objects.
- When arbitrary Lua scripts contain unparsable logic, generates readable descriptions in `migrationNotice` and applies default rules ending with a terminal `Match GLOBAL`.

The supplied `HandleRoute` migration keeps nested domain branches and their return order. Repeated IP checks merge into the same structured criteria. Legacy scripted routing uses `IPOnDemand` so domain rules run before IP resolution and the terminal default rule.

Runtime edits add required relay outbounds through `api ado`, then replace routing through `api adrules --append=false`. The API listener retains StatsService, RoutingService, and HandlerService. Importing a full Xray configuration updates the rule workspace as well as global settings.

The rules page imports and exports structured JSON through Android's document picker. Imports also accept Xray `routing.rules`. Long-press dragging updates the displayed order, scrolls at viewport edges, and saves the final order when released. Group targets carry a small group label in the list and target selectors.

Choosing the default group in the app or the Clash API GLOBAL selector also changes the last enabled Match rule to target GLOBAL. This connects the catch-all rule to the selected default group while preserving specific domain, IP, and other rule targets.
