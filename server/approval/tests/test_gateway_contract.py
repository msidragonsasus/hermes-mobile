"""Exercise the reference Hermes approval code without running an agent or writing protected files.

Set HERMES_AGENT_SOURCE to a local Hermes Agent checkout. The default is the
existing, read-only audit reference next to this repository, not the live host.
"""

from contextlib import nullcontext
import os
from pathlib import Path
import sys
import unittest
from types import ModuleType
from unittest.mock import patch

SOURCE = Path(os.environ.get(
    "HERMES_AGENT_SOURCE",
    Path(__file__).resolve().parents[4] / ".audit" / "hermes-agent",
)).resolve()
if not (SOURCE / "tools" / "file_tools_write_guards.py").is_file():
    raise RuntimeError("Set HERMES_AGENT_SOURCE to the local reference checkout")
sys.path.insert(0, str(SOURCE))

from tools import approval, approval_gateway_wait, file_tools_write_guards  # noqa: E402


class ProtectedInstructionGatewayContractTest(unittest.TestCase):
    def setUp(self):
        self.key = "synthetic-mobile-approval-contract"
        approval.unregister_gateway_notify(self.key)
        terminal = ModuleType("tools.terminal_tool")
        terminal._get_approval_callback = lambda: None
        modules = patch.dict(sys.modules, {"tools.terminal_tool": terminal})
        modules.start()
        self.addCleanup(modules.stop)
        for target, value in (
            ("tools.approval_context.get_current_session_key", self.key),
            ("tools.approval_context._get_approval_timeout", 1),
            ("tools.approval_context._fire_approval_hook", None),
            ("tools.approval_gateway_wait.activity_heartbeat", lambda: None),
            ("tools.approval_gateway_wait.human_wait_window", nullcontext()),
            ("tools.approval_gateway_wait.is_interrupted", False),
        ):
            mocked = patch(target, return_value=value)
            mocked.start()
            self.addCleanup(mocked.stop)
        self.addCleanup(approval.unregister_gateway_notify, self.key)
        self.requests = []

    def request(self):
        # Approval gate only: this never invokes write_file / patch / terminal.
        return file_tools_write_guards._request_protected_instruction_approval(["SOUL.md", "AGENTS.md"])

    def register(self, choice):
        def notify(data):
            self.requests.append(data)
            self.assertFalse(data["allow_session"])
            self.assertFalse(data["allow_permanent"])
            self.assertIn("SOUL.md", data["command"])
            self.assertIn("AGENTS.md", data["description"])
            self.assertTrue(data["request_id"])
            self.assertEqual(approval.resolve_gateway_approval(self.key, choice, request_id="stale-id"), 0)
            self.assertEqual(approval.resolve_gateway_approval(self.key, choice, request_id=data["request_id"]), 1)
            self.assertEqual(approval.resolve_gateway_approval(self.key, choice, request_id=data["request_id"]), 0)
        approval.register_gateway_notify(self.key, notify)

    def test_once_is_bound_to_one_request_and_never_persisted(self):
        self.register("once")
        self.assertIsNone(self.request())
        self.assertIsNone(self.request())
        self.assertNotEqual(self.requests[0]["request_id"], self.requests[1]["request_id"])
        self.assertEqual(approval.list_gateway_approvals(self.key), [])

    def test_deny_blocks_the_write(self):
        self.register("deny")
        self.assertIn("denied by the user", self.request())

    def test_timeout_removes_request_and_late_allow_cannot_resolve(self):
        approval.register_gateway_notify(self.key, self.requests.append)
        with patch.object(approval_gateway_wait, "_poll_event", return_value="timeout"):
            self.assertIn("Silence is not consent", self.request())
        self.assertEqual(approval.list_gateway_approvals(self.key), [])
        self.assertEqual(approval.resolve_gateway_approval(
            self.key, "once", request_id=self.requests[0]["request_id"]), 0)

    def test_missing_interactive_channel_fails_closed(self):
        self.assertIn("no interactive user or gateway", self.request())

    def test_delivery_failure_fails_closed_and_cleans_queue(self):
        def unavailable(_data):
            raise RuntimeError("synthetic delivery failure")
        approval.register_gateway_notify(self.key, unavailable)
        self.assertIn("could not be delivered", self.request())
        self.assertEqual(approval.list_gateway_approvals(self.key), [])


if __name__ == "__main__":
    unittest.main()
