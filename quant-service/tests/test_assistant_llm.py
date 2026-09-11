import asyncio
import json

import httpx
import pytest

from app.services.assistant_llm import AssistantModelSettings, AssistantModelError, QwenStreamingModel


def model(handler):
    config = AssistantModelSettings(_env_file=None, DASHSCOPE_API_KEY="test-only",
                                    ASSISTANT_LLM_MODEL="qwen3.7-plus")
    return QwenStreamingModel(config, httpx.MockTransport(handler))


def collect(instance):
    async def run():
        return [text async for text in instance.stream([{"role": "user", "content": "你好"}])]
    return asyncio.run(run())


def collect_events(instance, tools):
    async def run():
        return [event async for event in instance.stream_events([{"role": "user", "content": "分析持仓"}], tools)]
    return asyncio.run(run())


def test_stream_filters_reasoning_and_preserves_answer():
    def handler(request):
        payload = json.loads(request.content)
        assert payload["model"] == "qwen3.7-plus"
        assert payload["stream"] is True
        assert payload["enable_thinking"] is False
        events = [
            {"choices": [{"delta": {"reasoning_content": "private reasoning"}}]},
            {"choices": [{"delta": {"content": "你好"}}]},
            {"choices": [{"delta": {}, "finish_reason": "stop"}]},
        ]
        body = "".join("data: " + json.dumps(event) + "\n\n" for event in events) + "data: [DONE]\n\n"
        return httpx.Response(200, text=body)
    assert collect(model(handler)) == ["你好"]


def test_upstream_error_does_not_expose_response_body():
    instance = model(lambda request: httpx.Response(401, text="sensitive upstream body"))
    with pytest.raises(AssistantModelError, match="密钥无效") as error:
        collect(instance)
    assert "sensitive" not in str(error.value)


def test_truncated_stream_is_not_success():
    instance = model(lambda request: httpx.Response(200, text='data: {"choices":[{"delta":{"content":"部分"}}]}\n\n'))
    with pytest.raises(AssistantModelError, match="提前结束"):
        collect(instance)


def test_length_limit_is_not_success():
    instance = model(lambda request: httpx.Response(200, text='data: {"choices":[{"delta":{},"finish_reason":"length"}]}\n\n'))
    with pytest.raises(AssistantModelError, match="未完整结束"):
        collect(instance)


def test_can_reference_existing_local_credentials_without_copying_key(tmp_path):
    credentials = tmp_path / "learning.env"
    credentials.write_text("OTHER=value\n" + "DASHSCOPE_" + "API_KEY=referenced-test-key\n", encoding="utf-8")
    settings = AssistantModelSettings(_env_file=None, ASSISTANT_LLM_CREDENTIALS_FILE=credentials)
    assert settings.api_key.get_secret_value() == "referenced-test-key"


def test_stream_assembles_fragmented_function_call():
    def handler(request):
        payload = json.loads(request.content)
        assert payload["tool_choice"] == "auto"
        assert payload["tools"][0]["function"]["name"] == "get_current_portfolio_snapshot"
        events = [
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "call-1",
                "function": {"name": "get_current_", "arguments": "{"}}]}}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0,
                "function": {"name": "portfolio_snapshot", "arguments": "}"}}]}, "finish_reason": "tool_calls"}]},
        ]
        body = "".join("data: " + json.dumps(event) + "\n\n" for event in events) + "data: [DONE]\n\n"
        return httpx.Response(200, text=body)

    tools = [{"type": "function", "function": {"name": "get_current_portfolio_snapshot"}}]
    assert collect_events(model(handler), tools) == [{"type": "tool_calls", "calls": [{
        "id": "call-1", "name": "get_current_portfolio_snapshot", "arguments": "{}"
    }]}]
