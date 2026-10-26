"""Dummy sealed credentials only; no real vault, external portal or configuration edits."""
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.mypage_bot import MyPageBotStore
from career_core.mypage_browser_credentials import BrowserCredentialGrant, browser_credentials
from career_core.secret_store import SecretStore
from career_core.store import CareerError, Store


class BrowserCredentialTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / "data")
        self.session = {"id": "dummy-session"}
        self.secrets = SecretStore(Path(self.tmp.name) / "private", key=Fernet.generate_key())
        with self.store.transaction() as db:
            self.store.put(db, "companies", "dummy-company", 0, {"name": "Dummy Company"})
            self.store.put(db, "companies", "other-company", 0, {"name": "Other Company"})
        with patch("career_core.mypage_browser.public_host", return_value="portal.example.test"):
            MyPageBotStore(self.store, self.secrets).configure("dummy-company", "phone", {
                "company_id": "dummy-company", "url": "https://portal.example.test/company/login?year=2028",
                "login_id": "dummy-id", "password": "dummy-password", "enabled": True, "expected_version": 0})
        self.payload = {"company_id": "dummy-company", "profile": "phone", "expected_version": 1,
                        "url": "https://portal.example.test/company/login?year=2028"}

    def get(self, **changes):
        payload = dict(self.payload, **changes)
        return browser_credentials(self.store, payload, secrets=self.secrets,
                                   grant=BrowserCredentialGrant(payload["company_id"], payload["profile"], payload["expected_version"], payload["url"], self.session), session=self.session)

    def test_flag_alone_cannot_expand_access_and_grant_is_bound_single_use_and_expires(self):
        with patch.dict(os.environ, {"CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED": "1"}):
            with self.assertRaises(CareerError):
                browser_credentials(self.store, self.payload, secrets=self.secrets)
            grant = BrowserCredentialGrant("dummy-company", "phone", 1, self.payload["url"], self.session)
            with patch.object(self.secrets, "read", side_effect=AssertionError("must not read")) as read:
                with self.assertRaises(CareerError) as mismatch:
                    browser_credentials(self.store, dict(self.payload, expected_version=2), secrets=self.secrets, grant=grant, session=self.session)
                self.assertEqual("browser_authorization_required", mismatch.exception.code)
                read.assert_not_called()
            with self.assertRaises(CareerError):
                browser_credentials(self.store, self.payload, secrets=self.secrets, grant=grant, session={"id": "other-session"})
            self.assertEqual("dummy-id", browser_credentials(self.store, self.payload, secrets=self.secrets, grant=grant, session=self.session)["login_id"])
            with self.assertRaises(CareerError):
                browser_credentials(self.store, self.payload, secrets=self.secrets, grant=grant, session=self.session)
            expired = BrowserCredentialGrant("dummy-company", "phone", 1, self.payload["url"], self.session)
            expired.expires = 0
            with self.assertRaises(CareerError):
                browser_credentials(self.store, self.payload, secrets=self.secrets, grant=expired, session=self.session)

    def test_disabled_by_default_does_not_touch_vault(self):
        with patch.dict(os.environ, {}, clear=True):
            with patch.object(self.secrets, "read", side_effect=AssertionError("must not read")):
                with self.assertRaises(CareerError) as error:
                    self.get()
        self.assertEqual("browser_credentials_disabled", error.exception.code)

    def test_exact_company_profile_version_and_url_only(self):
        with patch.dict(os.environ, {"CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED": "1"}):
            self.assertEqual("dummy-password", self.get()["password"])
            for changes in ({"company_id": "other-company"}, {"profile": "default"}, {"expected_version": 2},
                            {"url": "https://other.example.test/company/login?year=2028"},
                            {"url": "https://portal.example.test/other/login?year=2028"},
                            {"url": "https://portal.example.test/company/login?year=2027"}):
                with self.subTest(changes=changes), patch.object(self.secrets, "read", side_effect=AssertionError("must not read")):
                    with self.assertRaises(CareerError) as error:
                        self.get(**changes)
                    expected = "mypage_not_connected" if "company_id" in changes or "profile" in changes else "version_conflict"
                    self.assertEqual(expected, error.exception.code)

    def test_extra_keys_bool_version_and_closed_company_rejected(self):
        with patch.dict(os.environ, {"CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED": "1"}):
            with self.assertRaises(CareerError):
                self.get(secret_ref="arbitrary")
            with self.assertRaises(CareerError):
                self.get(expected_version=True)
            with self.store.transaction() as db:
                self.store.put(db, "companies", "dummy-company", 1, {"name": "Dummy", "user_stage": "closed"})
            with self.assertRaises(CareerError):
                self.get()

    def client(self, scheme="https", session=True):
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def authenticated(request, call_next):
            if session:
                request.state.session = {"id": "dummy-session"}
            return await call_next(request)

        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        return TestClient(app, base_url=scheme + "://gateway.example.test")

    def test_api_requires_authenticated_tls_no_store_and_no_error_secret(self):
        route = "/api/plugins/career/mypage/browser-credentials"
        with patch("career_core.mypage_browser_credentials.browser_credentials", side_effect=AssertionError("must not read")) as read:
            result = self.client().post(route, content="{}", headers={"content-type": "text/plain"})
            self.assertEqual(415, result.status_code)
            read.assert_not_called()
        for scheme, session, expected in (("http", True, 403), ("https", False, 401)):
            with patch("career_core.mypage_browser_credentials.browser_credentials", side_effect=AssertionError("must not read")):
                result = self.client(scheme, session).post(route, json=self.payload)
                self.assertEqual(expected, result.status_code)
        with patch("career_core.mypage_browser_credentials.browser_credentials", return_value={"login_id": "dummy-id", "password": "dummy-password"}):
            result = self.client().post(route, json=self.payload)
            self.assertEqual(200, result.status_code)
            self.assertIn("no-store", result.headers["cache-control"])
        with patch("career_core.mypage_browser_credentials.browser_credentials", side_effect=RuntimeError("dummy-password")):
            result = self.client().post(route, json=self.payload)
        self.assertNotIn("dummy-password", result.text)

    def test_api_flag_on_and_existing_auth_without_grant_still_cannot_read_vault(self):
        with patch.dict(os.environ, {"CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED": "1"}):
            with patch.object(SecretStore, "read", side_effect=AssertionError("must not read")) as read:
                result = self.client().post("/api/plugins/career/mypage/browser-credentials", json=self.payload)
                self.assertEqual(403, result.status_code)
                self.assertEqual("browser_authorization_required", result.json()["detail"]["code"])
                read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
