"""Host-routed requests to the one native coach, with durable async delivery."""
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time

from .native_coach import NativeCoach, TICKET_ENV, host_identity, native_store
from .store import CareerError, encoded, now

_turn_origins = {}
_origin_lock = threading.Lock()
logger = logging.getLogger(__name__)
_STDERR_TAIL = 4000
_DIAG_KEEP = 20
# Fallback when Hermes' own redactor is unavailable: key=value secrets, bearer tokens, long opaque tokens, emails.
_FALLBACK_SECRETS = (
    (re.compile(r"(?i)\bbearer\s+\S+"), "Bearer [redacted]"),
    (re.compile(r"(?i)\b(api[_-]?key|token|secret|password|passwd|authorization|cookie|ticket)(\s*[:=]\s*)\S+"), r"\1\2[redacted]"),
    (re.compile(r"\b[A-Za-z0-9_\-]{32,}\b"), "[redacted]"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"), "[email]"),
)


def _redact(text, ticket=None):
    """Child stderr may echo credentials or the delegation ticket; never keep them."""
    text = text or ""
    if ticket:
        text = text.replace(ticket, "[ticket]")
    try:
        from agent.redact import redact_sensitive_text
        text = redact_sensitive_text(text, force=True)
    except Exception:
        pass
    for pattern, repl in _FALLBACK_SECRETS:
        text = pattern.sub(repl, text)
    return text


def _record_failure(store, bot, returncode, stderr, empty_answer, ticket=None):
    """Keep a redacted diagnostic for operators; the public error stays the stable code."""
    detail = {"at": now(), "bot": bot, "returncode": returncode, "empty_answer": empty_answer,
              "stderr_tail": _redact((stderr or "")[-_STDERR_TAIL:], ticket)}
    logger.warning("career coach child failed: bot=%s returncode=%s empty_answer=%s stderr_tail=%s",
                   bot, returncode, empty_answer, detail["stderr_tail"])
    try:
        path = os.path.join(os.path.dirname(str(store.path)), "coach_failures.jsonl")
        rows = []
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as handle:
                rows = handle.read().splitlines()[-(_DIAG_KEEP - 1):]
        rows.append(json.dumps(detail, ensure_ascii=False))
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(rows) + "\n")
        os.chmod(path, 0o600)
    except Exception:
        logger.debug("career coach diagnostic file not written", exc_info=True)


def capture_turn(store, metadata, identity=host_identity):
    """Keep the host's original current utterance briefly; never trust a model paraphrase."""
    caller, _ = identity()
    session, task = metadata.get("session_id"), metadata.get("task_id")
    text = metadata.get("user_message")
    if not session or not task or not isinstance(text, str) or not text:
        return
    if metadata.get("platform") in {"cron", "subagent"} or metadata.get("parent_session_id"):
        return
    with store.connect() as db:
        bindings = store.meta(db, "coach_native_profiles", {})
    if not any(caller != bot and caller in value.get("callers", [value["owner"]]) for bot, value in bindings.items()):
        return
    history = metadata.get("conversation_history", [])
    current = next((row for row in reversed(history) if isinstance(row, dict) and row.get("role") == "user"
                    and row.get("content") == text), None)
    # Synthetic event messages and unknown host author conventions are references only.
    if not current or current.get("display_kind") or current.get("turn_author"):
        return
    key = (str(store.path), caller, session, str(task))
    with _origin_lock:
        for old in list(_turn_origins):
            if _turn_origins[old][0] < time.monotonic() - 1800:
                del _turn_origins[old]
        if len(_turn_origins) >= 500:
            del _turn_origins[next(iter(_turn_origins))]
        _turn_origins[key] = (time.monotonic(), {"id": "main-user:" + session + ":" + str(task), "text": text})


def run_profile(bot, prompt, origin, stopped, store):
    bridge = NativeCoach(store)
    ticket = bridge.issue_origin(bot, origin)
    command = shutil.which("hermes")
    if not command:
        bridge.revoke(ticket)
        raise CareerError("hermes_cli_missing", "Hermes CLIを確認してください。", 503)
    process = None
    try:
        env = dict(os.environ, **{TICKET_ENV: ticket})
        with tempfile.TemporaryFile() as query:
            query.write(prompt.encode("utf-8"))
            query.seek(0)
            process = subprocess.Popen([command, "--profile", bot, "chat", "--toolsets=career_coach", "-Q", "--query-file", "-"],
                stdin=query, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
                text=True, encoding="utf-8", errors="replace",
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 1200
        while True:
            if stopped.is_set() or time.monotonic() > deadline:
                raise CareerError("coach_stopped", "就活相談を停止しました。", 409)
            try:
                answer, errors = process.communicate(timeout=.2)
                break
            except subprocess.TimeoutExpired:
                continue
        if process.returncode != 0 or not answer.strip():
            _record_failure(store, bot, process.returncode, errors, not answer.strip(), ticket)
            raise CareerError("coach_runtime_failed", "就活相談を完了できませんでした。", 503)
        bridge.verify_ticket(ticket)
        return answer.strip()
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
        bridge.revoke(ticket)


class Consultations:
    def __init__(self, store, identity=host_identity, routing=None, dispatch=None, durable=None, runtime=run_profile):
        self.store, self.identity, self.routing = store, identity, routing
        self.dispatch, self.durable, self.runtime = dispatch, durable, runtime
        NativeCoach(store, identity=identity)
        with store.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS coach_consultations(
                id TEXT PRIMARY KEY, owner_key TEXT NOT NULL, request_id TEXT NOT NULL,
                request TEXT NOT NULL, state TEXT NOT NULL, reply TEXT,
                error TEXT, updated_at TEXT NOT NULL, UNIQUE(owner_key,request_id))""")

    def host(self, metadata, identity=None):
        caller, _ = identity or self.identity()
        session = metadata.get("session_id")
        if not isinstance(session, str) or not session:
            raise CareerError("coach_host_session_required", "依頼元の会話を確認してください。", 403)
        with self.store.connect() as db:
            profiles = self.store.meta(db, "coach_native_profiles", {})
        matches = [(name, value) for name, value in profiles.items()
                   if caller in value.get("callers", [value["owner"]]) and name != caller]
        if len(matches) != 1:
            raise CareerError("coach_not_configured", "就活コーチの接続を設定してください。", 503)
        bot, binding = matches[0]
        key = hashlib.sha256(encoded([caller, session]).encode()).hexdigest()
        return key, session, bot, binding

    def route(self, metadata):
        if self.routing:
            return self.routing(metadata)
        from gateway.session_context import async_delivery_supported, get_session_env
        from tools.approval_context import get_current_session_key
        if not async_delivery_supported():
            raise CareerError("coach_delivery_unavailable", "この接続では結果の自動返却が使えません。就活コーチとの個別会話を開いてください。", 503)
        session = metadata["session_id"]
        key = get_current_session_key(default="") or session
        if get_session_env("HERMES_SESSION_SOURCE", "") == "tui":
            key = session
        return {"session_key": key, "parent_session_id": session,
                "origin_ui_session_id": get_session_env("HERMES_UI_SESSION_ID", ""),
                "origin_session_id": get_session_env("HERMES_SESSION_ID", "")}

    def read(self, identity, owner):
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM coach_consultations WHERE id=? AND owner_key=?", (identity, owner)).fetchone()
        if not row:
            raise CareerError("consultation_missing", "この会話の依頼が見つかりません。", 404)
        return dict(row)

    def reconcile(self, row):
        durable = self.durable
        if durable is None:
            from tools.async_delegation import get_durable_delegation
            durable = get_durable_delegation
        state = durable(row["id"])
        if row["state"] == "running" and state and state.get("state") in {"error", "stalled", "unknown"}:
            with self.store.transaction() as db:
                db.execute("UPDATE coach_consultations SET state='paused',error='host_interrupted',updated_at=? WHERE id=? AND state='running'",
                           (now(), row["id"]))
            return self.read(row["id"], row["owner_key"])
        return row

    def call(self, name, args, metadata):
        caller, home = self.identity()
        owner, session, bot, binding = self.host(metadata, (caller, home))
        request_id = args.get("request_id")
        if not isinstance(request_id, str) or not 1 <= len(request_id) <= 100:
            raise CareerError("invalid_operation_id", "依頼IDを確認してください。")
        identity = "career-consult-" + hashlib.sha256((owner + "\0" + request_id).encode()).hexdigest()[:40]
        if name != "career_consult":
            result = self.reconcile(self.read(identity, owner))
            if name == "career_consult_stop":
                with self.store.transaction() as db:
                    db.execute("UPDATE coach_consultations SET state='stopped',updated_at=? WHERE id=? AND state IN ('queued','running')",
                               (now(), identity))
                result = self.read(identity, owner)
            return self.public(result)
        text = args.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > 100000:
            raise CareerError("invalid_message", "相談内容を確認してください。")
        routing = self.route(metadata)  # verify delivery before accepting work
        request = encoded({"text": text, "thread_id": args.get("thread_id")})
        with self.store.transaction() as db:
            existing = db.execute("SELECT request FROM coach_consultations WHERE id=?", (identity,)).fetchone()
            if existing and existing[0] != request:
                raise CareerError("operation_conflict", "同じ依頼IDで内容を変更できません。", 409)
            db.execute("INSERT OR IGNORE INTO coach_consultations VALUES(?,?,?,?,'queued',NULL,NULL,?)",
                       (identity, owner, request_id, request, now()))
        dispatch, durable = self.dispatch, self.durable
        if dispatch is None:
            from tools.async_delegation import dispatch_async_delegation_batch, get_durable_delegation
            dispatch, durable = dispatch_async_delegation_batch, get_durable_delegation
        previous = durable(identity) if durable else None
        if previous and previous.get("state") not in {"unknown", "error", "stalled"}:
            return self.public(self.read(identity, owner))

        service = self
        class Stop:
            def is_set(self):
                return service.read(identity, owner)["state"] == "stopped"

        def execute():
            with self.store.transaction() as db:
                row = db.execute("SELECT state,reply FROM coach_consultations WHERE id=?", (identity,)).fetchone()
                if row["state"] == "completed":
                    return {"results": [{"task_index": 0, "status": "completed", "summary": row["reply"]}]}
                if row["state"] != "queued":
                    return {"results": [{"task_index": 0, "status": "error", "summary": "就活相談は停止または中断しています。"}]}
                db.execute("UPDATE coach_consultations SET state='running',updated_at=? WHERE id=?", (now(), identity))
            with _origin_lock:
                captured = _turn_origins.get((str(self.store.path), caller, session, str(metadata.get("task_id", ""))))
            proofs = [captured[1]] if captured and time.monotonic() - captured[0] < 1800 else []
            parent_state = home / "state.db"
            sources = [{"profile": caller, "state_path": str(parent_state), "session_id": session}] if parent_state.is_file() else []
            origin = {"kind": "delegation", "owner": binding["owner"], "conversation_id": session,
                      "thread_id": args.get("thread_id"), "title": text[:60], "user_messages": proofs,
                      "source_sessions": sources}
            try:
                reply = self.runtime(bot, text, origin, Stop(), self.store)
                with self.store.transaction() as db:
                    db.execute("UPDATE coach_consultations SET state='completed',reply=?,updated_at=? WHERE id=? AND state='running'",
                               (reply, now(), identity))
            except Exception as error:
                reason = error.code if isinstance(error, CareerError) else "coach_runtime_failed"
                with self.store.transaction() as db:
                    db.execute("UPDATE coach_consultations SET state='paused',error=?,updated_at=? WHERE id=? AND state='running'",
                               (reason, now(), identity))
            result = self.public(self.read(identity, owner))
            return {"results": [{"task_index": 0, "status": "completed" if result["state"] == "completed" else "error",
                                  "summary": encoded(result)}]}

        def stop():
            self.call("career_consult_stop", {"request_id": request_id}, metadata)
        response = dispatch(goals=["就活コーチへの相談"], context=None, toolsets=[], role="career_consult",
                            model=None, delegation_id=identity, runner=execute, interrupt_fn=stop, **routing)
        if response.get("status") != "dispatched":
            stop()
            raise CareerError("coach_delivery_unavailable", "結果の返却経路を確認してください。", 503)
        return self.public(self.read(identity, owner))

    @staticmethod
    def public(row):
        return {key: row[key] for key in ("request_id", "state", "reply", "error", "updated_at")}


def register_consultations(ctx):
    def observe(**metadata):
        try:
            capture_turn(native_store(), metadata)
        except Exception:
            pass
    ctx.register_hook("pre_llm_call", observe)
    definitions = (
        ("career_consult", "常設の就活コーチに相談する。既存の本人情報・訂正・ES・全相談履歴を参照でき、結果は元会話へ自動返却。同じ依頼は同じrequest_id。", True),
        ("career_consult_status", "この元会話から就活コーチへ依頼した相談の状態・結果を読む。", False),
        ("career_consult_stop", "この元会話から依頼した就活相談を停止する。", False))
    for name, description, submit in definitions:
        def handler(args, _name=name, **metadata):
            try:
                return encoded(Consultations(native_store()).call(_name, args, metadata))
            except CareerError as error:
                return encoded({"error": error.code, "detail": error.detail})
            except Exception:
                return encoded({"error": "coach_unavailable"})
        properties = {"request_id": {"type": "string"}}
        if submit:
            properties.update(text={"type": "string"}, thread_id={"type": "string"})
        ctx.register_tool(name=name, toolset="career", handler=handler, schema={"name": name,
            "description": description, "parameters": {"type": "object", "properties": properties,
            "required": ["request_id", "text"] if submit else ["request_id"], "additionalProperties": False}})
