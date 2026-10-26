"""Coach-requested MyPage connection changes: URL and on/off only.

Login IDs and passwords never pass through the coach. A new connection or a URL
change is held as a credential-free pending request; the App's MyPage form shows
it and the person enters the login there (the existing sealed-secret path).
"""
import json
import re

from .mypage_bot import MyPageBotStore, connection_id, portal_url
from .store import CareerError, encoded, now

CREDENTIAL_KEYS = {"login_id", "password", "credentials", "secret", "secret_ref", "token", "otp", "auth_code"}
URL_IN_TEXT = re.compile(r"https://[^\s<>\"'「」『』（）()、。]+", re.I)


def pending_key(identity):
    return "mypage:pending:" + identity


def clear_pending(db, identity):
    db.execute("DELETE FROM metadata WHERE key=?", (pending_key(identity),))


def pending_view(store, db, company, profile, version):
    """A pending request only applies to the connection version it was made against."""
    value = store.meta(db, pending_key(connection_id(company, profile)))
    if not value or value.get("base_version") != version:
        return None
    return {k: value[k] for k in ("portal_url", "enabled", "url_source", "requested_at")}


def registered_urls(store, db, company):
    urls = set()
    for row in db.execute("SELECT data FROM entities WHERE kind='company_links'"):
        data = json.loads(row[0])
        if data.get("company_id") != company or data.get("type") != "MyPage" or data.get("deleted_at"):
            continue
        try:
            urls.add(portal_url(data.get("url")))
        except CareerError:
            pass
    return sorted(urls)


def overview(store, profile, company_id=None):
    """Credential-free per-company state for the coach's connections read."""
    records = MyPageBotStore(store)
    result = []
    with store.connect() as db:
        companies = [store.entity(db, "companies", company_id)] if company_id else [
            store.entity(db, "companies", r[0]) for r in db.execute("SELECT id FROM entities WHERE kind='companies' ORDER BY id")]
        for company in companies:
            if not company or company["data"].get("deleted_at"):
                continue
            row = records.get(connection_id(company["id"], profile), db)
            version = row["version"] if row else 0
            result.append({
                "company_id": company["id"], "name": company["data"].get("name"),
                "connection_version": version, "portal_url": row["config"]["url"] if row else None,
                "enabled": bool(row and row["config"].get("enabled")),
                "verified": bool(row and row["config"].get("verified")),
                "credentials_saved": row is not None, "state": (row["status"] if row else {}).get("state", "not_started"),
                "closed": company["data"].get("user_stage") == "closed",
                "registered_mypage_urls": registered_urls(store, db, company["id"]),
                "pending_request": pending_view(store, db, company["id"], profile, version)})
    return result


def quoted_url(url, quote):
    if url in quote:
        return True
    for candidate in URL_IN_TEXT.findall(quote):
        try:
            if portal_url(candidate) == url:
                return True
        except CareerError:
            pass
    return False


def request_change(tools, args, run, reconcile=None):
    store = tools.store
    profile = run["profile"]
    company = args.get("company_id")
    if not isinstance(company, str) or not 1 <= len(company) <= 200:
        raise CareerError("company_missing", "設定する企業を指定してください。")
    scope = run["request"].get("scope", {})
    if scope.get("company_id") and company != scope["company_id"]:
        raise CareerError("write_scope_conflict", "相談中の企業だけを設定してください。", 403)
    raw_url = args.get("url")
    if raw_url is None:
        with store.connect() as db:
            urls = registered_urls(store, db, company)
        if len(urls) != 1:
            raise CareerError("mypage_url_required", "登録済みのマイページリンクが一つに決まりません。本人に接続URLを確認してください。", 409)
        url, source = urls[0], "company_link"
    else:
        url, source = portal_url(raw_url), "user_statement"
    # DNS is resolved outside the write transaction, as in the App save path.
    from .mypage_browser import public_host
    public_host(url)
    records = MyPageBotStore(store)
    identity = connection_id(company, profile)

    def apply(db, current, operation_id):
        proof = tools.evidence(db, current, args)
        if source == "user_statement" and not quoted_url(url, proof["quote"]):
            raise CareerError("setting_quote_required", "設定するURLを本人の依頼文と照合できません。", 409)
        row = records.get(identity, db)
        records.active(row or {"company_id": company}, db)
        version = row["version"] if row else 0
        if version != args["expected_version"]:
            raise CareerError("version_conflict", "マイページ接続設定が変わりました。connectionsで読み直してください。", 409)
        base = {"company_id": company, "portal_url": url, "url_source": source, "collection_verified": False}
        if row and row["config"]["url"] == url:
            clear_pending(db, identity)
            if row["config"].get("enabled") == args["enabled"]:
                return dict(base, saved=True, changed=False, connection_version=version, enabled=args["enabled"])
            config = dict(row["config"], enabled=args["enabled"])
            if not args["enabled"]:
                config["next_run_at"] = None
            # A new version invalidates in-flight runs and stale App forms; the sealed secret is unchanged.
            db.execute("UPDATE career_mypage_connections SET version=?,config=? WHERE id=?",
                       (version + 1, encoded(config), identity))
            db.execute("UPDATE career_mypage_runs SET state='cancelled',updated_at=? WHERE connection_id=? AND state IN ('queued','running')",
                       (now(), identity))
            return dict(base, saved=True, changed=True, connection_version=version + 1, enabled=args["enabled"],
                        schedule="毎日9時・18時（初回確認の成功後）" if args["enabled"] else "停止")
        if not row and not args["enabled"]:
            clear_pending(db, identity)
            return dict(base, saved=True, changed=False, connection_version=0, enabled=False, pending_request=None)
        pending = {"portal_url": url, "enabled": args["enabled"], "url_source": source, "requested_at": now(),
                   "base_version": version, "run_id": current["id"]}
        store.set_meta(db, pending_key(identity), pending)
        return dict(base, saved=False, pending=True, credentials_required=True, connection_version=version,
                    pending_request={k: pending[k] for k in ("portal_url", "enabled", "url_source", "requested_at")},
                    next_step="Appの企業詳細→設定→マイページで、表示されたURLを確認しログインID・パスワードを入力して保存してください。"
                              + ("URLを変えるため保存済みのログイン情報は引き継げません。" if row else ""))

    result = tools.coach.tool_once(run["id"], dict(args, tool="career_app"), apply)
    if result.get("changed"):
        latest = records.get(identity)
        if latest:
            (reconcile or default_reconcile)(records, latest)
    return result


def default_reconcile(records, row):
    from .mypage_cron import reconcile
    reconcile(records, row)
