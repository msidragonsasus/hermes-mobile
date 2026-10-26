"""Durable coach conversations and accepted work; independent of a phone connection."""
import hashlib
import json
import re
import uuid

from .store import CareerError, KINDS, encoded, now
from .coach_execution import ExecutionPolicy, canonical_execution, default_execution
from .conversations import accepted_scope, thread_scope, approved_fetch_urls

ACTIVE = {"queued", "running", "waiting_user", "paused"}
TERMINAL = {"completed", "failed", "cancelled"}


def profile_key(value):
    value = value or "default"
    if not isinstance(value, str) or not re.fullmatch(r"[\w.-]{1,100}", value):
        raise CareerError("invalid_profile", "接続プロファイルを確認してください。")
    return value


class CoachStore:
    def __init__(self, store):
        self.store = store
        self.execution = ExecutionPolicy(store)
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS coach_runs(
                    id TEXT PRIMARY KEY, profile TEXT NOT NULL, kind TEXT NOT NULL,
                    state TEXT NOT NULL, request TEXT NOT NULL, result TEXT NOT NULL DEFAULT '{}',
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS coach_messages(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, profile TEXT NOT NULL,
                    run_id TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS coach_events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, profile TEXT NOT NULL, run_id TEXT NOT NULL,
                    type TEXT NOT NULL, data TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS coach_context(
                    profile TEXT PRIMARY KEY, history TEXT NOT NULL DEFAULT '[]', checkpoint TEXT NOT NULL DEFAULT '{}');
                CREATE TABLE IF NOT EXISTS coach_tool_operations(
                    run_id TEXT NOT NULL, key TEXT NOT NULL, result TEXT NOT NULL,
                    PRIMARY KEY(run_id,key));
                CREATE TABLE IF NOT EXISTS coach_thread_context(
                    profile TEXT NOT NULL, thread_id TEXT NOT NULL,
                    history TEXT NOT NULL DEFAULT '[]', checkpoint TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY(profile,thread_id));
                CREATE INDEX IF NOT EXISTS coach_queue ON coach_runs(state,created_at);
                CREATE INDEX IF NOT EXISTS coach_event_cursor ON coach_events(profile,seq);
            """)
        # Additive migration under a write lock; never rebuild or discard legacy tables.
        with store.transaction() as db:
            from .threads import install
            install(db)
            if "thread_id" not in {row["name"] for row in db.execute("PRAGMA table_info(coach_runs)")}:
                db.execute("ALTER TABLE coach_runs ADD COLUMN thread_id TEXT NOT NULL DEFAULT 'global'")
            db.execute("CREATE INDEX IF NOT EXISTS coach_thread_runs ON coach_runs(profile,thread_id,created_at)")

    @staticmethod
    def event(db, profile, run_id, kind, data):
        db.execute("INSERT INTO coach_events(profile,run_id,type,data,created_at) VALUES(?,?,?,?,?)",
                   (profile, run_id, kind, encoded(data), now()))

    @staticmethod
    def decode(row):
        return dict(row, request=json.loads(row["request"]), result=json.loads(row["result"])) if row else None

    def run(self, run_id, profile=None):
        if not isinstance(run_id, str) or not 1 <= len(run_id) <= 100:
            raise CareerError("run_missing", "作業IDを確認してください。", 404)
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM coach_runs WHERE id=?", (run_id,)).fetchone()
        result = self.decode(row)
        if not result or (profile is not None and result["profile"] != profile_key(profile)):
            raise CareerError("run_missing", "この会話の作業が見つかりません。", 404)
        return result

    def submit(self, payload, profile="default", *, kind="message"):
        profile = profile_key(profile)
        thread_id = payload.get("thread_id", "global")
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > 20000:
            raise CareerError("invalid_message", "依頼を1〜20,000文字で入力してください。")
        target = payload.get("target")
        if target is not None and (not isinstance(target, dict) or not {"kind", "id"} <= set(target) or set(target) - {"kind", "id", "version", "source_refs"}):
            raise CareerError("invalid_target", "相談対象が不正です。")
        if target is not None and (not isinstance(target["kind"], str) or target["kind"] not in KINDS or
                                   not isinstance(target["id"], str) or not 1 <= len(target["id"]) <= 200):
            raise CareerError("invalid_target", "相談対象が不正です。")
        request = {"profile": profile, "text": text.strip(), "target": target, "kind": kind}
        if "answer_context" in payload:
            request["answer_context"] = payload["answer_context"]
        if thread_id != "global":
            request["thread_id"] = thread_id
        if target is not None and "version" in target and (target["kind"] != "documents" or type(target["version"]) is not int or target["version"] < 1):
            raise CareerError("invalid_target", "修正する文書の版を確認してください。")
        if "execution" in payload:
            request["execution"] = canonical_execution(payload["execution"])
        with self.store.connect() as db:
            exists = isinstance(payload.get("operation_id"), str) and db.execute("SELECT 1 FROM operations WHERE id=?", (payload["operation_id"],)).fetchone()
        execution = None
        if not exists:
            selected = request.get("execution")
            if selected is None:
                selected = self.execution.daily(profile).get("execution") if kind in {"daily", "research"} else default_execution()
            execution = self.execution.validate(selected)
        def apply(db):
            scope = accepted_scope(self.store, db, thread_id, target, profile, system_update=kind == "daily")
            from .conversations import accepted_sources
            selected_sources = accepted_sources(db, scope, target.get("source_refs", []) if target else [], profile)
            answer_context = None
            if "answer_context" in request:
                from .es_work import validate_consultation, check_document
                answer_context = validate_consultation(self.store, db, request["answer_context"], scope)
                if target and target["kind"] == "documents":
                    check_document(self.store, db, target["id"], scope["offer_id"], answer_context["question_id"], target.get("version"))
            if target and not self.store.entity(db, target["kind"], target["id"]):
                raise CareerError("target_missing", "相談対象を更新してください。", 409)
            self.check_target(db, target)
            if db.execute("SELECT COUNT(*) FROM coach_runs WHERE profile=? AND state='queued'", (profile,)).fetchone()[0] >= 20:
                raise CareerError("queue_full", "待機中の作業を先に完了・停止してください。", 409)
            run_id = str(uuid.uuid4())
            timestamp = now()
            question_versions = {}
            if scope.get("offer_id"):
                offer = self.store.entity(db, "offers", scope["offer_id"])
                question_versions = {q["id"]: q["version"] for q in offer["data"].get("questions", [])}
            if kind == "message":
                for waiting in db.execute("SELECT id FROM coach_runs WHERE profile=? AND thread_id=? AND state='waiting_user'", (profile, thread_id)).fetchall():
                    db.execute("UPDATE coach_runs SET state='completed',updated_at=? WHERE id=?", (timestamp, waiting[0]))
                    self.event(db, profile, waiting[0], "run", {"state": "completed"})
            db.execute("INSERT INTO coach_runs(id,profile,kind,state,request,created_at,updated_at,thread_id) VALUES(?,?,?,'queued',?,?,?,?)",
                       (run_id, profile, kind, encoded(dict(request, selected_sources=selected_sources, answer_context=answer_context, execution=execution, scope=scope, question_versions=question_versions,
                                                          fetch_urls=approved_fetch_urls(self.store, db, text, scope))), timestamp, timestamp, thread_id))
            if kind in {"message", "research"}:
                db.execute("INSERT INTO coach_messages(id,profile,run_id,role,text,created_at) VALUES(?,?,?,'user',?,?)",
                           (str(uuid.uuid4()), profile, run_id, text.strip(), timestamp))
            self.event(db, profile, run_id, "run", {"state": "queued"})
            return {"id": run_id, "state": "queued", "execution": execution}
        return self.store.operation(payload.get("operation_id"), request, apply)

    def check_target(self, db, target, run_id=None):
        if target and "version" in target:
            entity = self.store.entity(db, "documents", target["id"])
            if run_id and entity:
                for row in db.execute("SELECT result FROM coach_tool_operations WHERE run_id=?", (run_id,)):
                    saved = json.loads(row[0])
                    if saved.get("kind") == "documents" and saved.get("id") == target["id"] and saved.get("data", {}).get("version") == entity["data"].get("version"):
                        return
            if not entity or entity["data"].get("version") != target["version"]:
                raise CareerError("document_version_conflict", "文書が更新されています。表示中の版と最新版を確認して修正を依頼してください。", 409)

    def submit_collection(self, grant, channel, operation_id):
        raise CareerError("portal_collection_retired", "マイページからの取得は使用しません。公開されている公式採用情報を調査します。", 409)

    def snapshot(self, profile="default", *, before=None, thread_id="global"):
        profile = profile_key(profile)
        with self.store.connect() as db:
            thread_scope(self.store, db, thread_id, profile, read_only=True)
            metadata = db.execute("SELECT archived FROM coach_threads WHERE profile=? AND id=?", (profile, thread_id)).fetchone()
            archived = bool(metadata and metadata[0])
            rows = db.execute("SELECT m.* FROM coach_messages m LEFT JOIN coach_runs r ON r.id=m.run_id "
                              "WHERE m.profile=? AND COALESCE(r.thread_id,'global')=? AND m.seq<? ORDER BY m.seq DESC LIMIT 80",
                              (profile, thread_id, before or 9223372036854775807)).fetchall()
            runs = [self.decode(r) for r in db.execute(
                "SELECT * FROM coach_runs WHERE profile=? AND thread_id=? ORDER BY created_at DESC LIMIT 40", (profile, thread_id))]
            checkpoint = self.read_context(db, profile, thread_id, "checkpoint", {})
            if not checkpoint and thread_id == "global":
                shared = self.store.meta(db, "github_base", {}).get("last_session", {}).get("hermes_coach", {})
                candidates = [value for value in shared.values() if isinstance(value, dict) and value.get("profile") == profile] if isinstance(shared, dict) else []
                checkpoint = max(candidates, key=lambda value: value.get("completed_at", ""), default={})
            cursor = db.execute("SELECT COALESCE(MAX(e.seq),0) FROM coach_events e JOIN coach_runs r ON r.id=e.run_id "
                                "WHERE e.profile=? AND r.thread_id=?", (profile, thread_id)).fetchone()[0]
            shared_context = self.store.meta(db, "jobhunt_context_status", {"state": "not_imported"})
            context_conflicts = self.store.meta(db, "jobhunt_context_conflicts", [])
            sync = self.store.sharing_status(db)
        from .company_views import CompanyViews
        company_view = CompanyViews(self.store).overview(profile)
        with self.store.connect() as db:
            scope = thread_scope(self.store, db, thread_id, profile, read_only=True)
        companies = company_view["companies"]
        if scope.get("company_id"):
            companies = [row for row in companies if row["id"] == scope["company_id"]]
        company_context = {"companies": companies[:50], "total": len(companies),
                           "checked_at": company_view["checked_at"], "next_offset": 50 if len(companies) > 50 else None,
                           "coverage": "保存済み企業・接続設定・メール取得・マイページ巡回の状態。サイトへの未登録を推測しません。"}
        # Inputs live in messages and are not repeated as run-card diagnostics.
        visible = [dict({k: v for k, v in r.items() if k != "request"},
                        execution=r["request"].get("execution"), artifacts=self.artifacts(r["id"])) for r in runs]
        return {"api_version": 2, "profile": profile, "thread_id": thread_id, "messages": [dict(r) for r in reversed(rows)],
                "runs": visible, "cursor": cursor, "older_before": rows[-1]["seq"] if len(rows) == 80 else None,
                "checkpoint": checkpoint, "checked_at": now(), "archived": archived,
                "shared_context": shared_context, "sync": sync, "context_conflicts": context_conflicts, "company_context": company_context}

    def events(self, profile, after, thread_id="global"):
        if type(after) is not int or after < 0:
            raise CareerError("invalid_cursor", "会話を読み直してください。")
        with self.store.connect() as db:
            thread_scope(self.store, db, thread_id, profile, read_only=True)
            rows = db.execute("SELECT e.* FROM coach_events e JOIN coach_runs r ON r.id=e.run_id "
                              "WHERE e.profile=? AND r.thread_id=? AND e.seq>? ORDER BY e.seq LIMIT 200",
                              (profile_key(profile), thread_id, after)).fetchall()
        return {"thread_id": thread_id, "events": [dict(r, data=json.loads(r["data"])) for r in rows],
                "cursor": rows[-1]["seq"] if rows else after, "more": len(rows) == 200}

    def claim(self):
        with self.store.transaction() as db:
            # Also protects direct one() calls and old queued jobs created before the upgrade.
            for legacy in db.execute("SELECT id,profile,request,result FROM coach_runs WHERE state='queued' AND kind NOT IN ('collection','native')").fetchall():
                if not json.loads(legacy["request"]).get("execution"):
                    result = dict(json.loads(legacy["result"]), reason="execution_confirmation_required",
                                  message="以前の作業です。モデルと推論強度を確認して続行してください。")
                    db.execute("UPDATE coach_runs SET state='paused',result=?,updated_at=? WHERE id=?", (encoded(result), now(), legacy["id"]))
                    self.event(db, legacy["profile"], legacy["id"], "run", dict(result, state="paused"))
            row = db.execute("SELECT * FROM coach_runs WHERE state='queued' AND kind!='native' ORDER BY CASE kind WHEN 'message' THEN 0 WHEN 'research' THEN 1 ELSE 2 END,created_at LIMIT 1").fetchone()
            if not row:
                return None
            db.execute("UPDATE coach_runs SET state='running',updated_at=? WHERE id=?", (now(), row["id"]))
            self.event(db, row["profile"], row["id"], "run", {"state": "running"})
        return self.run(row["id"])

    def recover(self):
        # Called only while the worker holds its OS lock. Never assume an interrupted turn did nothing.
        with self.store.transaction() as db:
            for row in db.execute("SELECT * FROM coach_runs WHERE kind!='native' AND state IN ('running','queued','waiting_user','paused')").fetchall():
                missing = row["kind"] != "collection" and not json.loads(row["request"]).get("execution")
                if not missing and row["state"] != "running":
                    continue
                result = dict(json.loads(row["result"]), reason="execution_confirmation_required" if missing else "server_restarted",
                              message="モデルと推論強度を確認して続行してください。" if missing else "サーバーが再起動しました。保存済みの結果を確認して続行できます。")
                db.execute("UPDATE coach_runs SET state='paused',result=?,updated_at=? WHERE id=?", (encoded(result), now(), row["id"]))
                self.event(db, row["profile"], row["id"], "run", dict(result, state="paused"))

    def finish(self, run_id, state, result=None, *, reply=None):
        if state not in ACTIVE | TERMINAL:
            raise ValueError("Unknown run state")
        with self.store.transaction() as db:
            run = self.decode(db.execute("SELECT * FROM coach_runs WHERE id=?", (run_id,)).fetchone())
            if not run or run["state"] in TERMINAL:
                return
            if run["cancel_requested"]:
                state = "cancelled"
            db.execute("UPDATE coach_runs SET state=?,result=?,updated_at=? WHERE id=?", (state, encoded(dict(run["result"], **(result or {}))), now(), run_id))
            if reply:
                # One final assistant message per run, even after a lost acknowledgement.
                db.execute("INSERT OR IGNORE INTO coach_messages(id,profile,run_id,role,text,created_at) VALUES(?,?,?,'assistant',?,?)",
                           ("reply:" + run_id, run["profile"], run_id, reply, now()))
                self.event(db, run["profile"], run_id, "message", {"id": "reply:" + run_id})
            if state in {"completed", "waiting_user"}:
                checkpoint = self.read_context(db, run["profile"], run["thread_id"], "checkpoint", {})
                # Company threads can contain private portal excerpts: keep their checkpoints local.
                if checkpoint.get("run_id") == run_id and run["thread_id"] == "global":
                    pending = self.store.meta(db, "coach_checkpoint_pending", {})
                    pending[run_id] = dict(checkpoint, profile=run["profile"], completed_at=now())
                    self.store.set_meta(db, "coach_checkpoint_pending", pending)
            self.event(db, run["profile"], run_id, "run", dict(result or {}, state=state))

    def progress(self, run_id, kind, data):
        with self.store.transaction() as db:
            run = self.decode(db.execute("SELECT * FROM coach_runs WHERE id=?", (run_id,)).fetchone())
            if run and run["state"] == "running" and not run["cancel_requested"]:
                if kind in {"execution", "context_used"}:
                    result = dict(run["result"], **{"effective_execution" if kind == "execution" else kind: data})
                    db.execute("UPDATE coach_runs SET result=?,updated_at=? WHERE id=?", (encoded(result), now(), run_id))
                self.event(db, run["profile"], run_id, kind, data)

    def needs_manual_turn(self):
        with self.store.connect() as db:
            return db.execute("SELECT 1 FROM coach_runs WHERE kind='message' AND state='queued' LIMIT 1").fetchone() is not None

    def receipts(self, run_id):
        with self.store.connect() as db:
            return [json.loads(r[0]) for r in db.execute("SELECT result FROM coach_tool_operations WHERE run_id=?", (run_id,))]

    def artifacts(self, run_id):
        artifacts = []
        with self.store.connect() as db:
            for row in db.execute("SELECT result FROM coach_tool_operations WHERE run_id=?", (run_id,)):
                receipt = json.loads(row[0])
                if receipt.get("kind") != "documents":
                    continue
                data = receipt["data"]
                saved = db.execute("SELECT body,synced FROM document_files WHERE path=?", (data.get("path"),)).fetchone()
                if saved:
                    artifacts.append({"id": receipt["id"], "version": data["version"], "entity_version": receipt["version"],
                                      "title": data.get("title", receipt["id"]), "path": data["path"],
                                      "preview": saved["body"][:240], "characters": len(saved["body"]),
                                      "server_saved": True, "github_synced": bool(saved["synced"])})
        return sorted(artifacts, key=lambda item: (item["id"], item["version"]))

    def control(self, request, profile):
        run_id = request.get("id")
        run = self.run(run_id, profile)
        if request.get("thread_id", "global" if run["thread_id"].startswith("thread:") else run["thread_id"]) != run["thread_id"]:
            raise CareerError("thread_scope_conflict", "別の会話の作業は操作できません。", 409)
        action = request.get("control")
        if action not in {"stop", "resume"}:
            raise CareerError("invalid_control", "停止か続行を選んでください。")
        confirmation = canonical_execution(request["execution"]) if "execution" in request else None
        if action == "resume" and confirmation:
            with self.store.connect() as db:
                exists = db.execute("SELECT 1 FROM operations WHERE id=?", (request.get("operation_id"),)).fetchone()
            if not exists:
                self.execution.validate(confirmation)
        def apply(db):
            current = self.decode(db.execute("SELECT * FROM coach_runs WHERE id=?", (run_id,)).fetchone())
            if action == "resume":
                thread_scope(self.store, db, run["thread_id"], profile)
                if current["state"] != "paused":
                    raise CareerError("run_changed", "作業状態が変わりました。更新してください。", 409)
                accepted = current["request"].get("execution")
                if accepted and confirmation and accepted != confirmation:
                    raise CareerError("execution_immutable", "受理済みのモデル設定は変更できません。新しい依頼で選択してください。", 409)
                if not accepted:
                    if not confirmation:
                        raise CareerError("execution_confirmation_required", "モデルと推論強度を確認して続行してください。", 409)
                    current["request"]["execution"] = confirmation
                    db.execute("UPDATE coach_runs SET request=? WHERE id=?", (encoded(current["request"]), run_id))
                self.check_target(db, current["request"].get("target"), run_id)
                state, cancel = "queued", 0
            else:
                state = "running" if current["state"] == "running" else "cancelled"
                cancel = 1
                if current["state"] in TERMINAL:
                    return {"id": run_id, "state": current["state"]}
            db.execute("UPDATE coach_runs SET state=?,cancel_requested=?,updated_at=? WHERE id=?", (state, cancel, now(), run_id))
            self.event(db, run["profile"], run_id, "run", {"state": state, "cancel_requested": cancel})
            return {"id": run_id, "state": state}
        return self.store.operation(request.get("operation_id"), dict(request, profile=profile_key(profile)), apply)

    @staticmethod
    def read_context(db, profile, thread_id, field, default):
        assert field in {"history", "checkpoint"}
        if thread_id == "global":
            row = db.execute(f"SELECT {field} FROM coach_context WHERE profile=?", (profile,)).fetchone()
        else:
            row = db.execute(f"SELECT {field} FROM coach_thread_context WHERE profile=? AND thread_id=?", (profile, thread_id)).fetchone()
        return json.loads(row[0]) if row else default

    @staticmethod
    def write_context(db, profile, thread_id, field, value):
        assert field in {"history", "checkpoint"}
        if thread_id == "global":
            db.execute(f"INSERT INTO coach_context(profile,{field}) VALUES(?,?) ON CONFLICT(profile) DO UPDATE SET {field}=excluded.{field}",
                       (profile, encoded(value)))
        else:
            db.execute(f"INSERT INTO coach_thread_context(profile,thread_id,{field}) VALUES(?,?,?) "
                       f"ON CONFLICT(profile,thread_id) DO UPDATE SET {field}=excluded.{field}", (profile, thread_id, encoded(value)))

    def checkpoint(self, profile, value, db=None, *, thread_id="global"):
        if not isinstance(value, dict) or len(encoded(value)) > 12000:
            raise CareerError("invalid_checkpoint", "引き継ぎ情報が大きすぎます。")
        if db is None:
            with self.store.transaction() as connection:
                return self.checkpoint(profile, value, connection, thread_id=thread_id)
        self.write_context(db, profile, thread_id, "checkpoint", value)

    def history(self, profile, thread_id="global"):
        with self.store.connect() as db:
            return self.read_context(db, profile, thread_id, "history", [])

    def save_history(self, profile, history, thread_id="global"):
        # Full durable conversation; the runtime performs bounded context selection/compression.
        with self.store.transaction() as db:
            self.write_context(db, profile, thread_id, "history", history)

    def tool_once(self, run_id, arguments, action):
        key = hashlib.sha256(encoded(arguments).encode()).hexdigest()
        # Store.operation and this ledger share one transaction; no applied write without its receipt.
        with self.store.transaction() as db:
            run = self.decode(db.execute("SELECT * FROM coach_runs WHERE id=?", (run_id,)).fetchone())
            if not run or run["state"] != "running" or run["cancel_requested"]:
                raise CareerError("run_inactive", "この作業は停止・中断しています。", 409)
            row = db.execute("SELECT result FROM coach_tool_operations WHERE run_id=? AND key=?", (run_id, key)).fetchone()
            if row:
                return json.loads(row[0])
            result = action(db, run, "coach:" + run_id + ":" + key[:24])
            db.execute("INSERT INTO coach_tool_operations VALUES(?,?,?)", (run_id, key, encoded(result)))
            self.event(db, run["profile"], run_id, "saved", {"kind": arguments.get("kind"), "result": result})
            return result
