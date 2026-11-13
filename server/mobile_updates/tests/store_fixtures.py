import json
import os
from pathlib import Path
import tempfile

from apk_fixtures import binary_manifest, build_apk, digest

CERT = b"fake-certificate-der"
APP_ID = "com.m57.hermescontrol"


def make_root():
    holder = tempfile.TemporaryDirectory()
    root = Path(holder.name) / "updates"
    root.mkdir(mode=0o750)
    os.chmod(root, 0o750)
    return holder, root


def release_record(apk, version_code, version_name, **overrides):
    sha = digest(apk)
    record = {
        "schema": "hermes-mobile-update-release",
        "protocol": 1,
        "release_id": f"v{version_code}-{sha[:16]}",
        "application_id": APP_ID,
        "version_code": version_code,
        "version_name": version_name,
        "sha256": sha,
        "size_bytes": len(apk),
        "signer_sha256": [digest(CERT)],
        "apk_file": "app.apk",
        "notes": "Synthetic test release",
        "published_at": "2026-10-27T00:00:00Z",
    }
    record.update(overrides)
    return record


def write_release(root, version_code=2, version_name=None, apk=None, directory=None, **overrides):
    version_name = version_name or f"1.0.{version_code}"
    apk = apk if apk is not None else build_apk(binary_manifest(version_code=version_code, version_name=version_name))
    record = release_record(apk, version_code, version_name, **overrides)
    target = root / "releases" / (directory or record["release_id"])
    target.mkdir(parents=True)
    (target / "app.apk").write_bytes(apk)
    (target / "release.json").write_text(json.dumps(record), encoding="utf-8")
    return target, record, apk
