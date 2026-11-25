---
description: Architecture and development context for LucX in the SimpleXray source tree.
---

# LucX Project Context

LucX is an Android VPN/proxy client using one Xray core with Hev, SimpleTUN, or Xray TUN. The installed name is **LucX**, application ID is **`com.LucX`** (including debug builds), and Kotlin/JNI namespace is **`com.simplexray.re`**. The Gradle root project and repository retain the SimpleXray name. All paths below are relative to this source root, not its parent directory.

## Stack and Data Model

- Android **minSdk 34**, **targetSdk 37**, compile platform **37.2** (`android-37.2`); `arm64-v8a` only.
- Kotlin, Compose/Miuix, ViewModels/controllers, coroutines and flows; C/JNI for Hev and process launching, Zig for SimpleTUN.
- Preferences use Android `SharedPreferences`; global configuration, groups/nodes and rules use JSON files in the app's private files directory. These are separate stores, not one editable full configuration.
- UI and `TProxyService` share a process. `VpnStateHub` publishes state/logs in memory; Xray runs as a child process. Status, statistics and routing updates use plaintext gRPC on a dynamically allocated loopback port.
- Xray owns proxy protocols/routing. Per-node UDP-over-TCP and simple-obfs adaptations use Kotlin relays; there is no second Mihomo core, and Gradle excludes `libmihomo.so`.

## Active UI and Source Map

Android source paths in this section start at `app/src/main/kotlin/com/simplexray/re/`.

| Area | Entry points and responsibility |
| --- | --- |
| Navigation | `ui/scaffold/AppScaffold.kt`, `ui/navigation/BottomNavGraph.kt`, `ui/screens/MainScreen.kt`: five bottom tabs, **Dashboard / Proxies / Configuration / Logs / Settings**. Wide layouts keep the bottom bar; no navigation rail. |
| Dashboard | `ui/screens/DashboardScreen.kt`; `viewmodel/DashboardController.kt` handles status/measurements, `ExitInfoController.kt` handles exit IP/location. Dashboard refresh is an exit lookup, not node latency. |
| Shared cards | `ui/components/DashboardComponents.kt`: `DashboardCard` uses 30 dp corners, a 2 dp shadow and theme colors; shared by Dashboard, Proxies and Configuration. |
| Proxy behavior | `ui/screens/ProxiesScreen.kt`; `GroupSettingsScreen` in `ui/screens/GroupManagementScreen.kt`: selection strategy, ordered members, failover/recovery, test URL, front proxy, default group. These routes keep Proxies selected. |
| Sources and nodes | `ui/screens/WorkspaceConfigScreen.kt`, `NodeConfigurationScreen.kt`, `GroupSourceEditorSheet.kt`, `NodeEditorSheet.kt`; group management is in `GroupManagementScreen.kt`. Local/subscription sources, imports, refresh and node editing belong under Configuration. |
| Settings | `ui/screens/SettingsScreen.kt`: General, TUN, Inbounds, Rules, DNS, Panel, About. Content lives in the corresponding `Settings*Section.kt` files. `SettingsGlobalConfigSection.kt` is the DNS form; `SettingsNetworkSection.kt` contains LAN controls under TUN. |
| Rules/resources | `ui/screens/RulesScreen.kt`, `SettingsRuleFilesSection.kt`; `viewmodel/TrafficRulesController.kt`, `RuleFileController.kt`. Settings → Rules → Rule resources opens the DAT manager and keeps Settings selected. |
| Controllers | `viewmodel/MainViewModel.kt` is the facade; feature work belongs in `GroupWorkspaceController`, `TrafficRulesController`, `SettingsController`, `DashboardController`, `ExitInfoController`, `RuleFileController`, `VpnServiceController`, `AppIconController`, or `UpdateController`. |
| Files/preferences | `data/source/FileManager.kt` delegates DAT work to `RuleFileStore`/`FileUriHelper`; `LogFileManager` handles saved logs. `prefs/Preferences.kt` owns preference access. |
| Subscriptions | `common/subscription/NodeImportConverter.kt` parses supported share links, base64 subscriptions, Xray/sing-box outbounds and Mihomo YAML nodes; `SubscriptionService.kt` refreshes subscription groups. Unsupported combinations produce reasons rather than a second-core fallback. |

There is no active full-configuration editor/import screen, Lua editor/import sheet, or separate Mihomo import sheet. Node import extracts nodes; it does not install the source document's global DNS/inbounds/rules. Advanced node JSON edits one outbound. Node forms preserve unrelated fields and additional server/user entries.

## Stores and Live One-Time Migrations

These compatibility readers remain live even though the old editors were removed. Do not delete them solely because there is no editor UI.

- `common/config/GlobalConfigStore.kt` owns `global_config.json` and seeds missing/empty global data from an existing selected or discovered configuration, otherwise defaults.
- `common/config/GroupWorkspace.kt` defines groups, members and nodes; `GroupWorkspaceStore.kt` owns `groups_workspace.json`. When absent, it extracts existing full-config outbounds and uses `GroupProfileStore.kt`/`GroupProfile.kt` to translate old per-config group profiles.
- `common/config/RuleWorkspaceStore.kt` owns `rules_workspace.json`. Initial migration reads existing global/legacy routing rules or supported Lua patterns through `LegacyLuaRuleImporter.kt`, otherwise defaults. This is limited pattern translation, not arbitrary Lua execution. Preserve migration notices and review migrated rules.
- Runtime targets are `GLOBAL`, `direct`, `block`, or real group/node names. Routing aliases are not a runtime feature. Group schema 0 → 1 rewrites old alias targets to their owning groups before discarding aliases; rule schema 2 marks the rewrite as complete so interrupted migration is not applied twice. The two stores share a lock.
- `common/ConfigUtils.kt` delegates Android sanitization, statistics injection and inbound extraction to `common/config/ConfigSanitizer.kt` and `ConfigInjector.kt`; `ConfigCodec.kt` parses saved JSON/YAML for runtime and migration. `WorkspaceFiles.kt` protects versioned workspace writes and atomically replaces files.

## Runtime Boundaries and Contracts

- `service/TProxyService.kt` owns the VPN descriptor, foreground lifecycle and JNI entry points. `XrayProcessRunner.kt` assembles global settings/workspace/rules, sanitizes once with real `Preferences`, injects statistics/group plumbing, and starts Xray. Keep `-config stdin:` in both `ProcessBuilder` and `app/src/main/cpp/xray_exec.c`; otherwise a working-directory `config.json` can shadow the intended configuration.
- Feed stdin on a separate IO job while draining stdout. A synchronous write-then-read sequence can deadlock on large configurations. All start actions use the unified start path and respect `disableVpn`.
- `service/OutboundGroupConfig.kt` maps nodes/groups into Xray configuration. `GroupCoreSession.kt` owns the live session; `GroupRuntimeController.kt` owns member selection/health, `FallbackRelayServer.kt` handles relayed connections, and `XrayRoutingEditor.kt` applies routing changes. `SimpleObfsRelay.kt` and `RelayDatagramTransport.kt` implement the supported per-node adaptations.
- **Fallback contract**: local SOCKS setup follows candidate priority and represents Xray acceptance. Real traffic and comparative health results determine remote failures. TCP first-response expiry supplies a strong signal; UDP silence supplies a weak signal solely for comparative checking. Streams stay open for policy verification, and a current replacement permits bounded initial replay. Three distinct strong-failure destinations within 60 seconds or paired health failure/success can demote a member. Strong signals queue across checks; owner/generation checks include the current nested route. Child alternatives resolve before ancestor escalation, using results completed during the ancestor evaluation ahead of subsequent queued checks. Measurement timeouts begin after a semaphore permit is acquired. Cooldowns progress from 30 seconds to 5 minutes. Demotion samples received bytes for two seconds and preserves active downloads. Selection and health-order changes both start configured backup recovery, which applies to new streams. URL-test groups retain their independent schedule.
- `service/HttpLatencyMeasurement.kt` is shared by native UI and panel: one HEAD request, request-write-to-response-headers RTT; the overall timeout also includes setup. A successful health URL cannot prove every destination works.
- `TunnelBackendController.kt` manages Hev/SimpleTUN; `VpnBuilderFactory.kt` builds Android routes; `SocksHealthMonitor.kt`, `VpnNetworkMonitor.kt`, `GeoUpdateScheduler.kt`, `VpnNotificationHelper.kt`/`VpnNotificationUpdater.kt`, and `VpnWakeLock.kt` retain their focused lifecycle roles.
- **SimpleTUN scope**: IPv4-only, dotted-decimal no-auth SOCKS5, fixed MTU 1500, no IPv6 route/address or IP fragment reassembly. Hev/Xray TUN retain the IPv6 option. See `docs/simpletun_spec.md` before changing native paths.
- App-managed SOCKS binds must stay loopback-only; health/update clients use `127.0.0.1`. Credentials must be both set or both empty; existing config-side credentials can be adopted once. SimpleTUN strips primary SOCKS auth and ignores app-side credentials.
- Health checks must respect non-interactive/Doze state without the partial wake lock. Startup deadlines use `StartupProbeDeadline`/`SystemClock.uptimeMillis()`, excluding suspended time, not wall time.
- Missing bundled GEO files are a supported runtime state: `RuleFileStore.extractAssetsIfNeeded` skips them, the UI shows missing resources, and restore can download the configured URL. Keep the active-service `GeoUpdateScheduler` in addition to WorkManager; background scheduling can be restricted and GitHub downloads may need the proxy.
- App log saving and panel display independently filter the same Debug-level core stream without restarting it. Access/DNS logging switches apply on the next core start. Lower saved-log levels reduce writes, not all logging work.

## Embedded Panel

`service/ClashApiServer.kt` serves the Clash-compatible API and bundled **zashboard v3.30.0** on one `127.0.0.1` listener. `ClashApiPreferences.kt` defaults to disabled, port **9090**, with an automatically generated secret. `service/ZashboardAssets.kt` serves public static GET/HEAD requests under `/ui/`; `/` and `/ui` redirect there. API and WebSocket requests still require the secret.

`common/ZashboardLauncher.kt` supplies controller host/port/secret via a `/setup` URL fragment; opening `/ui/` manually instead requires the first API connection to be configured with the secret from Settings → Panel. Treat both the launch URL and copied secret as credentials. Service/controller must be running. Enable/port/secret changes restart only the listener while starting/connected, or are saved for the next service start.

`tools/fetch-zashboard.ps1` installs the hash-pinned distribution into `app/src/main/assets/zashboard/`. Keep its `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses/zashboard/LICENSE` in the APK. Offline assets eliminate the external frontend dependency, not network requirements for traffic or optional panel downloads.

## Native Inputs, Build and Verification

- `app/src/main/cpp/CMakeLists.txt` builds Hev and JNI; `app/src/main/proto/` contains gRPC definitions. Keep JNI names bound to their declaring service classes and inject lifecycle callbacks into helpers.
- `third_party/hev-socks5-tunnel/` and `third_party/miuix/` supply the tunnel and UI source. Read the Miuix-scoped instructions before modifying it. `third_party/scripta/` is retained source, not an included app dependency.
- `third_party/simpletun/` is in-tree, not a submodule. Build its Android archive with `zig build android` before Gradle. CMake imports `zig-out/android/prebuilt/arm64-v8a/libsimpletun.a` and fails if absent. Run host `zig build test` on Linux/WSL separately.
- Supply Xray at `third_party/xray-prebuilt/arm64-v8a/libxray.so`. Gradle does not fetch/build/verify Xray, and there is no `ensureXrayCore` task. Prepare GEO and zashboard assets explicitly too.
- `version.properties` records Xray tag/commit/ZIP SHA-256, Hev version, Go and NDK versions. GEO uses rolling upstream `latest`. `tools/sync_versions.py --check` validates property shapes, Hev version/revision and the QUIC Go minimum; it does not download/hash-check Xray or validate all dependencies.
- See `docs/building.md` / `docs/building_CN.md` for JDK 25 + 21, Gradle 9.9.1, Android 37.2/NDK, CMake 3.22.1, Zig 0.16.0, input preparation and private signing. The `LucX` branch fixes version name `1.0.0-68` and version code `10001068` in `app/build.gradle.kts`, shared by local builds and GitHub Actions. The Windows helper is `tools/build-groups-apk.ps1`; it uses prepared local tooling/signing material and assembles debug. Output: `app/build/outputs/apk/debug/LucX-arm64-v8a.apk`.
- Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug` (Windows) or `./gradlew :app:testDebugUnitTest :app:assembleDebug` (Unix) with the documented environment. Tool checks: `python -m unittest discover -s tools/tests -v`, `python tools/sync_versions.py --check`. Do not overlap builds in the same source tree.
- Focused existing tests include `ZashboardAssetsTest`, `ConfigConversionTest`, `ConfigSanitizerPrefsTest`, `FileManagerTest`, `SocksHealthMonitorPolicyTest`, and `StartupProbeDeadlineTest`. JVM tests do not replace device VPN/failover verification or inspecting the final APK assets/signature.
- `groups-apk.yml` prepares inputs and assembles signed debug, but does not run tests or verify the Xray ZIP hash. `verify.yml` does not prepare Xray/GEO/zashboard. `release.yml` still expects the old APK filename and omits Xray/panel provisioning; it is not a supported LucX release path. There is no No-GEO build variant. See the build guide for details.

## Coding Guidelines

1. Keep Compose state hoisted and composition non-blocking; use Miuix components and `MiuixTheme` colors rather than hardcoded colors.
2. Keep `MainViewModel`, `TProxyService`, `ConfigUtils`, `FileManager` and `SettingsScreen` as facades. Config transforms belong in `common/config/`, file I/O in stores, lifecycle work in service helpers, feature actions in controllers and settings content in section files.
3. Use coroutines/flows for asynchronous work. Preserve the foreground-service notification and Android background-start restrictions; headless starts may require bringing the activity forward first.
4. Preserve protobuf compatibility and regenerate stubs when definitions change. Native edits require ABI, POSIX socket/TCP/UDP and backend-specific understanding.
5. Keep VPN behavior and responsiveness intact. Do not infer real-device success from builds, unit tests or historical benchmarks.

## Benchmark Tools and Preserved Results

- `tools/benchmark.py`/`presets.py`: throughput, idle memory, bufferbloat, 60s stability, weaknet, CPS and QUIC smoke. Light uses Hev/SimpleTUN on Wi-Fi MTU 1500 without baseline; full includes Hev/Xray/SimpleTUN, physical baseline, Wi-Fi/USB/loopback and applicable MTU 9000 cases.
- `tools/common/` handles ADB, device paths, iPerf, datasets, themes and netem; `tools/suites/` implements the suites; `idle_bench/`, `microbench/`, and `quic/` provide probes. `BenchmarkService` is exported in debug only. The ADB helper still targets the old `com.simplexray.re.debug` package/launcher and needs alignment before driving LucX; see `tools/README.md`.
- `docs/benchmark/<profile>/{data,charts,report}/` preserves the `778g` and `8-elite-gen-5` datasets. `generate_charts.py` and `generate_mega_dashboard.py` create charts. `update_benchmark_doc.py` targets an older Chinese Section 3.1 format; current English device reports are maintained manually.
- Preserve historical SingTUN labels/results as historical measurements, not a current backend or evidence that a new build passed. Keep reports objective, distinguish measurements from hypotheses, state hardware/network/software limits, and omit private local network identifiers.

### Visualization and Report Conventions

- Use the backend palette in `tools/common/theme.py`: Hev `#555555`, historical SingTUN `#00ADD8`, Xray TUN `#e11d48`, SimpleTUN `#10B981`; JetBrains Mono (`prop_regular`, `prop_bold`, `prop_medium`).
- Self-labeled categorical bars need no redundant legend; shared-axis multi-series lines retain a centered legend. Avoid duplicate Y-axis labels in paired panels, order workloads naturally, and explain inapplicable cases instead of empty spacer rows.
- Avoid ` · ` in titles; add `(Higher is Better)` / `(Lower is Better)` where applicable. Reports follow environment → methodology → results → discussion → limitations → summary. Summarize trends rather than repeating every table cell.

## Device Testing, Commits and Repository Policy

- For Android/VPN/TUN changes, keep work uncommitted until the user confirms current real-device testing. Prior benchmark runs do not waive this requirement. Commit/push only when requested.
- Commit format: `<type>(<scope>): <concise English subject>`, no trailing period; single line unless documenting `BREAKING CHANGE:`. Core dependency bumps use `chore(deps): bump ...`; user-visible features use `feat(...)`/`ui(...)`; performance uses `perf(...)`; fixes use `fix(...)`; refactors/build/CI/docs/tests/style are Maintenance. Tooling/CI/lint fixes are Maintenance; other dependency bumps are Dependencies.
- This is a personal experimental project, not a supported community product. Compatibility and public API stability are not overriding maintenance requirements.
- Immutable releases/tags may retain older history. Do not use `git describe` or `merge-base` to infer release ancestry; compare trees/contracts. `feat/simpletun` is historical and its source is already reflected in `main`.
