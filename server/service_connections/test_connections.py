import asyncio
from contextlib import nullcontext
import importlib.util
from pathlib import Path
import sys
import os
import time
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch as mock_patch

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

import mobile_service_connections as subject
from prepare_install import patch


class FakeBackend:
    def __init__(self):
        self.state = "ready"
        self.starts = 0
        self.cancelled = []
        self.snapshots = {}
        self.url = "https://slack.com/oauth/v2_user/authorize?state=synthetic&client_id=1.2"

    def owner(self, request: Request):
        owner = request.headers.get("x-test-owner")
        if not owner:
            raise HTTPException(401)
        return owner

    def status(self, profile):
        return {"state": self.state, "server_name": "slack", "enabled": True}

    async def start(self, request, profile):
        self.starts += 1
        flow = {"flow_id": "synthetic-flow", "server_name": "slack", "status": "authorization_required",
                "authorization_url": self.url, "tokens": "never-return-this"}
        self.snapshots["synthetic-flow"] = flow
        return flow

    def snapshot(self, flow_id):
        return self.snapshots[flow_id]

    def cancel(self, flow_id):
        self.cancelled.append(flow_id)
        self.snapshots[flow_id].update(status="error", error="Cancelled by user")


class RouteTests(unittest.TestCase):
    def setUp(self):
        self.backend = FakeBackend()
        app = FastAPI()
        app.include_router(subject.create_router(self.backend))
        self.client = TestClient(app).__enter__()
        self.client.headers["x-test-owner"] = "owner-a"
        self.body = {"request_id": "11111111-1111-1111-1111-111111111111"}
        self.base = "/api/connections/slack"

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def begin(self):
        return self.client.post(self.base + "/connect?profile=work", json=self.body)

    def test_status_does_not_start_login_or_expose_credentials(self):
        response = self.client.get(self.base + "/status")
        self.assertEqual(200, response.status_code)
        self.assertEqual(0, self.backend.starts)
        self.assertNotIn("secret", response.text)

    def test_same_request_never_starts_two_authorizations(self):
        first, second = self.begin(), self.begin()
        self.assertEqual(200, first.status_code)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(1, self.backend.starts)
        self.assertNotIn("never-return-this", first.text)

    def test_retried_begin_returns_current_completion_without_restarting(self):
        self.assertEqual(200, self.begin().status_code)
        self.backend.snapshots["synthetic-flow"].update(status="approved")
        response = self.begin()
        self.assertEqual("approved", response.json()["status"])
        self.assertNotIn("authorization_url", response.json())
        self.assertEqual(1, self.backend.starts)

    def test_retried_begin_returns_current_failure_without_stale_login_url(self):
        self.begin()
        self.backend.snapshots["synthetic-flow"].update(status="error", error="token=private-secret")
        response = self.begin()
        self.assertEqual("error", response.json()["status"])
        self.assertNotIn("authorization_url", response.json())
        self.assertNotIn("private-secret", response.text)
        self.assertEqual(1, self.backend.starts)

    def test_retried_begin_reports_expiry_without_restarting_authorization(self):
        self.begin()
        with mock_patch.object(self.backend, "snapshot", side_effect=HTTPException(404, detail={"code": "flow_expired"})):
            response = self.begin()
        self.assertEqual(404, response.status_code)
        self.assertEqual("flow_expired", response.json()["detail"]["code"])
        self.assertNotIn("authorization_url", response.text)
        self.assertEqual(1, self.backend.starts)

    def test_setup_missing_does_not_fall_back_to_other_auth_routes(self):
        self.backend.state = "setup_required"
        self.assertEqual(409, self.begin().status_code)
        self.assertEqual(0, self.backend.starts)

    def test_requests_cannot_supply_secrets_redirects_or_server_urls(self):
        for field in ("client_secret", "client_id", "url", "redirect_uri", "server_name"):
            response = self.client.post(self.base + "/connect", json={**self.body, field: "untrusted"})
            self.assertEqual(422, response.status_code)
        self.assertEqual(0, self.backend.starts)

    def test_another_session_or_profile_cannot_read_or_cancel_flow(self):
        self.assertEqual(200, self.begin().status_code)
        path = self.base + "/flows/synthetic-flow"
        self.assertEqual(403, self.client.get(path + "?profile=other").status_code)
        self.client.headers["x-test-owner"] = "owner-b"
        self.assertEqual(403, self.client.get(path + "?profile=work").status_code)
        self.assertEqual(403, self.client.delete(path + "?profile=work").status_code)
        self.assertFalse(self.backend.cancelled)

    def test_unauthenticated_requests_do_not_reach_oauth(self):
        del self.client.headers["x-test-owner"]
        self.assertEqual(401, self.begin().status_code)
        self.assertEqual(0, self.backend.starts)

    def test_provider_error_is_not_returned_to_mobile(self):
        self.begin()
        self.backend.snapshots["synthetic-flow"].update(status="error", error="token=private-secret")
        response = self.client.get(self.base + "/flows/synthetic-flow?profile=work")
        self.assertEqual("slack_authorization_failed", response.json()["error"])
        self.assertNotIn("private-secret", response.text)

    def test_only_official_slack_authorization_pages_can_be_opened(self):
        self.backend.url = "https://slack.com.attacker.test/oauth/v2_user/authorize"
        self.assertEqual(503, self.begin().status_code)
        self.assertEqual(["synthetic-flow"], self.backend.cancelled)

    def test_cancel_is_owned_and_does_not_return_tokens(self):
        self.begin()
        response = self.client.delete(self.base + "/flows/synthetic-flow?profile=work")
        self.assertEqual({"ok": True}, response.json())
        self.assertEqual(["synthetic-flow"], self.backend.cancelled)

    def test_repeated_cancel_and_begin_retry_never_reopen_the_login(self):
        self.begin()
        path = self.base + "/flows/synthetic-flow?profile=work"
        self.assertEqual(200, self.client.delete(path).status_code)
        self.assertEqual(200, self.client.delete(path).status_code)
        response = self.begin()
        self.assertEqual("error", response.json()["status"])
        self.assertNotIn("authorization_url", response.json())
        self.assertEqual(1, self.backend.starts)


class CoreAdapterTests(unittest.TestCase):
    def cancel(self, flow):
        modules = {name: ModuleType(name) for name in ("hermes_cli", "hermes_cli.web_server_mcp")}
        modules["hermes_cli.web_server_mcp"]._mcp_oauth_flows = {"fixture": flow}
        with mock_patch.dict(sys.modules, modules):
            subject.CoreBackend.cancel("fixture")

    def test_current_core_cancellation_marks_the_flow_terminal(self):
        calls = []

        class Flow:
            def mark_error(self, error, *, cancelled=False):
                calls.append((error, cancelled))

        self.cancel(Flow())
        self.assertEqual([("Cancelled by user", True)], calls)

    def test_legacy_core_cancellation_without_keyword_is_supported(self):
        calls = []

        class Flow:
            def mark_error(self, error):
                calls.append(error)

        self.cancel(Flow())
        self.assertEqual(["Cancelled by user"], calls)

    def status(self, config, tokens=False, env=None):
        modules = {}
        for name in ("hermes_cli", "hermes_cli.mcp_config", "hermes_cli.config", "tools", "tools.mcp_oauth"):
            modules[name] = ModuleType(name)
        modules["hermes_cli.mcp_config"]._get_mcp_servers = lambda: config
        modules["hermes_cli.config"].get_env_value = lambda key: (env or {}).get(key)
        modules["tools.mcp_oauth"].HermesTokenStorage = lambda name: SimpleNamespace(has_cached_tokens=lambda: tokens)
        backend = subject.CoreBackend()
        backend._scope = lambda profile: nullcontext()
        with mock_patch.dict(sys.modules, modules):
            return backend.status("work")

    def configured(self):
        return {"slack": {"url": subject.SLACK_URL, "auth": "oauth", "enabled": False,
                "oauth": {"client_id": "1.2", "client_secret": "synthetic-secret",
                          "redirect_uri": "https://hermes.example.test/api/mcp/oauth/callback/slack"}}}

    def test_no_app_registration_is_setup_required(self):
        self.assertEqual("setup_required", self.status({})["state"])

    def test_read_only_status_keeps_disabled_state_and_never_returns_registration(self):
        result = self.status(self.configured(), tokens=True)
        self.assertEqual("authorized", result["state"])
        self.assertFalse(result["enabled"])
        self.assertNotIn("synthetic-secret", str(result))
        self.assertNotIn("client_id", result)

    def test_public_pkce_registration_does_not_need_client_secret(self):
        config = self.configured()
        config["slack"]["oauth"].pop("client_secret")
        config["slack"]["oauth"]["token_endpoint_auth_method"] = "none"
        self.assertEqual("ready", self.status(config)["state"])

    def test_existing_bot_or_custom_connection_is_not_reused(self):
        for config in ({"slack": {"command": "other", "auth": "none"}},
                       {"slack": {"url": subject.SLACK_URL, "auth": "header"}}):
            self.assertEqual("manual_connection", self.status(config)["state"])
        config = self.configured()
        config["slack"]["headers"] = {"Authorization": "Bearer private-bot-token"}
        self.assertEqual("manual_connection", self.status(config)["state"])

    def test_callback_is_https_and_has_no_embedded_secrets(self):
        for url in ("http://phone.local/callback", "https://user:password@host/api/mcp/oauth/callback/slack",
                    "https://host/api/mcp/oauth/callback/slack?token=secret"):
            config = self.configured()
            config["slack"]["oauth"]["redirect_uri"] = url
            self.assertEqual("setup_required", self.status(config)["state"])

    def test_server_environment_references_are_resolved_without_returning_values(self):
        config = self.configured()
        config["slack"]["oauth"].update(client_id="${SLACK_MCP_CLIENT_ID}", client_secret="${SLACK_MCP_CLIENT_SECRET}")
        result = self.status(config, env={"SLACK_MCP_CLIENT_ID": "1.2", "SLACK_MCP_CLIENT_SECRET": "private-value"})
        self.assertEqual("ready", result["state"])
        self.assertNotIn("private-value", str(result))


class InstallTests(unittest.TestCase):
    def test_patch_is_idempotent_and_preserves_other_code_and_crlf(self):
        original = "from fastapi import APIRouter\r\nrouter = APIRouter()\r\nUNRELATED = 1\r\n"
        result = patch(original)
        self.assertEqual(result, patch(result))
        self.assertIn("UNRELATED = 1\r\n", result)
        self.assertNotIn("\n", result.replace("\r\n", ""))

    def test_unknown_router_contract_is_rejected(self):
        with self.assertRaises(ValueError):
            patch("router = other_factory()\n")


class AuthGuardTests(unittest.TestCase):
    def setUp(self):
        self.session_type = type("Session", (), {})
        modules = {name: ModuleType(name) for name in (
            "hermes_cli", "hermes_cli.dashboard_auth", "hermes_cli.dashboard_auth.base", "hermes_cli.web_server")}
        modules["hermes_cli.dashboard_auth.base"].Session = self.session_type
        modules["hermes_cli.web_server"]._require_token = lambda request: None
        self.context = mock_patch.dict(sys.modules, modules)
        self.context.__enter__()
        self.backend = subject.CoreBackend()

    def tearDown(self):
        self.context.__exit__(None, None, None)

    def request(self, session, origin=None):
        headers = [(b"host", b"hermes.example.test")]
        if origin:
            headers.append((b"origin", origin.encode()))
        request = Request({"type": "http", "method": "POST", "scheme": "https", "path": "/connect",
                           "server": ("hermes.example.test", 443), "headers": headers})
        request.state.session = session
        return request

    def session(self, token="synthetic-token"):
        value = self.session_type()
        value.provider, value.access_token, value.expires_at = "test", token, time.time() + 60
        return value

    def test_unverified_dictionary_is_not_a_session(self):
        with self.assertRaises(HTTPException) as caught:
            self.backend.owner(self.request({"access_token": "fake", "expires_at": time.time() + 60}))
        self.assertEqual(401, caught.exception.status_code)

    def test_expired_session_is_rejected(self):
        session = self.session()
        session.expires_at = 0
        with self.assertRaises(HTTPException):
            self.backend.owner(self.request(session))

    def test_owner_fingerprint_changes_when_session_token_changes(self):
        first = self.backend.owner(self.request(self.session("first")))
        second = self.backend.owner(self.request(self.session("second")))
        self.assertNotEqual(first, second)
        self.assertNotIn("first", first)

    def test_cross_origin_write_is_rejected(self):
        with self.assertRaises(HTTPException) as caught:
            self.backend.owner(self.request(self.session(), "https://attacker.test"))
        self.assertEqual(403, caught.exception.status_code)
        self.backend.owner(self.request(self.session(), "https://hermes.example.test"))


@unittest.skipUnless(os.environ.get("HERMES_CORE_SOURCE"), "Set HERMES_CORE_SOURCE for the existing core callback contract")
class ExistingCoreCallbackTests(unittest.TestCase):
    def flow(self):
        path = Path(os.environ["HERMES_CORE_SOURCE"]) / "tools/mcp_dashboard_oauth.py"
        spec = importlib.util.spec_from_file_location("mobile_connection_core_fixture", path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        self.addCleanup(sys.modules.pop, spec.name, None)
        flow = module.DashboardOAuthFlow("fixture", "slack", "work", "unused-test-home", "https://hermes.example.test/callback")
        asyncio.run(flow.publish_authorization_url("https://slack.com/oauth/v2_user/authorize?state=expected"))
        return flow

    def test_existing_callback_rejects_wrong_state(self):
        flow = self.flow()
        with self.assertRaises(ValueError):
            flow.deliver_callback(code="synthetic", state="wrong", error=None)

    def test_existing_callback_is_single_use(self):
        flow = self.flow()
        flow.deliver_callback(code="synthetic", state="expected", error=None)
        with self.assertRaises(ValueError):
            flow.deliver_callback(code="synthetic", state="expected", error=None)

    def test_existing_cancellation_unblocks_the_waiter(self):
        flow = self.flow()
        CoreAdapterTests().cancel(flow)
        if hasattr(flow, "cancelled"):
            self.assertTrue(flow.cancelled)
        with self.assertRaises(RuntimeError):
            asyncio.run(flow.wait_for_callback(timeout=0.1))

    def test_cancelled_flow_cannot_publish_a_new_authorization_url(self):
        flow = self.flow()
        CoreAdapterTests().cancel(flow)
        with self.assertRaises(RuntimeError):
            asyncio.run(flow.publish_authorization_url("https://slack.com/oauth/v2_user/authorize?state=new"))

    def test_late_cancellation_keeps_a_failed_flow_terminal(self):
        flow = self.flow()
        flow.mark_error("Synthetic worker failure")
        CoreAdapterTests().cancel(flow)
        with self.assertRaises(RuntimeError):
            asyncio.run(flow.publish_authorization_url("https://slack.com/oauth/v2_user/authorize?state=new"))

    def test_unsafe_authorization_url_cancels_the_actual_core_flow(self):
        flow = self.flow()
        asyncio.run(flow.publish_authorization_url("https://attacker.test/authorize?state=bad"))
        modules = {name: ModuleType(name) for name in ("hermes_cli", "hermes_cli.web_server_mcp")}
        modules["hermes_cli.web_server_mcp"]._mcp_oauth_flows = {"fixture": flow}
        with mock_patch.dict(sys.modules, modules):
            with self.assertRaises(HTTPException):
                subject.Connections(subject.CoreBackend()).safe_snapshot(flow.snapshot())
        if hasattr(flow, "cancelled"):
            self.assertTrue(flow.cancelled)
        with self.assertRaises(RuntimeError):
            asyncio.run(flow.publish_authorization_url("https://slack.com/oauth/v2_user/authorize?state=new"))


if __name__ == "__main__":
    unittest.main()
