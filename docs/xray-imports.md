# Xray 原生节点导入与协议转换说明

节点导入把支持的分享链接、订阅和出站对象转换为 Xray 节点，不启动辅助代理内核。Shadowsocks 的 UoT 和 simple-obfs 由应用转发／传输层配合原生 SS 出站实现。

## 导入入口与格式

在**配置**页切换到目标组，点击加号选择**从剪贴板导入**或**从文件导入**。两者使用相同解析器；导入向当前本地组或订阅组追加节点，重名节点自动获得唯一名称，不替换全局 DNS、入站或规则。

| 输入 | 当前支持范围 |
| --- | --- |
| 节点分享链接 | `vless://`、`vmess://`、`trojan://`、`ss://`、`socks://`、`socks5://`、`http://`、`https://`；可逐行粘贴。 |
| Base64 订阅正文 | 解码后为上述逐行分享链接。 |
| Xray JSON | 完整配置中的 `outbounds`、出站数组，或带 `protocol` 与 `settings` 的单个出站对象。只提取节点。 |
| Mihomo YAML／JSON | 带 `proxies` 数组的文档，转换其中支持的节点类型。 |
| sing-box JSON | `outbounds` 数组、单个出站或省略外层花括号的出站片段；仅转换已实现的协议与字段。 |

`http://`／`https://` 在节点导入中表示 HTTP 代理节点，不是下载订阅的命令。订阅网址应放入**新建分组 → 来源类型：订阅**或已有组的**订阅设置**；新建订阅组保存后会拉取节点，也可使用分组刷新或**更新全部订阅**。

解析器能读到的全局段和 Mihomo `proxy-groups` 不会由当前节点导入／订阅更新入口应用。YAML 支持指 Mihomo 节点文档，不是任意 Xray YAML 的完整配置导入。当前没有完整配置导入或编辑页面；旧配置只通过保留的数据迁移路径恢复。规则另在**设置 → 规则**使用 [JSON 导入](./rules.md)，成功后替换规则列表。

## 支持的原生出站协议

- **VLESS**：支持标准 UUID、流控（flow，如 xtls-rprx-vision）及多种底层传输协议。
- **VMess**：支持标准 UUID、安全算法设置及传输流配置。
- **Trojan**：支持密码验证与 TLS/Reality 握手。
- **Shadowsocks**：支持原生 AEAD 出站。simple-obfs HTTP/TLS 通过本地传输封装连接原生 SS 出站；UoT v1/v2 经 SS TCP 发送。其他插件显示具体名称与模式。
- **Hysteria2**：Mihomo／sing-box 节点转换为原生 `hysteria` 出站与传输，保留认证和 TLS 服务名；带混淆或端口跳跃的条目会被跳过，需提供对应原生配置。不支持 `hysteria2://`／`hy2://` 分享链接。
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

主导航为仪表盘、代理、配置、日志、设置。**配置**管理来源与所属节点，标签和左右滑动切换浏览组，不改变默认路由组。加号另有**手动添加节点**、**新建分组**；更多菜单提供**分组管理**、本地组的**重命名分组**或订阅组的**订阅设置**，存在订阅时显示**更新全部订阅**。长按标签也可编辑来源信息。节点点击进入编辑，菜单提供复制节点配置与删除。

**代理 → 组设置**管理手选、主备、自动测速策略，节点／组／`direct`／`block` 成员，成员顺序、前置代理及测活参数。非默认组提供**保存并设为默认组**，`GLOBAL` 使用该默认组；没有路由别名设置。规则与规则资源位于**设置 → 规则**，不在配置页。

节点表单覆盖 VLESS、VMess、Trojan、Shadowsocks、SOCKS、HTTP 六种协议，提供基础认证、目标 `targetStrategy`、TCP/raw、WebSocket、gRPC、XHTTP/SplitHTTP、HTTPUpgrade、TLS 和适用协议的 REALITY 参数。Shadowsocks 还提供 UoT v1/v2、simple-obfs HTTP/TLS 与 Host。表单修改原出站并保留额外字段，兼容平铺 `settings` 和旧式 `vnext`／`servers` 数组。**高级 JSON**编辑完整节点出站，覆盖其他已导入原生协议，不是整份运行配置编辑器。

手动添加、导入和编辑的节点带 `userEdited` 标记。订阅刷新按原始名称 `subscriptionName` 对应旧节点；仍在订阅中的已编辑节点保留本地编辑，其他对应节点随订阅更新。手动节点也会保留；已从订阅移除的订阅节点不因曾编辑过就永久保留。

代理页的组卡片初始收起，显示选中成员与运行时的实际叶节点，提供搜索、展开／收起和整组 HTTP 测速。手选组停止时仍可保存选择，自动组运行中可临时切换。手动测速、自动测速组测活及故障复核使用 `AsIs` 专用测量出站，业务出站保留节点的目标解析策略。主备组不因具备测活地址就正常轮询主节点，恢复与连接边界见[配置与运行行为](./configuration_CN.md)。

## UDP over TCP

Mihomo 的 `udp-over-tcp` / `udp-over-tcp-version` 与 sing-box 的 `udp_over_tcp` 转换为节点参数 `udpOverTcpVersion`。零表示原生 UDP，1 和 2 对应 UoT 版本，启用且省略版本时采用 v2。

当前 Xray SS 配置结构缺少 UoT 字段，因此原生出站 JSON 保持 SS 标准字段。业务转发层通过 Xray SS TCP 连接特殊协议目标，完成 UDP 长度与地址封装，复用现有回落流程。此路径的内部 SS 出站使用 `AsIs` 传递协议目标域名，报文中的目标地址交由远端处理。

实现依据 SagerNet/sing 的 `common/uot/protocol.go`、`conn.go`：v1 目标为 `sp.udp-over-tcp.arpa`，v2 为 `sp.v2.udp-over-tcp.arpa`。v2 首先发送关联请求，后续报文包含地址、端口、长度与数据。

## simple-obfs

Mihomo 的 `plugin: obfs` 与 `plugin-opts.mode: http/tls`、sing-box 的插件参数、SS 分享链接统一保存为 `obfsMode` 和 `obfsHost`。Xray 出站保持标准 Shadowsocks 字段，SS 加密由 Xray 执行，应用传输层只包装和解包外层报文。

TLS 模式遵循 shadowsocks/simple-obfs 的 `src/obfs_tls.c`：首段密文位于 ClientHello 的 session-ticket 扩展，后续为 TLS 应用记录；服务器首个加密握手记录携带首段响应。HTTP 模式使用 `src/obfs_http.c` 的 Upgrade 请求与响应头处理。普通 TLS 与 simple-obfs TLS 使用各自的报文格式。

运行时通过 `streamSettings.sockopt.dialerProxy` 接入本地传输入口。前置代理在传输层连接 SS 服务器时应用；原生 UDP 使用独立的原生 SS 出站，UoT 继续使用带封装的 TCP 出站。逐节点目标 `targetStrategy` 保留。
