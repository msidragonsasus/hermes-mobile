"""Explicit-account, read-only CLI bridge for the ordinary Hermes agent.

``accounts`` lists connection metadata. ``read`` accepts bounded JSON on stdin.
Successful stdout is the requested tool result, never a diagnostic log.
"""
import argparse
from contextlib import redirect_stdout, redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
import re
import sys

from .google_accounts import GoogleAccounts, account_id
from .store import CareerError


def read_account(home, payload):
    if not isinstance(payload, dict) or set(payload) - {'account_id', 'action', 'query', 'message_id', 'max_results'}:
        raise CareerError('invalid_request', 'アカウントと読み取り操作を指定してください。')
    identity, action = payload.get('account_id'), payload.get('action')
    if action not in {'profile', 'search', 'get'}:
        raise CareerError('mail_operation_denied', 'Gmailの読み取りだけを利用できます。', 403)
    if action == 'search' and (not isinstance(payload.get('query'), str) or not 1 <= len(payload['query']) <= 4096):
        raise CareerError('invalid_request', '検索条件を指定してください。')
    if action == 'get' and not re.fullmatch('[a-fA-F0-9]{1,100}', str(payload.get('message_id', ''))):
        raise CareerError('invalid_request', 'メールIDを指定してください。')
    maximum = payload.get('max_results', 10)
    if type(maximum) is not int or not 1 <= maximum <= 50:
        raise CareerError('invalid_request', '検索件数は1件から50件で指定してください。')
    store = GoogleAccounts(home)
    captured, errors = io.StringIO(), io.StringIO()
    with store.lock(), redirect_stdout(captured), redirect_stderr(errors):
        token = store.path_for(identity)
        script = Path(home) / 'skills/productivity/google-workspace/scripts/google_api.py'
        sys.path.insert(0, str(script.parent))
        spec = importlib.util.spec_from_file_location('hermes_connected_google_read', script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        if Path(module.TOKEN_PATH) != Path(home) / 'google_token.json':
            raise CareerError('workspace_home_mismatch', 'Google連携の実行環境を確認してください。', 409)
        module.TOKEN_PATH = token
        module._gws_binary = lambda: None
        from .workspace_reader import inspect_module
        status = inspect_module(module, Path(home), token)
        if not status.get('ready'):
            raise CareerError(status.get('reason', 'authentication_required'), 'Google接続を確認してください。', 409)
        service = module.build_service('gmail', 'v1')
        try:
            profile = service.users().getProfile(userId='me').execute(num_retries=0)
        finally:
            service.close()
        if account_id(profile.get('emailAddress', '')) != identity:
            raise CareerError('mail_account_mismatch', '選択したGoogleアカウントを確認してください。', 409)
        if action == 'profile':
            return {'account_id': identity, 'email': profile['emailAddress']}
        args = argparse.Namespace(query=payload.get('query'), max=maximum, message_id=payload.get('message_id'))
        if action == 'search':
            module.gmail_search(args)
        else:
            module.gmail_get(args)
    output = captured.getvalue()
    if len(output.encode('utf-8')) > 8 * 1024 * 1024:
        raise CareerError('mail_response_too_large', '取得するメールを絞り込んでください。', 413)
    return {'account_id': identity, 'result': json.loads(output)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['accounts', 'read'])
    args = parser.parse_args()
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
        if args.action == 'accounts':
            result = {'accounts': GoogleAccounts(home).accounts()}
        else:
            content = sys.stdin.buffer.read(16385)
            if len(content) > 16384:
                raise ValueError()
            result = read_account(home, json.loads(content))
        print(json.dumps(result, ensure_ascii=False))
    except CareerError as error:
        print(json.dumps({'error': error.code}))
        return 1
    except (Exception, SystemExit):
        # Provider errors and refresh diagnostics can contain credentials.
        print(json.dumps({'error': 'google_read_unavailable'}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
