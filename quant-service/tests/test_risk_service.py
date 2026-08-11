from decimal import Decimal

from app.schemas.risk import PortfolioPoint, RiskCalculationRequest
from app.services.risk_service import calculate_risk


def test_risk_result_contains_versions():
    request = RiskCalculationRequest(
        accountId="1",
        dataVersion="snapshot-1",
        ruleVersion=1,
        portfolioValues=[
            PortfolioPoint(date="2026-08-01", value=Decimal("100")),
            PortfolioPoint(date="2026-08-02", value=Decimal("98")),
            PortfolioPoint(date="2026-08-03", value=Decimal("102")),
        ],
        thresholds={"maxDrawdownRate": Decimal("15")},
    )
    result = calculate_risk(request)
    assert result["dataVersion"] == "snapshot-1"
    assert result["ruleVersion"] == 1
