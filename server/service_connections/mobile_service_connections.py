"""Account login over the existing Hermes MCP OAuth implementation.

Mounted in the Dashboard process, alongside its existing MCP router. Credentials,
PKCE, callbacks, token refresh and rollback remain owned by Hermes. No standalone
server, browser automation, Slack bot credential reuse, or new token store.
"""
import asyncio
import hmac
import inspect
import re
import secrets
import threading
import time
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

SLACK_URL = "https://mcp.slack.com/mcp"
SERVER = "slack"
TTL = 900


def fail(code, status=409):
    raise HTTPException(status, detail={"code": code})


def slack_authorization_url(value):
    try:
        parsed = urlsplit(value)
        return (parsed.scheme == "https" and parsed.hostname == "slack.com"
                and parsed.port in (None, 443) and not parsed.username and not parsed.password
                and not parsed.fragment and parsed.path in {"/oauth/v2_user/authorize", "/oauth/v2/authorize"})
    except (ValueError, TypeError):
        return False


class Connect(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str = Field(pattern=r"^[a-f0-9-]{36}$")


class CoreBackend:
    def __init__(self):
        self.salt = secrets.token_bytes(32)

    def owner(self, request: Request):
        from hermes_cli.dashboard_auth.base import Session
        from hermes_cli.web_server import _require_token
        _require_token(request)
        session = getattr(request.state, "session", None)
        if (not isinstance(session, Session) or type(session.expires_at) not in (int, float)
                or not time.time() < session.expires_at or not isinstance(session.access_token, str)
                or not 1 <= len(session.access_token) <= 16384 or not isinstance(session.provider, str)):
            fail("dashboard_session_required", 401)
        origin = request.headers.get("origin")
        if request.method != "GET" and origin:
            if urlsplit(origin).scheme != request.url.scheme or urlsplit(origin).netloc != request.url.netloc:
                fail("cross_origin_write", 403)
        return hmac.digest(self.salt, (session.provider + "\0" + session.access_token).encode(), "sha256").hex()

    @staticmethod
    def _scope(profile):
        from hermes_cli.web_routers.mcp import _profile_scope
        return _profile_scope(profile)

    def status(self, profile):
        from hermes_cli.mcp_config import _get_mcp_servers
        from hermes_cli.config import get_env_value
        from tools.mcp_oauth import HermesTokenStorage

        def resolve(value):
            if not isinstance(value, str):
                return ""
            match = re.fullmatch(r"\$\{([A-Z][A-Z0-9_]*)\}", value)
            return get_env_value(match[1]) or "" if match else value

        with self._scope(profile):
            cfg = _get_mcp_servers().get(SERVER)
            if cfg is None:
                return {"state": "setup_required", "server_name": SERVER, "enabled": False}
            if not isinstance(cfg, dict) or cfg.get("url") != SLACK_URL or cfg.get("auth") != "oauth":
                return {"state": "manual_connection", "server_name": SERVER, "enabled": False}
            headers = cfg.get("headers") or {}
            if not isinstance(headers, dict) or any(str(key).lower() == "authorization" for key in headers):
                return {"state": "manual_connection", "server_name": SERVER, "enabled": False}
            oauth = cfg.get("oauth") or {}
            if not isinstance(oauth, dict):
                return {"state": "setup_required", "server_name": SERVER, "enabled": False}
            client = resolve(oauth.get("client_id"))
            secret = resolve(oauth.get("client_secret"))
            public = oauth.get("token_endpoint_auth_method") == "none"
            redirect = urlsplit(oauth.get("redirect_uri") or "")
            ready = (bool(re.fullmatch(r"[0-9]+\.[0-9]+", client)) and (public or bool(secret))
                     and redirect.scheme == "https" and bool(redirect.hostname)
                     and not redirect.username and not redirect.password and not redirect.query
                     and not redirect.fragment and redirect.path.endswith("/api/mcp/oauth/callback/slack"))
            if not ready:
                return {"state": "setup_required", "server_name": SERVER, "enabled": cfg.get("enabled", True) is not False}
            cached = HermesTokenStorage(SERVER).has_cached_tokens()
            return {"state": "authorized" if cached else "ready", "server_name": SERVER,
                    "enabled": cfg.get("enabled", True) is not False}

    async def start(self, request, profile):
        from hermes_cli.web_routers.mcp import auth_mcp_server
        return await auth_mcp_server(SERVER, request, profile)

    @staticmethod
    def snapshot(flow_id):
        from hermes_cli.web_server_mcp import _mcp_oauth_flows
        flow = _mcp_oauth_flows.get(flow_id)
        if flow is None:
            fail("flow_expired", 404)
        return flow.snapshot()

    @staticmethod
    def cancel(flow_id):
        from hermes_cli.web_server_mcp import _mcp_oauth_flows
        flow = _mcp_oauth_flows.get(flow_id)
        if flow is not None:
            # Current core retries ordinary failures on the same handle. An
            # explicit cancellation must remain terminal, including late calls.
            # Older core has no cancelled flag and treats every error as terminal.
            if "cancelled" in inspect.signature(flow.mark_error).parameters:
                flow.mark_error("Cancelled by user", cancelled=True)
            else:
                flow.mark_error("Cancelled by user")


class Connections:
    def __init__(self, backend, clock=time.monotonic):
        self.backend, self.clock = backend, clock
        self.lock = threading.Lock()
        self.requests = {}
        self.flows = {}

    def collect(self):
        cutoff = self.clock() - TTL
        self.requests = {key: value for key, value in self.requests.items() if value[0] > cutoff}
        self.flows = {key: value for key, value in self.flows.items() if value[2] > cutoff}

    def safe_snapshot(self, snapshot):
        # Never forward raw provider errors, tools, tokens or the callback URL.
        state = snapshot.get("status")
        flow_id = snapshot.get("flow_id")
        if snapshot.get("server_name") != SERVER or not isinstance(flow_id, str):
            fail("oauth_unavailable", 503)
        result = {"flow_id": flow_id, "server_name": SERVER, "status": state}
        if state == "authorization_required":
            url = snapshot.get("authorization_url")
            if not slack_authorization_url(url):
                self.backend.cancel(flow_id)
                fail("authorization_unavailable", 503)
            result["authorization_url"] = url
        elif state not in {"approved", "completed", "starting", "error"}:
            result["status"] = "error"
        if result["status"] == "error":
            result["error"] = "slack_authorization_failed"
        return result

    async def begin(self, request, profile, owner, request_id):
        key = (owner, profile, request_id)
        with self.lock:
            self.collect()
            record = self.requests.get(key)
            if record is None:
                if len(self.requests) >= 16:
                    fail("too_many_requests", 429)
                task = asyncio.create_task(self._start(request, profile, owner))
                task.add_done_callback(lambda done: done.exception() if not done.cancelled() else None)
                self.requests[key] = (self.clock(), task)
            else:
                task = record[1]
        result = await asyncio.shield(task)
        if record is not None:
            # A lost response may be retried after approval, cancellation or
            # expiry. Reuse the flow, but never replay its initial login URL.
            self.owned(result["flow_id"], owner, profile)
            return self.safe_snapshot(self.backend.snapshot(result["flow_id"]))
        return result

    async def _start(self, request, profile, owner):
        try:
            status = await asyncio.to_thread(self.backend.status, profile)
            if status["state"] not in {"ready", "authorized"}:
                fail("slack_setup_required")
            snapshot = await self.backend.start(request, profile)
            result = self.safe_snapshot(snapshot)
            with self.lock:
                self.flows[result["flow_id"]] = (owner, profile, self.clock())
            return result
        except HTTPException as error:
            if isinstance(error.detail, dict) and "code" in error.detail:
                raise
            fail("authorization_in_progress" if error.status_code == 409 else "oauth_unavailable",
                 409 if error.status_code == 409 else 503)
        except Exception:
            fail("oauth_unavailable", 503)

    def owned(self, flow_id, owner, profile):
        with self.lock:
            self.collect()
            record = self.flows.get(flow_id)
        if record is None:
            fail("flow_expired", 404)
        if not hmac.compare_digest(record[0], owner) or record[1] != profile:
            fail("flow_owner_mismatch", 403)


def create_router(backend=None):
    backend = backend or CoreBackend()
    manager = Connections(backend)
    router = APIRouter(prefix="/api/connections/slack")

    @router.get("/status")
    async def status(profile: str | None = None, owner=Depends(backend.owner)):
        try:
            return await asyncio.to_thread(backend.status, profile)
        except HTTPException:
            raise
        except Exception:
            fail("connection_status_unavailable", 503)

    @router.post("/connect")
    async def connect(body: Connect, request: Request, profile: str | None = None, owner=Depends(backend.owner)):
        return await manager.begin(request, profile, owner, body.request_id)

    @router.get("/flows/{flow_id}")
    async def flow(flow_id: str, profile: str | None = None, owner=Depends(backend.owner)):
        manager.owned(flow_id, owner, profile)
        return manager.safe_snapshot(backend.snapshot(flow_id))

    @router.delete("/flows/{flow_id}")
    async def cancel(flow_id: str, profile: str | None = None, owner=Depends(backend.owner)):
        manager.owned(flow_id, owner, profile)
        backend.cancel(flow_id)
        return {"ok": True}

    return router
