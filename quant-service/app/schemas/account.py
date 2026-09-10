"""账户同步接口的请求模型。"""

from datetime import datetime

from pydantic import BaseModel


class AccountSyncRequest(BaseModel):
    """Spring请求QMT账户快照时使用的稳定协议。"""

    # Spring侧账户主键使用字符串传输，避免Snowflake ID经过JavaScript时精度丢失。
    accountId: str
    # QMT真实资金账号，用于防止请求误操作另一个已登录账户。
    externalAccountId: str
    # 当前仅允许SIMULATION，实盘接入必须另行增加安全控制。
    environment: str = "SIMULATION"
    # 控制是否在响应中返回持仓明细；账户盈亏计算仍可能在内部读取持仓。
    includePositions: bool = True
    # 两秒实时链路会关闭此项，避免每次刷新额外查询委托和成交。
    includeOrders: bool = True
    # 为未来增量同步预留的时间游标；当前QMT实现读取完整快照。
    since: datetime | None = None
