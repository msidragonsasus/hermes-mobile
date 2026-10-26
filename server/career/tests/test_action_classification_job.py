"""Synthetic owner-job invocation through real policy, fake model, cache and Home."""
import contextlib
import io
import json
import sqlite3
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from career_core.action_classification import ActionSource
from career_core.action_classification_job import ExistingActionStore, main, run_once
from career_core.home import HomeView
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store
from test_action_classification import fixture_source, matched

EXECUTION = {"provider": "fixture-existing", "model": "configured-budget", "reasoning_effort": "default"}


class OwnerJobTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(self.temp.name)
        MailStore(self.store)
        with self.store.transaction() as db:
            self.store.put(db, "companies", "company", 0, {"name": "合成企業", "user_priority": "high"})
            self.store.set_meta(db, "mail:settings", {"enabled": True, "account_id": "fixture-account"})
            self.store.set_meta(db, "coach_daily:default", {"version": 1, "execution": EXECUTION, "source": "user"})
            self.store.set_meta(db, "coach_model_catalog", {
                "state": "available", "checked_at": datetime.now(timezone.utc).isoformat(),
                "models": [{"provider": EXECUTION["provider"], "id": EXECUTION["model"], "reasoning_efforts": ["default"]}],
            })
        self.view = HomeView(self.store, lambda: datetime(2026, 9, 29, tzinfo=timezone.utc))
        home = patch("career_core.home.HomeView", return_value=self.view)
        home.start()
        self.addCleanup(home.stop)
        self.calls = []
        self.closed = []
        self.behavior = None

    def save(self, identity, source=None, subject="ES提出をお願いします", tail=""):
        source = source or fixture_source()
        body = source.text + tail
        with self.store.transaction() as db:
            db.execute("INSERT OR REPLACE INTO career_mail_messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       ("fixture-account", identity, "fixture-thread", "company", "fixture@example.org", "合成企業",
                        subject, "", body, "https://example.org/mail/" + identity, "2026-09-29T00:00:00Z",
                        1, "2026-09-29", "2026-09-29"))

    def factory(self, session, prompt, **options):
        self.assertEqual(EXECUTION, options["execution"])
        self.assertFalse(options["tools_enabled"])
        self.assertIn("A refusal to extend", prompt)
        outer = self

        class FakeAgent:
            def run_conversation(self, payload):
                value = json.loads(payload)
                source = ActionSource(value["source_id"], value["source_version"], value["company_id"],
                                      value["text"], value["deadline"])
                outer.calls.append(source)
                # The runner must not leak runtime prints to its JSON output.
                print("synthetic-private-runtime-output")
                if outer.behavior:
                    response = outer.behavior(source, len(outer.calls))
                    if response is not None:
                        return response
                action = "answer_survey" if "参加後アンケート" in source.text else "submit_es"
                return {"completed": True, "final_response": json.dumps(matched(source, action), ensure_ascii=False)}

            def close(self):
                outer.closed.append(True)

        return FakeAgent()

    def run_job(self, limit=5, **options):
        # Only a fresh cached configured inventory is read; no network discovery.
        with patch("career_core.coach_execution.discover_models", side_effect=AssertionError("No provider call")):
            with contextlib.redirect_stdout(io.StringIO()):
                return run_once(self.store, "default", limit, factory=self.factory, **options)

    def caches(self):
        with self.store.connect() as db:
            return list(db.execute("SELECT key,value FROM metadata WHERE key LIKE 'home-action-classification:%'"))

    def test_stored_same_subject_and_different_reminders_group_rounds_and_actions_stay_distinct(self):
        self.save("a")
        self.save("b", tail="\n同じ件名のリマインド。")
        self.save("c", subject="再案内 ES提出をお願いします", tail="\n別件名のリマインド。")
        self.save("d", fixture_source(round_name="二次募集", due="2026-10-01T23:59+09:00"))
        self.save("e", fixture_source(program="1Day", round_name="参加後", action="answer_survey"),
                  subject="参加後アンケート回答をお願いします")
        before = self.view.read()["items"]
        self.assertTrue(all("action_identity" not in item for item in before))
        result = self.run_job()
        self.assertEqual(5, result["matched"])
        after = self.view.read()["items"]
        self.assertEqual({(i["id"], i["version"], i["target"]["source_id"]) for i in before},
                         {(i["id"], i["version"], i["target"]["source_id"]) for i in after})
        groups = {}
        for item in after:
            identity = item["action_identity"]
            self.assertEqual(item["id"], identity["source_id"])
            self.assertEqual(item["version"], identity["source_version"])
            self.assertEqual(5, len(identity["evidence"]))
            groups.setdefault(identity["key"], []).append(item["target"]["source_id"])
        self.assertEqual([1, 1, 3], sorted(map(len, groups.values())))
        with self.store.connect() as db:
            self.assertEqual(5, db.execute("SELECT COUNT(*) FROM career_mail_messages").fetchone()[0])
            before_get = list(db.iterdump())
        calls = len(self.calls)
        self.view.read()
        with self.store.connect() as db:
            self.assertEqual(before_get, list(db.iterdump()))
        self.assertEqual(calls, len(self.calls))

    def test_negated_extension_keeps_deadline_and_source_body_then_joins_same_action(self):
        self.save("a")
        self.save("b", subject="最終案内 ES提出をお願いします",
                  tail="\nアクセス混雑を理由とした応募期限の延長はいたしかねます。")
        before = self.view.read()["items"]
        self.assertTrue(all("期限変更の案内" not in i["detail"] for i in before))
        with self.store.connect() as db:
            original = [tuple(row) for row in db.execute("SELECT * FROM career_mail_messages ORDER BY message_id")]
        self.run_job()
        items = self.view.read()["items"]
        self.assertEqual(1, len({i["action_identity"]["key"] for i in items}))
        self.assertEqual({"2026-09-30T23:59+09:00"}, {i["deadline"] for i in items})
        with self.store.connect() as db:
            self.assertEqual(original, [tuple(row) for row in db.execute("SELECT * FROM career_mail_messages ORDER BY message_id")])

    def test_idempotent_rerun_and_abstention_do_not_call_model_again(self):
        self.save("a")
        self.behavior = lambda source, n: {"completed": True, "final_response": json.dumps(
            {"status": "abstain", "source_id": source.id, "source_version": source.version})}
        self.assertEqual(1, self.run_job()["abstained"])
        self.assertEqual(1, len(self.caches()))
        self.assertEqual(0, self.run_job()["classified"])
        self.assertEqual(1, len(self.calls))
        self.assertNotIn("action_identity", self.view.read()["items"][0])

    def test_changed_source_gets_new_version_and_preserves_old_cache_history(self):
        self.save("a")
        self.run_job()
        old = self.view.read()["items"][0]
        self.save("a", fixture_source(round_name="二次募集", due="2026-10-01T23:59+09:00"))
        current = self.view.read()["items"][0]
        self.assertNotEqual(old["version"], current["version"])
        self.assertNotIn("action_identity", current)
        self.run_job()
        updated = self.view.read()["items"][0]
        self.assertNotEqual(old["action_identity"]["key"], updated["action_identity"]["key"])
        self.assertEqual(2, len(self.caches()))
        self.assertEqual(2, len(self.calls))

    def test_failure_or_partial_second_response_never_commits_a_partial_batch(self):
        for mode in ("failed", "partial", "exception", "invalid"):
            with self.subTest(mode=mode):
                self.save("a")
                self.save("b", tail="\n再案内。")
                self.calls.clear()
                def behavior(source, count):
                    if count != 2:
                        return None
                    if mode == "exception":
                        raise RuntimeError("synthetic-private-error")
                    if mode == "invalid":
                        return {"completed": True, "final_response": '{"status":"matched"}'}
                    return {mode: True, "completed": False, "error": "synthetic-private-error"}
                self.behavior = behavior
                with self.assertRaises(CareerError):
                    self.run_job()
                self.assertEqual([], self.caches())
                self.assertEqual(2, len(self.calls))

    def test_mid_batch_source_or_company_version_change_refuses_all_writes(self):
        for kind in ("mail", "company"):
            with self.subTest(kind=kind):
                self.save("a")
                self.save("b", tail="\n再案内。")
                self.calls.clear()
                def behavior(source, count):
                    if count == 2:
                        if kind == "mail":
                            self.save("b", tail="\n新しい版。")
                        else:
                            with self.store.transaction() as db:
                                old = self.store.entity(db, "companies", "company")
                                self.store.put(db, "companies", "company", old["version"],
                                               {"name": "変更後の合成企業", "user_priority": "high"})
                    return None
                self.behavior = behavior
                with self.assertRaises(CareerError) as error:
                    self.run_job()
                self.assertEqual("home_changed", error.exception.code)
                self.assertEqual([], self.caches())

    def test_existing_catalogue_blocks_unconfigured_execution_and_limits_before_model_calls(self):
        self.save("a")
        with self.assertRaises(CareerError):
            self.run_job(execution=dict(EXECUTION, model="unconfigured-model"))
        for limit, pages in [(0, 3), (11, 3), (True, 3), (1, 11), (1, 0)]:
            with self.assertRaises(CareerError):
                self.run_job(limit=limit, max_pages=pages)
        self.assertEqual([], self.calls)
        self.assertEqual([], self.caches())

    def test_source_resolved_or_expired_during_inference_rejects_cache_commit(self):
        for kind in ("resolved", "expired"):
            with self.subTest(kind=kind):
                self.save(kind)
                self.calls.clear()
                def behavior(source, count):
                    if kind == "resolved":
                        self.view.resolve_source(source.id, source.version)
                    else:
                        self.view.clock = lambda: datetime(2026, 10, 1, tzinfo=timezone.utc)
                    return None
                self.behavior = behavior
                with self.assertRaises(CareerError) as error:
                    self.run_job()
                self.assertEqual("home_changed", error.exception.code)
                self.assertEqual([], self.caches())

    def test_existing_store_does_not_recreate_database_removed_after_attach(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            MailStore(store)
            attached = ExistingActionStore(directory)
            store.path.unlink()
            with self.assertRaises(sqlite3.Error):
                attached.connect()
            self.assertFalse(store.path.exists())

    def test_scan_cap_is_reported_without_silent_loop_or_retry(self):
        for n in range(102):
            self.save(str(n).zfill(3))
        self.run_job(limit=10, max_pages=1)
        for _ in range(9):
            self.run_job(limit=10, max_pages=1)
        calls = len(self.calls)
        result = self.run_job(limit=10, max_pages=1)
        self.assertEqual(0, result["classified"])
        self.assertTrue(result["scan_limited"])
        self.assertEqual(1, result["scan_pages"])
        self.assertEqual(calls, len(self.calls))
        self.assertEqual(2, self.run_job(limit=10, max_pages=2)["classified"])

    def test_cli_runs_complete_path_with_safe_json_and_explicit_execute_guard(self):
        self.save("a")
        args = ["--data-dir", self.temp.name, "--profile", "default", "--limit", "1", "--execute"]
        original = run_once
        with patch("career_core.action_classification_job.run_once",
                   side_effect=lambda *a, **kw: original(*a, **kw, factory=self.factory)):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, main(args))
        report = json.loads(output.getvalue())
        self.assertEqual(1, report["cache_results_committed"])
        self.assertEqual(EXECUTION, report["execution"])
        self.assertNotIn("synthetic-private", output.getvalue())
        self.assertIn("action_identity", self.view.read()["items"][0])
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                main(args[:-1])
        self.assertEqual(2, error.exception.code)
        self.assertEqual(1, len(self.calls))

    def test_missing_database_is_not_created_and_cli_failure_has_no_private_exception(self):
        missing = Path(self.temp.name) / "not-created"
        with self.assertRaises(CareerError):
            ExistingActionStore(missing)
        self.assertFalse(missing.exists())
        output = io.StringIO()
        with patch("career_core.action_classification_job.run_once", side_effect=RuntimeError("synthetic-private-secret")):
            with contextlib.redirect_stdout(output):
                self.assertEqual(1, main(["--data-dir", self.temp.name, "--profile", "default", "--limit", "1", "--execute"]))
        self.assertEqual({"status": "blocked", "reason": "action_job_failed", "cache_results_committed": 0},
                         json.loads(output.getvalue()))


if __name__ == "__main__":
    unittest.main()
