"""风险指标计算请求模型。"""

from decimal import Decimal

from pydantic import BaseModel, Field


class PortfolioPoint(BaseModel):
    """某个交易日的组合总资产观测值。"""

    # 日期由Spring统一生成，保留字符串避免在服务间发生时区转换。
    date: str
    # 总资产必须保持十进制精度，进入数值引擎前才显式转换为NumPy float。
    value: Decimal


class RiskCalculationRequest(BaseModel):
    """由Spring从MySQL历史快照组装后提交的风险计算输入。"""

    # accountId只用于审计和版本关联，Python不会据此访问数据库或QMT。
    accountId: str
    # dataVersion用于证明本次结果对应哪一版输入快照。
    dataVersion: str
    # 规则版本属于Spring告警阈值；算法版本属于Python计算实现。
    ruleVersion: int
    # 算法版本随Python公式变化；Spring会把它与结果一起持久化。
    algorithmVersion: str = "risk-v1.0.0"
    # VaR置信度默认95%，持有期通过平方根时间法则换算。
    confidenceLevel: Decimal = Decimal("0.95")
    holdingPeriodDays: int = 1
    # 至少两个净值点才能形成一个收益率样本。
    portfolioValues: list[PortfolioPoint] = Field(min_length=2)
    # positions和thresholds保留给上层规则判断；本文件只计算基础统计指标。
    positions: list[dict] = []
    # 阈值随请求传入用于协议自描述；最终分级仍由Spring规则层完成。
    thresholds: dict[str, Decimal]
