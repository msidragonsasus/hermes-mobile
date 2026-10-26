"""Hermes Mobile -> Google Calendar/Drive connection for the existing Workspace MCP.

The phone shows Google's own account picker and consent (same contract as the
Gmail connection) and sends only a one-time server authorization code. The
Dashboard exchanges it and stores one credential file per Google account in the
directory the configured ``google-workspace`` MCP already reads
(``WORKSPACE_MCP_CREDENTIALS_DIR``). Nothing else is changed: no MCP config
edit, no restart, no Gmail credential access, no new token store.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, unquote

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

SERVER = "google-workspace"
PREFIX = "/api/connections/google-workspace"
TOKEN_URI = "https://oauth2.googleapis.com/token"
USERINFO_URI = "https://openidconnect.googleapis.com/v1/userinfo"
TTL = 300
BACKUP_DIR = ".hermes-mobile-backups"

G = "https://www.googleapis.com/auth/"
# Exactly the scopes workspace-mcp 2.1.0 uses for --tools drive calendar docs.
REQUESTED_SCOPES = [
    "openid", G + "userinfo.email", G + "userinfo.profile",
    G + "calendar", G + "calendar.readonly", G + "calendar.events",
    G + "drive", G + "drive.readonly", G + "drive.file",
    G + "documents", G + "documents.readonly",
]
# Broad scopes imply the narrower ones in workspace-mcp's scope hierarchy.
REQUIRED_SCOPES = {G + "calendar", G + "drive", G + "documents", G + "userinfo.email"}
ALLOWED_SCOPES = set(REQUESTED_SCOPES)
ALIASES = {"email": G + "userinfo.email", "profile": G + "userinfo.profile"}
EMAIL = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,63}")
CLIENT_ID = re.compile(r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com")


def fail(code, status=409):
    raise HTTPException(status, detail={"code": code})


def account_id(email):
    return hashlib.sha256(email.encode()).hexdigest()[:20]


def _read_json(path, limit=65536):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError("unexpected file")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("unexpected content")
    return value


def read_client(path):
    """Return (client_id, client_secret) of a Google OAuth client JSON."""
    try:
        value = _read_json(Path(path))
    except (OSError, ValueError, UnicodeError):
        fail("google_client_missing", 503)
    block = value.get("web") if isinstance(value.get("web"), dict) else value.get("installed")
    if not isinstance(block, dict):
        fail("google_client_invalid", 503)
    cid, secret = block.get("client_id"), block.get("client_secret")
    if not (isinstance(cid, str) and CLIENT_ID.fullmatch(cid) and isinstance(secret, str) and 1 <= len(secret) <= 4096):
        fail("google_client_invalid", 503)
    return cid, secret


class CredentialDir:
    """workspace-mcp LocalDirectoryCredentialStore layout: <email>.json per account."""

    def __init__(self, path):
        self.path = Path(path)

    def _check_dir(self, create=False):
        if self.path.is_symlink():
            fail("google_storage_unavailable", 503)
        if not self.path.exists():
            if not create:
                return False
            self.path.mkdir(parents=True, mode=0o700)
        if not self.path.is_dir():
            fail("google_storage_unavailable", 503)
        return True

    def file_for(self, email):
        return self.path / (quote(email, safe="@._-") + ".json")

    def accounts(self):
        if not self._check_dir():
            return []
        rows = []
        for entry in sorted(self.path.iterdir()):
            name = entry.name
            if not name.endswith(".json") or entry.is_symlink() or not entry.is_file():
                continue
            email = unquote(name[:-5])
            if "@" not in email or email == "oauth_states":
                continue
            try:
                data = _read_json(entry)
                scopes = data.get("scopes") or []
                ok = (isinstance(data.get("refresh_token"), str) and data["refresh_token"]
                      and isinstance(scopes, list) and REQUIRED_SCOPES - {G + "userinfo.email"} <= set(scopes))
            except (OSError, ValueError, UnicodeError):
                ok = False
            rows.append({"id": account_id(email), "email": email,
                         "state": "connected" if ok else "reauth_required"})
        return rows

    def _backup(self, source):
        folder = self.path / BACKUP_DIR
        if folder.is_symlink():
            fail("google_storage_unavailable", 503)
        folder.mkdir(mode=0o700, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        target = folder / (stamp + "-" + source.name + ".bak")
        return target

    def save(self, email, credentials):
        self._check_dir(create=True)
        target = self.file_for(email)
        if target.is_symlink():
            fail("google_storage_unavailable", 503)
        if target.exists():
            shutil.copy2(target, self._backup(target))
        fd, tmp = tempfile.mkstemp(dir=self.path, prefix=".mobile-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(credentials, handle, indent=2)
            os.chmod(tmp, 0o600)
            os.replace(tmp, target)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise

    def disconnect(self, wanted):
        for row in self.accounts():
            if hmac.compare_digest(row["id"], wanted):
                source = self.file_for(row["email"])
                if not source.exists():
                    source = self.path / (re.sub(r"[^a-zA-Z0-9@._-]", "_", row["email"]) + ".json")
                os.replace(source, self._backup(source))
                return
        fail("account_not_found", 404)


class CoreBackend:
    """Reads the live Hermes configuration and Dashboard session."""

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
        return hmac.digest(self.salt, (session.provider + "\0" + session.access_token).encode(), "sha256").hex()

    def setup(self, profile):
        """Return {credentials_dir, client_secret_path, enabled} or a reason code."""
        from hermes_cli.config import get_env_value
        from hermes_cli.mcp_config import _get_mcp_servers
        from hermes_cli.web_routers.mcp import _profile_scope

        def resolve(value):
            if not isinstance(value, str) or not value:
                return ""
            match = re.fullmatch(r"\$\{([A-Z][A-Z0-9_]*)\}", value)
            value = (get_env_value(match[1]) or "") if match else value
            return os.path.expanduser(value) if value else ""

        with _profile_scope(profile):
            cfg = _get_mcp_servers().get(SERVER)
            if not isinstance(cfg, dict) or not cfg.get("command"):
                return {"reason": "workspace_mcp_missing"}
            env = cfg.get("env") if isinstance(cfg.get("env"), dict) else {}
            creds = resolve(env.get("WORKSPACE_MCP_CREDENTIALS_DIR") or env.get("GOOGLE_MCP_CREDENTIALS_DIR"))
            client = resolve(env.get("GOOGLE_CLIENT_SECRET_PATH"))
            args = [str(a) for a in cfg.get("args") or []]
            if "--single-user" in args or str(env.get("MCP_SINGLE_USER_MODE")) == "1":
                single = True
            else:
                single = False
            if not creds or not os.path.isabs(creds):
                return {"reason": "workspace_credentials_dir_unknown"}
            if not client or not os.path.isabs(client):
                return {"reason": "google_client_missing"}
            return {"credentials_dir": creds, "client_secret_path": client,
                    "enabled": cfg.get("enabled", True) is not False, "single_user": single}

    @staticmethod
    def http():
        import httpx
        return httpx.Client(timeout=20, follow_redirects=False, trust_env=False)


class WorkspaceConnection:
    def __init__(self, backend, clock=time.monotonic):
        self.backend, self.clock = backend, clock
        self.lock = threading.Lock()
        self.pending = {}

    def _setup(self, profile):
        try:
            setup = self.backend.setup(profile)
        except HTTPException:
            raise
        except Exception:
            fail("connection_status_unavailable", 503)
        return setup

    def status(self, profile):
        setup = self._setup(profile)
        if "reason" in setup:
            return {"state": "setup_required", "server_name": SERVER, "accounts": [], "can_add": False,
                    "reason": setup["reason"], "multi_account": True, "enabled": False}
        try:
            rows = CredentialDir(setup["credentials_dir"]).accounts()
        except HTTPException:
            raise
        except OSError:
            fail("google_storage_unavailable", 503)
        reason = None
        try:
            read_client(setup["client_secret_path"])
        except HTTPException as error:
            reason = error.detail["code"]
        connected = [row for row in rows if row["state"] == "connected"]
        state = "connected" if connected else ("reauth_required" if rows else "disconnected")
        return {"state": state, "server_name": SERVER, "accounts": rows, "can_add": reason is None,
                "reason": reason, "multi_account": not setup.get("single_user"),
                "enabled": setup["enabled"], "services": ["calendar", "drive", "docs"]}

    def begin(self, owner, profile, payload):
        if not isinstance(payload, dict) or set(payload) - {"account_id"}:
            fail("invalid_request", 400)
        setup = self._setup(profile)
        if "reason" in setup:
            fail(setup["reason"], 503)
        client_id, _ = read_client(setup["client_secret_path"])
        flow = secrets.token_urlsafe(32)
        with self.lock:
            now = self.clock()
            self.pending = {k: v for k, v in self.pending.items() if v[2] > now and v[0] != owner}
            if len(self.pending) >= 64:
                fail("google_connection_busy", 429)
            self.pending[hashlib.sha256(flow.encode()).digest()] = (owner, profile, now + TTL, client_id)
        return {"flow_id": flow, "client_id": client_id, "scopes": list(REQUESTED_SCOPES)}

    def complete(self, owner, profile, payload):
        if not isinstance(payload, dict) or set(payload) != {"flow_id", "code"} or not all(
                isinstance(payload[k], str) and 1 <= len(payload[k]) <= (128 if k == "flow_id" else 8192)
                for k in payload):
            fail("invalid_request", 400)
        digest = hashlib.sha256(payload["flow_id"].encode()).digest()
        with self.lock:
            pending = self.pending.get(digest)
            if (not pending or not hmac.compare_digest(pending[0], owner) or pending[1] != profile
                    or pending[2] <= self.clock()):
                fail("google_flow_expired", 409)
            del self.pending[digest]  # An authorization code is never retried.
        setup = self._setup(profile)
        if "reason" in setup:
            fail(setup["reason"], 503)
        client_id, client_secret = read_client(setup["client_secret_path"])
        if client_id != pending[3]:
            fail("google_flow_expired", 409)
        try:
            with self.backend.http() as http:
                response = http.post(TOKEN_URI, data={
                    "code": payload["code"], "client_id": client_id, "client_secret": client_secret,
                    "grant_type": "authorization_code", "redirect_uri": ""})
                if response.status_code != 200:
                    raise ValueError()
                result = response.json()
                scopes = result.get("scope", "").split() if isinstance(result, dict) and isinstance(result.get("scope"), str) else []
                # Google may report the short aliases; store the names workspace-mcp checks.
                scopes = list(dict.fromkeys(ALIASES.get(scope, scope) for scope in scopes))
                if not REQUIRED_SCOPES <= set(scopes):
                    fail("google_scope_missing", 409)
                if not set(scopes) <= ALLOWED_SCOPES or not all(
                        isinstance(result.get(k), str) and 1 <= len(result[k]) <= 8192
                        for k in ("access_token", "refresh_token")) or type(result.get("expires_in")) is not int \
                        or not 1 <= result["expires_in"] <= 86400:
                    raise ValueError()
                info = http.get(USERINFO_URI, headers={"Authorization": "Bearer " + result["access_token"]})
                if info.status_code != 200:
                    raise ValueError()
                info = info.json()
                email = info.get("email") if isinstance(info, dict) else None
                if not isinstance(email, str) or not EMAIL.fullmatch(email) or info.get("email_verified") is False:
                    raise ValueError()
                email = email.lower()
        except HTTPException:
            raise
        except Exception:
            fail("google_authorization_failed", 409)
        expiry = datetime.now(timezone.utc) + timedelta(seconds=result["expires_in"])
        credentials = {"token": result["access_token"], "refresh_token": result["refresh_token"],
                       "token_uri": TOKEN_URI, "client_id": client_id, "client_secret": client_secret,
                       "scopes": scopes, "expiry": expiry.replace(tzinfo=None).isoformat()}
        try:
            CredentialDir(setup["credentials_dir"]).save(email, credentials)
        except HTTPException:
            raise
        except OSError:
            fail("google_storage_unavailable", 503)
        return {**self.status(profile), "account_id": account_id(email)}

    def disconnect(self, profile, payload):
        if not isinstance(payload, dict) or set(payload) != {"account_id"} or not isinstance(payload["account_id"], str):
            fail("invalid_request", 400)
        setup = self._setup(profile)
        if "reason" in setup:
            fail(setup["reason"], 503)
        try:
            CredentialDir(setup["credentials_dir"]).disconnect(payload["account_id"])
        except HTTPException:
            raise
        except OSError:
            fail("google_storage_unavailable", 503)
        return self.status(profile)


def create_router(backend=None):
    backend = backend or CoreBackend()
    manager = WorkspaceConnection(backend)
    router = APIRouter(prefix=PREFIX)

    def response(value):
        return JSONResponse(value, headers={"Cache-Control": "no-store, private", "Pragma": "no-cache"})

    @router.get("")
    async def status(profile: str | None = None, owner=Depends(backend.owner)):
        return response(await run_in_threadpool(manager.status, profile))

    @router.post("/{action}")
    async def act(action: str, request: Request, profile: str | None = None, owner=Depends(backend.owner)):
        if action not in {"begin", "complete", "disconnect"}:
            raise HTTPException(404)
        # Native app only: a browser page cannot drive the consent or storage.
        if ("origin" in request.headers or any(h.startswith("sec-fetch-") for h in request.headers)
                or request.headers.get("x-hermes-google-consent") != "once"):
            fail("google_native_consent_required", 403)
        if request.headers.get("content-type", "").split(";", 1)[0].strip() != "application/json":
            fail("google_json_required", 415)
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 16384:
                fail("payload_too_large", 413)
        try:
            payload = json.loads(body)
        except (ValueError, UnicodeError, RecursionError):
            fail("invalid_request", 400)
        if action == "begin":
            result = await run_in_threadpool(manager.begin, owner, profile, payload)
        elif action == "complete":
            result = await run_in_threadpool(manager.complete, owner, profile, payload)
        else:
            result = await run_in_threadpool(manager.disconnect, profile, payload)
        return response(result)

    return router
