from datetime import datetime

from pydantic import BaseModel


class AccountSyncRequest(BaseModel):
    accountId: str
    externalAccountId: str
    environment: str = "SIMULATION"
    includePositions: bool = True
    includeOrders: bool = True
    since: datetime | None = None
