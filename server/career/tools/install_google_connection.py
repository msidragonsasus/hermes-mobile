"""Narrow, backed-up Google onboarding deployment; run by the host administrator.

Does not create, copy, replace, revoke, or print Google credentials. No agent run.
"""
import argparse
import ast
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import zipfile

BASE = Path('/var/lib/hermes')
HOME = BASE / '.hermes'
PLUGIN = HOME / 'plugins/career'
SERVICE = 'home-hermes-dashboard.service'
MEMBERS = {'google_connection.py', 'test_google_connection.py'}


def rollback(api, module, backup, original, candidate, new_module):
    subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=60)
    saved = backup / 'google_connection.py'
    metadata = saved.stat() if saved.exists() else None
    old_module = saved.read_bytes() if metadata is not None else None
    current_module = module.read_bytes() if module.exists() else None
    if api.read_bytes() not in {original, candidate} or current_module not in {old_module, new_module}:
        raise RuntimeError('Rollback code changed; Dashboard remains stopped for recovery')
    api.write_bytes(original)
    if old_module is not None:
        descriptor = os.open(module, os.O_RDWR | os.O_NOFOLLOW)
        with os.fdopen(descriptor, 'r+b') as stream:
            if stream.read() not in {old_module, new_module}:
                raise RuntimeError('Rollback module changed; Dashboard remains stopped for recovery')
            stream.seek(0)
            stream.truncate()
            stream.write(old_module)
            stream.flush()
            os.fchmod(stream.fileno(), metadata.st_mode & 0o7777)
            os.utime(stream.fileno(), ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
    elif module.exists():
        os.replace(module, backup / 'failed-new-google_connection.py')
    subprocess.run(['systemctl', 'restart', SERVICE], check=True, timeout=60)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def patch_api(source):
    addition = ('    from .google_connection import create_google_router\n'
                '    router.include_router(create_google_router())\n')
    if addition in source.replace('\r\n', '\n'):
        return source
    if 'google_connection' in source:
        raise RuntimeError('Unrecognized existing Google connection integration')
    tree = ast.parse(source)
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'create_router']
    if len(functions) != 1:
        raise RuntimeError('Cannot identify the current router factory')
    node = functions[0]
    lines = source.splitlines(keepends=True)
    anchors = [index for index in range(node.lineno, node.end_lineno)
               if lines[index].strip() == 'router = APIRouter(dependencies=[Depends(require_session)])']
    if len(anchors) != 1:
        raise RuntimeError('Current authentication/router contract differs; nothing installed')
    newline = '\r\n' if '\r\n' in source else '\n'
    lines.insert(anchors[0] + 1, addition.replace('\n', newline))
    result = ''.join(lines)
    compile(result, 'api.py', 'exec')
    return result


def read_bundle(data, expected):
    if digest(data) != expected:
        raise RuntimeError('Bundle hash differs; nothing installed')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if len(archive.namelist()) != 3 or set(archive.namelist()) != MEMBERS | {'RELEASE.json'}:
            raise RuntimeError('Unexpected archive members')
        release = json.loads(archive.read('RELEASE.json'))
        if release.get('feature') != 'google-native-connect-v1' or set(release.get('sha256', {})) != MEMBERS:
            raise RuntimeError('Unexpected release manifest')
        files = {}
        for name in MEMBERS:
            if archive.getinfo(name).file_size > 131072:
                raise RuntimeError('Oversized archive member')
            content = archive.read(name)
            if digest(content) != release['sha256'][name]:
                raise RuntimeError('Member hash differs')
            compile(content, name, 'exec')
            files[name] = content
        return files


def file_hash(path):
    if path.is_symlink():
        raise RuntimeError('Unexpected symlink')
    return digest(path.read_bytes()) if path.is_file() else None


def active():
    return subprocess.run(['systemctl', 'is-active', '--quiet', SERVICE]).returncode == 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or socket.gethostname() != 'hermes-node':
        raise SystemExit('Run with sudo on hermes-node')
    os.umask(0o077)
    import fcntl
    import pwd
    lock = open('/run/lock/hermes-google-connect-install.lock', 'a')
    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    for path in (BASE, HOME, PLUGIN, PLUGIN / 'career_core'):
        if not path.is_dir() or path.is_symlink() or path.resolve() != path:
            raise RuntimeError('Unexpected production directory')
    if not active():
        raise RuntimeError('Dashboard is not active; nothing changed')
    files = read_bundle(args.archive.read_bytes(), args.sha256)
    api = PLUGIN / 'career_core/api.py'
    if api.is_symlink() or (PLUGIN / 'career_core/google_connection.py').is_symlink():
        raise RuntimeError('Unexpected production file symlink')
    original = api.read_bytes()
    candidate = patch_api(original.decode('utf-8')).encode('utf-8')
    module = PLUGIN / 'career_core/google_connection.py'
    if module.exists() and module.read_bytes() != files['google_connection.py']:
        raise RuntimeError('A different Google integration is installed; review before replacing')
    protected = [HOME / 'config.yaml', HOME / 'google_token.json', HOME / 'google_client_secret.json',
                 HOME / 'google_mobile_client_secret.json', BASE / 'career/runtime.env']
    protected_before = {str(path): file_hash(path) for path in protected}
    outside_before = {str(p): file_hash(p) for p in (HOME / 'plugins').rglob('*.py')
                      if '__pycache__' not in p.parts and p not in {api, module}}
    account = pwd.getpwnam('hermes')
    pid = subprocess.check_output(['systemctl', 'show', SERVICE, '-pMainPID', '--value'], text=True).strip()
    environment = dict(item.split(b'=', 1) for item in Path('/proc/' + pid + '/environ').read_bytes().split(b'\0') if b'=' in item)
    env = {key.decode(): value.decode() for key, value in environment.items()}
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    env['PYTHONIOENCODING'] = 'utf-8'
    python = BASE / 'venv/bin/python'
    stage = Path(tempfile.mkdtemp(prefix='google-connect-stage-', dir=BASE))
    shutil.copytree(PLUGIN / 'career_core', stage / 'career_core', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    (stage / 'career_core/api.py').write_bytes(candidate)
    (stage / 'career_core/google_connection.py').write_bytes(files['google_connection.py'])
    (stage / 'test_google_connection.py').write_bytes(files['test_google_connection.py'])
    for path in [stage, *stage.rglob('*')]:
        os.chown(path, account.pw_uid, account.pw_gid)
    probe = """import pathlib,sys,unittest
sys.path.insert(0,sys.argv[1])
import career_core.api
assert pathlib.Path(career_core.api.__file__).resolve().is_relative_to(pathlib.Path(sys.argv[1]))
suite=unittest.defaultTestLoader.discover(sys.argv[1],pattern='test_google_connection.py')
result=unittest.TextTestRunner(verbosity=0).run(suite)
raise SystemExit(not result.wasSuccessful())
"""
    result = subprocess.run([str(python), '-I', '-B', '-c', probe, str(stage)], env=env,
        cwd=BASE / 'work', user=account.pw_uid, group=account.pw_gid,
        extra_groups=os.getgrouplist('hermes', account.pw_gid), capture_output=True, timeout=120)
    if result.returncode:
        # Import/test failures must not print production paths, environment,
        # credential values or raw provider output. Staging remains recoverable.
        raise RuntimeError('Host compatibility/synthetic tests failed; nothing installed')
    db_path = BASE / 'career/career.sqlite3'
    if db_path.is_symlink() or not db_path.is_file():
        raise RuntimeError('Expected Career database missing; nothing installed')
    with sqlite3.connect(db_path.as_uri() + '?mode=ro', uri=True) as db:
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise RuntimeError('Database check failed; nothing installed')
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table'"):
            if not re.fullmatch(r'[A-Za-z0-9_]+', table):
                continue
            columns = {row[1] for row in db.execute('PRAGMA table_info("' + table + '")')}
            for column in columns & {'state', 'status'}:
                if db.execute('SELECT COUNT(*) FROM "' + table + '" WHERE "' + column + '" = ?', ('running',)).fetchone()[0]:
                    raise RuntimeError('A Career job is running; retry after it finishes')
    summary = {'state': 'preflight_passed', 'host_synthetic_tests': 'passed', 'google_token_present': (HOME / 'google_token.json').is_file(),
               'mobile_client_present': (HOME / 'google_mobile_client_secret.json').is_file(), 'credentials_changed': False,
               'agent_execution': 'not_requested'}
    if not args.apply:
        print(json.dumps(summary, indent=2))
        return
    backup = Path('/var/backups/hermes') / ('google-connect-' + time.strftime('%Y%m%d-%H%M%S'))
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    (backup / 'api.py').write_bytes(original)
    if module.exists():
        shutil.copy2(module, backup / 'google_connection.py')
    with sqlite3.connect(db_path.as_uri() + '?mode=ro', uri=True) as db, sqlite3.connect(backup / 'career.sqlite3') as dest:
        db.backup(dest)
    swapped = False
    try:
        if api.read_bytes() != original or {str(p): file_hash(p) for p in protected} != protected_before:
            raise RuntimeError('Production changed during preflight; nothing installed')
        subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=60)
        swapped = True
        # Both replacement files live beside their targets so rename is atomic.
        for target, content in ((module, files['google_connection.py']), (api, candidate)):
            temporary = target.with_name(target.name + '.google-connect-new')
            with temporary.open('xb') as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.chown(temporary, api.stat().st_uid, api.stat().st_gid)
            os.chmod(temporary, api.stat().st_mode & 0o777)
            os.replace(temporary, target)
        subprocess.run(['systemctl', 'start', SERVICE], check=True, timeout=60)
        if not active():
            raise RuntimeError('Dashboard restart failed')
        if {str(p): file_hash(p) for p in protected} != protected_before:
            raise RuntimeError('Protected configuration or credential changed during deployment')
        if any(file_hash(Path(name)) != expected for name, expected in outside_before.items()):
            raise RuntimeError('Code outside the Google connection delta changed')
    except Exception:
        if swapped:
            rollback(api, module, backup, original, candidate, files['google_connection.py'])
        else:
            subprocess.run(['systemctl', 'start', SERVICE], check=True, timeout=60)
        raise
    summary.update(state='installed', backup=str(backup), service_active=True,
                   other_plugin_code_preserved=True, authenticated_phone_check='pending')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
