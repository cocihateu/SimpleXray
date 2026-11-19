# Xray 原生节点导入与协议转换说明

本模块提供面向 Xray 原生内核的节点解析与配置转换能力。所有出站节点统一映射为 Xray 官方支持的数据结构，直接由原生内核运行。

## 支持的原生出站协议

- **VLESS**：支持标准 UUID、流控（flow，如 xtls-rprx-vision）及多种底层传输协议。
- **VMess**：支持标准 UUID、安全算法设置及传输流配置。
- **Trojan**：支持密码验证与 TLS/Reality 握手。
- **Shadowsocks**：支持原生 AEAD 出站；UoT v1/v2 由本地转发层封装后交给 Xray SS TCP 出站，外置插件显示具体跳过原因。
- **Hysteria2**：转换为原生 `hysteria` 出站与传输，保留认证和 TLS 服务名；混淆与端口跳跃需要对应原生配置。
- **SOCKS / HTTP**：支持出站代理与可选认证信息。
- **WireGuard**：支持私钥、对端公钥、预共享密钥及保留字段配置。

## 传输流规范

- **TCP**：标准 TCP 传输，可选注入 HTTP 伪装报文头（置于 tcpSettings 中）。
- **WebSocket**：标准 ws 传输，配置路径与 Host 标头。
- **HTTPUpgrade**：支持标准 HTTPUpgrade 流配置。
- **gRPC**：支持服务名与多路复用配置。
- **旧 HTTP / H2 / QUIC**：当前 Xray 已移除这些传输，导入器提示采用服务端提供的 XHTTP 配置。
- **xHTTP / SplitHTTP**：支持多分片传输协议。

## 跳过原因与状态反馈

解析流程针对以下场景执行跳过，并把详细原因记录于 `skippedReasons` 列表：

1. **涉及辅助内核的协议**（例如 Hysteria v1、TUIC、AnyTLS、Snell、SSR）：系统记录原因并跳过导入。Hysteria2 使用原生转换。
2. **涉及外置插件的 Shadowsocks 配置**：插件记录具体原因并跳过；UoT 与插件分别处理。
3. **关键字段缺失或格式异常**：包含服务器地址空白、端口数值越界、UUID 缺失等情况。
4. **关闭 TLS 证书验证的配置**：当前核心已移除 `allowInsecure`，导入器提示使用有效证书。

## 界面展示

工作区导入控制器直接读取 `NodeImportResult.skippedReasons`，在节点导入完毕后向用户展示跳过统计与前五条具体原因，确保导入状态透明。

代理页内的分组管理与 Clash API 共用 `SubscriptionService`。有效节点正常保存；正文已下载且全部条目被跳过时返回 422，并显示转换原因。HTTP 下载错误保留对应状态码。

分组类型为基本或订阅。页面右上角加号创建分组，订阅组保存后自动拉取节点。组内加号提供剪贴板、文件导入和手动添加节点。右上角更多菜单进入分组管理，再进入各组的来源、链式代理及出站策略与测活设置；嵌套成员通过策略编辑器设置。

转换后的每个节点均可编辑 Xray JSON 与 `targetStrategy`。用户保存的编辑通过 `userEdited` 标记保留；其余节点随订阅更新。`subscriptionName` 用于将订阅原始名称与工作区节点对应。

## UDP over TCP

Mihomo 的 `udp-over-tcp` / `udp-over-tcp-version` 与 sing-box 的 `udp_over_tcp` 转换为节点参数 `udpOverTcpVersion`。零表示原生 UDP，1 和 2 对应 UoT 版本，启用且省略版本时采用 v2。

当前 Xray SS 配置结构缺少 UoT 字段，因此原生出站 JSON 保持 SS 标准字段。业务转发层通过 Xray SS TCP 连接特殊协议目标，完成 UDP 长度与地址封装，复用现有回落流程。此路径的内部 SS 出站使用 `AsIs` 传递协议目标域名，报文中的目标地址交由远端处理。

实现依据 SagerNet/sing 的 `common/uot/protocol.go`、`conn.go`：v1 目标为 `sp.udp-over-tcp.arpa`，v2 为 `sp.v2.udp-over-tcp.arpa`。v2 首先发送关联请求，后续报文包含地址、端口、长度与数据。
