"""Mocked filesystem/service tests; does not connect to a server."""
import contextlib
import hashlib
import json
import zipfile
import importlib.util
import io
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import urllib.error

SOURCE = Path(__file__).resolve().parents[1] / "tools/update_server.py"
if "pwd" not in sys.modules and sys.platform == "win32":
    sys.modules["pwd"] = types.SimpleNamespace(getpwnam=lambda name: types.SimpleNamespace(pw_uid=1001, pw_gid=1001))
spec = importlib.util.spec_from_file_location("update_career", SOURCE)
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class UpdateTests(unittest.TestCase):
    def test_candidate_routes_are_checked_in_a_fresh_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old = root / "installed"
            new = root / "candidate"
            for folder in (old, new):
                (folder / "career_core").mkdir(parents=True)
                (folder / "career_core/__init__.py").write_text("", encoding="utf-8")
            (old / "career_core/api.py").write_text("def create_router(): return None\n", encoding="utf-8")
            (new / "career_core/api.py").write_text(
                "from types import SimpleNamespace\n"
                "def create_router(*, run_worker=False):\n"
                "    return SimpleNamespace(routes=[SimpleNamespace(path=p) for p in "
                "['/home', '/home/content', '/snapshot', '/coach/conversation', '/coach/events', '/coach/capabilities', '/coach/daily-settings', '/coach/{action}', '/coach/thread/{action}', '/companies', '/companies/{company_id}', '/collection/notices', '/collection/client-mail', '/collection/{action}']])\n", encoding="utf-8")
            (new / "dashboard").mkdir()
            (new / "dashboard/plugin_api.py").write_text(
                "from career_core.api import create_router\nrouter = create_router(run_worker=True)\n", encoding="utf-8")
            # Reproduce the reported failure: a host import has cached old API.
            contaminated = (
                "import sys; sys.path.insert(0, sys.argv[2]); import career_core.api;\n" + update.PROBE_ROUTES
            )
            result = subprocess.run([sys.executable, "-I", "-c", contaminated, str(new), str(old)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unexpected keyword argument 'run_worker'", result.stderr)
            # The deployed fix uses an independent interpreter for candidate API.
            result = subprocess.run([sys.executable, "-I", "-c", update.PROBE_ROUTES, str(new)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("ROUTES_OK", result.stdout)

    def scenario(self, failure=None, version="0.4.0"):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            plugin = base / ".hermes/plugins/career"
            plugin.mkdir(parents=True)
            (plugin / "local-note.txt").write_text("preserve this", encoding="utf-8")
            (base / ".hermes/config.yaml").write_text("plugins: {enabled: [career]}", encoding="utf-8")
            data = base / "career"
            data.mkdir()
            (data / "runtime.env").write_text("CAREER_CODEX_MODEL=existing", encoding="utf-8")
            with contextlib.closing(sqlite3.connect(data / "career.sqlite3")) as database:
                database.execute("CREATE TABLE existing(value TEXT)")
                database.execute("INSERT INTO existing VALUES ('keep')")
                database.commit()
            if failure == "new-database":
                (data / "career.sqlite3").unlink()
            store_directory = base / "actual-career" if failure == "alternate-database" else data
            if failure == "alternate-database":
                store_directory.mkdir()
                (store_directory / "career.sqlite3").write_bytes((data / "career.sqlite3").read_bytes())
            archive = base / "synthetic-code.zip"
            code = ("version: " + version + "\n").encode()
            files = {"server/career/plugin.yaml": code, **{
                "server/career/" + name: b"synthetic inert fixture\n" for name in
                ("career_core/deployment.py", "career_core/api.py", "dashboard/plugin_api.py")}}
            release = {"version": version, "sha256": {name: hashlib.sha256(value).hexdigest() for name, value in files.items()}}
            with zipfile.ZipFile(archive, "w") as package:
                for name, value in files.items():
                    package.writestr(name, value)
                package.writestr("RELEASE.json", json.dumps(release))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            operations = []
            failed_once = False

            def call(args, **kwargs):
                nonlocal failed_once
                if args[1] == "start" and (plugin / "local-note.txt").exists():
                    self.assertTrue(proof.return_value.close.called, "Old code must not inherit the candidate challenge")
                operations.append(args)
                if failure == "stop" and args[1] == "stop":
                    raise RuntimeError("simulated stop failure")
                if failure == "candidate-stop" and args[1] == "stop" and failed_once:
                    raise RuntimeError("simulated candidate stop failure")
                if failure in {"schema", "data", "unknown", "old-code", "candidate-stop", "new-database", "alternate-database", "candidate-directory-mismatch"} and args[1] == "start" and not failed_once:
                    failed_once = True
                    if failure in {"schema", "data"}:
                        with contextlib.closing(sqlite3.connect(data / "career.sqlite3")) as database:
                            with database:
                                database.execute("CREATE TABLE migration_flag(value TEXT)" if failure == "schema" else "UPDATE existing SET value='unsynced-new'")
                    elif failure == "unknown":
                        (data / "career.sqlite3").write_bytes(b"not a sqlite database")
                    elif failure == "old-code":
                        next((base / "career-update-backups").glob("*/career-plugin-old/local-note.txt")).write_text("drift", encoding="utf-8")
                    elif failure == "new-database":
                        with contextlib.closing(sqlite3.connect(data / "career.sqlite3")) as database:
                            with database:
                                database.execute("CREATE TABLE existing(value TEXT)")
                                database.execute("INSERT INTO existing VALUES('keep')")
                    elif failure == "alternate-database":
                        with contextlib.closing(sqlite3.connect(store_directory / "career.sqlite3")) as database:
                            with database:
                                database.execute("UPDATE existing SET value='actual-unsynced-new'")
                    raise RuntimeError("simulated post-start failure")
                if failure == "start" and args[1] == "start" and not failed_once:
                    failed_once = True
                    raise RuntimeError("simulated start failure")
                return types.SimpleNamespace(stdout="", returncode=0)

            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(update, "ARCHIVE", archive))
                stack.enter_context(patch.object(update, "EXPECTED", digest))
                stack.enter_context(patch.object(update, "VERSION", version))
                stack.enter_context(patch.object(update, "pwd", types.SimpleNamespace(getpwnam=lambda name: types.SimpleNamespace(pw_uid=1001, pw_gid=1001))))
                stack.enter_context(patch.object(update, "BASE", base))
                stack.enter_context(patch.object(update, "PLUGIN", plugin))
                stack.enter_context(patch.object(update, "call", call))
                stack.enter_context(patch.object(update, "active", lambda name: name == update.SERVICE))
                stack.enter_context(patch.object(update, "running", return_value=failure != "not-active"))
                stack.enter_context(patch.object(update, "running_store", side_effect=RuntimeError("metadata unavailable") if failure == "metadata" else None,
                                                 return_value=(100, "old-start", store_directory)))
                proof = stack.enter_context(patch.object(update, "MountProof"))
                proof.return_value.confirmed.return_value = failure != "missing-receipt"
                proof.return_value.directory_matches.return_value = failure != "candidate-directory-mismatch"
                stack.enter_context(patch.object(update.time, "sleep"))
                probe = stack.enter_context(patch.object(update, "as_hermes", side_effect=RuntimeError("incompatible") if failure == "preflight" else None))
                stack.enter_context(patch.object(update.os, "geteuid", return_value=0, create=True))
                stack.enter_context(patch.object(update.os, "chown", create=True))
                stack.enter_context(patch.object(update.os, "umask", return_value=0o077))
                stack.enter_context(patch.object(update.urllib.request, "urlopen", side_effect=urllib.error.HTTPError("http://test", 401, "Unauthorized", {}, None)))
                if failure == "digest":
                    stack.enter_context(patch.object(update, "EXPECTED", "bad"))
                with contextlib.redirect_stdout(io.StringIO()):
                    if failure:
                        with self.assertRaises(RuntimeError):
                            update.main()
                    else:
                        update.main()
            if failure != "unknown":
                with contextlib.closing(sqlite3.connect(data / "career.sqlite3")) as database:
                    self.assertEqual(database.execute("SELECT value FROM existing").fetchall(), [("unsynced-new" if failure == "data" else "keep",)])
            if failure == "alternate-database":
                with contextlib.closing(sqlite3.connect(store_directory / "career.sqlite3")) as database:
                    self.assertEqual(database.execute("SELECT value FROM existing").fetchall(), [("actual-unsynced-new",)])
            self.assertEqual((data / "runtime.env").read_text(encoding="utf-8"), "CAREER_CODEX_MODEL=existing")
            self.assertEqual((base / ".hermes/config.yaml").read_text(encoding="utf-8"), "plugins: {enabled: [career]}")
            if failure:
                held = failure in {"schema", "data", "unknown", "old-code", "candidate-stop", "new-database", "alternate-database", "candidate-directory-mismatch"}
                if held:
                    self.assertTrue((plugin / "plugin.yaml").is_file())
                    self.assertTrue(next((base / "career-update-backups").glob("*/career-plugin-old")).is_dir())
                else:
                    self.assertEqual((plugin / "local-note.txt").read_text(encoding="utf-8"), "preserve this")
                if failure in ("digest", "preflight", "metadata"):
                    self.assertEqual(operations, [])
                elif failure == "stop":
                    self.assertEqual([op[1] for op in operations], ["stop"])
                elif held:
                    self.assertEqual([op[1] for op in operations], ["stop", "start", "stop"])
                    if failure != "candidate-stop":
                        self.assertTrue(next((base / "career-update-backups").glob("*/career-post-failure.sqlite3")).is_file())
                else:
                    self.assertEqual([op[1] for op in operations], ["stop", "start", "stop", "start"])
            else:
                self.assertEqual(probe.call_count, 3)
                self.assertEqual(probe.call_args_list[0].args[0], update.PROBE_MOUNT)
                self.assertEqual(probe.call_args_list[1].args, (update.PROBE_RUNTIME,))
                self.assertEqual(probe.call_args_list[2].args[0], update.PROBE_ROUTES)
                self.assertIn("version: " + version, (plugin / "plugin.yaml").read_text(encoding="utf-8"))
                backups = list((base / "career-update-backups").iterdir())
                self.assertEqual(len(backups), 1)
                self.assertTrue((backups[0] / "career-plugin-old/local-note.txt").is_file())
                with contextlib.closing(sqlite3.connect(backups[0] / "career.sqlite3")) as database:
                    self.assertEqual(database.execute("SELECT value FROM existing").fetchall(), [("keep",)])

    def test_update_preserves_data_and_backups(self):
        self.scenario()

    def test_mypage_release_preserves_data_and_backups(self):
        self.scenario(version="0.5.0")

    def test_failed_restart_restores_old_code(self):
        self.scenario("start")

    def test_incompatible_runtime_does_not_stop_service(self):
        self.scenario("preflight")

    def test_wrong_archive_does_not_change_service(self):
        self.scenario("digest")

    def test_anonymous_401_without_live_startup_receipt_fails(self):
        self.scenario("missing-receipt")

    def test_transitional_service_with_receipt_is_not_ready(self):
        self.scenario("not-active")

    def test_schema_change_holds_current_database_and_both_versions(self):
        self.scenario("schema")

    def test_same_count_unsynced_change_holds_current_database(self):
        self.scenario("data")

    def test_unreadable_database_requires_operator_recovery(self):
        self.scenario("unknown")

    def test_newly_created_database_is_retained_without_rollback(self):
        self.scenario("new-database")

    def test_actual_alternate_database_change_holds_with_default_unchanged(self):
        self.scenario("alternate-database")

    def test_candidate_changed_data_directory_is_not_rolled_back(self):
        self.scenario("candidate-directory-mismatch")

    def test_missing_running_process_metadata_refuses_before_stop(self):
        self.scenario("metadata")

    def test_changed_old_code_is_not_restarted(self):
        self.scenario("old-code")

    def test_initial_stop_failure_does_not_restart(self):
        self.scenario("stop")

    def test_candidate_stop_failure_does_not_restore_or_restart(self):
        self.scenario("candidate-stop")


if __name__ == "__main__":
    unittest.main()
