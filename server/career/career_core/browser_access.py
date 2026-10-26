"""Disabled-by-default in-memory grants; native ONCE consent lives in browser_consent."""
from dataclasses import dataclass
import hmac
import os
import threading
import time
import uuid

from .coach_store import profile_key
from .mypage_bot import MyPageBotStore, connection_id, portal_url
from .mypage_browser_credentials import BrowserCredentialGrant
from .store import CareerError


@dataclass
class _Ticket:
    owner: bytes
    target: tuple
    expires: float
    used: bool = False
    revoked: bool = False


class BrowserAccessManager:
    """Trusted bootstrap supplies verified session-ID and exact-target authorization callbacks.

    It MUST NOT infer identity/rights from request JSON, raw cookies or forwarded headers.
    enable/resume are Python controls, invoked AFTER explicit user approval.
    BrowserConsentAccess provides the native one-use confirmation adapter.
    Both process enable and the existing feature flag are required; neither is changed here.
    """
    def __init__(self, session_key=None, authorize_connection=None, *, clock=time.monotonic):
        self._session_key = session_key
        self._authorize = authorize_connection
        self._clock = clock
        self._salt = os.urandom(32)
        self._enabled = False
        self._pid = os.getpid()
        self._generation = 0
        self._lock = threading.RLock()
        self._tickets = {}
        self._blocked_sessions = set()
        self._blocked_targets = set()

    def enable(self, *, single_process=False, fresh_approval=False):
        # In-memory revocation is safe only in one worker. Every restart requires
        # fresh approval; this method must never be called automatically at boot.
        if single_process is not True or fresh_approval is not True or os.getpid() != self._pid:
            raise ValueError("Single-process operation and fresh explicit approval are required.")
        if not callable(self._session_key) or not callable(self._authorize):
            raise ValueError("Verified session and exact-target authorization adapters are required.")
        with self._lock:
            self._generation += 1
            self._enabled = True

    def disable(self):
        with self._lock:
            self._generation += 1
            self._enabled = False
            self._tickets.clear()

    def _owner(self, session):
        try:
            key = self._session_key(session) if session is not None and callable(self._session_key) else None
        except Exception:
            key = None
        if not isinstance(key, str) or not 1 <= len(key) <= 512:
            raise CareerError("browser_authorization_required", "Verified browser authorization is required.", 403)
        return hmac.digest(self._salt, key.encode(), "sha256")

    def _prune(self):
        self._tickets = {i: t for i, t in self._tickets.items() if t.expires > self._clock()}

    def _allowed(self, ticket):
        return self._enabled and os.getpid() == self._pid and os.environ.get("CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED") == "1" and not ticket.revoked and ticket.owner not in self._blocked_sessions and (
            ticket.owner, ticket.target[0], ticket.target[1]) not in self._blocked_targets

    def issue(self, store, payload, session):
        if os.environ.get("CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED") != "1":
            raise CareerError("browser_credentials_disabled", "Internal browser autofill is not enabled.", 403)
        with self._lock:
            if not self._enabled:
                raise CareerError("browser_authorization_required", "Browser access has not been authorized.", 403)
            generation = self._generation
        owner = self._owner(session)
        if not isinstance(payload, dict) or set(payload) != {"company_id", "profile", "expected_version", "url"}:
            raise CareerError("invalid_request", "Check the connection request.", 400)
        company, version = payload["company_id"], payload["expected_version"]
        if not isinstance(company, str) or not 1 <= len(company) <= 200 or type(version) is not int or version <= 0:
            raise CareerError("invalid_request", "Check the connection request.", 400)
        profile, url = profile_key(payload["profile"]), portal_url(payload["url"])
        with self._lock:
            if owner in self._blocked_sessions or (owner, company, profile) in self._blocked_targets:
                raise CareerError("browser_authorization_required", "A fresh browser authorization is required.", 403)
        try:
            authorized = self._authorize(session, (company, profile, version, url)) is True
        except Exception:
            authorized = False
        if not authorized:
            raise CareerError("browser_authorization_required", "This connection has not been authorized.", 403)
        records = MyPageBotStore(store)
        with store.transaction() as db:
            row = records.get(connection_id(company, profile), db)
            if row is None:
                raise CareerError("mypage_not_connected", "No saved connection for this company and profile.", 409)
            records.active(row, db)
            if row["version"] != version or row["config"]["url"] != url:
                raise CareerError("version_conflict", "Open the current saved login page again.", 409)
            target = (row["company_id"], row["profile"], row["version"], row["config"]["url"])
            if target != (company, profile, version, url):
                raise CareerError("browser_authorization_required", "Exact connection authorization is required.", 403)
        ticket = _Ticket(owner, target, self._clock() + 60)
        with self._lock:
            self._prune()
            if generation != self._generation or not self._allowed(ticket) or len(self._tickets) >= 256 or sum(t.owner == owner for t in self._tickets.values()) >= 8:
                raise CareerError("browser_authorization_required", "A fresh browser authorization is required.", 403)
            ticket_id = uuid.uuid4().hex
            self._tickets[ticket_id] = ticket
        return BrowserCredentialGrant(*target, session, active=lambda: self._consume(ticket_id))

    def _consume(self, ticket_id):
        with self._lock:
            self._prune()
            ticket = self._tickets.get(ticket_id)
            if ticket is None or ticket.used or not self._allowed(ticket):
                return False
            ticket.used = True
            return True

    def revoke(self, session, *, company_id=None, profile=None):
        owner = self._owner(session)
        with self._lock:
            self._prune()
            scope = None
            if company_id is None and profile is None:
                overflow = owner not in self._blocked_sessions and len(self._blocked_sessions) >= 256
            elif isinstance(company_id, str) and 1 <= len(company_id) <= 200 and isinstance(profile, str):
                scope = (owner, company_id, profile_key(profile))
                if scope not in self._blocked_targets and sum(s[0] == owner for s in self._blocked_targets) >= 32:
                    scope = None  # Escalate only this owner, preserving other users' access.
                overflow = (owner not in self._blocked_sessions and len(self._blocked_sessions) >= 256) if scope is None else (scope not in self._blocked_targets and len(self._blocked_targets) >= 1024)
            else:
                raise CareerError("invalid_request", "Check the revocation scope.", 400)
            self._generation += 1
            revoked = 0
            for ticket in self._tickets.values():
                if ticket.owner == owner and (scope is None or ticket.target[:2] == scope[1:]):
                    ticket.revoked = True
                    revoked += 1
            if overflow:
                self.disable()
            elif scope is None:
                self._blocked_sessions.add(owner)
            else:
                self._blocked_targets.add(scope)
            return {"revoked": revoked, "disabled": not self._enabled}

    def resume(self, session, *, company_id=None, profile=None):
        """Adapter control only; fresh explicit reapproval is required."""
        owner = self._owner(session)
        with self._lock:
            self._generation += 1
            if company_id is None and profile is None:
                self._blocked_sessions.discard(owner)
            elif isinstance(company_id, str) and isinstance(profile, str):
                self._blocked_targets.discard((owner, company_id, profile_key(profile)))
            else:
                raise CareerError("invalid_request", "Check the authorization scope.", 400)

    def status(self, session):
        owner = self._owner(session)
        with self._lock:
            self._prune()
            return {"enabled": self._enabled and os.getpid() == self._pid and os.environ.get("CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED") == "1",
                    "grants": [{"company_id": t.target[0], "profile": t.target[1], "connection_version": t.target[2],
                                "expires_in": max(0, int(t.expires - self._clock())), "used": t.used,
                                "revoked": t.revoked or owner in self._blocked_sessions or (owner, t.target[0], t.target[1]) in self._blocked_targets} for t in self._tickets.values() if t.owner == owner]}
