"""No real credentials, consent, mailbox, or network calls."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs

import httpx
from fastapi import Depends, FastAPI, Request
from fastapi.testclient import TestClient

from career_core.api import create_router, require_session
from career_core.google_connection import GoogleConnection, SCOPE, create_google_router
from career_core.store import CareerError


class GoogleConnectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.client_id = "synthetic.apps.googleusercontent.com"
        self.client = self.home / "google_mobile_client_secret.json"
        self.client.write_text(json.dumps({"web": {"client_id": self.client_id, "client_secret": "synthetic-secret"}}), encoding="utf-8")
        self.client.chmod(0o600)
        script = self.home / "skills/productivity/google-workspace/scripts/google_api.py"
        script.parent.mkdir(parents=True)
        script.write_text("# synthetic skill", encoding="utf-8")
        self.calls = []
        self.token_result = {"access_token": "synthetic-access", "refresh_token": "synthetic-refresh",
                             "scope": SCOPE, "expires_in": 3600}
        self.now = 0
        self.connector = GoogleConnection(home=self.home, session_key=lambda session: session,
            transport=httpx.MockTransport(self.google), clock=lambda: self.now)

    def google(self, request):
        self.calls.append(request)
        if request.url.path == "/token":
            data = parse_qs(request.content.decode(), keep_blank_values=True)
            self.assertEqual([self.client_id], data["client_id"])
            self.assertEqual([""], data["redirect_uri"])
            return httpx.Response(200, json=self.token_result)
        self.assertEqual("/gmail/v1/users/me/profile", request.url.path)
        self.assertEqual("Bearer synthetic-access", request.headers["authorization"])
        return httpx.Response(200, json={"emailAddress": "jobs@example.test"})

    def payload(self, owner="owner"):
        return {"flow_id": self.connector.begin(owner)["flow_id"], "code": "synthetic-code"}

    def assert_error(self, code, function, *args):
        with self.assertRaises(CareerError) as caught:
            function(*args)
        self.assertEqual(code, caught.exception.code)

    def test_native_consent_creates_standard_workspace_credentials_without_returning_secrets(self):
        self.assertEqual("disconnected", self.connector.status()["state"])
        response = self.connector.complete("owner", self.payload())
        self.assertEqual({"state": "connected", "email": "jobs@example.test", "scope": SCOPE},
                         {key: response[key] for key in ('state', 'email', 'scope')})
        self.assertEqual(['jobs@example.test'], [row['email'] for row in response['accounts']])
        value = json.loads((self.home / "google_token.json").read_text(encoding="utf-8"))
        self.assertEqual("synthetic-refresh", value["refresh_token"])
        self.assertEqual([SCOPE], value["scopes"])
        self.assertEqual("authorized_user", value["type"])
        self.assertNotIn("synthetic", json.dumps(response))
        self.assertEqual(2, len(self.calls))
        if os.name != "nt":
            self.assertEqual(0o600, (self.home / "google_token.json").stat().st_mode & 0o777)
        self.assertEqual([], list(self.home.glob(".google-connect-*")))

    def test_replay_and_expired_and_wrong_session_never_exchange(self):
        payload = self.payload()
        self.assert_error("google_flow_expired", self.connector.complete, "other", payload)
        self.now = 301
        self.assert_error("google_flow_expired", self.connector.complete, "owner", payload)
        self.assertEqual([], self.calls)
        payload = self.payload()
        self.connector.complete("owner", payload)
        self.assert_error("google_flow_expired", self.connector.complete, "owner", payload)
        self.assertEqual(2, len(self.calls))

    def test_existing_and_concurrently_created_credentials_are_preserved(self):
        payload = self.payload()
        target = self.home / "google_token.json"
        target.write_bytes(b"existing-credential")
        self.assert_error("google_existing_connection", self.connector.complete, "owner", payload)
        # Multi-account addition can begin while an unrelated legacy credential
        # exists; it must never replace that file.
        self.assertIn('flow_id', self.connector.begin('owner'))
        self.assertEqual(b"existing-credential", target.read_bytes())
        self.assertEqual([], self.calls)

    def test_scope_expansion_missing_refresh_and_malformed_google_response_do_not_save(self):
        for response in ({**self.token_result, "scope": SCOPE + " https://www.googleapis.com/auth/gmail.send"},
                         {**self.token_result, "refresh_token": ""}, {**self.token_result, "scope": []}, []):
            with self.subTest(response_type=type(response).__name__):
                self.token_result = response
                payload = self.payload()
                self.assert_error("google_authorization_failed", self.connector.complete, "owner", payload)
                self.assertFalse((self.home / "google_token.json").exists())
                self.assert_error("google_flow_expired", self.connector.complete, "owner", payload)

    def test_concurrent_create_during_exchange_is_not_overwritten(self):
        original = self.google

        def race(request):
            response = original(request)
            if request.url.path.endswith("/profile"):
                (self.home / "google_token.json").write_bytes(b"other-connection")
            return response

        self.connector._transport = httpx.MockTransport(race)
        self.assert_error("google_existing_connection", self.connector.complete, "owner", self.payload())
        self.assertEqual(b"other-connection", (self.home / "google_token.json").read_bytes())
        self.assertEqual([], list(self.home.glob(".google-connect-*")))

    def test_setup_status_does_not_leak_client_secret_or_claim_connected(self):
        self.client.write_text('{"installed":{"client_id":"private-id","client_secret":"secret"}}', encoding="utf-8")
        status = self.connector.status()
        self.assertEqual("setup_required", status["state"])
        self.assertNotIn("private-id", json.dumps(status))
        self.assertNotIn("secret", json.dumps(status))
        self.assert_error("google_setup_required", self.connector.begin, "owner")

    def test_existing_connection_is_verified_through_workspace_adapter(self):
        (self.home / "google_token.json").write_text("{}", encoding="utf-8")
        with patch("career_core.workspace_reader.connected_account_details", return_value={"email": "existing@example.test"}):
            self.assertEqual("existing@example.test", self.connector.status()["email"])
        with patch("career_core.workspace_reader.connected_account_details", side_effect=CareerError("authentication_required", "private-provider-error")):
            self.assertEqual("reauth_required", self.connector.status()["state"])

    def test_routes_require_session_native_consent_bounded_body_and_no_cache(self):
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request: Request, call_next):
            request.state.session = request.headers.get("test-session")
            return await call_next(request)

        app.include_router(create_google_router(self.connector), dependencies=[Depends(require_session)])
        client = TestClient(app)
        self.assertEqual(401, client.get("/connections/google").status_code)
        headers = {"test-session": "owner", "x-hermes-google-consent": "once"}
        self.assertEqual(403, client.post("/connections/google/begin", json={}, headers={"test-session": "owner"}).status_code)
        self.assertEqual(403, client.post("/connections/google/begin", json={}, headers={**headers, "origin": "https://testserver"}).status_code)
        self.assertEqual(413, client.post("/connections/google/complete", content="x" * 17000,
            headers={**headers, "content-type": "application/json"}).status_code)
        begin = client.post("/connections/google/begin", json={}, headers=headers)
        self.assertEqual(200, begin.status_code)
        self.assertIn("no-store", begin.headers["cache-control"])
        result = client.post("/connections/google/complete", json={"flow_id": begin.json()["flow_id"], "code": "synthetic-code"}, headers=headers)
        self.assertEqual(200, result.status_code)
        self.assertNotIn("synthetic", result.text)

    def test_career_router_mounts_connection_with_parent_authentication(self):
        app = FastAPI()
        app.state.auth_required = True
        app.include_router(create_router(), prefix="/api/plugins/career")
        response = TestClient(app).get("/api/plugins/career/connections/google")
        self.assertEqual(401, response.status_code)
