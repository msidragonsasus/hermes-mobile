"""Synthetic router lifecycles and Linux process proof; no host services or HTTP."""
import asyncio
from contextlib import asynccontextmanager
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import runpy
import sqlite3
import subprocess
import sys
import tempfile
import types
import unittest
import uuid
from unittest.mock import patch

from career_core import deployment

SOURCE = Path(__file__).resolve().parents[1] / "tools/update_server.py"
if "pwd" not in sys.modules and sys.platform == "win32":
    sys.modules["pwd"] = types.SimpleNamespace()
spec = importlib.util.spec_from_file_location("deployment_update_tests", SOURCE)
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)
PRIVATE_TEST_ROOT = False


class LifecycleTests(unittest.TestCase):
    def exercise(self, *, included=True, prefix=deployment.PREFIX, startup_failure=False, wrong_endpoint=False):
        from fastapi import APIRouter, FastAPI
        events = []
        receipts = []

        @asynccontextmanager
        async def host(app):
            events.append("host")
            yield
            events.append("host_closed")

        def start():
            events.append("started")
            if startup_failure:
                raise RuntimeError("synthetic startup failure")

        router = APIRouter()
        for path in deployment.REQUIRED:
            router.add_api_route(path, lambda: None)
        router.add_event_handler("startup", start)
        router.add_event_handler("shutdown", lambda: events.append("stopped"))
        with patch.object(deployment, "process_start", return_value="synthetic-start"):
            deployment.wrap_lifespan(router, {"nonce": "captured", "release": "candidate"}, receipts.append)
            app = FastAPI(lifespan=host)
            if included:
                app.include_router(router, prefix=prefix)
            if wrong_endpoint:
                next(route for route in app.routes if route.path.startswith(deployment.PREFIX)).endpoint = lambda: None
            self.assertEqual(receipts, [])

            async def run():
                async with app.router.lifespan_context(app):
                    if included:
                        self.assertEqual(events, ["host", "started"])
                        self.assertEqual(receipts[0], {"nonce": "captured", "release": "candidate", "pid": os.getpid(), "starttime": "synthetic-start"})

            if included and (prefix != deployment.PREFIX or startup_failure or wrong_endpoint):
                with self.assertRaises(RuntimeError):
                    asyncio.run(run())
                self.assertEqual(receipts, [])
            else:
                asyncio.run(run())
                self.assertEqual(events, ["host", "started", "stopped", "host_closed"] if included else ["host", "host_closed"])
                self.assertEqual(len(receipts), int(included))

    def test_real_fastapi_custom_lifespan_and_original_callbacks(self):
        self.exercise()
        deployment.check_lifespan_contract()

    def test_import_or_unincluded_router_has_no_receipt(self):
        self.exercise(included=False)

    def test_wrong_prefix_cannot_emit_receipt(self):
        self.exercise(prefix="/wrong")

    def test_endpoint_from_other_router_cannot_emit_receipt(self):
        self.exercise(wrong_endpoint=True)

    def test_failed_startup_cannot_emit_receipt(self):
        self.exercise(startup_failure=True)

    def test_static_host_contract_rejects_mount_inside_lifespan(self):
        server = "app = FastAPI(lifespan=_lifespan)\n_mount_plugin_api_routes()\n"
        loader = 'def _mount_plugin_api_routes():\n from hermes_cli.web_server import app\n app.include_router(router, prefix=f"/api/plugins/{plugin[\'name\']}")\n'
        deployment.check_host_contract(server, loader)
        with self.assertRaises(RuntimeError):
            deployment.check_host_contract("app = FastAPI(lifespan=_lifespan)\nasync def _lifespan(app):\n _mount_plugin_api_routes()\n", loader)
        with self.assertRaises(RuntimeError):
            deployment.check_host_contract(server, loader.replace("/api/plugins/", "/other/"))

    def test_request_release_members_are_verified_before_lifecycle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "source.py").write_bytes(b"reviewed")
            request = {"protocol": 1, "nonce": "a" * 64, "release": "candidate", "sha256": {"source.py": hashlib.sha256(b"reviewed").hexdigest()}, "data_directory": str(root.resolve())}
            from fastapi import APIRouter
            router = APIRouter()
            for path in deployment.REQUIRED:
                router.add_api_route(path, lambda: None)
            with patch.object(deployment, "read_request", return_value=request), patch.dict(os.environ, {"CAREER_DATA_DIR": str(root)}):
                deployment.attach(router, root)
                (root / "source.py").write_bytes(b"changed")
                with self.assertRaisesRegex(RuntimeError, "release differs"):
                    deployment.attach(router, root)


class DataDirectoryTests(unittest.TestCase):
    def test_process_absolute_relative_and_empty_paths_match_store_semantics(self):
        with tempfile.TemporaryDirectory() as temporary:
            current = Path(temporary).resolve()
            alternate = current / "alternate"
            alternate.mkdir()
            for configured, expected in ((str(alternate), alternate), ("alternate", alternate), ("", current)):
                environment = b"UNRELATED_SECRET=not-read-or-output\0CAREER_DATA_DIR=" + configured.encode() + b"\0"
                with patch.object(update, "process_start", return_value="start"), patch.object(update.os, "readlink", return_value=str(current)), \
                        patch.object(update.Path, "open", return_value=io.BytesIO(environment)):
                    self.assertEqual(update.process_store_directory(100, "start"), expected)

    def test_malformed_duplicate_missing_and_changed_process_metadata_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            for environment in (b"CAREER_DATA_DIR", b"CAREER_DATA_DIR=one\0CAREER_DATA_DIR=two\0", b"CAREER_DATA_DIR=unterminated"):
                with patch.object(update, "process_start", return_value="start"), patch.object(update.os, "readlink", return_value=temporary), \
                        patch.object(update.Path, "open", return_value=io.BytesIO(environment)):
                    with self.assertRaises(RuntimeError):
                        update.process_store_directory(100, "start")
            with patch.object(update, "process_start", return_value="changed"):
                with self.assertRaises(RuntimeError):
                    update.process_store_directory(100, "start")
            with patch.object(update, "process_start", return_value="start"), patch.object(update.Path, "open", side_effect=FileNotFoundError):
                with self.assertRaises(RuntimeError):
                    update.process_store_directory(100, "start")
            with patch.object(update, "process_start", return_value="start"), patch.object(update.Path, "open", return_value=io.BytesIO(b"")), \
                    patch.object(update.os, "readlink", side_effect=FileNotFoundError):
                with self.assertRaises(RuntimeError):
                    update.process_store_directory(100, "start")

    def test_main_pid_change_rejects_the_metadata_snapshot(self):
        with patch.object(update, "call", side_effect=[types.SimpleNamespace(stdout="100"), types.SimpleNamespace(stdout="101")]), \
                patch.object(update, "process_start", return_value="start"), \
                patch.object(update, "process_store_directory", return_value=Path("/synthetic")):
            with self.assertRaisesRegex(RuntimeError, "process changed"):
                update.running_store()

    def test_candidate_data_guard_runs_before_router_factory(self):
        with tempfile.TemporaryDirectory() as temporary:
            current = Path(temporary).resolve()
            alternate = current / "alternate"
            alternate.mkdir()
            factory = types.SimpleNamespace(create_router=lambda **kwargs: self.fail("Candidate Store/router factory must not run"))
            request = {"protocol": 1, "nonce": "a" * 64, "release": "fixture", "sha256": {}, "data_directory": str(current)}
            with patch.object(deployment, "read_request", return_value=request), patch.dict(os.environ, {"CAREER_DATA_DIR": str(alternate)}), \
                    patch.dict(sys.modules, {"career_core.api": factory}):
                with self.assertRaisesRegex(RuntimeError, "data directory differs"):
                    runpy.run_path(str(Path(__file__).resolve().parents[1] / "dashboard/plugin_api.py"))

    def test_relative_candidate_directory_is_frozen_to_reviewed_absolute_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            current = Path(temporary).resolve()
            alternate = current / "alternate"
            alternate.mkdir()
            request = {"protocol": 1, "nonce": "a" * 64, "release": "fixture", "sha256": {}, "data_directory": str(alternate)}
            previous = Path.cwd()
            try:
                os.chdir(current)
                with patch.object(deployment, "read_request", return_value=request), patch.dict(os.environ, {"CAREER_DATA_DIR": "alternate"}):
                    deployment.prepare(current)
                    self.assertEqual(os.environ["CAREER_DATA_DIR"], str(alternate))
            finally:
                os.chdir(previous)


class DatabaseIdentityTests(unittest.TestCase):
    def test_full_content_schema_rowid_and_sequence_are_observed(self):
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary).resolve() / "fixture.sqlite3"
            self.assertIsNone(update.database_identity(database))
            with contextlib.closing(sqlite3.connect(database)) as connection:
                with connection:
                    connection.execute("CREATE TABLE edits(value TEXT)")
                    connection.execute("INSERT INTO edits(rowid,value) VALUES(7,'unsynced')")
                    connection.execute("CREATE TABLE operations(id INTEGER PRIMARY KEY AUTOINCREMENT, receipt BLOB)")
                    connection.execute("INSERT INTO operations(receipt) VALUES(?)", (b"receipt",))
            original = update.database_identity(database)
            self.assertEqual(original, update.backup_database(database, database.with_name("copy.sqlite3")))
            for statement in ("UPDATE edits SET rowid=8", "UPDATE edits SET value='new-unsynced'", "UPDATE sqlite_sequence SET seq=20", "PRAGMA user_version=2", "CREATE TABLE migration_flag(value TEXT)"):
                before = update.database_identity(database)
                with contextlib.closing(sqlite3.connect(database)) as connection:
                    with connection:
                        connection.execute(statement)
                self.assertNotEqual(before, update.database_identity(database), statement)


@unittest.skipUnless(sys.platform.startswith("linux"), "Linux SO_PEERCRED and /proc contract")
class PeerProofTests(unittest.TestCase):
    def exercise_peer(self, *, stale=False, claimed_other_pid=False, previous=False):
        with tempfile.TemporaryDirectory() as temporary:
            address = "\0hermes-deployment-test-" + uuid.uuid4().hex
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            listener.bind(address)
            listener.listen(1)
            listener.settimeout(1)
            proof = update.MountProof.__new__(update.MountProof)
            proof.listener, proof.receipt, proof.uid = listener, None, os.getuid()
            proof.nonce, proof.release = "a" * 64, "candidate"
            proof.data_directory = "/synthetic"
            script = "import json,os,pathlib,socket,sys; pid=os.getpid(); start=pathlib.Path('/proc/self/stat').read_text().rsplit(')',1)[1].split()[19]; client=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); client.connect(chr(0)+sys.argv[1]); client.sendall(json.dumps(dict(protocol=1,nonce=sys.argv[2],release='candidate',pid=int(sys.argv[3]) or pid,starttime=start,data_directory='/synthetic')).encode()); client.close(); sys.stdin.read(1)"
            child = subprocess.Popen([sys.executable, "-I", "-B", "-c", script, address[1:], "b" * 64 if stale else proof.nonce, str(os.getpid()) if claimed_other_pid else "0"], stdin=subprocess.PIPE)
            try:
                peer_pid = os.getpid() if claimed_other_pid else child.pid
                proof.previous = (peer_pid, update.process_start(peer_pid)) if previous else (0, "old")
                with patch.object(update, "call", return_value=types.SimpleNamespace(stdout=str(peer_pid))):
                    self.assertEqual(proof.confirmed(), not (stale or claimed_other_pid or previous))
            finally:
                child.communicate(b"x", timeout=5)
                listener.close()

    def test_real_sender_process_and_start_identity_are_accepted(self):
        self.exercise_peer()

    def test_old_challenge_is_rejected(self):
        self.exercise_peer(stale=True)

    def test_child_cannot_claim_main_process_identity(self):
        self.exercise_peer(claimed_other_pid=True)

    def test_previous_process_cannot_supply_new_proof(self):
        self.exercise_peer(previous=True)


class PrivateStateTests(unittest.TestCase):
    def setUp(self):
        if not PRIVATE_TEST_ROOT:
            self.skipTest("Requires the managed private user/mount namespace chroot")
        self.assertEqual(os.geteuid(), 0)
        self.temporary = tempfile.TemporaryDirectory(dir="/")
        self.addCleanup(self.temporary.cleanup)
        self.state = Path(self.temporary.name) / "state"
        self.user = types.SimpleNamespace(pw_uid=0, pw_gid=0)

    def test_private_root_challenge_and_real_socket_receipt(self):
        with patch.object(update, "VERIFICATION", self.state), patch.object(deployment, "STATE", self.state), \
                patch.object(update, "call", return_value=types.SimpleNamespace(stdout=str(os.getpid()))), \
                patch.object(update, "process_start", side_effect=["new", "new"]):
            proof = update.MountProof({"source.py": b"fixture"}, self.user, (os.getpid(), "old", self.state.parent))
            try:
                request = deployment.read_request()
                self.assertEqual(request["nonce"], proof.nonce)
                self.assertEqual((self.state / "request.json").stat().st_mode & 0o777, 0o640)
                deployment.send_receipt(dict(protocol=1, nonce=request["nonce"], release=request["release"], pid=os.getpid(), starttime="new", data_directory=str(self.state.parent)))
                self.assertTrue(proof.confirmed())
            finally:
                proof.close()
            self.assertFalse(self.state.exists())

    def test_world_writable_parent_is_refused(self):
        self.state.parent.chmod(0o777)
        with patch.object(update, "VERIFICATION", self.state), \
                patch.object(update, "call", return_value=types.SimpleNamespace(stdout="1")), \
                patch.object(update, "process_start", return_value="old"):
            with self.assertRaisesRegex(RuntimeError, "parent"):
                update.MountProof({"source.py": b"fixture"}, self.user, (1, "old", self.state.parent))
        self.assertFalse(self.state.exists())
        self.state.parent.chmod(0o700)

    def test_existing_symlink_state_is_not_followed_or_removed(self):
        target = self.state.parent / "target"
        target.mkdir()
        self.state.symlink_to(target, target_is_directory=True)
        with patch.object(update, "VERIFICATION", self.state), \
                patch.object(update, "call", return_value=types.SimpleNamespace(stdout="1")), \
                patch.object(update, "process_start", return_value="old"):
            with self.assertRaises(FileExistsError):
                update.MountProof({"source.py": b"fixture"}, self.user, (1, "old", self.state.parent))
        self.assertTrue(self.state.is_symlink())
        self.assertEqual(list(target.iterdir()), [])

    def test_symlink_request_is_refused(self):
        self.state.mkdir(mode=0o710)
        target = self.state.parent / "target.json"
        target.write_text("{}", encoding="utf-8")
        (self.state / "request.json").symlink_to(target)
        with patch.object(deployment, "STATE", self.state):
            with self.assertRaises(OSError):
                deployment.read_request()
