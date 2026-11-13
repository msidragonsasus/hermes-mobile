"""Synthetic tests only: no credentials, mailbox, browser or model calls."""
import json
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime, timedelta
from typing import Any

from fastapi import FastAPI
from fastapi import HTTPException
from fastapi.testclient import TestClient

from today_core.api import create_router
from today_core.sources import manaba_read_url
from today_core.store import JST, PlanError, PlanStore


class PlanTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.current = datetime(2026, 10, 23, 10, tzinfo=JST)
        self.store = PlanStore(self.temp.name, lambda: self.current)
        self.settings = dict(email="test@example.org", manaba_url="https://university.example/ct/home", enabled=True)
        self.store.configure(self.settings)

    def inputs(self, gmail="updated", manaba="updated") -> list[dict[str, Any]]:
        return [dict(id=identity, label=identity, state=state, checked_at=self.current.isoformat(),
                     day=self.current.date().isoformat(), records=[dict(id=identity + ":one", source=identity,
                         url="https://example.org/" + identity, title="元情報", text="本人の必須個人課題。2026-10-23 17:00まで、未提出",
                         checked_at=self.current.isoformat(), retrieved_in_current_run=True)]
                     if state in {"updated", "partial"} else []) for identity, state in [("gmail", gmail), ("manaba", manaba)]]

    def answer(self, sources=("gmail", "manaba")) -> dict[str, Any]:
        return dict(tasks=[dict(source_id=s + ":one", title="課題を提出する", reason="今日が締切", deadline="2026-10-23", priority=1,
                                eligibility=dict(kind="coursework", source_id=s + ":one", quote="本人の必須個人課題。",
                                                 user_intent="confirmed", action_status="pending", deadline_policy="remains_actionable")) for s in sources])

    def publish(self, request="one", inputs=None, answer=None):
        self.assertTrue(self.store.begin(request))
        self.store.finish(request, inputs or self.inputs(), answer or self.answer())

    def test_duplicate_requests_and_automatic_day_do_not_repeat_model_work(self):
        self.assertTrue(self.store.begin("auto", automatic=True))
        self.assertFalse(self.store.begin("auto", automatic=True))
        self.assertFalse(self.store.begin("other"))
        self.store.fail("auto")
        self.assertFalse(self.store.begin("another-auto", automatic=True))
        self.assertTrue(self.store.begin("manual"))

    def test_partial_source_retains_previous_tasks_as_stale(self):
        self.publish()
        self.publish("two", self.inputs(gmail="failed"), self.answer(("manaba",)))
        snapshot = self.store.snapshot()
        self.assertEqual(2, len(snapshot["tasks"]))
        self.assertTrue(next(t for t in snapshot["tasks"] if t["source"] == "gmail")["stale"])
        self.assertEqual("failed", snapshot["coverage"][0]["state"])
        self.publish("three", self.inputs(gmail="partial"), self.answer(("manaba",)))
        self.assertEqual(2, len(self.store.snapshot()["tasks"]))

    def test_done_survives_regeneration_and_is_independent_of_source_read_state(self):
        self.publish()
        task = self.store.snapshot()["tasks"][0]
        self.store.complete(task["id"], True)
        self.publish("two", answer={"tasks": []})
        self.publish("three")
        task = next(t for t in self.store.snapshot()["tasks"] if t["id"] == task["id"])
        self.assertTrue(task["done"])
        self.store.complete(task["id"], False)
        self.assertFalse(next(t for t in self.store.snapshot()["tasks"] if t["id"] == task["id"])["done"])

    def test_agent_task_key_preserves_completion_across_a_new_message_and_wording(self):
        first = self.answer(("gmail",))
        first["tasks"][0]["task_key"] = "company/program/entry"
        self.publish(answer=first)
        task = self.store.snapshot()["tasks"][0]
        self.store.complete(task["id"], True)
        inputs = self.inputs()
        inputs[0]["records"][0]["id"] = "gmail:new-message"
        changed = self.answer(("gmail",))
        changed["tasks"][0].update(source_id="gmail:new-message", title="ESを確認して提出する", task_key="company/program/entry")
        changed["tasks"][0]['eligibility']['source_id'] = "gmail:new-message"
        self.publish("new-message", inputs, changed)
        self.assertEqual(task["id"], self.store.snapshot()["tasks"][0]["id"])
        self.assertTrue(self.store.snapshot()["tasks"][0]["done"])

    def test_midnight_and_failed_refresh_never_claim_current_coverage(self):
        self.publish()
        self.assertTrue(self.store.snapshot()["fresh"])
        self.current += timedelta(days=1)
        self.assertFalse(self.store.snapshot()["fresh"])
        self.assertEqual("stale", self.store.snapshot()["coverage"][0]["state"])
        self.assertTrue(all(t["stale"] for t in self.store.snapshot()["tasks"]))
        self.assertEqual(2, len(self.store.snapshot()["tasks"]))
        self.assertTrue(self.store.begin("next"))
        self.store.fail("next")
        self.assertEqual("failed", self.store.snapshot()["state"])

    def test_unknown_source_invalid_date_and_priority_are_rejected_atomically(self):
        self.publish()
        for index, (key, value) in enumerate([("source_id", "invented"), ("deadline", "2026-02-30"), ("deadline", "unknown"), ("priority", True)]):
            answer = self.answer()
            answer["tasks"][0][key] = value
            identity = f"case-{index}"
            self.assertTrue(self.store.begin(identity))
            with self.assertRaises(PlanError):
                self.store.finish(identity, self.inputs(), answer)
            self.store.fail(identity)
            self.assertEqual(2, len(self.store.snapshot()["tasks"]))

    def test_account_change_clears_previous_plan_and_cannot_race_active_refresh(self):
        self.publish()
        self.store.begin("running")
        with self.assertRaises(PlanError):
            self.store.configure(dict(self.settings, email="other@example.org"))
        self.store.fail("running")
        self.store.configure(dict(self.settings, email="other@example.org"))
        self.assertEqual(["manaba"], [t["source"] for t in self.store.snapshot()["tasks"]])

    def test_disabling_daily_collection_preserves_tasks_and_completion(self):
        self.publish()
        task = self.store.snapshot()["tasks"][0]
        self.store.complete(task["id"], True)
        self.store.configure(dict(self.settings, enabled=False))
        self.assertEqual(2, len(self.store.snapshot()["tasks"]))
        self.assertTrue(next(t for t in self.store.snapshot()["tasks"] if t["id"] == task["id"])["done"])

    def test_fetched_material_survives_restart_and_next_day_failure(self):
        self.store.begin("first")
        record = self.inputs()[0]["records"][0]
        self.store.cache_source("first", "gmail", [record], "updated")
        self.store.fail("first")
        restarted = PlanStore(self.temp.name, lambda: self.current + timedelta(days=1))
        self.assertEqual(record["text"], restarted.cached_records([record["id"]])[0]["text"])
        self.assertEqual("cached", restarted.cached_inputs("next")[0]["state"])
        restarted.begin("second")
        restarted.cache_source("second", "gmail", [], "failed")
        cached = restarted.cached_inputs("second")[0]["records"][0]
        self.assertEqual(record["text"], cached["text"])
        self.assertFalse(cached["retrieved_in_current_run"])

    def test_old_cache_schema_migrates_without_dropping_records_or_claiming_freshness(self):
        with tempfile.TemporaryDirectory() as directory:
            from pathlib import Path
            root = Path(directory) / "today_home"
            root.mkdir()
            record = self.inputs()[0]["records"][0]
            with sqlite3.connect(root / "plan.sqlite3") as db:
                db.execute("CREATE TABLE source_cache(id TEXT PRIMARY KEY,source TEXT,payload TEXT,checked_at TEXT)")
                db.execute("INSERT INTO source_cache VALUES(?,?,?,?)", (record["id"], "gmail", json.dumps(record), self.current.isoformat()))
            db.close()
            migrated = PlanStore(directory, lambda: self.current)
            cached = migrated.cached_records([record["id"]])[0]
            self.assertEqual(record["text"], cached["text"])
            self.assertFalse(cached["retrieved_in_current_run"])

    def test_successful_narrow_search_cannot_erase_unmentioned_pending_tasks(self):
        self.publish()
        self.publish("narrow", self.inputs(), {"tasks": []})
        snapshot = self.store.snapshot()
        self.assertEqual(2, len(snapshot["tasks"]))
        self.assertTrue(all(t["stale"] for t in snapshot["tasks"]))
        self.assertFalse(snapshot["fresh"])

    def test_new_message_search_cannot_make_older_cached_evidence_fresh(self):
        self.store.begin("old")
        record = self.inputs()[0]["records"][0]
        self.store.cache_source("old", "gmail", [record], "checked")
        self.store.finish("old", self.store.cached_inputs("old"), self.answer(("gmail",)))
        original_time = self.store.snapshot()["tasks"][0]["source_checked_at"]
        self.current += timedelta(days=1)
        self.store.begin("new-search")
        self.store.cache_source("new-search", "gmail", [], "checked")
        self.store.finish("new-search", self.store.cached_inputs("new-search"), self.answer(("gmail",)))
        snapshot = self.store.snapshot()
        self.assertTrue(snapshot["tasks"][0]["stale"])
        self.assertEqual(original_time, snapshot["tasks"][0]["source_checked_at"])
        self.assertFalse(snapshot["fresh"])

    def test_explicit_resolution_requires_current_evidence_and_keeps_an_audit_record(self):
        self.publish()
        resolution = {"task_key": "gmail:one", "source_id": "gmail:one", "reason": "今回確認した受付完了を根拠に解決"}
        self.publish("resolved", self.inputs(), {"tasks": [], "resolved": [resolution]})
        snapshot = self.store.snapshot()
        self.assertEqual(["manaba"], [t["source"] for t in snapshot["tasks"]])
        self.assertEqual(resolution["reason"], snapshot["resolved_tasks"][0]["resolution"]["reason"])
        inputs = self.inputs()
        inputs[1]["records"][0]["retrieved_in_current_run"] = False
        resolution.update(task_key="manaba:one", source_id="manaba:one")
        self.store.begin("cache-only-resolution")
        with self.assertRaises(PlanError):
            self.store.finish("cache-only-resolution", inputs, {"tasks": [], "resolved": [resolution]})
        self.assertEqual(1, len(self.store.snapshot()["tasks"]))

    def test_read_only_manaba_routes_reject_action_links_cross_origin_and_queries(self):
        root = self.settings["manaba_url"]
        self.assertTrue(manaba_read_url("https://university.example/ct/report_123", root))
        self.assertTrue(manaba_read_url("https://university.example/ct/course_12_report_123", root))
        self.assertTrue(manaba_read_url("https://university.example/ct/course_12_drill_123", root))
        for url in ["https://other.example/ct/report_123", "https://university.example/ct/report_delete_123",
                    "https://university.example/ct/report_123?action=submit", "https://university.example/ct/logout"]:
            self.assertFalse(manaba_read_url(url, root))

    def test_resolution_event_survives_evidence_overwrite_and_task_reappearance(self):
        self.publish(answer=self.answer(("gmail",)))
        resolution = dict(task_key="gmail:one", source_id="gmail:one", reason="fresh receipt")
        inputs = self.inputs()
        inputs[0]["records"][0]["text"] = "original receipt"
        self.publish("resolved", inputs, {"tasks": [], "resolved": [resolution]})
        event = self.store.resolution_history()[0]
        self.assertEqual("resolved", event["run_id"])
        self.assertEqual("resolved", event["evidence_run"])
        self.assertEqual(self.current.isoformat(), event["evidence_checked_at"])
        self.assertEqual("original receipt", event["evidence"]["text"])
        self.current += timedelta(hours=1)
        self.store.begin("reappeared")
        record = dict(inputs[0]["records"][0], text="本人の必須個人課題。changed source content")
        self.store.cache_source("reappeared", "gmail", [record], "checked")
        self.store.finish("reappeared", self.store.cached_inputs("reappeared"), self.answer(("gmail",)))
        self.assertEqual(1, len(self.store.snapshot()["tasks"]))
        self.assertEqual([], self.store.snapshot()["resolved_tasks"])
        self.assertEqual(event, self.store.resolution_history()[0])
        self.publish("resolved-again", inputs, {"tasks": [], "resolved": [resolution]})
        self.assertEqual(2, len(self.store.resolution_history()))
        self.assertEqual(event, self.store.resolution_history()[0])


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.auth = True
        self.attach_session = True
        guard_patch = patch("today_core.api.admitted_for_sensitive_route", side_effect=lambda request: self.auth)
        self.guard = guard_patch.start()
        self.addCleanup(guard_patch.stop)
        self.launched = []
        self.app = FastAPI()
        self.app.state.auth_required = True
        @self.app.middleware("http")
        async def session(request, call_next):
            if self.auth and self.attach_session:
                request.state.session = {"verified": True}
            return await call_next(request)
        self.app.include_router(create_router(
            resolver=lambda p: (self.temp.name + "/" + (p or "default"), p or "default"),
            model=lambda *args: self.fail("must not call a real model"),
            launch=self.launched.append), prefix="/api/plugins/today_home")
        self.client = TestClient(self.app)
        self.settings = dict(email="test@example.org", manaba_url="", enabled=True)

    def test_auth_origin_and_strict_completion_are_enforced(self):
        self.auth = False
        self.assertEqual(401, self.client.get("/api/plugins/today_home/plan").status_code)
        self.auth = True
        self.assertEqual(403, self.client.put("/api/plugins/today_home/settings", json=self.settings,
                         headers={"origin": "https://hostile.example"}).status_code)
        self.assertEqual(422, self.client.patch("/api/plugins/today_home/tasks/missing", json={"done": "false"}).status_code)

    def test_get_never_collects_profiles_are_isolated_and_refresh_is_idempotent(self):
        base = "/api/plugins/today_home"
        self.assertEqual(200, self.client.get(base + "/plan").status_code)
        self.assertEqual([], self.launched)
        self.assertEqual(200, self.client.put(base + "/settings?profile=a", json=self.settings).status_code)
        self.assertFalse(self.client.get(base + "/settings?profile=b").json()["enabled"])
        for _ in range(2):
            self.assertEqual(200, self.client.post(base + "/refresh?profile=a", json={"request_id": "same"}).status_code)
        self.assertEqual(1, len(self.launched))

    def test_core_admission_supports_native_without_session_and_rejects_spoofed_headers(self):
        self.attach_session = False
        self.assertEqual(200, self.client.get("/api/plugins/today_home/plan").status_code)
        self.assertFalse(hasattr(self.guard.call_args.args[0].state, "session"))
        self.auth = False
        self.assertEqual(401, self.client.get("/api/plugins/today_home/plan", headers={
            "x-hermes-native-ticket": "forged", "x-hosted-http-authenticated": "true"}).status_code)
        self.assertEqual([], self.launched)

    def test_missing_standard_guard_fails_closed_and_core_rejections_propagate(self):
        with patch("today_core.api.admitted_for_sensitive_route", None):
            self.assertEqual(503, self.client.get("/api/plugins/today_home/settings").status_code)
        self.guard.side_effect = HTTPException(403)
        self.assertEqual(403, self.client.get("/api/plugins/today_home/plan").status_code)

    def test_accepted_agent_can_cache_evidence_and_publish_a_plan(self):
        def model(profile, store, run):
            store.cache_source(run, "gmail", [dict(id="gmail:evidence", source="gmail", title="応募締切",
                url="https://example.org", text="申込受付済み。明日までにESを完成してください。")], "updated")
            return {"tasks": [dict(task_key="company/entry", source_id="gmail:evidence", title="ESを仕上げる",
                reason="明日の締切に備えて今日見直す", deadline=None, priority=2,
                eligibility=dict(kind="user_application", source_id="gmail:evidence", quote="申込受付済み。",
                                 user_intent="confirmed", action_status="pending", deadline_policy="closes"))]}
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            request.state.session = {"verified": True}
            return await call_next(request)
        app.include_router(create_router(resolver=lambda p: (self.temp.name, "default"), model=model,
                                         launch=lambda work: work()))
        client = TestClient(app)
        client.put("/settings", json=self.settings)
        result = client.post("/refresh", json={"request_id": "daily-agent"})
        self.assertEqual(200, result.status_code)
        self.assertEqual("complete", result.json()["state"])
        self.assertEqual("ESを仕上げる", result.json()["tasks"][0]["title"])
        self.assertEqual(1, len(PlanStore(self.temp.name).cache_index()))


if __name__ == "__main__":
    unittest.main()
