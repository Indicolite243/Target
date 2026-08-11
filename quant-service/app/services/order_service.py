from datetime import datetime

from app.schemas.order import CancelOrderRequest, SubmitOrderRequest


def submit_order(request: SubmitOrderRequest) -> dict:
    if request.environment != "SIMULATION":
        raise ValueError("REAL trading adapter is not enabled")
    return {
        "clientOrderNo": request.clientOrderNo,
        "externalOrderNo": f"SIM-{request.orderId}",
        "status": "SUBMITTED",
        "submittedAt": datetime.now().astimezone().isoformat(),
    }


def cancel_order(request: CancelOrderRequest) -> dict:
    if request.environment != "SIMULATION":
        raise ValueError("REAL trading adapter is not enabled")
    return {
        "clientOrderNo": request.clientOrderNo,
        "externalOrderNo": request.externalOrderNo,
        "status": "CANCELED",
        "canceledAt": datetime.now().astimezone().isoformat(),
    }
