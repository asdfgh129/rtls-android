# RTLS 数据模型（SQLite 3.45+）

- 状态：Accepted（ADR-009 / ADR-005）
- 更新日期：2026-10-20
- 对应迁移：`server/migrations/`
- 相关协议：[`protocol.md`](./protocol.md)

本文是 MVP 的权威数据模型。所有 DDL 以 SQLite 3.45+ 为目标；时间均为 `INTEGER`（Unix epoch 毫秒，UTC），坐标为 WGS-84。

> SQLite 没有 `UUID`、`TIMESTAMPTZ`、`ENUM` 类型：UUID 用 `TEXT`（应用生成 UUIDv7），时间用 `INTEGER` 毫秒，枚举用 `TEXT` + `CHECK`。所有表使用 `STRICT`，由数据库拒绝类型不匹配的写入。

---

## 1. 设计原则

1. **用户与设备分离**：好友关系在 user 级，位置在 device 级。
2. **规范化好友对**：`friendships` 只存 `(low, high)`，杜绝 `(A,B)` 与 `(B,A)` 重复。
3. **位置幂等**：`(device_id, client_msg_id)` 唯一，离线补传不重复。
4. **最新位置独立**：`device_latest` 一设备一行，实时查询不扫历史表。
5. **软删除最小化**：MVP 主要用硬删除 + 级联；需要审计时再引入 `deleted_at`。
6. **单库事务**：用户、关系、Token、位置都在同一个 SQLite 文件内，写入共享一个事务边界（ADR-009）。
7. **类型显式化**：所有表 `STRICT`；布尔、枚举、范围都用 `CHECK` 约束表达，不依赖应用层自觉。

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

SQLite 没有 `ENUM` 类型，枚举用 `TEXT` + `CHECK (col IN (...))` 实现：

| 枚举 | 取值 |
|---|---|
| `device_platform` | `android` / `ios` / `unknown` |
| `device_status` | `active` / `revoked` |
| `friend_request_status` | `pending` / `accepted` / `rejected` / `cancelled` |
| `location_provider` | `gps` / `network` / `fused` / `passive` / `unknown` |
| `coordinate_crs` | `WGS84` / `GCJ02` / `BD09` |

示例：

```sql
status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'revoked'))
```

扩展枚举值必须新增迁移并重建 CHECK 约束（SQLite 不支持 `ALTER TYPE ... ADD VALUE`）。重建表时不要在事务内切换 `PRAGMA foreign_keys`（事务内切换无效）；优先使用可逆的多步迁移。

---

## 4. 完整 DDL

### 4.1 连接与 PRAGMA

数据库按 ADR-009 使用 `better-sqlite3`。每条连接打开后立即执行：

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA temp_store = MEMORY;
PRAGMA auto_vacuum = INCREMENTAL;
```

说明：

- `auto_vacuum = INCREMENTAL` 必须在建表前设置；已有库需先 `VACUUM` 才能切换。
- `foreign_keys = ON` 是**连接级**开关，每个连接、包括迁移连接都必须设置，否则 `ON DELETE CASCADE` 不生效。
- `journal_mode = WAL` 是持久设置；会在数据库旁生成 `-wal` / `-shm` 文件。
- `synchronous = NORMAL` 在 WAL 下不会因崩溃损坏数据库，但断电可能丢失最近已提交事务；需要断电级持久性时改为 `FULL`。
- 数据库文件权限 `0600`、目录 `0700`；禁止放在 NFS / SMB / CIFS 上（文件锁不可靠）。
- `STRICT` 要求 SQLite ≥ 3.37；本项目目标 ≥ 3.45（`better-sqlite3` 13.x 内置版本满足）。

### 4.2 用户

```sql
CREATE TABLE users (
    id              TEXT PRIMARY KEY,
    display_name    TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 64),
    friend_code     TEXT NOT NULL UNIQUE
                    CHECK (friend_code GLOB '[A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9]'),
    share_enabled   INTEGER NOT NULL DEFAULT 1 CHECK (share_enabled IN (0, 1)),
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;
```

说明：

- `id` 由应用生成 UUIDv7（小写 36 字符），不使用数据库默认值。
- `friend_code` 由服务端生成（8 位 Base32/Crockford），用于添加好友；不用于登录。`GLOB` 是大小写敏感匹配，等价于 `^[A-Z0-9]{8}$`。
- `share_enabled = 0` 时，任何好友都不能查询/订阅该用户设备的位置。
- `display_name` 允许重名；好友通过 `userId` 或 `friendCode` 识别。

### 4.3 设备

```sql
CREATE TABLE devices (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),
    platform        TEXT NOT NULL DEFAULT 'android'
                    CHECK (platform IN ('android', 'ios', 'unknown')),
    app_version     TEXT,
    is_primary      INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'revoked')),
    token_version   INTEGER NOT NULL DEFAULT 1 CHECK (token_version > 0),
    last_seen_at    INTEGER,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE INDEX devices_user_id_idx ON devices(user_id);
CREATE UNIQUE INDEX devices_one_primary_per_user_idx
    ON devices(user_id)
    WHERE is_primary = 1;
```

说明：

- `token_version`：撤销设备时递增，使旧 Access Token 在服务端校验时失效。
- `is_primary`：每个用户最多一台主设备；好友默认查看主设备位置。
- `last_seen_at`：连接断开/心跳超时更新；在线状态以内存为准。

### 4.4 Refresh Token

```sql
CREATE TABLE refresh_tokens (
    id              TEXT PRIMARY KEY,
    device_id       TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash      BLOB NOT NULL UNIQUE,
    family_id       TEXT NOT NULL,
    expires_at      INTEGER NOT NULL,
    revoked_at      INTEGER,
    replaced_by     TEXT REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE INDEX refresh_tokens_device_id_idx ON refresh_tokens(device_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens(family_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens(expires_at);
```

说明：

- 只存 SHA-256 哈希（`BLOB`），不存明文。
- 每次刷新：旧记录 `revoked_at = now`，新记录 `family_id` 相同，`replaced_by` 指向新记录。
- 已撤销 Token 被再次使用 → 撤销整个 `family_id`。

### 4.5 邀请码

```sql
CREATE TABLE invite_codes (
    id                  TEXT PRIMARY KEY,
    code_hash           BLOB NOT NULL UNIQUE,
    created_by_user_id  TEXT REFERENCES users(id) ON DELETE SET NULL,
    used_by_user_id     TEXT REFERENCES users(id) ON DELETE SET NULL,
    expires_at          INTEGER NOT NULL,
    used_at             INTEGER,
    created_at          INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE INDEX invite_codes_expires_at_idx ON invite_codes(expires_at);
```

说明：

- 邀请码只存哈希；运维 CLI 生成后一次性展示。
- `used_at IS NULL AND expires_at > now` 才可用。
- 注册成功后设置 `used_at` 与 `used_by_user_id`。

### 4.6 好友申请

```sql
CREATE TABLE friend_requests (
    id                  TEXT PRIMARY KEY,
    requester_user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled')),
    created_at          INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    responded_at        INTEGER,
    CHECK (requester_user_id <> target_user_id)
) STRICT;

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
    user_low_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
) STRICT;

CREATE INDEX friendships_user_high_idx ON friendships(user_high_id);
```

说明：

- 写入前必须保证 `user_low_id < user_high_id`；应用层比较两个 UUID 字符串规范化（不依赖 `LEAST/GREATEST`，SQLite 没有这两个函数）。
- 查询某人的好友：

```sql
SELECT CASE
         WHEN user_low_id = :user_id THEN user_high_id
         ELSE user_low_id
       END AS friend_user_id
FROM friendships
WHERE user_low_id = :user_id OR user_high_id = :user_id;
```

### 4.8 拉黑

```sql
CREATE TABLE blocks (
    blocker_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CHECK (blocker_user_id <> blocked_user_id)
) STRICT;

CREATE INDEX blocks_blocked_idx ON blocks(blocked_user_id);
```

说明：

- 拉黑时删除 `friendships` 与双方 pending 请求，并撤销订阅。
- 授权查询必须检查双向拉黑。

### 4.9 历史位置

```sql
CREATE TABLE locations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   TEXT NOT NULL,
    device_ts       INTEGER NOT NULL,
    server_ts       INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    latitude        REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        TEXT NOT NULL DEFAULT 'unknown'
                    CHECK (provider IN ('gps', 'network', 'fused', 'passive', 'unknown')),
    source_crs      TEXT NOT NULL DEFAULT 'WGS84'
                    CHECK (source_crs IN ('WGS84', 'GCJ02', 'BD09')),
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         INTEGER NOT NULL DEFAULT 0 CHECK (is_mock IN (0, 1)),
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    UNIQUE (device_id, client_msg_id)
) STRICT;

CREATE INDEX locations_device_server_ts_idx
    ON locations(device_id, server_ts DESC, id DESC);

CREATE INDEX locations_device_device_ts_idx
    ON locations(device_id, device_ts DESC);

CREATE INDEX locations_server_ts_idx
    ON locations(server_ts);
```

说明：

- `latitude/longitude` 是 WGS-84；`source_crs` 记录原始来源，便于审计。
- `server_ts` 是服务端接收时间，用于排序与保留策略；`device_ts` 是设备时间。
- `UNIQUE (device_id, client_msg_id)` 保证离线补传幂等。
- `id` 使用 `INTEGER PRIMARY KEY AUTOINCREMENT`：保留期会持续删除旧行，普通 rowid 可能被复用，导致 `(server_ts, id)` 游标分页错乱；`AUTOINCREMENT` 保证 id 单调不复用。
- 排序索引包含 `id`，与历史分页的 `ORDER BY server_ts, id` 完全匹配，避免额外排序。
- `locations_server_ts_idx` 替代 PostgreSQL 的 BRIN 索引，用于保留期按时间批量删除。
- 1 台设备 5 秒一个点 ≈ 17,280 行/天。

### 4.10 最新位置

```sql
CREATE TABLE device_latest (
    device_id       TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   TEXT NOT NULL,
    latitude        REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        TEXT NOT NULL DEFAULT 'unknown'
                    CHECK (provider IN ('gps', 'network', 'fused', 'passive', 'unknown')),
    source_crs      TEXT NOT NULL DEFAULT 'WGS84'
                    CHECK (source_crs IN ('WGS84', 'GCJ02', 'BD09')),
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         INTEGER NOT NULL DEFAULT 0 CHECK (is_mock IN (0, 1)),
    device_ts       INTEGER NOT NULL,
    server_ts       INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;
```

UPSERT 示例（避免乱序旧数据覆盖新数据）：

```sql
INSERT INTO device_latest (
    device_id, client_msg_id, latitude, longitude, accuracy,
    provider, source_crs, speed, bearing, battery, is_mock,
    device_ts, server_ts
) VALUES (
    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
)
ON CONFLICT (device_id) DO UPDATE SET
    client_msg_id = excluded.client_msg_id,
    latitude = excluded.latitude,
    longitude = excluded.longitude,
    accuracy = excluded.accuracy,
    provider = excluded.provider,
    source_crs = excluded.source_crs,
    speed = excluded.speed,
    bearing = excluded.bearing,
    battery = excluded.battery,
    is_mock = excluded.is_mock,
    device_ts = excluded.device_ts,
    server_ts = excluded.server_ts,
    updated_at = CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
WHERE excluded.server_ts > device_latest.server_ts
   OR (excluded.server_ts = device_latest.server_ts
       AND excluded.device_ts >= device_latest.device_ts);
```

同一批上传的点通常共享同一个 `server_ts`（服务端按批取时），此时必须用 `device_ts` 兜底，否则 `device_latest` 会停在批次里的第一个点。相等时用 `>=` 让后写入的点胜出。

---

## 5. 关键查询

### 5.1 是否为好友

推荐由应用层先规范化为 `(low, high)`，再执行：

```sql
SELECT 1
FROM friendships
WHERE user_low_id = ?
  AND user_high_id = ?;
```

参数：第一个 `? = min(userA, userB)`，第二个 `? = max(userA, userB)`（按 UTF-8 字符串比较，与写入时的规范化规则一致）。

如果必须在 SQL 内规范化，可用：

```sql
SELECT 1
FROM friendships
WHERE user_low_id = CASE WHEN :a < :b THEN :a ELSE :b END
  AND user_high_id = CASE WHEN :a < :b THEN :b ELSE :a END;
```

SQLite 没有 `LEAST()` / `GREATEST()`；不要照搬 PostgreSQL 写法。

### 5.2 是否有权查看设备位置

```sql
SELECT 1
FROM devices d
JOIN users target_user ON target_user.id = d.user_id
JOIN friendships f
  ON f.user_low_id  = CASE WHEN :viewer_user_id < d.user_id THEN :viewer_user_id ELSE d.user_id END
 AND f.user_high_id = CASE WHEN :viewer_user_id < d.user_id THEN d.user_id ELSE :viewer_user_id END
WHERE d.id = :target_device_id
  AND d.status = 'active'
  AND target_user.share_enabled = 1
  AND NOT EXISTS (
      SELECT 1 FROM blocks b
      WHERE (b.blocker_user_id = :viewer_user_id AND b.blocked_user_id = d.user_id)
         OR (b.blocker_user_id = d.user_id AND b.blocked_user_id = :viewer_user_id)
  );
```

参数：`:viewer_user_id`、`:target_device_id`（命名参数，避免同一个值重复绑定）。

### 5.3 幂等插入位置

```sql
INSERT INTO locations (
    device_id, client_msg_id, device_ts, server_ts,
    latitude, longitude, accuracy, provider, source_crs,
    speed, bearing, battery, is_mock
) VALUES (
    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
)
ON CONFLICT (device_id, client_msg_id) DO NOTHING
RETURNING id;
```

- `RETURNING` 需要 SQLite ≥ 3.35。
- 返回行：新插入；
- 无返回行：重复消息，`ack.duplicate = true`。

### 5.4 历史分页

Cursor 编码 `serverTs:id`（Base64url）。

**无 cursor（第一页）：**

```sql
SELECT
    device_ts, server_ts, latitude, longitude,
    accuracy, provider, source_crs
FROM locations
WHERE device_id = ?
  AND server_ts >= ?
  AND server_ts <  ?
ORDER BY server_ts ASC, id ASC
LIMIT ?;
```

**有 cursor：**

```sql
SELECT
    device_ts, server_ts, latitude, longitude,
    accuracy, provider, source_crs
FROM locations
WHERE device_id = ?
  AND server_ts >= ?
  AND server_ts <  ?
  AND (server_ts, id) > (?, ?)
ORDER BY server_ts ASC, id ASC
LIMIT ?;
```

说明：

- 行值比较 `(server_ts, id) > (?, ?)` 需要 SQLite ≥ 3.15；当前目标版本满足。
- 拆成两条语句而不是 `(? IS NULL OR ...)`，避免 OR 破坏索引选择。
- `from/to` 最大跨度 7 天，由应用层校验；
- `limit` 最大 5,000；
- 返回 `nextCursor` 与 `truncated`；
- 如果数据量很大，Phase 3 再加时间桶抽稀。

### 5.5 保留期清理

SQLite 不支持 `DELETE ... USING`。分批删除，避免长事务：

```sql
DELETE FROM locations
WHERE id IN (
    SELECT id
    FROM locations
    WHERE server_ts < ?
    ORDER BY server_ts
    LIMIT 10000
);
```

建议：

- 在 `BEGIN IMMEDIATE` 事务中执行，每 10 分钟跑一批，直到删除行数为 0；
- 删除后执行 `PRAGMA incremental_vacuum(1000);` 回收文件空间。该 PRAGMA 只有在 `auto_vacuum = INCREMENTAL` 时有效，由 `20261020000000_pragma.sql` 在建表前用 `PRAGMA auto_vacuum = INCREMENTAL; VACUUM;` 固化；否则 `incremental_vacuum` 是空操作。
- 默认保留 90 天，通过配置项 `LOCATION_RETENTION_DAYS` 控制；
- 清理任务记录指标（删除行数、耗时、文件大小）。

### 5.6 最新位置读取

```sql
SELECT
    device_id, client_msg_id, latitude, longitude, accuracy,
    provider, source_crs, speed, bearing, battery, is_mock,
    device_ts, server_ts, updated_at
FROM device_latest
WHERE device_id = ?;
```

`stale` 判定由应用层完成：`now - server_ts > STALE_THRESHOLD_MS`。

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
   - 使用 `BEGIN IMMEDIATE` 开启写事务；
   - 校验请求仍是 `pending`（SQLite 不支持 `SELECT ... FOR UPDATE`，由单写者 + 条件更新保证）；
   - 插入 `friendships`（规范化 low/high，`ON CONFLICT DO NOTHING`）；
   - 更新请求为 `accepted`；
   - 取消同方向其他 pending；
   - 触发订阅关系刷新（如目标在线）。
5. 拉黑时：
   - 删除 `friendships`；
   - 删除双方 pending 请求；
   - 插入 `blocks`；
   - 撤销相关订阅。

示例：条件更新 + 检查影响行数，避免并发重复接受：

```sql
UPDATE friend_requests
SET status = 'accepted',
    responded_at = ?
WHERE id = ?
  AND status = 'pending';
```

`changes() = 1` 才继续执行后续写入；否则说明已被其他事务处理。

---

## 7. 容量与扩容

### 7.1 容量估算

| 设备数 | 点/天 | 90 天行数 | 说明 |
|---:|---:|---:|---|
| 10 | 172,800 | 1,555 万 | 单文件轻松 |
| 100 | 1,728,000 | 1.56 亿 | 单文件 + 保留期清理 |
| 1,000 | 17,280,000 | 15.6 亿 | 超出 SQLite 舒适区，迁移 PostgreSQL / TDengine |

按 100 设备 / 90 天估算，数据文件加索引约 2~4 GB；单行约 80~120 B，索引另计。SQLite 单文件上限远大于此（理论 281 TB），真正的瓶颈是单写者串行与 fsync，而不是文件大小。

写入速率：100 设备 × 每 5 秒一个点 ≈ 20 条/秒，远低于 WAL 在普通 SSD 上的写入能力。必须批量写入并复用事务，禁止每条位置单独提交。

### 7.2 扩容与归档

SQLite 没有表分区，MVP 不做提前优化。达到以下任一条件时启动迁移评估：

- `locations` 超过 5,000 万行，或单文件超过 10 GB；
- 写入 p95 持续超过 50 ms，或 `SQLITE_BUSY` 持续出现；
- 需要多 API 实例或跨机器部署；
- 历史查询 p95 超过 1 秒且索引/抽稀无法解决。

扩容路径（保持 `LocationRepository` 接口不变）：

1. 新增 PostgreSQL 或 TDengine 实现，双读校验；
2. 维护窗口内迁移历史数据；
3. 切换写入实现，保留 SQLite 只读副本一段时间用于回滚。

归档：保留期删除前可把冷数据导出为 Parquet/压缩文件存对象存储，但导出任务必须独立于在线库，避免长事务。

---

## 8. 迁移策略

目录：

```text
server/migrations/
├── 20261020000000_pragma.sql         # auto_vacuum 引导（transaction:false + VACUUM）
├── 20261020000100_identity.sql
├── 20261020000200_social.sql
├── 20261020000300_locations.sql
├── 20261020000400_indexes.sql
└── 20261020000500_seed_helpers.sql   # 仅函数/视图等，不写死数据
```

使用 `dbmate`（ADR-009）。每个文件必须包含 `up` 与 `down`：

```sql
-- migrate:up
CREATE TABLE example (
    id TEXT PRIMARY KEY
) STRICT;

-- migrate:down
DROP TABLE example;
```

规则：

1. 迁移按文件名排序执行，文件名使用 UTC 时间戳前缀；`schema_migrations` 由 dbmate 维护。
2. 每个迁移必须可回滚；不可回滚的迁移要在文件头注释说明原因。
3. SQLite 的 DDL 在事务中执行，dbmate 默认按事务运行单个迁移。唯一例外是 `20261020000000_pragma.sql`：dbmate 先建 `schema_migrations`，`auto_vacuum` 只能通过 `VACUUM` 固化，因此该文件标记 `transaction:false`。
4. 不支持 `CREATE INDEX CONCURRENTLY`：大表加索引会短暂持有写锁，必须放在维护窗口，并先备份。
5. 重建表（修改 CHECK/枚举/列类型）时，按“建新表 → 复制数据 → 删旧表 → 重命名”的顺序拆成可逆步骤；不要在事务内依赖 `PRAGMA foreign_keys` 切换。
6. 禁止在迁移中执行数据回填长事务；回填单独脚本、分批执行。
7. 生产迁移前用 `.backup` 自动备份。
8. CI 中跑：临时文件数据库 `up → down → up`，并执行 `PRAGMA integrity_check`。

---

## 9. 隐私与数据生命周期

| 数据 | 保留 | 删除方式 |
|---|---|---|
| `locations` | 默认 90 天 | 定时批量 `DELETE` + `PRAGMA incremental_vacuum`；用户可主动删除 |
| `device_latest` | 随设备 | 设备删除级联 |
| `refresh_tokens` | 过期/撤销后 30 天 | 定时清理 |
| `invite_codes` | 过期后 30 天 | 定时清理 |
| `friend_requests` | 1 年 | 定时清理 |
| `users/devices` | 账号存续期 | 注销级联删除 |

要求：

- 注销用户 → `ON DELETE CASCADE` 删除设备、位置、Refresh、好友关系、申请、拉黑；执行删除的连接必须开启 `PRAGMA foreign_keys = ON`。
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

`npm run migrate` 是对 dbmate 的薄封装：

```bash
npm run migrate up       # dbmate up
npm run migrate down     # dbmate rollback（回滚一个迁移）
npm run migrate status   # dbmate status
```

不要在迁移文件中写死邀请码或管理员密码。

---

## 11. 验收清单

- [ ] 空库执行全部迁移成功，`down` 可回滚，`up → down → up` 通过。
- [ ] `PRAGMA integrity_check` 返回 `ok`；`foreign_keys` 在迁移与运行时连接都为 `ON`。
- [ ] 所有表为 `STRICT`；写入错误类型会被拒绝。
- [ ] `users.friend_code` 唯一且满足 8 位 `[A-Z0-9]` GLOB 约束；`devices.is_primary` 每用户唯一。
- [ ] `friendships` 不能插入 `(A,A)` 或反向重复。
- [ ] 重复 `(device_id, client_msg_id)` 不产生第二行，`RETURNING` 无行时判定为重复。
- [ ] `device_latest` 不会被更旧的 `server_ts` 覆盖。
- [ ] 授权查询覆盖：非好友、被拉黑、目标关闭分享、设备撤销。
- [ ] 保留清理分批执行，不产生长事务；`incremental_vacuum` 能回收空间。
- [ ] 删除用户后，其设备、位置、Token、好友关系全部级联删除。
- [ ] `.backup` 备份可恢复，恢复后 `integrity_check = ok` 且行数一致。
