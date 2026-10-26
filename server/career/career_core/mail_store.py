"""Private, program-owned Gmail settings, sender rules and message bodies."""
import re
import base64
import json
from email.utils import parseaddr

from .collection_store import clean_excerpt, digest
from .store import CareerError, now


def normalize_address(value):
    if not isinstance(value, str) or len(value) > 320:
        raise CareerError("invalid_mail_sender", "送信元メールアドレスを確認してください。")
    address = parseaddr(value.strip())[1].lower()
    if address != value.strip().lower() or not re.fullmatch(r"[^\s@]{1,64}@[^\s@]{1,253}", address):
        raise CareerError("invalid_mail_sender", "送信元メールアドレスを確認してください。")
    return address


def connection_view(store):
    """Credential-free connection view shared by the App and coach tools."""
    from .workspace_reader import connected_accounts_details
    mail = MailStore(store)
    result = {"accounts": [], "account_details": [], "independent_channels": True,
              "mail": mail.settings(), "mail_senders": mail.sender_rules()}
    try:
        accounts = connected_accounts_details()
        ready = [account for account in accounts if account.get("state", "connected") == "connected"]
        result.update(accounts=[account["id"] for account in ready], account_details=accounts,
                      mail_status="ready" if ready else "authentication_required")
    except CareerError as error:
        result["mail_status"] = error.code
    return result


class MailStore:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            had_links = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='career_mail_company_messages'").fetchone()
            db.executescript("""
                CREATE TABLE IF NOT EXISTS career_mail_messages(
                    account_id TEXT NOT NULL, message_id TEXT NOT NULL, thread_id TEXT NOT NULL,
                    company_id TEXT NOT NULL, sender_address TEXT NOT NULL, from_text TEXT NOT NULL,
                    subject TEXT NOT NULL, snippet TEXT NOT NULL, body TEXT NOT NULL,
                    locator TEXT NOT NULL, received_at TEXT NOT NULL, gmail_unread INTEGER NOT NULL DEFAULT 0,
                    first_observed_at TEXT NOT NULL, observed_at TEXT NOT NULL,
                    PRIMARY KEY(account_id,message_id));
                CREATE INDEX IF NOT EXISTS career_mail_company
                    ON career_mail_messages(company_id,received_at DESC);
                CREATE TABLE IF NOT EXISTS career_mail_company_messages(
                    account_id TEXT NOT NULL, message_id TEXT NOT NULL, company_id TEXT NOT NULL,
                    PRIMARY KEY(account_id,message_id,company_id));
            """)
            if not had_links or not self.store.meta(db, "mail:links_migrated", False):
                db.execute("""INSERT OR IGNORE INTO career_mail_company_messages(account_id,message_id,company_id)
                    SELECT account_id,message_id,company_id FROM career_mail_messages""")
                self.store.set_meta(db, "mail:links_migrated", True)
            db.execute("""CREATE TABLE IF NOT EXISTS career_mail_sequence(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL,
                message_id TEXT NOT NULL, company_id TEXT NOT NULL,
                UNIQUE(account_id,message_id,company_id))""")
            db.execute("CREATE INDEX IF NOT EXISTS career_mail_sequence_company ON career_mail_sequence(company_id,seq)")
            if not self.store.meta(db, "mail:sequence_migrated", False):
                db.execute("""INSERT OR IGNORE INTO career_mail_sequence(account_id,message_id,company_id)
                    SELECT account_id,message_id,company_id FROM career_mail_company_messages
                    ORDER BY account_id,message_id,company_id""")
                self.store.set_meta(db, "mail:sequence_migrated", True)

    def settings(self, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.settings(connection)
        value = self.store.meta(db, "mail:settings", {"enabled": False, "account_id": None})
        status = self.status(db=db)
        return dict(value, revision=self.store.meta(db, "mail:settings_revision", 0),
                    status=status, sender_count=len(self.sender_rules(db=db)))

    def configure(self, account_id, enabled, *, db=None):
        if type(enabled) is not bool:
            raise CareerError("invalid_mail_setting", "メールの定期取得設定を確認してください。")
        if account_id is not None and (not isinstance(account_id, str) or not re.fullmatch(r"gmail-[a-f0-9]{32}", account_id)):
            raise CareerError("invalid_account", "接続するGmailを選んでください。")
        if enabled and not account_id:
            raise CareerError("mail_connection_required", "Hermesの接続済みGmailを選んでください。", 409)
        if db is None:
            with self.store.transaction() as connection:
                return self.configure(account_id, enabled, db=connection)
        old = self.store.meta(db, "mail:settings", {"enabled": False, "account_id": None})
        if old.get("account_id") != account_id:
            self.store.set_meta(db, "mail:status", {"state": "not_started"})
        value = {"enabled": enabled, "account_id": account_id}
        if value != old:
            self.store.set_meta(db, "mail:settings_revision", self.store.meta(db, "mail:settings_revision", 0) + 1)
        self.store.set_meta(db, "mail:settings", value)
        return self.settings(db)

    def sender_rules(self, company_id=None, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.sender_rules(company_id, connection)
        rows = db.execute("SELECT id,version,data,updated_at FROM entities WHERE kind='company_mail_senders' ORDER BY id").fetchall()
        result = []
        import json
        for row in rows:
            data = json.loads(row["data"])
            company = self.store.entity(db, "companies", data.get("company_id"))
            if company and company["data"].get("deleted_at"):
                continue
            if company_id is not None and data.get("company_id") != company_id:
                continue
            try:
                address = normalize_address(data.get("emailAddress", ""))
            except CareerError:
                continue
            result.append({"id": row["id"], "version": row["version"], "company_id": data.get("company_id"),
                           "address": address, "enabled": data.get("enabled", True) is True,
                           "source": data.get("source", {}).get("system", "Hermes"), "updated_at": row["updated_at"]})
        return result

    def active_assignments(self, db=None):
        rules = [rule for rule in self.sender_rules(db=db) if rule["enabled"]]
        assignments = {}
        for rule in rules:
            assignments.setdefault(rule["address"], set()).add(rule["company_id"])
        return assignments

    def configure_sender(self, company_id, address, enabled, *, db=None):
        if type(enabled) is not bool:
            raise CareerError("invalid_mail_sender", "送信元の設定を確認してください。")
        address = normalize_address(address)
        if db is None:
            with self.store.transaction() as connection:
                return self.configure_sender(company_id, address, enabled, db=connection)
        company = self.store.entity(db, "companies", company_id)
        if not company or company["data"].get("deleted_at"):
            raise CareerError("company_missing", "企業が見つかりません。", 404)
        entity_id = "mail-sender:" + digest(company_id, address)
        old = self.store.entity(db, "company_mail_senders", entity_id)
        data = dict(old["data"] if old else {}, company_id=company_id, emailAddress=address, enabled=enabled,
                    title=address, source=(old["data"].get("source") if old else {"system": "Hermes"}))
        return self.store.put(db, "company_mail_senders", entity_id, old["version"] if old else 0, data, dirty=False)

    def status(self, company_id=None, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.status(company_id, connection)
        settings = self.store.meta(db, "mail:settings", {})
        sync = self.store.meta(db, "mail:sync:" + str(settings.get("account_id")), None)
        if sync:
            from .mail_sync import status_view
            return status_view(sync, settings, self.active_assignments(db), company_id,
                               requested=self.store.meta(db, "mail:request:" + str(settings.get("account_id")), 0))
        # Legacy imports and partial-success timestamps cannot certify account-wide coverage.
        from .mail_sync import status_view
        return status_view({}, settings, self.active_assignments(db), company_id)

    def update_status(self, state, *, cursor=None, reason=None, success=False, company_ids=(), db=None):
        if db is None:
            with self.store.transaction() as connection:
                return self.update_status(state, cursor=cursor, reason=reason, success=success,
                                          company_ids=company_ids, db=connection)
        old = self.status(db=db)
        result = dict(old, state=state, last_attempt_at=now(), reason=reason)
        if success:
            result["last_success_at"] = now()
            result["consecutive_failures"] = 0
        elif state in {"failed", "auth_required"}:
            result["consecutive_failures"] = old.get("consecutive_failures", 0) + 1
        if cursor is not None:
            result["cursor"] = cursor
        self.store.set_meta(db, "mail:status", result)
        for company_id in set(company_ids):
            public = dict(self.status(company_id, db), state=state, last_attempt_at=result["last_attempt_at"], reason=reason)
            if success:
                public.update(last_success_at=result["last_success_at"], consecutive_failures=0)
            elif state in {"failed", "auth_required"}:
                public["consecutive_failures"] = public.get("consecutive_failures", 0) + 1
            self.store.set_meta(db, "mail:status:" + company_id, public)
        return result

    def save_message(self, db, account_id, company_id, message):
        body = clean_excerpt(message.get("text", ""), 30000)
        snippet = clean_excerpt(message.get("snippet", ""), 500)
        sender = normalize_address(message["sender_address"])
        timestamp = now()
        old_link = db.execute("SELECT 1 FROM career_mail_company_messages WHERE account_id=? AND message_id=? AND company_id=?",
                              (account_id, message["id"], company_id)).fetchone()
        old = db.execute("SELECT first_observed_at,company_id FROM career_mail_messages WHERE account_id=? AND message_id=?",
                         (account_id, message["id"])).fetchone()
        db.execute("INSERT OR REPLACE INTO career_mail_messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (account_id, message["id"], message.get("thread_id") or message["id"], old["company_id"] if old else company_id, sender,
                    clean_excerpt(message.get("from", sender), 300), clean_excerpt(message.get("subject", ""), 300),
                    snippet, body, message["locator"], message["received_at"], int(message.get("gmail_unread", False)),
                    old["first_observed_at"] if old else timestamp, timestamp))
        db.execute("INSERT OR IGNORE INTO career_mail_company_messages VALUES(?,?,?)",
                   (account_id, message["id"], company_id))
        db.execute("INSERT OR IGNORE INTO career_mail_sequence(account_id,message_id,company_id) VALUES(?,?,?)",
                   (account_id, message["id"], company_id))
        return not bool(old_link)

    def messages(self, company_id, limit=100, before=None):
        parameters = [company_id]
        boundary = ""
        if before:
            boundary = "AND (received_at,account_id,message_id) < (?,?,?) "
            parameters.extend(before)
        parameters.append(limit)
        with self.store.connect() as db:
            rows = db.execute(
                "SELECT account_id,message_id,thread_id,sender_address,from_text,subject,snippet,body,locator,received_at,gmail_unread,"
                "first_observed_at,observed_at FROM career_mail_messages AS mail WHERE EXISTS ("
                "SELECT 1 FROM career_mail_company_messages AS link WHERE link.account_id=mail.account_id "
                "AND link.message_id=mail.message_id AND link.company_id=?) "
                + boundary + "ORDER BY received_at DESC,account_id DESC,message_id DESC LIMIT ?", parameters).fetchall()
        return [dict(row, gmail_unread=bool(row["gmail_unread"])) for row in rows]

    def cached(self, account_id, ids):
        with self.store.connect() as db:
            rows = [db.execute("SELECT * FROM career_mail_messages WHERE account_id=? AND message_id=?",
                               (account_id, identity)).fetchone() for identity in ids]
        return {row["message_id"]: {"id": row["message_id"], "thread_id": row["thread_id"],
            "sender_address": row["sender_address"], "from": row["from_text"], "subject": row["subject"],
            "snippet": row["snippet"], "text": row["body"], "locator": row["locator"],
            "received_at": row["received_at"], "gmail_unread": bool(row["gmail_unread"])} for row in rows if row}

    def request_sync(self):
        with self.store.transaction() as db:
            settings = self.store.meta(db, "mail:settings", {})
            if not settings.get("enabled") or not settings.get("account_id"):
                raise CareerError("mail_collection_disabled", "メールの自動同期を有効にしてください。", 409)
            key = "mail:request:" + settings["account_id"]
            generation = self.store.meta(db, key, 0) + 1
            self.store.set_meta(db, key, generation)
            return {"state": "queued", "generation": generation, "account_id": settings["account_id"]}

    def sync_view(self):
        settings = self.settings()
        status = dict(settings["status"])
        status.pop("cursor", None)
        return {"version": 2, "enabled": settings.get("enabled", False),
                "account_id": settings.get("account_id"), "sender_count": settings["sender_count"], "status": status}

    def snapshot_bound(self):
        with self.store.connect() as db:
            return db.execute("SELECT COALESCE(MAX(seq),0) FROM career_mail_sequence").fetchone()[0]

    def snapshot_sources(self, company_id, upper):
        with self.store.connect() as db:
            return {"gmail:" + row[0] + ":" + row[1] for row in db.execute(
                "SELECT account_id,message_id FROM career_mail_sequence WHERE company_id=? AND seq<=?", (company_id, upper))}

    def snapshot_page(self, company_id, upper, cursor=None, limit=20):
        if type(upper) is not int or not 0 <= upper < 2**63 or type(limit) is not int or not 1 <= limit <= 100:
            raise CareerError("invalid_mail_page", "メールの読取範囲を確認してください。")
        boundary, parameters = "", [company_id, upper]
        if cursor is not None:
            try:
                if not isinstance(cursor, str) or len(cursor) > 4096:
                    raise ValueError()
                value = json.loads(base64.urlsafe_b64decode(cursor))
                if (not isinstance(value, list) or len(value) != 5 or value[:2] != [company_id, upper]
                        or any(not isinstance(v, str) or len(v) > 300 for v in value[2:])):
                    raise ValueError()
                boundary = "AND (m.received_at,m.account_id,m.message_id)<(?,?,?)"
                parameters.extend(value[2:])
            except (ValueError, TypeError, UnicodeError):
                raise CareerError("invalid_mail_page", "メールの読取位置を確認してください。") from None
        parameters.append(limit + 1)
        with self.store.connect() as db:
            rows = db.execute("""SELECT m.*,s.seq FROM career_mail_sequence s JOIN career_mail_messages m
                ON m.account_id=s.account_id AND m.message_id=s.message_id
                WHERE s.company_id=? AND s.seq<=? """ + boundary +
                " ORDER BY m.received_at DESC,m.account_id DESC,m.message_id DESC LIMIT ?", parameters).fetchall()
        next_cursor = None
        if len(rows) > limit:
            last = rows[limit - 1]
            next_cursor = base64.urlsafe_b64encode(json.dumps(
                [company_id, upper, last['received_at'], last['account_id'], last['message_id']]).encode()).decode()
        return {"messages": [dict(row, gmail_unread=bool(row["gmail_unread"])) for row in rows[:limit]],
                "next_cursor": next_cursor, "snapshot_upper": upper}

    def message_page(self, company_id, cursor=""):
        before = None
        if cursor:
            if len(cursor) > 4096:
                raise ValueError("Invalid cursor")
            before = json.loads(base64.urlsafe_b64decode(cursor).decode("utf-8"))
            if not isinstance(before, list) or len(before) != 3 or any(not isinstance(v, str) for v in before):
                raise ValueError("Invalid cursor")
        rows = self.messages(company_id, 101, before)
        next_cursor = None
        if len(rows) > 100:
            last = rows[99]
            next_cursor = base64.urlsafe_b64encode(json.dumps([
                last["received_at"], last["account_id"], last["message_id"]]).encode()).decode()
        return {"messages": rows[:100], "next_cursor": next_cursor}
