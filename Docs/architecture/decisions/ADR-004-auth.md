# ADR-004：认证与令牌生命周期

- 状态：Accepted
- 日期：2026-10-19
- 关联：[初始方案（归档）](../../archive/plan-2026-10-17.md) 2.3、4.2、[初始步骤（归档）](../../archive/step-2026-10-17.md) 阶段四

## 背景

现有文档要求“连接时携带 JWT”，但没有定义：

- 设备如何注册；
- Token 谁签发、有效期多久；
- 刷新、吊销、换机如何处理；
- Token 存哪里、怎么传给服务器；
- `tokens` 表为什么保存明文 Token。

位置数据属于敏感个人信息，认证必须闭环。

## 决策

采用 **邀请码注册 + Access JWT + 不透明 Refresh Token**。

### 注册流程（MVP 邀请制）

1. 运维通过 CLI 创建一次性邀请码（只存哈希）。
2. App 首次启动：`POST /v1/auth/register`，提交邀请码、设备名、平台、App 版本。
3. 服务端校验邀请码（未使用、未过期），创建 `users` 与 `devices`，返回：
   - `userId`
   - `deviceId`
   - `accessToken`
   - `refreshToken`
   - 过期时间
4. 邀请码标记为已使用（单次有效）。

### Token 设计

| 项 | 设计 |
|---|---|
| Access Token | JWT，HS256，15 分钟 |
| Refresh Token | 32 字节随机值，Base64url，30 天 |
| Refresh 存储 | 只存 SHA-256 哈希，不存明文 |
| 传输方式 | WebSocket upgrade / REST 使用 `Authorization: Bearer <access>` |
| Query 参数 | **禁止**携带 Token |
| Android 存储 | Access 仅内存；Refresh 存 `EncryptedSharedPreferences` / Keystore |
| 轮换 | 每次 refresh 生成新 Refresh，旧的立即失效 |
| 重放检测 | 已失效 Refresh 被再次使用 → 撤销同一 `family_id` 下全部 Token |
| 吊销 | 登出、换机、丢失设备时撤销 Refresh 并递增 `devices.token_version` |

### JWT Claims

```json
{
  "iss": "rtls-server",
  "aud": "rtls-android",
  "sub": "<deviceId>",
  "uid": "<userId>",
  "jti": "<uuid>",
  "ver": 1,
  "iat": 1720000000,
  "exp": 1720000900
}
```

服务端校验：

- 签名、`iss`、`aud`、`exp`；
- `sub` 对应设备存在且 `status = 'active'`；
- `ver` 与 `devices.token_version` 一致；
- 每次连接时校验，不信任消息体里的 `deviceId`。

### 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/auth/register` | 邀请码注册，签发 Token |
| POST | `/v1/auth/refresh` | 轮换 Refresh，签发新 Access |
| POST | `/v1/auth/logout` | 撤销当前 Refresh |
| POST | `/v1/auth/revoke-all` | 撤销该设备全部 Token |
| GET | `/v1/me` | 当前用户/设备信息 |

### 安全要求

- 注册、刷新、登出接口按 IP + 邀请码做限流。
- 登录失败不返回“邀请码不存在/已使用”的细分信息，统一错误。
- 日志中禁止出现完整 Token、Refresh、邀请码。
- 生产环境只允许 WSS；本地开发允许 `ws://`，但必须通过 debug 构建开关。
- JWT 密钥至少 256 位，来自环境变量/Secret，不写入仓库。

## 理由

- 邀请制适合小范围 RTLS 部署，避免开放注册被滥用。
- Access JWT 无状态，WS upgrade 校验成本低。
- Refresh 不透明且哈希存储，泄露数据库也无法直接使用。
- 轮换 + family 重放检测覆盖常见 Token 盗用场景。

## 后果

### 正面

- 认证流程完整，可从零启动一个新设备。
- Token 泄露后的影响窗口可控（Access 15 分钟）。
- 换机/丢失设备可远程吊销。

### 负面 / 需要注意

- 需要实现 Refresh 轮换与 family 撤销逻辑，测试要覆盖并发刷新。
- Access Token 撤销最多有 15 分钟延迟；若需要即时失效，可增加 Redis 黑名单。
- 邀请码需要运维入口（CLI 或管理脚本）。

## 备选方案

| 方案 | 结论 | 原因 |
|---|---|---|
| 设备密钥对 + 挑战签名 | 后续可评估 | 安全性更高，但 MVP 增加 Keystore/注册复杂度 |
| mTLS | 不采用 | Cloudflare Tunnel + Android 客户端证书运维成本高 |
| OAuth2 第三方登录 | 不采用 | RTLS 是私有部署，不需要社交登录 |
| 长期 JWT（不过期） | 不采用 | 泄露后无法收敛 |

## 验证方式

- 单元测试：JWT 过期、错误签名、错误 `aud`、`token_version` 不匹配。
- 集成测试：注册 → 连接 → 刷新 → 旧 Refresh 失效 → 重放触发 family 撤销。
- 安全测试：Query 参数带 Token 被拒绝；日志中无 Token。
