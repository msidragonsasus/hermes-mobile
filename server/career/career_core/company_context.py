"""Frozen, profile-scoped company directory for every kind of consultation."""
from .company_views import CompanyViews
from .store import encoded, now


def bounded_preview(value, maximum=24000):
    """Bound serialized data, with an explicit full-detail retrieval contract."""
    if len(encoded(value)) <= maximum:
        return value
    priority = {"id", "kind", "version", "state", "credential_state", "login_state", "label", "next_action",
                "external_generation_allowed", "blocked_ids", "company_id", "offer_id", "total", "next_offset", "source"}
    def preview(item, text_limit, count, depth=0):
        if isinstance(item, str):
            return item if len(item) <= text_limit else item[:text_limit] + " [続きはcareer_context]"
        if isinstance(item, list):
            return [preview(entry, text_limit, count, depth + 1) for entry in item[:count]] if depth < 7 else []
        if isinstance(item, dict):
            keys = sorted(item, key=lambda key: (key not in priority, key))[:30]
            result = {key: preview(item[key], text_limit, count, depth + 1) for key in keys
                      if key not in {"password", "login_id", "api_key", "token", "secret_ref", "authorization"}} if depth < 7 else {}
            for key in ("context_constraints", "constraints"):
                if isinstance(result.get(key), dict):
                    result[key].update(external_generation_allowed=False, requires_full_read=True)
            return result
        return item
    for text_limit, count in ((1000, 10), (500, 5), (200, 3), (100, 1)):
        result = preview(value, text_limit, count)
        result["preview_only"] = True
        result["retrieve_full_detail"] = "career_context。省略された資料・訂正・利用制限の全文を確認してから文章を生成する。"
        if isinstance(result.get("context_constraints"), dict):
            result["context_constraints"]["external_generation_allowed"] = False
            result["context_constraints"]["requires_full_read"] = True
        if len(encoded(result)) <= maximum:
            return result
    return {"preview_only": True, "retrieve_full_detail": "career_contextで本人情報・企業情報・訂正・利用制限を読む。",
            "context_constraints": {"external_generation_allowed": False, "requires_full_read": True}}


def directory(store, run):
    key = "coach_company_snapshot:" + run["id"]
    with store.connect() as db:
        cached = store.meta(db, key)
    if cached is not None:
        return cached
    view = CompanyViews(store).overview(run["profile"])
    scope = run["request"].get("scope", {})
    companies = view["companies"]
    if scope.get("company_id"):
        companies = [row for row in companies if row["id"] == scope["company_id"]]
    result = {"companies": companies, "checked_at": now(),
              "coverage": "保存済み企業情報・接続設定・巡回状態。サイトへの未登録や認証情報の読出可否は推測しない。"}
    with store.transaction() as db:
        existing = store.meta(db, key)
        if existing is not None:
            return existing
        store.set_meta(db, key, result)
    return result


def page(store, run, offset=0, limit=30):
    value = directory(store, run)
    companies = value["companies"]
    return dict(value, companies=companies[offset:offset + limit], total=len(companies),
                next_offset=offset + limit if offset + limit < len(companies) else None)


def initial_brief(context):
    """Bounded source previews; complete saved bodies remain available through tools."""
    from .collection_store import clean_excerpt
    updates = []
    for update in context.get("stored_updates", [])[:5]:
        updates.append({"company_id": update["company_id"], "mypage_status": update["mypage_status"],
            "mail_status": update["mail_status"],
            "notices": [{"id": item.get("id"), "title": clean_excerpt(item.get("title", ""), 150),
                         "excerpt": clean_excerpt(item.get("excerpt", ""), 500),
                         "observed_at": item.get("observed_at"), "deadlines": item.get("deadlines", [])}
                        for item in update["notices"][:3]],
            "mail": [{"message_id": item.get("message_id"), "account_id": item.get("account_id"),
                      "subject": clean_excerpt(item.get("subject", ""), 150),
                      "body": clean_excerpt(item.get("body", ""), 500), "received_at": item.get("received_at")}
                     for item in update["mail"][:3]],
            "coverage": "先頭3件の短い抜粋。全文・続きはcareer_contextで読む。"})
    result = {"company_overview": context["company_overview"], "saved_source_previews": updates,
              "material": context["material"], "context_constraints": context["context_constraints"],
              "context_used": context["context_used"], "page": context["page"]}
    company = context.get("company")
    if company:
        result["selected_company"] = {"company": company["company"], "mypage_readiness": company["mypage_readiness"],
                                      "offers": company["offers"][:10], "records": company["records"][:10]}
    return bounded_preview(result)
