# Building LucX from Source

**English** | **[中文](./building_CN.md)**

Prepare the native libraries and bundled assets, then build the `com.LucX` Android app. Commands below run from the LucX source tree root, alongside `settings.gradle.kts`.

## 1. Toolchain

The local helper targets **Windows PowerShell**; the [Groups APK workflow](../.github/workflows/groups-apk.yml) builds on Linux.

| Requirement | Current configuration |
| --- | --- |
| Android device | Android 14+ (`minSdk 34`), `arm64-v8a` only |
| Android SDK | Platform **37.2** (`platforms/android-37.2`), `targetSdk 37`; Groups CI installs Build Tools 36.0.0 |
| Android NDK | `NDK_VERSION` in [`version.properties`](../version.properties), currently `28.2.13676358` |
| CMake | 3.22.1, used for Hev and the JNI libraries |
| Zig | 0.16.0, used to prepare SimpleTUN |
| Java | JDK 25 for Gradle/app compilation, with JDK 21 also available for the included build; app bytecode targets Java/Kotlin JVM 21 |
| Gradle | 9.9.1, pinned by the Wrapper; the Windows helper uses a separately installed copy of the same version |
| Other tools | Git with recursive submodules; Python 3.10+ for tooling checks |

Go is not needed to package the official Xray executable. Go tooling uses `GO_VERSION` from `version.properties` (currently `1.27.3`); the optional QUIC helper requires at least Go 1.26.0. AGP, Kotlin and Android dependencies are configured in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

Initialize the source dependencies in the source tree you intend to build:

```powershell
git submodule update --init --recursive
```

### Windows toolchain layout

[`tools/build-groups-apk.ps1`](../tools/build-groups-apk.ps1) expects the following installations under `$env:LOCALAPPDATA\SimpleXrayBuild` and does not install them:

- `jdk-25/` and `jdk-21/`
- `gradle-9.9.1/`
- `android-sdk/`, including Platform 37.2, Build Tools, the pinned NDK and `cmake/3.22.1/`
- `signing/`, containing the private persistent signing material described below

For manual Gradle commands, set the SDK and Java locations first. Install Zig 0.16.0 separately and put it on `PATH`.

```powershell
$toolchainRoot = Join-Path $env:LOCALAPPDATA 'SimpleXrayBuild'
$env:JAVA_HOME = Join-Path $toolchainRoot 'jdk-25'
$env:ANDROID_HOME = Join-Path $toolchainRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmake\3.22.1\bin;$env:PATH"
```

An alternative SDK location can be supplied through local `local.properties` (`sdk.dir`) for manual builds. The PowerShell build helper itself uses the fixed layout above.

## 2. Prepare Native Libraries

### Hev

Gradle's external CMake build compiles `third_party/hev-socks5-tunnel/`, including its vendored YAML, lwIP and task-system sources. No separate Hev prebuild command is needed.

### SimpleTUN

CMake **imports**, rather than builds, the Zig static archive. Prepare it before running an APK build:

```powershell
Push-Location .\third_party\simpletun
try {
    zig build android
    if ($LASTEXITCODE -ne 0) { throw 'SimpleTUN build failed.' }
} finally {
    Pop-Location
}
```

Required output: `third_party/simpletun/zig-out/android/prebuilt/arm64-v8a/libsimpletun.a`. CMake fails if it is absent. Native SimpleTUN tests (`zig build test`) should be run on Linux/WSL; Android cross-compilation and host-native tests are separate steps.

### Xray-core

Supply the official Android ARM64 executable at **`third_party/xray-prebuilt/arm64-v8a/libxray.so`**. Gradle packages this directory as JNI libraries; it does not download, compile or validate Xray, and there is no `ensureXrayCore` task.

This PowerShell example downloads the version in `version.properties`, checks its pinned ZIP SHA-256, and installs the executable. Extraction stays in a new temporary directory; do not run files from the archive on the host.

```powershell
$ErrorActionPreference = 'Stop'
$pins = Get-Content .\version.properties -Raw | ConvertFrom-StringData
$temp = Join-Path ([IO.Path]::GetTempPath()) ('lucx-xray-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $zip = Join-Path $temp 'xray.zip'
    $url = "https://github.com/XTLS/Xray-core/releases/download/$($pins.XRAY_CORE_VERSION)/Xray-android-arm64-v8a.zip"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $pins.XRAY_CORE_ZIP_SHA256) {
        throw 'Xray archive SHA256 mismatch.'
    }
    $unpacked = Join-Path $temp 'unpacked'
    Expand-Archive -LiteralPath $zip -DestinationPath $unpacked
    $destination = '.\third_party\xray-prebuilt\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $unpacked 'xray') -Destination (Join-Path $destination 'libxray.so') -Force
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force
}
```

Do not also leave a second `libxray.so` under `app/src/main/jniLibs/`; both locations are JNI input directories and duplicate files can break packaging.

## 3. Prepare Bundled Assets

These generated/downloaded inputs are ignored by Git. Prepare them for **both debug and release** builds; neither Gradle nor the Windows build helper fetches them automatically.

### GEO routing data

```powershell
New-Item -ItemType Directory -Force -Path .\app\src\main\assets | Out-Null
$rules = 'https://github.com/XTLS/Xray-rules-dat/releases/latest/download'
Invoke-WebRequest -UseBasicParsing -Uri "$rules/geoip-lite.dat" -OutFile .\app\src\main\assets\geoip.dat
Invoke-WebRequest -UseBasicParsing -Uri "$rules/geosite.dat" -OutFile .\app\src\main\assets\geosite.dat
```

The GEO files use upstream `latest`, not version/hash pins. Preserve the downloaded files separately if you need to reproduce a particular APK's data.

### Offline zashboard bundle

```powershell
.\tools\fetch-zashboard.ps1
```

The fetcher downloads **zashboard v3.30.0** `dist.zip` and checks SHA-256 **`1f91c014e61e768240a86ad0a9bd565b247ec5a0e26b125fb5d19076682905b7`** before replacing `app/src/main/assets/zashboard/`. It requires both `index.html` and `THIRD_PARTY_NOTICES.md`. Its version and hash are pinned in the script, not `version.properties`.

The resulting APK must include the entire bundle and its third-party notices, plus `assets/licenses/zashboard/LICENSE`. Local panel files are available without downloading a web frontend at runtime; optional upstream features such as a user-requested geolocation database can still require networking. See [third-party licenses](./third_party_licenses.md).

Fetching requires network access each time it is invoked. Once prepared, the files can be reused for later builds. A successful Gradle build alone does **not** prove that the Xray, GEO or panel inputs were supplied.

## 4. Private Signing and APK Builds

### Persistent Groups signing

The Windows helper loads `simplexray-groups.p12` and the Windows-protected `simplexray-groups.credential.xml` from `$env:LOCALAPPDATA\SimpleXrayBuild\signing`. Keep these files private and back them up securely; updates to an installed app require the same signing key. Do not commit credentials, print passwords, or include them in build artifacts. The credential export is tied to the Windows account/machine and is not a portable plaintext password file.

[`tools/configure-groups-signing.ps1`](../tools/configure-groups-signing.ps1) creates or reuses this key **and uploads it to GitHub repository secrets**. It is not a local-only setup command. Run it only when you intend to configure that repository, with an explicit `-Repository OWNER/REPO` and an authenticated `gh` CLI. The secrets are `GROUPS_KEYSTORE_BASE64` and `GROUPS_KEYSTORE_PASSWORD`; the signing alias is `simplexray-groups`.

After all prerequisites and assets are ready:

```powershell
.\tools\build-groups-apk.ps1 -BuildNumber 40
```

Choose a build number newer than the installed build. The helper runs only `:app:assembleDebug`, with `versionName = 1.0.0-<BuildNumber>-debug` and `versionCode = 10001000 + BuildNumber`. It does not run tests or refresh native libraries/assets.

Output: **`app/build/outputs/apk/debug/LucX-arm64-v8a.apk`**. This is a persistently signed **debug** APK, not an R8-optimized release build.

### Direct Gradle builds

With the environment above, manual debug/test and release commands are:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-Porg.gradle.java.installations.paths=$toolchainRoot\jdk-25,$toolchainRoot\jdk-21"
.\gradlew.bat :app:assembleRelease -PappVerName=1.0.0 "-Porg.gradle.java.installations.paths=$toolchainRoot\jdk-25,$toolchainRoot\jdk-21"
```

On Linux use `./gradlew`, set the corresponding Java/SDK paths, and prepare the same inputs. `-PappVerName` and optional `-PappVerCode` override app versioning; without overrides the version name is `1.0.0` (with `-debug` on debug builds).

For direct builds, configure signing privately through `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, or a local, untracked `store.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Environment variables take precedence. Both build types use that signing configuration when its keystore file exists; otherwise they fall back to the default debug key. An APK labeled release is therefore **not proof of release signing**.

The release build enables R8 and resource shrinking; the configured persistent signer enables V3/V4 signing and disables V1/V2. Release output: **`app/build/outputs/apk/release/LucX-arm64-v8a.apk`**.

## 5. Verification

In addition to Gradle unit tests:

```powershell
python -m unittest discover -s tools/tests -v
python tools/sync_versions.py --check
jar tf .\app\build\outputs\apk\debug\LucX-arm64-v8a.apk | Select-String 'libxray.so|geoip.dat|geosite.dat|assets/zashboard/|licenses/zashboard/LICENSE'
```

Inspect the archive for `lib/arm64-v8a/libxray.so`, both GEO files, `assets/zashboard/index.html`, the bundle's supporting files and `THIRD_PARTY_NOTICES.md`, and the MIT license. Use Android Build Tools' `apksigner verify --verbose --print-certs` on the APK to verify the signer. Run `zig build test` from `third_party/simpletun/` on Linux/WSL. Device/instrumentation testing requires Android 14+ ARM64 hardware or a suitable ARM64 emulator.

`sync_versions.py --check` checks the six required property names and formats, the Hev source version/Git revision (where available), and that `GO_VERSION` covers `tools/quic/go.mod`. Without `--check`, it can update the Hev version entry. It **does not** resolve Xray tags, verify downloaded binaries/hashes, update the Android/Kotlin catalog, or check the zashboard/GEO inputs.

## 6. CI Scope and Unsupported Release Paths

- **`groups-apk.yml`** is the current APK packaging path: on `groups-apk` pushes or manual dispatch it installs the toolchain, prepares Xray/GEO/zashboard, builds SimpleTUN, loads the Groups signing secrets, runs `:app:assembleDebug`, and uploads `LucX-arm64-v8a`. It does not run the unit-test suite. The zashboard archive is hash-checked; the Xray download currently uses `XRAY_CORE_VERSION` without checking `XRAY_CORE_ZIP_SHA256` or `XRAY_CORE_COMMIT`.
- **`verify.yml`** declares pin/Go-format checks, SimpleTUN build/tests and Gradle debug tests/assembly on eligible `main`/`dev` changes. It does not prepare Xray, GEO or zashboard, so it is not a complete distributable-APK recipe. Markdown/docs-only changes are excluded by its path filters; successful CI must not be inferred from this documentation update.
- **`release.yml` is not a supported LucX release path as written.** Its tag-triggered steps still copy `simplexray-arm64-v8a.apk`, which does not match Gradle's `LucX-arm64-v8a.apk`, and do not install the Xray executable or panel bundle. Although it checks Xray tag/commit metadata, it does not download and hash-check the archive. Do not rely on it to publish a usable release. There is no current No-GEO Gradle variant or supported No-GEO release artifact.

---

[Back to project README](./README.md)
