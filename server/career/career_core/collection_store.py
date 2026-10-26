"""Private source versions and collector grants. None belong to SHARED/GitHub."""
import hashlib
import json
import re
import unicodedata
from datetime import datetime
from urllib.parse import urlsplit, urlunsplit

from .store import CareerError, encoded, now

PILOT = "nipponsteel-eng-28"
PILOT_NAME = "日鉄エンジニアリング"
PILOT_ROOT = "https://axol.jp/zw/s/nipponsteel-eng_28/mypage/"
PILOT_OFFER_KEY = "winter-3days"


def digest(*values):
    return hashlib.sha256(encoded(values).encode("utf-8")).hexdigest()[:32]


def clean_excerpt(text, maximum=12000):
    # Credential guidance, candidate login IDs and tokenized links never enter evidence.
    lines = []
    for line in str(text).splitlines():
        normalized = unicodedata.normalize("NFKC", line)
        if re.search(r"パスワード|password|個人\s*ID|あなたの\s*ID|ログイン\s*ID|認証コード|ワンタイム|OTP|access_token|refresh_token|Bearer\s", normalized, re.I):
            continue
        line = re.sub(r"https?://[^\s<>\"）]+", "[元資料のリンク]", line)
        lines.append(line)
    return "\n".join(lines).strip()[:maximum]


def public_locator(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.username or parts.password:
        raise CareerError("invalid_source", "資料の参照先を確認してください。")
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


class CollectionStore:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS career_collection_grants(
                    id TEXT PRIMARY KEY, company_id TEXT NOT NULL, account_id TEXT,
                    portal_id TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0,
                    profile TEXT NOT NULL DEFAULT 'default', created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS career_evidence(
                    id TEXT PRIMARY KEY, source_key TEXT NOT NULL, company_id TEXT NOT NULL,
                    offer_id TEXT, kind TEXT NOT NULL, title TEXT NOT NULL, locator TEXT NOT NULL,
                    excerpt TEXT NOT NULL, content_hash TEXT NOT NULL, occurred_at TEXT,
                    observed_at TEXT NOT NULL, first_observed_at TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS career_evidence_company ON career_evidence(company_id,observed_at);
                CREATE INDEX IF NOT EXISTS career_evidence_source ON career_evidence(source_key);
                CREATE TABLE IF NOT EXISTS career_notice_feed(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                    company_id TEXT NOT NULL, offer_id TEXT, kind TEXT NOT NULL,
                    title TEXT NOT NULL, created_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS career_notice_details(
                    id TEXT PRIMARY KEY, data TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS career_document_context(
                    document_id TEXT NOT NULL, version INTEGER NOT NULL,
                    company_id TEXT NOT NULL, offer_id TEXT, question_refs TEXT NOT NULL,
                    PRIMARY KEY(document_id,version));
            """)

    def configure_pilot(self, account_id, profile="default", enabled=False):
        if account_id is not None and (not isinstance(account_id, str) or not re.fullmatch(r"gmail-[a-f0-9]{32}", account_id)):
            raise CareerError("invalid_account", "接続するGmailを選んでください。")
        from .coach_store import profile_key
        profile = profile_key(profile)
        with self.store.transaction() as db:
            old = db.execute("SELECT * FROM career_collection_grants WHERE id=?", (PILOT,)).fetchone()
            companies = [self.store.entity(db, "companies", r[0]) for r in db.execute("SELECT id FROM entities WHERE kind='companies'")]
            matched = [c for c in companies if self.company_name(c["data"].get("name", c["data"].get("title", ""))) == PILOT_NAME]
            if len(matched) > 1 and not old:
                raise CareerError("company_ambiguous", "同じ企業の記録が複数あります。紐付け先を確認してください。", 409)
            company_id = old["company_id"] if old else (matched[0]["id"] if matched else "company:" + PILOT)
            if not old and not matched:
                self.store.put(db, "companies", company_id, 0, {"name": PILOT_NAME, "status": "unknown"}, dirty=False)
            if old and old["account_id"] != account_id:
                self.store.set_meta(db, f"collection:{PILOT}:mail", {"state": "not_started"})
            db.execute("INSERT INTO career_collection_grants VALUES(?,?,?,?,?,?,?) "
                       "ON CONFLICT(id) DO UPDATE SET account_id=excluded.account_id,enabled=excluded.enabled,profile=excluded.profile",
                       (PILOT, company_id, account_id, PILOT, int(enabled), profile, old["created_at"] if old else now()))
            return dict(db.execute("SELECT * FROM career_collection_grants WHERE id=?", (PILOT,)).fetchone())

    @staticmethod
    def company_name(name):
        return unicodedata.normalize("NFKC", name).replace("株式会社", "").replace(" ", "").replace("　", "").strip()

    def grants(self):
        with self.store.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM career_collection_grants ORDER BY created_at")]

    def grant(self, grant_id, *, require_enabled=True):
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM career_collection_grants WHERE id=?", (grant_id,)).fetchone()
        if not row or (require_enabled and not row["enabled"]):
            raise CareerError("collection_not_enabled", "この企業の情報取得を接続設定で有効にしてください。", 409)
        return dict(row)

    def status(self, grant_id, channel, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.status(grant_id, channel, connection)
        return self.store.meta(db, f"collection:{grant_id}:{channel}", {"state": "not_started"})

    def update_status(self, grant_id, channel, state, *, cursor=None, reason=None, success=False, db=None):
        if db is None:
            with self.store.transaction() as connection:
                return self.update_status(grant_id, channel, state, cursor=cursor, reason=reason, success=success, db=connection)
        old = self.status(grant_id, channel, db)
        result = dict(old, state=state, last_attempt_at=now(), reason=reason)
        if success:
            result["last_success_at"] = now()
            result["consecutive_failures"] = 0
        elif state in {"failed", "auth_required"}:
            result["consecutive_failures"] = old.get("consecutive_failures", 0) + 1
        if cursor is not None:
            result["cursor"] = cursor
        self.store.set_meta(db, f"collection:{grant_id}:{channel}", result)
        return result

    def save_evidence(self, db, *, source_key, company_id, kind, title, locator, text, occurred_at=None, offer_id=None):
        excerpt = clean_excerpt(text)
        content_hash = digest(excerpt)
        evidence_id = "evidence:" + digest(source_key, content_hash)
        old = db.execute("SELECT id,company_id FROM career_evidence WHERE id=?", (evidence_id,)).fetchone()
        if old and old["company_id"] != company_id:
            # Shared mail senders may be displayed in several company views.
            # Keep existing evidence IDs, but never reuse another company's record.
            evidence_id = "evidence:" + digest(source_key, company_id, content_hash)
            old = db.execute("SELECT id FROM career_evidence WHERE id=?", (evidence_id,)).fetchone()
        timestamp = now()
        if old:
            db.execute("UPDATE career_evidence SET observed_at=? WHERE id=?", (timestamp, evidence_id))
            return evidence_id, False
        db.execute("INSERT INTO career_evidence VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                   (evidence_id, source_key, company_id, offer_id, kind, clean_excerpt(title, 300), locator,
                    excerpt, content_hash, occurred_at, timestamp, timestamp))
        return evidence_id, True

    def notice(self, db, identity, company_id, offer_id, kind, title):
        db.execute("INSERT OR IGNORE INTO career_notice_feed(id,company_id,offer_id,kind,title,created_at) VALUES(?,?,?,?,?,?)",
                   (identity, company_id, offer_id, kind, clean_excerpt(title, 300), now()))
        from .notice_policy import classify
        from .mypage import deadlines
        evidence_id = identity.removeprefix("source:")
        evidence = db.execute("SELECT * FROM career_evidence WHERE id=? AND company_id=?", (evidence_id, company_id)).fetchone()
        if evidence is None:
            return
        evidence = dict(evidence)
        classification = classify(evidence["title"], evidence["excerpt"])
        eligible = classification is not None and kind != "initial_source"
        source = evidence["source_key"]
        if source.startswith("gmail:"):
            account = source.split(":", 2)[1]
            key = "notice-mail-start:" + digest(company_id, account)
            start = self.store.meta(db, key, None)
            if start is None:
                start = now()
                self.store.set_meta(db, key, start)
            try:
                eligible = eligible and datetime.fromisoformat(evidence["occurred_at"]) > datetime.fromisoformat(start)
            except (TypeError, ValueError):
                eligible = False
        lines = [line.strip() for line in re.split(r"[\n。！？]", evidence["excerpt"]) if line.strip()]
        quote = next((line for line in lines if classify("", line)), evidence["title"])
        dates = deadlines(evidence["title"] + "\n" + evidence["excerpt"])
        company = self.store.entity(db, "companies", company_id)
        data = dict(eligible=bool(eligible), company_name=clean_excerpt(company["data"].get("name", company["data"].get("title", "")), 100) if company else "",
                    category=classification[0] if classification else None, label=classification[1] if classification else None,
                    quote=clean_excerpt(quote, 240), deadline_text="\n".join(d["original_text"] for d in dates)[:300],
                    source_ref=dict(kind=evidence["kind"], id=evidence_id, key=digest(source, company_id)), source_key=source)
        db.execute("INSERT OR IGNORE INTO career_notice_details VALUES(?,?)", (identity, encoded(data)))

    def begin_mail_notices(self, db, company_id, account):
        key = "notice-mail-start:" + digest(company_id, account)
        if self.store.meta(db, key, None) is None:
            self.store.set_meta(db, key, now())

    def attention(self, db, connection, company_id, active, *, profile=None, mail=False):
        """Notify once per unresolved authentication episode, including after recovery."""
        key = "notice-auth:" + connection
        old = self.store.meta(db, key, {"active": False, "episode": 0})
        if active and not old["active"]:
            old["episode"] += 1
            identity = "auth:" + digest(connection, old["episode"])
            self.notice(db, identity, company_id, None, "auth_required", "メールの再接続が必要です" if mail else "マイページの再ログインが必要です")
            company = self.store.entity(db, "companies", company_id)
            data = dict(eligible=True, company_name=clean_excerpt(company["data"].get("name", company["data"].get("title", "")), 100) if company else "",
                        category="auth", label="再接続" if mail else "再ログイン", quote="接続設定を開いて認証してください。",
                        deadline_text="", source_ref=dict(kind="mail_settings" if mail else "settings", id=connection, key=digest(connection, company_id)), profile=profile,
                        attention_key=key, episode=old["episode"])
            db.execute("INSERT OR IGNORE INTO career_notice_details VALUES(?,?)", (identity, encoded(data)))
        elif not active and old["active"]:
            identity = "auth-resolved:" + digest(connection, old["episode"])
            self.notice(db, identity, company_id, None, "auth_resolved", "接続が復旧しました")
            data = dict(eligible=False, source_ref=dict(kind="mail_settings" if mail else "settings", id=connection, key=digest(connection, company_id)), profile=profile)
            db.execute("INSERT OR IGNORE INTO career_notice_details VALUES(?,?)", (identity, encoded(data)))
        old["active"] = active
        self.store.set_meta(db, key, old)

    def notice_data(self, db, row, profile):
        from .company_views import CompanyViews
        evidence_id = row["id"].removeprefix("source:")
        evidence = db.execute("SELECT * FROM career_evidence WHERE id=? AND company_id=?", (evidence_id, row["company_id"])).fetchone()
        details = db.execute("SELECT data FROM career_notice_details WHERE id=?", (row["id"],)).fetchone()
        data = json.loads(details[0]) if details else {"eligible": False}
        if profile is not None:
            if data.get("profile") is not None and data["profile"] != profile:
                return None
            if evidence and not CompanyViews.visible_source(dict(evidence), row["company_id"], profile, db):
                return None
            if row["id"].startswith("mypage-health:"):
                from .mypage_bot import connection_id
                if not row["id"].startswith("mypage-health:" + connection_id(row["company_id"], profile) + ":"):
                    return None
        if data.get("attention_key"):
            state = self.store.meta(db, data["attention_key"], {})
            data["eligible"] = state.get("active", False) and state.get("episode") == data["episode"]
        company = self.store.entity(db, "companies", row["company_id"])
        if not company or company["data"].get("deleted_at") or company["data"].get("user_stage") == "closed":
            data["eligible"] = False
        return dict(row, **{k: v for k, v in data.items() if k not in {"source_key", "attention_key", "episode", "profile"}})

    def notices(self, after=0, profile=None, baseline=False):
        if type(after) is not int or after < 0:
            raise CareerError("invalid_cursor", "通知の取得位置を確認してください。")
        if profile is not None:
            from .coach_store import profile_key
            profile = profile_key(profile)
        with self.store.connect() as db:
            if baseline:
                maximum = db.execute("SELECT COALESCE(MAX(seq),0) FROM career_notice_feed").fetchone()[0]
                return {"notices": [], "cursor": maximum, "more": False}
            rows = [dict(r) for r in db.execute("SELECT * FROM career_notice_feed WHERE seq>? ORDER BY seq LIMIT 100", (after,))]
            visible = [value for row in rows if (value := self.notice_data(db, row, profile)) is not None]
        return {"notices": visible, "cursor": rows[-1]["seq"] if rows else after, "more": len(rows) == 100}

    def notice_content(self, identity, profile):
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM career_notice_feed WHERE id=?", (identity,)).fetchone()
            value = self.notice_data(db, dict(row), profile) if row else None
            if value is None or not value.get("source_ref") or value["source_ref"]["kind"] in {"settings", "mail_settings"}:
                raise CareerError("notice_missing", "この案内を確認できません。企業の案内一覧を開いてください。", 404)
            evidence = db.execute("SELECT excerpt FROM career_evidence WHERE id=? AND company_id=?", (value["source_ref"]["id"], value["company_id"])).fetchone()
            if evidence is None:
                raise CareerError("notice_missing", "元の資料を確認できません。", 404)
            return dict(title=value["title"], body=evidence[0], company_id=value["company_id"])

    def evidence(self, company_id, offer_id=None):
        with self.store.connect() as db:
            return [dict(r) for r in db.execute(
                "SELECT * FROM career_evidence WHERE company_id=? AND (? IS NULL OR offer_id=? OR offer_id IS NULL) "
                "ORDER BY first_observed_at DESC LIMIT 100", (company_id, offer_id, offer_id))]

    def mark_seen(self, company_id, profile, cursor):
        from .coach_store import profile_key
        profile = profile_key(profile)
        if type(cursor) is not int or cursor < 0:
            raise CareerError("invalid_cursor", "新着の確認位置が不正です。")
        with self.store.transaction() as db:
            maximum = db.execute("SELECT COALESCE(MAX(seq),0) FROM career_notice_feed WHERE company_id=?", (company_id,)).fetchone()[0]
            key = f"seen:{profile}:{company_id}"
            self.store.set_meta(db, key, max(self.store.meta(db, key, 0), min(cursor, maximum)))
        return {"seen": True}


def document_context(db, document_id, version):
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='career_document_context' AND type='table'").fetchone():
        return {}
    row = db.execute("SELECT * FROM career_document_context WHERE document_id=? AND version=?", (document_id, version)).fetchone()
    return dict(row, question_refs=json.loads(row["question_refs"])) if row else {}


def bind_document(db, document_id, version, scope, questions):
    db.execute("INSERT INTO career_document_context VALUES(?,?,?,?,?)",
               (document_id, version, scope["company_id"], scope.get("offer_id"),
                encoded([{k: q[k] for k in ("id", "version", "evidence_id", "text", "max_characters", "input_kind", "required", "required_known", "no_line_breaks", "options") if k in q} for q in questions])))
