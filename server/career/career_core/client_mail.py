"""Validated import for Gmail messages read by the Android program.

The Google access token never leaves Android. This module accepts only normalized
message fields, re-checks exact sender assignments, sanitizes stored text through
MailStore/CollectionStore, and does not expose collection to the coach tools.
"""
from datetime import datetime
import re

from .collection_store import CollectionStore
from .gmail_reader import account_reference
from .mail_collector import MailCollector
from .mail_store import MailStore, normalize_address
from .store import CareerError


MESSAGE_ID = re.compile(r"[a-fA-F0-9]{1,100}")
FIELDS = {"id", "thread_id", "sender_address", "from_text", "subject", "snippet", "body", "locator",
          "received_at", "gmail_unread"}


class ClientMailImporter:
    def __init__(self, store):
        self.store = store
        self.mail = MailStore(store)
        self.collection = CollectionStore(store)

    def ingest(self, payload):
        if not isinstance(payload, dict) or set(payload) not in ({"account_email", "messages"}, {"account_email", "messages", "company_id"}):
            raise ValueError("Invalid client mail payload")
        account_email = normalize_address(payload["account_email"])
        messages = payload["messages"]
        if not isinstance(messages, list) or len(messages) > 100:
            raise ValueError("Invalid client mail count")
        account_id = account_reference(account_email)
        assignments = self.mail.active_assignments()
        requested_company = payload.get("company_id")
        if requested_company is not None and (not isinstance(requested_company, str) or not requested_company):
            raise ValueError("Invalid company")
        if requested_company is not None:
            assignments = {sender: companies for sender, companies in assignments.items() if requested_company in companies}
        company_ids = set()
        previous = self.mail.status()
        validated = [self._message(value, assignments) for value in messages]
        changed = 0
        collector = MailCollector(self.store)
        with self.store.transaction() as db:
            current_assignments = self.mail.active_assignments(db)
            targets = {c for message in validated for c in current_assignments.get(message["sender_address"], ())}
            if requested_company is not None and any(requested_company in companies for companies in current_assignments.values()):
                targets.add(requested_company)
            for company in targets:
                self.collection.begin_mail_notices(db, company, account_id)
            company_ids.update(targets)
            for message in validated:
                if requested_company is not None and requested_company not in current_assignments.get(message["sender_address"], ()):
                    raise CareerError("mail_source_unapproved", "送信元の設定が変わりました。企業画面を更新してください。", 409)
                for company_id in sorted(current_assignments.get(message["sender_address"], ())):
                    company_ids.add(company_id)
                    fresh = self.mail.save_message(db, account_id, company_id, message)
                    evidence_id, evidence_fresh = self.collection.save_evidence(
                        db, source_key="gmail:" + account_id + ":" + message["id"], company_id=company_id,
                        kind="mail", title=message["subject"], locator=message["locator"], text=message["text"],
                        occurred_at=message["received_at"])
                    if fresh:
                        changed += 1
                        self.collection.notice(db, "source:" + evidence_id, company_id, None,
                                               "initial_source" if not previous.get("last_success_at") else "source_received",
                                               message["subject"])
                    if evidence_fresh:
                        collector._update_pilot_offer(db, company_id, evidence_id, message)
            state = "updated" if changed else "unchanged"
            # Client chunks cannot prove account-wide completion or advance a server watermark.
            self.store.set_meta(db, "mail:client-import:" + account_id, {"state": state, "changed": changed})
            for company in company_ids:
                self.store.set_meta(db, "mail:client-import:" + account_id + ":" + company, {"state": state})
            for company in company_ids:
                self.collection.attention(db, "mail:" + account_id + ":" + company, company, False, mail=True)
        return {"state": state, "changed": changed, "companies": len(company_ids), "account_id": account_id}

    def reuse(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"account_email", "company_id", "message_ids"}:
            raise ValueError("Invalid cache request")
        account = account_reference(normalize_address(payload["account_email"]))
        company_id, ids = payload["company_id"], payload["message_ids"]
        if not isinstance(company_id, str) or not company_id or not isinstance(ids, list) or len(ids) > 100:
            raise ValueError("Invalid cache request")
        if any(not isinstance(value, str) or not MESSAGE_ID.fullmatch(value) for value in ids):
            raise ValueError("Invalid message ID")
        allowed = {sender for sender, companies in self.mail.active_assignments().items() if company_id in companies}
        messages = []
        with self.store.connect() as db:
            for identity in dict.fromkeys(ids):
                row = db.execute("SELECT * FROM career_mail_messages WHERE account_id=? AND message_id=?",
                                 (account, identity)).fetchone()
                if row and row["sender_address"] in allowed:
                    messages.append({"id": row["message_id"], **{field: row[field] for field in
                        ("thread_id", "sender_address", "from_text", "subject", "snippet", "body", "locator", "received_at")},
                        "gmail_unread": bool(row["gmail_unread"])})
        if messages:
            self.ingest({"account_email": payload["account_email"], "company_id": company_id, "messages": messages})
        return {"cached_ids": [message["id"] for message in messages]}

    @staticmethod
    def _message(value, assignments):
        if not isinstance(value, dict) or set(value) != FIELDS:
            raise ValueError("Invalid client mail message")
        message_id = value["id"]
        thread_id = value["thread_id"]
        sender = normalize_address(value["sender_address"])
        if sender not in assignments or not isinstance(message_id, str) or not MESSAGE_ID.fullmatch(message_id):
            raise CareerError("mail_source_unapproved", "登録していない送信元のメールは保存できません。", 403)
        if not isinstance(thread_id, str) or not MESSAGE_ID.fullmatch(thread_id):
            raise ValueError("Invalid Gmail thread")
        locator = value["locator"]
        if locator != "https://mail.google.com/mail/#all/" + message_id:
            raise ValueError("Invalid Gmail locator")
        received_at = value["received_at"]
        if not isinstance(received_at, str) or len(received_at) > 64:
            raise ValueError("Invalid received time")
        datetime.fromisoformat(received_at.replace("Z", "+00:00"))
        limits = {"from_text": 300, "subject": 300, "snippet": 500, "body": 30000}
        for key, maximum in limits.items():
            if not isinstance(value[key], str) or len(value[key]) > maximum:
                raise ValueError("Invalid mail text")
        if type(value["gmail_unread"]) is not bool:
            raise ValueError("Invalid unread state")
        return {"id": message_id, "thread_id": thread_id, "sender_address": sender,
                "from": value["from_text"], "subject": value["subject"], "snippet": value["snippet"],
                "text": value["body"], "locator": locator, "received_at": received_at,
                "gmail_unread": value["gmail_unread"]}
