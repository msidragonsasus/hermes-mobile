"""Dedicated mail worker. It never enters the coach/LLM run queue."""
import threading
import time

from .locking import job_lock
from .mail_collector import MailCollector
from .store import CareerError

_workers = {}
_guard = threading.Lock()


class MailWorker:
    def __init__(self, store, collector_factory=MailCollector):
        self.store = store
        self.collector_factory = collector_factory
        self.stopping = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.start_guard = threading.Lock()

    def start(self):
        with self.start_guard:
            if self.thread is None or not self.thread.is_alive():
                self.thread = threading.Thread(target=self.loop, name="career-mail", daemon=True)
                self.thread.start()
        self.wake.set()

    def close(self):
        self.stopping.set()
        self.wake.set()

    def loop(self):
        with job_lock(self.store.directory / "mail-worker.lock") as acquired:
            if not acquired:
                return
            next_check = 0.0
            idle = True
            observed_request = None
            while not self.stopping.is_set():
                now = time.monotonic()
                if now >= next_check:
                    try:
                        result = self.collector_factory(self.store).collect(should_cancel=self.stopping.is_set)
                        delay = max(0.25, result.get("delay", 300))
                        idle = result.get("state") in {"unchanged", "no_senders"}
                    except CareerError as error:
                        idle = error.code == 'mail_collection_disabled'
                        delay = 300 if idle else 5
                    except Exception:
                        delay, idle = 5, False
                    next_check = time.monotonic() + delay
                timeout = max(0.0, min(5.0, next_check - time.monotonic()))
                self.wake.wait(timeout)
                if self.wake.is_set():
                    self.wake.clear()
                    if idle:
                        next_check = min(next_check, time.monotonic() + 0.25)
                from .mail_store import MailStore
                mail = MailStore(self.store)
                settings = mail.settings()
                with self.store.connect() as db:
                    generation = self.store.meta(db, "mail:request:" + str(settings.get("account_id")), 0)
                current_request = (settings.get("account_id"), settings.get("enabled"), generation)
                if current_request != observed_request and idle:
                    next_check = min(next_check, time.monotonic() + 0.25)
                observed_request = current_request


def worker(store):
    key = str(store.path.resolve())
    with _guard:
        if key not in _workers or _workers[key].stopping.is_set():
            _workers[key] = MailWorker(store)
        return _workers[key]
