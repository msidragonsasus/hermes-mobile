"""Synthetic Bot contracts. These tests do not claim live company compatibility."""
import json
import io
from contextlib import redirect_stderr, redirect_stdout
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.company_lifecycle import delete_company
from career_core.mypage import MyPageStore
from career_core.mypage_agent import PortalRun, register_tools
from career_core.mypage_bot import MyPageBotStore, connection_id, portal_url
from career_core.secret_store import SecretStore
from career_core.store import CareerError, Store


class MyPageBotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / "data")
        self.secrets = SecretStore(Path(self.tmp.name) / "private", key=Fernet.generate_key())
        self.records = MyPageBotStore(self.store, self.secrets)
        self.host = patch("career_core.mypage_browser.public_host", return_value="portal.example.test")
        self.host.start()
        self.addCleanup(self.host.stop)
        with self.store.transaction() as db:
            for company in ("EY", "JR東海", "アビーム", "日鉄エンジニアリング"):
                self.store.put(db, "companies", company, 0, {"name": company})

    def configure(self, company="EY", profile="default", version=0, **values):
        return self.records.configure(company, profile, dict(company_id=company,
            url="https://portal.example.test/company/login", login_id="synthetic-id", password="synthetic-password",
            enabled=True, expected_version=version, **values))

    def successful(self, row, quote="夏期プログラム 締切 2026年10月1日 18:00 JST"):
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        self.records.finish(run, "completed", items=[{"title": "夏期プログラム", "quote": quote, "url": "https://portal.example.test/company/information"}])
        return run

    def test_all_four_synthetic_companies_use_same_connection_contract(self):
        for company in ("EY", "JR東海", "アビーム", "日鉄エンジニアリング"):
            row = self.configure(company)
            self.successful(row)
            view = MyPageStore(self.store).view(company)
            self.assertTrue(view["supported"])
            self.assertTrue(view["verified"])
            self.assertEqual("2026-10-01", view["notices"][0]["deadlines"][0]["date"])

    def test_credentials_do_not_enter_db_or_view(self):
        row = self.configure()
        self.assertEqual("synthetic-password", self.secrets.read(row["config"]["secret_ref"])["password"])
        self.assertNotIn(b"synthetic-password", self.store.path.read_bytes())
        self.assertNotIn(b"synthetic-id", self.store.path.read_bytes())
        self.assertNotIn("secret_ref", json.dumps(self.records.view("EY", "default")))

    def test_profile_separation_and_foreign_company_have_no_connection(self):
        one = self.configure(profile="phone")
        two = self.configure(profile="desktop")
        self.assertNotEqual(one["config"]["secret_ref"], two["config"]["secret_ref"])
        self.successful(one)
        self.assertEqual([], self.records.view("EY", "desktop")["notices"])
        self.assertIsNone(self.records.view("JR東海", "phone"))
        from career_core.company_views import CompanyViews
        from career_core.conversations import accepted_sources
        other = CompanyViews(self.store).detail("EY", "desktop")
        self.assertEqual([], other["evidence"])
        self.assertEqual([], self.records.collection.notices(profile="desktop")["notices"])
        overview = CompanyViews(self.store).overview("desktop")
        self.assertEqual(0, next(c["new_count"] for c in overview["companies"] if c["id"] == "EY"))
        own = self.records.view("EY", "phone")["notices"][0]
        with self.store.connect() as db, self.assertRaises(CareerError):
            accepted_sources(db, {"company_id": "EY"}, [{"kind": "mypage", "id": own["id"]}], "desktop")

    def test_confirmed_empty_replaces_current_items_but_retains_source_history(self):
        row = self.configure()
        self.successful(row)
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        self.records.finish(run, "completed", items=[])
        view = self.records.view("EY", "default")
        self.assertEqual([], view["notices"])
        self.assertEqual(1, len(view["history"]))

    def test_target_year_is_not_inferred_from_a_deadline(self):
        from career_core.mypage_bot import target_year
        self.assertIsNone(target_year("応募締切 2026年10月1日"))
        self.assertEqual("2028", target_year("2028年卒の方が対象です"))
        self.assertIsNone(target_year("2027年度・2028年度"))

    def test_old_reader_is_disabled_for_migrated_connection(self):
        self.configure("日鉄エンジニアリング")
        with self.assertRaisesRegex(CareerError, "mypage_bot_required"):
            MyPageStore(self.store).collect("日鉄エンジニアリング", reader=Mock())

    def test_failed_and_partial_runs_keep_last_success_and_previous_items(self):
        row = self.configure()
        self.successful(row)
        before = self.records.view("EY", "default")
        for state, reason in (("auth_required", "authentication_challenge"), ("failed", "browser_unavailable"), ("partial", "collection_limit")):
            self.records.enqueue(row["id"])
            run = self.records.claim(row["id"])
            self.records.finish(run, state, reason)
            after = self.records.view("EY", "default")
            self.assertEqual(before["status"]["last_success_at"], after["status"]["last_success_at"])
            self.assertEqual(before["notices"], after["notices"])

    def test_duplicate_and_changed_evidence_notifications(self):
        row = self.configure()
        self.successful(row)
        self.successful(row)
        self.assertEqual(1, len(self.records.collection.notices()["notices"]))
        self.successful(row, "夏期プログラム 締切 2026年10月2日")
        self.assertEqual(2, len(self.records.collection.notices()["notices"]))
        self.assertEqual(1, len(self.records.view("EY", "default")["notices"]))

    def test_initial_partial_pages_do_not_notify_until_completed_baseline(self):
        row = self.configure()
        for i in range(2):
            self.records.enqueue(row["id"])
            run = self.records.claim(row["id"])
            item = {"title": "ES提出依頼", "quote": "ESを提出してください。ページ" + str(i),
                    "url": "https://portal.example.test/company/page" + str(i)}
            self.records.checkpoint(run, item)
            self.records.finish(run, "partial" if i == 0 else "completed", items=[item])
            self.assertFalse(any(n["eligible"] for n in self.records.collection.notices()["notices"]))
        self.successful(row, "ESを提出してください。提出期限を延長しました。")
        self.assertEqual(1, sum(n["eligible"] for n in self.records.collection.notices()["notices"]))

    def test_auth_notifications_ignore_changed_reason_until_recovery(self):
        row = self.configure(profile="phone")
        for reason in ("authentication_challenge", "authentication_required", "authentication_challenge"):
            self.records.enqueue(row["id"])
            self.records.finish(self.records.claim(row["id"]), "auth_required", reason)
        feed = self.records.collection.notices(profile="phone")
        self.assertEqual(1, sum(n["eligible"] for n in feed["notices"]))
        self.assertFalse(any(n["eligible"] for n in self.records.collection.notices(profile="desktop")["notices"]))
        self.successful(row)
        self.assertFalse(any(n["eligible"] for n in self.records.collection.notices(profile="phone")["notices"]))
        self.records.enqueue(row["id"])
        self.records.finish(self.records.claim(row["id"]), "auth_required", "authentication_required")
        self.assertEqual(1, sum(n["eligible"] for n in self.records.collection.notices(profile="phone")["notices"]))

    def test_concurrent_enqueues_and_claims_do_not_duplicate(self):
        row = self.configure()
        first = self.records.enqueue(row["id"])
        self.assertEqual(first["id"], self.records.enqueue(row["id"])["id"])
        self.assertIsNotNone(self.records.claim(row["id"]))
        self.assertIsNone(self.records.claim(row["id"]))

    def test_same_failure_does_not_repeat_notification(self):
        row = self.configure()
        for _ in range(2):
            self.records.enqueue(row["id"])
            self.records.finish(self.records.claim(row["id"]), "failed", "browser_unavailable")
        self.assertEqual(1, len(self.records.collection.notices()["notices"]))

    def test_closed_company_cannot_be_collected(self):
        row = self.configure()
        with self.store.transaction() as db:
            company = self.store.entity(db, "companies", "EY")
            self.store.put(db, "companies", "EY", company["version"], dict(company["data"], user_stage="closed"))
        with self.assertRaisesRegex(CareerError, "company_closed"):
            self.records.enqueue(row["id"], scheduled=True)

    def test_changed_connection_prevents_late_save(self):
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        self.configure(version=1)
        self.records.finish(run, "completed", items=[{"title": "古い情報", "quote": "古い情報", "url": "https://portal.example.test/"}])
        self.assertEqual([], self.records.view("EY", "default")["notices"])

    def test_deletion_disables_jobs_and_prevents_saving_or_starting(self):
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        delete_company(self.store, {"operation_id": "delete-company-123", "id": "EY", "expected_version": 1, "confirmed_by_user": True})
        self.assertFalse(self.records.get(row["id"])["config"]["enabled"])
        with self.assertRaises(CareerError):
            self.records.enqueue(row["id"])
        self.records.finish(run, "completed")
        with self.store.connect() as db:
            self.assertEqual("cancelled", db.execute("SELECT state FROM career_mypage_runs WHERE id=?", (run["id"],)).fetchone()[0])

    def test_schedule_waits_for_verification_and_stops_after_auth_failure(self):
        row = self.configure()
        self.assertIsNone(self.records.enqueue(row["id"], scheduled=True))
        self.successful(row)
        self.assertIsNotNone(self.records.enqueue(row["id"], scheduled=True))
        run = self.records.claim(row["id"])
        self.records.finish(run, "auth_required", "authentication_required")
        self.assertIsNone(self.records.enqueue(row["id"], scheduled=True))

    def test_url_change_requires_reentered_credentials(self):
        self.configure()
        with self.assertRaisesRegex(CareerError, "credentials_required_for_new_url"):
            self.records.configure("EY", "default", {"company_id": "EY", "url": "https://other.example.test/login", "enabled": True, "expected_version": 1})

    def test_profile_not_in_request_schema_and_no_run_binding_no_tools(self):
        ctx = Mock()
        register_tools(ctx)
        for call in ctx.register_tool.call_args_list:
            handler = call.kwargs["handler"]
            self.assertEqual("portal_run_not_authorized", json.loads(handler({}, task_id="guessed", session_id="career-mypage:guessed"))["error"])
            self.assertNotIn("password", str(call.kwargs["schema"]))

    def test_empty_without_source_and_fabricated_quotes_are_rejected(self):
        browser = Mock(authenticated=True, blocked=False, pages={"1": {"text": "夏期プログラム 締切 2026年10月1日", "url": "https://portal.example.test/info", "authenticated": True}})
        portal = PortalRun(browser)
        self.addCleanup(portal.close)
        portal.started = True
        with self.assertRaisesRegex(CareerError, "empty_not_verified"):
            portal.invoke("career_mypage_finish", {"complete": True})
        with self.assertRaisesRegex(CareerError, "evidence_mismatch"):
            portal.invoke("career_mypage_save", {"page_id": "1", "title": "夏期プログラム", "quote": "締切は2027年"})
        portal.invoke("career_mypage_save", {"page_id": "1", "title": "夏期プログラム", "quote": "締切 2026年10月1日"})
        self.assertEqual("completed", portal.invoke("career_mypage_finish", {"complete": True})["state"])

    def test_evidence_rejections_identify_the_field_without_logging_source(self):
        browser = Mock(authenticated=True, blocked=False, pages={
            "1": {"authenticated": True, "url": "https://portal.example.test/info", "text": "SECRET_SOURCE 募集 締切 2026年10月1日"},
            "2": {"authenticated": False, "url": "https://portal.example.test/login", "text": "募集"}})
        portal = PortalRun(browser, run_id="fixture")
        self.addCleanup(portal.close)
        portal.started = True
        good = {"page_id": "1", "title": "募集", "quote": "締切 2026年10月1日"}
        cases = [({"page_id": "unknown"}, "unknown_page"), ({"page_id": []}, "unknown_page"),
                 ({"page_id": "2"}, "unauthenticated_page"), ({"title": ""}, "invalid_title_length"),
                 ({"quote": "x" * 10001}, "invalid_quote_length"), ({"title": "invented"}, "title_not_in_page"),
                 ({"quote": "締切 2027年10月1日"}, "quote_not_in_page")]
        with redirect_stderr(io.StringIO()) as captured:
            for changed, reason in cases:
                with self.subTest(reason=reason), self.assertRaises(CareerError) as raised:
                    portal.invoke("career_mypage_save", dict(good, **changed))
                self.assertEqual("evidence_mismatch", raised.exception.code)
                self.assertIn(reason, portal.evidence_rejections)
            self.assertEqual([], portal.items)
            portal.invoke("career_mypage_save", good)
        self.assertEqual(1, len(portal.items))
        self.assertNotIn("SECRET_SOURCE", captured.getvalue())
        self.assertNotIn("2027", captured.getvalue())
        self.assertNotIn("https://", captured.getvalue())

    def test_empty_incomplete_run_is_failed_and_browser_closes_before_agent(self):
        from career_core.mypage_agent import execute
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        order = []
        browser = Mock(authenticated=True, blocked=False, pages={})
        browser.close.side_effect = lambda: order.append("browser")
        agent = Mock(career_execution_error=None)
        agent.run_conversation.return_value = {"completed": True, "final_response": "SECRET_RESPONSE"}
        agent.close.side_effect = lambda: order.append("agent")
        with patch("career_core.coach_execution.ExecutionPolicy.daily", return_value={"execution": {
            "provider": "openai-codex", "model": "gpt-5.6-luna", "reasoning_effort": "high"}}), \
                patch.object(MyPageBotStore, "secret_store", return_value=self.secrets), \
                redirect_stderr(io.StringIO()) as captured:
            execute(self.store, row, run, browser_factory=lambda *args: browser, agent_factory=lambda *args, **kwargs: agent)
        self.assertEqual(["browser", "agent"], order)
        self.assertEqual("failed", self.records.get(row["id"])["status"]["state"])
        self.assertEqual("collection_incomplete", self.records.get(row["id"])["status"]["reason"])
        events = [json.loads(line) for line in captured.getvalue().splitlines()]
        outcome = next(event for event in events if event["event"] == "mypage_outcome")
        self.assertEqual(0, outcome["staged_items"])
        self.assertFalse(outcome["finished"])
        self.assertNotIn("SECRET_RESPONSE", captured.getvalue())

    def test_api_requires_dashboard_session_and_queues_async_run(self):
        self.configure()
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test") == "yes":
                request.state.session = {"test": True}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store))
        with TestClient(app) as client:
            self.assertEqual(401, client.post("/collection/mypage-check", json={"company_id": "EY"}).status_code)
            response = client.post("/collection/mypage-check", json={"company_id": "EY"}, headers={"x-test": "yes"})
            self.assertEqual(200, response.status_code)
            self.assertEqual("queued", response.json()["state"])
            self.assertTrue(response.json()["run_id"])

    def test_invalid_urls_are_rejected(self):
        for url in ("http://site.test", "https://name:secret@site.test", "https://site.test:12/", "https://site.test/\n"):
            with self.assertRaises(CareerError):
                portal_url(url)

    def manual_request(self, cron):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            request.state.session = {"test": True}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store, run_worker=True))
        with patch("career_core.mypage_cron.HermesMyPageCron", return_value=cron), \
                patch("career_core.coach_worker.worker"), patch("career_core.mail_worker.worker"), TestClient(app) as client:
            return client.post("/collection/mypage-check", json={"company_id": "EY"})

    def test_new_version_request_during_previous_cron_run_is_not_left_queued(self):
        row = self.configure()
        self.records.enqueue(row["id"])
        old = self.records.claim(row["id"])
        self.configure(version=1)
        cron = Mock()
        cron.claim.side_effect = CareerError("collection_running", "busy", 409)
        response = self.manual_request(cron)
        self.assertEqual(409, response.status_code)
        status = self.records.get(row["id"])["status"]
        self.assertEqual("failed", status["state"])
        self.assertEqual("collection_interrupted", status["reason"])
        with self.store.connect() as db:
            self.assertEqual("cancelled", db.execute("SELECT state FROM career_mypage_runs WHERE id=?", (old["id"],)).fetchone()[0])
        cron.fire.assert_not_called()

    def test_running_request_reuses_run_without_another_cron_claim(self):
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        cron = Mock()
        response = self.manual_request(cron)
        self.assertEqual({"state": "running", "run_id": run["id"]}, response.json())
        cron.claim.assert_not_called()

    def test_unexpected_claim_failure_is_recorded_without_raw_exception(self):
        row = self.configure()
        cron = Mock()
        cron.claim.side_effect = RuntimeError("secret-marker")
        with redirect_stderr(io.StringIO()) as log:
            response = self.manual_request(cron)
        self.assertEqual(503, response.status_code)
        self.assertEqual("failed", self.records.get(row["id"])["status"]["state"])
        self.assertNotIn("secret-marker", response.text + log.getvalue())

    def test_queued_duplicate_claim_rejection_keeps_original_request(self):
        row = self.configure()
        run = self.records.enqueue(row["id"])
        cron = Mock()
        cron.claim.side_effect = CareerError("collection_running", "busy", 409)
        response = self.manual_request(cron)
        self.assertEqual({"state": "queued", "run_id": run["id"]}, response.json())
        self.assertEqual("queued", self.records.get(row["id"])["status"]["state"])

    def test_preexisting_orphan_queue_can_be_dispatched_without_new_run(self):
        row = self.configure()
        run = self.records.enqueue(row["id"])
        cron = Mock()
        cron.claim.return_value = {"execution_id": "fixture"}
        response = self.manual_request(cron)
        self.assertEqual(run["id"], response.json()["run_id"])
        self.assertEqual(run["id"], cron.fire.call_args.args[1]["id"])

    def test_dispatch_failure_finalizes_queue_without_replacing_terminal_result(self):
        import sys
        from types import SimpleNamespace
        from contextlib import nullcontext
        from career_core.mypage_cron import HermesMyPageCron
        row = self.configure()
        provider = Mock()
        cron = object.__new__(HermesMyPageCron)
        cron.records = self.records
        cron.scope = nullcontext
        module = SimpleNamespace(resolve_cron_scheduler=lambda: provider)
        with patch.dict(sys.modules, {"cron.scheduler_provider": module}), redirect_stderr(io.StringIO()) as log:
            for failure in (None, RuntimeError("secret-marker")):
                run = self.records.enqueue(row["id"])
                provider.fire_claimed.side_effect = failure
                cron.fire({}, run)
                self.assertEqual("failed", self.records.get(row["id"])["status"]["state"])
            self.assertNotIn("secret-marker", log.getvalue())
            provider.fire_claimed.side_effect = None
            run = self.successful(row)
            before = self.records.get(row["id"])["status"]
            cron.fire({}, run)
            self.assertEqual(before, self.records.get(row["id"])["status"])
            self.assertEqual(1, len(self.records.view("EY", "default")["notices"]))

    def test_agent_quota_exit_keeps_staged_evidence_as_partial(self):
        from career_core.mypage_agent import execute, _runs
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        browser = Mock(authenticated=True, blocked=False, pages={"1": {"text": "夏期プログラム 締切 2026年10月1日", "url": "https://portal.example.test/company/info", "authenticated": True}})
        class Agent:
            def run_conversation(self, message, task_id):
                portal = _runs[task_id]
                portal.started = True
                portal.invoke("career_mypage_save", {"page_id": "1", "title": "夏期プログラム", "quote": "締切 2026年10月1日"})
                raise CareerError("quota_paused", "fixture")
            def close(self):
                pass
        with patch.object(MyPageBotStore, "secret_store", return_value=self.secrets), patch("career_core.coach_execution.ExecutionPolicy.daily", return_value={"execution": {}}), patch("career_core.coach_execution.canonical_execution", return_value={"provider": "openai-codex"}):
            execute(self.store, row, run, browser_factory=lambda *args: browser, agent_factory=lambda *args, **kwargs: Agent())
        view = self.records.view("EY", "default")
        self.assertEqual("partial", view["status"]["state"])
        self.assertEqual("quota_paused", view["status"]["reason"])
        self.assertEqual(1, len(view["notices"]))
        self.assertNotIn("last_success_at", view["status"])

    def test_missing_runtime_and_cleanup_errors_keep_original_reason_without_secret_logs(self):
        from career_core.mypage_agent import execute, _runs
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        browser = Mock()
        browser.close.side_effect = RuntimeError("cleanup secret-password session-cookie")
        factory = Mock(side_effect=ModuleNotFoundError("private-key secret-password"))
        diagnostics = io.StringIO()
        with redirect_stderr(diagnostics), patch.object(MyPageBotStore, "secret_store", return_value=self.secrets), patch("career_core.coach_execution.ExecutionPolicy.daily", return_value={"execution": {}}), patch("career_core.coach_execution.canonical_execution", return_value={"provider": "openai-codex"}):
            execute(self.store, row, run, browser_factory=lambda *args: browser, agent_factory=factory)
        status = self.records.view("EY", "default")["status"]
        self.assertEqual("failed", status["state"])
        self.assertEqual("hermes_runtime_missing", status["reason"])
        self.assertNotIn("last_success_at", status)
        self.assertNotIn(run["id"], _runs)
        browser.start.assert_not_called()
        browser.close.assert_called_once()
        events = [json.loads(line) for line in diagnostics.getvalue().splitlines()]
        self.assertEqual(["agent_setup", "browser_cleanup"], [event["phase"] for event in events])
        self.assertEqual("ModuleNotFoundError", events[0]["exception_type"])
        self.assertTrue(events[0]["frames"])
        for secret in ("private-key", "secret-password", "session-cookie", self.tmp.name):
            self.assertNotIn(secret, diagnostics.getvalue())

    def test_unexpected_agent_error_preserves_partial_evidence_and_cleans_both_resources(self):
        from career_core.mypage_agent import execute, _runs
        row = self.configure()
        self.records.enqueue(row["id"])
        run = self.records.claim(row["id"])
        browser = Mock()
        agent = Mock()
        def conversation(*args, **kwargs):
            _runs[run["id"]].items.append({"title": "案内", "quote": "締切 2026年10月1日", "url": "https://portal.example.test/company/info"})
            raise ValueError("private response body")
        agent.run_conversation.side_effect = conversation
        agent.close.side_effect = RuntimeError("private cleanup details")
        diagnostics = io.StringIO()
        with redirect_stderr(diagnostics), patch.object(MyPageBotStore, "secret_store", return_value=self.secrets), patch("career_core.coach_execution.ExecutionPolicy.daily", return_value={"execution": {}}), patch("career_core.coach_execution.canonical_execution", return_value={"provider": "openai-codex"}):
            execute(self.store, row, run, browser_factory=lambda *args: browser, agent_factory=lambda *args, **kwargs: agent)
        view = self.records.view("EY", "default")
        self.assertEqual("partial", view["status"]["state"])
        self.assertEqual("mypage_execution_failed", view["status"]["reason"])
        self.assertEqual(1, len(view["notices"]))
        self.assertNotIn("last_success_at", view["status"])
        agent.close.assert_called_once()
        browser.close.assert_called_once()
        self.assertNotIn("private", diagnostics.getvalue())

    def test_browser_failure_allows_cached_evidence_save_but_never_false_completion(self):
        browser = Mock(authenticated=True, blocked=False, pages={"1": {"text": "募集 締切 2026年10月1日", "url": "https://portal.example.test/company/info", "authenticated": True}})
        browser.open.side_effect = CareerError("browser_operation_failed", "fixture")
        check = Mock()
        portal = PortalRun(browser, check)
        self.addCleanup(portal.close)
        portal.started = True
        with self.assertRaises(CareerError):
            portal.invoke("career_mypage_open", {"link_id": "2"})
        with self.assertRaises(CareerError):
            portal.invoke("career_mypage_open", {"link_id": "2"})
        browser.open.assert_called_once()
        with self.assertRaisesRegex(CareerError, "evidence_mismatch"):
            portal.invoke("career_mypage_save", {"page_id": "1", "title": "募集", "quote": "推測"})
        portal.invoke("career_mypage_save", {"page_id": "1", "title": "募集", "quote": "締切 2026年10月1日"})
        result = portal.invoke("career_mypage_finish", {"complete": True})
        self.assertEqual("partial", result["state"])
        self.assertFalse(portal.complete)
        self.assertEqual("browser_operation_failed", portal.error)
        self.assertEqual(1, len(portal.items))

    def test_cancelled_connection_cannot_save_cached_evidence_after_browser_failure(self):
        portal = PortalRun(Mock(), Mock(side_effect=CareerError("connection_changed", "fixture")))
        self.addCleanup(portal.close)
        portal.started, portal.error = True, "browser_operation_failed"
        with self.assertRaisesRegex(CareerError, "collection_cancelled"):
            portal.invoke("career_mypage_save", {})
        self.assertEqual([], portal.items)

    def test_job_boundary_does_not_mislabel_unexpected_errors_as_browser_failure(self):
        from career_core.mypage_job import run_one
        for company, error, reason in (("EY", ModuleNotFoundError("secret"), "hermes_runtime_missing"),
                                       ("JR東海", ValueError("secret"), "mypage_execution_failed")):
            row = self.configure(company)
            self.records.enqueue(row["id"])
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr), patch("career_core.mypage_native.execute", side_effect=error), patch("career_core.mypage_cron.reconcile", side_effect=RuntimeError("secret")):
                self.assertEqual(1, run_one(self.records, row["id"]))
            self.assertEqual(reason, stdout.getvalue().strip())
            self.assertEqual(reason, self.records.view(company, "default")["status"]["reason"])
            self.assertNotIn("secret", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
