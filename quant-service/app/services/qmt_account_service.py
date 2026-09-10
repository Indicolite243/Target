"""MiniQMT/xtquant账户、行情和模拟交易适配器。

本文件是量化服务与券商SDK之间的唯一边界：集中处理动态依赖加载、长连接复用、账户发现与
订阅、线程串行化、不同SDK版本字段兼容、金额序列化、异常分类和敏感信息脱敏。Spring只接收
稳定字典协议，不直接依赖任何xtquant对象。
"""

from __future__ import annotations

import importlib
import site
import sys
import threading
import time
from datetime import datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from types import ModuleType
from typing import Any, Callable

from app.config import Settings
from app.schemas.account import AccountSyncRequest
from app.schemas.order import CancelOrderRequest, SubmitOrderRequest


class QmtAdapterError(RuntimeError):
    """允许转换为内部HTTP错误的QMT适配器异常基类。"""


class QmtConfigurationError(QmtAdapterError):
    """QMT目录、账号或读写开关配置不满足请求要求。"""

    pass


class QmtNotInstalledError(QmtAdapterError):
    """当前Python解释器及用户site-packages中均无法加载xtquant。"""

    pass


class QmtConnectionError(QmtAdapterError):
    """Trader创建、启动或连接MiniQMT失败。"""

    pass


class QmtSubscriptionError(QmtAdapterError):
    """找不到指定资金账号，或账号订阅失败。"""

    pass


class QmtQueryError(QmtAdapterError):
    """资产、持仓、委托或成交查询调用失败。"""

    pass


class QmtTradeError(QmtAdapterError):
    """下单/撤单参数错误、账号不匹配或QMT明确拒绝。"""

    pass


ModuleLoader = Callable[[str], ModuleType]
UserSiteGetter = Callable[[], str | list[str]]


# XtQuant使用数字订单状态；在适配边界统一转成Spring状态机可识别的英文常量。
_ORDER_STATUS = {
    48: "UNREPORTED",
    49: "WAIT_REPORTING",
    50: "REPORTED",
    51: "REPORTED_CANCEL",
    52: "PARTIALLY_FILLED_CANCEL_PENDING",
    53: "PARTIALLY_CANCELED",
    54: "CANCELED",
    55: "PARTIALLY_FILLED",
    56: "FILLED",
    57: "REJECTED",
    255: "UNKNOWN",
}

# MiniQMT行情订阅是异步的：首次立即读取可能只得到lastPrice=0的元数据外壳。
# 采用短退避重试，总等待0.56秒，兼顾一次搜索成功率和接口响应时间。
_QUOTE_FIRST_TICK_RETRY_DELAYS = (0.08, 0.16, 0.32)


def _value(record: Any, *names: str, default: Any = None) -> Any:
    """按候选字段顺序读取字典键或对象属性，兼容不同xtquant版本。"""

    for name in names:
        if isinstance(record, dict) and name in record:
            value = record[name]
        else:
            value = getattr(record, name, None)
        if value is not None:
            # 0和空字符串可能是SDK的有效值，只有None才继续尝试下一个别名。
            return value
    return default


def _decimal(value: Any, default: Decimal = Decimal("0")) -> Decimal:
    """安全转换券商数值；空值、非法值、NaN和Infinity统一回退。"""

    if value is None or value == "":
        return default
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return default
    return result if result.is_finite() else default


def _decimal_text(value: Any, scale: int | None = None) -> str:
    """按可选精度输出十进制字符串，避免跨语言JSON浮点误差。"""

    number = _decimal(value)
    if scale is not None:
        quantum = Decimal("1").scaleb(-scale)
        return number.quantize(quantum, rounding=ROUND_HALF_UP).to_eng_string()
    normalized = number.normalize()
    return format(normalized, "f") if normalized != 0 else "0"


def _timestamp(value: Any) -> str | None:
    """把秒/毫秒时间戳转换为本地时区ISO文本，其他格式原样保留。"""

    if value is None or value == "":
        return None
    try:
        raw = int(value)
    except (TypeError, ValueError):
        return str(value)
    if raw > 10_000_000_000:
        # 十一位以上按毫秒处理，先缩放为datetime.fromtimestamp需要的秒。
        raw //= 1000
    if raw >= 1_000_000_000:
        try:
            return datetime.fromtimestamp(raw).astimezone().isoformat()
        except (OSError, OverflowError, ValueError):
            pass
    return str(value)


def _side(record: Any) -> str:
    """把QMT买卖数字枚举转换为BUY/SELL；未知值保留原始文本。"""

    flag = _value(record, "offset_flag", "direction", "order_type")
    try:
        numeric = int(flag)
    except (TypeError, ValueError):
        return str(flag) if flag is not None else "UNKNOWN"
    if numeric in (23, 48):
        return "BUY"
    if numeric in (24, 49):
        return "SELL"
    return str(numeric)


def _mask_account(account_id: str | None) -> str | None:
    """仅保留资金账号最后两位，供健康检查和错误信息安全展示。"""

    if not account_id:
        return None
    suffix_length = min(2, len(account_id))
    return "*" * max(4, len(account_id) - suffix_length) + account_id[-suffix_length:]


class QmtAccountAdapter:
    """
    国金 QMT 的唯一适配边界。

    业务服务不直接接触 xtquant 对象，而是通过本类获得稳定的字典协议。
    这样可以把 SDK 版本差异、连接重建、账户订阅、字段兼容和错误分类集中处理。
    一个适配器实例对应一个 xtquant Session；HTTP 请求复用该实例，不能每次请求都新建 Trader。
    """
    def __init__(
        self,
        settings: Settings,
        *,
        module_loader: ModuleLoader = importlib.import_module,
        user_site_getter: UserSiteGetter = site.getusersitepackages,
        session_id: int | None = None,
    ) -> None:
        """保存配置与可注入依赖，连接和SDK模块均延迟到首次请求创建。"""

        # 高风险配置在构造时固化；配置变化由外部单例工厂替换整个适配器。
        self._path = settings.qmt_path.strip()
        self._configured_account_id = settings.qmt_account_id.strip()
        self._account_type = settings.qmt_account_type.strip().upper() or "STOCK"
        self._read_enabled = settings.qmt_read_enabled
        self._trade_enabled = settings.qmt_trade_enabled
        self._module_loader = module_loader
        self._user_site_getter = user_site_getter
        self._session_id = session_id if session_id is not None else self._new_session_id()

        # operation_lock串行化整个SDK调用链；RLock允许内部准备方法在同一线程安全复入。
        self._operation_lock = threading.RLock()
        # state_lock只保护下面的健康状态字段，避免健康检查读到一半更新的组合状态。
        self._state_lock = threading.Lock()
        # 模块、Trader、回调和账户对象均属于连接生命周期状态，不能按HTTP请求重建。
        self._xttrader_module: ModuleType | None = None
        self._xttype_module: ModuleType | None = None
        self._xtdata_module: ModuleType | None = None
        self._trader: Any = None
        self._callback: Any = None
        self._account: Any = None
        self._resolved_account_id: str | None = None
        self._installed = False
        self._connected = False
        self._subscribed = False
        self._account_status: Any = None
        self._error: str | None = None

    @staticmethod
    def _new_session_id() -> int:
        """生成当前适配器生命周期唯一的正整数Session ID。"""

        # 只在创建适配器时生成一次，HTTP请求不会持续创建MiniQMT会话。
        return int(time.time_ns() % 1_900_000_000) + 100_000_000

    def health(self) -> dict[str, Any]:
        """返回脱敏健康状态；只尝试加载SDK，不主动发送账号查询或交易请求。"""

        # 首次健康检查可发现xtquant是否安装，但连接仍由实际业务请求按需建立。
        if self._read_enabled and not self._installed:
            with self._operation_lock:
                if not self._installed:
                    try:
                        self._load_xtquant()
                    except QmtAdapterError:
                        pass
        with self._state_lock:
            # 状态优先级从显式禁用到账号回调，再到连接生命周期逐级判断。
            if not self._read_enabled:
                status: Any = "DISABLED"
            elif self._account_status is not None:
                status = self._account_status
            elif self._subscribed:
                status = "READY"
            elif self._connected:
                status = "CONNECTED"
            elif self._installed:
                status = "DISCONNECTED"
            else:
                status = "NOT_INSTALLED"
            return {
                "installed": self._installed,
                "connected": self._connected,
                "subscribed": self._subscribed,
                "account": _mask_account(self._resolved_account_id or self._configured_account_id),
                "status": status,
                "error": self._error,
            }

    def sync(self, request: AccountSyncRequest) -> dict[str, Any]:
        """
        读取一份完整的账户快照。

        读取顺序是“资产 -> 持仓 -> 可选委托”，其中持仓即使不返回给调用方也必须查询，
        因为部分 QMT 版本没有可靠的账户级盈亏字段，账户盈亏需要由持仓汇总得到。
        所有 SDK 调用都在 operation_lock 内完成，避免连接状态和 Trader 对象被并发修改。
        """
        if not self._read_enabled:
            # 禁用读取时立即失败，不能返回旧对象或Mock数据。
            raise QmtConfigurationError(
                "QMT read access is disabled by QUANT_QMT_READ_ENABLED."
            )

        with self._operation_lock:
            # 同一锁内读取资产和持仓，最大程度保证它们属于一个相近时点。
            self._ensure_ready()
            warnings: list[str] = []

            asset = self._query("query_stock_asset", self._account)
            if asset is None:
                message = "QMT asset query returned None; the query failed."
                self._set_state(error=message)
                raise QmtQueryError(message)

            # 不同 QMT 版本的 XtAsset 盈亏字段口径并不稳定，因此账户盈亏从持仓逐项汇总。
            # 即使调用方不要求返回持仓行，也必须查询持仓，否则资产汇总会缺少盈亏依据。
            raw_positions = self._query("query_stock_positions", self._account)
            if raw_positions is None:
                message = (
                    "query_stock_positions returned None; QMT defines this as either "
                    "a query failure or an empty position list. Sync was aborted to "
                    "preserve the last known holdings."
                )
                self._set_state(error=message)
                raise QmtQueryError(message)

            mapped_positions: list[dict[str, Any]] = []
            estimated_profit_loss = False
            for raw_position in raw_positions:
                mapped_position, was_estimated = self._map_position(raw_position)
                mapped_positions.append(mapped_position)
                estimated_profit_loss = estimated_profit_loss or was_estimated
            positions = mapped_positions if request.includePositions else []
            if estimated_profit_loss:
                warnings.append(
                    "部分持仓盈亏按持仓市值减成本估算，未扣费用，非券商结算口径。"
                )

            orders: list[dict[str, Any]] = []
            executions: list[dict[str, Any]] = []
            if request.includeOrders:
                # 委托和成交不是两秒资产热路径必需数据，只在完整同步时查询。
                raw_orders = self._query("query_stock_orders", self._account, False)
                if raw_orders is None:
                    warnings.append(
                        "query_stock_orders returned None; QMT defines this as either "
                        "a query failure or no orders today."
                    )
                else:
                    orders = [self._map_order(order) for order in raw_orders]

                raw_trades = self._query("query_stock_trades", self._account)
                if raw_trades is None:
                    warnings.append(
                        "query_stock_trades returned None; QMT defines this as either "
                        "a query failure or no trades today."
                    )
                else:
                    executions = [self._map_trade(trade) for trade in raw_trades]

            return {
                "snapshotTime": datetime.now().astimezone().isoformat(),
                "account": self._map_asset(asset, mapped_positions),
                "positions": positions,
                "orders": orders,
                "executions": executions,
                "source": "qmt",
                "environment": "SIMULATION",
                "warnings": warnings,
            }

    def submit_order(self, request: SubmitOrderRequest) -> dict[str, Any]:
        """
        向配置的 QMT 模拟账户发起一笔委托。

        本方法只负责“调用券商并把原始结果标准化”，不负责本地订单幂等、状态历史或数据库事务。
        那些职责由 Spring 的 OrderService 和 OrderStatePersistenceService 完成。

        下单链路可以按下面的顺序理解：
        1. 先检查配置开关和环境，避免测试请求误触发真实交易接口；
        2. 获取适配器锁并确认Trader连接、账号订阅仍然可用；
        3. 把前端的 BUY/SELL、LIMIT/MARKET 转换为 xtquant 数字枚举；
        4. 调用 XtQuantTrader.order_stock，把QMT返回的委托号转换成稳定字符串；
        5. 最多补查一次委托状态，但绝不因为“补查失败”而重复下单；
        6. 返回Spring订单服务需要的最小、可序列化结果。
        """
        # 交易开关是适配器侧的“总闸门”。默认关闭时，即使上层接口被误调用，
        # 也不会进入 xtquant 的 order_stock；生产环境可以通过配置中心统一控制它。
        if not self._trade_enabled:
            raise QmtConfigurationError("QMT simulated trading is disabled by QUANT_QMT_TRADE_ENABLED.")

        # 当前项目只允许向已经连接的QMT模拟账户下单；REAL/其它环境由上层业务拒绝，
        # 这里再次校验是为了形成跨服务边界的防御性保护。
        if request.environment != "SIMULATION":
            raise QmtTradeError("Only the configured QMT simulation account may be traded.")

        # 读取资产、同步账户、下单、撤单共用一把锁。
        # 原因是Trader对象内部包含连接状态，重连/订阅和交易并发执行可能产生竞态。
        with self._operation_lock:
            # _ensure_ready 会按需加载 xtquant、创建Trader、启动连接并完成账号订阅；
            # 因而后面的 order_stock 可以直接复用长连接，避免每次下单重复初始化。
            self._ensure_ready()

            # 优先使用连接阶段从QMT发现到的账号；发现不到时才回退到配置文件中的账号。
            # 这个值同时用于 order_stock 的 StockAccount 和响应中的外部账号标识。
            resolved = self._resolved_account_id or self._configured_account_id

            # 请求账号必须与当前连接账号完全一致，防止用户借用其它账号下单，
            # 也防止前端缓存了旧账号后把订单发到错误的资金账户。
            if request.externalAccountId.strip() != resolved:
                raise QmtTradeError("The order account does not match the connected QMT account.")

            # 去掉首尾空格并统一成大写，保证 buy、Buy、BUY 在业务层语义一致。
            side = request.side.strip().upper()

            # 当前适配器只开放买入和卖出两种股票方向；其它方向不能静默映射，必须明确失败。
            if side not in {"BUY", "SELL"}:
                raise QmtTradeError("QMT order side must be BUY or SELL.")

            # xtquant 的 order_stock 不接受字符串方向，而是使用数字枚举：23代表买入，
            # 24代表卖出。枚举转换集中在适配器边界，Spring和前端仍使用可读的BUY/SELL。
            order_type = 23 if side == "BUY" else 24

            # 同样地，xtquant 以数字表示价格类型：11代表限价，5代表市价。
            # 当前请求模型只要不是LIMIT就按市价处理，具体的枚举值不泄漏到上层。
            price_type = 11 if request.orderType.upper() == "LIMIT" else 5

            # Pydantic请求模型通常给出Decimal；SDK要求Python浮点数，因此在最后一跳转换。
            # 市价单允许价格为空/0，限价单则在下面校验为正数。
            price = float(request.price or 0)

            # QMT股票委托数量必须是整数股；Decimal数量在这里转为SDK需要的int。
            quantity = int(request.quantity)

            # 数量<=0一定是非法委托；限价单还必须有正的委托价。
            # 市价单不检查价格正数，因为order_stock约定用0表示按市价下单。
            if quantity <= 0 or (request.orderType.upper() == "LIMIT" and price <= 0):
                raise QmtTradeError("QMT order quantity and limit price must be positive.")

            try:
                # 这里是本文件真正进入QMT交易通道的调用点：XtQuantTrader.order_stock。
                # 调用成功只代表QMT接受了委托请求，不等于订单已经成交；成交状态需要后续查询/回报确认。
                #
                # 下面按参数顺序解释SDK调用：
                # 1) self._account：已完成订阅的StockAccount对象，包含账号类型和资金账号；
                # 2) symbol：标准化后的证券代码，如600000.SH、510300.SH；
                # 3) order_type：23=买入，24=卖出；
                # 4) quantity：整数委托数量，股票通常还需满足100股整数倍规则；
                # 5) price_type：11=限价，5=市价；
                # 6) price：限价单的委托价格，市价单传0；
                # 7) strategy_name：订单来源标识，便于在QMT中区分系统委托；
                # 8) remark：本地clientOrderNo的前32个字符，用于跨前后端链路关联。
                order_id = self._trader.order_stock(
                    # 参数1：QMT Trader已经订阅的资金账户对象。
                    self._account,
                    # 参数2：证券代码统一大写，避免因大小写导致QMT找不到标的。
                    request.symbol.strip().upper(),
                    # 参数3：买卖方向数字枚举，由上面的side转换而来。
                    order_type,
                    # 参数4：委托股数，已完成正数校验并转换为整数。
                    quantity,
                    # 参数5：价格类型数字枚举，限价/市价分别对应11/5。
                    price_type,
                    # 参数6：委托价格；市价单按QMT约定传0。
                    price,
                    # 参数7：策略/来源名称，固定值用于标识本系统的股票管理委托。
                    "StockManager",
                    # 参数8：本地订单号作为备注，截断避免超过QMT字段长度限制。
                    request.clientOrderNo[:32],
                )
            except Exception as exc:
                # SDK可能抛出版本相关异常；统一包装为QmtTradeError，
                # 由FastAPI边界转换为稳定HTTP错误，避免把xtquant对象泄漏给Spring。
                raise QmtTradeError(self._safe_error(f"QMT order_stock failed: {exc}")) from exc

            try:
                # 不同xtquant版本可能返回int、字符串或可转为整数的标识，
                # 先统一成Python int，方便校验并作为后续查询的主键。
                numeric_order_id = int(order_id)
            except (TypeError, ValueError) as exc:
                # 返回值无法转成数字，说明SDK响应不符合契约；此时不能伪造外部订单号。
                raise QmtTradeError(f"QMT returned an invalid order id: {order_id}") from exc

            # QMT通常以正数表示成功创建委托；0或负数视为明确拒单/失败。
            if numeric_order_id <= 0:
                raise QmtTradeError(f"QMT rejected the order (order_stock returned {numeric_order_id}).")

            # 在没有补查到QMT状态前，先给出“已提交”这一保守状态；
            # 它表示已拿到外部委托号，不表示已经成交。
            status = "SUBMITTED"
            try:
                # 下单返回ID后立即补查一次，可以把已报、已成、已撤等更准确状态及时返回前端。
                # 这里是查询，不是重试下单；所以即使查询失败，也不会产生第二笔委托。
                raw_order = self._trader.query_stock_order(self._account, numeric_order_id)

                # QMT可能返回None（暂时查不到/连接抖动）；只有拿到记录时才覆盖默认状态。
                if raw_order is not None:
                    status = self._map_order(raw_order)["status"]
            except Exception:
                # 委托号已经成功返回，补查异常不能把订单标记为失败，也不能再次order_stock。
                # 后续由订单同步任务或用户刷新重新查询最终状态。
                pass

            # 返回稳定的JSON字典，供Spring OrderService落库并返回前端。
            # clientOrderNo用于本地幂等关联，externalOrderNo用于QMT对账和撤单。
            return {
                # 本地请求号：贯穿前端、Spring订单表和QMT备注。
                "clientOrderNo": request.clientOrderNo,
                # QMT外部委托号：统一转字符串，避免JSON/Java Long精度和类型差异。
                "externalOrderNo": str(numeric_order_id),
                # 可能是SUBMITTED，也可能是补查后映射出的QMT状态。
                "status": status,
                # 适配器接收到委托的本地时间，带时区便于审计和跨服务排查。
                "submittedAt": datetime.now().astimezone().isoformat(),
                # 标明该订单来自QMT真实适配器，而不是MindGo模拟执行器。
                "source": "qmt_order_stock",
                # 返回最终使用的账号，便于前端展示和后续对账。
                "externalAccountId": resolved,
            }

    def quotes(self, symbols: list[str]) -> dict[str, Any]:
        """订阅并读取五档行情；第一次订阅可能只有元数据，因此必须等到有效最新价出现。"""
        if not self._read_enabled:
            raise QmtConfigurationError("QMT read access is disabled by QUANT_QMT_READ_ENABLED.")
        # 去空、转大写并按首次出现顺序去重，减少重复订阅与结果行。
        normalized = list(dict.fromkeys(symbol.strip().upper() for symbol in symbols if symbol and symbol.strip()))
        with self._operation_lock:
            self._ensure_ready()
            ticks: dict[str, Any] = {}
            if self._xtdata_module is not None and normalized:
                try:
                    # 先完成整批订阅，再成批读取tick，避免每只证券单独网络往返。
                    for symbol in normalized:
                        self._xtdata_module.subscribe_quote(symbol, period="1d", count=1)
                    for delay in (0.0, *_QUOTE_FIRST_TICK_RETRY_DELAYS):
                        if delay:
                            time.sleep(delay)
                        candidate = self._xtdata_module.get_full_tick(normalized) or {}
                        ticks = candidate if isinstance(candidate, dict) else {}
                        # 非空 tick 外壳不代表行情已就绪：MiniQMT 可能先返回 lastPrice=0 的元数据。
                        # 在本次请求内继续等待有效最新价，让用户一次搜索就能得到完整票据。
                        if all(
                            _decimal(_value(ticks.get(symbol, {}), "lastPrice", "last_price", default=0)) > 0
                            for symbol in normalized
                        ):
                            break
                except Exception:
                    ticks = {}
            quotes: list[dict[str, Any]] = []
            missing: list[str] = []
            for symbol in normalized:
                # 缺失tick时仍尝试读取证券名称，前端可展示明确的缺失标的。
                tick = ticks.get(symbol, {}) if isinstance(ticks, dict) else {}
                name = self._instrument_name(symbol) or symbol
                last_price = _decimal(_value(tick, "lastPrice", "last_price", default=0))
                open_price = _decimal(_value(tick, "open", "openPrice", default=0))
                high_price = _decimal(_value(tick, "high", "highPrice", default=0))
                low_price = _decimal(_value(tick, "low", "lowPrice", default=0))
                previous_close = _decimal(_value(tick, "lastClose", "preClose", default=0))
                bid_prices = list(_value(tick, "bidPrice", "bid_prices", default=[]) or [])
                ask_prices = list(_value(tick, "askPrice", "ask_prices", default=[]) or [])
                bid_volumes = list(_value(tick, "bidVol", "bid_volumes", default=[]) or [])
                ask_volumes = list(_value(tick, "askVol", "ask_volumes", default=[]) or [])
                if name == symbol and last_price <= 0:
                    missing.append(symbol)
                quotes.append({
                    "symbol": symbol,
                    "name": name,
                    "lastPrice": _decimal_text(last_price, 4),
                    "openPrice": _decimal_text(open_price, 4),
                    "highPrice": _decimal_text(high_price, 4),
                    "lowPrice": _decimal_text(low_price, 4),
                    "previousClose": _decimal_text(previous_close, 4),
                    "bidPrices": [_decimal_text(value, 4) for value in bid_prices[:5]],
                    "askPrices": [_decimal_text(value, 4) for value in ask_prices[:5]],
                    "bidVolumes": [_decimal_text(value) for value in bid_volumes[:5]],
                    "askVolumes": [_decimal_text(value) for value in ask_volumes[:5]],
                    "dataTime": datetime.now().astimezone().isoformat(),
                    "source": "qmt_tick" if tick else "qmt_instrument",
                })
            return {"quotes": quotes, "missingSymbols": missing, "marketStatus": "QMT"}

    def query_orders(self) -> dict[str, Any]:
        """查询当前 QMT 账户的当日委托；Spring 后台会用 externalOrderNo 做本地状态对账。"""
        with self._operation_lock:
            self._ensure_ready()
            raw_orders = self._query("query_stock_orders", self._account, False)
            if raw_orders is None:
                # None在SDK中可能表示查询失败或当日无委托，因此附带warning而非伪造终态。
                return {"orders": [], "source": "qmt", "warnings": ["QMT 未返回当日委托"]}
            return {"orders": [self._map_order(order) for order in raw_orders], "source": "qmt", "warnings": []}

    def cancel_order(self, request: CancelOrderRequest) -> dict[str, Any]:
        """
        向 QMT 发起撤单请求。

        QMT 返回 0 只能说明撤单请求被接口接受，不能证明最终已经撤成；因此统一返回
        CANCEL_PENDING，由 Spring 的订单状态轮询确认 CANCELED 或“撤单前已成交”。
        """
        if not self._trade_enabled:
            raise QmtConfigurationError("QMT simulated trading is disabled by QUANT_QMT_TRADE_ENABLED.")
        if request.environment != "SIMULATION":
            raise QmtTradeError("Only the configured QMT simulation account may be traded.")
        with self._operation_lock:
            self._ensure_ready()
            resolved = self._resolved_account_id or self._configured_account_id
            if request.externalAccountId.strip() != resolved:
                raise QmtTradeError("The cancel account does not match the connected QMT account.")
            try:
                # QMT撤单只认券商外部订单号，本地Snowflake ID不能代替。
                order_id = int(request.externalOrderNo)
                cancel_result = int(self._trader.cancel_order_stock(self._account, order_id))
            except Exception as exc:
                raise QmtTradeError(self._safe_error(f"QMT cancel_order_stock failed: {exc}")) from exc
            if cancel_result != 0:
                # 非0是QMT明确拒绝；Spring会保留审计并等待/展示真实结果。
                raise QmtTradeError(f"QMT rejected the cancel request (returned {cancel_result}).")
            return {
                "clientOrderNo": request.clientOrderNo,
                "externalOrderNo": request.externalOrderNo,
                "status": "CANCEL_PENDING",
                "canceledAt": datetime.now().astimezone().isoformat(),
                "source": "qmt_cancel_order_stock",
            }

    def close(self) -> None:
        """断开并清空长生命周期Trader对象；close可被重复安全调用。"""

        with self._operation_lock:
            # 先清空本地引用和健康状态，再调用SDK stop，避免并发健康检查误报可用。
            trader = self._trader
            self._trader = None
            self._callback = None
            self._account = None
            self._set_state(connected=False, subscribed=False)
            if trader is not None:
                stop = getattr(trader, "stop", None)
                if callable(stop):
                    try:
                        stop()
                    except Exception:
                        # 关闭阶段不再向上抛错，避免进程因SDK清理异常无法退出。
                        pass

    def _load_xtquant(self) -> None:
        """加载交易、账户类型和可选行情模块，并区分依赖缺失与内部导入失败。"""

        if self._xttrader_module is not None and self._xttype_module is not None:
            return

        try:
            xttrader_module, xttype_module = self._import_xtquant_modules()
        except ModuleNotFoundError as exc:
            # 只有确实缺少xtquant才尝试用户目录；xtquant内部依赖缺失应保留真实错误。
            missing_name = exc.name or ""
            if not missing_name.startswith("xtquant"):
                message = self._safe_error(f"xtquant dependency import failed: {exc}")
                self._set_state(installed=False, error=message)
                raise QmtNotInstalledError(message) from exc
            self._add_user_site_packages()
            importlib.invalidate_caches()
            try:
                xttrader_module, xttype_module = self._import_xtquant_modules()
            except (ImportError, OSError) as retry_error:
                message = self._safe_error(
                    "xtquant is unavailable to this Python interpreter after the "
                    f"user site-packages fallback: {retry_error}"
                )
                self._set_state(installed=False, error=message)
                raise QmtNotInstalledError(message) from retry_error
        except (ImportError, OSError) as exc:
            message = self._safe_error(f"xtquant could not be loaded: {exc}")
            self._set_state(installed=False, error=message)
            raise QmtNotInstalledError(message) from exc

        self._xttrader_module = xttrader_module
        self._xttype_module = xttype_module
        try:
            # xtdata不是账户/交易连接的硬依赖，加载失败时资产和订单功能仍可工作。
            self._xtdata_module = self._module_loader("xtquant.xtdata")
        except Exception:
            self._xtdata_module = None
        self._set_state(installed=True)

    def _import_xtquant_modules(self) -> tuple[ModuleType, ModuleType]:
        """通过可注入加载器导入核心交易模块，便于单元测试替换SDK。"""

        return (
            self._module_loader("xtquant.xttrader"),
            self._module_loader("xtquant.xttype"),
        )

    def _add_user_site_packages(self) -> None:
        """将QMT常见的用户site-packages追加到模块搜索路径末尾。"""

        user_sites = self._user_site_getter()
        candidates = [user_sites] if isinstance(user_sites, str) else list(user_sites)
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                # 使用append而非prepend，保证虚拟环境已安装依赖仍保持优先级。
                sys.path.append(candidate)

    def _ensure_ready(self) -> None:
        """
        保证 SDK 已加载、Trader 已启动、连接已建立且账户已订阅。

        这是所有读写操作的共同前置步骤，顺序不能调换：没有 Trader 不能 connect，
        没有连接不能 subscribe，没有订阅也不能安全查询账户数据。
        """
        self._validate_configuration()
        self._load_xtquant()
        self._ensure_trader_started()

        if not self._connected:
            try:
                connect_result = self._trader.connect()
            except Exception as exc:
                message = self._safe_error(f"QMT connection failed: {exc}")
                self._set_state(connected=False, subscribed=False, error=message)
                raise QmtConnectionError(message) from exc
            if connect_result != 0:
                message = f"QMT connection failed (connect returned {connect_result})."
                self._set_state(connected=False, subscribed=False, error=message)
                raise QmtConnectionError(message)
            self._set_state(connected=True, subscribed=False, account_status=None)

        if not self._subscribed:
            # 只订阅配置的资金账号，防止本机 QMT 中其他账号的数据串入当前业务账户。
            self._discover_account_and_subscribe()

        self._set_state(error=None)

    def _validate_configuration(self) -> None:
        """在接触SDK前验证目录与资金账号，返回比底层异常更清楚的配置错误。"""

        if not self._path:
            message = "QMT mode requires QUANT_QMT_PATH."
            self._set_state(error=message)
            raise QmtConfigurationError(message)
        if not Path(self._path).is_dir():
            message = "The configured QMT userdata_mini directory does not exist."
            self._set_state(error=message)
            raise QmtConfigurationError(message)
        if not self._configured_account_id:
            message = "QMT mode requires QUANT_QMT_ACCOUNT_ID."
            self._set_state(error=message)
            raise QmtConfigurationError(message)

    def _ensure_trader_started(self) -> None:
        """创建Trader、注册回调并启动工作线程；已存在时直接复用。"""

        if self._trader is not None:
            return
        assert self._xttrader_module is not None
        trader_class = getattr(self._xttrader_module, "XtQuantTrader")
        callback_class = getattr(self._xttrader_module, "XtQuantTraderCallback")
        adapter = self

        class AdapterCallback(callback_class):  # type: ignore[misc, valid-type]
            """把xtquant异步连接/账号事件安全写回外层适配器状态。"""

            def on_disconnected(self) -> None:
                """连接断开后清除订阅标志，下一次请求将自动重连。"""

                adapter._set_state(
                    connected=False,
                    subscribed=False,
                    account_status="DISCONNECTED",
                    error="QMT connection was disconnected; the next read will reconnect.",
                )

            def on_account_status(self, status: Any) -> None:
                """记录账号状态码，但不在回调线程执行查询或重连。"""

                adapter._set_state(account_status=_value(status, "status", default="UNKNOWN"))

        try:
            # 注册回调必须早于start，避免启动瞬间的断线/状态事件丢失。
            trader = trader_class(self._path, self._session_id)
            callback = AdapterCallback()
            trader.register_callback(callback)
            trader.start()
        except Exception as exc:
            message = self._safe_error(f"QMT adapter initialization failed: {exc}")
            self._set_state(connected=False, subscribed=False, error=message)
            raise QmtConnectionError(message) from exc
        self._trader = trader
        self._callback = callback

    def _discover_account_and_subscribe(self) -> None:
        """从MiniQMT已登录账号中精确选择配置账号并完成订阅。"""

        try:
            account_infos = self._trader.query_account_infos()
        except Exception as exc:
            message = self._safe_error(f"QMT account discovery failed: {exc}")
            self._set_state(subscribed=False, error=message)
            raise QmtSubscriptionError(message) from exc
        if not account_infos:
            message = "QMT account discovery returned no accounts."
            self._set_state(subscribed=False, error=message)
            raise QmtSubscriptionError(message)

        configured = self._configured_account_id
        # 不采用第一个账号；必须按完整account_id精确匹配，防止多账号串单。
        account_info = next(
            (
                info
                for info in account_infos
                if str(_value(info, "account_id", default="")).strip() == configured
            ),
            None,
        )
        if account_info is None:
            message = (
                f"Configured QMT account {_mask_account(configured)} was not reported by MiniQMT."
            )
            self._set_state(subscribed=False, error=message)
            raise QmtSubscriptionError(message)

        resolved_account_id = str(_value(account_info, "account_id")).strip()
        assert self._xttype_module is not None
        stock_account_class = getattr(self._xttype_module, "StockAccount")
        account = stock_account_class(resolved_account_id, self._account_type)
        try:
            # subscribe返回0才表示成功，异常和非0状态分别保留可读错误。
            subscribe_result = self._trader.subscribe(account)
        except Exception as exc:
            message = self._safe_error(f"QMT account subscription failed: {exc}")
            self._set_state(subscribed=False, error=message)
            raise QmtSubscriptionError(message) from exc
        if subscribe_result != 0:
            message = f"QMT account subscription failed (subscribe returned {subscribe_result})."
            self._set_state(subscribed=False, error=message)
            raise QmtSubscriptionError(message)

        self._account = account
        self._resolved_account_id = resolved_account_id
        self._set_state(
            subscribed=True,
            account_status=_value(account_info, "login_status", default=None),
        )

    def _query(self, method_name: str, *args: Any) -> Any:
        """统一调用Trader查询；异常时使连接失效，让下一次请求重连。"""

        try:
            method = getattr(self._trader, method_name)
            return method(*args)
        except Exception as exc:
            message = self._safe_error(f"QMT {method_name} failed: {exc}")
            # 当前无法区分SDK业务异常与传输中断，保守清除连接以便下次完整恢复。
            self._set_state(connected=False, subscribed=False, error=message)
            raise QmtQueryError(message) from exc

    def _map_asset(
        self, asset: Any, positions: list[dict[str, Any]]
    ) -> dict[str, str]:
        """把XtAsset和持仓汇总转换为Spring账户字段，全部金额输出字符串。"""

        cash = _value(asset, "cash", "m_dCash", "m_dAvailable", default=0)
        # QMT账户级盈亏字段跨版本不稳定，统一使用已映射持仓盈亏求和。
        profit_loss = sum(
            (_decimal(position.get("profitLoss")) for position in positions),
            start=Decimal("0"),
        )
        return {
            "externalAccountId": self._resolved_account_id or self._configured_account_id,
            "accountName": "国金QMT模拟账户",
            "broker": "GUOJIN_QMT",
            "environment": "SIMULATION",
            "currency": "CNY",
            "totalAsset": _decimal_text(
                _value(asset, "total_asset", "m_dBalance", default=0), 2
            ),
            "cash": _decimal_text(cash, 2),
            "availableCash": _decimal_text(cash, 2),
            "frozenCash": _decimal_text(
                _value(asset, "frozen_cash", "m_dFrozenCash", default=0), 2
            ),
            "marketValue": _decimal_text(
                _value(asset, "market_value", "m_dMarketValue", default=0), 2
            ),
            "profitLoss": _decimal_text(profit_loss, 2),
        }

    def _map_position(self, position: Any) -> tuple[dict[str, str], bool]:
        """标准化单只持仓，并返回盈亏是否由市值减成本估算。"""

        symbol = str(_value(position, "stock_code", "instrument_id", default=""))
        quantity = _decimal(_value(position, "volume", default=0))
        cost_price = _decimal(_value(position, "avg_price", "open_price", default=0))
        raw_market_value = _value(position, "market_value", default=None)
        last_price = _value(position, "last_price", default=None)
        if raw_market_value is None and last_price is not None:
            # 缺少市值但有最新价时按数量×最新价补算。
            market_value = quantity * _decimal(last_price)
        else:
            market_value = _decimal(raw_market_value)
        if last_price is None and quantity != 0 and market_value != 0:
            # 缺最新价时反推单位市值；仍不可得则回退成本价。
            last_price = market_value / quantity
        if last_price is None:
            last_price = cost_price

        raw_profit_loss = _value(
            position, "float_profit", "position_profit", default=None
        )
        profit_loss_was_estimated = raw_profit_loss is None
        profit_loss = (
            market_value - (cost_price * quantity)
            if profit_loss_was_estimated
            else _decimal(raw_profit_loss)
        )
        security_name = str(_value(position, "instrument_name", "security_name", default="") or "").strip()
        if not security_name or security_name == symbol:
            # 部分QMT持仓只返回代码，额外从证券详情读取中文名称。
            security_name = self._instrument_name(symbol)
        mapped = {
            "symbol": symbol,
            "securityName": security_name or symbol,
            "quantity": _decimal_text(quantity),
            "availableQuantity": _decimal_text(
                _value(position, "can_use_volume", default=0)
            ),
            "costPrice": _decimal_text(cost_price, 4),
            "lastPrice": _decimal_text(last_price, 4),
            "marketValue": _decimal_text(market_value, 2),
            "profitLoss": _decimal_text(profit_loss, 2),
        }
        return mapped, profit_loss_was_estimated

    def _instrument_name(self, symbol: str) -> str:
        """从xtdata证券详情读取名称；行情模块不可用或查询失败时返回空文本。"""

        if not symbol or self._xtdata_module is None:
            return ""
        try:
            detail = self._xtdata_module.get_instrument_detail(symbol)
        except Exception:
            return ""
        if isinstance(detail, dict):
            return str(detail.get("InstrumentName") or detail.get("instrument_name") or "").strip()
        return ""

    @staticmethod
    def _map_order(order: Any) -> dict[str, Any]:
        """把XtOrder映射为Spring订单对账协议，保留原始状态消息。"""

        order_status = _value(order, "order_status", default=255)
        try:
            status = _ORDER_STATUS.get(int(order_status), str(order_status))
        except (TypeError, ValueError):
            status = str(order_status)
        symbol = str(_value(order, "stock_code", "instrument_id", default=""))
        return {
            "externalOrderNo": str(_value(order, "order_id", default="")),
            "brokerOrderNo": str(_value(order, "order_sysid", default="")),
            "symbol": symbol,
            "securityName": str(
                _value(order, "instrument_name", "security_name", default=symbol)
            ),
            "side": _side(order),
            "orderType": str(_value(order, "order_type", default="")),
            "quantity": _decimal_text(_value(order, "order_volume", default=0)),
            "price": _decimal_text(_value(order, "price", default=0), 4),
            "tradedQuantity": _decimal_text(_value(order, "traded_volume", default=0)),
            "tradedPrice": _decimal_text(_value(order, "traded_price", default=0), 4),
            "status": status,
            "statusMessage": str(_value(order, "status_msg", default="")),
            "orderTime": _timestamp(_value(order, "order_time", default=None)),
            "strategyName": str(_value(order, "strategy_name", default="")),
            "remark": str(_value(order, "order_remark", default="")),
        }

    @staticmethod
    def _map_trade(trade: Any) -> dict[str, Any]:
        """把XtTrade映射为标准成交记录，缺少成交额时按价格×数量计算。"""

        symbol = str(_value(trade, "stock_code", "instrument_id", default=""))
        price = _decimal(_value(trade, "traded_price", default=0))
        quantity = _decimal(_value(trade, "traded_volume", default=0))
        amount = _value(trade, "traded_amount", default=None)
        if amount is None:
            amount = price * quantity
        return {
            "executionId": str(_value(trade, "traded_id", default="")),
            "externalOrderNo": str(_value(trade, "order_id", default="")),
            "brokerOrderNo": str(_value(trade, "order_sysid", default="")),
            "symbol": symbol,
            "securityName": str(
                _value(trade, "instrument_name", "security_name", default=symbol)
            ),
            "side": _side(trade),
            "price": _decimal_text(price, 4),
            "quantity": _decimal_text(quantity),
            "amount": _decimal_text(amount, 2),
            "tradedTime": _timestamp(_value(trade, "traded_time", default=None)),
            "strategyName": str(_value(trade, "strategy_name", default="")),
            "remark": str(_value(trade, "order_remark", default="")),
        }

    def _safe_error(self, message: str) -> str:
        """从异常文本中替换本机QMT路径和完整资金账号。"""

        safe = message
        if self._path:
            path_variants = {self._path, self._path.replace("\\", "/")}
            for path_value in path_variants:
                safe = safe.replace(path_value, "<qmt-path>")
        for account_id in (self._configured_account_id, self._resolved_account_id):
            if account_id:
                safe = safe.replace(account_id, _mask_account(account_id) or "<qmt-account>")
        return safe

    def _set_state(
        self,
        *,
        installed: bool | None = None,
        connected: bool | None = None,
        subscribed: bool | None = None,
        account_status: Any = ...,
        error: str | None | object = ...,
    ) -> None:
        """在短锁内按需更新健康字段；省略参数与显式None具有不同含义。"""

        with self._state_lock:
            if installed is not None:
                self._installed = installed
            if connected is not None:
                self._connected = connected
            if subscribed is not None:
                self._subscribed = subscribed
            if account_status is not ...:
                self._account_status = account_status
            if error is not ...:
                self._error = error if isinstance(error, str) else None


# 单例锁保护连接实例与配置键的原子替换，避免并发请求创建多个Trader Session。
_singleton_lock = threading.Lock()
_singleton: QmtAccountAdapter | None = None
_singleton_key: tuple[str, str, str, bool, bool] | None = None


def get_qmt_adapter(settings: Settings) -> QmtAccountAdapter:
    """
    获取当前配置对应的单例适配器。

    QMT Trader 是有连接状态的长生命周期对象，不能按 HTTP 请求创建和销毁。
    配置发生变化时先关闭旧实例，再创建新实例，避免旧 Session 继续占用 QMT。
    """
    global _singleton, _singleton_key
    key = (
        settings.qmt_path,
        settings.qmt_account_id,
        settings.qmt_account_type,
        settings.qmt_read_enabled,
        settings.qmt_trade_enabled,
    )
    with _singleton_lock:
        if _singleton is None or _singleton_key != key:
            if _singleton is not None:
                _singleton.close()
            _singleton = QmtAccountAdapter(settings)
            _singleton_key = key
        return _singleton


def reset_qmt_adapter() -> None:
    """关闭并清空QMT单例，供进程退出和测试隔离使用。"""
    global _singleton, _singleton_key
    with _singleton_lock:
        if _singleton is not None:
            _singleton.close()
        _singleton = None
        _singleton_key = None
