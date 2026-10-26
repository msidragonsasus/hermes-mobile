"""Optional isolated Chromium runtime. Credentials travel only through private pipes."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys

from .collection_store import PILOT
from .private_process import run_private
from .store import CareerError


def fetch_in_process(secrets, python, should_cancel=lambda: False, *, notices=False):
    value = secrets.read("portal-" + PILOT)
    # Chromium needs no Google/Codex credentials or systemd encryption key.
    names = {"HOME", "USER", "PATH", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP", "SYSTEMROOT",
             "PLAYWRIGHT_BROWSERS_PATH", "CAREER_CHROMIUM_EXECUTABLE"}
    env = {k: v for k, v in os.environ.items() if k in names}
    env.update(PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
    try:
        output = run_private([python, "-m", "career_core.portal_process"] + (["--notices"] if notices else []), value,
            cwd=Path(__file__).resolve().parents[1], env=env, timeout=120, limit=2 * 1024 * 1024, should_cancel=should_cancel)
    except (OSError, ValueError):
        raise CareerError("browser_unavailable", "専用ブラウザを開始できません。", 503) from None
    try:
        response = json.loads(output)
        errors = {"authentication_required", "authentication_challenge", "portal_structure_changed", "question_limit_conflict", "browser_unavailable"}
        if response.get("error"):
            code = response["error"] if response["error"] in errors else "browser_unavailable"
            message = "マイページへのログインを確認してください。" if code.startswith("authentication") else "マイページの取得状況を確認してください。"
            raise CareerError(code, message, 409)
        if not isinstance(response.get("page"), dict) or not isinstance(response.get("storage_state"), dict):
            raise ValueError()
        value["storage_state"] = response["storage_state"]
        secrets.write("portal-" + PILOT, value)
        return response["page"]
    except (ValueError, TypeError, KeyError, AttributeError):
        raise CareerError("browser_unavailable", "マイページの取得結果を確認できません。", 503) from None


def child_main():
    captured = io.StringIO()
    try:
        value = json.loads(sys.stdin.buffer.read(1024 * 1024 + 1))

        class PipeCredentials:
            def read(self, reference):
                if reference != "portal-" + PILOT:
                    raise ValueError()
                return value

            def write(self, reference, updated):
                if reference != "portal-" + PILOT:
                    raise ValueError()
                self.storage_state = updated["storage_state"]

        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            credentials = PipeCredentials()
            if "--notices" in sys.argv:
                from .mypage_reader import NoticeReader
                page = NoticeReader(credentials).fetch()
            else:
                from .portal_reader import AxolReader
                page = AxolReader(credentials).fetch()
            result = {"page": page, "storage_state": credentials.storage_state}
    except CareerError as error:
        result = {"error": error.code}
    except Exception:
        result = {"error": "browser_unavailable"}
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False).encode("utf-8"))


if __name__ == "__main__":
    child_main()
