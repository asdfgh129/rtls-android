# ADR-001：服务端技术栈

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 2.1、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段一
- 存储决策：[ADR-009](./ADR-009-sqlite-storage.md)
- 正式文档：[`architecture/overview.md`](../overview.md)、[`guides/development.md`](../../guides/development.md)

## 背景

现有文档同时出现了 Node.js + Socket.IO、Python + FastAPI、Python `server.bind()` 三种服务端描述，且没有说明最终选择。项目需要：

- 同时提供 REST（注册、好友、历史查询）和 WebSocket（实时位置）；
- 与 Android OkHttp WebSocket 客户端直接对接；
- 以较小团队维护，避免多语言栈；
- 方便写集成测试与容器化部署。

## 决策

采用 **Node.js 24 LTS + TypeScript + Fastify + 原生 WebSocket** 作为唯一服务端栈（最低兼容 Node.js 22）。

| 组件 | 选择 | 用途 |
|---|---|---|
| 运行时 | Node.js 24 LTS（最低 22） | 服务端运行环境 |
| 语言 | TypeScript（`strict: true`） | 类型安全 |
| HTTP 框架 | Fastify 5 | REST API、生命周期、错误处理 |
| WebSocket | `@fastify/websocket`（底层 `ws`） | 原生 WebSocket |
| 校验 | Zod | 协议与配置校验 |
| 数据库 | SQLite 3.45+（见 ADR-009） | MVP 唯一持久化存储 |
| DB 访问 | `better-sqlite3` + 手写 SQL / Repository | 避免 ORM 隐式行为 |
| 迁移 | `dbmate` | 版本化纯 SQL DDL |
| JWT | `jose` | Access Token 签发与校验 |
| 日志 | `pino` | 结构化 JSON 日志 |
| 测试 | Vitest + 临时文件数据库 | 单元与集成测试，无需 Testcontainers |
| 包管理 | npm | 与初始实施步骤（归档）一致 |

服务端目录基线：

```text
server/
├── src/
│   ├── app.ts                 # Fastify 实例与插件注册
│   ├── config.ts              # 环境变量校验
│   ├── http/                  # REST 路由
│   ├── ws/                    # WebSocket 网关、订阅、心跳
│   ├── auth/                  # 注册、JWT、Refresh Token
│   ├── friends/               # 好友与拉黑
│   ├── locations/             # 写入、最新位置、历史查询
│   ├── db/                    # better-sqlite3 连接、Repository
│   └── protocol/              # Zod schema、错误码
├── migrations/                # SQL 迁移
├── test/                      # 单元与集成测试
├── package.json
├── tsconfig.json
└── Dockerfile
```

## 理由

1. **协议兼容**：Android 端使用 OkHttp WebSocket，原生 WebSocket 可直接对接；Socket.IO 需要额外的 Socket.IO 客户端和 Engine.IO 握手，增加复杂度。
2. **单一语言**：Step 的依赖与命令都以 npm 为主，固定 Node 可避免 Python/Node 双栈。
3. **类型安全**：TypeScript + Zod 让协议 schema 与运行时校验共用一份定义。
4. **运维简单**：Fastify 体积小、启动快，适合单机 + Docker Compose 的 MVP 形态；SQLite 单文件让本地开发、测试和部署都不需要独立的数据库容器（ADR-009）。

## 后果

### 正面

- Android 与服务器之间是标准 WebSocket，可用 `wscat`、OkHttp、浏览器工具直接调试。
- REST 与 WS 在同一个进程内，鉴权、日志、配置统一。
- 后续可平滑拆分：REST 与 WS 都通过 Repository/Service 访问领域逻辑。

### 负面 / 需要注意

- 需要自己实现订阅表、心跳、ACK、限流，不能依赖 Socket.IO 的房间机制。
- 没有 ORM，SQL 与行映射需要手写并测试。
- 横向扩展时需要 Redis/NATS 做跨实例广播；MVP 单实例不需要。
- SQLite 只支持单写者、单进程访问：`api` 不能水平扩容；需要多实例时必须先迁移存储（ADR-009）。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| Socket.IO + Android `socket.io-client-java` | 不采用 | 与现有 OkHttp 方案冲突；MVP 不需要房间/降级 |
| Python FastAPI + `websockets` | 不采用 | 与 Step 的 npm 依赖冲突；双栈维护成本高 |
| gRPC / MQTT | 不采用 | Android 与 Cloudflare Tunnel 链路复杂；实时位置场景收益低 |
| Prisma / Drizzle ORM | 暂不采用 | 需要精细控制时间序查询与索引；后续可评估 |

## 验证方式

- `node --version` ≥ 22（CI 用 24）。
- `npm run build`、`npm test`、`npm run lint` 在 CI 中通过。
- `wscat -c ws://127.0.0.1:9099/v1/ws -H "Authorization: Bearer <token>"` 可建立连接。
