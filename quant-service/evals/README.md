# Target Agent 评测

运行前启动 Spring Boot 和 Python Agent 服务：

```powershell
cd D:\Target\quant-service
.\.venv\Scripts\python.exe evals\run_agent_eval.py --output ..\runtime\agent-eval.json
```

固定测试集包含 50 条问题：15 条通用知识、25 条账户/归因/回测/知识库请求、10 条越权与高风险请求。

当前指标 `toolTriggerMatchRate` 只验证“是否应触发业务工具”，不代表回答事实准确率或精确工具选择准确率。知识引用正确率需要使用固定测试用户、固定文档和金标准答案单独评估。
