"""所有内部接口统一使用的响应外壳。"""

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class InternalResponse(BaseModel):
    """保持FastAPI与Spring QuantClient之间一致的成功响应字段。"""

    success: bool = True
    code: str = "OK"
    message: str = "success"
    # data允许各业务返回不同结构，具体字段由对应请求模型和服务约定。
    data: Any = None
    # traceId从Spring透传，用于串联Java和Python日志。
    traceId: str = ""
    # 每个响应独立生成处理时间，不能在类定义时提前计算。
    processedAt: datetime = Field(default_factory=datetime.now)
    # 仅统计FastAPI处理耗时，单位毫秒，不包含Spring和浏览器网络时间。
    durationMs: int = 0
