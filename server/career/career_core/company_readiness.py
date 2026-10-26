"""Credential-free, evidence-based connection readiness shared by UI and coaching.

A configured connection is NOT proof that the website account exists or that the
sealed credential can currently be read. Never open the secret store here.
"""


def mypage_readiness(portal, url=None):
    status = portal.get("status", {})
    configured = portal.get("connection_version", 0) > 0
    legacy = not configured and portal.get("supported") is True
    state = status.get("state", "not_started")
    last_success = status.get("last_success_at")
    login_observed = portal.get("verified") is True or bool(last_success)
    credentials = "configured" if configured else "legacy_configured" if legacy else "not_configured"
    if state == "auth_required":
        login = "needs_attention"
        label = "ログインの確認が必要"
        action = "マイページのログイン設定を確認"
    elif not configured and not legacy:
        login = "unknown"
        label = "ログインID・パスワード未設定"
        action = ("Appのマイページ設定でログインID・パスワードを入力" if portal.get("pending_request")
                  else "マイページのURL・ログインID・パスワードを設定")
    elif not login_observed:
        login = "unknown"
        label = "ログイン設定あり・成功未確認"
        action = "マイページの初回確認を実行"
    else:
        login = "previously_confirmed"
        label = "ログイン成功の記録あり"
        action = None
    if state in {"failed", "partial"} and action is None:
        action = "マイページの取得状況を確認"
    return {"credential_state": credentials, "credential_availability": "not_checked",
            "website_registration": "login_observed" if login_observed else "unknown",
            "login_state": login, "collection_state": state, "label": label,
            "next_action": action, "portal_url_configured": bool(portal.get("portal_url") or url),
            "pending_request": portal.get("pending_request"),
            "automation": portal.get("automation", "disabled"),
            "last_attempt_at": status.get("last_attempt_at"), "last_success_at": last_success,
            "reason": status.get("reason"), "source": "saved_connection_and_collection_status"}
