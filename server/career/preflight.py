"""Read-only Hermes plugin contract probe. Run with the live Hermes Python environment."""
import importlib.util
import inspect
import json
from pathlib import Path


def main():
    report = {}
    for module, attributes in {
        "hermes_cli.plugins": ["PluginContext", "def register_tool", "def register_skill"],
        "hermes_cli.web_server_dashboard": ["_mount_plugin_api_routes"],
        "hermes_cli.dashboard_auth.middleware": ["gated_auth_middleware"],
        "hermes_cli.auth": ["resolve_codex_runtime_credentials"],
    }.items():
        spec = importlib.util.find_spec(module)
        if spec is None or spec.origin is None:
            report[module] = {"available": False}
            continue
        source = Path(spec.origin).read_text(encoding="utf-8")
        report[module] = {"file": spec.origin, "names_present": {name: name in source for name in attributes}}
    print(json.dumps(report, indent=2))
    from run_agent import AIAgent
    constructor = inspect.signature(AIAgent.__init__)
    turn = inspect.signature(AIAgent.run_conversation)
    required = {"session_id", "enabled_toolsets", "skip_background_review", "ephemeral_system_prompt", "fallback_model", "reasoning_config", "api_mode"}
    print(json.dumps({"agent_constructor_explicit": sorted(required.intersection(constructor.parameters)),
                      "agent_turn": {name: name in turn.parameters for name in ("conversation_history", "task_id")},
                      "interrupt": callable(getattr(AIAgent, "interrupt", None)),
                      "close": callable(getattr(AIAgent, "close", None))}, indent=2))
    from agent.transports.codex import _resolve_reasoning
    from hermes_cli.codex_models import _ranked_slugs
    try:
        from agent.codex_headers import codex_account_headers as account_headers
    except ImportError:
        from hermes_cli.codex_models import _extract_chatgpt_account_id as account_headers
    print(json.dumps({"execution_contract": {
        "wire_resolver": callable(_resolve_reasoning), "catalog_order": callable(_ranked_slugs),
        "catalog_account": callable(account_headers),
        "dispatch_hooks": {name: callable(getattr(AIAgent, name, None)) for name in
                           ("_build_api_kwargs", "_interruptible_api_call", "_interruptible_streaming_api_call")},
    }}, indent=2))
    print("Source presence only; verify live HEAD, middleware session contract, authentication and smoke flow before enabling timers.")


if __name__ == "__main__":
    main()
