"""One-time, backed-up code update for this user's existing Hermes service.

Run on hermes-node: sudo /usr/bin/python3 -I /home/keiya/career-coach-update/update-career-server-0.4.0.py
No credentials, config edits, dependency installs, timers, or model requests.
"""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import pwd
import shutil
import socket
import sqlite3
import stat
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

EXPECTED = ""
VERSION = "0.4.0"
ARCHIVE = Path(__file__).resolve().parent / "hermes-career-coach-server-0.4.0.zip"
BASE = Path("/var/lib/hermes")
PLUGIN = BASE / ".hermes/plugins/career"
PYTHON = BASE / "venv/bin/python"
SERVICE = "home-hermes-dashboard.service"
VERIFICATION = Path("/run/hermes-career-update")


def call(args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def active(name):
    value = call(["systemctl", "show", name, "-p", "ActiveState", "--value"], capture_output=True).stdout.strip()
    return value in ("active", "activating", "deactivating", "reloading")


def running(name):
    return call(["systemctl", "show", name, "-p", "ActiveState", "--value"], capture_output=True).stdout.strip() == "active"


def as_hermes(code, *args):
    call(["runuser", "-u", "hermes", "--", "env", "HOME=" + str(BASE),
          "HERMES_HOME=" + str(BASE / ".hermes"), "PYTHONDONTWRITEBYTECODE=1",
          str(PYTHON), "-I", "-c", code, *map(str, args)], cwd=BASE / "work")


def process_start(pid):
    return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def process_store_directory(pid, starttime):
    if process_start(pid) != starttime:
        raise RuntimeError("Dashboard process changed; no services changed")
    try:
        with Path(f"/proc/{pid}/environ").open("rb") as handle:
            environment = handle.read(2 * 1024 * 1024 + 1)
        if len(environment) > 2 * 1024 * 1024 or (environment and not environment.endswith(b"\0")):
            raise ValueError()
        values = [entry for entry in environment.split(b"\0") if entry == b"CAREER_DATA_DIR" or entry.startswith(b"CAREER_DATA_DIR=")]
        if len(values) > 1 or (values and b"=" not in values[0]):
            raise ValueError()
        configured = values[0].split(b"=", 1)[1].decode("utf-8") if values else "/var/lib/hermes/career"
        current = Path(os.readlink(f"/proc/{pid}/cwd"))
        if not current.is_absolute() or not current.is_dir():
            raise ValueError()
        directory = Path(configured)
        directory = (directory if directory.is_absolute() else current / directory).resolve(strict=True)
        if not directory.is_dir() or process_start(pid) != starttime:
            raise ValueError()
        return directory
    except (OSError, UnicodeError, ValueError):
        raise RuntimeError("Cannot verify the running Career data directory; no services changed") from None


def running_store():
    pid = int(call(["systemctl", "show", SERVICE, "-p", "MainPID", "--value"], capture_output=True).stdout.strip())
    if pid <= 0:
        raise RuntimeError("Dashboard process metadata unavailable; no services changed")
    starttime = process_start(pid)
    directory = process_store_directory(pid, starttime)
    current_pid = int(call(["systemctl", "show", SERVICE, "-p", "MainPID", "--value"], capture_output=True).stdout.strip())
    if current_pid != pid or process_start(pid) != starttime:
        raise RuntimeError("Dashboard process changed; no services changed")
    return pid, starttime, directory


class MountProof:
    def __init__(self, files, user, store):
        self.created = False
        self.listener = None
        self.receipt = None
        self.uid = user.pw_uid
        self.previous = store[:2]
        self.data_directory = str(store[2])
        hashes = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
        self.release = hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.nonce = uuid.uuid4().hex + uuid.uuid4().hex
        descriptor = os.open(VERIFICATION.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for component in VERIFICATION.parts[1:-1]:
                info = os.fstat(descriptor)
                if info.st_uid != 0 or info.st_mode & 0o022:
                    raise RuntimeError("Untrusted verification parent")
                child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            info = os.fstat(descriptor)
            if info.st_uid != 0 or info.st_mode & 0o022:
                raise RuntimeError("Untrusted verification parent")
            os.mkdir(VERIFICATION.name, 0o710, dir_fd=descriptor)
            self.created = True
            child = os.open(VERIFICATION.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            os.fchown(descriptor, 0, user.pw_gid)
            os.fchmod(descriptor, 0o710)
            request = {"protocol": 1, "nonce": self.nonce, "release": self.release, "sha256": hashes, "data_directory": self.data_directory}
            source = os.open("request.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o640, dir_fd=descriptor)
            with os.fdopen(source, "w", encoding="utf-8") as handle:
                os.fchown(handle.fileno(), 0, user.pw_gid)
                os.fchmod(handle.fileno(), 0o640)
                json.dump(request, handle, sort_keys=True)
            self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.listener.bind(str(VERIFICATION / "receipt.sock"))
            os.chown(VERIFICATION / "receipt.sock", 0, user.pw_gid, follow_symlinks=False)
            os.chmod(VERIFICATION / "receipt.sock", 0o620, follow_symlinks=False)
            self.listener.listen(4)
            self.listener.settimeout(0.1)
        except BaseException:
            self.close()
            raise
        finally:
            os.close(descriptor)

    def confirmed(self):
        try:
            pid = int(call(["systemctl", "show", SERVICE, "-p", "MainPID", "--value"], capture_output=True).stdout.strip())
            if pid <= 0:
                return False
            starttime = process_start(pid)
            if (pid, starttime) == self.previous:
                return False
            expected = {"protocol": 1, "nonce": self.nonce, "release": self.release, "pid": pid, "starttime": starttime, "data_directory": self.data_directory}
            if self.receipt == expected:
                return True
            with self.listener.accept()[0] as connection:
                connection.settimeout(0.2)
                peer_pid, peer_uid, peer_gid = struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))
                payload = bytearray()
                while len(payload) <= 4096:
                    chunk = connection.recv(4097 - len(payload))
                    if not chunk:
                        break
                    payload.extend(chunk)
                if peer_pid != pid or peer_uid != self.uid or len(payload) > 4096:
                    return False
                receipt = json.loads(payload)
                if type(receipt.get("pid")) is not int or type(receipt.get("protocol")) is not int or receipt != expected:
                    return False
                self.receipt = receipt
                return process_start(pid) == starttime
        except (OSError, ValueError, TypeError, AttributeError):
            return False

    def directory_matches(self):
        try:
            pid, starttime, directory = running_store()
            return (pid, starttime) != self.previous and str(directory) == self.data_directory
        except (OSError, RuntimeError, ValueError):
            return False

    def close(self):
        if self.listener is not None:
            self.listener.close()
        if self.created:
            for name in ("receipt.sock", "request.json"):
                try:
                    (VERIFICATION / name).unlink()
                except FileNotFoundError:
                    pass
            VERIFICATION.rmdir()
            self.created = False


def code_identity(root):
    result = {}
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if "__pycache__" in relative.parts and path.suffix in {".pyc", ".pyo"}:
            continue
        if path.is_symlink() or not (path.is_dir() or path.is_file()):
            raise RuntimeError("Unexpected plugin member")
        if path.is_file():
            result[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def database_identity(database):
    if database.is_symlink():
        raise RuntimeError("Unexpected database member")
    if not database.exists():
        return None
    info = database.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise RuntimeError("Unexpected database member")
    digest = hashlib.sha256()
    with contextlib.closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.execute("BEGIN")
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("Database integrity check failed")
        schema = connection.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name,tbl_name,sql").fetchall()
        digest.update(repr(schema).encode())
        for pragma in ("user_version", "application_id", "encoding"):
            digest.update(repr(connection.execute("PRAGMA " + pragma).fetchall()).encode())
        for name, in connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
            escaped = '"' + name.replace('"', '""') + '"'
            digest.update(name.encode())
            columns = {row[1].casefold() for row in connection.execute("PRAGMA table_xinfo(" + escaped + ")")}
            rowid = next((value for value in ("rowid", "_rowid_", "oid") if value not in columns), None)
            if rowid is None:
                raise RuntimeError("Database row identity cannot be verified")
            try:
                cursor = connection.execute("SELECT " + rowid + ",* FROM " + escaped)
            except sqlite3.OperationalError:
                sql = next(row[3] or "" for row in schema if row[0] == "table" and row[1] == name)
                if "WITHOUT ROWID" not in sql.upper():
                    raise
                cursor = connection.execute("SELECT * FROM " + escaped)
            rows = sorted(hashlib.sha256(repr(tuple(row)).encode()).digest() for row in cursor)
            digest.update(str(len(rows)).encode())
            for row in rows:
                digest.update(row)
    return digest.hexdigest()


def backup_database(database, destination):
    if database.is_symlink():
        raise RuntimeError("Unexpected database backup path")
    if not database.exists():
        return None
    if destination.exists() or destination.is_symlink():
        raise RuntimeError("Unexpected database backup path")
    with contextlib.closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as source:
        with contextlib.closing(sqlite3.connect(destination)) as target:
            source.backup(target)
    return database_identity(destination)


PROBE_MOUNT = r'''
import importlib.util, pathlib, runpy, sys
root = pathlib.Path(sys.argv[1])
helper = runpy.run_path(str(root / 'career_core/deployment.py'))
server = importlib.util.find_spec('hermes_cli.web_server')
dashboard = importlib.util.find_spec('hermes_cli.web_server_dashboard')
assert server and server.origin and dashboard and dashboard.origin, 'Dashboard source unavailable'
helper['check_host_contract'](pathlib.Path(server.origin).read_text(encoding='utf-8'), pathlib.Path(dashboard.origin).read_text(encoding='utf-8'))
helper['check_lifespan_contract']()
print('MOUNT_CONTRACT_OK: static installed loader and inert FastAPI lifecycle; no host app or jobs started')
'''


PROBE_RUNTIME = r'''
import importlib.util, inspect, pathlib
import yaml
from run_agent import AIAgent
from hermes_cli.plugins import PluginContext
config = yaml.safe_load(pathlib.Path('/var/lib/hermes/.hermes/config.yaml').read_text(encoding='utf-8')) or {}
plugins = config.get('plugins') or {}
assert 'career' in plugins.get('enabled', []) and 'career' not in plugins.get('disabled', []), 'career config is not enabled'
assert hasattr(PluginContext, 'register_skill') and hasattr(PluginContext, 'register_tool'), 'PluginContext contract missing'
required = {'session_id', 'enabled_toolsets', 'skip_background_review', 'ephemeral_system_prompt', 'fallback_model', 'reasoning_config', 'api_mode'}
assert required <= set(inspect.signature(AIAgent.__init__).parameters), 'AIAgent constructor incompatible'
assert {'conversation_history', 'task_id'} <= set(inspect.signature(AIAgent.run_conversation).parameters), 'AIAgent turn incompatible'
assert callable(getattr(AIAgent, 'interrupt', None)) and callable(getattr(AIAgent, 'close', None)), 'AIAgent lifecycle incompatible'
spec = importlib.util.find_spec('hermes_cli.web_server_dashboard')
assert spec and '_mount_plugin_api_routes' in pathlib.Path(spec.origin).read_text(encoding='utf-8'), 'Dashboard mount contract missing'
from agent.transports.codex import _resolve_reasoning
from hermes_cli.codex_models import _ranked_slugs
try:
    from agent.codex_headers import codex_account_headers as account_headers
except ImportError:
    from hermes_cli.codex_models import _extract_chatgpt_account_id as account_headers
from agent.reasoning_effort import codex_supported_efforts
assert all(callable(item) for item in (_resolve_reasoning, _ranked_slugs, account_headers, codex_supported_efforts)), 'Execution contract missing'
for name in ('_build_api_kwargs', '_interruptible_api_call', '_interruptible_streaming_api_call'):
    assert callable(getattr(AIAgent, name, None)), 'Dispatch hook missing: ' + name
print('RUNTIME_OK: real Hermes execution contracts; no agent run')
print('Hermes source:', spec.origin)
'''

# Importing Hermes can discover the installed plugin and cache career_core.
# Check staged routes in a separate process, before any Hermes import, so this
# verifies the candidate rather than a mixture of installed and staged code.
PROBE_ROUTES = r'''
import pathlib, runpy, sys
root = pathlib.Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root))
router = runpy.run_path(str(root / 'dashboard/plugin_api.py'))['router']
import career_core.api
assert pathlib.Path(career_core.api.__file__).resolve() == root / 'career_core/api.py', 'API was not loaded from the candidate'
paths = {route.path for route in router.routes}
assert {'/home', '/home/content'} <= paths, 'Home routes missing'
assert {'/snapshot', '/coach/conversation', '/coach/events', '/coach/capabilities', '/coach/daily-settings', '/coach/{action}', '/coach/thread/{action}', '/companies', '/companies/{company_id}', '/collection/notices', '/collection/client-mail', '/collection/{action}'} <= paths, 'Coach routes missing'
print('ROUTES_OK: candidate coach routes; startup and agent not invoked')
'''


def main():
    if os.geteuid() != 0:
        raise SystemExit("Run this script with sudo in the server terminal.")
    os.umask(0o077)
    for path in (BASE, BASE / ".hermes", PLUGIN.parent, PLUGIN, BASE / "career"):
        if not path.is_dir() or path.is_symlink() or path.resolve() != path:
            raise RuntimeError("Unexpected directory: " + str(path))
    if not active(SERVICE):
        raise RuntimeError("Dashboard is not running; no changes made")
    store = running_store()
    for name in ("research", "sync"):
        for suffix in ("timer", "service"):
            if active("hermes-career-" + name + "." + suffix):
                raise RuntimeError("Career background job/timer is active; no changes made")
    archive_path = ARCHIVE
    payload = archive_path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != EXPECTED:
        raise RuntimeError("Archive SHA256 mismatch; no changes made")
    files = {}
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        release = json.loads(archive.read("RELEASE.json"))
        assert VERSION in {"0.4.0", "0.5.0"} and release["version"] == VERSION
        assert set(archive.namelist()) == set(release["sha256"]) | {"RELEASE.json"}
        for name, digest in release["sha256"].items():
            relative = PurePosixPath(name)
            assert not relative.is_absolute() and ".." not in relative.parts and "\\" not in name
            data = archive.read(name)
            assert hashlib.sha256(data).hexdigest() == digest
            if name.startswith("server/career/"):
                files[name[len("server/career/"):]] = data
    if not {"career_core/deployment.py", "career_core/api.py", "dashboard/plugin_api.py"} <= set(files):
        raise RuntimeError("Release lacks the mounted-startup verification contract")
    runtime = BASE / "career/runtime.env"
    if not runtime.is_file() or runtime.is_symlink():
        raise RuntimeError("Existing runtime.env missing or unexpected")
    runtime_hash = hashlib.sha256(runtime.read_bytes()).digest()
    uid = pwd.getpwnam("hermes")
    tag = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
    stage = BASE / ("career-code-stage-" + tag)
    stage.mkdir(mode=0o700)
    for name, data in files.items():
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        target.write_bytes(data)
    for path in [stage, *stage.rglob("*")]:
        os.chown(path, uid.pw_uid, uid.pw_gid)
    # Import only: startup callbacks and model/GitHub operations are not invoked.
    as_hermes(PROBE_MOUNT, stage)
    as_hermes(PROBE_RUNTIME)
    as_hermes(PROBE_ROUTES, stage)
    backup_root = BASE / "career-update-backups"
    if backup_root.exists() and (backup_root.is_symlink() or backup_root.resolve() != backup_root):
        raise RuntimeError("Unexpected backup location")
    backup_root.mkdir(mode=0o700, exist_ok=True)
    os.chmod(backup_root, 0o700)
    backup = backup_root / tag
    backup.mkdir(mode=0o700)
    print("Backup directory:", backup, flush=True)
    for label, source in (("config.yaml", BASE / ".hermes/config.yaml"),
                          ("runtime.env", runtime),
                          ("dashboard.service", Path("/etc/systemd/system") / SERVICE),
                          ("dashboard-dropins", Path("/etc/systemd/system") / (SERVICE + ".d"))):
        if source.is_dir():
            shutil.copytree(source, backup / label)
        elif source.is_file():
            shutil.copy2(source, backup / label)
    stopped = False
    swapped = False
    stop_confirmed = False
    database = store[2] / "career.sqlite3"
    database_before = object()
    old_code = code_identity(PLUGIN)
    candidate_code = code_identity(stage)
    proof = MountProof(files, uid, store)
    try:
        if running_store() != store or hashlib.sha256(runtime.read_bytes()).digest() != runtime_hash:
            raise RuntimeError("Career runtime changed; no services changed")
        stopped = True
        call(["systemctl", "stop", SERVICE])
        stop_confirmed = True
        database_before = backup_database(database, backup / "career.sqlite3")
        # Keep the complete old code, including any local additions. No deletes.
        PLUGIN.rename(backup / "career-plugin-old")
        swapped = True
        stage.rename(PLUGIN)
        call(["systemctl", "start", SERVICE])
        ready = False
        for _ in range(30):
            try:
                with urllib.request.urlopen("http://100.103.190.68:9119/api/plugins/career/coach/conversation", timeout=2) as response:
                    status = response.status
            except urllib.error.HTTPError as error:
                status = error.code
            except (urllib.error.URLError, TimeoutError, OSError):
                status = 0
            if status == 401 and proof.confirmed() and running(SERVICE) and proof.confirmed():
                ready = True
                break
            time.sleep(1)
        if not ready:
            raise RuntimeError("Dashboard did not become ready")
    except Exception:
        candidate_directory_matches = not swapped or proof.directory_matches()
        if swapped:
            try:
                call(["systemctl", "stop", SERVICE])
            except Exception:
                print("RECOVERY_REQUIRED: candidate stop failed; current code and database retained; no rollback or restart attempted.", flush=True)
                raise
            stop_confirmed = True
        if not stop_confirmed:
            print("RECOVERY_REQUIRED: service stop was not confirmed; no rollback or restart attempted.", flush=True)
            raise
        try:
            database_after = backup_database(database, backup / "career-post-failure.sqlite3")
            old_path = backup / "career-plugin-old" if swapped else PLUGIN
            compatible = candidate_directory_matches and database_before == database_after and code_identity(old_path) == old_code
            compatible = compatible and hashlib.sha256(runtime.read_bytes()).digest() == runtime_hash
            if swapped:
                compatible = compatible and code_identity(PLUGIN) == candidate_code
        except Exception:
            compatible = False
        if not compatible:
            print("RECOVERY_REQUIRED: current database and both code versions retained; Dashboard remains stopped for operator review.", flush=True)
            raise
        if swapped:
            if PLUGIN.exists():
                PLUGIN.rename(backup / "career-plugin-failed")
            (backup / "career-plugin-old").rename(PLUGIN)
        if stopped:
            proof.close()
            call(["systemctl", "start", SERVICE])
        print("Update failed; old code restored where swapped. Database retained.", flush=True)
        raise
    finally:
        proof.close()
    print(f"UPDATE_OK: career {VERSION} installed; Career router mounted and startup callbacks completed in the new Dashboard process; unauthenticated HTTP 401.")
    print("Still required: verify existing Google Workspace access, public official-page research, scheduled research, device notification and versioned ES. No live collection was claimed by this update.")
    print("HTTP 401 alone does not prove authenticated routes or agent execution work.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--version", choices=("0.4.0", "0.5.0"), default="0.4.0")
    args = parser.parse_args()
    ARCHIVE, EXPECTED, VERSION = args.archive, args.sha256, args.version
    main()
