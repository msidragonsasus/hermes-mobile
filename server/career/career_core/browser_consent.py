"""Native, once-only consent for the verified Dashboard access-token lifetime.

Installing this adapter does not enable credential access. The existing operator
flag remains OFF by default. No cookies, token claims or user IDs confer consent.
"""
from dataclasses import dataclass
import hmac
import os
import secrets
import threading
import time

from .browser_access import BrowserAccessManager
from .coach_store import profile_key
from .locking import job_lock
from .mypage_bot import MyPageBotStore, connection_id, portal_url
from .store import CareerError


def dashboard_session_key(salt):
    """Fingerprint only a middleware-verified Session; never retain a raw token.

    Refresh/rotation changes the fingerprint and requires another native consent.
    This deliberately does not claim to identify a device or an entire login.
    """
    def key(session):
        from hermes_cli.dashboard_auth.base import Session
        if not isinstance(session, Session) or type(session.expires_at) not in (int, float) or session.expires_at <= time.time():
            return None
        token, provider = session.access_token, session.provider
        if not isinstance(token, str) or not 1 <= len(token) <= 16384 or not isinstance(provider, str) or not 1 <= len(provider) <= 200:
            return None
        return hmac.digest(salt, (provider + '\0' + token).encode(), 'sha256').hex()
    return key


@dataclass
class _Consent:
    owner: bytes
    target: tuple
    expires: float
    requires_confirmation: bool = True


class BrowserConsentAccess(BrowserAccessManager):
    def __init__(self, *, session_key=None, clock=time.monotonic):
        self._approval = threading.local()
        super().__init__(session_key or dashboard_session_key(os.urandom(32)), self._authorized, clock=clock)
        self._pending = {}
        self._permissions = {}
        self._lease = None
        self._directory = None
        self._halted = False

    def _authorized(self, session, target):
        approved = getattr(self._approval, 'value', None)
        return approved is not None and approved[0] is session and approved[1] == target

    def _ready(self, store):
        if self._halted:
            raise CareerError('browser_credentials_disabled', 'Browser access has been stopped.', 403)
        if os.environ.get('CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED') != '1':
            raise CareerError('browser_credentials_disabled', 'Internal browser autofill is not enabled.', 403)
        if os.getpid() != self._pid:
            raise CareerError('browser_authorization_required', 'Open the browser again.', 403)
        directory = store.directory.resolve()
        if self._lease is None:
            # One process owns consent/revocation for this store. Other workers
            # fail closed; this is not an assertion based on an environment count.
            lease = job_lock(directory / 'browser-consent.lock')
            if not lease.__enter__():
                lease.__exit__(None, None, None)
                raise CareerError('browser_worker_unavailable', 'Retry on the browser worker.', 503)
            self._lease, self._directory = lease, directory
        if directory != self._directory:
            raise CareerError('browser_authorization_required', 'Check the browser store.', 403)

    def _snapshot(self, store, payload):
        if not isinstance(payload, dict) or set(payload) != {'company_id', 'profile', 'expected_version', 'url'}:
            raise CareerError('invalid_request', 'Check the connection request.', 400)
        company, version = payload['company_id'], payload['expected_version']
        if not isinstance(company, str) or not 1 <= len(company) <= 200 or type(version) is not int or version <= 0:
            raise CareerError('invalid_request', 'Check the connection request.', 400)
        profile, url = profile_key(payload['profile']), portal_url(payload['url'])
        records = MyPageBotStore(store)
        with store.transaction() as db:
            row = records.get(connection_id(company, profile), db)
            if row is None:
                raise CareerError('mypage_not_connected', 'No saved connection.', 409)
            records.active(row, db)
            if (row['company_id'], row['profile'], row['version'], row['config']['url']) != (company, profile, version, url):
                raise CareerError('version_conflict', 'Open the current saved login page again.', 409)
        return company, profile, version, url

    def prepare(self, store, payload, session):
        with self._lock:
            self._ready(store)
            owner = self._owner(session)
            target = self._snapshot(store, payload)
            self._pending = {i: c for i, c in self._pending.items() if c.expires > self._clock()}
            self._permissions = {k: c for k, c in self._permissions.items() if c.expires > self._clock()}
            if len(self._pending) >= 256 or sum(c.owner == owner for c in self._pending.values()) >= 8:
                raise CareerError('browser_authorization_required', 'Close an existing confirmation and retry.', 403)
            approval_id = secrets.token_hex(32)
            permission = self._permissions.get((owner, target))
            self._pending[approval_id] = _Consent(owner, target, self._clock() + 60, permission is None)
            return {'approval_id': approval_id, 'expires_in': 60, 'company_id': target[0],
                    'profile': target[1], 'connection_version': target[2], 'url': target[3],
                    'requires_confirmation': permission is None,
                    'permission_expires_in': max(0, int(permission.expires - self._clock())) if permission else 1800}

    def approve_and_issue(self, store, payload, session, approval_id, consent_mode='allow_target'):
        with self._lock:
            self._ready(store)
            owner = self._owner(session)
            consent = self._pending.get(approval_id) if isinstance(approval_id, str) else None
            if consent is None or consent.owner != owner or consent.expires <= self._clock():
                raise CareerError('browser_authorization_required', 'Confirm this page again.', 403)
            # Consume even a mismatched attempt. Never make consent reusable on error.
            del self._pending[approval_id]
            target = self._snapshot(store, payload)
            if consent.target != target:
                raise CareerError('browser_authorization_required', 'Confirm the exact saved page.', 403)
            permission_key = (owner, target)
            permission = self._permissions.get(permission_key)
            if consent_mode == 'allow_target' and consent.requires_confirmation:
                self._permissions = {k: c for k, c in self._permissions.items() if c.expires > self._clock()}
                if len(self._permissions) >= 1024 or sum(k[0] == owner for k in self._permissions) >= 32:
                    raise CareerError('browser_authorization_required', 'Revoke an existing browser permission first.', 403)
                # Target authorization is memory-only and at most 30 minutes.
                # The verified token's own expiry/rotation also invalidates it.
                self._permissions[permission_key] = _Consent(owner, target, self._clock() + 1800)
            elif consent_mode != 'use_target' or consent.requires_confirmation or permission is None or permission.expires <= self._clock():
                raise CareerError('browser_authorization_required', 'Confirm this connection again.', 403)
            self.enable(single_process=True, fresh_approval=True)
            self.resume(session)
            self.resume(session, company_id=target[0], profile=target[1])
            self._approval.value = (session, target)
            try:
                grant = super().issue(store, payload, session)
            finally:
                self._approval.value = None
            # Session expiry is checked again immediately before secret retrieval.
            active = grant.active
            def consume_active():
                with self._lock:
                    current_permission = self._permissions.get(permission_key)
                    return self._session_key(session) is not None and current_permission is not None and current_permission.expires > self._clock() and active()
            grant.active = consume_active
            return grant

    def revoke(self, session, *, company_id=None, profile=None):
        with self._lock:
            owner = self._owner(session)
            # A non-owner worker must not acknowledge revocation as successful.
            if self._lease is None or os.getpid() != self._pid:
                raise CareerError('browser_worker_unavailable', 'Retry on the browser worker.', 503)
            if not (company_id is None and profile is None) and not (
                isinstance(company_id, str) and 1 <= len(company_id) <= 200 and isinstance(profile, str)):
                raise CareerError('invalid_request', 'Check the revocation scope.', 400)
            scope = None if company_id is None else (company_id, profile_key(profile))
            self._generation += 1
            self._prune()
            revoked = 0
            for ticket in self._tickets.values():
                if ticket.owner == owner and (scope is None or ticket.target[:2] == scope):
                    ticket.revoked = True
                    revoked += 1
            self._permissions = {k: c for k, c in self._permissions.items() if not (
                c.owner == owner and (scope is None or c.target[:2] == scope))}
            result = {'revoked': revoked, 'disabled': not self._enabled}
            self._pending = {i: c for i, c in self._pending.items() if not (
                c.owner == owner and (company_id is None or c.target[:2] == (company_id, profile_key(profile))))}
            return result

    def disable(self):
        with self._lock:
            self._halted = True
            super().disable()
            self._pending.clear()
            self._permissions.clear()

    def close(self):
        with self._lock:
            self.disable()
            if self._lease is not None:
                self._lease.__exit__(None, None, None)
                self._lease = None
