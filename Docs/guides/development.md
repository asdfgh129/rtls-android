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
| Docker + Compose | 最新版 | 本地 PostgreSQL | `docker compose version` |
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
make dev-up                     # 启动 PostgreSQL 16
make migrate                    # 空库迁移
psql "$DATABASE_URL" -c 'select version();'
```

`.env.example`：

```dotenv
NODE_ENV=development
PORT=9099
DATABASE_URL=postgres://rtls:rtls@127.0.0.1:5432/rtls
JWT_SECRET=change-me-at-least-32-bytes
LOCATION_RETENTION_DAYS=90
PUBLIC_BASE_URL=http://127.0.0.1:9099
```

注意：

- PostgreSQL 只映射 `127.0.0.1:5432`，不映射 `0.0.0.0`；
- `JWT_SECRET` ≥ 256 位，来自环境变量/Secret，不进仓库；
- 百度 AK 放 `local.properties`（不提交），通过 Manifest 占位符注入。

---

## 4. 常用命令

| 场景 | 命令 |
|---|---|
| 环境 | `make dev-up` / `make dev-down` / `make migrate` |
| Server 开发 | `cd server && npm ci && npm run dev` |
| Server 校验 | `cd server && npm run lint && npm run build && npm test` |
| Server 迁移 | `cd server && npm run migrate up` / `npm run migrate down` |
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
- 依赖（Phase 0 基线）：`fastify`、`@fastify/websocket`、`ws`、`zod`、`pg`、`node-pg-migrate`、`jose`、`pino`、`vitest`、`testcontainers`；
- **不引入**：`socket.io`、`tdengine-client`；
- 启动时校验配置，失败立即退出；
- SQL 与行映射写在 `db/repositories/`，不在路由里拼 SQL；
- 迁移文件只写结构，不写死邀请码/密码；
- 位置坐标、Token、Refresh、邀请码不写入 INFO 日志。

---

## 7. 分支、评审与 CI

| 项 | 约定 |
|---|---|
| 分支 | `main` + `feat/*`、`fix/*`、`chore/*` |
| PR | 至少 1 个 review；CI 全绿才能合并 |
| CI | server：lint / typecheck / test / build；Android：`testDebugUnitTest` + `lintDebug`；security：secret scan + 依赖审计 |
| Action | 第三方 Action 固定到 commit SHA，不用浮动 tag |
| DoD | 见 [`roadmap.md`](../planning/roadmap.md) §11 |

禁止给 AI Agent 的 `issue_comment` 工作流 `contents: write` 权限（公开仓库风险）。

---

## 8. 测试要求

测试矩阵见 [`roadmap.md`](../planning/roadmap.md) §10。**必须有测试**的部分：

- 坐标转换（已知点 WGS-84 → BD-09，与百度官方工具对比）；
- 重连退避与抖动；
- 鉴权（过期、错误签名、错误 `aud`、`token_version` 不匹配）；
- 位置写入幂等（重复 `clientMsgId`）；
- 授权矩阵（非好友 / 被拉黑 / 关闭分享 / 设备撤销）；
- 迁移 `up → down → up`。

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
| 用 `nmap` 扫公网域名"端口未开放" | 扫到的是 Cloudflare 边缘，不能证明源站安全（ADR-008） |

---

## 11. 相关文档

- [`architecture/overview.md`](../architecture/overview.md)：架构总览
- [`protocol.md`](../specs/protocol.md)：WebSocket / REST 契约
- [`data-model.md`](../specs/data-model.md)：DDL 与查询
- [`deployment.md`](./deployment.md)：部署与运维
- [`phase-0-tasks.md`](../planning/phase-0-tasks.md)：当前阶段任务
