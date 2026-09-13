"""基于 LangChain 的千问流式适配器。

本模块负责统一创建千问 ChatModel，并保留旧 Java 编排链路使用的流式协议适配器。
新链路的 Agent 与 MCP 编排位于同目录的 agent.py。
"""
import json
from pathlib import Path
from typing import AsyncIterator

import httpx
from langchain_core.messages import AIMessageChunk
from langchain_openai import ChatOpenAI
from openai import APIConnectionError, APIStatusError, APITimeoutError
from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AssistantModelSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(Path(__file__).resolve().parents[3] / ".env",
                  Path(__file__).resolve().parents[3] / ".env.assistant.local"),
        extra="ignore",
    )
    api_key: SecretStr = Field(default=SecretStr(""), validation_alias="DASHSCOPE_API_KEY")
    credentials_file: Path | None = Field(default=None, validation_alias="ASSISTANT_LLM_CREDENTIALS_FILE")
    base_url: str = Field(default="https://dashscope.aliyuncs.com/compatible-mode/v1", validation_alias="ASSISTANT_LLM_BASE_URL")
    model: str = Field(default="qwen3.7-plus", validation_alias="ASSISTANT_LLM_MODEL")
    temperature: float = Field(default=0.2, ge=0, le=2, validation_alias="ASSISTANT_LLM_TEMPERATURE")
    timeout_seconds: float = Field(default=90, gt=0, validation_alias="ASSISTANT_LLM_TIMEOUT_SECONDS")
    max_tokens: int = Field(default=4096, gt=0, validation_alias="ASSISTANT_LLM_MAX_TOKENS")

    @model_validator(mode="after")
    def load_referenced_key(self):
        """本机可引用既有 env；只读取目标键，不复制或输出其它配置。"""
        if self.api_key.get_secret_value() or self.credentials_file is None:
            return self
        try:
            if self.credentials_file.stat().st_size > 128 * 1024:
                return self
            for line in self.credentials_file.read_text(encoding="utf-8-sig").splitlines():
                if line.startswith("DASHSCOPE_API_KEY="):
                    self.api_key = SecretStr(line.partition("=")[2].strip().strip("\"'"))
                    break
        except (OSError, UnicodeError):
            pass
        return self


class AssistantModelError(RuntimeError):
    """可安全返回给 UI 的错误，不包含上游原始响应。"""


def create_chat_model(settings: AssistantModelSettings, http_client: httpx.AsyncClient) -> ChatOpenAI:
    """创建全项目统一的 LangChain 千问客户端。"""
    return ChatOpenAI(
        model=settings.model,
        api_key=settings.api_key,
        base_url=settings.base_url.rstrip("/"),
        temperature=settings.temperature,
        timeout=settings.timeout_seconds,
        max_retries=0,
        streaming=True,
        stream_usage=False,
        http_async_client=http_client,
        # 百炼的 OpenAI 兼容接口使用这两个请求字段；LangChain 会把
        # extra_body 合并到请求顶层，同时不会暴露 reasoning_content。
        extra_body={"enable_thinking": False, "max_tokens": settings.max_tokens},
    )


class QwenStreamingModel:
    def __init__(self, settings: AssistantModelSettings, transport=None):
        self.settings = settings
        self.transport = transport

    async def stream(self, messages: list[dict[str, str]]) -> AsyncIterator[str]:
        async for event in self.stream_events(messages):
            if event["type"] == "delta":
                yield event["text"]
            elif event["type"] == "tool_calls":
                raise AssistantModelError("模型意外请求了未启用的工具")

    async def stream_events(self, messages: list[dict], tools: list[dict] | None = None) -> AsyncIterator[dict]:
        settings = self.settings
        if not settings.api_key.get_secret_value():
            raise AssistantModelError("千问密钥尚未配置")
        if not messages or any(m.get("role") not in {"system", "user", "assistant", "tool"}
                               or not isinstance(m.get("content", ""), str) for m in messages):
            raise AssistantModelError("模型消息格式无效")
        tool_calls: dict[int, dict[str, str]] = {}
        finish_reason: str | None = None
        try:
            async with httpx.AsyncClient(
                transport=self.transport,
                timeout=httpx.Timeout(settings.timeout_seconds, connect=10),
                follow_redirects=False,
            ) as http_client:
                model = create_chat_model(settings, http_client)
                runnable = model.bind_tools(tools, tool_choice="auto") if tools else model
                async for chunk in runnable.astream(messages):
                    if not isinstance(chunk, AIMessageChunk):
                        raise AssistantModelError("千问响应格式异常")
                    text = self._text(chunk.content)
                    if text:
                        yield {"type": "delta", "text": text}
                    for fragment in chunk.tool_call_chunks:
                        index = int(fragment.get("index") or 0)
                        current = tool_calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
                        if fragment.get("id"):
                            current["id"] = str(fragment["id"])
                        if fragment.get("name"):
                            current["name"] += str(fragment["name"])
                        arguments = fragment.get("args")
                        if arguments:
                            current["arguments"] += arguments if isinstance(arguments, str) else json.dumps(arguments)
                    reason = chunk.response_metadata.get("finish_reason")
                    if reason in {"stop", "tool_calls"}:
                        finish_reason = str(reason)
                    elif reason:
                        raise AssistantModelError("模型回答未完整结束，请缩小问题范围后重试")
            if finish_reason is None:
                raise AssistantModelError("模型连接提前结束，回答可能不完整")
            if finish_reason == "tool_calls":
                calls = [tool_calls[index] for index in sorted(tool_calls)]
                if not calls:
                    raise AssistantModelError("模型未提供工具调用参数")
                if any(not call["id"] or not call["name"] for call in calls):
                    raise AssistantModelError("模型工具调用格式异常")
                yield {"type": "tool_calls", "calls": calls}
            else:
                yield {"type": "done"}
        except (APITimeoutError, httpx.TimeoutException):
            raise AssistantModelError("千问响应超时，请稍后重试") from None
        except APIStatusError as exc:
            hints = {401: "千问密钥无效或无访问权限", 403: "千问模型访问被拒绝",
                     429: "千问调用频率或额度受限"}
            raise AssistantModelError(hints.get(exc.status_code, "千问服务请求失败")) from None
        except (APIConnectionError, httpx.HTTPError):
            raise AssistantModelError("千问连接失败，请检查网络") from None
        except AssistantModelError:
            raise
        except (ValueError, TypeError, AttributeError):
            raise AssistantModelError("千问响应格式异常") from None

    def _text(self, content) -> str:
        if isinstance(content, str):
            return content
        if not isinstance(content, list):
            return ""
        parts: list[str] = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type") in {"text", "output_text"}:
                parts.append(str(block.get("text") or ""))
        return "".join(parts)
