# Xray 原生节点导入与协议转换说明

本模块提供面向 Xray 原生内核的节点解析与配置转换能力。所有出站节点统一映射为 Xray 官方支持的数据结构，直接由原生内核运行。

## 支持的原生出站协议

- **VLESS**：支持标准 UUID、流控（flow，如 xtls-rprx-vision）及多种底层传输协议。
- **VMess**：支持标准 UUID、安全算法设置及传输流配置。
- **Trojan**：支持密码验证与 TLS/Reality 握手。
- **Shadowsocks**：支持原生 AEAD 出站；外置插件与 UoT 配置显示具体跳过原因。
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
2. **涉及外置插件的 Shadowsocks 配置**：插件与 UoT 均记录原因并跳过；原生 TCP HTTP 伪装与 simple-obfs 属于各自的传输格式。
3. **关键字段缺失或格式异常**：包含服务器地址空白、端口数值越界、UUID 缺失等情况。
4. **关闭 TLS 证书验证的配置**：当前核心已移除 `allowInsecure`，导入器提示使用有效证书。

## 界面展示

工作区导入控制器直接读取 `NodeImportResult.skippedReasons`，在节点导入完毕后向用户展示跳过统计与前五条具体原因，确保导入状态透明。
