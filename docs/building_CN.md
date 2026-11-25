# 从源码构建 LucX

**[English](./building.md)** | **中文**

先准备原生库和内置资源，再构建应用 ID 为 `com.LucX` 的 Android 应用。以下命令均在 LucX 源码根目录（`settings.gradle.kts` 所在目录）执行。

## 1. 工具链

本地辅助脚本面向 **Windows PowerShell**；[Groups APK 工作流](../.github/workflows/groups-apk.yml) 在 Linux 上构建。

| 要求 | 当前配置 |
| --- | --- |
| Android 设备 | Android 14+（`minSdk 34`），仅支持 `arm64-v8a` |
| Android SDK | Platform **37.2**（`platforms/android-37.2`），`targetSdk 37`；Groups CI 安装 Build Tools 36.0.0 |
| Android NDK | 由 [`version.properties`](../version.properties) 的 `NDK_VERSION` 固定，当前为 `28.2.13676358` |
| CMake | 3.22.1，用于 Hev 和 JNI 库 |
| Zig | 0.16.0，用于预编译 SimpleTUN |
| Java | Gradle/应用编译使用 JDK 25，同时为 included build 提供 JDK 21；应用 Java/Kotlin JVM 字节码目标为 21 |
| Gradle | Wrapper 固定为 9.9.1；Windows 辅助脚本使用单独安装的同版本 Gradle |
| 其他工具 | 支持递归子模块的 Git；工具检查需要 Python 3.10+ |

打包官方 Xray 可执行文件不需要 Go。Go 工具使用 `version.properties` 中的 `GO_VERSION`（当前为 `1.27.3`）；可选 QUIC 工具至少需要 Go 1.26.0。AGP、Kotlin 和 Android 依赖版本见 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。

在准备构建的源码目录中初始化依赖：

```powershell
git submodule update --init --recursive
```

### Windows 工具链目录

[`tools/build-groups-apk.ps1`](../tools/build-groups-apk.ps1) 要求以下内容已安装到 `$env:LOCALAPPDATA\SimpleXrayBuild`，脚本不会自动安装：

- `jdk-25/` 与 `jdk-21/`
- `gradle-9.9.1/`
- `android-sdk/`，包含 Platform 37.2、Build Tools、固定版本 NDK 和 `cmake/3.22.1/`
- `signing/`，存放下文说明的私有持久签名材料

手动运行 Gradle 前，先设置 Java 和 SDK 路径。另行安装 Zig 0.16.0 并加入 `PATH`。

```powershell
$toolchainRoot = Join-Path $env:LOCALAPPDATA 'SimpleXrayBuild'
$env:JAVA_HOME = Join-Path $toolchainRoot 'jdk-25'
$env:ANDROID_HOME = Join-Path $toolchainRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmake\3.22.1\bin;$env:PATH"
```

手动构建也可以在本地 `local.properties` 中用 `sdk.dir` 指定其他 SDK 路径；PowerShell 构建辅助脚本本身仍使用上述固定目录。

## 2. 准备原生库

### Hev

Gradle 的 external CMake build 会编译 `third_party/hev-socks5-tunnel/`，包括其中的 YAML、lwIP 和任务系统源码，无需单独预编译 Hev。

### SimpleTUN

CMake 只会**导入** Zig 静态库，不会替你构建它。构建 APK 前执行：

```powershell
Push-Location .\third_party\simpletun
try {
    zig build android
    if ($LASTEXITCODE -ne 0) { throw 'SimpleTUN build failed.' }
} finally {
    Pop-Location
}
```

必需产物：`third_party/simpletun/zig-out/android/prebuilt/arm64-v8a/libsimpletun.a`。缺失时 CMake 会报错。SimpleTUN 主机原生测试（`zig build test`）应在 Linux/WSL 中执行；Android 交叉编译与主机测试是两个独立步骤。

### Xray-core

将官方 Android ARM64 可执行文件准备到 **`third_party/xray-prebuilt/arm64-v8a/libxray.so`**。Gradle 将此目录作为 JNI 库输入打包；不会下载、编译或校验 Xray，也没有 `ensureXrayCore` 任务。

下面的 PowerShell 示例读取 `version.properties`，下载固定版本 ZIP、核对其中记录的 SHA-256，再复制可执行文件。压缩包只解压到新建临时目录，不要在主机上运行其中的文件。

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

不要在 `app/src/main/jniLibs/` 中再留一份 `libxray.so`；两个目录都是 JNI 输入，重复文件可能导致打包失败。

## 3. 准备内置资源

这些生成或下载的输入被 Git 忽略。**Debug 和 release 都需要提前准备**；Gradle 和 Windows 构建辅助脚本均不会自动下载。

### GEO 路由数据

```powershell
New-Item -ItemType Directory -Force -Path .\app\src\main\assets | Out-Null
$rules = 'https://github.com/XTLS/Xray-rules-dat/releases/latest/download'
Invoke-WebRequest -UseBasicParsing -Uri "$rules/geoip-lite.dat" -OutFile .\app\src\main\assets\geoip.dat
Invoke-WebRequest -UseBasicParsing -Uri "$rules/geosite.dat" -OutFile .\app\src\main\assets\geosite.dat
```

GEO 文件来自上游 `latest`，不固定版本或哈希。如果需要复现某个 APK 的数据，请另外保存当次下载的文件。

### 离线 zashboard 面板

```powershell
.\tools\fetch-zashboard.ps1
```

脚本下载 **zashboard v3.30.0** 的 `dist.zip`，核验 SHA-256 **`1f91c014e61e768240a86ad0a9bd565b247ec5a0e26b125fb5d19076682905b7`** 后替换 `app/src/main/assets/zashboard/`。压缩包必须包含 `index.html` 和 `THIRD_PARTY_NOTICES.md`。版本与哈希固定在脚本中，而非 `version.properties`。

最终 APK 必须包含完整面板目录、其第三方声明及 `assets/licenses/zashboard/LICENSE`。本地面板文件不需要在运行时下载网页前端；上游的可选功能（例如用户主动下载地理位置数据库）仍可能需要联网。另见[第三方许可](./third_party_licenses.md)。

每次运行下载脚本都需要网络；准备完成后可以在后续构建中复用文件。Gradle 构建成功本身**不能证明** Xray、GEO 或面板资源已经齐备。

## 4. 私有签名与 APK 构建

### Groups 持久签名

Windows 构建辅助脚本从 `$env:LOCALAPPDATA\SimpleXrayBuild\signing` 读取 `simplexray-groups.p12` 和受 Windows 保护的 `simplexray-groups.credential.xml`。这些文件必须保密并安全备份；覆盖更新已安装应用需要同一签名密钥。不要提交凭据、打印密码或将其纳入构建产物。凭据导出与 Windows 账户/机器绑定，并非可随意迁移的明文密码文件。

[`tools/configure-groups-signing.ps1`](../tools/configure-groups-signing.ps1) 会创建或复用密钥，**并将其上传为 GitHub 仓库 Secrets**，不是仅配置本机的命令。只有确实要配置目标仓库时才运行它，并显式传入 `-Repository OWNER/REPO`，同时准备已认证的 `gh` CLI。使用的 Secrets 为 `GROUPS_KEYSTORE_BASE64`、`GROUPS_KEYSTORE_PASSWORD`，签名别名为 `simplexray-groups`。

所有依赖和资源准备完毕后执行：

```powershell
.\tools\build-groups-apk.ps1
```

脚本运行 `:app:assembleDebug`，并与 GitHub Actions 共用 `app/build.gradle.kts` 固定定义的 **1.0.0-68 / 10001068**。原生库、资源和签名材料按上文预先准备。

产物：**`app/build/outputs/apk/debug/LucX-arm64-v8a.apk`**。这是使用持久密钥签名的 **debug** APK，不是经过 R8 优化的 release 构建。

### 直接使用 Gradle

在上述环境中，手动调试/测试与 release 构建命令为：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug "-Porg.gradle.java.installations.paths=$toolchainRoot\jdk-25,$toolchainRoot\jdk-21"
.\gradlew.bat :app:assembleRelease "-Porg.gradle.java.installations.paths=$toolchainRoot\jdk-25,$toolchainRoot\jdk-21"
```

Linux 使用 `./gradlew`，设置相应 Java/SDK 路径并准备相同输入。`LucX` 分支的 debug 和 release 均使用 `app/build.gradle.kts` 固定定义的版本名 **1.0.0-68**、版本码 **10001068**。

直接构建时，通过 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 配置私有签名，或在本地、不纳入版本控制的 `store.properties` 中设置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；环境变量优先。两种 build type 都会在配置的 keystore 文件存在时使用该签名，否则回退到默认 debug 密钥。因此，标为 release 的 APK **不等于已正确使用发布签名**。

Release 开启 R8 与资源压缩；配置的持久签名启用 V3/V4、关闭 V1/V2。Release 产物：**`app/build/outputs/apk/release/LucX-arm64-v8a.apk`**。

## 5. 验证

除 Gradle 单元测试外，还可运行：

```powershell
python -m unittest discover -s tools/tests -v
python tools/sync_versions.py --check
jar tf .\app\build\outputs\apk\debug\LucX-arm64-v8a.apk | Select-String 'libxray.so|geoip.dat|geosite.dat|assets/zashboard/|licenses/zashboard/LICENSE'
```

检查 APK 中是否含有 `lib/arm64-v8a/libxray.so`、两个 GEO 文件、`assets/zashboard/index.html`、面板配套文件及 `THIRD_PARTY_NOTICES.md`、MIT 许可。使用 Android Build Tools 的 `apksigner verify --verbose --print-certs` 验证 APK 签名。在 Linux/WSL 的 `third_party/simpletun/` 中运行 `zig build test`。设备或 instrumentation 验证需要 Android 14+ ARM64 真机或合适的 ARM64 模拟器。

`sync_versions.py --check` 检查六个必需属性名及格式、Hev 源码版本及可用的 Git 修订，并确保 `GO_VERSION` 不低于 `tools/quic/go.mod` 的要求。不带 `--check` 时可以更新 Hev 版本项。它**不会**解析 Xray tag、校验下载文件/哈希、更新 Android/Kotlin 依赖目录，也不会检查 zashboard 或 GEO 输入。

## 6. CI 范围与不受支持的发布路径

- **`groups-apk.yml`** 是当前 APK 打包路径：在 `LucX` 分支推送或手动触发时安装工具链、准备 Xray/GEO/zashboard、构建 SimpleTUN、加载既有签名 Secrets、运行 `:app:assembleDebug`，并上传 `LucX-arm64-v8a`。版本与本地共用 `app/build.gradle.kts` 中的 **1.0.0-68 / 10001068**。
- **`verify.yml`** 为符合条件的 `main`/`dev` 变更配置了版本检查、Go 格式检查、SimpleTUN 构建/测试与 Gradle debug 测试/打包。它不准备 Xray、GEO 或 zashboard，因此不是完整的可分发 APK 构建流程。路径过滤会跳过仅 Markdown/docs 的变更；不能从本次文档更新推断 CI 已通过。
- **当前 `release.yml` 不是受支持的 LucX 发布路径。** 其 tag 触发流程仍复制 `simplexray-arm64-v8a.apk`，与 Gradle 生成的 `LucX-arm64-v8a.apk` 不符，也没有安装 Xray 可执行文件或面板资源。虽然流程核对 Xray tag/commit 元数据，但并未下载并核验 ZIP。不要依赖该流程发布可用版本。当前没有 No-GEO Gradle 变体或受支持的 No-GEO 发布产物。

---

[返回项目 README](./README_CN.md)
