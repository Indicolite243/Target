from __future__ import annotations

import importlib
import site
import sys
import threading
import time
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from app.schemas.analysis import AnalysisPosition, PortfolioHistoryRequest


_CACHE_LOCK = threading.Lock()
_BUILD_LOCK = threading.Lock()
_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}
_CACHE_SECONDS = 60


def _decimal(value: Any) -> Decimal:
    try:
        return Decimal(str(value))
    except Exception:
        return Decimal("0")


def _money(value: Decimal) -> str:
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP).to_eng_string()


def _price(value: Decimal) -> str:
    return value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP).to_eng_string()


def _load_xtdata():
    try:
        return importlib.import_module("xtquant.xtdata")
    except ModuleNotFoundError:
        user_sites = site.getusersitepackages()
        candidates = [user_sites] if isinstance(user_sites, str) else list(user_sites)
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                sys.path.append(candidate)
        importlib.invalidate_caches()
        return importlib.import_module("xtquant.xtdata")


def _date_key(value: Any) -> str:
    text = str(value)
    digits = "".join(ch for ch in text if ch.isdigit())
    if len(digits) >= 13:
        try:
            return datetime.fromtimestamp(int(digits[:13]) / 1000).strftime("%Y-%m-%d")
        except (OSError, OverflowError, ValueError):
            pass
    if len(digits) >= 8:
        return f"{digits[:4]}-{digits[4:6]}-{digits[6:8]}"
    return text[:10]


def _infer_region(symbol: str) -> str:
    suffix = symbol.upper().rsplit(".", 1)[-1]
    return {"SH": "上海市场", "SZ": "深圳市场", "BJ": "北京市场"}.get(suffix, "其他市场")


def _infer_industry(position: AnalysisPosition) -> str:
    if (position.industry or "").strip():
        return (position.industry or "").strip()
    name = position.securityName.strip()
    symbol = position.symbol.upper()
    if "ETF" in name.upper() or symbol.split(".")[0].startswith(("15", "16", "50", "51", "52", "56", "58")):
        return "宽基ETF"
    keyword_groups = (
        (("银行",), "银行"),
        (("茅台", "酒", "食品", "饮料"), "食品饮料"),
        (("机场", "国航", "顺丰", "航空", "铁路"), "交通运输"),
        (("电力", "能源", "三峡"), "公用事业"),
        (("移动", "通信", "有线"), "通信"),
        (("制药", "医疗", "医药"), "医药生物"),
        (("宁德", "电池", "光伏"), "电力设备"),
        (("寒武纪", "立讯", "电子", "芯片"), "电子"),
        (("科技", "汉王", "软件"), "计算机"),
        (("建材", "建设"), "建筑材料"),
        (("王府井", "商贸"), "商贸零售"),
        (("汽车", "东风"), "汽车"),
        (("地产", "大悦城"), "房地产"),
        (("纺织", "服饰", "安妮", "华升"), "纺织服饰"),
    )
    for keywords, industry in keyword_groups:
        if any(keyword in name for keyword in keywords):
            return industry
    return "其他"


def _cache_key(request: PortfolioHistoryRequest) -> str:
    position_key = ",".join(
        f"{p.symbol}:{p.quantity}:{p.lastPrice}:{p.marketValue}" for p in request.positions
    )
    return f"{request.accountId}|{request.startDate}|{request.endDate}|{request.cash}|{position_key}"


def _read_cache(key: str) -> dict[str, Any] | None:
    with _CACHE_LOCK:
        cached = _CACHE.get(key)
        if cached and time.monotonic() - cached[0] <= _CACHE_SECONDS:
            return cached[1]
    return None


def _write_cache(key: str, value: dict[str, Any]) -> None:
    with _CACHE_LOCK:
        _CACHE[key] = (time.monotonic(), value)
        if len(_CACHE) > 20:
            oldest = min(_CACHE, key=lambda item: _CACHE[item][0])
            _CACHE.pop(oldest, None)


def build_portfolio_history(request: PortfolioHistoryRequest) -> dict[str, Any]:
    key = _cache_key(request)
    cached = _read_cache(key)
    if cached is not None:
        return {**cached, "cached": True}
    with _BUILD_LOCK:
        cached = _read_cache(key)
        if cached is not None:
            return {**cached, "cached": True}
        return _build_portfolio_history(request)


def _build_portfolio_history(request: PortfolioHistoryRequest) -> dict[str, Any]:
    key = _cache_key(request)
    cached = _read_cache(key)
    if cached is not None:
        return {**cached, "cached": True}

    positions = [position for position in request.positions if position.symbol and position.quantity > 0]
    if not positions:
        result = {
            "portfolioValues": [],
            "positionHistory": [],
            "warnings": ["当前账户没有可用于历史重建的持仓"],
            "source": "qmt_position_reconstruction",
            "calculationMethod": "当前QMT持仓数量 × QMT前复权日收盘价 + 当前现金",
            "cached": False,
        }
        _write_cache(key, result)
        return result

    xtdata = _load_xtdata()
    start_time = request.startDate.replace("-", "")
    end_time = request.endDate.replace("-", "")
    symbols = [position.symbol.upper() for position in positions]
    warnings: list[str] = [
        "该曲线不是券商历史净值：它使用当前QMT持仓数量、当前现金和QMT前复权日线重建，仅用于分析参考。"
    ]

    if request.refreshHistory:
        for symbol in symbols:
            try:
                try:
                    xtdata.download_history_data(
                        symbol,
                        period="1d",
                        start_time=start_time,
                        end_time=end_time,
                        incrementally=True,
                    )
                except TypeError:
                    xtdata.download_history_data(symbol, "1d", start_time, end_time)
            except Exception as exc:
                warnings.append(f"{symbol}日线更新失败，已使用本地缓存：{exc}")

    raw_market = xtdata.get_market_data_ex(
        ["close"],
        symbols,
        period="1d",
        start_time=start_time,
        end_time=end_time,
        count=-1,
        dividend_type="front",
        fill_data=True,
    ) or {}

    closes_by_symbol: dict[str, dict[str, Decimal]] = {}
    all_dates: set[str] = set()
    for position in positions:
        symbol = position.symbol.upper()
        frame = raw_market.get(symbol)
        close_map: dict[str, Decimal] = {}
        if frame is not None and not getattr(frame, "empty", True) and "close" in frame:
            for index, value in frame["close"].items():
                close = _decimal(value)
                if close > 0:
                    date = _date_key(index)
                    close_map[date] = close
                    all_dates.add(date)
        closes_by_symbol[symbol] = close_map
        if not close_map:
            warnings.append(f"{symbol}在所选区间内没有日线，已使用成本价/最新价")

    end_date = request.endDate
    all_dates.add(end_date)
    ordered_dates = sorted(date for date in all_dates if request.startDate <= date <= request.endDate)
    if not ordered_dates:
        ordered_dates = [end_date]

    last_known = {
        position.symbol.upper(): (position.costPrice if position.costPrice > 0 else position.lastPrice)
        for position in positions
    }
    portfolio_values: list[dict[str, str]] = []
    for date in ordered_dates:
        total = request.cash
        for position in positions:
            symbol = position.symbol.upper()
            price = closes_by_symbol[symbol].get(date, last_known[symbol])
            if price > 0:
                last_known[symbol] = price
            total += position.quantity * price
        if date == end_date:
            total = request.cash + sum(
                (position.marketValue if position.marketValue > 0 else position.quantity * position.lastPrice)
                for position in positions
            )
        portfolio_values.append({"date": date, "value": _money(total)})

    position_history: list[dict[str, Any]] = []
    for position in positions:
        symbol = position.symbol.upper()
        close_map = closes_by_symbol[symbol]
        historical_prices = [close_map[date] for date in ordered_dates if date in close_map]
        start_price = historical_prices[0] if historical_prices else position.costPrice
        if start_price <= 0:
            start_price = position.lastPrice
        end_price = position.lastPrice
        if end_price <= 0 and historical_prices:
            end_price = historical_prices[-1]
        market_value = position.marketValue if position.marketValue > 0 else end_price * position.quantity
        pnl_amount = (end_price - start_price) * position.quantity
        position_history.append({
            "symbol": symbol,
            "securityName": position.securityName or symbol,
            "quantity": str(position.quantity),
            "costPrice": _price(position.costPrice),
            "startPrice": _price(start_price),
            "endPrice": _price(end_price),
            "marketValue": _money(market_value),
            "profitLoss": _money(position.profitLoss),
            "periodPnl": _money(pnl_amount),
            "industry": _infer_industry(position),
            "region": (position.region or "").strip() or _infer_region(symbol),
        })

    result = {
        "portfolioValues": portfolio_values,
        "positionHistory": position_history,
        "warnings": warnings,
        "source": "qmt_position_reconstruction",
        "calculationMethod": "当前QMT持仓数量 × QMT前复权日收盘价 + 当前现金",
        "rangeStart": portfolio_values[0]["date"],
        "rangeEnd": portfolio_values[-1]["date"],
        "tradingDays": len(portfolio_values),
        "cached": False,
    }
    _write_cache(key, result)
    return result
