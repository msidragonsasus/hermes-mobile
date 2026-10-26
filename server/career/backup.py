"""SQLite consistent backup to a new operator-selected path. Never overwrites a backup."""
import os
import contextlib
import sqlite3
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python backup.py NEW_BACKUP_PATH")
    source = Path(os.environ.get("CAREER_DATA_DIR", "/var/lib/hermes/career")) / "career.sqlite3"
    target = Path(sys.argv[1]).resolve()
    if not source.is_file() or target == source.resolve():
        raise SystemExit("Source missing or backup target equals source")
    descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    with contextlib.closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)) as src:
        with contextlib.closing(sqlite3.connect(target)) as dest:
            src.backup(dest)
            if dest.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise SystemExit("Backup integrity failed; original database retained")
    print("Backup created and integrity checked")


if __name__ == "__main__":
    main()
