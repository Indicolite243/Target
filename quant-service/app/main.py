from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from time import perf_counter

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile

from app.config import Settings, get_settings
from app.schemas.account import AccountSyncRequest
from app.schemas.analysis import PortfolioHistoryRequest
from app.schemas.common import InternalResponse
from app.schemas.market import QuoteRequest
from app.schemas.order import CancelOrderRequest, SubmitOrderRequest
from app.schemas.risk import RiskCalculationRequest
from app.services.mock_account_service import sync_account
from app.services.quote_service import batch_quotes
from app.services.order_service import cancel_order, submit_order
from app.services.qmt_account_service import (
    QmtAdapterError,
    get_qmt_adapter,
    reset_qmt_adapter,
)
from app.services.risk_service import calculate_risk
from app.services.portfolio_history_service import build_portfolio_history
from app.services.backtest_service import BacktestError, run_backtest


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    try:
        yield
    finally:
        reset_qmt_adapter()


app = FastAPI(
    title="Stock Manager Quant Service",
    version="1.0.0",
    lifespan=lifespan,
)


def authorize(
    x_internal_token: str = Header(alias="X-Internal-Token"),
    settings: Settings = Depends(get_settings),
) -> None:
    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


def response(data: dict, trace_id: str, started: float) -> InternalResponse:
    return InternalResponse(data=data, traceId=trace_id, durationMs=int((perf_counter() - started) * 1000))


@app.get("/internal/v1/health", response_model=InternalResponse)
def health(x_trace_id: str = Header(default="", alias="X-Trace-Id"),
           settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    if settings.mode == "QMT":
        qmt = get_qmt_adapter(settings).health()
    else:
        qmt = {
            "installed": False,
            "connected": False,
            "subscribed": False,
            "account": None,
            "status": "INACTIVE",
            "error": None,
        }
    return response({
        "service": "python-quant-service",
        "version": settings.service_version,
        "mode": settings.mode,
        "qmtConnected": qmt["connected"],
        "installed": qmt["installed"],
        "subscribed": qmt["subscribed"],
        "status": qmt["status"],
        "lastError": qmt["error"],
        "error": qmt["error"],
        "qmt": qmt,
        "readEnabled": settings.qmt_read_enabled,
        "tradeEnabled": settings.qmt_trade_enabled,
        "quoteSourceAvailable": True,
    }, x_trace_id, started)


@app.post("/internal/v1/accounts/sync", response_model=InternalResponse, dependencies=[Depends(authorize)])
def accounts_sync(payload: AccountSyncRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    if settings.mode != "QMT":
        return response(sync_account(payload), x_trace_id, started)
    try:
        data = get_qmt_adapter(settings).sync(payload)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/market/quotes", response_model=InternalResponse, dependencies=[Depends(authorize)])
def quotes(payload: QuoteRequest,
           x_trace_id: str = Header(default="", alias="X-Trace-Id"),
           settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).quotes(payload.symbols) if settings.mode == "QMT" else batch_quotes(payload.symbols)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/risk/calculate", response_model=InternalResponse, dependencies=[Depends(authorize)])
def risk(payload: RiskCalculationRequest,
         x_trace_id: str = Header(default="", alias="X-Trace-Id")) -> InternalResponse:
    started = perf_counter()
    return response(calculate_risk(payload), x_trace_id, started)


@app.post("/internal/v1/analysis/portfolio-history", response_model=InternalResponse, dependencies=[Depends(authorize)])
def portfolio_history(payload: PortfolioHistoryRequest,
                      x_trace_id: str = Header(default="", alias="X-Trace-Id")) -> InternalResponse:
    started = perf_counter()
    try:
        data = build_portfolio_history(payload)
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"QMT历史行情计算失败: {exc}") from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/orders/submit", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_submit(payload: SubmitOrderRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).submit_order(payload) if settings.mode == "QMT" else submit_order(payload)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/orders/cancel", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_cancel(payload: CancelOrderRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).cancel_order(payload) if settings.mode == "QMT" else cancel_order(payload)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.get("/internal/v1/orders/query", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_query(x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                 settings: Settings = Depends(get_settings)) -> InternalResponse:
    started = perf_counter()
    if settings.mode != "QMT":
        return response({"orders": [], "source": "simulation", "warnings": []}, x_trace_id, started)
    try:
        data = get_qmt_adapter(settings).query_orders()
    except QmtAdapterError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/backtests/run", response_model=InternalResponse, dependencies=[Depends(authorize)])
async def backtests_run(
    file: UploadFile = File(...),
    market_files: list[UploadFile] = File(default=[]),
    start_date: str = Form(...),
    end_date: str = Form(...),
    engine_type: str = Form(default="auto"),
    benchmark_symbol: str = Form(default=""),
    enable_bear_protection: bool = Form(default=False),
    x_trace_id: str = Header(default="", alias="X-Trace-Id"),
) -> InternalResponse:
    started = perf_counter()
    try:
        data = await run_backtest(file, market_files, start_date, end_date, engine_type,
                                  benchmark_symbol, enable_bear_protection)
    except BacktestError as exc:
        detail = {
            "message": str(exc),
            "detail": exc.detail,
            "error_code": "missing_market_data" if exc.missing_files else "backtest_failed",
            "missing_market_files": exc.missing_files,
        }
        raise HTTPException(status_code=400 if exc.missing_files else 422, detail=detail) from exc
    return response(data, x_trace_id, started)
