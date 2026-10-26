"""Profile-scoped portal connections and durable, evidence-backed Bot runs.

Only the credential reference reaches SQLite. Hermes owns scheduling/execution.
"""
import json
import re
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit, urlunsplit

from .collection_store import CollectionStore, digest
from .store import CareerError, encoded, now


def portal_url(value):
    if not isinstance(value, str) or len(value) > 2048:
        raise CareerError("invalid_portal_url", "HTTPSのログインURLを入力してください。")
    p = urlsplit(value.strip())
    try:
        valid = p.scheme == "https" and p.hostname and not p.username and not p.password and p.port in (None, 443)
    except ValueError:
        valid = False
    if not valid or re.search(r"[\s\\\x00-\x1f]", value):
        raise CareerError("invalid_portal_url", "HTTPSのログインURLを入力してください。")
    return urlunsplit((p.scheme, p.netloc.lower(), p.path or "/", p.query, ""))


def connection_id(company, profile):
    from .coach_store import profile_key
    return "mp-" + digest(profile_key(profile), company)[:32]


def target_year(text):
    years = {a or b for a, b in re.findall(r"(20\d{2})\s*(?:年卒|年度)|(?:対象年度|卒業年度)\s*[:：]?\s*(20\d{2})", text)}
    return next(iter(years)) if len(years) == 1 else None


class MyPageBotStore:
    def __init__(self, store, secrets=None):
        self.store, self.secrets = store, secrets
        self.collection = CollectionStore(store)
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS career_mypage_connections(
                    id TEXT PRIMARY KEY, company_id TEXT NOT NULL, profile TEXT NOT NULL,
                    version INTEGER NOT NULL, config TEXT NOT NULL, status TEXT NOT NULL,
                    UNIQUE(company_id,profile));
                CREATE TABLE IF NOT EXISTS career_mypage_runs(
                    id TEXT PRIMARY KEY, connection_id TEXT NOT NULL, version INTEGER NOT NULL,
                    state TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS career_mypage_bot_items(
                    connection_id TEXT NOT NULL, source_key TEXT NOT NULL, evidence_id TEXT NOT NULL,
                    data TEXT NOT NULL, PRIMARY KEY(connection_id,source_key));
                CREATE TABLE IF NOT EXISTS career_mypage_checkpoints(
                    run_id TEXT NOT NULL, source_key TEXT NOT NULL, data TEXT NOT NULL,
                    PRIMARY KEY(run_id,source_key));
            """)

    def secret_store(self):
        if self.secrets is None:
            from .secret_store import SecretStore
            self.secrets = SecretStore()
        return self.secrets

    def get(self, identity, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.get(identity, connection)
        row = db.execute("SELECT * FROM career_mypage_connections WHERE id=?", (identity,)).fetchone()
        return dict(row, config=json.loads(row["config"]), status=json.loads(row["status"])) if row else None

    def active(self, row, db):
        company = self.store.entity(db, "companies", row["company_id"])
        if not company or company["data"].get("deleted_at"):
            raise CareerError("company_missing", "この企業は取得対象ではありません。", 404)
        if company["data"].get("user_stage") == "closed":
            raise CareerError("company_closed", "終了した企業の巡回は停止しています。管理から選考状況を変更してください。", 409)

    def configure(self, company, profile, payload):
        from .coach_store import profile_key
        profile = profile_key(profile)
        identity = connection_id(company, profile)
        if set(payload) - {"company_id", "url", "login_id", "password", "enabled", "expected_version"}:
            raise CareerError("invalid_request", "接続設定を確認してください。")
        url = portal_url(payload.get("url"))
        # Resolve before accepting a destination; the browser rechecks each request.
        from .mypage_browser import public_host
        public_host(url)
        if type(payload.get("enabled")) is not bool or type(payload.get("expected_version")) is not int:
            raise CareerError("invalid_request", "接続設定を確認してください。")
        with self.store.transaction() as db:
            old = self.get(identity, db)
            self.active(old or {"company_id": company}, db)
            if payload["expected_version"] != (old["version"] if old else 0):
                raise CareerError("version_conflict", "接続設定が変更されています。読み直してください。", 409)
            previous = self.secret_store().read(old["config"]["secret_ref"]) if old else {}
            login = payload.get("login_id") or previous.get("login_id")
            password = payload.get("password") or previous.get("password")
            if not isinstance(login, str) or not 1 <= len(login) <= 500 or not isinstance(password, str) or not 1 <= len(password) <= 2000:
                raise CareerError("authentication_required", "取得用のIDとパスワードを入力してください。", 409)
            changed = not old or url != old["config"]["url"] or login != previous.get("login_id") or password != previous.get("password")
            if old and url != old["config"]["url"] and not (payload.get("login_id") and payload.get("password")):
                raise CareerError("credentials_required_for_new_url", "URL変更時はIDとパスワードも再入力してください。", 409)
            version = (old["version"] if old else 0) + 1
            reference = identity + "-" + str(version)
            self.secret_store().write(reference, {"login_id": login, "password": password})
            config = {"url": url, "secret_ref": reference, "enabled": payload["enabled"],
                      "verified": False if changed else old["config"].get("verified", False),
                      "job_id": old["config"].get("job_id") if old else None,
                      # New connections use the deployed standard Bot. Existing
                      # connections keep their route until individually migrated.
                      "engine": old["config"].get("engine", "legacy") if old else "standard"}
            status = dict(old["status"] if old else {}, state="not_started" if changed else "configured", reason=None)
            db.execute("INSERT INTO career_mypage_connections VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                       "version=excluded.version,config=excluded.config,status=excluded.status",
                       (identity, company, profile, version, encoded(config), encoded(status)))
            db.execute("UPDATE career_mypage_runs SET state='cancelled',updated_at=? WHERE connection_id=? AND state IN ('queued','running')", (now(), identity))
            # The person's own save supersedes any coach-requested pending URL.
            from .mypage_request import clear_pending
            clear_pending(db, identity)
        return self.get(identity)

    def update_config(self, identity, version, values):
        with self.store.transaction() as db:
            row = self.get(identity, db)
            if not row or row["version"] != version:
                raise CareerError("connection_changed", "接続設定が変更されました。", 409)
            row["config"].update(values)
            db.execute("UPDATE career_mypage_connections SET config=? WHERE id=?", (encoded(row["config"]), identity))

    def enqueue(self, identity, *, scheduled=False, with_created=False):
        with self.store.transaction() as db:
            row = self.get(identity, db)
            if not row:
                raise CareerError("mypage_not_connected", "取得用の接続を設定してください。", 409)
            self.active(row, db)
            if scheduled and (not row["config"].get("enabled") or not row["config"].get("verified") or row["status"].get("state") == "auth_required"):
                return None
            latest = db.execute("SELECT * FROM career_mypage_runs WHERE connection_id=? AND version=? AND state IN ('queued','running') ORDER BY created_at DESC LIMIT 1", (identity, row["version"])).fetchone()
            expiry = 120 if latest and latest["state"] == "queued" else 12
            if latest and latest["updated_at"] > (datetime.now(timezone.utc) - timedelta(minutes=expiry)).isoformat():
                return (dict(latest), False) if with_created else dict(latest)
            if latest:
                staged = db.execute("SELECT 1 FROM career_mypage_checkpoints WHERE run_id=? LIMIT 1", (latest["id"],)).fetchone()
                db.execute("UPDATE career_mypage_runs SET state=?,result=?,updated_at=? WHERE id=?", ("partial" if staged else "failed", encoded({"reason": "collection_interrupted"}), now(), latest["id"]))
            run = {"id": uuid.uuid4().hex, "connection_id": identity, "version": row["version"], "state": "queued", "created_at": now(), "updated_at": now(), "result": "{}"}
            db.execute("INSERT INTO career_mypage_runs VALUES(:id,:connection_id,:version,:state,:created_at,:updated_at,:result)", run)
            status = dict(row["status"], state="queued", run_id=run["id"], reason=None, last_attempt_at=now())
            db.execute("UPDATE career_mypage_connections SET status=? WHERE id=?", (encoded(status), identity))
            return (run, True) if with_created else run

    def claim(self, identity):
        with self.store.transaction() as db:
            row = self.get(identity, db)
            self.active(row, db)
            run = db.execute("SELECT * FROM career_mypage_runs WHERE connection_id=? AND version=? AND state='queued' ORDER BY created_at LIMIT 1", (identity, row["version"])).fetchone()
            if not run:
                return None
            started = now()
            db.execute("UPDATE career_mypage_runs SET state='running',updated_at=? WHERE id=?", (started, run["id"]))
            status = dict(row["status"], state="running", run_id=run["id"], reason=None, last_attempt_at=started)
            db.execute("UPDATE career_mypage_connections SET status=? WHERE id=?", (encoded(status), identity))
            return dict(run, state="running", updated_at=started)

    def checkpoint(self, run, item):
        """Persist each observed source without marking the running job complete."""
        with self.store.transaction() as db:
            row = self.get(run["connection_id"], db)
            self.active(row, db)
            current = db.execute("SELECT state,result FROM career_mypage_runs WHERE id=?", (run["id"],)).fetchone()
            if not current or current["state"] != "running" or row["version"] != run["version"]:
                raise CareerError("collection_cancelled", "接続設定が変更されました。", 409)
            source = digest(item["url"], item["title"])
            db.execute("INSERT INTO career_mypage_checkpoints VALUES(?,?,?) ON CONFLICT(run_id,source_key) DO UPDATE SET data=excluded.data", (run["id"], source, encoded(item)))
            changed = self._save_items(db, row, [item])
            result = json.loads(current["result"])
            result["checkpoint_changed"] = result.get("checkpoint_changed", 0) + changed
            db.execute("UPDATE career_mypage_runs SET result=? WHERE id=?", (encoded(result), run["id"]))

    def checkpoints(self, run):
        with self.store.connect() as db:
            return [json.loads(r[0]) for r in db.execute("SELECT data FROM career_mypage_checkpoints WHERE run_id=?", (run["id"],))]

    def finish_staged(self, run, state=None, reason="collection_interrupted"):
        items = self.checkpoints(run)
        self.finish(run, state or ("partial" if items else "failed"), reason, items)

    def _save_items(self, db, row, items):
        from .mypage import deadlines
        changed = 0
        notice_ready = self.store.meta(db, "notice-mypage-ready:" + digest(row["id"], row["config"]["url"]), False)
        for item in items:
            source = "mypage-bot:" + row["id"] + ":" + digest(item["url"], item["title"])
            body = item["quote"]
            evidence, fresh = self.collection.save_evidence(db, source_key=source, company_id=row["company_id"], kind="mypage", title=item["title"], locator=item["url"], text=body)
            data = dict(item, deadlines=deadlines(body), target_year=target_year(body), observed_at=now())
            db.execute("INSERT INTO career_mypage_bot_items VALUES(?,?,?,?) ON CONFLICT(connection_id,source_key) DO UPDATE SET evidence_id=excluded.evidence_id,data=excluded.data", (row["id"], source, evidence, encoded(data)))
            if fresh:
                self.collection.notice(db, evidence, row["company_id"], None,
                                       "source_changed" if notice_ready else "initial_source", item["title"])
                changed += 1
        return changed

    def finish(self, run, state, reason=None, items=()):
        with self.store.transaction() as db:
            row = self.get(run["connection_id"], db)
            current = db.execute("SELECT state,result FROM career_mypage_runs WHERE id=?", (run["id"],)).fetchone()
            if not current or current[0] not in {"queued", "running"}:
                return
            self.active(row, db)
            if row["version"] != run["version"]:
                db.execute("UPDATE career_mypage_runs SET state='cancelled',updated_at=? WHERE id=?", (now(), run["id"]))
                return
            changed = json.loads(current["result"]).get("checkpoint_changed", 0)
            if state in {"completed", "partial"}:
                if state == "completed":
                    db.execute("DELETE FROM career_mypage_bot_items WHERE connection_id=?", (row["id"],))
                changed += self._save_items(db, row, items)
            status = dict(row["status"], state=("updated" if changed else "unchanged") if state == "completed" else state,
                          reason=reason, run_id=run["id"], last_attempt_at=run["updated_at"])
            if state == "completed":
                status["last_success_at"] = now()
                row["config"]["verified"] = True
                self.store.set_meta(db, "notice-mypage-ready:" + digest(row["id"], row["config"]["url"]), True)
            if state == "auth_required":
                row["config"]["verified"] = False
                self.collection.attention(db, row["id"], row["company_id"], True, profile=row["profile"])
            elif state == "completed":
                self.collection.attention(db, row["id"], row["company_id"], False, profile=row["profile"])
            if reason and row["status"].get("last_attention_reason") != reason:
                self.collection.notice(db, "mypage-health:" + row["id"] + ":" + run["id"], row["company_id"], None, "source_attention", "マイページの確認が必要です")
            status["last_attention_reason"] = reason
            db.execute("UPDATE career_mypage_connections SET status=?,config=? WHERE id=?", (encoded(status), encoded(row["config"]), row["id"]))
            db.execute("UPDATE career_mypage_runs SET state=?,updated_at=?,result=? WHERE id=?", (state, now(), encoded({"reason": reason, "changed": changed}), run["id"]))

    def view(self, company, profile):
        from .mypage import deadlines
        row = self.get(connection_id(company, profile))
        if not row:
            return None
        with self.store.connect() as db:
            items = [dict(r) for r in db.execute("SELECT e.*,i.data FROM career_mypage_bot_items i JOIN career_evidence e ON e.id=i.evidence_id WHERE i.connection_id=? ORDER BY e.first_observed_at DESC", (row["id"],))]
            history = [dict(r) for r in db.execute("SELECT id,state,created_at,updated_at,result FROM career_mypage_runs WHERE connection_id=? ORDER BY created_at DESC LIMIT 30", (row["id"],))]
        status = dict(row["status"])
        expiry = 120 if status.get("state") == "queued" else 12
        if status.get("state") in {"running", "queued"} and status.get("last_attempt_at", "") < (datetime.now(timezone.utc) - timedelta(minutes=expiry)).isoformat():
            with self.store.connect() as db:
                staged = db.execute("SELECT 1 FROM career_mypage_checkpoints WHERE run_id=? LIMIT 1", (status.get("run_id"),)).fetchone()
            status.update(state="partial" if staged else "failed", reason="collection_interrupted")
        with self.store.connect() as db:
            evidence_history = [dict(r) for r in db.execute("SELECT * FROM career_evidence WHERE source_key LIKE ? ORDER BY observed_at DESC LIMIT 100", ("mypage-bot:" + row["id"] + ":%",))]
        return {"status": status, "notices": [dict(i, deadlines=deadlines(i["excerpt"]), target_year=json.loads(i["data"]).get("target_year")) for i in items], "history": evidence_history,
                "fetch_history": history, "supported": True, "bot_available": True, "connection_version": row["version"],
                "automation": "enabled" if row["config"].get("enabled") else "disabled", "verified": row["config"].get("verified", False),
                "portal_url": row["config"]["url"], "next_run_at": row["config"].get("next_run_at"),
                "schedule_error": row["config"].get("schedule_error"),
                "coverage": "Hermes Botが確認した募集・締切・案内。前回の情報は失敗時も保持します。"}
