# REST API

健康检查、注册和登录无需认证；其他接口要求 `Authorization: Bearer <登录返回的 token>`。所有业务操作按登录用户隔离，不接受用户 ID 指定访问他人数据。接口返回 JSON，不返回密码或交易/模型密钥。鉴权失败为 401，业务阻止为 409，格式错误为 400，登录/注册限流为 429。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/health | 服务健康、DEMO 标记 |
| POST | /api/auth/register | 用户名密码注册，返回会话；同一 IP 每小时最多 5 次尝试 |
| POST | /api/auth/login | 用户名密码登录；同一 IP 每分钟 40 次、同一用户名 5 分钟 10 次尝试 |
| GET | /api/auth/me | 当前用户 ID 与用户名 |
| POST | /api/auth/logout | 撤销当前会话 |
| GET | /api/connections | 当前用户连接状态、模型地址/名称、允许地址；不返回密钥 |
| PUT | /api/connections | 暂停时更新自己的连接；空值保留已有字段 |
| GET | /api/status | 运行状态、凭据是否配置、最近账户快照、风控参数 |
| POST | /api/sync | 主动从 OKX 刷新账户和持仓 |
| PUT | /api/settings | 暂停时修改风控设置 |
| POST | /api/start | 校验账户、未完成订单及保护单后启用自动交易 |
| POST | /api/pause | 暂停后续自动指令，保留保护单 |
| POST | /api/preview | 模型分析并风控校验，记录结果，不下单 |
| POST | /api/reconcile | 查询未完成/未知订单，更新记录 |
| POST | /api/positions/{instrument}/reduce | 按最新仓位减仓 50%，只减仓市价单 |
| POST | /api/positions/{instrument}/close | 按最新仓位平仓，只减仓市价单 |
| GET | /api/orders | 最近 100 条订单意图及交易所状态 |
| GET | /api/events | 最近 100 条操作/分析事件 |

设置请求示例：

```json
{
  "instruments": ["BTC-USDT-SWAP", "ETH-USDT-SWAP"],
  "maxOrderUsdt": 100,
  "maxExposureUsdt": 300,
  "maxDailyLossPct": 3,
  "maxPositions": 2,
  "leverage": 1,
  "intervalSeconds": 300
}
```

下单接口成功仅说明请求被接受，应查询 `/api/orders` 或对账，不代表已成交。网络异常时不要自行重放写请求。实际执行前始终重新获取持仓，检查未完成订单；已存在未知或未完成订单时拒绝新的指令。

建议单实例部署在 HTTPS 反向代理之后。不要给此接口开放无鉴权代理或在 URL 查询参数中传访问令牌。

注册/登录请求：`{"username":"alice","password":"your-password"}`。成功返回 `{"token":"随机会话令牌","expiresAt":"UTC 时间","user":{"id":1,"username":"alice"}}`。密码 BCrypt 哈希、会话 SHA-256 摘要落库。默认会话 7 天；过期/注销后返回 401。

连接写入字段：`okxKey`、`okxSecret`、`okxPassphrase`、`aiBaseUrl`、`aiKey`、`aiModel`。AI 地址必须出现在管理员配置的 `AI_ALLOWED_BASE_URLS` 中。连接凭据采用 AES-256-GCM 加密并绑定用户 ID。用户自行填写的地址不会扩展服务端允许列表。
