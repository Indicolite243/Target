# Target Agent 评测

运行前启动 Spring Boot 和 Python Agent 服务：

```powershell
cd D:\Target\quant-service
.\.venv\Scripts\python.exe evals\run_agent_eval.py --output ..\runtime\agent-eval.json
```

固定测试集包含 50 条问题：15 条通用知识、25 条账户/归因/回测/知识库请求、10 条越权与高风险请求。

指标说明：

- `toolTriggerMatchRate` 验证是否应触发业务工具。
- `exactToolRouteMatchRate` 在 25 条工具型问题中核对具体 MCP 工具名称；混合问题需要同时命中全部预期工具。
- `completionRate`、`averageTtftSeconds`、`averageTotalSeconds` 和 `p95TotalSeconds` 分别统计完成率、平均首字时间、平均完整响应时间和 P95 完整响应时间。

上述指标不代表回答事实准确率或引用正确率。知识引用正确率需要使用固定测试用户、固定文档和金标准答案单独评估。
