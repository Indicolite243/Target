"""TEST_MOCK模式的同步订单适配器。

真实QMT订单走`QmtAccountAdapter`；这里不模拟成交撮合，只为接口联调返回确定状态。
"""

from datetime import datetime

from app.schemas.order import CancelOrderRequest, SubmitOrderRequest


def submit_order(request: SubmitOrderRequest) -> dict:
    """为模拟环境生成一个可追踪的外部订单号并返回已提交状态。

    SIM前缀只用于接口联调；该订单号不会被发送到券商，也没有跨HTTP请求的模拟订单簿。
    """

    # 即使是Mock也拒绝REAL，避免测试入口被误认为实盘适配器。
    if request.environment != "SIMULATION":
        raise ValueError("REAL trading adapter is not enabled")
    return {
        # 原样回传Java生成的幂等号，方便测试核对请求与响应是否属于同一订单。
        "clientOrderNo": request.clientOrderNo,
        # 使用本地orderId构造稳定外部号，同一输入能得到同一结果。
        "externalOrderNo": f"SIM-{request.orderId}",
        "status": "SUBMITTED",
        "submittedAt": datetime.now().astimezone().isoformat(),
    }


def cancel_order(request: CancelOrderRequest) -> dict:
    """在Mock模式直接返回已撤；真实QMT撤单需要后台继续对账。

    这里不维护前序下单状态，所以它只能验证撤单协议映射，不能用于测试真实状态机时序。
    """

    if request.environment != "SIMULATION":
        raise ValueError("REAL trading adapter is not enabled")
    return {
        # 两类订单号都原样回传，Java据此定位本地订单并写审计记录。
        "clientOrderNo": request.clientOrderNo,
        "externalOrderNo": request.externalOrderNo,
        "status": "CANCELED",
        "canceledAt": datetime.now().astimezone().isoformat(),
    }
