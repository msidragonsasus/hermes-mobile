"""Synthetic coach→App MyPage connection contract. No real portal, account or credential."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.fernet import Fernet

from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools, register_tools
from career_core.company_views import CompanyViews
from career_core.mypage import MyPageStore
from career_core.mypage_bot import MyPageBotStore, connection_id
from career_core.mypage_cron import stop_company
from career_core.secret_store import SecretStore
from career_core.store import CareerError, Store
from coach_fixture import configure_execution

LINK = "https://portal.example.test/zw/s/demo_28/mypage/login"
OTHER = "https://portal.example.test/zw/s/demo_29/mypage/login"


class CoachMyPageConnectionTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.store = Store(Path(temporary.name) / "data")
        self.secrets = SecretStore(Path(temporary.name) / "private", key=Fernet.generate_key())
        self.records = MyPageBotStore(self.store, self.secrets)
        self.coach = CoachStore(self.store)
        self.tools = CoachTools(self.coach, fetch=lambda url: {"url": url, "text": "", "links": []})
        self.reconciled = []
        for mocked in (patch("career_core.mypage_browser.public_host", return_value="portal.example.test"),
                       patch("career_core.mypage_request.default_reconcile",
                             side_effect=lambda records, row: self.reconciled.append(row["version"]))):
            mocked.start()
            self.addCleanup(mocked.stop)
        with self.store.transaction() as db:
            for identity in ("demo", "other"):
                self.store.put(db, "companies", identity, 0, {"name": identity + "社"})
            self.store.put(db, "company_links", "demo:mypage", 0,
                           {"company_id": "demo", "type": "MyPage", "label": "マイページ", "url": LINK})

    def active(self, text, thread="company:demo"):
        self.submitted = getattr(self, "submitted", 0) + 1
        self.coach.submit({"text": text, "operation_id": "mypage-request-" + str(self.submitted), "thread_id": thread}, "phone")
        self.run = self.coach.claim()
        self.metadata = {"task_id": self.run["id"], "session_id": "career-run:" + self.run["id"]}
        statement = self.coach.snapshot("phone", thread_id=thread)["messages"][-1]
        self.proof = {"message_id": statement["id"], "quote": statement["text"]}

    def request(self, **changes):
        args = dict(action="mypage_connection", company_id="demo", enabled=True, expected_version=0, user_statement=self.proof)
        args.update(changes)
        return self.tools.dispatch("career_app", args, self.metadata)

    def app_save(self, version, url=LINK, enabled=True):
        return self.records.configure("demo", "phone", {"company_id": "demo", "url": url, "login_id": "synthetic-id",
                                                         "password": "synthetic-password", "enabled": enabled,
                                                         "expected_version": version})

    def connections(self):
        return self.tools.dispatch("career_app", {"action": "connections"}, self.metadata)["mypage_connections"]

    def test_registered_link_becomes_pending_until_person_enters_login_in_app(self):
        self.active("デンソーのマイページ自動巡回を、登録済みのリンクで設定して")
        before = self.connections()
        self.assertEqual([{"company_id": "demo", "name": "demo社", "connection_version": 0, "portal_url": None,
                           "enabled": False, "verified": False, "credentials_saved": False, "state": "not_started",
                           "closed": False, "registered_mypage_urls": [LINK], "pending_request": None}], before)
        first = self.request()
        self.assertEqual(first, self.request())
        self.assertFalse(first["saved"])
        self.assertTrue(first["credentials_required"])
        self.assertEqual("company_link", first["url_source"])
        self.assertIsNone(self.records.get(connection_id("demo", "phone")))
        self.assertEqual(LINK, MyPageStore(self.store).view("demo", "phone")["pending_request"]["portal_url"])
        readiness = CompanyViews(self.store).detail("demo", "phone")["mypage_readiness"]
        self.assertEqual("Appのマイページ設定でログインID・パスワードを入力", readiness["next_action"])
        self.assertEqual([], self.reconciled)
        row = self.app_save(0)
        self.assertEqual(1, row["version"])
        self.assertEqual(LINK, row["config"]["url"])
        self.assertIsNone(MyPageStore(self.store).view("demo", "phone")["pending_request"])
        self.assertNotIn(b"synthetic-password", self.store.path.read_bytes())
        self.assertTrue(self.connections()[0]["credentials_saved"])

    def test_explicit_url_must_be_in_the_persons_words(self):
        self.active("接続URLは " + OTHER + " にして自動確認をオンに")
        result = self.request(url=OTHER)
        self.assertEqual(OTHER, result["pending_request"]["portal_url"])
        self.assertEqual("user_statement", result["url_source"])
        self.active("接続URLを変えて")
        with self.assertRaises(CareerError) as error:
            self.request(url=OTHER)
        self.assertEqual("setting_quote_required", error.exception.code)
        with self.assertRaises(CareerError) as error:
            self.request(url="https://user:pw@portal.example.test/login")
        self.assertEqual("invalid_portal_url", error.exception.code)

    def test_credentials_are_refused_before_any_receipt(self):
        self.active("登録済みのリンクで巡回を設定して")
        for key in ("password", "login_id"):
            with self.assertRaises(CareerError) as error:
                self.request(**{key: "synthetic-secret-value"})
            self.assertEqual("credentials_app_only", error.exception.code)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM coach_tool_operations").fetchone()[0])
        self.assertNotIn(b"synthetic-secret-value", self.store.path.read_bytes())
        registered = {}

        class Context:
            def register_tool(self, **spec):
                registered[spec["name"]] = spec
        register_tools(Context(), lambda: self.store)
        schema = registered["career_app"]["schema"]["parameters"]
        self.assertIn("mypage_connection", schema["properties"]["action"]["enum"])
        self.assertNotIn("password", schema["properties"])
        self.assertFalse(schema["additionalProperties"])
        refused = json.loads(registered["career_app"]["handler"](
            {"action": "mypage_connection", "company_id": "demo", "password": "x"}, **self.metadata))
        self.assertEqual("credentials_app_only", refused["error"])

    def test_same_url_toggle_saves_immediately_and_keeps_sealed_secret(self):
        row = self.app_save(0)
        self.active("demo社のマイページ自動確認を止めて")
        result = self.request(enabled=False, expected_version=1)
        self.assertTrue(result["saved"])
        self.assertEqual(2, result["connection_version"])
        latest = self.records.get(row["id"])
        self.assertFalse(latest["config"]["enabled"])
        self.assertEqual(row["config"]["secret_ref"], latest["config"]["secret_ref"])
        self.assertEqual([2], self.reconciled)
        unchanged = self.request(enabled=False, expected_version=1)
        self.assertEqual(result, unchanged)
        with self.assertRaises(CareerError) as error:
            self.app_save(1)
        self.assertEqual("version_conflict", error.exception.code)
        self.active("demo社のマイページ自動確認をまた止めて")
        with self.assertRaises(CareerError) as error:
            self.request(enabled=False, expected_version=1)
        self.assertEqual("version_conflict", error.exception.code)
        same = self.request(enabled=False, expected_version=2)
        self.assertFalse(same["changed"])

    def test_url_change_keeps_live_connection_until_app_save_and_expires_on_new_version(self):
        row = self.app_save(0)
        self.active("接続URLを " + OTHER + " に変えて")
        result = self.request(url=OTHER, expected_version=1)
        self.assertTrue(result["pending"])
        self.assertIn("引き継げません", result["next_step"])
        self.assertEqual(row, self.records.get(row["id"]))
        self.assertEqual(OTHER, self.connections()[0]["pending_request"]["portal_url"])
        stop_company(self.store, "demo")
        self.assertIsNone(self.connections()[0]["pending_request"])

    def test_scope_daily_closed_and_ambiguous_links(self):
        self.active("登録済みのリンクで巡回を設定して")
        with self.assertRaises(CareerError) as error:
            self.request(company_id="other")
        self.assertEqual("write_scope_conflict", error.exception.code)
        self.active("other社の巡回を登録済みのリンクで設定して", thread="global")
        with self.assertRaises(CareerError) as error:
            self.request(company_id="other")
        self.assertEqual("mypage_url_required", error.exception.code)
        with self.store.transaction() as db:
            self.store.put(db, "company_links", "demo:mypage2", 0,
                           {"company_id": "demo", "type": "MyPage", "label": "別", "url": OTHER})
        with self.assertRaises(CareerError) as error:
            self.request()
        self.assertEqual("mypage_url_required", error.exception.code)
        with self.store.transaction() as db:
            company = self.store.entity(db, "companies", "other")
            self.store.put(db, "companies", "other", company["version"], dict(company["data"], user_stage="closed"))
        self.active("other社の巡回を " + OTHER + " で設定して", thread="global")
        with self.assertRaises(CareerError) as error:
            self.request(company_id="other", url=OTHER)
        self.assertEqual("company_closed", error.exception.code)
        with self.store.transaction() as db:
            db.execute("UPDATE coach_runs SET kind='daily' WHERE id=?", (self.run["id"],))
        with self.assertRaises(CareerError) as error:
            self.request(company_id="other", url=OTHER)
        self.assertEqual("daily_scope", error.exception.code)


if __name__ == "__main__":
    unittest.main()
