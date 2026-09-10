"""FastAPI内部接口入口。

浏览器只访问Spring Boot；Spring通过共享内部令牌调用本服务。这里负责协议校验、耗时与
traceId封装、运行模式路由和异常到HTTP状态的转换，具体计算与QMT副作用全部下沉到services。
"""

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
    """管理进程生命周期；退出时关闭QMT单例，释放Trader会话与回调。"""

    try:
        # yield之前无需主动连接QMT，首次真实请求再懒加载，缩短服务启动时间。
        yield
    finally:
        # 无论正常退出还是异常关闭，都清理长连接，避免重启后残留Session。
        reset_qmt_adapter()


# 路由统一挂在/internal/v1下，明确这些接口不是面向最终用户的公网API。
app = FastAPI(
    title="Stock Manager Quant Service",
    version="1.0.0",
    lifespan=lifespan,
)


def authorize(
    x_internal_token: str = Header(alias="X-Internal-Token"),
    settings: Settings = Depends(get_settings),
) -> None:
    """校验Spring与FastAPI之间的共享令牌；失败请求不得进入任何QMT读写逻辑。"""

    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


def response(data: dict, trace_id: str, started: float) -> InternalResponse:
    """把业务数据包装成统一响应，并记录本进程内部处理耗时。"""

    return InternalResponse(data=data, traceId=trace_id, durationMs=int((perf_counter() - started) * 1000))


@app.get("/internal/v1/health", response_model=InternalResponse)
def health(x_trace_id: str = Header(default="", alias="X-Trace-Id"),
           settings: Settings = Depends(get_settings)) -> InternalResponse:
    """返回运行模式、QMT连接状态以及读写开关，不暴露完整资金账号。"""

    started = perf_counter()
    # 健康检查允许触发SDK加载，但连接和订阅仍由实际业务请求按需建立。
    if settings.mode == "QMT":
        qmt = get_qmt_adapter(settings).health()
    else:
        # TEST_MOCK明确报告QMT未启用，而不是伪装成已连接。
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
    """读取账户、持仓以及可选的当日委托/成交完整快照。"""

    started = perf_counter()
    # Mock只能通过显式模式进入；QMT失败不会自动走这条分支。
    if settings.mode == "TEST_MOCK":
        return response(sync_account(payload), x_trace_id, started)
    try:
        data = get_qmt_adapter(settings).sync(payload)
    except QmtAdapterError as exc:
        # 账户同步失败返回503，让Spring保留Redis/MySQL中的最近一次有效快照。
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/accounts/live", response_model=InternalResponse, dependencies=[Depends(authorize)])
def accounts_live(payload: AccountSyncRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    """读取一份资金与持仓一致的实时快照。

    两秒采集热路径只调用此接口，并主动关闭委托/成交查询。Spring将同一份结果写入Redis，
    资产展示和对比评估因此共享同一个dataVersion，不会各自直连QMT产生口径差异。
    """
    started = perf_counter()
    # 即使调用方传错参数，实时接口仍强制包含持仓并排除低频订单数据。
    payload.includePositions = True
    payload.includeOrders = False
    if settings.mode == "TEST_MOCK":
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
    """批量读取证券票据与五档行情，QMT模式会完成订阅及首次有效tick等待。"""

    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).quotes(payload.symbols) if settings.mode == "QMT" else batch_quotes(payload.symbols)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/risk/calculate", response_model=InternalResponse, dependencies=[Depends(authorize)])
def risk(payload: RiskCalculationRequest,
         x_trace_id: str = Header(default="", alias="X-Trace-Id")) -> InternalResponse:
    """执行无外部副作用的风险统计计算。"""

    started = perf_counter()
    return response(calculate_risk(payload), x_trace_id, started)


@app.post("/internal/v1/analysis/portfolio-history", response_model=InternalResponse, dependencies=[Depends(authorize)])
def portfolio_history(payload: PortfolioHistoryRequest,
                      x_trace_id: str = Header(default="", alias="X-Trace-Id")) -> InternalResponse:
    """根据当前持仓与QMT日线重建分析用组合历史，不冒充券商真实历史净值。"""

    started = perf_counter()
    try:
        data = build_portfolio_history(payload)
    except Exception as exc:
        # xtdata下载、读取或数据格式错误统一映射为上游暂不可用。
        raise HTTPException(status_code=503, detail=f"QMT历史行情计算失败: {exc}") from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/orders/submit", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_submit(payload: SubmitOrderRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    """向选定适配器提交委托；本地幂等与订单状态事务已由Spring提前完成。"""

    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).submit_order(payload) if settings.mode == "QMT" else submit_order(payload)
    except QmtAdapterError as exc:
        # QMT明确拒绝映射为422；Spring据此写REJECTED而不是盲目重试。
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/orders/cancel", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_cancel(payload: CancelOrderRequest,
                  x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                  settings: Settings = Depends(get_settings)) -> InternalResponse:
    """向QMT发送一次撤单请求；成功仅代表已受理，最终状态仍由Spring轮询确认。"""

    started = perf_counter()
    try:
        data = get_qmt_adapter(settings).cancel_order(payload) if settings.mode == "QMT" else cancel_order(payload)
    except QmtAdapterError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.get("/internal/v1/orders/query", response_model=InternalResponse, dependencies=[Depends(authorize)])
def orders_query(x_trace_id: str = Header(default="", alias="X-Trace-Id"),
                 settings: Settings = Depends(get_settings)) -> InternalResponse:
    """批量查询QMT当日委托，供Spring后台按externalOrderNo完成状态收敛。"""

    started = perf_counter()
    if settings.mode == "TEST_MOCK":
        # Mock不维持跨请求订单簿，明确返回空列表而不是虚构状态。
        return response({"orders": [], "source": "test_mock", "warnings": []}, x_trace_id, started)
    try:
        data = get_qmt_adapter(settings).query_orders()
    except QmtAdapterError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return response(data, x_trace_id, started)


@app.post("/internal/v1/backtests/run", response_model=InternalResponse, dependencies=[Depends(authorize)])
async def backtests_run(
    # Spring QuantClient 重新上传的任务专属 strategy.py；该对象只在本次请求内有效。
    file: UploadFile = File(...),
    # 相同 multipart 字段名可以出现多次，FastAPI 会聚合成 UploadFile 列表。
    market_files: list[UploadFile] = File(default=[]),
    # 文本表单字段保持与浏览器/Spring API 命名一致，执行服务负责具体语义校验。
    start_date: str = Form(...),
    end_date: str = Form(...),
    # auto、mindgo、backtrader 三种选择最终由 backtest_service 解析。
    engine_type: str = Form(default="auto"),
    # 空基准会在执行服务中回退到 000300.SH。
    benchmark_symbol: str = Form(default=""),
    enable_bear_protection: bool = Form(default=False),
    # Java 透传的 TraceId 只用于统一响应和日志关联，不交给不可信策略进程。
    x_trace_id: str = Header(default="", alias="X-Trace-Id"),
) -> InternalResponse:
    """接收策略与可选行情文件，在隔离子进程中运行一次回测并返回结构化结果。"""

    # 使用单调时钟统计整个 Python 端处理耗时，避免系统时间校准造成负耗时。
    started = perf_counter()
    try:
        # run_backtest 会建立隔离目录并启动子进程；FastAPI 主进程不直接 exec 用户源码。
        data = await run_backtest(file, market_files, start_date, end_date, engine_type,
                                  benchmark_symbol, enable_bear_protection)
    except BacktestError as exc:
        # 缺少行情属于可补充输入的400；策略自身或结果格式错误属于422。
        detail = {
            "message": str(exc),
            "detail": exc.detail,
            "error_code": "missing_market_data" if exc.missing_files else "backtest_failed",
            "missing_market_files": exc.missing_files,
        }
        raise HTTPException(status_code=400 if exc.missing_files else 422, detail=detail) from exc
    # 成功时套入统一 InternalResponse：success、data、traceId 和 durationMs。
    return response(data, x_trace_id, started)
