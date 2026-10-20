# 变更日志

本文件记录 RTLS 的所有重要变更。格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

## 记录规范

1. **按天记录**：所有变更写入 `## [Unreleased]` 下当天日期小节（`### YYYY-MM-DD`，Asia/Shanghai）。同一天多次提交合并进同一小节，不重复建小节。
2. **按类别归档**：日期小节内使用 `新增` / `变更` / `弃用` / `移除` / `修复` / `安全`；没有内容的类别不保留空标题。
3. **面向影响**：描述"改了什么、影响谁、如何迁移"，不写文件级流水账；涉及的 ADR 与文档用反引号标注。
4. **破坏性变更**：条目标注 **BREAKING**，并给出迁移步骤。
5. **发版**：发布时把 `[Unreleased]` 内容整体移到新版本号小节并标注发布日期；`0.y.z` 阶段允许不兼容变更。
6. **提交前检查**：当天有代码、文档或配置改动时，先在 `[Unreleased]` 补齐记录，再提交。

## [Unreleased]

### 2026-10-20

#### 新增

- `server/` 服务端骨架：Fastify 5 应用、Zod 配置校验、`GET /healthz` 健康检查、SIGINT/SIGTERM 优雅关闭。
- SQLite 访问层：全局单连接、ADR-009 全量 PRAGMA（WAL、`synchronous=NORMAL`、`foreign_keys=ON`、`busy_timeout`）、写事务统一 `BEGIN IMMEDIATE`、应用层 UUIDv7 主键。
- 6 个 dbmate 迁移（`auto_vacuum` 引导、身份、社交、位置、索引、只读视图），`up`/`down` 均可逆，`down` 按依赖逆序执行。
- Repository 层：用户、设备、刷新令牌、好友、位置。位置批量写入按 `(device_id, client_msg_id)` 幂等，`device_latest` 以 `server_ts` 单调守卫，历史查询使用 `(server_ts, id)` 行值游标分页，保留期清理分批删除后 `incremental_vacuum`。
- Vitest 测试 34 项：迁移 `up → down → up`、`integrity_check`、`auto_vacuum`、STRICT/CHECK 约束、重复写入幂等、同批 `server_ts` 取最新点、旧点不回退、游标分页无重漏、`limit` 收敛、配置路径解析、错误信封、级联删除、好友授权与拉黑。
- 根目录 `Makefile`（`install`/`migrate`/`migrate-down`/`migrate-status`/`db-reset`/`db-shell`/`test`/`build`/`typecheck`）与 `.env.example`。
- 本变更日志，建立每日记录约定。
- `ADR-009`《SQLite 单文件存储》，取代 `ADR-003`。
- GitHub Actions CI：`server (22)` / `server (24)` 跑 typecheck、34 项测试与 build，Node 24 额外执行迁移 `up → down → up` 并校验 `integrity_check`、`auto_vacuum`。
- 文档门禁：`scripts/check-links.py` 检查全库相对链接，`scripts/check-changelog.py` 在 PR 中要求非文档改动同步更新本文件。
- 安全流水线：生产依赖审计（high 及以上失败）、CodeQL（PR + `master` + 每周）、Dependabot（npm 与 Actions 每周分组升级）。
- `master` 分支 Ruleset：禁止删除/强推、线性历史、必需状态检查（`server (22)`、`server (24)`、`docs`、`security`）、分支必须最新；同时开启 Actions SHA 固定校验与 Dependabot 安全更新。
- Android 多模块 Gradle 工程（`:app`、`:core:model`、`:core:network`、`:core:location`、`:core:data`、`:core:map`）：Kotlin 2.0.21、AGP 8.7.3、Compose BOM 2024.12.01、Room 2.6.1/KSP、OkHttp 4.12；compileSdk/targetSdk 35、minSdk 26、JDK 17。
- `:core:model`：WGS-84/GCJ-02/BD-09 坐标转换、`GeoPoint`/`LocationSample`/`GeoMath`；WGS-84 为唯一传输坐标，样本保留 `sourceCrs` 供审计。
- `:core:network`：协议信封与消息、4 KB 帧限制、1s→60s ±20% 重连退避、Bearer WebSocket 客户端（30s ping、断线重订阅、4401 不重试）。
- `:core:location`：`LocationSource` 抽象、GMS FLP → `LocationManager` → Debug Fake 回退、前台定位服务与通知渠道。
- `:core:data`：Room 离线队列（1000 条 / 24 小时、去重）、加密 TokenStore、DataStore 偏好。
- `:core:map`：地图渲染契约与 WGS-84 → BD-09 渲染坐标转换。
- `:app`：Compose 单 Activity 空壳、权限引导（前台/通知/后台）、地图/好友/设置三页、debug/release WebSocket 地址。
- Android CI workflow：JDK 17 + SDK 35 + Gradle 缓存，执行 `assembleDebug` / `testDebugUnitTest` / `lintDebug`。

#### 变更

- **BREAKING** 存储方案由 PostgreSQL 改为 SQLite 单文件（`better-sqlite3` + 手写 SQL）。迁移方式：不再需要数据库服务与连接串，改为设置 `DATABASE_PATH` 并执行 `make migrate`；细节见 `Docs/architecture/decisions/ADR-009-sqlite-storage.md`。
- `Docs/specs/data-model.md` 全量改写为 SQLite 规范：`STRICT` 表、UUID 用 `TEXT`、时间用 `INTEGER`（Unix epoch 毫秒）、二进制用 `BLOB`、枚举用 `TEXT` + CHECK、布尔用 `INTEGER` 0/1 + CHECK、B-tree 索引替代 BRIN。
- 文档体系同步 SQLite：`ADR-001`、`ADR-008`、`overview.md`、`guides/development.md`、`guides/deployment.md`、`planning/roadmap.md`、`planning/phase-0-tasks.md`。
- `ADR-003` 标记为 Superseded，`decisions/README.md` 索引与文档地图同步。
- 依赖基线更新为 `better-sqlite3` 13.x、`dbmate` 2.x。
- 分支模型统一为 `master`（原文档写 `main`），`feat/*`、`fix/*`、`chore/*` 保持不变；`guides/development.md` 与 `phase-0-tasks.md` 同步。

#### 移除

- PostgreSQL 连接串 `DATABASE_URL`、`pg_dump`/`psql` 运维流程、`node-pg-migrate` 与 5432 端口相关配置。
- 不支持的能力说明：`SELECT ... FOR UPDATE`、`DELETE ... USING`、`CREATE INDEX CONCURRENTLY`、`ALTER TYPE`、表分区。

#### 修复

- 同批位置共享同一个 `server_ts` 时，`device_latest` 停留在批次首点；UPSERT 增加 `device_ts` 兜底，取批次内最新的点。
- `DATABASE_PATH` 相对路径按进程 CWD 解析，导致 `cd server && npm run dev` 指向 `server/server/data/` 并崩溃；改为按仓库根解析并自动创建父目录，`.env` 同时支持仓库根回退。
- `PRAGMA auto_vacuum` 在 dbmate 建表后设置无效，`incremental_vacuum` 成为空操作；新增 `transaction:false` 引导迁移用 `VACUUM` 固化。
- 历史查询未收敛 `limit`，`LIMIT -1` 会退化为全量返回；强制限制在 `[1, 5000]`。
- `npm run typecheck` 未覆盖 `test/`；新增 `tsconfig.test.json`，测试代码纳入类型检查。
- 错误响应为 Fastify 默认结构，不符合协议信封；统一为 `{error:{code,message,requestId}}`，并生成 `req_*` 请求 ID。
- 文档示例使用 `?1`/`?2` 编号参数且重复引用，better-sqlite3 无法这样绑定；改为 `?` 位置参数或命名参数。
- Dependabot PR 被 `CHANGELOG` 门禁误拦；门禁对 `dependabot[bot]` 豁免。Dependency Review 在该仓库不可用，移除该步骤，依赖风险由 `npm audit` 与 Dependabot 覆盖。
- `.gitignore` 的 `data/` 规则误忽略 `core/data` 模块；改为仅忽略根目录 `/data/`，确保 Room 队列源码与 schema 进入版本控制。
- 权限引导页“打开设置”同时直接调用系统设置和回调，导致重复启动设置页；改为只调用一次回调。
- 未获取定位时，地图预览仍显示固定的 WGS-84/BD-09 占位坐标；改为显示“等待定位…”，避免误认为已定位。
- 仅授予模糊定位时，`LocationManager` 回退仍可能请求 GPS provider 并失败；现在按 fine/coarse 权限选择 GPS 或网络 provider。
- 系统定位开关关闭但 Google Play Services 可用时仍会选择 FLP，表现为“共享中”但无位置；GMS 源现在检查系统定位开关，关闭时回退或明确报错。
- 共享运行中切换“模拟定位”只更新偏好、不重启定位源；现在切换后立即用新定位源重启共享。
