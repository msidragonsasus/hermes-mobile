import base64
import binascii
import hmac
import json
import logging
import os
import secrets
import sys
import time
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response

from .sessions import Sessions, TerminalError

log = logging.getLogger("hermes.mobile_terminal")


def fail(code, status=409):
    raise HTTPException(status, detail={"code": code})


class SessionAuth:
    def __init__(self):
        self.salt = secrets.token_bytes(32)

    def owner(self, request: Request):
        from hermes_cli.dashboard_auth.base import Session
        from hermes_cli.web_server import _require_token
        _require_token(request)
        session = getattr(request.state, "session", None)
        if (not getattr(request.app.state, "auth_required", False)
                or not isinstance(session, Session) or not isinstance(session.access_token, str)
                or not session.access_token or type(session.expires_at) not in (int, float)
                or session.expires_at <= time.time()
                or not isinstance(getattr(session, "user_id", None), str) or not session.user_id):
            fail("dashboard_session_required", 401)
        origin = request.headers.get("origin")
        if origin and (urlsplit(origin).scheme, urlsplit(origin).netloc) != (request.url.scheme, request.url.netloc):
            fail("cross_origin_request", 403)
        if request.headers.get("sec-fetch-site") == "cross-site":
            fail("cross_origin_request", 403)
        profile = request.query_params.get("profile", "")
        if len(profile) > 128:
            fail("invalid_profile", 400)
        # Bind to the stable login identity, not the access token: the Dashboard rotates
        # access tokens on refresh and that must not orphan running terminals.
        account = "\0".join([str(session.provider), session.user_id, str(getattr(session, "org_id", "") or "")])
        account_key = hmac.digest(self.salt, b"account\0" + account.encode(), "sha256").hex()[:32]
        profile_key = hmac.digest(self.salt, (account + "\0" + profile).encode(), "sha256").hex()[:32]
        # "<account>.<profile>": limits count per account, isolation is per account+profile.
        return account_key + "." + profile_key


def audit(request, event, session_id):
    session = getattr(request.state, "session", None)
    client = request.client.host if request.client else "-"
    # Metadata only. Terminal input/output is never logged.
    log.warning("mobile_terminal %s session=%s user=%s provider=%s client=%s", event, session_id,
                getattr(session, "user_id", "-"), getattr(session, "provider", "-"), client)


async def payload(request, fields):
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > 24000:
            fail("payload_too_large", 413)
    try:
        value = json.loads(data)
        if not isinstance(value, dict) or set(value) != set(fields):
            raise ValueError()
        return value
    except (ValueError, UnicodeDecodeError):
        fail("invalid_payload", 400)


def dimensions(value):
    cols, rows = value.get("cols"), value.get("rows")
    if type(cols) is not int or type(rows) is not int or not 20 <= cols <= 240 or not 4 <= rows <= 120:
        fail("invalid_dimensions", 400)
    return cols, rows


def create_router(manager=None, auth=None, enabled=None):
    manager = manager or Sessions()
    auth = auth or SessionAuth()
    enabled = enabled or (lambda: sys.platform == "linux" and os.environ.get("HERMES_MOBILE_TERMINAL") == "1")

    def authorize(owner=Depends(auth.owner)):
        if not enabled():
            fail("terminal_disabled", 503)
        return owner

    def privacy(response: Response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"

    router = APIRouter(dependencies=[Depends(privacy)])

    @router.get("/status")
    def status(owner=Depends(auth.owner)):
        user = None
        if sys.platform == "linux":
            import pwd
            user = pwd.getpwuid(os.getuid()).pw_name
        on = enabled()
        return dict(api_version=1, enabled=on, platform=sys.platform, user=user if on else None)

    @router.get("/sessions")
    def listing(owner=Depends(authorize)):
        with manager.lock:
            return {"sessions": [dict(id=s.id, closed=s.closed) for s in manager.sessions.values() if s.owner == owner]}

    @router.post("/sessions")
    async def create(request: Request, owner=Depends(authorize)):
        body = await payload(request, {"id", "cols", "rows"})
        if not isinstance(body["id"], str):
            fail("invalid_session_id", 400)
        cols, rows = dimensions(body)
        try:
            from starlette.concurrency import run_in_threadpool
            session, created = await run_in_threadpool(manager.create_or_get, body["id"], owner, cols, rows)
            if created:
                audit(request, "create", session.id)
            return session.snapshot()
        except TerminalError as error:
            fail(error.code, error.status)
        except (OSError, KeyError):
            fail("terminal_unavailable", 503)

    def owned(session_id, owner):
        try:
            return manager.get(session_id, owner)
        except TerminalError as error:
            fail(error.code, error.status)

    @router.get("/sessions/{session_id}")
    def read(session_id: str, offset: int = Query(0, ge=0), owner=Depends(authorize)):
        try:
            return owned(session_id, owner).snapshot(offset)
        except TerminalError as error:
            fail(error.code, error.status)

    @router.post("/sessions/{session_id}/input")
    async def write(session_id: str, request: Request, owner=Depends(authorize)):
        body = await payload(request, {"sequence", "data"})
        try:
            if type(body["sequence"]) is not int or body["sequence"] < 1 or not isinstance(body["data"], str):
                raise ValueError()
            data = base64.b64decode(body["data"], validate=True)
            if not 1 <= len(data) <= 16384:
                raise ValueError()
        except (ValueError, binascii.Error):
            fail("invalid_input", 400)
        try:
            # The bounded PTY write must not block the Dashboard event loop.
            from starlette.concurrency import run_in_threadpool
            return await run_in_threadpool(owned(session_id, owner).write, body["sequence"], data)
        except TerminalError as error:
            fail(error.code, error.status)

    @router.post("/sessions/{session_id}/size")
    async def resize(session_id: str, request: Request, owner=Depends(authorize)):
        body = await payload(request, {"cols", "rows"})
        try:
            owned(session_id, owner).resize(*dimensions(body))
        except OSError:
            fail("session_closed")
        return {"ok": True}

    @router.delete("/sessions/{session_id}")
    def close(session_id: str, request: Request, owner=Depends(authorize)):
        with manager.lock:
            session = owned(session_id, owner)
            manager.sessions.pop(session_id, None)
        # Killing and reaping can take seconds; never hold the shared lock meanwhile.
        session.close()
        audit(request, "close", session_id)
        return {"ok": True}

    return router
