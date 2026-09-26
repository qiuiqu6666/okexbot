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
| GET | /api/news | 根据当前环境所选币种返回真实资讯与来源状态，无可用数据时返回 usable=false |
| GET | /api/instruments | 当前环境可交易的线性 USDT 永续合约 ID 列表；无需 OKX 私有密钥，失败返回错误 |
| GET | /api/status | 运行状态、凭据是否配置、最近账户快照、风控参数 |
| POST | /api/sync | 主动从 OKX 刷新账户和持仓 |
| PUT | /api/settings | 暂停时修改风控设置 |
| PUT | /api/risk-policy | 暂停时修改硬性风险约束，见 [风险约束说明](risk-controls.md) |
| POST | /api/risk/reset | `{"confirm":true}` 人工复核解除熔断，要求空仓、无挂单且当日日损未超限 |
| POST | /api/start | 校验账户、未完成订单及保护单后启用自动交易 |
| POST | /api/pause | 暂停后续自动指令，保留保护单 |
| POST | /api/preview | 模型分析并风控校验，记录结果，不下单 |
| POST | /api/reconcile | 查询未完成/未知订单，更新记录 |
| POST | /api/positions/{instrument}/reduce | 按最新仓位减仓 50%，只减仓市价单 |
| POST | /api/positions/{instrument}/close | 按最新仓位平仓，只减仓市价单 |
| GET | /api/orders | 最近 100 条订单意图及交易所状态 |
| GET | /api/events | 最近 100 条操作/分析事件 |
| GET | /api/analysis | 当前用户及环境最近 100 条 AI 分析、拦截和执行异常记录；完整说明在 payload 中，不受其他日志挤占 |

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


## 交易环境（V3）

已认证的业务接口接受 `X-Trading-Environment: DEMO` 或 `LIVE`，缺省 DEMO，非法值拒绝。`/connections`、`/settings`、`/orders`、`/events`、`/status` 与交易操作均使用指定环境，登录会话本身不切换全局环境。两个环境的连接凭据（包括模型）、风控、订单、执行锁及权益基准独立存储。实盘密文额外绑定 LIVE 环境，不能复制模拟盘密文冒充实盘配置。

`POST /api/start` 在 LIVE 环境必须同时提交 `{"confirmLive":true}`，否则拒绝；DEMO 仍兼容空请求体。App 切换时先对原环境调用 `/pause`，再请求目标 `/status`，不会自动调用 `/start`。健康接口提供 `tradingEnvironments: ["DEMO","LIVE"]`；健康响应中的 environment 是默认环境，不表示所有用户的运行环境。

`GET /api/instruments` 示例返回 `["BTC-USDT-SWAP","ETH-USDT-SWAP"]`，实际结果来自 OKX `GET /api/v5/public/instruments?instType=SWAP`，筛选 state=live、ctType=linear、settleCcy=USDT、面值币种与交易币种一致的合约。设置仍接受 1–5 个不重复 ID，执行交易前再次验证合约状态、模式及精度。


## 资讯接口与证据

`GET /api/news` 需要登录，环境由 X-Trading-Environment 指定，按该环境的 settings.instruments 筛选。响应字段：

- checkedAt：本次评估时间；usable：是否有至少一条当前相关且有效的文章；message：当前证据状态说明。
- sources：source、url、official、status（OK / UNAVAILABLE）、lastSuccess、message。状态反映获取成功，不等于事实确认或必有近期文章。
- articles：id、source、url、title、excerpt、publishedAt、fetchedAt、category（MACRO / REGULATION / SECURITY / ASSET）、symbols、marketWide。

列表最多 20 条。模型收到相同结构，开仓理由必须用 `[N0123456789abcdef]` 格式引用真实文章 ID。后端检查 ID 存在、当前币种相关性、来源状态及 48 小时发布／15 分钟采集窗口；不合格则拒绝指令。任何动作中的伪造 ID 都被拒绝。

`/events` 新增 NEWS_EVIDENCE 类型，payload 为该轮资讯快照；DECISION / PREVIEW 的 payload 增加 news 字段，供复核引用。实时资讯为只读外部数据，不允许用户传入任意抓取 URL；XML 禁用 DTD、外部实体，响应上限 1 MiB，文章链接限发布者 HTTPS 域名。
