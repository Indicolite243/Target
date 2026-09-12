"""策略文件回测的安全调度与结果标准化服务。

该模块负责校验上传内容、识别策略引擎、准备任务专属目录、以子进程执行策略、限制最长运行
时间并统一结果协议。真正的MindGo撮合实现在`mindgo_runner.py`；这里不在FastAPI进程内直接
执行用户策略，避免策略异常污染QMT连接和Web服务状态。
"""

from __future__ import annotations

import hashlib
import json
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
    """可安全映射到HTTP响应的回测业务异常。"""

    def __init__(self, message: str, *, detail: str = "", missing_files: list[str] | None = None):
        """保存用户提示、受限长度的诊断详情和缺失行情文件列表。"""

        super().__init__(message)
        self.detail = detail
        self.missing_files = missing_files or []


def _safe_name(filename: str | None, fallback: str) -> str:
    """去除目录并限制文件名字符，防止上传名称造成路径穿越。"""

    # Path.name丢弃客户端传入的目录部分，只保留最后一级文件名。
    name = Path(filename or fallback).name
    sanitized = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return sanitized or fallback


def _is_mindgo(script: str) -> bool:
    """通过兼容API导入特征判断脚本是否为MindGo/SuperMind格式。"""

    lowered = script.lower()
    return "mindgo_api" in lowered or "from mindgo_api import *" in lowered


def _engine(requested: str, script: str) -> str:
    """规范化用户选择；auto模式依据脚本内容识别执行器。"""

    requested = (requested or "auto").strip().lower()
    if requested not in {"auto", "mindgo", "backtrader"}:
        requested = "auto"
    return ("mindgo" if _is_mindgo(script) else "backtrader") if requested == "auto" else requested


def _missing_files(output: str) -> list[str]:
    """从不同Python异常格式中提取缺失的xlsx文件名，并保持首次出现顺序。"""

    matches: list[str] = []
    # 同时兼容英文FileNotFoundError和回测内核主动抛出的中文提示。
    for pattern in (
        r"No such file or directory[^\r\n]*['\"]([^'\"]+?\.xlsx)['\"]",
        r"FileNotFoundError[^\r\n]*?([A-Za-z0-9._-]+\.xlsx)",
        r"未找到行情文件[^\r\n]*?([A-Za-z0-9._-]+)",
    ):
        matches.extend(Path(item).name for item in re.findall(pattern, output, re.IGNORECASE))
    return list(dict.fromkeys(matches))


def _normalize(raw: dict[str, Any]) -> dict[str, Any]:
    """把任意兼容策略结果转换为前端统一收益曲线和风险指标协议。"""

    # 三条序列必须按同一交易日严格对齐，否则图表和统计指标都没有可靠含义。
    dates = list(raw.get("dates") or [])
    strategy = [float(value) for value in (raw.get("strategy") or [])]
    benchmark = [float(value) for value in (raw.get("benchmark") or [])]
    if not dates or len(dates) != len(strategy) or len(dates) != len(benchmark):
        raise BacktestError("策略结果格式错误：dates、strategy、benchmark 必须是长度一致的非空数组")
    if raw.get("_skip_normalization"):
        # 自研MindGo引擎已经按SuperMind口径计算，禁止再次做基准化导致双重转换。
        return raw

    # 第一个观测点归零，兼容直接输出累计收益而非从0开始的第三方策略。
    strategy0 = strategy[0]
    benchmark0 = benchmark[0]
    strategy = [round(value - strategy0, 4) for value in strategy]
    benchmark = [round(value - benchmark0, 4) for value in benchmark]
    # 百分比累计收益转换为净值，复合超额收益使用策略净值/基准净值计算。
    strategy_nav = 1.0 + np.asarray(strategy, dtype=float) / 100.0
    benchmark_nav = 1.0 + np.asarray(benchmark, dtype=float) / 100.0
    excess = np.round((strategy_nav / benchmark_nav - 1.0) * 100.0, 4).tolist()
    # 补入初始净值1.0，使首日收益和最大回撤都包含从初始资金到首个收盘的变化。
    strategy_path = np.concatenate(([1.0], strategy_nav))
    benchmark_path = np.concatenate(([1.0], benchmark_nav))
    daily = strategy_path[1:] / strategy_path[:-1] - 1.0
    benchmark_daily = benchmark_path[1:] / benchmark_path[:-1] - 1.0
    n_daily = len(daily)
    total_return = float(strategy[-1])
    benchmark_total_return = float(benchmark[-1])
    # 与SuperMind对齐使用250个交易日年化；净值非正时无法做幂运算，按-100%处理。
    annual_return_decimal = float(strategy_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily and strategy_nav[-1] > 0 else -1.0
    benchmark_annual_decimal = float(benchmark_nav[-1] ** (250.0 / n_daily) - 1.0) if n_daily and benchmark_nav[-1] > 0 else -1.0
    annual_return = annual_return_decimal * 100.0
    benchmark_annual_return = benchmark_annual_decimal * 100.0
    # 每个时点相对历史峰值的跌幅构成回撤序列。
    drawdowns = (strategy_path - np.maximum.accumulate(strategy_path)) / np.maximum.accumulate(strategy_path)
    volatility = float(np.sqrt((250.0 / n_daily) * np.square(daily - daily.mean()).sum())) if n_daily else 0.0
    benchmark_centered = benchmark_daily - benchmark_daily.mean() if n_daily else np.array([])
    strategy_centered = daily - daily.mean() if n_daily else np.array([])
    # Beta分母为基准离均差平方和；基准无波动时Beta定义为0以避免除零。
    benchmark_variation = float(np.square(benchmark_centered).sum()) if n_daily else 0.0
    beta = float((strategy_centered * benchmark_centered).sum() / benchmark_variation) if benchmark_variation > 0 else 0.0
    # 主动收益用于跟踪误差、信息比率和下行风险。
    active = daily - benchmark_daily
    tracking_error = (
        float(np.sqrt((250.0 / (n_daily - 1)) * np.square(active - active.mean()).sum()))
        if n_daily > 1 else 0.0
    )
    downside = np.where(daily < benchmark_daily, active, 0.0)
    downside_risk = float(np.sqrt((250.0 / n_daily) * np.square(downside).sum())) if n_daily else 0.0
    # 无风险利率通过环境变量注入，默认使用当前对齐样本中的1.95%。
    risk_free_rate = float(os.environ.get("BACKTEST_RISK_FREE_RATE", "0.0195"))
    alpha = annual_return_decimal - risk_free_rate - beta * (benchmark_annual_decimal - risk_free_rate)
    sharpe = (annual_return_decimal - risk_free_rate) / volatility if volatility > 0 else 0.0
    sortino = (annual_return_decimal - risk_free_rate) / downside_risk if downside_risk > 0 else 0.0
    information_ratio = float(active.mean() * 250.0 / tracking_error) if tracking_error > 0 else 0.0
    # 保留策略自行提供的扩展指标，同名核心指标以统一口径覆盖。
    metrics = dict(raw.get("metrics") or {})
    metrics.update({
        "total_return": f"{total_return:.2f}%",
        "benchmark_return": f"{benchmark_total_return:.2f}%",
        "annual_return": f"{annual_return:.2f}%",
        "benchmark_annual_return": f"{benchmark_annual_return:.2f}%",
        "max_drawdown": f"{abs(float(drawdowns.min())) * 100:.2f}%",
        "sharpe_ratio": f"{sharpe:.2f}",
        "sortino_ratio": f"{sortino:.2f}",
        "alpha": f"{alpha:.2f}",
        "beta": f"{beta:.2f}",
        "volatility": f"{volatility:.2f}",
        "tracking_error": f"{tracking_error:.2f}",
        "information_ratio": f"{information_ratio:.2f}",
        "downside_risk": f"{downside_risk:.2f}",
        "win_rate": f"{float((daily > 0).mean()) * 100:.2f}%" if n_daily else "0.00%",
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
    """校验并执行一次上传策略，返回标准结果与可审计执行元数据。"""

    # 上传文件先完整读取到内存，并设置5MB上限，避免大文件耗尽Web进程内存。
    raw = await strategy_file.read()
    if not raw:
        raise BacktestError("策略文件为空")
    if len(raw) > 5 * 1024 * 1024:
        raise BacktestError("策略文件不能超过 5MB")
    # 国内量化脚本常见UTF-8和GBK编码，按无损优先顺序尝试解码。
    script = None
    for encoding in ("utf-8", "utf-8-sig", "gbk", "gb2312"):
        try:
            script = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if script is None:
        raise BacktestError("策略文件编码无法识别，请使用 UTF-8 或 GBK")

    # 显式选择与脚本格式冲突时立即失败，避免用错误解释器产生难懂的运行异常。
    # resolved_engine 是本次真正采用的执行器；请求值为 auto 时会读取脚本特征完成判定。
    resolved_engine = _engine(engine_type, script)
    if resolved_engine == "mindgo" and not _is_mindgo(script):
        raise BacktestError("该文件不是 MindGo/SuperMind 策略，请选择自动识别或 Backtrader")
    if resolved_engine == "backtrader" and _is_mindgo(script):
        raise BacktestError("该文件是 MindGo/SuperMind 策略，请选择自动识别或 MindGo")

    # 每次请求使用独立随机目录，避免并发回测覆盖行情、策略或结果文件。
    # 从当前模块反推 quant-service 根目录，避免运行时工作目录不同导致相对路径漂移。
    service_root = Path(__file__).resolve().parents[2]
    # 随机 request_id 只标识 Python 侧的一次执行；Spring 侧仍以 quant_task.id 作为业务主键。
    request_id = uuid.uuid4().hex[:12]
    # run_dir 保存策略、结果和元数据，work_dir 作为用户策略的当前目录和行情查找根目录。
    run_dir = service_root / "runtime" / "backtests" / request_id
    work_dir = run_dir / "data"
    # exist_ok=False 可在极小概率目录冲突时立即失败，禁止两个请求共享运行空间。
    work_dir.mkdir(parents=True, exist_ok=False)
    packaged_data = service_root / "backtest-data"
    if packaged_data.is_dir():
        # 先复制项目内置行情，用户上传同名文件随后覆盖，便于补充或更新样本。
        for source in packaged_data.glob("*.xlsx"):
            shutil.copy2(source, work_dir / source.name)

    # 只在审计元数据中记录清洗后的文件名，不记录上传内容或客户端绝对路径。
    uploaded_market_files: list[str] = []
    for market_file in market_files:
        # 非xlsx附件直接忽略；文件名清洗后只能落在work_dir内部。
        filename = _safe_name(market_file.filename, "market_data.xlsx")
        if not filename.lower().endswith(".xlsx"):
            continue
        # UploadFile 是异步文件接口；完整读取后写入当前请求独占的 data 目录。
        content = await market_file.read()
        (work_dir / filename).write_bytes(content)
        uploaded_market_files.append(filename)

    # 策略文件保留清洗后的原名，便于异常堆栈和审计元数据定位。
    strategy_name = _safe_name(strategy_file.filename, "strategy.py")
    if not strategy_name.lower().endswith(".py"):
        raise BacktestError("策略文件必须是 .py 文件")
    # 策略放在 run_dir，而执行 cwd 指向 data；这样策略和输入行情、输出文件边界更清楚。
    strategy_path = run_dir / strategy_name
    strategy_path.write_bytes(raw)
    # 新协议要求策略/兼容执行器把结构化结果写到该路径，stdout 只用于诊断。
    result_path = run_dir / "strategy_performance.json"
    # 子进程继承基础环境，再注入本次任务独有参数；不修改FastAPI进程全局环境。
    env = os.environ.copy()
    env.update({
        "STRATEGY_NO_PLOT": "1",
        "MPLBACKEND": "Agg",
        "PYTHONIOENCODING": "utf-8",
        "BACKTEST_START_DATE": start_date,
        "BACKTEST_END_DATE": end_date,
        "BACKTEST_BENCHMARK": benchmark_symbol or "000300.SH",
        "BACKTEST_ENABLE_BEAR_PROTECTION": "1" if enable_bear_protection else "0",
        "BACKTEST_DATA_DIR": str(work_dir),
        "BACKTEST_RESULT_JSON_PATH": str(result_path),
        "BACKTEST_RISK_FREE_RATE": os.environ.get("BACKTEST_RISK_FREE_RATE", "0.0195"),
    })
    # MindGo策略经过兼容执行器；普通Python/Backtrader脚本直接由同一解释器启动。
    # MindGo 源码不能直接由普通 Python 运行，需要兼容层提供 initialize、history、order 等 API。
    runner = Path(__file__).with_name("mindgo_runner.py")
    # shell=False（subprocess.run 的默认值）意味着文件名不会被拼进 Shell 命令，降低命令注入风险。
    command = [sys.executable, str(runner), str(strategy_path)] if resolved_engine == "mindgo" else [sys.executable, str(strategy_path)]
    try:
        # capture_output避免子进程污染服务控制台；300秒硬超时防止死循环长期占用资源。
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

    # 新协议写入run_dir；兼容旧策略仍在当前工作目录写固定结果文件。
    shared_result = work_dir / "strategy_performance.json"
    # 优先读取环境变量指定的新结果路径；旧策略若写在 cwd，则兼容读取 shared_result。
    actual_result = result_path if result_path.exists() else shared_result
    # stdout 与 stderr 合并后仅用于失败诊断，不会作为成功业务数据直接返回前端。
    combined_output = f"{completed.stdout}\n{completed.stderr}"
    if completed.returncode != 0 and not actual_result.exists():
        # 优先给出可操作的缺失行情提示；详细输出最多保留末尾3000字符。
        missing = _missing_files(combined_output)
        message = "策略缺少行情文件" if missing else f"策略执行失败（退出码 {completed.returncode}）"
        raise BacktestError(message, detail=combined_output[-3000:], missing_files=missing)
    if not actual_result.exists():
        raise BacktestError(
            "策略运行完成但没有生成 strategy_performance.json",
            detail=combined_output[-3000:],
        )

    try:
        # JSON解析后再做统一口径转换，任何非结构化stdout都不会进入成功结果。
        normalized = _normalize(json.loads(actual_result.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError) as exc:
        raise BacktestError("回测结果 JSON 无法读取", detail=str(exc)) from exc
    # execution_meta记录文件摘要和执行选择，不保存策略全文或敏感账户信息。
    # execution_meta 用于结果页说明“谁、以什么引擎和参数执行”，不参与收益指标计算。
    requested_benchmark = normalized.get("benchmark_symbol") or env["BACKTEST_BENCHMARK"]
    used_benchmark = normalized.get("benchmark_symbol_used") or requested_benchmark
    warnings = list(normalized.get("warnings") or [])
    normalized["execution_meta"] = {
        "request_id": request_id,
        "uploaded_filename": strategy_file.filename,
        "uploaded_file_md5": hashlib.md5(raw).hexdigest(),
        "requested_engine": engine_type or "auto",
        "resolved_engine": resolved_engine,
        "executor_type": "mindgo_runner" if resolved_engine == "mindgo" else "python_script",
        "strategy_format": "mindgo" if resolved_engine == "mindgo" else "python",
        "benchmark_symbol": used_benchmark,
        "benchmark_symbol_requested": requested_benchmark,
        "benchmark_data_source": normalized.get("benchmark_data_source", "strategy_output"),
        "benchmark_warning": warnings[0] if warnings else "",
        "bear_protection_enabled": enable_bear_protection,
        "uploaded_market_files": uploaded_market_files,
        "source": "uploaded_strategy_execution",
        "is_mock": False,
        "engine_info": normalized.get("engine", {}),
        "artifacts": normalized.get("artifacts", {}),
    }
    # 返回给 Spring 的是统一业务 data；FastAPI 路由还会在外层补 success/traceId/durationMs。
    return {
        "status": "success",
        "message": f"策略执行完成，共 {len(normalized['dates'])} 个交易日数据",
        "data": normalized,
        "is_mock": False,
        "dataSource": "用户上传策略真实执行结果",
    }
