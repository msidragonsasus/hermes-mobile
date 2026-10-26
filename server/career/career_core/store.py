"""Transactional, versioned local store. Never contains authentication material."""
import contextlib
import hashlib
import json
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

KINDS = {"companies", "company_links", "company_aliases", "company_statuses", "company_mail_senders", "offers", "applications", "tasks", "deadlines", "events",
         "profile", "facts", "stories", "hypotheses", "documents", "decisions", "sources", "proposals"}
SHARED = {"profile", "facts", "stories", "hypotheses", "documents", "decisions", "sources"}


def now():
    return datetime.now(timezone.utc).isoformat()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


class CareerError(Exception):
    def __init__(self, code, detail, status=400):
        self.code, self.detail, self.status = code, detail, status
        super().__init__(code)


class ClosingConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


class Store:
    def __init__(self, directory=None):
        self.directory = Path(directory or os.environ.get("CAREER_DATA_DIR", "/var/lib/hermes/career"))
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.directory / "career.sqlite3"
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS entities(
                    kind TEXT NOT NULL, id TEXT NOT NULL, version INTEGER NOT NULL,
                    data TEXT NOT NULL, updated_at TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(kind,id));
                CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS imports(source_key TEXT PRIMARY KEY, version_id TEXT NOT NULL,
                    local_version INTEGER NOT NULL, data TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS notices(id TEXT PRIMARY KEY, type TEXT NOT NULL, data TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, kind TEXT NOT NULL, entity_id TEXT NOT NULL,
                    base TEXT NOT NULL, local TEXT NOT NULL, remote TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS document_files(path TEXT PRIMARY KEY, body TEXT NOT NULL,
                    checksum TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0);
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=30, factory=ClosingConnection)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=30000")
        db.execute("PRAGMA foreign_keys=ON")
        return db

    @contextlib.contextmanager
    def transaction(self):
        db = self.connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def entity(db, kind, entity_id):
        row = db.execute("SELECT * FROM entities WHERE kind=? AND id=?", (kind, entity_id)).fetchone()
        return dict(row, data=json.loads(row["data"])) if row else None

    @staticmethod
    def meta(db, key, default=None):
        row = db.execute("SELECT value FROM metadata WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    @staticmethod
    def set_meta(db, key, value):
        db.execute("INSERT OR REPLACE INTO metadata VALUES(?,?)", (key, encoded(value)))

    def snapshot(self):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM entities ORDER BY kind,id").fetchall()
            entities = [dict(r, data=json.loads(r["data"])) for r in rows]
            from .company_lifecycle import active_records
            entities = active_records(entities)
            for entity in entities:
                if entity["kind"] == "documents":
                    path = entity["data"].get("path") or entity["data"].get("file_path")
                    body = db.execute("SELECT body FROM document_files WHERE path=?", (path,)).fetchone()
                    if body:
                        entity["data"]["body"] = body[0]
                    entity["data"]["history_paths"] = [r[0] for r in db.execute(
                        "SELECT path FROM document_files WHERE path LIKE ? ORDER BY path DESC", (f".jobhunt/documents/{entity['id']}-v%",))]
            return {"api_version": 1, "entities": entities,
                    "notices": [dict(r, data=json.loads(r["data"])) for r in db.execute(
                        "SELECT * FROM notices ORDER BY created_at DESC LIMIT 100")],
                    "conflicts": [dict(r, **{k: json.loads(r[k]) for k in ("base", "local", "remote")})
                                  for r in db.execute("SELECT * FROM conflicts ORDER BY created_at")] +
                                 [dict(item, expected_version=self.meta(db, "jobhunt_context_versions", {}).get(item["id"], {}).get("version", 0))
                                  for item in self.meta(db, "jobhunt_context_conflicts", [])],
                    "sync": self.sharing_status(db),
                    "research": self.meta(db, "research_status", {"state": "not_started"})}

    def sharing_status(self, db):
        """A cached successful sync cannot cover changes staged afterward."""
        status = self.meta(db, "sync_status", {"state": "not_configured"})
        if status.get("state") == "synced":
            placeholders = ",".join("?" for _ in SHARED)
            pending = db.execute("SELECT 1 FROM entities WHERE dirty=1 AND kind IN (" +
                                 placeholders + ") LIMIT 1", tuple(SHARED)).fetchone()
            pending = pending or db.execute("SELECT 1 FROM document_files WHERE synced=0 LIMIT 1").fetchone()
            pending = pending or self.meta(db, "jobhunt_context_pending", {}) or self.meta(db, "coach_checkpoint_pending", {})
            if pending:
                return dict(status, state="unsynced", reason="local_changes_pending")
        return status

    def operation(self, operation_id, request, action):
        if not isinstance(operation_id, str) or not 8 <= len(operation_id) <= 128:
            raise CareerError("invalid_operation_id", "操作IDが必要です。")
        fingerprint = hashlib.sha256(encoded(request).encode()).hexdigest()
        with self.transaction() as db:
            existing = db.execute("SELECT * FROM operations WHERE id=?", (operation_id,)).fetchone()
            if existing:
                if existing["fingerprint"] != fingerprint:
                    raise CareerError("operation_reused", "同じ操作IDで内容が変更されています。", 409)
                return json.loads(existing["result"])
            result = action(db)
            db.execute("INSERT INTO operations VALUES(?,?,?)", (operation_id, fingerprint, encoded(result)))
            return result

    def put(self, db, kind, entity_id, expected_version, data, *, dirty=None):
        if kind not in KINDS or not isinstance(entity_id, str) or not 1 <= len(entity_id) <= 200:
            raise CareerError("invalid_entity", "対象が不正です。")
        if not isinstance(data, dict) or len(encoded(data)) > 262144:
            raise CareerError("invalid_data", "保存内容が不正または大きすぎます。")
        old = self.entity(db, kind, entity_id)
        version = old["version"] if old else 0
        if type(expected_version) is not int or expected_version != version:
            raise CareerError("version_conflict", {"kind": kind, "id": entity_id, "current": old}, 409)
        if kind == "documents" and old and old["data"].get("status") == "submitted" and data != old["data"]:
            raise CareerError("submitted_immutable", "提出済み文書は新しい版を作成してください。", 409)
        if kind in {"applications", "offers", "tasks", "deadlines", "events"}:
            company_id = data.get("company_id")
            if company_id and not self.entity(db, "companies", company_id):
                raise CareerError("missing_company", "参照先の企業がありません。", 409)
        timestamp = now()
        dirty = kind in SHARED if dirty is None else dirty
        db.execute("INSERT OR REPLACE INTO entities VALUES(?,?,?,?,?,?)",
                   (kind, entity_id, version + 1, encoded(data), timestamp, int(dirty)))
        return self.entity(db, kind, entity_id)

    def save(self, request):
        def apply(db):
            from .shared import save_shared
            if request["kind"] in SHARED:
                return save_shared(self, db, request)
            if request["kind"] in {"proposals", "offers"}:
                raise CareerError("dedicated_action_required", "この項目は専用の操作から更新してください。")
            data = dict(request["data"])
            if request["kind"] == "companies":
                name_field = "name" if "name" in data else "title"
                name = data.get(name_field)
                if not isinstance(name, str) or not name.strip() or len(name.strip()) > 200:
                    raise CareerError("invalid_company_name", "企業名を1〜200文字で入力してください。")
                data[name_field] = name.strip()
            if request["kind"] == "company_links":
                from urllib.parse import urlsplit
                company_id = data.get("company_id")
                company = self.entity(db, "companies", company_id) if isinstance(company_id, str) else None
                if not company or company["data"].get("deleted_at"):
                    raise CareerError("missing_company", "参照先の企業がありません。", 409)
                url = data.get("url", "")
                try:
                    parsed = urlsplit(url) if isinstance(url, str) else None
                    valid = parsed and parsed.scheme == "https" and parsed.hostname and not parsed.username and not parsed.password
                except ValueError:
                    valid = False
                if not valid or any(ch.isspace() for ch in url):
                    raise CareerError("invalid_company_link", "HTTPSのマイページURLを入力してください。")
                previous = self.entity(db, "company_links", request["id"])
                if previous and previous["data"].get("company_id") != company_id:
                    raise CareerError("invalid_company_link", "リンクの企業は変更できません。", 409)
            old = self.entity(db, request["kind"], request["id"])
            if old and data.get("deadline") != old["data"].get("deadline"):
                data["deadline_source"] = "manual"
            return self.put(db, request["kind"], request["id"], request["expected_version"], data)
        return self.operation(request["operation_id"], request, apply)

    @staticmethod
    def notice(db, notice_type, data, notice_id=None):
        db.execute("INSERT OR IGNORE INTO notices VALUES(?,?,?,?)",
                   (notice_id or str(uuid.uuid4()), notice_type, encoded(data), now()))

    def decide(self, request):
        def apply(db):
            offer = self.entity(db, "offers", request["id"])
            if not offer:
                raise CareerError("not_found", "募集が見つかりません。", 404)
            decision = request["decision"]
            if decision not in {"apply", "dismiss"}:
                raise CareerError("invalid_decision", "応募するか見送るかを選んでください。")
            data = dict(offer["data"], decision=decision)
            updated = self.put(db, "offers", offer["id"], request["expected_version"], data)
            app_id = "offer:" + offer["id"]
            if decision == "apply" and not self.entity(db, "applications", app_id):
                self.put(db, "applications", app_id, 0, {
                    "company_id": data["company_id"], "offer_id": offer["id"], "title": data.get("title", ""),
                    "status": "planned", "deadline": data.get("deadline"), "target_year": data.get("target_year"), "deadline_source": "offer"})
                if data.get("deadline"):
                    self.put(db, "tasks", f"{app_id}:deadline", 0, {
                        "application_id": app_id, "company_id": data["company_id"],
                        "title": "募集締切までの応募手続き", "deadline": data["deadline"], "status": "todo", "deadline_source": "offer"})
                for i, requirement in enumerate(data.get("requirements", [])):
                    self.put(db, "tasks", f"{app_id}:{i}", 0, {
                        "application_id": app_id, "company_id": data["company_id"],
                        "title": requirement["title"], "deadline": requirement.get("deadline"), "status": "todo"})
            return updated
        return self.operation(request["operation_id"], request, apply)

    def propose(self, request):
        # Agent tools can propose, never approve their own write.
        if request.get("kind") not in KINDS - {"proposals", "offers", "sources", "decisions"}:
            raise CareerError("invalid_proposal", "この対象への保存案は作れません。")
        if not isinstance(request.get("data"), dict) or type(request.get("expected_version")) is not int:
            raise CareerError("invalid_proposal", "版番号と保存内容が必要です。")
        def apply(db):
            current = self.entity(db, request["kind"], request["id"])
            if (current["version"] if current else 0) != request["expected_version"]:
                raise CareerError("version_conflict", "最新の対象を読み直してください。", 409)
            proposal = dict(request, status="pending", created_at=now())
            return self.put(db, "proposals", str(uuid.uuid4()), 0, proposal, dirty=False)
        return self.operation(request["operation_id"], request, apply)

    def approve(self, request):
        if type(request.get("approve")) is not bool:
            raise CareerError("invalid_approval", "反映するかどうかの明示的な選択が必要です。")
        def apply(db):
            proposal = self.entity(db, "proposals", request["id"])
            if not proposal or proposal["data"].get("status") != "pending":
                raise CareerError("proposal_expired", "この保存案は処理済みです。", 409)
            target = proposal["data"]
            result = None
            if request["approve"]:
                if target["kind"] in SHARED:
                    from .shared import save_shared, correct_fact, save_document
                    change = dict(target, operation_id=request["operation_id"], confirmed_by_user=request.get("confirmed_by_user") is True)
                    if target["kind"] == "facts" and target["expected_version"] > 0:
                        if not change["confirmed_by_user"]:
                            raise CareerError("fact_confirmation_required", "既存の本人事実の訂正には、本人による内容確認が必要です。", 409)
                        result = correct_fact(self, change, transaction=db)
                    elif target["kind"] == "documents":
                        result = save_document(self, change, transaction=db)
                    else:
                        result = save_shared(self, db, change, from_proposal=True)
                else:
                    data = dict(target["data"])
                    old = self.entity(db, target["kind"], target["id"])
                    if old and data.get("deadline") != old["data"].get("deadline"):
                        data["deadline_source"] = "manual"
                    result = self.put(db, target["kind"], target["id"], target["expected_version"], data)
            self.put(db, "proposals", proposal["id"], request["expected_version"],
                     dict(target, status="accepted" if request["approve"] else "rejected"), dirty=False)
            return {"result": result}
        return self.operation(request["operation_id"], request, apply)
