import json
import re
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request

from .config import ConfigurationError, PowerConfig
from .controller import PowerActionError, PowerController


_OPERATION_ID = re.compile(r"^[0-9a-f]{8}-[0-9a-f-]{27,40}$", re.IGNORECASE)


def require_session(request: Request):
    if not getattr(request.app.state, "auth_required", False) or not getattr(request.state, "session", None):
        raise HTTPException(401, detail={"code": "dashboard_session_required"})
    origin = request.headers.get("origin")
    if request.method != "GET" and origin and urlsplit(origin).netloc != request.url.netloc:
        raise HTTPException(403, detail={"code": "cross_origin_write"})


def create_router(config_factory=PowerConfig.from_environment, controller_factory=PowerController):
    router = APIRouter(dependencies=[Depends(require_session)])

    def configured_controller():
        try:
            config = config_factory()
            return config, controller_factory(config)
        except (ConfigurationError, ValueError, TypeError):
            raise HTTPException(503, detail={"code": "pc_power_not_configured"}) from None

    @router.get("/status")
    def status():
        config, controller = configured_controller()
        power_state = controller.status()
        health = controller.receiver_health()
        # An authenticated receiver proves reachability even when ICMP is blocked.
        if health.get("ready") is True:
            power_state = "awake"
        sleep_available = power_state == "awake" and health.get("ready") is True
        last_operation = health.get("last_operation")
        sleep_error = None
        if health.get("error") == "shutdown_privilege_missing":
            sleep_error = "shutdown_privilege_missing"
        elif isinstance(last_operation, dict) and last_operation.get("state") == "failed":
            sleep_error = "sleep_failed"
        return {
            "api_version": 1,
            "target_name": config.target_name,
            "power_state": power_state,
            "wake_available": True,
            "sleep_available": sleep_available,
            "sleep_error": sleep_error,
        }

    @router.post("/wake", status_code=202)
    def wake():
        config, controller = configured_controller()
        try:
            state = controller.wake()
        except PowerActionError as error:
            raise HTTPException(502, detail={"code": error.code, "detail": error.detail}) from None
        return {"api_version": 1, "target_name": config.target_name, "result": state}

    @router.post("/sleep", status_code=202)
    async def sleep(request: Request):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 1024:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            operation_id = payload["operation_id"]
            if set(payload) != {"operation_id", "confirm"} or payload["confirm"] is not True:
                raise ValueError()
            if not isinstance(operation_id, str) or not _OPERATION_ID.fullmatch(operation_id):
                raise ValueError()
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            raise HTTPException(400, detail={"code": "invalid_sleep_confirmation"}) from None

        config, controller = configured_controller()
        try:
            state = controller.sleep(operation_id)
        except ConfigurationError:
            raise HTTPException(503, detail={"code": "sleep_receiver_not_configured"}) from None
        except PowerActionError as error:
            raise HTTPException(502, detail={"code": error.code, "detail": error.detail}) from None
        return {
            "api_version": 1,
            "target_name": config.target_name,
            "result": state,
            "operation_id": operation_id,
        }

    return router
