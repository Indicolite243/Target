from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class InternalResponse(BaseModel):
    success: bool = True
    code: str = "OK"
    message: str = "success"
    data: Any = None
    traceId: str = ""
    processedAt: datetime = Field(default_factory=datetime.now)
    durationMs: int = 0
