"""Programmatic retrieval. Credentials never enter the plan or model context."""
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib.parse import quote, urljoin, urlsplit

from .store import PlanError

ASSIGNMENT = r"(?:course_[0-9]+_)?(?:report|query|drill|survey|project)_[0-9]+"


def mail_account(home, settings):
    email = settings["email"]
    if not email:
        raise PlanError("mail_not_configured", 409)
    plugin = str(Path(home) / "plugins" / "career")
    if Path(plugin).is_dir() and plugin not in sys.path:
        sys.path.insert(0, plugin)
    from career_core.google_accounts import GoogleAccounts
    accounts = [a for a in GoogleAccounts(home).accounts() if a["email"].lower() == email]
    if len(accounts) != 1 or accounts[0]["state"] != "connected":
        raise PlanError("mail_auth_required", 409)
    return accounts[0]["id"]


def search_mail(home, settings, query, maximum=20):
    identity = mail_account(home, settings)
    from career_core.google_connected_accounts import read_account
    try:
        result = read_account(home, dict(account_id=identity, action="search", query=query, max_results=maximum))["result"]
    except json.JSONDecodeError as error:
        # The installed Python formatter emits this exact non-JSON sentinel
        # for zero matches. Any other parsing failure remains a failed read.
        if error.doc.strip() != "No messages found.":
            raise
        result = []
    if isinstance(result, list):
        messages, more = result, False
    elif isinstance(result, dict) and ("messages" in result or result.get("resultSizeEstimate") == 0):
        messages, more = result.get("messages", []), bool(result.get("nextPageToken"))
    else:
        raise ValueError()
    if not isinstance(messages, list):
        raise ValueError()
    if not all(isinstance(message, dict) for message in messages):
        raise ValueError()
    # A successful query proves only the scope searched, never a full mailbox reconciliation.
    return dict(messages=messages[:maximum], query=query, scope="query", has_more=more or len(messages) >= maximum), "partial" if len(messages) >= maximum or more else "checked"


def read_mail(home, settings, message):
    identity = mail_account(home, settings)
    from career_core.google_connected_accounts import read_account
    full = read_account(home, dict(account_id=identity, action="get", message_id=message))["result"]
    text = json.dumps(full, ensure_ascii=False)
    title = full.get("subject", "メール") if isinstance(full, dict) else "メール"
    return dict(id=f"gmail:{identity}:{message}", source="gmail", title=str(title)[:150],
                url=f"https://mail.google.com/mail/u/?authuser={quote(settings['email'])}#all/{quote(message)}",
                text=text[:20000]), "partial" if len(text) > 20000 else "updated"


def manaba_read_url(value, root):
    parsed, origin = urlsplit(value), urlsplit(root)
    name = parsed.path.rsplit("/", 1)[-1]
    return (parsed.scheme == "https" and parsed.netloc == origin.netloc and not parsed.query
            and not parsed.fragment and not parsed.username and (parsed.path == origin.path or
                bool(re.fullmatch(ASSIGNMENT + r"|home_library_(?:query|report|survey)", name))))


def manaba(home, settings):
    url = settings["manaba_url"]
    if not url:
        return [], "not_configured"
    state = Path(home) / "today_home" / "manaba-session.json"
    if state.is_symlink() or not state.is_file():
        return [], "auth_required"
    if os.name != "nt" and state.stat().st_mode & 0o077:
        return [], "auth_required"
    from playwright.sync_api import sync_playwright
    def allowed(value):
        return manaba_read_url(value, url)

    # Start at the consolidated unsubmitted-assignment list, not a crawl of news.
    assignments = urljoin(url, "/ct/home_library_query")
    records, visited, queue, partial = [], set(), [assignments], False
    end = time.monotonic() + 120
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, chromium_sandbox=True)
        try:
            context = browser.new_context(storage_state=str(state), java_script_enabled=False, accept_downloads=False,
                                          service_workers="block")
            # Read only the consolidated list. Opening a timed quiz itself can
            # start an attempt, so assignment/detail links are never followed.
            context.route("**/*", lambda route: route.continue_() if route.request.method == "GET" and
                          route.request.resource_type == "document" and route.request.url == assignments else route.abort())
            page = context.new_page()
            while queue and len(visited) < 20 and time.monotonic() < end:
                target = queue.pop(0)
                if target in visited:
                    continue
                visited.add(target)
                response = page.goto(target, wait_until="domcontentloaded", timeout=20000)
                if (not response or response.status >= 400 or not allowed(page.url)
                        or page.locator('input[type="password"]').count()):
                    return records, "partial" if records else "auth_required"
                text = page.locator("body").inner_text(timeout=5000)
                if not text.strip() or "manaba" not in (page.title() + text).lower():
                    return records, "partial" if records else "auth_required"
                if "未提出課題" not in text:
                    return [], "partial"
                records.append(dict(id="manaba:list:" + hashlib.sha256(target.encode()).hexdigest()[:32], source="manaba",
                                    title="未提出課題一覧", url=target, text=text[:30000]))
                partial = partial or len(text) > 30000
                if "非表示に設定中" in text:
                    partial = True
                # Original rows retain course names, completion state and deadline text.
                rows = page.locator("tr").all_inner_texts()
                if not rows:
                    return records, "partial"
                partial = partial or len(rows) > 100 or any(len(row) > 6000 for row in rows)
                for index, content in enumerate(rows[:100]):
                    if not content.strip():
                        continue
                    links = page.locator("tr").nth(index).locator("a[href]").evaluate_all("nodes => nodes.map(n => ({url:n.href,title:n.textContent}))")
                    link = next((l for l in links if allowed(l["url"]) and re.fullmatch(
                        ASSIGNMENT, urlsplit(l["url"]).path.rsplit("/", 1)[-1])), None)
                    if link is None:
                        continue
                    locator, key = link["url"], link["url"]
                    records.append(dict(id="manaba:" + hashlib.sha256(key.encode()).hexdigest()[:32], source="manaba",
                                        title=(link["title"].strip() if link else "manaba")[:150], url=locator, text=content[:6000]))
                if len(records) == 1 and "該当する課題はありません" not in text:
                    partial = True
            return list({r["id"]: r for r in records}.values())[:200], "partial" if partial or queue or len(records) > 200 else "updated"
        finally:
            browser.close()


