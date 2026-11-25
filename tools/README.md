# LucX Build and Benchmark Tools

This source tree retains the SimpleXray name; the current Android app is **LucX** (`com.LucX`, including debug builds), with Kotlin/service classes under `com.simplexray.re`. Commands below run from the project root.

## Windows APK Helpers

- `build-groups-apk.ps1 [-BuildNumber N]` assembles a persistently signed debug APK at `app/build/outputs/apk/debug/LucX-arm64-v8a.apk`. It expects JDK 25/21, Gradle 9.9.1, Android SDK/NDK, CMake 3.22.1 and private signing material under `$env:LOCALAPPDATA\SimpleXrayBuild`. It does not install the toolchain, run tests, fetch Xray/GEO, or build SimpleTUN.
- `fetch-zashboard.ps1` downloads **v3.30.0**, checks SHA-256 `1f91c014e61e768240a86ad0a9bd565b247ec5a0e26b125fb5d19076682905b7`, and replaces `app/src/main/assets/zashboard/` only after validating `index.html` and `THIRD_PARTY_NOTICES.md`. Preserve the separate `assets/licenses/zashboard/LICENSE` when packaging.
- `configure-groups-signing.ps1` creates/reuses local signing material **and uploads it to GitHub repository secrets**. It is not a local-only setup helper; use it only for an intended repository with an explicit `-Repository OWNER/REPO`. Never commit signing credentials.

See [Building from Source](../docs/building.md) / [从源码构建](../docs/building_CN.md) for Platform 37.2, minSdk 34/targetSdk 37, native/GEO prerequisites, signing and CI limitations. Xray must be supplied at `third_party/xray-prebuilt/arm64-v8a/libxray.so`; SimpleTUN requires `zig build android` before Gradle. There is no automatic Xray download task.

## Benchmark Compatibility

The scripts and published device datasets are retained for reproducibility, but **the current runner is not ready to drive the renamed app unchanged**. `common/adb.py` still hardcodes `APP_PKG = "com.simplexray.re.debug"` and the old launcher component. Align its package/component targets with the installed `com.LucX` debug APK before running device suites; there is no package override CLI flag. `BenchmarkService` remains debug-only.

Current backend IDs are `hev`, `xray`, and `simpletun`. Historical SingTUN data/labels remain valid records of earlier measurements, not a current backend. The light/full presets request HTTP/3 smoke as well as the other suites. The full preset includes applicable MTU 9000 cases; current SimpleTUN itself remains fixed at 1500. Existing benchmark reports are not verification of the latest APK.

## Benchmark Files & Architecture

- `benchmark.py`: Unified master CLI runner orchestrating physical throughput (Wi-Fi, USB, Loopback), idle flow retention, and advanced suites (Bufferbloat, 60s stability, CPS, Weaknet). Supports `--preset light` and `--preset full`.
- `presets.py`: Formal benchmark contract definitions (`light` vs `full`).
- `generate_charts.py`: Unified publication-grade 16:9 dashboard generator (Wi-Fi, Bufferbloat, Stability, Idle Memory, CPS, Weaknet, Scheme 2 Memory Attribution).
- `generate_mega_dashboard.py`: Master 8-archetype 3-row mega infographic generator.
- `common/`: Core shared libraries:
  - `adb.py`: ADB communication, device discovery, `BenchmarkService` headless control, `tun0` interface verification, CPU/PSS sampling.
  - `device.py`: Symmetric profile path resolution (`778g`, `8-elite-gen-5`) mapping data, chart, and report locations.
  - `iperf.py`: Host/device iPerf3 server management, arguments builder, JSON parsing.
  - `dataset.py`: JSON dataset loading, multi-round arithmetic clean averaging, `version.properties` extraction.
  - `theme.py`: Unified `PALETTE`, JetBrains Mono typography, standard light theme styling.
  - `netem.py`: Linux host `tc netem` delay/loss network condition emulator.
  - `logging.py`: Terminal colors, logger utilities, and subprocess wrapper.
- `suites/`: High-cohesion benchmark test suites:
  - `throughput.py`: Physical media throughput (Wi-Fi, USB, Loopback; MTU 1500/9000, P=1/P=8, TCP/UDP).
  - `idle_memory.py`: Stepped 0 -> 1000 idle connection retention and memory slope probe.
  - `bufferbloat.py`: Saturated TCP download with concurrent TCP echo probe.
  - `stability.py`: 60s continuous 8-stream TCP download stability, decay rate, and CV%.
  - `weaknet.py`: Bidirectional TCP throughput under host netem delay and packet loss.
  - `cps.py`: Short-lived TCP connection-per-second handshake stress suite.
  - `quic.py`: Optional HTTP/3 smoke suite through the active TUN backend.
- `update_benchmark_doc.py`: Legacy non-destructive synchronizer targeting the older Chinese-format Section 3.1 report layout; current English per-device benchmark reports are maintained manually.
- `sync_versions.py`: Validates the six `version.properties` keys/formats, Hev source version/Git revision where available, and the Go minimum in `quic/go.mod`. Without `--check`, it can update the Hev entry; it does not resolve Xray tags, hash binaries, check zashboard/GEO, or synchronize every dependency.
- `update_licenses.py`: Rewrites the license document, invoking Gradle when its export is absent or `--refresh` is set. Its current template still describes SimpleXray/scripta and omits the bundled zashboard notices; do not use it as an authoritative current inventory without updating the template/export.
- `tests/`: Python `unittest` coverage for benchmark preset parsing and `version.properties` validation.
- `idle_bench/`: High-performance, zero-external-dependency Go probe (cross-compiled for Linux `amd64` and Android `arm64`).
- `microbench/`: Standalone Linux user-namespace microbenchmark harness (Scheme 2).
- `quic/`: Small Go HTTP/3 server/client helper used by `suites/quic.py`.

## Prerequisites

1. **ADB**: Android Debug Bridge installed and accessible in `PATH`.
2. **Device**: Android 14+ ARM64, connected via USB or Wi-Fi debugging, with the LucX debug APK installed and VPN permission granted. Resolve the package/component mismatch above first; bring the app to the foreground for service commands on Android 14+.
3. **Python**: Python 3.10+; `matplotlib` and `numpy` for chart generation.
4. **iPerf3**: Installed on both host and Android device (`/data/local/tmp/iperf3`).
5. **Linux host**: `iproute2` and permission to use `tc netem` for weaknet; user namespaces for `microbench`. These requirements are separate from the Windows APK helper.
6. **Go**: Use `GO_VERSION` from [`version.properties`](../version.properties) (currently `1.27.3`); the optional QUIC helper's `go.mod` requires at least 1.26.0.
7. **Host Xray binary** (for `microbench` `xray` case): set `XRAY_BIN`, add `xray` to `PATH`, or place it at `tools/bin/xray` (gitignored).

## Usage

Device commands below assume the ADB compatibility issue is resolved. In a POSIX shell, set `WIFI_SERVER_IP` to the host address reachable from the device and replace `<adb-serial>` with the selected device. `--wifi-server-ip` is required for every runner invocation and has no built-in host default. With `--preset full`, `--usb-server-ip auto` probes for an `enx`/`rndis`/`usb` interface and aborts if none is found.

### 1. Preset-Driven Benchmark (Recommended)

#### Light Preset (Hev and SimpleTUN on Wi-Fi MTU 1500, no baseline)
```bash
python3 tools/benchmark.py \
  --preset light \
  --device <adb-serial> \
  --device-profile 778g \
  --wifi-server-ip "$WIFI_SERVER_IP"
```

#### Full Preset (All backends, physical baseline, Wi-Fi / USB / Loopback, MTU 1500 & 9000)
```bash
python3 tools/benchmark.py \
  --preset full \
  --device <adb-serial> \
  --device-profile 8-elite-gen-5 \
  --wifi-server-ip "$WIFI_SERVER_IP"
```

### 2. Manual Suite Invocation

```bash
# Run specific suite
python3 tools/benchmark.py --mode throughput --wifi-server-ip "$WIFI_SERVER_IP" --duration 10
python3 tools/benchmark.py --mode idle_memory --wifi-server-ip "$WIFI_SERVER_IP" --network tcp --backends hev,simpletun

# Weak-network TCP download through host netem (requires root/sudo on the host)
python3 tools/benchmark.py \
  --mode weaknet \
  --device <adb-serial> \
  --device-profile 778g \
  --wifi-server-ip "$WIFI_SERVER_IP" \
  --backends hev,simpletun \
  --netem-losses 3,5,8 \
  --netem-delay 50 \
  --weaknet-duration 10

# Short-lived TCP connection rate (CPS)
python3 tools/benchmark.py \
  --mode cps \
  --device <adb-serial> \
  --device-profile 778g \
  --wifi-server-ip "$WIFI_SERVER_IP" \
  --backends hev,simpletun \
  --cps-workers 4,8 \
  --cps-connections 5000

# HTTP/3 smoke through the active TUN backend
python3 tools/benchmark.py \
  --mode quic \
  --device <adb-serial> \
  --device-profile 778g \
  --wifi-server-ip "$WIFI_SERVER_IP" \
  --backends simpletun
```

### 3. Publication Dashboard Generation

```bash
# Generate all standardized 16:9 WebP charts directly into docs/benchmark/<profile>/charts/
python3 tools/generate_charts.py --device-profile 778g --preset light
```

### 4. Tooling Unit Tests

```bash
python3 -m unittest discover -s tools/tests -v
```
