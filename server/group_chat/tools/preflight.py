"""Read-only production compatibility report. Does not run an agent or change services."""
import json
import os
import pwd
from pathlib import Path
import subprocess
import sys

SERVICE = "home-hermes-dashboard.service"
PYTHON = "/var/lib/hermes/venv/bin/python"


def run():
    if os.geteuid() != 0:
        raise SystemExit("Run with sudo on hermes-node. No password is accepted by this script.")
    values = subprocess.run(
        ["systemctl", "show", SERVICE, "-pMainPID", "-pActiveState", "-pUser"],
        capture_output=True, text=True, check=True,
    ).stdout
    service = dict(line.split("=", 1) for line in values.splitlines() if "=" in line)
    if service.get("ActiveState") != "active" or service.get("User") != "hermes":
        raise SystemExit("Expected active Dashboard running as hermes.")
    environ = dict(item.split(b"=", 1) for item in Path(f"/proc/{int(service['MainPID'])}/environ").read_bytes().split(b"\0") if b"=" in item)
    env = os.environ.copy()
    env.update({key.decode(): value.decode() for key, value in environ.items()})
    code = r'''
import importlib.util,inspect,json,os,pathlib,shutil,subprocess,ast
from hermes_cli import profiles
report={"python":str(pathlib.Path(__import__('sys').executable).resolve()),
        "hermes_home":os.environ.get("HERMES_HOME"),"cli":shutil.which("hermes"),
        "profile_functions":{name:callable(getattr(profiles,name,None)) for name in ["get_profile_dir","profile_exists","list_profile_names"]}}
report['profile_signatures']={name:str(inspect.signature(getattr(profiles,name))) for name in ['get_profile_dir','list_profile_names'] if callable(getattr(profiles,name,None))}
for module in ["hermes_cli.web_server_dashboard","hermes_cli.dashboard_auth.middleware","hermes_cli.main","hermes_cli.cli","hermes_cli.cli_single_query"]:
 try:
  spec=importlib.util.find_spec(module)
  if not spec or not spec.origin: continue
  text=pathlib.Path(spec.origin).read_text()
  report[module]={"path":spec.origin,"mount_api":"_mount_plugin_api_routes" in text,
                  "session_context":"request.state.session" in text,"quiet_flag":"--quiet" in text or '"-Q"' in text,
                  "profile_flag":"--profile" in text}
  if "_mount_plugin_api_routes" in text:
   lines=text.splitlines()
   for i,line in enumerate(lines):
    if line.startswith("def _mount_plugin_api_routes") or line.startswith("async def _mount_plugin_api_routes"):
     break
 except (ImportError,OSError,ValueError):
  report[module]={"available":False}
try:
 from hermes_cli.config import load_config
 cfg=load_config()
 plugins=cfg.get("plugins") or {}
 report["plugin_config"]={key:plugins.get(key) for key in ["enabled","disabled"]}
except Exception as error:
 report["config_error_type"]=type(error).__name__
home=pathlib.Path(os.environ.get("HERMES_HOME") or "/var/lib/hermes/.hermes")
report["group_plugin_exists"]=(home/"plugins/group_chat").exists()
source_root=pathlib.Path('/var/lib/hermes/app/hermes_cli')
report['plugin_discovery_source']={}
for path in set(source_root.glob('web_server*.py')) | set(source_root.rglob('*plugin*.py')):
 text=path.read_text()
 try: tree=ast.parse(text)
 except SyntaxError: continue
 for node in tree.body:
  if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)) and node.name in {'_get_dashboard_plugins','_plugin_api_mount_skip_reason'}:
   report['plugin_discovery_source'][str(path.relative_to(source_root))+':'+node.name]='\n'.join(text.splitlines()[node.lineno-1:min(node.end_lineno,node.lineno+149)])
print(json.dumps(report,ensure_ascii=False,indent=2))
'''
    # Use only the existing service user's environment; never print secret values.
    account = pwd.getpwnam("hermes")
    result = subprocess.run(
        [PYTHON, "-c", code],
        user=account.pw_uid, group=account.pw_gid,
        extra_groups=os.getgrouplist("hermes", account.pw_gid),
        cwd="/var/lib/hermes/work", env=env, capture_output=True, text=True,
    )
    if result.returncode:
        print(json.dumps({"state": "preflight_failed", "returncode": result.returncode}))
        raise SystemExit(1)
    report = json.loads(result.stdout)
    settings = subprocess.run(
        ["systemctl", "show", SERVICE, "-pStandardOutput", "-pStandardError"],
        capture_output=True, text=True, check=True,
    ).stdout
    report["service_log_destinations"] = settings.splitlines()
    journal = subprocess.run(
        ["journalctl", "-u", SERVICE, "--since", "30 minutes ago", "--no-pager", "-o", "cat"],
        capture_output=True, text=True, check=True,
    ).stdout
    needles = ["Failed to load plugin group_chat", "Mounted plugin API routes: /api/plugins/group_chat", "Plugin group_chat", "plugin 'group_chat'", "group_chat/dashboard/manifest"]
    report["plugin_loader_journal"] = [line for line in journal.splitlines() if any(n in line for n in needles)][-20:]
    for line in settings.splitlines():
        value = line.split("=", 1)[-1]
        if value.startswith(("append:", "file:", "truncate:")):
            log_path = Path(value.split(":", 1)[1])
            if log_path.is_file():
                with log_path.open("rb") as handle:
                    handle.seek(max(0, log_path.stat().st_size - 200000))
                    text = handle.read().decode("utf-8", errors="replace")
                report["plugin_loader_file"] = [row for row in text.splitlines() if any(n in row for n in needles)][-20:]
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
