"""Durable group queue. HTTP readers have no ownership of the running discussion."""
import json
import logging
import re
import sqlite3
import subprocess
import tempfile
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path


def timestamp():
    return int(time.time() * 1000)


class GroupError(Exception):
    def __init__(self, code, status=409, *, diagnostics=None):
        self.code, self.status = code, status
        self.diagnostics = diagnostics


class CliRuntime:
    """Use the host's configured Hermes profile, provider, tools and approval policy."""
    def __init__(self, owner_profile="default"):
        self.owner_profile = owner_profile
        import shutil
        self.command = shutil.which("hermes")
        if not self.command:
            raise GroupError("hermes_runtime_missing", 503)

    def validate(self, members):
        from hermes_cli.profiles import profile_exists
        if not all(profile_exists(member) for member in members):
            raise GroupError("member_not_found", 404)

    def reply(self, member, prompt, stopped):
        return self._reply(member, prompt, stopped)

    def reply_with_context(self, member, prompt, stopped, context):
        from hermes_cli.profiles import get_profile_dir
        marker = get_profile_dir(member) / "career-coach.json"
        if not marker.is_file():
            if member == "career-coach":
                raise GroupError("coach_not_configured", 503)
            return self.reply(member, prompt, stopped)
        try:
            value = json.loads(marker.read_text(encoding="utf-8"))
            import sys
            plugin_root = value["plugin_root"]
            if plugin_root not in sys.path:
                sys.path.insert(0, plugin_root)
            from career_core.coach_consult import run_profile
            from career_core.store import Store
            store = Store(value["data_directory"])
            with store.connect() as db:
                binding = store.meta(db, "coach_native_profiles", {}).get(member)
            if not binding or self.owner_profile not in binding.get("callers", [binding["owner"]]):
                raise GroupError("coach_owner_mismatch", 403)
            origin = dict(context, kind="group", owner=binding["owner"])
            return run_profile(member, prompt, origin, stopped, store)
        except GroupError:
            raise
        except Exception:
            raise GroupError("coach_context_unavailable", 503) from None

    def review(self, member, prompt, stopped):
        # A dedicated, empty toolset keeps the evaluator on transcript analysis.
        return self._reply(member, prompt, stopped, review=True)

    def _reply(self, member, prompt, stopped, review=False):
        deadline = time.monotonic() + 1200
        capacity_deadline = time.monotonic() + 60
        attempts = 0
        while True:
            if stopped.is_set():
                raise GroupError("stopped")
            attempts += 1
            try:
                return self._reply_once(member, prompt, stopped, review, deadline)
            except GroupError as failure:
                # Only a native, pre-session capacity refusal is safe to submit
                # again. Never replay an unknown failure or a completed model call.
                if failure.code != "runtime_capacity":
                    raise
                failure.diagnostics["attempts"] = attempts
                if attempts >= 6 or time.monotonic() >= capacity_deadline:
                    raise
                until = min(time.monotonic() + min(2 ** attempts, 10), capacity_deadline)
                while time.monotonic() < until:
                    if stopped.is_set():
                        raise GroupError("stopped")
                    time.sleep(max(0, min(.2, until - time.monotonic())))
                if time.monotonic() >= capacity_deadline:
                    raise

    def _reply_once(self, member, prompt, stopped, review, deadline):
        # No shell and no transcript in logs. Quiet mode returns only the final answer.
        # Each turn has a fresh profile-owned session and receives the durable group
        # transcript explicitly; a client is never needed to drive the next turn.
        # The transcript can exceed the OS per-argument byte limit. Reuse the
        # CLI's stdin query contract without truncating or exposing it in argv.
        # The child inherits its own handle; the parent's temporary handle closes
        # after launch, including when launch fails. No named prompt file remains.
        with tempfile.TemporaryFile() as diagnostic:
            return self._run_cli(member, prompt, stopped, review, deadline, diagnostic)

    def _run_cli(self, member, prompt, stopped, review, deadline, diagnostic):
        stage = "review" if review else "member"
        with tempfile.TemporaryFile() as query:
            query.write(prompt.encode("utf-8"))
            query.seek(0)
            review_options = {}
            if review:
                import os
                review_options["env"] = dict(os.environ, HERMES_CAREER_REVIEW="1")
            try:
                process = subprocess.Popen(
                    [self.command, "--profile", member, "chat", *(["--toolsets=group_review"] if review else []), "-Q", "--query-file", "-"],
                    stdin=query, stdout=subprocess.PIPE, stderr=diagnostic,
                    text=True, encoding="utf-8", errors="replace", **review_options,
                )
            except OSError as failure:
                raise GroupError("runtime_launch_failed", diagnostics={
                    "stage": stage, "kind": "launch", "exception": type(failure).__name__, "errno": failure.errno,
                }) from None
        try:
            while True:
                try:
                    output, _ = process.communicate(timeout=0.2)
                    answer = output.strip()
                    if review:
                        # This host prints this startup warning even with -Q.
                        # group_review intentionally resolves to zero tools. Remove
                        # only the exact known prefix; keep strict JSON validation
                        # and never search arbitrary stdout for a usable verdict.
                        warning = "Warning: Unknown toolsets: group_review\n"
                        if answer == warning.rstrip("\n"):
                            answer = ""
                        elif answer.startswith(warning):
                            answer = answer[len(warning):].strip()
                    if process.returncode != 0 or not answer:
                        diagnostic.seek(0, 2)
                        stderr_bytes = diagnostic.tell()
                        diagnostic.seek(0)
                        # Raw stderr may contain secrets or message text. It stays
                        # in this anonymous file; persist only this allowlisted data.
                        raw = diagnostic.read(65537)
                        refusals = re.findall(
                            rb"(?m)^hermes-refusal-reason: ([A-Z_]+)\r?$", raw,
                        ) if stderr_bytes <= 65536 else []
                        allowed = {b"MAX_CONCURRENT_SESSIONS", b"SESSION_NOT_OWNED", b"SESSION_COORDINATION_UNAVAILABLE"}
                        reason = refusals[0].decode("ascii") if len(refusals) == 1 and refusals[0] in allowed else None
                        details = {"stage": stage, "kind": "exit" if process.returncode else "empty_output",
                                   "returnCode": process.returncode, "stdoutBytes": len(output.encode("utf-8")),
                                   "stdoutBlank": not bool(output.strip()), "answerBlank": not bool(answer), "stderrBytes": stderr_bytes}
                        code = "runtime_exit_failed" if process.returncode else "runtime_empty_output"
                        if reason:
                            details["refusalReason"] = reason
                        if (reason == "MAX_CONCURRENT_SESSIONS" and process.returncode == 1 and not answer
                                and not re.search(rb"(?m)^session_id:", raw)):
                            code = "runtime_capacity"
                        raise GroupError(code, diagnostics=details)
                    return answer
                except subprocess.TimeoutExpired:
                    if stopped.is_set() or time.monotonic() > deadline:
                        process.terminate()
                        try:
                            process.communicate(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.communicate()
                        raise GroupError("stopped" if stopped.is_set() else "turn_timeout")
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()


class GroupService:
    def __init__(self, path, runtime, *, start=True):
        self.path, self.runtime = Path(path), runtime
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.wake, self.closed = threading.Event(), threading.Event()
        self.stops = {}
        self.owner = open(str(self.path) + ".lock", "a+b")
        try:
            if __import__("os").name == "nt":
                import msvcrt
                self.owner.seek(0)
                self.owner.write(b"0")
                self.owner.flush()
                self.owner.seek(0)
                msvcrt.locking(self.owner.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.owner.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.owner.close()
            raise GroupError("worker_already_running", 503) from None
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY, group_name TEXT NOT NULL, title TEXT NOT NULL,
                    created INTEGER NOT NULL, updated INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT UNIQUE NOT NULL, conversation TEXT NOT NULL, payload TEXT NOT NULL,
                    position INTEGER PRIMARY KEY AUTOINCREMENT);
            """)
            db.executescript("""
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY, conversation TEXT NOT NULL, request TEXT NOT NULL,
                    state TEXT NOT NULL, speaker TEXT, error TEXT, created INTEGER NOT NULL);
                CREATE UNIQUE INDEX IF NOT EXISTS one_active_run ON runs(conversation)
                    WHERE state IN ('queued','running');
                CREATE TABLE IF NOT EXISTS run_diagnostics (
                    run_id TEXT PRIMARY KEY, payload TEXT NOT NULL);
            """)
            # Never replay a model/tool call after a server crash. Preserve every saved
            # message and expose the interrupted state for a deliberate new request.
            db.execute("UPDATE runs SET state='interrupted', speaker=NULL, error='server_restarted' WHERE state='running'")
        from group_supervision import Supervision
        self.supervision = Supervision(self)
        self.thread = None
        if start:
            self.heartbeat_thread = threading.Thread(target=self._heartbeat, daemon=True, name="group-heartbeat")
            self.heartbeat_thread.start()
            self.thread = threading.Thread(target=self.loop, daemon=True, name="group-discussions")
            self.thread.start()

    def _heartbeat(self):
        while not self.closed.is_set():
            try:
                self.supervision.heartbeat()
            except Exception as failure:
                logging.getLogger(__name__).warning("Group heartbeat failed: %s", type(failure).__name__)
            self.closed.wait(2)

    @contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=15)
            db.row_factory = sqlite3.Row
            try:
                db.execute("PRAGMA journal_mode=WAL")
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise
            finally:
                db.close()

    def add_message(self, db, conversation, message):
        db.execute("INSERT INTO messages(id,conversation,payload) VALUES(?,?,?)",
                   (message["id"], conversation, json.dumps(message, ensure_ascii=False)))
        db.execute("UPDATE conversations SET updated=? WHERE id=?", (timestamp(), conversation))

    def snapshot(self, conversation, group, *, include_supervision=True):
        with self.db() as db:
            row = db.execute("SELECT * FROM conversations WHERE id=? AND group_name=?", (conversation, group)).fetchone()
            if not row:
                raise GroupError("conversation_not_found", 404)
            messages = [json.loads(r[0]) for r in db.execute(
                "SELECT payload FROM messages WHERE conversation=? ORDER BY position", (conversation,))]
            run = db.execute("SELECT * FROM runs WHERE conversation=? ORDER BY created DESC, rowid DESC LIMIT 1", (conversation,)).fetchone()
            result = {"id": row["id"], "groupName": group, "title": row["title"],
                    "createdAt": row["created"], "messages": messages,
                    "runId": run["id"] if run else None, "state": run["state"] if run else "idle",
                    "activeSpeaker": run["speaker"] if run else None, "error": run["error"] if run else None}
            if run:
                diagnostic = db.execute("SELECT payload FROM run_diagnostics WHERE run_id=?", (run["id"],)).fetchone()
                if diagnostic:
                    result["diagnostics"] = json.loads(diagnostic[0])
            if include_supervision:
                task = db.execute("SELECT * FROM delegations WHERE conversation=?", (conversation,)).fetchone()
                if task and (not run or task["run_id"] == run["id"]):
                    result["discussionState"] = result["state"]
                    result["delegation"] = self.supervision.store.public(dict(task))
                    if task["state"] not in {"complete", "needs_input", "capped", "failed", "interrupted", "stopped"}:
                        result["state"] = "running"
                        if task["state"] == "reviewing":
                            result["activeSpeaker"] = json.loads(task["request"])["supervisor"]
                    else:
                        result["state"] = task["state"]
                        result["activeSpeaker"] = None
                        if task["result"]:
                            saved = json.loads(task["result"])
                            result["error"] = saved.get("reason")
                            if saved.get("diagnostics"):
                                result["diagnostics"] = saved["diagnostics"]
            return result

    def history(self):
        with self.db() as db:
            return [dict(id=r["id"], groupName=r["group_name"], title=r["title"],
                         createdAt=r["created"], updatedAt=r["updated"])
                    for r in db.execute("SELECT * FROM conversations ORDER BY updated DESC")]

    def rename(self, conversation, payload):
        group, title, expected = payload.get("groupName"), payload.get("title"), payload.get("expectedTitle")
        if (not isinstance(group, str) or not group.strip() or len(group) > 300
                or not isinstance(title, str) or not title.strip() or len(title) > 80
                or any(ord(char) < 32 for char in title)
                or not isinstance(expected, str) or len(expected) > 100000):
            raise GroupError("invalid_title", 422)
        title = title.strip()
        with self.db() as db:
            row = db.execute("SELECT title FROM conversations WHERE id=? AND group_name=?",
                             (conversation, group)).fetchone()
            if not row:
                raise GroupError("conversation_not_found", 404)
            if row["title"] not in {expected, title}:
                raise GroupError("title_conflict")
            # Metadata only: never add a message, create a run, or change activity ordering.
            db.execute("UPDATE conversations SET title=? WHERE id=? AND group_name=?", (title, conversation, group))
        return self.snapshot(conversation, group)

    def submit(self, conversation, payload):
        group, text, request_id = payload.get("groupName"), payload.get("text"), payload.get("requestId")
        members = payload.get("members", [])
        if (not isinstance(group, str) or not group.strip() or len(group) > 300
                or not isinstance(text, str) or not text.strip() or len(text) > 100000
                or not isinstance(request_id, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", request_id)
                or not isinstance(members, list) or not 1 <= len(members) <= 20
                or any(not isinstance(m, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", m) for m in members)
                or len(set(members)) != len(members)):
            raise GroupError("invalid_request", 422)
        for key, low, high, default in [("rounds", 1, 10, 1), ("maxMessages", 1, 50, 6), ("maxContinuationPasses", 0, 20, 2)]:
            if type(payload.get(key, default)) is not int or not low <= payload.get(key, default) <= high:
                raise GroupError("invalid_limits", 422)
        if payload.get("summaryMember") not in [None, *members]:
            raise GroupError("invalid_summary", 422)
        initial = payload.get("initialResponders", members)
        if (not isinstance(initial, list) or not initial or any(m not in members for m in initial)
                or len(set(initial)) != len(initial) or not isinstance(payload.get("memberLabels", {}), dict)
                or any(not isinstance(v, str) or len(v) > 300 for v in payload.get("memberLabels", {}).values())
                or not isinstance(payload.get("systemPrompt") or "", str)
                or len(payload.get("systemPrompt") or "") > 100000):
            raise GroupError("invalid_request", 422)
        self.runtime.validate(members)
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        with self.db() as db:
            task = db.execute("SELECT state,run_id FROM delegations WHERE conversation=?", (conversation,)).fetchone()
            if task and task["state"] not in {"complete", "needs_input", "capped", "failed", "interrupted", "stopped"} and request_id != task["run_id"]:
                raise GroupError("discussion_managed")
            existing = db.execute("SELECT * FROM runs WHERE id=?", (request_id,)).fetchone()
            if existing:
                if existing["conversation"] != conversation or existing["request"] != encoded:
                    raise GroupError("request_conflict")
            else:
                row = db.execute("SELECT * FROM conversations WHERE id=?", (conversation,)).fetchone()
                if row and row["group_name"] != group:
                    raise GroupError("conversation_conflict")
                if db.execute("SELECT 1 FROM runs WHERE conversation=? AND state IN ('queued','running')", (conversation,)).fetchone():
                    raise GroupError("discussion_running")
                if not row:
                    db.execute("INSERT INTO conversations VALUES(?,?,?,?,?)", (conversation, group, text[:60], timestamp(), timestamp()))
                    # Import the old local history only when first taking ownership.
                    seed = payload.get("seed", [])
                    if not isinstance(seed, list) or len(seed) > 5000:
                        raise GroupError("invalid_history", 422)
                    for message in seed:
                        if (not isinstance(message, dict) or not isinstance(message.get("id"), str)
                                or not isinstance(message.get("text"), str)
                                or not isinstance(message.get("senderName"), str)
                                or type(message.get("isUser")) is not bool):
                            raise GroupError("invalid_history", 422)
                        self.add_message(db, conversation, message)
                self.add_message(db, conversation, dict(id=request_id, senderName="user", senderDisplayName="You",
                                                       isUser=True, text=text, timestamp=timestamp()))
                db.execute("INSERT INTO runs VALUES(?,?,?,'queued',NULL,NULL,?)", (request_id, conversation, encoded, timestamp()))
        self.wake.set()
        return self.snapshot(conversation, group)

    def stop(self, conversation, group):
        self.snapshot(conversation, group)
        with self.db() as db:
            row = db.execute("SELECT id FROM runs WHERE conversation=? AND state IN ('queued','running')", (conversation,)).fetchone()
            if row:
                self.stops.setdefault(row[0], threading.Event()).set()
                db.execute("UPDATE runs SET state='stopped',speaker=NULL WHERE id=?", (row[0],))
            db.execute("""UPDATE delegations SET state='stopped',updated=?,result=? WHERE conversation=?
                AND state NOT IN ('complete','needs_input','capped','failed','interrupted','stopped')""",
                       (timestamp(), json.dumps({"reason": "user_stopped"}), conversation))
        return self.snapshot(conversation, group)

    def loop(self):
        while not self.closed.is_set():
            try:
                self.supervision.heartbeat()
                progressed = self.supervision.tick()
                ran = self.run_one()
            except Exception as failure:
                logging.getLogger(__name__).warning("Group worker iteration failed: %s", type(failure).__name__)
                progressed, ran = False, False
            if not ran and not progressed:
                self.wake.wait(1)
                self.wake.clear()

    def run_one(self):
        with self.db() as db:
            row = db.execute("SELECT * FROM runs WHERE state='queued' ORDER BY created,rowid LIMIT 1").fetchone()
            if not row:
                return False
            run = dict(row)
            db.execute("UPDATE runs SET state='running' WHERE id=? AND state='queued'", (run["id"],))
            stopped = self.stops.setdefault(run["id"], threading.Event())
        diagnostics = None
        try:
            stopped = self.supervision.cancellation(run["conversation"], stopped, run_id=run["id"])
            payload = json.loads(run["request"])
            members = payload["members"]
            initial = payload.get("initialResponders", members)
            controlled = payload.get("controlledOrder", False)
            direct = payload.get("direct", False)
            rounds = 1 if direct or not controlled else payload.get("rounds", 1)
            remaining, priority = list(initial), []
            round_number, extra, attempted = 1, 0, 0
            summary_done = False
            budget = payload.get("maxMessages", 6)
            while True:
                if not remaining and not priority and round_number < rounds:
                    remaining = list(initial)
                    round_number += 1
                summary = not remaining and not priority and controlled and not direct and payload.get("summaryMember") and not summary_done
                if not remaining and not priority and not summary:
                    break
                if attempted >= budget:
                    raise GroupError("message_limit")
                member = payload["summaryMember"] if summary else (priority.pop(0) if priority else remaining.pop(0))
                summary_done = bool(summary)
                attempted += 1
                if stopped.is_set() or self.closed.is_set():
                    raise GroupError("stopped")
                with self.db() as db:
                    db.execute("UPDATE runs SET speaker=? WHERE id=? AND state='running'", (member, run["id"]))
                snapshot = self.snapshot(run["conversation"], payload["groupName"])
                transcript = json.dumps(snapshot["messages"], ensure_ascii=False)
                if len(transcript) > 300000:
                    raise GroupError("context_too_large")
                purpose = "これまでの議論をまとめてください。" if summary else "あなた自身の立場で議論に参加してください。"
                prompt = (f"グループ {payload['groupName']} の会話です。あなたは {member} です。{purpose}\n"
                          f"参加者: {', '.join('@' + m for m in members)}。現在は {round_number}/{rounds} 巡目です。\n"
                          "返答はそのまま全員に表示されます。必要なら @名前 で他の参加者に発言を求めてください。\n"
                          "以下は発言者付きの会話履歴です。他の参加者の発言をあなたの発言として扱わないでください。\n"
                          + str(payload.get("systemPrompt") or "") + "\n" + transcript)
                contextual = getattr(self.runtime, "reply_with_context", None)
                if contextual:
                    with self.db() as db:
                        delegated = db.execute("SELECT 1 FROM delegations WHERE conversation=?", (run["conversation"],)).fetchone()
                    origin = {"conversation_id": run["conversation"], "title": payload["groupName"],
                              "user_messages": [] if delegated else [{"id": "group:" + run["id"], "text": payload["text"]}]}
                    reply = contextual(member, prompt, stopped, origin)
                else:
                    reply = self.runtime.reply(member, prompt, stopped)
                passing = reply.strip().lower() in {"(pass)", "pass", "(pass).", "pass."}
                with self.db() as db:
                    state = db.execute("SELECT state FROM runs WHERE id=?", (run["id"],)).fetchone()[0]
                    if state != "running" or stopped.is_set():
                        raise GroupError("stopped")
                    names = payload.get("memberLabels", {})
                    self.add_message(db, run["conversation"], dict(
                        id=str(uuid.uuid4()), senderName=member, senderDisplayName=names.get(member, member),
                        isUser=False, text=reply, timestamp=timestamp(), isSystem=passing, isPass=passing))
                if not direct and not summary:
                    promoted = []
                    handles = {}
                    for candidate in members:
                        label = payload.get("memberLabels", {}).get(candidate, candidate).lower()
                        for handle in [candidate.lower(), re.sub(r"[\s_-]+", "", candidate.lower()), label.replace(" ", ""), label.split()[0] if label.split() else label]:
                            handles[handle] = candidate
                    for handle in re.findall(r"@([\w.-]+)", reply.lower()):
                        target = handles.get(handle) or handles.get(re.sub(r"[._-]+", "", handle))
                        if target == member or target not in initial or target in promoted:
                            continue
                        if target in priority:
                            priority.remove(target)
                        elif target in remaining:
                            remaining.remove(target)
                        elif extra < payload.get("maxContinuationPasses", 2):
                            extra += 1
                        else:
                            continue
                        promoted.append(target)
                    priority[:0] = promoted
            state, error = "complete", None
        except Exception as failure:
            error = failure.code if isinstance(failure, GroupError) else "member_failed"
            diagnostics = failure.diagnostics if isinstance(failure, GroupError) else {
                "stage": "member", "kind": "exception", "exception": type(failure).__name__}
            state = "stopped" if stopped.is_set() else ("capped" if error == "message_limit" else "failed")
        finally:
            self.stops.pop(run["id"], None)
        while True:
            try:
                with self.db() as db:
                    changed = db.execute("UPDATE runs SET state=?,error=?,speaker=NULL WHERE id=? AND state='running'", (state, error, run["id"])).rowcount
                    if changed and diagnostics:
                        db.execute("INSERT OR REPLACE INTO run_diagnostics VALUES(?,?)", (run["id"], json.dumps(diagnostics)))
                break
            except sqlite3.OperationalError:
                if self.closed.wait(0.2):
                    raise
        return True

    def close(self):
        self.closed.set()
        if hasattr(self, "heartbeat_thread"):
            self.heartbeat_thread.join(timeout=3)
        for event in list(self.stops.values()):
            event.set()
        self.wake.set()
        if self.thread:
            self.thread.join(timeout=15)
        if not self.thread or not self.thread.is_alive():
            self.owner.close()
