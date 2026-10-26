"""Read-only Gmail bridge to the installed Hermes google-workspace skill.

Credentials remain owned by that skill. A bounded child process contains its
CLI output and refresh handling; neither credentials nor raw errors reach logs.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys

from .private_process import run_private
from .store import CareerError

READONLY = "https://www.googleapis.com/auth/gmail.readonly"
MAX_OUTPUT = 8 * 1024 * 1024


def operation(resource, params):
    """Map a fixed read surface, never a URL, arbitrary method or request body."""
    if resource == "profile" and not params:
        return ["gmail", "users", "getProfile"], {"userId": "me"}
    if resource == "messages" and set(params) <= {"q", "maxResults", "pageToken"}:
        query = params.get("q", "")
        match = re.fullmatch(r"(?:\{(from:[^\s{}]+(?: from:[^\s{}]+){0,99})\}|(from:[^\s{}]+))(?: after:(?:\d{4}/\d{2}/\d{2}|\d{1,12}))?(?: before:\d{1,12})? -in:spam -in:trash", query)
        if not match or not re.search(r" (?:after|before):", query):
            raise ValueError("Unapproved query")
        from .mail_store import normalize_address
        try:
            addresses = [normalize_address(item.removeprefix("from:")) for item in (match[1] or match[2]).split(" ")]
        except CareerError:
            raise ValueError("Unapproved query") from None
        if len(addresses) != len(set(addresses)):
            raise ValueError("Unapproved query")
        parts = ["gmail", "users", "messages", "list"]
    elif resource == "history" and set(params) <= {"startHistoryId", "historyTypes", "maxResults", "pageToken"}:
        if params.get("historyTypes") != "messageAdded" or not str(params.get("startHistoryId", "")).isdigit():
            raise ValueError("Invalid history request")
        parts = ["gmail", "users", "history", "list"]
    elif re.fullmatch(r"messages/[a-fA-F0-9]{1,100}", resource) and set(params) <= {"format", "metadataHeaders"}:
        if params.get("format") not in {"metadata", "full"}:
            raise ValueError("Invalid message format")
        if "metadataHeaders" in params and params["metadataHeaders"] != "From":
            raise ValueError("Invalid headers")
        return ["gmail", "users", "messages", "get"], {"userId": "me", "id": resource.split("/")[1],
            **{k: [v] if k == "metadataHeaders" else v for k, v in params.items()}}
    else:
        raise ValueError("Unapproved operation")
    if type(params.get("maxResults")) is not int or not 1 <= params["maxResults"] <= 100:
        raise ValueError("Invalid page size")
    if "pageToken" in params and (not isinstance(params["pageToken"], str) or len(params["pageToken"]) > 4096):
        raise ValueError("Invalid page token")
    return parts, {"userId": "me", **{k: [v] if k == "historyTypes" else v for k, v in params.items()}}


def inspect_module(module, home, expected_token=None):
    token = Path(module.TOKEN_PATH)
    if token != (expected_token or home / "google_token.json") or token.is_symlink():
        return {"ready": False, "reason": "workspace_home_mismatch"}
    if not token.is_file():
        return {"ready": False, "reason": "workspace_connection_unavailable"}
    if token.stat().st_size > 1024 * 1024:
        return {"ready": False, "reason": "workspace_connection_invalid"}
    # Inspect only the scope contract. Do not return or copy the credential fields.
    payload = json.loads(token.read_text(encoding="utf-8"))
    scopes = payload.get("scopes", payload.get("scope", []))
    scopes = scopes.split() if isinstance(scopes, str) else scopes
    if not isinstance(scopes, list) or not all(isinstance(scope, str) for scope in scopes) or READONLY not in scopes:
        return {"ready": False, "reason": "mail_scope_unsupported"}
    if not all(callable(getattr(module, name, None)) for name in ("_gws_binary", "_run_gws", "build_service")):
        return {"ready": False, "reason": "workspace_contract_changed"}
    return {"ready": True, "provider": "hermes-google-workspace", "readonly": True,
            "backend": "gws" if module._gws_binary() else "python"}


def execute(module, resource, params, *, prefer_python=False):
    parts, arguments = operation(resource, params)
    if not prefer_python and module._gws_binary():
        return module._run_gws(parts, params=arguments)
    service = module.build_service("gmail", "v1")
    try:
        users = service.users()
        if resource == "profile":
            request = users.getProfile(**arguments)
        elif resource == "history":
            request = users.history().list(**arguments)
        elif resource == "messages":
            request = users.messages().list(**arguments)
        else:
            request = users.messages().get(**arguments)
        return request.execute(num_retries=0)
    finally:
        if callable(getattr(service, "close", None)):
            service.close()


class WorkspaceApi:
    def __init__(self, *, home=None, script=None, should_cancel=lambda: False, prefer_python=False, account_id=None):
        if home is None:
            try:
                from hermes_constants import get_hermes_home
                home = get_hermes_home()
            except ImportError:
                raise CareerError("workspace_runtime_unavailable", "HermesのGoogle Workspace接続を確認できません。", 503) from None
        self.home = Path(home).resolve()
        self.should_cancel = should_cancel
        self.prefer_python = prefer_python
        self.account_id = account_id
        self.response_bytes = 0
        self.http_requests = 0
        self.script = Path(script) if script else self.home / "skills/productivity/google-workspace/scripts/google_api.py"
        if not self.script.is_file():
            raise CareerError("workspace_skill_unavailable", "Hermesのgoogle-workspace Skillを確認してください。", 503)

    def _call(self, request):
        names = {"HOME", "USER", "PATH", "LANG", "LC_ALL", "TMPDIR", "TEMP", "TMP", "SYSTEMROOT", "HERMES_GWS_BIN",
                 "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
                 "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE"}
        env = {k: v for k, v in os.environ.items() if k in names}
        env.update(HERMES_HOME=str(self.home), PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="utf-8")
        python = os.environ.get("CAREER_WORKSPACE_PYTHON", sys.executable)
        if not Path(python).is_absolute():
            raise CareerError("workspace_runtime_unavailable", "Google Workspaceの実行環境を確認してください。", 503)
        command = [python, "-m", "career_core.workspace_reader", str(self.script), str(self.home)]
        try:
            if self.account_id is not None:
                from .google_accounts import GoogleAccounts
                accounts = GoogleAccounts(self.home)
                # Serialize refresh and disconnect across processes so a late
                # refresh cannot recreate a disconnected account's credentials.
                with accounts.lock():
                    accounts.path_for(self.account_id)
                    output = run_private(command, {**request, 'account_id': self.account_id},
                        cwd=Path(__file__).resolve().parents[1], env=env, timeout=25,
                        limit=MAX_OUTPUT, should_cancel=self.should_cancel)
            else:
                output = run_private(command, request, cwd=Path(__file__).resolve().parents[1], env=env,
                    timeout=25, limit=MAX_OUTPUT, should_cancel=self.should_cancel)
        except TimeoutError:
            raise CareerError("workspace_timeout", "Google Workspaceからの応答を確認できません。", 503) from None
        except OSError:
            raise CareerError("workspace_runtime_unavailable", "Google Workspaceの読取処理を開始できません。", 503) from None
        except ValueError:
            raise CareerError("workspace_unavailable", "Google Workspaceの取得結果を確認できません。", 503) from None
        try:
            value = json.loads(output)
        except (ValueError, UnicodeError):
            raise CareerError("workspace_unavailable", "Google Workspaceの取得結果を確認できません。", 503) from None
        if not isinstance(value, dict):
            raise CareerError("workspace_unavailable", "Google Workspaceの取得結果を確認できません。", 503)
        if value.get("error"):
            code = value["error"]
            if self.account_id is not None and code == 'authentication_required':
                from .google_accounts import GoogleAccounts
                GoogleAccounts(self.home).mark_reauth(self.account_id)
            allowed = {"mail_cursor_expired", "authentication_required", "mail_scope_unsupported", "workspace_home_mismatch",
                       "workspace_connection_unavailable", "workspace_connection_invalid", "workspace_contract_changed",
                       "workspace_dependency_unavailable", "workspace_permission_denied"}
            from .mail_transport import MailReadError
            allowed.update({"mail_rate_limited", "mail_daily_limit", "mail_domain_policy", "mail_message_missing", "mail_page_expired", "mail_bad_request"})
            raise MailReadError(code if code in allowed else "workspace_unavailable", value.get("retry_after", 0),
                                http_status=value.get("http_status"), limit_kind=value.get("limit_kind"))
        if not isinstance(value.get("data"), dict):
            raise CareerError("workspace_unavailable", "Google Workspaceの取得結果を確認できません。", 503)
        return value["data"]

    def status(self):
        return self._call({"inspect": True, "python": self.prefer_python})

    def get(self, resource, **params):
        try:
            operation(resource, params)
        except (ValueError, TypeError):
            raise CareerError("mail_operation_denied", "このメール操作は利用できません。", 403) from None
        return self._call({"resource": resource, "params": params, "python": self.prefer_python})

    def get_many(self, ids, *, format="metadata", batch_size=1, concurrency=1):
        result = self._call({"batch": {"ids": ids, "format": format,
                                      "batch_size": batch_size, "concurrency": concurrency}})
        metrics = result.get('metrics', {})
        self.response_bytes += metrics.get('response_bytes', 0)
        self.http_requests += metrics.get('http_requests', 0)
        return result['items']

    def close(self):
        pass


def connected_account_details(account_id=None):
    from .gmail_reader import account_reference
    from .mail_store import normalize_address
    api = WorkspaceApi(prefer_python=True, **({'account_id': account_id} if account_id is not None else {}))
    try:
        state = api.status()
        if not state.get("ready"):
            raise CareerError(state.get("reason", "workspace_unavailable"), "Hermesの既存Google Workspace接続を確認してください。", 409)
        address = api.get("profile").get("emailAddress")
        if not isinstance(address, str) or not address.strip() or "@" not in address:
            raise CareerError("mail_account_unavailable", "接続済みGmailのアカウントを確認できません。", 409)
        try:
            address = normalize_address(address)
        except CareerError:
            raise CareerError("mail_account_unavailable", "接続済みGmailのアカウントを確認できません。", 409) from None
        identity = account_reference(address)
        if account_id is not None and identity != account_id:
            raise CareerError('mail_account_mismatch', '接続したGmailが選択したアカウントと異なります。', 409)
        return {
            "id": identity,
            "email": address,
            "provider": state.get("provider", "hermes-google-workspace"),
            "read_surface": "gmail.readonly",
        }
    finally:
        api.close()


def connected_account(account_id=None):
    return connected_account_details(**({'account_id': account_id} if account_id is not None else {}))["id"]


def connected_accounts_details():
    try:
        from hermes_constants import get_hermes_home
    except ImportError:
        return [connected_account_details()]
    from .google_accounts import GoogleAccounts
    accounts = GoogleAccounts(get_hermes_home())
    rows = accounts.accounts()
    if not rows:
        return [connected_account_details()]
    return [{**row, 'provider': 'hermes-google-workspace', 'read_surface': 'gmail.readonly'} for row in rows]


def child_main():
    request = json.loads(sys.stdin.buffer.read(16000))
    script, home = Path(sys.argv[1]), Path(sys.argv[2])
    captured = io.StringIO()
    try:
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            sys.path.insert(0, str(script.parent))
            spec = importlib.util.spec_from_file_location("career_workspace_adapter", script)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            selected = request.get('account_id')
            expected = None
            if selected is not None:
                from .google_accounts import GoogleAccounts
                if Path(module.TOKEN_PATH) != home / 'google_token.json':
                    raise CareerError('workspace_home_mismatch', 'Google接続先を確認してください。', 409)
                expected = GoogleAccounts(home).path_for(selected)
                module.TOKEN_PATH = expected
            status = inspect_module(module, home, expected)
            if request.get("inspect"):
                result = {"data": status}
            elif not status["ready"]:
                result = {"error": status["reason"]}
            elif "batch" in request:
                from .mail_transport import read_batch
                result = {"data": read_batch(module, **request["batch"])}
            else:
                result = {"data": execute(module, request["resource"], request["params"], prefer_python=request.get("python") is True)}
    except (Exception, SystemExit) as error:
        from .mail_transport import provider_error
        result = provider_error(error, request.get("resource", "batch"), captured.getvalue())
    encoded = json.dumps(result, ensure_ascii=False).encode("utf-8")
    sys.stdout.buffer.write(encoded if len(encoded) <= MAX_OUTPUT else b'{"error":"workspace_unavailable"}')


if __name__ == "__main__":
    child_main()
