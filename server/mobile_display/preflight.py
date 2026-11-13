"""Read source contracts only. Does not import/run an agent or modify an installation."""
import argparse
import ast
import json
from pathlib import Path


def inspect_contract(agent_root):
    root = Path(agent_root)
    missing = []
    files = {}
    for relative in ("hermes_cli/plugins.py", "hermes_cli/plugins_dispatch.py", "agent/system_prompt.py", "hermes_cli/plugins_context.py"):
        path = root / relative
        if not path.is_file():
            if relative == "hermes_cli/plugins_context.py":
                continue
            missing.append(relative)
            continue
        try:
            files[relative] = ast.parse(path.read_text(encoding="utf-8-sig"))
        except (OSError, UnicodeError, SyntaxError):
            missing.append(relative + ":unreadable")
    plugins = files.get("hermes_cli/plugins_context.py") or files.get("hermes_cli/plugins.py")
    context = next((n for n in ast.walk(plugins) if isinstance(n, ast.ClassDef) and n.name == "PluginContext"), None) if plugins else None
    methods = {n.name for n in context.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))} if context else set()
    missing.extend("PluginContext." + name for name in ("register_system_prompt_section", "register_hook") if name not in methods)
    dispatch = files.get("hermes_cli/plugins_dispatch.py")
    names = {n.name for n in ast.walk(dispatch) if isinstance(n, ast.FunctionDef)} if dispatch else set()
    missing.extend("PluginDispatchMixin." + name for name in ("render_system_prompt_sections", "invoke_hook") if name not in names)
    prompt = files.get("agent/system_prompt.py")
    if prompt and not any(isinstance(n, ast.Call) and
                          (n.func.attr if isinstance(n.func, ast.Attribute) else getattr(n.func, "id", "")) == "render_system_prompt_sections"
                          for n in ast.walk(prompt)):
        missing.append("agent.system_prompt:render_system_prompt_sections")
    return {"state": "ready" if not missing else "unsupported", "missing": missing,
            "client_surfaces": "register_client_surface" in methods,
            "display_mode": "surface" if "register_client_surface" in methods else "legacy_suffix",
            "scope": "source_contract_only", "installed": False, "model_called": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-root", required=True)
    result = inspect_contract(parser.parse_args().agent_root)
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["state"] == "ready" else 2)
