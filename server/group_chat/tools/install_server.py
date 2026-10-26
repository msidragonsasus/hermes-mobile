"""Pinned, backed-up first installation on the existing Hermes Dashboard host."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import socket
import stat
import subprocess
import time
import uuid
import zipfile

SERVICE = "home-hermes-dashboard.service"
BASE = Path("/var/lib/hermes")
HOME = BASE / ".hermes"
PLUGIN = HOME / "plugins/group_chat"
PYTHON = BASE / "venv/bin/python"
CLI = BASE / "venv/bin/hermes"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_bundle(data, expected):
    if digest(data) != expected:
        raise RuntimeError("Bundle SHA256 mismatch; nothing installed")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("Duplicate archive names")
        release = json.loads(archive.read("RELEASE.json"))
        if release.get("version") != "0.1.0" or set(names) != set(release["sha256"]) | {"RELEASE.json"}:
            raise RuntimeError("Unexpected release manifest")
        files = {}
        for name, expected_hash in release["sha256"].items():
            relative = PurePosixPath(name)
            info = archive.getinfo(name)
            if (relative.is_absolute() or ".." in relative.parts or "\\" in name
                    or not name.startswith("group_chat/") or stat.S_ISLNK(info.external_attr >> 16)
                    or info.file_size > 1_000_000 or relative.suffix not in {".py", ".json", ".md", ".yaml"}):
                raise RuntimeError("Unexpected archive member")
            value = archive.read(name)
            if digest(value) != expected_hash:
                raise RuntimeError("Archive member hash mismatch")
            files[str(relative.relative_to("group_chat"))] = value
        if not {"group_service.py", "group_api.py", "dashboard/plugin_api.py", "dashboard/manifest.json", "plugin.yaml"} <= set(files):
            raise RuntimeError("Incomplete plugin bundle")
        return files


def call(args, **kwargs):
    return subprocess.run([str(value) for value in args], check=True, **kwargs)


def active():
    return subprocess.run(["systemctl", "is-active", "--quiet", SERVICE]).returncode == 0


def code_hashes():
    return {str(p.relative_to(HOME / "plugins")): digest(p.read_bytes())
            for p in (HOME / "plugins").rglob("*")
            if p.is_file() and p.suffix in {".py", ".json", ".yaml"}
            and PLUGIN not in p.parents and "__pycache__" not in p.parts}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0 or socket.gethostname() != "hermes-node":
        raise SystemExit("Run with sudo on hermes-node")
    os.umask(0o077)
    import fcntl
    import pwd
    lock = open("/run/lock/hermes-group-chat-install.lock", "a")
    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    for path in [BASE, HOME, HOME / "plugins", HOME / "config.yaml"]:
        if not path.exists() or path.is_symlink() or path.resolve() != path:
            raise RuntimeError("Unexpected production path; nothing installed")
    if PLUGIN.exists() or PLUGIN.is_symlink() or not active():
        raise RuntimeError("Expected first install and active Dashboard; nothing installed")
    files = read_bundle(args.archive.read_bytes(), args.sha256)
    pid = int(call(["systemctl", "show", SERVICE, "-pMainPID", "--value"], capture_output=True, text=True).stdout.strip())
    environ = dict(item.split(b"=", 1) for item in Path(f"/proc/{pid}/environ").read_bytes().split(b"\0") if b"=" in item)
    env = os.environ.copy()
    env.update({key.decode(): value.decode() for key, value in environ.items()})
    account = pwd.getpwnam("hermes")

    def as_hermes(command, **kwargs):
        return call(command, user=account.pw_uid, group=account.pw_gid,
                    extra_groups=os.getgrouplist("hermes", account.pw_gid),
                    cwd=BASE / "work", env=env, **kwargs)

    help_text = as_hermes([CLI, "--profile", "default", "chat", "--help"],
                          capture_output=True, text=True, timeout=45).stdout
    if "-Q" not in help_text:
        raise RuntimeError("Host CLI quiet-query contract unavailable; nothing installed")
    tag = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    stage = BASE / ("group-chat-code-stage-" + tag)
    stage.mkdir(mode=0o700)
    for name, data in files.items():
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        target.write_bytes(data)
    for path in [stage, *stage.rglob("*")]:
        os.chown(path, account.pw_uid, account.pw_gid)
    test_result = as_hermes([PYTHON, "-m", "unittest", "discover", "-s", stage / "tests", "-v"],
                            capture_output=True, text=True, timeout=60)
    if "OK" not in test_result.stderr:
        raise RuntimeError("Host synthetic tests did not complete; nothing installed")
    router_code = r'''
import sys
sys.path.insert(0,sys.argv[1])
from group_api import create_router
router=create_router(factory=lambda profile: None)
paths={route.path for route in router.routes}
assert {'/conversations','/conversations/{conversation}','/conversations/{conversation}/messages','/conversations/{conversation}/stop'} <= paths
print('ROUTER_IMPORT_OK')
'''
    router_result = as_hermes([PYTHON, "-c", router_code, stage], capture_output=True, text=True)
    if "ROUTER_IMPORT_OK" not in router_result.stdout:
        raise RuntimeError("Host API router import failed; nothing installed")
    config = HOME / "config.yaml"
    old_config = config.read_bytes()
    config_metadata = config.stat()
    # The parsed values of every other setting must stay identical. Never print YAML.
    candidate_code = r'''
import json,pathlib,hashlib,yaml
p=pathlib.Path('/var/lib/hermes/.hermes/config.yaml')
raw=p.read_bytes(); original=yaml.safe_load(raw) or {}
import copy
updated=copy.deepcopy(original)
plugins=updated.setdefault('plugins',{})
enabled=plugins.setdefault('enabled',[])
disabled=plugins.get('disabled') or []
assert isinstance(enabled,list) and isinstance(disabled,list)
assert 'group_chat' not in disabled and 'group_chat' not in enabled
enabled.append('group_chat')
new_yaml=yaml.safe_dump(updated,allow_unicode=True,sort_keys=False)
loaded=yaml.safe_load(new_yaml)
verify=copy.deepcopy(loaded); verify['plugins']['enabled'].remove('group_chat')
if 'plugins' not in original: verify.pop('plugins')
elif 'enabled' not in original['plugins']: verify['plugins'].pop('enabled')
assert verify==original
print(json.dumps({'old_hash':hashlib.sha256(raw).hexdigest(),'yaml':new_yaml}))
'''
    candidate = json.loads(as_hermes([PYTHON, "-c", candidate_code], capture_output=True, text=True).stdout)
    if candidate["old_hash"] != digest(old_config):
        raise RuntimeError("Configuration changed during preparation; nothing installed")
    new_config = candidate["yaml"].encode("utf-8")
    outside = code_hashes()
    if not args.apply:
        print(json.dumps({"state": "ready", "host_cli_help": "passed", "host_synthetic_tests": "passed",
                          "configuration_preserved": True, "stage": str(stage)}))
        return
    backups = Path("/var/backups/hermes")
    if backups.is_symlink():
        raise RuntimeError("Unexpected backup location")
    backups.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = backups / ("group-chat-" + tag)
    backup.mkdir(mode=0o700)
    shutil.copy2(config, backup / "config.yaml")
    shutil.copy2("/etc/systemd/system/" + SERVICE, backup / SERVICE)
    dropins = Path("/etc/systemd/system/" + SERVICE + ".d")
    if dropins.is_dir():
        shutil.copytree(dropins, backup / "dashboard-dropins")
    (backup / "synthetic-tests.txt").write_text(test_result.stdout + test_result.stderr, encoding="utf-8")
    (backup / "outside-code-hashes.json").write_text(json.dumps(outside, indent=2), encoding="utf-8")
    if config.read_bytes() != old_config:
        raise RuntimeError("Configuration changed before installation; nothing installed")

    def replace_config(data):
        temporary = HOME / (".group-chat-config-" + uuid.uuid4().hex + ".yaml")
        with temporary.open("xb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chown(temporary, config_metadata.st_uid, config_metadata.st_gid)
        os.chmod(temporary, stat.S_IMODE(config_metadata.st_mode))
        os.replace(temporary, config)

    swapped = False
    try:
        call(["systemctl", "stop", SERVICE])
        stage.rename(PLUGIN)
        swapped = True
        replace_config(new_config)
        call(["systemctl", "start", SERVICE])
        listening = False
        for _ in range(20):
            try:
                with socket.create_connection(("100.103.190.68", 9119), timeout=2):
                    listening = True
            except OSError:
                listening = False
            if active() and listening:
                break
            time.sleep(1)
        if not active() or not listening:
            raise RuntimeError("Dashboard did not resume listening")
        if outside != code_hashes():
            raise RuntimeError("Outside-scope plugin code changed")
    except BaseException:
        call(["systemctl", "stop", SERVICE])
        if swapped and PLUGIN.exists():
            PLUGIN.rename(backup / "group-chat-failed")
        if config.read_bytes() in [new_config, old_config]:
            replace_config(old_config)
        else:
            print("Configuration changed concurrently; retained for operator review.", flush=True)
        call(["systemctl", "start", SERVICE])
        print("INSTALL_FAILED: new code removed from loading; existing data retained.", flush=True)
        raise
    receipt = {"state": "installed", "version": "0.1.0", "backup": str(backup),
               "bundle_sha256": args.sha256, "service_active": active(), "server_listening": listening,
               "route_mount_confirmed": False, "host_api_router_import": "passed",
               "host_cli_help": "passed", "host_synthetic_tests": "passed",
               "outside_plugin_code_unchanged": True, "other_configuration_values_preserved": True,
               "agent_execution": "not_requested", "authenticated_phone_check": "pending"}
    (backup / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
