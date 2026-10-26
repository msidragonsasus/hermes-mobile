"""Administrator-only, backed-up setup for an explicitly approved Google reconnect.

Never prints credentials, revokes Google access, or invokes the agent.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import time

CLIENT_ID = '123067273555-20f4d7qr42jtdseg1oo8g488updt1jc0.apps.googleusercontent.com'
BASE = Path('/var/lib/hermes')
HOME = BASE / '.hermes'
SERVICE = 'home-hermes-dashboard.service'


def digest(content):
    return hashlib.sha256(content).hexdigest()


def validate_client(content, expected):
    if len(content) > 65536 or digest(content) != expected:
        raise ValueError('Configuration hash/size mismatch')
    value = json.loads(content)
    web = value.get('web', {}) if isinstance(value, dict) else {}
    if web.get('client_id') != CLIENT_ID or web.get('project_id') != 'ageless-arcanum-508105-m2':
        raise ValueError('Unexpected Google application')
    secret = web.get('client_secret')
    if not isinstance(secret, str) or not secret.startswith('GOCSPX-') or not 16 <= len(secret) <= 4096:
        raise ValueError('Invalid Google client configuration')


def configure(home, backup, content, *, uid, gid, token_hash):
    token = home / 'google_token.json'
    target = home / 'google_mobile_client_secret.json'
    if target.exists() or target.is_symlink() or token.is_symlink() or not token.is_file():
        raise RuntimeError('Google files changed; nothing replaced')
    if digest(token.read_bytes()) != token_hash:
        raise RuntimeError('Google token changed; nothing replaced')
    backup.mkdir(mode=0o700, exist_ok=False)
    if backup.stat().st_dev != home.stat().st_dev:
        raise RuntimeError('Backup must share the credential filesystem')
    descriptor, temporary = tempfile.mkstemp(prefix='.google-mobile-config-', dir=home)
    linked = False
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.chown(temporary, uid, gid)
        if digest(token.read_bytes()) != token_hash:
            raise RuntimeError('Google token changed during preparation')
        os.link(temporary, target)
        linked = True
        # This preserves the original token file itself; no invalid credential
        # is deleted or revoked, and the normal first-connect path can now run.
        os.rename(token, backup / 'google_token.json')
    except Exception:
        if linked and not (backup / 'google_token.json').exists():
            target.unlink()
        raise
    finally:
        Path(temporary).unlink(missing_ok=True)


def runtime_environment():
    pid = subprocess.check_output(['systemctl', 'show', SERVICE, '-pMainPID', '--value'], text=True).strip()
    if not pid.isdigit() or pid == '0':
        raise RuntimeError('Dashboard is not running')
    values = dict(item.split(b'=', 1) for item in Path('/proc/' + pid + '/environ').read_bytes().split(b'\0') if b'=' in item)
    env = {key.decode(): value.decode() for key, value in values.items()}
    env.update(PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8')
    return env


def probe(env, account, script):
    result = subprocess.run([str(BASE / 'venv/bin/python'), '-I', '-B', '-c', script], env=env,
        cwd=BASE / 'work', user=account.pw_uid, group=account.pw_gid,
        extra_groups=os.getgrouplist('hermes', account.pw_gid), capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError('Runtime verification failed; raw provider output suppressed')
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('configuration', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0 or socket.gethostname() != 'hermes-node':
        raise SystemExit('Run with sudo on hermes-node')
    os.umask(0o077)
    import fcntl
    import pwd
    with open('/run/lock/hermes-google-connect-install.lock', 'a') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        for path in (BASE, HOME, HOME / 'plugins/career/career_core'):
            if path.is_symlink() or not path.is_dir() or path.resolve() != path:
                raise RuntimeError('Unexpected production path')
        source = args.configuration
        if source.is_symlink() or not source.is_file() or source.stat().st_mode & 0o077:
            raise RuntimeError('Configuration input must be a private regular file')
        content = source.read_bytes()
        validate_client(content, args.sha256)
        account = pwd.getpwnam('hermes')
        env = runtime_environment()
        prefix = "import sys,json;sys.path.insert(0,'/var/lib/hermes/.hermes/plugins/career');"
        check = prefix + '''
from career_core.workspace_reader import connected_account_details
from career_core.store import CareerError
try:
    connected_account_details()
    code='ready'
except CareerError as error:
    code=error.code
print(json.dumps({'state':code}))
'''
        if probe(env, account, check).get('state') != 'authentication_required':
            raise RuntimeError('Existing connection is not confirmed to need reauthentication; nothing changed')
        token = HOME / 'google_token.json'
        if token.is_symlink() or not token.is_file() or token.stat().st_size > 1048576:
            raise RuntimeError('Unexpected existing credential file')
        token_hash = digest(token.read_bytes())
        database = BASE / 'career/career.sqlite3'
        if database.is_symlink() or not database.is_file():
            raise RuntimeError('Expected Career database missing')
        with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as db:
            for table, in db.execute("SELECT name FROM sqlite_master WHERE type='table'"):
                quoted = '"' + table.replace('"', '""') + '"'
                columns = {row[1] for row in db.execute('PRAGMA table_info(' + quoted + ')')}
                for column in columns & {'state', 'status'}:
                    if db.execute('SELECT COUNT(*) FROM ' + quoted + ' WHERE "' + column + '"=?', ('running',)).fetchone()[0]:
                        raise RuntimeError('Career job running; retry after it finishes')
        if (HOME / 'google_mobile_client_secret.json').exists():
            raise RuntimeError('Mobile configuration already exists; nothing replaced')
        if not args.apply:
            print(json.dumps({'state':'preflight_passed', 'existing_connection':'authentication_required', 'changed':False}))
            return
        backup = BASE / ('google-connect-credentials-backup-' + time.strftime('%Y%m%d-%H%M%S'))
        configure(HOME, backup, content, uid=account.pw_uid, gid=account.pw_gid, token_hash=token_hash)
        state = probe(env, account, prefix + "from career_core.google_connection import GoogleConnection;print(json.dumps(GoogleConnection().status()))")
        if state.get('state') != 'disconnected':
            raise RuntimeError('Configuration retained with backup; verify setup before consent')
        print(json.dumps({'state':'mobile_ready', 'google_status':'disconnected', 'previous_token_backed_up':True,
            'backup':str(backup), 'existing_client_secret_revoked':False, 'service_restart_required':False,
            'agent_execution':'not_requested', 'phone_consent':'pending'}, indent=2))


if __name__ == '__main__':
    main()
