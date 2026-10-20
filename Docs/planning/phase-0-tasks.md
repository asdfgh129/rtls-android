# Phase 0：决策落地与工程骨架任务清单

- 状态：Ready to execute
- 日期：2026-10-19
- 目标：在写业务功能前，把决策、契约、数据库、工程骨架、CI 和关键风险验证全部落地
- 预计：3~5 个工作日（2 人并行）
- 前置：ADR-001 ~ ADR-009 已确认

> Phase 0 的完成标准不是“代码能跑”，而是 **Phase 1 可以在没有架构歧义的情况下直接开工**。

---

## 1. 退出标准（Exit Criteria）

全部满足才进入 Phase 1：

- [ ] ADR-001 ~ ADR-009 状态为 Accepted（ADR-003 已由 ADR-009 取代）；`Plan.md`/`Step.md` 已归档为 `Docs/archive/`，正式文档（`architecture/overview.md` / `guides/development.md` / `guides/deployment.md`）就位。
- [ ] `Docs/specs/protocol.md` 与 `Docs/specs/data-model.md` 可被客户端/服务端同时引用。
- [ ] 空库可一键迁移，`up → down → up` 通过，且 `PRAGMA integrity_check = ok`。
- [ ] `server/` 可启动，`GET /healthz` 返回 200。
- [ ] Android 工程可编译，空壳 App 可启动，权限流程可展示。
- [ ] CI 对 server 与 Android 都执行 lint/test/build。
- [ ] 4 个风险 spike 有结论（GMS、百度坐标、Cloudflare WS、百度隐私初始化）。
- [ ] Phase 1 的 ticket 已拆分，每个 ticket 有验收标准。

---

## 2. 任务总览

| ID | 任务 | 负责人 | 预估 | 依赖 | 状态 |
|---|---|---|---:|---|---|
| P0-01 | ADR 确认与冲突关闭 | Tech lead | 0.5d | — | Draft |
| P0-02 | 仓库骨架 | Server + Android | 1d | P0-01 | Todo |
| P0-03 | 本地开发环境 | Server | 0.5d | P0-02 | Todo |
| P0-04 | 协议 schema 与契约测试 | Server | 1d | P0-01 | Todo |
| P0-05 | 数据库迁移与 Repository | Server | 1d | P0-02 | Todo |
| P0-06 | 服务端空壳 | Server | 0.5d | P0-02/03/05 | Todo |
| P0-07 | Android 空壳 | Android | 1d | P0-02 | Todo |
| P0-08 | CI 与分支保护 | DevOps | 0.5d | P0-02 | Done |
| P0-09 | 风险 spike | Android + Server | 1.5d | P0-03/07 | Todo |
| P0-10 | AGENTS.md 更新 | Tech lead | 0.5d | P0-01~09 | Todo |
| P0-11 | Phase 1 就绪评审 | 全员 | 0.5d | 全部 | Todo |

> 预估为净工作时间，不含等待真机/Cloudflare 配置的时间。

---

## 3. 任务详情

### P0-01：ADR 确认与冲突关闭

**目标**：让所有实现者知道“以哪份文档为准”。

**产出**

- 团队确认 `Docs/architecture/decisions/ADR-001` ~ `ADR-009` 的 Accepted 状态（ADR-003 已由 ADR-009 取代；有异议则记录并新增 ADR）。
- 初始方案与实施步骤已归档为 `Docs/archive/plan-2026-10-17.md`、`Docs/archive/step-2026-10-17.md`，并转为 `architecture/overview.md` / `guides/development.md` / `guides/deployment.md`；后续只需维护新文档。
- 列出仍存在的冲突及处理方式；不允许“以后再说”。

**验收**

- 每个 ADR 有明确结论、后果、备选方案。
- 任何“Socket.IO / Python / TDengine / 坐标自动转换”的旧描述都有对应 ADR 覆盖。

**注意**

- ADR-006 必须明确：百度 SDK **不会**自动把 WGS-84 转 BD-09。
- ADR-007 必须明确：FLP 依赖 GMS，国内需要兜底。

---

### P0-02：仓库骨架

**目标**：建立 Android + server 的最小可编译工程。

**产出**

```text
RTLS/
├── app/                          # Android 主模块（空壳 Compose Activity）
├── core/
│   ├── model/                    # GeoPoint、LocationSample、Crs
│   ├── network/                  # WS 客户端接口与空实现
│   ├── location/                 # LocationSource 接口与空实现
│   ├── map/                      # 地图接口与空实现
│   └── data/                     # Room 数据库骨架
├── server/
│   ├── src/
│   │   ├── app.ts
│   │   ├── config.ts
│   │   ├── http/
│   │   ├── ws/
│   │   ├── auth/
│   │   ├── friends/
│   │   ├── locations/
│   │   ├── db/
│   │   └── protocol/
│   ├── migrations/
│   ├── test/
│   ├── package.json
│   └── tsconfig.json
├── Docs/                          # 文档：architecture/ specs/ guides/ planning/ reviews/ spikes/ archive/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── docker-compose.yml
├── .env.example
└── README.md
```

> `app/`、`core/*` 目录下各有 `build.gradle.kts`；Gradle 模块路径写作 `:app`、`:core:model`，磁盘路径写作 `app/`、`core/model/`，两者不要混用（见 `guides/development.md` §2）。

**Android 基线**

- Kotlin + Jetpack Compose
- minSdk 26，targetSdk 按当前稳定版
- JDK 17
- Room 使用 KSP（不是 kapt）
- 依赖版本统一放在 `gradle/libs.versions.toml`
- `debug` / `release` 两个 build type：
  - debug：`ws://10.0.2.2:9099`
  - release：`wss://rtls.example.com`

**验收**

```bash
./gradlew :app:assembleDebug
cd server && npm ci && npm run build
```

两条命令在干净环境通过。

**不要做**

- 不写业务逻辑；
- 不接百度 SDK；
- 不写 WebSocket 协议实现（P0-04 之后）。

---

### P0-03：本地开发环境

**目标**：一条命令准备本地 SQLite 数据目录并完成迁移。

**产出**

- `server/data/` 目录（本地 SQLite 文件，加入 `.gitignore`）。
- `docker-compose.yml`：
  - `api`（可选，Phase 1 再启用）
  - 绑定 `./data` 卷
  - 网络
- `.env.example`：

```dotenv
NODE_ENV=development
PORT=9099
DATABASE_PATH=./server/data/rtls.db
JWT_SECRET=change-me-at-least-32-bytes
LOCATION_RETENTION_DAYS=90
PUBLIC_BASE_URL=http://127.0.0.1:9099
```

- `Makefile` 或 npm scripts（`make migrate` 是 dbmate 的薄封装）：

```bash
make migrate     # mkdir -p server/data && dbmate --url "sqlite:$DATABASE_PATH" up
make db-reset     # dbmate drop && dbmate up（仅本地）
make db-shell     # sqlite3 "$DATABASE_PATH"
make test
```

- `server/package.json` 脚本：

```json
{
  "scripts": {
    "migrate": "dbmate --migrations-dir migrations --url \"sqlite:$DATABASE_PATH\""
  }
}
```

**验收**

```bash
mkdir -p server/data
make migrate
sqlite3 "$DATABASE_PATH" 'select sqlite_version();'
cd server && npm run migrate status
```

**注意**

- `DATABASE_PATH` 必须显式导出，或由 `server/src/config.ts` 读取 `.env` 后传入；Makefile 不自动加载 `.env`。
- SQLite 文件权限 `0600`、目录 `0700`；禁止放在 NFS/SMB。
- 只允许一个 API 进程访问数据库文件（ADR-009）。
- `.env` 不提交；`.env.example` 可以提交。

---

### P0-04：协议 schema 与契约测试

**目标**：把 `Docs/specs/protocol.md` 变成可执行契约。

**产出**

- `server/src/protocol/envelope.ts`：Zod schema。
- `server/src/protocol/messages.ts`：所有消息 payload schema。
- `server/src/protocol/errors.ts`：错误码常量。
- `server/src/protocol/schemas/rest.yaml`：OpenAPI 3.1（至少覆盖 auth/friends/history）。
- 契约测试：
  - 合法/非法 envelope；
  - 坐标边界、NaN/Infinity；
  - 超长字段、超大帧；
  - 未知 `type`；
  - `v != 1`；
  - 错误码映射。

**验收**

```bash
cd server && npm test -- protocol
```

- 每个消息类型至少 1 个正例、1 个反例。
- `Docs/specs/protocol.md` 中的示例可以粘贴进测试并通过校验。

**注意**

- 不要先写业务路由再补 schema；schema 是 Phase 1 的输入。
- 未知字段默认忽略（`passthrough` 或显式配置），保证前向兼容。

---

### P0-05：数据库迁移与 Repository

**目标**：`Docs/specs/data-model.md` 可在空库（SQLite 文件）落地。

**产出**

- `server/migrations/`，dbmate 纯 SQL 迁移（`-- migrate:up` / `-- migrate:down`）：
  - `20261020000100_identity.sql`
  - `20261020000200_social.sql`
  - `20261020000300_locations.sql`
  - `20261020000400_indexes.sql`
  - `20261020000500_seed_helpers.sql`
- `server/src/db/connection.ts`：打开唯一的 `better-sqlite3` 连接，应用 ADR-009 的 PRAGMA，提供 `SELECT 1` 健康检查与优雅关闭（关闭前 `PRAGMA wal_checkpoint(TRUNCATE)`）。
- `server/src/db/repositories/`：
  - `UserRepository`
  - `DeviceRepository`
  - `FriendRepository`
  - `LocationRepository`
  - `TokenRepository`
- 集成测试（Vitest + 临时文件数据库，不用 Testcontainers）：
  - 迁移 `up/down/up` + `PRAGMA integrity_check`；
  - 规范化好友对；
  - 重复 `client_msg_id` 只产生一行；
  - `device_latest` 旧数据不覆盖新数据；
  - 授权查询；
  - 开启 `foreign_keys=ON` 后删除用户级联清理。

**验收**

```bash
cd server
npm run migrate up
npm run migrate down
npm run migrate up
npm test -- db
```

**注意**

- 所有写操作使用 `BEGIN IMMEDIATE`；SQLite 不支持 `SELECT ... FOR UPDATE`。
- `friendships` 写入前规范化为 `(low, high)`（字符串比较，不依赖 `LEAST/GREATEST`）。
- `locations` 的 `UNIQUE (device_id, client_msg_id)` 不能省。
- 迁移中不要写死邀请码/密码。

---

### P0-06：服务端空壳

**目标**：Fastify + WS 网关骨架可启动、可拒绝未认证连接。

**产出**

- `GET /healthz`：检查进程与 DB。
- 统一错误处理（`error.code/message/requestId`）。
- 请求 ID（`requestId`）。
- 配置校验（Zod，启动时失败即退出）。
- `GET /v1/ws` upgrade：
  - 读取 `Authorization`；
  - 校验 JWT（可先用测试密钥）；
  - 失败返回 401 / close 4401；
  - 成功建立连接并记录 `deviceId`。
- 心跳骨架：30s ping / 90s timeout（可先只记录日志）。
- 限流中间件骨架（可先用内存实现）。

**验收**

```bash
curl -i http://127.0.0.1:9099/healthz
wscat -c ws://127.0.0.1:9099/v1/ws
# 预期：401 / close 4401
```

**不要做**

- 不实现 `location_update` 业务；
- 不接好友逻辑；
- 不接百度/地图。

---

### P0-07：Android 空壳

**目标**：App 可启动，权限流程和模块边界就位。

**产出**

- Compose 单 Activity。
- 权限流程：
  - 前台定位；
  - 通知（Android 13+）；
  - 后台定位引导页（按需）；
  - 拒绝后的可恢复状态。
- `LocationForegroundService` 空壳：启动/停止、通知渠道。
- `LocationSource` 接口 + `FakeLocationSource`。
- `RtlsWebSocketClient` 接口 + 空实现（不连服务器）。
- `MapScreen` 空壳：显示占位，不接百度 SDK。
- `GeoPoint`/`Crs` 模型 + 单元测试。

**验收**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

- 模拟器可启动 App；
- 权限拒绝不崩溃；
- `GeoPoint` 测试通过。

---

### P0-08：CI 与分支保护

**目标**：每次 PR 自动验证，不把问题留到联调。

**产出**

- `.github/workflows/ci.yml`：
  - server：Node 22 / 24 矩阵，`npm ci` → `typecheck` → `test` → `build`；Node 24 额外执行迁移 `up → down → up` 并校验 `integrity_check` 和 `auto_vacuum`；
  - docs：Markdown 相对链接检查、PR 的 `CHANGELOG.md` 门禁；
  - security：`npm audit --omit=dev --audit-level=high`、Dependabot 告警与安全更新；
  - 测试使用临时文件数据库（无需 DB service / Testcontainers）。
- `.github/workflows/codeql.yml`：JavaScript/TypeScript 静态扫描，PR、`master` push 与每周一运行。
- `.github/dependabot.yml`：npm（`server/`）与 GitHub Actions 每周分组升级。
- `scripts/check-links.py`、`scripts/check-changelog.py`：文档链接与变更日志门禁。
- 密钥扫描使用 GitHub 原生 Secret Scanning + Push Protection（已启用），不再引入 gitleaks Action。
- 分支保护 Ruleset：禁止删除/强推、线性历史、必需状态检查、分支必须最新；单人阶段 review 数为 0。
- `.github/workflows/android.yml` 延后到 Android 工程落地（P0-07）后补充。

**验收**

- 新建 PR 后 `server (22)`、`server (24)`、`docs`、`security` 全部运行；
- 故意提交一个类型错误或失效链接，CI 变红；
- 修复后 CI 变绿，且 `master` 直接 push 被拒绝。

**注意**

- Action 固定到 commit SHA，不使用浮动 tag。
- 不要让 AI Agent 在 `issue_comment` 上拥有 `contents: write` 权限（见 ADR/评审）。

---

### P0-09：风险 spike（时间盒 1.5 天）

每个 spike 必须有书面结论，记录在 `Docs/spikes/`。

#### S1：目标设备 GMS 可用性

- 在至少 2 台目标真机上运行 `GoogleApiAvailability.isGooglePlayServicesAvailable()`。
- 记录：是否有 GMS、FLP 是否可用、定位权限行为。
- 结论：是否需要 Phase 2 立即接百度/高德兜底。

**通过标准**：明确“有 GMS / 无 GMS”设备清单与兜底方案。

#### S2：百度坐标转换

- 用已知点（天安门、上海人民广场、广州塔）做 WGS-84 → BD-09。
- 在百度地图上放 Marker，检查是否与真实位置一致。
- 对比 `setCoordType(BD09LL)` 直接使用 WGS-84 的偏移。

**通过标准**：转换后偏移在可接受范围；记录对比截图。

#### S3：Cloudflare WebSocket 空闲

- 起一个最小 WS echo server，通过 Cloudflare Tunnel 暴露。
- 客户端每 30 秒 ping，持续 10 分钟。
- 记录是否断开、断开时间、重连行为。

**通过标准**：10 分钟不断开；若断开，确认 30 秒心跳是否足够。

#### S4：百度 SDK 隐私初始化

- 按“用户同意隐私政策 → `setAgreePrivacy(true)` → `initialize()`”顺序初始化。
- 验证未同意时 SDK 不初始化、不崩溃。
- 验证 AK 绑定 debug/release SHA1。

**通过标准**：隐私流程可演示；AK 不进入版本库。

#### S5（可选）：OpenCode 工具链验证

- 验证 `opencode-android-tdd`、`opencode/agent-action@v1`、`opencode-agent-kit` 是否存在、是否维护。
- 如果不存在：不要写进 `guides/development.md` 的硬依赖；如需保留，标注“可选/待验证”并给出替代方案。

**通过标准**：每个工具标注“可用 / 不可用 / 待定”，并给出替代方案。

---

### P0-10：AGENTS.md 更新

**目标**：让任何 AI/新人都能从仓库直接开始工作。

**必须包含**

- 项目一句话描述与当前阶段。
- 文档优先级：ADR > protocol/data-model > phase-0-tasks/roadmap > architecture/development-guide/deployment > archive。
- 目录结构（Android 模块、server 模块、Docs）。
- 常用命令：

```bash
make migrate
make db-shell
make test
./gradlew :app:assembleDebug
cd server && npm run dev
```

- 编码规范：Kotlin/TS、KSP、Zod、SQL 迁移。
- 测试要求：坐标转换、退避、鉴权、幂等必须有测试。
- 禁止事项：
  - 不提交 `.env`、AK、Token；
  - 不用 `as any`/`@ts-ignore`；
  - 不在 Query 参数放 JWT；
  - 不信任消息体里的 `deviceId`；
  - 不直接使用 WGS-84 画百度 Marker。
- OpenCode 使用边界：AI 可以改代码，但必须通过 CI + 人工 review；公开仓库禁止 AI 写权限 Action。

**验收**

- 新成员按 AGENTS.md 能在 30 分钟内跑起 server 与 Android 空壳。

---

### P0-11：Phase 1 就绪评审

**检查项**

- [ ] 协议 v1 已冻结（允许后续新增字段，不允许破坏性修改）。
- [ ] DDL 已落地，迁移可回滚，`PRAGMA integrity_check = ok`。
- [ ] 认证流程可端到端演示（注册 → 连接）。
- [ ] Android 空壳可启动、权限流程可用。
- [ ] CI 全绿。
- [ ] 4 个 spike 有结论。
- [ ] Phase 1 ticket 已拆分，每个有验收标准。
- [ ] 没有任何“待定”的 P0 决策。

**输出**

- `Docs/planning/phase-1-plan.md`（或 issue/项目看板）。
- Go / No-Go 结论。

---

## 4. Phase 1 预览（不在 Phase 0 实现）

Phase 1 只做 **一台设备端到端**：

1. 注册/登录（邀请码）。
2. WebSocket 连接（Header JWT）。
3. 上报位置 → 写 `locations` + `device_latest`。
4. 自己在地图上看到自己的位置。
5. 断网 → 本地队列 → 重连 → 补传 → 不重复。
6. 历史查询返回自己的轨迹（可选，Phase 1 末）。

Phase 1 不做：

- 好友互查；
- 历史轨迹 UI 完整版；
- 百度/高德兜底（除非 S1 结论要求）；
- 生产部署；
- TDengine。

---

## 5. 命令速查

```bash
# 环境
make migrate
make db-reset
make db-shell

# Server
cd server
npm ci
npm run dev
npm run build
npm test
npm run lint

# Android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug

# 协议调试
wscat -c ws://127.0.0.1:9099/v1/ws -H "Authorization: Bearer <token>"
```

---

## 6. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 目标设备无 GMS | FLP 不可用 | S1 结论 + 百度/高德兜底 |
| 百度坐标偏移 | 地图错误 | S2 + ADR-006 单元测试 |
| Cloudflare 空闲断开 | 实时性差 | 30s 心跳 + 重连 |
| 百度隐私初始化不合规 | 上架/审核风险 | S4 + 隐私政策 |
| OpenCode 工具不存在 | Step 计划落空 | S5 验证，失败则移除 |
| 邀请码流程未定 | 无法注册 | P0-05/06 提供 CLI 与管理脚本 |
| 无真机 | 无法验证 FGS/后台 | 至少准备 2 台目标设备；模拟器只做 UI |

---

## 7. 完成后的下一步

Phase 0 结束后，按以下顺序进入 Phase 1：

1. 服务端：注册/刷新 → WS 网关 → 位置写入 → 最新位置。
2. Android：权限 → FGS → WS 客户端 → 离线队列 → 地图。
3. 联调：模拟器 + 真机各一台。
4. 验收：断网重连、重复消息、越权拒绝、坐标正确。
