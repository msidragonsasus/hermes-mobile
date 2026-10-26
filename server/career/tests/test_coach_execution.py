import json
import os
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.coach_execution import CODEX_BASE, ExecutionPolicy, codex_account_headers, discover_codex_models, discover_models, runtime_credentials, wire_effort
from career_core.coach_runtime import HermesCoachRuntime, make_agent, verify_wire
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools, TOOLS
from career_core.coach_worker import CoachWorker, enqueue_daily
from career_core.shared import save_document
from career_core.store import CareerError, Store, encoded
from coach_fixture import FIRST, SECOND, configure_execution


class ExecutionTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(self.temp.name)
        self.coach = CoachStore(self.store)

    def submit(self, operation="first-message", execution=FIRST, **extra):
        return self.coach.submit(dict(operation_id=operation, text="本文を作成", execution=execution, **extra), "phone")

    def error(self, code, call):
        with self.assertRaises(CareerError) as error:
            call()
        self.assertEqual(code, error.exception.code)

    def test_capabilities_and_daily_http_auth_cas_and_profile_boundary(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test-session"):
                request.state.session = {"user": "test"}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        client = TestClient(app)
        root = "/api/plugins/career/coach/"
        headers = {"x-test-session": "yes"}
        self.assertEqual(401, client.get(root + "capabilities").status_code)
        capabilities = client.get(root + "capabilities?profile=phone", headers=headers)
        self.assertEqual(200, capabilities.status_code)
        self.assertEqual("0.4.0", capabilities.json()["plugin_version"])
        self.assertIn("execution_config", capabilities.json()["features"])
        request = {"operation_id": "http-daily-first", "expected_version": 0, "execution": SECOND}
        self.assertEqual(401, client.post(root + "daily-settings?profile=phone", json=request).status_code)
        saved = client.post(root + "daily-settings?profile=phone", json=request, headers=headers)
        self.assertEqual(200, saved.status_code)
        self.assertEqual(saved.json(), client.post(root + "daily-settings?profile=phone", json=request, headers=headers).json())
        self.assertEqual(SECOND, client.get(root + "daily-settings?profile=phone", headers=headers).json()["execution"])
        self.assertEqual(FIRST, client.get(root + "daily-settings?profile=desktop", headers=headers).json()["execution"])
        stale = client.post(root + "daily-settings?profile=phone", json=dict(request, operation_id="http-daily-stale"), headers=headers)
        self.assertEqual(409, stale.status_code)
        self.assertEqual("version_conflict", stale.json()["detail"]["code"])

    def test_explicit_execution_is_immutable_and_retry_does_not_need_catalog(self):
        first = self.submit()
        with patch("career_core.coach_execution.discover_models", side_effect=AssertionError("No catalog on retry")), patch.dict(os.environ, {"CAREER_CODEX_MODEL": SECOND["model"]}):
            self.assertEqual(first, self.submit())
            self.error("operation_reused", lambda: self.submit(execution=SECOND))
        self.assertEqual(FIRST, self.coach.run(first["id"])["request"]["execution"])
        self.assertEqual(FIRST, self.coach.snapshot("phone")["runs"][0]["execution"])
        self.assertEqual(1, len(self.coach.snapshot("phone")["messages"]))

    def test_old_client_default_frozen_and_original_fingerprint_replayed(self):
        payload = {"operation_id": "old-client-message", "text": "相談"}
        accepted = self.coach.submit(payload, "phone")
        with patch.dict(os.environ, {"CAREER_CODEX_MODEL": SECOND["model"]}):
            self.assertEqual(accepted, self.coach.submit(payload, "phone"))
        self.assertEqual(FIRST, self.coach.run(accepted["id"])["request"]["execution"])
        # A pre-upgrade acknowledgement must still replay under its old fingerprint.
        with self.store.transaction() as db:
            db.execute("DELETE FROM operations WHERE id=?", (payload["operation_id"],))
        old_request = dict(profile="phone", text="相談", target=None, kind="message")
        old_ack = {"id": accepted["id"], "state": "queued"}
        self.store.operation(payload["operation_id"], old_request, lambda db: old_ack)
        self.assertEqual(old_ack, self.coach.submit(payload, "phone"))

    def test_unknown_provider_model_effort_rejected_without_run(self):
        self.error("model_unsupported", lambda: self.submit(execution=dict(FIRST, provider="openai")))
        self.error("model_unsupported", lambda: self.submit(execution=dict(FIRST, model="synthetic-not-in-account")))
        self.error("reasoning_unsupported", lambda: self.submit(execution=dict(FIRST, reasoning_effort="ultra")))
        self.assertEqual([], self.coach.snapshot("phone")["runs"])

    def test_other_provider_daily_setting_is_saved_and_frozen_by_provider_model_pair(self):
        execution = dict(FIRST, provider="anthropic", reasoning_effort="default")
        catalog = self.coach.execution.catalog()
        # Same model id on two providers must not borrow the Codex effort list.
        catalog["models"].append({"provider": "anthropic", "id": FIRST["model"], "reasoning_efforts": ["default"]})
        with self.store.transaction() as db:
            self.store.set_meta(db, "coach_model_catalog", catalog)
        policy = self.coach.execution
        request = {"operation_id": "daily-other-provider", "expected_version": 0, "execution": execution}
        saved = policy.update_daily(request, "phone")
        self.assertEqual(execution, saved["execution"])
        self.submit(execution=execution)
        enqueue_daily(self.store)
        daily = next(run for run in self.coach.snapshot("phone")["runs"] if run["kind"] == "daily")
        policy.update_daily(dict(request, operation_id="daily-back-to-codex", expected_version=1, execution=FIRST), "phone")
        self.assertEqual(execution, self.coach.run(daily["id"])["request"]["execution"])
        self.error("reasoning_unsupported", lambda: self.submit(operation="wrong-effort-provider", execution=dict(execution, reasoning_effort="high")))

    def test_daily_settings_cas_profile_isolation_and_existing_job_fixed(self):
        self.submit()
        policy = self.coach.execution
        request = {"operation_id": "daily-settings-one", "expected_version": 0, "execution": SECOND}
        saved = policy.update_daily(request, "phone")
        self.assertEqual(saved, policy.update_daily(request, "phone"))
        self.assertEqual(FIRST, policy.daily("desktop")["execution"])
        self.error("version_conflict", lambda: policy.update_daily(dict(request, operation_id="daily-settings-stale"), "phone"))
        enqueue_daily(self.store)
        daily = next(run for run in self.coach.snapshot("phone")["runs"] if run["kind"] == "daily")
        self.assertEqual(SECOND, daily["execution"])
        policy.update_daily(dict(request, operation_id="daily-settings-two", expected_version=1, execution=FIRST), "phone")
        self.assertEqual(SECOND, self.coach.run(daily["id"])["request"]["execution"])
        self.assertEqual(FIRST, self.coach.snapshot("phone")["runs"][-1]["execution"])

    def test_legacy_queued_and_running_pause_without_inventing_execution(self):
        for index in range(2):
            accepted = self.submit(operation="legacy-message-" + str(index))
            with self.store.transaction() as db:
                run = self.coach.run(accepted["id"])
                run["request"].pop("execution")
                db.execute("UPDATE coach_runs SET request=?,state=? WHERE id=?", (encoded(run["request"]), "queued" if index == 0 else "running", accepted["id"]))
        self.coach.recover()
        for run in self.coach.snapshot("phone")["runs"]:
            self.assertEqual("paused", run["state"])
            self.assertIsNone(run["execution"])
            self.assertEqual("execution_confirmation_required", run["result"]["reason"])
        self.assertIsNone(self.coach.claim())
        request = {"id": accepted["id"], "control": "resume", "operation_id": "legacy-resume-one"}
        self.error("execution_confirmation_required", lambda: self.coach.control(request, "phone"))
        confirmed = dict(request, execution=SECOND)
        result = self.coach.control(confirmed, "phone")
        self.assertEqual(result, self.coach.control(confirmed, "phone"))
        self.assertEqual(SECOND, self.coach.claim()["request"]["execution"])

    def test_recorded_resume_cannot_replace_execution(self):
        accepted = self.submit()
        self.coach.finish(accepted["id"], "paused")
        request = {"id": accepted["id"], "control": "resume", "operation_id": "resume-config-change", "execution": SECOND}
        self.error("execution_immutable", lambda: self.coach.control(request, "phone"))

    def document(self, body="初稿", version=0):
        return save_document(self.store, {"kind": "documents", "id": "DOC-001", "expected_version": version,
                            "data": {"title": "自己PR", "body": body}, "operation_id": "save-document-" + str(version)})

    def test_artifact_receipt_survives_missing_checkpoint_and_offline_sync(self):
        from test_career import ContextRemote
        from career_core.sync import synchronize
        remote = ContextRemote()
        remote.state["documents"] = []
        synchronize(self.store, remote)
        self.submit()
        class Runtime:
            def __init__(inner, coach):
                inner.coach = coach
            def run(inner, run):
                inner.coach.progress(run["id"], "execution", dict(FIRST, state="dispatched"))
                CoachTools(inner.coach).dispatch("career_save", {"kind": "documents", "id": "DOC-001", "expected_version": 0,
                    "data": {"title": "自己PR", "body": "本文\n😀"}}, {"task_id": run["id"], "session_id": "career-run:" + run["id"]})
                return "本文を保存しました"
        with patch("career_core.api.sync_job", side_effect=[{"state": "synced"}, {"state": "unsynced"}]):
            self.assertTrue(CoachWorker(self.store, Runtime).one())
        run = self.coach.snapshot("phone")["runs"][0]
        self.assertEqual("completed", run["state"])
        self.assertEqual(["DOC-001"], run["result"]["artifact_ids"])
        self.assertEqual(FIRST["model"], run["result"]["effective_execution"]["model"])
        artifact = run["artifacts"][0]
        self.assertTrue(artifact["server_saved"])
        self.assertFalse(artifact["github_synced"])
        self.assertEqual(4, artifact["characters"])
        self.document("最新版の別本文", 1)
        self.assertEqual("本文\n😀", self.coach.artifacts(run["id"])[0]["preview"])
        with self.store.transaction() as db:
            db.execute("UPDATE document_files SET synced=1 WHERE path=?", (artifact["path"],))
        self.assertTrue(self.coach.artifacts(run["id"])[0]["github_synced"])

    def test_revision_version_is_checked_on_accept_execute_and_write(self):
        self.document()
        target = {"kind": "documents", "id": "DOC-001", "version": 1}
        accepted = self.submit(target=target)
        run = self.coach.claim()
        metadata = {"task_id": run["id"], "session_id": "career-run:" + run["id"]}
        self.document("本人が更新", 1)
        self.error("document_version_conflict", lambda: self.submit(operation="stale-document-submit", target=target))
        self.error("document_version_conflict", lambda: CoachTools(self.coach).dispatch("career_save", {
            "kind": "documents", "id": "DOC-001", "expected_version": 2, "data": {"body": "誤上書き"}}, metadata))
        self.coach.finish(accepted["id"], "paused")
        self.error("document_version_conflict", lambda: self.coach.control({"id": run["id"], "control": "resume", "operation_id": "stale-resume-doc"}, "phone"))
        self.assertEqual("本人が更新", self.store.snapshot()["entities"][0]["data"]["body"])

    def test_saved_revision_can_resume_after_crash_without_extra_version(self):
        self.document()
        self.submit(target={"kind": "documents", "id": "DOC-001", "version": 1})
        run = self.coach.claim()
        args = {"kind": "documents", "id": "DOC-001", "expected_version": 1, "data": {"body": "改善本文"}}
        metadata = {"task_id": run["id"], "session_id": "career-run:" + run["id"]}
        receipt = CoachTools(self.coach).dispatch("career_save", args, metadata)
        self.coach.recover()
        self.coach.control({"id": run["id"], "control": "resume", "operation_id": "resume-receipt-one"}, "phone")
        self.coach.claim()
        self.assertEqual(receipt, CoachTools(self.coach).dispatch("career_save", args, metadata))
        self.assertEqual(2, self.store.snapshot()["entities"][0]["data"]["version"])

    def test_runtime_and_extractor_both_receive_the_accepted_setting(self):
        self.submit(execution=SECOND)
        run = self.coach.claim()
        calls = []
        class Agent:
            def run_conversation(inner, text, **kwargs):
                return {"final_response": '{"offers": []}'}
            def close(inner):
                pass
        def factory(*args, **kwargs):
            calls.append(kwargs)
            return Agent()
        runtime = HermesCoachRuntime(self.coach, factory)
        runtime.extractor(run).extract({}, [])
        runtime.run(run)
        self.assertEqual([SECOND, SECOND], [call["execution"] for call in calls])
        self.assertFalse(calls[0]["tools_enabled"])

    def test_capabilities_show_setup_separately_from_account_candidates(self):
        capabilities = self.coach.execution.capabilities("phone")
        self.assertIn("execution_config", capabilities["features"])
        self.assertFalse(capabilities["models"][0]["inference_verified"])
        self.assertEqual("setup_required", capabilities["work"][-1]["state"])
        with patch("career_core.coach_execution.discover_models", side_effect=CareerError("oauth_unavailable", "login", 503)):
            result = self.coach.execution.capabilities("phone", refresh=True)
        self.assertEqual("oauth_unavailable", result["reason"])
        self.assertEqual([], result["models"])


class RuntimeWireTests(unittest.TestCase):
    def test_mypage_native_headers_are_delegated_to_hermes(self):
        from career_core.mypage_agent import guard_execution
        execution = {"provider": "openai-codex", "model": "gpt-5.6-luna", "reasoning_effort": "high"}
        runtime = {"provider": "openai-codex", "base_url": CODEX_BASE, "api_mode": "codex_responses"}
        headers = {"session_id": "career-mypage:fixture", "x-client-request-id": "fixture", "X-Hermes-Test": "fixture"}
        payload = {"model": execution["model"], "extra_headers": headers.copy()}
        guard_execution(execution, runtime, payload)
        self.assertEqual(headers, payload["extra_headers"])
        with self.assertRaises(CareerError):
            guard_execution(execution, runtime, dict(payload, model="different-model"))
        with self.assertRaises(CareerError):
            guard_execution(execution, dict(runtime, base_url="https://other.invalid"), payload)

    def test_mypage_free_model_guard_rejects_paid_aliases_and_endpoint_changes(self):
        from career_core.mypage_agent import guard_execution
        execution = {"provider": "openrouter", "model": "nvidia/nemotron-3-super-120b-a12b:free", "reasoning_effort": "default"}
        runtime = {"provider": "openrouter", "base_url": "https://openrouter.ai/api/v1", "api_mode": "chat_completions"}
        guard_execution(execution, runtime)
        for model in ("openrouter/auto:free", "openrouter/free", "@preset/free", "vendor/model", "vendor/model:free:online", "vendor/model:free?x=1"):
            with self.subTest(model=model), self.assertRaises(CareerError):
                guard_execution(dict(execution, model=model), runtime)
        for key, value in (("provider", "custom"), ("base_url", "https://mirror.invalid/api/v1"), ("api_mode", "responses")):
            with self.subTest(key=key), self.assertRaises(CareerError):
                guard_execution(execution, dict(runtime, **{key: value}))
        for option in ("models", "fallbacks", "plugins", "preset", "web_search_options"):
            for extra in (False, True):
                payload = {"model": execution["model"]}
                payload.update({"extra_body": {option: ["paid"]}} if extra else {option: ["paid"]})
                with self.subTest(option=option, extra=extra), self.assertRaises(CareerError):
                    guard_execution(execution, runtime, payload)

    def test_mypage_caps_are_applied_again_at_actual_stream_and_retry_dispatch(self):
        from career_core.mypage_agent import guard_execution
        execution = {"provider": "openrouter", "model": "nvidia/nemotron-3-super-120b-a12b:free", "reasoning_effort": "default"}
        runtime = {"provider": "openrouter", "api_mode": "chat_completions", "base_url": "https://openrouter.ai/api/v1", "api_key": "synthetic-secret"}
        sent, constructor = [], {}
        class Agent:
            def __init__(inner, **kwargs):
                constructor.update(kwargs)
                for name in ("provider", "model", "base_url", "api_mode", "reasoning_config"):
                    setattr(inner, name, kwargs[name])
                inner.context_compressor = types.SimpleNamespace()
            def _build_api_kwargs(inner):
                return {"model": inner.model, "messages": [], "extra_body": {"provider": {"only": ["fixture"]}}}
            def _interruptible_api_call(inner, payload):
                sent.append(json.loads(json.dumps(payload)))
            def _interruptible_streaming_api_call(inner, payload, **kwargs):
                sent.append(json.loads(json.dumps(payload)))
            def close(inner):
                pass
        modules = {"run_agent": types.SimpleNamespace(AIAgent=Agent), "model_tools": types.SimpleNamespace(get_tool_definitions=lambda **kwargs: [])}
        with patch.dict(sys.modules, modules), patch("career_core.coach_runtime.credentials", return_value=runtime):
            agent = make_agent("career-mypage:test", "", execution=execution, tools_enabled=False, execution_guard=guard_execution)
            payload = agent._build_api_kwargs()
            payload["extra_body"]["provider"].update(allow_fallbacks=True, max_price={"prompt": 10})
            agent._interruptible_api_call(payload)
            payload["extra_body"]["provider"].update(allow_fallbacks=True, max_price={"completion": 10})
            agent._interruptible_streaming_api_call(payload)
            with self.assertRaises(CareerError):
                agent._interruptible_api_call(dict(payload, models=["paid/model"]))
        self.assertEqual(2, len(sent))
        for value in sent:
            self.assertEqual(execution["model"], value["model"])
            routing = value["extra_body"]["provider"]
            self.assertEqual(["fixture"], routing["only"])
            self.assertFalse(routing["allow_fallbacks"])
            self.assertEqual({"prompt": 0, "completion": 0, "request": 0, "image": 0, "audio": 0}, routing["max_price"])
        self.assertEqual([], constructor["fallback_model"])
        self.assertIsNone(constructor["credential_pool"])

    def test_wire_guard_rejects_model_effort_and_extra_body_overrides(self):
        good = {"model": FIRST["model"], "reasoning": {"effort": FIRST["reasoning_effort"]}}
        self.assertEqual(FIRST, verify_wire(FIRST, good))
        for payload in (dict(good, model=SECOND["model"]), dict(good, reasoning={"effort": "low"}), dict(good, extra_body={"reasoning": {"effort": "high"}})):
            with self.assertRaises(CareerError) as error:
                verify_wire(FIRST, payload)
            self.assertEqual("execution_mismatch", error.exception.code)

    def test_real_adapter_constructor_and_dispatch_guard(self):
        sent, constructor, diagnostics = [], {}, []
        class Agent:
            def __init__(inner, **kwargs):
                constructor.update(kwargs)
                inner.provider, inner.base_url, inner.model = kwargs["provider"], kwargs["base_url"], kwargs["model"]
                inner.api_mode = kwargs["api_mode"]
                inner.reasoning_config = kwargs["reasoning_config"]
                inner.context_compressor = types.SimpleNamespace()
            def _build_api_kwargs(inner, *args):
                return {"model": inner.model, "reasoning": {"effort": constructor["reasoning_config"]["effort"]}}
            def _interruptible_api_call(inner, payload):
                sent.append(payload)
            def _interruptible_streaming_api_call(inner, payload, **kwargs):
                sent.append(payload)
            def close(inner):
                pass
        modules = {"run_agent": types.SimpleNamespace(AIAgent=Agent), "model_tools": types.SimpleNamespace(
            get_tool_definitions=lambda **kwargs: [{"function": {"name": name}} for name in TOOLS])}
        value = {"api_key": "synthetic", "provider": "openai-codex", "base_url": CODEX_BASE, "api_mode": "codex_responses"}
        with patch.dict(sys.modules, modules), patch("career_core.coach_runtime.credentials", return_value=value), patch("career_core.coach_runtime.wire_effort"):
            agent = make_agent("career-run:unit", "test", execution=SECOND, on_execution=diagnostics.append)
            payload = agent._build_api_kwargs([])
            agent._interruptible_api_call(payload)
            agent._interruptible_streaming_api_call(payload)
            with self.assertRaises(CareerError):
                agent._interruptible_api_call(dict(payload, reasoning={"effort": "low"}))
        self.assertEqual(2, len(sent))
        self.assertEqual(SECOND["model"], constructor["model"])
        self.assertEqual({"effort": "high", "enabled": True}, constructor["reasoning_config"])
        self.assertEqual([], constructor["fallback_model"])
        self.assertEqual("high", diagnostics[0]["reasoning_effort"])
        self.assertNotIn("synthetic", json.dumps(diagnostics))

    def test_runtime_effort_helper_rejects_hermes_clamping(self):
        module = types.SimpleNamespace(_resolve_reasoning=lambda model, params: ("high", True))
        with patch.dict(sys.modules, {"agent.transports.codex": module}):
            self.assertEqual("high", wire_effort("example", "high"))
            with self.assertRaises(CareerError) as error:
                wire_effort("example", "ultra")
        self.assertEqual("reasoning_unsupported", error.exception.code)


class ProviderTests(unittest.TestCase):
    def test_codex_catalog_uses_current_headers_without_removed_private_helper(self):
        import httpx
        token = "synthetic-token-never-log"
        entries = [{"slug": "account-model", "supported_reasoning_levels": [{"effort": "high"}]}]
        # Current Hermes no longer exports _extract_chatgpt_account_id here.
        modules = {
            "hermes_cli.codex_models": types.SimpleNamespace(_ranked_slugs=lambda items: [item["slug"] for item in items]),
            "agent.codex_headers": types.SimpleNamespace(codex_account_headers=lambda value: {
                "ChatGPT-Account-ID": "synthetic-account", "x-openai-internal-codex-residency": "eu"}),
            "agent.reasoning_effort": types.SimpleNamespace(codex_supported_efforts=lambda model: ["high"]),
        }
        response = httpx.Response(200, json={"models": entries}, request=httpx.Request("GET", CODEX_BASE + "/models"))
        with patch.dict(sys.modules, modules), patch("career_core.coach_execution.oauth_credentials", return_value={"api_key": token}), patch(
            "career_core.coach_execution.wire_effort", side_effect=lambda model, effort: effort
        ), patch("httpx.get", return_value=response) as get:
            result = discover_codex_models()
        self.assertEqual(["account-model"], [model["id"] for model in result["models"]])
        self.assertEqual(["high"], result["models"][0]["reasoning_efforts"])
        self.assertFalse(result["models"][0]["inference_verified"])
        self.assertEqual({"Authorization": "Bearer " + token, "ChatGPT-Account-ID": "synthetic-account",
                          "x-openai-internal-codex-residency": "eu"}, get.call_args.kwargs["headers"])
        self.assertNotIn(token, json.dumps(result))

    def test_codex_headers_support_older_hermes_and_missing_account(self):
        for account in ("synthetic-account", None):
            with self.subTest(account=account), patch.dict(sys.modules, {
                "agent.codex_headers": None,
                "hermes_cli.codex_models": types.SimpleNamespace(_extract_chatgpt_account_id=lambda token: account),
            }):
                self.assertEqual({"ChatGPT-Account-Id": account} if account else {}, codex_account_headers("synthetic"))

    def test_codex_missing_header_contract_is_not_reported_as_network_failure(self):
        with patch.dict(sys.modules, {"agent.codex_headers": None, "hermes_cli.codex_models": types.SimpleNamespace()}):
            with self.assertRaises(CareerError) as error:
                codex_account_headers("synthetic-token-never-log")
        self.assertEqual("hermes_contract_unsupported", error.exception.code)
        self.assertNotIn("synthetic-token-never-log", str(error.exception))

    def test_inventory_keeps_other_providers_when_codex_login_fails(self):
        inventory = {"providers": [
            {"slug": "openai-codex", "models": ["codex"]},
            {"slug": "anthropic", "models": ["claude"], "authenticated": True},
            {"slug": "nous", "models": ["other"], "authenticated": True},
            {"slug": "openai", "models": ["unconfigured"], "authenticated": False},
            {"slug": "moa", "models": ["implicit-chain"]},
        ]}
        module = types.SimpleNamespace(load_picker_context=lambda: object(), build_model_options_payload=lambda *a, **k: inventory)
        with patch.dict(sys.modules, {"hermes_cli.inventory": module}), patch(
            "career_core.coach_execution.discover_codex_models", side_effect=CareerError("oauth_unavailable", "login", 503)
        ):
            result = discover_models()
        self.assertEqual(["anthropic", "nous"], [row["provider"] for row in result["models"]])
        self.assertEqual(["default"], result["models"][0]["reasoning_efforts"])
        self.assertEqual([{"provider": "openai-codex", "reason": "oauth_unavailable"}], result["provider_errors"])
        self.assertFalse(result["models"][0]["inference_verified"])

    def test_runtime_resolves_explicit_provider_and_rejects_fallback_or_model_change(self):
        execution = {"provider": "anthropic", "model": "claude", "reasoning_effort": "default"}
        good = {"provider": "anthropic", "model": "claude", "api_mode": "anthropic_messages",
                "base_url": "https://api.example.invalid", "api_key": "never-log-this"}
        calls = []
        def resolve(**kwargs):
            calls.append(kwargs)
            return good.copy()
        modules = {
            "hermes_cli.runtime_provider": types.SimpleNamespace(resolve_runtime_provider=resolve, has_named_custom_provider=lambda p: False),
            "hermes_cli.auth": types.SimpleNamespace(resolve_provider=lambda p: p),
        }
        with patch.dict(sys.modules, modules):
            self.assertEqual(good, runtime_credentials(execution))
            for field, value in (("provider", "openrouter"), ("model", "different")):
                previous = good[field]
                good[field] = value
                with self.assertRaises(CareerError) as error:
                    runtime_credentials(execution)
                self.assertEqual("execution_mismatch", error.exception.code)
                self.assertNotIn("never-log-this", str(error.exception))
                good[field] = previous
        self.assertEqual({"requested": "anthropic", "target_model": "claude"}, calls[0])

    def test_non_codex_agent_dispatch_keeps_selected_transport_without_fallback(self):
        for provider, mode in (("anthropic", "anthropic_messages"), ("nous", "chat_completions"), ("openrouter", "chat_completions")):
            with self.subTest(provider=provider):
                sent, diagnostics, constructor = [], [], {}
                execution = {"provider": provider, "model": "shared-name", "reasoning_effort": "default"}
                class Agent:
                    def __init__(inner, **kwargs):
                        constructor.update(kwargs)
                        for name in ("provider", "model", "base_url", "api_mode", "reasoning_config"):
                            setattr(inner, name, kwargs[name])
                        inner.context_compressor = types.SimpleNamespace()
                    def _build_api_kwargs(inner):
                        return {"model": inner.model, "messages": []}
                    def _interruptible_api_call(inner, payload):
                        sent.append(payload)
                    def _interruptible_streaming_api_call(inner, payload, **kwargs):
                        sent.append(payload)
                    def close(inner):
                        pass
                modules = {"run_agent": types.SimpleNamespace(AIAgent=Agent), "model_tools": types.SimpleNamespace(get_tool_definitions=lambda **kwargs: [])}
                runtime = {"provider": provider, "api_mode": mode, "base_url": "https://example.invalid", "api_key": "synthetic-secret"}
                with patch.dict(sys.modules, modules), patch("career_core.coach_runtime.credentials", return_value=runtime):
                    agent = make_agent("career-test", "", execution=execution, tools_enabled=False, on_execution=diagnostics.append)
                    payload = agent._build_api_kwargs()
                    agent._interruptible_api_call(payload)
                    agent._interruptible_streaming_api_call(payload)
                    agent.provider = "openai"
                    with self.assertRaises(CareerError):
                        agent._interruptible_api_call(payload)
                self.assertEqual(2, len(sent))
                self.assertEqual(execution, {key: diagnostics[0][key] for key in execution})
                self.assertEqual([], constructor["fallback_model"])
                self.assertIsNone(constructor["credential_pool"])
                self.assertIsNone(constructor["reasoning_config"])
                self.assertNotIn("synthetic-secret", json.dumps(diagnostics))


if __name__ == "__main__":
    unittest.main()
