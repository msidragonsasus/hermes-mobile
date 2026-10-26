"""Installed Hermes/agent-browser contract; loopback dummy credentials only."""
import json
import os
from pathlib import Path
import re
import runpy
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from career_core.mypage_native_browser import NativeBrowser, install_environment_adapter
from career_core.store import CareerError


def observed_result(message):
    # Hermes wraps standard browser output as untrusted external data.
    text = message["content"]
    return json.JSONDecoder().raw_decode(text[text.index("{"):])[0]


@unittest.skipUnless(os.environ.get("CAREER_NATIVE_BROWSER_BINARY") and os.environ.get("CAREER_NATIVE_CHROMIUM"), "installed native browser contract opt-in")
class NativeBrowserLiveTests(unittest.TestCase):
    def test_native_auth_read_policy_and_cleanup(self):
        requests = []

        class Fixture(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                requests.append(self.path)
                if self.path == "/login":
                    # i-web style login: explicit input button, not submit type.
                    text = '<form method="POST" action="/login"><label>User<input name="user" autocomplete="username"></label><label>Password<input name="password" type="password"></label><input type="button" value="Login" onclick="this.form.submit()"></form>'
                elif self.path == "/home":
                    text = '''<h1>Announcements</h1><p>synthetic-user</p>
                    <a href="/notice" onclick="fetch('/apply');return false">Program notice</a>
                    <a href="/apply">Apply</a><button>Submit</button>
                    <a href="javascript:void(0);" onclick="javascript:$('#formList').submit();">All messages</a>
                    <form id="formList" action="/message/index/" method="post"><input type="hidden" name="pra" value="must-not-be-submitted"></form>'''
                    text += ''.join(f'<a href="/notice/{n}">Notice {n}</a>' for n in range(1, 23))
                    text += '<a href="https://outside.invalid/notice">Outside</a><a href="/hidden">応募する</a>'
                elif self.path.startswith("/notice/"):
                    number = int(self.path.rsplit("/", 1)[1])
                    text = f'<h1>Notice {number}</h1><p>対象年度：2028</p><p>締切：2026年10月1日 17:00 JST</p>'
                elif self.path == "/message/index/":
                    text = '''<h1>Messages</h1><a href="javascript:void(0);" onclick="javascript:formDetailSubmit(2);">Welcome message</a>
                    <form id="formDetail" method="post"><input type="hidden" name="funtype"></form>
                    <script>function formDetailSubmit(msgcd, funtype) {
                        action = "/message/detail/msgcd/" + msgcd;
                        $('#formDetail').attr('action', action);
                        $('form#formDetail input:hidden[name="funtype"]').val(funtype);
                        $('#formDetail').submit();
                    }</script>'''
                else:
                    text = '<h1>Program notice</h1><p>締切：2026年10月1日 17:00 JST</p>'
                body = text.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                requests.append("POST " + self.path)
                body = self.rfile.read(int(self.headers["Content-Length"]))
                if body != b"user=synthetic-user&password=synthetic-password":
                    self.send_error(403)
                    return
                self.send_response(303)
                self.send_header("Location", "/home")
                self.end_headers()

        server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory:
            import yaml
            config = {"browser": {"cloud_provider": "local", "engine": "chrome"}, "agent": {"max_turns": 32}, "compression": {"enabled": False}}
            Path(directory, "config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")
            from hermes_constants import set_hermes_home_override, reset_hermes_home_override
            token = set_hermes_home_override(directory)
            self.addCleanup(reset_hermes_home_override, token)
            from tools import browser_tool_session as session
            from tools.browser_tool_lifecycle import cleanup_browser
            install_environment_adapter()
            original_env = session._agent_browser_command_env

            def environment(socket_dir):
                value = original_env(socket_dir)
                value["AGENT_BROWSER_EXECUTABLE_PATH"] = os.environ["CAREER_NATIVE_CHROMIUM"]
                return value

            browser = NativeBrowser("native-career-fixture::local", f"http://127.0.0.1:{server.server_port}/login",
                {"login_id": "synthetic-user", "password": "synthetic-password"}, validate_host=lambda url: None)
            with patch("tools.browser_tool_install._find_agent_browser", return_value=os.environ["CAREER_NATIVE_BROWSER_BINARY"]), patch.object(session, "_agent_browser_command_env", side_effect=environment):
                try:
                    result = browser.login()
                    self.assertTrue(browser.authenticated)
                    self.assertNotIn("synthetic-user", json.dumps(result))
                    self.assertNotIn("synthetic-password", json.dumps(result))
                    self.assertEqual(23, len(result["read_links"]))
                    for forbidden in ("/apply", "/hidden", "/unseen", "https://outside.invalid/notice"):
                        url = forbidden if forbidden.startswith("https:") else f"http://127.0.0.1:{server.server_port}" + forbidden
                        with self.assertRaises(CareerError):
                            browser.invoke("browser_navigate", {"url": url})
                    ref = next(ref for ref, label in browser.refs.items() if label == "Program notice")
                    with self.assertRaises(CareerError):
                        browser.invoke("browser_type", {"ref": ref, "text": "forbidden"})
                    with self.assertRaises(CareerError):
                        browser.invoke("browser_navigate", {"url": f"http://127.0.0.1:{server.server_port}/apply"})
                    result = browser.invoke("browser_click", {"ref": ref})
                    self.assertIn("2026年10月1日", result["snapshot"])
                    self.assertNotIn("/apply", requests)
                    self.assertTrue(browser.pages[result["page_id"]]["authenticated"])
                    # Revisit only the already observed home; JavaScript is parsed
                    # for a GET destination, never executed or submitted.
                    browser.invoke("browser_navigate", {"url": f"http://127.0.0.1:{server.server_port}/home"})
                    ref = next(ref for ref, label in browser.refs.items() if label == "All messages")
                    listed = browser.invoke("browser_click", {"ref": ref})
                    self.assertTrue(listed["url"].endswith("/message/index/"))
                    ref = next(ref for ref, label in browser.refs.items() if label == "Welcome message")
                    detail = browser.invoke("browser_click", {"ref": ref})
                    detail = browser.invoke("browser_snapshot", {"full": True})
                    self.assertIn("2026年10月1日", detail["snapshot"])
                    self.assertTrue(detail["url"].endswith("/message/detail/msgcd/2"))
                    self.assertEqual(["POST /login"], [request for request in requests if request.startswith("POST ")])
                    before = len(requests)
                    self.standard_agent_roundtrip(directory, config, server.server_port)
                    self.assertEqual(1, requests[before:].count("/home"))
                    self.assertEqual(22, len([value for value in requests[before:] if value.startswith("/notice/")]))
                    self.assertEqual(["POST /login", "POST /login"], [value for value in requests if value.startswith("POST ")])
                finally:
                    # Test harness acts as the standard Agent teardown, not the plugin.
                    cleanup_browser(browser.task)
                    browser.forget()

    def standard_agent_roundtrip(self, directory, config, port):
        """Real native Agent loop with scripted, in-process model HTTP responses."""
        from cryptography.fernet import Fernet
        import httpx
        from openai import OpenAI
        from career_core.store import Store
        from career_core.secret_store import SecretStore
        from career_core.mypage_bot import MyPageBotStore
        from career_core import mypage_native as plugin
        from run_agent import AIAgent
        store = Store(Path(directory) / "data")
        records = MyPageBotStore(store, SecretStore(Path(directory) / "private", key=Fernet.generate_key()))
        with store.transaction() as db:
            store.put(db, "companies", "fixture", 0, {"name": "fixture"})
        with patch("career_core.mypage_browser.public_host", return_value="127.0.0.1"), patch("career_core.mypage_bot.portal_url", side_effect=lambda value: value):
            row = records.configure("fixture", "default", {"url": f"http://127.0.0.1:{port}/login", "login_id": "synthetic-user", "password": "synthetic-password", "enabled": True, "expected_version": 0})
        records.enqueue(row["id"])
        run = records.claim(row["id"])
        task = run["id"]
        execution = {"provider": "openrouter", "model": "qwen/qwen3-coder:free", "reasoning_effort": "default"}
        requests = []
        socket_paths = []

        def model(request):
            body = json.loads(request.content)
            requests.append(body)
            self.assertNotIn("synthetic-password", request.content.decode())
            self.assertNotIn("synthetic-user", request.content.decode())
            tools = [m for m in body["messages"] if m["role"] == "tool"]
            count = len(tools)
            if count == 0:
                name, args = plugin.AUTH, {}
            elif count == 1:
                from tools import browser_tool_session as native_session
                info = native_session._get_session_info(task)
                socket_paths.append(native_session._prepare_session_socket_dir(info["session_name"]))
                observed = observed_result(tools[-1])
                destinations.extend(link["url"] for link in observed["read_links"] if re.search(r"/notice/\d+$", link["url"]))
                self.assertEqual(22, len(destinations))
                name, args = "browser_navigate", {"url": destinations[0]}
            elif 2 <= count <= 23:
                observed = observed_result(tools[-1])
                observed = observed.get("next_page", observed)
                line = next(int(text.split(":", 1)[0]) for text in observed["snapshot"].splitlines() if "締切：2026年10月1日" in text)
                name, args = plugin.RECORD, {"action": "save", "page_id": observed["page_id"], "title": f"Notice {count - 1}", "line_start": line, "line_end": line}
                if count < 23:
                    args["next_url"] = destinations[count - 1]
            elif count == 24:
                name, args = plugin.RECORD, {"action": "finish", "complete": True}
            else:
                name, args = None, None
            message = {"role": "assistant", "content": None if name else "22件、完了。"}
            if name:
                message["tool_calls"] = [{"id": "call_" + str(count), "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}]
            if body.get("stream"):
                delta = dict(message)
                if name:
                    delta["tool_calls"][0]["index"] = 0
                chunk = {"id": "fixture", "object": "chat.completion.chunk", "created": 1, "model": "gpt-4.1", "choices": [{"index": 0, "delta": delta, "finish_reason": None}]}
                final = dict(chunk, choices=[{"index": 0, "delta": {}, "finish_reason": "tool_calls" if name else "stop"}])
                return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content="data: " + json.dumps(chunk) + "\n\ndata: " + json.dumps(final) + "\n\ndata: [DONE]\n\n")
            return httpx.Response(200, json={"id": "fixture", "object": "chat.completion", "created": 1, "model": "gpt-4.1", "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if name else "stop"}], "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}})

        destinations = []
        from career_core import coach_runtime
        original_factory = coach_runtime.make_agent
        agents = []
        def fixture_client(**kwargs):
            return OpenAI(api_key="fixture-only", base_url="http://127.0.0.1:9/v1", http_client=httpx.Client(transport=httpx.MockTransport(model)))
        def factory(*args, **kwargs):
            timing = []
            kwargs["on_api_timing"] = lambda *value: timing.append(value)
            agent = original_factory(*args, **kwargs)
            self.assertEqual("nested", agent._career_timed_call(lambda: agent._career_timed_call(lambda: "nested")))
            self.assertEqual(1, len(timing))
            self.assertTrue(timing[0][1])
            with self.assertRaises(ValueError):
                agent._career_timed_call(lambda: agent._career_timed_call(lambda: (_ for _ in ()).throw(ValueError("fixture"))))
            self.assertEqual(2, len(timing))
            self.assertFalse(timing[1][1])
            self.assertEqual(0, agent._career_api_timing_depth)
            # Keep timings local in this fixture; production callback still uses
            # the exact same outer-call mechanism tested above.
            self.assertEqual(480, agent.run_budget_seconds)
            self.assertEqual(32, agent.max_iterations)
            agent.client = fixture_client()
            agent._api_max_retries = 1
            agents.append(agent)
            return agent
        credentials = dict(execution, api_key="fixture-only", base_url="https://openrouter.ai/api/v1", api_mode="chat_completions")
        started = time.monotonic()
        verified = False
        try:
            with patch.object(plugin, "MyPageBotStore", return_value=records), patch.object(plugin, "NativeBrowser", side_effect=lambda *args: NativeBrowser(*args, validate_host=lambda url: None)), patch("career_core.mypage_agent.selected_mypage_execution", return_value=execution), patch("career_core.mypage_agent.model_credentials", return_value=credentials), patch.object(coach_runtime, "make_agent", side_effect=factory), patch.object(AIAgent, "_create_request_openai_client", side_effect=fixture_client):
                plugin.execute(store, row, run)
            view = records.view("fixture", "default")
            self.assertTrue(view["verified"])
            self.assertEqual(22, len(view["notices"]))
            self.assertEqual("2026-10-01", view["notices"][0]["deadlines"][0]["date"])
            self.assertEqual(26, len(requests))
            verified = True
        finally:
            turn_seconds = time.monotonic() - started
            cleanup_started = time.monotonic()
            for agent in agents:
                agent.close()
            self.assertTrue(socket_paths)
            self.assertTrue(all(not Path(path).exists() for path in socket_paths))
            print(json.dumps({"event": "native_fixture_result", "model_inference": False,
                "saved_and_verified": verified, "model_requests": len(requests),
                "turn_seconds": round(turn_seconds, 3), "cleanup_seconds": round(time.monotonic() - cleanup_started, 3),
                "native_socket_removed": True}))


if __name__ == "__main__":
    unittest.main()
