import pytest

from app.services.backtest_service import BacktestError, _normalize


def test_generic_result_uses_supermind_compounded_excess_and_250_day_year(monkeypatch):
    monkeypatch.setenv("BACKTEST_RISK_FREE_RATE", "0.0195")
    raw = {
        "dates": ["2024-01-01", "2024-01-02", "2024-01-03"],
        "strategy": [0.0, 10.0, 21.0],
        "benchmark": [0.0, 5.0, 10.0],
    }

    normalized = _normalize(raw)

    assert normalized["excess"] == [0.0, pytest.approx(4.7619), 10.0]
    assert normalized["metrics"]["total_return"] == "21.00%"
    assert normalized["metrics"]["benchmark_return"] == "10.00%"
    assert normalized["metrics"]["annual_return"] != "21.00%"


def test_generic_result_rejects_misaligned_series():
    with pytest.raises(BacktestError, match="长度一致"):
        _normalize({"dates": ["2024-01-01"], "strategy": [0.0], "benchmark": []})
