"""LangChain Agent 编排器：模型负责决策，业务工具通过 Target MCP 执行。"""
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator

import httpx
from langchain.agents import create_agent
from langchain.agents.middleware import ModelCallLimitMiddleware, ToolCallLimitMiddleware
from langchain_core.messages import AIMessage, AIMessageChunk, ToolMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain_mcp_adapters.interceptors import MCPToolCallRequest, MCPToolCallResult
from openai import APIConnectionError, APIStatusError, APITimeoutError
from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.assistant.llm import (
    AssistantModelError,
    AssistantModelSettings,
    create_chat_model,
)


class AssistantAgentSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(Path(__file__).resolve().parents[3] / ".env",
                  Path(__file__).resolve().parents[3] / ".env.assistant.local"),
        extra="ignore",
    )
    mcp_base_url: str = Field(default="http://127.0.0.1:8080", validation_alias="ASSISTANT_MCP_BASE_URL")
    mcp_endpoint: str = Field(default="/internal/mcp", validation_alias="ASSISTANT_MCP_ENDPOINT")
    mcp_token: SecretStr = Field(
        default=SecretStr("development-internal-token"),
        validation_alias=AliasChoices("ASSISTANT_MCP_INTERNAL_TOKEN", "QUANT_INTERNAL_TOKEN"),
    )
    mcp_timeout_seconds: float = Field(
        default=45, ge=5, le=300, validation_alias="ASSISTANT_MCP_REQUEST_TIMEOUT_SECONDS")
    max_model_calls: int = Field(default=3, ge=2, le=6, validation_alias="ASSISTANT_AGENT_MAX_MODEL_CALLS")
    max_tool_calls: int = Field(default=5, ge=1, le=10, validation_alias="ASSISTANT_AGENT_MAX_TOOL_CALLS")

    @property
    def mcp_url(self) -> str:
        return self.mcp_base_url.rstrip("/") + "/" + self.mcp_endpoint.strip("/")


@dataclass(frozen=True)
class TargetAgentContext:
    user_id: int
    conversation_id: str
    request_id: str
    trace_id: str


class TargetContextHeaders:
    USER_ID = "X-Target-User-Id"
    CONVERSATION_ID = "X-Target-Conversation-Id"
    REQUEST_ID = "X-Target-Request-Id"
    TRACE_ID = "X-Trace-Id"

    async def __call__(self, request: MCPToolCallRequest, handler) -> MCPToolCallResult:
        runtime = request.runtime
        context = getattr(runtime, "context", None)
        if not isinstance(context, TargetAgentContext):
            raise AssistantModelError("Agent 工具调用缺少可信身份上下文")
        headers = {
            self.USER_ID: str(context.user_id),
            self.CONVERSATION_ID: context.conversation_id,
            self.REQUEST_ID: context.request_id,
            self.TRACE_ID: context.trace_id,
        }
        return await handler(request.override(headers=headers))


class TargetLangChainAgent:
    def __init__(self, model_settings: AssistantModelSettings,
                 agent_settings: AssistantAgentSettings, transport=None):
        self.model_settings = model_settings
        self.agent_settings = agent_settings
        self.transport = transport

    async def stream_events(self, messages: list[dict], context: TargetAgentContext) -> AsyncIterator[dict]:
        if not self.model_settings.api_key.get_secret_value():
            raise AssistantModelError("千问密钥尚未配置")
        system_prompt = next((str(item.get("content") or "") for item in messages
                              if item.get("role") == "system"), "")
        conversation = [item for item in messages if item.get("role") != "system"]
        if not conversation:
            raise AssistantModelError("Agent 消息格式无效")

        try:
            async with httpx.AsyncClient(
                transport=self.transport,
                timeout=httpx.Timeout(self.model_settings.timeout_seconds, connect=10),
                follow_redirects=False,
            ) as http_client:
                model = create_chat_model(self.model_settings, http_client)
                client = MultiServerMCPClient({
                    "target": {
                        "transport": "streamable_http",
                        "url": self.agent_settings.mcp_url,
                        "headers": {"X-Internal-Token": self.agent_settings.mcp_token.get_secret_value()},
                        "timeout": self.agent_settings.mcp_timeout_seconds,
                        "sse_read_timeout": self.agent_settings.mcp_timeout_seconds,
                    }
                }, tool_interceptors=[TargetContextHeaders()], handle_tool_errors=True)
                tools = await client.get_tools(server_name="target")
                if not tools:
                    raise AssistantModelError("MCP 工具服务暂时不可用")
                agent = create_agent(
                    model=model,
                    tools=tools,
                    system_prompt=system_prompt,
                    context_schema=TargetAgentContext,
                    middleware=[
                        ModelCallLimitMiddleware(
                            run_limit=self.agent_settings.max_model_calls,
                            exit_behavior="error",
                        ),
                        ToolCallLimitMiddleware(
                            run_limit=self.agent_settings.max_tool_calls,
                            exit_behavior="error",
                        ),
                    ],
                    name="target-investment-agent",
                )
                saw_answer = False
                reading_data = False
                async for event in agent.astream(
                    {"messages": conversation},
                    context=context,
                    stream_mode=["messages"],
                    version="v2",
                ):
                    if event.get("type") != "messages":
                        continue
                    message, metadata = event["data"]
                    if isinstance(message, (AIMessage, AIMessageChunk)):
                        if (getattr(message, "tool_call_chunks", None)
                                or getattr(message, "tool_calls", None)):
                            if not reading_data:
                                reading_data = True
                                yield {"type": "status", "phase": "READING_DATA",
                                       "message": "正在通过 MCP 读取会话固定的数据"}
                            continue
                        text = self._text(message.content)
                        if text:
                            saw_answer = True
                            yield {"type": "delta", "text": text}
                    elif isinstance(message, ToolMessage):
                        structured = self._structured_content(message.artifact)
                        if structured:
                            yield {"type": "tool_result", "metadata": structured}
                        yield {"type": "status", "phase": "GENERATING",
                               "message": "正在结合工具结果生成回答"}
                if not saw_answer:
                    raise AssistantModelError("模型没有返回回答")
                yield {"type": "done"}
        except AssistantModelError:
            raise
        except (APITimeoutError, httpx.TimeoutException):
            raise AssistantModelError("千问响应超时，请稍后重试") from None
        except APIStatusError as exc:
            hints = {401: "千问密钥无效或无访问权限", 403: "千问模型访问被拒绝",
                     429: "千问调用频率或额度受限"}
            raise AssistantModelError(hints.get(exc.status_code, "千问服务请求失败")) from None
        except (APIConnectionError, httpx.HTTPError):
            raise AssistantModelError("Agent 内部服务连接失败，请稍后重试") from None
        except Exception as exc:
            message = str(exc)
            if "limit" in message.lower():
                raise AssistantModelError("Agent 已达到受控执行步数上限") from None
            raise AssistantModelError("Agent 执行失败，请稍后重试") from None

    def _structured_content(self, artifact) -> dict:
        if not isinstance(artifact, dict):
            return {}
        value = artifact.get("structured_content")
        return value if isinstance(value, dict) else {}

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
