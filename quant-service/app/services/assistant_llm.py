"""千问流式适配器；仅输出正文，不执行代码，不把上游错误体或密钥暴露给用户。"""
import json
from pathlib import Path
from typing import AsyncIterator

import httpx
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class AssistantModelSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=Path(__file__).resolve().parents[3] / ".env",
        extra="ignore",
    )
    api_key: SecretStr = Field(default=SecretStr(""), validation_alias="DASHSCOPE_API_KEY")
    base_url: str = Field(default="https://dashscope.aliyuncs.com/compatible-mode/v1", validation_alias="ASSISTANT_LLM_BASE_URL")
    model: str = Field(default="qwen3.7-plus", validation_alias="ASSISTANT_LLM_MODEL")
    temperature: float = Field(default=0.2, ge=0, le=2, validation_alias="ASSISTANT_LLM_TEMPERATURE")
    timeout_seconds: float = Field(default=90, gt=0, validation_alias="ASSISTANT_LLM_TIMEOUT_SECONDS")
    max_tokens: int = Field(default=4096, gt=0, validation_alias="ASSISTANT_LLM_MAX_TOKENS")


class AssistantModelError(RuntimeError):
    """可安全返回给 UI 的错误，不包含上游原始响应。"""


class QwenStreamingModel:
    def __init__(self, settings: AssistantModelSettings, transport=None):
        self.settings = settings
        self.transport = transport

    async def stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        settings = self.settings
        if not settings.api_key.get_secret_value():
            raise AssistantModelError("千问密钥尚未配置")
        if not messages or any(m.get("role") not in {"system", "user", "assistant"}
                               or not isinstance(m.get("content"), str) for m in messages):
            raise AssistantModelError("模型消息格式无效")
        payload = {
            "model": settings.model,
            "messages": messages,
            "temperature": settings.temperature,
            "max_tokens": settings.max_tokens,
            "stream": True,
            "enable_thinking": False,
        }
        try:
            async with httpx.AsyncClient(
                transport=self.transport,
                timeout=httpx.Timeout(settings.timeout_seconds, connect=10),
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    "POST", settings.base_url.rstrip("/") + "/chat/completions",
                    headers={"Authorization": "Bearer " + settings.api_key.get_secret_value()},
                    json=payload,
                ) as response:
                    if response.status_code != 200:
                        hints = {401: "千问密钥无效或无访问权限", 403: "千问模型访问被拒绝",
                                 429: "千问调用频率或额度受限"}
                        raise AssistantModelError(hints.get(response.status_code, "千问服务请求失败"))
                    finished = False
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        raw = line[5:].strip()
                        if raw == "[DONE]":
                            if not finished:
                                raise AssistantModelError("模型未返回完成状态，回答可能不完整")
                            return
                        if not raw:
                            continue
                        event = json.loads(raw)
                        if event.get("error"):
                            raise AssistantModelError("千问生成失败，已生成内容可能不完整")
                        for choice in event.get("choices", []):
                            # reasoning_content 不属于用户回答，不转发或持久化。
                            content = choice.get("delta", {}).get("content")
                            if isinstance(content, str) and content:
                                yield content
                            reason = choice.get("finish_reason")
                            if reason == "stop":
                                finished = True
                            elif reason:
                                raise AssistantModelError("模型回答未完整结束，请缩小问题范围后重试")
                    if not finished:
                        raise AssistantModelError("模型连接提前结束，回答可能不完整")
        except httpx.TimeoutException:
            raise AssistantModelError("千问响应超时，请稍后重试") from None
        except httpx.HTTPError:
            raise AssistantModelError("千问连接失败，请检查网络") from None
        except (ValueError, TypeError, AttributeError):
            raise AssistantModelError("千问响应格式异常") from None
