import base64
import os
from pathlib import Path
import sys
import time
import unittest
import uuid
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from terminal_core.sessions import Sessions, TerminalError


@unittest.skipUnless(sys.platform == "linux", "real Linux PTY required")
class PtyTests(unittest.TestCase):
    def setUp(self):
        self.manager = Sessions()
        self.id = str(uuid.uuid4())
        self.session = self.manager.create(self.id, "owner", 80, 24)

    def tearDown(self):
        self.manager.close()

    def until(self, predicate):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            value = self.session.snapshot()
            if predicate(value):
                return value
            time.sleep(0.03)
        self.fail("PTY result did not arrive")

    def test_real_shell_unicode_resize_and_controlling_terminal(self):
        self.session.resize(71, 19)
        self.session.write(1, "printf '\\n%s\\n' '日本語'; stty size; test -t 0 && echo PTY_OK\r".encode())
        result = self.until(lambda value: b"19 71" in base64.b64decode(value["data"]))
        output = base64.b64decode(result["data"])
        self.assertIn("日本語".encode(), output)
        self.assertIn(b"PTY_OK", output)
        self.assertNotIn(b"no job control", output)
        self.assertNotIn(b"cannot set terminal process group", output)

    def test_input_retry_is_deduplicated_and_sequence_mismatch_rejected(self):
        data = b"printf '\\nONCE_%s\\n' MARKER\r"
        first = self.session.write(1, data)
        self.assertEqual(first, self.session.write(1, data))
        result = self.until(lambda value: b"ONCE_MARKER" in base64.b64decode(value["data"]))
        time.sleep(0.1)
        self.assertEqual(base64.b64decode(self.session.snapshot()["data"]).count(b"ONCE_MARKER"), 1)
        for sequence, value in [(1, b"different"), (3, data)]:
            with self.assertRaises(TerminalError):
                self.session.write(sequence, value)
        self.assertEqual(result["last_input"], 1)

    def test_owner_isolation_and_create_retry_reuses_shell(self):
        self.assertIs(self.session, self.manager.create(self.id, "owner", 80, 24))
        with self.assertRaises(TerminalError):
            self.manager.get(self.id, "other")
        with self.assertRaises(TerminalError):
            self.manager.create(self.id, "other", 80, 24)

    def test_partial_write_reserves_sequence_and_retry_never_replays(self):
        data = b"echo must-not-replay\r"
        with patch("terminal_core.sessions.os.write", side_effect=[4, OSError()]) as write:
            receipt = self.session.write(1, data)
            self.assertEqual(receipt, dict(sequence=1, state="uncertain"))
            self.assertEqual(self.session.write(1, data), receipt)
            self.assertEqual(write.call_count, 2)
        self.assertEqual(self.session.snapshot()["input_state"], "uncertain")

    def test_output_offsets_are_replayable_and_bounded(self):
        self.session.LIMIT = 128
        self.session.write(1, b"printf '%0400d' 1\r")
        result = self.until(lambda value: value["truncated"])
        self.assertGreater(result["start"], 0)
        self.assertLessEqual(len(base64.b64decode(result["data"])), 128)
        tail = self.session.snapshot(result["next"])
        self.assertGreaterEqual(tail["start"], result["next"])
        self.assertFalse(tail["truncated"])

    def test_interrupt_and_explicit_close_reap_process(self):
        self.session.write(1, b"sleep 30\r")
        time.sleep(0.2)
        self.session.write(2, b"\x03")
        self.session.write(3, b"printf '\\nINTERRUPT_%s\\n' OK\r")
        self.until(lambda value: b"INTERRUPT_OK" in base64.b64decode(value["data"]))
        self.session.close()
        self.assertIsNotNone(self.session.process.poll())
        self.assertTrue(self.session.closed)
        self.assertEqual(self.session.output, bytearray())


    def test_close_kills_background_and_nohup_jobs(self):
        marker = str(os.getpid())
        self.session.write(1, f"sleep 4{marker} &\r".encode())
        self.session.write(2, f"nohup sleep 5{marker} >/dev/null 2>&1 &\r".encode())
        self.session.write(3, b"printf '\\nJOBS_%s\\n' STARTED\r")
        self.until(lambda value: b"JOBS_STARTED" in base64.b64decode(value["data"]))
        time.sleep(0.2)
        sid = self.session.process.pid
        from terminal_core.sessions import session_processes
        self.assertGreaterEqual(len(session_processes(sid)), 3)
        self.session.close()
        self.assertEqual(session_processes(sid), [])

    def test_exited_shell_is_reaped(self):
        self.session.write(1, b"exit\r")
        self.until(lambda value: value["closed"])
        deadline = time.monotonic() + 3
        while self.session.process.returncode is None and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertIsNotNone(self.session.process.returncode)


if __name__ == "__main__":
    unittest.main()
