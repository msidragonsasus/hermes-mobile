import json
import sys
import types
import unittest
from unittest.mock import Mock, patch

from today_core.sources import search_mail, read_mail
from today_core.store import PlanError


class GmailTests(unittest.TestCase):
    def modules(self, reader, accounts=None):
        package = types.ModuleType("career_core")
        package.__path__ = []
        account_module = types.ModuleType("career_core.google_accounts")
        account_module.GoogleAccounts = lambda home: types.SimpleNamespace(accounts=lambda: accounts or [
            dict(id="selected", email="wanted@example.org", state="connected"),
            dict(id="other", email="other@example.org", state="connected")])
        read_module = types.ModuleType("career_core.google_connected_accounts")
        read_module.read_account = reader
        return {"career_core": package, "career_core.google_accounts": account_module,
                "career_core.google_connected_accounts": read_module}

    def test_agent_selects_query_and_retrieval_never_selects_another_account(self):
        reader = Mock(side_effect=[{"result": {"messages": [{"id": "abc"}]}},
                                   {"result": {"subject": "ES締切", "body": "2026-10-23まで"}}])
        with patch.dict(sys.modules, self.modules(reader)):
            result, state = search_mail("/unused", {"email": "wanted@example.org"}, "after:2026/10/20 締切の変更")
            record, _ = read_mail("/unused", {"email": "wanted@example.org"}, result["messages"][0]["id"])
        self.assertEqual("checked", state)
        self.assertEqual("ES締切", record["title"])
        for call in reader.call_args_list:
            self.assertEqual("selected", call.args[1]["account_id"])
        self.assertEqual("after:2026/10/20 締切の変更", reader.call_args_list[0].args[1]["query"])

    def test_unknown_search_shape_is_not_a_successful_empty_inbox(self):
        reader = Mock(return_value={"result": {"unexpected": []}})
        with patch.dict(sys.modules, self.modules(reader)):
            with self.assertRaises(ValueError):
                search_mail("/unused", {"email": "wanted@example.org"}, "any query")

    def test_real_host_bridge_array_and_empty_array_are_supported(self):
        for messages in [[{"id": "abc", "subject": "fixture"}], []]:
            reader = Mock(return_value={"result": messages})
            with patch.dict(sys.modules, self.modules(reader)):
                result, state = search_mail("/unused", {"email": "wanted@example.org"}, "after:2026/10/22")
            self.assertEqual(messages, result["messages"])
            self.assertEqual("query", result["scope"])
            self.assertEqual("checked", state)

    def test_missing_selected_account_does_not_fall_back(self):
        reader = Mock()
        with patch.dict(sys.modules, self.modules(reader)):
            with self.assertRaises(PlanError):
                search_mail("/unused", {"email": "missing@example.org"}, "any query")
        reader.assert_not_called()

    def test_only_the_known_empty_formatter_output_is_a_successful_empty_search(self):
        for output, accepted in [("No messages found.\n", True), ("Permission denied", False), ("{broken", False)]:
            reader = Mock(side_effect=json.JSONDecodeError("Expecting value", output, 0))
            with patch.dict(sys.modules, self.modules(reader)):
                if accepted:
                    result, state = search_mail("/unused", {"email": "wanted@example.org"}, "a query")
                    self.assertEqual([], result["messages"])
                    self.assertEqual("checked", state)
                else:
                    with self.assertRaises(json.JSONDecodeError):
                        search_mail("/unused", {"email": "wanted@example.org"}, "a query")

    def test_array_at_result_limit_remains_partial(self):
        reader = Mock(return_value={"result": [{"id": "abc"}] * 20})
        with patch.dict(sys.modules, self.modules(reader)):
            result, state = search_mail("/unused", {"email": "wanted@example.org"}, "a query")
        self.assertTrue(result["has_more"])
        self.assertEqual("partial", state)

    def test_large_message_is_explicitly_partial_and_does_not_drop_its_identity(self):
        reader = Mock(return_value={"result": {"subject": "締切", "body": "a" * 30000}})
        with patch.dict(sys.modules, self.modules(reader)):
            record, state = read_mail("/unused", {"email": "wanted@example.org"}, "abc")
        self.assertEqual("partial", state)
        self.assertEqual("gmail:selected:abc", record["id"])
        self.assertEqual(20000, len(record["text"]))


if __name__ == "__main__":
    unittest.main()
