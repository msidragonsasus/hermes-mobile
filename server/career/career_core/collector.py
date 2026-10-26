"""Deterministic pilot collection. Only explicit grants reach read adapters."""
import re
from datetime import datetime

from .collection_store import CollectionStore, PILOT, PILOT_NAME, PILOT_OFFER_KEY, digest
from .gmail_reader import GmailReader, PILOT_SENDER
from .portal_reader import AxolReader
from .secret_store import SecretStore
from .store import CareerError, now


def deadline_from_text(text):
    # Preserve local wall time without inventing a timezone absent from the source.
    match = re.search(r"(?:応募締切日|応募締切|提出期限|提出締切|受付締切)[^\n]{0,30}\n?\s*"
                      r"(20\d{2})\s*[年/.-]\s*(\d{1,2})\s*[月/.-]\s*(\d{1,2})日?[^\n\d]{0,20}"
                      r"(?:(\d{1,2})\s*[:時]\s*(\d{2})分?)?", text)
    if not match:
        return None
    year, month, day, hour, minute = match.groups()
    try:
        value = datetime(int(year), int(month), int(day), int(hour or 0), int(minute or 0))
    except ValueError:
        return None
    return {"value": value.strftime("%Y-%m-%dT%H:%M") if hour else value.strftime("%Y-%m-%d"),
            "quote": match[0].strip(), "timezone": "Asia/Tokyo" if re.search(r"日本時間|JST", match[0]) else None}


class Collector:
    def __init__(self, store, *, secrets=None, mail_factory=GmailReader, portal_factory=AxolReader):
        self.store = store
        self.collection = CollectionStore(store)
        self.secrets = secrets
        self.mail_factory, self.portal_factory = mail_factory, portal_factory

    def collect(self, grant_id, channel, *, should_cancel=lambda: False):
        grant = self.collection.grant(grant_id)
        if grant["portal_id"] != PILOT or channel not in {"mail", "portal"}:
            raise CareerError("collector_unsupported", "この取得経路は未対応です。", 409)
        if should_cancel():
            return {"state": "cancelled"}
        previous = self.collection.status(grant_id, channel)
        if channel == "mail":
            with self.store.transaction() as db:
                self.collection.begin_mail_notices(db, grant["company_id"], grant["account_id"])
        self.collection.update_status(grant_id, channel, "running")
        def cancelled():
            if should_cancel():
                return True
            current = self.collection.grant(grant_id, require_enabled=False)
            return not current["enabled"] or current["account_id"] != grant["account_id"] or current["profile"] != grant["profile"]
        try:
            if channel == "mail":
                if not grant.get("account_id"):
                    raise CareerError("mail_connection_required", "Hermesの接続済みGmailを選んでください。", 409)
                reader = self.mail_factory(grant["account_id"], should_cancel=cancelled)
                try:
                    messages, cursor = reader.changes(previous.get("cursor"))
                finally:
                    reader.close()
                changed = 0
                with self.store.transaction() as db:
                    self.check_grant(db, grant, cancelled)
                    for message in messages:
                        is_offer = "冬季3days" in message["subject"].replace(" ", "") or "冬季3days" in message["text"][:1500].replace(" ", "")
                        offer_id = self.offer_id(grant) if is_offer else None
                        source_id, fresh = self.collection.save_evidence(db, source_key="gmail:" + grant["account_id"] + ":" + message["id"],
                            company_id=grant["company_id"], offer_id=offer_id, kind="mail", title=message["subject"],
                            locator="https://mail.google.com/mail/#all/" + message["id"], text=message["text"], occurred_at=message["received_at"])
                        if is_offer:
                            self.update_offer(db, grant, source_id, message["text"], channel="mail")
                        if fresh:
                            changed += 1
                            self.collection.notice(db, "source:" + source_id, grant["company_id"], offer_id,
                                                   "initial_source" if not previous.get("last_success_at") else "source_received", message["subject"])
                    state = "partial" if cursor.get("page_token") or cursor.get("pending_ids") else ("updated" if changed else "unchanged")
                    self.collection.update_status(grant_id, channel, state, cursor=cursor, success=True, db=db)
            else:
                secrets = self.secrets or SecretStore()
                page = self.portal_factory(secrets, should_cancel=cancelled).fetch()
                if not page.get("authenticated"):
                    raise CareerError("authentication_required", "本人向けマイページを確認できません。", 409)
                deadline = deadline_from_text(page["text"])
                proof = "\n\n".join(q["text"] + "\n" + (q.get("limit_evidence") or "文字数上限は未確認") for q in page.get("questions", []))
                if deadline:
                    proof += "\n" + deadline["quote"]
                if not proof.strip():
                    proof = "\n".join(line for line in page["text"].splitlines() if any(w in line for w in ("アンケート", "未回答", "受付", "エントリー", "提出")))[:4000]
                if not proof.strip():
                    raise CareerError("portal_structure_changed", "ESの設問や受付状況を確認できません。既存情報は保持しています。", 409)
                with self.store.transaction() as db:
                    self.check_grant(db, grant, cancelled)
                    source_id, fresh = self.collection.save_evidence(db, source_key="portal:" + grant["id"] + ":es",
                        company_id=grant["company_id"], offer_id=self.offer_id(grant), kind="portal", title="マイページのES案内",
                        locator=page["url"], text=proof)
                    self.update_offer(db, grant, source_id, page["text"], channel="portal", questions=page.get("questions", []), portal_state=page["state"])
                    if fresh:
                        self.collection.notice(db, "source:" + source_id, grant["company_id"], self.offer_id(grant),
                                               "initial_source" if not previous.get("last_success_at") else "source_changed", "マイページのES案内を確認しました")
                    state = page["state"] if page["state"] != "available" else ("updated" if fresh else "unchanged")
                    self.collection.update_status(grant_id, channel, state, success=page["state"] in {"available", "prerequisite_required"}, db=db)
                changed = int(fresh)
            with self.store.transaction() as db:
                self.collection.attention(db, "pilot:" + grant_id + ":" + channel, grant["company_id"], False,
                                          profile=grant["profile"], mail=channel == "mail")
            return {"state": state, "company_id": grant["company_id"], "changed": changed, "channel": channel}
        except Exception as error:
            if not isinstance(error, CareerError):
                error = CareerError("collector_unavailable", "取得結果を確認できませんでした。保存済み情報は保持しています。", 503)
            state = "auth_required" if error.code in {"authentication_required", "authentication_challenge", "mail_connection_required"} else "failed"
            with self.store.transaction() as db:
                try:
                    self.check_grant(db, grant, cancelled)
                except CareerError:
                    current = db.execute("SELECT account_id,profile FROM career_collection_grants WHERE id=?", (grant_id,)).fetchone()
                    if current and current["account_id"] == grant["account_id"] and current["profile"] == grant["profile"]:
                        self.collection.update_status(grant_id, channel, "cancelled", db=db)
                    return {"state": "cancelled", "channel": channel}
                self.collection.update_status(grant_id, channel, state, reason=error.code, db=db)
                if state == "auth_required":
                    self.collection.attention(db, "pilot:" + grant_id + ":" + channel, grant["company_id"], True,
                                              profile=grant["profile"], mail=channel == "mail")
                self.collection.notice(db, "failure:" + grant_id + ":" + channel + ":" + error.code, grant["company_id"], None, state,
                                       "Gmailへの接続を確認してください" if channel == "mail" else "マイページへの接続を確認してください")
            raise error from None

    def check_grant(self, db, grant, should_cancel):
        current = db.execute("SELECT * FROM career_collection_grants WHERE id=?", (grant["id"],)).fetchone()
        if should_cancel() or not current or not current["enabled"] or current["account_id"] != grant["account_id"]:
            raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)

    @staticmethod
    def offer_id(grant):
        return "offer:" + digest(grant["company_id"], PILOT, PILOT_OFFER_KEY)

    def update_offer(self, db, grant, evidence_id, text, *, channel, questions=None, portal_state=None):
        offer_id = self.offer_id(grant)
        old = self.store.entity(db, "offers", offer_id)
        data = dict(old["data"] if old else {"company_id": grant["company_id"], "title": "冬季3daysプログラム", "target_year": None},
                    collection_managed=True, observed_at=now())
        references = list(data.get("evidence_ids", []))
        if evidence_id not in references:
            references.append(evidence_id)
        data["evidence_ids"] = references
        deadline = deadline_from_text(text)
        if deadline:
            candidates = list(data.get("deadline_candidates", []))
            observation = dict(deadline, evidence_id=evidence_id, channel=channel)
            if observation not in candidates:
                candidates.append(observation)
            data["deadline_candidates"] = candidates
            different = {c["value"] for c in candidates}
            if len(different) == 1:
                data.update(deadline=deadline["value"], deadline_verification="portal" if any(c["channel"] == "portal" for c in candidates) else "mail")
            else:
                data["deadline_verification"] = "conflict"
        if "アンケート" in text and any(w in text for w in ("事前", "未回答", "回答が必要", "回答後")):
            data["prerequisite"] = "ES提出前にアンケート回答が必要です。回答状況はマイページで確認してください。"
        if "SPI" in text:
            data["completion_requirement"] = "ES提出とSPI受検の両方が必要です。本人の完了状況は未確認です。"
        if channel == "portal":
            data["portal_state"] = portal_state
            if questions:
                data["questions"] = [dict(q, id="question:" + digest(offer_id, q["field"]),
                                           version=digest(q["text"], q.get("max_characters"), q.get("input_kind"), q.get("required"), q.get("required_known"), q.get("no_line_breaks"), q.get("options")), evidence_id=evidence_id) for q in questions]
                data["questions_verified_at"] = now()
            elif data.get("questions"):
                data["questions_stale"] = True
            if questions:
                data["questions_stale"] = False
        # Stable program key across sources and repeated fetches; no new application is fabricated.
        self.store.put(db, "offers", offer_id, old["version"] if old else 0, data, dirty=False)
