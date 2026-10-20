# RTLS 开发指南

- 状态：现行（Current）
- 日期：2026-10-19
- 取代：[初始实施步骤（归档）](../archive/step-2026-10-17.md)
- 相关：[`architecture/overview.md`](../architecture/overview.md)、[`planning/phase-0-tasks.md`](../planning/phase-0-tasks.md)、[`planning/roadmap.md`](../planning/roadmap.md)

本文覆盖：**环境搭建 → 工程结构 → 日常开发命令 → 分支与评审 → 测试 → AI Agent 使用边界**。
部署与运维见 [`deployment.md`](./deployment.md)。

> 文中的命令要求可复制执行；示例值（域名、AK、Token）一律用占位符。

---

## 1. 工具链

| 工具 | 版本 | 用途 | 检查命令 |
|---|---|---|---|
| JDK | 17（完整 JDK，非 JRE） | Kotlin/Gradle | `java -version` |
| Android Studio | 最新稳定版 | Android IDE / SDK | — |
| Node.js | ≥ 22（CI 用 24） | server 运行时 | `node --version` |
| npm | 随 Node | 包管理 | `npm --version` |
| `sqlite3` CLI | ≥ 3.45 | 数据库检查与备份 | `sqlite3 --version` |
| `dbmate` | 最新版 | SQL 迁移 | `dbmate --version` |
| Docker + Compose | 最新版 | 生产/部署演练（本地开发不需要） | `docker compose version` |
| Git | 最新版 | 版本控制 | `git --version` |
| `wscat`（可选） | 最新 | WebSocket 手工调试 | `npm i -g wscat` |

`JAVA_HOME` 必须指向完整 JDK；仅 JRE 无法编译。

---

## 2. 仓库结构

```text
RTLS/
├── app/                          # :app，Compose 单 Activity
├── core/
│   ├── model/                    # :core:model
│   ├── network/                  # :core:network
│   ├── location/                 # :core:location
│   ├── map/                      # :core:map
│   └── data/                     # :core:data
├── server/
│   ├── src/{app,config,http,ws,auth,friends,locations,db,protocol}
│   ├── migrations/
│   ├── data/                     # 本地 SQLite 文件（不提交）
│   ├── test/
│   ├── package.json
│   └── tsconfig.json
├── Docs/                         # 文档（目录规范见 Docs/README.md）
│   ├── architecture/             # 架构总览 + decisions/ADR-NNN-*.md
│   ├── specs/                    # protocol.md、data-model.md
│   ├── guides/                   # development.md、deployment.md
│   ├── planning/                 # roadmap.md、phase-N-tasks.md
│   ├── reviews/                  # YYYY-MM-DD-<subject>.md
│   ├── spikes/                   # SN-<topic>.md
│   └── archive/                  # 已归档的历史文档
├── gradle/libs.versions.toml     # 依赖版本唯一来源
├── settings.gradle.kts
├── docker-compose.yml
├── .env.example
├── AGENTS.md                     # AI Agent 共享上下文（P0-10）
└── README.md
```

Gradle 模块路径一律写作 `:app`、`:core:model`；磁盘路径写作 `app/`、`core/model/`。两者不要混用。

---

## 3. 本地环境

```bash
git clone <repo> && cd RTLS
cp .env.example .env            # .env 不提交
export DATABASE_PATH=./server/data/rtls.db
mkdir -p server/data
make migrate                    # 创建 SQLite 文件并执行迁移
sqlite3 "$DATABASE_PATH" 'select sqlite_version();'
```

`.env.example`：

```dotenv
NODE_ENV=development
PORT=9099
DATABASE_PATH=./server/data/rtls.db
JWT_SECRET=change-me-at-least-32-bytes
LOCATION_RETENTION_DAYS=90
PUBLIC_BASE_URL=http://127.0.0.1:9099
```

注意：

- `DATABASE_PATH` 必须导出为环境变量，或由 `server/src/config.ts` 从 `.env` 读取后再传给 dbmate；Makefile 不会自动加载 `.env`。
- SQLite 文件权限 `0600`、目录 `0700`；不要放在 NFS/SMB。
- `JWT_SECRET` ≥ 256 位，来自环境变量/Secret，不进仓库；
- 百度 AK 放 `local.properties`（不提交），通过 Manifest 占位符注入。

---

## 4. 常用命令

| 场景 | 命令 |
|---|---|
| 环境 | `make migrate` / `make db-reset` / `make db-shell` |
| Server 开发 | `cd server && npm ci && npm run dev` |
| Server 校验 | `cd server && npm run lint && npm run build && npm test` |
| Server 迁移 | `make migrate` / `make migrate-down` / `make migrate-status`（Makefile 注入绝对 `DATABASE_PATH`；直接用 `npm run migrate` 需先导出该变量） |
| Android 构建 | `./gradlew :app:assembleDebug` |
| Android 测试 | `./gradlew :app:testDebugUnitTest` |
| Android lint | `./gradlew :app:lintDebug` |
| WS 调试 | `wscat -c ws://127.0.0.1:9099/v1/ws -H "Authorization: Bearer <token>"` |

---

## 5. Android 工程约定

- Kotlin + Jetpack Compose；`minSdk 26`；JDK 17；
- **Room 用 KSP，不用 kapt**；
- 依赖版本统一在 `gradle/libs.versions.toml`，模块内不写死版本号；
- `debug` / `release` 两个 build type：
  - `debug`：`ws://10.0.2.2:9099`
  - `release`：`wss://rtls.example.com`，并配置 `network_security_config` 禁止明文；
- 权限按 ADR-007 的权限决策树申请：**不要**无条件把 `ACCESS_BACKGROUND_LOCATION` 当必选；
- 定位一律经 `LocationSource`，业务层不直接依赖 GMS；
- 坐标一律用带 `Crs` 的 `GeoPoint`，禁止裸 `lat/lng`；
- 地图层只接受 `crs = BD09` 的点。

---

## 6. Server 工程约定

- TypeScript `strict: true`；Fastify 5 + `@fastify/websocket`；Zod 做协议与配置校验；
- 依赖（Phase 0 基线）：`fastify`、`@fastify/websocket`、`ws`、`zod`、`better-sqlite3`、`uuid`、`dbmate`、`jose`、`pino`、`vitest`；
- **不引入**：`socket.io`、`tdengine-client`、`pg`、`node-pg-migrate`、`testcontainers`；
- 启动时校验配置，失败立即退出；
- 全局只打开一个 `better-sqlite3` 连接；打开后立即执行 ADR-009 的 PRAGMA（`journal_mode=WAL`、`synchronous=NORMAL`、`foreign_keys=ON`、`busy_timeout=5000`、`temp_store=MEMORY`）；
- 所有写操作使用 `BEGIN IMMEDIATE`，位置批量写入必须在同一事务内；
- SQL 与行映射写在 `db/repositories/`，不在路由里拼 SQL；
- 迁移文件只写结构，不写死邀请码/密码；不依赖 `SELECT ... FOR UPDATE`；
- 位置坐标、Token、Refresh、邀请码不写入 INFO 日志。

---

## 7. 分支、评审与 CI

| 项 | 约定 |
|---|---|
| 分支 | `master`（受保护）+ `feat/*`、`fix/*`、`chore/*` |
| PR | CI 全绿才能合并；至少 1 个 review（单人阶段为 0，有协作者后调整为 1） |
| CI | server：typecheck / test / build（Node 22、24）+ 迁移 `up → down → up`；android：`assembleDebug` / `testDebugUnitTest` / `lintDebug`（JDK 17、SDK 35）；docs：相对链接 + `CHANGELOG` 门禁（Dependabot PR 豁免）；security：`npm audit --omit=dev`、Dependabot 告警与安全更新；CodeQL 每周与 PR 扫描 |
| Action | 第三方 Action 固定到 commit SHA，不用浮动 tag（已开启 `sha_pinning_required`） |
| 变更日志 | 当天改动记入根目录 [`CHANGELOG.md`](../../CHANGELOG.md) 的 `[Unreleased]`，与代码同一提交 |
| DoD | 见 [`roadmap.md`](../planning/roadmap.md) §11 |

`master` 由仓库 Ruleset 保护：禁止删除与强推、要求线性历史、要求状态检查（`server (22)`、`server (24)`、`docs`、`security`）通过、分支必须最新。直接 push 会被拒绝，改动一律走 PR。Android workflow 已加入，需在 Ruleset 中将 `android` 设为必需检查。

Android CI 由 `.github/workflows/android.yml` 覆盖：JDK 17 + Android SDK 35 + Gradle 缓存，执行 `assembleDebug` / `testDebugUnitTest` / `lintDebug`。

禁止给 AI Agent 的 `issue_comment` 工作流 `contents: write` 权限（公开仓库风险）。

---

## 8. 测试要求

测试矩阵见 [`roadmap.md`](../planning/roadmap.md) §10。**必须有测试**的部分：

- 坐标转换（已知点 WGS-84 → BD-09，与百度官方工具对比）；
- 重连退避与抖动；
- 鉴权（过期、错误签名、错误 `aud`、`token_version` 不匹配）；
- 位置写入幂等（重复 `clientMsgId`）；
- 授权矩阵（非好友 / 被拉黑 / 关闭分享 / 设备撤销）；
- 迁移：每个测试用独立临时文件数据库执行 `up → down → up`，并校验 `PRAGMA integrity_check = ok`；
- 级联删除：开启 `foreign_keys=ON` 后删除用户，设备/位置/Token 全部消失；
- 重复 `(device_id, client_msg_id)` 不产生第二行；更旧的 `server_ts` 不覆盖 `device_latest`。

---

## 9. AI Agent 使用边界

仓库根目录维护 `AGENTS.md`（见 `phase-0-tasks.md` P0-10），至少包含：

1. 一句话项目描述与当前阶段；
2. 文档优先级：ADR > `protocol.md` / `data-model.md` > `phase-0-tasks.md` > 归档文档；
3. 目录结构与常用命令（本文 §2、§4）；
4. 编码规范（Kotlin/TS、KSP、Zod、SQL 迁移）；
5. 测试要求（本文 §8）；
6. 禁止事项：
   - 不提交 `.env`、AK、Token；
   - 不用 `as any` / `@ts-ignore`；
   - 不在 Query 参数放 JWT；
   - 不信任消息体里的 `deviceId`；
   - 不把 WGS-84 直接画到百度地图上。

使用边界：

- AI 可以改代码，但**必须通过 CI + 人工 review** 才能合并；
- 公开仓库禁止为 AI Action 配置写权限；
- 会话共享关闭（避免位置/密钥相关数据外传）。

---

## 10. 常见陷阱

| 现象 | 原因 / 处理 |
|---|---|
| 地图标记偏移几百米 | 百度 SDK 不会自动把 WGS-84 转 BD-09，必须手动转换（ADR-006） |
| 国内真机定位不可用 | 无 GMS，FLP 不可用 → `LocationSource` 兜底（ADR-007） |
| 空闲几分钟后连接断开 | Cloudflare 空闲超时，需 30s 应用层心跳（ADR-008） |
| 补传很慢 | 协议限流 60 条/分钟；批量补传方案待 ADR 决策（见评审记录） |
| 后台定位被杀 | 前台服务 + OEM 白名单引导；不要用固定 5 秒高精度常驻（ADR-007） |
| 写入报 `database is locked` / `SQLITE_BUSY` | 用 `BEGIN IMMEDIATE`、缩短写事务、批量写入；确认只有一个 API 进程（ADR-009） |
| 直接 `cp` 备份 WAL 模式数据库 | WAL 可能未合并，副本不一致；用 `sqlite3 <db> ".backup '<file>'"` 或 `VACUUM INTO` |
| 多个 API 实例共享同一 DB 文件 | 不支持；SQLite 单写者，扩容需先迁移存储（ADR-009） |
| 用 `nmap` 扫公网域名"端口未开放" | 扫到的是 Cloudflare 边缘，不能证明源站安全（ADR-008） |

---

## 11. 相关文档

- [`architecture/overview.md`](../architecture/overview.md)：架构总览
- [`protocol.md`](../specs/protocol.md)：WebSocket / REST 契约
- [`data-model.md`](../specs/data-model.md)：DDL 与查询
- [`deployment.md`](./deployment.md)：部署与运维
- [`phase-0-tasks.md`](../planning/phase-0-tasks.md)：当前阶段任务
