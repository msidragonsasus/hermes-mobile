"""Synthetic Home contracts. No mailbox, browser, model or production calls."""
import json
import tempfile
import unittest
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router
from career_core.collection_store import CollectionStore
from career_core.home import HomeView, classify, deadline
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store, encoded


class HomeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(self.temp.name)
        self.mail = MailStore(self.store)
        self.collection = CollectionStore(self.store)
        self.clock = lambda: datetime(2026, 9, 16, 3, tzinfo=timezone.utc)
        self.view = HomeView(self.store, self.clock)
        self.put("companies", "c", name="テスト社", user_priority="high")
        with self.store.transaction() as db:
            self.store.set_meta(db, "mail:settings", {"enabled": True, "account_id": "account"})

    def put(self, kind, identity, **data):
        with self.store.transaction() as db:
            old = self.store.entity(db, kind, identity)
            self.store.put(db, kind, identity, old["version"] if old else 0, data)

    def message(self, identity="m", subject="選考結果のご案内", body="詳細をご確認ください。"):
        with self.store.transaction() as db:
            db.execute("INSERT OR REPLACE INTO career_mail_messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       ("account", identity, "thread", "c", "test@example.org", "テスト社", subject, "", body,
                        "https://mail.google.com/", "2026-09-16T00:00:00+00:00", 1, "2026-09-16", "2026-09-16"))

    def items(self):
        return self.view.read()["items"]

    def test_future_result_promises_are_not_delivered_results(self):
        self.message(subject="説明会のお知らせ", body="選考結果は後日ご連絡します。")
        self.assertEqual([], self.items())
        self.message(subject="選考結果のご連絡までお待ちください", body="")
        self.assertEqual([], self.items())

    def test_exact_reader_preserves_full_subject_for_content_read_receipts(self):
        subject = "選考結果のご案内" + "保存済み件名" * 80
        self.message(subject=subject, body="詳細本文")
        item = self.items()[0]
        content = self.view.content(item["id"], item["version"])
        import hashlib
        self.assertEqual(subject, content["title"])
        self.assertEqual(item["content_hash"], hashlib.sha256((content["title"] + "\n" + content["body"]).encode("utf-8")).hexdigest())

    def test_interest_window_precision_and_closed_exclusions(self):
        self.put("offers", "o", company_id="c", title="応募", deadline="2026-09-18")
        item = self.items()[0]
        self.assertEqual("2026-09-18", item["deadline"])
        self.assertTrue(item["urgent"])
        self.put("companies", "c", name="テスト社", user_priority="low")
        self.assertEqual([], self.items())
        self.put("applications", "a", company_id="c", offer_id="o", status="planned")
        self.assertEqual(1, len(self.items()))
        self.put("offers", "o", company_id="c", title="応募", deadline="2026-09-18", decision="dismiss")
        self.assertEqual([], self.items())

    def test_overdue_tasks_are_hidden_but_not_completed_and_unconfirmed_stay_hidden(self):
        self.put("tasks", "t", company_id="c", title="提出", deadline="2026-09-15", status="todo")
        self.assertEqual([], self.items())
        with self.store.connect() as db:
            self.assertEqual("todo", self.store.entity(db, "tasks", "t")["data"]["status"])
        self.put("tasks", "t", company_id="c", deadline="2026-09-15", status="done")
        self.assertEqual([], self.items())
        for state in ("unconfirmed", "conflict"):
            self.put("offers", "o", company_id="c", deadline="2026-09-18", deadline_verification=state)
            self.assertEqual([], self.items())
        self.assertIsNone(deadline("9/18"))
        self.assertIsNone(deadline("2026-99-18"))

    def test_exact_time_expires_at_boundary_and_date_only_at_japanese_midnight(self):
        self.put("tasks", "exact", company_id="c", title="exact", deadline="2026-09-16T12:00+09:00", status="todo")
        self.put("tasks", "date", company_id="c", title="date", deadline="2026-09-16", status="todo")
        self.assertEqual(["2026-09-16"], [i["deadline"] for i in self.items()])
        self.view.clock = lambda: datetime(2026, 9, 16, 2, 59, 59, tzinfo=timezone.utc)
        self.assertEqual(2, len(self.items()))
        self.view.clock = lambda: datetime(2026, 9, 16, 14, 59, 59, tzinfo=timezone.utc)
        self.assertEqual(["2026-09-16"], [i["deadline"] for i in self.items()])
        self.view.clock = lambda: datetime(2026, 9, 16, 15, tzinfo=timezone.utc)
        self.assertEqual([], self.items())

    def test_upcoming_actions_sort_by_instant_unknown_dates_remain_and_storage_is_unchanged(self):
        self.put("tasks", "late", company_id="c", title="late", deadline="2026-09-16T14:00+09:00")
        self.put("tasks", "near", company_id="c", title="near", deadline="2026-09-16T04:00Z")
        self.message(subject="面接予約をお願いします", body="予約期限：調整中")
        with self.store.connect() as db:
            before = list(db.iterdump())
        items = self.items()
        self.assertEqual(["2026-09-16T04:00Z", "2026-09-16T14:00+09:00", None], [i["deadline"] for i in items])
        with self.store.connect() as db:
            self.assertEqual(before, list(db.iterdump()))

    def test_expired_mail_is_hidden_without_resolving_or_deleting_it(self):
        self.message(subject="エントリーシート提出のお願い", body="提出期限：2026年9月15日 15時まで")
        with self.store.connect() as db:
            before = list(db.iterdump())
        self.assertEqual([], self.items())
        with self.store.connect() as db:
            self.assertEqual(before, list(db.iterdump()))
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM career_mail_messages").fetchone()[0])

    def test_explicit_hour_only_deadline_keeps_time_without_fabricating_relative_date(self):
        from career_core.action_classification import exact_deadlines
        self.assertEqual({"2026-09-16T15:00"}, exact_deadlines("応募期限：2026年9月16日 15時まで"))
        self.assertEqual(set(), exact_deadlines("最終案内 秋2Days 応募締切は本日15時まで"))
        self.message(subject="最終案内 秋2Days ES提出をお願いします 応募締切は本日15時まで", body="原文で締切をご確認ください。")
        item = self.items()[0]
        self.assertIsNone(item["deadline"])
        self.assertFalse(item["overdue"])
        self.message(subject="ES提出をお願いします", body="応募期限：2026年9月16日 15時まで")
        self.assertEqual("2026-09-16T15:00", self.items()[0]["deadline"])
        self.view.clock = lambda: datetime(2026, 9, 16, 6, tzinfo=timezone.utc)
        self.assertEqual([], self.items())

    def test_generated_deadline_is_one_item_but_distinct_tasks_are_not_merged(self):
        self.put("offers", "o", company_id="c", deadline="2026-09-18")
        self.put("applications", "a", company_id="c", offer_id="o", deadline="2026-09-18", deadline_source="offer")
        self.put("tasks", "t", company_id="c", application_id="a", deadline="2026-09-18", deadline_source="offer")
        self.assertEqual(1, len(self.items()))
        self.put("tasks", "other", company_id="c", title="別の提出物", deadline="2026-09-18")
        self.assertEqual(2, len(self.items()))

    def test_completed_application_does_not_reappear_as_a_priority_company_offer(self):
        self.put("offers", "o", company_id="c", deadline="2026-09-18")
        self.put("applications", "a", company_id="c", offer_id="o", status="submitted")
        self.assertEqual([], self.items())

    def test_mail_rules_do_not_infer_outcome_or_include_general_news(self):
        self.message()
        self.message("news", "説明会のお知らせ", "企業説明会を開催します。")
        self.assertEqual(1, len(self.items()))
        self.assertEqual("選考結果のご案内", self.items()[0]["title"])
        self.assertNotIn("body", self.items()[0])
        self.assertEqual(("action", "面接・面談の予約案内"), classify("面接予約をお願いします", ""))
        self.assertEqual(("action", "提出・回答の依頼"), classify("書類の提出をお願いします", ""))

    def test_future_action_is_held_unknown_date_is_explicit_and_read_is_not_completion(self):
        self.message(subject="面接予約をお願いします", body="予約期限：2026年10月1日")
        self.assertEqual([], self.items())
        self.message(subject="面接予約をお願いします", body="予約期限：9月18日")
        item = self.items()[0]
        self.assertIsNone(item["deadline"])
        self.assertIn("元の案内で確認", item["detail"])
        self.view.content(item["id"], item["version"])
        self.assertEqual(1, len(self.items()))
        self.view.resolve_source(item["id"], item["version"])
        self.assertEqual([], self.items())
        self.assertEqual(1, len(self.view.read("other")["items"]))
        self.message(subject="面接予約をお願いします", body="予約期限：9月19日")
        self.assertEqual(1, len(self.items()))

    def test_versions_are_stable_and_changed_content_cannot_mark_old_version_read(self):
        self.message()
        item = self.items()[0]
        with self.store.transaction() as db:
            db.execute("UPDATE career_mail_messages SET observed_at='tomorrow'")
        self.assertEqual(item["version"], self.items()[0]["version"])
        self.message(body="更新された結果です。")
        with self.assertRaises(CareerError) as error:
            self.view.content(item["id"], item["version"])
        self.assertEqual(409, error.exception.status)

    def test_failure_is_not_empty_and_recovered_errors_disappear(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, "mail:status", {"state": "auth_required", "last_success_at": "yesterday"})
        self.assertEqual("settings", self.items()[0]["target"]["kind"])
        with self.store.transaction() as db:
            self.store.set_meta(db, "mail:status", {"state": "failed", "last_success_at": "yesterday"})
        self.assertEqual("failed", self.view.read()["coverage"][0]["state"])
        with self.store.transaction() as db:
            self.store.set_meta(db, "mail:status", {"state": "updated", "last_success_at": "today"})
        self.assertEqual([], self.items())
        self.assertEqual("updated", self.view.read()["coverage"][0]["state"])

    def test_keyed_pagination_and_exact_source_beyond_first_hundred(self):
        for n in range(105):
            self.message(str(n).zfill(3))
        first = self.view.read()
        self.assertEqual(100, len(first["items"]))
        second = self.view.read(cursor=first["next_cursor"])
        self.assertEqual(5, len(second["items"]))
        self.assertIsNone(second["next_cursor"])
        item = second["items"][-1]
        self.assertTrue(self.view.content(item["id"], item["version"])["body"])
        self.message("new")
        with self.assertRaises(CareerError) as error:
            self.view.read(cursor=first["next_cursor"])
        self.assertEqual("home_changed", error.exception.code)

    def test_read_does_not_write_database_or_start_workers(self):
        self.message()
        with self.store.connect() as db:
            before = list(db.iterdump())
        self.view.read()
        with self.store.connect() as db:
            self.assertEqual(before, list(db.iterdump()))

    def test_only_manual_results_for_the_current_profile_are_contributed(self):
        from career_core.coach_store import CoachStore
        CoachStore(self.store)
        with self.store.transaction() as db:
            for identity, profile, kind in [("manual", "default", "message"), ("research", "default", "research"),
                                            ("daily", "default", "daily"), ("private", "other", "message")]:
                db.execute("INSERT INTO coach_runs(id,profile,kind,state,request,result,created_at,updated_at,thread_id) VALUES(?,?,?,'completed','{}','{}',?,?,?)",
                           (identity, profile, kind, "2026-09-16", "2026-09-16", "global"))
                db.execute("INSERT INTO coach_messages(id,profile,run_id,role,text,created_at) VALUES(?,?,?,'assistant',?,?)",
                           ("reply:" + identity, profile, identity, "合成テストの回答", "2026-09-16"))
        self.assertEqual({"reply:manual", "reply:research"}, {i["id"] for i in self.items()})
        item = next(i for i in self.items() if i["id"] == "reply:manual")
        self.assertEqual("合成テストの回答", self.view.content(item["id"], item["version"])["body"])
        with self.assertRaises(CareerError):
            self.view.content(item["id"], item["version"], "other")

    def test_api_requires_dashboard_session_and_validates_cursor(self):
        app = FastAPI()
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        with TestClient(app) as client:
            self.assertEqual(401, client.get("/api/plugins/career/home").status_code)
        app = FastAPI()
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            request.state.session = {"user": "fixture"}
            return await call_next(request)

        with TestClient(app) as client:
            self.assertEqual(200, client.get("/api/plugins/career/home").status_code)
            self.assertEqual(400, client.get("/api/plugins/career/home?cursor=bad").status_code)


if __name__ == "__main__":
    unittest.main()
