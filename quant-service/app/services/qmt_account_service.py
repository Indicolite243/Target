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
    """Base error safe to expose to an internal service caller."""


class QmtConfigurationError(QmtAdapterError):
    pass


class QmtNotInstalledError(QmtAdapterError):
    pass


class QmtConnectionError(QmtAdapterError):
    pass


class QmtSubscriptionError(QmtAdapterError):
    pass


class QmtQueryError(QmtAdapterError):
    pass


class QmtTradeError(QmtAdapterError):
    pass


ModuleLoader = Callable[[str], ModuleType]
UserSiteGetter = Callable[[], str | list[str]]


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


def _value(record: Any, *names: str, default: Any = None) -> Any:
    for name in names:
        if isinstance(record, dict) and name in record:
            value = record[name]
        else:
            value = getattr(record, name, None)
        if value is not None:
            return value
    return default


def _decimal(value: Any, default: Decimal = Decimal("0")) -> Decimal:
    if value is None or value == "":
        return default
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return default
    return result if result.is_finite() else default


def _decimal_text(value: Any, scale: int | None = None) -> str:
    number = _decimal(value)
    if scale is not None:
        quantum = Decimal("1").scaleb(-scale)
        return number.quantize(quantum, rounding=ROUND_HALF_UP).to_eng_string()
    normalized = number.normalize()
    return format(normalized, "f") if normalized != 0 else "0"


def _timestamp(value: Any) -> str | None:
    if value is None or value == "":
        return None
    try:
        raw = int(value)
    except (TypeError, ValueError):
        return str(value)
    if raw > 10_000_000_000:
        raw //= 1000
    if raw >= 1_000_000_000:
        try:
            return datetime.fromtimestamp(raw).astimezone().isoformat()
        except (OSError, OverflowError, ValueError):
            pass
    return str(value)


def _side(record: Any) -> str:
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
    if not account_id:
        return None
    suffix_length = min(2, len(account_id))
    return "*" * max(4, len(account_id) - suffix_length) + account_id[-suffix_length:]


class QmtAccountAdapter:
    """Thread-safe MiniQMT adapter for reads and explicitly enabled simulated trades."""

    def __init__(
        self,
        settings: Settings,
        *,
        module_loader: ModuleLoader = importlib.import_module,
        user_site_getter: UserSiteGetter = site.getusersitepackages,
        session_id: int | None = None,
    ) -> None:
        self._path = settings.qmt_path.strip()
        self._configured_account_id = settings.qmt_account_id.strip()
        self._account_type = settings.qmt_account_type.strip().upper() or "STOCK"
        self._read_enabled = settings.qmt_read_enabled
        self._trade_enabled = settings.qmt_trade_enabled
        self._module_loader = module_loader
        self._user_site_getter = user_site_getter
        self._session_id = session_id if session_id is not None else self._new_session_id()

        self._operation_lock = threading.RLock()
        self._state_lock = threading.Lock()
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
        # One ID is generated for the adapter lifetime; HTTP requests never create sessions.
        return int(time.time_ns() % 1_900_000_000) + 100_000_000

    def health(self) -> dict[str, Any]:
        if self._read_enabled and not self._installed:
            with self._operation_lock:
                if not self._installed:
                    try:
                        self._load_xtquant()
                    except QmtAdapterError:
                        pass
        with self._state_lock:
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
        if not self._read_enabled:
            raise QmtConfigurationError(
                "QMT read access is disabled by QUANT_QMT_READ_ENABLED."
            )

        with self._operation_lock:
            self._ensure_ready()
            warnings: list[str] = []

            asset = self._query("query_stock_asset", self._account)
            if asset is None:
                message = "QMT asset query returned None; the query failed."
                self._set_state(error=message)
                raise QmtQueryError(message)

            # Account-level profit/loss is derived from positions because XtAsset does
            # not expose a reliable profit field across QMT versions. Query positions
            # even when the caller does not request the position rows themselves.
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
        if not self._trade_enabled:
            raise QmtConfigurationError("QMT simulated trading is disabled by QUANT_QMT_TRADE_ENABLED.")
        if request.environment != "SIMULATION":
            raise QmtTradeError("Only the configured QMT simulation account may be traded.")
        with self._operation_lock:
            self._ensure_ready()
            resolved = self._resolved_account_id or self._configured_account_id
            if request.externalAccountId.strip() != resolved:
                raise QmtTradeError("The order account does not match the connected QMT account.")
            side = request.side.strip().upper()
            if side not in {"BUY", "SELL"}:
                raise QmtTradeError("QMT order side must be BUY or SELL.")
            order_type = 23 if side == "BUY" else 24
            price_type = 11 if request.orderType.upper() == "LIMIT" else 5
            price = float(request.price or 0)
            quantity = int(request.quantity)
            if quantity <= 0 or (request.orderType.upper() == "LIMIT" and price <= 0):
                raise QmtTradeError("QMT order quantity and limit price must be positive.")
            try:
                order_id = self._trader.order_stock(
                    self._account,
                    request.symbol.strip().upper(),
                    order_type,
                    quantity,
                    price_type,
                    price,
                    "StockManager",
                    request.clientOrderNo[:32],
                )
            except Exception as exc:
                raise QmtTradeError(self._safe_error(f"QMT order_stock failed: {exc}")) from exc
            try:
                numeric_order_id = int(order_id)
            except (TypeError, ValueError) as exc:
                raise QmtTradeError(f"QMT returned an invalid order id: {order_id}") from exc
            if numeric_order_id <= 0:
                raise QmtTradeError(f"QMT rejected the order (order_stock returned {numeric_order_id}).")
            status = "SUBMITTED"
            try:
                raw_order = self._trader.query_stock_order(self._account, numeric_order_id)
                if raw_order is not None:
                    status = self._map_order(raw_order)["status"]
            except Exception:
                pass
            return {
                "clientOrderNo": request.clientOrderNo,
                "externalOrderNo": str(numeric_order_id),
                "status": status,
                "submittedAt": datetime.now().astimezone().isoformat(),
                "source": "qmt_order_stock",
                "externalAccountId": resolved,
            }

    def quotes(self, symbols: list[str]) -> dict[str, Any]:
        if not self._read_enabled:
            raise QmtConfigurationError("QMT read access is disabled by QUANT_QMT_READ_ENABLED.")
        normalized = list(dict.fromkeys(symbol.strip().upper() for symbol in symbols if symbol and symbol.strip()))
        with self._operation_lock:
            self._ensure_ready()
            ticks: dict[str, Any] = {}
            if self._xtdata_module is not None and normalized:
                try:
                    for symbol in normalized:
                        self._xtdata_module.subscribe_quote(symbol, period="1d", count=1)
                    ticks = self._xtdata_module.get_full_tick(normalized) or {}
                except Exception:
                    ticks = {}
            quotes: list[dict[str, Any]] = []
            missing: list[str] = []
            for symbol in normalized:
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
        with self._operation_lock:
            self._ensure_ready()
            raw_orders = self._query("query_stock_orders", self._account, False)
            if raw_orders is None:
                return {"orders": [], "source": "qmt", "warnings": ["QMT 未返回当日委托"]}
            return {"orders": [self._map_order(order) for order in raw_orders], "source": "qmt", "warnings": []}

    def cancel_order(self, request: CancelOrderRequest) -> dict[str, Any]:
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
                order_id = int(request.externalOrderNo)
                cancel_result = int(self._trader.cancel_order_stock(self._account, order_id))
            except Exception as exc:
                raise QmtTradeError(self._safe_error(f"QMT cancel_order_stock failed: {exc}")) from exc
            if cancel_result != 0:
                raise QmtTradeError(f"QMT rejected the cancel request (returned {cancel_result}).")
            return {
                "clientOrderNo": request.clientOrderNo,
                "externalOrderNo": request.externalOrderNo,
                "status": "CANCEL_PENDING",
                "canceledAt": datetime.now().astimezone().isoformat(),
                "source": "qmt_cancel_order_stock",
            }

    def close(self) -> None:
        with self._operation_lock:
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
                        pass

    def _load_xtquant(self) -> None:
        if self._xttrader_module is not None and self._xttype_module is not None:
            return

        try:
            xttrader_module, xttype_module = self._import_xtquant_modules()
        except ModuleNotFoundError as exc:
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
            self._xtdata_module = self._module_loader("xtquant.xtdata")
        except Exception:
            self._xtdata_module = None
        self._set_state(installed=True)

    def _import_xtquant_modules(self) -> tuple[ModuleType, ModuleType]:
        return (
            self._module_loader("xtquant.xttrader"),
            self._module_loader("xtquant.xttype"),
        )

    def _add_user_site_packages(self) -> None:
        user_sites = self._user_site_getter()
        candidates = [user_sites] if isinstance(user_sites, str) else list(user_sites)
        for candidate in candidates:
            if candidate and Path(candidate).is_dir() and candidate not in sys.path:
                # Append instead of prepend so the virtual environment keeps precedence.
                sys.path.append(candidate)

    def _ensure_ready(self) -> None:
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
            self._discover_account_and_subscribe()

        self._set_state(error=None)

    def _validate_configuration(self) -> None:
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
        if self._trader is not None:
            return
        assert self._xttrader_module is not None
        trader_class = getattr(self._xttrader_module, "XtQuantTrader")
        callback_class = getattr(self._xttrader_module, "XtQuantTraderCallback")
        adapter = self

        class AdapterCallback(callback_class):  # type: ignore[misc, valid-type]
            def on_disconnected(self) -> None:
                adapter._set_state(
                    connected=False,
                    subscribed=False,
                    account_status="DISCONNECTED",
                    error="QMT connection was disconnected; the next read will reconnect.",
                )

            def on_account_status(self, status: Any) -> None:
                adapter._set_state(account_status=_value(status, "status", default="UNKNOWN"))

        try:
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
        try:
            method = getattr(self._trader, method_name)
            return method(*args)
        except Exception as exc:
            message = self._safe_error(f"QMT {method_name} failed: {exc}")
            # A transport failure is indistinguishable here. Force a reconnect next time.
            self._set_state(connected=False, subscribed=False, error=message)
            raise QmtQueryError(message) from exc

    def _map_asset(
        self, asset: Any, positions: list[dict[str, Any]]
    ) -> dict[str, str]:
        cash = _value(asset, "cash", "m_dCash", "m_dAvailable", default=0)
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
        symbol = str(_value(position, "stock_code", "instrument_id", default=""))
        quantity = _decimal(_value(position, "volume", default=0))
        cost_price = _decimal(_value(position, "avg_price", "open_price", default=0))
        raw_market_value = _value(position, "market_value", default=None)
        last_price = _value(position, "last_price", default=None)
        if raw_market_value is None and last_price is not None:
            market_value = quantity * _decimal(last_price)
        else:
            market_value = _decimal(raw_market_value)
        if last_price is None and quantity != 0 and market_value != 0:
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


_singleton_lock = threading.Lock()
_singleton: QmtAccountAdapter | None = None
_singleton_key: tuple[str, str, str, bool, bool] | None = None


def get_qmt_adapter(settings: Settings) -> QmtAccountAdapter:
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
    """Close and clear the singleton; intended for orderly shutdown and tests."""
    global _singleton, _singleton_key
    with _singleton_lock:
        if _singleton is not None:
            _singleton.close()
        _singleton = None
        _singleton_key = None
