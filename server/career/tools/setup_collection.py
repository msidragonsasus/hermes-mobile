"""Prepare encrypted collector storage on the existing host after deployment approval.

No package upgrade, Gmail login, website login, or collection is performed here.
Missing runtime dependencies stop the setup before any configuration changes.
"""
import base64
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import tempfile

from privileged_paths import prepare_private

BASE = Path("/var/lib/hermes")
SERVICE = "home-hermes-dashboard.service"
KEY = Path("/etc/credstore.encrypted/hermes-career-key")
DROPIN = Path("/etc/systemd/system") / (SERVICE + ".d/70-career-collection.conf")
PRIVATE = BASE / "career-private"
BROWSER = BASE / "career-browser-1.58.0"

PROBE = """
from cryptography.fernet import Fernet
fernet = Fernet(Fernet.generate_key())
assert fernet.decrypt(fernet.encrypt(b'career-contract')) == b'career-contract'
"""


def call(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run with sudo on hermes-node after approving the server change.")
    os.umask(0o077)
    if not shutil.which("systemd-creds"):
        raise SystemExit("systemd-creds is unavailable; no configuration changed.")
    try:
        call(["runuser", "-u", "hermes", "--", "env", "HOME=" + str(BASE), "PYTHONDONTWRITEBYTECODE=1",
              str(BASE / "venv/bin/python"), "-I", "-c", PROBE], capture_output=True, timeout=45)
        import runpy
        browser_check = runpy.run_path(str(Path(__file__).with_name("prepare_browser.py")))["CHECK"]
        call(["runuser", "-u", "hermes", "--", "env", "HOME=" + str(BASE),
              "PLAYWRIGHT_BROWSERS_PATH=" + str(BROWSER / "browsers"),
              str(BROWSER / "bin/python"), "-I", "-c", browser_check], capture_output=True, timeout=45)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SystemExit("Collector runtime is not ready (existing Fernet and separate local Chromium). No configuration changed; review collection_preflight.py and prepare_browser.py first.") from None
    for path in (PRIVATE, DROPIN.parent, KEY.parent):
        if path.is_symlink() or path.resolve() != path:
            raise SystemExit("Unexpected path; no configuration changed.")
    user = pwd.getpwnam("hermes")
    prepare_private(BASE, PRIVATE.name, user)
    KEY.parent.mkdir(mode=0o700, exist_ok=True)
    if KEY.is_symlink() or (KEY.exists() and KEY.stat().st_mode & 0o077):
        raise SystemExit("Existing encrypted key permissions differ; no key was replaced.")
    if not KEY.exists():
        # Only systemd receives plaintext key bytes through stdin; no temporary plaintext key file.
        with tempfile.TemporaryDirectory(prefix="career-key-", dir=KEY.parent) as temporary:
            output = Path(temporary) / "encrypted"
            call(["systemd-creds", "encrypt", "--name=career-key", "-", str(output)],
                 input=base64.urlsafe_b64encode(os.urandom(32)), capture_output=True)
            os.chmod(output, 0o600)
            output.replace(KEY)
    DROPIN.parent.mkdir(parents=True, exist_ok=True)
    if DROPIN.is_symlink():
        raise SystemExit("Unexpected service drop-in; configuration retained.")
    previous = DROPIN.read_bytes() if DROPIN.exists() else None
    desired = ("[Service]\nLoadCredentialEncrypted=career-key:" + str(KEY) + "\nEnvironment=CAREER_PRIVATE_DIR=" + str(PRIVATE) +
               "\nEnvironment=CAREER_BROWSER_PYTHON=" + str(BROWSER / "bin/python") +
               "\nEnvironment=PLAYWRIGHT_BROWSERS_PATH=" + str(BROWSER / "browsers") +
               "\nReadWritePaths=" + str(PRIVATE) + "\n").encode("utf-8")
    if previous == desired:
        print("COLLECTION_STORAGE_ALREADY_CONFIGURED: no restart needed.")
        return
    backup = BASE / "career-update-backups"
    backup.mkdir(mode=0o700, exist_ok=True)
    if previous is not None:
        with tempfile.NamedTemporaryFile(prefix="collection-dropin-", suffix=".conf", dir=backup, delete=False) as saved:
            saved.write(previous)
    try:
        DROPIN.write_bytes(desired)
        call(["systemctl", "daemon-reload"], capture_output=True)
        call(["systemctl", "restart", SERVICE], capture_output=True)
        call(["systemctl", "is-active", "--quiet", SERVICE], capture_output=True)
    except Exception:
        if previous is None:
            DROPIN.unlink(missing_ok=True)
        else:
            DROPIN.write_bytes(previous)
        call(["systemctl", "daemon-reload"], capture_output=True)
        call(["systemctl", "restart", SERVICE], capture_output=True)
        raise SystemExit("Storage activation failed; previous drop-in restored. Keys, data and credentials retained.") from None
    print("COLLECTION_STORAGE_READY: key delivered by systemd; collection remains controlled by app settings.")


if __name__ == "__main__":
    main()
