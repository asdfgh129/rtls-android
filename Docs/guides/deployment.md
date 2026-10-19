# RTLS 部署与运维手册

- 状态：现行（Current）
- 日期：2026-10-19
- 决策依据：[ADR-008](../architecture/decisions/ADR-008-deployment.md)
- 相关：[`architecture/overview.md`](../architecture/overview.md)、[`guides/development.md`](./development.md)

本文是**可执行的操作手册**：拓扑、配置、首次部署、备份恢复、验证与回滚。
为什么这样选（Cloudflare Tunnel、单机 Compose）见 ADR-008，本文不重复论证。

> 所有示例域名/密钥均为占位符；命令在本机或目标机上逐条执行。

---

## 1. 部署拓扑

```text
Android ── WSS(443) ──> Cloudflare Edge
                              │ Tunnel（出站）
                              ▼
                     cloudflared（容器）
                              │ http://api:9099（compose 网络）
                              ▼
                     api（Fastify + ws）
                              ▼
                     postgres（容器，不发布宿主机端口）
```

| 组件 | 端口 | 暴露范围 |
|---|---|---|
| Cloudflare Edge | 443 | 公网 |
| `cloudflared` | 无 | 仅出站 |
| `api` | 9099 | 仅 Compose 网络（本地调试可映射 `127.0.0.1:9099`） |
| `postgres` | 5432 | 仅 Compose 网络 |

---

## 2. 前置条件

- 一个托管在 Cloudflare 的域名；
- 目标机：Docker + Compose、可出站访问 Cloudflare；
- 云主机安全组：入站仅 SSH（限制来源 IP），**不放行 9099 / 5432**；
- CI 已能构建镜像并打不可变 tag（commit SHA）；
- `pg_dump` 可用，备份存储位置已就绪。

---

## 3. 配置与密钥

| 配置 | 来源 | 说明 |
|---|---|---|
| `DATABASE_URL` | 环境变量 / Secret | Compose 内网地址 |
| `JWT_SECRET` | 环境变量 / Secret | ≥ 256 位 |
| `CLOUDFLARE_TUNNEL_TOKEN` | 环境变量 / Secret | 不写入镜像 |
| `PUBLIC_BASE_URL` | 环境变量 | 公网 `https://rtls.example.com` |
| `LOCATION_RETENTION_DAYS` | 环境变量 | 默认 90 |
| 百度 AK | `local.properties` / CI Secret | 经 Manifest 占位符注入，不进仓库 |

规则：

- 仓库只提交 `.env.example`；
- 日志脱敏：Token、Refresh、邀请码、完整坐标不进 INFO 日志。

---

## 4. 首次部署

```bash
# 1. 拉取镜像（不可变 tag）
docker compose pull

# 2. 迁移（失败则停止，不继续启动）
cd server && npm run migrate up

# 3. 启动
cd .. && docker compose up -d

# 4. 健康检查
curl -fsS http://127.0.0.1:9099/healthz
# 期望：{"status":"ok","db":"ok"}

# 5. WebSocket 连通性
wscat -c wss://rtls.example.com/v1/ws -H "Authorization: Bearer <token>"
```

`/healthz` 与 `/metrics` 只在内网/本机暴露，不通过 Tunnel 发布。

---

## 5. Cloudflare Tunnel

1. Cloudflare Dashboard → **Networking → Tunnels** → Create Tunnel（如 `rtls-websocket`）；
2. 选择目标机系统，复制安装命令并执行；
3. 隧道状态变为 **Healthy**；
4. 添加 **Public Hostname**：`rtls.example.com` → `http://api:9099`；
5. 确认 WebSocket 已启用（Cloudflare 默认支持；免费版空闲超时不可调，必须保留 30s 心跳）。

隧道 Token 通过环境变量注入 `cloudflared` 容器，不进镜像与仓库。

---

## 6. Android 发布配置

- `release` build type：`wss://rtls.example.com`；
- `network_security_config` 禁止明文流量；
- AK 绑定 **包名 + debug/release 两套 SHA1**，通过 Manifest 占位符注入；
- 发布前完成百度隐私初始化流程（用户同意 → `setAgreePrivacy(true)` → `initialize()`）；
- 隐私政策与第三方 SDK 清单随包提供（见 `roadmap.md` Phase 6）。

---

## 7. 备份与恢复

| 项 | 策略 |
|---|---|
| 频率 | 每日 `pg_dump` |
| 保留 | 7 天日备 + 4 周周备 |
| 加密 | 备份文件加密并异地保存 |
| 迁移前 | 自动备份，失败则中止 |
| 演练 | 每月一次恢复演练 |

恢复验证要点：恢复到空库后，`locations` 行数与 `device_latest` 与生产一致。

---

## 8. 监控与告警

- `/healthz`：进程 + DB 连通性；
- `/metrics`：Prometheus，仅内网；
- 关键指标：连接数、消息速率、推送延迟、DB 写入/查询延迟、队列深度、认证失败、限流次数；
- 告警：API 5xx、DB 连接失败、cloudflared 断开、磁盘使用率 > 80%；
- 日志：pino JSON，结构化。

---

## 9. 验证源站未暴露

**不要**用 `nmap` 扫公网域名——那只能命中 Cloudflare 边缘。

```bash
# 源站
ss -lntp | grep 9099        # 只应看到 127.0.0.1:9099 或容器内网
docker compose ps           # postgres 不应有 0.0.0.0 端口映射

# 云主机安全组：入站仅 SSH（限制来源），无 9099 / 5432
# 若源站有公网 IP：从外部网络扫描该 IP
```

---

## 10. 回滚 Runbook

1. 确认故障版本镜像 tag（commit SHA）；
2. `docker compose down`；
3. 用上一个可用 tag 启动（若涉及 DB 迁移回滚，先执行 `npm run migrate down`）；
4. 恢复数据（如需）：用最近一次 `pg_dump` 恢复到空库；
5. `curl /healthz` + `wscat` 验证；
6. 记录故障时间、影响范围、根因。

目标：10 分钟内恢复（见 `roadmap.md` Phase 6）。

---

## 11. 发布检查清单

- [ ] 源站无 9099 / 5432 入站；
- [ ] 公网只开放 443；
- [ ] WSS 空闲 10 分钟不断开；
- [ ] 未授权 / 越权 / 重放全部被拒；
- [ ] Token 只在 Header，日志无 Token；
- [ ] 备份可恢复（已演练）；
- [ ] 百度隐私流程可演示，AK 不进仓库；
- [ ] 保留上一版本镜像，回滚路径已验证。

---

## 12. 相关文档

- [ADR-008：部署与隧道](../architecture/decisions/ADR-008-deployment.md)
- [`architecture/overview.md`](../architecture/overview.md)
- [`guides/development.md`](./development.md)
- [`roadmap.md`](../planning/roadmap.md) Phase 4 / Phase 6
