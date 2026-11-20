# Clash REST & WebSocket API Specification

## 1. 概述

本模块为 SimpleXray 提供与 Clash / Mihomo 控制面板（如 zashboard、Clash Verge 等）兼容的外部控制器接口。
服务运行在本地回环地址（`127.0.0.1`），由前台服务在核心就绪后按需启动与停止。

## 2. 外部依赖配置

本服务采用轻量级嵌入式库实现 HTTP 与 WebSocket 协议交互：

```kotlin
implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
```

## 3. 核心组件

- **ClashApiPreferences**: 负责读取与保存配置项：
  - `enabled`: 开关状态，默认关闭。
  - `port`: 监听端口，默认 9090。
  - `secret`: API 安全令牌，初次启动自动生成 UUID，支持重置。
  - `logLevel`: 面板独立日志级别，初始 `error`，与 App 保存级别分别持久化。
- **ClashTagMapper**: 解决代理组与物理节点同名标签冲突问题。若两者重名，物理节点对外名称追加 ` (Node)` 后缀，确保名称全局唯一并双向解析。
- **ClashApiServer**: 继承自 `NanoWSD`，实现 `Closeable` 接口。

## 4. 鉴权与跨域

- **CORS 支持**: OPTIONS 预检请求返回成功状态，并包含通用允许标头。
- **凭证校验**:
  - HTTP 请求头: `Authorization: Bearer <secret>`
  - URL 查询参数: `?token=<secret>`（适用于 WebSocket 连接）
  - 若校验失败，返回 HTTP 401 Unauthorized。

## 5. HTTP REST 接口

### 5.1 基础与配置

- `GET /version`
  - 返回 API 适配层版本、Xray 核心标识与连接统计范围。
- `GET /configs`
  - 返回当前的端口分配、路由模式（`rule` / `global` / `direct`）与基础参数。
- `PUT /configs` 或 `PATCH /configs`
  - 接收 JSON 如 `{"mode": "global"}`，持久化保存后通过 Xray RoutingService 更新规则。
  - `log-level` 更新面板保存级别。核心综合 App 保存级别、面板保存级别和当前日志订阅级别，按最详细的一档输出；实际输出级别变化时自动重载核心，App 保存级别独立保留。

### 5.2 代理与节点

- `GET /proxies`
  - 返回全部代理组与物理节点列表。注入虚拟节点 `GLOBAL`、`DIRECT`、`REJECT`。
  - 代理组包含 `all`（成员列表）、`now`（当前激活成员）、`history`（测速历史）。
- `GET /proxies/{name}`
  - 查询指定节点或代理组的详细属性。
- `PUT /proxies/{name}`
  - 切换代理组的出站目标。请求体如 `{"name": "TargetNode"}`。实时调用核心切换并更新工作区。
- `GET /proxies/{name}/delay`
  - 测试目标延迟。参数包含 `url` 与 `timeout`。成功返回 `{"delay": ms}`，超时返回 504。
  - 首次 HEAD 准备连接，第二次测量请求发送到响应头返回的往返。超时覆盖准备和两次请求，节点使用独立调度器，测量使用独立连接池。
  - 测量专用出站统一使用 `targetStrategy: AsIs`，业务出站保留节点设置；节点、分组和订阅测活入口共用此策略。
- `GET /group`
  - 获取代理组摘要列表。

### 5.3 订阅提供者 (Providers)

- `GET /providers/proxies`
  - 列出由外部订阅生成的代理组提供者状态与节点数。
- `GET /providers/proxies/{name}`
  - 获取具体订阅提供者的节点明细。
- `PUT /providers/proxies/{name}`
  - 与原生订阅页共用 SubscriptionService，触发订阅拉取与节点转换。
  - 有效节点成功保存后返回 204；跳过条目原因写入日志。
  - 下载错误返回对应 HTTP 状态；转换后有效节点为零返回 422。

### 5.4 规则与连接

- `GET /rules`
  - 从结构化规则工作区读取类型、内容、目标、索引、UUID 和启用状态。
- `PATCH /rules/disable`
  - 按索引设置停用状态，并实时更新 Xray 路由规则。
- `GET /connections`
  - 返回业务入口中的活动 TCP / UDP 连接，包含目标域名或地址、实际叶节点、节点组链、命中规则及字节计数。
- `DELETE /connections/{id}` 与 `DELETE /connections`
  - 关闭指定连接或全部业务连接。

## 6. WebSocket 实时数据流

- `GET /traffic`
  - 每秒推送当前上行与下行字节速率：`{"up": rate, "down": rate}`。
- `GET /memory`
  - 每秒推送 Xray 内存统计：`{"inuse": bytes, "oslimit": 0}`。
- `GET /logs`
  - 持续推送日志：`{"type": "info", "payload": line}`。按原始等级映射 debug、info、warning、error，实时推送按该连接的 `level` 参数筛选，缺省为 `info`。
  - `level=debug` 会申请核心输出 Debug，App Warning 仍只保存警告和错误。订阅建立后应用输出需求，断开时释放；多个订阅取最详细级别。核心重载期间 API 服务和订阅继续存在。
  - 去除重复时间戳和内部连接编号，过滤 SOCKS 健康检查立即关闭提示。
  - 采集阶段过滤 Shadowsocks 的固定弃用提示，其余警告和错误沿用原有级别处理。
- `GET /connections`
  - 推送业务入口中的活动连接及核心流量总计，覆盖节点组、物理节点和直连目标。健康检查使用独立内部入口。
  - 源地址是 Xray 到转发器的本地连接地址；应用进程字段保留为空。

## 7. 差异与暂缓开放端点

- Lua 规则在首次载入时迁移成结构化规则。运行时采用 Xray JSON；复合条件保留为 Xray 类型。
- 运行中规则编辑和模式切换调用 Xray API，节点拓扑变更重启核心。
- 分组延迟查询支持 `/group/{name}/delay`。界面显式测速可能触发主节点检查；自动恢复检查仍服从分组计时。
- 设置页复制 `http://127.0.0.1:9090`（或自定义端口）与密钥到同机 zashboard 控制器设置。浏览器的本地网络访问授权按浏览器提示处理。
- 原生菜单依次为概览、代理、规则、日志和设置。日志页顶部可直接选择 App 保存级别，显示落盘记录并支持搜索、导出和清空；核心启停后继续保留。代理页右上角加号创建基本或订阅分组，更多菜单进入分组管理，zashboard 提供实时连接和独立级别的实时日志。
