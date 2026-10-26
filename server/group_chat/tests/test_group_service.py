import sys
import sqlite3
import tempfile
import threading
import time
import unittest
from pathlib import Path
from contextlib import contextmanager
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_service import GroupError, GroupService


class Runtime:
    def __init__(self):
        self.calls = []
        self.entered, self.release = threading.Event(), threading.Event()
        self.release.set()
        self.replies = []

    def validate(self, members):
        if any(m not in {"alice", "bob"} for m in members):
            raise GroupError("member_not_found", 404)

    def reply(self, member, prompt, stopped):
        self.calls.append(member)
        self.entered.set()
        while not self.release.wait(.01):
            if stopped.is_set():
                raise GroupError("stopped")
        return self.replies.pop(0) if self.replies else "意見: " + member


def request(**changes):
    return dict(requestId="request-1", groupName="開発会議", text="設計を議論してください",
                members=["alice", "bob"], rounds=2, controlledOrder=True,
                maxMessages=6, summaryMember="alice") | changes


class GroupServiceTest(unittest.TestCase):
    def test_terminal_database_lock_retries_receipt_without_replaying_member(self):
        self.service.submit("conversation", request(direct=True, members=["alice"], initialResponders=["alice"]))
        original = self.service.db
        attempts = []
        class Connection:
            def __init__(self, connection):
                self.connection = connection
            def execute(self, sql, *args):
                if sql.startswith("UPDATE runs SET state=?,error=?"):
                    attempts.append(sql)
                    if len(attempts) == 1:
                        raise sqlite3.OperationalError("locked")
                return self.connection.execute(sql, *args)
        @contextmanager
        def database():
            with original() as connection:
                yield Connection(connection)
        with patch.object(self.service, "db", database):
            self.assertTrue(self.service.run_one())
        self.assertEqual((2, ["alice"]), (len(attempts), self.runtime.calls))
        self.assertEqual("complete", self.service.snapshot("conversation", "開発会議")["state"])

    def test_claimed_run_setup_failure_is_terminal_without_model_replay(self):
        self.service.submit("conversation", request())
        with patch.object(self.service.supervision, "cancellation", side_effect=sqlite3.OperationalError("locked")):
            self.assertTrue(self.service.run_one())
        self.assertEqual("failed", self.service.snapshot("conversation", "開発会議")["state"])
        self.assertEqual([], self.runtime.calls)
        self.assertFalse(self.service.run_one())

    def test_worker_recovers_iteration_failure_and_processes_next_work(self):
        failures = [sqlite3.OperationalError("locked"), None]
        def heartbeat():
            failure = failures.pop(0) if failures else None
            if failure:
                raise failure
        def run():
            self.service.closed.set()
            return True
        self.service.wake.set()
        with patch.object(self.service.supervision, "heartbeat", side_effect=heartbeat), \
             patch.object(self.service.supervision, "tick", return_value=False), \
             patch.object(self.service, "run_one", side_effect=run) as execute:
            self.service.loop()
        self.assertEqual(1, execute.call_count)

    def test_member_diagnostics_survive_restart_without_replaying_or_rewriting_saved_messages(self):
        self.service.submit("conversation", request())
        failure = GroupError("runtime_launch_failed", diagnostics={"stage": "member", "kind": "launch", "errno": 7})
        with patch.object(self.runtime, "reply", side_effect=failure):
            self.service.run_one()
        before = self.service.snapshot("conversation", "開発会議")
        self.assertEqual("runtime_launch_failed", before["error"])
        self.assertEqual(7, before["diagnostics"]["errno"])
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        after = self.service.snapshot("conversation", "開発会議")
        self.assertEqual(before, after)
        self.assertEqual([], self.runtime.calls)

    def test_generated_title_survives_restart_without_changing_messages_runs_or_history_order(self):
        self.service.submit("conversation", request())
        before = self.service.snapshot("conversation", "開発会議")
        updated = self.service.rename("conversation", dict(groupName="開発会議", title="設計方針の比較", expectedTitle=before["title"]))
        self.assertEqual("設計方針の比較", updated["title"])
        self.assertEqual(before["messages"], updated["messages"])
        self.assertEqual(before["runId"], updated["runId"])
        self.assertEqual(before["state"], updated["state"])
        self.assertEqual([], self.runtime.calls)
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        self.assertEqual("設計方針の比較", self.service.history()[0]["title"])
        self.service.run_one()
        self.assertEqual("設計方針の比較", self.service.snapshot("conversation", "開発会議")["title"])

    def test_stale_or_wrong_group_title_update_never_overwrites_a_conversation(self):
        self.service.submit("conversation", request())
        before = self.service.snapshot("conversation", "開発会議")
        with self.assertRaises(GroupError) as wrong:
            self.service.rename("conversation", dict(groupName="other", title="Wrong", expectedTitle=before["title"]))
        self.assertEqual(404, wrong.exception.status)
        with self.assertRaises(GroupError) as stale:
            self.service.rename("conversation", dict(groupName="開発会議", title="Wrong", expectedTitle="stale"))
        self.assertEqual("title_conflict", stale.exception.code)
        self.assertEqual(before, self.service.snapshot("conversation", "開発会議"))
        for title in (None, "", " ", "a\nb", "x" * 81):
            with self.subTest(title=title), self.assertRaises(GroupError):
                self.service.rename("conversation", dict(groupName="開発会議", title=title, expectedTitle=before["title"]))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "history.db"
        self.runtime = Runtime()
        self.service = GroupService(self.path, self.runtime, start=False)

    def tearDown(self):
        self.runtime.release.set()
        self.service.close()
        self.temp.cleanup()

    def test_acceptance_is_durable_and_duplicate_request_does_not_execute_twice(self):
        self.service.submit("conversation", request())
        self.service.submit("conversation", request())
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        self.assertTrue(self.service.run_one())
        self.assertFalse(self.service.run_one())
        result = self.service.snapshot("conversation", "開発会議")
        self.assertEqual("complete", result["state"])
        self.assertEqual(["alice", "bob", "alice", "bob", "alice"], self.runtime.calls)
        self.assertEqual(6, len(result["messages"]))
        with self.assertRaises(GroupError):
            self.service.submit("conversation", request(text="changed"))

    def test_reading_or_discarding_snapshot_does_not_stop_server(self):
        self.runtime.release.clear()
        self.service.submit("conversation", request())
        worker = threading.Thread(target=self.service.run_one)
        worker.start()
        self.assertTrue(self.runtime.entered.wait(2))
        self.assertEqual("running", self.service.snapshot("conversation", "開発会議")["state"])
        # No reader survives beyond this point; the server must drive all remaining turns.
        self.runtime.release.set()
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual("complete", self.service.snapshot("conversation", "開発会議")["state"])
        self.assertEqual(5, len(self.runtime.calls))

    def test_explicit_stop_prevents_following_turns(self):
        self.runtime.release.clear()
        self.service.submit("conversation", request())
        worker = threading.Thread(target=self.service.run_one)
        worker.start()
        self.assertTrue(self.runtime.entered.wait(2))
        self.service.stop("conversation", "開発会議")
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(["alice"], self.runtime.calls)
        self.assertEqual("stopped", self.service.snapshot("conversation", "開発会議")["state"])

    def test_server_restart_preserves_history_and_does_not_replay_inflight_call(self):
        self.service.submit("conversation", request())
        with self.service.db() as db:
            db.execute("UPDATE runs SET state='running'")
            self.service.add_message(db, "conversation", dict(id="reply", senderName="alice", text="保存済み", isUser=False))
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        result = self.service.snapshot("conversation", "開発会議")
        self.assertEqual("interrupted", result["state"])
        self.assertEqual("保存済み", result["messages"][-1]["text"])
        self.assertFalse(self.service.run_one())
        self.assertEqual([], self.runtime.calls)

    def test_history_separates_groups_and_ids(self):
        self.service.submit("one", request())
        self.service.run_one()
        self.service.submit("two", request(requestId="request-2", groupName="会議"))
        self.assertEqual({"one", "two"}, {r["id"] for r in self.service.history()})
        with self.assertRaises(GroupError):
            self.service.snapshot("one", "会議")
        with self.assertRaises(GroupError):
            self.service.submit("one", request(requestId="request-3", groupName="会議"))

    def test_invalid_profile_and_limits_are_rejected_without_acceptance(self):
        for changes in [dict(members=["../secret"]), dict(members=["unknown"]), dict(maxMessages=51), dict(rounds=0)]:
            with self.assertRaises(GroupError):
                self.service.submit("conversation", request(**changes))
        self.assertEqual([], self.service.history())

    def test_one_worker_owns_database(self):
        with self.assertRaises(GroupError):
            GroupService(self.path, self.runtime, start=False)

    def test_budget_and_mention_handoffs_are_bounded(self):
        self.runtime.replies = ["@bob 確認して", "@alice 確認して"] * 10
        self.service.submit("conversation", request(maxMessages=3))
        self.service.run_one()
        self.assertEqual(3, len(self.runtime.calls))
        self.assertEqual("capped", self.service.snapshot("conversation", "開発会議")["state"])


if __name__ == "__main__":
    unittest.main()
