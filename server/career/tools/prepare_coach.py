"""Inspect by default; --install adds the native profile while preserving Career data."""
import argparse
import ast
import importlib
import importlib.util
import inspect
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def hook_contract(sources):
    required = {"pre_llm_call": {"session_id", "task_id", "user_message"},
                "post_llm_call": {"session_id", "assistant_response"},
                "on_session_end": {"session_id", "completed", "interrupted"}}
    found = {key: set() for key in required}
    for source in sources:
        for node in ast.walk(ast.parse(source)):
            if not isinstance(node, ast.Call) or not node.args or not isinstance(node.args[0], ast.Constant):
                continue
            event = node.args[0].value
            name = node.func.attr if isinstance(node.func, ast.Attribute) else getattr(node.func, "id", "")
            if event in required and "hook" in name:
                found[event].update(arg.arg for arg in node.keywords if arg.arg)
    return [event + ":" + field for event, fields in required.items() for field in sorted(fields - found[event])]


def inspect_contract():
    missing, sources = [], []
    modules = {"hermes_cli.profiles": ("create_profile", "get_profile_dir", "profile_exists", "get_active_profile_name"),
               "tools.async_delegation": ("dispatch_async_delegation_batch", "get_durable_delegation"),
               "hermes_constants": ("get_hermes_home",)}
    loaded = {}
    for name, names in modules.items():
        try:
            module = importlib.import_module(name)
            loaded[name] = module
            missing.extend(name + "." + key for key in names if not callable(getattr(module, key, None)))
        except ImportError:
            missing.append(name)
    # Reading a module's source avoids run_agent import-time recovery/prewarm side effects.
    runtime = importlib.util.find_spec("run_agent")
    if runtime and runtime.origin:
        root = Path(runtime.origin).resolve().parent
        if not any(isinstance(node, ast.ClassDef) and node.name == "AIAgent"
                   for node in ast.walk(ast.parse(Path(runtime.origin).read_text(encoding="utf-8")))):
            missing.append("run_agent.AIAgent")
        files = [root / "run_agent.py", *(root / "agent").glob("*.py")]
        for file in files:
            if file.is_file():
                sources.append(file.read_text(encoding="utf-8"))
        missing.extend(hook_contract(sources))
        # Credential reuse must be the host's existing read-only fallback, not a new secret copy.
        try:
            auth = importlib.import_module("hermes_cli.auth")
            if not callable(getattr(auth, "_global_auth_file_path", None)):
                missing.append("shared_auth_read_only_fallback")
        except ImportError:
            missing.append("hermes_cli.auth")
    else:
        missing.append("run_agent")
    profiles = loaded.get("hermes_cli.profiles")
    if profiles and callable(getattr(profiles, "create_profile", None)):
        parameters = inspect.signature(profiles.create_profile).parameters
        missing.extend("create_profile:" + key for key in ("no_alias", "no_skills", "description") if key not in parameters)
    return {"state": "ready" if not missing else "unsupported", "missing_contracts": missing,
            "scope": "source_and_callable_contracts_only", "model_called": False, "installed": False}


def inspect_data(store):
    # Inspection never initializes or migrates the installed DB.
    import sqlite3
    if not store.is_file():
        return {"database_exists": False, "profiles": []}
    connection = sqlite3.connect(store.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        profiles = []
        if "coach_messages" in tables:
            profiles = [{"profile": row[0], "messages": row[1]} for row in connection.execute(
                "SELECT profile,COUNT(*) FROM coach_messages GROUP BY profile ORDER BY profile")]
        counts = {name: connection.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0]
                  for name in ("entities", "coach_threads", "coach_runs", "coach_messages", "coach_thread_context") if name in tables}
        return {"database_exists": True, "integrity": connection.execute("PRAGMA quick_check").fetchone()[0],
                "profiles": profiles, "counts": counts}
    finally:
        connection.close()


def install(data_directory, owner_profile, caller_profile="default", history_profiles=()):
    from career_core.native_coach import PROFILE
    from career_core.store import Store
    from career_core.coach_store import profile_key
    from hermes_cli.profiles import create_profile, get_profile_dir, profile_exists
    owner_profile, caller_profile = profile_key(owner_profile), profile_key(caller_profile)
    history_profiles = sorted({owner_profile, *(profile_key(value) for value in history_profiles)})
    directory = Path(data_directory).resolve()
    database = directory / "career.sqlite3"
    before = inspect_data(database)
    if not before["database_exists"] or before.get("integrity") != "ok":
        raise RuntimeError("existing_career_database_required")
    if not profile_exists(caller_profile):
        raise RuntimeError("caller_profile_missing")
    home = Path(get_profile_dir(PROFILE))
    store = Store(directory)
    with store.connect() as db:
        existing = store.meta(db, "coach_native_profiles", {}).get(PROFILE)
    if profile_exists(PROFILE):
        if (not existing or existing.get("owner") != owner_profile or Path(existing["home"]).resolve() != home.resolve()
                or not (home / "career-coach.json").is_file()):
            raise RuntimeError("existing_profile_not_owned_by_this_installer")
        if existing.get("history_profiles", [owner_profile]) != history_profiles or caller_profile not in existing.get("callers", []):
            raise RuntimeError("existing_binding_differs_review_required")
        return {"installed": True, "already_installed": True, "profile": PROFILE, "data": before}
    # Preserve a recoverable database snapshot before adding any native binding tables.
    import sqlite3
    stamp = __import__("datetime").datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup = directory / "backups" / ("native-coach-" + stamp + ".sqlite3")
    backup.parent.mkdir(parents=True, exist_ok=True)
    source = sqlite3.connect(database)
    target = sqlite3.connect(backup)
    try:
        source.backup(target)
    finally:
        source.close()
        target.close()
    backup.chmod(0o600)
    home = Path(create_profile(PROFILE, no_alias=True, no_skills=True, description="就活コーチ"))
    try:
        return configure_profile(home, store, before, backup, owner_profile, caller_profile, history_profiles)
    except BaseException:
        retained = Path(tempfile.mkdtemp(prefix=PROFILE + "-incomplete-", dir=home.parent))
        home.rename(retained / "profile")
        raise


def configure_profile(home, store, before, backup, owner_profile, caller_profile, history_profiles):
    import yaml
    from career_core.native_coach import NativeCoach, PROFILE
    from career_core.store import encoded
    from hermes_cli.platforms import PLATFORMS
    directory = store.directory
    config_file = home / "config.yaml"
    config = yaml.safe_load(config_file.read_text(encoding="utf-8")) or {}
    config["platform_toolsets"] = {platform: ["career_coach"] for platform in PLATFORMS}
    config["fallback_model"] = []
    config["compression"] = dict(config.get("compression") or {}, enabled=False)
    config["memory"] = dict(config.get("memory") or {}, enabled=False)
    plugins = config.setdefault("plugins", {})
    plugins["enabled"] = sorted(set(plugins.get("enabled", [])) | {"career"})
    # Prevent host migrations from auto-enabling tools beyond the specialist's fixed surface.
    config["known_plugin_toolsets"] = ["career_coach", "career"]
    config_file.write_text(yaml.safe_dump(config, allow_unicode=True, sort_keys=False), encoding="utf-8")
    config_file.chmod(0o600)
    profile_meta_file = home / "profile.yaml"
    profile_meta = yaml.safe_load(profile_meta_file.read_text(encoding="utf-8")) if profile_meta_file.is_file() else {}
    profile_meta = dict(profile_meta or {}, display_name="就活コーチ")
    profile_meta_file.write_text(yaml.safe_dump(profile_meta, allow_unicode=True, sort_keys=False), encoding="utf-8")
    plugin = home / "plugins" / "career"
    plugin.parent.mkdir(exist_ok=True)
    plugin.symlink_to(ROOT, target_is_directory=True)
    marker = {"data_directory": str(directory), "plugin_root": str(ROOT)}
    (home / "career-coach.json").write_text(encoded(marker) + "\n", encoding="utf-8")
    (home / "SOUL.md").write_text(
        "# 就活コーチ\n\nHermesの常設就活コーチとして、本人情報・訂正・経験・全相談履歴を確認して相談する。"
        "career_contextで現在の制約を読み、career_recallで関連する全資料と原文を探す。"
        "情報が見つからないと判断する前に目録・検索・続きを確認する。本人の事実と他Botの解釈を区別する。"
        "詳しい共通指示とSkillはCareerプラグインを使う。保存はCareerツールを通し、汎用ファイル操作は使わない。\n", encoding="utf-8")
    NativeCoach(store)
    after = inspect_data(store.path)
    if any(after["counts"].get(name) != count for name, count in before["counts"].items()):
        raise RuntimeError("legacy_row_count_changed")
    with store.transaction() as db:
        bindings = store.meta(db, "coach_native_profiles", {})
        bindings[PROFILE] = {"owner": owner_profile, "history_profiles": history_profiles,
                             "callers": sorted({caller_profile, owner_profile, PROFILE}), "home": str(home.resolve())}
        store.set_meta(db, "coach_native_profiles", bindings)
    return {"installed": True, "profile": PROFILE, "data": after, "backup": str(backup),
            "gateway_started": False, "credentials_copied": False, "live_response_verified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-directory", default=os.environ.get("CAREER_DATA_DIR", "/var/lib/hermes/career"))
    parser.add_argument("--owner-profile")
    parser.add_argument("--caller-profile", default="default")
    parser.add_argument("--history-profile", action="append", default=[])
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    result = inspect_contract()
    result["data"] = inspect_data(Path(args.data_directory) / "career.sqlite3")
    if args.install:
        if result["state"] != "ready" or not args.owner_profile:
            result["installation"] = "owner_profile_and_ready_host_required"
        else:
            result["installation"] = install(args.data_directory, args.owner_profile, args.caller_profile, args.history_profile)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["state"] == "ready" and (not args.install or isinstance(result.get("installation"), dict)) else 1


if __name__ == "__main__":
    raise SystemExit(main())
