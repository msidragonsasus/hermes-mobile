"""AXOL home notices only. An allowlist forbids ES pages and application actions."""
import os
import re
from urllib.parse import urljoin, urlsplit

from .collection_store import PILOT, PILOT_ROOT, clean_excerpt, digest
from .store import CareerError

HOME = PILOT_ROOT + "index"
LOGIN = PILOT_ROOT + "login"


def notice_request_allowed(url, method, navigation=False, logging_in=False):
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.netloc != "axol.jp" or parts.username or parts.password:
        return False
    if method == "POST":
        return logging_in and url == LOGIN
    if method not in {"GET", "HEAD"}:
        return False
    if navigation:
        return url in {HOME, LOGIN, PILOT_ROOT}
    return bool(re.search(r"\.(?:css|js|png|jpg|jpeg|gif|svg|woff2?|ico)$", parts.path, re.I))


# No form/input/textarea values are read. Missing known list structure is a failure,
# not a successful empty list. The home-only scope is shown in the app.
NOTICE_LIST = r"""() => {
  const roots = [...document.querySelectorAll('#news, #information, .news-list, .information-list, .newsList, .infoList')];
  const result = [];
  for (const root of roots) {
    for (const element of root.querySelectorAll('li, article, tr, .item')) {
      const anchor = element.querySelector('a[href]');
      const identity = element.id || anchor?.getAttribute('href');
      if (!identity) continue;
      const clone = element.cloneNode(true);
      clone.querySelectorAll('form,input,textarea,select,button,script,style').forEach(n => n.remove());
      const body = clone.textContent.trim();
      if (body) result.push({key: identity, title: anchor?.textContent.trim() || body.split('\n')[0], body});
    }
  }
  const empty = roots.some(r => /お知らせはありません|新着情報はありません|現在.*案内.*ありません/.test(r.textContent));
  return {recognized: result.length > 0 || empty, notices: result};
}"""


class NoticeReader:
    def __init__(self, secrets):
        self.secrets = secrets

    def fetch(self):
        if os.environ.get("CAREER_BROWSER_PYTHON"):
            from .portal_process import fetch_in_process
            return fetch_in_process(self.secrets, os.environ["CAREER_BROWSER_PYTHON"], notices=True)
        value = self.secrets.read("portal-" + PILOT)
        if not value.get("login_id") or not value.get("password"):
            raise CareerError("authentication_required", "既存の安全な保存先のログイン情報を確認してください。", 409)
        try:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as runtime:
                options = {"headless": True, "chromium_sandbox": True}
                if os.environ.get("CAREER_CHROMIUM_EXECUTABLE"):
                    options["executable_path"] = os.environ["CAREER_CHROMIUM_EXECUTABLE"]
                browser = runtime.chromium.launch(**options)
                try:
                    context = browser.new_context(storage_state=value.get("storage_state"), service_workers="block", accept_downloads=False)
                    context.set_default_timeout(15000)
                    logging_in = [False]

                    def route_request(route):
                        request = route.request
                        if notice_request_allowed(request.url, request.method, request.is_navigation_request(), logging_in[0]):
                            route.continue_()
                        else:
                            route.abort()

                    context.route("**/*", route_request)
                    page = context.new_page()
                    page.on("dialog", lambda dialog: dialog.dismiss())
                    # A direct login entry avoids following nextpage redirects to retired ES forms.
                    page.goto(LOGIN, wait_until="domcontentloaded", timeout=30000)
                    if page.locator('input[type="password"]').count():
                        form = page.locator('form[name="form1"]')
                        if page.url != LOGIN or form.count() != 1 or (form.get_attribute("method") or "").upper() != "POST" or urljoin(page.url, form.get_attribute("action") or "") != LOGIN:
                            raise CareerError("portal_structure_changed", "ログイン画面の構造を確認できません。", 409)
                        form.locator('input[name="id"]').fill(value["login_id"])
                        form.locator('input[name="password"]').fill(value["password"])
                        logging_in[0] = True
                        try:
                            form.locator('button[name="submit"]').click()
                            page.wait_for_load_state("domcontentloaded")
                        finally:
                            logging_in[0] = False
                    page.goto(HOME, wait_until="domcontentloaded", timeout=30000)
                    if page.url not in {HOME, PILOT_ROOT} or page.locator('input[type="password"]').count():
                        raise CareerError("authentication_required", "マイページに再ログインが必要です。", 409)
                    if not page.locator('a[href*="logout"]').count():
                        raise CareerError("portal_structure_changed", "ログイン後の画面であることを確認できません。", 409)
                    result = page.evaluate(NOTICE_LIST)
                    notices = []
                    for item in result["notices"]:
                        title, body = item["title"], item["body"]
                        for secret in (value["login_id"], value["password"]):
                            title, body = title.replace(secret, "[非表示]"), body.replace(secret, "[非表示]")
                        notices.append({"key": digest(item["key"]), "title": clean_excerpt(title, 300),
                                        "body": clean_excerpt(body), "url": HOME})
                    if not result["recognized"]:
                        raise CareerError("portal_structure_changed", "ログイン後の案内一覧を確認できません。", 409)
                    value["storage_state"] = context.storage_state()
                    self.secrets.write("portal-" + PILOT, value)
                    return {"authenticated": True, "recognized": True, "notices": notices}
                finally:
                    browser.close()
        except CareerError:
            raise
        except Exception:
            raise CareerError("browser_unavailable", "マイページの取得処理を完了できませんでした。", 503) from None
