from decimal import Decimal

from pydantic import BaseModel, Field


class PortfolioPoint(BaseModel):
    date: str
    value: Decimal


class RiskCalculationRequest(BaseModel):
    accountId: str
    dataVersion: str
    ruleVersion: int
    algorithmVersion: str = "risk-v1.0.0"
    confidenceLevel: Decimal = Decimal("0.95")
    holdingPeriodDays: int = 1
    portfolioValues: list[PortfolioPoint] = Field(min_length=2)
    positions: list[dict] = []
    thresholds: dict[str, Decimal]
