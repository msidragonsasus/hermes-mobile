import json
import os
from pathlib import Path
import tempfile
import unittest
from urllib.parse import parse_qs

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

import mobile_google_workspace as subject
from prepare_install import patch

G = subject.G
CLIENT = {"web": {"client_id": "123-abc.apps.googleusercontent.com", "client_secret": "s3cret-value"}}
NATIVE = {"x-hermes-google-consent": "once"}


class FakeBackend:
    def __init__(self, root):
        self.root = Path(root)
        self.creds = self.root / "workspace_mcp_credentials"
        self.client_path = self.root / "google_mobile_client_secret.json"
        self.client_path.write_text(json.dumps(CLIENT))
        self.setup_value = None
        self.scope = " ".join(subject.REQUESTED_SCOPES)
        self.email = "Person@Example.com"
        self.token_calls = []
        self.token_status = 200

    def owner(self, request: Request):
        owner = request.headers.get("x-test-owner")
        if not owner:
            raise HTTPException(401)
        return owner

    def setup(self, profile):
        if self.setup_value is not None:
            return self.setup_value
        return {"credentials_dir": str(self.creds), "client_secret_path": str(self.client_path),
                "enabled": True, "single_user": False}

    def handler(self, request):
        if request.url == httpx.URL(subject.TOKEN_URI):
            form = parse_qs(request.content.decode(), keep_blank_values=True)
            self.token_calls.append(form)
            return httpx.Response(self.token_status, json={
                "access_token": "access-" + form["code"][0], "refresh_token": "refresh-" + form["code"][0],
                "expires_in": 3599, "scope": self.scope, "token_type": "Bearer"})
        if request.url == httpx.URL(subject.USERINFO_URI):
            return httpx.Response(200, json={"email": self.email, "email_verified": True})
        return httpx.Response(404)

    def http(self):
        return httpx.Client(transport=httpx.MockTransport(self.handler))


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.backend = FakeBackend(self.tmp.name)
        app = FastAPI()
        app.include_router(subject.create_router(self.backend))
        self.client = TestClient(app).__enter__()
        self.client.headers["x-test-owner"] = "owner-a"
        self.base = subject.PREFIX

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.tmp.cleanup()

    def post(self, action, body, headers=NATIVE, **kw):
        return self.client.post(self.base + "/" + action, json=body, headers=headers, **kw)

    def connect(self, code="code-1"):
        begin = self.post("begin", {})
        self.assertEqual(200, begin.status_code, begin.text)
        return begin, self.post("complete", {"flow_id": begin.json()["flow_id"], "code": code})


class FlowTests(Base):
    def test_status_before_connecting_is_disconnected_and_has_no_secret(self):
        response = self.client.get(self.base)
        self.assertEqual(200, response.status_code)
        self.assertEqual("disconnected", response.json()["state"])
        self.assertTrue(response.json()["can_add"])
        self.assertNotIn("s3cret", response.text)
        self.assertEqual("no-store, private", response.headers["cache-control"])

    def test_begin_returns_public_client_and_workspace_scopes_only(self):
        body = self.post("begin", {}).json()
        self.assertEqual(CLIENT["web"]["client_id"], body["client_id"])
        self.assertEqual(subject.REQUESTED_SCOPES, body["scopes"])
        self.assertFalse(any("gmail" in s for s in body["scopes"]))
        self.assertNotIn("s3cret", json.dumps(body))

    def test_complete_writes_workspace_mcp_credential_file(self):
        _, done = self.connect()
        self.assertEqual(200, done.status_code, done.text)
        self.assertEqual("connected", done.json()["state"])
        self.assertEqual("person@example.com", done.json()["accounts"][0]["email"])
        self.assertNotIn("refresh-", done.text)
        self.assertNotIn("s3cret", done.text)
        path = self.backend.creds / "person@example.com.json"
        data = json.loads(path.read_text())
        self.assertEqual({"token", "refresh_token", "token_uri", "client_id", "client_secret", "scopes", "expiry"}, set(data))
        self.assertEqual("refresh-code-1", data["refresh_token"])
        self.assertEqual(CLIENT["web"]["client_secret"], data["client_secret"])
        if os.name != "nt":
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertEqual([""], self.backend.token_calls[0]["redirect_uri"])
        self.assertEqual(["authorization_code"], self.backend.token_calls[0]["grant_type"])

    def test_two_accounts_are_kept_side_by_side(self):
        self.connect("a")
        self.backend.email = "second@example.org"
        _, done = self.connect("b")
        emails = sorted(row["email"] for row in done.json()["accounts"])
        self.assertEqual(["person@example.com", "second@example.org"], emails)

    def test_reconnecting_same_account_backs_up_old_file(self):
        self.connect("a")
        _, done = self.connect("b")
        self.assertEqual(1, len(done.json()["accounts"]))
        backups = list((self.backend.creds / subject.BACKUP_DIR).iterdir())
        self.assertEqual(1, len(backups))
        self.assertEqual("refresh-a", json.loads(backups[0].read_text())["refresh_token"])
        self.assertEqual("refresh-b", json.loads((self.backend.creds / "person@example.com.json").read_text())["refresh_token"])

    def test_flow_is_single_use(self):
        begin, done = self.connect()
        again = self.post("complete", {"flow_id": begin.json()["flow_id"], "code": "code-2"})
        self.assertEqual(409, again.status_code)
        self.assertEqual("google_flow_expired", again.json()["detail"]["code"])
        self.assertEqual(1, len(self.backend.token_calls))

    def test_failed_exchange_is_not_retried_and_stores_nothing(self):
        self.backend.token_status = 400
        begin, done = self.connect()
        self.assertEqual(409, done.status_code)
        self.assertEqual("google_authorization_failed", done.json()["detail"]["code"])
        self.assertFalse(self.backend.creds.exists() and any(self.backend.creds.glob("*.json")))
        again = self.post("complete", {"flow_id": begin.json()["flow_id"], "code": "x"})
        self.assertEqual(409, again.status_code)

    def test_partial_consent_is_rejected(self):
        self.backend.scope = "openid " + G + "userinfo.email " + G + "calendar"
        _, done = self.connect()
        self.assertEqual("google_scope_missing", done.json()["detail"]["code"])
        self.assertFalse(any(self.backend.creds.glob("*.json")) if self.backend.creds.exists() else False)

    def test_short_scope_aliases_are_stored_as_full_names(self):
        self.backend.scope = "openid email profile " + " ".join(subject.REQUESTED_SCOPES[3:])
        _, done = self.connect()
        self.assertEqual(200, done.status_code, done.text)
        data = json.loads((self.backend.creds / "person@example.com.json").read_text())
        self.assertIn(G + "userinfo.email", data["scopes"])
        self.assertNotIn("email", data["scopes"])

    def test_unexpected_extra_scope_is_rejected(self):
        self.backend.scope = " ".join(subject.REQUESTED_SCOPES) + " " + G + "gmail.modify"
        _, done = self.connect()
        self.assertEqual("google_authorization_failed", done.json()["detail"]["code"])

    def test_other_owner_or_profile_cannot_complete(self):
        begin = self.post("begin", {})
        flow = {"flow_id": begin.json()["flow_id"], "code": "c"}
        other = self.client.post(self.base + "/complete", json=flow, headers={**NATIVE, "x-test-owner": "owner-b"})
        self.assertEqual(409, other.status_code)
        begin = self.post("begin", {})
        flow = {"flow_id": begin.json()["flow_id"], "code": "c"}
        profile = self.client.post(self.base + "/complete?profile=work", json=flow, headers=NATIVE)
        self.assertEqual(409, profile.status_code)
        self.assertEqual([], self.backend.token_calls)

    def test_browser_requests_are_refused(self):
        for headers in ({}, {**NATIVE, "origin": "https://evil.example"}, {**NATIVE, "sec-fetch-site": "cross-site"}):
            self.assertEqual(403, self.post("begin", {}, headers=headers).status_code)

    def test_unauthenticated_requests_are_refused(self):
        del self.client.headers["x-test-owner"]
        self.assertEqual(401, self.client.get(self.base).status_code)
        self.assertEqual(401, self.post("begin", {}).status_code)

    def test_clients_cannot_supply_redirects_or_extra_fields(self):
        self.assertEqual(400, self.post("begin", {"redirect_uri": "https://x"}).status_code)
        begin = self.post("begin", {})
        bad = self.post("complete", {"flow_id": begin.json()["flow_id"], "code": "c", "client_id": "x"})
        self.assertEqual(400, bad.status_code)

    def test_disconnect_moves_file_to_backup(self):
        _, done = self.connect()
        account = done.json()["account_id"]
        result = self.post("disconnect", {"account_id": account})
        self.assertEqual(200, result.status_code)
        self.assertEqual([], result.json()["accounts"])
        self.assertFalse((self.backend.creds / "person@example.com.json").exists())
        self.assertEqual(1, len(list((self.backend.creds / subject.BACKUP_DIR).iterdir())))
        self.assertEqual(404, self.post("disconnect", {"account_id": account}).status_code)

    def test_existing_server_login_is_listed_and_old_files_without_refresh_need_reauth(self):
        self.backend.creds.mkdir()
        (self.backend.creds / "keep@example.com.json").write_text(json.dumps(
            {"token": "t", "refresh_token": "r", "scopes": [G + "drive", G + "calendar", G + "documents"]}))
        (self.backend.creds / "old@example.com.json").write_text(json.dumps({"token": "t", "scopes": []}))
        (self.backend.creds / "oauth_states.json").write_text("{}")
        rows = {r["email"]: r["state"] for r in self.client.get(self.base).json()["accounts"]}
        self.assertEqual({"keep@example.com": "connected", "old@example.com": "reauth_required"}, rows)

    def test_missing_mcp_setup_is_reported(self):
        self.backend.setup_value = {"reason": "workspace_mcp_missing"}
        status = self.client.get(self.base).json()
        self.assertEqual("setup_required", status["state"])
        self.assertEqual(503, self.post("begin", {}).status_code)

    def test_missing_client_file_blocks_adding(self):
        self.backend.client_path.unlink()
        status = self.client.get(self.base).json()
        self.assertFalse(status["can_add"])
        self.assertEqual("google_client_missing", status["reason"])

    @unittest.skipIf(os.name == "nt", "symlinks")
    def test_symlinked_credentials_dir_is_refused(self):
        real = Path(self.tmp.name) / "elsewhere"
        real.mkdir()
        os.symlink(real, self.backend.creds)
        self.assertEqual(503, self.client.get(self.base).status_code)


class InstallTests(unittest.TestCase):
    SOURCE = ("from fastapi import APIRouter\nrouter = APIRouter()\n"
              "from hermes_cli.mobile_service_connections import create_router as _mobile_connections_router\n"
              "router.include_router(_mobile_connections_router())\n\n@router.get('/x')\ndef x():\n    return 1\n")

    def test_patch_keeps_slack_and_is_idempotent(self):
        once = patch(self.SOURCE)
        self.assertEqual(once, patch(once))
        self.assertIn("_mobile_connections_router()", once)
        self.assertEqual(1, once.count("router.include_router(_mobile_google_workspace_router())"))

    def test_patch_preserves_crlf(self):
        once = patch(self.SOURCE.replace("\n", "\r\n"))
        self.assertNotIn("\r\r", once)
        self.assertEqual(once.count("\n"), once.count("\r\n"))

    def test_unknown_router_is_rejected(self):
        with self.assertRaises(ValueError):
            patch("x = 1\n")


if __name__ == "__main__":
    unittest.main()
