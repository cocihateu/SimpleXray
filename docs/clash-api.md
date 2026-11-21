# Clash REST & WebSocket API Specification

## 1. 概述

本模块为 SimpleXray 提供与 Clash / Mihomo 控制面板（如 zashboard、Clash Verge 等）兼容的外部控制器接口。
服务运行在本地回环地址（`127.0.0.1`）。启用后随前台服务启动流程启动，时间早于首轮代理组测活。组会话准备完成后立即公布核心就绪，首轮 URL-test 测活随后在后台异步执行；API、核心就绪与各节点测量结果分别反映各自状态。

服务正在启动或运行时，设置页修改启用状态、端口或密钥仅停止并启动 API 监听器，Xray 核心与业务连接继续运行。服务停止时设置保持持久化，供下次启动使用。API 监听器随服务结束关闭。

## 2. 外部依赖配置

本服务采用轻量级嵌入式库实现 HTTP 与 WebSocket 协议交互：

```kotlin
implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
```

## 3. 核心组件

- **ClashApiPreferences**: 负责读取与保存配置项：
  - `enabled`: 开关状态，默认关闭。
  - `port`: 监听端口，默认 9090。
  - `secret`: API 安全令牌，首次读取自动生成 UUID；读取、写入与重置共用 `SECRET_LOCK`，并发首次读取采用同一生成值。
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
  - `log-level` 仅更新 `ClashApiPreferences.logLevel`，初始 `error`；省略 `level` 的现有 `/logs` 连接动态采用新值，显式查询参数优先生效。App 保存级别独立保留。日志级别更新产生零 `GlobalConfigStore.notifyModified` 调用和零核心重载；模式、路由与 DNS 配置沿用原有应用流程。

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
  - App 管理的 DNS 接管规则固定置于业务规则之前；规则启停、默认组与模式切换均保留该入口。
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
  - 持续推送日志：`{"type": "info", "payload": line}`。按原始等级映射 debug、info、warning、error。显式 `level` 参数独立筛选该连接并优先生效；省略时逐条读取面板保存的 `ClashApiPreferences.logLevel`，初始 `error`，随 `PATCH /configs` 的 `log-level` 更新动态生效。
  - 主核心启动时固定输出 Debug，整个会话保持该级别；同一原始流供各消费者独立筛选。`level=debug` 接收 Debug 流，App Warning 继续仅保存警告和错误。App 级别调整仅写入 SharedPreferences 并更新界面状态，控制后续文件写入和保存日志界面新增条目。
  - 所有日志级别调整与订阅打开、关闭、重连均产生零 `GlobalConfigStore.notifyModified` 调用和零核心重载。App Warning 下详细生成仍消耗 CPU 与管道传输资源；逐行 `XrayLog` Logcat 回显已移除。Access、DNS 类别开关在下次核心启动时生效，原有路由与 DNS 配置重载行为继续保留。
  - 去除重复时间戳和内部连接编号，过滤 SOCKS 健康检查立即关闭提示。
  - 采集阶段过滤 Shadowsocks 的固定弃用提示，其余警告和错误沿用原有级别处理。
- `GET /connections`
  - 推送业务入口中的活动连接及核心流量总计，覆盖节点组、物理节点和直连目标。健康检查使用独立内部入口。
  - 源地址是 Xray 到转发器的本地连接地址；应用进程字段保留为空。

## 7. 差异与暂缓开放端点

- Lua 规则在首次载入时迁移成结构化规则。运行时采用 Xray JSON；复合条件保留为 Xray 类型。
- 运行中规则编辑和模式切换调用 Xray API，节点拓扑变更重启核心。
- 分组延迟查询支持 `/group/{name}/delay`。界面显式测速可能触发主节点检查；自动恢复检查仍服从分组计时。
- 设置页通过 `ZashboardLauncher` 打开托管 zashboard，点击时读取当前 API 端口与密钥。链接采用官方 setup 参数，全部设置位于浏览器片段中。本地 API 继续使用 Bearer／WebSocket token 鉴权。设置页仍提供控制器地址与密钥复制入口。
- 原生菜单依次为仪表盘、代理、配置、日志和设置。配置页创建基本或订阅组，分别打开节点、分组设置和结构化规则子页；默认组在分组设置中指定。节点子页负责导入、表单／高级 JSON 编辑与删除。规则子页保留顺序、启用状态、匹配内容、出站目标和导入／导出控件。
- 代理页全部分组以初始收起的圆角卡片展示，提供选中成员／运行叶节点、搜索、展开／收起、成员选择与整组 HTTP 测速；停止时展示保存选择，主备组展示首个主成员。日志页顶部选择 App 保存级别，显示落盘记录并支持搜索、导出和清空；核心启停后继续保留。zashboard 提供实时连接和独立级别的实时日志。

## 8. 回落与连接范围

建连与首响应回落沿用现有逻辑，客户端载荷可用后启动首响应等待，初始重放保留至多 256 KiB。核心就绪先行发布，首轮 URL-test 在后台执行；转发入口为候选等待首轮测量结果时，采用分组超时上限。

启用响应观察的已建立 TCP 流在上传后等待下行进展，socket 写入期间持续保留确认机会。到期后通过该叶节点的 AsIs 入口执行真实 HTTP 确认。有效失败关闭对应挂起流；修改组状态额外要求选中计划、分支和测量状态仍有效。其他连接先触发切换后，旧流仍能独立确认并结束。更新的健康结果优先，空闲流保持被动。首轮待测成员可按配置顺序尝试，随后采用有效延迟排序。

已有加密流需要应用重新建连才能使用新的出口。特定目标被封锁时，测活地址仍可能正常响应；因此恢复取决于测活路径与应用重试行为。实际备用响应启动主节点恢复计时，成功恢复影响后续连接。连接关闭范围保持按流处理，API 的显式删除接口按请求关闭指定或全部业务连接。
