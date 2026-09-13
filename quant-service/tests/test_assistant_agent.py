import asyncio
from types import SimpleNamespace

import pytest
from langchain_mcp_adapters.interceptors import MCPToolCallRequest
from langchain_core.messages import AIMessage, ToolMessage

from app.assistant import agent as assistant_agent
from app.assistant.agent import (
    AssistantAgentSettings,
    TargetAgentContext,
    TargetContextHeaders,
    TargetLangChainAgent,
)
from app.assistant.llm import AssistantModelError, AssistantModelSettings


def test_context_interceptor_injects_identity_headers_without_tool_arguments():
    captured = {}
    context = TargetAgentContext(7, "conversation-1", "request-1", "trace-1")
    request = MCPToolCallRequest(
        name="get_current_portfolio_snapshot",
        args={},
        server_name="target",
        runtime=SimpleNamespace(context=context),
    )

    async def handler(value):
        captured["request"] = value
        return "ok"

    result = asyncio.run(TargetContextHeaders()(request, handler))

    assert result == "ok"
    forwarded = captured["request"]
    assert forwarded.args == {}
    assert forwarded.headers == {
        "X-Target-User-Id": "7",
        "X-Target-Conversation-Id": "conversation-1",
        "X-Target-Request-Id": "request-1",
        "X-Trace-Id": "trace-1",
    }


def test_context_interceptor_rejects_calls_outside_agent_runtime():
    request = MCPToolCallRequest(
        name="get_current_portfolio_snapshot", args={}, server_name="target")

    async def handler(value):
        raise AssertionError("handler must not be invoked")

    with pytest.raises(AssistantModelError, match="可信身份上下文"):
        asyncio.run(TargetContextHeaders()(request, handler))


def test_agent_converts_langgraph_messages_and_preserves_structured_metadata(monkeypatch):
    captured = {}

    class FakeMcpClient:
        def __init__(self, connections, **kwargs):
            captured["connections"] = connections
            captured["interceptors"] = kwargs["tool_interceptors"]

        async def get_tools(self, server_name):
            assert server_name == "target"
            return [object()]

    class FakeGraph:
        async def astream(self, state, **kwargs):
            captured["state"] = state
            captured["stream"] = kwargs
            yield {"type": "messages", "data": (
                AIMessage(content="", tool_calls=[{
                    "name": "get_current_portfolio_snapshot", "args": {},
                    "id": "call-1", "type": "tool_call"}]), {})}
            yield {"type": "messages", "data": (
                ToolMessage(content="{}", tool_call_id="call-1", artifact={
                    "structured_content": {"snapshotId": "snapshot-1"}}), {})}
            yield {"type": "messages", "data": (AIMessage(content="分析完成"), {})}

    monkeypatch.setattr(assistant_agent, "create_chat_model", lambda settings, client: object())
    monkeypatch.setattr(assistant_agent, "MultiServerMCPClient", FakeMcpClient)
    monkeypatch.setattr(assistant_agent, "create_agent", lambda **kwargs: FakeGraph())
    context = TargetAgentContext(7, "conversation-1", "request-1", "trace-1")
    agent = TargetLangChainAgent(
        AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="test-only"),
        AssistantAgentSettings(_env_file=None, ASSISTANT_MCP_INTERNAL_TOKEN="mcp-token"),
    )

    async def collect():
        return [event async for event in agent.stream_events([
            {"role": "system", "content": "只读"},
            {"role": "user", "content": "分析持仓"},
        ], context)]

    events = asyncio.run(collect())

    assert [event["type"] for event in events] == [
        "status", "tool_result", "status", "delta", "done"]
    assert events[1]["metadata"] == {"snapshotId": "snapshot-1"}
    assert events[3]["text"] == "分析完成"
    assert captured["state"] == {"messages": [{"role": "user", "content": "分析持仓"}]}
    assert captured["stream"]["context"] == context
    assert captured["connections"]["target"]["headers"] == {"X-Internal-Token": "mcp-token"}
