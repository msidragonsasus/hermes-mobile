"""Bounded private pipes for the two fixed collection adapters, never a model tool."""
import contextlib
import json
import os
import signal
import subprocess
import time

from .store import CareerError


def run_private(command, payload, *, cwd, env, timeout, limit, should_cancel=lambda: False):
    if should_cancel():
        raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
    process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, start_new_session=os.name == "posix",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    request = json.dumps(payload).encode("utf-8")
    deadline = time.monotonic() + timeout
    try:
        while True:
            if should_cancel():
                raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError()
            try:
                output, _ = process.communicate(request, timeout=min(0.5, remaining))
                if process.returncode or len(output) > limit:
                    raise ValueError("Private adapter failed")
                return output
            except subprocess.TimeoutExpired:
                # communicate retains the partially sent input; never resend credentials.
                request = None
    finally:
        if process.poll() is None:
            if os.name == "posix":
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.communicate()
