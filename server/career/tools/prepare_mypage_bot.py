"""Preflight by default; --install provisions a Hermes Bot, never starts a gateway."""
import argparse
import ast
import importlib
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path


def inspect_contract():
    required = {
        "cron.jobs": ("create_job", "get_job", "list_jobs", "use_cron_store"),
        "cron.scheduler_provider": ("_profile_cron_scope", "resolve_cron_scheduler"),
        "hermes_cli.profiles": ("create_profile", "get_profile_dir", "profile_exists"),
        "tools.browser_tool_session": ("_run_browser_command", "_get_session_info"),
        "croniter": ("croniter",),
    }
    missing = []
    runtime = importlib.util.find_spec("run_agent")
    if not runtime or not runtime.origin or not any(
            isinstance(node, ast.ClassDef) and node.name == "AIAgent"
            for node in ast.walk(ast.parse(Path(runtime.origin).read_text(encoding="utf-8")))):
        missing.append("run_agent.AIAgent")
    for module, names in required.items():
        try:
            value = importlib.import_module(module)
            missing.extend(module + "." + name for name in names if not callable(getattr(value, name, None)))
        except ImportError:
            missing.append(module)
    python = os.environ.get("CAREER_BROWSER_PYTHON")
    try:
        if not python or subprocess.run([python, "-c", "from playwright.sync_api import sync_playwright"],
            capture_output=True, timeout=15,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0).returncode:
            missing.append("CAREER_BROWSER_PYTHON:playwright")
    except (OSError, subprocess.TimeoutExpired):
        missing.append("CAREER_BROWSER_PYTHON:playwright")
    revision = None
    try:
        module = importlib.import_module("hermes_constants")
        result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=Path(module.__file__).parent,
            capture_output=True, text=True, timeout=10,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if result.returncode == 0:
            revision = result.stdout.strip()
    except (ImportError, OSError, subprocess.TimeoutExpired):
        pass
    return {"state": "ready" if not missing else "unsupported", "missing_contracts": missing,
            "python_version": sys.version.split()[0], "hermes_revision": revision,
            "readiness_scope": "callable_contracts_only", "browser_launch_verified": False,
            "live_login_verified": False, "scheduler_running_verified": False}


def install():
    from hermes_cli.profiles import create_profile, get_profile_dir, profile_exists
    import yaml
    name = "career-mypage"
    home = get_profile_dir(name) if profile_exists(name) else create_profile(name, no_alias=True, no_skills=True, description="マイページ確認Bot")
    config_path = Path(home) / "config.yaml"
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    config.update(timezone="Asia/Tokyo")
    config.setdefault("cron", {}).update(script_timeout_seconds=1080, max_parallel_jobs=1)
    config.setdefault("browser", {}).update(provider="local")
    # Register the same plugin in the Bot's own profile. No credential files copied.
    plugin_dir = Path(home) / "plugins" / "career"
    plugin_dir.parent.mkdir(exist_ok=True)
    source = Path(__file__).resolve().parents[1]
    if plugin_dir.exists() and plugin_dir.resolve() != source:
        raise RuntimeError("Existing Bot plugin path differs; leave it unchanged")
    if not plugin_dir.exists():
        plugin_dir.symlink_to(source, target_is_directory=True)
    config_path.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False), encoding="utf-8")
    config_path.chmod(0o600)
    return {"profile": name, "schedule": "09:00,18:00 Asia/Tokyo", "gateway_started": False,
            "next_step": "Verify the Hermes profile scheduler, existing Codex authentication and career-key credential access in its service context."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    result = inspect_contract()
    if args.install and result["state"] == "ready":
        result["installation"] = install()
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["state"] == "ready" else 1)
