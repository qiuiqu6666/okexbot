# 仓位领航 · OKX AI Pilot

Java 17 / Spring Boot + MySQL + Flutter Android/iOS 的个人 AI 仓位管理 MVP。当前仅支持 **OKX 模拟盘、USDT 永续、合约账户模式、单向持仓、逐仓**，没有实盘开关。

## 当前功能

- 手机端：账户权益、持仓、自动交易启停、仅分析、减仓 50%、平仓、订单/决策记录、风险参数。
- 模型端：可配置 OpenAI 兼容的 `/chat/completions` 接口，须支持 `response_format: json_object`；模型生成结构化交易建议。
- 交易端：获取 OKX 行情、5 分钟 K 线、余额与持仓；开多/开空、减仓、平仓、调整本程序创建的止盈止损。调整止损只允许收紧。
- 风控：白名单、单笔/总敞口、持仓数量、1–3 倍杠杆、UTC 当日权益损失上限、保证金检查、价格/张数精度、过期账户数据检查。开仓强制附带止盈止损。
- 可靠性：先记录订单意图，再发交易请求；超时不重复下单；定时查单；部分成交/结果不明时暂停新增指令。服务重启默认暂停。

AI 不拥有下单密钥；Java 后端独立执行校验。手机关闭后，服务器仍可运行。程序不是盈利策略或已验证的实盘系统。

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
| `PILOT_OPERATOR_TOKEN` | 手机访问服务端的随机令牌，至少 32 字符 |
| `OKX_API_KEY / OKX_SECRET_KEY / OKX_PASSPHRASE` | 在 OKX **模拟交易**里创建的 API 凭据 |
| `AI_BASE_URL` | 模型接口地址，例如 `https://provider.example/v1` |
| `AI_API_KEY / AI_MODEL` | 模型服务密钥和真实模型 ID |
| `SERVER_ADDRESS / SERVER_PORT` | 默认监听 `127.0.0.1:8080` |

可在本地 PowerShell 用 `[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')` 生成访问令牌。OKX 模拟账户请设为合约模式、单向持仓，并保留模拟 USDT 余额。凭据只需要读取/交易权限。`.env` 已被忽略，不要把它提交或发到聊天。

```powershell
# 在项目根目录运行；脚本读取 .env，不会执行其中的代码。
./scripts/start-backend.ps1
```

Linux/macOS 将同名变量导出到进程环境后，在 `backend` 运行 `mvn spring-boot:run`。Spring Boot 本身不会自动读取根目录 `.env`。

不配置 OKX/AI 也可以启动控制台；此时会明确显示“未配置”，不会显示虚构资产或发出订单。数据库与访问令牌是启动必填项。

### 3. Flutter

```text
cd mobile
flutter pub get
flutter run
```

在 App 输入后端地址及 `PILOT_OPERATOR_TOKEN`。Android 标准模拟器访问宿主机可用 `http://10.0.2.2:8080`（仅调试版允许 HTTP）。真机通过 HTTPS 反向代理连接后端；正式 App 不允许明文 HTTP。默认绑定本机的后端不会直接对外暴露。

Android 调试包：`flutter build apk --debug`。

iOS：在 macOS 安装 Xcode，配置签名团队，使用 `flutter run` 或 `flutter build ipa`。本项目在 Windows 上无法签名或验证 iOS 安装包。

## 推荐验收顺序

1. 配置 MySQL、访问令牌，确认手机可连接控制台。
2. 配置模拟盘凭据，点击“同步账户”，核对权益和持仓。
3. 配置模型，点击“仅分析一次”，核对结构化建议与参数。
4. 使用小额度开启模拟交易，检查订单接受、成交、保护单状态，再测试减仓、平仓与暂停。
5. 人为模拟断网/重启，确认未知订单会查单且不会被重复提交。

交易 API 按 [OKX 官方文档](https://www.okx.com/docs-v5/zh/) 实现，模拟盘请求固定携带 `x-simulated-trading: 1`。真实外部联调仍需要用户自己的模拟盘和模型凭据。

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

Java 测试覆盖数量换算、减仓方向、强制保护单、风控、签名、鉴权、持久化、超时不重试、暂停竞态、部分成交、执行锁。默认使用 H2 的 MySQL 兼容模式；额外提供真实 MySQL 冒烟测试：设置 `TEST_MYSQL_URL / TEST_MYSQL_USER / TEST_MYSQL_PASSWORD` 后运行 `mvn test`，必须指向专用测试库。

## 第一版边界

- 单用户、单实例、单账户；一个数据库只对应一个 OKX 账户，不能直接更换账户凭据复用历史库。暂不支持双向/全仓、加仓、自动反手、实盘、多账户或分布式高可用。
- 使用 REST 轮询（默认每 15 秒监控），没有逐笔实时行情或高频交易能力。模型决策默认间隔 5 分钟。
- 当日权益基准是当天程序首次观察的 USDT 权益；充值/划转/提现会影响损失计算。日损上限禁止新增风险，不保证最大亏损严格止于该值，也不自动强平。
- 保护单缺失、部分成交或查询无法确认时，暂停并显示异常，不声称仓位已受保护；必要时在 OKX 人工处理。
- 自动修改只支持本程序创建、能唯一识别的一组保护单。人工平仓后的残留保护单须在 OKX 清理后再开新仓，避免旧保护单影响新仓位。
- UNKNOWN 状态保留并阻止新增订单；若交易所长时间无法返回结果，需要人工核对，不提供随意清除未知状态的按钮。
- 暂停不取消已经提交的请求、订单或保护单；手机断开不暂停服务器。服务重启后需重新开启。
- 凭据从服务端环境变量加载，不存 MySQL；没有界面密钥管理、推送通知、回测或模型收益比较。

设计：[docs/architecture.md](docs/architecture.md)；接口：[docs/api.md](docs/api.md)。

本次实际验证范围见 [验收记录](docs/acceptance.md)，界面见 [Android 总览截图](docs/screenshots/overview.png)。
