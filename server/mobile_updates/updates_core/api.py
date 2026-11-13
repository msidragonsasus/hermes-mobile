"""Read-only Dashboard routes, mounted at /api/plugins/mobile_updates.

GET /status                          whether an operator configured a release directory
GET /latest                          newest verified release metadata (or null)
GET /releases/{release_id}/apk       the exact verified APK bytes of one immutable release

There are no write, build, publish, shell or deploy routes. Publishing is the operator CLI."""
import logging
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import StreamingResponse

from .store import (PROTOCOL, ConfigurationError, ReleaseStore, StoreError, config_from_environment)

log = logging.getLogger("hermes.mobile_updates")
APK_MIME = "application/vnd.android.package-archive"
CHUNK = 256 * 1024


def fail(code, status):
    raise HTTPException(status, detail={"code": code})


def require_session(request: Request):
    hosted = getattr(request.state, "hosted_http_authenticated", None) is True
    in_process = getattr(request.app.state, "auth_required", False) and getattr(request.state, "session", None)
    native = getattr(request.state, "native_http_principal", None)
    if native:
        # Only the core middleware may redeem a same-user grant. Reuse its route
        # authorization seam; neither a header nor a static loopback token grants
        # this plugin access. Do not redeem the one-use ticket a second time.
        from hermes_cli.web_server import _require_token
        _require_token(request)
    if not (hosted or in_process or native):
        fail("dashboard_session_required", 401)
    origin = request.headers.get("origin")
    if origin:
        parts = urlsplit(origin)
        if (parts.scheme, parts.netloc) != (request.url.scheme, request.url.netloc):
            fail("cross_origin_request", 403)
    if request.headers.get("sec-fetch-site") == "cross-site":
        fail("cross_origin_request", 403)


def privacy(response: Response):
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"


def environment_store_factory():
    stores = {}

    def factory():
        config = config_from_environment()
        if config is None:
            return None
        if config not in stores:
            stores.clear()
            stores[config] = ReleaseStore(config)
        return stores[config]

    return factory


def create_router(store_factory=None):
    store_factory = store_factory or environment_store_factory()
    router = APIRouter(dependencies=[Depends(require_session), Depends(privacy)])

    def configured():
        try:
            store = store_factory()
        except ConfigurationError:
            fail("updates_misconfigured", 503)
        if store is None:
            fail("updates_not_configured", 503)
        return store

    @router.get("/status")
    def status():
        try:
            enabled = store_factory() is not None
        except ConfigurationError:
            enabled = False
        return {"schema": "hermes-mobile-update-status", "protocol": PROTOCOL, "enabled": enabled}

    @router.get("/latest")
    def latest():
        store = configured()
        try:
            release = store.latest()
        except StoreError as error:
            fail(error.code, error.status)
        return {"schema": "hermes-mobile-update-latest", "protocol": PROTOCOL, "release": release}

    @router.get("/releases/{release_id}/apk")
    def apk(release_id: str, request: Request):
        store = configured()
        try:
            handle, meta = store.open_apk(release_id)
        except StoreError as error:
            fail(error.code, error.status)

        def stream():
            with handle:
                while chunk := handle.read(CHUNK):
                    yield chunk

        session = getattr(request.state, "session", None)
        client = request.client.host if request.client else "-"
        # Metadata only: release id, user and client address.
        log.warning("mobile_updates download release=%s user=%s client=%s", meta["release_id"],
                    getattr(session, "user_id", "-"), client)
        return StreamingResponse(stream(), media_type=APK_MIME, headers={
            "Content-Length": str(meta["size_bytes"]),
            "Content-Disposition": f'attachment; filename="hermes-mobile-{meta["release_id"]}.apk"',
            "ETag": f'"sha256:{meta["sha256"]}"',
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
        })

    return router
