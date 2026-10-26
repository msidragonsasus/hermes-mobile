"""One durable server worker, with priority at official-research company boundaries."""
import threading
from datetime import datetime, timedelta, timezone

from .coach_runtime import HermesCoachRuntime
from .coach_store import CoachStore
from .locking import job_lock
from .store import CareerError, Store, encoded

_workers = {}
_guard = threading.Lock()


class CoachWorker:
    def __init__(self, store, runtime_factory=HermesCoachRuntime):
        self.coach = CoachStore(store)
        self.runtime_factory = runtime_factory
        self.runtime = None
        self.current_run = None
        self.stopping = threading.Event()
        self.wake = threading.Event()
        self.thread = None

    def start(self):
        if self.thread is None or not self.thread.is_alive():
            self.thread = threading.Thread(target=self.loop, name="career-coach", daemon=True)
            self.thread.start()
        self.wake.set()

    def close(self):
        self.stopping.set()
        self.wake.set()
        if self.runtime:
            self.runtime.stop()

    def cancel(self, run_id):
        if self.current_run == run_id and self.runtime:
            self.runtime.stop()

    def one(self):
        run = self.coach.claim()
        if not run:
            return False
        self.current_run = run["id"]
        monitor_done = threading.Event()
        def monitor_stop():
            while not monitor_done.wait(0.5):
                try:
                    if self.stopping.is_set() or self.coach.run(run["id"])["cancel_requested"]:
                        if self.runtime:
                            self.runtime.stop()
                except CareerError:
                    return
        monitor = threading.Thread(target=monitor_stop, name="career-coach-stop", daemon=True)
        try:
            if run["kind"] == "collection":
                self.coach.finish(run["id"], "cancelled", {"reason": "portal_collection_retired"})
                return True
            self.runtime = self.runtime_factory(self.coach)
            from .api import sync_job
            from .sharing import pin_run_context
            monitor.start()
            sync_status = sync_job(self.coach.store)
            with self.coach.store.transaction() as db:
                self.coach.store.set_meta(db, "sync_status", sync_status)
            pin_run_context(self.coach, run)
            with self.coach.store.connect() as db:
                self.coach.check_target(db, run["request"].get("target"), run["id"])
            if self.coach.run(run["id"])["cancel_requested"] or self.stopping.is_set():
                self.coach.finish(run["id"], "cancelled")
                return True
            # Automated research summarizes stored private sources. Portal and Gmail
            # collection are program-owned; no public-site crawler or ES reader runs here.
            from .mail_context import prepare as prepare_mail
            prepare_mail(self.coach.store, run,
                         lambda: self.stopping.is_set() or self.coach.run(run["id"])["cancel_requested"])
            if self.stopping.is_set() or self.coach.run(run["id"])["cancel_requested"]:
                self.coach.finish(run["id"], "cancelled")
                return True
            reply = self.runtime.run(run)
            checkpoint = self.coach.snapshot(run["profile"], thread_id=run.get("thread_id", "global"))["checkpoint"]
            current_checkpoint = checkpoint if checkpoint.get("run_id") == run["id"] else {}
            state = "waiting_user" if current_checkpoint.get("waiting_user") else "completed"
            self.coach.finish(run["id"], state, {"artifact_ids": sorted({item["id"] for item in self.coach.artifacts(run["id"])})}, reply=reply)
            # A successful local save is retained even if the existing GitHub path is unavailable.
            from .api import sync_job
            sync_status = sync_job(self.coach.store)
            with self.coach.store.transaction() as db:
                latest = self.coach.decode(db.execute("SELECT * FROM coach_runs WHERE id=?", (run["id"],)).fetchone())
                result = dict(latest["result"], sharing_save=sync_status)
                db.execute("UPDATE coach_runs SET result=? WHERE id=?", (encoded(result), run["id"]))
                self.coach.event(db, run["profile"], run["id"], "run", dict(result, state=latest["state"]))
        except CareerError as error:
            self.coach.finish(run["id"], "paused", {"reason": error.code, "message": error.detail})
        except Exception:
            self.coach.finish(run["id"], "paused", {"reason": "runtime_failed", "message": "作業が中断しました。保存済みの結果は保持しています。"})
        finally:
            monitor_done.set()
            if monitor.ident:
                monitor.join(timeout=2)
            self.runtime = None
            self.current_run = None
        return True

    def loop(self):
        with job_lock(self.coach.store.directory / "coach-worker.lock") as acquired:
            if not acquired:
                return
            self.coach.recover()
            while not self.stopping.is_set():
                if not self.one():
                    self.wake.wait(1)
                    self.wake.clear()


def worker(store):
    key = str(store.path.resolve())
    with _guard:
        if key not in _workers or _workers[key].stopping.is_set():
            _workers[key] = CoachWorker(store)
        return _workers[key]


def enqueue_daily(store, at=None):
    from .collection_store import CollectionStore
    coach = CoachStore(store)
    day = (at or datetime.now(timezone(timedelta(hours=9)))).strftime("%Y-%m-%d")
    with store.connect() as db:
        profiles = {"default"}
        profiles.update(r[0] for r in db.execute("SELECT DISTINCT profile FROM coach_runs WHERE kind='message'"))
        profiles.update(grant["profile"] for grant in CollectionStore(store).grants())
    for profile in profiles:
        with store.connect() as db:
            pending = db.execute("SELECT 1 FROM coach_runs WHERE profile=? AND kind='daily' AND state IN ('queued','running','paused')", (profile,)).fetchone()
        if not pending:
            coach.submit({"text": "保存済みのマイページ案内・企業メールで確認できた変更だけを整理してください。公開サイトの調査やログイン・情報取得は行いません。変更がなければ今日の提案や回答待ちを作りません。依頼していないES作成・面接練習は始めません。",
                          "operation_id": f"daily:{day}:{profile}"}, profile, kind="daily")
    return {"state": "queued", "profiles": len(profiles)}


def enqueue_collections(store, channel="mail", *, grant_id=None, at=None):
    state = "program_owned" if channel == "mail" else "retired"
    return {"state": state, "collections": []}
