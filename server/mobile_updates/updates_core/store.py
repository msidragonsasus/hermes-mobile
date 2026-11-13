"""Read-only view of the operator-managed release directory.

Layout (created only by the publish CLI or an operator):

    $HERMES_MOBILE_UPDATES_DIR/releases/v<version_code>-<sha256[:16]>/release.json
    $HERMES_MOBILE_UPDATES_DIR/releases/v<version_code>-<sha256[:16]>/app.apk

Every value that reaches a file open comes from this module's own directory listing or a strictly
validated release id; HTTP input never supplies a path. Symlinks, extra files and records that
disagree with the APK bytes are skipped (listing) or refused (download)."""
from dataclasses import dataclass
import hashlib
import json
import logging
import mmap
import os
from pathlib import Path
import re
import stat
import threading

from .apk import ApkError, inspect_apk

log = logging.getLogger("hermes.mobile_updates")

SCHEMA = "hermes-mobile-update-release"
PROTOCOL = 1
DEFAULT_APPLICATION_ID = "com.m57.hermescontrol"
MAX_APK_BYTES = 200 * 1024 * 1024
MAX_RECORD_BYTES = 64 * 1024
MAX_NOTES = 8000
MAX_VERSION_CODE = 2_100_000_000
APK_FILE = "app.apk"
RECORD_FILE = "release.json"
RECORD_KEYS = {"schema", "protocol", "release_id", "application_id", "version_code", "version_name", "sha256",
               "size_bytes", "signer_sha256", "apk_file", "notes", "published_at"}
RELEASE_ID = re.compile(r"^v([1-9][0-9]{0,9})-([0-9a-f]{16})$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
APPLICATION_ID = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
OPEN_FLAGS = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)


class ConfigurationError(ValueError):
    pass


class StoreError(Exception):
    def __init__(self, code, status=409):
        super().__init__(code)
        self.code, self.status = code, status


@dataclass(frozen=True)
class UpdatesConfig:
    root: Path
    application_id: str = DEFAULT_APPLICATION_ID
    # JSON activation binds the canonical root to its owner and directory identity.
    root_identity: tuple | None = None

    @property
    def releases(self):
        return self.root / "releases"


def check_private_directory(path):
    """A real directory (not a symlink) that only its owner can write."""
    try:
        info = os.lstat(path)
    except OSError:
        raise ConfigurationError("updates_dir_missing") from None
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise ConfigurationError("updates_dir_not_directory")
    if info.st_mode & 0o022:
        raise ConfigurationError("updates_dir_writable_by_others")


def _profile_owned_path(path, *, directory=True):
    """Canonical owner-writable-only target below non-writable, non-link ancestors."""
    if not path.is_absolute() or path != path.resolve():
        raise ConfigurationError("updates_config_unsafe_path")
    uid = os.geteuid()
    for current in (path, *path.parents):
        info = os.lstat(current)
        is_target = current == path
        expected_type = stat.S_ISDIR if directory or not is_target else stat.S_ISREG
        if (not expected_type(info.st_mode) or info.st_mode & 0o022
                or info.st_uid not in ({uid} if is_target else {0, uid})):
            raise ConfigurationError("updates_config_unsafe_path")
    return os.lstat(path)


def _unique_config_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ConfigurationError("updates_config_duplicate_key")
        result[key] = value
    return result


def config_from_profile():
    """Opt-in settings in the trusted server profile, never a request-selected home."""
    try:
        from hermes_constants import get_hermes_home
    except ImportError:
        return None  # Standalone CLI without a Hermes profile resolver stays inert.
    home = Path(get_hermes_home())
    path = home / "mobile-updates.json"
    try:
        if not os.path.lexists(path):
            return None
        _profile_owned_path(home)
        before = _profile_owned_path(path, directory=False)
        fd = os.open(path, OPEN_FLAGS | getattr(os, "O_NONBLOCK", 0))
        with os.fdopen(fd, "rb") as handle:
            opened = os.fstat(handle.fileno())
            if ((before.st_dev, before.st_ino, before.st_uid) !=
                    (opened.st_dev, opened.st_ino, opened.st_uid)
                    or not stat.S_ISREG(opened.st_mode) or opened.st_mode & 0o022
                    or opened.st_size > MAX_RECORD_BYTES):
                raise ConfigurationError("updates_config_unsafe_file")
            data = handle.read(MAX_RECORD_BYTES + 1)
        if len(data) > MAX_RECORD_BYTES:
            raise ConfigurationError("updates_config_too_large")
        record = json.loads(data.decode("utf-8"), object_pairs_hook=_unique_config_keys)
        if (not isinstance(record, dict)
                or set(record) != {"schema", "protocol", "enabled", "root", "application_id"}
                or record["schema"] != "hermes-mobile-updates-config"
                or type(record["protocol"]) is not int or record["protocol"] != 1
                or type(record["enabled"]) is not bool
                or not isinstance(record["root"], str)
                or not isinstance(record["application_id"], str)
                or not APPLICATION_ID.fullmatch(record["application_id"])):
            raise ConfigurationError("updates_config_invalid")
        root = Path(record["root"])
        info = _profile_owned_path(root)
        if not record["enabled"]:
            return None
        return UpdatesConfig(root, record["application_id"], (info.st_dev, info.st_ino, info.st_uid))
    except (OSError, ValueError, RuntimeError) as error:
        if isinstance(error, ConfigurationError):
            raise
        raise ConfigurationError("updates_config_invalid") from None


def config_from_environment(env=None):
    values = os.environ if env is None else env
    raw = str(values.get("HERMES_MOBILE_UPDATES_DIR", "")).strip()
    if not raw:
        return config_from_profile()
    root = Path(raw)
    if not root.is_absolute():
        raise ConfigurationError("updates_dir_must_be_absolute")
    check_private_directory(root)
    application_id = str(values.get("HERMES_MOBILE_UPDATES_APPLICATION_ID", DEFAULT_APPLICATION_ID)).strip()
    if not APPLICATION_ID.fullmatch(application_id):
        raise ConfigurationError("invalid_application_id")
    return UpdatesConfig(root, application_id)


def validate_record(record, application_id, release_id=None):
    """Return the record when it is a complete, self-consistent release description."""
    if not isinstance(record, dict) or set(record) != RECORD_KEYS:
        raise StoreError("release_record_invalid")
    code, name, sha = record["version_code"], record["version_name"], record["sha256"]
    signers = record["signer_sha256"]
    valid = (
        record["schema"] == SCHEMA
        and type(record["protocol"]) is int and record["protocol"] == PROTOCOL
        and record["application_id"] == application_id
        and type(code) is int and 1 <= code <= MAX_VERSION_CODE
        and isinstance(name, str) and 1 <= len(name) <= 128 and name.isprintable()
        and isinstance(sha, str) and SHA256.fullmatch(sha)
        and type(record["size_bytes"]) is int and 1 <= record["size_bytes"] <= MAX_APK_BYTES
        and isinstance(signers, list) and 1 <= len(signers) <= 8
        and all(isinstance(s, str) and SHA256.fullmatch(s) for s in signers)
        and len(set(signers)) == len(signers)
        and record["release_id"] == f"v{code}-{sha[:16]}"
        and (release_id is None or record["release_id"] == release_id)
        and record["apk_file"] == APK_FILE
        and isinstance(record["notes"], str) and len(record["notes"]) <= MAX_NOTES
        and isinstance(record["published_at"], str) and TIMESTAMP.fullmatch(record["published_at"])
    )
    if not valid:
        raise StoreError("release_record_invalid")
    return record


def identity_matches(record, identity):
    return (identity.application_id == record["application_id"]
            and identity.version_code == record["version_code"]
            and identity.version_name == record["version_name"]
            and set(identity.signer_sha256) == set(record["signer_sha256"]))


def public_metadata(record):
    return {
        "release_id": record["release_id"],
        "application_id": record["application_id"],
        "version_code": record["version_code"],
        "version_name": record["version_name"],
        "sha256": record["sha256"],
        "size_bytes": record["size_bytes"],
        "signer_sha256": sorted(record["signer_sha256"]),
        "apk_path": f"releases/{record['release_id']}/apk",
        "notes": record["notes"],
        "published_at": record["published_at"],
    }


def open_regular(path, limit):
    fd = os.open(path, OPEN_FLAGS)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise StoreError("release_file_invalid")
        return fd, info
    except BaseException:
        os.close(fd)
        raise


def fingerprint_fd(fd, size, expected_sha=None):
    """SHA-256 and identity claims of an open APK, read through one descriptor. Bytes that do not
    match [expected_sha] are an integrity failure and are never parsed."""
    if size == 0:
        raise StoreError("release_file_invalid")
    with mmap.mmap(fd, size, access=mmap.ACCESS_READ) as view:
        sha = hashlib.sha256(view).hexdigest()
        if expected_sha is not None and sha != expected_sha:
            raise StoreError("release_integrity_failed")
        try:
            identity = inspect_apk(view)
        except ApkError as error:
            raise StoreError(error.code) from None
    return sha, identity


class ReleaseStore:
    def __init__(self, config):
        self.config = config
        self._cache = {}
        self._lock = threading.Lock()

    def _check_root_identity(self):
        if self.config.root_identity is not None:
            try:
                info = _profile_owned_path(self.config.root)
                if (info.st_dev, info.st_ino, info.st_uid) != self.config.root_identity:
                    raise ConfigurationError("updates_root_changed")
            except (OSError, ValueError, RuntimeError):
                raise StoreError("store_unavailable", 503) from None

    def _directory(self, release_id):
        self._check_root_identity()
        try:
            check_private_directory(self.config.root)
            check_private_directory(self.config.releases)
        except ConfigurationError:
            raise StoreError("store_unavailable", 503) from None
        path = self.config.releases / release_id
        try:
            info = os.lstat(path)
        except OSError:
            raise StoreError("release_not_found", 404) from None
        if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
            raise StoreError("release_not_directory")
        if set(os.listdir(path)) != {RECORD_FILE, APK_FILE}:
            raise StoreError("release_unexpected_files")
        return path

    def _record(self, path, release_id):
        try:
            fd, info = open_regular(path / RECORD_FILE, MAX_RECORD_BYTES)
        except OSError:
            raise StoreError("release_record_unreadable") from None
        with os.fdopen(fd, "rb") as handle:
            data = handle.read(MAX_RECORD_BYTES + 1)
        try:
            record = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise StoreError("release_record_invalid") from None
        return validate_record(record, self.config.application_id, release_id)

    def _verified(self, release_id):
        """Validated record of one release whose APK bytes match it (hash cached per inode/mtime)."""
        path = self._directory(release_id)
        record = self._record(path, release_id)
        try:
            fd, info = open_regular(path / APK_FILE, MAX_APK_BYTES)
        except OSError:
            raise StoreError("release_apk_unreadable") from None
        try:
            if info.st_size != record["size_bytes"]:
                raise StoreError("release_integrity_failed")
            key = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
            with self._lock:
                cached = self._cache.get(key)
            if cached is None:
                cached = fingerprint_fd(fd, info.st_size, record["sha256"])
                with self._lock:
                    self._cache[key] = cached
            sha, identity = cached
            if sha != record["sha256"] or not identity_matches(record, identity):
                raise StoreError("release_integrity_failed")
        finally:
            os.close(fd)
        return record

    def releases(self):
        self._check_root_identity()
        try:
            check_private_directory(self.config.releases)
        except ConfigurationError as error:
            if str(error) == "updates_dir_missing":
                return []
            raise StoreError("store_unavailable", 503) from None
        valid = []
        for name in sorted(os.listdir(self.config.releases)):
            if name.startswith(".staging-"):
                continue
            if not RELEASE_ID.fullmatch(name):
                log.warning("mobile_updates skipped release entry with invalid name")
                continue
            try:
                valid.append(self._verified(name))
            except StoreError as error:
                log.warning("mobile_updates skipped release %s: %s", name, error.code)
        return valid

    def latest(self):
        valid = self.releases()
        if not valid:
            return None
        highest = max(record["version_code"] for record in valid)
        newest = [record for record in valid if record["version_code"] == highest]
        if len(newest) != 1:
            raise StoreError("ambiguous_release")
        return public_metadata(newest[0])

    def open_apk(self, release_id):
        """Open a release APK, verifying the exact bytes that will be streamed. Caller closes it."""
        if not isinstance(release_id, str) or not RELEASE_ID.fullmatch(release_id):
            raise StoreError("release_not_found", 404)
        record = self._verified(release_id)
        try:
            fd, info = open_regular(self.config.releases / release_id / APK_FILE, MAX_APK_BYTES)
        except OSError:
            raise StoreError("release_not_found", 404) from None
        try:
            if info.st_size != record["size_bytes"]:
                raise StoreError("release_integrity_failed")
            _, identity = fingerprint_fd(fd, info.st_size, record["sha256"])
            if not identity_matches(record, identity):
                raise StoreError("release_integrity_failed")
            os.lseek(fd, 0, os.SEEK_SET)
            return os.fdopen(fd, "rb"), public_metadata(record)
        except BaseException:
            os.close(fd)
            raise
