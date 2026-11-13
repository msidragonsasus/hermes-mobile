"""Synthetic installed-Skill contracts; never authenticate or use a real mailbox."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.collection_store import CollectionStore, PILOT
from career_core.collector import Collector
from career_core.gmail_reader import PILOT_SENDER, account_reference
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store
from career_core.workspace_reader import READONLY, WorkspaceApi, connected_account, connected_account_details, execute, inspect_module, operation


FAKE_SKILL = '''
import json, os, sys
from pathlib import Path
TOKEN_PATH = Path(os.environ["HERMES_HOME"]) / "google_token.json"
def _gws_binary(): return "synthetic-gws"
def build_service(api, version): raise AssertionError("Do not use Python when gws is present")
def _run_gws(parts, *, params):
    print("synthetic-private-token-do-not-log", file=sys.stderr)
    if parts[-1] == "getProfile":
        return {"emailAddress": TOKEN_PATH.parent.name + "@example.test", "historyId": "50"}
    if "history" in parts:
        print(json.dumps({"error": {"code": 404, "message": "synthetic-private-provider-error"}}), file=sys.stderr)
        sys.exit(1)
    return {"messages": []}
'''


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        # Connection-list code resolves the active home independently of the
        # injected WorkspaceApi; never let this fixture read the host registry.
        active_home = patch.dict('os.environ', {'HERMES_HOME': str(self.root)})
        active_home.start()
        self.addCleanup(active_home.stop)

    def workspace(self, name="first", scopes=None):
        home = self.root / name
        script = home / "skills/productivity/google-workspace/scripts/google_api.py"
        script.parent.mkdir(parents=True)
        script.write_text(FAKE_SKILL, encoding="utf-8")
        (home / "google_token.json").write_text(json.dumps({"refresh_token": "synthetic-original-credential",
            "scopes": [READONLY] if scopes is None else scopes}), encoding="utf-8")
        return WorkspaceApi(home=home)

    def test_existing_skill_and_token_are_reused_without_copy_or_output_leak(self):
        api = self.workspace()
        before = (api.home / "google_token.json").read_bytes()
        self.assertEqual({"ready": True, "provider": "hermes-google-workspace", "readonly": True, "backend": "gws"}, api.status())
        self.assertEqual("first@example.test", api.get("profile")["emailAddress"])
        with patch("career_core.workspace_reader.WorkspaceApi", return_value=api):
            self.assertEqual(account_reference("first@example.test"), connected_account())
        with patch("career_core.workspace_reader.WorkspaceApi", return_value=api):
            self.assertEqual({
                "id": account_reference("first@example.test"),
                "email": "first@example.test",
                "provider": "hermes-google-workspace",
                "read_surface": "gmail.readonly",
            }, connected_account_details())
        self.assertEqual(before, (api.home / "google_token.json").read_bytes())
        self.assertEqual(1, len(list(api.home.rglob("*.json"))))

    def test_active_home_is_used_and_other_home_is_not_a_fallback(self):
        first, second = self.workspace(), self.workspace("second")
        with patch.dict("sys.modules", {"hermes_constants": types.SimpleNamespace(get_hermes_home=lambda: second.home)}):
            self.assertEqual(second.home, WorkspaceApi().home)
            self.assertEqual("second@example.test", WorkspaceApi().get("profile")["emailAddress"])
        (second.home / "google_token.json").unlink()
        self.assertFalse(second.status()["ready"])
        self.assertTrue(first.status()["ready"])

    def test_missing_read_scope_stops_before_any_api_call(self):
        for index, scopes in enumerate(([], ["https://www.googleapis.com/auth/gmail.modify"], "")):
            api = self.workspace(str(index), scopes)
            self.assertEqual("mail_scope_unsupported", api.status()["reason"])
            with self.assertRaises(CareerError) as raised:
                api.get("profile")
            self.assertEqual("mail_scope_unsupported", raised.exception.code)

    def test_official_workspace_scope_superset_is_accepted_but_operations_remain_read_only(self):
        api = self.workspace("official", [
            READONLY,
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/drive",
        ])
        self.assertTrue(api.status()["ready"])
        with self.assertRaises(CareerError) as raised:
            api.get("messages/send")
        self.assertEqual("mail_operation_denied", raised.exception.code)

    def test_gws_error_does_not_disclose_raw_provider_output(self):
        api = self.workspace()
        with self.assertRaises(CareerError) as raised:
            api.get("history", startHistoryId="1", historyTypes="messageAdded", maxResults=100)
        self.assertEqual("mail_cursor_expired", raised.exception.code)
        self.assertNotIn("synthetic-private", str(raised.exception) + raised.exception.detail)

    def test_fixed_allowlist_rejects_other_apis_writes_and_arbitrary_queries(self):
        denied = [("messages/send", {}), ("messages/abc/modify", {}), ("calendar", {}),
                  ("https://external.example/collect", {}), ("profile", {"userId": "another"}),
                  ("messages", {"q": "in:inbox", "maxResults": 100}),
                  ("messages/abc", {"format": "raw"}), ("history", {"historyTypes": "labelAdded", "startHistoryId": "1", "maxResults": 100})]
        module = types.SimpleNamespace(_gws_binary=Mock(return_value="gws"), _run_gws=Mock())
        for resource, params in denied:
            with self.assertRaises(ValueError):
                execute(module, resource, params)
        module._gws_binary.assert_not_called()
        module._run_gws.assert_not_called()

    def test_both_backends_get_identical_read_request_and_no_body(self):
        params = {"q": f"from:{PILOT_SENDER} after:2026/06/01 -in:spam -in:trash", "maxResults": 100}
        gws = types.SimpleNamespace(_gws_binary=lambda: "gws", _run_gws=Mock(return_value={"messages": []}))
        self.assertEqual({"messages": []}, execute(gws, "messages", params))
        gws._run_gws.assert_called_once_with(["gmail", "users", "messages", "list"], params={"userId": "me", **params})
        service = Mock()
        service.users.return_value.messages.return_value.list.return_value.execute.return_value = {"messages": []}
        python = types.SimpleNamespace(_gws_binary=lambda: None, build_service=Mock(return_value=service))
        self.assertEqual({"messages": []}, execute(python, "messages", params))
        python.build_service.assert_called_once_with("gmail", "v1")
        service.users.return_value.messages.return_value.list.assert_called_once_with(userId="me", **params)
        service.users.return_value.messages.return_value.list.return_value.execute.assert_called_once_with(num_retries=0)
        service.close.assert_called_once()

    def test_mail_collection_does_not_require_new_portal_encryption_storage(self):
        store = Store(self.root / "data")
        grant = CollectionStore(store).configure_pilot(account_reference("first@example.test"), enabled=True)
        reader = Mock()
        reader.changes.return_value = ([], {"history_id": "10"})
        factory = Mock(return_value=reader)
        with patch("career_core.collector.SecretStore", side_effect=AssertionError("Do not create Gmail credential storage")):
            result = Collector(store, mail_factory=factory).collect(PILOT, "mail")
        self.assertEqual("unchanged", result["state"])
        self.assertEqual((grant["account_id"],), factory.call_args.args)
        self.assertFalse(factory.call_args.kwargs["should_cancel"]())
        reader.close.assert_called_once()

    def test_timeout_terminates_the_reader_instead_of_retrying_forever(self):
        api = self.workspace()
        with patch("career_core.workspace_reader.run_private", side_effect=TimeoutError()):
            with self.assertRaises(CareerError) as raised:
                api.get("profile")
        self.assertEqual("workspace_timeout", raised.exception.code)

    def test_mail_collection_can_be_stopped_while_google_connection_is_unavailable(self):
        store = Store(self.root / "data")
        collection = CollectionStore(store)
        account = account_reference("first@example.test")
        collection.configure_pilot(account, enabled=True)
        MailStore(store).configure(account, enabled=True)
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request: Request, call_next):
            request.state.session = {"user": "synthetic"}
            return await call_next(request)

        app.include_router(create_router(lambda: store))
        with patch("career_core.workspace_reader.connected_account", side_effect=AssertionError("Stopping must not require Google")):
            response = TestClient(app).post("/collection/settings", json={"channel": "mail", "enabled": False, "account_id": account})
        self.assertEqual(200, response.status_code)
        self.assertFalse(MailStore(store).settings()["enabled"])
        self.assertTrue(collection.grant(PILOT, require_enabled=False)["enabled"])

    def test_connection_surface_returns_human_label_without_credentials(self):
        store = Store(self.root / "data")
        account = {
            "id": account_reference("first@example.test"),
            "email": "first@example.test",
            "provider": "hermes-google-workspace",
            "read_surface": "gmail.readonly",
        }
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request: Request, call_next):
            request.state.session = {"user": "synthetic"}
            return await call_next(request)

        app.include_router(create_router(lambda: store))
        with patch("career_core.workspace_reader.connected_account_details", return_value=account):
            response = TestClient(app).get("/collection/connections")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertEqual([account["id"]], payload["accounts"])
        self.assertEqual([account], payload["account_details"])
        self.assertNotIn("token", json.dumps(payload).lower())

    def test_workspace_subprocess_does_not_inherit_other_credentials_or_token_override(self):
        api = self.workspace()
        with patch.dict(os.environ, {"CODEX_API_KEY": "synthetic-key", "GITHUB_TOKEN": "synthetic-github",
                                    "GOOGLE_WORKSPACE_CLI_TOKEN": "synthetic-override", "CREDENTIALS_DIRECTORY": "/synthetic-key"}), \
             patch("career_core.workspace_reader.run_private", return_value=b'{"data":{"ready":true}}') as runner:
            self.assertTrue(api.status()["ready"])
        env = runner.call_args.kwargs["env"]
        self.assertFalse({"CODEX_API_KEY", "GITHUB_TOKEN", "GOOGLE_WORKSPACE_CLI_TOKEN", "CREDENTIALS_DIRECTORY"} & set(env))
        self.assertEqual(str(api.home), env["HERMES_HOME"])

    def test_configured_runtime_is_used_without_copying_credentials(self):
        api = self.workspace()
        executable = str(self.root / 'separate-runtime' / 'python')
        with patch.dict(os.environ, {'CAREER_WORKSPACE_PYTHON': executable}), \
             patch('career_core.workspace_reader.run_private', return_value=b'{"data":{"ready":true}}') as runner:
            self.assertTrue(api.status()['ready'])
        self.assertEqual(executable, runner.call_args.args[0][0])
        self.assertEqual(str(api.home), runner.call_args.kwargs['env']['HERMES_HOME'])

    def test_missing_google_dependency_is_classified_without_exposing_raw_errors(self):
        api = self.workspace()
        source = api.script.read_text(encoding='utf-8')
        source = source.replace('return "synthetic-gws"', 'return None').replace(
            'raise AssertionError("Do not use Python when gws is present")',
            'raise ModuleNotFoundError("synthetic-sensitive-module-details")')
        api.script.write_text(source, encoding='utf-8')
        with self.assertRaises(CareerError) as raised:
            api.get('profile')
        self.assertEqual('workspace_dependency_unavailable', raised.exception.code)
        self.assertNotIn('synthetic-sensitive', str(raised.exception))


if __name__ == "__main__":
    unittest.main()
