"""Bounded official-page research; Codex OAuth only, no paid fallback."""
import hashlib
import ipaddress
import socket
import re
from datetime import date, datetime
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit

import httpx

from .importing import public_url
from .store import CareerError, encoded, now


class PageText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.text, self.links, self.hidden = [], [], 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript"}:
            self.hidden += 1
        if tag == "a":
            self.links.extend(value for key, value in attrs if key == "href")

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript"}:
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, text):
        if not self.hidden and text.strip():
            self.text.append(text.strip())


def fetch_page(url):
    url = public_url(url)
    if not url:
        raise CareerError("invalid_official_url", "公式HTTPS URLを確認してください。")
    host = urlsplit(url).hostname
    try:
        addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(entry[4][0]).is_global for entry in addresses):
            raise CareerError("unsafe_url", "外部の公式サイトを指定してください。")
        with httpx.Client(timeout=25, follow_redirects=False, trust_env=False) as client:
            with client.stream("GET", url, headers={"User-Agent": "HermesCareer/0.1 personal recruiting research"}) as response:
                if response.status_code != 200 or "text/html" not in response.headers.get("content-type", ""):
                    raise CareerError("page_unavailable", "公式ページを取得できませんでした。", 503)
                content = bytearray()
                for chunk in response.iter_bytes():
                    content.extend(chunk)
                    if len(content) > 2 * 1024 * 1024:
                        raise CareerError("page_too_large", "ページが取得上限を超えています。")
                parser = PageText()
                parser.feed(bytes(content).decode(response.encoding or "utf-8", errors="replace"))
                return {"url": url, "text": "\n".join(parser.text)[:60000], "links": parser.links}
    except (httpx.HTTPError, OSError):
        raise CareerError("page_unavailable", "公式ページに接続できません。既存情報は保持しています。", 503) from None


class CodexExtractor:
    """Compatibility entry point; all extraction now runs through Hermes AIAgent."""
    def __init__(self):
        self.execution = None

    def extract(self, company, pages):
        from .coach_runtime import HermesExtractor
        from .coach_execution import default_execution, oauth_credentials
        # Old direct callers get one frozen setting per extractor, never per company.
        oauth_credentials()
        if self.execution is None:
            self.execution = default_execution()
        return HermesExtractor(self.execution).extract(company, pages)


def validate_offer(offer, pages):
    if not isinstance(offer, dict):
        raise CareerError("invalid_research", "募集情報の形式を確認できません。")
    sources = {p["url"]: p["text"] for p in pages}
    source = sources.get(offer.get("source_url"))
    if not source or not isinstance(offer.get("title"), str) or not offer["title"].strip():
        raise CareerError("invalid_evidence", "募集の公式出典を確認できません。")
    for field, evidence in (("target_year", "year_evidence"), ("deadline", "deadline_evidence")):
        if offer.get(field) is not None and (not isinstance(offer.get(evidence), str) or not offer[evidence] or offer[evidence] not in source):
            raise CareerError("invalid_evidence", "年度・締切の根拠が公式ページに見つかりません。")
    year = offer.get("target_year")
    if year is not None and (type(year) is not int or not 2000 <= year <= 2100 or str(year) not in offer["year_evidence"]):
        raise CareerError("invalid_year", "対象年度とその根拠が一致しません。")
    deadline = offer.get("deadline")
    if deadline is not None:
        if not isinstance(deadline, str):
            raise CareerError("invalid_deadline", "締切の形式が不正です。")
        evidence = offer["deadline_evidence"]
        explicit_dates = set()
        for match in re.finditer(r"(20\d{2})[年/.-](\d{1,2})[月/.-](\d{1,2})日?", evidence):
            try:
                explicit_dates.add(date(*map(int, match.groups())).isoformat())
            except ValueError:
                pass
        if deadline[:10] not in explicit_dates:
            raise CareerError("invalid_deadline", "締切年月日の根拠を確認できません。")
        if len(deadline) > 10:
            try:
                parsed = datetime.fromisoformat(deadline)
            except ValueError:
                raise CareerError("invalid_deadline", "締切時刻の形式が不正です。") from None
            clocks = [(int(h), int(m or 0)) for h, m in re.findall(r"(\d{1,2})[:時](\d{2})?分?", evidence)]
            zone_explicit = any(zone in evidence for zone in ("JST", "日本時間", "+09:00"))
            if (parsed.hour, parsed.minute) not in clocks or not zone_explicit or not deadline.endswith("+09:00"):
                raise CareerError("invalid_deadline_time", "締切時刻・時間帯の根拠を確認できません。")
    result = {key: offer.get(key) for key in ("title", "source_url", "target_year", "year_evidence", "deadline", "deadline_evidence")}
    result["requirements"] = []
    requirements = offer.get("requirements", [])
    if not isinstance(requirements, list):
        raise CareerError("invalid_research", "提出物の形式を確認できません。")
    for requirement in requirements:
        if not isinstance(requirement, dict) or not isinstance(requirement.get("title"), str) or requirement["title"] not in source:
            continue
        result["requirements"].append({"title": requirement["title"], "deadline": None})
    return result


def research(store, extractor=None, fetch=fetch_page, *, should_yield=lambda: False, should_cancel=lambda: False):
    # OS advisory lock covers manual and timer invocation; process exit releases it.
    from .locking import job_lock
    with job_lock(store.directory / "research.lock") as acquired:
        if not acquired:
            return {"state": "running"}
        extractor = extractor or CodexExtractor()
        snapshot = store.snapshot()
        companies = [e for e in snapshot["entities"] if e["kind"] == "companies"]
        with store.connect() as db:
            checkpoint = store.meta(db, "research_checkpoint", [])
        if not checkpoint:
            checkpoint = [e["id"] for e in companies]
        by_id = {e["id"]: e for e in companies}
        failures = 0
        while checkpoint:
            if should_cancel():
                return {"state": "cancelled"}
            if should_yield():
                with store.transaction() as db:
                    store.set_meta(db, "research_checkpoint", checkpoint)
                return {"state": "yielded"}
            company_id = checkpoint[0]
            company = by_id.get(company_id)
            if not company:
                checkpoint.pop(0)
                continue
            try:
                urls = company["data"].get("official_urls", [])
                if not urls:
                    raise CareerError("official_url_missing", "調査する公式採用URLを登録してください。")
                pages = [fetch(url) for url in urls[:4]]
                # Follow bounded recruiting links on the same official origin.
                for page in list(pages):
                    for link in page.get("links", []):
                        target = public_url(urljoin(page["url"], link))
                        if target and urlsplit(target).netloc == urlsplit(page["url"]).netloc and any(
                            word in target.lower() for word in ("recruit", "intern", "graduate", "entry")):
                            if len(pages) < 8 and target not in {p["url"] for p in pages}:
                                try:
                                    pages.append(fetch(target))
                                except CareerError:
                                    pass
                if should_cancel():
                    return {"state": "cancelled"}
                extracted = extractor.extract(company["data"], pages)
                if not isinstance(extracted, list) or len(extracted) > 100:
                    raise CareerError("invalid_research", "募集情報の形式・件数を確認できません。")
                offers = [validate_offer(o, pages) for o in extracted]
                with store.transaction() as db:
                    if should_cancel():
                        return {"state": "cancelled"}
                    for offer in offers:
                        offer_id = hashlib.sha256(encoded([company_id, offer["title"], offer["source_url"], offer["target_year"]]).encode()).hexdigest()[:32]
                        old = store.entity(db, "offers", offer_id)
                        data = dict(offer, company_id=company_id, observed_at=now(), decision=old["data"].get("decision") if old else None)
                        if old and old["data"].get("deadline") and data.get("deadline") is None:
                            data.update(deadline=old["data"]["deadline"], deadline_verification="unconfirmed",
                                        previous_deadline_evidence=old["data"].get("deadline_evidence"))
                        relevant = {k: v for k, v in data.items() if k != "observed_at"}
                        prior = {k: v for k, v in old["data"].items() if k != "observed_at"} if old else None
                        store.put(db, "offers", offer_id, old["version"] if old else 0, data, dirty=False)
                        if relevant != prior:
                            store.notice(db, "offer_changed" if old else "new_offer", {"offer_id": offer_id, "before": prior, "after": relevant})
                            if old and data.get("deadline") != old["data"].get("deadline"):
                                application_id = "offer:" + offer_id
                                for linked_id, kind in ((application_id, "applications"), (application_id + ":deadline", "tasks")):
                                    linked = store.entity(db, kind, linked_id)
                                    if linked and linked["data"].get("deadline_source") == "offer" and linked["data"].get("deadline") == old["data"].get("deadline"):
                                        store.put(db, kind, linked_id, linked["version"], dict(linked["data"], deadline=data["deadline"]), dirty=False)
            except CareerError as error:
                failures += 1
                paused = error.code in {"quota_paused", "research_paused", "oauth_unavailable", "paid_fallback_blocked", "model_not_configured", "oauth_request_failed"}
                with store.transaction() as db:
                    status = {"state": "paused" if paused else "failed", "reason": error.code, "company_id": company_id, "checked_at": now()}
                    store.set_meta(db, "research_status", status)
                    store.notice(db, "research_failed", status, "research-failure:" + company_id + ":" + error.code)
                    if paused:
                        store.set_meta(db, "research_checkpoint", checkpoint)
                        return status
            checkpoint.pop(0)
            with store.transaction() as db:
                store.set_meta(db, "research_checkpoint", checkpoint)
        status = {"state": "completed_with_failures" if failures else "completed", "checked_at": now(), "company_count": len(companies), "failure_count": failures}
        with store.transaction() as db:
            store.set_meta(db, "research_status", status)
        return status
