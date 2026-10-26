"""Company-facing read models composed from existing records and private evidence."""
from .collection_store import CollectionStore, document_context
from .store import CareerError, now
from datetime import datetime, timedelta, timezone


class CompanyViews:
    def __init__(self, store):
        self.store = store
        self.collection = CollectionStore(store)
        from .mail_store import MailStore
        self.mail = MailStore(store)
        from .mypage import MyPageStore
        self.mypage = MyPageStore(store)

    @staticmethod
    def current_offer(offer, today):
        data = offer["data"]
        return data.get("status") not in {"closed", "completed", "cancelled", "withdrawn"} and (
            not data.get("deadline") or data["deadline"][:10] >= today
        )

    def overview(self, profile="default"):
        from .coach_store import profile_key
        profile = profile_key(profile)
        snapshot = self.store.snapshot()
        records = snapshot["entities"]
        mail_companies = {rule["company_id"] for rule in self.mail.sender_rules() if rule["enabled"]}
        result = []
        today = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d")
        with self.store.connect() as db:
            for company in (e for e in records if e["kind"] == "companies"):
                company_id = company["id"]
                offers = [e for e in records if e["kind"] == "offers" and e["data"].get("company_id") == company_id]
                current_offers = [e for e in offers if self.current_offer(e, today)]
                tasks = [e for e in records if e["kind"] in {"tasks", "deadlines"} and e["data"].get("company_id") == company_id
                         and e["data"].get("status") not in {"done", "submitted", "cancelled"}]
                deadlines = sorted([e["data"]["deadline"] for e in current_offers + tasks if e["data"].get("deadline") and e["data"].get("deadline_verification") != "conflict"])
                seen = self.store.meta(db, f"seen:{profile}:{company_id}", 0)
                new_count = sum(self.visible_source(dict(r), company_id, profile, db) for r in db.execute(
                    "SELECT COALESCE(e.source_key,'') AS source_key FROM career_notice_feed n LEFT JOIN career_evidence e ON e.id=n.id "
                    "WHERE n.company_id=? AND n.seq>? AND n.kind IN ('initial_source','source_received','source_changed')", (company_id, seen)))
                states = []
                if company_id in mail_companies:
                    states.append(dict(self.mail.status(company_id, db), channel="mail"))
                portal = self.mypage.view(company_id, profile)
                from .company_readiness import mypage_readiness
                portal_link = next((e["data"].get("url") for e in records if e["kind"] == "company_links"
                                    and e["data"].get("company_id") == company_id and e["data"].get("type") == "MyPage"), None)
                readiness = mypage_readiness(portal, portal_link)
                active = company["data"].get("user_stage") != "closed" and not company["data"].get("deleted_at")
                setup_action = readiness["next_action"] if active else None
                if portal.get("connection_version", 0) > 0 or portal.get("supported"):
                    states.append(dict(portal["status"], channel="mypage"))
                last_success = max((s.get("last_success_at") or "" for s in states), default="")
                blocked = next((s for s in states if s["state"] in {"auth_required", "failed", "partial"}), None)
                if blocked:
                    channel = "マイページ" if blocked["channel"] == "mypage" else "メール"
                    action = channel + ("接続を確認" if blocked["state"] == "auth_required" else "取得状況を確認")
                elif setup_action:
                    action = setup_action
                elif tasks:
                    action = tasks[0]["data"].get("title", "必要な対応を確認")
                elif current_offers:
                    action = "募集中の情報と締切を確認"
                elif new_count:
                    action = "届いた案内を確認"
                else:
                    action = "現在の募集情報を確認"
                application_counts = {}
                for application in records:
                    if application["kind"] != "applications":
                        continue
                    data = application["data"]
                    if data.get("company_id") != company_id and data.get("offer_id") not in {o["id"] for o in offers}:
                        continue
                    status = data.get("status", "unknown")
                    group = ("preparing" if status in {"planned", "preparing"} else
                             "in_process" if status in {"submitted", "in_process"} else
                             "offered" if status == "offered" else
                             "closed" if status in {"rejected", "withdrawn", "closed", "completed", "cancelled"} else "review")
                    application_counts[group] = application_counts.get(group, 0) + 1
                result.append({"application_counts": application_counts, "id": company_id, "name": company["data"].get("name", company["data"].get("title", company_id)),
                               "offer_count": len(current_offers), "next_action": action, "deadline": deadlines[0] if deadlines else None,
                               "new_count": new_count, "needs_action": bool(tasks or blocked or setup_action or new_count or current_offers),
                               "collection_state": blocked["state"] if blocked else ("available" if last_success else "not_started"),
                               "mypage_readiness": readiness,
                               "mail_status": {key: self.mail.status(company_id, db).get(key) for key in
                                               ("state", "fresh", "last_success_at", "last_attempt_at", "reason", "coverage")},
                               "last_success_at": last_success or None})
        result.sort(key=lambda c: (not c["needs_action"], c["deadline"] or "9999", -c["new_count"], c["name"]))
        from .company_order import read_order, apply_order
        with self.store.connect() as db:
            order = read_order(self.store, db, profile)
        if order is not None:
            result = apply_order(result, order["company_ids"])
        return {"api_version": 1, "companies": result, "checked_at": now(),
                "order_version": order["version"] if order is not None else None,
                "order_state": "ready" if order is not None and order["version"] < 2147483647 else "unavailable"}

    def detail(self, company_id, profile="default"):
        snapshot = self.store.snapshot()
        records = snapshot["entities"]
        company = next((e for e in records if e["kind"] == "companies" and e["id"] == company_id), None)
        if not company:
            raise CareerError("company_missing", "企業が見つかりません。", 404)
        today = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d")
        offers = [e for e in records if e["kind"] == "offers" and e["data"].get("company_id") == company_id]
        offers = [dict(offer, data=dict(offer["data"], is_current=self.current_offer(offer, today))) for offer in offers]
        offers.sort(key=lambda offer: (not offer["data"]["is_current"], offer["data"].get("deadline") or "9999", offer["data"].get("title", "")))
        offer_ids = {o["id"] for o in offers}
        linked = [e for e in records if e["kind"] in {"tasks", "deadlines", "applications", "events", "company_statuses"}
                  and (e["data"].get("company_id") == company_id or e["data"].get("offer_id") in offer_ids)]
        documents = []
        with self.store.connect() as db:
            from .es_work import answer_summary
            answer_states = {offer["id"]: answer_summary(self.store, db, offer["id"]) for offer in offers}
            for entity in (e for e in records if e["kind"] == "documents"):
                context = document_context(db, entity["id"], entity["data"].get("version", 1))
                if context.get("company_id") == company_id or entity["data"].get("company_id") == company_id:
                    documents.append(dict(entity, data=dict(entity["data"], career_context=context)))
            cursor = db.execute("SELECT COALESCE(MAX(seq),0) FROM career_notice_feed WHERE company_id=?", (company_id,)).fetchone()[0]
            visible_evidence = [evidence for evidence in self.collection.evidence(company_id)
                                if evidence["kind"] != "portal" and self.visible_source(evidence, company_id, profile, db)]
        mail_page = self.mail.message_page(company_id)
        portal = self.mypage.view(company_id, profile)
        from .company_readiness import mypage_readiness
        return {"api_version": 1, "company": company, "offers": offers, "records": linked, "documents": documents,
                "mypage_readiness": mypage_readiness(portal),
                "links": [e for e in records if e["kind"] == "company_links" and e["data"].get("company_id") == company_id],
                "current_offer_count": sum(offer["data"]["is_current"] for offer in offers),
                "evidence": visible_evidence,
                "connections": [], "mypage": portal,
                "mail_coverage": "検索・未読の絞り込みは表示中の保存済みメールが対象です。未読は取得時点の状態です。",
                "mail": mail_page["messages"], "mail_next_cursor": mail_page["next_cursor"],
                "mail_senders": self.mail.sender_rules(company_id),
                "mail_status": {key: value for key, value in self.mail.status(company_id).items() if key != "cursor"},
                "thread_id": "company:" + company_id, "notice_cursor": cursor, "checked_at": now(),
                "answer_states": answer_states, "es_workspace": True}

    @staticmethod
    def visible_source(evidence, company_id, profile, db=None):
        from .mypage_bot import connection_id
        source = evidence.get("source_key", "")
        if source.startswith("mypage-bot:"):
            return source.startswith("mypage-bot:" + connection_id(company_id, profile) + ":")
        if source.startswith("mypage:"):
            if db is None:
                return False
            grant_id = source.split(":", 2)[1]
            return db.execute("SELECT 1 FROM career_collection_grants WHERE id=? AND company_id=? AND profile=?",
                              (grant_id, company_id, profile)).fetchone() is not None
        return True
