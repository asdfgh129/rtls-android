# ADR-003：MVP 存储方案

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 四、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段一；详细 DDL 见 [`../data-model.md`](../../specs/data-model.md)

## 背景

`Plan.md` 建议 TDengine（轨迹）+ PostgreSQL（用户关系）双库，`Step.md` 使用 `tdengine-client`。存在三个问题：

1. `tdengine-client` 不是 TDengine 官方 Node.js 连接器；官方现为 `@tdengine/websocket`，`@tdengine/client` 已停止维护。
2. MVP 的设备量很小，双库带来的部署、备份、迁移、联调成本远高于收益。
3. 双写没有事务/补偿方案，容易出现“实时位置有、历史没有”的不一致。

## 决策

**MVP 只使用 PostgreSQL 16 单库。**

- 用户、设备、好友、Token、最新位置、历史位置全部存 PostgreSQL。
- 通过 `LocationRepository` 接口隔离存储实现，为未来迁移 TDengine 留出边界。
- 使用 `node-pg-migrate` 管理 DDL。
- 位置表按月分区作为规模化选项，MVP 先使用单表 + B-tree/BRIN 索引。
- 默认保留 90 天；保留期可配置。

### 仓库接口

```ts
export interface LocationRepository {
  insertBatch(points: NewLocationPoint[]): Promise<InsertResult>;
  latest(deviceId: string): Promise<LatestLocation | null>;
  queryHistory(input: HistoryQuery): Promise<HistoryPage>;
  deleteOlderThan(cutoff: Date): Promise<number>;
}
```

未来若迁移 TDengine，只需新增 `TdengineLocationRepository`，HTTP/WS 层不感知。

### 规模判断

5 秒一个点：

| 设备数 | 点/天 | 90 天行数（约） | 建议 |
|---:|---:|---:|---|
| 10 | 17.3 万 | 1,556 万 | PostgreSQL 单表 |
| 100 | 173 万 | 1.56 亿 | PostgreSQL + 月分区 |
| 1,000 | 1,728 万 | 15.6 亿 | 考虑 TDengine / 专用时序方案 |

单行按 80–120 B 估算，1 亿行约 8–12 GB（不含索引）。PostgreSQL 在 MVP 规模完全够用。

## 理由

- 一个数据库 = 一套备份、迁移、监控、连接池。
- PostgreSQL 的 `(device_id, server_ts DESC)` 索引足以支撑单设备时间段查询。
- 好友/权限/历史可以在同一个事务边界内处理。
- 避免在 MVP 阶段引入 TDengine 的 `taosAdapter`、FQDN、容器网络等额外故障点。

## 后果

### 正面

- 部署简单：`docker compose up` 只有 API + PostgreSQL。
- 备份/恢复、迁移、测试工具成熟。
- 授权查询与历史查询可以用同一条 SQL 事务完成。

### 负面 / 需要注意

- 数据量到亿级后需要分区/归档；需要提前写容量监控。
- 高频写入的压缩率不如专用时序库。
- 未来迁移 TDengine 时需要数据回填与双读验证。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| TDengine + PostgreSQL | 暂不采用 | 运维复杂、Node 连接器不稳定、MVP 收益低 |
| PostgreSQL + TimescaleDB | 可选 | 若需要自动分区/压缩，可在 Phase 3 后评估 |
| SQLite | 不采用 | 多设备并发、远程部署、备份不合适 |
| 仅内存 + 定期落盘 | 不采用 | 历史轨迹与可靠性不满足 |

## 验证方式

- `npm run migrate up` 可从空库创建全部结构；`down` 可回滚。
- 集成测试覆盖：好友授权查询、历史分页、重复 `client_msg_id`、保留期删除。
- 压测：1,000 设备、5 秒间隔写入时，`locations` 插入 p95 与查询 p95 记录在案。
