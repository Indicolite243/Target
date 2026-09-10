"""TEST_MOCK模式的确定性批量行情生成器。"""

from datetime import datetime
from decimal import Decimal


def batch_quotes(symbols: list[str]) -> dict:
    """按输入顺序生成递增价格，便于前端批量行情联调和断言。

    本函数不会联网、不会读取QMT，也不模拟停牌和交易时段；它只在显式TEST_MOCK模式提供
    字段完整、结果可重复的行情协议样本。
    """

    # 一批行情共用同一时间戳，表达它们属于同一次快照。
    now = datetime.now().astimezone().isoformat()
    quotes = []
    for index, symbol in enumerate(symbols):
        # 每个标的增加0.1元，使测试可以验证返回顺序而不是拿到完全相同数据。
        price = Decimal("9.78") + Decimal(index) / Decimal("10")
        quotes.append({
            # symbol/name保持输入值，避免Mock引入额外证券主数据依赖。
            "symbol": symbol,
            "name": symbol,
            "lastPrice": str(price),
            "openPrice": str(price - Decimal("0.08")),
            "highPrice": str(price + Decimal("0.05")),
            "lowPrice": str(price - Decimal("0.10")),
            "previousClose": str(price - Decimal("0.14")),
            "volume": "1000000",
            "amount": str(price * Decimal("1000000")),
            "dataTime": now,
            "source": "mock",
        })
    # 所有输入都能生成结果，因此没有missingSymbols；市场状态固定OPEN便于页面联调。
    return {"quotes": quotes, "missingSymbols": [], "marketStatus": "OPEN"}
