import importlib.util
import json
import sys
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_agent_tools import AgentTools, register_tools
from group_catalog import GroupCatalog
from group_supervision import DelegationStore
import test_supervision as fixture
from test_supervision import request, review


class AgentToolsTest(unittest.TestCase):
    submit = fixture.SupervisionTest.submit
    drive = fixture.SupervisionTest.drive

    def setUp(self):
        fixture.SupervisionTest.setUp(self)
        self.events = []
        self.runners = []
        self.ledger = {}
        self.service.supervision.heartbeat()

        def context(metadata):
            owner = metadata.get("session_id", self.owner)
            return DelegationStore(self.path), owner, "alice", {"session_key": owner, "parent_session_id": owner,
                "origin_ui_session_id": "ui-" + owner, "origin_session_id": "raw-" + owner}

        def dispatch(**kw):
            self.events.append(kw)
            self.ledger[kw["delegation_id"]] = {"state": "running"}
            def execute():
                self.runners.append(kw["runner"]())
                self.ledger[kw["delegation_id"]] = {"state": "completed"}
            thread = threading.Thread(target=execute)
            thread.start()
            self.threads.append(thread)
            return {"status": "dispatched", "delegation_id": kw["delegation_id"]}

        self.threads = []
        self.catalog_profiles = [
            {"name": "default", "ui_meta": {"hermes-bots-groups": {"rooms": {
                "name:開発会議": {"name": "開発会議", "members": ["alice", "bob"]}}}}},
            {"name": "alice"}, {"name": "bob"}, {"name": "unrelated"}]
        self.tools = AgentTools(context, dispatch, self.ledger.get, GroupCatalog(lambda: self.catalog_profiles))

    def tearDown(self):
        for task in self.store.list(self.owner):
            self.store.stop(self.owner, task["taskId"])
        for thread in self.threads:
            thread.join(3)
        fixture.SupervisionTest.tearDown(self)

    def call(self, name, args, **metadata):
        return json.loads(self.tools.call(name, args, metadata))

    def test_discovery_and_unknown_selection_do_not_create_or_dispatch(self):
        self.assertEqual(["alice", "bob"], self.call("group_list", {})["groups"][0]["members"])
        for args in (request(groupName="unknown"), request(members=["alice", "bob", "unrelated"])):
            self.assertIn(self.call("group_delegate", args)["error"], {"group_not_found", "group_members_mismatch"})
        self.assertEqual([], self.events)
        self.assertEqual([], self.store.list(self.owner))

    def test_existing_group_id_uses_saved_members_and_returns_on_same_native_path(self):
        room = self.catalog_profiles[0]["ui_meta"]["hermes-bots-groups"]["rooms"]["name:開発会議"]
        room.update(speakingOrder=["bob", "alice"], finalSummaryMember="alice", systemPrompt="保存ルール")
        args = request()
        del args["members"]
        del args["groupName"]
        accepted = self.call("group_delegate", args | {"groupId": "name:開発会議"})["task"]
        self.assertEqual(["bob", "alice"], accepted["members"])
        self.assertEqual("開発会議", accepted["groupName"])
        self.assertEqual("complete", self.drive()["state"])
        self.assertEqual(["bob", "alice", "alice"], self.runtime.calls)
        self.threads[0].join(3)
        self.assertEqual(1, len(self.events))
        self.assertEqual(self.owner, self.events[0]["parent_session_id"])

    def test_main_tool_returns_immediately_and_result_automatically_reenters_origin(self):
        self.runtime.reviews = [review("continue"), review()]
        started = time.monotonic()
        accepted = self.call("group_delegate", request())
        self.assertLess(time.monotonic() - started, .5)
        self.assertEqual("queued", accepted["task"]["state"])
        # Main agent is free to continue; no status read drives the worker.
        self.drive()
        self.threads[0].join(3)
        event = self.events[0]
        self.assertEqual(self.owner, event["session_key"])
        self.assertEqual("ui-" + self.owner, event["origin_ui_session_id"])
        returned = json.loads(self.runners[0]["results"][0]["summary"])
        self.assertEqual("complete", returned["groupDelegation"]["state"])
        self.assertEqual(2, returned["groupDelegation"]["cycle"])

    def test_duplicate_tool_call_and_hook_do_not_duplicate_work_or_notifications(self):
        first = self.call("group_delegate", request())
        self.call("group_delegate", request())
        self.tools.recover(session_id=self.owner)
        self.assertEqual(1, len(self.events))
        self.assertEqual(1, len(self.store.list(self.owner)))
        self.drive()
        self.threads[0].join(3)
        self.tools.recover(session_id=self.owner)
        self.assertEqual(1, len(self.events))
        self.assertEqual("design-1", first["task"]["taskId"])

    def test_model_arguments_cannot_choose_owner_or_other_sessions_task(self):
        self.call("group_delegate", request() | {"owner": "victim", "supervisor": "bob"})
        response = self.call("group_task_status", {"taskId": "design-1", "owner": self.owner}, session_id="other")
        self.assertEqual("delegation_not_found", response["error"])
        self.assertEqual([], self.store.list("victim"))
        self.assertEqual("alice", json.loads(self.store_request())["supervisor"])

    def store_request(self):
        with self.store.db() as db:
            return db.execute("SELECT request FROM delegations").fetchone()[0]

    def test_worker_unavailable_does_not_accept_a_task(self):
        with self.store.db() as db:
            db.execute("DELETE FROM group_worker")
        self.assertEqual("group_worker_unavailable", self.call("group_delegate", request())["error"])
        self.assertEqual([], self.store.list(self.owner))
        self.assertEqual([], self.events)

    def test_rejected_native_delivery_stops_task_without_model_calls(self):
        def reject(**kw):
            # Even if the worker races the native notification admission, it must
            # not start work that has no accepted return path.
            self.service.supervision.tick()
            self.assertFalse(self.service.run_one())
            return {"status": "rejected"}
        self.tools.dispatch = reject
        self.assertEqual("background_delivery_unavailable", self.call("group_delegate", request())["error"])
        self.assertEqual("stopped", self.store.read(self.owner, "design-1")["state"])
        self.service.supervision.tick()
        self.assertFalse(self.service.run_one())
        self.assertEqual([], self.runtime.calls)

    def test_stop_tool_cancels_accepted_work_and_surfaces_stopped_result(self):
        self.call("group_delegate", request())
        response = self.call("group_task_stop", {"taskId": "design-1"})
        self.assertEqual("stopped", response["task"]["state"])
        self.threads[0].join(3)
        self.assertEqual("stopped", json.loads(self.runners[0]["results"][0]["summary"])["groupDelegation"]["state"])

    def test_recovery_reattaches_a_persisted_task_without_repeating_discussion(self):
        self.submit()
        self.tools.recover(session_id=self.owner)
        self.assertEqual(1, len(self.events))
        self.drive()
        self.threads[0].join(3)
        self.assertEqual(["alice", "bob", "alice"], self.runtime.calls)

    def test_status_recovers_acceptance_interrupted_before_notification_admission(self):
        self.store.submit(self.owner, request(), "alice", hold=True)
        response = self.call("group_task_status", {"taskId": "design-1"})
        self.assertEqual("queued", response["task"]["state"])
        self.assertEqual("complete", self.drive()["state"])
        self.threads[0].join(3)
        self.assertEqual(1, len(self.runners))

    def test_recovery_does_not_loop_outage_notifications_until_worker_returns(self):
        self.submit()
        with self.store.db() as db:
            db.execute("DELETE FROM group_worker")
        self.tools.recover(session_id=self.owner)
        self.tools.recover(session_id=self.owner)
        self.assertEqual([], self.events)
        self.service.supervision.heartbeat()
        self.tools.recover(session_id=self.owner)
        self.assertEqual(1, len(self.events))
        self.drive()
        self.threads[0].join(3)
        self.assertEqual(1, len(self.runners))

    def test_recovery_delivers_completed_result_even_when_worker_is_offline(self):
        self.submit()
        self.drive()
        with self.store.db() as db:
            db.execute("DELETE FROM group_worker")
        self.tools.recover(session_id=self.owner)
        self.threads[0].join(3)
        self.assertEqual(1, len(self.runners))
        self.assertEqual("complete", json.loads(self.runners[0]["results"][0]["summary"])["groupDelegation"]["state"])


class RegistrationTest(unittest.TestCase):
    def test_native_entrypoint_registers_tools_and_recovery_hook(self):
        registered, hooks = {}, {}
        ctx = SimpleNamespace(register_tool=lambda **entry: registered.update({entry["name"]: entry}),
                              register_hook=lambda name, callback: hooks.update({name: callback}))
        entrypoint = Path(__file__).resolve().parents[1] / "__init__.py"
        spec = importlib.util.spec_from_file_location("group_plugin_test", entrypoint)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.register(ctx)
        self.assertEqual({"group_delegate", "group_list", "group_task_status", "group_task_stop", "group_tasks"}, set(registered))
        self.assertIn("pre_llm_call", hooks)
        self.assertEqual("group_chat", registered["group_delegate"]["toolset"])
        self.assertNotIn("owner", registered["group_delegate"]["schema"]["parameters"]["properties"])


if __name__ == "__main__":
    unittest.main()
