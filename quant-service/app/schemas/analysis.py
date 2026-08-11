from decimal import Decimal

from pydantic import BaseModel, Field


class AnalysisPosition(BaseModel):
    symbol: str
    securityName: str = ""
    quantity: Decimal = Decimal("0")
    costPrice: Decimal = Decimal("0")
    lastPrice: Decimal = Decimal("0")
    marketValue: Decimal = Decimal("0")
    profitLoss: Decimal = Decimal("0")
    industry: str | None = ""
    region: str | None = ""


class PortfolioHistoryRequest(BaseModel):
    accountId: str
    startDate: str
    endDate: str
    cash: Decimal = Decimal("0")
    positions: list[AnalysisPosition] = Field(default_factory=list)
    refreshHistory: bool = True
