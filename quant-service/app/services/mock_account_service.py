from datetime import datetime

from app.schemas.account import AccountSyncRequest


def sync_account(request: AccountSyncRequest) -> dict:
    return {
        "snapshotTime": datetime.now().astimezone().isoformat(),
        "account": {
            "currency": "CNY",
            "totalAsset": "1023567.82",
            "cash": "235678.10",
            "availableCash": "235678.10",
            "frozenCash": "0.00",
            "marketValue": "787889.72",
        },
        "positions": [
            {
                "symbol": "600000.SH",
                "securityName": "浦发银行",
                "quantity": "10000",
                "availableQuantity": "10000",
                "costPrice": "9.5200",
                "lastPrice": "9.7800",
                "marketValue": "97800.00",
            }
        ] if request.includePositions else [],
        "orders": [],
        "executions": [],
        "source": "mock",
        "warnings": [],
    }
