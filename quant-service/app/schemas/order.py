"""下单和撤单内部请求模型。

订单幂等、用户权限和MySQL状态机由Spring负责；这里仅声明传给QMT适配器的稳定字段。
"""

from decimal import Decimal

from pydantic import BaseModel


class SubmitOrderRequest(BaseModel):
    """Spring已经落库的本地订单意图。"""

    # 三种订单标识分别服务于本地追踪、幂等关联和券商对账。
    orderId: str
    clientOrderNo: str
    # 必须与QMT当前订阅账号一致，否则适配器拒绝发送。
    externalAccountId: str
    symbol: str
    side: str
    orderType: str
    # 数量和价格使用Decimal接收，发送到xtquant时才做受控类型转换。
    quantity: Decimal
    price: Decimal | None = None
    environment: str


class CancelOrderRequest(BaseModel):
    """撤销一笔已经取得QMT外部订单号的委托。"""

    orderId: str
    clientOrderNo: str
    # QMT撤单真正依赖externalOrderNo；为空或非数字会被适配器拒绝。
    externalOrderNo: str = ""
    externalAccountId: str
    environment: str
    # 幂等键由Spring审计和去重，FastAPI只透传业务语义。
    idempotencyKey: str | None = None
