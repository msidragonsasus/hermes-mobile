"""Private, account-bound credentials; no global token swapping or provider revocation."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
import threading

from .store import CareerError

_LOCK = threading.RLock()
ACCOUNT = re.compile(r'gmail-[a-f0-9]{32}')


def account_id(email):
    return 'gmail-' + hashlib.sha256(email.strip().lower().encode()).hexdigest()[:32]


class GoogleAccounts:
    def __init__(self, home):
        self.home = Path(home)
        self.root = self.home / 'google_connections'

    def _directory(self, path):
        if path.is_symlink():
            raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503)
        path.mkdir(mode=0o700, exist_ok=True)
        if not path.is_dir() or (os.name != 'nt' and path.stat().st_mode & 0o077):
            raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503)

    @contextmanager
    def lock(self):
        with _LOCK:
            self._directory(self.root)
            path = self.root / '.lock'
            if path.is_symlink():
                raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503)
            descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
            with os.fdopen(descriptor, 'r+b') as stream:
                if os.name == 'nt':
                    import msvcrt
                    if not stream.read(1):
                        stream.write(b'\0')
                        stream.flush()
                    stream.seek(0)
                    msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream, fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    if os.name == 'nt':
                        stream.seek(0)
                        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(stream, fcntl.LOCK_UN)

    def _read(self):
        path = self.root / 'accounts.json'
        if not path.exists() and not path.is_symlink():
            return {}
        if path.is_symlink() or path.stat().st_size > 131072:
            raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503)
        try:
            value = json.loads(path.read_text(encoding='utf-8'))
            if not isinstance(value, dict) or value.get('version') != 1 or not isinstance(value.get('accounts'), dict):
                raise ValueError()
            for identity, row in value['accounts'].items():
                if not ACCOUNT.fullmatch(identity) or not isinstance(row, dict) or row.get('id') != identity or not isinstance(row.get('email'), str) or account_id(row['email']) != identity:
                    raise ValueError()
                if row.get('kind') not in {'legacy', 'managed'} or row.get('state') not in {'connected', 'reauth_required', 'disconnected'}:
                    raise ValueError()
            return value['accounts']
        except (ValueError, TypeError, KeyError, OSError):
            raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503) from None

    def _write(self, rows):
        self._atomic(self.root / 'accounts.json', {'version': 1, 'accounts': rows})

    def _atomic(self, path, value, *, exclusive=False):
        if path.is_symlink():
            raise CareerError('google_storage_unavailable', 'Google接続情報を確認できません。', 503)
        descriptor, temporary = tempfile.mkstemp(prefix='.google-account-', dir=path.parent)
        try:
            with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as stream:
                json.dump(value, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            if exclusive:
                os.link(temporary, path)
            else:
                os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)

    def _path(self, row):
        return self.home / 'google_token.json' if row['kind'] == 'legacy' else self.root / (row['id'] + '.json')

    def path_for(self, identity):
        if not isinstance(identity, str) or not ACCOUNT.fullmatch(identity):
            raise CareerError('google_account_missing', '接続するアカウントを選んでください。', 404)
        row = self._read().get(identity)
        if row is None:
            # Compatibility with a pre-registry installation. GmailReader still
            # verifies the real profile against the caller's requested account.
            path = self.home / 'google_token.json'
            if not (self.root / 'accounts.json').exists() and path.is_file() and not path.is_symlink():
                return path
        if not row or row['state'] == 'disconnected':
            raise CareerError('google_account_missing', 'このGoogleアカウントは切断されています。', 404)
        path = self._path(row)
        if path.is_symlink() or not path.is_file():
            raise CareerError('authentication_required', 'このアカウントを接続し直してください。', 409)
        return path

    def accounts(self):
        with self.lock():
            return [self.public(row) for row in self._read().values() if row['state'] != 'disconnected']

    @staticmethod
    def public(row):
        return {key: row.get(key, '') for key in ('id', 'email', 'label', 'state')}

    def revision(self, identity):
        with self.lock():
            self.path_for(identity)
            row = self._read().get(identity)
            if not row:
                raise CareerError('google_account_missing', '接続一覧を再読込してください。', 409)
            return row['revision']

    def adopt_legacy(self, email):
        identity = account_id(email)
        with self.lock():
            rows = self._read()
            if any(row['kind'] == 'legacy' and row['state'] != 'disconnected' for row in rows.values()):
                return
            if identity in rows:
                raise CareerError('google_account_exists', '同じアカウントがすでに接続されています。', 409)
            path = self.home / 'google_token.json'
            if path.is_symlink() or not path.is_file():
                raise CareerError('google_existing_connection', '既存の接続を確認してください。', 409)
            rows[identity] = {'id': identity, 'email': email, 'label': '', 'kind': 'legacy',
                              'state': 'connected', 'revision': secrets.token_hex(16)}
            self._write(rows)

    def save(self, email, credentials, *, reconnect=None, revision=None, legacy_before=None):
        identity = account_id(email)
        if reconnect is not None and reconnect != identity:
            raise CareerError('google_account_mismatch', '接続し直すアカウントと同じGoogleアカウントを選んでください。', 409)
        with self.lock():
            rows = self._read()
            old = rows.get(identity)
            active = old and old['state'] != 'disconnected'
            if reconnect:
                if not active or old['revision'] != revision:
                    raise CareerError('google_flow_expired', '接続状態が変わりました。もう一度お試しください。', 409)
            elif active:
                raise CareerError('google_account_exists', 'このアカウントは接続済みです。一覧から接続し直せます。', 409)
            elif sum(row['state'] != 'disconnected' for row in rows.values()) >= 20:
                raise CareerError('google_account_limit', '接続できるアカウントは20件までです。', 409)
            legacy = self.home / 'google_token.json'
            # A v38/official skill credential created during consent is never
            # overwritten. Additional managed accounts never change this file.
            if not reconnect and legacy_before is None and (legacy.exists() or legacy.is_symlink()):
                raise CareerError('google_existing_connection', '別の接続が作成されました。再読込してください。', 409)
            kind = old['kind'] if reconnect else ('legacy' if not legacy.exists() and not rows else 'managed')
            row = {'id': identity, 'email': email, 'label': old.get('label', '') if old else '',
                   'kind': kind, 'state': 'connected', 'revision': secrets.token_hex(16)}
            target = self._path(row)
            if target.is_symlink() or (target.exists() and not reconnect):
                raise CareerError('google_existing_connection', '既存の接続情報を保持しました。再読込してください。', 409)
            previous = target.read_bytes() if reconnect else None
            archive = None
            if previous is not None:
                archive = self._archive(target, identity)
            written = False
            try:
                self._atomic(target, credentials, exclusive=True)
                written = True
                rows[identity] = row
                self._write(rows)
            except Exception:
                if archive is not None and (written or not target.exists()):
                    os.replace(archive, target)
                elif written:
                    target.unlink(missing_ok=True)
                raise
            return self.public(row)

    def _archive(self, path, identity):
        directory = self.root / 'backups'
        self._directory(directory)
        target = directory / (identity + '-' + secrets.token_hex(16) + '.json')
        os.rename(path, target)
        return target

    def update(self, identity, *, label=None, disconnect=False):
        with self.lock():
            rows = self._read()
            row = rows.get(identity)
            if not row or row['state'] == 'disconnected':
                raise CareerError('google_account_missing', 'このアカウントは接続されていません。', 404)
            if label is not None:
                if not isinstance(label, str) or len(label) > 40 or any(ord(c) < 32 for c in label):
                    raise CareerError('invalid_request', '用途名は40文字以内で入力してください。')
                row['label'] = label.strip()
            archive = None
            target = self._path(row)
            if disconnect:
                if target.is_symlink():
                    raise CareerError('google_storage_unavailable', '接続情報を確認できません。', 503)
                if target.exists():
                    archive = self._archive(target, identity)
                row['state'] = 'disconnected'
            row['revision'] = secrets.token_hex(16)
            try:
                self._write(rows)
            except Exception:
                if archive is not None:
                    os.replace(archive, target)
                raise

    def mark_reauth(self, identity):
        self.mark_state(identity, 'reauth_required')

    def mark_state(self, identity, state):
        # Called after a bounded request, outside the request's file lock.
        with self.lock():
            rows = self._read()
            if identity in rows and rows[identity]['state'] != 'disconnected':
                rows[identity]['state'] = state
                self._write(rows)
