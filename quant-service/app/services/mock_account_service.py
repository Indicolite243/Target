"""显式TEST_MOCK模式使用的固定账户快照。

该模块仅用于自动化测试和无QMT环境下的接口联调，绝不会在QMT异常时自动启用。
"""

from datetime import datetime

from app.schemas.account import AccountSyncRequest


def sync_account(request: AccountSyncRequest) -> dict:
    """按照真实QMT同步协议返回一份确定性Mock数据。

    该函数只复刻字段契约，不创建持久账户、不维护订单簿，也不模拟QMT故障恢复。这样测试可以
    验证Java/Python协议，但不会把Mock误用成生产备用数据源。
    """

    # 时间使用本地时区ISO格式，保持与真实适配器响应一致。
    return {
        "snapshotTime": datetime.now().astimezone().isoformat(),
        "account": {
            # 金融数值使用十进制字符串，与真实QMT适配器保持一致，避免JSON浮点误差。
            "currency": "CNY",
            "totalAsset": "1023567.82",
            "cash": "235678.10",
            "availableCash": "235678.10",
            "frozenCash": "0.00",
            "marketValue": "787889.72",
        },
        # includePositions=false时保留字段但返回空数组，调用方无需判断字段是否存在。
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
        # source显式写mock，Spring和前端可以区分它与qmt真实数据。
        "source": "mock",
        # 保留warnings字段，让调用方始终按同一响应结构处理。
        "warnings": [],
    }
