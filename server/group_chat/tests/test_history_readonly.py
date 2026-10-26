import hashlib
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_api import create_router, read_history
from group_service import GroupError, GroupService
from test_group_service import Runtime


class ReadOnlyHistoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.path = self.root / "owner/group_chat/history.db"
        self.owner = GroupService(self.path, Runtime(), start=False)
        with self.owner.db() as db:
            db.execute("INSERT INTO conversations VALUES(?,?,?,?,?)", ("old", "team", "Saved", 1, 2))
            db.execute("INSERT INTO conversations VALUES(?,?,?,?,?)", ("new", "team", "Newest", 3, 4))
        self.authenticated = True

    def tearDown(self):
        self.owner.close()
        self.temp.cleanup()

    def client(self):
        profiles = types.ModuleType("hermes_cli.profiles")
        profiles.get_profile_dir = lambda profile: self.root / profile
        profiles.profile_exists = lambda profile: profile in {"owner", "empty"}
        profiles.list_profile_names = lambda: []
        modules = {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.profiles": profiles}
        self.enterContext(patch.dict(sys.modules, modules))
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            if self.authenticated:
                request.state.session = {"id": "synthetic"}
            return await call_next(request)

        app.include_router(create_router(), prefix="/api/plugins/group_chat")
        return self.enterContext(TestClient(app))

    def test_production_history_route_reads_while_another_worker_owns_database(self):
        with self.assertRaises(GroupError) as failure:
            GroupService(self.path, Runtime(), start=False)
        self.assertEqual("worker_already_running", failure.exception.code)
        client = self.client()
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with patch("group_api.GroupService", side_effect=AssertionError("reader must not create worker")):
            response = client.get("/api/plugins/group_chat/conversations?profile=owner")
        self.assertEqual(200, response.status_code)
        self.assertEqual(["new", "old"], [entry["id"] for entry in response.json()["conversations"]])
        self.assertEqual({"id", "groupName", "title", "createdAt", "updatedAt"},
                         set(response.json()["conversations"][0]))
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).hexdigest())
        self.assertEqual(2, len(self.owner.history()))
        with self.assertRaises(GroupError) as still_owned:
            GroupService(self.path, Runtime(), start=False)
        self.assertEqual("worker_already_running", still_owned.exception.code)

    def test_reads_new_committed_wal_rows_without_a_worker_or_database_reset(self):
        client = self.client()
        url = "/api/plugins/group_chat/conversations?profile=owner"
        self.assertEqual(2, len(client.get(url).json()["conversations"]))
        with self.owner.db() as db:
            db.execute("INSERT INTO conversations VALUES(?,?,?,?,?)", ("latest", "team", "Live", 5, 6))
        self.assertEqual("latest", client.get(url).json()["conversations"][0]["id"])

    def test_missing_history_does_not_create_profile_directories_or_database(self):
        client = self.client()
        response = client.get("/api/plugins/group_chat/conversations?profile=empty")
        self.assertEqual({"conversations": []}, response.json())
        self.assertFalse((self.root / "empty").exists())

    def test_authentication_and_profile_boundaries_are_preserved(self):
        client = self.client()
        url = "/api/plugins/group_chat/conversations"
        self.assertEqual(422, client.get(url, params={"profile": "../owner"}).status_code)
        self.assertEqual(404, client.get(url, params={"profile": "unknown"}).status_code)
        self.authenticated = False
        with patch("group_api.read_history", side_effect=AssertionError("must authenticate before read")):
            self.assertEqual(401, client.get(url, params={"profile": "owner"}).status_code)

    def test_corrupt_or_incompatible_storage_is_an_error_and_is_never_recreated(self):
        for payload in (b"invalid database", b""):
            with self.subTest(payload=payload):
                target = self.root / "broken.db"
                target.write_bytes(payload)
                with self.assertRaises(GroupError) as failure:
                    read_history(target)
                self.assertEqual("history_unavailable", failure.exception.code)
                self.assertEqual(payload, target.read_bytes())

    def test_reader_never_opens_a_write_connection(self):
        connect = sqlite3.connect
        uris = []

        def readonly(database, **kwargs):
            uris.append(database)
            connection = connect(database, **kwargs)
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("DELETE FROM conversations")
            return connection

        with patch("group_api.sqlite3.connect", side_effect=readonly):
            self.assertEqual(2, len(read_history(self.path)))
        self.assertTrue(uris[0].endswith("?mode=ro"))
        self.assertEqual(2, len(self.owner.history()))


if __name__ == "__main__":
    unittest.main()
