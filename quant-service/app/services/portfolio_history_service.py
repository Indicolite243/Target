"""使用当前QMT持仓和历史日线重建组合分析曲线。

这里生成的是“当前持仓数量在历史价格下的静态回放”，不是券商真实历史净值。服务通过60秒
内存缓存和单飞锁抑制重复xtdata下载，输出同时包含组合曲线和按证券拆分的期间损益信息。
"""

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


# CACHE_LOCK只保护字典的短临界区；BUILD_LOCK保证同一进程同一时刻只执行一次重型行情重建。
_CACHE_LOCK = threading.Lock()
_BUILD_LOCK = threading.Lock()
# 缓存值保存“写入时的单调时钟值 + 结果”，单调时钟不受系统时间校准影响。
_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}
_CACHE_SECONDS = 60


def _decimal(value: Any) -> Decimal:
    """把QMT/Pandas弱类型数值安全转为Decimal；异常值按0处理。"""

    try:
        return Decimal(str(value))
    except Exception:
        return Decimal("0")


def _money(value: Decimal) -> str:
    """金额按四舍五入保留两位，并以字符串输出避免JSON浮点误差。"""

    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP).to_eng_string()


def _price(value: Decimal) -> str:
    """证券价格统一保留四位小数。"""

    return value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP).to_eng_string()


def _load_xtdata():
    """加载xtdata；虚拟环境找不到时再追加用户site-packages并重试。"""

    try:
        return importlib.import_module("xtquant.xtdata")
    except ModuleNotFoundError:
        # QMT通常把xtquant安装到用户Python目录，而服务运行在独立venv中。
        user_sites = site.getusersitepackages()
        candidates = [user_sites] if isinstance(user_sites, str) else list(user_sites)
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                # append而非prepend，确保venv内的常规依赖仍具有更高优先级。
                sys.path.append(candidate)
        importlib.invalidate_caches()
        return importlib.import_module("xtquant.xtdata")


def _date_key(value: Any) -> str:
    """把QMT毫秒时间戳、YYYYMMDD或普通日期统一为YYYY-MM-DD。"""

    text = str(value)
    # 索引可能包含分隔符，先提取数字以兼容多种xtdata版本。
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
    """根据证券代码后缀推断交易市场，仅用于前端分市场展示。"""

    suffix = symbol.upper().rsplit(".", 1)[-1]
    return {"SH": "上海市场", "SZ": "深圳市场", "BJ": "北京市场"}.get(suffix, "其他市场")


def _infer_industry(position: AnalysisPosition) -> str:
    """优先使用上游行业；缺失时按名称和代码规则提供可解释的展示分类。"""

    if (position.industry or "").strip():
        return (position.industry or "").strip()
    name = position.securityName.strip()
    symbol = position.symbol.upper()
    if "ETF" in name.upper() or symbol.split(".")[0].startswith(("15", "16", "50", "51", "52", "56", "58")):
        return "宽基ETF"
    # 规则按顺序匹配，第一个命中结果生效；它不是专业行业分类数据源。
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
    """把会影响结果的账户、区间、现金和持仓字段编码为缓存键。"""

    # 不包含refreshHistory：是否下载只影响数据新鲜度，不改变相同输入的结果协议。
    position_key = ",".join(
        f"{p.symbol}:{p.quantity}:{p.lastPrice}:{p.marketValue}" for p in request.positions
    )
    return f"{request.accountId}|{request.startDate}|{request.endDate}|{request.cash}|{position_key}"


def _read_cache(key: str) -> dict[str, Any] | None:
    """在锁内读取未过期缓存；不延长命中的TTL。"""

    with _CACHE_LOCK:
        cached = _CACHE.get(key)
        if cached and time.monotonic() - cached[0] <= _CACHE_SECONDS:
            return cached[1]
    return None


def _write_cache(key: str, value: dict[str, Any]) -> None:
    """写入结果，并在超过20项时淘汰最早写入的缓存。"""

    with _CACHE_LOCK:
        _CACHE[key] = (time.monotonic(), value)
        if len(_CACHE) > 20:
            # 缓存规模很小，线性寻找最旧项比引入额外LRU依赖更简单。
            oldest = min(_CACHE, key=lambda item: _CACHE[item][0])
            _CACHE.pop(oldest, None)


def build_portfolio_history(request: PortfolioHistoryRequest) -> dict[str, Any]:
    """带缓存和单飞保护地重建组合历史。"""

    key = _cache_key(request)
    # 第一遍无重型锁读取，命中时直接返回热缓存。
    cached = _read_cache(key)
    if cached is not None:
        return {**cached, "cached": True}
    # 重建涉及历史下载和多标的DataFrame处理，单飞锁避免并发请求重复压测QMT本地服务。
    with _BUILD_LOCK:
        # 等待锁期间其他请求可能已生成结果，因此进入后必须二次检查。
        cached = _read_cache(key)
        if cached is not None:
            return {**cached, "cached": True}
        return _build_portfolio_history(request)


def _build_portfolio_history(request: PortfolioHistoryRequest) -> dict[str, Any]:
    """执行xtdata下载、价格整理、逐日估值和持仓期间损益计算。"""

    key = _cache_key(request)
    cached = _read_cache(key)
    if cached is not None:
        return {**cached, "cached": True}

    # 空代码和非正持仓不产生市场价值，提前过滤避免无意义行情请求。
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

    # xtdata只在真正需要行情时加载，空仓请求不依赖本机QMT环境。
    xtdata = _load_xtdata()
    start_time = request.startDate.replace("-", "")
    end_time = request.endDate.replace("-", "")
    # 统一大写后批量读取，保持与QMT证券代码格式一致。
    symbols = [position.symbol.upper() for position in positions]
    warnings: list[str] = [
        "该曲线不是券商历史净值：它使用当前QMT持仓数量、当前现金和QMT前复权日线重建，仅用于分析参考。"
    ]

    if request.refreshHistory:
        # 逐标的下载，单只失败时继续使用本地缓存并把原因放入warnings。
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
                    # 兼容不支持关键字参数的旧xtquant版本。
                    xtdata.download_history_data(symbol, "1d", start_time, end_time)
            except Exception as exc:
                warnings.append(f"{symbol}日线更新失败，已使用本地缓存：{exc}")

    # 前复权收盘价用于跨期比较；fill_data让停牌日沿用最近价格以保持组合曲线连续。
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

    # 转换成symbol -> date -> Decimal的简单索引，后续逐日估值无需反复访问Pandas对象。
    closes_by_symbol: dict[str, dict[str, Decimal]] = {}
    all_dates: set[str] = set()
    for position in positions:
        symbol = position.symbol.upper()
        frame = raw_market.get(symbol)
        close_map: dict[str, Decimal] = {}
        if frame is not None and not getattr(frame, "empty", True) and "close" in frame:
            # 只接受正收盘价，0、NaN和异常文本不能参与估值。
            for index, value in frame["close"].items():
                close = _decimal(value)
                if close > 0:
                    date = _date_key(index)
                    close_map[date] = close
                    all_dates.add(date)
        closes_by_symbol[symbol] = close_map
        if not close_map:
            warnings.append(f"{symbol}在所选区间内没有日线，已使用成本价/最新价")

    # 即使QMT区间末日无行情，也显式加入结束日期并在该日使用当前真实市值校准终点。
    end_date = request.endDate
    all_dates.add(end_date)
    # 多只证券可能交易日集合不同，使用并集后排序，再通过last_known为缺失日向前填充。
    ordered_dates = sorted(date for date in all_dates if request.startDate <= date <= request.endDate)
    if not ordered_dates:
        ordered_dates = [end_date]

    # 每只证券初始回退价优先成本价，其次最新价；随后按日期向前填充已知收盘价。
    last_known = {
        position.symbol.upper(): (position.costPrice if position.costPrice > 0 else position.lastPrice)
        for position in positions
    }
    # 金额最终仍以字符串输出，避免FastAPI JSON编码时重新引入浮点误差。
    portfolio_values: list[dict[str, str]] = []
    for date in ordered_dates:
        # 每日组合总值 = 固定当前现金 + 各当前持仓数量 × 当日/最近价格。
        total = request.cash
        for position in positions:
            symbol = position.symbol.upper()
            price = closes_by_symbol[symbol].get(date, last_known[symbol])
            if price > 0:
                last_known[symbol] = price
            total += position.quantity * price
        if date == end_date:
            # 末日使用Spring传入的当前市值对齐页面实时快照，避免行情缓存时点差。
            total = request.cash + sum(
                (position.marketValue if position.marketValue > 0 else position.quantity * position.lastPrice)
                for position in positions
            )
        portfolio_values.append({"date": date, "value": _money(total)})

    position_history: list[dict[str, Any]] = []
    for position in positions:
        symbol = position.symbol.upper()
        close_map = closes_by_symbol[symbol]
        # 期间盈亏使用区间首个有效价格到当前最新价，不修改原始持仓累计盈亏字段。
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

    # 返回字段同时服务风险曲线、资产对比表和行业/地区归因展示。
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
