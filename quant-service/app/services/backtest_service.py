from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Any

import numpy as np
from fastapi import UploadFile


class BacktestError(RuntimeError):
    def __init__(self, message: str, *, detail: str = "", missing_files: list[str] | None = None):
        super().__init__(message)
        self.detail = detail
        self.missing_files = missing_files or []


def _safe_name(filename: str | None, fallback: str) -> str:
    name = Path(filename or fallback).name
    sanitized = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return sanitized or fallback


def _is_mindgo(script: str) -> bool:
    lowered = script.lower()
    return "mindgo_api" in lowered or "from mindgo_api import *" in lowered


def _engine(requested: str, script: str) -> str:
    requested = (requested or "auto").strip().lower()
    if requested not in {"auto", "mindgo", "backtrader"}:
        requested = "auto"
    return ("mindgo" if _is_mindgo(script) else "backtrader") if requested == "auto" else requested


def _missing_files(output: str) -> list[str]:
    matches: list[str] = []
    for pattern in (
        r"No such file or directory[^\r\n]*['\"]([^'\"]+?\.xlsx)['\"]",
        r"FileNotFoundError[^\r\n]*?([A-Za-z0-9._-]+\.xlsx)",
        r"未找到行情文件[^\r\n]*?([A-Za-z0-9._-]+)",
    ):
        matches.extend(Path(item).name for item in re.findall(pattern, output, re.IGNORECASE))
    return list(dict.fromkeys(matches))


def _normalize(raw: dict[str, Any]) -> dict[str, Any]:
    dates = list(raw.get("dates") or [])
    strategy = [float(value) for value in (raw.get("strategy") or [])]
    benchmark = [float(value) for value in (raw.get("benchmark") or [])]
    if not dates or len(dates) != len(strategy) or len(dates) != len(benchmark):
        raise BacktestError("策略结果格式错误：dates、strategy、benchmark 必须是长度一致的非空数组")
    if raw.get("_skip_normalization"):
        return raw

    strategy0 = strategy[0]
    benchmark0 = benchmark[0]
    strategy = [round(value - strategy0, 4) for value in strategy]
    benchmark = [round(value - benchmark0, 4) for value in benchmark]
    excess = [round(left - right, 4) for left, right in zip(strategy, benchmark)]
    total_return = strategy[-1]
    annual_return = ((1 + total_return / 100) ** (252 / len(dates)) - 1) * 100
    net_values = np.array([1 + value / 100 for value in strategy])
    drawdowns = (net_values - np.maximum.accumulate(net_values)) / np.maximum.accumulate(net_values)
    daily = np.diff(np.array(strategy)) / 100
    sharpe = 0.0
    if daily.size and float(daily.std()) > 0:
        sharpe = float(((daily - 0.0175 / 252).mean() / daily.std()) * math.sqrt(252))
    metrics = dict(raw.get("metrics") or {})
    metrics.update({
        "total_return": f"{total_return:.2f}%",
        "annual_return": f"{annual_return:.2f}%",
        "max_drawdown": f"{abs(float(drawdowns.min())) * 100:.2f}%",
        "sharpe_ratio": f"{sharpe:.2f}",
    })
    normalized = dict(raw)
    normalized.update({
        "dates": dates,
        "strategy": strategy,
        "benchmark": benchmark,
        "excess": excess,
        "metrics": metrics,
        "_normalized": True,
    })
    return normalized


async def run_backtest(
    strategy_file: UploadFile,
    market_files: list[UploadFile],
    start_date: str,
    end_date: str,
    engine_type: str,
    benchmark_symbol: str,
    enable_bear_protection: bool,
) -> dict[str, Any]:
    raw = await strategy_file.read()
    if not raw:
        raise BacktestError("策略文件为空")
    if len(raw) > 5 * 1024 * 1024:
        raise BacktestError("策略文件不能超过 5MB")
    script = None
    for encoding in ("utf-8", "utf-8-sig", "gbk", "gb2312"):
        try:
            script = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if script is None:
        raise BacktestError("策略文件编码无法识别，请使用 UTF-8 或 GBK")

    resolved_engine = _engine(engine_type, script)
    if resolved_engine == "mindgo" and not _is_mindgo(script):
        raise BacktestError("该文件不是 MindGo/SuperMind 策略，请选择自动识别或 Backtrader")
    if resolved_engine == "backtrader" and _is_mindgo(script):
        raise BacktestError("该文件是 MindGo/SuperMind 策略，请选择自动识别或 MindGo")

    service_root = Path(__file__).resolve().parents[2]
    request_id = uuid.uuid4().hex[:12]
    run_dir = service_root / "runtime" / "backtests" / request_id
    work_dir = run_dir / "data"
    work_dir.mkdir(parents=True, exist_ok=False)
    packaged_data = service_root / "backtest-data"
    if packaged_data.is_dir():
        for source in packaged_data.glob("*.xlsx"):
            shutil.copy2(source, work_dir / source.name)

    uploaded_market_files: list[str] = []
    for market_file in market_files:
        filename = _safe_name(market_file.filename, "market_data.xlsx")
        if not filename.lower().endswith(".xlsx"):
            continue
        content = await market_file.read()
        (work_dir / filename).write_bytes(content)
        uploaded_market_files.append(filename)

    strategy_name = _safe_name(strategy_file.filename, "strategy.py")
    if not strategy_name.lower().endswith(".py"):
        raise BacktestError("策略文件必须是 .py 文件")
    strategy_path = run_dir / strategy_name
    strategy_path.write_bytes(raw)
    result_path = run_dir / "strategy_performance.json"
    env = os.environ.copy()
    env.update({
        "STRATEGY_NO_PLOT": "1",
        "MPLBACKEND": "Agg",
        "PYTHONIOENCODING": "utf-8",
        "BACKTEST_START_DATE": start_date,
        "BACKTEST_END_DATE": end_date,
        "BACKTEST_BENCHMARK": benchmark_symbol or "510300.SH",
        "BACKTEST_ENABLE_BEAR_PROTECTION": "1" if enable_bear_protection else "0",
        "BACKTEST_DATA_DIR": str(work_dir),
        "BACKTEST_RESULT_JSON_PATH": str(result_path),
        "BACKTEST_RISK_FREE_RATE": "0.0175",
    })
    runner = Path(__file__).with_name("mindgo_runner.py")
    command = [sys.executable, str(runner), str(strategy_path)] if resolved_engine == "mindgo" else [sys.executable, str(strategy_path)]
    try:
        completed = subprocess.run(
            command,
            cwd=work_dir,
            env=env,
            timeout=300,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except subprocess.TimeoutExpired as exc:
        raise BacktestError("策略运行超过 5 分钟，已终止") from exc

    shared_result = work_dir / "strategy_performance.json"
    actual_result = result_path if result_path.exists() else shared_result
    combined_output = f"{completed.stdout}\n{completed.stderr}"
    if completed.returncode != 0 and not actual_result.exists():
        missing = _missing_files(combined_output)
        message = "策略缺少行情文件" if missing else f"策略执行失败（退出码 {completed.returncode}）"
        raise BacktestError(message, detail=combined_output[-3000:], missing_files=missing)
    if not actual_result.exists():
        raise BacktestError(
            "策略运行完成但没有生成 strategy_performance.json",
            detail=combined_output[-3000:],
        )

    try:
        normalized = _normalize(json.loads(actual_result.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError) as exc:
        raise BacktestError("回测结果 JSON 无法读取", detail=str(exc)) from exc
    normalized["execution_meta"] = {
        "request_id": request_id,
        "uploaded_filename": strategy_file.filename,
        "uploaded_file_md5": hashlib.md5(raw).hexdigest(),
        "requested_engine": engine_type or "auto",
        "resolved_engine": resolved_engine,
        "executor_type": "mindgo_runner" if resolved_engine == "mindgo" else "python_script",
        "strategy_format": "mindgo" if resolved_engine == "mindgo" else "python",
        "benchmark_symbol": env["BACKTEST_BENCHMARK"],
        "bear_protection_enabled": enable_bear_protection,
        "uploaded_market_files": uploaded_market_files,
        "source": "uploaded_strategy_execution",
        "is_mock": False,
        "engine_info": normalized.get("engine", {}),
        "artifacts": normalized.get("artifacts", {}),
    }
    return {
        "status": "success",
        "message": f"策略执行完成，共 {len(normalized['dates'])} 个交易日数据",
        "data": normalized,
        "is_mock": False,
        "dataSource": "用户上传策略真实执行结果",
    }
