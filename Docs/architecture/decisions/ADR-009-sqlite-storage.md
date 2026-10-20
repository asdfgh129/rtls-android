# ADR-009：MVP 存储改为 SQLite

- 状态：Accepted
- 日期：2026-10-20
- 取代：[ADR-003](./ADR-003-mvp-storage.md)
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 四；详细 DDL 见 [`../data-model.md`](../../specs/data-model.md)
- 操作手册：[`../../guides/development.md`](../../guides/development.md)、[`../../guides/deployment.md`](../../guides/deployment.md)

## 背景

ADR-003 为 MVP 选择了 PostgreSQL 16 单库，理由是关系查询、事务和迁移工具成熟。实际进入 Phase 0 后发现：

1. 仓库尚无 `server/` 代码，也没有任何生产数据，存储结论仍可无成本调整。
2. 当前规模是 10~100 台设备、5 秒一个点，写入约 0.2~20 条/秒；PostgreSQL 的能力远超需求。
3. 单独运行 PostgreSQL 容器带来了额外的镜像、端口、卷、备份、迁移和本地环境成本；MVP 只有一台 VPS，收益很低。
4. 位置与关系必须在同一事务边界内写入；SQLite 的单文件 + 本地事务正好满足这一点，且没有网络数据库暴露面。
5. 开发与集成测试可以直接使用临时文件数据库，不再依赖 Docker 或 Testcontainers。

因此本 ADR 取代 ADR-003 的存储结论。`LocationRepository` 抽象保留，仍作为未来迁移 PostgreSQL / TDengine 的边界。

## 决策

**MVP 只使用 SQLite 3.45+ 单文件数据库。**

- 用户、设备、好友、Token、最新位置、历史位置全部存入同一个 `.db` 文件。
- 通过 `LocationRepository` 接口隔离存储实现，HTTP / WS 层不感知数据库。
- 使用 `better-sqlite3`（13.x）访问数据库，手写 SQL + Repository，不引入 ORM。
- 使用 `dbmate` 管理 DDL，迁移文件为纯 SQL，放在 `server/migrations/`。
- 数据库路径由 `DATABASE_PATH` 指定：开发默认 `./server/data/rtls.db`（相对仓库根），生产 `/data/rtls.db`。

### 类型映射

| 领域类型 | SQLite 表示 | 约定 |
|---|---|---|
| UUID | `TEXT` | 应用层生成 UUIDv7（`uuid` 包 `v7()`），小写 36 字符 |
| 时间 | `INTEGER` | Unix epoch 毫秒（UTC）；Repository 负责 `Date` ↔ `number` |
| 布尔 | `INTEGER` | `0` / `1`，列上带 `CHECK (col IN (0, 1))` |
| 枚举 | `TEXT` | `CHECK (col IN (...))`；变更枚举需新增迁移重建 CHECK |
| 二进制 | `BLOB` | Token 哈希、邀请码哈希 |
| 自增主键 | `INTEGER PRIMARY KEY AUTOINCREMENT` | 仅 `locations.id`，保证保留期删除后 id 不复用 |

所有表使用 `STRICT`，禁止 SQLite 的动态类型把字符串写进数值列。

### 连接与 PRAGMA

每条连接打开后立即执行：

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
PRAGMA temp_store = MEMORY;
PRAGMA auto_vacuum = INCREMENTAL;
```

- `auto_vacuum = INCREMENTAL` 必须在建表前设置；对已存在的库需要先 `VACUUM` 才生效。`20261020000000_pragma.sql` 以 `transaction:false` 执行 `PRAGMA auto_vacuum = INCREMENTAL; VACUUM;`（dbmate 会先创建 `schema_migrations`，所以必须 VACUUM 固化）；应用连接上的同名 PRAGMA 仅对空库有效，保留它作为兜底。
- `foreign_keys` 是连接级开关，每个连接都要设置，迁移连接也不例外。
- `journal_mode = WAL` 是持久设置，写入后不需要每次重开。
- `synchronous = NORMAL` 在 WAL 下可避免崩溃损坏，但断电可能丢失最近若干已提交事务；若要求断电级持久性，改为 `FULL`。

### 并发模型

- SQLite 同一时刻只允许一个写事务；WAL 允许读与写并发。
- 所有写操作使用 `BEGIN IMMEDIATE` 开启事务，避免读事务升级写事务时出现 `SQLITE_BUSY`。
- 位置批量写入必须放在同一事务内，禁止逐条自动提交。
- 遇到 `SQLITE_BUSY` 由 `busy_timeout` 等待；超时后向上返回可重试错误，不做无限重试。
- **只允许一个 API 进程/实例访问数据库文件**。`better-sqlite3` 是进程内同步驱动，多进程共享同一文件不在支持范围内。

### 部署约束

- 数据库文件必须位于本机 SSD 的本地卷；禁止放在 NFS、SMB、CIFS 或容器跨主机共享卷上。
- 只运行一个 `api` 副本，不支持滚动多实例或横向扩容。
- 数据库文件权限 `0600`，所在目录 `0700`，属主为 API 运行用户。
- 磁盘是单点故障：需要监控剩余空间并保留可恢复备份。

### 备份

- 在线备份使用 SQLite 备份 API：

  ```bash
  sqlite3 /data/rtls.db ".backup '/backup/rtls-$(date +%F).db'"
  ```

- 或使用 `VACUUM INTO '/backup/rtls-$(date +%F).db'`（会顺带压缩文件）。
- 禁止在 WAL 模式下直接 `cp` 正在使用的 `.db` 文件。
- 恢复前必须停止 API，并删除目标位置的 `-wal` / `-shm` 文件。

### 仓库接口

`LocationRepository` 与 ADR-003 保持一致，未来迁移存储只需新增实现：

```ts
export interface LocationRepository {
  insertBatch(points: NewLocationPoint[]): Promise<InsertResult>;
  latest(deviceId: string): Promise<LatestLocation | null>;
  queryHistory(input: HistoryQuery): Promise<HistoryPage>;
  deleteOlderThan(cutoff: Date): Promise<number>;
}
```

### 规模判断

5 秒一个点：

| 设备数 | 点/天 | 90 天行数（约） | 建议 |
|---:|---:|---:|---|
| 10 | 17.3 万 | 1,556 万 | SQLite 单文件，轻松 |
| 100 | 173 万 | 1.56 亿 | SQLite 单文件 + 保留期清理 |
| 1,000 | 1,728 万 | 15.6 亿 | 超出 SQLite 舒适区，迁移 PostgreSQL / TDengine |

按 100 设备 / 90 天估算，数据文件（含索引）约 2~4 GB。SQLite 的写入瓶颈是单写者串行与 fsync，而不是数据量：20 条/秒远低于 WAL 在普通 SSD 上的写入能力。

## 理由

- 一个文件 = 一套备份、恢复、迁移；不需要数据库容器和数据库端口。
- 本地开发与 CI 不需要 Docker/Testcontainers，测试之间用临时文件隔离。
- 关系、Token、位置写入共享同一个 ACID 事务，避免跨库一致性问题。
- 数据库文件不监听网络端口，减少攻击面。
- 数据量在 1 亿行以内时，B-tree 索引足以支撑单设备时间段查询。

## 后果

### 正面

- 部署简单：`docker compose up` 只有 API + cloudflared。
- 备份是一个文件，恢复是替换文件；演练成本低。
- 本地开发零外部依赖，测试可并行使用临时数据库。
- 没有数据库网络暴露面，也没有连接池配置。
- 事务语义完整，位置与 `device_latest` 可原子更新。

### 负面 / 需要注意

- 单写者：写事务串行执行，长事务会阻塞所有写入。
- 只支持单进程/单实例；多实例部署必须迁移存储或引入外部写服务。
- 没有分区、BRIN、`CREATE INDEX CONCURRENTLY`；大表加索引会短暂阻塞写入。
- 不支持 `SELECT ... FOR UPDATE`；并发控制改用 `BEGIN IMMEDIATE` + 唯一约束 + 条件更新。
- 不支持 `DELETE ... USING`；保留期清理需改写为子查询删除。
- 类型系统弱于 PostgreSQL，必须靠 `STRICT` + `CHECK` + 应用层校验兜底。
- 断电时 `synchronous = NORMAL` 可能丢失最近事务；需要更强保证时改 `FULL`。
- 磁盘损坏即数据损坏，必须有异地备份与恢复演练。
- `better-sqlite3` 是同步调用，长查询会阻塞事件循环；必须限制历史查询范围并加索引。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| PostgreSQL 16 | 暂不采用 | MVP 运维成本高；保留为 1,000 设备以上的扩容路径 |
| PostgreSQL + Testcontainers | 不采用 | 本地开发与 CI 依赖 Docker，反馈慢 |
| libSQL / Turso | 暂不采用 | 重新引入网络与外部服务依赖；如未来需要托管副本再评估 |
| DuckDB / Parquet | 不采用 | 面向分析型查询，不适合高频点写入与事务 |
| 仅内存 + 定期落盘 | 不采用 | 崩溃会丢失位置历史与 Token 状态 |

## 迁移路径

- 当前没有生产数据，也没有服务端代码，本次是**直接切换**，不需要数据迁移脚本。
- 若未来已有 PostgreSQL 实例：在一个维护窗口内导出表数据、按同一数据模型导入 SQLite，并校验行数与 `PRAGMA integrity_check`。
- 若未来规模超过 SQLite 舒适区：保留 `LocationRepository` 接口，新增 PostgreSQL / TDengine 实现，HTTP/WS 层不修改。

## 安全

- 数据库文件与目录权限分别为 `0600` / `0700`，不得放进仓库或镜像层。
- 备份文件同样按 `0600` 保存，并加密后异地存储。
- 需要静态加密时使用全盘加密，或改用 SQLCipher；SQLCipher 需要自定义编译 `better-sqlite3`，不作为 MVP 默认方案。
- 日志不记录完整坐标、Token、Refresh、邀请码。

## 验证方式

- 空库执行 `npm run migrate up → down → up` 成功。
- `sqlite3 <db> 'PRAGMA integrity_check;'` 返回 `ok`。
- 并发写入测试：100 设备、5 秒间隔下不出现未处理的 `SQLITE_BUSY`。
- 保留期清理：分批删除后 `PRAGMA incremental_vacuum` 能回收文件空间。
- 备份恢复演练：从 `.backup` 恢复后行数、`device_latest` 与生产一致，`integrity_check` 为 `ok`。
- 进程重启后 WAL 正常回放，无 `database disk image is malformed`。
