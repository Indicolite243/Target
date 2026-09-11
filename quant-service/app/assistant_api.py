"""仅供已认证的 Java 服务调用的模型流；不查询账户，也不执行工具。"""
import asyncio
import json
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.services.assistant_llm import AssistantModelError, AssistantModelSettings, QwenStreamingModel

router = APIRouter()


class ModelMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=32000)


class StreamRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    messages: list[ModelMessage] = Field(min_length=1, max_length=80)

    @model_validator(mode="after")
    def bound_context(self):
        if sum(len(message.content) for message in self.messages) > 120000:
            raise ValueError("conversation context too large")
        if self.messages[-1].role != "user":
            raise ValueError("last message must be a user question")
        return self


def get_model() -> QwenStreamingModel:
    return QwenStreamingModel(AssistantModelSettings())


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/assistant/stream")
async def assistant_stream(payload: StreamRequest, model=Depends(get_model)):
    if not model.settings.api_key.get_secret_value():
        raise HTTPException(status_code=503, detail="千问密钥尚未配置")

    async def events():
        yield sse("status", {"phase": "GENERATING", "message": "正在生成回答"})
        # 对端断开时 StreamingResponse 会取消生成器；必须让取消传播到 HTTP 客户端。
        stream = model.stream([message.model_dump() for message in payload.messages])
        try:
            async with asyncio.timeout(300):
                async for text in stream:
                    yield sse("delta", {"text": text})
            yield sse("done", {"status": "COMPLETED"})
        except AssistantModelError as exc:
            yield sse("error", {"status": "FAILED", "message": str(exc)})
        except TimeoutError:
            yield sse("error", {"status": "FAILED", "message": "回答生成超过时间限制，已生成内容可能不完整"})
        except asyncio.CancelledError:
            raise
        except Exception:
            # 不向客户端传递堆栈、环境变量、请求内容或上游错误体。
            yield sse("error", {"status": "FAILED", "message": "模型服务异常，已生成内容可能不完整"})
        finally:
            await stream.aclose()

    return StreamingResponse(events(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache, no-store", "X-Accel-Buffering": "no",
    })
