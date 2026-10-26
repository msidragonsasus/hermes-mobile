"""Prepare a separate local Chromium runtime after deployment approval.

Only --install creates a versioned directory and downloads official packages.
Does not change Hermes's venv, Google OAuth, service configuration or OS packages.
"""
import os
from pathlib import Path
import pwd
import subprocess
import sys

from privileged_paths import prepare_stage, publish_stage

BASE = Path("/var/lib/hermes")
RUNTIME = BASE / "career-browser-1.58.0"
CHECK = """
import importlib.metadata
assert importlib.metadata.version('playwright') == '1.58.0'
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, chromium_sandbox=True)
    browser.close()
print('BROWSER_OK: isolated Chromium launch; no website or login')
"""


def call(root, arguments):
    return subprocess.run(["runuser", "-u", "hermes", "--", "env", "-u", "PIP_EXTRA_INDEX_URL", "-u", "PIP_INDEX_URL",
        "HOME=" + str(BASE), "PIP_CONFIG_FILE=/dev/null", "PLAYWRIGHT_BROWSERS_PATH=" + str(root / "browsers"),
        *map(str, arguments)], check=True, timeout=600)


def main(install=False):
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run with sudo on hermes-node; this requires the existing administrator login.")
    if BASE.is_symlink() or BASE.resolve() != BASE or RUNTIME.is_symlink():
        raise SystemExit("Unexpected runtime path; no changes made.")
    if RUNTIME.exists():
        call(RUNTIME, [RUNTIME / "bin/python", "-I", "-c", CHECK])
        return
    if not install:
        raise SystemExit("Separate browser runtime is not prepared. Use --install only after approving this change.")
    os.umask(0o077)
    user = pwd.getpwnam("hermes")
    stage, stage_identity = prepare_stage(BASE, "career-browser-stage-", user)
    try:
        call(stage, ["/usr/bin/python3", "-m", "venv", str(stage)])
        call(stage, [stage / "bin/python", "-m", "pip", "install", "--disable-pip-version-check", "--no-input",
            "--only-binary=:all:", "--index-url", "https://pypi.org/simple", "playwright==1.58.0"])
        call(stage, [stage / "bin/python", "-m", "playwright", "install", "chromium"])
        call(stage, [stage / "bin/python", "-I", "-c", CHECK])
        publish_stage(stage, RUNTIME, stage_identity)
        # Python -m entry points keep working after rename; no relocated console scripts are used.
        call(RUNTIME, [RUNTIME / "bin/python", "-I", "-c", CHECK])
    except Exception:
        print("Browser preparation failed; existing Hermes packages and service were not changed.")
        print("New runtime files are retained for inspection:", RUNTIME if RUNTIME.exists() else stage)
        raise
    print("BROWSER_PREPARED:", RUNTIME)
    print("Service activation and website verification are separate steps.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true")
    main(parser.parse_args().install)
