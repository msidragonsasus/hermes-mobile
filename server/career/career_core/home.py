"""Read-only Home contribution. Classifies saved sources; never collects or invokes AI."""
import json
import hashlib
import re
from datetime import datetime, timedelta, timezone

from .collection_store import clean_excerpt, digest
from .coach_store import profile_key
from .store import CareerError
from .notice_policy import classify

JST = timezone(timedelta(hours=9))
CLOSED = {"done", "submitted", "completed", "closed", "cancelled", "canceled", "withdrawn", "rejected"}


def deadline(value):
    """Preserve date precision. An offset-free explicit time is Japanese local time."""
    if not isinstance(value, str):
        return None
    try:
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            return datetime.strptime(value, "%Y-%m-%d").date()
        if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}", value):
            return None
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.replace(tzinfo=JST) if parsed.tzinfo is None else parsed
    except ValueError:
        return None


def timing(value, current):
    parsed = deadline(value)
    if parsed is None:
        return None
    if isinstance(parsed, datetime):
        return (parsed <= current + timedelta(days=7), parsed <= current + timedelta(hours=48), parsed <= current)
    today = current.astimezone(JST).date()
    return (parsed <= today + timedelta(days=7), parsed <= today + timedelta(days=2), parsed < today)




class HomeView:
    def __init__(self, store, clock=None):
        self.store = store
        self.clock = clock or (lambda: datetime.now(timezone.utc))

    def content(self, identity, version, profile="default"):
        """Resolve a selected item against current saved data, including sources beyond list limits."""
        cursor = None
        item = None
        while True:
            page = self.read(profile, cursor)
            item = next((i for i in page["items"] if i["id"] == identity), None)
            cursor = page["next_cursor"]
            if item or not cursor:
                break
        if item is None:
            raise CareerError("home_item_missing", "この項目は更新または解決済みです。ホームを読み直してください。", 404)
        if item["version"] != version:
            raise CareerError("home_item_changed", "内容が更新されています。ホームを読み直してください。", 409)
        target = item["target"]
        result = dict(title=item["title"], body="", locator="", target=target)
        with self.store.connect() as db:
            kind = target["kind"]
            if kind == "mail":
                row = db.execute("SELECT * FROM career_mail_messages WHERE account_id=? AND message_id=? AND company_id=?",
                                 (target["account_id"], target["source_id"], target["company_id"])).fetchone()
                if row:
                    result.update(title=row["subject"], body=row["body"], locator=row["locator"])
            elif kind == "mypage":
                row = db.execute("SELECT * FROM career_evidence WHERE id=? AND company_id=?",
                                 (target["source_id"], target["company_id"])).fetchone()
                if row:
                    result.update(title=row["title"], body=row["excerpt"], locator=row["locator"])
            elif kind == "conversation":
                row = db.execute("SELECT text FROM coach_messages WHERE id=? AND profile=?", ("reply:" + target["run_id"], profile_key(profile))).fetchone()
                if row:
                    result["body"] = row[0]
            elif kind == "document":
                for row in db.execute("SELECT o.result FROM coach_tool_operations o JOIN coach_runs r ON r.id=o.run_id WHERE r.profile=?", (profile_key(profile),)):
                    receipt = json.loads(row[0])
                    if receipt.get("id") == target["entity_id"] and receipt.get("data", {}).get("version") == target["document_version"]:
                        saved = db.execute("SELECT body FROM document_files WHERE path=?", (receipt["data"].get("path"),)).fetchone()
                        if saved:
                            result["body"] = saved[0]
                            break
            elif kind == "company":
                row = db.execute("SELECT data FROM entities WHERE id=?", (target["entity_id"],)).fetchone()
                if row:
                    data = json.loads(row[0])
                    result.update(body=data.get("title", item["title"]) + "\n期限：" + str(data.get("deadline", "不明")) +
                                  "\n" + data.get("deadline_evidence", "") + "\n" + data.get("description", ""),
                                  locator=data.get("source_url", ""))
        if not result["body"]:
            raise CareerError("home_content_missing", "内容を表示できません。元のAppで確認してください。", 404)
        return result

    def resolve_source(self, identity, version, profile="default"):
        """An explicit App-side record of completed work; never performs a submission."""
        content = self.content(identity, version, profile)
        if content["target"]["kind"] not in {"mail", "mypage"}:
            raise CareerError("invalid_source", "元のAppで対応状況を更新してください。")
        with self.store.transaction() as db:
            self.store.set_meta(db, "home-resolved:" + digest(profile_key(profile), identity), version)
        return {"resolved": True}

    def read(self, profile="default", cursor=None):
        profile = profile_key(profile)
        current = self.clock()
        items, coverage = [], []
        with self.store.connect() as db:
            # One read transaction across all sources. Do not initialize collectors here.
            db.execute("BEGIN")
            tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            records = [dict(r, data=json.loads(r["data"])) for r in db.execute("SELECT * FROM entities")]
            companies = {e["id"]: e for e in records if e["kind"] == "companies" and not e["data"].get("deleted_at")}
            offers = {e["id"]: e for e in records if e["kind"] == "offers"}
            applications = {e["id"]: e for e in records if e["kind"] == "applications"}

            def company_active(cid):
                data = companies.get(cid, {}).get("data", {})
                return cid in companies and data.get("user_stage") != "closed" and data.get("status") not in CLOSED

            def offer_for(e):
                data = e["data"]
                app = applications.get(data.get("application_id"), {})
                return offers.get(data.get("offer_id") or app.get("data", {}).get("offer_id"))

            def add(identity, category, title, target, *, due=None, changed=None, detail="", version_data=None, content=None):
                flags = timing(due, current)
                item = dict(id=identity, category=category, title=clean_excerpt(title, 300),
                            detail=clean_excerpt(detail, 400), target=target, deadline=due,
                            urgent=bool(flags and flags[1]), overdue=bool(flags and flags[2]),
                            updated_at=changed or "", provider="career")
                item["version"] = digest(category, title, target, due, detail, version_data)
                item["content_hash"] = hashlib.sha256(content.encode("utf-8")).hexdigest() if content is not None else None
                items.append(item)
                return item

            # Linked tasks take precedence over the corresponding application/offer deadline.
            candidates = sorted((e for e in records if e["kind"] in {"tasks", "deadlines", "applications", "offers"}),
                                key=lambda e: (e["kind"] not in {"tasks", "deadlines"}, e["id"]))
            seen_deadlines = set()
            for entity in candidates:
                data, kind = entity["data"], entity["kind"]
                offer = entity if kind == "offers" else offer_for(entity)
                cid = data.get("company_id") or (offer or {}).get("data", {}).get("company_id")
                app = applications.get(data.get("application_id"))
                if not company_active(cid) or data.get("status") in CLOSED or (app and app["data"].get("status") in CLOSED):
                    continue
                od = (offer or {}).get("data", {})
                if data.get("decision") == "dismiss" or od.get("decision") == "dismiss" or od.get("status") in CLOSED:
                    continue
                cd = companies[cid]["data"]
                interested = cd.get("user_priority") in {"high", "medium"} or cd.get("user_stage") in {"considering", "preparing", "applied", "interview"}
                linked_applications = [a for a in applications.values() if a["data"].get("offer_id") == entity["id"]]
                linked = any(a["data"].get("status") not in CLOSED for a in linked_applications)
                if kind == "offers" and linked_applications and not linked:
                    continue
                if kind == "offers" and not (interested or linked or data.get("decision") == "apply"):
                    continue
                if data.get("deadline_verification") in {"unconfirmed", "conflict"} or (data.get("deadline_source") == "offer" and od.get("deadline_verification") in {"unconfirmed", "conflict"}):
                    continue
                due = data.get("deadline")
                flags = timing(due, current)
                if not flags or not flags[0] or (flags[2] and kind not in {"tasks", "deadlines"}):
                    continue
                # Only collapse the generated application deadline, never unrelated tasks on the same date.
                canonical = (offer or {}).get("id") or data.get("application_id") or entity["id"]
                dedupe = (canonical, due) if kind in {"applications", "offers"} or data.get("deadline_source") == "offer" else (entity["id"], due)
                if dedupe in seen_deadlines:
                    continue
                seen_deadlines.add(dedupe)
                target = dict(kind="company", company_id=cid, section="prep", entity_id=entity["id"], offer_id=(offer or {}).get("id"))
                add("deadline:" + digest(dedupe), "action", companies[cid]["data"].get("name", "企業") + "：" + data.get("title", "締切"),
                    target, due=due, changed=entity["updated_at"], detail="期限超過・未完了" if flags[2] else "期限までに対応を確認")

            if "career_mail_messages" in tables:
                settings = self.store.meta(db, "mail:settings", {})
                for row in db.execute("SELECT * FROM career_mail_messages WHERE account_id=?", (settings.get("account_id", ""),)):
                    if not company_active(row["company_id"]):
                        continue
                    self.source(add, row["subject"], row["body"], "mail:" + row["account_id"] + ":" + row["message_id"],
                                dict(kind="mail", company_id=row["company_id"], source_id=row["message_id"], account_id=row["account_id"]),
                                row["received_at"], current, companies[row["company_id"]]["data"].get("name", "企業"), db=db, profile=profile)
                if settings.get("enabled"):
                    self.health(add, coverage, "mail", "就活メール", self.store.meta(db, "mail:status", {"state": "not_started"}))
            bot_companies = {r[0] for r in db.execute("SELECT company_id FROM career_mypage_connections WHERE profile=?", (profile,))} if "career_mypage_connections" in tables else set()
            if {"career_mypage_current", "career_evidence", "career_collection_grants"} <= tables:
                for row in db.execute("SELECT DISTINCT e.* FROM career_mypage_current c JOIN career_evidence e ON e.id=c.evidence_id "
                                      "JOIN career_collection_grants g ON g.company_id=c.company_id WHERE g.profile=?", (profile,)):
                    from .company_views import CompanyViews
                    if company_active(row["company_id"]) and row["company_id"] not in bot_companies and CompanyViews.visible_source(dict(row), row["company_id"], profile, db):
                        self.source(add, row["title"], row["excerpt"], row["source_key"],
                                    dict(kind="mypage", company_id=row["company_id"], source_id=row["id"]), row["first_observed_at"], current, companies[row["company_id"]]["data"].get("name", "企業"), db=db, profile=profile)
                for row in db.execute("SELECT DISTINCT company_id FROM career_collection_grants WHERE profile=?", (profile,)):
                    cid = row[0]
                    if company_active(cid) and cid not in bot_companies:
                        status = self.store.meta(db, "mypage:status:" + cid, {"state": "not_started"})
                        if status.get("state") == "running" and status.get("lease_until", "") <= current.isoformat():
                            status = dict(status, state="failed")
                        self.health(add, coverage, "mypage:" + cid, companies[cid]["data"].get("name", "企業") + "のマイページ", status, cid)
            if {"career_mypage_connections", "career_mypage_bot_items", "career_evidence"} <= tables:
                for row in db.execute("SELECT e.* FROM career_mypage_bot_items i JOIN career_mypage_connections c ON c.id=i.connection_id JOIN career_evidence e ON e.id=i.evidence_id WHERE c.profile=?", (profile,)):
                    if company_active(row["company_id"]):
                        self.source(add, row["title"], row["excerpt"], row["source_key"],
                                    dict(kind="mypage", company_id=row["company_id"], source_id=row["id"]), row["first_observed_at"], current, companies[row["company_id"]]["data"].get("name", "企業"), db=db, profile=profile)
                for row in db.execute("SELECT company_id,status FROM career_mypage_connections WHERE profile=?", (profile,)):
                    if company_active(row["company_id"]):
                        self.health(add, coverage, "mypage-bot:" + row["company_id"], companies[row["company_id"]]["data"].get("name", "企業") + "のマイページ", json.loads(row["status"]), row["company_id"])
            if {"coach_runs", "coach_messages"} <= tables:
                for row in db.execute("SELECT r.id,r.thread_id,r.updated_at,r.state,m.text FROM coach_runs r JOIN coach_messages m "
                                      "ON m.run_id=r.id AND m.id='reply:'||r.id WHERE r.profile=? AND r.kind IN ('message','research') AND r.state IN ('completed','waiting_user')", (profile,)):
                    add("reply:" + row["id"], "action" if row["state"] == "waiting_user" else "result",
                        "相談への回答が必要です" if row["state"] == "waiting_user" else "依頼への回答が届きました", dict(kind="conversation", thread_id=row["thread_id"], run_id=row["id"]),
                        changed=row["updated_at"], version_data=digest(row["text"]), content=row["text"])
                if "coach_tool_operations" in tables:
                    for row in db.execute("SELECT o.result,r.thread_id,r.id,r.updated_at FROM coach_tool_operations o JOIN coach_runs r ON r.id=o.run_id "
                                          "WHERE r.profile=? AND r.kind IN ('message','research') AND r.state='completed' ORDER BY r.updated_at", (profile,)):
                        receipt = json.loads(row["result"])
                        data = receipt.get("data", {})
                        if receipt.get("kind") != "documents" or "document_files" not in tables:
                            continue
                        saved = db.execute("SELECT body FROM document_files WHERE path=?", (data.get("path"),)).fetchone()
                        if not saved:
                            continue
                        items[:] = [i for i in items if i["id"] != "reply:" + row["id"]]
                        add("document:" + receipt["id"], "result", data.get("title", "資料ができました"),
                            dict(kind="document", entity_id=receipt["id"], document_version=data.get("version", 1), thread_id=row["thread_id"]),
                            changed=row["updated_at"], version_data=digest(data.get("version"), saved[0]), content=str(data.get("version", 1)) + "\n" + saved[0])
            items = [i for i in items if not (i["category"] == "action" and i["id"].startswith("source:") and
                     self.store.meta(db, "home-resolved:" + digest(profile, i["id"]), None) == i["version"])]
        # Display-only expiry; do not delete sources or record completion.
        items = [i for i in items if not (i["category"] == "action" and
                 (timing(i["deadline"], current) or (False, False, False))[2])]
        # Stable identities/versions prevent repeated collection from recreating unread cards.
        def order(item):
            parsed = deadline(item["deadline"])
            if parsed is not None and not isinstance(parsed, datetime):
                parsed = datetime.combine(parsed + timedelta(days=1), datetime.min.time(), JST)
            return (item["category"] != "action",
                    not (item["category"] == "action" and item["target"]["kind"] == "conversation" and item["deadline"] is None),
                    parsed or datetime.max.replace(tzinfo=timezone.utc), item["id"])
        items = sorted({i["id"]: i for i in items}.values(), key=order)
        fingerprint = digest([(i["id"], i["version"], i["deadline"], i["detail"], i.get("action_identity")) for i in items])
        offset = 0
        if cursor:
            try:
                previous, position = cursor.split(":")
                offset = int(position)
                if offset < 0 or offset > len(items):
                    raise ValueError()
            except (ValueError, AttributeError):
                raise CareerError("invalid_cursor", "ホームの取得位置を確認してください。") from None
            if previous != fingerprint:
                raise CareerError("home_changed", "情報が更新されました。読み直してください。", 409)
        page = items[offset:offset + 100]
        return dict(api_version=1, items=page, coverage=coverage, checked_at=current.isoformat(),
                    next_cursor=f"{fingerprint}:{offset + 100}" if offset + 100 < len(items) else None)

    def source(self, add, title, text, identity, target, changed, current, company_name="", *, db=None, profile="default"):
        label_prefix = company_name + " · " if company_name else ""
        from .mypage import deadlines
        classification = classify(title, text)
        # Mail questionnaires are actions too; the shared crawler notice policy stays unchanged.
        if classification is None and target["kind"] == "mail" and re.search(
                r"アンケート.{0,30}回答.{0,25}(?:ください|お願い|必要|期限)", title + "\n" + text):
            classification = ("action", "アンケート回答の依頼")
        legacy_classification = classification
        if target["kind"] == "mail":
            from .mail_deadline_notice import correct_deadline_notice
            classification = correct_deadline_notice(title, text, classification)
        if not classification:
            return
        category, label = classification
        dates = deadlines(title + "\n" + text)
        valid = [d for d in dates if d["date"]]
        # Multiple different deadlines cannot be safely collapsed into one requirement.
        values = {d["date"] + ("T" + d["time"] + "+09:00" if d["time"] and d["timezone"] == "Asia/Tokyo" else "") for d in valid}
        legacy_due = next(iter(values)) if len(values) == 1 else None
        if target["kind"] == "mail":
            from .action_classification import exact_deadlines
            values = exact_deadlines(title + "\n" + text)
        due = next(iter(values)) if len(values) == 1 else None
        flags = timing(due, current)
        if category == "action" and flags and not flags[0]:
            return
        item = add("source:" + identity, category, title, target, due=due if category == "action" else None,
            changed=changed, detail=label_prefix + label + ("・期限は元の案内で確認" if category == "action" and due is None else ""),
            version_data=digest(text), content=title + "\n" + text)

        if target["kind"] == "mail" and category == "action":
            # Displaying an explicit local time must not reopen an already resolved
            # unchanged source. Keep the source's pre-upgrade content/version binding.
            legacy_category, legacy_label = legacy_classification or (category, label)
            legacy_detail = label_prefix + legacy_label + ("・期限は元の案内で確認" if legacy_due is None else "")
            item["version"] = digest(legacy_category, title, target, legacy_due, legacy_detail, digest(text))

        if db is not None and category == "action" and target["kind"] == "mail":
            from .action_classification import ActionSource, cached_identity
            source = ActionSource(item["id"], item["version"], target["company_id"], title + "\n" + text, due)
            semantic = cached_identity(self.store, db, profile, source)
            if semantic:
                item["action_identity"] = semantic

    @staticmethod
    def health(add, coverage, identity, label, status, cid=None):
        state = status.get("state", "not_started")
        coverage.append(dict(id=identity, label=label, state=state, last_success_at=status.get("last_success_at")))
        if state in {"auth_required", "failed", "partial"}:
            add("connection:" + identity, "action", label + "の接続を確認してください",
                dict(kind="settings", company_id=cid), detail="最新情報を確認できません。接続設定を開いて確認してください。")
