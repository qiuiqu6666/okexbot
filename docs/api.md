# REST API

除 `GET /api/health` 外，所有接口要求 `Authorization: Bearer <PILOT_OPERATOR_TOKEN>`。接口返回 JSON，不返回交易或模型密钥。鉴权失败为 401，业务阻止为 409，格式错误为 400。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/health | 服务健康、DEMO 标记 |
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
