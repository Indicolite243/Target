# Target 高性能改造进度

> 对应总计划：[RedisProject/Target证券账户系统高性能改造计划书.md](D:\秋招\Redis\RedisProject\Target证券账户系统高性能改造计划书.md)
> 改造分支：`codex/target-redis-realtime-refactor`
> 最近更新：2026-08-26

## 已完成

### 阶段0：基础安全和运行基线

- JWT默认过期时间由30分钟调整为30天（`2592000`秒）。
- Token加入`jti`，退出时写入`stock:auth:blacklist:{jti}`，TTL等于Token剩余时间。
- QMT真实交易默认关闭；生产默认`QUANT_MODE=QMT`，`TEST_MOCK`仅用于测试。
- 新建仅暴露本机回环地址的`docker-compose.infrastructure.yml`，包含Redis AOF、密码和健康检查。
- 修复前端遗留的空壳`HelloWorld`测试，使前端测试可运行。

### 阶段1-3：实时组合最小闭环

- FastAPI新增`POST /internal/v1/accounts/live`，一次读取账户资产和持仓。
- Spring新增`CurrentPortfolioSnapshot`和Redis Key：
  - `stock:live:portfolio:{accountId}`：10秒实时快照。
  - `stock:live:portfolio:version:{accountId}`：单账户数据版本。
  - `stock:lock:collect:{accountId}`：自动/手动刷新互斥锁。
- Spring新增对外接口：
  - `GET /api/v1/accounts/{accountId}/live`：只读Redis，缺失时显式降级至MySQL最近数据。
  - `POST /api/v1/accounts/{accountId}/refresh`：同步请求QMT，成功后更新Redis和MySQL。
- 自动采集每2秒请求QMT并刷新Redis；MySQL每30秒只消费Redis，不追加QMT请求。
- QMT离线后自动采集改为每5秒探测一次，手动刷新仍可立即尝试，避免连接失败时每2秒重复占用QMT并刷屏日志。
- 前端`portfolioLive` Pinia Store成为当前组合唯一轮询者；资产展示页与资产对比图共享相同的`dataVersion`。
- 旧的“30秒直连QMT并写MongoDB”调度器默认关闭，防止重复采集和数据覆盖。

### 阶段4（账户历史部分）：MySQL历史快照

- Flyway V5已实际创建`account_snapshot`和`position_snapshot`；资产摘要与完整持仓分表保存。
- 新增Redis→MySQL历史写入器：交易时间内每5分钟归档资产，每15分钟或持仓内容变化归档完整持仓，15:10额外保留日终完整快照。
- 历史写入只读取Redis已有的实时组合，绝不追加QMT调用；单次账户快照与持仓行在短事务内完成。
- 历史曲线、风险计算与归因的历史数据读取已从MongoDB账户快照切到MySQL。
- 已运行一次性、默认关闭的Mongo快照迁移器：135条资产快照迁入MySQL，130组完整持仓拆为3770行；5条旧记录缺少`positions`字段，按设计仅保留资产摘要。

### 阶段5：MongoDB迁移与运行依赖移除

- Flyway V6已实际创建`risk_assessment`，JSON字段用于指标、建议、样本和告警，稳定筛选字段保持普通列与索引。
- 208条Mongo风险评估均已迁入MySQL，208条JSON字段均通过数据库有效性校验。
- 账户历史、风险计算与风险结果写入已全部切到MySQL；已删除Spring Data MongoDB依赖、Mongo配置、旧Document/Repository和一次性迁移代码。
- 本机MongoDB服务和原始集合没有删除，作为只读回退副本保留；当前应用启动日志已确认不再连接MongoDB。

### 阶段6（异步任务起步）：风险评估

- Flyway V7已实际创建`quant_task`，包含任务状态、进度、输入/结果摘要、错误信息和用户级幂等键索引。
- 新增独立的有界量化线程池（默认核心2、最大4、队列32），与HTTP请求线程和2秒QMT采集线程隔离；队列满时任务记录为失败，不在HTTP线程执行。
- `POST /api/v1/accounts/{accountId}/risk-assessments` 已改为返回`202 Accepted + taskId`；`GET /api/v1/quant-tasks/{taskId}`可按当前用户查询进度与结果摘要。
- 保留`GET /risk-assessments/latest`同步兼容接口，避免既有前端读取风险指标中断；归因和回测现已复用同一任务框架。

### 阶段7：归因与回测异步化

- Flyway V8创建`attribution_result`：归因完整结果以JSON持久化，并与唯一`quant_task`关联；`POST /api/v1/accounts/{accountId}/analyses/attribution/tasks`返回`202 + taskId`。
- Flyway V9创建`backtest_run`：回测完整结果、策略文件名、引擎、时间范围和任务关联写入MySQL；`POST /api/v1/backtests/run`已改为返回`202 + taskId`。
- 新增`GET /api/v1/quant-tasks/{taskId}/result`，按当前用户校验任务归属后读取风险、归因或回测完整结果。
- 回测上传文件在HTTP请求阶段完成类型/大小/日期校验后，暂存到`runtime/backtests/{taskId}/input`；后台线程只读取该任务专属文件，不使用已经失效的`MultipartFile`。
- 前端策略执行组件改为20秒内完成任务受理、每秒轮询任务状态、成功后读取结果；不再占用最长6分钟的同步HTTP连接。点击“取消”仅停止当前页面等待，不会伪称已经终止后台回测。

### 阶段8：订单受理、最终状态收敛与审计

- Flyway V10新增`trade_order_status_history`和`trade_order_audit`，分别记录状态/成交数量变化与提交、撤单、QMT确认等高风险操作；审计JSON不写密码、Token和完整资金账号。
- 下单、撤单均改为“短MySQL事务写入意图 → QMT同步调用 → 短MySQL事务写回结果”；不再把QMT网络等待置于数据库事务内。
- 提交请求在QMT明确4xx拒绝时写入`REJECTED`；网络或服务端不确定时写入`UNKNOWN`，禁止自动重新下单；后台轮询负责后续状态收敛。
- `GET /api/v1/orders`现为MySQL只读，不再由任意页面请求触发QMT查询。后台每3秒只查询有`external_order_no`的未终态QMT订单，且仅在状态、成交量或成交均价变化时写库。
- 订单列表前端每3秒读取MySQL；Snowflake订单ID全程作为字符串传递，避免JavaScript数值精度丢失导致撤单目标错误。
- 订单列表新增最大100条分页保护；新增`GET /api/v1/orders/{orderId}/timeline`，按当前用户归属读取状态历史和脱敏审计时间线，不返回撤单幂等键。
- 订单历史筛选与清理已补齐：`GET /api/v1/orders`支持`start_date`/`end_date`（按日期闭区间查询）；新增`DELETE /api/v1/orders/{orderId}`单条软删除和`DELETE /api/v1/orders/history`按日期范围批量软删除。
- Flyway V11已实际执行，为现有`trade_order`表增加`deleted_at`及`idx_trade_order_user_created_deleted`查询索引；删除只隐藏历史记录，不删除订单状态历史、不影响QMT委托，也不新建数据库。进行中的`PENDING_SUBMIT/SUBMITTED/PARTIALLY_FILLED/CANCEL_PENDING/UNKNOWN`订单禁止删除，仍按真实交易逻辑保留撤单入口。

### 阶段9：量化任务取消与重启恢复

- 新增`POST /api/v1/quant-tasks/{taskId}/cancel`：仅当任务仍为`PENDING`时才取消；`RUNNING`任务明确返回不可强制终止，避免页面或接口伪称已中止外部计算。
- 任务从`PENDING → RUNNING`和`PENDING → CANCELLED`使用带状态条件的MySQL更新，避免取消与工作线程抢占时覆盖彼此状态。
- Spring启动完成后，遗留的`PENDING/RUNNING`任务统一落为`FAILED + APPLICATION_RESTARTED`，提示用户重新提交；不在进程重启后盲目重放风险、归因或回测任务。
- 新增`QUANT_TASK_RECOVERY_ENABLED`配置（默认`true`）。本轮重启实际核对为0条遗留任务，服务健康检查为`UP`。

### 阶段10：回测运行目录保留与清理

- 回测文件清理器只选择`BACKTEST`且已处于`SUCCEEDED/FAILED/CANCELLED`终态、超过保留期的任务目录；MySQL中的任务和结果摘要不会删除。
- 清理时严格限定在`runtime/backtests/{taskId}`内，路径越界会拒绝；单元测试已确认删除任务42目录不会影响任务43。
- `BACKTEST_CLEANUP_ENABLED`默认`false`，`BACKTEST_CLEANUP_RETENTION_DAYS`默认14天，定时任务默认每天03:30执行。当前实例未启用自动删除，因此没有删除任何用户文件。

### 阶段11：前端统一数据链路与加载性能

- 前端账户摘要新增轻量`GET /accounts`读取；当前组合页面不再使用旧`fetchAccountInfo()`的“1次账户列表 + N次持仓”扇出链路。
- `portfolioLive`增加初始化请求合并和单飞轮询：下一次读取只会在上一次`/accounts/{id}/live`完成后再调度；页面后台或非实时降级状态延迟为10秒，正常实时状态为2秒。
- 交易页、交易下单模块、资产展示页和对比评估页共用同一个Pinia实时快照与账户选择；交易页已删除旧3秒账户/持仓轮询。
- 对比评估的当前资产数据读取Redis实时快照；时间、地区与归因历史数据明确读取MySQL。前端已移除MongoDB来源选项和残留请求参数。
- 策略回测的“取消执行”已对接`POST /api/v1/quant-tasks/{taskId}/cancel`：仅排队任务会被取消；运行中的任务只停止本页等待并如实提示后台仍在执行。
- `QuantTaskView`中的Snowflake任务、账户和结果ID统一序列化为JSON字符串，避免浏览器将19位整数转换为不精确的`Number`，导致轮询时误报“量化任务不存在”。
- 手动下单面板启动时读取QMT状态；当`QUANT_QMT_TRADE_ENABLED=false`或QMT未连接时，明确展示原因并禁用买入、卖出按钮，防止重复产生必然被拒绝的模拟委托。开启模拟交易仍需显式修改服务配置并重启FastAPI。
- 订单区按方案A完成局部改造，前端界面仅调整右下角订单模块：增加日期范围选择、今天/近7天/近30天快捷筛选、筛选组合工具栏、终态订单勾选、单条删除、删除选中和按当前日期范围清空终态记录；进行中订单不显示删除选择，避免把在途委托误当作历史清除。
- 日期范围控件的开始/结束日期数字改为深色加粗，分隔符和图标使用深灰色，提升浅色背景下的可读性；其他页面和下单模块保持不变。
- 修复手动下单搜索首次点击显示不完整的问题：MiniQMT首次订阅返回非空但价格为0的tick外壳时，量化服务继续等待有效最新价；前端在同一次搜索点击内自动重试，票据未完成前强制绕过短期行情缓存，避免再次读到元数据空壳。
- 修复搜索后委托计算需要二次点击的问题：首次有效tick到达后，右侧盘口与中间票据统一调用`syncTicketFromQuote`同步委托价格；账户/持仓异步加载完成后，再按当前价格、买卖方向和已选比例自动重算委托数量、可买/可卖数量及预计金额。用户手动修改过委托数量时保留其输入。
- Vue路由改为按页面懒加载，ECharts与Excel导出依赖拆分为独立缓存块，避免登录页预先加载对比图与导出代码。

### 热路径写入优化

- 当前持仓同步改为“短事务内删除旧持仓 + 单条批量INSERT新持仓”，不再按每个持仓发一条INSERT；新增单元测试确认批量路径。

### 阶段12：SuperMind回测内核对齐

- MindGo日线撮合改为原始未复权开盘价成交，可变滑点在买卖两侧各计一半；信号查询仍支持动态锚定的前复权行情，避免把复权价格误当真实成交价。
- 股票/ETF订单按100股整手执行，默认限制为当日成交量25%，手续费按成交额0.02%并保留单笔最低5元；现金不足时按整手缩量。
- 接入QMT `xtdata`自动补齐打包行情缺口，读取分红、送股、拆分和配股事件并同步调整现金、持仓数量与成本；默认基准统一为沪深300指数`000300.SH`。
- 收益、复合超额收益、年化收益、Alpha、Beta、Sharpe、Sortino、波动率、最大回撤、跟踪误差、信息比率和下行风险按SuperMind口径重算；风险序列显式包含初始净值1.0。
- Java异步回测暂存保留上传行情原文件名（如`510300.SH.xlsx`），不再改成`market-0.xlsx`导致量化引擎找不到证券代码；清洗后同名文件会明确拒绝。
- 使用`毕业设计/ETF.py`和SuperMind导出的`detal.csv`、`dailyposition.csv`完成2020-01-01至2025-12-01黄金回放：本系统最终资产1,517,255.23元，SuperMind为1,517,219.46元，差35.77元（0.0024%）；除总收益最后0.01个百分点和少数临界整手外，主要风险指标均对齐截图展示精度。该残差来自两套行情源未展示的小数精度，不再通过改变全局估值时点过拟合。
- 第二份`strategies/ETF_momentum_rotation.py`趋势轮动策略已完成双平台交叉验证：SuperMind/Target策略收益分别为-4.81%/-4.78%，年化-0.86%/-0.85%，最大回撤34.48%/34.49%；Alpha、Beta、Sharpe、Sortino、信息比率、波动率、跟踪误差和下行风险均对齐到页面展示精度。该策略包含66笔Target成交和多次权益ETF/债券ETF切换，证明内核不是针对第一份策略特调。用户已确认本阶段通过。
- Python模块在FastAPI进程启动时载入；修改`mindgo_runner.py`或`backtest_service.py`后必须重启8000端口服务。曾出现13:44启动的旧FastAPI继续返回旧基准与旧截止日期，重启后同一`test.py`恢复为沪深300指数基准11.71%、截止2025-12-01和最终净值952,234.70元。

## 2026-08-26 本轮改动核对

- 订单后端链路已逐项核对：`OrderController`日期参数与删除路由、`OrderService/OrderServiceImpl`日期查询和软删除保护、`TradeOrder.deletedAt`实体映射、`accountApi.js`删除请求均与V11表结构一致。
- 订单前端链路已逐项核对：`OrderList.vue`包含日期范围、快捷日期、状态/关键词展示过滤、终态勾选、单条删除、删除选中、按日期范围批量删除和深色日期文字；订单区以外页面未因本轮日期/删除功能发生修改。
- 手动下单链路已逐项核对：`qmt_account_service.py`等待正数最新价，`accountApi.js`支持`allowStale=false`，`OrderModule.vue`在一次搜索内重试并在首个有效tick到达后同步委托价格、数量上限、比例数量和预计金额。
- 本轮重新验证：`backend`执行`mvn -q test`通过；`quant-service`使用项目`.venv`执行26项测试全部通过；`frontend`生产构建通过，`OrderList.vue`、`OrderModule.vue`和`accountApi.js`定向ESLint通过。
- 运行态只读核对：Flyway V11成功记录为1条，`trade_order.deleted_at`字段和`idx_trade_order_user_created_deleted`索引均存在；Spring健康状态为`UP`；FastAPI为QMT模式且QMT已连接、已订阅。
- 已知语义限制：订单工具栏的关键词和状态标签当前只过滤前端表格；“清空当前筛选”批量接口只接收日期范围，因此实际含义是“清空当前日期范围内的终态历史记录”。上线前应选择补充后端关键词/状态过滤参数，或将按钮改名为“清空当前日期范围”。

## 尚未实施

1. 风险GET接口后续改为只读最新结果并由前端轮询任务；补充进度细分和任务级超时。
2. 订单轮询指标、QMT在线/离线演练和端到端性能压测；订单历史日期筛选、软删除接口和V11运行态核对已完成。
3. 统一“清空当前筛选”的产品语义：当前后端只按日期范围批量软删除，尚未接收关键词和状态过滤条件。

## 已验证

- `backend`: `mvn test -q` 通过。
- `quant-service`: 使用项目`.venv`执行`.\.venv\Scripts\python.exe -m pytest -q`通过，26项；其中新增8项SuperMind撮合、公司行动、动态前复权和MindGo/通用结果指标口径测试。
- `frontend`: `npm run test:unit -- --run` 通过。
- `frontend`: `npm run build` 通过。
- 2026-08-26 订单区与单击搜索联动改造后，`frontend`: `npm run build`通过，`npx eslint src/components/layout/OrderList.vue src/components/layout/OrderModule.vue src/api/accountApi.js`通过；`backend`: `mvn -q test`和`mvn -q -DskipTests compile`通过；`quant-service`项目`.venv`内26项测试通过。
- 前端新增`portfolioLive`单元测试，覆盖并发初始化合并和轮询不重叠；当前前端单测4项通过，ESLint通过。
- Redis 7.4 容器 `target-redis` 已通过健康检查；密码认证返回 `PONG`，AOF 已启用，端口仅监听 `127.0.0.1:6379`。
- 早期安全基线验证时，WSL 2、Docker Desktop、FastAPI（8000）和 Spring Boot（8080）均已实际启动；当时FastAPI为`QMT`模式且模拟交易开关为`false`，Spring健康检查为`UP`。当前开关状态以后文最新运行态核对为准。
- Spring Boot已重启并成功执行Flyway V5；当前`application.yml`中Flyway保持启用，启动时会执行尚未应用的迁移。MySQL核对结果为135条账户快照、130组完整持仓、3770条持仓行，快照时间范围为2026-08-05至2026-08-14（本地时区）。
- Spring Boot已重启并成功执行Flyway V6；MySQL核对结果为208条风险评估。当前普通运行实例健康检查为`UP`，启动日志无MongoDB连接记录。
- Spring Boot已重启并成功执行Flyway V7；`quant_task`表和关键索引已核对，普通运行实例健康检查为`UP`。
- Spring Boot已重启并成功执行Flyway V8、V9；`attribution_result`和`backtest_run`表及关键索引已核对，普通运行实例健康检查为`UP`。
- Spring Boot已重启并成功执行Flyway V10；`trade_order_status_history`和`trade_order_audit`表、索引及Flyway成功记录均已核对，普通运行实例健康检查为`UP`。
- Spring Boot已成功执行Flyway V11；`trade_order.deleted_at`字段、`idx_trade_order_user_created_deleted`索引及Flyway成功记录均已只读核对。
- FastAPI（8000）和Spring Boot（8080）当前均在本机回环地址运行；FastAPI为`QMT`模式，健康接口显示QMT已连接、已订阅、读取开关开启，模拟交易开关当前也已开启；Spring健康检查为`UP`。
- 2026-08-26 已完成QMT极简模式恢复演练：FastAPI健康状态为`connected=true`、`subscribed=true`；Spring自动采集已写入Redis实时快照，并同步MySQL当前账户与29条持仓。一次只读账户+持仓快照实测FastAPI处理约2ms、本机HTTP端到端约102ms（单次基线，非P95压测）；全程`QUANT_QMT_TRADE_ENABLED=false`，未执行下单或撤单。
- 新增`D:\Target\scripts\benchmark-live-api.ps1`：只读调用FastAPI实时组合接口，默认按2秒频率采样，输出P50/P95且不输出账户、资金或持仓明细。2026-08-26实测5轮：全部成功、无告警、29条持仓，HTTP端到端P50=9ms、P95=66ms，FastAPI服务内P50/P95=1ms（本机基线，不替代压力测试）。

## 接手注意事项

1. 先检查`git -C D:\Target status --short --branch`，不要覆盖未提交修改。
2. 运行实时功能前，在`.env`填写随机`REDIS_PASSWORD`、`JWT_SECRET`、QMT路径和账户；保持`QUANT_QMT_TRADE_ENABLED=false`。
3. Redis 已通过 Docker Desktop 启动。查看状态可执行：`docker compose -f docker-compose.infrastructure.yml ps`。
4. 运行QMT自动采集前，账户的`broker`必须为`GUOJIN_QMT`且QMT客户端已登录极简模式。
