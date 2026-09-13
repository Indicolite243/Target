import asyncio
import json

import httpx
import pytest

from app.assistant.embedding import (
    AssistantEmbeddingError,
    AssistantEmbeddingSettings,
    QwenEmbeddingModel,
)


def test_embedding_uses_configured_model_and_preserves_input_order():
    def handler(request):
        payload = json.loads(request.content)
        assert payload["model"] == "text-embedding-v4"
        assert payload["dimensions"] == 64
        assert payload["input"] == ["第一段", "第二段"]
        return httpx.Response(200, json={"model": "text-embedding-v4", "data": [
            {"index": 1, "embedding": [2.0] * 64},
            {"index": 0, "embedding": [1.0] * 64},
        ]})

    settings = AssistantEmbeddingSettings(
        _env_file=None, DASHSCOPE_API_KEY="test-only", ASSISTANT_EMBEDDING_DIMENSIONS=64)
    model = QwenEmbeddingModel(settings, httpx.MockTransport(handler))
    selected, vectors = asyncio.run(model.embed(["第一段", "第二段"]))
    assert selected == "text-embedding-v4"
    assert vectors[0][0] == 1.0
    assert vectors[1][0] == 2.0


def test_embedding_error_does_not_expose_upstream_body():
    settings = AssistantEmbeddingSettings(
        _env_file=None, DASHSCOPE_API_KEY="test-only", ASSISTANT_EMBEDDING_DIMENSIONS=64)
    model = QwenEmbeddingModel(settings, httpx.MockTransport(
        lambda request: httpx.Response(401, text="sensitive upstream body")))
    with pytest.raises(AssistantEmbeddingError, match="密钥无效") as error:
        asyncio.run(model.embed(["测试"]))
    assert "sensitive" not in str(error.value)
