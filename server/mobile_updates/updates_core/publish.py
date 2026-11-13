"""Publish one reviewed, already-built APK into a NEW immutable release directory.

    python -m updates_core.publish --root DIR --apk FILE --metadata FILE [--dry-run]

Run from server/mobile_updates (or the installed plugin directory) as the operator. It never builds,
signs, runs shell commands, deploys or restarts anything, and never replaces an existing release.
The APK is copied into a staging directory on the same filesystem, verified there, and renamed into
place, so the Dashboard sees either the complete release or nothing."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile

from .store import (APK_FILE, APPLICATION_ID, DEFAULT_APPLICATION_ID, MAX_APK_BYTES, MAX_NOTES, OPEN_FLAGS,
                    PROTOCOL, RECORD_FILE, SCHEMA, SHA256, ConfigurationError, ReleaseStore, StoreError,
                    UpdatesConfig, check_private_directory, fingerprint_fd, identity_matches, public_metadata,
                    validate_record)

INPUT_REQUIRED = {"application_id", "version_code", "version_name", "signer_sha256", "notes"}
INPUT_OPTIONAL = {"sha256", "size_bytes"}


class PublishError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _read_input(path):
    try:
        with open(path, "rb") as handle:
            data = handle.read(64 * 1024 + 1)
        value = json.loads(data.decode("utf-8"))
    except (OSError, UnicodeDecodeError, ValueError):
        raise PublishError("metadata_invalid") from None
    if (len(data) > 64 * 1024 or not isinstance(value, dict) or not INPUT_REQUIRED <= set(value)
            or not set(value) <= INPUT_REQUIRED | INPUT_OPTIONAL or not isinstance(value["notes"], str)
            or len(value["notes"]) > MAX_NOTES):
        raise PublishError("metadata_invalid")
    if "sha256" in value and not (isinstance(value["sha256"], str) and SHA256.fullmatch(value["sha256"])):
        raise PublishError("metadata_invalid")
    if "size_bytes" in value and type(value["size_bytes"]) is not int:
        raise PublishError("metadata_invalid")
    return value


def _copy_source(source, destination):
    """Copy a regular, non-symlink APK into the staging file, hashing the copied bytes."""
    try:
        info = os.lstat(source)
        if not stat.S_ISREG(info.st_mode):
            raise PublishError("apk_not_regular_file")
        fd = os.open(source, OPEN_FLAGS)
    except OSError:
        raise PublishError("apk_not_regular_file") from None
    digest, size = hashlib.sha256(), 0
    with os.fdopen(fd, "rb") as reader, open(destination, "xb") as writer:
        if not stat.S_ISREG(os.fstat(reader.fileno()).st_mode):
            raise PublishError("apk_not_regular_file")
        while chunk := reader.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_APK_BYTES:
                raise PublishError("apk_too_large")
            digest.update(chunk)
            writer.write(chunk)
        writer.flush()
        os.fsync(writer.fileno())
    return digest.hexdigest(), size


def _fsync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def publish(root, apk, metadata, application_id=DEFAULT_APPLICATION_ID, dry_run=False, now=None):
    root, apk = Path(root), Path(apk)
    if not root.is_absolute() or not APPLICATION_ID.fullmatch(application_id):
        raise PublishError("updates_dir_must_be_absolute" if not root.is_absolute() else "invalid_application_id")
    try:
        check_private_directory(root)
    except ConfigurationError as error:
        raise PublishError(str(error)) from None
    config = UpdatesConfig(root, application_id)
    supplied = _read_input(metadata)
    if supplied["application_id"] != application_id:
        raise PublishError("application_id_mismatch")

    releases = config.releases
    if not dry_run and not releases.exists():
        releases.mkdir(mode=0o750)
    if releases.exists():
        try:
            check_private_directory(releases)
        except ConfigurationError as error:
            raise PublishError(str(error)) from None
    staging_parent = releases if not dry_run else None
    staging = Path(tempfile.mkdtemp(prefix=".staging-", dir=staging_parent))
    try:
        staged_apk = staging / APK_FILE
        sha, size = _copy_source(apk, staged_apk)
        if supplied.get("sha256", sha) != sha:
            raise PublishError("sha256_mismatch")
        if supplied.get("size_bytes", size) != size:
            raise PublishError("size_mismatch")
        fd = os.open(staged_apk, OPEN_FLAGS)
        try:
            staged_sha, identity = fingerprint_fd(fd, size)
        except StoreError as error:
            raise PublishError(error.code) from None
        finally:
            os.close(fd)
        if staged_sha != sha:
            raise PublishError("sha256_mismatch")
        published_at = now or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        record = {
            "schema": SCHEMA,
            "protocol": PROTOCOL,
            "release_id": f"v{supplied['version_code']}-{sha[:16]}",
            "application_id": supplied["application_id"],
            "version_code": supplied["version_code"],
            "version_name": supplied["version_name"],
            "sha256": sha,
            "size_bytes": size,
            "signer_sha256": sorted(supplied["signer_sha256"]) if isinstance(supplied["signer_sha256"], list)
            else supplied["signer_sha256"],
            "apk_file": APK_FILE,
            "notes": supplied["notes"],
            "published_at": published_at,
        }
        try:
            validate_record(record, application_id)
        except StoreError:
            raise PublishError("metadata_invalid") from None
        if not identity_matches(record, identity):
            raise PublishError("metadata_apk_mismatch")

        existing = ReleaseStore(config).releases() if releases.exists() else []
        names = os.listdir(releases) if releases.exists() else []
        if any(r["version_code"] >= record["version_code"] for r in existing) or any(
                n.startswith(f"v{record['version_code']}-") for n in names):
            raise PublishError("version_code_not_increasing")
        target = releases / record["release_id"]
        if os.path.lexists(target):
            raise PublishError("release_exists")
        if dry_run:
            return public_metadata(record)

        record_path = staging / RECORD_FILE
        with open(record_path, "x", encoding="utf-8") as handle:
            json.dump(record, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(staged_apk, 0o440)
        os.chmod(record_path, 0o440)
        os.chmod(staging, 0o750)
        _fsync_directory(staging)
        os.rename(staging, target)
        staging = None
        _fsync_directory(releases)
        ReleaseStore(config)._verified(record["release_id"])
        return public_metadata(record)
    except StoreError as error:
        raise PublishError(error.code) from None
    finally:
        if staging is not None:
            shutil.rmtree(staging, ignore_errors=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Publish a reviewed Hermes Mobile APK into a new release.")
    parser.add_argument("--root", required=True, help="absolute HERMES_MOBILE_UPDATES_DIR")
    parser.add_argument("--apk", required=True, help="already built and signed APK to copy")
    parser.add_argument("--metadata", required=True, help="reviewed release input JSON")
    parser.add_argument("--application-id", default=DEFAULT_APPLICATION_ID)
    parser.add_argument("--dry-run", action="store_true", help="validate only; write nothing")
    args = parser.parse_args(argv)
    try:
        result = publish(args.root, args.apk, args.metadata, args.application_id, args.dry_run)
    except PublishError as error:
        print(f"mobile_updates publish refused: {error.code}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
