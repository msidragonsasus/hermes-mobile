"""Recognize declarative read destinations without executing site JavaScript.

Some portals encode message links as form.submit(). Only a small, fully parsed
navigation grammar is accepted. We navigate to the observed action with GET;
we never submit the form, copy its values, or run its event handler.
"""
import json
import re
from urllib.parse import urljoin, urlsplit

from .mypage_native_browser import fail

IDENT = r"[A-Za-z_][A-Za-z_0-9]*"
READ_PATH = re.compile(r"/(?:messages?|notices?|news)/(?:index|list|detail)(?:/|$)|/top/index/?$")


def form_selector(handler):
    handler = re.sub(r"^\s*javascript:\s*", "", handler, flags=re.I).strip()
    named = re.fullmatch(r"document\.(" + IDENT + r")\.submit\(\);?", handler)
    if named:
        return 'form[name="' + named[1] + '"]'
    selected = re.fullmatch(r"\$\((['\"])#(" + IDENT + r")\1\)\.submit\(\);?", handler)
    if selected:
        return "form#" + selected[2]
    return None


def function_destination(handler, declaration):
    """Accept a literal URL + numeric ID assigned to a form, nothing executable."""
    call = re.fullmatch(r"(?:javascript:)?\s*(" + IDENT + r")\(\s*([0-9]{1,12})\s*\);?", handler.strip())
    if not call:
        fail("portal_action_blocked")
    # Match the entire function, not a URL occurring somewhere in arbitrary JS.
    pattern = (r"function\s+" + re.escape(call[1]) + r"\s*\(\s*(?P<id>" + IDENT + r")\s*,\s*(?P<kind>" + IDENT + r")\s*\)\s*\{\s*"
        r"action\s*=\s*[\"](?P<url>/[^\"\\\s]+)[\"]\s*\+\s*(?P=id)\s*;\s*"
        r"\$\('#(?P<form>" + IDENT + r")'\)\.attr\('action',\s*action\);\s*"
        r"\$\('form#(?P=form) input:hidden\[name=\"(?P<field>" + IDENT + r")\"\]'\)\.val\((?P=kind)\);\s*"
        r"\$\('#(?P=form)'\)\.submit\(\);\s*\}")
    match = re.fullmatch(pattern, declaration)
    if not match:
        fail("portal_action_blocked")
    return match["url"] + call[2]


def read_destination(browser, ref, href):
    handler = href if href.lower().startswith("javascript:") and not re.fullmatch(r"javascript:\s*void\(0\);?", href, re.I) else browser.command("get", ["attr", ref, "onclick"]).get("value")
    if not isinstance(handler, str) or len(handler) > 1000:
        fail("portal_action_blocked")
    selector = form_selector(handler)
    if selector:
        if browser.command("get", ["count", selector]).get("count") != 1:
            fail("portal_action_blocked")
        target = browser.command("get", ["attr", selector, "action"]).get("value")
    else:
        call = re.fullmatch(r"(?:javascript:)?\s*(" + IDENT + r")\(\s*[0-9]{1,12}\s*\);?", handler.strip())
        if not call:
            fail("portal_action_blocked")
        # Fixed, read-only DOM inspection. Neither handler nor function is eval'd.
        pattern = r"function\s+" + call[1] + r"\s*\([^)]*\)\s*\{[^}]*\}"
        script = "JSON.stringify(Array.from(document.scripts).flatMap(s=>(s.textContent||'').match(new RegExp(" + json.dumps(pattern) + ",'g'))||[]))"
        raw = browser.command("eval", [script]).get("result")
        try:
            declarations = json.loads(raw)
        except (TypeError, ValueError):
            fail("portal_action_blocked")
        if not isinstance(declarations, list) or len(declarations) != 1 or not isinstance(declarations[0], str) or len(declarations[0]) > 4000:
            fail("portal_action_blocked")
        target = function_destination(handler, declarations[0])
    if not isinstance(target, str) or not target:
        fail("portal_action_blocked")
    url = browser.safe_url(urljoin(browser.current_url, target))
    if (not READ_PATH.search(urlsplit(url).path) or urlsplit(url).query or urlsplit(url).fragment
        or not re.fullmatch(r"/[A-Za-z0-9_/-]+", urlsplit(url).path)):
        fail("portal_action_blocked")
    return url
