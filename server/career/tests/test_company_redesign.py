"""Company redesign contract fixtures; no live portal, mailbox or inference calls."""
import tempfile
import unittest
from unittest.mock import Mock, patch
from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router

from career_core.collection_store import CollectionStore, PILOT, PILOT_ROOT
from career_core.company_views import CompanyViews
from career_core.conversations import accepted_sources
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools
from career_core.coach_worker import CoachWorker
from career_core.mail_store import MailStore
from career_core.mypage import MyPageStore, deadlines
from career_core.mypage_reader import HOME, LOGIN, notice_request_allowed
from career_core.store import CareerError, Store
from coach_fixture import configure_execution


class CompanyRedesignTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.collection = CollectionStore(self.store)
        self.grant = self.collection.configure_pilot(None, enabled=False)
        self.company = self.grant["company_id"]
        self.pages = MyPageStore(self.store)
        self.mail = MailStore(self.store)
        configure_execution(self)

    def reader(self, text="ES提出締切 2026年9月30日", key="notice-42"):
        return Mock(fetch=Mock(return_value={"authenticated": True, "recognized": True, "notices": [
            {"key": key, "title": "応募のご案内", "body": text, "url": HOME}]}))

    def test_repeated_notice_and_changed_version_keep_one_card_and_history(self):
        self.pages.collect(self.company, reader=self.reader())
        self.pages.collect(self.company, reader=self.reader())
        first = self.pages.view(self.company)
        self.assertEqual((1, 1), (len(first["notices"]), len(first["history"])))
        self.pages.collect(self.company, reader=self.reader("提出締切 2026年10月1日"))
        updated = self.pages.view(self.company)
        self.assertEqual((1, 2), (len(updated["notices"]), len(updated["history"])))
        self.assertIn("10月1日", updated["notices"][0]["excerpt"])
        self.assertEqual(3, len(updated["fetch_history"]))
        self.assertFalse(self.collection.grant(PILOT, require_enabled=False)["enabled"])

    def test_distinct_upstream_ids_do_not_collapse_same_title(self):
        reader = self.reader(key="one")
        reader.fetch.return_value["notices"] += self.reader(key="two").fetch.return_value["notices"]
        self.pages.collect(self.company, reader=reader)
        self.assertEqual(2, len(self.pages.view(self.company)["notices"]))

    def test_failure_retains_cache_and_last_success_and_unknown_does_not_become_empty(self):
        self.assertEqual("not_started", self.pages.status(self.company)["state"])
        self.pages.collect(self.company, reader=self.reader())
        previous = self.pages.status(self.company)["last_success_at"]
        with self.assertRaises(CareerError):
            self.pages.collect(self.company, reader=Mock(fetch=Mock(side_effect=RuntimeError("sensitive error"))))
        result = self.pages.view(self.company)
        self.assertEqual("failed", result["status"]["state"])
        self.assertEqual(previous, result["status"]["last_success_at"])
        self.assertEqual(1, len(result["notices"]))
        self.assertNotIn("sensitive", str(result))

    def test_recognized_empty_differs_from_structure_failure(self):
        with self.assertRaises(CareerError):
            self.pages.collect(self.company, reader=Mock(fetch=Mock(return_value={"authenticated": True, "recognized": False})))
        self.assertNotIn("last_success_at", self.pages.status(self.company))
        self.pages.collect(self.company, reader=Mock(fetch=Mock(return_value={"authenticated": True, "recognized": True, "notices": []})))
        self.assertIn("last_success_at", self.pages.status(self.company))

    def test_source_views_exclude_public_research_mail_and_old_es_evidence(self):
        with self.store.transaction() as db:
            for kind in ("public", "mail", "portal"):
                self.collection.save_evidence(db, source_key=kind, company_id=self.company, kind=kind,
                    title=kind, locator=HOME, text=kind)
        self.pages.collect(self.company, reader=self.reader())
        result = CompanyViews(self.store).detail(self.company)
        self.assertEqual(["mypage"], [n["kind"] for n in result["mypage"]["notices"]])
        self.assertEqual([], result["mail"])
        self.assertEqual([], result["offers"])

    def test_reading_and_unsupported_company_never_invoke_es_reader(self):
        with patch("career_core.portal_reader.AxolReader.fetch", side_effect=AssertionError("ES forbidden")):
            for _ in range(10):
                CompanyViews(self.store).detail(self.company)
            self.pages.collect(self.company, reader=self.reader())
            with self.assertRaises(CareerError):
                self.pages.collect("another-company", reader=self.reader())

    def test_deadline_precision_multiple_kinds_and_unknown_year(self):
        result = deadlines("ES提出締切 2026年9月30日\nSPI受検期限 2026/10/1 18:00 JST\n予約締切 10月2日")
        self.assertEqual(["date", "minute", "unknown"], [d["precision"] for d in result])
        self.assertIsNone(result[0]["time"])
        self.assertIsNone(result[0]["timezone"])
        self.assertEqual("Asia/Tokyo", result[1]["timezone"])

    def test_notice_request_allowlist_blocks_es_apply_and_nonlogin_posts(self):
        self.assertTrue(notice_request_allowed(HOME, "GET", True))
        self.assertTrue(notice_request_allowed(LOGIN, "POST", True, True))
        for url in (PILOT_ROOT + "com/21", PILOT_ROOT + "submit", HOME + "?next=submit", "https://evil.example/a.js"):
            self.assertFalse(notice_request_allowed(url, "GET", True))
        self.assertFalse(notice_request_allowed(HOME, "POST", True, True))
        self.assertFalse(notice_request_allowed(LOGIN, "POST", True, False))

    def save_mail(self, account, company=None):
        with self.store.transaction() as db:
            self.mail.save_message(db, account, company or self.company, {"id": "same-id", "sender_address": "hr@example.com",
                "subject": account, "text": "説明会のお知らせ", "locator": "https://mail.google.com/mail/#all/same-id", "received_at": "2026-09-15T00:00:00Z"})

    def test_account_message_identity_and_per_company_success_isolation(self):
        self.save_mail("account-a")
        self.save_mail("account-b")
        self.assertEqual({"account-a", "account-b"}, {m["account_id"] for m in self.mail.messages(self.company)})
        self.mail.update_status("updated", success=True, company_ids=["other"])
        self.mail.update_status("failed", company_ids=[self.company])
        status = self.mail.status(self.company)
        self.assertIsNone(status.get("last_success_at"))
        self.assertFalse(status["fresh"])

    def test_handoff_rejects_other_company_and_freezes_selected_body(self):
        self.save_mail("account-a")
        self.save_mail("other-account", "other-company")
        target = {"kind": "companies", "id": self.company, "source_refs": [{"kind": "mail", "id": "same-id", "account_id": "account-a"}]}
        coach = CoachStore(self.store)
        result = coach.submit({"operation_id": "handoff-source-1", "text": "この案内について相談", "thread_id": "company:" + self.company, "target": target})
        run = coach.run(result["id"])
        self.assertEqual("説明会のお知らせ", run["request"]["selected_sources"][0]["body"])
        with self.store.connect() as db, self.assertRaises(CareerError):
            accepted_sources(db, {"company_id": self.company}, [{"kind": "mail", "id": "same-id", "account_id": "other-account"}])
        claimed = coach.claim()
        context = CoachTools(coach).context({}, claimed)
        self.assertEqual(run["request"]["selected_sources"], context["company"]["selected_sources"])

    def test_automatic_research_never_fetches_public_pages(self):
        coach = CoachStore(self.store)
        job = coach.submit({"operation_id": "daily-private", "text": "保存済みの情報を整理"}, kind="daily")
        runtime = Mock(run=Mock(return_value="変更はありません。"))
        with patch("career_core.research.research", side_effect=AssertionError("public forbidden")), patch("career_core.api.sync_job", return_value={"state": "unsynced", "reason": "synthetic-offline"}):
            self.assertTrue(CoachWorker(self.store, runtime_factory=lambda _: runtime).one())
        self.assertEqual("completed", coach.run(job["id"])["state"])

    def test_preferences_require_session_and_preserve_other_company_fields_and_version(self):
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def fixture_session(request, call_next):
            if request.headers.get("x-test-session") == "yes":
                request.state.session = {"test": True}
            return await call_next(request)

        app.include_router(create_router(lambda: self.store), prefix="/career")
        with TestClient(app) as client:
            payload = {"company_id": self.company, "expected_version": 1, "priority": "high", "stage": "preparing"}
            self.assertEqual(401, client.post("/career/collection/company-preferences", json=payload).status_code)
            response = client.post("/career/collection/company-preferences", json=payload, headers={"x-test-session": "yes"})
            self.assertEqual(200, response.status_code)
            self.assertEqual("high", response.json()["company"]["data"]["user_priority"])
            self.assertEqual("日鉄エンジニアリング", response.json()["company"]["data"]["name"])
            self.assertEqual(409, client.post("/career/collection/company-preferences", json=payload, headers={"x-test-session": "yes"}).status_code)

    def test_interrupted_collection_is_not_reported_as_running_forever(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, "mypage:status:" + self.company, {"state": "running", "lease_until": "2020-01-01", "last_success_at": "2019-12-31"})
        self.assertEqual("failed", self.pages.status(self.company)["state"])
        self.assertEqual("2019-12-31", self.pages.status(self.company)["last_success_at"])

