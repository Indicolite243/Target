from decimal import Decimal

import numpy as np

from app.schemas.risk import RiskCalculationRequest


def calculate_risk(request: RiskCalculationRequest) -> dict:
    values = np.array([float(point.value) for point in request.portfolioValues], dtype=float)
    returns = np.diff(values) / values[:-1]
    running_max = np.maximum.accumulate(values)
    drawdowns = values / running_max - 1.0

    annual_volatility = float(np.std(returns, ddof=1) * np.sqrt(252)) if len(returns) > 1 else 0.0
    max_drawdown = abs(float(np.min(drawdowns)))
    max_principal_loss = abs(float(min(np.min(values / values[0] - 1.0), 0.0)))
    var_quantile = float(np.quantile(returns, 1 - float(request.confidenceLevel)))
    var_rate = abs(min(var_quantile, 0.0)) * np.sqrt(request.holdingPeriodDays)

    percent = lambda value: str((Decimal(str(value)) * Decimal("100")).quantize(Decimal("0.01")))
    return {
        "metrics": {
            "maxPrincipalLossRate": percent(max_principal_loss),
            "annualVolatilityRate": percent(annual_volatility),
            "maxDrawdownRate": percent(max_drawdown),
            "varRate": percent(var_rate),
        },
        "availability": {
            "maxPrincipalLoss": True,
            "volatility": len(returns) > 1,
            "maxDrawdown": True,
            "var": len(returns) > 1,
        },
        "sample": {
            "startDate": request.portfolioValues[0].date,
            "endDate": request.portfolioValues[-1].date,
            "tradingDays": len(request.portfolioValues),
        },
        "dataVersion": request.dataVersion,
        "ruleVersion": request.ruleVersion,
        "algorithmVersion": request.algorithmVersion,
        "warnings": [] if len(returns) > 1 else ["样本过少，波动率和 VaR 仅供接口联调"],
    }
