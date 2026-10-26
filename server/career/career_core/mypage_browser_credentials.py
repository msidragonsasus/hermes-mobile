"""Disabled by default: transient, exact-connection credential use by the Android browser.

Enabling this endpoint is a NEW credential-access boundary. It needs operator approval
and verified TLS; never enable it as part of installing the browser UI.
"""
import os
import threading
import time

from .coach_store import profile_key
from .mypage_bot import MyPageBotStore, connection_id, portal_url
from .store import CareerError


class BrowserCredentialGrant:
    """A trusted auth adapter may issue one bound, 60-second in-memory grant.

    A disabled-by-default BrowserAccessManager can issue this inside the same request.
    Production wiring and enable require explicit approval of the access boundary.
    Never create this object from request JSON, headers, or an unverified cookie field.
    """
    def __init__(self, company_id, profile, version, url, session, *, active=None):
        self.target = (company_id, profile_key(profile), version, portal_url(url))
        self.session = session
        self.expires = time.monotonic() + 60
        self.used = False
        self.lock = threading.Lock()
        self.active = active

    def consume(self, target, session):
        with self.lock:
            if self.used or time.monotonic() > self.expires or target != self.target or session is None or session is not self.session:
                raise CareerError("browser_authorization_required", "This connection needs a fresh browser grant.", 403)
            if self.active is not None and self.active() is not True:
                raise CareerError("browser_authorization_required", "This browser authorization has expired or was revoked.", 403)
            self.used = True


def browser_credentials(store, payload, *, secrets=None, grant=None, session=None):
    if os.environ.get("CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED") != "1":
        raise CareerError("browser_credentials_disabled", "Internal browser autofill is not enabled.", 403)
    if not isinstance(payload, dict) or set(payload) != {"company_id", "profile", "expected_version", "url"}:
        raise CareerError("invalid_request", "Check the connection request.", 400)
    company = payload["company_id"]
    version = payload["expected_version"]
    if not isinstance(company, str) or not 1 <= len(company) <= 200 or type(version) is not int or version <= 0:
        raise CareerError("invalid_request", "Check the connection request.", 400)
    profile = profile_key(payload["profile"])
    url = portal_url(payload["url"])
    if not isinstance(grant, BrowserCredentialGrant):
        raise CareerError("browser_authorization_required", "Browser access has not been authorized.", 403)
    grant.consume((company, profile, version, url), session)
    records = MyPageBotStore(store, secrets)
    # Keep the connection snapshot stable until the sealed credential has been read.
    with store.transaction() as db:
        row = records.get(connection_id(company, profile), db)
        if row is None:
            raise CareerError("mypage_not_connected", "No saved connection for this company and profile.", 409)
        records.active(row, db)
        if row["version"] != version or row["config"]["url"] != url:
            raise CareerError("version_conflict", "Open the current saved login page again.", 409)
        value = records.secret_store().read(row["config"]["secret_ref"])
        login, password = value.get("login_id"), value.get("password")
        if not isinstance(login, str) or not 1 <= len(login) <= 500 or not isinstance(password, str) or not 1 <= len(password) <= 2000:
            raise CareerError("authentication_required", "Check the saved connection.", 409)
        return {"company_id": company, "profile": profile, "connection_version": version,
                "url": url, "login_id": login, "password": password}
