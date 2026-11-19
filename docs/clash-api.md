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
  - 接收 JSON 如 `{"mode": "global"}`，持久化保存后重载核心配置。

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
- `GET /group`
  - 获取代理组摘要列表。

### 5.3 订阅提供者 (Providers)

- `GET /providers/proxies`
  - 列出由外部订阅生成的代理组提供者状态与节点数。
- `GET /providers/proxies/{name}`
  - 获取具体订阅提供者的节点明细。
- `PUT /providers/proxies/{name}`
  - 触发对应订阅远程拉取与节点更新。

## 6. WebSocket 实时数据流

- `GET /traffic`
  - 每秒推送当前上行与下行字节速率：`{"up": rate, "down": rate}`。
- `GET /memory`
  - 每秒推送 Xray 内存统计：`{"inuse": bytes, "oslimit": 0}`。
- `GET /logs`
  - 持续推送系统与核心运行日志：`{"type": "info", "payload": line}`。
- `GET /connections`
  - 推送转发器拥有的活动连接及全核心流量总计。物理直连路径仅参与总计。HTTP GET 同样提供当前列表，DELETE 支持关闭指定连接或全部转发器连接。

## 7. 差异与暂缓开放端点

- `/rules` 展示 Xray JSON 规则；Lua 动态规则返回 501 并指向应用内脚本编辑。DNS 查询与规则提供者管理保留在应用设置。
- 配置热重载聚焦路由模式切换与节点实时选择。
- 分组延迟查询支持 `/group/{name}/delay`。界面显式测速可能触发主节点检查；自动恢复检查仍服从分组计时。
- 设置页复制 `http://127.0.0.1:9090`（或自定义端口）与密钥到同机 zashboard 控制器设置。浏览器的本地网络访问授权按浏览器提示处理。
