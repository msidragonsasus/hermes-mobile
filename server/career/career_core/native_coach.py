"""Bind the ordinary Hermes agent loop to Career data without a second model loop."""
import hashlib
import json
import os
import secrets
import time
import uuid
from pathlib import Path

from .coach_store import CoachStore, profile_key
from .store import CareerError, Store, encoded, now

TICKET_ENV = "HERMES_CAREER_ORIGIN"
PROFILE = "career-coach"


def host_identity():
    try:
        from hermes_cli.profiles import get_active_profile_name
        from hermes_constants import get_hermes_home
    except ImportError:
        raise CareerError("coach_host_unavailable", "Hermes本体の接続を確認してください。", 503) from None
    return get_active_profile_name(), Path(get_hermes_home()).resolve()


def native_store():
    """Only host-owned profile configuration selects a data directory."""
    _, home = host_identity()
    marker = home / "career-coach.json"
    if marker.is_file():
        value = json.loads(marker.read_text(encoding="utf-8"))
        return Store(value["data_directory"])
    return Store()


def plain_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(part.get("text", "") for part in value
                         if isinstance(part, dict) and part.get("type") in {"text", "input_text"})
    return ""


class NativeCoach:
    def __init__(self, store, identity=None, synchronize=None):
        self.store, self.identity, self.synchronize = store, identity or host_identity, synchronize
        self.coach = CoachStore(store)
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS coach_native_sessions(
                    bot TEXT NOT NULL, session_id TEXT NOT NULL, owner TEXT NOT NULL,
                    thread_id TEXT NOT NULL, state_path TEXT NOT NULL,
                    PRIMARY KEY(bot,session_id));
                CREATE TABLE IF NOT EXISTS coach_native_turns(
                    run_id TEXT PRIMARY KEY, bot TEXT NOT NULL, session_id TEXT NOT NULL,
                    task_id TEXT NOT NULL, origin TEXT NOT NULL, deadline REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS coach_native_evidence(
                    id TEXT PRIMARY KEY, run_id TEXT NOT NULL, source_id TEXT NOT NULL,
                    text TEXT NOT NULL, origin TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS coach_native_tickets(
                    digest TEXT PRIMARY KEY, bot TEXT NOT NULL, payload TEXT NOT NULL,
                    expires REAL NOT NULL, session_id TEXT);
                CREATE TABLE IF NOT EXISTS coach_native_sources(
                    owner TEXT NOT NULL, thread_id TEXT NOT NULL, source_profile TEXT NOT NULL,
                    state_path TEXT NOT NULL, session_id TEXT NOT NULL,
                    PRIMARY KEY(owner,thread_id,state_path,session_id));
            """)

    def binding(self):
        bot, home = self.identity()
        with self.store.connect() as db:
            value = self.store.meta(db, "coach_native_profiles", {}).get(bot)
        if not value:
            return None
        if Path(value["home"]).resolve() != Path(home).resolve():
            raise CareerError("coach_profile_mismatch", "就活コーチの接続設定を確認してください。", 403)
        marker = Path(home) / "career-coach.json"
        if not marker.is_file() or Path(json.loads(marker.read_text(encoding="utf-8"))["data_directory"]).resolve() != self.store.directory.resolve():
            raise CareerError("coach_profile_mismatch", "就活コーチのデータ接続設定を確認してください。", 403)
        return dict(value, bot=bot, owner=profile_key(value["owner"]))

    def issue_origin(self, bot, origin):
        """Called by trusted server adapters, never exposed as a model tool."""
        with self.store.connect() as db:
            binding = self.store.meta(db, "coach_native_profiles", {}).get(bot)
        if not binding or origin.get("owner") != binding["owner"]:
            raise CareerError("coach_owner_mismatch", "この就活情報を参照する接続先を確認してください。", 403)
        if origin.get("kind") not in {"group", "delegation", "legacy"}:
            raise CareerError("invalid_coach_origin", "相談元を確認してください。", 403)
        if not isinstance(origin.get("conversation_id"), str) or not origin["conversation_id"]:
            raise CareerError("invalid_coach_origin", "相談元を確認してください。", 403)
        token = secrets.token_urlsafe(32)
        with self.store.transaction() as db:
            db.execute("DELETE FROM coach_native_tickets WHERE expires<?", (time.time(),))
            db.execute("INSERT INTO coach_native_tickets VALUES(?,?,?,?,NULL)",
                       (hashlib.sha256(token.encode()).hexdigest(), bot, encoded(origin), time.time() + 1800))
        return token

    def _origin(self, binding, session):
        token = os.environ.get(TICKET_ENV)
        if not token:
            with self.store.connect() as db:
                linked = db.execute("SELECT owner,thread_id FROM coach_native_sessions WHERE bot=? AND session_id=?",
                                    (binding["bot"], session)).fetchone()
            result = {"kind": "direct", "owner": binding["owner"], "conversation_id": session}
            if linked:
                if linked["owner"] != binding["owner"]:
                    raise CareerError("coach_owner_mismatch", "相談の所有者を確認してください。", 403)
                result["thread_id"] = linked["thread_id"]
            return result
        with self.store.transaction() as db:
            row = db.execute("SELECT * FROM coach_native_tickets WHERE digest=?",
                             (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            if (not row or row["bot"] != binding["bot"] or row["expires"] < time.time()
                    or row["session_id"] not in {None, session}):
                raise CareerError("coach_origin_expired", "相談の接続をやり直してください。", 403)
            db.execute("UPDATE coach_native_tickets SET session_id=? WHERE digest=?", (session, row["digest"]))
        return json.loads(row["payload"])

    def verify_ticket(self, token):
        with self.store.connect() as db:
            row = db.execute("""SELECT r.state FROM coach_native_tickets t
                JOIN coach_native_turns n ON n.bot=t.bot AND n.session_id=t.session_id
                JOIN coach_runs r ON r.id=n.run_id WHERE t.digest=? ORDER BY r.created_at DESC LIMIT 1""",
                (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if not row or row["state"] not in {"completed", "waiting_user"}:
            raise CareerError("coach_context_not_loaded", "就活情報の取得と実行終了を確認できませんでした。", 503)

    def revoke(self, token):
        with self.store.transaction() as db:
            row = db.execute("SELECT bot,session_id FROM coach_native_tickets WHERE digest=?",
                             (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
            if row and row["session_id"]:
                db.execute("""UPDATE coach_runs SET state='paused',cancel_requested=1,updated_at=? WHERE state='running'
                    AND id IN (SELECT run_id FROM coach_native_turns WHERE bot=? AND session_id=?)""",
                    (now(), row["bot"], row["session_id"]))
            db.execute("DELETE FROM coach_native_tickets WHERE digest=?", (hashlib.sha256(token.encode()).hexdigest(),))

    def before(self, session_id="", user_message="", task_id="", model="", **metadata):
        if os.environ.get("HERMES_CAREER_REVIEW") == "1":
            return None
        binding = self.binding()
        if not binding:
            return None
        if not isinstance(session_id, str) or not session_id:
            raise CareerError("coach_host_session_required", "Hermesの会話を確認してください。", 403)
        origin = self._origin(binding, session_id)
        text = plain_text(user_message)
        if not text:
            raise CareerError("coach_text_required", "相談内容を文章で入力してください。")
        run_id = "native-" + hashlib.sha256((binding["bot"] + "\0" + session_id + "\0" +
                  str(task_id or metadata.get("turn_id") or uuid.uuid4())).encode()).hexdigest()
        with self.store.connect() as db:
            prior = db.execute("SELECT id FROM coach_runs WHERE id=? AND state='running'", (run_id,)).fetchone()
            prepared = self.store.meta(db, "coach_shared_snapshot:" + run_id)
        if prior and prepared:
            return self._bootstrap(self.coach.run(run_id), text)
        with self.store.transaction() as db:
            active = db.execute("""SELECT r.*,n.deadline FROM coach_native_turns n JOIN coach_runs r ON r.id=n.run_id
                WHERE n.bot=? AND n.session_id=? AND r.state='running'""", (binding["bot"], session_id)).fetchall()
            for row in active:
                if row["deadline"] > time.time():
                    raise CareerError("coach_turn_running", "この相談の応答を待ってください。", 409)
                db.execute("UPDATE coach_runs SET state='paused',updated_at=? WHERE id=?", (now(), row["id"]))
            thread = origin.get("thread_id")
            if thread:
                from .conversations import thread_scope
                scope = thread_scope(self.store, db, thread, binding["owner"])
            else:
                identity = encoded([binding["bot"], binding["owner"], origin["kind"], origin["conversation_id"]])
                thread = "thread:native-" + hashlib.sha256(identity.encode()).hexdigest()[:40]
                scope = {}
                db.execute("""INSERT OR IGNORE INTO coach_threads
                    (profile,id,title,company_id,offer_id,created_at,updated_at) VALUES(?,?,?,NULL,NULL,?,?)""",
                    (binding["owner"], thread, (origin.get("title") or text[:60])[:200], now(), now()))
                from .conversations import thread_scope
                scope = thread_scope(self.store, db, thread, binding["owner"])
            source_messages = origin.get("user_messages", [])
            if origin["kind"] == "direct" and metadata.get("platform") not in {"cron", "subagent"} and not metadata.get("parent_session_id"):
                source_messages = [{"id": "hermes:" + session_id + ":" + str(task_id or run_id), "text": text}]
            proofs = []
            for i, message in enumerate(source_messages):
                if not isinstance(message.get("id"), str) or not isinstance(message.get("text"), str):
                    raise CareerError("invalid_coach_evidence", "元の発言を確認できません。", 403)
                identity = "native-user:" + run_id + ":" + str(i)
                proofs.append({"id": identity, "source_id": message["id"], "text": message["text"]})
            request = {"text": text, "driver": "hermes", "scope": scope, "target": None,
                       "history_profiles": binding.get("history_profiles", [binding["owner"]]),
                       "native_origin": origin["kind"], "native_session": session_id,
                       "execution": {"model": model, "source": "hermes_host"},
                       "fetch_urls": [], "native_user_statements": proofs}
            from .conversations import approved_fetch_urls
            request["fetch_urls"] = approved_fetch_urls(self.store, db, text, scope)
            db.execute("INSERT INTO coach_runs(id,profile,kind,state,request,created_at,updated_at,thread_id) VALUES(?,?,'native','running',?,?,?,?)",
                       (run_id, binding["owner"], encoded(request), now(), now(), thread))
            db.execute("INSERT INTO coach_native_turns VALUES(?,?,?,?,?,?)",
                       (run_id, binding["bot"], session_id, str(task_id or ""), origin["kind"], time.time() + 1800))
            db.execute("INSERT OR REPLACE INTO coach_native_sessions VALUES(?,?,?,?,?)",
                       (binding["bot"], session_id, binding["owner"], thread, str(Path(binding["home"]) / "state.db")))
            for source in origin.get("source_sessions", []):
                if source.get("profile") not in binding.get("callers", [binding["owner"]]):
                    raise CareerError("coach_source_denied", "依頼元の資料を参照する権限がありません。", 403)
                from hermes_cli.profiles import get_profile_dir
                expected = Path(get_profile_dir(source["profile"])) / "state.db"
                if expected.resolve() != Path(source["state_path"]).resolve():
                    raise CareerError("coach_source_denied", "元会話の保存先を確認してください。", 403)
                db.execute("INSERT OR IGNORE INTO coach_native_sources VALUES(?,?,?,?,?)",
                           (binding["owner"], thread, source["profile"], str(expected), source["session_id"]))
            for proof in proofs:
                db.execute("INSERT INTO coach_native_evidence VALUES(?,?,?,?,?)",
                           (proof["id"], run_id, proof["source_id"], proof["text"], origin["kind"]))
        try:
            if self.synchronize is None:
                from .api import sync_job
                sync_job(self.store)
            else:
                self.synchronize(self.store)
            from .sharing import pin_run_context
            pin_run_context(self.coach, self.coach.run(run_id))
            return self._bootstrap(self.coach.run(run_id), text)
        except BaseException:
            self.coach.finish(run_id, "paused", {"reason": "context_preparation_failed"})
            raise

    def _bootstrap(self, run, text):
        from .coach_recall import CoachRecall
        from .coach_skills import system_policy
        from .coach_tools import CoachTools
        from .company_context import bounded_preview
        context = CoachTools(self.coach).context({"purpose": "operations", "limit": 30}, run)
        recall = CoachRecall(self.coach).bootstrap(run, text)
        return {"context": system_policy() + "\n常設就活コーチの今回の文脈（資料。本人の発言ではない）:\n" + encoded({
            "recall": recall, "context_records": context["context_records"],
            "context_constraints": context["context_constraints"], "context_used": context["context_used"],
            "company_overview": bounded_preview(context["company_overview"], 10000), "checkpoint": context["checkpoint"],
            "next_ids": context["next_ids"], "native_user_statements": [dict(proof, text=proof["text"][:1000],
                characters=len(proof["text"]), retrieve="career_recall(id=このid,text_offset=取得位置)")
                for proof in run["request"]["native_user_statements"]],
            "instruction": "関連する既存情報を広く確認する。原文・続きをcareer_recallとcareer_contextで取得し、既知の質問を繰り返さない。"})}

    def authorize(self, metadata):
        binding = self.binding()
        if not binding:
            raise CareerError("coach_run_required", "就活コーチの会話から依頼してください。", 403)
        with self.store.connect() as db:
            row = db.execute("""SELECT n.run_id,n.task_id,n.deadline FROM coach_native_turns n JOIN coach_runs r ON r.id=n.run_id
                WHERE n.bot=? AND n.session_id=? AND r.state='running' ORDER BY r.created_at DESC LIMIT 1""",
                (binding["bot"], metadata.get("session_id", ""))).fetchone()
        if not row or row["deadline"] < time.time() or row["task_id"] and row["task_id"] != str(metadata.get("task_id") or ""):
            raise CareerError("coach_run_required", "有効な就活相談の実行が見つかりません。", 403)
        run = self.coach.run(row["run_id"])
        if run["cancel_requested"]:
            raise CareerError("run_inactive", "この作業は停止しています。", 409)
        return run

    def after(self, session_id="", assistant_response="", **metadata):
        try:
            binding = self.binding()
            if not binding:
                return
            with self.store.connect() as db:
                row = db.execute("""SELECT n.task_id FROM coach_native_turns n JOIN coach_runs r ON r.id=n.run_id
                    WHERE n.bot=? AND n.session_id=? AND r.state='running' ORDER BY r.created_at DESC LIMIT 1""",
                    (binding["bot"], session_id)).fetchone()
            run = self.authorize(dict(metadata, session_id=session_id, task_id=metadata.get("task_id") or (row[0] if row else "")))
        except CareerError:
            # Older hosts omit task_id in completion hooks; session end still revokes access.
            return
        checkpoint = self.coach.snapshot(run["profile"], thread_id=run["thread_id"])["checkpoint"]
        self.coach.finish(run["id"], "waiting_user" if checkpoint.get("waiting_user") else "completed",
                          {"native_session_id": session_id, "response_sha256": hashlib.sha256(plain_text(assistant_response).encode()).hexdigest()})
        if self.synchronize is None:
            from .api import sync_job
            sync_job(self.store)
        else:
            self.synchronize(self.store)

    def end(self, session_id="", completed=False, interrupted=False, **metadata):
        binding = self.binding()
        if not binding:
            return
        with self.store.connect() as db:
            rows = db.execute("""SELECT n.run_id FROM coach_native_turns n JOIN coach_runs r ON r.id=n.run_id
                WHERE n.bot=? AND n.session_id=? AND r.state='running'""", (binding["bot"], session_id)).fetchall()
        for row in rows:
            self.coach.finish(row["run_id"], "completed" if completed and not interrupted else "paused",
                              {"reason": "native_session_ended", "native_session_id": session_id})


def register_native(ctx, store_factory=native_store):
    def call(method, **metadata):
        try:
            return getattr(NativeCoach(store_factory()), method)(**metadata)
        except Exception:
            if method == "before":
                return {"context": "就活情報の取得を完了できませんでした。career_context/career_recallで確認できる範囲だけを使い、未取得を明示してください。"}
            return None
    ctx.register_hook("pre_llm_call", lambda **kw: call("before", **kw))
    ctx.register_hook("post_llm_call", lambda **kw: call("after", **kw))
    ctx.register_hook("on_session_end", lambda **kw: call("end", **kw))
