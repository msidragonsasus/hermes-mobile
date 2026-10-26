import hashlib
import json
import os
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit

JST = timezone(timedelta(hours=9))
SOURCES = {"gmail": "メール", "manaba": "manaba"}


class PlanError(Exception):
    def __init__(self, code, status=422):
        self.code, self.status = code, status


def normalize_plan(answer):
    # A model's optional explanation is inert; task/evidence validation stays strict.
    if not isinstance(answer, dict) or set(answer) - {'tasks', 'resolved', 'verification_notes'}:
        raise PlanError('invalid_plan')
    return {k: v for k, v in answer.items() if k in {'tasks', 'resolved'}}


def parse_plan_response(final):
    if not isinstance(final, str) or not final.strip() or len(final) > 100_000:
        raise PlanError('invalid_plan')
    try:
        return normalize_plan(json.loads(final))
    except (TypeError, ValueError):
        raise PlanError('invalid_plan') from None


def now():
    return datetime.now(JST)


def safe_url(value):
    try:
        parsed = urlsplit(value)
        return (parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username
                and not parsed.password and len(value) <= 2048)
    except (ValueError, TypeError):
        return False


class PlanStore:
    def __init__(self, home, clock=now):
        self.root = Path(home) / "today_home"
        self.clock = clock
        self.lease_tokens = {}

    @contextmanager
    def db(self):
        if self.root.is_symlink():
            raise PlanError("storage_unavailable", 503)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        path = self.root / "plan.sqlite3"
        if path.is_symlink():
            raise PlanError("storage_unavailable", 503)
        fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(fd)
        db = sqlite3.connect(path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, day TEXT NOT NULL, state TEXT NOT NULL, started TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS source_cache (id TEXT PRIMARY KEY, source TEXT NOT NULL, payload TEXT NOT NULL, checked_at TEXT NOT NULL, last_run TEXT NOT NULL DEFAULT '', run_token TEXT NOT NULL DEFAULT '');
                CREATE TABLE IF NOT EXISTS source_checks (request_id TEXT NOT NULL, source TEXT NOT NULL, state TEXT NOT NULL, checked_at TEXT NOT NULL, PRIMARY KEY(request_id,source));
                CREATE TABLE IF NOT EXISTS resolution_events (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS run_contexts (request_id TEXT PRIMARY KEY, token TEXT NOT NULL, settings TEXT NOT NULL, revision INTEGER NOT NULL, expires TEXT NOT NULL, session_id TEXT UNIQUE, stored_session_id TEXT UNIQUE, admission_id TEXT, input_id TEXT NOT NULL, phase TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS run_calls (request_id TEXT NOT NULL, tool TEXT NOT NULL, used INTEGER NOT NULL, PRIMARY KEY(request_id,tool));
                CREATE TABLE IF NOT EXISTS daily_sessions (session_id TEXT PRIMARY KEY, token TEXT NOT NULL);
            """)
            if not {'last_run', 'run_token'} <= {r["name"] for r in db.execute("PRAGMA table_info(source_cache)")}:
                db.execute("BEGIN IMMEDIATE")
                columns = {r["name"] for r in db.execute("PRAGMA table_info(source_cache)")}
                if 'last_run' not in columns:
                    db.execute("ALTER TABLE source_cache ADD COLUMN last_run TEXT NOT NULL DEFAULT ''")
                if 'run_token' not in columns:
                    db.execute("ALTER TABLE source_cache ADD COLUMN run_token TEXT NOT NULL DEFAULT ''")
                db.commit()
            with db:
                yield db
        finally:
            db.close()

    def settings(self):
        with self.db() as db:
            row = db.execute("SELECT value FROM state WHERE key='settings'").fetchone()
            return json.loads(row[0]) if row else {"email": "", "manaba_url": "", "enabled": False}

    def configure(self, value):
        if (not isinstance(value, dict) or set(value) != {"email", "manaba_url", "enabled"}
                or type(value["enabled"]) is not bool
                or not isinstance(value["email"], str) or len(value["email"]) > 254
                or (value["email"] and ("@" not in value["email"] or any(c.isspace() for c in value["email"])))
                or not isinstance(value["manaba_url"], str)
                or (value["manaba_url"] and (not safe_url(value["manaba_url"])
                    or urlsplit(value["manaba_url"]).path not in {"/ct/home", "/ct/"}
                    or urlsplit(value["manaba_url"]).query or urlsplit(value["manaba_url"]).fragment))):
            raise PlanError("invalid_settings")
        value = dict(value, email=value["email"].strip().lower())
        if value["enabled"] and not (value["email"] or value["manaba_url"]):
            raise PlanError("source_required")
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            for row in db.execute("SELECT id,started FROM requests WHERE state='running'").fetchall():
                if self.clock() - datetime.fromisoformat(row["started"]) > timedelta(minutes=15):
                    db.execute("UPDATE requests SET state='interrupted' WHERE id=?", (row["id"],))
            if db.execute("SELECT 1 FROM requests WHERE state='running'").fetchone():
                raise PlanError("refresh_running", 409)
            old = db.execute("SELECT value FROM state WHERE key='settings'").fetchone()
            previous = json.loads(old[0]) if old else {}
            if previous == value:
                return value
            db.execute("INSERT OR REPLACE INTO state VALUES('settings',?)", (json.dumps(value),))
            revision = db.execute("SELECT value FROM state WHERE key='settings_revision'").fetchone()
            db.execute("INSERT OR REPLACE INTO state VALUES('settings_revision',?)", (json.dumps((json.loads(revision[0]) if revision else 0) + 1),))
            # A different account must never inherit the previous account's plan.
            changed = {s for s, key in (("gmail", "email"), ("manaba", "manaba_url")) if previous.get(key) != value[key]}
            for task in db.execute("SELECT id,payload FROM tasks").fetchall():
                if json.loads(task["payload"])["source"] in changed:
                    db.execute("DELETE FROM tasks WHERE id=?", (task["id"],))
            for source in changed:
                db.execute("DELETE FROM source_cache WHERE source=?", (source,))
                db.execute("DELETE FROM resolution_events WHERE source=?", (source,))
            if changed:
                db.execute("DELETE FROM state WHERE key IN ('coverage','checked_at','day')")
            db.execute("DELETE FROM requests")
            db.execute("DELETE FROM source_checks")
            db.execute("DELETE FROM run_contexts")
            db.execute("DELETE FROM run_calls")
        return value

    def begin(self, request_id, automatic=False):
        import re
        if not isinstance(request_id, str) or not re.fullmatch(r"[a-zA-Z0-9:_-]{1,100}", request_id):
            raise PlanError("invalid_request")
        current = self.clock()
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            settings_row = db.execute("SELECT value FROM state WHERE key='settings'").fetchone()
            settings = json.loads(settings_row[0]) if settings_row else {}
            if not settings.get("enabled"):
                raise PlanError("setup_required", 409)
            # A lost process is reported as interrupted, never silently replayed.
            for row in db.execute("SELECT id,started FROM requests WHERE state='running'").fetchall():
                if current - datetime.fromisoformat(row["started"]) > timedelta(minutes=15):
                    db.execute("UPDATE requests SET state='interrupted' WHERE id=?", (row["id"],))
            if db.execute("SELECT 1 FROM requests WHERE id=?", (request_id,)).fetchone():
                return False
            if db.execute("SELECT 1 FROM requests WHERE state='running'").fetchone():
                return False
            day = current.date().isoformat()
            if automatic and db.execute("SELECT 1 FROM requests WHERE day=?", (day,)).fetchone():
                return False
            db.execute("INSERT INTO requests VALUES(?,?,'running',?)", (request_id, day, current.isoformat()))
            token = str(uuid.uuid4())
            revision = db.execute("SELECT value FROM state WHERE key='settings_revision'").fetchone()
            db.execute("INSERT INTO run_contexts(request_id,token,settings,revision,expires,input_id,phase) VALUES(?,?,?,?,?,?,'accepted')",
                       (request_id, token, json.dumps(settings, sort_keys=True), json.loads(revision[0]) if revision else 0,
                        (current + timedelta(minutes=10)).isoformat(), str(uuid.uuid5(uuid.NAMESPACE_URL, token + ':prompt'))))
        self.lease_tokens[request_id] = token
        return True

    def _active_context(self, db, request_id, *, check_expiry=True, allowed_states=('running',)):
        row = db.execute("SELECT c.*,r.state FROM run_contexts c JOIN requests r ON r.id=c.request_id WHERE c.request_id=?", (request_id,)).fetchone()
        revision = db.execute("SELECT value FROM state WHERE key='settings_revision'").fetchone()
        settings = db.execute("SELECT value FROM state WHERE key='settings'").fetchone()
        expected = self.lease_tokens.get(request_id)
        if (not row or row['state'] not in allowed_states or (check_expiry and datetime.fromisoformat(row['expires']) <= self.clock())
                or (expected is not None and expected != row['token'])
                or row['revision'] != (json.loads(revision[0]) if revision else 0)
                or not settings or json.loads(row['settings']) != json.loads(settings[0])):
            raise PlanError("active_daily_run_required", 409)
        return dict(row)

    def run_context(self, request_id):
        with self.db() as db:
            return self._active_context(db, request_id)

    def run_record(self, request_id):
        with self.db() as db:
            row = db.execute("SELECT * FROM run_contexts WHERE request_id=?", (request_id,)).fetchone()
            if not row or self.lease_tokens.get(request_id) != row['token']:
                raise PlanError('request_changed', 409)
            return dict(row)

    def _read_context(self, db, request_id):
        context = self._active_context(db, request_id)
        if context['session_id'] and context['phase'] not in {'bound', 'sending', 'submitted'}:
            raise PlanError('active_daily_run_required', 409)
        return context

    def bind_session(self, request_id, session_id, stored_session_id):
        if not all(isinstance(s, str) and 1 <= len(s) <= 200 for s in (session_id, stored_session_id)):
            raise PlanError("invalid_daily_session")
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            context = self._active_context(db, request_id)
            if self.lease_tokens.get(request_id) != context['token']:
                raise PlanError("daily_session_binding_denied", 403)
            if context['session_id'] and (context['session_id'], context['stored_session_id']) != (session_id, stored_session_id):
                raise PlanError("daily_session_changed", 409)
            if context['session_id']:
                return
            for identity in {session_id, stored_session_id}:
                old = db.execute("SELECT token FROM daily_sessions WHERE session_id=?", (identity,)).fetchone()
                if old and old['token'] != context['token']:
                    raise PlanError("daily_session_changed", 409)
                db.execute("INSERT OR IGNORE INTO daily_sessions VALUES(?,?)", (identity, context['token']))
            db.execute("UPDATE run_contexts SET session_id=?,stored_session_id=?,phase='bound' WHERE request_id=?",
                       (session_id, stored_session_id, request_id))

    def record_admission(self, request_id, admission_id):
        if not isinstance(admission_id, str) or not 1 <= len(admission_id) <= 200:
            raise PlanError("invalid_daily_admission")
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            context = self._active_context(db, request_id, check_expiry=False)
            if self.lease_tokens.get(request_id) != context['token'] or not context['session_id']:
                raise PlanError("daily_session_binding_denied", 403)
            if context['admission_id'] and context['admission_id'] != admission_id:
                raise PlanError("daily_admission_changed", 409)
            db.execute("UPDATE run_contexts SET admission_id=?,phase='submitted' WHERE request_id=?", (admission_id, request_id))

    def prepare_submit(self, request_id):
        with self.db() as db:
            context = self._active_context(db, request_id)
            if self.lease_tokens.get(request_id) != context['token'] or not context['session_id'] or context['phase'] != 'bound':
                raise PlanError("daily_session_binding_denied", 403)
            db.execute("UPDATE run_contexts SET phase='sending' WHERE request_id=?", (request_id,))

    def context_for_session(self, session_id):
        if not isinstance(session_id, str) or not session_id:
            raise PlanError("active_daily_run_required", 409)
        with self.db() as db:
            row = db.execute("SELECT request_id FROM run_contexts WHERE session_id=? OR stored_session_id=?", (session_id, session_id)).fetchone()
            if not row:
                raise PlanError("active_daily_run_required", 409)
            context = self._active_context(db, row['request_id'])
            if context['phase'] not in {'bound', 'sending', 'submitted'}:
                raise PlanError("active_daily_run_required", 409)
            return context

    def was_daily_session(self, session_id):
        if not isinstance(session_id, str) or not session_id:
            return False
        with self.db() as db:
            return db.execute("SELECT 1 FROM daily_sessions WHERE session_id=?", (session_id,)).fetchone() is not None

    def record_terminal(self, request_id, receipt):
        # Retain only bounded execution metadata, never result/history/credentials.
        metadata = {'status': 'terminal', 'outcome': receipt.get('outcome') if receipt.get('outcome') in {'completed', 'failed', 'cancelled', 'interrupted'} else 'unrecognized'}
        if type(receipt.get('execution_generation')) is int:
            metadata['execution_generation'] = receipt['execution_generation']
        usage = receipt.get('usage')
        if isinstance(usage, dict):
            metadata['usage'] = {k: v for k, v in usage.items() if k in {'api_calls', 'input_tokens', 'output_tokens', 'total_tokens'} and type(v) is int}
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            context = self._active_context(db, request_id, check_expiry=False)
            if self.lease_tokens.get(request_id) != context['token']:
                raise PlanError("daily_session_binding_denied", 403)
            changed = db.execute("UPDATE run_contexts SET phase='terminal' WHERE request_id=? AND token=? AND EXISTS(SELECT 1 FROM requests r WHERE r.id=run_contexts.request_id AND r.state='running')",
                                 (request_id, context['token'])).rowcount
            if not changed:
                raise PlanError('request_changed', 409)
            metadata.update(request_id=request_id, run_token=context['token'])
            db.execute("INSERT OR REPLACE INTO state VALUES(?,?)", ('runtime:' + context['token'], json.dumps(metadata)))

    def uncertain(self, request_id):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            token = self.lease_tokens.get(request_id)
            changed = db.execute("UPDATE run_contexts SET phase='uncertain' WHERE request_id=? AND token=? AND phase IN ('accepted','bound','sending','submitted') AND EXISTS(SELECT 1 FROM requests r WHERE r.id=run_contexts.request_id AND r.state='running')",
                                 (request_id, token)).rowcount
            if changed:
                db.execute("UPDATE requests SET state='uncertain' WHERE id=? AND state='running' AND EXISTS(SELECT 1 FROM run_contexts c WHERE c.request_id=requests.id AND c.token=?)", (request_id, token))

    def consume_call(self, request_id, tool, limit):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            context = self._read_context(db, request_id)
            row = db.execute("SELECT used FROM run_calls WHERE request_id=? AND tool=?", (request_id, tool)).fetchone()
            used = row['used'] if row else 0
            if used >= limit:
                raise PlanError("daily_read_limit", 429)
            db.execute("INSERT INTO run_calls VALUES(?,?,?) ON CONFLICT(request_id,tool) DO UPDATE SET used=excluded.used", (request_id, tool, used + 1))
            return json.loads(context['settings'])

    def finish(self, request_id, inputs, answer, *, recovery_receipt=None):
        answer = normalize_plan(answer)
        if (not isinstance(answer, dict) or set(answer) - {"tasks", "resolved"} or "tasks" not in answer
                or not isinstance(answer["tasks"], list) or len(answer["tasks"]) > 40
                or not isinstance(answer.get("resolved", []), list) or len(answer.get("resolved", [])) > 100):
            raise PlanError("invalid_plan")
        evidence = {row["id"]: row for source in inputs for row in source["records"]}
        tasks, seen = [], set()
        for row in answer["tasks"]:
            if (not isinstance(row, dict) or set(row) - {"source_id", "title", "reason", "deadline", "priority", "task_key"}
                    or not {"source_id", "title", "reason", "deadline", "priority"} <= set(row)
                    or not isinstance(row.get("source_id"), str)
                    or row.get("source_id") not in evidence
                    or not all(isinstance(row.get(k), str) and 1 <= len(row[k]) <= 500 for k in ("title", "reason"))
                    or type(row.get("priority")) is not int or row["priority"] not in {1, 2, 3}):
                raise PlanError("invalid_plan")
            original = evidence[row["source_id"]]
            key = row.get("task_key", row["source_id"])
            if not isinstance(key, str) or not 1 <= len(key) <= 200 or key in seen:
                raise PlanError("invalid_task_key")
            due = row.get("deadline")
            if due is not None:
                import re
                if not isinstance(due, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?", due):
                    raise PlanError("invalid_deadline")
                try:
                    datetime.fromisoformat(due)
                except ValueError:
                    raise PlanError("invalid_deadline") from None
            seen.add(key)
            identity = hashlib.sha256(key.encode()).hexdigest()[:32]
            tasks.append(dict(row, task_key=key, id=identity, source=original["source"], url=original["url"],
                              source_title=original["title"], stale=original.get("retrieved_in_current_run") is not True,
                              source_checked_at=original.get("checked_at", ""), resolved=False, day=inputs[0]["day"]))
        resolutions = []
        for row in answer.get("resolved", []):
            if (not isinstance(row, dict) or set(row) != {"task_key", "source_id", "reason"}
                    or not isinstance(row["task_key"], str) or not 1 <= len(row["task_key"]) <= 200
                    or row["task_key"] in seen or not isinstance(row["source_id"], str)
                    or row["source_id"] not in evidence or not isinstance(row["reason"], str)
                    or not 1 <= len(row["reason"]) <= 500
                    or evidence[row["source_id"]].get("retrieved_in_current_run") is not True):
                raise PlanError("unconfirmed_resolution")
            seen.add(row["task_key"])
            resolutions.append(dict(row, id=hashlib.sha256(row["task_key"].encode()).hexdigest()[:32]))
        current = self.clock().isoformat()
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            context = self._active_context(db, request_id, check_expiry=False,
                                           allowed_states=('failed',) if recovery_receipt is not None else ('running',))
            if recovery_receipt is not None:
                # Host-only recovery of the SAME completed admission. No request/tool
                # lease is reopened and the entire publish remains one transaction.
                receipt = recovery_receipt
                recorded = db.execute('SELECT value FROM state WHERE key=?', ('runtime:' + context['token'],)).fetchone()
                metadata = json.loads(recorded[0]) if recorded else {}
                latest = db.execute('SELECT id FROM requests ORDER BY rowid DESC LIMIT 1').fetchone()
                result = receipt.get('result') if isinstance(receipt, dict) else None
                if (self.lease_tokens.get(request_id) != context['token'] or context['phase'] != 'terminal'
                        or not latest or latest['id'] != request_id
                        or not isinstance(receipt, dict) or receipt.get('status') != 'terminal'
                        or receipt.get('outcome') != 'completed' or not context['admission_id']
                        or receipt.get('admission_id') != context['admission_id']
                        or not isinstance(receipt.get('ref'), dict)
                        or receipt['ref'].get('session_id') != context['session_id']
                        or metadata.get('status') != 'terminal' or metadata.get('request_id') != request_id
                        or metadata.get('outcome') != 'completed' or metadata.get('run_token') != context['token']
                        or type(metadata.get('execution_generation')) is not int
                        or type(receipt.get('execution_generation')) is not int
                        or metadata.get('execution_generation') != receipt.get('execution_generation')
                        or not isinstance(result, dict) or any(result.get(k) for k in ('failed', 'interrupted', 'error'))):
                    raise PlanError('recovery_denied', 409)
                if parse_plan_response(result.get('final_response')) != answer:
                    raise PlanError('recovery_result_changed', 409)
            request = db.execute("SELECT * FROM requests WHERE id=?", (request_id,)).fetchone()
            for old in db.execute("SELECT * FROM tasks").fetchall():
                value = json.loads(old["payload"])
                # Omission from an LLM answer or a narrow search is not completion.
                value["stale"] = True
                db.execute("UPDATE tasks SET payload=? WHERE id=?", (json.dumps(value, ensure_ascii=False), old["id"]))
            for resolution in resolutions:
                old = db.execute("SELECT payload FROM tasks WHERE id=?", (resolution["id"],)).fetchone()
                if old is None:
                    raise PlanError("resolution_task_missing")
                value = json.loads(old["payload"])
                original = evidence[resolution["source_id"]]
                if original.get('retrieval_run_token', context['token']) != context['token']:
                    raise PlanError('unconfirmed_resolution')
                event = dict(task_key=resolution["task_key"], task_id=resolution["id"], run_id=request_id,
                             run_token=context['token'], evidence_run_token=original.get('retrieval_run_token', context['token']),
                             resolved_at=current, reason=resolution["reason"], task=dict(value),
                             evidence=dict(original), evidence_run=request_id,
                             evidence_checked_at=original.get("checked_at", ""))
                db.execute("INSERT INTO resolution_events(task_id,source,payload) VALUES(?,?,?)",
                           (resolution["id"], value["source"], json.dumps(event, ensure_ascii=False)))
                value.update(resolved=True, resolution=resolution, resolved_at=current)
                db.execute("UPDATE tasks SET payload=? WHERE id=?", (json.dumps(value, ensure_ascii=False), resolution["id"]))
            for task in tasks:
                original = evidence[task['source_id']]
                if original.get('retrieval_run_token', context['token']) != context['token']:
                    task['stale'] = True
                db.execute("INSERT INTO tasks(id,payload) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
                           (task["id"], json.dumps(task, ensure_ascii=False)))
            coverage = [{k: source[k] for k in ("id", "label", "state", "checked_at")} for source in inputs]
            for key, value in {"coverage": coverage, "checked_at": current, "day": request["day"]}.items():
                db.execute("INSERT OR REPLACE INTO state VALUES(?,?)", (key, json.dumps(value)))
            db.execute("UPDATE requests SET state='complete' WHERE id=?", (request_id,))

    def fail(self, request_id):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            token = self.lease_tokens.get(request_id)
            db.execute("UPDATE requests SET state='failed' WHERE id=? AND state='running' AND EXISTS(SELECT 1 FROM run_contexts c WHERE c.request_id=requests.id AND c.token=?)", (request_id, token))

    def require_run(self, request_id):
        with self.db() as db:
            self._read_context(db, request_id)

    def cache_source(self, request_id, source, records, state):
        self.require_run(request_id)
        checked = self.clock().isoformat()
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            context = self._read_context(db, request_id)
            for row in records:
                db.execute("INSERT OR REPLACE INTO source_cache(id,source,payload,checked_at,last_run,run_token) VALUES(?,?,?,?,?,?)",
                           (row["id"], source, json.dumps(row, ensure_ascii=False), checked, request_id, context['token']))
            previous = db.execute("SELECT state FROM source_checks WHERE request_id=? AND source=?", (request_id, source)).fetchone()
            if previous and (previous[0] == "partial" or (previous[0] != state and ({"updated", "checked"} & {previous[0], state}))):
                state = "partial"
            db.execute("INSERT OR REPLACE INTO source_checks VALUES(?,?,?,?)", (request_id, source, state, checked))

    def cached_inputs(self, request_id):
        with self.db() as db:
            db.execute('BEGIN')
            context = db.execute("SELECT token FROM run_contexts WHERE request_id=?", (request_id,)).fetchone()
            token = context['token'] if context else None
            if request_id in self.lease_tokens and self.lease_tokens[request_id] != token:
                raise PlanError('request_changed', 409)
            rows = db.execute("SELECT * FROM source_cache ORDER BY checked_at DESC").fetchall()
            checks = {r["source"]: dict(r) for r in db.execute("SELECT * FROM source_checks WHERE request_id=?", (request_id,))}
            settings_row = db.execute("SELECT value FROM state WHERE key='settings'").fetchone()
            settings = json.loads(settings_row[0]) if settings_row else {}
        output = []
        for source, label in SOURCES.items():
            records = [dict(json.loads(r["payload"]), checked_at=r["checked_at"], retrieval_run_token=r['run_token'],
                            retrieved_in_current_run=bool(token) and r['run_token'] == token)
                       for r in rows if r["source"] == source]
            check = checks.get(source, {})
            configured = settings.get("email" if source == "gmail" else "manaba_url")
            default = "not_configured" if not configured else "cached" if records else "not_checked"
            output.append(dict(id=source, label=label, records=records, state=check.get("state", default),
                               checked_at=check.get("checked_at", ""), day=self.clock().date().isoformat()))
        return output

    def cache_index(self, request_id=None):
        with self.db() as db:
            db.execute('BEGIN')
            if request_id:
                self._read_context(db, request_id)
            rows = db.execute("SELECT * FROM source_cache ORDER BY checked_at DESC LIMIT 200").fetchall()
        return [dict(id=r["id"], source=r["source"], title=json.loads(r["payload"])["title"], checked_at=r["checked_at"])
                for r in rows]

    def cached_records(self, identities, request_id=None):
        if not isinstance(identities, list) or not 1 <= len(identities) <= 20 or not all(isinstance(i, str) for i in identities):
            raise PlanError("invalid_cache_request")
        with self.db() as db:
            db.execute('BEGIN')
            if request_id:
                context = self._read_context(db, request_id)
            return [dict(json.loads(r["payload"]), checked_at=r["checked_at"], retrieved_in_current_run=bool(request_id) and r['run_token'] == context['token'])
                    for identity in identities if (r := db.execute("SELECT payload,checked_at,run_token FROM source_cache WHERE id=?", (identity,)).fetchone())]

    def complete(self, identity, done):
        if type(done) is not bool:
            raise PlanError("invalid_request")
        with self.db() as db:
            if not db.execute("UPDATE tasks SET done=? WHERE id=?", (int(done), identity)).rowcount:
                raise PlanError("task_missing", 404)

    def resolution_history(self):
        # Host-side audit only: evidence bodies are not added to the mobile JSON.
        with self.db() as db:
            return [dict(json.loads(r["payload"]), event_id=r["id"])
                    for r in db.execute("SELECT id,payload FROM resolution_events ORDER BY id")]

    def snapshot(self, request_id=None):
        current = self.clock()
        with self.db() as db:
            db.execute('BEGIN')
            if request_id:
                self._read_context(db, request_id)
            values = {r["key"]: json.loads(r["value"]) for r in db.execute("SELECT * FROM state")}
            tasks = [dict(json.loads(r["payload"]), done=bool(r["done"])) for r in db.execute("SELECT * FROM tasks")]
            latest = db.execute("SELECT state,started FROM requests ORDER BY rowid DESC LIMIT 1").fetchone()
        state = latest["state"] if latest else "idle"
        if state == "running" and current - datetime.fromisoformat(latest["started"]) > timedelta(minutes=15):
            state = "interrupted"
        day = current.date().isoformat()
        for task in tasks:
            try:
                checked = datetime.fromisoformat(task.get("source_checked_at", ""))
                if checked.tzinfo is None:
                    checked = checked.replace(tzinfo=JST)
                task["stale"] = bool(task.get("stale", True)) or checked.astimezone(JST).date().isoformat() != day
            except (TypeError, ValueError):
                task["stale"] = True
        tasks, resolved = [t for t in tasks if not t.get("resolved")], [t for t in tasks if t.get("resolved")]
        same_day = values.get("day") == day and state == "complete"
        coverage = values.get("coverage", [{"id": k, "label": v, "state": "not_checked", "checked_at": ""} for k, v in SOURCES.items()])
        fresh = (same_day and all(not t.get("stale", True) for t in tasks if not t["done"])
                 and any(c["state"] in {"updated", "checked"} for c in coverage)
                 and all(c["state"] in {"updated", "checked", "not_configured"} for c in coverage))
        if not same_day:
            coverage = [dict(c, state="stale" if c["state"] in {"updated", "checked", "partial"} else c["state"]) for c in coverage]
        return dict(day=day, plan_day=values.get("day", ""), checked_at=values.get("checked_at", ""),
                    state=state, fresh=fresh, tasks=sorted(tasks, key=lambda t: (t["done"], t["priority"], t["deadline"] or "9999", t["id"])),
                    coverage=coverage, resolved_tasks=resolved)
