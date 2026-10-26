"""A restricted tab inside Hermes' local browser, with private credential input.

No browser/terminal/eval tool is exposed to the model. Playwright attaches to a
Hermes-owned local CDP session to enforce network and form boundaries before login.
"""
import ipaddress
import re
import socket
import time
import json
import os
import subprocess
import sys
import threading
import contextvars
from pathlib import Path
from urllib.parse import urlsplit, urljoin

from .store import CareerError

WRITE_PATH = re.compile(r"(?:submit|logout|delete|cancel|withdraw|reserve|reservation|(?:^|/)(?:entry|apply)(?:/|\.|$))", re.I)
WRITE_LABEL = re.compile(r"応募する|予約する|申し込む|申込確定|送信|提出する|辞退|取消|削除|ログアウト|エントリーする")
CHALLENGE = re.compile(r"認証コード|ワンタイム|二段階認証|ロボットでは|verification code|captcha", re.I)


def browser_failure(error, phase):
    """Only code locations cross the private worker pipe; never exception text."""
    frames, trace = [], error.__traceback__
    while trace:
        frames.append({"file": Path(trace.tb_frame.f_code.co_filename).name, "line": trace.tb_lineno})
        trace = trace.tb_next
    return {"phase": phase, "exception_type": type(error).__name__, "frames": frames[-8:]}


def local_cdp_url(result):
    match = re.search(r"ws://127\.0\.0\.1:\d+/[A-Za-z0-9/_-]+", json.dumps(result))
    return match[0] if result.get("success") and match else None


_browser_start_lock = threading.Lock()
BROWSER_IDLE_MS = 510_000  # Eight-minute run plus bounded cleanup; never disable expiry.


def open_native_browser(task):
    """Set the daemon's standard idle bound only while this Cron child's browser starts."""
    from tools.browser_tool_session import _run_browser_command, _get_session_info, _agent_browser_command_env
    # Each Career run is a dedicated Cron subprocess under the browser flock.
    # Restore even on failure; no profile/config/service-wide timeout is changed.
    with _browser_start_lock:
        key = "AGENT_BROWSER_IDLE_TIMEOUT_MS"
        previous = os.environ.get(key)
        os.environ[key] = str(BROWSER_IDLE_MS)
        try:
            if _agent_browser_command_env("").get(key) != str(BROWSER_IDLE_MS):
                raise CareerError("hermes_contract_unsupported", "Hermesのブラウザ実行設定を確認してください。", 503)
            result = _run_browser_command(task, "open", ["about:blank"], timeout=45)
            info = _get_session_info(task)
            if not result.get("success") or info.get("cdp_url") or not info.get("features", {}).get("local"):
                raise CareerError("browser_unavailable", "Hermesの専用ブラウザを確認してください。", 503)
            cdp = local_cdp_url(_run_browser_command(task, "get", ["cdp-url"], timeout=15))
            if not cdp:
                raise CareerError("hermes_contract_unsupported", "Hermesのブラウザ接続に対応する版が必要です。", 503)
            return info, cdp
        finally:
            if previous is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = previous


def touch_native_browser_session(task, expected_session):
    """Keep the Python janitor informed without CLI commands during CDP reads."""
    from tools import browser_tool as native
    from tools.browser_tool_lifecycle import _update_session_activity
    with native._cleanup_lock:
        current = native._active_sessions.get(task)
    if current is not expected_session:
        # Never create a replacement browser or attempt another login.
        raise CareerError("browser_session_ended", "ブラウザセッションの維持を確認できませんでした。", 503)
    _update_session_activity(task)


class BrowserSessionLease:
    """Refresh the owning browser through a bounded, run-scoped callback."""
    def __init__(self, task, deadline, touch, on_failure, interval=15):
        self.task, self.deadline, self.touch = task, deadline, touch
        self.on_failure, self.interval = on_failure, interval
        self.stopped = threading.Event()
        self.thread = None

    def pulse(self):
        if self.stopped.is_set() or time.monotonic() >= self.deadline:
            return False
        self.touch(self.task)
        return True

    def start(self):
        if not self.pulse():
            raise CareerError("collection_limit", "今回の確認時間の上限に達しました。", 409)
        context = contextvars.copy_context()
        self.thread = threading.Thread(target=context.run, args=(self.run,), daemon=True,
                                       name="career-browser-activity")
        self.thread.start()

    def run(self):
        try:
            while not self.stopped.wait(min(self.interval, max(0, self.deadline - time.monotonic()))):
                if not self.pulse():
                    return
        except Exception as error:
            self.on_failure(error)

    def stop(self):
        self.stopped.set()
        if self.thread and self.thread is not threading.current_thread():
            self.thread.join(timeout=30)


def public_host(url):
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.port not in (None, 443):
        raise CareerError("unsafe_url", "取得先のURLを確認してください。")
    try:
        addresses = socket.getaddrinfo(parts.hostname, parts.port or 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
            raise ValueError()
    except (OSError, ValueError):
        raise CareerError("unsafe_url", "取得先の接続を確認できません。", 409) from None
    return parts.hostname


PAGE = """() => {
 const clone=document.body.cloneNode(true);
 clone.querySelectorAll('input,textarea,select,script,style,iframe').forEach(n=>n.remove());
 const links=[...document.querySelectorAll('a[href]')].slice(0,200).map(a=>({url:a.href,label:a.innerText.trim()}));
 return {text:clone.innerText || clone.textContent,links, title:document.title,
 password:!!document.querySelector('input[type=password]'),
 authenticated:[...document.querySelectorAll('a,button')].some(a=>/ログアウト|logout|sign out/i.test(a.textContent+' '+(a.getAttribute('href')||'')))};
}"""


class GuardedPortalTab:
    def __init__(self, run_id, url, credentials, endpoint):
        self.task = "career-mypage-" + run_id + "::local"
        self.url, self.credentials = url, credentials
        self.host = public_host(url)
        # Keep credentials and page reads inside the configured portal directory.
        # Cross-directory SSO requires an explicit integration, never a guessed host grant.
        self.path_prefix = urlsplit(url).path.rsplit("/", 1)[0] + "/"
        self.deadline = time.monotonic() + 480
        self.logging_in, self.login_action = False, None
        self.authenticated, self.blocked = False, False
        self.pages, self.links = {}, {}
        self.runtime = self.browser = self.context = None
        self.endpoint = endpoint

    def allowed_page(self, url):
        from urllib.parse import unquote
        parts = urlsplit(url)
        path = unquote(parts.path)
        return (parts.scheme == "https" and parts.hostname == self.host and parts.port in (None, 443)
                and not parts.username and not parts.password and path.startswith(self.path_prefix)
                and "\\" not in path and not any(p in {".", ".."} for p in path.split("/"))
                and not WRITE_PATH.search(path))

    def start(self):
        from playwright.sync_api import sync_playwright
        self.runtime = sync_playwright().start()
        self.browser = self.runtime.chromium.connect_over_cdp(self.endpoint)
        self.context = self.browser.new_context(service_workers="block", accept_downloads=False)
        self.context.set_default_timeout(15000)
        self.context.route("**/*", self.route)
        self.page = self.context.new_page()
        self.page.on("dialog", lambda dialog: dialog.dismiss())
        self.page.goto(self.url, wait_until="domcontentloaded", timeout=30000)
        self.wait_content()
        return self.read()

    def wait_content(self):
        self.page.wait_for_function("() => document.body && (document.body.innerText.trim().length > 0 || document.querySelector('input[type=password]'))", timeout=15000)

    def route(self, route):
        request = route.request
        try:
            parts = urlsplit(request.url)
            public_host(request.url)
            if request.is_navigation_request() and not self.allowed_page(request.url):
                raise ValueError()
            if request.method not in {"GET", "HEAD"} and not (
                self.logging_in and request.method == "POST" and request.url == self.login_action
            ):
                raise ValueError()
            # Cross-origin assets never carry portal credentials via query strings.
            if parts.hostname != self.host and (parts.query or request.resource_type not in {"stylesheet", "image", "font", "script"}):
                raise ValueError()
            # Browser routing does not re-run the handler for every HTTP redirect.
            # Fetch one response only, then make an approved redirect a fresh navigation.
            response = route.fetch(max_redirects=0, timeout=20000)
            headers = {k: v for k, v in response.headers.items() if k.lower() not in {"content-length", "content-encoding", "transfer-encoding"}}
            if response.status in {301, 302, 303, 307, 308}:
                target = urljoin(request.url, headers.get("location", ""))
                public_host(target)
                if not request.is_navigation_request() or not self.allowed_page(target):
                    raise ValueError()
                if response.status in {307, 308} and request.method == "POST":
                    raise ValueError()
                headers.pop("location", None)
                headers["content-type"] = "text/html; charset=utf-8"
                # This response contains only our fixed navigation shim, no remote HTML.
                headers["content-security-policy"] = "default-src 'none'; script-src 'unsafe-inline'"
                script_url = json.dumps(target).replace("<", "\\u003c")
                route.fulfill(status=200, headers=headers, body="<script>location.replace(" + script_url + ")</script>")
            else:
                if request.is_navigation_request() and response.status >= 400:
                    self.blocked = True
                route.fulfill(status=response.status, headers=headers, body=response.body())
        except Exception:
            # Blocked analytics/images do not make a successfully read list incomplete.
            if request.is_navigation_request() or (urlsplit(request.url).hostname == self.host and request.resource_type in {"xhr", "fetch"}):
                self.blocked = True
            route.abort()

    def guard(self):
        if time.monotonic() >= self.deadline:
            raise CareerError("collection_limit", "今回の確認時間の上限に達しました。", 409)
        if not self.allowed_page(self.page.url):
            raise CareerError("portal_origin_changed", "別のログイン先への移動を確認してください。", 409)

    def redact(self, value):
        for secret in self.credentials.values():
            if isinstance(secret, str) and secret:
                value = value.replace(secret, "[非表示]")
        return value

    def read(self):
        self.guard()
        value = self.page.evaluate(PAGE)
        text = self.redact(value["text"] or "")[:40000]
        if CHALLENGE.search(text):
            raise CareerError("authentication_challenge", "本人による追加認証が必要です。", 409)
        if self.authenticated and value["password"]:
            raise CareerError("authentication_required", "再ログインが必要です。", 409)
        self.authenticated = self.authenticated or (value["authenticated"] and not value["password"])
        page_id = str(len(self.pages) + 1)
        # A URL query can contain a signed session locator; only persist the public path.
        parts = urlsplit(self.page.url)
        url = parts.scheme + "://" + parts.netloc + parts.path
        self.pages[page_id] = {"text": text, "url": url, "authenticated": self.authenticated}
        links = []
        for link in value["links"]:
            p = urlsplit(link["url"])
            if self.allowed_page(link["url"]) and not WRITE_LABEL.search(link["label"]):
                key = str(len(self.links) + 1)
                self.links[key] = link["url"]
                links.append({"id": key, "label": self.redact(link["label"])[:200]})
        return {"page_id": page_id, "url": url, "text": text, "links": links, "authenticated": self.authenticated,
                "login_required": value["password"], "blocked_requests": self.blocked}

    def login(self):
        self.guard()
        if self.authenticated:
            return self.read()
        forms = self.page.locator("form").filter(has=self.page.locator('input[type="password"]'))
        if forms.count() != 1:
            raise CareerError("portal_structure_changed", "ログインフォームを特定できません。", 409)
        form = forms.first
        action = form.evaluate("f=>f.action")
        if not self.allowed_page(action) or (form.get_attribute("method") or "get").lower() != "post":
            raise CareerError("portal_structure_changed", "ログイン先を確認できません。", 409)
        ids = form.locator('input:not([type]):visible,input[type="text"]:visible,input[type="email"]:visible,input[type="tel"]:visible')
        passwords = form.locator('input[type="password"]')
        if ids.count() != 1 or passwords.count() != 1:
            raise CareerError("portal_structure_changed", "ID入力欄を特定できません。", 409)
        buttons = form.locator('button[type="submit"],input[type="submit"],button:not([type])')
        if buttons.count() != 1:
            raise CareerError("portal_structure_changed", "ログイン操作を特定できません。", 409)
        ids.fill(self.credentials["login_id"])
        passwords.fill(self.credentials["password"])
        self.logging_in, self.login_action = True, action
        try:
            buttons.click()
            self.page.wait_for_load_state("domcontentloaded")
            self.page.wait_for_function("""() => document.body && /ログアウト|logout|sign out|認証コード|ワンタイム|二段階認証|ロボットでは|verification code|captcha/i.test(document.body.innerText + [...document.querySelectorAll('a')].map(a=>a.href).join(' '))""", timeout=15000)
        except Exception:
            # Preserve challenge classification, without exposing form values or backend errors.
            self.read()
            raise CareerError("authentication_required", "ID・パスワードを確認してください。", 409) from None
        finally:
            self.logging_in = False
        result = self.read()
        if not result["authenticated"]:
            raise CareerError("portal_structure_changed", "ログイン後の画面であることを確認できません。", 409)
        return result

    def open(self, link_id):
        self.guard()
        if not self.authenticated or link_id not in self.links:
            raise CareerError("invalid_portal_link", "確認済みの案内リンクを選んでください。")
        self.page.goto(self.links[link_id], wait_until="domcontentloaded", timeout=30000)
        self.wait_content()
        return self.read()

    def close(self):
        try:
            if self.context:
                self.context.close()
        finally:
            if self.runtime:
                self.runtime.stop()


class HermesPortalBrowser:
    """Keep Playwright in CAREER_BROWSER_PYTHON; Hermes owns the local browser session."""
    def __init__(self, run_id, url, credentials):
        self.run_id, self.url, self.credentials = run_id, url, credentials
        self.task = "career-mypage-" + run_id + "::local"
        self.process = self.timer = None
        self.lease = None
        self.activity_error = None
        self.deadline = time.monotonic() + 480
        self.pages, self.authenticated, self.blocked = {}, False, False

    def start(self):
        python = os.environ.get("CAREER_BROWSER_PYTHON")
        if not python or not Path(python).is_file():
            raise CareerError("browser_runtime_missing", "専用ブラウザのPython環境を確認してください。", 503)
        info, cdp_url = open_native_browser(self.task)
        endpoint = "http://" + urlsplit(cdp_url).netloc
        allowed = {"PATH", "HOME", "USER", "LANG", "SYSTEMROOT", "TMPDIR", "TEMP", "TMP"}
        env = {k: v for k, v in os.environ.items() if k in allowed}
        env.update(PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        self.process = subprocess.Popen([python, "-m", "career_core.mypage_browser"],
            cwd=Path(__file__).resolve().parents[1], env=env, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.lease = BrowserSessionLease(self.task, self.deadline,
            lambda task: touch_native_browser_session(task, info), self.activity_failed)
        self.lease.start()
        self.timer = threading.Timer(max(0, self.deadline - time.monotonic()), self.abort)
        self.timer.daemon = True
        self.timer.start()
        return self.call({"action": "start", "run_id": self.run_id, "url": self.url,
                          "credentials": self.credentials, "endpoint": endpoint})

    def call(self, message):
        if time.monotonic() >= self.deadline:
            raise CareerError("collection_limit", "今回の確認時間の上限に達しました。", 409)
        if self.activity_error:
            raise CareerError("browser_session_ended", "ブラウザセッションの維持に失敗しました。", 503)
        try:
            self.process.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
            self.process.stdin.flush()
            line = self.process.stdout.readline(1024 * 1024)
            value = json.loads(line)
        except Exception as error:
            self.report(browser_failure(error, "browser_pipe"), "browser_session_ended")
            if time.monotonic() >= self.deadline:
                raise CareerError("collection_limit", "今回の確認時間の上限に達しました。", 409) from None
            raise CareerError("browser_session_ended", "ブラウザの確認処理が終了しました。", 503) from None
        diagnostic = value.pop("diagnostic", None)
        if diagnostic:
            self.report(diagnostic, value.get("error", "browser_operation_failed"))
        if value.get("error"):
            raise CareerError(value["error"], "マイページの接続・取得状態を確認してください。", 409)
        if "page_id" in value:
            self.pages[value["page_id"]] = {k: value[k] for k in ("text", "url", "authenticated")}
            self.authenticated, self.blocked = value["authenticated"], value["blocked_requests"]
        return value

    def report(self, diagnostic, code):
        from .mypage_job import report_event
        report_event("mypage_failure", self.run_id, code=code, **diagnostic)

    def activity_failed(self, error):
        self.activity_error = True
        self.report(browser_failure(error, "browser_activity"), "browser_session_ended")
        self.abort()

    def read(self):
        return self.call({"action": "read"})

    def login(self):
        return self.call({"action": "login"})

    def open(self, link_id):
        return self.call({"action": "open", "link_id": link_id})

    def close(self):
        if self.timer:
            self.timer.cancel()
        if self.lease:
            self.lease.stop()
        try:
            if self.process:
                try:
                    self.process.stdin.close()
                    self.process.wait(timeout=10)
                except Exception:
                    self.process.kill()
                    self.process.wait(timeout=10)
                finally:
                    self.process.stdout.close()
        finally:
            from tools.browser_tool_lifecycle import cleanup_browser
            cleanup_browser(self.task)

    def abort(self):
        if self.process and self.process.poll() is None:
            self.process.kill()
        if self.lease:
            self.lease.stop()


def child_main():
    tab = None
    try:
        while line := sys.stdin.readline(1024 * 1024):
            action = "protocol"
            try:
                message = json.loads(line)
                requested = message["action"]
                if requested not in {"start", "read", "login", "open"}:
                    raise CareerError("invalid_browser_action", "利用できないブラウザ操作です。")
                action = requested
                if action == "start" and tab is None:
                    tab = GuardedPortalTab(message["run_id"], message["url"], message["credentials"], message["endpoint"])
                    result = tab.start()
                elif tab is not None and action in {"read", "login", "open"}:
                    result = getattr(tab, action)(message["link_id"]) if action == "open" else getattr(tab, action)()
                else:
                    result = {"error": "invalid_browser_action"}
            except CareerError as error:
                result = {"error": error.code, "diagnostic": browser_failure(error, "browser_" + action)}
            except Exception as error:
                result = {"error": "browser_operation_failed", "diagnostic": browser_failure(error, "browser_" + action)}
            print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        if tab:
            tab.close()


if __name__ == "__main__":
    child_main()
