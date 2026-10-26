"""Pinned, narrow multi-account upgrade. Preserves credentials and unrelated code."""
import argparse
import ast
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import textwrap
import time
import zipfile

BASE = Path('/var/lib/hermes')
HOME = BASE / '.hermes'
PLUGIN = HOME / 'plugins/career'
SERVICE = 'home-hermes-dashboard.service'


def rollback(written, originals, targets, backup):
    subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=60)
    if any(not path.is_file() or path.read_bytes() != targets[path] for path in written):
        raise RuntimeError('Rollback code changed; Dashboard remains stopped for recovery')
    for path in reversed(written):
        old = originals[path]
        if old is not None:
            path.write_bytes(old)
        else:
            os.replace(path, backup / ('failed-new-' + path.name))
    subprocess.run(['systemctl', 'restart', SERVICE], check=True, timeout=60)
MODULES = {'google_connection.py', 'google_accounts.py', 'google_connected_accounts.py', 'workspace_reader.py', 'gmail_reader.py'}
TESTS = {'test_google_connection.py', 'test_google_multi_account.py'}


def digest(content):
    return hashlib.sha256(content).hexdigest()


def normalized(content):
    return digest(content.replace(b'\r\n', b'\n'))


def function_source(source):
    factory = next(node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef) and node.name == 'create_router')
    matches = [node for node in factory.body if isinstance(node, ast.FunctionDef) and node.name == 'collection_connections']
    if len(matches) != 1:
        raise RuntimeError('Unexpected production connection route')
    node = matches[0]
    lines = source.splitlines(keepends=True)
    return node, ''.join(lines[node.lineno - 1:node.end_lineno])


def patch_api(source, release):
    node, current = function_source(source)
    dump = lambda code: ast.dump(ast.parse(textwrap.dedent(code)), include_attributes=False)
    if dump(current) == dump(release['api_before']):
        lines = source.splitlines(keepends=True)
        newline = '\r\n' if '\r\n' in source else '\n'
        lines[node.lineno - 1:node.end_lineno] = [release['api_after'].replace('\r\n', '\n').replace('\n', newline)]
        source = ''.join(lines)
    elif dump(current) != dump(release['api_after']):
        raise RuntimeError('Production connection route differs; nothing installed')
    before = 'if payload["account_id"] != connected_account():'
    after = 'if payload["account_id"] != connected_account(payload[\'account_id\']):'
    if source.count(before) == 1:
        source = source.replace(before, after)
    elif source.count(after) != 1:
        raise RuntimeError('Production account validation differs; nothing installed')
    compile(source, 'api.py', 'exec')
    return source


def read_bundle(content, expected):
    if digest(content) != expected:
        raise RuntimeError('Bundle hash mismatch')
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        expected_names = MODULES | TESTS | {'RELEASE.json', 'SKILL.md'}
        if set(archive.namelist()) != expected_names or len(archive.namelist()) != len(expected_names):
            raise RuntimeError('Unexpected bundle members')
        files = {}
        for name in expected_names:
            if archive.getinfo(name).file_size > 262144:
                raise RuntimeError('Oversized bundle member')
            files[name] = archive.read(name)
            if name.endswith('.py'):
                compile(files[name], name, 'exec')
        release = json.loads(files['RELEASE.json'])
        if release.get('feature') != 'google-multi-account-v2':
            raise RuntimeError('Unexpected release')
        for name in MODULES | TESTS | {'SKILL.md'}:
            if digest(files[name]) != release['sha256'][name]:
                raise RuntimeError('Member hash mismatch')
        return release, files


def active():
    return subprocess.run(['systemctl', 'is-active', '--quiet', SERVICE]).returncode == 0


def credential_hashes():
    paths = [HOME / name for name in ('config.yaml', 'google_token.json', 'google_client_secret.json', 'google_mobile_client_secret.json')]
    root = HOME / 'google_connections'
    if root.is_symlink():
        raise RuntimeError('Unexpected account registry symlink')
    if root.exists():
        paths.extend(root.rglob('*'))
    result = {}
    for path in paths:
        if path.is_symlink():
            raise RuntimeError('Unexpected protected symlink')
        if path.is_file() and path.name != '.lock':
            result[str(path)] = digest(path.read_bytes())
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or socket.gethostname() != 'hermes-node':
        raise SystemExit('Run with sudo on hermes-node')
    import pwd
    import fcntl
    os.umask(0o077)
    lock = open('/run/lock/hermes-google-connect-install.lock', 'a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    for path in (BASE, HOME, PLUGIN, PLUGIN / 'career_core', HOME / 'skills/productivity'):
        if path.is_symlink() or not path.is_dir() or path.resolve() != path:
            raise RuntimeError('Unexpected production directory')
    if not active():
        raise RuntimeError('Dashboard is not active')
    release, files = read_bundle(args.archive.read_bytes(), args.sha256)
    api = PLUGIN / 'career_core/api.py'
    if api.is_symlink():
        raise RuntimeError('Unexpected API symlink')
    original_api = api.read_bytes()
    candidate_api = patch_api(original_api.decode('utf-8'), release).encode('utf-8')
    targets = {PLUGIN / 'career_core' / name: files[name] for name in MODULES}
    skill_dir = HOME / 'skills/productivity/google-connected-accounts'
    if skill_dir.is_symlink():
        raise RuntimeError('Unexpected skill directory')
    targets[skill_dir / 'SKILL.md'] = files['SKILL.md']
    originals = {api: original_api}
    for path, content in targets.items():
        if path.is_symlink():
            raise RuntimeError('Unexpected code symlink')
        previous = path.read_bytes() if path.is_file() else None
        expected = release['previous'].get(path.name) if path.name in MODULES else None
        if previous is not None and previous != content and (expected is None or normalized(previous) != expected):
            raise RuntimeError('Production Google code differs: ' + path.name + '; nothing installed')
        if previous is None and expected is not None:
            raise RuntimeError('Expected production Google code missing')
        originals[path] = previous
    targets[api] = candidate_api
    protected = credential_hashes()
    outside = {str(p): digest(p.read_bytes()) for p in (HOME / 'plugins').rglob('*.py') if '__pycache__' not in p.parts and p not in targets}
    account = pwd.getpwnam('hermes')
    pid = subprocess.check_output(['systemctl', 'show', SERVICE, '-pMainPID', '--value'], text=True).strip()
    if not pid.isdigit() or pid == '0':
        raise RuntimeError('Dashboard process unavailable')
    raw_env = dict(part.split(b'=', 1) for part in Path('/proc/' + pid + '/environ').read_bytes().split(b'\0') if b'=' in part)
    env = {key.decode(): value.decode() for key, value in raw_env.items()}
    env.update(PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8')
    stage = Path(tempfile.mkdtemp(prefix='google-multi-stage-', dir=BASE))
    shutil.copytree(PLUGIN / 'career_core', stage / 'career_core', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    (stage / 'career_core/api.py').write_bytes(candidate_api)
    for name in MODULES:
        (stage / 'career_core' / name).write_bytes(files[name])
    for name in TESTS:
        (stage / name).write_bytes(files[name])
    for path in [stage, *stage.rglob('*')]:
        os.chown(path, account.pw_uid, account.pw_gid)
    probe = '''import sys,pathlib,unittest
sys.path.insert(0,sys.argv[1])
import career_core.api
assert pathlib.Path(career_core.api.__file__).is_relative_to(pathlib.Path(sys.argv[1]))
suite=unittest.defaultTestLoader.discover(sys.argv[1],pattern='test_google_*.py')
result=unittest.TextTestRunner(verbosity=0).run(suite)
raise SystemExit(not result.wasSuccessful())
'''
    tested = subprocess.run([str(BASE / 'venv/bin/python'), '-I', '-B', '-c', probe, str(stage)], env=env,
        cwd=BASE / 'work', user=account.pw_uid, group=account.pw_gid,
        extra_groups=os.getgrouplist('hermes', account.pw_gid), capture_output=True, timeout=120)
    if tested.returncode:
        raise RuntimeError('Host compatibility/isolation tests failed; raw output suppressed; nothing installed')
    database = BASE / 'career/career.sqlite3'
    if database.is_symlink() or not database.is_file():
        raise RuntimeError('Career database missing')
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as db:
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise RuntimeError('Career database integrity check failed')
        for table, in db.execute("SELECT name FROM sqlite_master WHERE type='table'"):
            table = '"' + table.replace('"', '""') + '"'
            columns = {row[1] for row in db.execute('PRAGMA table_info(' + table + ')')}
            for column in columns & {'state', 'status'}:
                if db.execute('SELECT COUNT(*) FROM ' + table + ' WHERE "' + column + '"=?', ('running',)).fetchone()[0]:
                    raise RuntimeError('Career job running; retry later')
    result = {'state': 'preflight_passed', 'host_isolation_tests': 'passed', 'credentials_changed': False, 'agent_execution': 'not_requested'}
    if not args.apply:
        print(json.dumps(result, indent=2))
        return
    if credential_hashes() != protected or any((p.read_bytes() if p.exists() else None) != old for p, old in originals.items()):
        raise RuntimeError('Production changed during preflight; nothing installed')
    backup = Path('/var/backups/hermes') / ('google-multi-' + time.strftime('%Y%m%d-%H%M%S'))
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    for index, (path, old) in enumerate(originals.items()):
        if old is not None:
            (backup / (str(index) + '-' + path.name)).write_bytes(old)
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as db, sqlite3.connect(backup / 'career.sqlite3') as destination:
        db.backup(destination)
    stopped = False
    written = []
    try:
        subprocess.run(['systemctl', 'stop', SERVICE], check=True, timeout=60)
        stopped = True
        if credential_hashes() != protected or any((p.read_bytes() if p.exists() else None) != old for p, old in originals.items()):
            raise RuntimeError('Production changed before replacement; nothing installed')
        for path, content in targets.items():
            if path.parent == skill_dir and not skill_dir.exists():
                skill_dir.mkdir(mode=0o700)
                os.chown(skill_dir, account.pw_uid, account.pw_gid)
            descriptor, temporary = tempfile.mkstemp(prefix='.google-multi-new-', dir=path.parent)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.chown(temporary, api.stat().st_uid, api.stat().st_gid)
            os.chmod(temporary, api.stat().st_mode & 0o777)
            os.replace(temporary, path)
            written.append(path)
        subprocess.run(['systemctl', 'start', SERVICE], check=True, timeout=60)
        if not active() or credential_hashes() != protected:
            raise RuntimeError('Service or credential preservation verification failed')
        if any(digest(Path(name).read_bytes()) != value for name, value in outside.items()):
            raise RuntimeError('Unrelated plugin code changed')
    except Exception:
        if stopped:
            rollback(written, originals, targets, backup)
        raise
    result.update(state='installed', backup=str(backup), service_active=True,
                  other_plugin_code_preserved=True, phone_consent='pending')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
