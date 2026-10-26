"""One verified AXOL login route, isolated local Chromium, read-only ES inspection.

The model never receives a browser handle, JavaScript evaluator or credentials.
Unknown authentication challenges stop rather than exposing a generic form submitter.
"""
import re
from urllib.parse import urljoin, urlsplit

from .collection_store import PILOT, PILOT_ROOT, clean_excerpt
from .store import CareerError

LOGIN = PILOT_ROOT + "login"
ES_PAGE = PILOT_ROOT + "com/21"
ES_LOGIN = LOGIN + "?nextpage=mypage%2Fcom%2F21"


def login_form_allowed(page_url, method, action):
    return (page_url in {LOGIN, ES_LOGIN} and isinstance(method, str)
            and method.lower() == "post" and isinstance(action, str)
            and urljoin(page_url, action) in {LOGIN, ES_LOGIN})


def expired_session_page(address, headings):
    url = urlsplit(address)
    return (url.scheme == "https" and url.netloc == "axol.jp"
            and url.path == urlsplit(PILOT_ROOT + "error").path
            and any(heading.strip() == "ログアウト" for heading in headings))

# Field identities and dependencies from the reviewed pilot form; never applicant answers.
PILOT_QUESTION_FIELDS = {"memo" + str(n) for n in
    (188, 189, 190, 538, 795, 191, 192, 193, 199, 666, 785, 786, 787, 788, 789, 790,
     791, 792, 793, 794, 205, 206, 207, 208, 209)}
QUESTION_DEPENDENCIES = {**{"memo" + str(n): ("memo785",) for n in range(786, 792)},
    "memo792": ("memo785", "memo791"), "memo794": ("memo793",),
    **{"memo" + str(n): ("memo206",) for n in (207, 208, 209)}}

# Reads static wording, field types and constraints. No value/checked/selected state.
FORM_QUESTIONS = r"""() => {
    const clean = node => {
        const copy = node.cloneNode(true);
        copy.querySelectorAll('label:has(input[name$="_skip"])').forEach(n => n.remove());
        copy.querySelectorAll('textarea,input,select,script,style,button').forEach(n => n.remove());
        return copy.textContent.replace(/ファイルを更新しない[^\n]*/g, '').trim();
    };
    const seen = new Set();
    return Array.from(document.querySelectorAll('textarea,input[type="text"],input[type="radio"],input[type="file"],select'))
      .filter(e => e.getClientRects().length && !e.disabled).flatMap((e, index) => {
        const field = e.name || e.id || 'field-' + index;
        if (seen.has(field)) return [];
        seen.add(field);
        const item = e.closest('.form__item');
        const container = item || e.closest('tr,.form-group,.question,.form-item') || e.parentElement;
        const ownLabel = e.labels && Array.from(e.labels).map(clean).join(' ');
        const kind = e.type === 'file' ? 'file' : (e.type === 'radio' || e.tagName === 'SELECT' ? 'choice' : 'text');
        const options = e.tagName === 'SELECT' ? Array.from(e.options).map(o => o.textContent.trim()) :
            (e.type === 'radio' ? Array.from(container.querySelectorAll('input[type="radio"]'))
                .filter(r => r.name === e.name).flatMap(r => Array.from(r.labels || []).map(clean)) : []);
        return [{field, label: item ? clean(item) : (ownLabel || clean(container)),
            input_kind: kind, options: [...new Set(options)], pilot_form: !!item,
            max_length: e.getAttribute('maxlength'), required: e.required || /必須/.test(clean(container)),
            required_known: e.required || /必須|任意/.test(clean(container))}];
    });
}"""


def request_allowed(method, address, *, navigation=False, login_in_progress=False):
    url = urlsplit(address)
    if url.scheme != "https" or url.netloc != "axol.jp" or url.username or url.password:
        return False
    if method == "POST":
        return login_in_progress and address in {LOGIN, ES_LOGIN}
    if method not in {"GET", "HEAD"} or re.search(r"/(?:logout|delete|reserve|submit|apply)(?:/|$)", url.path, re.I):
        return False
    if navigation:
        if address == ES_LOGIN:
            return True
        return not url.query and url.path in {urlsplit(value).path for value in (LOGIN, ES_PAGE, PILOT_ROOT, PILOT_ROOT + "index")}
    # Static browser assets only. Unknown AJAX/endpoints require adapter review.
    return bool(re.search(r"\.(?:css|js|png|jpe?g|gif|svg|ico|woff2?|ttf)(?:$)", url.path, re.I))


def questions_from_fields(fields):
    result = []
    by_key = {field.get("field"): field for field in fields if isinstance(field.get("field"), str)}
    if any(field.get("pilot_form") for field in fields):
        required = {"memo199", "memo666", *{"memo" + str(n) for n in range(785, 795)}}
        if not required.issubset(by_key):
            raise CareerError("portal_structure_changed", "関連する設問の一部を確認できません。保存済み情報は保持しています。", 409)
    for field in fields:
        label = clean_excerpt(field.get("label", ""), 2500)
        key = field.get("field")
        if field.get("pilot_form") and key not in PILOT_QUESTION_FIELDS:
            continue
        if not label or not isinstance(key, str) or len(key) > 150:
            continue
        if re.search(r"パスワード|メールアドレス|電話番号|住所|氏名", label) and not re.search(r"経験|志望|学生時代|自己PR", label):
            continue
        kind = field.get("input_kind", "text")
        maximum = field.get("max_length") if kind == "text" else None
        maximum = int(maximum) if isinstance(maximum, (int, str)) and str(maximum).isdigit() else None
        explicit = re.search(r"([0-9,]+)\s*(?:文字|字)\s*(?:以内|以下|まで|程度)", label) if kind == "text" else None
        if maximum is None and explicit:
            maximum = int(explicit[1].replace(",", ""))
        if maximum is not None and not 1 <= maximum <= 20000:
            maximum = None
        if maximum is not None and explicit and maximum != int(explicit[1].replace(",", "")):
            raise CareerError("question_limit_conflict", "設問に表示された字数と入力欄の上限が異なります。原文を確認してください。", 409)
        context = [clean_excerpt(item, 1800) for item in field.get("context", []) if isinstance(item, str)]
        if field.get("pilot_form"):
            for dependency in QUESTION_DEPENDENCIES.get(key, ()):
                parent = by_key.get(dependency)
                if not parent:
                    raise CareerError("portal_structure_changed", "関連する設問を確認できません。保存済み情報は保持しています。", 409)
                context.append(clean_excerpt(parent.get("label", ""), 2500))
        options = list(dict.fromkeys(clean_excerpt(item, 300) for item in field.get("options", []) if isinstance(item, str)))
        text = "\n".join(dict.fromkeys([item for item in context if item and item != label] + [label]))
        if kind == "choice" and options:
            text += "\n選択肢：" + " / ".join(options)
        result.append({"field": key, "text": text, "max_characters": maximum,
                       "limit_evidence": f"maxlength={maximum}" if field.get("max_length") else (explicit[0] if explicit else None),
                       "required": bool(field.get("required")), "required_known": bool(field.get("required_known", field.get("required"))),
                       "no_line_breaks": bool(re.search(r"改行(?:を)?(?:使用|入力)?(?:せず|しない|しないで|禁止|不可)", text)), "input_kind": kind,
                       "options": options, "verified": True})
    return result


class AxolReader:
    def __init__(self, secrets, *, playwright_factory=None, should_cancel=lambda: False):
        self.secrets = secrets
        self.playwright_factory = playwright_factory
        self.should_cancel = should_cancel

    def fetch(self):
        import os
        if self.should_cancel():
            raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
        runtime = os.environ.get("CAREER_BROWSER_PYTHON")
        if runtime and self.playwright_factory is None:
            from .portal_process import fetch_in_process
            return fetch_in_process(self.secrets, runtime, self.should_cancel)
        value = self.secrets.read("portal-" + PILOT)
        if not all(isinstance(value.get(k), str) and value[k] for k in ("login_id", "password")):
            raise CareerError("authentication_required", "マイページのIDとパスワードを入力してください。", 409)
        try:
            from playwright.sync_api import sync_playwright, Error as BrowserError
        except ImportError:
            raise CareerError("browser_unavailable", "サーバーの専用ブラウザを準備してください。", 503) from None
        factory = self.playwright_factory or sync_playwright
        try:
            with factory() as playwright:
                launch = {"headless": True, "chromium_sandbox": True, "args": ["--disable-breakpad", "--disable-crash-reporter"]}
                executable = os.environ.get("CAREER_CHROMIUM_EXECUTABLE")
                if executable:
                    launch["executable_path"] = executable
                browser = playwright.chromium.launch(**launch)
                try:
                    login_in_progress = [False]
                    blocked = []

                    def route_request(route):
                        request = route.request
                        if not request_allowed(request.method, request.url, navigation=request.is_navigation_request(), login_in_progress=login_in_progress[0]):
                            blocked.append("restricted")
                            route.abort()
                            return
                        route.continue_()

                    def open_context(storage_state=None):
                        context = browser.new_context(storage_state=storage_state, service_workers="block", accept_downloads=False)
                        context.set_default_timeout(15000)
                        context.route("**/*", route_request)
                        page = context.new_page()
                        page.on("dialog", lambda dialog: dialog.dismiss())
                        return context, page

                    context, page = open_context(value.get("storage_state"))
                    page.goto(ES_PAGE, wait_until="domcontentloaded", timeout=30000)
                    if value.get("storage_state") and expired_session_page(page.url, page.locator("h1").all_inner_texts()):
                        if self.should_cancel():
                            raise CareerError("collection_cancelled", "情報取得を停止しました。", 409)
                        context.close()
                        context, page = open_context()
                        page.goto(ES_PAGE, wait_until="domcontentloaded", timeout=30000)
                    if page.locator('form[name="form1"] input[name="password"]').count():
                        if urlsplit(page.url).path != urlsplit(LOGIN).path:
                            raise CareerError("portal_structure_changed", "マイページのログイン画面が変わりました。", 409)
                        form = page.locator('form[name="form1"]')
                        if not login_form_allowed(page.url, form.get_attribute("method"), form.get_attribute("action")):
                            raise CareerError("portal_structure_changed", "ログイン先を確認できません。", 409)
                        form.locator('input[name="id"]').fill(value["login_id"])
                        form.locator('input[name="password"]').fill(value["password"])
                        login_in_progress[0] = True
                        try:
                            form.locator('button[name="submit"]').click()
                            page.wait_for_load_state("domcontentloaded")
                        finally:
                            login_in_progress[0] = False
                        if page.locator('input[name="password"]').count():
                            raise CareerError("authentication_required", "ログインできませんでした。ID・パスワードまたは追加認証を確認してください。", 409)
                        page.goto(ES_PAGE, wait_until="domcontentloaded", timeout=30000)
                    if urlsplit(page.url).path != urlsplit(ES_PAGE).path:
                        raise CareerError("authentication_required", "マイページに追加認証または再ログインが必要です。", 409)
                    text = page.locator("body").inner_text()
                    for secret in (value["login_id"], value["password"]):
                        text = text.replace(secret, "[非表示]")
                    if page.locator('input[type="password"]').count() or re.search(r"認証コード|CAPTCHA|画像認証|ワンタイムパスワード", text, re.I):
                        raise CareerError("authentication_challenge", "この追加認証は自動処理できません。専用ブラウザでの対応が必要です。", 409)
                    fields = page.evaluate(FORM_QUESTIONS)
                    for field in fields:
                        for secret in (value["login_id"], value["password"]):
                            field["label"] = field.get("label", "").replace(secret, "[非表示]")
                            field["context"] = [text.replace(secret, "[非表示]") for text in field.get("context", [])]
                            field["options"] = [text.replace(secret, "[非表示]") for text in field.get("options", [])]
                    questions = questions_from_fields(fields)
                    sanitized = clean_excerpt(text, 20000)
                    if not questions:
                        if "アンケート" in text and any(word in text for word in ("未回答", "回答してください", "回答が必要", "回答後")):
                            state = "prerequisite_required"
                        else:
                            state = "questions_unavailable"
                    else:
                        state = "available"
                    value["storage_state"] = context.storage_state()
                    self.secrets.write("portal-" + PILOT, value)
                    return {"url": ES_PAGE, "text": sanitized, "questions": questions, "state": state,
                            "authenticated": True, "blocked_operations": len(blocked)}
                finally:
                    browser.close()
        except BrowserError:
            # Exceptions can contain request URLs, selectors and credential values. Never expose them.
            raise CareerError("browser_unavailable", "専用ブラウザでマイページを確認できませんでした。保存済み情報は保持しています。", 503) from None
