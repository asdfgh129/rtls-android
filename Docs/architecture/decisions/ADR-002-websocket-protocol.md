# ADR-002：实时通信协议

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 2.2、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段一/二；详细规范见 [`../protocol.md`](../../specs/protocol.md)

## 背景

`Plan.md` 选择 Socket.IO，`Step.md` 的 Android 端使用 OkHttp 原生 WebSocket。两者不能直接互通：

- Socket.IO 在 WebSocket 之上有 Engine.IO 握手、分包、心跳和事件名；
- OkHttp 的 `WebSocket` 只实现 RFC 6455，不理解 Socket.IO 协议。

同时，原协议缺少版本号、消息 ID、ACK、幂等、限流、错误码和订阅语义。

## 决策

采用 **原生 WebSocket + JSON envelope + 版本号 v1**。

### 传输分工

| 能力 | 通道 | 原因 |
|---|---|---|
| 实时位置上报/推送 | WebSocket `/v1/ws` | 低延迟、长连接 |
| 订阅/取消订阅 | WebSocket | 与连接生命周期绑定 |
| 心跳 | WebSocket | 保活、检测半开连接 |
| 注册/刷新 Token | REST `/v1/auth/*` | 请求-响应清晰，便于限流 |
| 好友管理 | REST `/v1/friends/*` | 非实时、需要事务 |
| 历史轨迹 | REST `/v1/devices/:id/locations` | 分页、范围查询、缓存 |

### 消息 envelope

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "location_update",
  "ts": 1720000000000,
  "payload": {}
}
```

- `v`：协议版本，MVP 固定 `1`。
- `id`：客户端消息唯一 ID（UUIDv7 或 ULID）；服务端回复时通过 `replyTo` 引用。
- `type`：消息类型。
- `ts`：客户端发送时间，Unix epoch 毫秒。
- `payload`：类型相关字段。

服务端消息同样使用 envelope；`replyTo` 指向客户端消息 `id`。

### 消息类型

客户端 → 服务端：

- `ping`
- `subscribe`
- `unsubscribe`
- `location_update`
- `query_location`

服务端 → 客户端：

- `pong`
- `ack`
- `error`
- `location_push`
- `presence`
- `subscription_revoked`

> 历史轨迹不通过 WebSocket；使用 REST，以支持分页、范围限制和 HTTP 缓存。

### 可靠性

- 除 `ping`/`pong` 外，每个客户端消息都必须收到 `ack` 或 `error`。
- `location_update` 使用 `clientMsgId` 做幂等；服务端唯一约束 `(device_id, client_msg_id)`。
- 实时推送是“最新值覆盖”语义，不要求逐条可靠；历史写入是“至少一次 + 幂等”。
- 服务端以 `server_ts` 作为存储与排序依据；客户端 `ts` 仅作为设备时间保留。
- 客户端应丢弃 `serverTs` 早于该设备已展示位置的推送。

### 保活与重连

- 客户端每 30 秒发送应用层 `ping`，服务端回 `pong`。
- 服务端 90 秒未收到任何消息或 `pong` 时关闭连接。
- 客户端指数退避重连：1s、2s、4s、8s、16s、30s、60s，带 ±20% 抖动；稳定 30 秒后重置。
- 重连成功后必须重新 `subscribe`；订阅不跨连接持久化。

### 限制

- 单帧最大 4 KB。
- 单设备最多 3 条并发连接。
- 位置上报最小间隔 2 秒，允许短时突发 3 条。
- 每设备 60 条消息/分钟；超限返回 `RATE_LIMITED`。
- 历史查询最大 7 天、最多 5,000 点。

## 理由

- 原生 WebSocket 与 OkHttp 零适配成本。
- JSON 可读、易调试，MVP 流量下开销可接受。
- 版本号 + 未知字段忽略，便于协议演进。
- REST/WS 分工避免在 WebSocket 上实现分页和复杂查询。

## 后果

### 正面

- 客户端/服务端可用 `wscat`、Postman、Vitest 直接测试。
- 协议契约可以落成 JSON Schema 和契约测试。
- 不依赖 Socket.IO 的版本兼容性。

### 负面 / 需要注意

- 需要自己实现订阅注册表、ACK、限流、心跳。
- 重连后必须重新订阅，客户端逻辑要覆盖。
- 跨实例部署时需要 Redis/NATS 广播；MVP 单实例不涉及。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| Socket.IO | 不采用 | OkHttp 不兼容；房间/降级能力对 MVP 过度 |
| MQTT | 不采用 | QoS/主题模型增加运维与 Android 依赖 |
| gRPC streaming | 不采用 | Cloudflare Tunnel/移动网络下调试成本高 |
| WebSocket + Protobuf | 暂不采用 | 体积收益在 MVP 阶段不明显，调试成本更高 |

## 验证方式

- `Docs/specs/protocol.md` 中的每个消息类型都有 JSON Schema 与示例。
- 契约测试覆盖：合法消息、缺字段、非法坐标、超限、未认证、越权订阅、重复消息。
- 用 `wscat` 完成一次 `connect → subscribe → location_update → location_push → ping/pong`。
