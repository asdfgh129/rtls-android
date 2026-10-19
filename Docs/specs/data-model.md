# RTLS 数据模型（PostgreSQL 16）

- 状态：Accepted（ADR-003 / ADR-005）
- 更新日期：2026-10-19
- 对应迁移：`server/migrations/`
- 相关协议：[`protocol.md`](./protocol.md)

本文是 MVP 的权威数据模型。所有 DDL 以 PostgreSQL 16 为目标；如无特殊说明，时间均为 `TIMESTAMPTZ`，坐标为 WGS-84。

---

## 1. 设计原则

1. **用户与设备分离**：好友关系在 user 级，位置在 device 级。
2. **规范化好友对**：`friendships` 只存 `(low, high)`，杜绝 `(A,B)` 与 `(B,A)` 重复。
3. **位置幂等**：`(device_id, client_msg_id)` 唯一，离线补传不重复。
4. **最新位置独立**：`device_latest` 一设备一行，实时查询不扫历史表。
5. **软删除最小化**：MVP 主要用硬删除 + 级联；需要审计时再引入 `deleted_at`。
6. **不做跨库事务**：MVP 只有 PostgreSQL，避免 TDengine 双写一致性问题。

---

## 2. ER 关系

```text
users 1 ─── n devices
users n ─── n users        friendships (user_low_id, user_high_id)
users n ─── n users        friend_requests
users n ─── n users        blocks
devices 1 ─── n locations
devices 1 ─── 1 device_latest
devices 1 ─── n refresh_tokens
invite_codes ─── used_by_user_id
```

---

## 3. 枚举

使用 PostgreSQL `ENUM`，迁移时通过 `ALTER TYPE ... ADD VALUE` 扩展。

```sql
CREATE TYPE device_platform AS ENUM ('android', 'ios', 'unknown');
CREATE TYPE device_status AS ENUM ('active', 'revoked');
CREATE TYPE friend_request_status AS ENUM ('pending', 'accepted', 'rejected', 'cancelled');
CREATE TYPE location_provider AS ENUM ('gps', 'network', 'fused', 'passive', 'unknown');
CREATE TYPE coordinate_crs AS ENUM ('WGS84', 'GCJ02', 'BD09');
```

---

## 4. 完整 DDL

### 4.1 扩展

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

`gen_random_uuid()` 由 `pgcrypto` 提供（PostgreSQL 13+ 也可用内置版本，保留扩展更稳妥）。

### 4.2 用户

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name    TEXT NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 64),
    friend_code     TEXT NOT NULL UNIQUE CHECK (friend_code ~ '^[A-Z0-9]{8}$'),
    share_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

说明：

- `friend_code` 由服务端生成（8 位 Base32/Crockford），用于添加好友；不用于登录。
- `share_enabled = false` 时，任何好友都不能查询/订阅该用户设备的位置。
- `display_name` 允许重名；好友通过 `userId` 或 `friendCode` 识别。

### 4.3 设备

```sql
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    platform        device_platform NOT NULL DEFAULT 'android',
    app_version     TEXT,
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    status          device_status NOT NULL DEFAULT 'active',
    token_version   INTEGER NOT NULL DEFAULT 1 CHECK (token_version > 0),
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX devices_user_id_idx ON devices(user_id);
CREATE UNIQUE INDEX devices_one_primary_per_user_idx
    ON devices(user_id)
    WHERE is_primary;
```

说明：

- `token_version`：撤销设备时递增，使旧 Access Token 在服务端校验时失效。
- `is_primary`：每个用户最多一台主设备；好友默认查看主设备位置。
- `last_seen_at`：连接断开/心跳超时更新；在线状态以内存/Redis 为准。

### 4.4 Refresh Token

```sql
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id       UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash      BYTEA NOT NULL UNIQUE,
    family_id       UUID NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_device_id_idx ON refresh_tokens(device_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens(family_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens(expires_at);
```

说明：

- 只存 SHA-256 哈希，不存明文。
- 每次刷新：旧记录 `revoked_at = now()`，新记录 `family_id` 相同，`replaced_by` 指向新记录。
- 已撤销 Token 被再次使用 → 撤销整个 `family_id`。

### 4.5 邀请码

```sql
CREATE TABLE invite_codes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash           BYTEA NOT NULL UNIQUE,
    created_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    used_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX invite_codes_expires_at_idx ON invite_codes(expires_at);
```

说明：

- 邀请码只存哈希；运维 CLI 生成后一次性展示。
- `used_at IS NULL AND expires_at > now()` 才可用。
- 注册成功后设置 `used_at` 与 `used_by_user_id`。

### 4.6 好友申请

```sql
CREATE TABLE friend_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              friend_request_status NOT NULL DEFAULT 'pending',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at        TIMESTAMPTZ,
    CHECK (requester_user_id <> target_user_id)
);

CREATE UNIQUE INDEX friend_requests_pending_direction_idx
    ON friend_requests(requester_user_id, target_user_id)
    WHERE status = 'pending';

CREATE INDEX friend_requests_target_idx
    ON friend_requests(target_user_id, status, created_at DESC);
```

说明：

- 同方向重复 pending 由唯一索引阻止。
- 反向 pending 由应用层处理：如果 B 已向 A 发过 pending，A 再向 B 发时自动接受。
- 接受后写入 `friendships`，并把该对用户的其他 pending 请求置为 `cancelled`。

### 4.7 好友关系（规范化）

```sql
CREATE TABLE friendships (
    user_low_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
);

CREATE INDEX friendships_user_high_idx ON friendships(user_high_id);
```

说明：

- 写入前必须保证 `user_low_id < user_high_id`；应用层用 `least/greatest` 规范化。
- 查询某人的好友：

```sql
SELECT CASE
         WHEN user_low_id = $1 THEN user_high_id
         ELSE user_low_id
       END AS friend_user_id
FROM friendships
WHERE user_low_id = $1 OR user_high_id = $1;
```

### 4.8 拉黑

```sql
CREATE TABLE blocks (
    blocker_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CHECK (blocker_user_id <> blocked_user_id)
);

CREATE INDEX blocks_blocked_idx ON blocks(blocked_user_id);
```

说明：

- 拉黑时删除 `friendships` 与双方 pending 请求，并撤销订阅。
- 授权查询必须检查双向拉黑。

### 4.9 历史位置

```sql
CREATE TABLE locations (
    id              BIGSERIAL PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   UUID NOT NULL,
    device_ts       TIMESTAMPTZ NOT NULL,
    server_ts       TIMESTAMPTZ NOT NULL DEFAULT now(),
    latitude        DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        location_provider NOT NULL DEFAULT 'unknown',
    source_crs      coordinate_crs NOT NULL DEFAULT 'WGS84',
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (device_id, client_msg_id)
);

CREATE INDEX locations_device_server_ts_idx
    ON locations(device_id, server_ts DESC);

CREATE INDEX locations_device_device_ts_idx
    ON locations(device_id, device_ts DESC);

CREATE INDEX locations_server_ts_brin_idx
    ON locations USING BRIN (server_ts);
```

说明：

- `latitude/longitude` 是 WGS-84；`source_crs` 记录原始来源，便于审计。
- `server_ts` 是服务端接收时间，用于排序与保留策略；`device_ts` 是设备时间。
- `UNIQUE (device_id, client_msg_id)` 保证离线补传幂等。
- BRIN 索引适合按时间范围清理；B-tree 适合单设备时间段查询。
- 1 台设备 5 秒一个点 ≈ 17,280 行/天。

### 4.10 最新位置

```sql
CREATE TABLE device_latest (
    device_id       UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   UUID NOT NULL,
    latitude        DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        location_provider NOT NULL DEFAULT 'unknown',
    source_crs      coordinate_crs NOT NULL DEFAULT 'WGS84',
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         BOOLEAN NOT NULL DEFAULT FALSE,
    device_ts       TIMESTAMPTZ NOT NULL,
    server_ts       TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

UPSERT 示例（避免乱序旧数据覆盖新数据）：

```sql
INSERT INTO device_latest (
    device_id, client_msg_id, latitude, longitude, accuracy,
    provider, source_crs, speed, bearing, battery, is_mock,
    device_ts, server_ts
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13
)
ON CONFLICT (device_id) DO UPDATE SET
    client_msg_id = EXCLUDED.client_msg_id,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    accuracy = EXCLUDED.accuracy,
    provider = EXCLUDED.provider,
    source_crs = EXCLUDED.source_crs,
    speed = EXCLUDED.speed,
    bearing = EXCLUDED.bearing,
    battery = EXCLUDED.battery,
    is_mock = EXCLUDED.is_mock,
    device_ts = EXCLUDED.device_ts,
    server_ts = EXCLUDED.server_ts,
    updated_at = now()
WHERE device_latest.server_ts < EXCLUDED.server_ts;
```

---

## 5. 关键查询

### 5.1 是否为好友

```sql
SELECT 1
FROM friendships
WHERE user_low_id = LEAST($1::uuid, $2::uuid)
  AND user_high_id = GREATEST($1::uuid, $2::uuid);
```

### 5.2 是否有权查看设备位置

```sql
SELECT 1
FROM devices d
JOIN users target_user ON target_user.id = d.user_id
JOIN friendships f
  ON f.user_low_id = LEAST($viewer_user_id::uuid, d.user_id)
 AND f.user_high_id = GREATEST($viewer_user_id::uuid, d.user_id)
WHERE d.id = $target_device_id
  AND d.status = 'active'
  AND target_user.share_enabled = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM blocks b
      WHERE (b.blocker_user_id = $viewer_user_id AND b.blocked_user_id = d.user_id)
         OR (b.blocker_user_id = d.user_id AND b.blocked_user_id = $viewer_user_id)
  );
```

### 5.3 幂等插入位置

```sql
INSERT INTO locations (
    device_id, client_msg_id, device_ts, server_ts,
    latitude, longitude, accuracy, provider, source_crs,
    speed, bearing, battery, is_mock
) VALUES (
    $1, $2, $3, now(), $4, $5, $6, $7, $8, $9, $10, $11, $12
)
ON CONFLICT (device_id, client_msg_id) DO NOTHING
RETURNING id;
```

- 返回行：新插入；
- 无返回行：重复消息，`ack.duplicate = true`。

### 5.4 历史分页

Cursor 编码 `serverTs:id`（Base64url）。查询：

```sql
SELECT
    device_ts, server_ts, latitude, longitude,
    accuracy, provider, source_crs
FROM locations
WHERE device_id = $1
  AND server_ts >= $2
  AND server_ts <  $3
  AND ($4::timestamptz IS NULL OR (server_ts, id) > ($4, $5))
ORDER BY server_ts ASC, id ASC
LIMIT $6;
```

说明：

- `from/to` 最大跨度 7 天，由应用层校验；
- `limit` 最大 5,000；
- 返回 `nextCursor` 与 `truncated`；
- 如果数据量很大，Phase 3 再加时间桶抽稀。

### 5.5 保留期清理

分批删除，避免长事务：

```sql
WITH doomed AS (
    SELECT id
    FROM locations
    WHERE server_ts < $1
    ORDER BY server_ts
    LIMIT 10000
)
DELETE FROM locations
USING doomed
WHERE locations.id = doomed.id;
```

建议：

- 每 10 分钟跑一批，直到删除行数为 0；
- 默认保留 90 天，通过配置项 `LOCATION_RETENTION_DAYS` 控制；
- 清理任务记录指标（删除行数、耗时）。

---

## 6. 好友申请状态机

```text
            ┌──────────┐
            │ pending  │
            └────┬─────┘
      accept │   │ reject/cancel
             ▼   ▼
       accepted  rejected/cancelled
```

规则：

1. 不能向自己发申请。
2. 已是好友 → `409 ALREADY_FRIENDS`。
3. 存在反向 pending → 直接接受，创建 `friendships`，两个请求都置为 `accepted`。
4. 接受时：
   - 锁住请求行 `FOR UPDATE`；
   - 校验仍是 `pending`；
   - 插入 `friendships`（规范化 low/high，`ON CONFLICT DO NOTHING`）；
   - 更新请求为 `accepted`；
   - 取消同方向其他 pending；
   - 触发订阅关系刷新（如目标在线）。
5. 拉黑时：
   - 删除 `friendships`；
   - 删除双方 pending 请求；
   - 插入 `blocks`；
   - 撤销相关订阅。

---

## 7. 容量与分区

### 7.1 容量估算

| 设备数 | 点/天 | 90 天行数 | 说明 |
|---:|---:|---:|---|
| 10 | 172,800 | 1,555 万 | 单表足够 |
| 100 | 1,728,000 | 1.56 亿 | 建议月分区 |
| 1,000 | 17,280,000 | 15.6 亿 | 考虑 TDengine |

单行约 80–120 B；索引另计。按 100 设备/90 天估算，数据约 12–19 GB，索引约 5–10 GB。

### 7.2 分区策略（未来）

当 `locations` 超过约 5,000 万行或单表查询明显变慢时：

```sql
-- 仅示意，不在 Phase 0 执行
CREATE TABLE locations (
    ...
    PRIMARY KEY (id, server_ts)
) PARTITION BY RANGE (server_ts);

CREATE TABLE locations_2026_10 PARTITION OF locations
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
```

注意：

- 分区表的唯一约束必须包含分区键；`(device_id, client_msg_id)` 无法直接做全局唯一。
- 可选方案：按 `device_id` HASH 分区（幂等唯一仍成立），或增加 `processed_messages(device_id, client_msg_id)` 去重表。
- 分区方案需要在 Phase 3 用真实数据压测后决定，不在 MVP 提前优化。

---

## 8. 迁移策略

目录：

```text
server/migrations/
├── 001_extensions.sql
├── 002_identity.sql
├── 003_social.sql
├── 004_locations.sql
└── 005_indexes.sql
```

规则：

1. 每个迁移文件必须可重复执行路径清晰：`up` 成功、`down` 可回滚（或明确不可回滚并说明）。
2. 生产迁移前自动 `pg_dump`。
3. 大表加索引用 `CREATE INDEX CONCURRENTLY`，且不能放在事务块内；迁移工具需支持。
4. 枚举扩展用 `ALTER TYPE ... ADD VALUE`；删除枚举值需新建类型并迁移。
5. 禁止在迁移中执行数据回填长事务；回填单独脚本、分批执行。
6. CI 中跑：空库 `up → down → up`，并校验 schema。

---

## 9. 隐私与数据生命周期

| 数据 | 保留 | 删除方式 |
|---|---|---|
| `locations` | 默认 90 天 | 定时批量删除；用户可主动删除 |
| `device_latest` | 随设备 | 设备删除级联 |
| `refresh_tokens` | 过期/撤销后 30 天 | 定时清理 |
| `invite_codes` | 过期后 30 天 | 定时清理 |
| `friend_requests` | 1 年 | 定时清理 |
| `users/devices` | 账号存续期 | 注销级联删除 |

要求：

- 注销用户 → `ON DELETE CASCADE` 删除设备、位置、Refresh、好友关系、申请、拉黑。
- 日志不记录完整坐标；调试日志需脱敏。
- 对外导出/删除接口在 Phase 3 后评估。
- 国内部署需评估《个人信息保护法》的告知同意、最小必要、第三方 SDK（百度）披露。

---

## 10. 种子与运维脚本

Phase 0 需要提供：

```bash
# 创建邀请码（只输出一次明文）
npm run admin -- invite create --expires-in 7d

# 列出未使用邀请码（只显示哈希前缀）
npm run admin -- invite list

# 清理过期邀请码/Refresh
npm run admin -- cleanup
```

不要在迁移文件中写死邀请码或管理员密码。

---

## 11. 验收清单

- [ ] 空库执行全部迁移成功，`down` 可回滚。
- [ ] `users.friend_code` 唯一；`devices.is_primary` 每用户唯一。
- [ ] `friendships` 不能插入 `(A,A)` 或反向重复。
- [ ] 重复 `(device_id, client_msg_id)` 不产生第二行。
- [ ] `device_latest` 不会被更旧的 `server_ts` 覆盖。
- [ ] 授权查询覆盖：非好友、被拉黑、目标关闭分享、设备撤销。
- [ ] 保留清理分批执行，不产生长事务。
- [ ] 删除用户后，其设备、位置、Token、好友关系全部级联删除。
