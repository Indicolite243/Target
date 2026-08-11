from decimal import Decimal

from pydantic import BaseModel


class SubmitOrderRequest(BaseModel):
    orderId: str
    clientOrderNo: str
    externalAccountId: str
    symbol: str
    side: str
    orderType: str
    quantity: Decimal
    price: Decimal | None = None
    environment: str


class CancelOrderRequest(BaseModel):
    orderId: str
    clientOrderNo: str
    externalOrderNo: str = ""
    externalAccountId: str
    environment: str
    idempotencyKey: str | None = None
