"""Operator-run first login in a local browser. Never takes a password as an argument."""
import argparse
import json
import os
import tempfile
from pathlib import Path

from .store import PlanStore


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", default="default")
    args = parser.parse_args()
    from hermes_cli.profiles import get_profile_dir, profile_exists
    if not profile_exists(args.profile):
        raise SystemExit("Profile not found")
    store = PlanStore(get_profile_dir(args.profile))
    url = store.settings()["manaba_url"]
    if not url:
        raise SystemExit("Set the manaba home URL in Mobile first")
    from playwright.sync_api import sync_playwright
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=False, chromium_sandbox=True)
        try:
            context = browser.new_context()
            page = context.new_page()
            page.goto(url, wait_until="domcontentloaded")
            input("Log in in the browser, return to the manaba home page, then press Enter here: ")
            from urllib.parse import urlsplit
            if urlsplit(page.url).netloc != urlsplit(url).netloc or page.locator('input[type="password"]').count():
                raise SystemExit("Login was not confirmed; previous session is preserved")
            # An atomic 0600 write, outside the repository. Never print state or cookies.
            path = store.root / "manaba-session.json"
            if path.is_symlink():
                raise SystemExit("Session storage is unavailable")
            fd, temporary = tempfile.mkstemp(prefix=".manaba-", dir=store.root)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(context.storage_state(), stream)
                os.replace(temporary, path)
            finally:
                Path(temporary).unlink(missing_ok=True)
            print("Login state saved. No password was stored by this command.")
        finally:
            browser.close()


if __name__ == "__main__":
    main()
