from datetime import datetime
from decimal import Decimal


def batch_quotes(symbols: list[str]) -> dict:
    now = datetime.now().astimezone().isoformat()
    quotes = []
    for index, symbol in enumerate(symbols):
        price = Decimal("9.78") + Decimal(index) / Decimal("10")
        quotes.append({
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
    return {"quotes": quotes, "missingSymbols": [], "marketStatus": "OPEN"}
