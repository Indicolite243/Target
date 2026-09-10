"""组合历史重建与归因计算所需的数据模型。"""

from decimal import Decimal

from pydantic import BaseModel, Field


class AnalysisPosition(BaseModel):
    """从Spring传入的单只当前持仓，用于重建历史组合市值。"""

    symbol: str
    # 名称、行业和地区只用于展示与分组，不参与金额计算。
    securityName: str = ""
    # 金融数量和金额均使用Decimal，避免二进制浮点在接口边界产生误差。
    quantity: Decimal = Decimal("0")
    costPrice: Decimal = Decimal("0")
    lastPrice: Decimal = Decimal("0")
    marketValue: Decimal = Decimal("0")
    profitLoss: Decimal = Decimal("0")
    industry: str | None = ""
    region: str | None = ""


class PortfolioHistoryRequest(BaseModel):
    """按当前持仓和QMT历史行情重建指定日期区间组合曲线的请求。"""

    # 账号只参与缓存键和审计，历史行情仍按positions中的证券代码读取。
    accountId: str
    # 日期采用YYYY-MM-DD字符串，进入服务后再转换为QMT需要的YYYYMMDD。
    startDate: str
    endDate: str
    # 当前可用现金在每个历史交易日保持不变；结果不是券商真实历史净值。
    cash: Decimal = Decimal("0")
    # default_factory保证不同请求不会共享同一个可变列表。
    positions: list[AnalysisPosition] = Field(default_factory=list)
    # 为false时只使用本机QMT缓存，减少下载等待。
    refreshHistory: bool = True
