"""Synthetic regression controls for the remaining server review findings."""
import asyncio
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]


def tool(name):
    spec = importlib.util.spec_from_file_location("remaining_" + name, ROOT / "tools" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RemainingServerTests(unittest.TestCase):
    def test_invalid_classification_abstains_per_source_but_transient_failure_commits_none(self):
        import test_action_classification as fixtures
        from career_core.action_classification import classify_saved_mail_actions
        from career_core.store import CareerError
        fixture = fixtures.ProjectionTests("runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        for number in range(2):
            fixture.save(fixtures.fixture_source(prefix=str(number)), str(number))
        calls = []
        def classify(source):
            calls.append(source.id)
            return {"invalid": True} if len(calls) == 1 else fixtures.matched(source)
        classifier = types.SimpleNamespace(classify=classify)
        result = classify_saved_mail_actions(fixture.store, "default", classifier)
        self.assertEqual((2, 1, 1), (result["classified"], result["matched"], result["abstained"]))
        self.assertEqual(0, classify_saved_mail_actions(fixture.store, "default", classifier)["classified"])
        with fixture.store.transaction() as db:
            db.execute("DELETE FROM metadata WHERE key LIKE 'home-action-classification:%'")
        fixture.save(fixtures.fixture_source(prefix="third"), "third")
        with fixture.store.connect() as db:
            before = list(db.iterdump())
        classifier.classify = lambda source: (_ for _ in ()).throw(CareerError("action_classification_paused", "paused", 503))
        with self.assertRaises(CareerError):
            classify_saved_mail_actions(fixture.store, "default", classifier)
        with fixture.store.connect() as db:
            self.assertEqual(before, list(db.iterdump()))

    def test_same_login_url_requires_logout_proof_and_rejects_password_prompt(self):
        from career_core.mypage_native_browser import NativeBrowser
        from career_core.store import CareerError
        browser = NativeBrowser("task", "https://fixture.test/login", {}, validate_host=lambda url: None)
        browser.authenticated = True
        browser.command = lambda *args: {"url": browser.url}
        self.assertTrue(browser.capture({"success": True, "snapshot": 'link "ログアウト" [ref=e1]'})["success"])
        for text in ["ログインしてください", 'textbox "パスワード"\nlink "ログアウト" [ref=e1]']:
            with self.assertRaises(CareerError) as failure:
                browser.capture({"success": True, "snapshot": text})
            self.assertEqual("authentication_required", failure.exception.code)

    def test_null_mail_timestamp_and_missing_or_real_portal_timestamp_render(self):
        from career_core.company_views import CompanyViews
        from career_core.store import Store
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            with store.transaction() as db:
                store.put(db, "companies", "one", 0, {"name": "one"})
            view = CompanyViews(store)
            with patch.object(view.mail, "sender_rules", return_value=[{"company_id": "one", "enabled": True}]), \
                 patch.object(view.mail, "status", return_value={"state": "not_started", "last_success_at": None}):
                for timestamp in [None, "2026-10-20T00:00:00+00:00"]:
                    portal = {"supported": True, "status": {"state": "not_started"}, "connection_version": 0}
                    if timestamp:
                        portal["status"]["last_success_at"] = timestamp
                    with patch.object(view.mypage, "view", return_value=portal):
                        self.assertEqual(timestamp, view.overview()["companies"][0]["last_success_at"])

    def test_sync_network_runs_without_live_lock_and_concurrent_edits_stay_dirty(self):
        from career_core.store import Store
        from career_core.sync import synchronize
        from test_career import Remote
        with tempfile.TemporaryDirectory() as directory:
            store, remote = Store(directory), Remote()
            self.assertEqual("synced", synchronize(store, remote)["state"])
            read = remote.read
            def concurrent_read():
                with store.transaction() as db:
                    row = store.entity(db, "facts", "FACT-001")
                    store.put(db, "facts", row["id"], row["version"], dict(row["data"], statement="changed during read"))
                return read()
            remote.read = concurrent_read
            self.assertEqual("unsynced", synchronize(store, remote)["state"])
            with store.connect() as db:
                row = store.entity(db, "facts", "FACT-001")
                self.assertEqual(("changed during read", 1), (row["data"]["statement"], row["dirty"]))
            remote.read = read
            self.assertEqual("synced", synchronize(store, remote)["state"])

    def test_sync_publication_edit_and_second_sync_cannot_clear_pending_work(self):
        from career_core.locking import job_lock
        from career_core.store import Store
        from career_core.sync import synchronize
        from test_career import Remote
        with tempfile.TemporaryDirectory() as directory:
            store, remote = Store(directory), Remote()
            synchronize(store, remote)
            with job_lock(store.directory / "github-sync.lock") as acquired:
                self.assertTrue(acquired)
                self.assertEqual("running", synchronize(store, remote)["state"])
            with store.transaction() as db:
                row = store.entity(db, "facts", "FACT-001")
                store.put(db, "facts", row["id"], row["version"], dict(row["data"], statement="first"))
            remote.state["candidate"]["university"] = "remote university"
            write = remote.write
            def concurrent_write(value, sha):
                with store.transaction() as db:
                    row = store.entity(db, "facts", "FACT-001")
                    store.put(db, "facts", row["id"], row["version"], dict(row["data"], statement="second"))
                return write(value, sha)
            remote.write = concurrent_write
            self.assertEqual("unsynced", synchronize(store, remote)["state"])
            with store.connect() as db:
                row = store.entity(db, "facts", "FACT-001")
                self.assertEqual(("second", 1), (row["data"]["statement"], row["dirty"]))
                self.assertEqual("remote university", store.entity(db, "profile", "candidate")["data"]["university"])
            remote.write = write
            self.assertEqual("synced", synchronize(store, remote)["state"])
            self.assertEqual("second", remote.state["facts"][0]["statement"])

    def test_sync_preserves_checkpoint_added_during_remote_read(self):
        from career_core.store import Store
        from career_core.sync import synchronize
        from test_career import Remote
        with tempfile.TemporaryDirectory() as directory:
            store, remote = Store(directory), Remote()
            synchronize(store, remote)
            read = remote.read
            def concurrent_read():
                with store.transaction() as db:
                    store.set_meta(db, "coach_checkpoint_pending", {"run-new": {"summary": "new"}})
                return read()
            remote.read = concurrent_read
            self.assertEqual("unsynced", synchronize(store, remote)["state"])
            with store.connect() as db:
                self.assertEqual({"run-new": {"summary": "new"}}, store.meta(db, "coach_checkpoint_pending"))

    def test_context_edit_during_publication_rebases_without_false_conflict(self):
        import test_sharing as fixtures
        from career_core.sharing import save_context_patch
        from career_core.sync import synchronize
        fixture = fixtures.SharingTests("runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.assertEqual("synced", fixture.sync()["state"])
        def edit(text, version):
            with fixture.store.transaction() as db:
                save_context_patch(fixture.store, db, {"id": "OQ-002", "expected_version": version,
                    "data": {"section": "open_questions", "record": {"id": "OQ-002", "question": text, "status": "unanswered"}}},
                    {"message_id": "synthetic-message", "quote": text})
        edit("first", 0)
        remote = fixture.remote()
        self.addCleanup(remote.client.close)
        write = remote.write
        def concurrent_write(value, sha):
            result = write(value, sha)
            edit("second", 1)
            return result
        remote.write = concurrent_write
        self.assertEqual("unsynced", synchronize(fixture.store, remote)["state"])
        with fixture.store.connect() as db:
            pending = fixture.store.meta(db, "jobhunt_context_pending")["open_questions:OQ-002"]
            self.assertEqual(("first", "second"), (pending["base"]["question"], pending["data"]["question"]))
        self.assertEqual("synced", fixture.sync()["state"])
        with fixture.store.connect() as db:
            self.assertEqual({}, fixture.store.meta(db, "jobhunt_context_pending"))
            bundle = fixture.store.meta(db, "jobhunt_context_bundle")
            self.assertEqual("second", next(item for item in bundle["open_questions"] if item["id"] == "OQ-002")["question"])

    def test_mobile_action_store_work_yields_the_event_loop(self):
        import httpx
        from fastapi import FastAPI
        from career_core.mobile_sdui import create_mobile_router
        from career_core.store import Store
        entered, released = threading.Event(), threading.Event()
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            def factory():
                entered.set()
                released.wait(1)
                return store
            app = FastAPI()
            app.include_router(create_mobile_router(factory))
            async def exercise():
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture.test") as client:
                    task = asyncio.create_task(client.post("/mobile/actions/mypage.check", json={
                        "protocol_version": 1, "release_id": "bundled-1", "company_id": "missing", "operation_id": "synthetic-operation-0001"}))
                    for attempt in range(100):
                        if entered.is_set():
                            break
                        await asyncio.sleep(.005)
                    self.assertTrue(entered.is_set())
                    self.assertFalse(task.done())
                    released.set()
                    self.assertEqual(404, (await task).status_code)
            try:
                asyncio.run(exercise())
            finally:
                released.set()

    def test_failed_coach_install_retains_new_profile_and_retry_handles_fresh_tables(self):
        from career_core.store import Store
        module = tool("prepare_coach")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = Store(root / "data")
            with store.transaction() as db:
                store.put(db, "companies", "kept", 0, {"name": "kept"})
            home = root / "career-coach"
            profiles = types.ModuleType("hermes_cli.profiles")
            profiles.get_profile_dir = lambda name: home
            profiles.profile_exists = lambda name: home.exists() if name == "career-coach" else True
            def create(*args, **kwargs):
                home.mkdir()
                (home / "config.yaml").write_text("model: {default: kept}\n", encoding="utf-8")
                return str(home)
            profiles.create_profile = create
            platforms = types.ModuleType("hermes_cli.platforms")
            platforms.PLATFORMS = {"cli": None}
            modules = {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.profiles": profiles,
                       "hermes_cli.platforms": platforms}
            with patch.dict(sys.modules, modules), patch.object(Path, "symlink_to", lambda path, target, **kw: path.mkdir()):
                with patch.object(module, "configure_profile", side_effect=RuntimeError("synthetic failure")):
                    with self.assertRaises(RuntimeError):
                        module.install(store.directory, "phone")
                self.assertFalse(home.exists())
                retained = list(root.glob("career-coach-incomplete-*/profile/config.yaml"))
                self.assertEqual(1, len(retained))
                self.assertIn("kept", retained[0].read_text(encoding="utf-8"))
                self.assertTrue(module.install(store.directory, "phone")["installed"])
                self.assertTrue(module.install(store.directory, "phone")["already_installed"])
            self.assertEqual(["kept"], [row["id"] for row in store.snapshot()["entities"]])

    def test_mypage_preflight_reads_runtime_source_without_importing_it(self):
        module = tool("prepare_mypage_bot")
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "run_agent.py"
            source.write_text("raise AssertionError('must not import')\nclass AIAgent: pass\n", encoding="utf-8")
            imported = []
            def load(name):
                imported.append(name)
                if name == "run_agent":
                    raise AssertionError("runtime imported")
                return types.SimpleNamespace(__file__=str(source), __getattr__=lambda name: None)
            with patch.object(module.importlib.util, "find_spec", return_value=types.SimpleNamespace(origin=str(source))), \
                 patch.object(module.importlib, "import_module", side_effect=load), \
                 patch.object(module.subprocess, "run", return_value=types.SimpleNamespace(returncode=0, stdout="fixture")), \
                 patch.dict(os.environ, {"CAREER_BROWSER_PYTHON": sys.executable}):
                result = module.inspect_contract()
            self.assertNotIn("run_agent", imported)
            self.assertNotIn("run_agent.AIAgent", result["missing_contracts"])

    def test_coach_legacy_row_change_blocks_binding_and_foreign_profile_is_preserved(self):
        from career_core.store import Store
        module = tool("prepare_coach")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = Store(root / "data")
            home = root / "career-coach"
            profiles = types.ModuleType("hermes_cli.profiles")
            profiles.get_profile_dir = lambda name: home
            profiles.profile_exists = lambda name: home.exists() if name == "career-coach" else True
            def create(*args, **kwargs):
                home.mkdir()
                (home / "config.yaml").write_text("{}", encoding="utf-8")
                return home
            profiles.create_profile = create
            platforms = types.ModuleType("hermes_cli.platforms")
            platforms.PLATFORMS = {"cli": None}
            def modify_legacy(current):
                with current.transaction() as db:
                    current.put(db, "companies", "unexpected", 0, {"name": "unexpected"})
            with patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"),
                    "hermes_cli.profiles": profiles, "hermes_cli.platforms": platforms}), \
                 patch.object(Path, "symlink_to", lambda path, target, **kw: path.mkdir()):
                with patch("career_core.native_coach.NativeCoach", side_effect=modify_legacy):
                    with self.assertRaisesRegex(RuntimeError, "legacy_row_count_changed"):
                        module.install(store.directory, "phone")
                with store.connect() as db:
                    self.assertIsNone(store.meta(db, "coach_native_profiles"))
                home.mkdir()
                (home / "foreign").write_text("preserve", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "existing_profile_not_owned"):
                    module.install(store.directory, "phone")
                self.assertEqual("preserve", (home / "foreign").read_text(encoding="utf-8"))

    def test_second_release_workcopy_retains_old_edits_and_preserves_active_draft(self):
        import test_mobile_sdui as fixtures
        fixture = fixtures.ReleaseTests("runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        identity = fixture.candidate()
        fixtures.release.test_release(fixture.root, identity)
        fixtures.release.activate(fixture.root, identity, fixture.initial, identity)
        before = (fixture.root / "workcopy/payload/mypage_handler.py").read_bytes()
        second = fixtures.release.workcopy(fixture.root)
        self.assertTrue(second.is_dir())
        retained = list(fixture.root.glob("workcopy-retained-*/workcopy/payload/mypage_handler.py"))
        self.assertEqual([before], [path.read_bytes() for path in retained])
        with self.assertRaises(ValueError):
            fixtures.release.workcopy(fixture.root)

    def test_google_rollbacks_stop_before_restore_and_restart_restored_code(self):
        for name in ["install_google_connection", "install_google_multi_connection"]:
            module = tool(name)
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                api, extension, backup = root / "api.py", root / "google.py", root / "backup"
                backup.mkdir()
                api.write_bytes(b"new api")
                extension.write_bytes(b"new module")
                calls = []
                def command(args, **kwargs):
                    calls.append(args[1])
                    self.assertEqual(b"new api" if args[1] == "stop" else b"old api", api.read_bytes())
                with patch.object(module.subprocess, "run", side_effect=command):
                    if name.endswith("multi_connection"):
                        module.rollback([api, extension], {api: b"old api", extension: None},
                                        {api: b"new api", extension: b"new module"}, backup)
                    else:
                        module.rollback(api, extension, backup, b"old api", b"new api", b"new module")
                self.assertEqual(["stop", "restart"], calls)
                self.assertEqual(b"old api", api.read_bytes())
                self.assertFalse(extension.exists())
                self.assertTrue(any(path.read_bytes() == b"new module" for path in backup.iterdir()))
                api.write_bytes(b"another writer")
                extension.write_bytes(b"new module")
                with patch.object(module.subprocess, "run") as service:
                    with self.assertRaises(RuntimeError):
                        if name.endswith("multi_connection"):
                            module.rollback([api], {api: b"old api"}, {api: b"new api"}, backup)
                        else:
                            module.rollback(api, extension, backup, b"old api", b"new api", b"new module")
                    self.assertEqual(["stop"], [call.args[0][1] for call in service.call_args_list])
                self.assertEqual(b"another writer", api.read_bytes())


@unittest.skipUnless(os.name == "posix", "POSIX descriptors require the isolated Linux test workspace")
class PrivilegedPathTests(unittest.TestCase):
    def setUp(self):
        self.module = tool("privileged_paths")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.base = self.root / "hermes"
        self.base.mkdir()
        self.user = types.SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid())

    def test_single_google_rollback_restores_prior_mode_and_timestamp(self):
        module = tool("install_google_connection")
        api, extension, backup = self.root / "api.py", self.root / "google.py", self.root / "backup"
        backup.mkdir()
        saved = backup / "google_connection.py"
        saved.write_bytes(b"old module")
        saved.chmod(0o600)
        os.utime(saved, (123456789, 123456789))
        before = saved.stat()
        api.write_bytes(b"new api")
        extension.write_bytes(b"new module")
        extension.chmod(0o644)
        with patch.object(module.subprocess, "run"):
            module.rollback(api, extension, backup, b"old api", b"new api", b"new module")
        self.assertEqual(b"old module", extension.read_bytes())
        self.assertEqual((0o600, before.st_mtime_ns), (extension.stat().st_mode & 0o777, extension.stat().st_mtime_ns))

    def test_private_directory_control_and_symlink_substitution_never_change_victim(self):
        self.module.prepare_private(self.base, "private", self.user)
        private = self.base / "private"
        self.assertEqual(0o700, private.stat().st_mode & 0o777)
        (private / "kept").write_text("kept")
        self.module.prepare_private(self.base, "private", self.user)
        victim = self.root / "victim"
        victim.mkdir(mode=0o755)
        before = victim.stat()
        private.rename(self.base / "retained")
        private.symlink_to(victim, target_is_directory=True)
        with self.assertRaises(OSError):
            self.module.prepare_private(self.base, "private", self.user)
        self.assertEqual((before.st_uid, before.st_gid, before.st_mode),
                         (victim.stat().st_uid, victim.stat().st_gid, victim.stat().st_mode))
        self.assertEqual("kept", (self.base / "retained/kept").read_text())

    def test_replacement_during_fd_ownership_change_is_rejected(self):
        private = self.base / "private"
        victim = self.root / "victim"
        victim.mkdir(mode=0o755)
        before = victim.stat()
        original = os.fchown
        def substitute(descriptor, owner, group):
            private.rename(self.base / "retained")
            private.symlink_to(victim, target_is_directory=True)
            original(descriptor, owner, group)
        with patch.object(self.module.os, "fchown", side_effect=substitute):
            with self.assertRaises(RuntimeError):
                self.module.prepare_private(self.base, "private", self.user)
        self.assertEqual((before.st_uid, before.st_gid, before.st_mode),
                         (victim.stat().st_uid, victim.stat().st_gid, victim.stat().st_mode))

    def test_staging_control_and_replacement_before_publication(self):
        stage, expected = self.module.prepare_stage(self.base, "stage-", self.user)
        (stage / "kept").write_text("candidate")
        destination = self.base / "runtime"
        self.module.publish_stage(stage, destination, expected)
        self.assertEqual("candidate", (destination / "kept").read_text())
        for replacement in ["directory", "symlink"]:
            stage, expected = self.module.prepare_stage(self.base, "stage-", self.user)
            retained = stage.with_name(stage.name + "-retained")
            stage.rename(retained)
            if replacement == "symlink":
                stage.symlink_to(destination, target_is_directory=True)
            else:
                stage.mkdir()
            with self.assertRaises(RuntimeError):
                self.module.publish_stage(stage, self.base / ("bad-" + replacement), expected)
            self.assertFalse((self.base / ("bad-" + replacement)).exists())
            self.assertEqual("candidate", (destination / "kept").read_text())

    def test_writable_or_linked_ancestor_is_rejected(self):
        self.root.chmod(0o777)
        with self.assertRaises(RuntimeError):
            self.module.prepare_stage(self.base, "stage-", self.user)
        self.root.chmod(0o700)
        linked = self.root / "linked"
        linked.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(OSError):
            self.module.prepare_private(linked, "private", self.user)


if __name__ == "__main__":
    unittest.main()
