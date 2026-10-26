"""Read-only, metadata-only probe for this private Hermes host. No login or model run."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

BASE = Path("/var/lib/hermes")
PYTHON = BASE / "venv/bin/python"
SERVICE = "home-hermes-dashboard.service"

PROBE = r'''
import hashlib, importlib.metadata, importlib.util, json, pathlib, shutil
from hermes_constants import get_hermes_home
report = {"packages": {}}
for name in ("cryptography", "playwright", "httpx", "fastapi", "hermes-agent"):
    try: report["packages"][name] = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError: report["packages"][name] = None
report["chromium"] = shutil.which("chromium") or shutil.which("chromium-browser")
home = get_hermes_home()
script = home / 'skills/productivity/google-workspace/scripts/google_api.py'
report['hermes_home'] = str(home)
report['google_workspace'] = {
    'wrapper_present': script.is_file(),
    'wrapper_sha256': hashlib.sha256(script.read_bytes()).hexdigest() if script.is_file() else None,
    'gws_in_path': shutil.which('gws') is not None,
    'credential_files': [str(home / name) for name in ('google_token.json', 'google_client_secret.json') if (home / name).is_file()],
    'authentication': 'not_tested',
}
try:
    import yaml
    plugin = home / 'plugins/career/plugin.yaml'
    report["career_plugin_version"] = (yaml.safe_load(plugin.read_text(encoding='utf-8')) or {}).get('version')
except Exception:
    report["configuration_metadata"] = "unavailable"
print(json.dumps(report))
'''


def main():
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        print("Run with sudo on hermes-node. This probe only reads metadata.", file=sys.stderr)
        return 1
    # Use the running service's home selection; do not guess or change it.
    pid = subprocess.run(["systemctl", "show", SERVICE, "-p", "MainPID", "--value"],
        capture_output=True, text=True, check=True).stdout.strip()
    if not pid.isdigit() or pid == "0":
        print("Dashboard PID is unavailable; no configuration was changed.")
        return 1
    service_environment = {}
    for item in Path("/proc", pid, "environ").read_bytes().split(b"\0"):
        name, separator, value = item.partition(b"=")
        if separator and name in {b"HOME", b"HERMES_HOME", b"PATH", b"HERMES_GWS_BIN"}:
            service_environment[name.decode()] = value.decode()
    arguments = [key + "=" + value for key, value in service_environment.items()]
    result = subprocess.run(["runuser", "-u", "hermes", "--", "env", "-u", "HERMES_HOME", *arguments,
        "PYTHONDONTWRITEBYTECODE=1", str(PYTHON), "-I", "-c", PROBE],
        capture_output=True, text=True, timeout=20)
    if result.returncode:
        print("Service Python metadata could not be read. No raw error or credentials were printed.")
        return 1
    report = json.loads(result.stdout)
    locations = []
    for directory in (BASE, *[p for p in BASE.iterdir() if p.is_dir() and not p.is_symlink()]):
        for name in ('google_token.json', 'google_client_secret.json'):
            path = directory / name
            if path.is_file():
                locations.append(str(path))
    report['google_credential_locations_maxdepth2'] = sorted(locations)
    service = subprocess.run(["systemctl", "show", SERVICE, "-p", "ActiveState", "--value"], capture_output=True, text=True, check=True)
    report["dashboard_active"] = service.stdout.strip() == "active"
    report["systemd_creds_present"] = shutil.which("systemd-creds") is not None
    report["encrypted_career_key_present"] = Path("/etc/credstore.encrypted/hermes-career-key").is_file()
    report["private_credentials_directory_present"] = (BASE / "career-private").is_dir()
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
