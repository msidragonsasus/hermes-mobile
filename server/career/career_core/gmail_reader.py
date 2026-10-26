"""Gmail API adapter with a fixed read surface; never changes labels or messages."""
import base64
import hashlib
import re
from datetime import datetime, timedelta, timezone
from email.utils import parseaddr

from .collection_store import clean_excerpt
from .mail_store import normalize_address
from .store import CareerError

API = "https://gmail.googleapis.com/gmail/v1/users/me/"
SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
PILOT_SENDER = "nipponsteel-eng@mail.axol.jp"


def account_reference(address):
    return "gmail-" + hashlib.sha256(address.strip().lower().encode()).hexdigest()[:32]


def text_parts(payload):
    parts = []
    if payload.get("mimeType") == "text/plain" and payload.get("body", {}).get("data"):
        try:
            parts.append(base64.urlsafe_b64decode(payload["body"]["data"] + "===").decode("utf-8", errors="replace"))
        except (ValueError, TypeError):
            raise CareerError("mail_unreadable", "メール本文の形式を確認できません。", 503) from None
    for child in payload.get("parts", []):
        parts.extend(text_parts(child))
        if parts and payload.get("mimeType") == "multipart/alternative":
            break
    if not parts and payload.get("mimeType") == "text/html" and payload.get("body", {}).get("data"):
        from html.parser import HTMLParser
        class MailText(HTMLParser):
            def __init__(self):
                super().__init__()
                self.text, self.hidden = [], 0
            def handle_starttag(self, tag, attrs):
                if tag in {"script", "style", "noscript"}:
                    self.hidden += 1
            def handle_endtag(self, tag):
                if tag in {"script", "style", "noscript"}:
                    self.hidden = max(0, self.hidden - 1)
            def handle_data(self, data):
                if not self.hidden and data.strip():
                    self.text.append(data)
        parser = MailText()
        try:
            parser.feed(base64.urlsafe_b64decode(payload["body"]["data"] + "===").decode("utf-8", errors="replace"))
            parts.append("\n".join(parser.text))
        except (ValueError, TypeError):
            raise CareerError("mail_unreadable", "メール本文の形式を確認できません。", 503) from None
    return parts


class GmailReader:
    def __init__(self, account_id, *, api=None, should_cancel=lambda: False):
        from .workspace_reader import WorkspaceApi
        self.account_id = account_id
        self.calls = 0
        self.should_cancel = should_cancel
        self.api = api or WorkspaceApi(should_cancel=should_cancel, prefer_python=True, account_id=account_id)

    def close(self):
        self.api.close()

    def get(self, resource, **params):
        if self.should_cancel():
            raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
        # Internal callers can only select these methods. No caller-supplied URL or HTTP verb.
        if resource not in {"profile", "messages", "history"} and not re.fullmatch(r"messages/[a-fA-F0-9]{1,100}", resource):
            raise CareerError("mail_operation_denied", "このメール操作は利用できません。", 403)
        self.calls += 1
        return self.api.get(resource, **params)

    def profile(self):
        profile = self.get("profile")
        if account_reference(profile.get("emailAddress", "")) != self.account_id:
            raise CareerError("mail_account_mismatch", "接続したGmailが選択したアカウントと異なります。", 409)
        return profile

    def search_ids(self, query, page_token=None):
        params = {"q": query, "maxResults": 100}
        if page_token:
            params["pageToken"] = page_token
        return self.get("messages", **params)

    def history_ids(self, history_id, page_token=None):
        params = {"startHistoryId": history_id, "historyTypes": "messageAdded", "maxResults": 100}
        if page_token:
            params["pageToken"] = page_token
        return self.get("history", **params)

    def read_ids(self, ids, senders, cached, tuning):
        """Return successful IDs independently; failed items remain in the durable queue."""
        from .mail_transport import MailReadError
        messages, done, errors = [], [], []
        pending = []
        for identity in ids:
            if identity in cached:
                if cached[identity]["sender_address"] in senders:
                    messages.append(cached[identity])
                done.append(identity)
            else:
                pending.append(identity)

        def read(identities, format):
            if not identities:
                return []
            if self.should_cancel():
                raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
            if hasattr(self.api, "get_many"):
                self.calls += len(identities)
                return self.api.get_many(identities, format=format,
                    batch_size=tuning["batch_size"], concurrency=tuning["concurrency"])
            values = []
            for identity in identities:
                try:
                    params = {"format": format}
                    if format == "metadata":
                        params["metadataHeaders"] = "From"
                    values.append({"id": identity, "data": self.get("messages/" + identity, **params)})
                except CareerError as error:
                    values.append({"id": identity, "error": error.code, "retry_after": getattr(error, "retry_after", 0)})
            return values

        def failure(item):
            if not item.get("error"):
                return False
            if item["error"] == "mail_message_missing":
                done.append(item["id"])
            else:
                errors.append(MailReadError(item["error"], item.get("retry_after", 0),
                                            http_status=item.get("http_status"), limit_kind=item.get("limit_kind")))
            return True

        approved = {}
        for item in read(pending, "metadata"):
            if failure(item):
                continue
            value = item["data"]
            sender = next((h["value"] for h in value.get("payload", {}).get("headers", []) if h["name"].lower() == "from"), "")
            address = parseaddr(sender)[1].lower()
            if address not in senders or set(value.get("labelIds", [])) & {"SPAM", "TRASH"}:
                done.append(item["id"])
            else:
                approved[item["id"]] = address
        if any(error.code in {'mail_rate_limited', 'mail_daily_limit'} for error in errors):
            return messages, set(done), errors
        for item in read(list(approved), "full"):
            if failure(item):
                continue
            value = item["data"]
            headers = {h["name"].lower(): h["value"] for h in value.get("payload", {}).get("headers", [])}
            address = parseaddr(headers.get("from", ""))[1].lower()
            if address != approved[item["id"]] or address not in senders:
                errors.append(MailReadError("mail_source_changed"))
                continue
            if set(value.get("labelIds", [])) & {"SPAM", "TRASH"}:
                done.append(item["id"])
                continue
            try:
                message = {"id": item["id"], "thread_id": value.get("threadId", item["id"]),
                    "subject": clean_excerpt(headers.get("subject", ""), 300), "from": clean_excerpt(headers.get("from", ""), 300),
                    "sender_address": address, "snippet": clean_excerpt(value.get("snippet", ""), 500),
                    "text": clean_excerpt("\n".join(text_parts(value.get("payload", {}))), 30000),
                    "locator": "https://mail.google.com/mail/#all/" + item["id"],
                    "gmail_unread": "UNREAD" in value.get("labelIds", []),
                    "received_at": datetime.fromtimestamp(int(value["internalDate"]) / 1000, timezone.utc).isoformat()}
            except (CareerError, ValueError, KeyError, TypeError, OverflowError):
                errors.append(MailReadError("mail_unreadable"))
                continue
            messages.append(message)
            done.append(item["id"])
        return messages, set(done), errors

    def changes(self, cursor=None, *, max_messages=100, sender_addresses=None):
        senders = {normalize_address(value) for value in (sender_addresses or [PILOT_SENDER])}
        if not senders or len(senders) > 100:
            raise CareerError("mail_sender_limit", "仕分ける送信元は1件から100件までにしてください。", 409)
        # Capture a watermark BEFORE listing. Messages arriving during the scan are not skipped.
        current = self.profile()
        cursor = dict(cursor or {})
        ids = []
        if cursor.get("pending_ids"):
            ids = cursor["pending_ids"]
            next_cursor = cursor["after_pending"]
        elif cursor.get("history_id") and cursor.get("mode") != "initial":
            try:
                params = {"startHistoryId": cursor["history_id"], "historyTypes": "messageAdded", "maxResults": max_messages}
                if cursor.get("page_token"):
                    params["pageToken"] = cursor["page_token"]
                result = self.get("history", **params)
                for entry in result.get("history", []):
                    ids.extend(m["message"]["id"] for m in entry.get("messagesAdded", []))
                if result.get("nextPageToken"):
                    # Save the page token with its original watermark; next call continues this exact delta.
                    next_cursor = {"history_id": cursor["history_id"], "page_token": result["nextPageToken"], "mode": "history"}
                else:
                    next_cursor = {"history_id": result.get("historyId", current["historyId"])}
            except CareerError as error:
                if error.code != "mail_cursor_expired":
                    raise
                cursor = {}
        if not cursor.get("pending_ids") and (not cursor.get("history_id") or cursor.get("mode") == "initial"):
            after = cursor.get("after") or (datetime.now(timezone.utc) - timedelta(days=90)).strftime("%Y/%m/%d")
            query = ("from:" + next(iter(senders))) if len(senders) == 1 else "{" + " ".join("from:" + address for address in sorted(senders)) + "}"
            params = {"q": f"{query} after:{after} -in:spam -in:trash", "maxResults": max_messages}
            if cursor.get("page_token"):
                params["pageToken"] = cursor["page_token"]
            result = self.get("messages", **params)
            ids = [m["id"] for m in result.get("messages", [])]
            watermark = cursor.get("baseline_history_id", current["historyId"])
            next_cursor = ({"mode": "initial", "after": after, "page_token": result["nextPageToken"], "baseline_history_id": watermark}
                           if result.get("nextPageToken") else {"history_id": watermark})
        ids = list(dict.fromkeys(ids))
        if len(ids) > max_messages:
            next_cursor = {"pending_ids": ids[max_messages:], "after_pending": next_cursor}
        messages = []
        for message_id in ids[:max_messages]:
            # Metadata first: a mailbox-wide history event never grants body access to unrelated senders.
            meta = self.get("messages/" + message_id, format="metadata", metadataHeaders="From")
            sender = next((h["value"] for h in meta.get("payload", {}).get("headers", []) if h["name"].lower() == "from"), "")
            sender_address = parseaddr(sender)[1].lower()
            if sender_address not in senders:
                continue
            if set(meta.get("labelIds", [])) & {"SPAM", "TRASH"}:
                continue
            value = self.get("messages/" + message_id, format="full")
            headers = {h["name"].lower(): h["value"] for h in value.get("payload", {}).get("headers", [])}
            full_sender = parseaddr(headers.get("from", ""))[1].lower()
            if full_sender != sender_address or full_sender not in senders:
                raise CareerError("mail_source_changed", "メールの送信元を再確認してください。", 409)
            text = clean_excerpt("\n".join(text_parts(value.get("payload", {}))), 30000)
            messages.append({"id": message_id, "thread_id": value.get("threadId", message_id),
                             "subject": clean_excerpt(headers.get("subject", ""), 300), "from": clean_excerpt(headers.get("from", ""), 300),
                             "sender_address": full_sender, "snippet": clean_excerpt(value.get("snippet", ""), 500), "text": text,
                             "locator": "https://mail.google.com/mail/#all/" + message_id,
                             "gmail_unread": "UNREAD" in value.get("labelIds", []),
                             "received_at": datetime.fromtimestamp(int(value["internalDate"]) / 1000, timezone.utc).isoformat()})
        return messages, next_cursor
