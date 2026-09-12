# 迁移进度

更新时间：2026-09-11

## 运行态联调结果

- MySQL 登录成功，已创建 `stock_manager` 数据库。
- Flyway V1、V2、V3 成功执行，已创建用户、账户、持仓、任务和订单相关表，并扩展用户标识列。
- Spring Boot 已同时连接 MySQL 与 MongoDB。
- 已通过真实 HTTP 链路验证：健康检查 → 注册 → JWT 登录 → 模拟账户 → Java 调用 Python 行情 → 模拟下单 → 订单查询 → Python 风险计算 → MongoDB 保存 → 组合分析。
- MongoDB 已生成 `risk_assessments` 集合并保存联调结果。
- 已创建并验证测试账户 `test / 123456`，登录后有独立本地账户。
- 本机 `xtquant`、行情和账户通道均已联通；登录极简模式后已完成资金账号订阅并同步 29 条真实模拟持仓。
- Redis 6379 仍未监听，因此 Actuator 总体健康状态暂为 503；不影响当前未使用 Redis 的模拟接口，但缓存、会话黑名单和分布式锁尚不能验收。

## 阶段 0：现状盘点与契约冻结

状态：已完成第一轮。

- 已盘点 Django 根路由及 auth、account、Comparison、risk_threshold 路由。
- 已确认旧 Django 几乎没有关系型业务模型，数据访问主要位于 View、MongoDB/QMT 工具中。
- 已扫描 Vue API，确认存在多个 Axios 实例、硬编码 8000 和旧 snake_case 接口。
- 新系统接口统一采用 `/api/v1/**`；Java-Python 内部接口采用 `/internal/v1/**`。

## 阶段 1：工程与基础设施

状态：基础骨架已完成并通过构建。

- 创建统一工程目录。
- Spring Boot 接入 Web、Security、Validation、MyBatis-Plus、MySQL、Redis、MongoDB、Flyway、Actuator、OpenAPI。
- 实现统一响应、业务异常、全局异常处理、traceId Filter。
- Maven 测试通过。
- Vue 生产构建通过。
- Python 源码编译检查通过。
- Python 已在隔离虚拟环境中通过 FastAPI 接口测试：健康检查、内部鉴权、批量行情、模拟下单和风险计算。

待办：

- 获取 MySQL 有效用户名/密码并创建 `stock_manager` 数据库。
- 启动 Redis 6379。
- 完成三种存储的运行态 Actuator 健康验证。

## 阶段 2：认证、用户与权限

状态：基础功能已完成。

- 已完成注册、登录、JWT、当前用户和退出接口的第一版。
- 已完成 Vue 登录字段适配与路由守卫。
- 已删除前端明文记住密码行为。
- 已删除账号和密码的位数、字符种类限制，仅保留非空与确认密码一致。
- 新密码采用 SHA-256 预哈希后 BCrypt，兼容改造前的 BCrypt 哈希，也支持超长/Unicode/特殊符号密码。

待办：Refresh Cookie、Redis 会话/黑名单、完整 RBAC 表、认证集成测试、登录限流。

## 阶段 3：账户、持仓与资产快照

状态：进行中。

- 已创建账户、持仓 Entity/Mapper/Service/Controller。
- 已创建 MongoDB `account_snapshots` 文档和 Repository。
- 已加入 Vue 旧页面账户字段 Adapter。
- 注册用户时自动创建隔离的模拟账户和一条演示持仓，便于完成最小闭环。
- 已实现 JWT 保护的 `POST /api/v1/accounts/{accountId}/sync`，Java 调用 Python QMT 后全量替换本次持仓、更新账户并保存 MongoDB 快照。
- QMT 持仓查询返回 `None` 时整次同步失败并保留历史持仓，避免把接口故障误判为空仓。
- Vue 已加入 QMT 状态、错误原因与手动同步按钮。

待办：账户同步异步任务、Redis 缓存、历史曲线接口、更多账户绑定策略。

## 阶段 4：Python Quant Service

状态：QMT 只读适配与真实模拟账户联调已完成。

- 已实现内部 Token 校验。
- 已实现健康检查、模拟账户同步、批量模拟行情、基础风险指标计算。
- 已实现长生命周期、线程安全的 XtQuantTrader 单例，包含动态加载、账号发现、订阅、断线重连和退出清理。
- 已映射 QMT 资产、持仓、当日委托与成交；账户页同步只查询资产和持仓。
- QMT 模式连接失败返回 503，不回退模拟数据；真实交易开关固定为关闭。
- Python QMT 回归测试 14 项通过。
- 已增加 QMT 日线下载与组合历史重建服务，支持按当前真实持仓和现金生成指定区间的日度组合价值。

待办：后续迁移 baostock 元数据，并补充多账户绑定与周期同步任务。

## 阶段 5 至 7

状态：阶段 5、阶段 6 核心展示链路已完成，阶段 7 未开始。

- 已实现批量行情的 Java 编排接口与 Python 模拟行情接口。
- 已实现订单 Entity/Mapper/Service/Controller、幂等键、基础状态机、模拟提交和撤单。
- 已实现 MongoDB 风险评估文档、QMT 历史行情组合重建、四项真实风险指标计算和最近风险结果接口。
- 已实现 29 条 QMT 持仓明细对比、沪深市场汇总、243 个交易日周期对比，以及个股/行业业绩归因。
- 风控与归因默认区间为最近 30 天；本次实机验收得到 23 个交易日样本、29 只归因股票和 16 个行业分组。
- Vue 对比评估页面已移除占位柱、No Data 和模拟风控结果，并消除图表/表格重复请求历史行情的问题。
- Vue 所有业务 API 已移除 Django 8000 硬编码，统一切换 `/api/v1`。
- 待完成：成交回报、外部状态查询、Redis 幂等持久化、定时对账和完整交易规则。
- 待完成：完整风险历史分页、真实历史样本、策略回测。
- OSS 文件中心、导入导出。
- 压测、部署、E2E 与答辩材料。

## 投研助手增量

状态：对话、账户工具、回测、业绩归因、用户私有知识库 RAG 和 MCP 第一版已完成。

- Spring Boot 负责 JWT 用户边界、会话和消息持久化、受控 Function Calling、持仓/归因/回测冻结快照，以及私有知识文档和证据追溯；Agent 通过官方 Java SDK 的 Streamable HTTP MCP 客户端发现和调用 5 个只读投研工具。
- FastAPI 负责千问流式生成与百炼 `text-embedding-v4` 向量生成；浏览器不接触百炼密钥，Java-Python 之间继续使用内部令牌。
- 私有知识库支持 PDF、DOCX、MD、TXT，使用 Apache Tika 提取正文；切片和 1024 维向量保存在 MySQL，原文件存放于运行时私有目录。
- 检索同时校验文档和切片的当前用户归属，采用向量相似度、轻量词法匹配与 RRF 融合；检索证据随回答冻结，支持 `[K1]` 形式引用。
- Flyway 已执行至 V17。真实浏览器已验证上传、索引、工具调用、流式回答、文档名与引用；测试数据已清理。
- MCP 服务端点默认 `/internal/mcp`，由独立内部令牌保护；用户、会话、请求和追踪身份由可信客户端元数据传递，不交给模型生成。
- 当前回归基线：Spring Boot 测试包含 MCP 初始化、工具发现、工具调用及鉴权覆盖；Vue 14 项、FastAPI 41 项测试通过，Vue 生产构建通过。
