import json
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools, register_tools
from career_core.gmail_reader import account_reference
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store
from coach_fixture import configure_execution


class CoachAppTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.store = Store(temporary.name)
        self.coach = CoachStore(self.store)
        self.tools = CoachTools(self.coach, fetch=lambda url: {"url": url, "text": "公式案内", "links": []})
        self.mail = MailStore(self.store)
        self.address = "mailbox@example.test"
        self.identity = account_reference(self.address)
        self.accounts = [{"id": self.identity, "email": self.address, "state": "connected"}]
        for mocked in (patch("career_core.workspace_reader.connected_accounts_details", side_effect=lambda: self.accounts),
                       patch("career_core.workspace_reader.connected_account", side_effect=lambda identity: identity)):
            mocked.start()
            self.addCleanup(mocked.stop)
        with self.store.transaction() as db:
            for identity in ("a", "b"):
                self.store.put(db, "companies", identity, 0,
                               {"name": identity, "official_urls": ["https://example.test/recruit"]})

    def active(self, text=None, thread="global"):
        self.coach.submit({"text": text or "受信先をmailbox@example.testに設定し、企業送信元recruit@example.testを登録して",
                           "operation_id": "app-request-1", "thread_id": thread}, "phone")
        self.run = self.coach.claim()
        self.metadata = {"task_id": self.run["id"], "session_id": "career-run:" + self.run["id"]}
        statement = self.coach.snapshot("phone", thread_id=thread)["messages"][-1]
        self.proof = {"message_id": statement["id"], "quote": statement["text"]}

    def change(self, action="mail_account", **changes):
        args = dict(action=action, address=self.address, enabled=True, expected_version=0, user_statement=self.proof)
        args.update(changes)
        return self.tools.dispatch("career_app", args, self.metadata)

    def test_account_selects_real_connected_identity_and_retries_once(self):
        self.active()
        original = self.store.snapshot()["entities"]
        first = self.change()
        self.assertEqual(first, self.change())
        self.assertEqual(self.identity, self.mail.settings()["account_id"])
        self.assertEqual(1, self.mail.settings()["revision"])
        self.assertEqual("all_companies", first["mail_scope"])
        self.assertFalse(first["collection_verified"])
        self.assertEqual(original, self.store.snapshot()["entities"])
        self.assertEqual([], self.mail.sender_rules())

    def test_connections_and_app_route_share_settings_and_scope_senders(self):
        self.mail.configure_sender("a", "recruit@example.test", True)
        self.mail.configure_sender("b", "other@example.test", True)
        self.active(thread="company:a")
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            request.state.session = {"user": "synthetic"}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store, run_worker=False))
        response = TestClient(app).get("/collection/connections")
        self.assertEqual(200, response.status_code)
        ui = response.json()
        result = self.tools.dispatch("career_app", {"action": "connections"}, self.metadata)
        self.assertEqual(ui["mail"], result["mail"])
        self.assertEqual(ui["account_details"], result["account_details"])
        self.assertEqual(["a"], [rule["company_id"] for rule in result["mail_senders"]])

    def test_missing_connection_does_not_write(self):
        self.active()
        self.accounts.clear()
        with self.assertRaises(CareerError) as error:
            self.change()
        self.assertEqual("mail_connection_required", error.exception.code)
        self.assertIsNone(self.mail.settings()["account_id"])

    def test_cannot_invent_quote_or_use_another_address(self):
        self.active()
        for changes in ({"address": "other@example.test"},
                        {"user_statement": dict(self.proof, quote="mailbox@example.testを別の内容へ捏造")}):
            with self.assertRaises(CareerError):
                self.change(**changes)
        self.assertFalse(self.mail.settings()["enabled"])

    def test_disabling_selected_account_does_not_require_google(self):
        self.mail.configure(self.identity, True)
        self.active("mailbox@example.testの自動同期を止めて")
        with patch("career_core.workspace_reader.connected_accounts_details", side_effect=AssertionError("no Google reads")), \
             patch("career_core.workspace_reader.connected_account", side_effect=AssertionError("no Google reads")):
            self.change(enabled=False, expected_version=1)
        self.assertFalse(self.mail.settings()["enabled"])
        self.assertEqual(self.identity, self.mail.settings()["account_id"])

    def test_changed_live_google_identity_does_not_write(self):
        self.active()
        with patch("career_core.workspace_reader.connected_account", return_value=account_reference("other@example.test")):
            with self.assertRaises(CareerError) as error:
                self.change()
        self.assertEqual("mail_account_mismatch", error.exception.code)
        self.assertIsNone(self.mail.settings()["account_id"])

    def test_stale_settings_detect_aba_changes(self):
        self.active()
        self.mail.configure(self.identity, True)
        self.mail.configure(None, False)
        with self.assertRaises(CareerError) as error:
            self.change()
        self.assertEqual("version_conflict", error.exception.code)
        self.assertIsNone(self.mail.settings()["account_id"])

    def test_daily_and_cancelled_runs_cannot_change_settings(self):
        self.active()
        with self.store.transaction() as db:
            db.execute("UPDATE coach_runs SET kind='daily' WHERE id=?", (self.run["id"],))
        with self.assertRaises(CareerError) as error:
            self.change()
        self.assertEqual("daily_scope", error.exception.code)
        with self.store.transaction() as db:
            db.execute("UPDATE coach_runs SET kind='message',cancel_requested=1 WHERE id=?", (self.run["id"],))
        with self.assertRaises(CareerError):
            self.change()
        self.assertFalse(self.mail.settings()["enabled"])

    def test_recipient_is_rejected_as_company_sender(self):
        self.active()
        with self.assertRaises(CareerError) as error:
            self.change(action="mail_sender", company_id="a")
        self.assertEqual("recipient_is_not_sender", error.exception.code)
        self.assertEqual([], self.mail.sender_rules())

    def test_company_sender_uses_canonical_store_and_checks_scope_version(self):
        self.active(thread="company:a")
        first = self.change(action="mail_sender", company_id="a", address="recruit@example.test")
        self.assertEqual(first, self.change(action="mail_sender", company_id="a", address="recruit@example.test"))
        self.assertEqual("recruit@example.test", self.mail.sender_rules("a")[0]["address"])
        with self.assertRaises(CareerError) as error:
            self.change(action="mail_sender", company_id="b", address="recruit@example.test")
        self.assertEqual("write_scope_conflict", error.exception.code)
        with self.assertRaises(CareerError) as error:
            self.change(action="mail_sender", company_id="a", address="recruit@example.test", enabled=False)
        self.assertEqual("version_conflict", error.exception.code)

    def test_receipt_failure_rolls_back_settings(self):
        self.active()
        with patch.object(self.coach, "event", side_effect=RuntimeError("receipt unavailable")):
            with self.assertRaises(RuntimeError):
                self.change()
        self.assertIsNone(self.mail.settings()["account_id"])
        self.assertEqual(0, self.mail.settings()["revision"])

    def test_registered_tool_requires_host_metadata(self):
        self.active()
        registered = {}
        class Context:
            def register_tool(self, **spec):
                registered[spec["name"]] = spec
        register_tools(Context(), lambda: self.store)
        handler = registered["career_app"]["handler"]
        self.assertIn("error", json.loads(handler({"action": "connections"})))
        self.assertIn("mail", json.loads(handler({"action": "connections"}, **self.metadata)))

    def test_public_paths_on_approved_origin_and_mypage_link_save(self):
        self.active(thread="company:a")
        url = "https://example.test/recruit/mypage"
        result = self.tools.dispatch("career_fetch", {"url": url}, self.metadata)
        self.assertEqual(url, result["url"])
        self.tools.dispatch("career_save", {"kind": "company_links", "id": "a:mypage", "expected_version": 0,
            "data": {"company_id": "a", "type": "MyPage", "label": "マイページ", "url": url}}, self.metadata)
        with self.store.connect() as db:
            self.assertEqual(url, self.store.entity(db, "company_links", "a:mypage")["data"]["url"])
        for blocked in ("https://other.test/recruit", "http://example.test/recruit", "https://example.test/recruit?token=secret"):
            with self.assertRaises(CareerError) as error:
                self.tools.dispatch("career_fetch", {"url": blocked}, self.metadata)
            self.assertEqual("fetch_not_granted", error.exception.code)

    def test_daily_cannot_fetch_even_approved_origin(self):
        self.active()
        with self.store.transaction() as db:
            db.execute("UPDATE coach_runs SET kind='daily' WHERE id=?", (self.run["id"],))
        with self.assertRaises(CareerError) as error:
            self.tools.dispatch("career_fetch", {"url": "https://example.test/recruit/new"}, self.metadata)
        self.assertEqual("stored_sources_only", error.exception.code)
