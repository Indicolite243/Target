from types import SimpleNamespace

import pandas as pd
import pytest

from app.services import mindgo_runner


def make_engine(monkeypatch, tmp_path):
    monkeypatch.setenv("BACKTEST_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("BACKTEST_RESULT_JSON_PATH", str(tmp_path / "result.json"))
    monkeypatch.setenv("BACKTEST_START_DATE", "2024-01-01")
    monkeypatch.setenv("BACKTEST_END_DATE", "2024-12-31")
    return mindgo_runner.MindgoBacktestEngine(tmp_path / "strategy.py")


def test_order_uses_raw_open_half_slippage_lot_and_daily_volume_limit(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    engine.set_commission(mindgo_runner.PerShare(cost=0.0002))
    engine.set_slippage(mindgo_runner.PriceSlippage(0.001))
    engine.current_date = pd.Timestamp("2024-01-02")
    engine.current_bars = {
        "510170.SH": mindgo_runner.Bar(4.12, 4.2, 4.1, 4.18, 39_400, 4.12, 4.2, 4.1, 4.18)
    }
    engine.current_exec_prices = {"510170.SH": 4.12}
    engine.current_mark_prices = {"510170.SH": 4.12}
    engine.current_remaining_volume = {"510170.SH": 9_800}

    order = engine.order_value("510170.SH", 200_000)

    assert order.amount == 9_800
    assert order.price == pytest.approx(4.12 * 1.0005)
    assert order.amount % 100 == 0
    assert engine.current_remaining_volume["510170.SH"] == 0


def test_commission_has_supermind_five_yuan_minimum(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    engine.set_commission(mindgo_runner.PerShare(cost=0.0002))

    assert engine._trade_fee(1_000) == 5.0
    assert engine._trade_fee(100_000) == 20.0


def test_daily_mark_uses_close_while_execution_keeps_open(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    engine.current_bars = {
        "510300.SH": mindgo_runner.Bar(4.12, 4.2, 4.1, 4.18, 1_000_000, 4.12, 4.2, 4.1, 4.18)
    }
    engine.current_exec_prices = {"510300.SH": 4.12}

    engine._mark_to_close()

    assert engine.current_mark_prices["510300.SH"] == 4.18
    assert engine._get_exec_price("510300.SH", True) == 4.12


def test_pre_adjusted_price_is_dynamically_anchored_to_query_end(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    index = pd.to_datetime(["2024-01-02", "2024-01-03"])
    engine.data_cache["510300.SH"] = pd.DataFrame(
        {
            "open": [9.0, 11.0], "high": [11.0, 13.0], "low": [8.0, 10.0], "close": [10.0, 12.0],
            "volume": [1000, 2000], "open_pre": [4.5, 5.5], "high_pre": [5.5, 6.5],
            "low_pre": [4.0, 5.0], "close_pre": [5.0, 6.0],
        },
        index=index,
    )

    prices = engine.get_price("510300.SH", end_date="2024-01-03", fields=["close", "volume"], fq="pre")

    assert prices["close"].tolist() == [10.0, 12.0]
    assert prices["volume"].tolist() == [1000, 2000]


def test_corporate_action_adjusts_cash_shares_and_cost(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    engine.context.portfolio.cash = 0.0
    engine.context.portfolio.positions["510500.SH"] = mindgo_runner.Position(amount=100, avg_cost=10.0)
    factors = pd.DataFrame(
        [{"interest": 0.5, "stockBonus": 1.0, "stockGift": 0.0, "allotNum": 0.0, "allotPrice": 0.0}],
        index=["20240102"],
    )
    monkeypatch.setattr(mindgo_runner, "xtdata", SimpleNamespace(get_divid_factors=lambda code: factors))

    engine._apply_corporate_actions(pd.Timestamp("2024-01-02"), ["510500.SH"])

    position = engine.context.portfolio.positions["510500.SH"]
    assert engine.context.portfolio.cash == 50.0
    assert position.amount == 200
    assert position.avg_cost == pytest.approx(4.75)


def test_export_uses_compounded_excess_and_includes_first_day_return(monkeypatch, tmp_path):
    engine = make_engine(monkeypatch, tmp_path)
    engine.records = {
        "2024-01-02": {"net_value": 1_100_000},
        "2024-01-03": {"net_value": 1_210_000},
    }
    benchmark = pd.DataFrame(
        {"close": [100.0, 105.0, 110.0]},
        index=pd.to_datetime(["2023-12-29", "2024-01-02", "2024-01-03"]),
    )
    monkeypatch.setattr(engine, "_load_benchmark_symbol", lambda code: benchmark)
    monkeypatch.setattr(engine, "_export_markdown", lambda: None)
    monkeypatch.setattr(engine, "_export_excel", lambda: None)

    payload = engine.export_results()

    assert payload["strategy"] == [10.0, 21.0]
    assert payload["benchmark"] == [5.0, 10.0]
    assert payload["excess"] == [4.76, 10.0]
    assert payload["metrics"]["total_return"] == "21.00%"
    assert payload["metrics"]["benchmark_return"] == "10.00%"
