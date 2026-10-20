# RTLS 系统架构

- 状态：现行（Current）
- 版本：1.0
- 日期：2026-10-19
- 取代：[初始方案（归档）](../archive/plan-2026-10-17.md)
- 权威细节：[`decisions/`](./decisions/README.md)、[`protocol.md`](../specs/protocol.md)、[`data-model.md`](../specs/data-model.md)
- 实施计划：[`roadmap.md`](../planning/roadmap.md)、[`phase-0-tasks.md`](../planning/phase-0-tasks.md)

本文是 RTLS 的架构总览，只描述"系统由什么组成、数据怎么流动、有哪些硬约束"。
每个技术选型的**结论与理由以 ADR 为准**，本文不重复论证；接口与库表的**细节以 `protocol.md` / `data-model.md` 为准**。

---

## 1. 目标与非目标

### 1.1 目标

RTLS（Real-Time Location Sharing）让**互相授权的好友**在 Android 上实时查看对方位置，并可回放历史轨迹。

- 采集：Android 持续定位并上传；
- 中转/存储：服务端校验、落库、实时 fan-out；
- 展示：好友端在百度地图上看到实时位置与历史轨迹。

### 1.2 MVP 边界（非目标）

| 不做 | 原因 |
|---|---|
| 开放注册 | 邀请制，见 ADR-004 |
| TDengine / 时序库双写 | MVP 单库，见 ADR-009 |
| 多实例横向扩展与跨实例广播 | 单机 MVP，见 ADR-001 / ADR-008 |
| 组织、群组、地理围栏、告警 | 当前只有好友互查，见 ADR-005 |
| 百度鹰眼纠偏 | 优先本地滤波，见 ADR-006 |
| 海外地图 | MVP 只服务国内，见 ADR-006 |

---

## 2. 系统上下文

| 角色 | 说明 |
|---|---|
| Android App | 采集位置（前台服务）、WebSocket 上报、百度地图渲染、离线队列 |
| Cloudflare Edge | 公网入口，终止 TLS（WSS:443），转发到 Tunnel |
| `cloudflared` | 容器内的出站隧道客户端，无入站端口 |
| `api`（Fastify） | REST + WebSocket 同进程；鉴权、校验、限流、订阅、落库 |
| SQLite 3.45+ | 唯一持久化存储（单文件，身份、关系、位置） |
| 运维 CLI | 邀请码管理、清理任务、备份 |

---

## 3. 架构总览

```text
┌──────────────────────────────────────────────────────────┐
│ Android App                                              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│  │ LocationSource│→│ 上传队列/补传 │→│ RtlsWebSocketClient│ │
│  │ (FGS 持有)    │ │ (Room)       │ │ (OkHttp, 心跳/退避)│ │
│  └──────────────┘ └──────────────┘ └──────────────────┘  │
│         │                                   │            │
│         │ WGS-84 GeoPoint                   │            │
│         ▼                                   ▼            │
│  ┌──────────────┐              ┌───────────────────────┐ │
│  │ 百度地图渲染层 │              │ 好友/历史 REST 调用     │ │
│  │ WGS-84→BD-09 │              └───────────────────────┘ │
│  └──────────────┘                                        │
└──────────────────────────────────────────────────────────┘
                          │ WSS (443)
                          ▼
                 Cloudflare Edge
                          │ Tunnel（出站）
                          ▼
┌──────────────────────────────────────────────────────────┐
│ api（Node.js 24 LTS / TypeScript / Fastify 5）            │
│  ┌────────┐ ┌────────┐ ┌──────────┐ ┌────────────────┐   │
│  │ http/  │ │  ws/   │ │  auth/   │ │ friends/       │   │
│  │ REST   │ │ 网关    │ │ JWT/Refresh│ │ 好友/申请/拉黑 │   │
│  └────────┘ └────────┘ └──────────┘ └────────────────┘   │
│  ┌────────────────┐ ┌──────────┐ ┌──────────────────┐    │
│  │ locations/     │ │  db/     │ │ protocol/        │    │
│  │ 写入/最新/历史  │ │ Conn+Repo│ │ Zod schema/错误码 │    │
│  └────────────────┘ └──────────┘ └──────────────────┘    │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│ SQLite 3.45+（/data/rtls.db，WAL，本机卷）                  │
│  users / devices / refresh_tokens / invite_codes         │
│  friend_requests / friendships / blocks                  │
│  locations / device_latest                               │
└──────────────────────────────────────────────────────────┘
```

部署形态与网络约束见 [`deployment.md`](../guides/deployment.md) 与 ADR-008。

---

## 4. 核心数据流

### 4.1 位置上报

1. `LocationSource` 产出 `LocationSample`，统一转换为 **WGS-84**（ADR-006）；
2. 写入 Room 队列（离线可缓存，见 `protocol.md` §8.3）；
3. WebSocket `location_update`，带 `clientMsgId`；
4. 服务端校验 → 幂等写 `locations` → UPSERT `device_latest` → `ack`；
5. 同一事务成功后向订阅者 fan-out `location_push`。

### 4.2 好友订阅与实时推送

1. B 通过 REST 与 A 成为好友（user 级关系，ADR-005）；
2. B 的 WS 连接发送 `subscribe(targetDeviceId)`；
3. 服务端校验好友关系 / `share_enabled` / 双向拉黑；
4. 成功后立即回 `ack` + `presence` + 一次 `location_push`；
5. 之后 A 每次上报都 fan-out 给 B；连接断开则订阅全部失效，重连后必须重新订阅（ADR-002）。

### 4.3 历史轨迹

- **不通过 WebSocket**：走 `GET /v1/devices/{deviceId}/locations`，支持 `from/to/limit/cursor`；
- 返回 **WGS-84**，Android 渲染时转 BD-09；
- 范围上限 7 天、点数上限 5,000，超出返回 `truncated`。

### 4.4 授权判定（每次查询/订阅都要做）

请求方设备 active + 目标设备 active + 目标用户 `share_enabled` + 互为好友 + 双向无拉黑。
身份一律从 JWT 解析，**不信任消息体中的身份字段**（ADR-005）。

---

## 5. 服务端结构

| 模块 | 职责 | 不做 |
|---|---|---|
| `protocol/` | envelope 与 payload 的 Zod schema、错误码常量、OpenAPI/JSON Schema | 业务规则 |
| `auth/` | 邀请码注册、JWT 签发/校验、Refresh 轮换与 family 撤销 | 社交登录 |
| `http/` | REST 路由（auth / friends / devices / healthz / metrics） | 页面渲染 |
| `ws/` | upgrade 鉴权、心跳、订阅注册表、fan-out、限流 | 跨实例广播 |
| `locations/` | 位置写入、最新位置、历史查询、保留清理 | 纠偏算法 |
| `friends/` | 好友申请状态机、好友关系、拉黑 | 群组 |
| `db/` | `better-sqlite3` 连接、Repository、迁移执行 | ORM 隐式行为 |

目录与依赖基线见 ADR-001；存储与 Repository 接口见 ADR-009。

---

## 6. Android 结构

| 模块 | 职责 |
|---|---|
| `:app` | 单 Activity + Compose、权限流程、DI 组装 |
| `:core:model` | `GeoPoint` / `LocationSample` / `Crs`（坐标必须带 CRS 标签） |
| `:core:location` | `LocationSource` 抽象与实现（GMS FLP / 百度 / 高德 / Fake）、`LocationForegroundService` |
| `:core:network` | `RtlsWebSocketClient`、心跳、指数退避重连、REST 调用 |
| `:core:data` | Room 离线队列、Token 安全存储 |
| `:core:map` | 百度地图渲染层（WGS-84 → BD-09）、Marker / Polyline 复用 |

约定：

- 定位源可替换（ADR-007），上传前规范化为 WGS-84（ADR-006）；
- 地图层只接受 `crs = BD09` 的 `GeoPoint`；
- `debug` 用 `ws://10.0.2.2:9099`，`release` 只允许 `wss://`。

---

## 7. 关键设计约束

| 约束 | 内容 | 依据 |
|---|---|---|
| 实时协议 | 原生 WebSocket + JSON envelope（v1），非 Socket.IO | ADR-002 |
| 服务端栈 | Node.js 24 LTS（最低 22）+ TypeScript + Fastify | ADR-001 |
| 存储 | SQLite 3.45+ 单文件（WAL），位置与关系同库 | ADR-009 |
| 身份 | `users`（关系/好友码/分享开关）+ `devices`（位置/Token/在线） | ADR-005 |
| 坐标 | 全链路 WGS-84，仅百度渲染层转 BD-09 | ADR-006 |
| 定位源 | `LocationSource` 抽象，GMS 不可用时百度/高德兜底 | ADR-007 |
| 认证 | 邀请码注册 + Access JWT(15min) + 不透明 Refresh(30d，仅存哈希) | ADR-004 |
| 传输安全 | 生产只 WSS；Token 只在 `Authorization` Header | ADR-004 / ADR-008 |
| 部署 | Docker Compose + Cloudflare Tunnel，源站零入站端口 | ADR-008 |
| 幂等 | `(deviceId, clientMsgId)` 唯一；顺序以 `server_ts` 为准 | ADR-002 / `protocol.md` |
| 保活 | 30s 应用层 ping，90s 无消息断开，退避 1s→60s | ADR-002 / ADR-008 |

非功能指标（延迟、耗电、崩溃率）见 [`roadmap.md`](../planning/roadmap.md) §12。

---

## 8. 已知限制

记录在内、不是缺陷，但实现与排障时要知道：

- 单实例部署：订阅表与 presence 在内存，多实例需 Redis/NATS（ADR-001 / ADR-008）；
- SQLite 单写者 + 单进程：不支持多个 API 实例并发写；扩容需迁移存储（ADR-009）；
- DB 文件必须在本地卷，禁止 NFS/SMB；磁盘是单点故障（ADR-009）；
- Access Token 吊销最多有 15 分钟生效延迟（ADR-004）；
- 单连接 `subscribe` 上限 100 个目标；
- GCJ-02 → WGS-84 是近似转换，误差需在 UI 上可接受（ADR-006）；
- Cloudflare 非企业版 WebSocket 空闲超时不可调，必须靠 30s 心跳（ADR-008）。

---

## 9. 相关文档

- 决策：[`decisions/`](./decisions/README.md)
- 契约：[`protocol.md`](../specs/protocol.md)
- 数据：[`data-model.md`](../specs/data-model.md)
- 搭建与开发：[`guides/development.md`](../guides/development.md)
- 部署运维：[`deployment.md`](../guides/deployment.md)
- 计划：[`roadmap.md`](../planning/roadmap.md)、[`phase-0-tasks.md`](../planning/phase-0-tasks.md)
- 历史方案（归档）：[`archive/plan-2026-10-17.md`](../archive/plan-2026-10-17.md)
