# 文档评审记录：初始方案 / 初始实施步骤

- 日期：2026-10-19
- 评审对象：初始方案与初始实施步骤（现归档为 [`archive/plan-2026-10-17.md`](../archive/plan-2026-10-17.md)、[`archive/step-2026-10-17.md`](../archive/step-2026-10-17.md)）
- 评审方式：逐节阅读 + 官方文档核实（百度地图、Cloudflare、TDengine）+ 架构/安全/可执行性检查
- 结论：**方向正确，但不能直接作为开发依据；已通过 ADR 与协议/数据模型文档关闭主要冲突。**

---

## 1. 总评

| 维度 | 评分 | 说明 |
|---|---:|---|
| 架构方向 | 8/10 | 采集 → 中转/存储 → 展示主线清晰 |
| 技术选型一致性 | 4/10 | Socket.IO/OkHttp、Node/Python、TDengine 依赖冲突 |
| 安全与隐私 | 4/10 | WSS/Token 方向对，但缺少认证生命周期、授权模型、隐私合规 |
| 可执行性 | 3/10 | 无协议规范、DDL、接口定义、验收标准 |
| 测试与运维 | 2/10 | 无测试矩阵、CI、监控、备份、回滚 |

---

## 2. P0 阻断项与处理

| # | 问题 | 证据 | 影响 | 处理 |
|---|---|---|---|---|
| 1 | Socket.IO 服务端 vs OkHttp 原生 WebSocket | Socket.IO 使用 Engine.IO 协议，OkHttp 只实现 RFC 6455 | 联调必然失败 | ADR-002：改用原生 WebSocket + JSON |
| 2 | 百度 SDK 不会自动转换 WGS-84 → BD-09 | 百度官方文档：自动转换仅适用于 GCJ-02；WGS-84 需 `CoordinateConverter.from(GPS)` | 地图标记偏移 | ADR-006：全链路 WGS-84，渲染层手动转 BD-09 |
| 3 | 只有 Token 校验，没有签发流程 | Plan/Step 未定义注册、刷新、吊销 | 无法注册设备、无法连接 | ADR-004：邀请码注册 + Access/Refresh + 吊销 |
| 4 | FLP 依赖 Google Play Services | 国内大量设备无 GMS | 定位模块可能不可用 | ADR-007：`LocationSource` 抽象 + 百度/高德兜底 |
| 5 | `tdengine-client` 不是官方 Node 连接器 | 官方为 `@tdengine/websocket`，`@tdengine/client` 已停止维护 | 依赖不可用 | ADR-003：MVP 只用 PostgreSQL |
| 6 | 没有订阅/推送模型 | Plan 只有 `query_location`/`location_push` | 不知道推给谁 | ADR-002 + protocol：`subscribe/unsubscribe` |

---

## 3. P1/P2 问题与处理

| # | 问题 | 严重度 | 处理 |
|---|---|---|---|
| 7 | 消息无 ID/ACK/幂等/顺序 | P1 | protocol 第 4、8 节；`clientMsgId` + 唯一约束 |
| 8 | 好友表 `UNIQUE(a,b)` 允许反向重复 | P1 | data-model：规范化 `(low, high)` |
| 9 | 无好友申请/拉黑状态机 | P1 | data-model 第 6 节；protocol 好友接口 |
| 10 | 历史查询无范围/分页/抽稀 | P1 | protocol 5.3；data-model 5.4 |
| 11 | 无保留期/删除策略 | P1 | data-model 第 9 节 |
| 12 | `tokens` 表存明文 Token | P1 | ADR-004：只存 Refresh 哈希 |
| 13 | JWT 放 Query 参数 | P1 | ADR-004/protocol：只放 `Authorization` Header |
| 14 | 客户端 `deviceId` 可伪造 | P1 | ADR-005/protocol：服务端从 Token 取身份 |
| 15 | Android 14 后台定位描述绝对化 | P1 | ADR-007：权限决策树 |
| 16 | 5 秒高精度常驻耗电 | P1 | ADR-007：自适应频率/距离过滤 |
| 17 | 百度 SDK 隐私初始化缺失 | P1 | ADR-007/Phase 0 S4：`setAgreePrivacy` |
| 18 | Cloudflare WebSocket 空闲超时 | P1 | ADR-008：30s 心跳 + 重连 |
| 19 | `nmap` 公网域名验证源站端口 | P1 | ADR-008：源站 `ss`/安全组/源 IP 扫描 |
| 20 | GitHub Actions `/opencode` 写权限风险 | P0（公开仓库） | 评审建议：限制触发者/只读/Environment；Phase 0 不启用 AI 写 Action |
| 21 | OpenCode 插件/Action 未验证 | P2 | Phase 0 S5：先验证再写入计划 |
| 22 | 文档路径 `Docs` vs `docs`、`:server` vs `/server` | P2 | 已统一：`guides/development.md` §2（Gradle 模块路径 vs 磁盘路径）；仓库使用大写 `Docs`，文档按类型分目录、命名统一 kebab-case |
| 23 | 双库双写一致性 | P1 | ADR-003：MVP 单库 + Repository 接口 |
| 24 | 容量未估算 | P2 | data-model 第 7 节 |
| 25 | 轨迹纠偏上传百度鹰眼与自建隐私冲突 | P2 | ADR-006：优先本地滤波；使用第三方需告知 |

---

## 4. 保留的优点

- 端到端主线清晰，没有过度设计业务层。
- 前台服务 + FLP 的方向正确。
- 提到 Polyline 单例复用，是真实性能要点。
- 离线缓存 + 批量补传 + 指数退避 + 心跳，覆盖移动网络关键问题。
- 风险表覆盖后台被杀、轨迹漂移、断连、隐私等主要方向。
- 分阶段路线图顺序合理。

---

## 5. 官方资料

- 百度地图 Android SDK 坐标转换：<https://lbs.baidu.com/docs/android?title=androidsdk%2Fguide%2Ftool%2Fcoordinate>
- Cloudflare WebSockets（含 idle timeout / keepalive 建议）：<https://developers.cloudflare.com/network/websockets/>
- TDengine Node.js 客户端：<https://docs.tdengine.com/cloud/programming/client-libraries/node/>

---

## 6. 残余风险

| 风险 | 说明 | 处理阶段 |
|---|---|---|
| 目标设备无 GMS | 需真机验证并接兜底 | Phase 0 S1 |
| 百度坐标精度 | GCJ-02 → WGS-84 为近似转换 | Phase 0 S2 |
| Cloudflare 边缘重启 | 连接会断，依赖重连 | Phase 0 S3 / Phase 4 |
| 百度隐私合规 | 国内上架要求 | Phase 0 S4 |
| OpenCode 工具链 | 包/Action 可能不存在 | Phase 0 S5 |
| 单机故障 | PostgreSQL/API 单点 | Phase 4 备份/恢复 |

---

## 7. 结论

原文档已归档为 `archive/plan-2026-10-17.md`、`archive/step-2026-10-17.md`，转为正式文档 `architecture/overview.md`、`guides/development.md`、`guides/deployment.md`；实现以 ADR、`specs/protocol.md`、`specs/data-model.md`、`planning/phase-0-tasks.md` 为准。  
后续对生成文档的评审见 [`reviews/2026-10-19-phase0-docs.md`](./2026-10-19-phase0-docs.md)。
Phase 0 的退出标准是“所有 P0 决策关闭 + 骨架/CI/风险验证完成”，不是“功能跑通”。
