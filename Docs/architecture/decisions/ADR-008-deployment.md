# ADR-008：部署与隧道

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 五、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段四
- 操作手册：[`../deployment.md`](../../guides/deployment.md)

## 背景

方案要求：

- 服务器不直接暴露公网端口；
- Android 通过 WSS 连接；
- 使用 Cloudflare Named Tunnel；
- 位置数据不能被中间人窃取；
- 单机可运维、可备份、可回滚。

现有文档的 `nmap` 验证方式不成立：扫描公网域名只会命中 Cloudflare 边缘，无法证明源站端口是否暴露。

## 决策

采用 **Docker Compose + Cloudflare Tunnel + 仅回环监听** 的单机部署。

### 拓扑

```text
Android ── WSS(443) ──> Cloudflare Edge
                              │
                              │ Tunnel (outbound)
                              ▼
                     cloudflared (container)
                              │
                              │ http://api:9099 (compose network)
                              ▼
                     api (Fastify + ws)
                              │
                              ▼
                     postgres (container, no host port)
```

### 端口与网络

- `api` 只在 Compose 网络内暴露 `9099`；
- 本地调试可映射 `127.0.0.1:9099:9099`，不映射 `0.0.0.0`；
- `postgres` 不发布宿主机端口；
- 云主机安全组只允许 SSH（限制来源 IP）和出站；
- cloudflared 使用 Tunnel Token，不把 Token 写入镜像。

### TLS

- 公网侧由 Cloudflare 终止 TLS，Android 只连接 `wss://rtls.example.com`；
- cloudflared → api 在私有网络内使用 HTTP；
- 本地开发允许 `ws://10.0.2.2:9099`，仅 debug 构建；
- release 构建禁止明文连接，使用 `network_security_config` 限制。

### 心跳与重连

- 客户端每 30 秒应用层 `ping`；
- Cloudflare 对空闲 WebSocket 有时限（社区实测约 100 秒，非企业版不可调）；
- 边缘节点发布/重启会断开连接，客户端必须指数退避重连并重新订阅；
- 服务端 90 秒无消息关闭连接。

### 配置与密钥

| 配置 | 来源 |
|---|---|
| `DATABASE_URL` | 环境变量 / Secret |
| `JWT_SECRET` | 环境变量 / Secret，≥ 256 位 |
| `CLOUDFLARE_TUNNEL_TOKEN` | 环境变量 / Secret |
| `PUBLIC_BASE_URL` | 环境变量 |
| 百度 AK | `local.properties` / CI Secret + Manifest 占位符 |

- 仓库只提交 `.env.example`，不提交真实值。
- 日志脱敏：Token、Refresh、邀请码、完整坐标不进入 INFO 日志。

### 备份与恢复

- PostgreSQL 每日 `pg_dump`，保留 7 天日备 + 4 周周备；
- 每月做一次恢复演练；
- 迁移前自动备份；
- 备份文件加密并异地保存。

### 可观测性

- `/healthz`：进程 + DB 连通性；
- `/metrics`：仅内网/本机可访问；
- 结构化日志（pino JSON）；
- 关键指标：连接数、消息速率、推送延迟、DB 写入延迟、队列深度、认证失败、限流次数；
- 告警：API 5xx、DB 连接失败、cloudflared 断开、磁盘使用率 > 80%。

### 部署流程

1. CI 构建镜像并打不可变 tag（commit SHA）；
2. 目标机 `docker compose pull`；
3. 运行迁移（失败则停止）；
4. `docker compose up -d`；
5. 健康检查通过后切流量；
6. 保留上一版本镜像用于回滚。

### 验证源站未暴露

不要 `nmap` 公网域名。正确做法：

```bash
# 源站
ss -lntp | grep 9099        # 应只看到 127.0.0.1:9099 或容器内网
docker compose ps           # postgres 不应有 0.0.0.0 端口映射
# 云主机安全组：入站仅 SSH（限制来源），无 9099/5432
# 从外部网络扫描源站公网 IP（如果存在）
```

## 理由

- Cloudflare Tunnel 不需要公网 IP，源站零入站端口。
- Docker Compose 足够单机 MVP，避免 Kubernetes 过度设计。
- 迁移、备份、健康检查都可以脚本化。
- 心跳 30 秒可以覆盖 Cloudflare 约 100 秒的空闲超时。

## 后果

### 正面

- 源站不暴露，攻击面小。
- TLS 由 Cloudflare 管理，证书自动续期。
- 单机部署成本低，回滚简单。

### 负面 / 需要注意

- 依赖 Cloudflare；隧道故障时服务不可用。
- 免费/非企业版 WebSocket 空闲超时不可调，必须保活。
- Cloudflare 边缘重启会断开连接，客户端必须能快速重连。
- 单机是单点故障；需要备份和恢复演练。
- 如果未来多实例，需要 Redis/NATS 做跨实例广播与 presence。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 云服务器公网 IP + Nginx | 可选 | 需自行处理 TLS、防火墙、端口暴露 |
| Cloudflare Tunnel + Kubernetes | 暂不采用 | MVP 运维过重 |
| Tailscale/ZeroTier | 不采用 | 需要每台手机安装 VPN，用户体验差 |
| 直连 IP + 自签证书 | 不采用 | Android 信任与证书轮换复杂 |

## 验证方式

- 公网只开放 443（Cloudflare 边缘）；源站无 9099/5432 入站。
- `wscat` 通过 `wss://` 连接，空闲 10 分钟不断开。
- 手动重启 cloudflared，客户端在 10 秒内重连并恢复订阅。
- 从备份恢复到一个空数据库，数据量与最新位置一致。
