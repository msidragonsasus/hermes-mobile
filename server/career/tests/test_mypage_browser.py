"""Headless fixture pages; no accounts or real recruiting sites are contacted."""
import os
import io
import json
import time
import threading
import contextvars
import sys
from types import SimpleNamespace
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch, Mock

from career_core.mypage_browser import GuardedPortalTab, HermesPortalBrowser, BrowserSessionLease, child_main, public_host, open_native_browser, touch_native_browser_session, BROWSER_IDLE_MS
from career_core.store import CareerError


class BrowserBoundaryTests(unittest.TestCase):
    def test_parent_starts_lease_and_stops_it_before_native_cleanup(self):
        parent = HermesPortalBrowser("fixture", "unused", {})
        def cleanup(task):
            self.assertEqual("career-mypage-fixture::local", task)
            self.assertTrue(parent.lease.stopped.is_set())
            self.assertFalse(parent.lease.thread.is_alive())
        lifecycle = SimpleNamespace(cleanup_browser=Mock(side_effect=cleanup))
        def command(task, action, args, timeout):
            return {"success": True} if action == "open" else {"success": True, "value": "ws://127.0.0.1:9222/devtools/browser/fixture"}
        session = SimpleNamespace(_run_browser_command=Mock(side_effect=command), _get_session_info=Mock(return_value={"features": {"local": True}}))
        value = {"page_id": "1", "text": "fixture", "url": "https://portal.example.test/info", "authenticated": True, "blocked_requests": False}
        process = Mock(stdin=io.StringIO(), stdout=io.StringIO(json.dumps(value)), poll=Mock(return_value=None))
        with patch.dict(sys.modules, {"tools.browser_tool_session": session, "tools.browser_tool_lifecycle": lifecycle}), patch.dict(os.environ, {"CAREER_BROWSER_PYTHON": sys.executable}), patch("subprocess.Popen", return_value=process), patch("career_core.mypage_browser.open_native_browser", return_value=({}, "ws://127.0.0.1:9222/devtools/browser/fixture")), patch("career_core.mypage_browser.touch_native_browser_session") as touch:
            self.assertEqual(value, parent.start())
            touch.assert_called_once_with(parent.task, {})
            session._run_browser_command.assert_not_called()
            parent.close()
            lifecycle.cleanup_browser.assert_called_once()
        parent.abort()
        self.assertTrue(parent.lease.stopped.is_set())

    def test_native_start_scopes_daemon_idle_and_restores_environment_on_all_paths(self):
        key = "AGENT_BROWSER_IDLE_TIMEOUT_MS"
        for previous in (None, "120000"):
            for fails in (False, True):
                with patch.dict(os.environ):
                    os.environ.pop(key, None)
                    if previous is not None:
                        os.environ[key] = previous
                    def command(task, action, args, timeout):
                        self.assertEqual(str(BROWSER_IDLE_MS), os.environ[key])
                        if fails:
                            raise RuntimeError("fixture")
                        return {"success": True, "value": "ws://127.0.0.1:9222/devtools/browser/fixture"}
                    info = {"features": {"local": True}}
                    session = SimpleNamespace(_run_browser_command=Mock(side_effect=command),
                        _get_session_info=Mock(return_value=info),
                        _agent_browser_command_env=lambda _: {key: os.environ[key]})
                    with patch.dict(sys.modules, {"tools.browser_tool_session": session}):
                        if fails:
                            with self.assertRaises(RuntimeError):
                                open_native_browser("fixture::local")
                        else:
                            actual, cdp = open_native_browser("fixture::local")
                            self.assertIs(info, actual)
                            self.assertTrue(cdp.endswith("/fixture"))
                    self.assertEqual(previous, os.environ.get(key))

    def test_activity_rejects_replacement_without_spawning_or_touching_it(self):
        original = {"session_name": "fixture"}
        touch = Mock()
        for current in (original, dict(original), None):
            touch.reset_mock()
            native = SimpleNamespace(_cleanup_lock=threading.Lock(), _active_sessions={"fixture::local": current})
            with patch.dict(sys.modules, {"tools": SimpleNamespace(browser_tool=native),
                    "tools.browser_tool_lifecycle": SimpleNamespace(_update_session_activity=touch)}):
                if current is original:
                    touch_native_browser_session("fixture::local", original)
                    touch.assert_called_once_with("fixture::local")
                else:
                    with self.assertRaisesRegex(CareerError, "browser_session_ended"):
                        touch_native_browser_session("fixture::local", original)
                    touch.assert_not_called()

    def test_activity_lease_covers_model_wait_preserves_scope_and_stops_at_deadline(self):
        profile = contextvars.ContextVar("fixture_profile", default="wrong-profile")
        profile.set("career-mypage")
        seen, pulsed = [], threading.Event()
        def touch(task):
            seen.append((task, profile.get()))
            if len(seen) >= 2:
                pulsed.set()
        failed = Mock()
        lease = BrowserSessionLease("run::local", time.monotonic() + 10, touch, failed, interval=0.01)
        try:
            lease.start()
            self.assertTrue(pulsed.wait(2), "Must update while no browser command is running")
        finally:
            lease.stop()
        count = len(seen)
        self.assertFalse(lease.pulse())
        self.assertEqual(count, len(seen))
        self.assertFalse(lease.thread.is_alive())
        self.assertTrue(all(value == ("run::local", "career-mypage") for value in seen))
        failed.assert_not_called()
        expired = BrowserSessionLease("expired", 0, touch, failed)
        self.assertFalse(expired.pulse())
        self.assertEqual(count, len(seen))

    def test_activity_failure_ends_lease_instead_of_silently_losing_ownership(self):
        failed = threading.Event()
        touch = Mock(side_effect=[None, RuntimeError("private-fixture")])
        lease = BrowserSessionLease("run::local", time.monotonic() + 10, touch, lambda error: failed.set(), interval=0.01)
        try:
            lease.start()
            self.assertTrue(failed.wait(2))
        finally:
            lease.stop()
        self.assertEqual(2, touch.call_count)

    def test_worker_exception_reaches_parent_diagnostics_without_secret_or_tool_leak(self):
        tab = Mock()
        tab.start.return_value = {"page_id": "1", "text": "fixture", "url": "https://portal.example.test/company/info", "authenticated": True, "blocked_requests": False}
        tab.open.side_effect = TimeoutError("password=private-fixture cookie=private-cookie")
        incoming = io.StringIO(json.dumps({"action": "start", "run_id": "fixture", "url": "unused", "credentials": {}, "endpoint": "unused"}) + '\n' + json.dumps({"action": "open", "link_id": "1"}) + '\n')
        output = io.StringIO()
        with patch("sys.stdin", incoming), redirect_stdout(output), patch("career_core.mypage_browser.GuardedPortalTab", return_value=tab):
            child_main()
        response = json.loads(output.getvalue().splitlines()[1])
        self.assertEqual("browser_operation_failed", response["error"])
        self.assertEqual("TimeoutError", response["diagnostic"]["exception_type"])
        self.assertEqual("browser_open", response["diagnostic"]["phase"])
        self.assertTrue(response["diagnostic"]["frames"])
        parent = HermesPortalBrowser("fixture", "unused", {})
        parent.process = Mock(stdin=io.StringIO(), stdout=io.StringIO(json.dumps(response)))
        diagnostic = io.StringIO()
        with redirect_stderr(diagnostic), self.assertRaises(CareerError) as error:
            parent.open("1")
        self.assertEqual("browser_operation_failed", error.exception.code)
        self.assertNotIn("TimeoutError", error.exception.detail)
        event = json.loads(diagnostic.getvalue())
        self.assertEqual("fixture", event["run_id"])
        self.assertEqual("browser_open", event["phase"])
        for secret in ("private-fixture", "private-cookie", "password=", "cookie="):
            self.assertNotIn(secret, output.getvalue() + diagnostic.getvalue())
        tab.close.assert_called_once()

    def test_pipe_eof_and_time_limit_are_distinct_from_startup_failure(self):
        parent = HermesPortalBrowser("fixture", "unused", {})
        parent.process = Mock(stdin=io.StringIO(), stdout=io.StringIO(""))
        with redirect_stderr(io.StringIO()), self.assertRaises(CareerError) as error:
            parent.read()
        self.assertEqual("browser_session_ended", error.exception.code)
        parent.deadline = 0
        with self.assertRaises(CareerError) as error:
            parent.read()
        self.assertEqual("collection_limit", error.exception.code)

    def test_same_host_tenants_ports_and_encoded_paths_are_not_login_authority(self):
        with patch("career_core.mypage_browser.public_host", return_value="portal.example.test"):
            tab = GuardedPortalTab("fixture", "https://portal.example.test/company/login", {}, "unused")
        for url in ("https://portal.example.test/other/login", "https://portal.example.test:444/company/login",
                    "https://portal.example.test/company/%2e%2e/other/login", "https://portal.example.test/company/entry"):
            self.assertFalse(tab.allowed_page(url))
        self.assertTrue(tab.allowed_page("https://portal.example.test/company/information"))

    def test_public_destination_rejects_private_ipv4_ipv6_and_mixed_dns(self):
        for ips in (("127.0.0.1",), ("::1",), ("169.254.169.254",), ("1.1.1.1", "10.0.0.1")):
            entries = [(0, 0, 0, "", (ip, 443)) for ip in ips]
            with patch("socket.getaddrinfo", return_value=entries), self.assertRaises(CareerError):
                public_host("https://portal.example.test/login")


@unittest.skipUnless(os.environ.get("CAREER_BROWSER_FIXTURE_TESTS") == "1", "Run with the separate Playwright runtime")
class RealBrowserFixtureTests(unittest.TestCase):
    def setUp(self):
        from playwright.sync_api import sync_playwright
        self.runtime = sync_playwright().start()
        self.addCleanup(self.runtime.stop)
        self.browser = self.runtime.chromium.launch(headless=True)
        self.addCleanup(self.browser.close)
        self.context = self.browser.new_context(service_workers="block")
        self.addCleanup(self.context.close)
        self.patch = patch("career_core.mypage_browser.public_host", return_value="portal.example.test")
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.tab = GuardedPortalTab("fixture", "https://portal.example.test/company/login", {"login_id": "fixture-id", "password": "fixture-private-password"}, "unused")
        self.tab.context = self.context
        self.tab.page = self.context.new_page()
        self.requests = []
        self.challenge = False
        self.context.route("**/*", self.route)

    def route(self, route):
        test = self
        class FixtureRoute:
            request = route.request
            def abort(self):
                route.abort()
            def fulfill(self, **kwargs):
                route.fulfill(**kwargs)
            def fetch(self, **kwargs):
                class Response:
                    status = 200
                    headers = {"content-type": "text/html; charset=utf-8"}
                    def body(self):
                        return self.text.encode("utf-8")
                response = Response()
                req = self.request
                test.requests.append((req.method, req.url))
                if req.url.endswith("/login") and req.method == "GET":
                    body = '<form method="post" action="/company/login"><input name="id"><input type="password" name="password"><button type="submit">ログイン</button></form>'
                elif req.url.endswith("/login") and req.method == "POST":
                    test.assertIn("fixture-private-password", req.post_data)
                    response.status = 302
                    response.headers = {"location": "/company/home"}
                    response.text = ""
                    return response
                elif test.challenge:
                    body = "本人確認の認証コードを入力してください"
                else:
                    body = '<a href="/company/logout">ログアウト</a><h1>夏期プログラム</h1><p>募集締切 2026年10月1日 18:00 JST</p><a href="/company/info">募集の詳細</a><a href="/company/submit">応募する</a><input value="fixture-private-password">'
                response.text = body
                return response
        self.tab.route(FixtureRoute())

    def test_login_read_and_detail_do_not_expose_secret_or_application_controls(self):
        self.tab.page.goto(self.tab.url)
        self.assertTrue(self.tab.read()["login_required"])
        result = self.tab.login()
        self.assertTrue(result["authenticated"])
        self.assertNotIn("fixture-private-password", str(result))
        self.assertEqual(["募集の詳細"], [x["label"] for x in result["links"]])
        detail = self.tab.open(result["links"][0]["id"])
        self.assertIn("2026年10月1日", detail["text"])
        self.assertEqual(["POST"], [method for method, url in self.requests if method == "POST"])

    def test_post_submission_and_cross_origin_navigation_are_blocked(self):
        self.tab.page.goto(self.tab.url)
        self.tab.login()
        self.tab.page.evaluate("fetch('/company/submit',{method:'POST',body:'not-allowed'}).catch(()=>{})")
        self.tab.page.wait_for_timeout(150)
        self.assertNotIn(("POST", "https://portal.example.test/company/submit"), self.requests)
        self.assertTrue(self.tab.blocked)
        with self.assertRaises(Exception):
            self.tab.page.goto("https://other.example.test/company/home")

    def test_additional_authentication_is_not_successful_empty(self):
        self.tab.page.goto(self.tab.url)
        self.challenge = True
        with self.assertRaisesRegex(CareerError, "authentication_challenge"):
            self.tab.login()


if __name__ == "__main__":
    unittest.main()
