# 仓位领航 · OKX AI Pilot

Java 17 / Spring Boot + MySQL + Flutter Android/iOS 的 AI 仓位管理 MVP。支持 **OKX 模拟盘／实盘切换、USDT 永续、合约账户模式、单向持仓、逐仓**，每次登录默认显示模拟盘。

## 当前功能

- 账户：用户名/密码注册登录、退出与过期会话处理；每个用户拥有独立的连接凭据、风控、交易记录和运行状态。密码使用 BCrypt，连接凭据使用 AES-256-GCM 加密。
- 环境与币种：设置页切换模拟盘／实盘，两套凭据、风险参数、记录和权益基准独立；从交易所列表搜索并勾选 1–5 个交易币种。
- 手机端：账户权益、持仓、自动交易启停、仅分析、减仓 50%、平仓、订单/决策记录、风险参数。
- 模型端：可配置 OpenAI 兼容的 `/chat/completions` 接口，须支持 `response_format: json_object`；模型生成结构化交易建议。
- 交易端：获取 OKX 行情、5 分钟 K 线、余额与持仓；开多/开空、减仓、平仓、调整本程序创建的止盈止损。调整止损只允许收紧。
- 风控：白名单、单笔/总敞口、持仓数量、1–3 倍杠杆、UTC 当日权益损失上限、保证金检查、价格/张数精度、过期账户数据检查。开仓强制附带止盈止损。
- 可靠性：先记录订单意图，再发交易请求；超时不重复下单；定时查单；部分成交/结果不明时暂停新增指令。服务重启默认暂停。

AI 不拥有下单密钥；Java 后端独立执行校验。手机关闭后，服务器仍可运行。实盘功能使用真实资金；本次未执行真实资金订单，不代表策略已经过实盘验证。

## 本地启动

### 1. MySQL

使用 MySQL 8.0 或 8.4，创建独立数据库和应用用户；密码请自行替换：

```sql
CREATE DATABASE okx_pilot CHARACTER SET utf8mb4;
CREATE USER 'okx_pilot'@'localhost' IDENTIFIED BY 'replace-with-a-random-password';
GRANT ALL PRIVILEGES ON okx_pilot.* TO 'okx_pilot'@'localhost';
```

表结构由 Flyway 自动创建。不要把已有业务库配置为本项目数据库。

也可使用 Docker Compose：在 `.env` 配置 `DB_PASSWORD`、额外设置 `MYSQL_ROOT_PASSWORD`，运行 `docker compose up -d`。该容器仅映射本机 3307 端口，需把 `DB_URL` 的端口改为 3307。

### 2. 配置服务端

复制 `.env.example` 为 `.env`，填写：

| 参数 | 用途 |
|---|---|
| `DB_URL / DB_USER / DB_PASSWORD` | MySQL 连接 |
| `PILOT_MASTER_KEY` | 32 随机字节的 Base64，用于加密各用户的连接凭据，需长期保管 |
| `PILOT_SESSION_HOURS` | 会话有效小时数，默认 168，范围 1–720 |
| `AI_ALLOWED_BASE_URLS` | 模型地址允许列表，默认包含 `https://nxaiapp.com/v1` |
| `SERVER_ADDRESS / SERVER_PORT` | 默认监听 `0.0.0.0:8080`，手机连接服务器实际 IP |

可在 PowerShell 7 使用 `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))` 生成加密主密钥。主密钥必须与数据库分别备份；丢失后无法解密已保存的凭据。OKX 对应环境的账户请设为合约模式、单向持仓，并保留模拟 USDT 余额。凭据只需要读取/交易权限。`.env` 已被忽略，不要把它提交或发到聊天。

```powershell
# 在项目根目录运行；脚本读取 .env，不会执行其中的代码。
./scripts/start-backend.ps1
```

Linux/macOS 将同名变量导出到进程环境后，在 `backend` 运行 `mvn spring-boot:run`。Spring Boot 本身不会自动读取根目录 `.env`。

模型连接按用户加密保存。新用户默认使用 `https://nxaiapp.com/v1` 和 `gpt-6-luna`，在账户连接设置中填写中转站的 `NX_API_KEY` 实际值；后端自动追加 `/chat/completions` 并使用 Bearer 认证。已有用户需在连接设置中更新地址和模型。若覆盖了 `AI_ALLOWED_BASE_URLS`，需包含该中转站地址。服务端需要配置 `PILOT_MASTER_KEY` 才能保存连接密钥。示例中的 `max_tokens:16` 仅适用于简单问候，不用于完整交易决策。

不配置 OKX/AI 也可以启动控制台；此时会明确显示“未配置”，不会显示虚构资产或发出订单。数据库是启动必填项；未配置主密钥时仍可注册登录，但不能保存连接。

### 3. Flutter

```text
cd mobile
flutter pub get
flutter run
```

App 默认直连 `http://47.76.193.239:8080`，首次使用点击“没有账号？注册”，输入用户名、密码及确认密码。用户名为 3–32 位英文字母、数字或下划线，不区分大小写；密码为 8–64 字符且 UTF-8 不超过 72 字节。登录后在“设置 → 我的账户连接”保存模拟盘与模型凭据。

登录页可展开“服务器设置”修改 IP:端口，也可以用 `flutter run --dart-define=PILOT_URL=http://10.0.2.2:8080` 覆盖打包默认值。Android/iOS 工程已允许指定的 HTTP 地址；公网 HTTP 会明文传输密码、会话和连接凭据，正式使用应切换 HTTPS。云服务器需允许 TCP 8080 入站；MySQL 不必向公网开放。

当前会话仅保存在 App 内存中，完全退出 App 后需要重新登录。点击“退出登录”立即撤销当前会话，其他设备会话不受影响。退出不会暂停后台交易。

Android 调试包：`flutter build apk --debug`。

iOS：在 macOS 安装 Xcode，配置签名团队，使用 `flutter run` 或 `flutter build ipa`。本项目在 Windows 上无法签名或验证 iOS 安装包。

## 推荐验收顺序

1. 配置 MySQL、加密主密钥并启动后端，确认手机可以注册、退出并重新登录。
2. 配置模拟盘凭据，点击“同步账户”，核对权益和持仓。
3. 配置模型，点击“仅分析一次”，核对结构化建议与参数。
4. 使用小额度开启模拟交易，检查订单接受、成交、保护单状态，再测试减仓、平仓与暂停。
5. 人为模拟断网/重启，确认未知订单会查单且不会被重复提交。

交易 API 按 [OKX 官方文档](https://www.okx.com/docs-v5/zh/) 实现，模拟盘请求携带 `x-simulated-trading: 1`，实盘请求不携带该标记。真实外部联调仍需要用户自己的模拟盘和模型凭据。

## 测试

```text
cd backend
mvn test
mvn package

cd ../mobile
flutter analyze
flutter test
flutter build apk --debug
```

Java 测试覆盖注册登录、会话过期及注销、跨用户隔离、凭据加密与允许地址校验，以及数量换算、减仓方向、强制保护单、风控、签名、鉴权、持久化、超时不重试、暂停竞态、部分成交、执行锁。默认使用 H2 的 MySQL 兼容模式；额外提供真实 MySQL 冒烟测试：设置 `TEST_MYSQL_URL / TEST_MYSQL_USER / TEST_MYSQL_PASSWORD` 后运行 `mvn test`，必须指向专用测试库。

## 第一版边界

- 支持多用户，仍按单实例部署；每个用户可分别绑定一个独立 OKX 模拟账户和实盘账户。同一 API Key 不可绑定两个用户，有交易记录后不可切换 API Key。不同 API Key 仍可能属于同一个交易所账户，部署方须避免共用账户。暂不支持双向/全仓、加仓、自动反手、同一环境多账户或分布式高可用。
- 使用 REST 轮询（默认每 15 秒监控），没有逐笔实时行情或高频交易能力。模型决策默认间隔 5 分钟。
- 当日权益基准是当天程序首次观察的 USDT 权益；充值/划转/提现会影响损失计算。日损上限禁止新增风险，不保证最大亏损严格止于该值，也不自动强平。
- 保护单缺失、部分成交或查询无法确认时，暂停并显示异常，不声称仓位已受保护；必要时在 OKX 人工处理。
- 自动修改只支持本程序创建、能唯一识别的一组保护单。人工平仓后的残留保护单须在 OKX 清理后再开新仓，避免旧保护单影响新仓位。
- UNKNOWN 状态保留并阻止新增订单；若交易所长时间无法返回结果，需要人工核对，不提供随意清除未知状态的按钮。
- 暂停不取消已经提交的请求、订单或保护单；手机断开不暂停服务器。服务重启后需重新开启。
- 凭据在 App 设置页提交，数据库只保存加密值，读取接口不返回密钥；没有密码找回/修改、账号删除、推送通知、回测或模型收益比较。

设计：[docs/architecture.md](docs/architecture.md)；接口：[docs/api.md](docs/api.md)。

本次实际验证范围见 [验收记录](docs/acceptance.md)，界面见 [Android 总览截图](docs/screenshots/overview.png)。

## 从旧版本升级

先暂停旧服务并备份数据库，替换 Java 包后启动，Flyway 自动执行 V2。V1 的共享设置、订单和审计表保留但不分配给新用户，避免注册者继承他人交易权限；新用户从空记录开始。不要在旧账户仍有未决订单或仓位时直接重绑，需要先人工核对处理，或另用全新的模拟账户。

旧 `PILOT_OPERATOR_TOKEN` 与全局 OKX/AI 密钥已不再用于认证或交易，新版手机必须配套新版后端。`GET /api/health` 返回 `auth: username-password` 时表示后端支持此登录方式。反向代理场景下登录限流默认按代理来源 IP 汇总；当前不盲目信任客户端提供的转发头。


## 实盘切换与币种选择

1. 在“设置”顶部选择“实盘”并确认；App 先暂停当前环境，再读取目标环境。已有仓位、订单和保护单保留，后台仍按各自环境继续对账。暂停不会撤销已经提交的请求。
2. 在实盘的“我的账户连接”单独填写实盘 OKX 凭据和模型配置。模拟盘密钥不自动复制到实盘。密钥框显示“已保存”时无需重复填写。
3. 在风险参数中的“选择交易币种”打开列表，可搜索和勾选 1–5 个当前可交易 USDT 永续币种，点击“确定”后再“保存风险参数”。获取列表失败时保留原选择并允许重试，不以虚构列表代替交易所数据。
4. 在总览确认“实盘”标记，先同步账户，再明确确认开启实盘自动交易；切换环境本身不会调用启动接口。另一设备已启动的目标环境会如实显示运行中。

服务升级自动执行 V3，原 V2 用户数据仍归模拟盘，新增 `live_user_*` 表保存实盘数据。既有用户自动补充实盘执行锁。环境由每次请求的 `X-Trading-Environment` 指定，缺省为 DEMO；不同手机会话的显示环境独立，退出或关闭 App 不会停止后台交易。币种列表要求服务器可访问 OKX 官方接口。
