"""Parent review regressions: real local fixtures, no live services."""
import sys
from pathlib import Path
import unittest

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from store_fixtures import make_root, write_release
from updates_core.api import create_router
from updates_core.store import ReleaseStore, StoreError, config_from_environment


class ReviewRegressionTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()
        self.addCleanup(self.holder.cleanup)
        self.store = ReleaseStore(config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root)}))

    def test_hosted_authentication_uses_only_parent_verified_request_state(self):
        app = FastAPI()
        app.state.auth_required = False

        @app.middleware("http")
        async def verified_parent(request: Request, call_next):
            # Synthetic stand-in for the parent host; not an application HTTP header.
            request.state.hosted_http_authenticated = request.url.path == "/status"
            return await call_next(request)

        app.include_router(create_router(lambda: self.store))
        with TestClient(app) as client:
            self.assertEqual(200, client.get("/status").status_code)
            self.assertEqual(401, client.get("/latest", headers={"hosted_http_authenticated": "true"}).status_code)

    def test_nested_signer_metadata_is_rejected_without_breaking_valid_latest(self):
        write_release(self.root, 2)
        write_release(self.root, 3, signer_sha256=[{"not": "a digest"}])
        latest = self.store.latest()
        assert latest is not None
        self.assertEqual(2, latest["version_code"])

    def test_direct_apk_download_rejects_symlinked_releases_directory(self):
        _, record, _ = write_release(self.root, 2)
        releases = self.root / "releases"
        alternate = self.root.parent / "alternate-releases"
        releases.rename(alternate)
        releases.symlink_to(alternate, target_is_directory=True)
        with self.assertRaises(StoreError):
            handle, _ = self.store.open_apk(record["release_id"])
            handle.close()

    def test_direct_apk_download_rejects_root_replaced_by_symlink(self):
        _, record, _ = write_release(self.root, 2)
        alternate = self.root.parent / "alternate-root"
        self.root.rename(alternate)
        self.root.symlink_to(alternate, target_is_directory=True)
        with self.assertRaises(StoreError):
            handle, _ = self.store.open_apk(record["release_id"])
            handle.close()


if __name__ == "__main__":
    unittest.main()
