from pathlib import Path
import sys
import unittest

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from store_fixtures import make_root, write_release
from updates_core.api import create_router
from updates_core.store import ConfigurationError, ReleaseStore, config_from_environment


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()
        self.mode = "enabled"

        def store():
            if self.mode == "disabled":
                return None
            if self.mode == "broken":
                raise ConfigurationError("updates_dir_writable_by_others")
            return ReleaseStore(config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root)}))

        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request: Request, call_next):
            if request.headers.get("x-test-auth"):
                request.state.session = type("Session", (), {"user_id": "alice", "provider": "basic"})()
            return await call_next(request)

        app.include_router(create_router(store))
        self.app = app
        self.client = TestClient(app)
        self.headers = {"x-test-auth": "1"}

    def tearDown(self):
        self.holder.cleanup()

    def get(self, path, **headers):
        return self.client.get(path, headers=self.headers | headers)

    def test_every_route_requires_the_dashboard_session(self):
        _, record, _ = write_release(self.root, 2)
        for path in ["/status", "/latest", f"/releases/{record['release_id']}/apk"]:
            with self.subTest(path=path):
                self.assertEqual(401, self.client.get(path).status_code)
        self.app.state.auth_required = False
        self.assertEqual(401, self.get("/latest").status_code)

    def test_cross_origin_and_cross_site_requests_are_refused(self):
        _, record, _ = write_release(self.root, 2)
        for headers in [{"origin": "https://evil.example"}, {"origin": "http://testserver.evil"},
                        {"sec-fetch-site": "cross-site"}]:
            for path in ["/latest", f"/releases/{record['release_id']}/apk"]:
                with self.subTest(headers=headers, path=path):
                    self.assertEqual(403, self.get(path, **headers).status_code)
        self.assertEqual(200, self.get("/latest", origin="http://testserver").status_code)

    def test_disabled_unless_operator_configured(self):
        self.mode = "disabled"
        status = self.get("/status")
        self.assertEqual(200, status.status_code)
        self.assertEqual({"schema": "hermes-mobile-update-status", "protocol": 1, "enabled": False},
                         status.json())
        for path in ["/latest", "/releases/v2-0123456789abcdef/apk"]:
            response = self.get(path)
            self.assertEqual(503, response.status_code)
            self.assertEqual("updates_not_configured", response.json()["detail"]["code"])

    def test_misconfiguration_fails_closed_without_leaking_paths(self):
        self.mode = "broken"
        response = self.get("/latest")
        self.assertEqual(503, response.status_code)
        self.assertEqual({"code": "updates_misconfigured"}, response.json()["detail"])
        self.assertNotIn(str(self.root), response.text)

    def test_latest_metadata_protocol(self):
        empty = self.get("/latest")
        self.assertEqual({"schema": "hermes-mobile-update-latest", "protocol": 1, "release": None}, empty.json())
        write_release(self.root, 2)
        _, record, apk = write_release(self.root, 3)
        response = self.get("/latest")
        self.assertEqual(200, response.status_code)
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertEqual("nosniff", response.headers["x-content-type-options"])
        body = response.json()
        self.assertEqual("hermes-mobile-update-latest", body["schema"])
        self.assertEqual(1, body["protocol"])
        release = body["release"]
        self.assertEqual(record["release_id"], release["release_id"])
        self.assertEqual(3, release["version_code"])
        self.assertEqual(len(apk), release["size_bytes"])
        self.assertEqual(f"releases/{record['release_id']}/apk", release["apk_path"])
        self.assertEqual(200, self.get("/status").status_code)
        self.assertTrue(self.get("/status").json()["enabled"])

    def test_apk_download_streams_exact_verified_bytes(self):
        _, record, apk = write_release(self.root, 2)
        with self.assertLogs("hermes.mobile_updates", "WARNING") as logs:
            response = self.get(f"/releases/{record['release_id']}/apk")
        self.assertEqual(200, response.status_code)
        self.assertEqual(apk, response.content)
        self.assertEqual("application/vnd.android.package-archive", response.headers["content-type"])
        self.assertEqual(str(len(apk)), response.headers["content-length"])
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertIn("attachment", response.headers["content-disposition"])
        self.assertEqual(f'"sha256:{record["sha256"]}"', response.headers["etag"])
        text = "\n".join(logs.output)
        self.assertIn(record["release_id"], text)
        self.assertNotIn(str(self.root), text)

    def test_download_refuses_unknown_traversal_and_tampered_releases(self):
        target, record, apk = write_release(self.root, 2)
        for path in ["/releases/v9-0123456789abcdef/apk", "/releases/..%2F..%2Fetc/apk",
                     "/releases/v2-ZZZ/apk", "/releases/%2e%2e/apk"]:
            with self.subTest(path=path):
                self.assertEqual(404, self.get(path).status_code)
        (target / "app.apk").write_bytes(b"x" * len(apk))
        response = self.get(f"/releases/{record['release_id']}/apk")
        self.assertEqual(409, response.status_code)
        self.assertEqual("release_integrity_failed", response.json()["detail"]["code"])

    def test_api_is_read_only(self):
        _, record, _ = write_release(self.root, 2)
        for method in ("post", "put", "delete", "patch"):
            for path in ["/latest", f"/releases/{record['release_id']}/apk", "/publish"]:
                with self.subTest(method=method, path=path):
                    response = getattr(self.client, method)(path, headers=self.headers)
                    self.assertIn(response.status_code, (404, 405))


if __name__ == "__main__":
    unittest.main()
