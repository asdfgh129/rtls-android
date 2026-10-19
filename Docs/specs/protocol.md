# RTLS 协议规范 v1

- 状态：Accepted（ADR-002）
- 版本：1
- 更新日期：2026-10-19
- 适用范围：Android 客户端 ↔ RTLS 服务端

本规范是客户端与服务端的唯一契约。实现与本文冲突时，以本文为准；修改协议必须先新增 ADR。

---

## 1. 约定

| 项 | 约定 |
|---|---|
| 时间 | Unix epoch 毫秒（整数），UTC |
| ID | UUID（v4/v7 均可），字符串 |
| 坐标 | WGS-84，十进制度 |
| 编码 | UTF-8 JSON |
| 字段命名 | `camelCase` |
| 未知字段 | 服务端/客户端均应忽略，保证向前兼容 |
| 空值 | 可选字段缺省，不使用 `null` 表示“无” |
| 最大帧 | 4 KB |
| 版本 | WebSocket envelope 的 `v`；REST 路径 `/v1` |

---

## 2. 传输与认证

### 2.1 WebSocket

- 路径：`/v1/ws`
- 生产：`wss://rtls.example.com/v1/ws`
- 本地：`ws://10.0.2.2:9099/v1/ws`（仅 debug）
- 认证：升级请求必须带

```http
Authorization: Bearer <accessToken>
```

- **禁止**通过 Query 参数传 Token。
- 服务端从 JWT 解析 `deviceId/userId`，忽略消息体中的身份字段。
- 未认证/过期：HTTP 401 或 WebSocket close `4401`。

### 2.2 REST

- 基址：`/v1`
- 认证：`Authorization: Bearer <accessToken>`（除注册/刷新/健康检查）
- Content-Type：`application/json`
- 错误响应：

```json
{
  "error": {
    "code": "FORBIDDEN",
    "message": "Not allowed to view this device",
    "requestId": "req_01J8Z6YQ5K3F9M2N7P4R6S8T0V"
  }
}
```

---

## 3. Envelope

### 3.1 客户端 → 服务端

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "location_update",
  "ts": 1720000000000,
  "payload": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | integer | 是 | 协议版本，必须为 `1` |
| `id` | string | 是 | 消息唯一 ID，客户端生成 |
| `type` | string | 是 | 消息类型 |
| `ts` | integer | 是 | 客户端发送时间（epoch ms） |
| `payload` | object | 是 | 类型相关字段 |

### 3.2 服务端 → 客户端

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ack",
  "ts": 1720000000123,
  "payload": {}
}
```

- `replyTo` 仅在响应客户端消息时出现。
- 服务端主动推送（如 `location_push`）没有 `replyTo`。

---

## 4. WebSocket 消息

### 4.1 `ping` / `pong`

客户端每 30 秒发送：

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ping",
  "ts": 1720000000000,
  "payload": {}
}
```

服务端回复：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "pong",
  "ts": 1720000000042,
  "payload": {}
}
```

- 服务端 90 秒未收到任何消息/`pong` 时关闭连接（close `4408`）。
- WebSocket 协议层 ping/pong 可作为补充，但不能替代应用层心跳。

### 4.2 `subscribe`

请求：

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "subscribe",
  "ts": 1720000000000,
  "payload": {
    "targetDeviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11"
  }
}
```

成功：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ack",
  "ts": 1720000000100,
  "payload": {
    "status": "ok"
  }
}
```

订阅成功后，服务端立即发送：

- `presence`（目标在线状态）；
- 如果存在最新位置，发送一次 `location_push`。

规则：

- 必须是好友，且目标 `share_enabled = true`，双方无拉黑；
- 重复订阅幂等；
- 单连接最多 100 个订阅；
- 订阅只属于当前连接，重连后必须重新订阅。

### 4.3 `unsubscribe`

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "unsubscribe",
  "ts": 1720000000000,
  "payload": {
    "targetDeviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11"
  }
}
```

服务端回复 `ack`。取消不存在的订阅也返回 `ok`（幂等）。

### 4.4 `location_update`

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "location_update",
  "ts": 1720000000000,
  "payload": {
    "clientMsgId": "c0a8012e-7f3b-4a8e-9d2c-5b6f7a8b9c0d",
    "crs": "WGS84",
    "sourceCrs": "WGS84",
    "latitude": 39.9042,
    "longitude": 116.4074,
    "accuracy": 15.0,
    "provider": "gps",
    "speed": 1.2,
    "bearing": 87.5,
    "isMock": false,
    "battery": 0.82
  }
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `clientMsgId` | string(UUID) | 是 | 设备内唯一，用于幂等 |

> `envelope.id` 是本次传输的消息 ID，重试时可以重新生成；`clientMsgId` 是业务幂等键，重试时**必须复用**。
| `crs` | string | 是 | 必须为 `WGS84` |
| `sourceCrs` | string | 否 | `WGS84` / `GCJ02` / `BD09`，默认 `WGS84` |
| `latitude` | number | 是 | `[-90, 90]`，禁止 NaN/Infinity |
| `longitude` | number | 是 | `[-180, 180]`，禁止 NaN/Infinity |
| `accuracy` | number | 否 | `[0, 10000]` 米 |
| `provider` | string | 否 | `gps` / `network` / `fused` / `passive` / `unknown` |
| `speed` | number | 否 | `[0, 150]` m/s |
| `bearing` | number | 否 | `[0, 360)` 度 |
| `isMock` | boolean | 否 | 模拟位置标记 |
| `battery` | number | 否 | `[0, 1]` |

服务端处理：

1. 校验 envelope 与 payload；
2. 校验 `ts` 在 `[now - 7d, now + 5min]`；
3. 以 `(deviceId, clientMsgId)` 幂等写入；
4. 更新 `device_latest`；
5. 持久化成功后发送 `ack`；
6. 向订阅了该设备的在线好友推送 `location_push`。

成功：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ack",
  "ts": 1720000000200,
  "payload": {
    "status": "ok",
    "duplicate": false
  }
}
```

重复消息：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ack",
  "ts": 1720000000200,
  "payload": {
    "status": "ok",
    "duplicate": true
  }
}
```

### 4.5 `query_location`

查询目标设备最新位置。适合进入好友详情页时主动拉取；实时更新仍依赖 `subscribe`。

```json
{
  "v": 1,
  "id": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "query_location",
  "ts": 1720000000000,
  "payload": {
    "targetDeviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11"
  }
}
```

响应：带 `replyTo` 的 `location_push`，或 `error(NO_LOCATION)`。

### 4.6 服务端推送 `location_push`

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "type": "location_push",
  "ts": 1720000000300,
  "payload": {
    "deviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11",
    "latitude": 39.905,
    "longitude": 116.408,
    "accuracy": 20.0,
    "provider": "fused",
    "sourceCrs": "WGS84",
    "deviceTs": 1720000000000,
    "serverTs": 1720000000300,
    "stale": false,
    "online": true
  }
}
```

- `stale = true`：最新位置超过阈值（默认 5 分钟）未更新，客户端应弱化展示。
- `online`：当前连接状态。
- 客户端应忽略 `serverTs` 早于该设备已展示位置的推送。
- 作为 `query_location` 的响应时，服务端会带 `replyTo`；主动推送不带。

### 4.7 服务端推送 `presence`

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "type": "presence",
  "ts": 1720000000300,
  "payload": {
    "deviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11",
    "online": false,
    "lastSeenAt": 1720000000000
  }
}
```

订阅成功后发送一次；之后在连接建立/断开时向订阅者广播。

### 4.8 `subscription_revoked`

当好友关系解除、拉黑、目标关闭分享或设备被撤销时：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "type": "subscription_revoked",
  "ts": 1720000000300,
  "payload": {
    "deviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11",
    "reason": "unfriended"
  }
}
```

`reason`：`unfriended` / `blocked` / `sharing_disabled` / `device_revoked`。

### 4.9 `ack`

服务端对所有客户端消息返回：

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "ack",
  "ts": 1720000000200,
  "payload": {
    "status": "ok",
    "duplicate": false
  }
}
```

### 4.10 `error`

```json
{
  "v": 1,
  "id": "srv_01J8Z7A2M4N6P8Q0R2S4T6V8W0",
  "replyTo": "01J8Z6YQ5K3F9M2N7P4R6S8T0V",
  "type": "error",
  "ts": 1720000000200,
  "payload": {
    "code": "FORBIDDEN",
    "message": "Not allowed to subscribe to this device",
    "details": {}
  }
}
```

---

## 5. REST API

### 5.1 认证

#### `POST /v1/auth/register`

请求：

```json
{
  "inviteCode": "ABCD-EFGH",
  "deviceName": "Pixel 8",
  "platform": "android",
  "appVersion": "1.0.0"
}
```

响应 `201`：

```json
{
  "userId": "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071",
  "deviceId": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11",
  "accessToken": "<jwt>",
  "refreshToken": "<opaque>",
  "accessExpiresIn": 900,
  "refreshExpiresIn": 2592000
}
```

错误：

- `410 INVITE_EXPIRED`
- `409 INVITE_USED`
- `429 RATE_LIMITED`

#### `POST /v1/auth/refresh`

```json
{
  "refreshToken": "<opaque>"
}
```

响应 `200`：

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque>",
  "accessExpiresIn": 900,
  "refreshExpiresIn": 2592000
}
```

#### `POST /v1/auth/logout`

需要认证。

```json
{
  "refreshToken": "<opaque>"
}
```

响应 `204`。

#### `POST /v1/auth/revoke-all`

需要认证。撤销当前设备全部 Refresh，递增 `token_version`。响应 `204`。

#### `GET /v1/me`

```json
{
  "user": {
    "id": "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071",
    "displayName": "Alice",
    "friendCode": "A1B2C3D4",
    "shareEnabled": true
  },
  "device": {
    "id": "8f14e45f-ceea-467f-a8d0-1c0f8a3b2c11",
    "name": "Pixel 8",
    "platform": "android",
    "isPrimary": true,
    "status": "active",
    "lastSeenAt": 1720000000000
  }
}
```

### 5.2 好友

#### `POST /v1/friends/requests`

二选一：

```json
{ "targetUserId": "..." }
```

或：

```json
{ "friendCode": "A1B2C3D4" }
```

响应 `201`：

```json
{
  "requestId": "0b7c...",
  "status": "pending",
  "target": {
    "userId": "...",
    "displayName": "Bob",
    "friendCode": "A1B2C3D4"
  }
}
```

规则：

- 不能加自己；
- 已好友：`409 ALREADY_FRIENDS`；
- 已有反向 pending：自动接受并返回 `200`；
- 被拉黑：`403 FORBIDDEN`。

#### `GET /v1/friends/requests?direction=incoming&status=pending`

`direction`：`incoming` / `outgoing`；`status`：`pending` / `accepted` / `rejected` / `cancelled`。

```json
{
  "requests": [
    {
      "id": "0b7c...",
      "direction": "incoming",
      "status": "pending",
      "user": {
        "userId": "...",
        "displayName": "Bob",
        "friendCode": "A1B2C3D4"
      },
      "createdAt": 1720000000000
    }
  ]
}
```

#### `POST /v1/friends/requests/{id}/accept`

响应 `200`：

```json
{
  "friend": {
    "userId": "...",
    "displayName": "Bob",
    "friendCode": "A1B2C3D4",
    "primaryDeviceId": "...",
    "online": true,
    "lastSeenAt": 1720000000000
  }
}
```

#### `POST /v1/friends/requests/{id}/reject`

响应 `204`。

#### `GET /v1/friends`

```json
{
  "friends": [
    {
      "userId": "...",
      "displayName": "Bob",
      "friendCode": "A1B2C3D4",
      "primaryDeviceId": "...",
      "shareEnabled": true,
      "online": true,
      "lastSeenAt": 1720000000000
    }
  ]
}
```

#### `DELETE /v1/friends/{userId}`

解除好友，同时服务端撤销相关订阅。响应 `204`。

#### `POST /v1/blocks/{userId}` / `DELETE /v1/blocks/{userId}`

拉黑/取消拉黑。拉黑时自动解除好友、撤销订阅。响应 `204`。

### 5.3 设备与历史

#### `GET /v1/devices/{deviceId}/latest`

```json
{
  "deviceId": "...",
  "latitude": 39.905,
  "longitude": 116.408,
  "accuracy": 20.0,
  "provider": "fused",
  "sourceCrs": "WGS84",
  "deviceTs": 1720000000000,
  "serverTs": 1720000000300,
  "stale": false,
  "online": true
}
```

无位置：`404 NO_LOCATION`。

#### `GET /v1/devices/{deviceId}/locations`

Query：

| 参数 | 必填 | 说明 |
|---|---|---|
| `from` | 是 | epoch ms |
| `to` | 是 | epoch ms，`to > from` |
| `limit` | 否 | 默认 1000，最大 5000 |
| `cursor` | 否 | 上一页返回的 `nextCursor` |

约束：

- 时间范围最大 7 天；
- 返回点数不超过 `limit`；
- 超过范围/点数时 `truncated = true`；
- 按 `server_ts` 升序返回。

```json
{
  "deviceId": "...",
  "from": 1719990000000,
  "to": 1720000000000,
  "points": [
    {
      "deviceTs": 1719999990000,
      "serverTs": 1719999990100,
      "latitude": 39.9042,
      "longitude": 116.4074,
      "accuracy": 15.0,
      "provider": "gps",
      "sourceCrs": "WGS84"
    }
  ],
  "nextCursor": null,
  "truncated": false
}
```

#### `PATCH /v1/me/sharing`

```json
{ "enabled": false }
```

响应：

```json
{ "shareEnabled": false }
```

关闭分享后，服务端撤销所有订阅该用户设备的连接。

### 5.4 健康检查

- `GET /healthz`：`{"status":"ok","db":"ok"}`；不需要认证，但只在内网/本机暴露。
- `GET /metrics`：Prometheus 格式；只在内网/本机暴露。

---

## 6. 校验规则

| 字段 | 规则 |
|---|---|
| `v` | 必须为 `1` |
| `id` | 非空字符串，≤ 64 字符 |
| `type` | 必须是已知类型 |
| `ts` | `[now - 7d, now + 5min]` |
| `clientMsgId` | UUID |
| `targetDeviceId` | UUID |
| `latitude` | `[-90, 90]`，有限数 |
| `longitude` | `[-180, 180]`，有限数 |
| `accuracy` | `[0, 10000]` |
| `provider` | 枚举 |
| `sourceCrs` | 枚举 |
| `speed` | `[0, 150]` |
| `bearing` | `[0, 360)` |
| `battery` | `[0, 1]` |
| 帧大小 | ≤ 4096 字节 |
| 历史范围 | ≤ 7 天 |
| 历史点数 | ≤ 5000 |

非法消息返回 `INVALID_PAYLOAD`，不写入数据库。

---

## 7. 限流

| 范围 | 限制 |
|---|---|
| WebSocket 单设备 | 60 条/分钟；位置最小间隔 2s，突发 3 条 |
| WebSocket 连接数 | 每设备最多 3 条 |
| `subscribe` | 每连接最多 100 个目标 |
| 注册 | 10 次/分钟/IP |
| 刷新 Token | 30 次/分钟/设备 |
| 好友操作 | 60 次/分钟/用户 |
| 历史查询 | 30 次/分钟/用户 |
| 最新位置 | 120 次/分钟/用户 |

超限：

- WebSocket：`error(RATE_LIMITED)`，持续超限则 close `4429`；
- REST：HTTP `429` + `Retry-After`。

---

## 8. 幂等、顺序与离线补传

### 8.1 幂等

- `location_update` 以 `(deviceId, clientMsgId)` 唯一；
- 重复消息返回 `ack.duplicate = true`，不重复写入；
- 客户端重试必须复用同一个 `clientMsgId`。

### 8.2 顺序

- 存储与历史查询以 `server_ts` 为准；
- `device_ts` 保留用于展示与排查；
- 实时推送允许乱序到达，客户端按 `serverTs` 丢弃旧值；
- 历史接口按 `server_ts ASC` 返回。

### 8.3 离线补传

- 客户端本地队列最多 1,000 条或 24 小时，先到先淘汰；
- 网络恢复后按时间顺序批量发送；
- 服务端接受最多 7 天前的 `device_ts`；
- 补传使用原 `clientMsgId`，保证不重复。

---

## 9. 订阅生命周期

```text
连接建立
  │
  ├─ 无订阅
  │
  ├─ subscribe(targetDeviceId)
  │     ├─ 校验好友/分享/拉黑
  │     ├─ ack
  │     ├─ presence
  │     └─ location_push（如果有最新位置）
  │
  ├─ location_update（目标设备）
  │     └─ location_push 给所有订阅者
  │
  ├─ unsubscribe
  │     └─ ack
  │
  ├─ 好友解除/拉黑/关闭分享
  │     └─ subscription_revoked
  │
  └─ 连接断开
        └─ 订阅全部失效，重连后重新订阅
```

---

## 10. 心跳与重连

### 10.1 心跳

- 客户端每 30 秒发送 `ping`；
- 服务端立即回 `pong`；
- 服务端 90 秒未收到任何消息 → close `4408`；
- Cloudflare 非企业版 WebSocket 空闲超时约 100 秒，30 秒心跳必须保留。

### 10.2 重连

- 退避：1s、2s、4s、8s、16s、30s、60s；
- 抖动：±20%；
- 成功连接并稳定 30 秒后重置退避；
- `4401`：尝试刷新 Access Token，再重连；刷新失败则要求重新登录；
- `4403`：不要重试，提示无权限；
- 网络切换（WiFi ↔ 蜂窝）立即重连，不等待退避；
- 重连后：
  1. 重新 `subscribe` 所有当前页面需要的设备；
  2. 用 `query_location` 或 REST 拉取最新位置；
  3. 补传离线队列。

---

## 11. 错误码与关闭码

### 11.1 错误码（`error.payload.code`）

| 代码 | 含义 | 客户端处理 |
|---|---|---|
| `AUTH_REQUIRED` | 缺少 Token | 刷新/重新登录 |
| `AUTH_INVALID` | Token 无效/过期 | 刷新/重新登录 |
| `FORBIDDEN` | 无权限（非好友/拉黑/未分享） | 不重试，提示用户 |
| `NOT_FOUND` | 目标不存在 | 刷新好友列表 |
| `NO_LOCATION` | 目标暂无位置 | 显示“暂无位置” |
| `INVALID_PAYLOAD` | 字段非法 | 修正后重试；不要原样重发 |
| `UNSUPPORTED_VERSION` | 协议版本不支持 | 提示升级 App |
| `RATE_LIMITED` | 超限 | 按 `Retry-After` 退避 |
| `SUBSCRIPTION_LIMIT` | 订阅数超限 | 取消不需要的订阅 |
| `CONFLICT` | 状态冲突（已好友等） | 刷新状态 |
| `ALREADY_FRIENDS` | 已经是好友 | 刷新好友列表 |
| `INVITE_EXPIRED` | 邀请码已过期 | 联系管理员 |
| `INVITE_USED` | 邀请码已被使用 | 联系管理员 |
| `INTERNAL` | 服务端错误 | 指数退避重试 |

### 11.2 WebSocket 关闭码

| 关闭码 | 含义 |
|---|---|
| `1000` | 正常关闭 |
| `1011` | 服务端内部错误 |
| `4400` | 协议版本/消息格式错误 |
| `4401` | 未认证/Token 失效 |
| `4403` | 无权限 |
| `4408` | 心跳超时 |
| `4429` | 限流 |
| `4500` | 服务端错误 |

---

## 12. 版本演进

- 新增字段：允许，旧客户端忽略；
- 删除/改名字段：必须升级 `v`；
- 新增消息类型：旧客户端收到未知类型应忽略并记录；
- 服务端在过渡期可同时支持 `v=1` 与 `v=2`；
- 不支持版本：`error(UNSUPPORTED_VERSION)`，随后 close `4400`。

---

## 13. 典型流程

### 13.1 首次注册与连接

```text
App                         Server
 │  POST /v1/auth/register    │
 │───────────────────────────>│
 │<───────────────────────────│ 201 tokens
 │  WS upgrade + Bearer       │
 │───────────────────────────>│
 │<───────────────────────────│ 101 Switching Protocols
 │  subscribe(A)              │
 │───────────────────────────>│
 │<───────────────────────────│ ack + presence + latest
```

### 13.2 位置上报与好友推送

```text
A                    Server                    B
 │ location_update     │                        │
 │────────────────────>│                        │
 │                     │ 1. validate            │
 │                     │ 2. insert locations    │
 │                     │ 3. upsert latest       │
 │<────────────────────│ ack                    │
 │                     │ location_push          │
 │                     │───────────────────────>│
```

### 13.3 断网重连

```text
A                    Server
 │ 网络断开             │
 │ 本地队列缓存位置      │
 │ ...                 │
 │ WS reconnect        │
 │────────────────────>│
 │<────────────────────│ 101
 │ subscribe(...)      │
 │────────────────────>│
 │<────────────────────│ ack + latest
 │ location_update ×N  │  （原 clientMsgId）
 │────────────────────>│
 │<────────────────────│ ack（duplicate=false）
```

---

## 14. 安全清单

- [ ] 生产只允许 WSS。
- [ ] Token 只在 `Authorization` Header，不进入 URL/日志。
- [ ] 服务端从 Token 取身份，忽略消息体 `deviceId`。
- [ ] 每次 `subscribe`/`query_location`/历史查询都校验好友与拉黑。
- [ ] 单帧 ≤ 4 KB，字段白名单校验。
- [ ] 位置频率与消息频率限流。
- [ ] 位置数据不写入 INFO 日志；必要时脱敏。
- [ ] 关闭分享/解除好友/拉黑后立即撤销订阅。
- [ ] 设备撤销后 `token_version` 递增，Access Token 15 分钟内自然失效。

---

## 15. 机器可读契约

Phase 0 需要落地：

```text
server/src/protocol/
├── envelope.ts          # Zod schema
├── messages.ts          # 消息类型与 payload schema
├── errors.ts            # 错误码常量
└── schemas/
    ├── ws-messages.json # JSON Schema（可选导出）
    └── rest.yaml        # OpenAPI 3.1
```

契约测试必须覆盖本文所有消息类型和错误码。
