# LucX

<div align="center">
<img src="images/lineal.svg" alt="LucX 图标" width="150">

**[English](./README.md)** | **中文**

<img src="https://app.fossa.com/api/projects/git%2Bgithub.com%2FReRokutosei%2FSimpleXray.svg?type=shield" alt="FOSSA Status" width="150">

<sub>Android 14+ (API 34) | arm64-v8a | MPL-2.0</sub>
</div>

LucX 是 [SimpleXray](https://github.com/lhear/SimpleXray) 的个人分支，围绕单一 Xray 代理内核、共享节点/分组工作区和原生配置控件构建，提供 Xray TUN、Hev TUN 和 Simple TUN 三种后端。安装名称与应用 ID 分别为 **LucX** 和 **`com.LucX`**；Kotlin/模型命名空间仍为 `com.simplexray.re`。

> [!NOTE]
> 本分支用于个人使用与实验。不接受 Issue 和 PR，也不提供支持。如需受支持的客户端，请使用上游或维护自己的分支。

## 主要功能

- **分组与节点**：本地/订阅分组、手动选择、自动测速、嵌套回落组、前置代理及逐节点目标 DNS 策略。
- **原生编辑**：节点表单与高级出站 JSON、结构化路由规则、DNS 和入站设置。
- **离线面板**：由本地 Clash 兼容控制器提供内置 zashboard，无需外部面板网站。
- **规则资源**：`geoip.dat`、`geosite.dat`、自定义 `.dat`、`ext:` 引用、更新 URL 与后台更新。
- **Miuix 界面**：五个底部标签，仪表盘/代理/配置共用 30 dp 圆角卡片，手机/平板内容宽度适配，浅色/深色/跟随系统主题及动态取色。
- **Android 集成**：分应用代理、本地 SOCKS5、网络切换处理与 16 KB 原生库页对齐。
- **基准测试工具**：headless 测试套件、报告/图表生成及已发布设备数据集。

结构对照见[与上游的差异](./upstream-differences_CN.md)。

## 界面预览

### 手机

<div align="center">
  <img src="./images/mobile_01.webp" alt="手机端界面 1" width="48%">
  <img src="./images/mobile_02.webp" alt="手机端界面 2" width="48%">
  <br>
  <img src="./images/mobile_03.webp" alt="手机端界面 3" width="48%">
  <img src="./images/mobile_04.webp" alt="手机端界面 4" width="48%">
</div>

### 平板

<div align="center">
  <img src="./images/table_01.webp" alt="平板端界面 1" width="48%">
  <img src="./images/table_02.webp" alt="平板端界面 2" width="48%">
  <br>
  <img src="./images/table_03.webp" alt="平板端界面 3" width="48%">
  <img src="./images/table_04.webp" alt="平板端界面 4" width="48%">
</div>

## 页面与导航

| 底部标签 | 用途 |
| --- | --- |
| 仪表盘 / Dashboard | 启停、就绪状态、出口 IP/位置、规则/全局/直连模式、流量和内存。刷新查询出口信息，不测节点延迟。 |
| 代理 / Proxies | 切换组内成员、查看当前节点、搜索、展开/收起分组卡片、测量 HTTP 延迟，以及进入代理组设置。 |
| 配置 / Configuration | 创建本地/订阅分组、导入或编辑节点、刷新订阅。 |
| 日志 / Logs | 保存日志级别、Access/DNS 日志开关、搜索、导出和清空。 |
| 设置 / Settings | 通用、TUN、入站、规则、DNS、面板、关于。 |

配置页的 **+** 菜单提供剪贴板/文件节点导入、手动添加节点和创建分组；更多菜单提供分组管理、重命名/订阅设置及刷新订阅。节点卡片可打开编辑，并提供复制/删除操作。从代理页进入的**代理组设置**负责成员排序、选择策略、超时/恢复设置、前置代理和设为默认组。

设置 → 规则打开原生规则编辑器，其中的**规则资源**入口打开 DAT 管理器。规则与规则资源页面保持设置标签选中；分组管理保持配置标签选中；代理组设置保持代理标签选中。宽屏仍使用底部导航，通过适用页面的内容宽度限制适配，不使用独立 NavigationRail。

## 节点与配置

- 剪贴板/文件导入和订阅 URL 接受支持的分享链接、base64 订阅、Xray/sing-box 出站 JSON 与 Mihomo YAML 节点。不支持的协议/插件组合会报告原因。导入是格式转换，不代表实现源格式的全部功能。详见[原生导入说明](./xray-imports.md)。
- 表单支持 VLESS、VMess、Trojan、Shadowsocks、SOCKS、HTTP，以及传输层、TLS/REALITY、目标 DNS 策略和适用的 Shadowsocks UDP-over-TCP/simple-obfs 选项。高级 JSON 编辑单个完整 Xray 出站；表单修改保留无关字段及额外服务器/用户条目。
- Xray 负责代理协议与分流。支持的 UDP-over-TCP 和 simple-obfs 适配由 Kotlin 中继实现；LucX 不运行第二套 Mihomo 内核，也不打包 `libmihomo.so`。
- 应用不提供完整配置导入/编辑器或 Lua 导入/编辑器。节点导入可以从配置文档提取出站，但不会将整份文档安装为当前全局配置；DNS、入站、分组和规则各有独立控件。
- 初始化时，存储层可以读取已有完整配置、分组配置和受支持的 Lua 路由模式，迁移到工作区。这是数据迁移，不是 Lua 执行/编辑流程；任意 Lua 逻辑无法完整转换，应检查迁移后的规则。

运行时配置由全局设置、分组和结构化规则组装，经 Android 适配后通过 stdin 传给 Xray。详见[配置覆写与删除](./configuration_CN.md)。

## 规则与资源

原生规则编辑器管理类型、匹配内容、目标、启用状态和顺序，支持拖动排序。规则支持域名/精确/后缀/关键词、IP CIDR、GeoIP、GeoSite、目标端口、网络、兜底及原始 Xray 匹配对象。JSON 导入会**替换当前规则列表**；导出为结构化 JSON。YAML 节点导入与规则导入是不同流程。

目标可以是 `GLOBAL`（默认组）、`direct`、`block` 或实际分组/节点名称。需要经过回落组时，应选该组而不是物理主节点。没有路由别名设置；加载旧工作区时，会先将保存的别名引用改写为所属分组，再丢弃别名。

设置 → 规则 → 规则资源管理内置 `geoip.dat`/`geosite.dat` 和自定义 `.dat`。自定义资源支持 `ext:<file>:<tag>` 引用及独立更新 URL，下载通过校验后才会安装。缺失的内置 GEO 文件可以恢复或下载。GitHub 下载需要联网，可能需要先开启代理。

## 分组选择与主备回落

手动选择组保留已保存的选择。自动测速组在会话就绪后异步执行首次测量，并按配置周期复测。代理页和分组节点页提供主动 HTTP 测速；仪表盘刷新仅查询出口信息。

主备回落结合真实业务、多目标强失败和主备对照测活。60 秒内累计 3 个独立目标的停滞或强失败会降低成员优先级，即使其测活网址仍能响应。冷却从 30 秒递增至 5 分钟；复核期间收到的强失败会保留并安排后续检查。本地 SOCKS 按优先级接入，响应观察期间保留慢请求并等待策略复核。嵌套检查先处理子组候选；切换备用后启动配置的恢复间隔，健康排序触发的切换同样适用。详见[主备回落与恢复](./configuration_CN.md#主备回落与恢复)。

降权后观察现有连接 2 秒，接收字节持续增加的连接继续传输，计数持平的连接关闭并由应用重新连接。初始请求保留有限重试能力，既有 TLS 会话保持原传输关系。

HTTP 延迟是经节点发出一次 HEAD 请求，从写入请求到收到响应头的 RTT，不是单纯 TCP 握手时间；超时还包含连接准备过程。原生 UI 与 zashboard 共用测量实现。

## TUN、DNS 与日志

- **Hev TUN** 是默认 C/lwIP 后端；**Simple TUN** 是源码内 Zig 后端，二者将 VPN 接口流量转发到 Xray 的本地 SOCKS5 入站。**Xray TUN** 通过 JNI 启动器接收 Android VPN 描述符。
- Simple TUN 仅支持 IPv4、无认证 SOCKS5，MTU 固定为 1500；Hev 与 Xray TUN 保留 IPv6 选项。详见 [SimpleTUN 架构说明](./simpletun_spec.md)。
- 设置 → TUN → 绕过局域网从 VPN 排除私网地址，并对注册的系统 HTTP 代理应用对应 IPv4 排除项。修改后需要重连。应用自行选择代理时需自行管理绕过项；内核中指向 `direct` 的 `IPCIDR` 规则则仍经内核转发。
- 设置 → DNS 管理全局/分域解析器、hosts、查询策略和 DoH 引导 DNS IP（初始值 `223.5.5.5`）。TUN DNS 接收地址由应用管理；保存 DNS 设置可能重启运行中的内核。应用自带的加密 DNS 不受该设置直接控制。
- UI 与服务在同一 Android 进程，通过内存流发布状态/日志。Xray 是子进程，状态与流量统计通过动态分配的回环端口使用明文 gRPC 查询。
- App 保存日志与面板显示日志独立筛选同一内核日志流；修改筛选级别不重启 Xray。内核持续生成 Debug 日志供即时查看，因此降低保存级别主要减少写入，而非全部日志处理开销。Access/DNS 开关在下次内核启动时生效，关闭内核后保留已保存日志。
- 前台通知显示当前主代理节点，直连模式显示直连。仪表盘内存显示应用/内核 RSS，内核停止时仍显示应用内存。Manifest 为 Android 17 本地网络权限模型声明了 `ACCESS_LOCAL_NETWORK`。

## 内置 zashboard 与本地 API

1. 打开**设置 → 面板**，启用控制器并设置端口（默认 `9090`）；控制器默认关闭。
2. 启动服务并等待连接就绪。
3. 选择**打开内置 zashboard**。浏览器访问 `http://127.0.0.1:<port>/ui/`，与 API 使用**同一个回环监听器和端口**；启动入口通过 URL fragment 传入当前连接信息与密钥。

静态 UI 随 APK 打包，加载时无需联系外部面板网站；服务/控制器停止时不可访问。`/` 和 `/ui` 重定向到 `/ui/`。手动打开页面不会自动传入 API 凭据：首次手动连接时，需要填写控制器地址/端口及从面板设置复制的密钥。静态 GET/HEAD 请求公开提供；API/WebSocket 访问仍需要密钥。请将启动链接和复制的密钥视为凭据保密。

服务启动中或已连接时，修改控制器开关/端口/密钥仅重启 API 监听器；服务停止时保存设置供下次启动使用。面板提供分组选择、延迟、连接和独立筛选的日志。详见 [Clash API](./clash-api.md)。

构建资源位于 `app/src/main/assets/zashboard/`。[`tools/fetch-zashboard.ps1`](../tools/fetch-zashboard.ps1) 下载固定的 **v3.30.0** 发行包，核验 SHA-256 后连同第三方声明安装。离线 UI 资源不意味着代理流量、订阅或出口 IP 查询能够脱网工作。

## 基准测试与工具

- `tools/benchmark.py` 提供吞吐、空闲内存、bufferbloat、稳定性、弱网、CPS 与 QUIC 套件，通过仅 debug 构建包含的 headless `BenchmarkService` 控制测试。
- ADB 辅助代码仍指向旧的 `com.simplexray.re.debug` 包及启动组件，而非 `com.LucX`；驱动当前 APK 前需要先同步这些目标。详见[工具前置条件与限制](../tools/README.md)。
- 包含图表与报告生成。已发布数据是历史测量，不代表当前构建已通过验证：[Snapdragon 8 Elite Gen 5](./benchmark/8-elite-gen-5/report/benchmark_report.md) 与 [Snapdragon 778G](./benchmark/778g/report/benchmark_report.md)。

## 已知问题

### 网络切换后残留的本地 SOCKS5 连接

Telegram 等客户端可能在上游传输失效后继续复用到 `127.0.0.1:<socksPort>` 的 TCP 连接。更短的 outbound keepalive/user-timeout 有助于识别黑洞传输，但不能保证每个客户端都会重建本地连接。可以重连客户端、切换 VPN/内核，或使用具备自身存活检测的客户端。回落后的既有加密流也可能需要应用重试。

## 本地构建

工具准备与签名详见[从源码构建](./building_CN.md)。当前配置为 **minSdk 34**、**targetSdk 37**、编译平台 **`android-37.2`**，仅打包 **`arm64-v8a`**。本地 PowerShell 辅助脚本要求 `%LOCALAPPDATA%\SimpleXrayBuild` 中已准备工具链（JDK 25/21、Gradle 9.9.1、Android SDK/NDK、CMake 3.22.1）及持久本地签名凭据。

打包前，准备 `third_party/xray-prebuilt/arm64-v8a/libxray.so`，在 `third_party/simpletun` 中执行 `zig build android`，并准备 GEO/面板资源。单独运行 Gradle **不会**下载 Xray 或构建 Zig 静态库。前置条件齐备后，在项目根目录执行：

```powershell
.\tools\fetch-zashboard.ps1
.\tools\build-groups-apk.ps1
```

LucX 分支固定构建版本 **1.0.0-68**，版本码为 **10001068**；版本定义集中在 `app/build.gradle.kts`。辅助脚本生成持久签名的 debug APK，产物为 **`app/build/outputs/apk/debug/LucX-arm64-v8a.apk`**。工具链和签名凭据按构建文档预先准备，升级继续使用既有签名密钥。

环境准备完毕后，JVM 测试与直接 Gradle 构建可在 Windows 使用 `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`，Unix 使用 `./gradlew :app:testDebugUnitTest :app:assembleDebug`。VPN/回落等设备相关行为仍需真机验证。

## 文档

- [从源码构建](./building_CN.md)
- [原生导入说明](./xray-imports.md)
- [Clash API](./clash-api.md)
- [配置覆写与删除](./configuration_CN.md)
- [与上游的差异](./upstream-differences_CN.md)
- [SimpleTUN 架构说明](./simpletun_spec.md)
- [基准测试报告](./benchmark/8-elite-gen-5/report/benchmark_report.md)
- [更新日志](../CHANGELOG.md)

## 上游与第三方

项目基于 [Xray-core](https://github.com/XTLS/Xray-core)、[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)、[compose-miuix-ui](https://github.com/compose-miuix-ui/miuix)、[zashboard](https://github.com/Zephyruso/zashboard)，并衍生自 [SimpleXray](https://github.com/lhear/SimpleXray)。[scripta](https://github.com/YuKongA/scripta) 源码仍保留在 `third_party/`，但当前应用不包含其完整配置/Lua 编辑器 UI。

应用图标使用 [Magnific](https://www.magnific.com) 提供的免费 Cookie Icons。

## 隐私与许可

详见[隐私政策](./PrivacyPolicy_CN.md)、[免责声明](./Disclaimer_CN.md)和[开源许可证](./third_party_licenses.md)。

除另有说明外，本项目以 Mozilla Public License 2.0（MPL-2.0）分发。完整条款见 [`LICENSE`](../LICENSE)。
