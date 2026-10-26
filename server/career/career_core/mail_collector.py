"""Program-owned Gmail collection for each company's configured senders."""
from .collection_store import CollectionStore
from .gmail_reader import GmailReader
from .mail_store import MailStore
from .store import CareerError


class MailCollector:
    def __init__(self, store, *, reader_factory=GmailReader):
        self.store = store
        self.mail = MailStore(store)
        self.collection = CollectionStore(store)
        self.reader_factory = reader_factory

    def collect(self, *, should_cancel=lambda: False):
        from .mail_sync import MailSync
        return MailSync(self).step(should_cancel)

    def persist(self, db, account, messages, *, notify=True):
        assignments = self.mail.active_assignments(db)
        changed = 0
        for message in messages:
            for company_id in sorted(assignments.get(message["sender_address"], ())):
                self.collection.begin_mail_notices(db, company_id, account)
                fresh = self.mail.save_message(db, account, company_id, message)
                evidence_id, evidence_fresh = self.collection.save_evidence(
                    db, source_key="gmail:" + account + ":" + message["id"],
                    company_id=company_id, kind="mail", title=message["subject"], locator=message["locator"],
                    text=message["text"], occurred_at=message["received_at"])
                if fresh:
                    changed += 1
                    self.collection.notice(db, "source:" + evidence_id, company_id, None,
                                           "source_received" if notify else "initial_source", message["subject"])
                if evidence_fresh:
                    self._update_pilot_offer(db, company_id, evidence_id, message)
                self.collection.attention(db, "mail:" + account + ":" + company_id, company_id, False, mail=True)
        return changed

    def _update_pilot_offer(self, db, company_id, evidence_id, message):
        from .collection_store import PILOT
        grant = next((item for item in self.collection.grants() if item["id"] == PILOT and item["company_id"] == company_id), None)
        if not grant:
            return
        compact = (message["subject"] + " " + message["text"][:1500]).replace(" ", "")
        if "冬季3days" not in compact:
            return
        from .collector import Collector
        Collector(self.store).update_offer(db, grant, evidence_id, message["text"], channel="mail")
