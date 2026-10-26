"""Native integration acceptance with isolated data and a synthetic Hermes host."""
import contextlib
import json
import os
import sqlite3
import tempfile
import subprocess
import sys
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from career_core.coach_consult import Consultations, run_profile
from career_core.coach_recall import CoachRecall
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools
from career_core.native_coach import NativeCoach, TICKET_ENV
from career_core.store import CareerError, Store, encoded
from coach_fixture import configure_execution


class NativeCoachTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / "profile"
        self.home.mkdir()
        self.store = Store(Path(self.tmp.name) / "career")
        (self.home / "career-coach.json").write_text(encoded({"data_directory": str(self.store.directory)}), encoding="utf-8")
        self.identity = lambda: ("career-coach", self.home)
        self.bridge = NativeCoach(self.store, identity=self.identity, synchronize=lambda store: None)
        self.coach = self.bridge.coach
        with self.store.transaction() as db:
            self.store.set_meta(db, "coach_native_profiles", {"career-coach": {
                "owner": "phone", "callers": ["default"], "home": str(self.home)}})
        self.host = patch("career_core.native_coach.host_identity", side_effect=self.identity)
        self.host.start()
        self.addCleanup(self.host.stop)

    def begin(self, text="本人は野球部で調整役を務めた", session="session-1", task="task-1"):
        context = self.bridge.before(session_id=session, task_id=task, user_message=text, model="existing-model")
        run = self.bridge.authorize({"session_id": session, "task_id": task})
        return context, run

    def test_bootstrap_discovers_legacy_consultations_and_does_not_overwrite_them(self):
        old = self.coach.submit({"operation_id": "old-message-1", "text": "以前の面接相談"}, "phone")
        before = self.coach.snapshot("phone")
        context, run = self.begin()
        self.assertIn("以前の面接相談", context["context"])
        self.assertIn("career_recall", context["context"])
        self.assertEqual(before["messages"], self.coach.snapshot("phone")["messages"])
        self.assertNotEqual("global", run["thread_id"])
        self.assertEqual("queued", self.coach.run(old["id"])["state"])

    def test_original_native_user_quote_can_be_verified_but_tool_context_cannot(self):
        _, run = self.begin()
        proof = run["request"]["native_user_statements"][0]
        tools = CoachTools(self.coach)
        with self.store.connect() as db:
            self.assertEqual(proof["id"], tools.evidence(db, run, {"user_statement": {
                "message_id": proof["id"], "quote": "野球部"}})["message_id"])
            with self.assertRaises(CareerError):
                tools.evidence(db, run, {"user_statement": {"message_id": proof["id"], "quote": "AIが考えた強み"}})

    def test_task_session_profile_and_expiry_are_all_enforced(self):
        _, run = self.begin()
        for meta in ({"session_id": "other", "task_id": "task-1"}, {"session_id": "session-1", "task_id": "forged"}):
            with self.assertRaises(CareerError):
                self.bridge.authorize(meta)
        with self.store.transaction() as db:
            db.execute("UPDATE coach_native_turns SET deadline=0 WHERE run_id=?", (run["id"],))
        with self.assertRaises(CareerError):
            self.bridge.authorize({"session_id": "session-1", "task_id": "task-1"})
        wrong = NativeCoach(self.store, identity=lambda: ("career-coach", self.home / "other"))
        with self.assertRaises(CareerError):
            wrong.binding()

    def test_native_context_and_shared_save_use_the_same_registered_tools(self):
        _, run = self.begin()
        metadata = {"session_id": "session-1", "task_id": "task-1"}
        result = CoachTools(self.coach).dispatch("career_recall", {"kind": "messages"}, metadata)
        self.assertTrue(any(item["evidence_type"] == "original_user_statement" for item in result["items"]))
        saved = CoachTools(self.coach).dispatch("career_save", {"kind": "tasks", "id": "next",
            "expected_version": 0, "data": {"title": "面接の準備"}}, metadata)
        self.assertIsNotNone(saved)
        self.bridge.end(session_id="session-1", interrupted=True)
        with self.assertRaises(CareerError):
            CoachTools(self.coach).dispatch("career_save", {"kind": "tasks", "id": "late", "expected_version": 0,
                "data": {"title": "停止後"}}, metadata)

    def test_native_run_is_never_claimed_or_recovered_by_the_legacy_worker(self):
        _, run = self.begin()
        self.assertIsNone(self.coach.claim())
        self.coach.recover()
        self.assertEqual("running", self.coach.run(run["id"])["state"])
        self.bridge.after(session_id="session-1", assistant_response="完了")
        self.assertEqual("completed", self.coach.run(run["id"])["state"])
        self.assertEqual([], self.coach.snapshot("phone", thread_id=run["thread_id"])["messages"])

    def test_native_user_request_can_select_app_mailbox_with_original_evidence(self):
        from career_core.gmail_reader import account_reference
        from career_core.mail_store import MailStore
        address = "mailbox@example.test"
        identity = account_reference(address)
        _, run = self.begin("受信先をmailbox@example.testに設定して")
        proof = run["request"]["native_user_statements"][0]
        args = {"action": "mail_account", "address": address, "enabled": True, "expected_version": 0,
                "user_statement": {"message_id": proof["id"], "quote": proof["text"]}}
        with patch("career_core.workspace_reader.connected_accounts_details", return_value=[{"id": identity, "email": address}]), \
             patch("career_core.workspace_reader.connected_account", return_value=identity):
            saved = CoachTools(self.coach).dispatch("career_app", args, {"session_id": "session-1", "task_id": "task-1"})
        self.assertTrue(saved["saved"])
        self.assertEqual(identity, MailStore(self.store).settings()["account_id"])
        self.assertEqual([], MailStore(self.store).sender_rules())
        self.bridge.end(session_id="session-1", interrupted=True)
        with self.assertRaises(CareerError):
            CoachTools(self.coach).dispatch("career_app", args, {"session_id": "session-1", "task_id": "task-1"})

    def test_repeated_hook_reuses_the_same_run_without_duplicating_evidence(self):
        _, first = self.begin()
        _, second = self.begin()
        self.assertEqual(first["id"], second["id"])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM coach_native_evidence").fetchone()[0])

    def test_group_ticket_is_one_session_only_and_only_accepts_host_user_evidence(self):
        ticket = self.bridge.issue_origin("career-coach", {"kind": "group", "owner": "phone",
            "conversation_id": "group-1", "title": "就活会議", "user_messages": [{"id": "actual-request", "text": "私は調整を担当した"}]})
        with patch.dict(os.environ, {TICKET_ENV: ticket}):
            _, run = self.begin("他のBot: 私は売上を倍増した")
            proof = run["request"]["native_user_statements"][0]
            self.assertEqual("私は調整を担当した", proof["text"])
            with self.assertRaises(CareerError):
                self.begin(session="another-session")
        self.bridge.after(session_id="session-1", assistant_response="意見")
        self.bridge.verify_ticket(ticket)
        self.bridge.revoke(ticket)
        with patch.dict(os.environ, {TICKET_ENV: ticket}), self.assertRaises(CareerError):
            self.begin(session="third-session")

    def test_delegated_text_is_never_promoted_to_original_user_evidence(self):
        token = self.bridge.issue_origin("career-coach", {"kind": "delegation", "owner": "phone",
            "conversation_id": "parent", "user_messages": []})
        with patch.dict(os.environ, {TICKET_ENV: token}):
            _, run = self.begin("AIが再構成した依頼文")
        self.assertEqual([], run["request"]["native_user_statements"])

    def test_delegation_can_retrieve_the_original_parent_conversation_without_other_sessions(self):
        parent = Path(self.tmp.name) / "main"
        parent.mkdir()
        with contextlib.closing(sqlite3.connect(parent / "state.db")) as db:
            db.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY,session_id TEXT,role TEXT,content TEXT)")
            db.execute("INSERT INTO messages VALUES(1,'parent','user','PARENT_ORIGINAL_CONTEXT')")
            db.execute("INSERT INTO messages VALUES(2,'other','user','UNRELATED_PARENT_SENTINEL')")
            db.commit()
        token = self.bridge.issue_origin("career-coach", {"kind": "delegation", "owner": "phone",
            "conversation_id": "parent", "user_messages": [], "source_sessions": [{"profile": "default",
            "state_path": str(parent / "state.db"), "session_id": "parent"}]})
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: parent
        with patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.profiles": profiles}), \
             patch.dict(os.environ, {TICKET_ENV: token}):
            context, run = self.begin("メインからの相談")
        self.assertIn("PARENT_ORIGINAL_CONTEXT", context["context"])
        self.assertNotIn("UNRELATED_PARENT_SENTINEL", context["context"])
        result = CoachRecall(self.coach).read({"query": "PARENT_ORIGINAL_CONTEXT"}, run)
        self.assertEqual("hermes_parent", result["items"][0]["source"])

    def test_recall_searches_all_old_threads_and_retrieves_long_originals_in_pages(self):
        from career_core.threads import create_thread
        text = "原文" * 17000 + "末尾の根拠"
        thread = create_thread(self.coach, {"operation_id": "thread-create-1", "title": "過去の相談"}, "phone")
        # Existing API limits a single message to 20k; retained historic text can be larger.
        old = self.coach.submit({"operation_id": "old-message-1", "text": "昔の話", "thread_id": thread["id"]}, "phone")
        with self.store.transaction() as db:
            db.execute("UPDATE coach_messages SET text=? WHERE run_id=?", (text, old["id"]))
        _, run = self.begin()
        recall = CoachRecall(self.coach)
        found = recall.read({"query": "末尾の根拠", "kind": "messages"}, run)
        self.assertEqual(1, found["total"])
        identity = found["items"][0]["id"]
        body, position = "", 0
        while position is not None:
            item = recall.read({"id": identity, "text_offset": position}, run)["items"][0]
            body += item["text"]
            position = item["next_text_offset"]
        self.assertEqual(text, body)

    def test_other_owner_history_never_appears_and_every_category_has_bootstrap_coverage(self):
        self.coach.submit({"operation_id": "private-message-1", "text": "別所有者だけの情報"}, "other")
        with self.store.transaction() as db:
            for i in range(130):
                self.store.put(db, "tasks", "task-" + str(i), 0, {"title": "準備"})
            self.store.put(db, "companies", "last-company", 0, {"name": "最後の企業"})
        context, run = self.begin()
        self.assertNotIn("別所有者だけの情報", context["context"])
        bootstrap = CoachRecall(self.coach).bootstrap(run)
        self.assertEqual(130, bootstrap["counts"]["tasks"])
        self.assertTrue(any(row["kind"] == "companies" for row in bootstrap["items"]))

    def test_native_history_is_read_from_hermes_without_copying_transcripts(self):
        _, run = self.begin()
        host_db = self.home / "state.db"
        with contextlib.closing(sqlite3.connect(host_db)) as db:
            db.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY,session_id TEXT,role TEXT,content TEXT)")
            db.execute("INSERT INTO messages VALUES(1,'session-1','assistant','過去のコーチの返答')")
            db.execute("INSERT INTO messages VALUES(2,'unrelated','user','UNRELATED_ONLY_SENTINEL')")
            db.commit()
        before = host_db.read_bytes()
        result = CoachRecall(self.coach).read({"query": "過去のコーチ"}, run)
        self.assertEqual("hermes_state", result["items"][0]["source"])
        self.assertEqual(before, host_db.read_bytes())
        self.assertEqual(0, CoachRecall(self.coach).read({"query": "UNRELATED_ONLY_SENTINEL"}, run)["total"])

    def test_history_aliases_are_explicit_and_original_profile_ids_are_retained(self):
        self.coach.submit({"operation_id": "desktop-message-1", "text": "DESKTOP_ORIGINAL_CONTEXT"}, "desktop")
        with self.store.transaction() as db:
            bindings = self.store.meta(db, "coach_native_profiles")
            bindings["career-coach"]["history_profiles"] = ["phone", "desktop"]
            self.store.set_meta(db, "coach_native_profiles", bindings)
        _, run = self.begin()
        result = CoachRecall(self.coach).read({"query": "DESKTOP_ORIGINAL_CONTEXT"}, run)
        self.assertEqual("desktop", result["items"][0]["source_profile"])
        self.assertEqual("DESKTOP_ORIGINAL_CONTEXT", self.coach.snapshot("desktop")["messages"][0]["text"])

    def test_latest_corrections_and_guards_are_loaded_before_recall(self):
        from test_sharing import Repository
        from career_core.sync import GitHub, synchronize
        import httpx
        repository = Repository()
        token = Path(self.tmp.name) / "synthetic-token"
        token.write_text("synthetic-token", encoding="utf-8")
        with httpx.Client(transport=httpx.MockTransport(repository.request)) as client:
            synchronize(self.store, GitHub(token, client, repository="synthetic/private"))
        context, run = self.begin("自己分析の続きを相談")
        self.assertIn("COR-009", context["context"])
        result = CoachRecall(self.coach).read({"kind": "hypotheses"}, run)
        self.assertNotIn("HYP-001", [item["id"] for item in result["items"]])
        self.assertIn("HYP-001", result["context_constraints"]["blocked_ids"])
        self.assertIn("FACT-001", result["context_constraints"]["guarded_fact_ids"])


class GroupNativeIntegrationTests(unittest.TestCase):
    setUp = NativeCoachTests.setUp

    def test_missing_native_marker_never_falls_back_to_an_unscoped_group_prompt(self):
        import importlib
        with patch.object(sys, "path", [str(Path(__file__).resolve().parents[2] / "group_chat")] + sys.path):
            runtime_module = importlib.import_module("group_service")
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: self.home / "missing"
        with patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.profiles": profiles}), \
             patch("shutil.which", return_value="synthetic"), patch("subprocess.Popen") as child:
            with self.assertRaises(runtime_module.GroupError):
                runtime_module.CliRuntime().reply_with_context("career-coach", "untrusted group text", threading.Event(), {})
            child.assert_not_called()

    def test_group_launch_loads_legacy_context_and_only_the_accepted_user_statement(self):
        import importlib
        group_root = Path(__file__).resolve().parents[2] / "group_chat"
        with patch.object(sys, "path", [str(group_root)] + sys.path):
            runtime_module = importlib.import_module("group_service")
        self.coach.submit({"operation_id": "known-message-1", "text": "LEGACY_CONTEXT_SENTINEL"}, "phone")
        marker = {"data_directory": str(self.store.directory),
                  "plugin_root": str(Path(__file__).resolve().parents[1])}
        (self.home / "career-coach.json").write_text(encoded(marker), encoding="utf-8")
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda name: self.home
        original_popen = subprocess.Popen
        child = '''import json,os,sys
from pathlib import Path
from career_core.native_coach import NativeCoach
from career_core.coach_recall import CoachRecall
from career_core.store import Store
home=Path(os.environ['SYNTHETIC_COACH_HOME'])
data=json.loads((home/'career-coach.json').read_text(encoding='utf-8'))
bridge=NativeCoach(Store(data['data_directory']), identity=lambda: ('career-coach',home), synchronize=lambda store: None)
context=bridge.before(session_id='native-child',task_id='child-turn',user_message=sys.stdin.read(),model='synthetic')
assert 'LEGACY_CONTEXT_SENTINEL' in context['context']
run=bridge.authorize({'session_id':'native-child','task_id':'child-turn'})
assert [p['text'] for p in run['request']['native_user_statements']]==['AUTHENTIC_GROUP_USER']
bridge.after(session_id='native-child',assistant_response='synthetic answer')
print('native coach answered with retained context')
'''
        launched = []
        def launch(args, **kwargs):
            self.assertIn("--profile", args)
            self.assertNotIn("BOT_INFERENCE_SENTINEL", " ".join(args))
            launched.append(args)
            return original_popen([sys.executable, "-X", "utf8", "-c", child], **kwargs)
        with patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.profiles": profiles}), \
             patch.dict(os.environ, {"SYNTHETIC_COACH_HOME": str(self.home),
                 "PYTHONPATH": str(Path(__file__).resolve().parents[1])}), \
             patch("shutil.which", return_value="synthetic-hermes"), patch("subprocess.Popen", side_effect=launch):
            runtime = runtime_module.CliRuntime(owner_profile="default")
            reply = runtime.reply_with_context("career-coach", "BOT_INFERENCE_SENTINEL", threading.Event(),
                {"conversation_id": "stable-group", "title": "会議", "user_messages": [{"id": "request-1", "text": "AUTHENTIC_GROUP_USER"}]})
        self.assertIn("retained context", reply)
        self.assertEqual(1, len(launched))
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM coach_native_tickets").fetchone()[0])


class InstallerContractTests(unittest.TestCase):
    def test_installer_requires_actual_hook_dispatch_and_trusted_ids(self):
        import importlib.util
        file = Path(__file__).resolve().parents[1] / "tools" / "prepare_coach.py"
        spec = importlib.util.spec_from_file_location("prepare_native_coach", file)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        incomplete = "register_hook('pre_llm_call', callback)"
        self.assertIn("pre_llm_call:session_id", module.hook_contract([incomplete]))
        source = """invoke_hook('pre_llm_call',session_id=s,task_id=t,user_message=u)
invoke_hook('post_llm_call',session_id=s,assistant_response=r)
invoke_hook('on_session_end',session_id=s,completed=c,interrupted=i)
"""
        self.assertEqual([], module.hook_contract([source]))

    def test_install_preserves_existing_records_and_reuses_its_owned_profile(self):
        import importlib.util
        import yaml
        file = Path(__file__).resolve().parents[1] / "tools" / "prepare_coach.py"
        spec = importlib.util.spec_from_file_location("prepare_native_coach_install", file)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = Store(root / "data")
            CoachStore(store)
            with store.transaction() as db:
                store.put(db, "companies", "existing-company", 0, {"name": "既存企業"})
            original = store.snapshot()["entities"]
            home = root / "career-coach"
            profiles = types.ModuleType("hermes_cli.profiles")
            profiles.get_profile_dir = lambda name: home if name == "career-coach" else root
            profiles.profile_exists = lambda name: home.exists() if name == "career-coach" else True
            def create(name, **kwargs):
                self.assertTrue(kwargs["no_skills"])
                home.mkdir()
                (home / "config.yaml").write_text("model:\n  default: existing-model\n", encoding="utf-8")
                return home
            profiles.create_profile = create
            platforms = types.ModuleType("hermes_cli.platforms")
            platforms.PLATFORMS = {"cli": None, "web": None}
            with patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"),
                    "hermes_cli.profiles": profiles, "hermes_cli.platforms": platforms}), \
                 patch.object(Path, "symlink_to", lambda path, target, **kw: path.mkdir()):
                result = module.install(store.directory, "phone", "default")
                self.assertTrue(result["installed"])
                self.assertTrue(Path(result["backup"]).is_file())
                self.assertEqual(original, store.snapshot()["entities"])
                config = yaml.safe_load((home / "config.yaml").read_text(encoding="utf-8"))
                self.assertEqual(["career_coach"], config["platform_toolsets"]["cli"])
                self.assertEqual("existing-model", config["model"]["default"])
                before = (home / "config.yaml").read_bytes()
                second = module.install(store.directory, "phone", "default")
                self.assertTrue(second["already_installed"])
                self.assertEqual(before, (home / "config.yaml").read_bytes())


class ConsultationDeliveryTests(unittest.TestCase):
    setUp = NativeCoachTests.setUp
    def service(self):
        self.calls, self.sent = [], []
        def runtime(bot, prompt, origin, stop, store):
            self.calls.append((bot, prompt, origin))
            return "コーチの回答"
        def dispatch(**kwargs):
            self.sent.append(kwargs)
            return {"status": "dispatched"}
        return Consultations(self.store, identity=lambda: ("default", self.home / "main"),
            routing=lambda meta: {"parent_session_id": meta["session_id"], "session_key": meta["session_id"]},
            dispatch=dispatch, durable=lambda identity: None, runtime=runtime)

    def test_main_request_runs_one_native_coach_and_returns_to_original_session(self):
        service = self.service()
        result = service.call("career_consult", {"request_id": "request", "text": "ESを見て"}, {"session_id": "main-session"})
        self.assertEqual("queued", result["state"])
        self.assertEqual("main-session", self.sent[0]["session_key"])
        response = self.sent[0]["runner"]()
        self.assertEqual("completed", response["results"][0]["status"])
        self.assertEqual(1, len(self.calls))
        self.assertEqual([], self.calls[0][2]["user_messages"])
        self.sent[0]["runner"]()
        self.assertEqual(1, len(self.calls))

    def test_stop_does_not_start_a_queued_consultation_and_other_parent_cannot_read_it(self):
        service = self.service()
        meta = {"session_id": "parent"}
        service.call("career_consult", {"request_id": "one", "text": "相談"}, meta)
        service.call("career_consult_stop", {"request_id": "one"}, meta)
        self.sent[0]["runner"]()
        self.assertEqual([], self.calls)
        with self.assertRaises(CareerError):
            service.call("career_consult_status", {"request_id": "one"}, {"session_id": "other-parent"})

    def test_request_id_conflict_is_rejected(self):
        service = self.service()
        service.call("career_consult", {"request_id": "one", "text": "相談"}, {"session_id": "parent"})
        with self.assertRaises(CareerError):
            service.call("career_consult", {"request_id": "one", "text": "違う相談"}, {"session_id": "parent"})

    def test_main_current_original_is_forwarded_separately_from_the_model_summary(self):
        from career_core.coach_consult import capture_turn
        service = self.service()
        meta = {"session_id": "parent", "task_id": "typed-turn", "platform": "telegram",
                "user_message": "私は部活で調整を担当した。コーチにも相談して。",
                "conversation_history": [{"role": "user", "content": "私は部活で調整を担当した。コーチにも相談して。"}]}
        capture_turn(self.store, meta, identity=service.identity)
        service.call("career_consult", {"request_id": "original", "text": "MODEL_SUMMARY"}, meta)
        self.sent[0]["runner"]()
        self.assertEqual(meta["user_message"], self.calls[0][2]["user_messages"][0]["text"])

    def test_synthetic_completion_is_not_captured_as_a_human_statement(self):
        from career_core.coach_consult import capture_turn
        service = self.service()
        meta = {"session_id": "parent", "task_id": "synthetic-turn", "user_message": "SYSTEM_SUMMARY",
                "conversation_history": [{"role": "user", "content": "SYSTEM_SUMMARY", "display_kind": "delegation_status"}]}
        capture_turn(self.store, meta, identity=service.identity)
        service.call("career_consult", {"request_id": "synthetic", "text": "相談"}, meta)
        self.sent[0]["runner"]()
        self.assertEqual([], self.calls[0][2]["user_messages"])


if __name__ == "__main__":
    unittest.main()
