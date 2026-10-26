import hashlib
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_service import CliRuntime, GroupError


class CliRuntimeTest(unittest.TestCase):
    def test_review_uses_main_profile_and_a_separate_empty_toolset(self):
        child = Mock(returncode=0)
        child.communicate.return_value = ('{"decision":"complete"}', None)
        child.poll.return_value = 0
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child) as launch:
            CliRuntime().review("main", "synthetic review", threading.Event())
        self.assertEqual(["/test/hermes", "--profile", "main", "chat", "--toolsets=group_review", "-Q", "--query-file", "-"], launch.call_args.args[0])

    def test_review_removes_exact_host_unknown_empty_toolset_warning(self):
        answer = '{"decision":"continue","summary":"incomplete","openQuestions":["comparison"],"followUp":"compare"}'
        child = Mock(returncode=0)
        child.communicate.return_value = ('Warning: Unknown toolsets: group_review\n' + answer + '\n', None)
        child.poll.return_value = 0
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child):
            self.assertEqual(answer, CliRuntime().review("main", "synthetic review", threading.Event()))

    def test_review_does_not_remove_unrecognised_prefix_or_member_text(self):
        for review, output in [(True, 'unexpected warning\n{"decision":"continue"}'),
                               (False, 'Warning: Unknown toolsets: group_review\nmember text')]:
            with self.subTest(review=review):
                child = Mock(returncode=0)
                child.communicate.return_value = (output, None)
                child.poll.return_value = 0
                with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child):
                    runtime = CliRuntime()
                    method = runtime.review if review else runtime.reply
                    self.assertEqual(output, method("main", "synthetic", threading.Event()))

    def test_uses_the_existing_profile_cli_without_a_shell(self):
        child = Mock(returncode=0)
        child.communicate.return_value = ("合成応答\n", None)
        child.poll.return_value = 0
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child) as launch:
            runtime = CliRuntime()
            self.assertEqual("合成応答", runtime.reply("alice", "合成の会話", threading.Event()))
        self.assertEqual(["/test/hermes", "--profile", "alice", "chat", "-Q", "--query-file", "-"], launch.call_args.args[0])
        self.assertNotIn("shell", launch.call_args.kwargs)
        self.assertTrue(launch.call_args.kwargs["stderr"].closed)

    def test_large_utf8_input_reaches_real_child_after_parent_handle_closes(self):
        prompt = '合成履歴\r\n引用"`$()\x00😀' * 12000
        expected = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        self.assertGreater(len(prompt.encode("utf-8")), 131072)
        real_popen = subprocess.Popen
        script = "import hashlib,sys,time; time.sleep(.3); print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
        for review in (False, True):
            with self.subTest(review=review):
                def launch(args, **kwargs):
                    self.assertEqual(["--query-file", "-"], args[-2:])
                    self.assertNotIn(prompt, args)
                    return real_popen([sys.executable, "-c", script], **kwargs)
                with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", side_effect=launch):
                    runtime = CliRuntime()
                    method = runtime.review if review else runtime.reply
                    self.assertEqual(expected, method("main", prompt, threading.Event()))

    def test_prompt_handle_closes_even_if_launch_fails(self):
        handles = []
        def launch(args, **kwargs):
            handles.append(kwargs["stdin"])
            self.assertEqual("合成".encode("utf-8"), kwargs["stdin"].read())
            raise OSError("synthetic launch failure")
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", side_effect=launch):
            with self.assertRaises(GroupError) as failure:
                CliRuntime().reply("main", "合成", threading.Event())
        self.assertEqual("runtime_launch_failed", failure.exception.code)
        self.assertEqual("OSError", failure.exception.diagnostics["exception"])
        self.assertNotIn("synthetic launch failure", str(failure.exception.diagnostics))
        self.assertTrue(handles[0].closed)

    def test_timeout_continues_without_resending_input(self):
        child = Mock(returncode=0)
        child.communicate.side_effect = [subprocess.TimeoutExpired("hermes", .2), ("answer", None)]
        child.poll.return_value = 0
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child):
            self.assertEqual("answer", CliRuntime().reply("main", "synthetic", threading.Event()))
        self.assertEqual([{"timeout": .2}, {"timeout": .2}], [call.kwargs for call in child.communicate.call_args_list])

    def test_failed_member_is_not_retried(self):
        child = Mock(returncode=1)
        child.communicate.return_value = ("", None)
        child.poll.return_value = 1
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child) as launch:
            with self.assertRaises(GroupError) as failure:
                CliRuntime().reply("alice", "synthetic", threading.Event())
        self.assertEqual("runtime_exit_failed", failure.exception.code)
        self.assertEqual(1, failure.exception.diagnostics["returnCode"])
        self.assertEqual(1, launch.call_count)

    def test_explicit_stop_terminates_current_cli(self):
        child = Mock(returncode=0)
        child.poll.return_value = 0
        stopped = threading.Event()
        def communicate(**kwargs):
            if not stopped.is_set():
                stopped.set()
                raise subprocess.TimeoutExpired("hermes", .2)
            return "", None
        child.communicate.side_effect = communicate
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", return_value=child):
            with self.assertRaises(GroupError) as failure:
                CliRuntime().reply("alice", "synthetic", stopped)
        child.terminate.assert_called_once()
        self.assertEqual("stopped", failure.exception.code)

    def run_children(self, outputs, *, review=False, stopped=None, on_sleep=None):
        clock = [0.0]
        launches = []
        def sleep(seconds):
            clock[0] += seconds
            if on_sleep:
                on_sleep()
        def launch(args, **kwargs):
            launches.append(kwargs)
            code, output, stderr = outputs[min(len(launches) - 1, len(outputs) - 1)]
            kwargs["stderr"].write(stderr)
            child = Mock(returncode=code)
            child.communicate.return_value = output, None
            child.poll.return_value = code
            return child
        with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", side_effect=launch), \
             patch("time.monotonic", side_effect=lambda: clock[0]), patch("time.sleep", side_effect=sleep):
            runtime = CliRuntime()
            try:
                value = (runtime.review if review else runtime.reply)("main", "合成本文", stopped or threading.Event())
            except GroupError as failure:
                value = failure
        self.assertTrue(all(call["stdin"].closed and call["stderr"].closed for call in launches))
        return value, launches, clock[0]

    def test_native_capacity_refusal_waits_then_succeeds_with_startup_warnings(self):
        refusal = b"Skipping unreadable plugin directory synthetic\nhermes-refusal-reason: MAX_CONCURRENT_SESSIONS\ncapacity\n"
        for is_review in (False, True):
            with self.subTest(review=is_review):
                warning = "Warning: Unknown toolsets: group_review\n" if is_review else ""
                value, launches, elapsed = self.run_children([(1, warning, refusal), (0, "answer", b"")], review=is_review)
                self.assertEqual("answer", value)
                self.assertEqual(2, len(launches))
                self.assertGreater(elapsed, 0)

    def test_unknown_errors_and_completed_calls_are_never_replayed(self):
        capacity = b"hermes-refusal-reason: MAX_CONCURRENT_SESSIONS\ncapacity\n"
        for code, output, stderr in [
            (1, "", b"secret-token and private message"), (0, " \n", b""),
            (1, "", b"hermes-refusal-reason: SESSION_NOT_OWNED\nowned\n"),
            (1, "", b"hermes-refusal-reason: SESSION_COORDINATION_UNAVAILABLE\nunavailable\n"),
            (1, "", b"prefix " + capacity), (1, "", capacity * 2),
            (1, "", capacity + b"hermes-refusal-reason: UNKNOWN_REASON\n"),
            (1, "", capacity + b"session_id: synthetic\n"),
            (1, "model answer", capacity), (0, "", capacity),
            (1, "", capacity + b"x" * 65536),
        ]:
            with self.subTest(code=code, output=output, stderr_length=len(stderr)):
                value, launches, _ = self.run_children([(code, output, stderr)])
                self.assertIsInstance(value, GroupError)
                self.assertNotEqual("runtime_capacity", value.code)
                self.assertEqual(1, len(launches))
                self.assertNotIn("secret-token", str(value.diagnostics))
                self.assertNotIn("合成本文", str(value.diagnostics))

    def test_capacity_retry_is_bounded_and_can_be_cancelled_without_launching_again(self):
        refusal = (1, "", b"hermes-refusal-reason: MAX_CONCURRENT_SESSIONS\ncapacity\n")
        value, launches, elapsed = self.run_children([refusal], review=True)
        self.assertEqual("runtime_capacity", value.code)
        self.assertEqual(6, len(launches))
        self.assertLessEqual(elapsed, 60)
        self.assertEqual("review", value.diagnostics["stage"])
        self.assertEqual(6, value.diagnostics["attempts"])
        stopped = threading.Event()
        value, launches, _ = self.run_children([refusal], stopped=stopped, on_sleep=stopped.set)
        self.assertEqual("stopped", value.code)
        self.assertEqual(1, len(launches))

    def test_known_review_warning_alone_is_an_empty_answer(self):
        value, launches, _ = self.run_children([(0, "Warning: Unknown toolsets: group_review\n", b"")], review=True)
        self.assertEqual("runtime_empty_output", value.code)
        self.assertEqual(1, len(launches))

    def test_already_cancelled_call_never_launches_a_child(self):
        stopped = threading.Event()
        stopped.set()
        value, launches, _ = self.run_children([(0, "answer", b"")], stopped=stopped)
        self.assertEqual("stopped", value.code)
        self.assertEqual([], launches)

    def test_real_child_capacity_refusal_retries_without_losing_utf8_input(self):
        real_popen = subprocess.Popen
        prompt = '合成履歴😀"`$()\n' * 12000
        expected = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        script = (
            "import hashlib,sys; from pathlib import Path; marker=Path(sys.argv[1]); "
            "already=marker.exists(); marker.write_text('synthetic',encoding='utf-8'); "
            "print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()) if already else "
            "print('Skipping unreadable plugin directory synthetic\\nhermes-refusal-reason: MAX_CONCURRENT_SESSIONS\\ncapacity',file=sys.stderr); "
            "sys.exit(0 if already else 1)"
        )
        for is_review in (False, True):
            with self.subTest(review=is_review), tempfile.TemporaryDirectory() as directory:
                calls = []
                def launch(args, **kwargs):
                    calls.append(kwargs)
                    self.assertNotIn(prompt, args)
                    return real_popen([sys.executable, "-c", script, str(Path(directory) / "attempt")], **kwargs)
                with patch("shutil.which", return_value="/test/hermes"), patch("subprocess.Popen", side_effect=launch):
                    runtime = CliRuntime()
                    actual = (runtime.review if is_review else runtime.reply)("main", prompt, threading.Event())
                self.assertEqual(expected, actual)
                self.assertEqual(2, len(calls))
                self.assertTrue(all(call["stdin"].closed and call["stderr"].closed for call in calls))


if __name__ == "__main__":
    unittest.main()
