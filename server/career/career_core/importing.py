"""Import only the Mobile-projected allowlist, not decrypted transfer envelopes."""
import re
from urllib.parse import urlsplit, urlunsplit

from .store import CareerError, encoded

FIELDS = {
    "Company": {"name", "status", "interestLevel", "entryRoute", "notes", "isImportant", "isArchived", "createdAt", "updatedAt"},
    "CompanyStatus": {"companySyncId", "status", "createdAt", "updatedAt"},
    "CompanyAlias": {"companySyncId", "alias", "createdAt", "updatedAt"},
    "CompanyMailSender": {"companySyncId", "emailAddress", "createdAt", "updatedAt"},
    "CompanyLink": {"companySyncId", "type", "label", "url", "createdAt", "updatedAt"},
    "Deadline": {"companySyncId", "type", "title", "dueAt", "note", "completed", "createdAt", "updatedAt"},
    "Task": {"companySyncId", "title", "dueAt", "estimate", "priority", "status", "note", "createdAt", "updatedAt"},
    "CompanyEvent": {"companySyncId", "type", "title", "scheduledAt", "endAt", "note", "completed", "createdAt", "updatedAt"},
}
KINDS = {"Company": "companies", "CompanyStatus": "company_statuses", "CompanyAlias": "company_aliases",
         "CompanyMailSender": "company_mail_senders", "CompanyLink": "company_links", "Deadline": "deadlines", "Task": "tasks", "CompanyEvent": "events"}
APPLICATION_STATES = {"Applied", "EsSubmitted", "WebTestCompleted", "WorkshopConfirmed", "Participated", "Internship", "InProgress", "Offer", "Declined", "Rejected"}
TASK_STATES = {"NotStarted": "todo", "InProgress": "doing", "Completed": "done", "OnHold": "blocked"}


def public_url(value):
    try:
        url = urlsplit(value)
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            return None
        # Tracking and login query strings may carry credentials; never retain them.
        return urlunsplit((url.scheme, url.netloc, url.path, "", ""))
    except (TypeError, ValueError):
        return None


def import_bundle(store, request):
    if request.get("format_version") != 1 or not re.fullmatch(r"[a-f0-9]{64}", request.get("file_sha256", "")):
        raise CareerError("unsupported_import", "未対応の取り込み形式です。元ファイルは保持されています。")
    records = request.get("records")
    if not isinstance(records, list) or len(records) > 20000:
        raise CareerError("invalid_import", "取り込み件数が不正です。")
    seen = set()
    for record in records:
        if not isinstance(record, dict):
            raise CareerError("invalid_import", "取り込み項目の形式が不正です。")
        kind = record.get("type")
        if kind not in FIELDS or not isinstance(record.get("data"), dict) or set(record["data"]) - FIELDS[kind]:
            raise CareerError("unapproved_fields", "取り込み対象外の項目が含まれています。")
        key = (kind, record.get("source_id"))
        if (not isinstance(key[1], str) or not 1 <= len(key[1]) <= 128
                or not isinstance(record.get("source_version"), str) or not record["source_version"]
                or key in seen):
            raise CareerError("invalid_source", "元ID・版の重複または欠落があります。")
        seen.add(key)

    def apply(db):
        result = {"imported": 0, "unchanged": 0, "conflicts": [], "skipped": request.get("skipped", [])}
        for record in sorted(records, key=lambda r: r["type"] != "Company"):
            source_key = record["type"] + ":" + record["source_id"]
            old_import = db.execute("SELECT * FROM imports WHERE source_key=?", (source_key,)).fetchone()
            if old_import and old_import["version_id"] == record["source_version"]:
                result["unchanged"] += 1
                continue
            kind = KINDS[record["type"]]
            entity_id = "shukatsu:" + source_key
            current = store.entity(db, kind, entity_id)
            # Re-export updates are reviewed rather than silently replacing user edits/deletions.
            if old_import or record.get("deleted", False):
                conflict = {"source_key": source_key, "reason": "source_changed" if old_import else "source_deleted",
                            "incoming": record, "current": current}
                result["conflicts"].append(conflict)
                store.notice(db, "import_conflict", conflict,
                             "import:" + source_key + ":" + record["source_version"])
                continue
            data = dict(record["data"])
            if record["type"] == "CompanyMailSender":
                from .mail_store import normalize_address
                data["emailAddress"] = normalize_address(data.get("emailAddress", ""))
            if record["type"] == "CompanyLink":
                data["url"] = public_url(data.get("url"))
            company = data.pop("companySyncId", None)
            if company:
                data["company_id"] = "shukatsu:Company:" + company
                if not store.entity(db, "companies", data["company_id"]):
                    result["conflicts"].append({"source_key": source_key, "reason": "missing_company", "incoming": record})
                    continue
            data.update({"source": {"system": "Shukatsu", "bundle_id": request.get("bundle_id"),
                                    "file_sha256": request["file_sha256"], "id": record["source_id"],
                                    "version": record["source_version"], "type": record["type"]}})
            data["title"] = data.get("title") or data.get("name") or data.get("label") or data.get("alias") or "選考状況"
            if record["type"] == "CompanyMailSender":
                data["title"] = data["emailAddress"]
                data["enabled"] = True
            if record["type"] == "CompanyStatus":
                data["source_status"] = data.get("status")
            if record["type"] == "Task":
                data["source_status"] = data.get("status")
                data["status"] = TASK_STATES.get(data.get("status"), "unknown")
            if record["type"] == "Deadline":
                data["status"] = "done" if data.get("completed") else "todo"
            if "dueAt" in data:
                data["deadline"] = data["dueAt"] or None
            saved = store.put(db, kind, entity_id, 0, data, dirty=False)
            if record["type"] in {"Company", "CompanyStatus"} and data.get("status") in APPLICATION_STATES:
                company_id = entity_id if record["type"] == "Company" else data["company_id"]
                application_id = "shukatsu:application:" + company_id
                existing = store.entity(db, "applications", application_id)
                if not existing:
                    store.put(db, "applications", application_id, 0, {"company_id": company_id,
                        "title": "応募・選考", "status": "imported", "source_status": data["status"], "source": data["source"]}, dirty=False)
            if record["type"] == "CompanyLink" and data.get("type") == "Recruit" and data.get("url"):
                company = store.entity(db, "companies", data["company_id"])
                urls = list(dict.fromkeys(company["data"].get("official_urls", []) + [data["url"]]))
                store.put(db, "companies", company["id"], company["version"], dict(company["data"], official_urls=urls), dirty=False)
            db.execute("INSERT INTO imports VALUES(?,?,?,?)", (source_key, record["source_version"], saved["version"], encoded(record)))
            result["imported"] += 1
        store.notice(db, "import_complete", result, "import:" + request["file_sha256"])
        return result
    return store.operation(request["operation_id"], request, apply)
