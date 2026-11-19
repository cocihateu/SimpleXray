# 配置与运行流程

**[English](./configuration.md)** | **中文**

应用将全局配置、节点组和规则分别管理。运行时先组合工作区与全局配置，再执行一次偏好适配，最后注入统计、规则 API 和业务转发入口。最终 JSON 通过 stdin 传给 Xray。

## 存储与编辑

- `global_config.json` 保存 DNS、入站、日志与全局参数。
- 节点组工作区保存 Xray 原生节点、逐节点 `targetStrategy`、成员顺序、主备参数和订阅来源。
- `rules_workspace.json` 保存规则顺序、类型、匹配内容、目标和启用状态。
- 旧 Lua 在首次加载规则时迁移为结构化规则。运行配置采用 Xray JSON。
- 用户编辑过的订阅节点保留编辑结果；其余节点随订阅刷新。

## TUN

| 模式 | 行为 |
| --- | --- |
| Xray TUN | 保留或注入 `tun` inbound，传入 Android VPN 描述符。 |
| Hev TUN | 使用原版 Hev 引擎，经本地 SOCKS 入站接入 Xray。 |
| Simple TUN | 使用原版 SimpleTUN 引擎，采用 IPv4 与 `noauth` SOCKS 入站。 |

Xray TUN 的 MTU、接口名称和嗅探参数根据应用设置补齐。Hev 与 Simple TUN 模式移除配置中的桌面 TUN 入站。

## 全局适配

| 字段 | 处理 |
| --- | --- |
| `geodata` | 资源更新交由应用管理。 |
| `log.error` | 核心日志输出到应用读取的进程流。 |
| `log.access` | 关闭访问日志时设为 `none`。 |
| `log.dnsLog` | 跟随应用 DNS 日志设置。 |
| `log.loglevel` | 跟随应用日志等级；Auto 使用全局配置。 |
| 主 SOCKS 入站 | 同步应用地址、端口和认证设置。 |
| 其他通配监听地址 | 改为 `127.0.0.1`。 |
| `dns.hosts` | 按现有逻辑补齐阿里 DoH 域名的静态地址。 |
| TCP 出站 socket 参数 | 沿用原有保活和超时注入逻辑，保留用户显式配置。 |

## 分流与主备

所有业务目标均进入共享本地转发入口，覆盖节点组、物理节点、直连和拒绝目标。内部健康检查走独立入口，直接选择物理节点。每个物理节点的 `targetStrategy` 独立生效。

主节点健康期间保持业务连接，周期任务跳过主节点。首响应超时或真实业务连接失败后尝试备用；备用交付首个响应后开始恢复计时，到期单独检查主节点。恢复成功后新连接使用主节点。备用 URL-test 组按自身检查间隔和结果信任时长运行。

规则编辑、规则启停和模式切换通过 Xray API 应用。节点内容与组拓扑变化触发核心重启。

## API 与统计

运行时启用 `StatsService`、`RoutingService` 和 `HandlerService`。核心统计排除内部 `sx-` 出站计数。Clash API 将业务连接、命中规则、日志、流量与内存转换为 zashboard 格式。

连接源地址反映本地转发入口，进程字段保留为空。原生页面聚焦概览、代理、订阅、规则与设置；实时连接与日志在 zashboard 查看。

[返回项目 README](./README_CN.md)
