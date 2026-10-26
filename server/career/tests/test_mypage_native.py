"""Native Bot state/policy tests; no account, model, or company requests."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from cryptography.fernet import Fernet
from career_core.mypage_bot import MyPageBotStore
from career_core.mypage_native import NativeRun, AUTH, RECORD, handle
from career_core.mypage_native_browser import NativeBrowser
from career_core.secret_store import SecretStore
from career_core.store import CareerError, Store


class FakeBrowser:
    def __init__(self, task, url, credentials):
        self.authenticated = False
        self.pages = {"seen": {"url": url.replace("login", "notice"), "text": "対象年度：2028\n募集締切 2026年10月1日", "authenticated": True}}
        self.credentials = credentials

    def login(self):
        self.authenticated = True
        return {"success": True}

    def forget(self):
        self.credentials.clear()


class NativeStateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / "data")
        secrets = SecretStore(Path(self.tmp.name) / "secrets", key=Fernet.generate_key())
        self.records = MyPageBotStore(self.store, secrets)
        with self.store.transaction() as db:
            self.store.put(db, "companies", "fixture", 0, {"name": "fixture"})
        self.host = patch("career_core.mypage_browser.public_host", return_value="fixture.test")
        self.host.start()
        self.addCleanup(self.host.stop)
        self.row = self.records.configure("fixture", "default", {"url": "https://fixture.test/login", "login_id": "private-id", "password": "private-password", "enabled": True, "expected_version": 0})
        self.records.enqueue(self.row["id"])
        self.run = self.records.claim(self.row["id"])
        self.native = NativeRun(self.records, self.row, self.run, "task", "session", FakeBrowser)
        self.native.invoke(AUTH, {})

    def save(self):
        return self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集", "quote": "対象年度：2028\n募集締切 2026年10月1日"})

    def state(self):
        with self.store.connect() as db:
            return dict(db.execute("SELECT * FROM career_mypage_runs WHERE id=?", (self.run["id"],)).fetchone())

    def test_checkpoint_survives_interruption_and_preserves_source(self):
        self.save()
        self.assertEqual("running", self.state()["state"])
        self.assertEqual(1, len(self.records.view("fixture", "default")["notices"]))
        self.native.settle()
        self.assertEqual("partial", self.state()["state"])
        self.assertFalse(self.records.get(self.row["id"])["config"]["verified"])
        self.assertEqual({}, self.native.browser.credentials)

    def test_success_and_repeat_do_not_duplicate_evidence_or_notices(self):
        self.save()
        self.save()
        self.native.invoke(RECORD, {"action": "finish", "complete": True})
        self.native.settle()
        view = self.records.view("fixture", "default")
        self.assertTrue(view["verified"])
        self.assertEqual("updated", view["status"]["state"])
        self.assertEqual(1, len(view["history"]))
        self.assertEqual(1, json.loads(self.state()["result"])["changed"])
        with self.store.connect() as db:
            before = db.execute("SELECT COUNT(*) FROM career_notice_feed").fetchone()[0]
        self.records.enqueue(self.row["id"])
        later = self.records.claim(self.row["id"])
        self.records.finish(later, "completed", items=self.records.checkpoints(self.run))
        with self.store.connect() as db:
            self.assertEqual(before, db.execute("SELECT COUNT(*) FROM career_notice_feed").fetchone()[0])

    def test_fabricated_quote_and_foreign_page_rejected(self):
        for args in ({"page_id": "foreign", "quote": "募集"}, {"page_id": "seen", "quote": "2026年12月31日"}):
            with self.assertRaises(CareerError):
                self.native.invoke(RECORD, dict(action="save", title="募集", **args))
        self.assertEqual([], self.records.checkpoints(self.run))

    def test_save_observed_page_without_retyping_quote(self):
        self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集"})
        self.assertEqual(self.native.browser.pages["seen"]["text"], self.records.checkpoints(self.run)[0]["quote"])

    def test_line_selection_uses_original_text_and_rejects_invalid_ranges(self):
        args = {"action": "save", "page_id": "seen", "title": "募集", "line_start": 2, "line_end": 2}
        for invalid in ({"line_start": 0}, {"line_end": 3}, {"line_start": True}, {"line_start": 2.0}, {"quote": "fabricated"}, {"page_id": "foreign"}):
            with self.subTest(invalid=invalid), self.assertRaises(CareerError):
                self.native.invoke(RECORD, dict(args, **invalid))
        self.native.invoke(RECORD, args)
        self.assertEqual("募集締切 2026年10月1日", self.records.checkpoints(self.run)[0]["quote"])

    def test_large_page_requires_explicit_range_without_silent_truncation(self):
        self.native.browser.pages["seen"]["text"] = "a" * 10000 + "\n締切原文"
        with self.assertRaises(CareerError):
            self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集"})
        self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集", "line_start": 2, "line_end": 2})
        self.assertEqual("締切原文", self.records.checkpoints(self.run)[0]["quote"])

    def test_finish_explains_partial_without_logging_page_or_credentials(self):
        self.save()
        self.native.browser.incomplete = True
        with patch("career_core.mypage_job.report_event") as event:
            self.native.invoke(RECORD, {"action": "finish", "complete": True})
        self.assertEqual({"requested_complete": True, "unread_snapshot": True, "saved_sources": 1, "state": "partial"}, event.call_args.kwargs)

    def test_save_and_next_checkpoint_before_navigation(self):
        def navigate(name, args):
            self.assertEqual(1, len(self.records.checkpoints(self.run)))
            self.assertEqual("browser_navigate", name)
            self.assertEqual({"url": "https://fixture.test/notice/2"}, args)
            return {"page_id": "next", "snapshot": "next source"}
        self.native.browser.invoke = navigate
        result = self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集", "next_url": "https://fixture.test/notice/2"})
        self.assertTrue(result["saved"])
        self.assertEqual("next", result["next_page"]["page_id"])

    def test_next_navigation_failure_preserves_saved_source(self):
        self.native.browser.invoke = Mock(side_effect=CareerError("browser_operation_failed", "private", 409))
        response = json.loads(handle(self.native, RECORD, {"action": "save", "page_id": "seen", "title": "募集", "next_url": "https://fixture.test/notice/2"}, task_id="task", session_id="session"))
        self.assertEqual("browser_operation_failed", response["error"])
        self.assertEqual("partial", self.state()["state"])
        self.assertEqual(1, len(self.records.checkpoints(self.run)))

    def test_save_next_rechecks_connection_before_navigation(self):
        checkpoint = self.records.checkpoint
        def change_connection(*args):
            checkpoint(*args)
            self.records.configure("fixture", "default", {"url": "https://fixture.test/login", "enabled": False, "expected_version": 1})
        self.native.browser.invoke = Mock()
        with patch.object(self.records, "checkpoint", side_effect=change_connection), self.assertRaises(CareerError):
            self.native.invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集", "next_url": "https://fixture.test/notice/2"})
        self.native.browser.invoke.assert_not_called()

    def test_turn_limit_diagnostic_does_not_log_raw_exit_text(self):
        with patch("career_core.mypage_job.report_event") as event:
            self.execute_fixture({"failure_reason": "max_iterations private-password", "api_calls": 32})
        values = [call.kwargs for call in event.call_args_list if call.args[0] == "mypage_native_exit"]
        self.assertEqual([{"category": "turn_limit", "api_calls": 32, "saved_sources": 1, "finished": False}], values)
        self.assertNotIn("private-password", str(event.call_args_list))

    def test_cron_retains_safe_timing_on_success_and_restores_output_scope(self):
        from career_core import mypage_job as job
        def task(*args):
            job.report_event("mypage_native_timing", "fixture", phase="total", elapsed_ms=1000, success=True)
            return 0
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(job, "_main", side_effect=task), patch("sys.stdout", stdout), patch("sys.stderr", stderr):
            self.assertEqual(0, job.main("fixture", "fixture"))
            job.report_event("after_cron", None)
        self.assertTrue(json.loads(stdout.getvalue())["success"])
        self.assertEqual("after_cron", json.loads(stderr.getvalue())["event"])
        with patch.object(job, "_main", side_effect=RuntimeError("fixture")), self.assertRaises(RuntimeError):
            job.main("fixture", "fixture")
        self.assertFalse(job._cron_output.get())

    def test_empty_requires_observed_post_login_proof(self):
        with self.assertRaises(CareerError):
            self.native.invoke(RECORD, {"action": "finish", "complete": True})
        self.native.browser.pages["seen"]["text"] = "該当なし"
        self.native.invoke(RECORD, {"action": "finish", "complete": True, "page_id": "seen", "empty_quote": "該当なし"})
        self.assertEqual("completed", self.state()["state"])

    def test_truncated_page_cannot_be_marked_complete(self):
        self.save()
        self.native.browser.incomplete = True
        result = self.native.invoke(RECORD, {"action": "finish", "complete": True})
        self.assertEqual("partial", result["state"])
        self.assertFalse(self.records.view("fixture", "default")["verified"])

    def test_crashed_run_is_recovered_as_partial_and_keeps_checkpoint(self):
        self.save()
        from career_core.store import encoded
        with self.store.transaction() as db:
            db.execute("UPDATE career_mypage_runs SET updated_at='2000-01-01' WHERE id=?", (self.run["id"],))
            row = self.records.get(self.row["id"], db)
            row["status"]["last_attempt_at"] = "2000-01-01"
            db.execute("UPDATE career_mypage_connections SET status=? WHERE id=?", (encoded(row["status"]), self.row["id"]))
        self.assertEqual("partial", self.records.view("fixture", "default")["status"]["state"])
        self.records.enqueue(self.row["id"])
        self.assertEqual("partial", self.state()["state"])
        self.assertEqual(1, len(self.records.view("fixture", "default")["notices"]))

    def test_limit_and_auth_failure_are_not_normal_empty(self):
        self.native.started -= 481
        with self.assertRaises(CareerError) as error:
            self.native.check()
        self.assertEqual("collection_limit", error.exception.code)
        self.native.error = "authentication_challenge"
        self.native.settle()
        self.assertEqual("auth_required", self.state()["state"])
        self.assertIsNone(self.records.enqueue(self.row["id"], scheduled=True))

    def test_changed_connection_cannot_save(self):
        self.records.configure("fixture", "default", {"url": "https://fixture.test/login", "enabled": True, "expected_version": 1})
        with self.assertRaises(CareerError):
            self.save()
        self.assertEqual("cancelled", self.state()["state"])

    def test_raw_errors_never_reach_model_or_diagnostics(self):
        from contextlib import redirect_stderr
        log = io.StringIO()
        with patch.object(self.native, "invoke", side_effect=ValueError("private-password Cookie=session-secret")), redirect_stderr(log):
            result = handle(self.native, AUTH, {}, task_id="task", session_id="session")
        self.assertNotIn("private-password", result + log.getvalue())
        self.assertNotIn("session-secret", result + log.getvalue())
        self.assertEqual("failed", self.state()["state"])

    def test_foreign_session_rejected_before_tool_access(self):
        for metadata in ({}, {"task_id": "other", "session_id": "session"}, {"task_id": "task", "session_id": "other"}):
            with patch.object(self.native, "invoke") as invoke:
                result = handle(self.native, AUTH, {}, **metadata)
            self.assertEqual("portal_run_not_authorized", json.loads(result)["error"])
            invoke.assert_not_called()

    def test_config_edit_preserves_standard_route_and_existing_job(self):
        self.records.update_config(self.row["id"], 1, {"engine": "standard", "job_id": "existing"})
        row = self.records.configure("fixture", "default", {"url": "https://fixture.test/login", "enabled": False, "expected_version": 1})
        self.assertEqual("standard", row["config"]["engine"])
        self.assertEqual("existing", row["config"]["job_id"])

    def test_new_connection_uses_standard_without_manual_cutover(self):
        self.assertEqual("standard", self.row["config"]["engine"])
        self.native.invoke(RECORD, {"action": "finish", "complete": False})
        self.records.enqueue(self.row["id"])
        from career_core.mypage_job import run_one
        def finish(store, row, run):
            self.records.finish(run, "completed")
        with patch("career_core.mypage_agent.execute") as old, patch("career_core.mypage_native.execute", side_effect=finish) as native, patch("career_core.mypage_cron.reconcile"), patch("sys.stdout", io.StringIO()):
            self.assertEqual(0, run_one(self.records, self.row["id"]))
        old.assert_not_called()
        native.assert_called_once()

    def test_config_edit_does_not_implicitly_migrate_existing_legacy(self):
        from career_core.store import encoded
        for explicit in (True, False):
            with self.subTest(explicit=explicit):
                row = self.records.get(self.row["id"])
                row["config"].pop("engine", None)
                if explicit:
                    row["config"]["engine"] = "legacy"
                with self.store.transaction() as db:
                    db.execute("UPDATE career_mypage_connections SET config=? WHERE id=?", (encoded(row["config"]), row["id"]))
                edited = self.records.configure("fixture", "default", {"url": "https://fixture.test/login", "enabled": False, "expected_version": row["version"]})
                self.assertEqual("legacy", edited["config"]["engine"])

    def test_same_cron_dispatches_only_the_selected_engine(self):
        self.native.invoke(RECORD, {"action": "finish", "complete": False})
        self.records.update_config(self.row["id"], 1, {"engine": "standard"})
        self.records.enqueue(self.row["id"])
        from career_core.mypage_job import run_one
        def finish(store, row, run):
            self.records.finish(run, "completed")
        with patch("career_core.mypage_agent.execute") as old, patch("career_core.mypage_native.execute", side_effect=finish) as native, patch("career_core.mypage_cron.reconcile"), patch("sys.stdout", io.StringIO()):
            self.assertEqual(0, run_one(self.records, self.row["id"]))
        old.assert_not_called()
        native.assert_called_once()

    def execute_fixture(self, result, error=None):
        from career_core import mypage_native as native
        from career_core.mypage_agent import model_credentials
        active = []
        agent = Mock(career_execution_error=None)
        def conversation(*args, **kwargs):
            active[0].invoke(AUTH, {})
            active[0].invoke(RECORD, {"action": "save", "page_id": "seen", "title": "募集", "quote": "募集締切 2026年10月1日"})
            if error:
                raise error
            return result
        agent.run_conversation.side_effect = conversation
        with patch.object(native, "MyPageBotStore", return_value=self.records), patch.object(native, "NativeBrowser", FakeBrowser), patch.object(native, "register_tools", side_effect=active.append), patch("career_core.coach_execution.ExecutionPolicy.daily", return_value={"execution": {"provider": "openrouter", "model": "fixture/model:free", "reasoning_effort": "default"}}), patch("career_core.coach_runtime.make_agent", return_value=agent) as make, patch("sys.stderr", io.StringIO()):
            native.execute(self.store, self.row, self.run)
        self.assertIs(model_credentials, make.call_args.kwargs["credential_resolver"])
        agent.close.assert_called_once()
        self.assertEqual({}, active[0].browser.credentials)

    def test_native_executor_preserves_checkpoint_on_model_failure(self):
        self.execute_fixture({}, error=RuntimeError("private-provider-detail"))
        self.assertEqual("partial", self.state()["state"])
        self.assertEqual("mypage_execution_failed", json.loads(self.state()["result"])["reason"])

    def test_native_executor_budget_exit_is_partial(self):
        self.execute_fixture({"failed": True, "failure_reason": "run_budget_exceeded"})
        self.assertEqual("partial", self.state()["state"])
        self.assertEqual("collection_limit", json.loads(self.state()["result"])["reason"])


class NativePolicyTests(unittest.TestCase):
    def setUp(self):
        self.browser = NativeBrowser("task", "https://fixture.test/login", {"login_id": 'private"id', "password": "private\npassword"}, validate_host=lambda url: None)

    def test_origin_and_write_urls_are_rejected(self):
        for url in ("https://evil.test/read", "https://fixture.test/apply", "https://fixture.test/logout", "https://fixture.test/read?token=secret"):
            with self.assertRaises(CareerError):
                self.browser.safe_url(url)

    def test_login_input_button_uses_observed_label_in_validated_form(self):
        with patch.object(self.browser, "command", side_effect=[{"count": 0}, {"count": 1}, {"value": "ログイン"}]):
            selector = self.browser.login_submit_selector('form:has(input[type="password"])')
        self.assertEqual('form:has(input[type="password"]) input[type="button"]', selector)

    def test_login_button_does_not_accept_ambiguous_or_write_controls(self):
        for answers in ([{"count": 2}], [{"count": 0}, {"count": 2}], [{"count": 0}, {"count": 1}, {"value": "応募する"}], [{"count": 0}, {"count": 1}, {"value": "ログインして応募する"}]):
            with self.subTest(answers=answers), patch.object(self.browser, "command", side_effect=answers), self.assertRaises(CareerError):
                self.browser.login_submit_selector('form')

    def test_regular_login_submit_uses_native_supported_selector(self):
        with patch.object(self.browser, "command", return_value={"count": 1}):
            self.assertIn('input[type="submit"]', self.browser.login_submit_selector('form'))

    def test_json_escaped_secrets_redacted(self):
        output = self.browser.redact({"text": 'private"id private\npassword'})
        self.assertEqual("[private] [private]", output["text"])

    def test_numbered_snapshot_keeps_unmodified_source_for_record(self):
        with patch.object(self.browser, "command", return_value={"url": "https://fixture.test/notice"}):
            result = self.browser.capture({"success": True, "snapshot": "- heading 募集\n- text: 締切原文"})
        self.assertEqual("1: - heading 募集\n2: - text: 締切原文", result["snapshot"])
        self.assertEqual("- heading 募集\n- text: 締切原文", self.browser.pages[result["page_id"]]["text"])


class NativeNavigationTests(unittest.TestCase):
    def test_only_literal_form_submission_grammar_is_recognized(self):
        from career_core.mypage_native_navigation import form_selector
        self.assertEqual("form#formList", form_selector("javascript:$('#formList').submit();"))
        self.assertEqual('form[name="formReturn"]', form_selector("javascript:document.formReturn.submit();"))
        for value in ("$('#formList').submit();fetch('/apply')", "document.formReturn.submit(foo)", "document['formReturn'].submit()"):
            self.assertIsNone(form_selector(value))

    def test_numeric_detail_destination_parsed_without_executing_script(self):
        from career_core.mypage_native_navigation import function_destination
        declaration = '''function formDetailSubmit(msgcd, funtype) {
          action = "/portal/message/detail/msgcd/" + msgcd;
          $('#formDetail').attr('action', action);
          $('form#formDetail input:hidden[name="funtype"]').val(funtype);
          $('#formDetail').submit();
        }'''
        self.assertEqual("/portal/message/detail/msgcd/2", function_destination("javascript:formDetailSubmit(2);", declaration))
        for handler, source in (("formDetailSubmit('secret')", declaration), ("formDetailSubmit(2);fetch('/apply')", declaration), ("formDetailSubmit(2)", declaration.replace("action =", "fetch('/apply'); action ="))):
            with self.assertRaises(CareerError):
                function_destination(handler, source)

    def test_dynamic_form_route_cannot_submit_or_leave_origin(self):
        from career_core.mypage_native_navigation import read_destination
        browser = NativeBrowser("task", "https://fixture.test/login", {}, validate_host=lambda _: None)
        for target in ("/apply", "/message/index?password=secret", "https://other.test/message/index", "/message/detail/../../apply"):
            with patch.object(browser, "command", side_effect=[{"count": 1}, {"value": target}]), self.assertRaises(CareerError):
                read_destination(browser, "@e1", "javascript:document.formReturn.submit();")
        with patch.object(browser, "command", side_effect=[{"count": 1}, {"value": "/message/index"}]):
            self.assertEqual("https://fixture.test/message/index", read_destination(browser, "@e1", "javascript:document.formReturn.submit();"))


if __name__ == "__main__":
    unittest.main()
