import asyncio

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.config import Settings, get_settings
from app.assistant.api import get_agent, get_model, assistant_stream, StreamRequest
from app.assistant.agent import TargetAgentContext
from app.assistant.llm import AssistantModelSettings, AssistantModelError


class FakeModel:
    def __init__(self, fail=False):
        self.settings = AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="test-only")
        self.fail = fail
        self.closed = False

    async def stream_events(self, messages, tools=None):
        try:
            assert messages[-1]["content"] == "你好"
            yield {"type": "delta", "text": "第一行\n第二行"}
            if self.fail:
                raise AssistantModelError("模型连接提前结束，回答可能不完整")
            yield {"type": "done"}
        finally:
            self.closed = True


class FakeAgent:
    def __init__(self, fail=False):
        self.model_settings = AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="test-only")
        self.fail = fail
        self.closed = False
        self.context = None

    async def stream_events(self, messages, context):
        try:
            self.context = context
            assert messages[-1]["content"] == "分析持仓"
            yield {"type": "status", "phase": "READING_DATA", "message": "正在读取"}
            yield {"type": "tool_result", "metadata": {"snapshotId": "snapshot-1"}}
            yield {"type": "delta", "text": "组合较集中"}
            if self.fail:
                raise AssistantModelError("Agent 执行失败")
            yield {"type": "done"}
        finally:
            self.closed = True


@pytest.fixture
def client():
    previous = app.dependency_overrides.copy()
    app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None, internal_token="test-token", mode="TEST_MOCK")
    app.dependency_overrides[get_model] = lambda: FakeModel()
    # 不启动 lifespan，避免测试触碰真实 QMT。
    yield TestClient(app)
    app.dependency_overrides.clear()
    app.dependency_overrides.update(previous)


def post(client, **kwargs):
    return client.post("/internal/v1/assistant/stream", headers={"X-Internal-Token": "test-token"},
                       json={"messages": [{"role": "user", "content": "你好"}]}, **kwargs)


def test_internal_auth_rejects_wrong_token(client):
    response = client.post("/internal/v1/assistant/stream", headers={"X-Internal-Token": "wrong"},
                           json={"messages": [{"role": "user", "content": "你好"}]})
    assert response.status_code == 401


def test_success_has_ordered_events_and_escaped_newlines(client):
    model = FakeModel()
    app.dependency_overrides[get_model] = lambda: model
    response = post(client)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.text.index("event: status") < response.text.index("event: delta") < response.text.index("event: done")
    assert "第一行\\n第二行" in response.text
    assert model.closed


def test_partial_failure_never_emits_done(client):
    model = FakeModel(fail=True)
    app.dependency_overrides[get_model] = lambda: model
    response = post(client)
    assert "event: delta" in response.text
    assert "event: error" in response.text
    assert "event: done" not in response.text
    assert model.closed


def test_model_cannot_be_overridden_from_request(client):
    response = client.post("/internal/v1/assistant/stream", headers={"X-Internal-Token": "test-token"},
                           json={"messages": [{"role": "user", "content": "你好"}], "base_url": "https://example.com"})
    assert response.status_code == 422


def test_unconfigured_key_returns_503_before_streaming(client):
    model = FakeModel()
    model.settings = AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="")
    app.dependency_overrides[get_model] = lambda: model
    assert post(client).status_code == 503


def test_oversized_context_is_rejected(client):
    response = client.post("/internal/v1/assistant/stream", headers={"X-Internal-Token": "test-token"},
                           json={"messages": [{"role": "user", "content": "a" * 32000}] * 4})
    assert response.status_code == 422


def test_cancel_propagates_and_closes_model_stream():
    async def run():
        started = asyncio.Event()

        class WaitingModel(FakeModel):
            async def stream_events(self, messages, tools=None):
                try:
                    started.set()
                    await asyncio.Event().wait()
                    yield {"type": "delta", "text": "must not be emitted"}
                finally:
                    self.closed = True

        model = WaitingModel()
        response = await assistant_stream(StreamRequest(messages=[{"role": "user", "content": "你好"}]), model)
        iterator = response.body_iterator
        assert "event: status" in await anext(iterator)
        pending = asyncio.create_task(anext(iterator))
        await asyncio.wait_for(started.wait(), timeout=2)
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert model.closed

    asyncio.run(run())


def test_agent_stream_forwards_trusted_context_and_tool_metadata(client):
    agent = FakeAgent()
    app.dependency_overrides[get_agent] = lambda: agent
    response = client.post(
        "/internal/v1/assistant/agent/stream",
        headers={"X-Internal-Token": "test-token"},
        json={
            "messages": [{"role": "system", "content": "只读"},
                         {"role": "user", "content": "分析持仓"}],
            "context": {"userId": 7, "conversationId": "conversation-1",
                        "requestId": "request-1", "traceId": "trace-1"},
        },
    )

    assert response.status_code == 200
    assert response.text.index("event: status") < response.text.index("event: tool_result")
    assert response.text.index("event: tool_result") < response.text.index("event: delta")
    assert '"snapshotId": "snapshot-1"' in response.text
    assert "event: done" in response.text
    assert agent.context == TargetAgentContext(7, "conversation-1", "request-1", "trace-1")
    assert agent.closed


def test_agent_stream_failure_never_emits_done(client):
    agent = FakeAgent(fail=True)
    app.dependency_overrides[get_agent] = lambda: agent
    response = client.post(
        "/internal/v1/assistant/agent/stream",
        headers={"X-Internal-Token": "test-token"},
        json={"messages": [{"role": "user", "content": "分析持仓"}],
              "context": {"userId": 7, "conversationId": "conversation-1",
                          "requestId": "request-1", "traceId": "trace-1"}},
    )
    assert "event: delta" in response.text
    assert "event: error" in response.text
    assert "event: done" not in response.text
    assert agent.closed
