# 与上游的差异

**[English](./upstream-differences.md)** | **中文**

本文以固定的上游快照 `4c78901` 为基准。内容只覆盖架构与行为差异；构建／核心版本固定在 [`version.properties`](../version.properties)、[`gradle/libs.versions.toml`](../gradle/libs.versions.toml)、[`gradle/wrapper/gradle-wrapper.properties`](../gradle/wrapper/gradle-wrapper.properties) 和 [`.github/workflows/verify.yml`](../.github/workflows/verify.yml) 中。内置 zashboard 版本与压缩包哈希固定在 [`tools/fetch-zashboard.ps1`](../tools/fetch-zashboard.ps1)。

| 项目 | 上游快照（`4c78901`） | 本仓库 |
|-|-|-|
| **进程与配置传递** | 独立的 Xray 子进程，通过标准输入接收配置 | Android 应用层（UI + `VpnService`）采用单进程架构，Xray 仍为独立子进程并通过标准输入接收配置；原生 Xray TUN 使用 JNI 启动器，Hev/SimpleTUN 使用 `ProcessBuilder` 并各自通过 SOCKS5 路径转发流量。APK 仅打包 `arm64-v8a` |
| **流量与 IPC** | 由 `hev-socks5-tunnel` 读取 Android VPN fd 并转发至 Xray 本地 SOCKS5 入站；状态统计使用动态回环 gRPC 端口 | 数据面由设置页在 Xray TUN、Hev、SimpleTUN 之间切换；服务状态与日志通过内存级 `VpnStateHub` 传递，状态统计使用动态 `127.0.0.1` gRPC 端口 |
| **节点导入** | 支持 JSON 配置、`vless://` 链接和 `simplexray://config/` 链接 | 通过剪贴板／SAF 向当前组追加节点：支持的分享链接及 Base64 订阅、Xray JSON 出站、Mihomo YAML／JSON 节点和已实现的 sing-box JSON 出站；这些入口不替换全局设置或规则 |
| **规则文件** | 内置 `geoip.dat` 和 `geosite.dat`，并支持本地替换及这两个文件的 URL 更新 | 保留标准规则文件管理，并增加任意自定义 `.dat` 文件、`ext:` 文件引用、独立更新地址、文件校验和后台更新 |
| **配置组装** | 对 JSON 进行格式化，并删除 `log.access` 和 `log.error` | 从独立的全局、分组和结构化规则存储组装运行时 Xray JSON，再适配 Android 入站、DNS 接管与引导解析、日志、统计和分组转发 |
| **构建系统** | 使用 `ndkBuild` 和 `Android.mk`，配合标准 Gradle 配置 | 使用 CMake 和 `CMakeLists.txt`；原生隧道目标包含 Android 16 KB 内存页对齐链接选项，并使用 Version Catalog 和 Plugins DSL。工具链与依赖版本不在此重复 |
| **界面与布局** | 使用标准 Material 3 界面 | 使用 `compose-miuix-ui` 实现 Xiaomi HyperOS / MIUI 风格的界面，并针对手机和平板提供自适应布局、NavigationRail 支持和 Android 12+ 动态取色 |
| **数据存储与通信架构** | 使用 ContentProvider 封装的 `SharedPreferences` 和 `Gson` | 直接使用轻量级原生 `SharedPreferences` 与 `kotlinx.serialization`；UI 与后台服务通过内存级 `StateFlow` / `SharedFlow` 实现响应式通信 |
| **核心组件** | 仅 Xray-core launcher + `hev-socks5-tunnel` | Xray-core launcher 加可选 Hev（C/lwIP）、SimpleTUN（源码内 Zig）和原生 Xray TUN 后端；外部组件 revision 由 `version.properties` 固定 |
| **ABI 打包** | 提供 `arm64-v8a` 和 `x86_64` 分包 APK，以及通用 APK | 仅提供 `arm64-v8a` APK |
| **TUN 后端设置** | 不提供 Xray TUN 后端设置 | 可选 `Xray TUN`、`SimpleTUN` 和 `Hev Socks5 Tunnel`，默认值为 `Hev Socks5 Tunnel` |

## 当前使用方式与兼容行为

- **配置**管理本地／订阅来源及所属节点；**代理**负责成员选择、测速和运行组设置；**设置 → 规则**编辑有序结构化规则并打开规则资源。加号／更多菜单及准确导入格式见[配置说明](./configuration_CN.md)和[节点导入](./xray-imports.md)。
- 规则直接指向组、节点或内置目标。`GLOBAL` 仍使用默认组，`DIRECT`／`direct` 和 `REJECT`／`block` 不变。旧别名目标只迁移一次到所属组，重命名维护相关目标；没有别名编辑器或 API。参见[流量规则](./rules.md)。
- 完整配置与 Lua 编辑页面已移除，但不删除已有数据。首次初始化存储时保留旧全局／节点／分组数据，并转换支持的旧规则模式；不支持的脚本逻辑可能需要手动调整规则。
- DNS 提供解析策略、DoH 引导、全局服务器、分流服务器及 hosts 入口，不重置已保存的默认值。DNS 保存会重载运行中的核心；入站嗅探 `routeOnly` 修改需重启核心。
- APK 内置 zashboard，地址为 `http://127.0.0.1:<port>/ui/`，默认端口 `9090`。静态资源不需 API 鉴权或外部托管页面，HTTP／WebSocket 控制接口仍需密钥。App 入口用浏览器片段自动设置；手动输入网址时首次需填写后端与密钥。监听器和服务须运行，实时数据还依赖核心。参见 [Clash API](./clash-api.md)。
- 主备组可只为受影响的目标选用其他成员。真实业务和对照测活用于整组降权；清理保留活跃上传、下载及健康的空闲会话。详见[主备回落与恢复](./configuration_CN.md#主备回落与恢复)。

---

[返回项目 README](./README_CN.md)
