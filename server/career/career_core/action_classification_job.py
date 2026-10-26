"""One explicitly invoked, bounded owner job; no GET hook, collector or scheduler."""
import argparse
import contextlib
import io
import json
import re
import sqlite3
import sys
from pathlib import Path

from .action_classification import HermesActionClassifier, classify_saved_mail_actions
from .coach_execution import ExecutionPolicy
from .coach_store import profile_key
from .store import CareerError, ClosingConnection, Store


class ExistingActionStore(Store):
    """Attach to an existing owner database without initialization or migration."""
    def __init__(self, directory):
        self.directory = Path(directory)
        self.path = self.directory / "career.sqlite3"
        if not self.path.is_file():
            raise CareerError("action_database_missing", "An existing Career database is required.", 409)
        with self.connect() as db:
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"entities", "metadata", "career_mail_messages"} <= tables:
            raise CareerError("action_database_unprepared", "The existing Career schema is required.", 409)

    def connect(self):
        # SQLite mode=rw never creates a replacement if the checked DB disappears.
        db = sqlite3.connect(self.path.resolve().as_uri() + "?mode=rw", uri=True,
                             timeout=30, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=30000")
        db.execute("PRAGMA foreign_keys=ON")
        return db


def run_once(store, profile, limit, *, execution=None, max_pages=3, policy=None, factory=None):
    """Testable production entry: configure through policy, classify once, return counts."""
    if (type(limit) is not int or not 1 <= limit <= 10 or
            type(max_pages) is not int or not 1 <= max_pages <= 10):
        raise CareerError("action_batch_limit", "Choose 1 to 10 items and scan pages.")
    profile = profile_key(profile)
    policy = policy or ExecutionPolicy(store)
    selected = execution if execution is not None else policy.daily(profile).get("execution")
    if not selected:
        raise CareerError("model_not_configured", "Select an existing authorized model before running.", 409)
    classifier = HermesActionClassifier(selected, policy, factory)
    result = classify_saved_mail_actions(store, profile, classifier, limit=limit, max_pages=max_pages)
    return dict(status="completed", execution=classifier.execution, **result)


class QuietStream(io.TextIOBase):
    """Discard runtime prints rather than retaining private source/model text in logs."""
    def write(self, text):
        return len(text)

    def flush(self):
        pass


def main(argv=None):
    parser = argparse.ArgumentParser(description="Classify one bounded batch of saved Career mail as its authorized owner.")
    parser.add_argument("--data-dir", required=True, help="Existing directory containing career.sqlite3.")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--limit", required=True, type=int, choices=range(1, 11))
    parser.add_argument("--scan-pages", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--provider")
    parser.add_argument("--model")
    parser.add_argument("--reasoning-effort")
    parser.add_argument("--execute", action="store_true", required=True,
                        help="Explicitly authorize this one run, including model calls and cache writes.")
    args = parser.parse_args(argv)
    supplied = [args.provider, args.model, args.reasoning_effort]
    if any(value is not None for value in supplied) and not all(value is not None for value in supplied):
        parser.error("Provide provider, model and reasoning-effort together, or use the saved configured execution.")
    execution = dict(provider=args.provider, model=args.model, reasoning_effort=args.reasoning_effort) if supplied[0] is not None else None
    try:
        with contextlib.redirect_stdout(QuietStream()), contextlib.redirect_stderr(QuietStream()):
            store = ExistingActionStore(args.data_dir)
            report = run_once(store, args.profile, args.limit, execution=execution, max_pages=args.scan_pages)
        code = 0
    except CareerError as error:
        reason = error.code if isinstance(error.code, str) and re.fullmatch(r"[a-z0-9_]{1,64}", error.code) else "action_job_failed"
        report, code = dict(status="blocked", reason=reason, cache_results_committed=0), 1
    except Exception:
        report, code = dict(status="blocked", reason="action_job_failed", cache_results_committed=0), 1
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
