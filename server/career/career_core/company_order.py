"""Profile-local company presentation order; excluded from shared career/GitHub state."""
import json

from .coach_store import profile_key
from .store import CareerError


def order_key(profile):
    return "company_list_order:" + profile_key(profile)


def read_order(store, db, profile):
    try:
        value = store.meta(db, order_key(profile), {"version": 0, "company_ids": []})
        ids = value["company_ids"]
        if type(value["version"]) is not int or not 0 <= value["version"] <= 2147483647:
            return None
        if not isinstance(ids, list) or any(not isinstance(i, str) or not 1 <= len(i) <= 200 for i in ids) or len(set(ids)) != len(ids):
            return None
        return value
    except (KeyError, TypeError, ValueError):
        return None


def apply_order(rows, ids):
    by_id = {row["id"]: row for row in rows}
    known = [i for i in ids if i in by_id]
    chosen = set(known)
    return [by_id[i] for i in known] + [row for row in rows if row["id"] not in chosen]


def active_company_ids(db):
    """Use exactly Store.snapshot's lifecycle predicate, within the write transaction."""
    from .company_lifecycle import active_records
    try:
        records = [dict(r, data=json.loads(r['data'])) for r in db.execute('SELECT * FROM entities')]
        return {r['id'] for r in active_records(records) if r['kind'] == 'companies'}
    except (ValueError, TypeError, AttributeError):
        raise CareerError('company_order_unavailable', '保存データを確認できないため並べ替えできません。', 422) from None


def save_order(store, payload, profile):
    profile = profile_key(profile)
    if not isinstance(payload, dict) or set(payload) != {"operation_id", "expected_version", "company_ids"}:
        raise CareerError("invalid_company_order", "企業の順序を確認してください。", 400)
    ids, version = payload["company_ids"], payload["expected_version"]
    if type(version) is not int or version < 0 or not isinstance(ids, list):
        raise CareerError("invalid_company_order", "企業の順序を確認してください。", 400)
    if any(not isinstance(i, str) or not 1 <= len(i) <= 200 for i in ids) or len(set(ids)) != len(ids):
        raise CareerError("invalid_company_order", "企業の順序を確認してください。", 400)

    def apply(db):
        current = read_order(store, db, profile)
        if current is None:
            raise CareerError("company_order_unavailable", "保存済みの順序を確認できません。", 422)
        if current["version"] != version:
            raise CareerError("company_order_conflict", "企業の順序が更新されています。一覧を再読み込みしてください。", 409)
        if version >= 2147483647:
            raise CareerError("company_order_unavailable", "並べ替えの保存回数が上限に達しました。", 422)
        active = active_company_ids(db)
        if set(ids) != active:
            raise CareerError("company_list_changed", "企業一覧が更新されています。再読み込みしてください。", 409)
        result = {"version": version + 1, "company_ids": list(ids)}
        store.set_meta(db, order_key(profile), result)
        return result

    return store.operation(payload["operation_id"], dict(payload, action="company-order", profile=profile), apply)
