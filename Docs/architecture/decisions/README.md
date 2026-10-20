# RTLS 架构决策记录（ADR）

本目录记录 RTLS 项目的关键技术决策。任何与初始方案、初始实施步骤冲突的内容，以本目录中状态为 **Accepted** 的 ADR 为准；初始文档已归档到 [`../archive/`](../../archive)。

配套正式文档：[`architecture/overview.md`](../overview.md)（架构总览）、[`specs/protocol.md`](../../specs/protocol.md)（协议）、[`specs/data-model.md`](../../specs/data-model.md)（数据模型）。

## 决策索引

| ADR | 主题 | 状态 | 取代的旧描述 |
|---|---|---|---|
| [ADR-001](./ADR-001-server-stack.md) | 服务端技术栈 | Accepted | [初始方案 2.1](../../archive/plan-2026-10-17.md) / [初始步骤 阶段一](../../archive/step-2026-10-17.md) |
| [ADR-002](./ADR-002-websocket-protocol.md) | 实时通信协议 | Accepted | [初始方案 2.2](../../archive/plan-2026-10-17.md) / [初始步骤 阶段一、二](../../archive/step-2026-10-17.md) |
| [ADR-003](./ADR-003-mvp-storage.md) | MVP 存储方案 | Superseded（由 ADR-009 取代） | [初始方案 四](../../archive/plan-2026-10-17.md) / [初始步骤 阶段一](../../archive/step-2026-10-17.md) |
| [ADR-004](./ADR-004-auth.md) | 认证与令牌生命周期 | Accepted | [初始方案 2.3、4.2](../../archive/plan-2026-10-17.md) / [初始步骤 阶段四](../../archive/step-2026-10-17.md) |
| [ADR-005](./ADR-005-identity.md) | 用户与设备身份模型 | Accepted | [初始方案 3.4、4.2](../../archive/plan-2026-10-17.md) |
| [ADR-006](./ADR-006-coordinate-system.md) | 坐标系统与转换 | Accepted | [初始方案 3.3](../../archive/plan-2026-10-17.md) / [初始步骤 阶段三](../../archive/step-2026-10-17.md) |
| [ADR-007](./ADR-007-location-source.md) | Android 定位源抽象 | Accepted | [初始方案 3.1](../../archive/plan-2026-10-17.md) / [初始步骤 阶段二](../../archive/step-2026-10-17.md) |
| [ADR-008](./ADR-008-deployment.md) | 部署与隧道 | Accepted | [初始方案 五](../../archive/plan-2026-10-17.md) / [初始步骤 阶段四](../../archive/step-2026-10-17.md) |
| [ADR-009](./ADR-009-sqlite-storage.md) | MVP 存储改为 SQLite | Accepted | 取代 [ADR-003](./ADR-003-mvp-storage.md) |

## ADR 状态定义

- **Proposed**：已提出，尚未确认。
- **Accepted**：已确认，后续实现必须遵守。
- **Deprecated**：仍保留，但不建议新代码使用。
- **Superseded**：已被新的 ADR 取代，需注明替代者。

## 变更规则

1. 新增/修改架构决策时，先新增 ADR，再改代码或旧文档。
2. ADR 一旦 Accepted，不直接改写结论；需要变更时新增 ADR 并标记旧 ADR 为 Superseded。
3. 每个 ADR 必须包含：背景、决策、后果、备选方案、验证方式。
