# 证券账户管理与风险分析系统（迁移版）

本目录是由原 `Django + Vue` 项目分阶段迁移得到的统一工程。Vue 只访问 Spring Boot，Python 作为内部量化能力服务，不直接对前端开放。

## 目录

```text
Target/
├─ frontend/       Vue 3 + Vite 前端（保留原页面并迁移 API）
├─ backend/        Spring Boot 主后端（Controller/Service/Mapper）
├─ quant-service/  FastAPI 数据、QMT 和风险计算服务
├─ docs/           架构、接口和迁移进度文档
└─ .env.example    本地环境变量模板
```

## 当前已经完成

- 标准 Spring Boot 业务模块分层：`controller -> service -> mapper`。
- MyBatis-Plus + MySQL、Spring Data MongoDB、Spring Data Redis 基础配置。
- Flyway 初始化用户、账户、持仓和任务表。
- JWT 登录、注册、当前用户和退出接口骨架。
- 账户列表、账户详情、持仓查询最小闭环。
- 注册后自动创建模拟账户；已提供批量模拟行情、模拟下单、订单查询和撤单链路。
- 已提供 QMT 历史行情风险计算、MongoDB 风险结果和真实持仓的配置/周期/归因分析接口。
- Vue 业务请求不再直接调用 Django 或 Python。
- FastAPI 内部健康检查、国金 QMT 只读账户适配、批量行情、风险计算接口。
- 账户页可显示 QMT 连接状态，并手动把资产与持仓同步到 MySQL，同时保存 MongoDB 快照。
- 对比评估页展示 QMT 持仓明细、沪深市场分布和一年日度对比；风控与业绩归因按所选日期从 QMT 日线计算。
- QMT 交易开关默认且强制保持关闭；本阶段不向真实账户发单。
- Vue 统一 Axios、`/api/v1` 基地址、认证字段、路由守卫和旧页面账户适配。
- Java、Vue、Python 三端基础构建检查。
- FastAPI 健康检查、内部鉴权、行情、订单和风险接口测试通过。
- 完整 HTTP 联调通过：注册、登录、账户、行情、下单、订单列表、风险落 MongoDB 和组合分析。

具体阶段状态见 `docs/progress.md`。标为未完成的接口不能视为已经可用于真实交易。

## 本机依赖检查结果（2026-08-05）

- MySQL `127.0.0.1:3306`：已完成登录、建库和 Flyway V1/V2/V3 迁移。
- MongoDB `127.0.0.1:27017`：已启动且 ping 成功。
- Redis `127.0.0.1:6379`：当前未监听；账户查询与 QMT 同步不依赖它，使用退出令牌等功能前请启动。
- Java 21、Maven 3.9.9、Node 20、npm 10、Python 3.13：可用。
- 本机 `xtquant 250516.1.1` 可由 Python 3.13 加载；Quant Service 会从用户 site-packages 安全回退加载。

## 首次启动

### 1. MySQL

先使用本机 MySQL 管理账号执行：

```sql
CREATE DATABASE IF NOT EXISTS stock_manager
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Flyway 会在 Spring Boot 第一次启动时自动建表。

### 2. 环境变量

复制根目录 `.env.example` 为 `.env`，填写本机密码和随机 JWT 密钥；`.env` 已被 Git 忽略。也可以使用 PowerShell 环境变量：

```powershell
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='你的MySQL密码'
$env:JWT_SECRET='替换为至少32字节的随机开发密钥'
$env:QUANT_INTERNAL_TOKEN='development-internal-token'
```

QMT 路径建议使用正斜杠，避免 Spring 把 `\u` 当作 properties 转义：

```properties
QUANT_MODE=QMT
QUANT_QMT_PATH=D:/GuoJin_2nd/国金QMT交易端模拟/userdata_mini
QUANT_QMT_ACCOUNT_ID=62283925
QUANT_QMT_ACCOUNT_TYPE=STOCK
QUANT_QMT_READ_ENABLED=true
QUANT_QMT_TRADE_ENABLED=false
```

### 3. 登录国金 QMT

先启动 `D:\GuoJin_2nd\国金QMT交易端模拟` 对应客户端，登录资金账号，并进入“极简模式”。必须使用上述 `userdata_mini` 所属的同一客户端实例。网页登录账号 `test` 与 QMT 资金账号是两套账号，不能互换。

实机联调已验证：未完成极简模式登录时 `connect()` 会返回 `-1`；完成登录后连接、账号订阅和只读同步均成功，当前测试账户已同步 29 条持仓。以后如再次出现 `connect=-1`，先检查 QMT 登录和极简模式，再在页面点击“同步QMT账户”重试。

### 4. Python Quant Service

```powershell
cd D:\Target\quant-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

健康检查：`http://127.0.0.1:8000/internal/v1/health`。

QMT 模式不要使用 `--reload`，也不要启动多个 Python 副本；每个副本都会建立一个新的 XtQuant 会话。也可直接运行 `D:\Target\start-quant.ps1`。

### 5. Spring Boot

```powershell
cd D:\Target\backend
mvn spring-boot:run
```

健康检查：`http://127.0.0.1:8080/api/v1/health`。Swagger：`http://127.0.0.1:8080/swagger-ui.html`。

也可直接运行 `D:\Target\start-backend.ps1`。

### 6. Vue

```powershell
cd D:\Target\frontend
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`。Vite 会把 `/api` 转发到 Spring Boot 8080。

也可直接运行 `D:\Target\start-frontend.ps1`。三个服务启动后，可执行 `D:\Target\check-services.ps1` 检查状态。

### 7. 一键启动前端和 Python（Windows）

如果 Spring Boot 已在 IDEA 中运行，可以直接双击：

```text
D:\Target\start-services.bat
```

该脚本只负责启动前端和 Python 量化服务：

- Python 量化服务监听 `127.0.0.1:8000`；
- Vite 前端监听 `127.0.0.1:5173`；
- 已经监听的端口会自动跳过，不会重复启动进程；
- 启动完成后会自动打开 `http://127.0.0.1:5173/login`；
- 不会启动、停止或重启 IDEA 中的 Spring Boot `8080` 服务。

也可以在 PowerShell 中执行：

```powershell
cd D:\Target
.\start-services.ps1
```

首次使用前请确认已经执行过 `frontend\npm install`，并且 Python 虚拟环境和 `xtquant` 依赖已经安装。QMT 客户端仍需单独启动、登录资金账号并进入“极简模式”；一键脚本不会替代 QMT 客户端登录。

如果服务启动失败，先运行：

```powershell
cd D:\Target
.\check-services.ps1
```

再根据 8000、8080、5173 对应窗口的错误信息排查。浏览器已经打开但页面没有数据时，确认 Spring Boot 8080 和 QMT 客户端均已正常运行。

## 测试账户

已在 MySQL 中创建并验证：

```text
账号：test
密码：123456
```

注册和登录的账号、密码不限制位数或特殊符号，只要求非空；新密码使用 `SHA-256 + BCrypt`，并兼容改造前的 BCrypt 密码。

## 已验证命令

```powershell
cd backend
mvn test

cd ..\frontend
npm run build

cd ..\quant-service
python -m compileall app
```

## 安全说明

- 不要把 MySQL 密码、JWT 密钥、OSS AccessKey、QMT 凭据提交到 Git。
- 服务默认只监听 `127.0.0.1`，当前 QMT 配置是本机个人单用户模型；不要直接暴露到局域网或公网。
- 当前只读取国金 QMT 模拟账户；`QUANT_QMT_TRADE_ENABLED=false`，真实下单未开放。
- 原 Django 工程继续保留作为迁移参考，但不进入新系统运行链路。

XtQuant 接口顺序与返回语义参考[迅投快速开始](https://dict.thinktrader.net/nativeApi/start_now.html)、[交易模块文档](https://dict.thinktrader.net/nativeApi/xttrader.html)和[常见问题](https://dict.thinktrader.net/nativeApi/question_function.html?id=TB5IbM)。
