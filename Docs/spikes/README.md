# 风险 Spike 记录

本目录记录 Phase 0 的技术风险验证（文件命名 `SN-<topic>.md`，目录与命名规范见 [`../README.md`](../README.md)）。每个 spike 用独立文件，例如：

- `S1-gms-availability.md`
- `S2-baidu-coordinate.md`
- `S3-cloudflare-websocket.md`
- `S4-baidu-privacy.md`
- `S5-opencode-tooling.md`

## 结论汇总

| Spike | 问题 | 结论 | 影响 |
|---|---|---|---|
| S1 | 目标设备是否有 GMS / FLP 可用 | 待验证 | 决定是否接百度/高德定位兜底 |
| S2 | WGS-84 → BD-09 转换是否正确 | 待验证 | 地图是否偏移 |
| S3 | Cloudflare WebSocket 30s 心跳是否足够 | 待验证 | 实时连接稳定性 |
| S4 | 百度 SDK 隐私初始化流程 | 待验证 | 合规与 SDK 可用性 |
| S5 | OpenCode 工具是否存在/维护 | 待验证 | 初始实施步骤（已归档）中的工具链是否成立 |

## 模板

```markdown
# SX：标题

- 日期：
- 负责人：
- 时间盒：
- 环境/设备：

## 问题

## 方法

## 结果

## 结论

## 对计划的影响

## 证据

- 日志/截图/链接
```
