import tempfile
import unittest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router, require_session
from career_core.company_views import CompanyViews
from career_core.home import HomeView
from career_core.mail_store import MailStore
from career_core.store import Store


class CompanyLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.app = FastAPI()
        self.app.include_router(create_router(lambda: self.store), prefix="/career")
        self.app.dependency_overrides[require_session] = lambda: None
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)
        self.create = dict(operation_id="create-company-1", kind="companies", id="c1", expected_version=0,
                           data={"name": "  追加した企業  "})
        self.delete = dict(operation_id="delete-company-1", id="c1", expected_version=1, confirmed_by_user=True)

    def test_add_reload_delete_reload_and_replay(self):
        first = self.client.post("/career/save", json=self.create)
        self.assertEqual(200, first.status_code, first.text)
        self.assertEqual("追加した企業", first.json()["data"]["name"])
        self.assertEqual(first.json(), self.client.post("/career/save", json=self.create).json())
        self.assertEqual(["c1"], [c["id"] for c in CompanyViews(self.store).overview()["companies"]])
        removed = self.client.post("/career/delete-company", json=self.delete)
        self.assertEqual(200, removed.status_code, removed.text)
        self.assertEqual(removed.json(), self.client.post("/career/delete-company", json=self.delete).json())
        self.assertEqual([], CompanyViews(Store(self.tmp.name)).overview()["companies"])
        self.assertEqual(404, self.client.get("/career/companies/c1").status_code)
        with self.store.connect() as db:
            self.assertEqual(2, self.store.entity(db, "companies", "c1")["version"])

    def test_deletion_preserves_evidence_and_excludes_related_work(self):
        self.client.post("/career/save", json=self.create)
        mail = MailStore(self.store)
        mail.configure_sender("c1", "recruit@example.com", True)
        with self.store.transaction() as db:
            self.store.put(db, "companies", "other", 0, {"name": "残す企業"})
            self.store.put(db, "offers", "offer", 0, {"company_id": "c1", "title": "募集"})
            self.store.put(db, "applications", "app", 0, {"offer_id": "offer"})
            self.store.put(db, "tasks", "task", 0, {"application_id": "app", "title": "提出", "deadline": "2099-01-01"})
            self.store.put(db, "documents", "DOC-001", 0, {"company_id": "c1", "body": "保存済みES"})
            mail.save_message(db, "a", "c1", dict(id="m", sender_address="recruit@example.com",
                text="保存済みメール", locator="gmail:m", received_at="2026-09-18T00:00:00Z"))
        self.assertEqual(200, self.client.post("/career/delete-company", json=self.delete).status_code)
        self.assertEqual({}, mail.active_assignments())
        records = self.store.snapshot()["entities"]
        self.assertEqual({"other", "DOC-001"}, {e["id"] for e in records})
        with self.store.connect() as db:
            self.assertEqual("保存済みES", self.store.entity(db, "documents", "DOC-001")["data"]["body"])
            self.assertIsNotNone(self.store.entity(db, "tasks", "task"))
            self.assertEqual("保存済みメール", db.execute("SELECT body FROM career_mail_messages").fetchone()[0])
        self.assertNotIn("提出", str(HomeView(self.store).read()))

    def test_invalid_name_confirmation_and_stale_version_do_not_mutate(self):
        for name in ("", "  ", "a" * 201):
            request = dict(self.create, data={"name": name})
            self.assertEqual(400, self.client.post("/career/save", json=request).status_code)
        self.client.post("/career/save", json=self.create)
        self.assertEqual(400, self.client.post("/career/delete-company", json=dict(self.delete, confirmed_by_user=False)).status_code)
        self.assertEqual(409, self.client.post("/career/delete-company", json=dict(self.delete, expected_version=0)).status_code)
        self.assertEqual(1, len(CompanyViews(self.store).overview()["companies"]))

    def test_authentication_is_required_for_both_actions(self):
        self.app.dependency_overrides.clear()
        for action, payload in (("save", self.create), ("delete-company", self.delete)):
            self.assertEqual(401, self.client.post("/career/" + action, json=payload).status_code)
