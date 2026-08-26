import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app
from app.schemas.account import AccountSyncRequest
from app.schemas.order import CancelOrderRequest, SubmitOrderRequest
from app.services.qmt_account_service import (
    QmtAccountAdapter,
    QmtConfigurationError,
    QmtConnectionError,
    QmtQueryError,
    QmtSubscriptionError,
)
import app.services.qmt_account_service as qmt_account_service


ACCOUNT_ID = "62283925"


class FakeCallbackBase:
    pass


class FakeStockAccount:
    def __init__(self, account_id: str, account_type: str = "STOCK") -> None:
        self.account_id = account_id
        self.account_type = account_type


def fake_loader(trader_class):
    modules = {
        "xtquant.xttrader": SimpleNamespace(
            XtQuantTrader=trader_class,
            XtQuantTraderCallback=FakeCallbackBase,
        ),
        "xtquant.xttype": SimpleNamespace(StockAccount=FakeStockAccount),
    }
    return lambda name: modules[name]


def make_trader(
    *,
    connect_result=0,
    subscribe_result=0,
    positions=None,
    orders=None,
    trades=None,
):
    class FakeTrader:
        instances = []

        def __init__(self, path: str, session_id: int) -> None:
            self.path = path
            self.session_id = session_id
            self.calls = []
            self.callback = None
            self.__class__.instances.append(self)

        def register_callback(self, callback) -> None:
            self.calls.append("register_callback")
            self.callback = callback

        def start(self) -> None:
            self.calls.append("start")

        def stop(self) -> None:
            self.calls.append("stop")

        def connect(self) -> int:
            self.calls.append("connect")
            return connect_result

        def query_account_infos(self):
            self.calls.append("query_account_infos")
            return [SimpleNamespace(account_id=ACCOUNT_ID, login_status=0)]

        def subscribe(self, account) -> int:
            self.calls.append("subscribe")
            assert account.account_id == ACCOUNT_ID
            assert account.account_type == "STOCK"
            return subscribe_result

        def query_stock_asset(self, account):
            self.calls.append("query_stock_asset")
            return SimpleNamespace(
                total_asset=123456.789,
                cash=23456.7,
                frozen_cash=12.345,
                market_value=99987.744,
                position_profit=88.8,
            )

        def query_stock_positions(self, account):
            self.calls.append("query_stock_positions")
            return positions

        def query_stock_orders(self, account, cancelable_only=False):
            self.calls.append("query_stock_orders")
            assert cancelable_only is False
            return orders

        def query_stock_trades(self, account):
            self.calls.append("query_stock_trades")
            return trades

    return FakeTrader


def settings(qmt_path: Path, **overrides) -> Settings:
    values = {
        "mode": "QMT",
        "qmt_path": str(qmt_path),
        "qmt_account_id": ACCOUNT_ID,
        "qmt_account_type": "STOCK",
        "qmt_read_enabled": True,
        "qmt_trade_enabled": False,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def request() -> AccountSyncRequest:
    return AccountSyncRequest(
        accountId="local-account",
        externalAccountId="SIM-LOCAL-ACCOUNT",
        environment="SIMULATION",
        includePositions=True,
        includeOrders=True,
    )


def test_qmt_submit_and_cancel_use_real_trader_methods(tmp_path):
    trader_class = make_trader(positions=[], orders=[], trades=[])

    def order_stock(self, account, symbol, side, quantity, price_type, price, strategy, remark):
        self.calls.append(("order_stock", account.account_id, symbol, side, quantity, price_type, price, strategy, remark))
        return 90001

    def query_stock_order(self, account, order_id):
        self.calls.append(("query_stock_order", order_id))
        return SimpleNamespace(order_id=order_id, stock_code="510300.SH", order_status=50)

    def cancel_order_stock(self, account, order_id):
        self.calls.append(("cancel_order_stock", account.account_id, order_id))
        return 0

    trader_class.order_stock = order_stock
    trader_class.query_stock_order = query_stock_order
    trader_class.cancel_order_stock = cancel_order_stock
    adapter = QmtAccountAdapter(
        settings(tmp_path, qmt_trade_enabled=True),
        module_loader=fake_loader(trader_class),
        session_id=100002,
    )
    submitted = adapter.submit_order(SubmitOrderRequest(
        orderId="1", clientOrderNo="ORD-1", externalAccountId=ACCOUNT_ID,
        symbol="510300.SH", side="BUY", orderType="LIMIT", quantity="100",
        price="4.12", environment="SIMULATION",
    ))
    assert submitted["externalOrderNo"] == "90001"
    assert submitted["status"] == "REPORTED"
    assert submitted["source"] == "qmt_order_stock"

    canceled = adapter.cancel_order(CancelOrderRequest(
        orderId="1", clientOrderNo="ORD-1", externalOrderNo="90001",
        externalAccountId=ACCOUNT_ID, environment="SIMULATION",
    ))
    assert canceled["status"] == "CANCEL_PENDING"
    assert ("cancel_order_stock", ACCOUNT_ID, 90001) in trader_class.instances[0].calls


def test_qmt_submit_requires_explicit_trade_enablement(tmp_path):
    trader_class = make_trader(positions=[], orders=[], trades=[])
    adapter = QmtAccountAdapter(settings(tmp_path), module_loader=fake_loader(trader_class))
    with pytest.raises(QmtConfigurationError):
        adapter.submit_order(SubmitOrderRequest(
            orderId="1", clientOrderNo="ORD-1", externalAccountId=ACCOUNT_ID,
            symbol="510300.SH", side="BUY", orderType="LIMIT", quantity="100",
            price="4.12", environment="SIMULATION",
        ))


def test_quotes_wait_for_the_first_tick_after_subscribing(tmp_path, monkeypatch):
    trader_class = make_trader(positions=[], orders=[], trades=[])

    class FakeXtData:
        def __init__(self):
            self.subscriptions = []
            self.read_count = 0

        def subscribe_quote(self, symbol, period, count):
            self.subscriptions.append((symbol, period, count))

        def get_full_tick(self, symbols):
            self.read_count += 1
            if self.read_count == 1:
                return {}
            return {"600000.SH": {"lastPrice": 9.78, "lastClose": 9.6}}

        def get_instrument_detail(self, symbol):
            return {"InstrumentName": "浦发银行"}

    fake_xtdata = FakeXtData()
    base_loader = fake_loader(trader_class)

    def loader(name):
        if name == "xtquant.xtdata":
            return fake_xtdata
        return base_loader(name)

    monkeypatch.setattr(qmt_account_service.time, "sleep", lambda _: None)
    adapter = QmtAccountAdapter(settings(tmp_path), module_loader=loader, session_id=100009)

    result = adapter.quotes(["600000.SH"])

    assert fake_xtdata.subscriptions == [("600000.SH", "1d", 1)]
    assert fake_xtdata.read_count == 2
    assert result["quotes"][0]["name"] == "浦发银行"
    assert result["quotes"][0]["lastPrice"] == "9.7800"


def test_qmt_sync_success_maps_all_read_models_and_ignores_request_account(tmp_path):
    position = SimpleNamespace(
        stock_code="600000.SH",
        instrument_name="浦发银行",
        volume=10000,
        can_use_volume=9000,
        avg_price=9.52,
        last_price=9.78,
        market_value=97800,
        float_profit=2600,
    )
    order = SimpleNamespace(
        order_id=101,
        order_sysid="SYS-101",
        stock_code="600000.SH",
        instrument_name="浦发银行",
        offset_flag=48,
        order_type=23,
        order_volume=1000,
        price=9.6,
        traded_volume=500,
        traded_price=9.59,
        order_status=55,
        status_msg="",
        order_time=1_785_900_000,
        strategy_name="readonly-test",
        order_remark="test",
    )
    trade = SimpleNamespace(
        traded_id="T-1",
        order_id=101,
        order_sysid="SYS-101",
        stock_code="600000.SH",
        instrument_name="浦发银行",
        offset_flag=48,
        traded_price=9.59,
        traded_volume=500,
        traded_amount=4795,
        traded_time=1_785_900_001,
        strategy_name="readonly-test",
        order_remark="test",
    )
    trader_class = make_trader(positions=[position], orders=[order], trades=[trade])
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100001
    )

    result = adapter.sync(request())

    trader = trader_class.instances[0]
    assert trader.calls[:5] == [
        "register_callback",
        "start",
        "connect",
        "query_account_infos",
        "subscribe",
    ]
    assert result["source"] == "qmt"
    assert result["environment"] == "SIMULATION"
    assert result["account"] == {
        "externalAccountId": ACCOUNT_ID,
        "accountName": "国金QMT模拟账户",
        "broker": "GUOJIN_QMT",
        "environment": "SIMULATION",
        "currency": "CNY",
        "totalAsset": "123456.79",
        "cash": "23456.70",
        "availableCash": "23456.70",
        "frozenCash": "12.35",
        "marketValue": "99987.74",
        "profitLoss": "2600.00",
    }
    assert result["positions"][0]["securityName"] == "浦发银行"
    assert result["positions"][0]["lastPrice"] == "9.7800"
    assert result["orders"][0]["status"] == "PARTIALLY_FILLED"
    assert result["orders"][0]["side"] == "BUY"
    assert result["executions"][0]["executionId"] == "T-1"
    assert result["warnings"] == []

    health = adapter.health()
    assert health["installed"] is True
    assert health["connected"] is True
    assert health["subscribed"] is True
    assert health["account"] != ACCOUNT_ID
    assert health["account"].endswith("25")
    assert str(tmp_path) not in str(health)


def test_qmt_connect_failure_is_explicit_and_does_not_query_account(tmp_path):
    trader_class = make_trader(connect_result=-1)
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100002
    )

    with pytest.raises(QmtConnectionError, match=r"connect returned -1"):
        adapter.sync(request())

    trader = trader_class.instances[0]
    assert "query_account_infos" not in trader.calls
    health = adapter.health()
    assert health["connected"] is False
    assert health["subscribed"] is False
    assert "connect returned -1" in health["error"]


def test_qmt_subscribe_failure_is_explicit(tmp_path):
    trader_class = make_trader(subscribe_result=-1)
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100006
    )

    with pytest.raises(QmtSubscriptionError, match=r"subscribe returned -1"):
        adapter.sync(request())

    trader = trader_class.instances[0]
    assert "query_account_infos" in trader.calls
    assert "query_stock_asset" not in trader.calls
    assert adapter.health()["subscribed"] is False


def test_none_positions_abort_sync_to_preserve_last_known_holdings(tmp_path):
    trader_class = make_trader(positions=None, orders=None, trades=None)
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100003
    )

    with pytest.raises(QmtQueryError, match=r"preserve the last known holdings"):
        adapter.sync(request())

    assert "query_stock_orders" not in trader_class.instances[0].calls
    assert "preserve the last known holdings" in adapter.health()["error"]


def test_empty_positions_map_to_empty_but_none_orders_and_trades_warn(tmp_path):
    trader_class = make_trader(positions=[], orders=None, trades=None)
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100007
    )

    result = adapter.sync(request())

    assert result["positions"] == []
    assert result["orders"] == []
    assert result["executions"] == []
    assert len(result["warnings"]) == 2
    assert "no orders today" in result["warnings"][0]
    assert "no trades today" in result["warnings"][1]


def test_missing_position_profit_is_estimated_and_account_total_uses_positions(
    tmp_path,
):
    position = SimpleNamespace(
        stock_code="600000.SH",
        volume=100,
        can_use_volume=100,
        avg_price=10,
        last_price=12,
        market_value=1200,
    )
    trader_class = make_trader(positions=[position], orders=[], trades=[])
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100008
    )

    result = adapter.sync(request())

    assert result["positions"][0]["profitLoss"] == "200.00"
    assert result["account"]["profitLoss"] == "200.00"
    assert result["warnings"] == [
        "部分持仓盈亏按持仓市值减成本估算，未扣费用，非券商结算口径。"
    ]


def test_disconnection_callback_reconnects_on_next_sync(tmp_path):
    trader_class = make_trader(positions=[], orders=[], trades=[])
    adapter = QmtAccountAdapter(
        settings(tmp_path), module_loader=fake_loader(trader_class), session_id=100004
    )

    adapter.sync(request())
    trader = trader_class.instances[0]
    trader.callback.on_disconnected()
    assert adapter.health()["connected"] is False

    adapter.sync(request())

    assert trader.calls.count("connect") == 2
    assert trader.calls.count("query_account_infos") == 2
    assert trader.calls.count("subscribe") == 2


def test_user_site_packages_fallback_is_used_after_initial_module_not_found(
    tmp_path, monkeypatch
):
    trader_class = make_trader(positions=[], orders=[], trades=[])
    user_site = tmp_path / "user-site"
    user_site.mkdir()
    monkeypatch.setattr(sys, "path", list(sys.path))
    base_loader = fake_loader(trader_class)

    def loader(name):
        if str(user_site) not in sys.path:
            raise ModuleNotFoundError("No module named 'xtquant'", name="xtquant")
        return base_loader(name)

    adapter = QmtAccountAdapter(
        settings(tmp_path),
        module_loader=loader,
        user_site_getter=lambda: str(user_site),
        session_id=100005,
    )

    health = adapter.health()

    assert str(user_site) in sys.path
    assert health["installed"] is True
    assert health["connected"] is False


def test_settings_bind_expected_qmt_environment_names(tmp_path, monkeypatch):
    for name in (
        "QUANT_MODE",
        "QUANT_QMT_PATH",
        "QUANT_QMT_ACCOUNT_ID",
        "QUANT_QMT_ACCOUNT_TYPE",
        "QUANT_QMT_READ_ENABLED",
        "QUANT_QMT_TRADE_ENABLED",
    ):
        monkeypatch.delenv(name, raising=False)
    env_file = tmp_path / ".env"
    env_file.write_text(
        "\n".join(
            (
                "QUANT_MODE=qmt",
                f"QUANT_QMT_PATH={tmp_path}",
                "QUANT_QMT_ACCOUNT_ID=10020030",
                "QUANT_QMT_ACCOUNT_TYPE=stock",
                "QUANT_QMT_READ_ENABLED=false",
                "QUANT_QMT_TRADE_ENABLED=true",
            )
        ),
        encoding="utf-8",
    )

    loaded = Settings(_env_file=env_file)

    assert loaded.mode == "QMT"
    assert loaded.qmt_path == str(tmp_path)
    assert loaded.qmt_account_id == "10020030"
    assert loaded.qmt_account_type == "STOCK"
    assert loaded.qmt_read_enabled is False
    assert loaded.qmt_trade_enabled is True


def test_qmt_mode_returns_http_503_without_falling_back_to_mock(tmp_path, monkeypatch):
    class BrokenAdapter:
        def sync(self, payload):
            raise QmtConnectionError("QMT connection failed (connect returned -1).")

    qmt_settings = settings(tmp_path, internal_token="test-token")
    app.dependency_overrides[get_settings] = lambda: qmt_settings
    monkeypatch.setattr("app.main.get_qmt_adapter", lambda _: BrokenAdapter())
    try:
        response = TestClient(app).post(
            "/internal/v1/accounts/sync",
            headers={"X-Internal-Token": "test-token"},
            json={
                "accountId": "local-account",
                "externalAccountId": "SIM-LOCAL-ACCOUNT",
                "environment": "SIMULATION",
                "includePositions": True,
                "includeOrders": True,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 503
    assert "connect returned -1" in response.json()["detail"]


def test_health_exposes_top_level_qmt_state_without_secrets(tmp_path, monkeypatch):
    class HealthAdapter:
        def health(self):
            return {
                "installed": True,
                "connected": True,
                "subscribed": True,
                "account": "******25",
                "status": "READY",
                "error": None,
            }

    qmt_settings = settings(tmp_path)
    app.dependency_overrides[get_settings] = lambda: qmt_settings
    monkeypatch.setattr("app.main.get_qmt_adapter", lambda _: HealthAdapter())
    try:
        response = TestClient(app).get("/internal/v1/health")
    finally:
        app.dependency_overrides.clear()

    data = response.json()["data"]
    assert data["qmtConnected"] is True
    assert data["installed"] is True
    assert data["subscribed"] is True
    assert data["status"] == "READY"
    assert data["lastError"] is None
    assert ACCOUNT_ID not in str(data)
    assert str(tmp_path) not in str(data)


def test_explicit_test_mock_mode_keeps_mock_sync(tmp_path, monkeypatch):
    test_mock_settings = settings(
        tmp_path, mode="TEST_MOCK", internal_token="test-token"
    )
    app.dependency_overrides[get_settings] = lambda: test_mock_settings

    def fail_if_called(_):
        raise AssertionError("QMT adapter must not be used in TEST_MOCK mode")

    monkeypatch.setattr("app.main.get_qmt_adapter", fail_if_called)
    try:
        response = TestClient(app).post(
            "/internal/v1/accounts/sync",
            headers={"X-Internal-Token": "test-token"},
            json={
                "accountId": "local-account",
                "externalAccountId": "SIM-LOCAL-ACCOUNT",
                "environment": "SIMULATION",
                "includePositions": True,
                "includeOrders": True,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["data"]["source"] == "mock"


def test_live_endpoint_forces_one_portfolio_snapshot_without_orders(tmp_path, monkeypatch):
    qmt_settings = settings(tmp_path, internal_token="test-token")
    received = []

    class LiveAdapter:
        def sync(self, payload):
            received.append(payload)
            return {
                "snapshotTime": "2026-08-25T10:00:00+08:00",
                "account": {"totalAsset": "100.00", "cash": "20.00", "marketValue": "80.00"},
                "positions": [],
                "orders": [],
                "executions": [],
                "source": "qmt",
                "warnings": [],
            }

    app.dependency_overrides[get_settings] = lambda: qmt_settings
    monkeypatch.setattr("app.main.get_qmt_adapter", lambda _: LiveAdapter())
    try:
        response = TestClient(app).post(
            "/internal/v1/accounts/live",
            headers={"X-Internal-Token": "test-token"},
            json={
                "accountId": "local-account",
                "externalAccountId": ACCOUNT_ID,
                "environment": "SIMULATION",
                "includePositions": False,
                "includeOrders": True,
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert received[0].includePositions is True
    assert received[0].includeOrders is False
    assert response.json()["data"]["source"] == "qmt"


def test_fastapi_lifespan_resets_qmt_singleton(monkeypatch):
    reset_calls = []
    monkeypatch.setattr(
        "app.main.reset_qmt_adapter", lambda: reset_calls.append("reset")
    )

    with TestClient(app):
        pass

    assert reset_calls == ["reset"]
