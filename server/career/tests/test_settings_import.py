import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.collection_store import CollectionStore
from career_core.mail_store import MailStore
from career_core.store import Store


class SettingsImportTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.store = Store(temporary.name)
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            request.state.session = {"user": "synthetic"}
            return await call_next(request)

        app.include_router(create_router(lambda: self.store))
        self.client = TestClient(app)
        self.addCleanup(self.client.close)

    def test_import_mail_sender_round_trip_and_same_operation_retry(self):
        request = {"operation_id": "import-settings-test", "format_version": 1,
                   "file_sha256": "a" * 64, "bundle_id": "synthetic", "records": [
                       {"type": "Company", "source_id": "one", "source_version": "company-v1",
                        "data": {"name": "Synthetic company"}},
                       {"type": "CompanyMailSender", "source_id": "sender", "source_version": "sender-v1",
                        "data": {"companySyncId": "one", "emailAddress": "recruit@example.test"}},
                   ]}
        response = self.client.post("/import", json=request)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(2, response.json()["imported"])
        self.assertEqual(response.json(), self.client.post("/import", json=request).json())
        detail = self.client.get("/companies/shukatsu:Company:one").json()
        self.assertEqual("recruit@example.test", detail["mail_senders"][0]["address"])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_secret_field_rejects_entire_import_without_partial_writes(self):
        request = {"operation_id": "rejected-import", "format_version": 1, "file_sha256": "a" * 64,
                   "records": [{"type": "Company", "source_id": "one", "source_version": "v1",
                                "data": {"name": "Synthetic", "password": "do-not-store"}}]}
        response = self.client.post("/import", json=request)
        self.assertEqual(400, response.status_code)
        self.assertEqual("unapproved_fields", response.json()["detail"]["code"])
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM entities").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_only_mail_settings_remain_configurable(self):
        account = "gmail-" + "a" * 32
        with patch("career_core.workspace_reader.connected_account", return_value=account):
            response = self.client.post("/collection/settings", json={"channel": "mail", "account_id": account, "enabled": True})
        self.assertEqual(200, response.status_code)
        self.assertEqual([], CollectionStore(self.store).grants())
        self.assertTrue(MailStore(self.store).settings()["enabled"])
        response = self.client.post("/collection/settings", json={"channel": "portal", "enabled": True})
        self.assertEqual(400, response.status_code)
        self.assertEqual("invalid_request", response.json()["detail"]["code"])
        self.assertEqual([], CollectionStore(self.store).grants())
        self.assertTrue(MailStore(self.store).settings()["enabled"])
        self.client.post("/collection/settings", json={"channel": "mail", "account_id": account, "enabled": False})
        self.assertFalse(MailStore(self.store).settings()["enabled"])

    def test_unknown_channel_cannot_change_either_setting(self):
        response = self.client.post("/collection/settings", json={"channel": "all-typo", "enabled": True})
        self.assertEqual(400, response.status_code)
        self.assertEqual([], CollectionStore(self.store).grants())
        self.assertFalse(MailStore(self.store).settings()["enabled"])
