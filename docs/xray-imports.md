# Xray 原生节点导入与协议转换说明

本模块提供面向 Xray 原生内核的节点解析与配置转换能力。所有出站节点统一映射为 Xray 官方支持的数据结构，直接由原生内核运行。

## 支持的原生出站协议

- **VLESS**：支持标准 UUID、流控（flow，如 xtls-rprx-vision）及多种底层传输协议。
- **VMess**：支持标准 UUID、安全算法设置及传输流配置。
- **Trojan**：支持密码验证与 TLS/Reality 握手。
- **Shadowsocks**：支持原生 AEAD 出站。simple-obfs HTTP/TLS 通过本地传输封装连接原生 SS 出站；UoT v1/v2 经 SS TCP 发送。其他插件显示具体名称与模式。
- **Hysteria2**：转换为原生 `hysteria` 出站与传输，保留认证和 TLS 服务名；混淆与端口跳跃需要对应原生配置。
- **SOCKS / HTTP**：支持出站代理与可选认证信息。
- **WireGuard**：支持私钥、对端公钥、预共享密钥及保留字段配置。
- **其他原生 JSON 出站**：协议列表还接收 `hysteria` 与 `masque`；这类节点使用高级 JSON 编辑完整参数。

## 传输流规范

- **TCP**：标准 TCP 传输，可选注入 HTTP 伪装报文头（置于 tcpSettings 中）。
- **WebSocket**：标准 ws 传输，配置路径与 Host 标头。
- **HTTPUpgrade**：支持标准 HTTPUpgrade 流配置。
- **gRPC**：支持服务名与多路复用配置。
- **旧 HTTP / H2 / QUIC**：当前 Xray 已移除这些传输，导入器提示采用服务端提供的 XHTTP 配置。
- **xHTTP / SplitHTTP**：支持多分片传输协议。

## 跳过原因与状态反馈

解析流程按输入格式执行对应校验，已有具体原因保存在 `skippedReasons` 列表：

1. **涉及辅助内核的协议**（例如 Hysteria v1、TUIC、AnyTLS、Snell、SSR）：系统记录原因并跳过导入。Hysteria2 使用原生转换。
2. **插件传输**：simple-obfs 的 HTTP/TLS 参数映射为节点传输设置；其余插件记录具体名称和模式。UoT 与插件分别处理，可组合使用。
3. **入口专属字段检查**：分享链接、Mihomo 和 sing-box 转换按各协议读取服务器、端口与认证字段；Mihomo 转换检查有效服务器与端口，相关协议继续检查 UUID、密码或插件参数。具体检查与反馈随转换入口而定。
4. **关闭 TLS 证书验证的配置**：当前核心已移除 `allowInsecure`，导入器提示使用有效证书。

原生 Xray JSON 路径识别 JSON／出站结构、规范化协议别名并检查支持的原生协议，随后执行共用的传输与 TLS 兼容性过滤。它保留原出站参数，服务器、端口、UUID 等参数的完整有效性由对应 Xray 配置加载决定。分享链接和 Mihomo 的字段规则更具体，各入口的成功转换表示该入口已完成自己的检查。

## 界面展示

工作区导入控制器读取 `NodeImportResult.skippedReasons`，在节点导入完毕后展示跳过统计与已有的前五条原因。

配置页的订阅更新与 Clash API 共用 `SubscriptionService`。有效节点正常保存；API 在正文已下载且全部条目被跳过时返回 422，并显示已有转换原因。HTTP 下载错误保留对应状态码。

主导航为仪表盘、代理、配置、日志、设置。配置页创建基本或订阅分组，订阅组保存后自动拉取节点；每组分别打开节点编辑与分组设置。节点子页的加号提供剪贴板、文件导入和手动添加，已有节点支持编辑与删除。分组设置编辑来源、策略、主节点、备用顺序、链式代理与测活参数，并指定默认组；主节点和备用均可选择节点或组。配置中的规则子页使用结构化类型、匹配内容、顺序、启用状态、目标及导入／JSON 导出控件。YAML 用作配置与订阅导入格式，规则编辑使用结构化界面。

节点表单覆盖 VLESS、VMess、Trojan、Shadowsocks、SOCKS、HTTP 六种协议，提供基础认证、目标 `targetStrategy`、TCP/raw、WebSocket、gRPC、XHTTP/SplitHTTP、HTTPUpgrade、TLS 和适用协议的 REALITY 参数。Shadowsocks 还提供 UoT v1/v2、simple-obfs HTTP/TLS 与 Host。表单直接修改原出站对象，保留其余字段、服务器、用户与高级参数，兼容平铺 `settings` 和旧式 `vnext`／`servers` 数组。高级 JSON 提供完整出站编辑，覆盖其余已导入原生协议。用户保存的编辑通过 `userEdited` 标记保留；其余节点随订阅更新。`subscriptionName` 用于将订阅原始名称与工作区节点对应。

代理页专注运行选择与测量：全部分组以初始收起的圆角卡片显示选中成员与实际叶节点，提供搜索、展开／收起与整组 HTTP 延迟。停止运行时展示保存选择，主备组展示首个主成员。手动测速、定时测活、恢复与停滞确认共用实际 HTTP 测量入口和 `AsIs` 专用出站，业务出站保留节点目标解析策略。

## UDP over TCP

Mihomo 的 `udp-over-tcp` / `udp-over-tcp-version` 与 sing-box 的 `udp_over_tcp` 转换为节点参数 `udpOverTcpVersion`。零表示原生 UDP，1 和 2 对应 UoT 版本，启用且省略版本时采用 v2。

当前 Xray SS 配置结构缺少 UoT 字段，因此原生出站 JSON 保持 SS 标准字段。业务转发层通过 Xray SS TCP 连接特殊协议目标，完成 UDP 长度与地址封装，复用现有回落流程。此路径的内部 SS 出站使用 `AsIs` 传递协议目标域名，报文中的目标地址交由远端处理。

实现依据 SagerNet/sing 的 `common/uot/protocol.go`、`conn.go`：v1 目标为 `sp.udp-over-tcp.arpa`，v2 为 `sp.v2.udp-over-tcp.arpa`。v2 首先发送关联请求，后续报文包含地址、端口、长度与数据。

## simple-obfs

Mihomo 的 `plugin: obfs` 与 `plugin-opts.mode: http/tls`、sing-box 的插件参数、SS 分享链接统一保存为 `obfsMode` 和 `obfsHost`。Xray 出站保持标准 Shadowsocks 字段，SS 加密由 Xray 执行，应用传输层只包装和解包外层报文。

TLS 模式遵循 shadowsocks/simple-obfs 的 `src/obfs_tls.c`：首段密文位于 ClientHello 的 session-ticket 扩展，后续为 TLS 应用记录；服务器首个加密握手记录携带首段响应。HTTP 模式使用 `src/obfs_http.c` 的 Upgrade 请求与响应头处理。普通 TLS 与 simple-obfs TLS 使用各自的报文格式。

运行时通过 `streamSettings.sockopt.dialerProxy` 接入本地传输入口。前置代理在传输层连接 SS 服务器时应用；原生 UDP 使用独立的原生 SS 出站，UoT 继续使用带封装的 TCP 出站。逐节点目标 `targetStrategy` 保留。
