"""Synthetic notifications: eligibility, immutable evidence, cursors and privacy."""
import tempfile
import unittest
from unittest.mock import patch

from career_core.collection_store import CollectionStore
from career_core.mypage_bot import connection_id
from career_core.notice_policy import classify
from career_core.store import CareerError, Store


class NoticePolicyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.collection = CollectionStore(self.store)
        with self.store.transaction() as db:
            for company in ("a", "b"):
                self.store.put(db, "companies", company, 0, {"name": "企業" + company})

    def source(self, key="mypage:one", title="ESのご提出をお願いします", body="ESを提出してください。提出期限 2026年10月1日 18:00 JST", kind="source_changed", company="a", received=None, prefix=False):
        with self.store.transaction() as db:
            if key.startswith("mypage:"):
                # Legacy portal fixtures need an explicit owning grant, just like production.
                db.execute("INSERT OR IGNORE INTO career_collection_grants VALUES(?,?,?,?,?,?,?)",
                           (key.split(":", 2)[1], company, None, "synthetic-portal", 0, "default", "synthetic"))
            identity, fresh = self.collection.save_evidence(db, source_key=key, company_id=company, kind="mail" if key.startswith("gmail:") else "mypage", 
                title=title, locator="https://example.test/source", text=body, occurred_at=received)
            if fresh:
                self.collection.notice(db, ("source:" if prefix else "") + identity, company, None, kind, title)
        return ("source:" if prefix else "") + identity

    def eligible(self, **kwargs):
        return [n for n in self.collection.notices(**kwargs)["notices"] if n["eligible"]]

    def test_classification_and_future_results(self):
        cases = [("説明会のご案内", "ぜひご参加ください", None),
                 ("ES提出", "ESを提出してください", "action"),
                 ("面接", "面接の日程予約をお願いします", "action"),
                 ("提出期限を延長しました", "", "action"),
                 ("選考結果のお知らせ", "マイページをご確認ください", "result"),
                 ("選考結果について", "選考結果は後日通知します", None),
                 ("説明会", "選考結果は追ってお知らせする予定です", None)]
        for title, body, expected in cases:
            with self.subTest(title=title):
                value = classify(title, body)
                self.assertEqual(expected, value[0] if value else None)

    def test_content_names_quote_and_deadline_are_original(self):
        identity = self.source()
        notice = self.eligible()[0]
        self.assertEqual("企業a", notice["company_name"])
        self.assertEqual("ESを提出してください", notice["quote"])
        self.assertIn("2026年10月1日 18:00 JST", notice["deadline_text"])
        self.assertIn("ESを提出", self.collection.notice_content(identity, "default")["body"])

    def test_initial_and_general_sources_are_kept_but_not_notified(self):
        self.source(kind="initial_source")
        self.source(key="mypage:other", title="説明会のお知らせ", body="ご参加お待ちしています")
        self.assertEqual([], self.eligible())
        self.assertEqual(2, len(self.collection.evidence("a")))

    def test_repeat_is_deduplicated_and_changed_body_is_a_new_version(self):
        self.source()
        self.source()
        self.assertEqual(1, len(self.eligible()))
        self.source(body="ESを提出してください。提出期限を延長しました。2026年10月3日")
        self.assertEqual(2, len(self.eligible()))

    def test_mail_baseline_survives_pages_and_is_per_company_account(self):
        with patch("career_core.collection_store.now", return_value="2026-09-01T00:00:00+00:00"):
            with self.store.transaction() as db:
                self.collection.begin_mail_notices(db, "a", "account")
            self.source(key="gmail:account:first", kind="initial_source", received="2026-08-31T23:00:00+00:00", prefix=True)
        self.source(key="gmail:account:page2", received="2026-08-30T00:00:00+00:00", prefix=True)
        self.source(key="gmail:account:new", received="2026-09-02T00:00:00+00:00", prefix=True)
        self.source(key="gmail:account:other-company", company="b", received="2026-09-02T00:00:00+00:00", prefix=True)
        self.assertEqual(1, len(self.eligible()))
        self.assertEqual("a", self.eligible()[0]["company_id"])

    def test_unknown_received_time_cannot_replay_history(self):
        self.source(key="gmail:account:unknown", received=None)
        self.assertEqual([], self.eligible())

    def test_auth_is_once_until_recovery_and_profile_scoped(self):
        for _ in range(3):
            with self.store.transaction() as db:
                self.collection.attention(db, "connection", "a", True, profile="phone")
        self.assertEqual(1, len(self.eligible(profile="phone")))
        self.assertEqual([], self.eligible(profile="desktop"))
        with self.store.transaction() as db:
            self.collection.attention(db, "connection", "a", False, profile="phone")
        self.assertEqual([], self.eligible(profile="phone"))
        with self.store.transaction() as db:
            self.collection.attention(db, "connection", "a", True, profile="phone")
        self.assertEqual(1, len(self.eligible(profile="phone")))
        self.assertEqual(3, len(self.collection.notices(profile="phone")["notices"]))

    def test_filtered_page_advances_cursor_and_normalizes_source_prefix(self):
        key = "mypage-bot:" + connection_id("a", "phone") + ":"
        for i in range(101):
            self.source(key=key + str(i), prefix=True)
        first = self.collection.notices(profile="desktop")
        self.assertEqual([], first["notices"])
        self.assertTrue(first["more"])
        self.assertEqual(100, first["cursor"])
        second = self.collection.notices(after=first["cursor"], profile="desktop")
        self.assertEqual(101, second["cursor"])
        self.assertFalse(second["more"])
        identity = self.collection.notices(profile="phone")["notices"][0]["id"]
        with self.assertRaises(CareerError):
            self.collection.notice_content(identity, "desktop")

    def test_upgrade_baseline_skips_backlog_and_legacy_rows_are_ineligible(self):
        with self.store.transaction() as db:
            self.collection.notice(db, "legacy", "a", None, "failed", "確認してください")
        self.assertFalse(self.collection.notices()["notices"][0]["eligible"])
        self.assertEqual({"notices": [], "cursor": 1, "more": False}, self.collection.notices(baseline=True))
        self.source()
        self.assertEqual(1, len(self.eligible(after=1)))

    def test_reopen_preserves_feed_and_transaction_failure_does_not_consume_seq(self):
        self.source()
        with self.assertRaises(RuntimeError):
            with self.store.transaction() as db:
                self.collection.attention(db, "broken", "a", True)
                raise RuntimeError("synthetic")
        reopened = CollectionStore(Store(self.tmp.name))
        self.assertEqual(1, reopened.notices()["cursor"])
        self.assertEqual(1, len(reopened.notices()["notices"]))

    def test_deadline_on_next_line_retains_original_precision(self):
        self.source(body="ESを提出してください。\n提出期限\n2026年10月1日 18:00")
        self.assertIn("提出期限\n2026年10月1日 18:00", self.eligible()[0]["deadline_text"])

    def test_credentials_and_links_do_not_enter_preview(self):
        self.source(body="ESを提出してください。\nログインID: synthetic-secret\nhttps://example.test/?token=synthetic-token")
        notice = self.eligible()[0]
        self.assertNotIn("synthetic-secret", str(notice))
        self.assertNotIn("synthetic-token", str(notice))

    def test_api_feed_baseline_and_exact_content_contract(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from career_core.api import create_router
        identity = self.source()
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test") == "yes":
                request.state.session = {"test": True}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store))
        client = TestClient(app)
        self.addCleanup(client.close)
        self.assertEqual(401, client.get("/collection/notices").status_code)
        client.headers["x-test"] = "yes"
        feed = client.get("/collection/notices", params={"profile": "default"})
        self.assertEqual(200, feed.status_code)
        self.assertTrue(feed.json()["notices"][0]["eligible"])
        baseline = client.get("/collection/notices", params={"baseline": "true"})
        self.assertEqual({"notices": [], "cursor": 1, "more": False}, baseline.json())
        content = client.get("/collection/notices/" + identity)
        self.assertEqual(200, content.status_code)
        self.assertEqual("a", content.json()["company_id"])
        self.assertIn("ESを提出", content.json()["body"])
        self.assertEqual(404, client.get("/collection/notices/missing").status_code)
