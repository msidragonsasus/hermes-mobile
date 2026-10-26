"""Run against the installed Hermes Python with isolated profile files; no inference."""
import os
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

from career_core.mypage_bot import MyPageBotStore
from career_core.mypage_cron import HermesMyPageCron
from career_core.secret_store import SecretStore
from career_core.store import Store


@unittest.skipUnless(os.environ.get("CAREER_HERMES_CONTRACT_TESTS") == "1", "Run with the installed Hermes interpreter")
class HermesCronContractTests(unittest.TestCase):
    def test_native_janitor_keeps_leased_run_during_cdp_and_model_wait_but_reaps_others(self):
        from tools import browser_tool as browser
        from tools.browser_tool_lifecycle import _update_session_activity, _cleanup_inactive_browser_sessions
        from career_core.mypage_browser import BrowserSessionLease, touch_native_browser_session
        task, other = "career-mypage-fixture::local", "unrelated::local"
        stamps = {task: 0, other: 0}
        session = {"session_name": "fixture"}
        with patch.object(browser, "_active_sessions", {task: session}), patch.object(browser, "_session_last_activity", stamps), patch.object(browser, "_session_owner_homes", {}), patch.object(browser, "_cleanup_failures", {}), patch.object(browser, "BROWSER_SESSION_INACTIVITY_TIMEOUT", 120), patch("tools.browser_tool_lifecycle._session_owner_scope", return_value=nullcontext()), patch("tools.browser_tool_lifecycle.cleanup_browser") as cleanup:
            lease = BrowserSessionLease(task, 480, lambda key: touch_native_browser_session(key, session), lambda error: self.fail(type(error).__name__))
            # No native browser command is issued during the full simulated run.
            for now in range(15, 480, 15):
                with patch("time.time", return_value=now), patch("time.monotonic", return_value=now):
                    self.assertTrue(lease.pulse())
                    _cleanup_inactive_browser_sessions()
            self.assertEqual([other], [call.args[0] for call in cleanup.call_args_list])
            with patch("time.monotonic", return_value=480):
                self.assertFalse(lease.pulse())
            lease.stop()
            with patch("time.time", return_value=601):
                _cleanup_inactive_browser_sessions()
            self.assertEqual([other, task], [call.args[0] for call in cleanup.call_args_list])

    def test_model_auth_uses_root_scope_and_restores_bot_without_copying_secrets(self):
        from agent.secret_scope import current_secret_scope, set_secret_scope, reset_secret_scope
        from hermes_constants import get_hermes_home
        from career_core.mypage_agent import model_credentials
        from career_core.store import CareerError
        execution = {"provider": "openrouter", "model": "nvidia/nemotron-3-super-120b-a12b:free", "reasoning_effort": "default"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bot = root / "profiles" / "career-mypage"
            bot.mkdir(parents=True)
            (root / ".env").write_text("OPENROUTER_API_KEY=fixture-model-secret\nOTHER_SECRET=not-for-the-bot\n", encoding="utf-8")
            def resolve(value):
                self.assertEqual(execution, value)
                self.assertEqual(root.resolve(), Path(get_hermes_home()).resolve())
                self.assertEqual({"OPENROUTER_API_KEY": "fixture-model-secret"}, current_secret_scope())
                return {"provider": "openrouter", "base_url": "https://openrouter.ai/api/v1", "api_mode": "chat_completions", "api_key": "fixture-model-secret"}
            with patch.dict(os.environ, {"HERMES_HOME": str(bot)}):
                token = set_secret_scope({"BOT_SECRET": "unchanged"})
                try:
                    with patch("career_core.coach_execution.runtime_credentials", side_effect=resolve):
                        self.assertEqual("fixture-model-secret", model_credentials(execution)["api_key"])
                    with patch("career_core.coach_execution.runtime_credentials", side_effect=CareerError("provider_unavailable", "fixture")):
                        with self.assertRaises(CareerError):
                            model_credentials(execution)
                    self.assertEqual(bot.resolve(), Path(get_hermes_home()).resolve())
                    self.assertEqual({"BOT_SECRET": "unchanged"}, current_secret_scope())
                    self.assertFalse((bot / "auth.json").exists())
                    self.assertFalse((bot / ".env").exists())
                    for path in bot.rglob("*"):
                        if path.is_file():
                            self.assertNotIn(b"fixture-model-secret", path.read_bytes())
                finally:
                    reset_secret_scope(token)

    def test_real_cron_job_idempotence_pause_manual_claim_and_profile_isolation(self):
        from cryptography.fernet import Fernet
        from cron.jobs import get_job, list_jobs
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "bot"
            home.mkdir()
            (home / "config.yaml").write_text("timezone: Asia/Tokyo\ncron:\n  max_parallel_jobs: 1\n", encoding="utf-8")
            store = Store(Path(directory) / "data")
            secrets = SecretStore(Path(directory) / "private", key=Fernet.generate_key())
            records = MyPageBotStore(store, secrets)
            with store.transaction() as db:
                store.put(db, "companies", "EY", 0, {"name": "EY"})
            with patch("career_core.mypage_browser.public_host", return_value="portal.example.test"):
                row = records.configure("EY", "default", {"company_id": "EY", "url": "https://portal.example.test/login", "login_id": "fake", "password": "private-fixture", "enabled": True, "expected_version": 0})
            cron = HermesMyPageCron(records, home)
            first = cron.ensure(row)
            self.assertFalse(first["enabled"])
            again = cron.ensure(records.get(row["id"]))
            self.assertEqual(first["id"], again["id"])
            records.update_config(row["id"], row["version"], {"verified": True})
            active = cron.ensure(records.get(row["id"]))
            from datetime import datetime
            scheduled = datetime.fromisoformat(active["next_run_at"])
            self.assertIn(scheduled.hour, {9, 18})
            self.assertEqual(9 * 3600, scheduled.utcoffset().total_seconds())
            with cron.scope():
                self.assertEqual(1, len(list_jobs(include_disabled=True)))
            claim = cron.claim(records.get(row["id"]))
            self.assertEqual(first["id"], claim["id"])
            self.assertTrue(claim["execution_id"])
            cron.pause(records.get(row["id"]))
            with cron.scope():
                self.assertFalse(get_job(first["id"])["enabled"])
            self.assertNotIn("private-fixture", "".join(p.read_text(encoding="utf-8") for p in home.rglob("*.py")))

    def test_native_registry_exposes_only_five_restricted_tools(self):
        from tools.registry import registry
        from model_tools import get_tool_definitions
        from career_core.mypage_agent import register_tools, TOOLS
        class Context:
            def register_tool(self, **kwargs):
                registry.register(**kwargs)
        register_tools(Context())
        definitions = get_tool_definitions(enabled_toolsets=["career_mypage"], quiet_mode=True, skip_tool_search_assembly=True)
        self.assertEqual(set(TOOLS), {t["function"]["name"] for t in definitions})

    def test_native_agent_builds_free_request_with_only_portal_tools_without_inference(self):
        from tools.registry import registry
        from career_core.mypage_agent import register_tools, TOOLS, guard_execution
        from career_core.coach_runtime import make_agent
        class Context:
            def register_tool(self, **kwargs):
                registry.register(**kwargs)
        register_tools(Context())
        execution = {"provider": "openrouter", "model": "nvidia/nemotron-3-super-120b-a12b:free", "reasoning_effort": "default"}
        runtime = {"provider": "openrouter", "base_url": "https://openrouter.ai/api/v1", "api_mode": "chat_completions", "api_key": "fixture-only"}
        # Hermes' process-wide logging lock is released at process exit on Windows.
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory, patch.dict(os.environ, {"HERMES_HOME": directory}):
            Path(directory, "config.yaml").write_text("{}", encoding="utf-8")
            agent = make_agent("career-mypage:fixture", "fixture", execution=execution,
                credential_resolver=lambda _: runtime, execution_guard=guard_execution,
                toolset="career_mypage", tool_names=TOOLS)
            try:
                payload = agent._build_api_kwargs([{"role": "user", "content": "fixture"}])
                self.assertEqual(execution["model"], payload["model"])
                self.assertFalse(payload["extra_body"]["provider"]["allow_fallbacks"])
                self.assertEqual(0, payload["extra_body"]["provider"]["max_price"]["prompt"])
                self.assertEqual(set(TOOLS), {tool["function"]["name"] for tool in payload["tools"]})
            finally:
                agent.close()

    def test_native_codex_request_session_headers_pass_without_inference(self):
        from tools.registry import registry
        from career_core.mypage_agent import register_tools, TOOLS, guard_execution
        from career_core.coach_runtime import make_agent
        class Context:
            def register_tool(self, **kwargs):
                registry.register(**kwargs)
        register_tools(Context())
        execution = {"provider": "openai-codex", "model": "gpt-5.6-luna", "reasoning_effort": "high"}
        runtime = {"provider": "openai-codex", "base_url": "https://chatgpt.com/backend-api/codex", "api_mode": "codex_responses", "api_key": "fixture-only"}
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as directory, patch.dict(os.environ, {"HERMES_HOME": directory}):
            Path(directory, "config.yaml").write_text("{}", encoding="utf-8")
            agent = make_agent("career-mypage:fixture", "fixture", execution=execution,
                credential_resolver=lambda _: runtime, execution_guard=guard_execution,
                toolset="career_mypage", tool_names=TOOLS)
            try:
                payload = agent._build_api_kwargs([{"role": "user", "content": "fixture"}])
                self.assertEqual(execution["model"], payload["model"])
                self.assertEqual("high", payload["reasoning"]["effort"])
                self.assertEqual("career-mypage:fixture", payload["extra_headers"]["session_id"])
                self.assertEqual(payload["prompt_cache_key"], payload["extra_headers"]["x-client-request-id"])
                self.assertEqual(set(TOOLS), {tool["name"] for tool in payload["tools"]})
            finally:
                agent.close()


if __name__ == "__main__":
    unittest.main()
