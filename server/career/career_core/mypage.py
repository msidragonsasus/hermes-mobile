"""Read-only, explicit portal notice collection. Never invokes the retired ES reader."""
from datetime import datetime, timedelta, timezone
import re

from .collection_store import CollectionStore, PILOT, PILOT_ROOT, clean_excerpt, public_locator
from .store import CareerError, now


def deadlines(text):
    """Keep every labelled deadline and its precision; never invent a year or midnight."""
    result = []
    for match in re.finditer(r"([^\n。]{0,30}(?:締切|期限)[^\n。]{0,100}(?:\n\s*20\d{2}[^\n。]{0,80})?)", text):
        quote = match[0].strip()
        date = re.search(r"(20\d{2})[年/.-]\s*(\d{1,2})[月/.-]\s*(\d{1,2})日?", quote)
        time = re.search(r"(\d{1,2})[:時](\d{2})分?", quote)
        value = None
        if date:
            try:
                value = datetime(*map(int, date.groups())).strftime("%Y-%m-%d")
            except ValueError:
                pass
        clock = None
        if time and int(time[1]) < 24 and int(time[2]) < 60:
            clock = f"{int(time[1]):02}:{time[2]}"
        result.append({"original_text": quote, "date": value, "time": clock,
                       "timezone": "Asia/Tokyo" if re.search(r"日本時間|JST", quote) else None,
                       "precision": "minute" if value and clock else "date" if value else "unknown"})
    return result


class MyPageStore:
    def __init__(self, store):
        self.store = store
        self.collection = CollectionStore(store)
        with store.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS career_mypage_current(
                    company_id TEXT NOT NULL, source_key TEXT NOT NULL, evidence_id TEXT NOT NULL,
                    PRIMARY KEY(company_id,source_key));
            """)

    def status(self, company_id, db=None):
        if db is None:
            with self.store.connect() as connection:
                return self.status(company_id, connection)
        status = self.store.meta(db, "mypage:status:" + company_id, {"state": "not_started"})
        if status.get("state") == "running" and status.get("lease_until", "") <= now():
            return dict(status, state="failed", reason="collection_interrupted")
        return status

    def grant(self, company_id, profile):
        return next((g for g in self.collection.grants()
                     if g["company_id"] == company_id and g["profile"] == profile and g["portal_id"] == PILOT), None)

    def view(self, company_id, profile="default"):
        result = self._view(company_id, profile)
        from .mypage_request import pending_view
        with self.store.connect() as db:
            result["pending_request"] = pending_view(self.store, db, company_id, profile, result.get("connection_version", 0))
        return result

    def _view(self, company_id, profile="default"):
        from .mypage_bot import MyPageBotStore
        bot = MyPageBotStore(self.store).view(company_id, profile)
        if bot is not None:
            return bot
        with self.store.connect() as db:
            rows = [dict(r) for r in db.execute(
                "SELECT e.* FROM career_mypage_current c JOIN career_evidence e ON e.id=c.evidence_id "
                "WHERE c.company_id=? ORDER BY e.first_observed_at DESC", (company_id,))]
            history = [dict(r) for r in db.execute(
                "SELECT * FROM career_evidence WHERE company_id=? AND kind='mypage' AND source_key NOT LIKE 'mypage-bot:%' "
                "ORDER BY observed_at DESC LIMIT 100", (company_id,))]
            status = self.status(company_id, db)
            runs = self.store.meta(db, "mypage:history:" + company_id, [])
        grant = self.grant(company_id, profile)
        if not grant:
            rows, history, runs = [], [], []
            status = {"state": "not_started"}
        return {"status": status, "notices": [dict(r, deadlines=deadlines(r["excerpt"])) for r in rows],
                "history": history, "fetch_history": runs, "supported": grant is not None, "automation": "disabled",
                "portal_url": PILOT_ROOT + "index" if grant else None,
                "bot_available": True, "connection_version": 0,
                "coverage": "ログイン後のホームに掲載された案内。別ページとES回答フォームは取得対象外。"}

    def collect(self, company_id, profile="default", reader=None):
        from .mypage_bot import MyPageBotStore, connection_id
        if MyPageBotStore(self.store).get(connection_id(company_id, profile)):
            raise CareerError("mypage_bot_required", "この接続はHermes Botから確認してください。", 409)
        grant = self.grant(company_id, profile)
        if not grant:
            raise CareerError("mypage_not_connected", "この企業のマイページ取得接続は未対応、または未設定です。", 409)
        key = "mypage:status:" + company_id
        with self.store.transaction() as db:
            old = self.status(company_id, db)
            if old.get("state") == "running" and old.get("lease_until", "") > now():
                raise CareerError("collection_running", "マイページを取得中です。", 409)
            self.store.set_meta(db, key, dict(old, state="running", reason=None, last_attempt_at=now(),
                lease_until=(datetime.now(timezone.utc) + timedelta(minutes=3)).isoformat()))
        try:
            if reader is None:
                from .mypage_reader import NoticeReader
                from .secret_store import SecretStore
                reader = NoticeReader(SecretStore())
            page = reader.fetch()
            if not page.get("authenticated") or not page.get("recognized"):
                raise CareerError("portal_structure_changed", "案内一覧を確認できません。前回の情報を保持しています。", 409)
            changed = 0
            with self.store.transaction() as db:
                if db.execute("SELECT 1 FROM career_mypage_connections WHERE id=?", (connection_id(company_id, profile),)).fetchone():
                    raise CareerError("collection_cancelled", "Bot接続へ切り替わったため旧方式の保存を中止しました。", 409)
                current_grant = db.execute("SELECT company_id,profile FROM career_collection_grants WHERE id=?", (grant["id"],)).fetchone()
                if not current_grant or current_grant["company_id"] != company_id or current_grant["profile"] != profile:
                    raise CareerError("collection_cancelled", "取得中に接続先が変更されたため、保存を中止しました。", 409)
                # Only the current home view is replaced; immutable source versions remain.
                db.execute("DELETE FROM career_mypage_current WHERE company_id=?", (company_id,))
                for notice in page["notices"]:
                    source_key = "mypage:" + grant["id"] + ":" + notice["key"]
                    evidence_id, fresh = self.collection.save_evidence(db, source_key=source_key,
                        company_id=company_id, kind="mypage", title=notice["title"],
                        locator=public_locator(notice["url"]), text=notice["title"] + "\n" + notice["body"])
                    db.execute("INSERT INTO career_mypage_current VALUES(?,?,?) ON CONFLICT(company_id,source_key) "
                               "DO UPDATE SET evidence_id=excluded.evidence_id", (company_id, source_key, evidence_id))
                    changed += int(fresh)
                    if fresh:
                        self.collection.notice(db, evidence_id, company_id, None,
                                               "source_changed" if old.get("last_success_at") else "initial_source", clean_excerpt(notice["title"], 300))
                self.collection.attention(db, "legacy-mypage:" + grant["id"], company_id, False, profile=profile)
                self.store.set_meta(db, key, {"state": "updated" if changed else "unchanged",
                    "last_attempt_at": self.status(company_id, db)["last_attempt_at"], "last_success_at": now(),
                    "reason": None, "coverage": "authenticated_home", "pagination_complete": False})
                self.record_run(db, company_id)
            return {"state": "completed", "changed": changed}
        except Exception as exc:
            error = exc if isinstance(exc, CareerError) else CareerError("browser_unavailable", "マイページを取得できませんでした。", 503)
            with self.store.transaction() as db:
                status = self.status(company_id, db)
                status.update(state="auth_required" if error.code.startswith("authentication") else "failed", reason=error.code)
                if status["state"] == "auth_required":
                    self.collection.attention(db, "legacy-mypage:" + grant["id"], company_id, True, profile=profile)
                status.pop("lease_until", None)
                self.store.set_meta(db, key, status)
                self.record_run(db, company_id)
            raise error from None

    def record_run(self, db, company_id):
        key = "mypage:history:" + company_id
        self.store.set_meta(db, key, [self.status(company_id, db)] + self.store.meta(db, key, [])[:99])
