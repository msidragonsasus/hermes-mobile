import base64
from contextlib import contextmanager
from pathlib import Path
import sys
import time
import types
import unittest
import uuid
from unittest.mock import patch

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from terminal_core.api import SessionAuth, create_router
from terminal_core.sessions import Sessions


class FakeSession:
    def __init__(self, session_id, owner, cols, rows):
        self.id, self.owner, self.closed = session_id, owner, False
        self.writes = []

    def snapshot(self, offset=0):
        return dict(id=self.id, data="", start=offset, next=offset, truncated=False,
                    closed=self.closed, last_input=0, input_state="ready")

    def write(self, sequence, data):
        self.writes.append((sequence, data))
        return dict(sequence=sequence, state="accepted")

    def resize(self, cols, rows):
        pass

    def close(self):
        self.closed = True


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.manager = Sessions(FakeSession)
        self.auth = SessionAuth()
        self.base = types.ModuleType("hermes_cli.dashboard_auth.base")
        self.base.Session = type("Session", (), {})
        self.server = types.ModuleType("hermes_cli.web_server")
        self.server._require_token = lambda request: None
        self.modules = patch.dict(sys.modules, {
            "hermes_cli.dashboard_auth.base": self.base,
            "hermes_cli.web_server": self.server,
        })
        self.modules.start()
        self.enabled = True
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test-auth"):
                record = self.base.Session()
                record.provider = "basic"
                record.access_token = request.headers["x-test-auth"]
                record.user_id = request.headers.get("x-test-user", record.access_token)
                record.org_id = ""
                record.expires_at = time.time() + (-10 if record.access_token == "expired" else 600)
                request.state.session = record
            return await call_next(request)

        app.include_router(create_router(self.manager, self.auth, lambda: self.enabled))
        self.app = app
        self.client = TestClient(app)
        self.headers = {"x-test-auth": "owner"}
        self.id = str(uuid.uuid4())

    def tearDown(self):
        self.manager.close()
        self.modules.stop()

    def create(self, **overrides):
        return self.client.post("/sessions", json=dict(id=self.id, cols=80, rows=24, **overrides), headers=self.headers)

    def test_no_shell_before_auth_and_explicit_enable(self):
        for headers in [{}, {"x-test-auth": "expired"}]:
            self.assertEqual(self.client.get("/status", headers=headers).status_code, 401)
        self.enabled = False
        self.assertFalse(self.client.get("/status", headers=self.headers).json()["enabled"])
        self.assertEqual(self.create().status_code, 503)
        self.assertEqual(len(self.manager.sessions), 0)
        self.app.state.auth_required = False
        self.assertEqual(self.client.get("/status", headers=self.headers).status_code, 401)

    def test_origin_and_cross_site_requests_cannot_read_or_create(self):
        for headers in [{"origin": "https://evil.example"}, {"origin": "https://testserver"},
                        {"sec-fetch-site": "cross-site"}]:
            result = self.client.get("/sessions", headers=self.headers | headers)
            self.assertEqual(result.status_code, 403)
        self.assertEqual(len(self.manager.sessions), 0)

    def test_session_owner_and_profile_are_bound_to_auth(self):
        self.assertEqual(self.create().status_code, 200)
        for suffix, headers in [("", {"x-test-auth": "other"}), ("?profile=other", self.headers)]:
            self.assertEqual(self.client.get("/sessions/" + self.id + suffix, headers=headers).status_code, 404)
            self.assertEqual(self.client.get("/sessions" + suffix, headers=headers).json(), {"sessions": []})
        result = self.client.get("/sessions/" + self.id, headers=self.headers)
        self.assertEqual(result.headers["cache-control"], "no-store")

    def test_access_token_rotation_keeps_the_terminal(self):
        self.headers = {"x-test-auth": "token-1", "x-test-user": "alice"}
        self.assertEqual(self.create().status_code, 200)
        rotated = {"x-test-auth": "token-2", "x-test-user": "alice"}
        self.assertEqual(self.client.get("/sessions/" + self.id, headers=rotated).status_code, 200)
        other = {"x-test-auth": "token-2", "x-test-user": "bob"}
        self.assertEqual(self.client.get("/sessions/" + self.id, headers=other).status_code, 404)

    def test_session_limit_is_per_login_across_profiles(self):
        for index in range(4):
            response = self.client.post(f"/sessions?profile=p{index}", headers=self.headers,
                                        json=dict(id=str(uuid.uuid4()), cols=80, rows=24))
            self.assertEqual(response.status_code, 200)
        response = self.client.post("/sessions?profile=p9", headers=self.headers,
                                    json=dict(id=str(uuid.uuid4()), cols=80, rows=24))
        self.assertEqual(response.status_code, 429)
        next(iter(self.manager.sessions.values())).closed = True
        response = self.client.post("/sessions?profile=p9", headers=self.headers,
                                    json=dict(id=str(uuid.uuid4()), cols=80, rows=24))
        self.assertEqual(response.status_code, 200)

    def test_status_hides_user_when_disabled_and_lifecycle_is_audited(self):
        self.enabled = False
        self.assertIsNone(self.client.get("/status", headers=self.headers).json()["user"])
        self.enabled = True
        with self.assertLogs("hermes.mobile_terminal", "WARNING") as logs:
            self.create()
            self.client.post(f"/sessions/{self.id}/input", headers=self.headers,
                             json=dict(sequence=1, data=base64.b64encode(b"secret-code").decode()))
            self.client.delete(f"/sessions/{self.id}", headers=self.headers)
        text = "\n".join(logs.output)
        self.assertIn("create", text)
        self.assertIn("close", text)
        self.assertNotIn("secret-code", text)

    def test_payload_bounds_and_validation_fail_without_writing(self):
        self.assertEqual(self.create().status_code, 200)
        for payload in [dict(sequence=True, data="YQ=="), dict(sequence=1, data="bad!"),
                        dict(sequence=1, data=""), dict(sequence=1, data="YQ==", command="evil"),
                        dict(sequence=1, data="x" * 25000)]:
            response = self.client.post(f"/sessions/{self.id}/input", headers=self.headers, json=payload)
            self.assertIn(response.status_code, (400, 413))
        self.assertEqual(self.manager.get(self.id, self.manager.sessions[self.id].owner).writes, [])
        self.assertEqual(self.client.get(f"/sessions/{self.id}?offset=-1", headers=self.headers).status_code, 422)
        self.assertEqual(self.client.post(f"/sessions/{self.id}/size", headers=self.headers, json={"cols": 900, "rows": 24}).status_code, 400)

    def test_only_explicit_input_reaches_shell_and_close_is_owned(self):
        self.create()
        data = "認証コード".encode()
        result = self.client.post(f"/sessions/{self.id}/input", headers=self.headers,
                                  json=dict(sequence=1, data=base64.b64encode(data).decode()))
        self.assertEqual(result.json(), dict(sequence=1, state="accepted"))
        self.assertEqual(self.manager.sessions[self.id].writes, [(1, data)])
        self.assertEqual(self.client.delete(f"/sessions/{self.id}", headers={"x-test-auth": "other"}).status_code, 404)
        self.assertEqual(self.client.delete(f"/sessions/{self.id}", headers=self.headers).status_code, 200)
        self.assertEqual(len(self.manager.sessions), 0)


if __name__ == "__main__":
    unittest.main()
