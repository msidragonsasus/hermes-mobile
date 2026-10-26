"""Accepted coaching requests use the same mail settings as the Career App."""
import re

from .collection_store import digest
from .mail_store import MailStore, connection_view, normalize_address
from .store import CareerError


ACTIONS = ["connections", "mail_account", "mail_sender", "mypage_connection"]


def operate(tools, args, run):
    from .mypage_request import CREDENTIAL_KEYS, overview, request_change
    # Rejected before any receipt or event can record the arguments.
    if CREDENTIAL_KEYS & set(args):
        raise CareerError("credentials_app_only", "ログインID・パスワードは会話で扱いません。Appのマイページ設定で本人が入力します。", 403)
    action = args.get("action")
    if action == "connections":
        result = connection_view(tools.store)
        company_id = run["request"].get("scope", {}).get("company_id")
        if company_id:
            result["mail_senders"] = [rule for rule in result["mail_senders"] if rule["company_id"] == company_id]
        return dict(result, mail_scope="all_companies", supported_actions=ACTIONS,
                    mypage_connections=overview(tools.store, run.get("profile", "default"), company_id))
    if action not in set(ACTIONS) - {"connections"}:
        raise CareerError("unsupported_app_action", "対応する操作はconnections/mail_account/mail_sender/mypage_connectionです。")
    if run["kind"] in {"daily", "research"}:
        raise CareerError("daily_scope", "自動調査では接続設定を変更できません。", 403)
    if type(args.get("expected_version")) is not int or args["expected_version"] < 0 or type(args.get("enabled")) is not bool:
        raise CareerError("invalid_app_setting", "読取済みの版番号と有効・無効を指定してください。")
    if action == "mypage_connection":
        return request_change(tools, args, run)
    address = normalize_address(args.get("address"))
    # Initialize schema before entering the receipt transaction.
    mail = MailStore(tools.store)

    def apply(db, current, operation_id):
        proof = tools.evidence(db, current, args)
        if not re.search(r"(?<![a-z0-9.!#$%&'*+/=?^_`{|}~@-])" + re.escape(address) +
                         r"(?![a-z0-9.@-])", proof["quote"].lower()):
            raise CareerError("setting_quote_required", "設定するアドレスを本人の依頼文と照合できません。", 409)
        if action == "mail_account":
            from .workspace_reader import connected_accounts_details, connected_account
            settings = mail.settings(db)
            if settings["revision"] != args["expected_version"]:
                raise CareerError("version_conflict", "メール設定が変わりました。connectionsで読み直してください。", 409)
            if args["enabled"]:
                accounts = connected_accounts_details()
                candidates = [account for account in accounts if account.get("email", "").lower() == address
                              and account.get("state", "connected") == "connected"]
                if len(candidates) != 1:
                    raise CareerError("mail_connection_required", "このGmailは未接続です。AppのGoogle接続画面で読み取りを許可してください。", 409)
                identity = candidates[0]["id"]
                if connected_account(identity) != identity:
                    raise CareerError("mail_account_mismatch", "接続済みGmailが変更されています。読み直してください。", 409)
            else:
                from .gmail_reader import account_reference
                identity = account_reference(address)
                if settings.get("account_id") != identity:
                    raise CareerError("mail_account_mismatch", "停止するGmailが現在の同期先と一致しません。", 409)
            result = mail.configure(identity, args["enabled"], db=db)
            return {"mail": result, "mail_scope": "all_companies", "saved": True, "collection_verified": False}
        scope = current["request"].get("scope", {})
        company_id = args.get("company_id")
        if not isinstance(company_id, str) or not company_id:
            raise CareerError("company_missing", "設定する企業を指定してください。")
        if scope.get("company_id") and company_id != scope["company_id"]:
            raise CareerError("write_scope_conflict", "相談中の企業だけを設定してください。", 403)
        from .workspace_reader import connected_accounts_details
        if args["enabled"] and any(account.get("email", "").lower() == address for account in connected_accounts_details()):
            raise CareerError("recipient_is_not_sender", "本人の受信先Gmailは企業の送信元に登録できません。", 409)
        identity = "mail-sender:" + digest(company_id, address)
        old = tools.store.entity(db, "company_mail_senders", identity)
        if (old["version"] if old else 0) != args["expected_version"]:
            raise CareerError("version_conflict", "送信元設定が変わりました。connectionsで読み直してください。", 409)
        sender = mail.configure_sender(company_id, address, args["enabled"], db=db)
        return {"sender": sender, "saved": True, "collection_verified": False}

    return tools.coach.tool_once(run["id"], dict(args, tool="career_app"), apply)
