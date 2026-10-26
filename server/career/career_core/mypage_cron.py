"""Hermes Cron adapter. No polling loop, second scheduler or paid browser service."""
import os
from pathlib import Path

from .store import CareerError


class HermesMyPageCron:
    def __init__(self, records, home=None):
        self.records = records
        if home is None:
            try:
                from hermes_constants import get_default_hermes_root
                home = os.environ.get("CAREER_MYPAGE_BOT_HOME") or Path(get_default_hermes_root()) / "profiles" / "career-mypage"
            except ImportError:
                raise CareerError("hermes_runtime_missing", "HermesのBot実行環境が必要です。", 503) from None
        self.home = Path(home).resolve()
        if not (self.home / "config.yaml").is_file():
            raise CareerError("mypage_bot_not_ready", "マイページ確認Botの準備が必要です。", 503)

    def scope(self):
        from cron.scheduler_provider import _profile_cron_scope
        return _profile_cron_scope(self.home)

    def ensure(self, row):
        from cron.jobs import create_job, get_job, list_jobs, pause_job, resume_job
        from cron.scheduler_provider import resolve_cron_scheduler
        from .locking import job_lock
        with job_lock(self.records.store.directory / "mypage-cron-config.lock") as acquired:
            if not acquired:
                raise CareerError("cron_configuration_busy", "接続設定を更新中です。再試行してください。", 409)
            latest = self.records.get(row["id"])
            if not latest or latest["version"] != row["version"]:
                raise CareerError("connection_changed", "接続設定が変更されました。", 409)
            row = latest
            with self.scope():
                from hermes_time import get_timezone
                if str(get_timezone()) != "Asia/Tokyo":
                    raise CareerError("mypage_timezone_mismatch", "Botの実行時間帯を日本時間に設定してください。", 503)
                identity = row["id"]
                script_name = "career-mypage-" + identity + ".py"
                scripts = self.home / "scripts"
                scripts.mkdir(exist_ok=True, mode=0o700)
                script = scripts / script_name
                # Only host-owned paths and hash IDs are embedded, never a URL/password/model text.
                contents = ("import sys\nfrom pathlib import Path\n"
                            f"sys.path.insert(0, {str(Path(__file__).resolve().parents[1])!r})\n"
                            "from career_core.mypage_job import main\n"
                            f"raise SystemExit(main({str(self.records.store.directory.resolve())!r}, {identity!r}))\n")
                if not script.exists() or script.read_text(encoding="utf-8") != contents:
                    script.write_text(contents, encoding="utf-8")
                    script.chmod(0o700)
                job = get_job(row["config"].get("job_id")) if row["config"].get("job_id") else None
                if not job:
                    # Recover a create whose DB acknowledgement was lost.
                    job = next((j for j in list_jobs(include_disabled=True) if j.get("script") == script_name), None)
                if not job:
                    job = create_job(prompt=None, schedule="0 9,18 * * *", name="マイページ確認 " + identity,
                                     deliver="local", script=script_name, no_agent=True)
                    resolve_cron_scheduler().register_job(job)
                if job.get("script") != script_name:
                    raise CareerError("cron_binding_mismatch", "定期確認の接続先を確認してください。", 409)
                with self.records.store.connect() as db:
                    self.records.active(row, db)
                enabled = row["config"].get("enabled") and row["config"].get("verified") and row["status"].get("state") != "auth_required"
                if enabled and not job.get("enabled"):
                    job = resume_job(job["id"])
                elif not enabled:
                    job = pause_job(job["id"], reason="connection_not_ready")
                self.records.update_config(identity, row["version"], {"job_id": job["id"], "schedule_error": None,
                    "next_run_at": job.get("next_run_at") if enabled else None})
                return job

    def claim(self, row):
        from cron.scheduler_provider import resolve_cron_scheduler
        job = self.ensure(row)
        with self.scope():
            provider = resolve_cron_scheduler()
            claim = provider.claim_fire(job["id"], force=True)
            if not claim:
                raise CareerError("collection_running", "確認はすでに受け付けています。", 409)
            return claim

    def fire(self, claim, run):
        from cron.scheduler_provider import resolve_cron_scheduler
        from .mypage_job import report_failure
        try:
            with self.scope():
                resolve_cron_scheduler().fire_claimed(claim)
        except Exception as error:
            report_failure(error, "cron_dispatch", run["id"])
        finally:
            # Native dispatch is synchronous, but can fail before the child claims
            # the Career run or return after terminating a child without a result.
            # finish preserves terminal/cancelled runs and their saved evidence.
            self.records.finish_staged(run)

    def pause(self, row):
        from cron.jobs import pause_job
        with self.scope():
            if row["config"].get("job_id"):
                pause_job(row["config"]["job_id"], reason="company_disabled")


def reconcile(records, row):
    try:
        HermesMyPageCron(records).ensure(row)
    except Exception as error:
        code = error.code if isinstance(error, CareerError) else "hermes_contract_unsupported"
        try:
            records.update_config(row["id"], row["version"], {"schedule_error": code, "next_run_at": None})
        except CareerError as changed:
            if changed.code != "connection_changed":
                raise


def stop_company(store, company_id):
    from .mypage_bot import MyPageBotStore
    from .store import encoded
    records = MyPageBotStore(store)
    with store.transaction() as db:
        ids = [r[0] for r in db.execute("SELECT id FROM career_mypage_connections WHERE company_id=?", (company_id,))]
        for identity in ids:
            row = records.get(identity, db)
            row["config"].update(enabled=False, next_run_at=None)
            row["status"].update(state="disabled", reason=None)
            db.execute("UPDATE career_mypage_connections SET version=version+1,config=?,status=? WHERE id=?", (encoded(row["config"]), encoded(row["status"]), identity))
            db.execute("UPDATE career_mypage_runs SET state='cancelled' WHERE connection_id=? AND state IN ('queued','running')", (identity,))
    for identity in ids:
        try:
            HermesMyPageCron(records).pause(records.get(identity))
        except Exception:
            # The durable disabled/version checks also stop already claimed executions.
            pass
