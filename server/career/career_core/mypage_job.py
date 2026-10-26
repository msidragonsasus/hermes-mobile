"""One bounded Hermes Cron execution; safe status only on stdout."""
import time
import json
import sys
from pathlib import Path
from datetime import datetime, timezone
from contextvars import ContextVar
from .store import CareerError, Store
from .locking import job_lock
from .mypage_bot import MyPageBotStore

_cron_output = ContextVar("career_mypage_cron_output", default=False)


def report_event(event, run_id, **metadata):
    """Internal callers provide only fixed categories/counts/flags, never content."""
    print(json.dumps({"event": event, "run_id": run_id,
                      "at": datetime.now(timezone.utc).isoformat(), **metadata}),
          file=sys.stdout if _cron_output.get() else sys.stderr, flush=True)


def report_failure(error, phase, run_id=None):
    """Emit code locations only; never exception text, source lines or locals."""
    code = error.code if isinstance(error, CareerError) else (
        "hermes_runtime_missing" if isinstance(error, ImportError) else "mypage_execution_failed")
    frames, trace = [], error.__traceback__
    while trace:
        frames.append({"file": Path(trace.tb_frame.f_code.co_filename).name, "line": trace.tb_lineno})
        trace = trace.tb_next
    report_event("mypage_failure", run_id, phase=phase, code=code,
                 exception_type=type(error).__name__, frames=frames[-8:])
    return code


def main(directory, identity):
    # Hermes no-agent Cron retains stdout on success, but discards stderr.
    # Route only our structured, content-free events to its normal run history.
    token = _cron_output.set(True)
    try:
        return _main(directory, identity)
    finally:
        _cron_output.reset(token)


def _main(directory, identity):
    store = Store(directory)
    records = MyPageBotStore(store)
    # Serialize browser runs across companies/processes. No separate worker daemon.
    limit = time.monotonic() + 480
    while time.monotonic() < limit:
        with job_lock(store.directory / "mypage-browser.lock") as acquired:
            if acquired:
                return run_one(records, identity)
        time.sleep(1)
    print("queued")
    return 1


def run_one(records, identity):
    run = None
    phase = "claim"
    try:
        run = records.claim(identity)
        if run is None:
            if not records.enqueue(identity, scheduled=True):
                print("disabled")
                return 0
            run = records.claim(identity)
        if run is None:
            print("already_running")
            return 0
        phase = "execute"
        if records.get(identity)["config"].get("engine") == "standard":
            from .mypage_native import execute
        else:
            from .mypage_agent import execute
        execute(records.store, records.get(identity), run)
        phase = "read_result"
        with records.store.connect() as db:
            state = db.execute("SELECT state FROM career_mypage_runs WHERE id=?", (run["id"],)).fetchone()[0]
        print(state)
        return 0 if state == "completed" else 1
    except Exception as error:
        code = report_failure(error, phase, run["id"] if run else None)
        if run:
            try:
                records.finish_staged(run, "auth_required" if code.startswith("authentication") else None, code)
            except CareerError:
                pass
        print(code)
        return 1
    finally:
        # Force-fire resumes Hermes jobs; always restore the connection's desired schedule.
        try:
            from .mypage_cron import reconcile
            row = records.get(identity)
            if row:
                reconcile(records, row)
        except Exception as error:
            report_failure(error, "reconcile", run["id"] if run else None)
