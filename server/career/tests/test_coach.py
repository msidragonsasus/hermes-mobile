import copy
import json
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.coach_runtime import HermesCoachRuntime, bounded_history
from career_core.coach_skills import catalog, read_skill
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools, register_tools
from career_core.coach_worker import CoachWorker, enqueue_daily
from career_core.research import research
from career_core.shared import save_document
from career_core.store import CareerError, Store
from career_core.sync import synchronize
from career_core.vendor.v40.state_v4 import initial_state
from coach_fixture import configure_execution


class CoachTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name)
        self.coach = CoachStore(self.store)
        self.tools = CoachTools(self.coach)

    def tearDown(self):
        self.tmp.cleanup()

    def submit(self, text="ESの下書きを作って", profile="phone", operation="message-1", kind="message"):
        return self.coach.submit({"text": text, "operation_id": operation}, profile, kind=kind)

    def active(self, text="ESの下書きを作って"):
        self.submit(text)
        run = self.coach.claim()
        return run, {"task_id": run["id"], "session_id": "career-run:" + run["id"]}

    def test_message_retry_profile_isolation_and_cursor_recovery(self):
        first = self.submit()
        self.assertEqual(first, self.submit())
        self.assertEqual(1, len(self.coach.snapshot("phone")["messages"]))
        self.assertEqual([], self.coach.snapshot("desktop")["messages"])
        with self.assertRaises(CareerError):
            self.submit(profile="desktop")
        with self.assertRaises(CareerError):
            self.coach.control({"id": first["id"], "control": "stop", "operation_id": "other-stop"}, "desktop")
        self.assertEqual(first["id"], self.coach.events("phone", 0)["events"][0]["run_id"])
        cursor = self.coach.snapshot("phone")["cursor"]
        self.assertEqual([], self.coach.events("phone", cursor)["events"])

    def test_restart_resume_reconciles_saved_document_without_second_version(self):
        run, meta = self.active()
        request = {"kind": "documents", "id": "DOC-001", "expected_version": 0,
                   "data": {"title": "下書き", "body": "確認済み材料による本文"}}
        first = self.tools.dispatch("career_save", request, meta)
        recovered = CoachStore(Store(self.tmp.name))
        recovered.recover()
        self.assertEqual("paused", recovered.run(run["id"])["state"])
        recovered.control({"id": run["id"], "control": "resume", "operation_id": "resume-1"}, "phone")
        recovered.claim()
        self.assertEqual(first, CoachTools(recovered).dispatch("career_save", request, meta))
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM document_files").fetchone()[0])
        changed = dict(request, data={"body": "違う結果"})
        with self.assertRaises(CareerError):
            self.tools.dispatch("career_save", changed, meta)

    def test_transaction_failure_rolls_back_write_and_receipt(self):
        run, _ = self.active()
        def crash(db, run, operation):
            self.store.put(db, "tasks", "task", 0, {"title": "test"})
            raise RuntimeError("simulated lost process before commit")
        with self.assertRaises(RuntimeError):
            self.coach.tool_once(run["id"], {"kind": "tasks"}, crash)
        self.assertEqual([], self.store.snapshot()["entities"])
        self.assertEqual([], self.coach.receipts(run["id"]))

    def test_model_cannot_grant_run_access_or_make_application_decision(self):
        run, meta = self.active()
        args = {"kind": "applications", "id": "app-1", "expected_version": 0, "data": {"status": "applied"}}
        for metadata in ({}, dict(meta, session_id="regular-chat"), meta):
            with self.assertRaises(CareerError):
                self.tools.dispatch("career_save", args, metadata)
        self.assertEqual([], self.store.snapshot()["entities"])

    def test_fact_uses_exact_user_quote_and_correction_keeps_old_claim(self):
        run, meta = self.active("私は木材とテクノロジーを結びつけたとは言っていません。")
        message = self.coach.snapshot("phone")["messages"][0]
        args = {"kind": "facts", "id": "FACT-001", "expected_version": 0,
                "data": {"id": "FACT-001", "statement": message["text"]},
                "user_statement": {"message_id": message["id"], "quote": message["text"]}}
        forged = copy.deepcopy(args)
        forged["data"]["statement"] = "木材とテクノロジーを結びつけた"
        with self.assertRaises(CareerError):
            self.tools.dispatch("career_save", forged, meta)
        saved = self.tools.dispatch("career_save", args, meta)
        self.assertEqual("CONFIRMED", saved["data"]["status"])
        self.assertIn(message["id"], saved["data"]["provenance"]["source_ref"])
        correction = dict(args, expected_version=1, correction=True)
        result = self.tools.dispatch("career_save", correction, meta)
        self.assertEqual("FACT-002", result["replacement"]["id"])
        with self.store.connect() as db:
            self.assertEqual("SUPERSEDED", self.store.entity(db, "facts", "FACT-001")["data"]["status"])

    def test_daily_cannot_write_fact_document_or_prepare_interview(self):
        self.submit(kind="daily")
        run = self.coach.claim()
        meta = {"task_id": run["id"], "session_id": "career-run:" + run["id"]}
        for kind in ("facts", "documents", "events", "applications"):
            with self.assertRaises(CareerError) as error:
                self.tools.dispatch("career_save", {"kind": kind, "id": "x", "expected_version": 0, "data": {"body": "unsolicited"}}, meta)
            self.assertEqual("daily_scope", error.exception.code)

    def test_stop_blocks_late_write_and_late_completion(self):
        run, meta = self.active()
        self.coach.control({"id": run["id"], "control": "stop", "operation_id": "stop-run-1"}, "phone")
        with self.assertRaises(CareerError):
            self.tools.dispatch("career_save", {"kind": "tasks", "id": "task", "expected_version": 0, "data": {}}, meta)
        self.coach.finish(run["id"], "completed")
        self.assertEqual("cancelled", self.coach.run(run["id"])["state"])
        self.coach.finish(run["id"], "completed", reply="late reply")
        self.assertEqual(1, len(self.coach.snapshot("phone")["messages"]))

    def test_waiting_answer_is_resolved_by_next_message_and_manual_priority(self):
        self.submit(kind="daily", operation="daily-run-1")
        manual = self.submit(operation="manual-run-1")
        self.assertEqual(manual["id"], self.coach.claim()["id"])
        self.coach.finish(manual["id"], "waiting_user", reply="その時どんな判断をしましたか。")
        self.submit("その判断は…", operation="answer-run-1")
        self.assertEqual("completed", self.coach.run(manual["id"])["state"])
        self.assertEqual("message", self.coach.claim()["kind"])

    def test_daily_enqueue_is_idempotent_and_paused_work_is_not_replaced(self):
        self.submit()
        enqueue_daily(self.store)
        enqueue_daily(self.store)
        daily = [r for r in self.coach.snapshot("phone")["runs"] if r["kind"] == "daily"]
        self.assertEqual(1, len(daily))
        self.coach.finish(daily[0]["id"], "paused")
        enqueue_daily(self.store)
        self.assertEqual(1, len([r for r in self.coach.snapshot("phone")["runs"] if r["kind"] == "daily"]))

    def test_research_yields_at_company_boundary_and_preserves_checkpoint(self):
        for identity in ("a", "b"):
            self.store.save({"kind": "companies", "id": identity, "expected_version": 0,
                            "operation_id": "company-" + identity, "data": {"name": identity, "source": {"type": "Company"}, "official_urls": ["https://example.com"]}})
        class Extractor:
            calls = 0
            def extract(self, *args):
                self.calls += 1
                return []
        extractor = Extractor()
        result = research(self.store, extractor, lambda url: {"url": url, "text": "official"}, should_yield=lambda: extractor.calls == 1)
        self.assertEqual("yielded", result["state"])
        with self.store.connect() as db:
            self.assertEqual(["b"], self.store.meta(db, "research_checkpoint"))

    def test_yielded_manual_research_does_not_starve_new_message(self):
        self.submit(kind="research", operation="research-job-1")
        first = self.coach.claim()
        next_run = self.submit(operation="manual-job-2")
        self.coach.finish(first["id"], "queued", {"reason": "manual_priority"})
        self.assertEqual(next_run["id"], self.coach.claim()["id"])

    def test_stop_during_research_does_not_commit_late_offer(self):
        self.store.save({"kind": "companies", "id": "a", "expected_version": 0, "operation_id": "company-stop-1",
                         "data": {"name": "a", "source": {"type": "Company"}, "official_urls": ["https://example.com"]}})
        stopped = False
        class Extractor:
            def extract(inner, *args):
                nonlocal stopped
                stopped = True
                return [{"title": "course", "source_url": "https://example.com"}]
        result = research(self.store, Extractor(), lambda url: {"url": url, "text": "course"}, should_cancel=lambda: stopped)
        self.assertEqual("cancelled", result["state"])
        self.assertFalse(any(e["kind"] == "offers" for e in self.store.snapshot()["entities"]))

    def test_company_saved_by_coach_is_included_in_official_research(self):
        _, metadata = self.active("この企業の採用情報を継続して調べたいです。https://example.com")
        self.tools.dispatch("career_save", {"kind": "companies", "id": "company-new", "expected_version": 0,
                            "data": {"name": "new", "official_urls": ["https://example.com"]}}, metadata)
        class Extractor:
            calls = 0
            def extract(inner, *args):
                inner.calls += 1
                return []
        extractor = Extractor()
        self.assertEqual("completed", research(self.store, extractor, lambda url: {"url": url, "text": "official"})["state"])
        self.assertEqual(1, extractor.calls)

    def test_worker_saves_reply_and_artifact_without_client_connection(self):
        from test_career import ContextRemote
        remote = ContextRemote()
        remote.state["documents"] = []
        synchronize(self.store, remote)
        run = self.submit()
        class Runtime:
            def __init__(inner, coach):
                inner.coach = coach
            def run(inner, accepted):
                tools = CoachTools(inner.coach)
                metadata = {"task_id": accepted["id"], "session_id": "career-run:" + accepted["id"]}
                tools.dispatch("career_skill", {"name": "draft-jp-entry-sheets"}, metadata)
                tools.dispatch("career_save", {"kind": "documents", "id": "DOC-001", "expected_version": 0,
                                               "data": {"title": "下書き", "body": "合成データの本文"}}, metadata)
                tools.dispatch("career_checkpoint", {"topic": "自己PR", "next_action": "本文の確認", "artifact_ids": ["DOC-001"], "waiting_user": True}, metadata)
                return "下書きを保存しました。"
        with patch("career_core.api.sync_job", side_effect=[{"state": "synced"}, {"state": "unsynced"}]):
            self.assertTrue(CoachWorker(self.store, Runtime).one())
        reconnected = CoachStore(Store(self.tmp.name)).snapshot("phone")
        self.assertEqual("下書きを保存しました。", reconnected["messages"][-1]["text"])
        self.assertEqual(["DOC-001"], reconnected["runs"][0]["result"]["artifact_ids"])
        self.assertEqual("waiting_user", reconnected["runs"][0]["state"])

    def test_checkpoint_rejects_invented_artifact_and_non_boolean_waiting_state(self):
        _, meta = self.active()
        for args in ({"topic": "test", "next_action": "test", "waiting_user": "false"},
                     {"topic": "test", "next_action": "test", "waiting_user": False, "artifact_ids": ["DOC-404"]}):
            with self.assertRaises(CareerError):
                self.tools.dispatch("career_checkpoint", args, meta)
        self.assertEqual({}, self.coach.snapshot("phone")["checkpoint"])

    def test_skill_port_keeps_business_policy_and_blocks_path_escape(self):
        self.assertEqual(23, len(catalog()))
        self.assertIn("既に分かっていることを聞き直さない", read_skill("operate-jp-job-hunting", "references/question-policy.md")["text"])
        self.assertIn("本文", read_skill("draft-jp-entry-sheets")["text"])
        with self.assertRaises(CareerError):
            read_skill("operate-jp-job-hunting", "../../HERMES.md")

    def test_registered_handlers_use_host_ids_and_load_skill(self):
        handlers = {}
        class Context:
            def register_tool(self, **spec):
                handlers[spec["name"]] = spec["handler"]
        register_tools(Context(), lambda: self.store)
        run, meta = self.active()
        self.assertIn("error", json.loads(handlers["career_skill"]({"name": "operate-jp-job-hunting"})))
        loaded = json.loads(handlers["career_skill"]({"name": "operate-jp-job-hunting"}, **meta))
        self.assertIn("就活を管制", loaded["text"])

    def test_runtime_adapter_passes_same_conversation_and_surfaces_quota_pause(self):
        run, _ = self.active("続きから")
        old = [{"role": "user", "content": "前の相談"}, {"role": "assistant", "content": "前の問い"}]
        self.coach.save_history("phone", old)
        class Agent:
            closed = False
            def run_conversation(inner, text, **kwargs):
                self.assertEqual(run["id"], kwargs["task_id"])
                self.assertEqual(old, kwargs["conversation_history"])
                return {"failed": True, "failure_reason": "rate_limit", "messages": old}
            def close(inner):
                inner.closed = True
        agent = Agent()
        runtime = HermesCoachRuntime(self.coach, lambda *a, **kw: agent)
        with self.assertRaises(CareerError) as error:
            runtime.run(run)
        self.assertEqual("quota_paused", error.exception.code)
        self.assertTrue(agent.closed)

    def test_structured_checkpoint_sync_preserves_other_client_context_without_raw_chat(self):
        class Remote:
            def __init__(inner):
                inner.state = initial_state("2026-09-08 09:00 JST")
                inner.state["last_session"] = {"topic": "他のChatの論点"}
                inner.writes = 0
            def read(inner):
                return copy.deepcopy(inner.state), "sha"
            def write(inner, value, sha):
                inner.state = value
                inner.writes += 1
                return "new-sha"
        remote = Remote()
        run, meta = self.active("生の会話にしかない特別な内容")
        self.tools.dispatch("career_checkpoint", {"topic": "ESの続き", "next_action": "本文を確認", "waiting_user": True}, meta)
        self.coach.finish(run["id"], "waiting_user", reply="質問")
        self.assertEqual("synced", synchronize(self.store, remote)["state"])
        self.assertEqual("他のChatの論点", remote.state["last_session"]["topic"])
        self.assertEqual("ESの続き", remote.state["last_session"]["hermes_coach"][run["id"]]["topic"])
        self.assertNotIn("生の会話", json.dumps(remote.state, ensure_ascii=False))
        synchronize(self.store, remote)
        self.assertEqual(1, remote.writes)
        with tempfile.TemporaryDirectory() as next_server:
            replacement = Store(next_server)
            synchronize(replacement, remote)
            resumed = CoachStore(replacement).snapshot("phone")
            self.assertEqual("ESの続き", resumed["checkpoint"]["topic"])
            self.assertEqual([], resumed["messages"])

    def test_http_auth_request_retry_and_profile_boundary(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test-session"):
                request.state.session = {"user": "test"}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        client = TestClient(app)
        url = "/api/plugins/career/coach/message?profile=phone"
        payload = {"text": "相談したい", "operation_id": "http-test-message"}
        self.assertEqual(401, client.post(url, json=payload).status_code)
        headers = {"x-test-session": "yes"}
        first = client.post(url, json=payload, headers=headers)
        self.assertEqual(200, first.status_code)
        self.assertEqual(first.json(), client.post(url, json=payload, headers=headers).json())
        self.assertEqual([], client.get("/api/plugins/career/coach/conversation?profile=other", headers=headers).json()["messages"])
        self.assertEqual(403, client.post(url, json=payload, headers=dict(headers, origin="https://evil.test")).status_code)


if __name__ == "__main__":
    unittest.main()
