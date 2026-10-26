"""Native Google consent -> the standard Hermes Workspace credential store.

Only a single-use server authorization code crosses the native/server boundary.
Each account has separate credentials; reconnection is explicit and backed up.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone

import httpx

from .store import CareerError
from .google_accounts import GoogleAccounts, account_id

SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
TOKEN_URI = "https://oauth2.googleapis.com/token"
PROFILE_URI = "https://gmail.googleapis.com/gmail/v1/users/me/profile"
SAFE_SCOPES = {SCOPE, "openid", "email", "profile",
               "https://www.googleapis.com/auth/userinfo.email",
               "https://www.googleapis.com/auth/userinfo.profile"}


class GoogleConnection:
    def __init__(self, *, home=None, session_key=None, transport=None, clock=time.monotonic):
        self._home = home
        self._transport = transport
        self._clock = clock
        if session_key is None:
            from .browser_consent import dashboard_session_key
            session_key = dashboard_session_key(os.urandom(32))
        self._session_key = session_key
        self._pending = {}
        self._lock = threading.Lock()

    def home(self):
        if self._home is not None:
            return Path(self._home)
        try:
            from hermes_constants import get_hermes_home
            return Path(get_hermes_home())
        except ImportError:
            raise CareerError("google_setup_required", "HermesのGoogle連携を準備してください。", 503) from None

    def _read(self, path):
        try:
            if path.is_symlink() or path.stat().st_size > 65536:
                raise ValueError()
            if os.name != "nt" and path.stat().st_mode & 0o077:
                raise ValueError()
            value = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except (OSError, ValueError, UnicodeError):
            raise CareerError("google_setup_required", "HermesのGoogle連携を準備してください。", 503) from None

    def _client(self):
        home = self.home()
        dedicated = home / "google_mobile_client_secret.json"
        value = self._read(dedicated if dedicated.exists() else home / "google_client_secret.json")
        web = value.get("web", {})
        if not isinstance(web, dict) or not all(isinstance(web.get(k), str) and 1 <= len(web[k]) <= 4096
                                               for k in ("client_id", "client_secret")):
            raise CareerError("google_setup_required", "Google連携の初期設定が必要です。", 503)
        if not re.fullmatch(r"[A-Za-z0-9_-]+\.apps\.googleusercontent\.com", web["client_id"]):
            raise CareerError("google_setup_required", "Google連携の初期設定を確認してください。", 503)
        if not (home / "skills/productivity/google-workspace/scripts/google_api.py").is_file():
            raise CareerError("google_workspace_required", "HermesのGoogle Workspace連携を準備してください。", 503)
        return web

    def status(self, *, verify=True):
        accounts = GoogleAccounts(self.home())
        token = self.home() / "google_token.json"
        rows = accounts.accounts()
        legacy_error = None
        adopted = False
        if (token.exists() or token.is_symlink()) and not any(
            accounts._read().get(row['id'], {}).get('kind') == 'legacy' for row in rows
        ):
            # The installed skill owns refresh and its credentials. Never copy
            # or return those credentials, or call its login/setup scripts.
            from .workspace_reader import connected_account_details
            try:
                details = connected_account_details()
                accounts.adopt_legacy(details['email'])
                adopted = True
            except CareerError:
                legacy_error = 'reauth_required'
        rows = accounts.accounts()
        if verify and not adopted:
            for row in rows:
                if accounts._read()[row['id']]['kind'] == 'legacy':
                    from .workspace_reader import connected_account_details
                    try:
                        connected_account_details(row['id'])
                        accounts.mark_state(row['id'], 'connected')
                    except CareerError:
                        accounts.mark_reauth(row['id'])
            rows = accounts.accounts()
        reason = None
        try:
            self._client()
            can_add = True
        except CareerError as error:
            can_add, reason = False, error.code
        connected = [row for row in rows if row['state'] == 'connected']
        state = 'connected' if connected else ('reauth_required' if rows or legacy_error else ('disconnected' if can_add else 'setup_required'))
        return {'state': state, 'email': connected[0]['email'] if connected else None,
                'scope': SCOPE, 'accounts': rows, 'can_add': can_add,
                'reason': reason, 'multi_account': True}

    def begin(self, session, payload=None):
        owner = self._session_key(session)
        if not owner:
            raise CareerError("dashboard_session_required", "Hermesにログインしてください。", 401)
        client = self._client()
        payload = {} if payload is None else payload
        if not isinstance(payload, dict) or set(payload) - {'account_id'}:
            raise CareerError('invalid_request', '接続するアカウントを確認してください。')
        reconnect = payload.get('account_id')
        accounts = GoogleAccounts(self.home())
        revision = accounts.revision(reconnect) if reconnect is not None else None
        target = self.home() / "google_token.json"
        if target.is_symlink():
            raise CareerError('google_existing_connection', '既存のGoogle接続を確認してください。', 409)
        legacy_before = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
        flow = secrets.token_urlsafe(32)
        with self._lock:
            self._pending = {k: v for k, v in self._pending.items() if v[1] > self._clock() and v[0] != owner}
            if len(self._pending) >= 64:
                raise CareerError("google_connection_busy", "少し待ってから再試行してください。", 429)
            self._pending[hashlib.sha256(flow.encode()).digest()] = (
                owner, self._clock() + 300, client['client_id'], reconnect, revision, legacy_before)
        return {"flow_id": flow, "client_id": client["client_id"], "scope": SCOPE}

    def complete(self, session, payload):
        if not isinstance(payload, dict) or set(payload) != {"flow_id", "code"} or not all(
            isinstance(payload[k], str) and 1 <= len(payload[k]) <= (128 if k == "flow_id" else 8192)
            for k in payload
        ):
            raise CareerError("invalid_request", "接続をやり直してください。")
        owner = self._session_key(session)
        digest = hashlib.sha256(payload["flow_id"].encode()).digest()
        with self._lock:
            pending = self._pending.get(digest)
            if not owner or not pending or pending[0] != owner or pending[1] <= self._clock():
                raise CareerError("google_flow_expired", "接続をやり直してください。", 409)
            del self._pending[digest]  # Never retry an OAuth code after an uncertain outcome.
        client = self._client()
        if client["client_id"] != pending[2]:
            raise CareerError("google_flow_expired", "接続をやり直してください。", 409)
        legacy = self.home() / 'google_token.json'
        if not pending[3] and pending[5] is None and (legacy.exists() or legacy.is_symlink()):
            raise CareerError('google_existing_connection', '別の接続が作成されました。再読込してください。', 409)
        try:
            with httpx.Client(transport=self._transport, timeout=20, follow_redirects=False, trust_env=False) as http:
                response = http.post(TOKEN_URI, data={"code": payload["code"], "client_id": client["client_id"],
                    "client_secret": client["client_secret"], "grant_type": "authorization_code", "redirect_uri": ""})
                if response.status_code != 200:
                    raise ValueError()
                result = response.json()
                if not isinstance(result, dict) or not isinstance(result.get("scope"), str):
                    raise ValueError()
                scopes = result.get("scope", "").split()
                if SCOPE not in scopes or not set(scopes) <= SAFE_SCOPES or not all(
                    isinstance(result.get(k), str) and 1 <= len(result[k]) <= 8192 for k in ("access_token", "refresh_token")
                ) or type(result.get("expires_in")) is not int or not 1 <= result["expires_in"] <= 86400:
                    raise ValueError()
                profile = http.get(PROFILE_URI, headers={"Authorization": "Bearer " + result["access_token"]})
                if profile.status_code != 200:
                    raise ValueError()
                from .mail_store import normalize_address
                email = normalize_address(profile.json()["emailAddress"])
        except (httpx.HTTPError, ValueError, TypeError, KeyError, CareerError):
            raise CareerError("google_authorization_failed", "Google接続を完了できませんでした。もう一度許可してください。", 409) from None
        credentials = {"type": "authorized_user", "token": result["access_token"], "refresh_token": result["refresh_token"],
            "token_uri": TOKEN_URI, "client_id": client["client_id"], "client_secret": client["client_secret"],
            "scopes": scopes, "expiry": (datetime.now(timezone.utc) + timedelta(seconds=result["expires_in"])).strftime("%Y-%m-%dT%H:%M:%SZ")}
        try:
            account = GoogleAccounts(self.home()).save(email, credentials, reconnect=pending[3],
                revision=pending[4], legacy_before=pending[5])
        except FileExistsError:
            raise CareerError('google_existing_connection', '別の接続が作成されました。再読込してください。', 409) from None
        except OSError:
            raise CareerError("google_storage_unavailable", "接続情報を保存できませんでした。", 503) from None
        return {**self.status(verify=False), 'account_id': account['id']}

    def manage(self, action, payload):
        if not isinstance(payload, dict) or set(payload) != ({'account_id', 'label'} if action == 'rename' else {'account_id'}):
            raise CareerError('invalid_request', '操作するアカウントを確認してください。')
        GoogleAccounts(self.home()).update(payload['account_id'], label=payload.get('label'), disconnect=action == 'disconnect')
        return self.status(verify=False)


def create_google_router(connector=None):
    from fastapi import APIRouter, HTTPException, Request
    from fastapi.responses import JSONResponse
    from starlette.concurrency import run_in_threadpool
    router = APIRouter(prefix="/connections/google")
    manager = connector or GoogleConnection()

    def response(value):
        return JSONResponse(value, headers={"Cache-Control": "no-store, private", "Pragma": "no-cache"})

    @router.get("")
    def status():
        try:
            return response(manager.status())
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None

    @router.post("/{action}")
    async def connect(action: str, request: Request):
        if action not in {'begin', 'complete', 'rename', 'disconnect'}:
            raise HTTPException(404)
        if "origin" in request.headers or any(h.startswith("sec-fetch-") for h in request.headers) or request.headers.get("x-hermes-google-consent") != "once":
            raise HTTPException(403, detail={"code": "google_native_consent_required"})
        if request.headers.get("content-type", "").split(";", 1)[0] != "application/json":
            raise HTTPException(415, detail={"code": "google_json_required"})
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 16384:
                raise HTTPException(413, detail={"code": "payload_too_large"})
        try:
            payload = json.loads(body)
            if action == "begin":
                result = await run_in_threadpool(manager.begin, request.state.session, payload)
            elif action == 'complete':
                result = await run_in_threadpool(manager.complete, request.state.session, payload)
            else:
                result = await run_in_threadpool(manager.manage, action, payload)
            return response(result)
        except CareerError as error:
            raise HTTPException(error.status, detail={"code": error.code}) from None
        except (ValueError, TypeError, UnicodeError, RecursionError):
            raise HTTPException(400, detail={"code": "invalid_request"}) from None

    return router
