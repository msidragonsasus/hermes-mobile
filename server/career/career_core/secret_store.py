"""Encrypted collector credentials, outside the career database and all exports.

Production keys come only from systemd's credential directory. No generated fallback
key and no key in the database, configuration, source tree, or Android draft queue.
"""
import errno
import json
import os
import re
import stat
import struct
import tempfile
from pathlib import Path

from .store import CareerError, encoded


def credential_permissions(info, uid, gid, acl=None):
    """Accept private files or systemd's root-owned, service-user-only ACL.

    systemd sets a named-user ACL before considering an ownership fallback:
    https://github.com/systemd/systemd/blob/main/src/core/exec-credential.c
    Linux's POSIX ACL xattr has a little-endian version followed by tag/perm/id.
    """
    if not stat.S_ISREG(info.st_mode):
        return False
    mode = stat.S_IMODE(info.st_mode)
    if mode in {0o400, 0o600}:
        return info.st_uid in {0, uid}
    if mode != 0o440 or info.st_uid != 0:
        return False
    if acl is None:
        return info.st_gid == gid
    if len(acl) != 44 or struct.unpack_from('<I', acl)[0] != 2:
        return False
    entries = list(struct.iter_unpack('<HHI', acl[4:]))
    undefined = 0xffffffff
    expected = {(1, 4, undefined), (2, 4, uid), (4, 0, undefined),
                (16, 4, undefined), (32, 0, undefined)}
    return len(entries) == len(expected) and set(entries) == expected


def credential_acl(path):
    try:
        return os.getxattr(path, 'system.posix_acl_access', follow_symlinks=False)
    except OSError as error:
        if error.errno in {errno.ENODATA, errno.ENOTSUP}:
            return None
        raise


class SecretStore:
    def __init__(self, directory=None, *, key=None):
        self.directory = Path(directory or os.environ.get("CAREER_PRIVATE_DIR", "/var/lib/hermes/career-private"))
        try:
            from cryptography.fernet import Fernet
            if key is None:
                credentials = os.environ.get("CREDENTIALS_DIRECTORY")
                if not credentials:
                    raise OSError("Credential directory unavailable")
                key_path = Path(credentials) / "career-key"
                if key_path.is_symlink() or (os.name != "nt" and not credential_permissions(
                    key_path.stat(), os.geteuid(), os.getegid(), credential_acl(key_path)
                )):
                    raise OSError("Credential permissions")
                key = key_path.read_bytes().strip()
            self.cipher = Fernet(key)
        except (ImportError, OSError, ValueError):
            raise CareerError("secret_store_unavailable", "認証情報の安全な保存先をサーバーで準備してください。", 503) from None

    def path(self, reference):
        if not isinstance(reference, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,120}", reference):
            raise CareerError("invalid_secret_reference", "認証先を確認してください。")
        return self.directory / (reference + ".sealed")

    def read(self, reference):
        from cryptography.fernet import InvalidToken
        try:
            path = self.path(reference)
            if path.is_symlink() or path.stat().st_size > 2 * 1024 * 1024:
                raise OSError("Invalid credential file")
            if os.name != "nt" and path.stat().st_mode & 0o077:
                raise OSError("Credential permissions")
            return json.loads(self.cipher.decrypt(path.read_bytes()))
        except FileNotFoundError:
            raise CareerError("authentication_required", "この接続へのログインが必要です。", 409) from None
        except (OSError, ValueError, InvalidToken):
            raise CareerError("secret_unavailable", "保存した認証情報を読み取れません。接続を確認してください。", 503) from None

    def write(self, reference, value):
        payload = encoded(value).encode("utf-8")
        if len(payload) > 1024 * 1024:
            raise CareerError("secret_too_large", "認証情報のサイズを確認してください。")
        path = self.path(reference)
        temporary = None
        try:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            if self.directory.is_symlink() or (os.name != "nt" and self.directory.stat().st_mode & 0o077):
                raise OSError("Private directory permissions")
            if path.is_symlink():
                raise OSError("Credential symlink")
            descriptor, temporary = tempfile.mkstemp(prefix="sealed-", dir=self.directory)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(self.cipher.encrypt(payload))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            temporary = None
        except OSError:
            raise CareerError("secret_save_failed", "認証情報を保存できませんでした。", 503) from None
        finally:
            if temporary:
                Path(temporary).unlink(missing_ok=True)

    def references(self, prefix):
        if not self.directory.exists():
            return []
        return sorted(p.stem for p in self.directory.glob(prefix + "*.sealed") if not p.is_symlink())
