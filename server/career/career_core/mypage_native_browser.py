"""Read-only policy around Hermes' standard browser; no CDP/lifecycle owner.

The model uses the standard browser names. Link clicks become ordinary native
navigations, so a page's onclick handler cannot submit an application. Credentials
are briefly placed in agent-browser's encrypted vault, then removed after login.
"""
from contextlib import contextmanager
from contextvars import ContextVar
import json
import os
import re
import secrets
import subprocess
import uuid
from urllib.parse import urljoin, urlsplit

from .store import CareerError

WRITE = re.compile(r"submit|logout|delete|cancel|withdraw|reserv|(?:^|[/_.?&=-])(?:entry|apply|confirm)(?:$|[/_.?&=-])|応募する|予約する|申し込む|申込確定|送信|提出する|辞退|取消|削除|エントリーする", re.I)
CHALLENGE = re.compile(r"認証コード|ワンタイム|二段階認証|ロボットでは|verification code|captcha", re.I)
REF = re.compile(r"^@?e[0-9]+$")
_browser_env = ContextVar("career_native_browser_env", default=None)


def install_environment_adapter():
    """Supply native CLI settings per call without changing os.environ.

    Hermes' browser spawn intentionally ignores terminal env_passthrough. This
    adapter only adds the domain/key for the current protected tool invocation;
    unrelated profiles and Agent.close still receive the original native env.
    """
    from tools import browser_tool_session as session
    original = session._agent_browser_command_env
    if getattr(original, "career_scoped_environment", False):
        return

    def environment(socket_dir):
        return dict(original(socket_dir), **(_browser_env.get() or {}))

    environment.career_scoped_environment = True
    session._agent_browser_command_env = environment


def fail(code):
    raise CareerError(code, "マイページ確認を続行できません。取得履歴を確認してください。", 409)


class NativeBrowser:
    def __init__(self, task, url, credentials, *, validate_host=None):
        # Credentials never appear in repr, tool arguments, or returned errors.
        self.task, self.url = task, url
        self.credentials = credentials
        self.key = secrets.token_hex(32)
        self.authenticated = False
        self.login_attempted = False
        self.incomplete = False
        self.pages, self.refs, self.links = {}, {}, {url}
        self.current_url = url
        if validate_host is None:
            from .mypage_browser import public_host
            validate_host = public_host
        self.validate_host = validate_host
        self.validate_host(url)

    @contextmanager
    def scope(self):
        from tools.browser_tool_cloud import _is_local_mode
        from tools import browser_tool as native
        if not _is_local_mode() or native._is_camofox_mode():
            fail("paid_browser_blocked")
        token = _browser_env.set(dict(
            AGENT_BROWSER_ALLOWED_DOMAINS=urlsplit(self.url).hostname,
            AGENT_BROWSER_ENCRYPTION_KEY=self.key))
        try:
            yield
        finally:
            _browser_env.reset(token)

    def safe_url(self, url, *, login=False):
        p, expected = urlsplit(url), urlsplit(self.url)
        if (p.scheme, p.netloc) != (expected.scheme, expected.netloc) or p.username or p.password:
            fail("portal_origin_changed")
        if not login and (WRITE.search(p.path + "?" + p.query) or re.search(r"(?:token|password|secret|session)=", p.query, re.I)):
            fail("portal_action_blocked")
        self.validate_host(url)
        return url

    def command(self, command, args):
        from tools.browser_tool_session import _run_browser_command
        result = _run_browser_command(self.task, command, args)
        if not result.get("success"):
            fail("browser_operation_failed")
        return result.get("data") or {}

    def redact(self, value):
        text = json.dumps(value, ensure_ascii=False)
        for secret in self.credentials.values():
            if isinstance(secret, str) and secret:
                # Replace JSON-escaped forms too; newlines/quotes must not leak.
                text = text.replace(json.dumps(secret, ensure_ascii=False)[1:-1], "[private]")
        return json.loads(text)

    def capture(self, result):
        if not result.get("success"):
            fail("browser_operation_failed")
        url = self.command("get", ["url"]).get("url")
        if not isinstance(url, str):
            fail("portal_structure_changed")
        self.safe_url(url, login=not self.authenticated)
        result = self.redact(result)
        text = result.get("snapshot", "")
        if not isinstance(text, str):
            fail("portal_structure_changed")
        if re.search(r"\[\.\.\. .*truncated", text):
            self.incomplete = True
        self.current_url = url
        self.links.add(url)
        self.refs = {m.group(2): m.group(1) for m in re.finditer(r'link "([^"\n]*)" \[ref=(e\d+)\]', text)}
        if CHALLENGE.search(text):
            fail("authentication_challenge")
        if self.authenticated and (re.search(r"textbox.*(?:password|パスワード)", text, re.I)
                or url == self.url and not re.search(r"ログアウト|logout|sign out", text, re.I)):
            fail("authentication_required")
        page_id = uuid.uuid4().hex
        self.pages[page_id] = {"url": url, "text": text, "authenticated": self.authenticated}
        # No cache paths, debug values, cookies, or arbitrary backend metadata.
        numbered = "\n".join(f"{number}: {line}" for number, line in enumerate(text.splitlines(), 1))
        return {"success": True, "url": url, "snapshot": numbered, "page_id": page_id,
                "line_count": len(text.splitlines()), "partial": self.incomplete}

    def full_page(self):
        """Use the standard full snapshot and expose observed read destinations.

        Keeping URLs from the list avoids re-opening that list for every notice.
        This is DOM reading only: no page event handler or arbitrary model JS.
        """
        from tools import browser_tool as native
        result = self.capture(json.loads(native.browser_snapshot(full=True, task_id=self.task)))
        raw = self.command("eval", ["JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a=>({label:(a.innerText||a.textContent||'').trim().slice(0,300),href:a.getAttribute('href')})))"]).get("result")
        try:
            observed = json.loads(raw)
        except (TypeError, ValueError):
            fail("portal_structure_changed")
        if not isinstance(observed, list):
            fail("portal_structure_changed")
        links, seen = [], set()
        for item in observed:
            if not isinstance(item, dict):
                continue
            href, label = item.get("href"), item.get("label")
            if not isinstance(href, str) or not href or href.lower().startswith(("#", "javascript:")) or not isinstance(label, str) or WRITE.search(label):
                continue
            try:
                url = self.safe_url(urljoin(self.current_url, href))
            except CareerError:
                continue
            if url in seen or any(value and value in url for value in self.credentials.values()):
                continue
            if len(links) >= 200:
                self.incomplete = True
                break
            seen.add(url)
            self.links.add(url)
            links.append({"label": label, "url": url})
        result.update(read_links=self.redact(links), partial=self.incomplete)
        return result

    def invoke(self, name, args):
        from tools import browser_tool as native
        with self.scope():
            if name == "browser_navigate":
                url = args.get("url")
                if url not in self.links:
                    fail("portal_action_blocked")
                self.safe_url(url, login=url == self.url and not self.authenticated)
                result = native.browser_navigate(url, task_id=self.task)
            elif name == "browser_snapshot":
                return self.full_page()
            elif name == "browser_click":
                ref = args.get("ref", "")
                if not isinstance(ref, str) or not REF.fullmatch(ref):
                    fail("portal_action_blocked")
                label = self.refs.get(ref.lstrip("@"))
                if label is None or WRITE.search(label):
                    fail("portal_action_blocked")
                href = self.command("get", ["attr", "@" + ref.lstrip("@"), "href"]).get("value")
                if not isinstance(href, str) or not href:
                    fail("portal_action_blocked")
                if href.lower().startswith(("#", "javascript:")):
                    from .mypage_native_navigation import read_destination
                    url = read_destination(self, "@" + ref.lstrip("@"), href)
                else:
                    url = self.safe_url(urljoin(self.current_url, href))
                self.links.add(url)
                result = native.browser_navigate(url, task_id=self.task)
            else:
                fail("portal_action_blocked")
            # The navigation tool's auto snapshot is compact. Return full text
            # in this same tool result, so reading costs no additional Agent turn.
            opened = json.loads(result)
            if not opened.get("success"):
                fail("browser_operation_failed")
            self.safe_url(opened.get("url", ""))
            return self.full_page()

    def login_submit_selector(self, form):
        selector = form + ' :is(button[type="submit"],input[type="submit"],button:not([type]))'
        count = self.command("get", ["count", selector]).get("count")
        if count == 1:
            return selector
        if count != 0:
            fail("portal_structure_changed")
        # Standard auth supports explicit submit selectors. Some login forms use
        # input[type=button]; accept only one with an observed login label.
        selector = form + ' input[type="button"]'
        if self.command("get", ["count", selector]).get("count") != 1:
            fail("portal_structure_changed")
        label = self.command("get", ["attr", selector, "value"]).get("value")
        if not isinstance(label, str) or not re.search(r"ログイン|log\s*in|sign\s*in", label, re.I) or WRITE.search(label):
            fail("portal_action_blocked")
        return selector

    def login(self):
        if self.login_attempted:
            fail("authentication_required")
        self.login_attempted = True
        # The vault is ephemeral, encrypted with a unique per-run key. Standard
        # auth save consumes only the password via stdin, never the model API.
        from tools import browser_tool as native
        from tools import browser_tool_session as session
        from tools.browser_tool_install import _find_agent_browser
        vault = "career-" + uuid.uuid4().hex
        with self.scope():
            initial = self.capture(json.loads(native.browser_navigate(self.url, task_id=self.task)))
            if not re.search(r"password|パスワード", initial["snapshot"], re.I):
                fail("portal_structure_changed")
            form = 'form:has(input[type="password"])'
            if self.command("get", ["count", form]).get("count") != 1:
                fail("portal_structure_changed")
            action = self.command("get", ["attr", form, "action"]).get("value") or self.url
            action = self.safe_url(urljoin(self.url, action), login=True)
            if action != self.url and not re.search(r"(?:login|signin|sign-in|auth)", urlsplit(action).path, re.I):
                fail("portal_action_blocked")
            submit_selector = self.login_submit_selector(form)
            info = session._get_session_info(self.task)
            if info.get("cdp_url") or not info.get("features", {}).get("local"):
                fail("paid_browser_blocked")
            env = session._agent_browser_command_env(session._prepare_session_socket_dir(info["session_name"]))
            if env.get("AGENT_BROWSER_ENCRYPTION_KEY") != self.key or env.get("AGENT_BROWSER_ALLOWED_DOMAINS") != urlsplit(self.url).hostname:
                fail("native_browser_policy_missing")
            argv = session._agent_browser_argv(_find_agent_browser()) + ["--session", info["session_name"]]
            common = dict(env=env, capture_output=True, timeout=30,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                saved = subprocess.run(argv + ["--json", "auth", "save", vault, "--url", self.url,
                    "--username", self.credentials["login_id"], "--password-stdin", "--submit-selector", submit_selector],
                    input=self.credentials["password"].encode("utf-8"), **common)
                if saved.returncode or not json.loads(saved.stdout).get("success"):
                    fail("authentication_required")
                self.command("auth", ["login", vault])
            finally:
                deleted = subprocess.run(argv + ["--json", "auth", "delete", vault], **common)
                if deleted.returncode:
                    fail("credential_cleanup_failed")
            result = self.full_page()
            if (self.command("get", ["count", 'input[type="password"]']).get("count") != 0
                or re.search(r"textbox.*(?:password|パスワード)", result["snapshot"], re.I)
                or result["url"] == self.url and not re.search(r"ログアウト|logout|sign out", result["snapshot"], re.I)):
                fail("authentication_required")
            self.authenticated = True
            self.pages[result["page_id"]]["authenticated"] = True
            return result

    def forget(self):
        # Hermes Agent.close owns browser teardown. No browser close/lease here.
        self.credentials.clear()
        self.key = ""
        self.pages.clear()
