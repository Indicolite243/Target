"""组合净值序列的轻量风险指标计算。

本模块只负责可复现的数值计算，不读取QMT、Redis或MySQL；数据来源、阈值告警和结果持久化
由Spring负责，从而保持计算函数无外部副作用并便于单元测试。
"""

from decimal import Decimal

import numpy as np

from app.schemas.risk import RiskCalculationRequest


def calculate_risk(request: RiskCalculationRequest) -> dict:
    """计算年化波动率、最大回撤、本金最大损失与历史模拟VaR。"""

    # 接口边界使用Decimal，进入NumPy后转float以利用向量化计算。
    # Pydantic已经保证至少有两个点；Spring还会提前过滤有效交易日不足的请求。
    values = np.array([float(point.value) for point in request.portfolioValues], dtype=float)
    # 相邻净值的简单收益率：r_t = V_t / V_(t-1) - 1。
    # 例如100万变成101万，对应收益率0.01；风险计算使用小数而不是百分数字符串。
    returns = np.diff(values) / values[:-1]
    # running_max记录截至每一天的历史峰值，当前净值相对峰值的跌幅即回撤。
    running_max = np.maximum.accumulate(values)
    drawdowns = values / running_max - 1.0

    # 日收益率样本标准差乘sqrt(252)得到年化波动率；只有一个收益样本时不可估计。
    # ddof=1使用样本标准差；sqrt(252)假设一年约252个交易日。
    annual_volatility = float(np.std(returns, ddof=1) * np.sqrt(252)) if len(returns) > 1 else 0.0
    # 对外返回正数形式的损失率，便于Spring统一与正数阈值比较。
    max_drawdown = abs(float(np.min(drawdowns)))
    # 本金最大损失以首个净值为固定基准，与使用移动历史峰值的最大回撤含义不同。
    max_principal_loss = abs(float(min(np.min(values / values[0] - 1.0), 0.0)))
    # 历史模拟VaR取左尾分位数，再用平方根时间法则换算持有期。
    # 95%置信度对应收益率序列5%分位；若分位数为正，损失VaR按0处理。
    var_quantile = float(np.quantile(returns, 1 - float(request.confidenceLevel)))
    var_rate = abs(min(var_quantile, 0.0)) * np.sqrt(request.holdingPeriodDays)

    # 统一转成保留两位小数的百分数字符串，避免JSON浮点尾差。
    # 先从float文本恢复Decimal再乘100，避免直接Decimal(float)带入二进制尾差。
    percent = lambda value: str((Decimal(str(value)) * Decimal("100")).quantize(Decimal("0.01")))
    return {
        # metrics只返回基础统计量；阈值颜色、综合分和风险等级由Spring业务规则计算。
        "metrics": {
            "maxPrincipalLossRate": percent(max_principal_loss),
            "annualVolatilityRate": percent(annual_volatility),
            "maxDrawdownRate": percent(max_drawdown),
            "varRate": percent(var_rate),
        },
        # availability把“算出的0”与“样本不足只能返回0”区分开。
        "availability": {
            "maxPrincipalLoss": True,
            "volatility": len(returns) > 1,
            "maxDrawdown": True,
            "var": len(returns) > 1,
        },
        # 样本摘要用于MySQL结果审计，不复制整条净值数组。
        "sample": {
            "startDate": request.portfolioValues[0].date,
            "endDate": request.portfolioValues[-1].date,
            "tradingDays": len(request.portfolioValues),
        },
        "dataVersion": request.dataVersion,
        "ruleVersion": request.ruleVersion,
        "algorithmVersion": request.algorithmVersion,
        # 两个净值点只能形成一个收益样本，无法可靠估计样本波动率和VaR。
        "warnings": [] if len(returns) > 1 else ["样本过少，波动率和 VaR 仅供接口联调"],
    }
