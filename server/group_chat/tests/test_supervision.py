import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_service import GroupError, GroupService, timestamp
from group_supervision import DelegationStore
from test_group_service import Runtime


def request(**changes):
    return dict(taskId="design-1", groupName="開発会議", members=["alice", "bob"], goal="方式を比較して選ぶ",
                completionCriteria="根拠、反論、費用を比較して推奨案を出す", maxCycles=3) | changes


def review(decision="complete", **changes):
    return json.dumps(dict(decision=decision, summary="方式Aを推奨。費用の根拠は議論で確認。", openQuestions=[],
                           followUp="費用の比較を追加してください" if decision == "continue" else "") | changes, ensure_ascii=False)


class ReviewRuntime(Runtime):
    def __init__(self):
        super().__init__()
        self.reviews = []
        self.review_calls = []
        self.review_entered, self.review_release = threading.Event(), threading.Event()
        self.review_release.set()

    def review(self, member, prompt, stopped):
        self.review_calls.append((member, prompt))
        self.review_entered.set()
        while not self.review_release.wait(.01):
            if stopped.is_set():
                raise GroupError("stopped")
        return self.reviews.pop(0) if self.reviews else review()


class SupervisionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "history.db"
        self.runtime = ReviewRuntime()
        self.service = GroupService(self.path, self.runtime, start=False)
        self.store = DelegationStore(self.path)
        self.owner = "main-session"

    def tearDown(self):
        self.runtime.release.set()
        self.runtime.review_release.set()
        self.service.close()
        self.temp.cleanup()

    def submit(self, **changes):
        return self.store.submit(self.owner, request(**changes), "alice")

    def drive(self, limit=30):
        for _ in range(limit):
            self.service.supervision.tick()
            self.service.run_one()
            result = self.store.read(self.owner, "design-1")
            if result["state"] in {"complete", "capped", "needs_input", "stopped", "interrupted", "failed"}:
                return result
        self.fail("did not converge")

    def test_completed_round_is_evaluated_and_missing_criteria_are_sent_back(self):
        self.runtime.reviews = [review("continue", openQuestions=["費用が未検証"]), review()]
        self.submit()
        result = self.drive()
        self.assertEqual("complete", result["state"])
        self.assertEqual(2, result["cycle"])
        self.assertEqual(["alice", "bob", "alice"] * 2, self.runtime.calls)
        self.assertEqual(2, len(self.runtime.review_calls))
        self.assertEqual("alice", self.runtime.review_calls[0][0])
        self.assertIn("根拠、反論、費用", self.runtime.review_calls[0][1])
        messages = self.service.snapshot(result["conversationId"], "開発会議")["messages"]
        self.assertIn("費用の比較を追加", [m["text"] for m in messages if m["isUser"]][-1])

    def test_repeated_incomplete_reviews_stop_at_budget_with_open_questions(self):
        self.runtime.reviews = [review("continue", openQuestions=["未解決"])] * 5
        self.submit(maxCycles=2)
        result = self.drive()
        self.assertEqual("capped", result["state"])
        self.assertEqual(2, result["cycle"])
        self.assertEqual(["未解決"], result["result"]["openQuestions"])
        self.assertEqual(2, len(self.runtime.review_calls))

    def test_user_decision_wait_is_returned_without_forcing_agreement(self):
        self.runtime.reviews = [review("needs_input", openQuestions=["予算の上限は？"])]
        self.submit()
        self.assertEqual("needs_input", self.drive()["state"])
        self.assertEqual(1, len(self.runtime.review_calls))

    def test_deadline_expires_before_any_model_is_called(self):
        self.submit()
        with self.store.db() as db:
            db.execute("UPDATE delegations SET deadline=0")
        result = self.drive()
        self.assertEqual("capped", result["state"])
        self.assertEqual("time_budget", result["result"]["reason"])
        self.assertEqual([], self.runtime.calls)

    def test_heartbeat_expires_waiting_task_without_waiting_for_busy_worker(self):
        self.submit()
        with self.store.db() as db:
            db.execute("UPDATE delegations SET deadline=0")
        self.service.supervision.heartbeat()
        result = self.store.read(self.owner, "design-1")
        self.assertEqual("capped", result["state"])
        self.assertEqual("time_budget", result["result"]["reason"])
        self.assertEqual([], self.runtime.calls)

    def test_stop_while_member_running_interrupts_without_restart_or_review(self):
        self.runtime.release.clear()
        self.submit()
        self.service.supervision.tick()
        worker = threading.Thread(target=self.service.run_one)
        worker.start()
        self.assertTrue(self.runtime.entered.wait(2))
        self.store.stop(self.owner, "design-1")
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual("stopped", self.drive()["state"])
        self.assertEqual(["alice"], self.runtime.calls)
        self.assertEqual([], self.runtime.review_calls)

    def test_deadline_during_running_member_interrupts_and_reports_budget(self):
        self.runtime.release.clear()
        self.submit()
        with self.store.db() as db:
            db.execute("UPDATE delegations SET deadline=?", (timestamp() + 500,))
        self.service.supervision.tick()
        worker = threading.Thread(target=self.service.run_one)
        worker.start()
        self.assertTrue(self.runtime.entered.wait(2))
        status = self.store.read(self.owner, "design-1")
        self.assertEqual("alice", status["activeSpeaker"])
        worker.join(3)
        self.assertFalse(worker.is_alive())
        result = self.drive()
        self.assertEqual("capped", result["state"])
        self.assertEqual("time_budget", result["result"]["reason"])
        self.assertEqual(["alice"], self.runtime.calls)
        self.assertEqual([], self.runtime.review_calls)

    def test_invalid_supervisor_is_rejected_before_discussion_starts(self):
        self.store.submit(self.owner, request(), "missing")
        self.assertEqual("failed", self.drive()["state"])
        self.assertEqual([], self.runtime.calls)

    def test_stop_during_review_cannot_be_overwritten_by_review_result(self):
        self.runtime.review_release.clear()
        self.submit()
        self.service.supervision.tick()
        self.service.run_one()
        worker = threading.Thread(target=self.service.supervision.tick)
        worker.start()
        self.assertTrue(self.runtime.review_entered.wait(2))
        self.store.stop(self.owner, "design-1")
        self.runtime.review_release.set()
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual("stopped", self.store.read(self.owner, "design-1")["state"])

    def test_group_screen_keeps_polling_while_main_is_reviewing_and_can_stop_it(self):
        self.runtime.review_release.clear()
        task = self.submit()
        self.service.supervision.tick()
        self.service.run_one()
        worker = threading.Thread(target=self.service.supervision.tick)
        worker.start()
        self.assertTrue(self.runtime.review_entered.wait(2))
        snapshot = self.service.snapshot(task["conversationId"], "開発会議")
        self.assertEqual("running", snapshot["state"])
        self.assertEqual("complete", snapshot["discussionState"])
        self.assertEqual("reviewing", snapshot["delegation"]["state"])
        self.assertEqual("alice", snapshot["activeSpeaker"])
        self.service.stop(task["conversationId"], "開発会議")
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual("stopped", self.service.snapshot(task["conversationId"], "開発会議")["state"])

    def test_manual_message_cannot_replace_a_managed_round_but_works_after_completion(self):
        from test_group_service import request as message
        task = self.submit()
        self.service.supervision.tick()
        self.service.run_one()
        with self.assertRaises(GroupError) as failure:
            self.service.submit(task["conversationId"], message(requestId="manual"))
        self.assertEqual("discussion_managed", failure.exception.code)
        self.drive()
        snapshot = self.service.submit(task["conversationId"], message(requestId="manual"))
        self.assertEqual("queued", snapshot["state"])
        self.assertNotIn("delegation", snapshot)
        history = snapshot["messages"]
        calls = len(self.runtime.calls)
        self.service.run_one()
        continued = self.service.snapshot(task["conversationId"], "開発会議")
        self.assertEqual("complete", continued["state"])
        self.assertEqual(history, continued["messages"][:len(history)])
        self.assertEqual(["alice", "bob", "alice", "bob", "alice"], self.runtime.calls[calls:])
        self.assertEqual("complete", self.store.read(self.owner, "design-1")["state"])

    def test_manual_continuation_after_terminal_delegation_ignores_its_stop_and_expired_budget(self):
        from test_group_service import request as message
        from group_supervision import TERMINAL
        for state in sorted(TERMINAL):
            with self.subTest(state=state):
                task_id = "previous-" + state
                task = self.submit(taskId=task_id)
                self.service.supervision.tick()
                self.service.run_one()
                self.service.stop(task["conversationId"], "開発会議")
                with self.store.db() as db:
                    db.execute("UPDATE delegations SET state=?,deadline=? WHERE conversation=?",
                               (state, timestamp() - 1, task["conversationId"]))
                original = self.service.snapshot(task["conversationId"], "開発会議")["messages"]
                calls, reviews = len(self.runtime.calls), len(self.runtime.review_calls)
                self.service.submit(task["conversationId"], message(requestId="continue-" + state, text="@all"))
                self.service.run_one()
                continued = self.service.snapshot(task["conversationId"], "開発会議")
                self.assertEqual("complete", continued["state"])
                self.assertEqual(original, continued["messages"][:len(original)])
                self.assertEqual("@all", continued["messages"][len(original)]["text"])
                self.assertEqual(["alice", "bob", "alice", "bob", "alice"], self.runtime.calls[calls:])
                self.assertEqual(reviews, len(self.runtime.review_calls))
                self.assertEqual(state, self.store.read(self.owner, task_id)["state"])

    def test_manual_continuation_can_still_be_explicitly_stopped(self):
        from test_group_service import request as message
        task = self.submit()
        self.service.supervision.tick()
        self.service.stop(task["conversationId"], "開発会議")
        self.service.submit(task["conversationId"], message(requestId="manual", text="@all"))
        self.runtime.release.clear()
        worker = threading.Thread(target=self.service.run_one)
        worker.start()
        try:
            self.assertTrue(self.runtime.entered.wait(2))
            self.service.stop(task["conversationId"], "開発会議")
            worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertEqual("stopped", self.service.snapshot(task["conversationId"], "開発会議")["state"])
            self.assertEqual(["alice"], self.runtime.calls)
            self.assertEqual("stopped", self.store.read(self.owner, "design-1")["state"])
        finally:
            self.runtime.release.set()
            worker.join(3)

    def test_review_decisions_are_saved_once_in_the_shared_group_history(self):
        task = self.submit()
        self.drive()
        self.service.supervision.tick()
        messages = self.service.snapshot(task["conversationId"], "開発会議")["messages"]
        decisions = [m for m in messages if m["id"].startswith("review-")]
        self.assertEqual(1, len(decisions))
        self.assertIn("進行役の判定: 完了", decisions[0]["text"])

    def test_idempotent_acceptance_and_owner_isolation(self):
        first = self.submit()
        self.assertEqual(first, self.submit())
        with self.assertRaises(GroupError):
            self.submit(goal="別の依頼")
        with self.assertRaises(GroupError):
            self.store.read("other-session", "design-1")
        other = self.store.submit("other-session", request(), "bob")
        self.assertNotEqual(first["conversationId"], other["conversationId"])
        self.assertEqual(1, len(self.store.list("other-session")))

    def test_restart_resumes_accepted_queue_without_replaying_member(self):
        self.submit()
        self.service.supervision.tick()
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        result = self.drive()
        self.assertEqual("complete", result["state"])
        self.assertEqual(["alice", "bob", "alice"], self.runtime.calls)

    def test_restart_after_uncertain_submission_reuses_run_id(self):
        self.submit()
        self.service.supervision.tick()
        with self.store.db() as db:
            db.execute("UPDATE delegations SET state='submitting',cycle=0")
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=False)
        self.assertEqual("complete", self.drive()["state"])
        self.assertEqual(["alice", "bob", "alice"], self.runtime.calls)

    def test_restart_during_member_or_review_exposes_interruption(self):
        for state in ("member", "review"):
            with self.subTest(state=state):
                task_id = "interrupted-" + state
                self.submit(taskId=task_id)
                self.service.supervision.tick()
                conversation = self.store.read(self.owner, task_id)["conversationId"]
                with self.store.db() as db:
                    if state == "member":
                        db.execute("UPDATE runs SET state='running' WHERE conversation=?", (conversation,))
                    else:
                        db.execute("UPDATE delegations SET state='reviewing' WHERE json_extract(request,'$.taskId')=?", (task_id,))
                self.service.close()
                self.service = GroupService(self.path, self.runtime, start=False)
                self.service.supervision.tick()
                self.assertEqual("interrupted", self.store.read(self.owner, task_id)["state"])
        self.assertEqual([], self.runtime.calls)
        self.assertEqual([], self.runtime.review_calls)

    def test_invalid_review_does_not_mean_success_or_trigger_another_cycle(self):
        for bad in ("not json", "[]", review("continue", followUp=""), review("complete", summary="")):
            with self.subTest(reply=bad):
                task_id = "bad-" + str(len(self.runtime.review_calls))
                self.runtime.reviews = [bad]
                self.submit(taskId=task_id)
                self.service.supervision.tick()
                self.service.run_one()
                self.service.supervision.tick()
                result = self.store.read(self.owner, task_id)
                self.assertEqual("failed", result["state"])
                self.assertEqual("invalid_review", result["result"]["reason"])

    def test_review_failure_keeps_completed_member_run_and_safe_diagnostics(self):
        self.submit()
        self.service.supervision.tick()
        self.service.run_one()
        task = self.store.read(self.owner, "design-1")
        before = self.service.snapshot(task["conversationId"], "開発会議")
        failure = GroupError("runtime_exit_failed", diagnostics={"kind": "exit", "returnCode": 1})
        with patch.object(self.runtime, "review", side_effect=failure):
            self.service.supervision.tick()
        task = self.store.read(self.owner, "design-1")
        after = self.service.snapshot(task["conversationId"], "開発会議")
        self.assertEqual("failed", task["state"])
        self.assertEqual("complete", task["runState"])
        self.assertEqual("review", task["result"]["diagnostics"]["stage"])
        self.assertEqual(before["messages"], after["messages"])
        self.assertEqual("complete", after["discussionState"])
        self.assertEqual("review", after["diagnostics"]["stage"])
        self.assertEqual(1, after["diagnostics"]["returnCode"])

    def test_invalid_inputs_are_rejected_before_acceptance(self):
        for change in (dict(taskId="../bad"), dict(members=["../bad"]), dict(members=["alice", "alice"]),
                       dict(maxCycles=True), dict(maxMessages=1), dict(budgetSeconds=0), dict(completionCriteria="")):
            with self.subTest(change=change), self.assertRaises(GroupError):
                self.submit(**change)
        self.assertEqual([], self.store.list(self.owner))

    def test_actual_background_worker_completes_without_any_reader(self):
        self.service.close()
        self.service = GroupService(self.path, self.runtime, start=True)
        self.submit()
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            if self.store.read(self.owner, "design-1")["state"] == "complete":
                break
            time.sleep(.02)
        self.assertTrue(self.runtime.review_entered.is_set())
        self.assertTrue(self.store.ready())
        self.assertEqual("complete", self.store.read(self.owner, "design-1")["state"])


if __name__ == "__main__":
    unittest.main()
