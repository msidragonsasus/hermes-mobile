"""No website traffic: real short-lived child processes and synthetic credentials."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from career_core.collection_store import PILOT
from career_core.portal_process import fetch_in_process
from career_core.private_process import run_private
from career_core.store import CareerError


class PrivateProcessTests(unittest.TestCase):
    def test_private_stderr_is_not_returned_and_json_input_is_sent_once(self):
        code = "import json,sys; value=json.load(sys.stdin); print('synthetic-secret',file=sys.stderr); print(json.dumps({'length':len(value['input'])}))"
        result = run_private([sys.executable, "-c", code], {"input": "a" * 500000}, cwd=Path.cwd(), env=os.environ.copy(), timeout=5, limit=1024)
        self.assertEqual({"length": 500000}, json.loads(result))
        self.assertNotIn(b"synthetic-secret", result)

    def test_timeout_and_cancellation_leave_no_running_child(self):
        real_popen = subprocess.Popen
        for cancel in (False, True):
            processes = []
            def start(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                processes.append(process)
                return process
            calls = [0]
            def cancelled():
                calls[0] += 1
                return cancel and calls[0] >= 3
            with patch("career_core.private_process.subprocess.Popen", side_effect=start):
                with self.assertRaises(CareerError if cancel else TimeoutError):
                    run_private([sys.executable, "-c", "import time; time.sleep(60)"], {}, cwd=Path.cwd(),
                        env=os.environ.copy(), timeout=5 if cancel else 0.1, limit=1024, should_cancel=cancelled)
            self.assertEqual(1, len(processes))
            self.assertIsNotNone(processes[0].poll())

    def test_browser_receives_neither_google_credentials_nor_systemd_key(self):
        secret = Mock()
        secret.read.return_value = {"login_id": "synthetic-login", "password": "synthetic-password"}
        output = json.dumps({"page": {"authenticated": True}, "storage_state": {"cookies": []}}).encode()
        with patch.dict(os.environ, {"CREDENTIALS_DIRECTORY": "/synthetic-private-key", "GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE": "/synthetic-token",
                                    "CODEX_API_KEY": "synthetic-key", "CAREER_BROWSER_PYTHON": "synthetic-python"}), \
             patch("career_core.portal_process.run_private", return_value=output) as runner:
            self.assertTrue(fetch_in_process(secret, "synthetic-python")["authenticated"])
        env = runner.call_args.kwargs["env"]
        self.assertFalse({"CREDENTIALS_DIRECTORY", "GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE", "CODEX_API_KEY", "CAREER_BROWSER_PYTHON"} & set(env))
        self.assertEqual("synthetic-password", runner.call_args.args[1]["password"])
        self.assertNotIn("synthetic-password", " ".join(runner.call_args.args[0]))
        secret.write.assert_called_once()

    def test_isolated_browser_rejects_missing_login_without_network_or_storage_write(self):
        secret = Mock()
        secret.read.return_value = {"login_id": "", "password": "synthetic-password"}
        with self.assertRaises(CareerError) as raised:
            fetch_in_process(secret, sys.executable)
        self.assertEqual("authentication_required", raised.exception.code)
        self.assertNotIn("synthetic-password", raised.exception.detail)
        secret.write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
