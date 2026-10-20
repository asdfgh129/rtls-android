# RTLS 文档索引

RTLS（Real-Time Location Sharing）是一个 Android 采集 → 服务端中转/存储 → 好友端实时查看/历史轨迹的系统。

## 目录与命名规范

文档按**类型**分目录存放，根目录只保留本索引；文件名一律 **kebab-case 小写**。

| 目录 | 用途 | 命名约定 |
|---|---|---|
| `architecture/` | 架构总览与架构决策 | `overview.md`；`decisions/ADR-NNN-<topic>.md` |
| `specs/` | 可执行契约：协议、数据模型 | `<subject>.md` |
| `guides/` | 操作手册：开发环境、部署运维 | `<topic>.md` |
| `planning/` | 路线图与阶段任务 | `roadmap.md`、`phase-N-tasks.md` |
| `reviews/` | 评审记录 | `YYYY-MM-DD-<subject>.md` |
| `spikes/` | 风险验证记录 | `SN-<topic>.md` |
| `archive/` | 已归档的历史文档 | `<type>-YYYY-MM-DD.md` |

规则：

1. 新文档先选目录再命名，不在根目录堆文件；
2. ADR 编号只增不改，Accepted 后如需变更则新增 ADR 并标记旧 ADR 为 Superseded；
3. 移动/重命名必须同步更新所有引用（链接与正文路径）；
4. 过时但需追溯的文档进 `archive/`，不直接删除。

## 文档地图

| 文档 | 内容 | 状态 |
|---|---|---|
| [`architecture/overview.md`](./architecture/overview.md) | 架构总览、数据流、模块职责、硬约束 | **现行** |
| [`architecture/decisions/`](./architecture/decisions/README.md) | 架构决策记录（ADR-001 ~ ADR-009） | **权威** |
| [`specs/protocol.md`](./specs/protocol.md) | WebSocket / REST 协议 v1 | **权威** |
| [`specs/data-model.md`](./specs/data-model.md) | SQLite DDL、索引、查询、保留策略 | **权威** |
| [`guides/development.md`](./guides/development.md) | 环境、工程结构、命令、分支流程、测试、AI 边界 | **现行** |
| [`guides/deployment.md`](./guides/deployment.md) | 部署拓扑、配置、备份恢复、验证与回滚 | **现行** |
| [`planning/roadmap.md`](./planning/roadmap.md) | Phase 1~6 的实施路线与验收标准 | **执行中** |
| [`planning/phase-0-tasks.md`](./planning/phase-0-tasks.md) | Phase 0 可执行任务与验收标准 | **执行中** |
| [`spikes/`](./spikes/README.md) | 风险验证记录（GMS、坐标、Cloudflare、百度隐私） | 待填写 |
| [`reviews/2026-10-19-initial-plan.md`](./reviews/2026-10-19-initial-plan.md) | 初始方案评审记录 | 已完成 |
| [`reviews/2026-10-19-phase0-docs.md`](./reviews/2026-10-19-phase0-docs.md) | Phase 0 文档评审记录与待办 | 已完成 |
| [`archive/`](./archive) | 已归档的初始方案与实施步骤 | 归档 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 每日变更记录（Keep a Changelog 1.1.0） | **持续更新** |

## 权威顺序

出现冲突时，按以下顺序取信：

1. `architecture/decisions/ADR-*.md`（状态 Accepted）
2. `specs/protocol.md` / `specs/data-model.md`
3. `planning/phase-0-tasks.md` / `planning/roadmap.md`
4. `architecture/overview.md` / `guides/*.md`（总览与操作手册；细节以第 1、2 级为准）
5. `archive/`（仅背景资料）

`archive/` 保留 2026-10-17 的初始方案与实施步骤，仅供追溯，不再作为实现依据。它们的已知问题：

- Socket.IO 与 OkHttp 原生 WebSocket 不兼容；
- 百度 SDK 不会自动把 WGS-84 转 BD-09；
- `tdengine-client` 不是官方 Node 连接器；
- 认证只有校验、没有签发流程；
- Android 14 后台定位描述过于绝对；
- `nmap` 无法验证源站端口是否暴露。

## 建议阅读顺序

**新成员**

1. 本文件
2. `architecture/decisions/README.md`
3. `architecture/overview.md`
4. `specs/protocol.md`
5. `specs/data-model.md`
6. `planning/roadmap.md`
7. `planning/phase-0-tasks.md`

**服务端开发**

1. ADR-001 / 002 / 004 / 009（存储）
2. `specs/protocol.md`
3. `specs/data-model.md`
4. `guides/development.md` §6
5. `planning/phase-0-tasks.md` 的 P0-04 / P0-05 / P0-06
6. `planning/roadmap.md` 的 Phase 1~4

**Android 开发**

1. ADR-005 / 006 / 007
2. `specs/protocol.md` 第 2~4、10 节
3. `guides/development.md` §5
4. `planning/phase-0-tasks.md` 的 P0-07 / P0-09
5. `planning/roadmap.md` 的 Phase 1~3

**部署运维**

1. ADR-008
2. `guides/deployment.md`
3. `guides/development.md` §3、§4

## 当前阶段

**Phase 0：决策与工程骨架**

已完成：

- ADR-001 ~ ADR-009 文档（ADR-009 将存储改为 SQLite，取代 ADR-003）；
- 协议规范 v1；
- 数据模型；
- 实施路线图；
- 初始方案评审与 Phase 0 文档评审记录；
- Phase 0 任务清单；
- 正式文档（`architecture/overview.md`、`guides/development.md`、`guides/deployment.md`），初始文档已归档；
- 文档按类型目录整理（`architecture/`、`specs/`、`guides/`、`planning/`、`reviews/`、`archive/`）；
- `server/` 骨架：Fastify + `/healthz`、SQLite 迁移（dbmate）、Repository 层、Vitest 测试；
- CI（GitHub Actions）：server 双 Node 版本、迁移回归、文档链接与 `CHANGELOG` 门禁、依赖审计、CodeQL、Dependabot，`master` 分支保护；
- 变更日志（`CHANGELOG.md`，按日更新）。

待完成：

- Android 空壳；
- Android CI（随 Android 工程一起补齐）；
- 风险 spike；
- `AGENTS.md` 更新；
- ADR-010（批量补传与配额、注册错误码取舍、注销删除接口）。

## 变更流程

1. 架构/协议/数据模型有变更 → 先新增 ADR。
2. 修改 `specs/protocol.md` 时同步更新 `server/src/protocol` schema 与契约测试。
3. 修改 `specs/data-model.md` 时同步新增迁移，禁止只改文档不改库。
4. 文档中的命令必须可复制执行；不可执行的示例要标注“示意”。
5. 新文档按“目录与命名规范”落位；过时文档移入 `archive/` 并同步更新所有引用。
6. 每次 Phase 结束更新本文件的“当前阶段”。
7. 当天有代码、文档或配置改动 → 先在根目录 [`CHANGELOG.md`](../CHANGELOG.md) 的 `[Unreleased]` 下按日期记录，再提交。
