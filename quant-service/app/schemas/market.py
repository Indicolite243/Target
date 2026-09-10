"""批量行情查询请求模型。"""

from pydantic import BaseModel, Field


class QuoteRequest(BaseModel):
    """一次最多查询100只证券，限制请求规模以保护QMT SDK。"""

    symbols: list[str] = Field(min_length=1, max_length=100)
    # fields为协议兼容字段；当前QMT适配器固定返回票据与五档行情。
    fields: list[str] = []
    # Mock模式的兼容字段，QMT模式下数据源由服务配置决定。
    preferredSource: str = "MOCK"
    environment: str = "SIMULATION"
