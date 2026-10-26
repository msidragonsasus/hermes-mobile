"""Explicit company/offer conversation scope; old conversations remain global."""
from .store import CareerError


def accepted_sources(db, scope, refs, profile="default"):
    """Validate ownership and freeze exactly the sanitized evidence the user selected."""
    if not isinstance(refs, list) or len(refs) > 10:
        raise CareerError("invalid_source_refs", "相談する資料を選び直してください。")
    result = []
    for ref in refs:
        if not isinstance(ref, dict) or not isinstance(ref.get("id"), str) or not scope.get("company_id"):
            raise CareerError("invalid_source_refs", "相談する資料を選び直してください。")
        row = None
        if ref.get("kind") == "mail" and set(ref) == {"kind", "id", "account_id"} and isinstance(ref["account_id"], str):
            row = db.execute("SELECT account_id,message_id,subject,body,received_at FROM career_mail_messages "
                "WHERE company_id=? AND account_id=? AND message_id=?", (scope["company_id"], ref["account_id"], ref["id"])).fetchone()
        elif ref.get("kind") == "mypage" and set(ref) == {"kind", "id"}:
            row = db.execute("SELECT id,title,excerpt,locator,observed_at,source_key FROM career_evidence "
                "WHERE company_id=? AND id=? AND kind='mypage' AND (offer_id IS NULL OR offer_id=?)",
                (scope["company_id"], ref["id"], scope.get("offer_id"))).fetchone()
            if row:
                from .company_views import CompanyViews
                if not CompanyViews.visible_source(dict(row), scope["company_id"], profile, db):
                    row = None
        if not row:
            raise CareerError("source_scope_conflict", "資料と企業の紐付けを確認できません。", 409)
        result.append(dict(row, kind=ref["kind"]))
    return result


def approved_fetch_urls(store, db, text, scope):
    import re
    from .importing import public_url
    urls = re.findall(r"https://[^\s<>\"）]+", text)
    for row in db.execute("SELECT id FROM entities WHERE kind='companies'"):
        if scope.get("company_id") and row[0] != scope["company_id"]:
            continue
        entity = store.entity(db, "companies", row[0])
        urls.extend(entity["data"].get("official_urls", []))
    return sorted({value for url in urls if isinstance(url, str) and (value := public_url(url)) and value == url})[:200]


def thread_scope(store, db, thread_id="global", profile="default", *, read_only=False, allow_archived=False):
    if not isinstance(thread_id, str) or len(thread_id) > 240:
        raise CareerError("invalid_thread", "相談する企業を選び直してください。")
    if isinstance(thread_id, str) and thread_id.startswith("thread:"):
        row = db.execute("SELECT * FROM coach_threads WHERE profile=? AND id=?", (profile, thread_id)).fetchone()
        if not row:
            raise CareerError("thread_missing", "会話が見つかりません。", 404)
        if not read_only:
            if row["archived"] and not allow_archived:
                raise CareerError("thread_archived", "会話を復元してから送信してください。", 409)
            for kind, value in (("companies", row["company_id"]), ("offers", row["offer_id"])):
                if value and not store.entity(db, kind, value):
                    raise CareerError("target_missing", "関連先が見つかりません。履歴は引き続き読めます。", 409)
        return {k: row[k] for k in ("company_id", "offer_id") if row[k]}
    if not read_only and not allow_archived and db.execute("SELECT 1 FROM sqlite_master WHERE name='coach_threads'").fetchone():
        row = db.execute("SELECT archived FROM coach_threads WHERE profile=? AND id=?", (profile, thread_id)).fetchone()
        if row and row[0]:
            raise CareerError("thread_archived", "会話を復元してから送信してください。", 409)
    if thread_id == "global":
        return {}
    if not isinstance(thread_id, str) or len(thread_id) > 240:
        raise CareerError("invalid_thread", "相談する企業を選び直してください。")
    kind, separator, entity_id = thread_id.partition(":")
    if not separator or kind not in {"company", "offer"}:
        raise CareerError("invalid_thread", "相談する企業を選び直してください。")
    entity = store.entity(db, "companies" if kind == "company" else "offers", entity_id)
    if not entity:
        if read_only:
            return {"company_id" if kind == "company" else "offer_id": entity_id}
        raise CareerError("target_missing", "相談する企業・募集が見つかりません。", 404)
    company_id = entity_id if kind == "company" else entity["data"].get("company_id")
    if not read_only and (not company_id or not store.entity(db, "companies", company_id)):
        raise CareerError("target_missing", "募集の企業を確認できません。", 409)
    return {"company_id": company_id, **({"offer_id": entity_id} if kind == "offer" else {})}


def target_scope(store, db, target):
    if not target:
        return {}
    entity = store.entity(db, target["kind"], target["id"])
    if not entity:
        raise CareerError("target_missing", "相談対象を更新してください。", 409)
    data = entity["data"]
    if entity["kind"] == "documents":
        from .collection_store import document_context
        data = dict(data, **document_context(db, entity["id"], target.get("version", data.get("version", 1))))
    company_id = entity["id"] if entity["kind"] == "companies" else data.get("company_id")
    offer_id = entity["id"] if entity["kind"] == "offers" else data.get("offer_id")
    if offer_id:
        offer = store.entity(db, "offers", offer_id)
        if not offer or (company_id and company_id != offer["data"].get("company_id")):
            raise CareerError("target_scope_conflict", "文書・募集の関連を確認してください。", 409)
        company_id = offer["data"].get("company_id")
    return {k: v for k, v in (("company_id", company_id), ("offer_id", offer_id)) if v}


def accepted_scope(store, db, thread_id, target, profile="default", *, system_update=False):
    scope = thread_scope(store, db, thread_id, profile, allow_archived=system_update and thread_id == "global")
    selected = target_scope(store, db, target)
    if any(k in selected and selected[k] != value for k, value in scope.items()):
        raise CareerError("target_scope_conflict", "別の企業・募集が選択されています。相談画面を開き直してください。", 409)
    if scope and target and not selected.get("company_id") and target["kind"] not in {"facts", "stories", "hypotheses", "profile"}:
        raise CareerError("target_scope_conflict", "この企業との関連を確認できません。", 409)
    return dict(scope, **selected)
