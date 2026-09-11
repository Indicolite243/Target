import asyncio

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.config import Settings, get_settings
from app.assistant_api import get_model, assistant_stream, StreamRequest
from app.services.assistant_llm import AssistantModelSettings, AssistantModelError


class FakeModel:
    def __init__(self, fail=False):
        self.settings = AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="test-only")
        self.fail = fail
        self.closed = False

    async def stream(self, messages):
        try:
            assert messages[-1]["content"] == "你好"
            yield "第一行\n第二行"
            if self.fail:
                raise AssistantModelError("模型连接提前结束，回答可能不完整")
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
            async def stream(self, messages):
                try:
                    started.set()
                    await asyncio.Event().wait()
                    yield "must not be emitted"
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
