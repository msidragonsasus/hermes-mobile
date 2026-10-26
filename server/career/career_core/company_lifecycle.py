"""User-requested company removal without destroying private evidence or ES drafts."""
from .store import CareerError, now


def active_records(records):
    removed = {e["id"] for e in records if e["kind"] == "companies" and e["data"].get("deleted_at")}
    offers = {e["id"] for e in records if e["kind"] == "offers" and e["data"].get("company_id") in removed}
    applications = {e["id"] for e in records if e["kind"] == "applications" and (
        e["data"].get("company_id") in removed or e["data"].get("offer_id") in offers)}
    from .store import SHARED
    return [e for e in records if e["kind"] in SHARED or not (
        (e["kind"] == "companies" and e["id"] in removed)
        or e["data"].get("company_id") in removed
        or e["data"].get("offer_id") in offers
        or e["data"].get("application_id") in applications)]


def delete_company(store, request):
    if request.get("confirmed_by_user") is not True:
        raise CareerError("confirmation_required", "企業の削除を確認してください。")

    def apply(db):
        company = store.entity(db, "companies", request["id"])
        if not company:
            raise CareerError("company_missing", "企業が見つかりません。", 404)
        return store.put(db, "companies", company["id"], request["expected_version"],
                         dict(company["data"], deleted_at=now()), dirty=False)

    result = store.operation(request["operation_id"], request, apply)
    from .mypage_cron import stop_company
    stop_company(store, request["id"])
    return result
