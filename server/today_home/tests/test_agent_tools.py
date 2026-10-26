import tempfile
import unittest
from unittest.mock import patch

from today_core.agent_tools import DailyTools
from today_core.store import PlanStore


class DailyAgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = PlanStore(self.temp.name)
        self.store.configure(dict(email="test@example.org", manaba_url="", enabled=True))

    def test_ordinary_chat_can_read_cache_but_cannot_trigger_unaccepted_collection(self):
        tools = DailyTools(self.temp.name)
        with patch("today_core.agent_tools.search_mail") as search:
            self.assertEqual("active_daily_run_required", tools.call("today_mail_search", {"query": "x"})["error"])
            search.assert_not_called()
        self.assertIn("cache", tools.call("today_sources", {}))

    def test_agent_queries_are_passed_through_and_failed_read_retains_previous_material(self):
        self.store.begin("daily")
        tools = DailyTools(self.temp.name, "daily")
        record = dict(id="gmail:account:abc", source="gmail", title="締切延長", url="https://example.org", text="明日まで")
        with patch("today_core.agent_tools.search_mail", return_value=({"messages": [{"id": "abc"}]}, "updated")) as search:
            tools.call("today_mail_search", {"query": "subject:延長 after:2026/10/22"})
            self.assertEqual("subject:延長 after:2026/10/22", search.call_args.args[2])
        with patch("today_core.agent_tools.read_mail", return_value=(record, "updated")):
            tools.call("today_mail_read", {"message_id": "abc"})
        with patch("today_core.agent_tools.read_mail", side_effect=RuntimeError("secret diagnostic")):
            result = tools.call("today_mail_read", {"message_id": "other"})
        self.assertNotIn("secret", str(result))
        self.assertEqual(record["text"], self.store.cached_records([record["id"]])[0]["text"])
        self.assertEqual("partial", self.store.cached_inputs("daily")[0]["state"])

    def test_source_change_does_not_expose_previous_accounts_cache(self):
        self.store.begin("daily")
        self.store.cache_source("daily", "gmail", [dict(id="old", source="gmail", title="old", url="https://example.org", text="private")], "updated")
        self.store.fail("daily")
        self.store.configure(dict(email="other@example.org", manaba_url="", enabled=True))
        self.assertEqual([], self.store.cache_index())

    def test_all_retrieval_limits_record_partial_coverage(self):
        record = dict(id="gmail:account:abc", source="gmail", title="fixture", url="https://example.org", text="fixture")
        for name, limit, source, adapter, result in [
            ("today_mail_search", 8, "gmail", "search_mail", ({"messages": []}, "checked")),
            ("today_mail_read", 80, "gmail", "read_mail", (record, "updated")),
            ("today_manaba_read", 2, "manaba", "manaba", ([], "updated")),
        ]:
            self.store.begin(name)
            tools = DailyTools(self.temp.name, name)
            with patch("today_core.agent_tools." + adapter, return_value=result) as retrieve:
                for _ in range(limit):
                    self.assertNotIn("error", tools.call(name, {"query": "x", "message_id": "abc"}))
                blocked = tools.call(name, {"query": "x", "message_id": "abc"})
                self.assertEqual(limit, retrieve.call_count)
            self.assertEqual(source, blocked["source"])
            self.assertEqual("daily_read_limit", blocked["error"])
            inputs = self.store.cached_inputs(name)
            self.assertEqual("partial", next(i for i in inputs if i["id"] == source)["state"])
            self.store.finish(name, inputs, {"tasks": []})
            self.assertFalse(self.store.snapshot()["fresh"])


if __name__ == "__main__":
    unittest.main()
