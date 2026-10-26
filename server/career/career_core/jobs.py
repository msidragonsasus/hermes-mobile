"""Usage: python -m career_core.jobs sync|research. No network listener."""
import sys

from .api import sync_job
from .coach_worker import enqueue_daily
from .mail_store import MailStore
from .store import Store


def main():
    store = Store()
    action = sys.argv[1] if len(sys.argv) == 2 else ""
    if action not in {"sync", "research", "collect"}:
        raise SystemExit("Choose sync, research or collect")
    result = sync_job(store) if action == "sync" else (MailStore(store).request_sync() if action == "collect" else enqueue_daily(store))
    print(result["state"])  # No personal data or exception bodies in journal.
    return 0 if result["state"] in {"synced", "completed", "running", "queued"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
