"""Pinned runtime-argument repair. Keeps configuration and all conversation DBs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import stat
import time
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0 or socket.gethostname() != "hermes-node":
        raise SystemExit("Run with sudo on hermes-node")
    os.umask(0o077)
    helper = Path(__file__).resolve().with_name("install_server.py")
    if hashlib.sha256(helper.read_bytes()).hexdigest() != "c8a555d2ceda39245de70a84d7cc716dda87c4798359f99227ea8349123d4708":
        raise RuntimeError("Pinned installation helper differs")
    spec = importlib.util.spec_from_file_location("pinned_group_installer", helper)
    base = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(base)
    import fcntl
    import pwd
    lock = open("/run/lock/hermes-group-chat-install.lock", "a")
    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    for path in [base.BASE, base.HOME, base.HOME / "plugins", base.PLUGIN]:
        if not path.is_dir() or path.is_symlink() or path.resolve() != path:
            raise RuntimeError("Unexpected installed plugin path")
    if not base.active():
        raise RuntimeError("Dashboard is not active")
    previous = base.read_bundle(helper.with_name("group_chat-server-deploy-r2.zip").read_bytes(),
                                "481c5c1d1ac58910e0b0ebf0e4162534b6952a0e768988df1ad8772afa6b3f47")
    candidate = base.read_bundle(args.archive.read_bytes(), args.sha256)
    changed = {name for name in previous if candidate.get(name) != previous[name]}
    allowed = {"group_service.py", "tests/test_runtime.py", "README.md"}
    if changed != allowed:
        raise RuntimeError("Unexpected runtime patch scope")

    def verify_original():
        for name, value in previous.items():
            path = base.PLUGIN / name
            if path.is_symlink() or path.read_bytes() != value:
                raise RuntimeError("Installed plugin changed; nothing overwritten")

    verify_original()
    pid = int(base.call(["systemctl", "show", base.SERVICE, "-pMainPID", "--value"],
                        capture_output=True, text=True).stdout.strip())
    environ = dict(item.split(b"=", 1) for item in Path(f"/proc/{pid}/environ").read_bytes().split(b"\0") if b"=" in item)
    env = os.environ.copy()
    env.update({key.decode(): value.decode() for key, value in environ.items()})
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    account = pwd.getpwnam("hermes")

    def as_hermes(command, **kwargs):
        return base.call(command, user=account.pw_uid, group=account.pw_gid,
                         extra_groups=os.getgrouplist("hermes", account.pw_gid),
                         cwd=base.BASE / "work", env=env, **kwargs)

    tag = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    stage = base.BASE / ("group-chat-runtime-stage-" + tag)
    stage.mkdir(mode=0o700)
    for name, value in candidate.items():
        path = stage / name
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.write_bytes(value)
    for path in [stage, *stage.rglob("*")]:
        os.chown(path, account.pw_uid, account.pw_gid)
    checked = as_hermes([base.PYTHON, stage / "tools/probe_cli_args.py", base.CLI],
                        capture_output=True, text=True, timeout=45)
    cli_contract = json.loads(checked.stdout)
    if cli_contract != {"old_command_rejected": True, "corrected_command_parsed": True, "agent_execution": "not_requested"}:
        raise RuntimeError("CLI parser contract was not confirmed")
    tests = as_hermes([base.PYTHON, "-m", "unittest", "discover", "-s", stage / "tests", "-v"],
                      capture_output=True, text=True, timeout=60)
    if "OK" not in tests.stderr:
        raise RuntimeError("Host synthetic tests failed")
    idle_code = '''from hermes_cli.profiles import get_profile_dir,list_profile_names
import sqlite3
for name in list_profile_names():
 p=get_profile_dir(name)/'group_chat/history.db'
 if p.is_file():
  with sqlite3.connect(p.as_uri()+'?mode=ro',uri=True) as db:
   if db.execute("SELECT count(*) FROM runs WHERE state IN ('queued','running')").fetchone()[0]:
    raise RuntimeError('Group work is active; no restart')
print('GROUP_QUEUES_IDLE')
'''
    as_hermes([base.PYTHON, "-c", idle_code], capture_output=True, text=True)
    config = base.HOME / "config.yaml"
    config_hash = base.digest(config.read_bytes())
    outside = base.code_hashes()
    if not args.apply:
        print(json.dumps({"state": "ready", "cli_parser": cli_contract, "host_synthetic_tests": "passed"}))
        return
    backup = Path("/var/backups/hermes") / ("group-chat-runtime-" + tag)
    backup.mkdir(mode=0o700)
    for name in changed:
        saved = backup / name
        saved.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copy2(base.PLUGIN / name, saved)
    (backup / "synthetic-tests.txt").write_text(tests.stdout + tests.stderr, encoding="utf-8")

    def replace(name, content):
        target = base.PLUGIN / name
        metadata = target.stat()
        temporary = target.with_name(".runtime-patch-" + uuid.uuid4().hex)
        with temporary.open("xb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chown(temporary, metadata.st_uid, metadata.st_gid)
        os.chmod(temporary, stat.S_IMODE(metadata.st_mode))
        os.replace(temporary, target)

    replaced = []
    stopped = False
    try:
        if int(base.call(["systemctl", "show", base.SERVICE, "-pMainPID", "--value"], capture_output=True, text=True).stdout) != pid:
            raise RuntimeError("Dashboard changed concurrently; no patch applied")
        verify_original()
        stopped = True
        base.call(["systemctl", "stop", base.SERVICE])
        for name in sorted(changed):
            replace(name, candidate[name])
            replaced.append(name)
        base.call(["systemctl", "start", base.SERVICE])
        for _ in range(20):
            try:
                with socket.create_connection(("100.103.190.68", 9119), timeout=2):
                    if base.active():
                        break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise RuntimeError("Dashboard did not resume listening")
        if base.digest(config.read_bytes()) != config_hash or base.code_hashes() != outside:
            raise RuntimeError("Outside-scope changes detected")
    except BaseException:
        if stopped:
            try:
                if replaced:
                    base.call(["systemctl", "stop", base.SERVICE])
                    for name in replaced:
                        if (base.PLUGIN / name).read_bytes() != candidate[name]:
                            raise RuntimeError("Concurrent runtime edit; backup retained for review")
                        replace(name, previous[name])
            finally:
                base.call(["systemctl", "start", base.SERVICE])
        raise
    receipt = {"state": "installed", "revision": "runtime-r3", "backup": str(backup),
               "bundle_sha256": args.sha256, "cli_parser": cli_contract, "host_synthetic_tests": "passed",
               "service_active": base.active(), "configuration_unchanged": True,
               "other_plugin_code_unchanged": True, "conversation_databases_untouched": True,
               "failed_requests_replayed": False, "agent_execution": "not_requested"}
    (backup / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
