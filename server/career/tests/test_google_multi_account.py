"""Multi-account isolation using synthetic identities and provider responses only."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx

import test_google_connection as original
from career_core.google_accounts import GoogleAccounts, account_id
from career_core.google_connection import GoogleConnection, SCOPE
from career_core.store import CareerError
from career_core.workspace_reader import WorkspaceApi
from career_core.google_connected_accounts import read_account


class MultiAccountTests(unittest.TestCase):
    setUp = original.GoogleConnectionTests.setUp
    payload = original.GoogleConnectionTests.payload
    assert_error = original.GoogleConnectionTests.assert_error

    def google(self, request):
        self.calls.append(request)
        if request.url.path == '/token':
            return httpx.Response(200, json=self.token_result)
        self.assertEqual('/gmail/v1/users/me/profile', request.url.path)
        return httpx.Response(200, json={'emailAddress': getattr(self, 'email', 'jobs@example.test')})

    def add(self, email, owner='owner'):
        self.email = email
        return self.connector.complete(owner, self.payload(owner))['account_id']

    def test_second_and_fifth_accounts_never_replace_the_first(self):
        self.add('jobs@example.test')
        original_token = (self.home / 'google_token.json').read_bytes()
        for email in ('personal@example.test', 'school@example.test', 'another@example.test', 'fifth@example.test'):
            self.add(email)
        status = self.connector.status(verify=False)
        self.assertEqual(5, len(status['accounts']))
        self.assertEqual(original_token, (self.home / 'google_token.json').read_bytes())
        self.assertEqual(10, len(self.calls))
        self.assertNotIn('synthetic-refresh', json.dumps(status))

    def test_duplicate_add_does_not_replace_any_credentials(self):
        identity = self.add('jobs@example.test')
        store = GoogleAccounts(self.home)
        before = store.path_for(identity).read_bytes()
        self.assert_error('google_account_exists', self.connector.complete, 'owner', self.payload())
        self.assertEqual(before, store.path_for(identity).read_bytes())

    def test_reconnect_is_bound_to_the_selected_account_and_retains_label(self):
        first = self.add('jobs@example.test')
        second = self.add('personal@example.test')
        store = GoogleAccounts(self.home)
        first_before = store.path_for(first).read_bytes()
        second_before = store.path_for(second).read_bytes()
        store.update(second, label='個人')
        pending = self.connector.begin('owner', {'account_id': second})
        self.email = 'jobs@example.test'
        self.assert_error('google_account_mismatch', self.connector.complete, 'owner', {'flow_id': pending['flow_id'], 'code': 'wrong-account'})
        self.assertEqual(second_before, store.path_for(second).read_bytes())
        self.email = 'personal@example.test'
        self.token_result['refresh_token'] = 'synthetic-refreshed-personal'
        pending = self.connector.begin('owner', {'account_id': second})
        result = self.connector.complete('owner', {'flow_id': pending['flow_id'], 'code': 'new-consent'})
        self.assertEqual(first_before, store.path_for(first).read_bytes())
        self.assertIn(second_before, [path.read_bytes() for path in (store.root / 'backups').glob('*.json')])
        self.assertEqual('個人', next(row for row in result['accounts'] if row['id'] == second)['label'])

    def test_disconnect_does_not_revive_from_a_late_consent_or_use_another_account(self):
        first = self.add('jobs@example.test')
        second = self.add('personal@example.test')
        pending = self.connector.begin('owner', {'account_id': second})
        store = GoogleAccounts(self.home)
        first_before = store.path_for(first).read_bytes()
        self.connector.manage('disconnect', {'account_id': second})
        self.assert_error('google_flow_expired', self.connector.complete, 'owner', {'flow_id': pending['flow_id'], 'code': 'late-consent'})
        self.assert_error('google_account_missing', store.path_for, second)
        self.assertEqual(first_before, store.path_for(first).read_bytes())
        self.assertEqual([first], [row['id'] for row in store.accounts()])

    def test_one_invalid_account_does_not_hide_the_others(self):
        first = self.add('jobs@example.test')
        second = self.add('personal@example.test')
        GoogleAccounts(self.home).mark_reauth(second)
        rows = {row['id']: row for row in self.connector.status(verify=False)['accounts']}
        self.assertEqual('connected', rows[first]['state'])
        self.assertEqual('reauth_required', rows[second]['state'])

    def test_failed_metadata_commit_restores_the_exact_old_credential(self):
        identity = self.add('jobs@example.test')
        store = GoogleAccounts(self.home)
        before = store.path_for(identity).read_bytes()
        revision = store.revision(identity)
        with patch.object(store, '_write', side_effect=OSError('synthetic commit failure')):
            with self.assertRaises(OSError):
                store.save('jobs@example.test', {'token': 'SYNTHETIC_NEW'}, reconnect=identity, revision=revision)
        self.assertEqual(before, store.path_for(identity).read_bytes())
        self.assertEqual('connected', store.accounts()[0]['state'])

    def test_labels_paths_and_corrupt_registry_are_fail_closed(self):
        identity = self.add('jobs@example.test')
        store = GoogleAccounts(self.home)
        self.assert_error('invalid_request', lambda: store.update(identity, label='bad\nlabel'))
        self.assert_error('google_account_missing', store.path_for, '../google_token.json')
        (store.root / 'accounts.json').write_text('invalid', encoding='utf-8')
        self.assert_error('google_storage_unavailable', store.path_for, identity)


class WorkspaceAccountIsolationTests(unittest.TestCase):
    def test_child_process_uses_only_the_explicit_account_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            script = home / 'skills/productivity/google-workspace/scripts/google_api.py'
            script.parent.mkdir(parents=True)
            script.write_text('''
import json,os
from pathlib import Path
TOKEN_PATH=Path(os.environ['HERMES_HOME'])/'google_token.json'
def _gws_binary(): return 'synthetic'
def build_service(api,version): raise AssertionError('not used')
def _run_gws(parts, *, params):
    return {'emailAddress':json.loads(TOKEN_PATH.read_text())['fixture_email']}
''', encoding='utf-8')
            store = GoogleAccounts(home)
            for email in ('jobs@example.test', 'personal@example.test'):
                legacy = home / 'google_token.json'
                store.save(email, {'fixture_email': email, 'scopes': [SCOPE]}, legacy_before='present' if legacy.exists() else None)
            for email in ('personal@example.test', 'jobs@example.test'):
                api = WorkspaceApi(home=home, account_id=account_id(email))
                self.assertEqual(email, api.get('profile')['emailAddress'])
            store.update(account_id('personal@example.test'), disconnect=True)
            with self.assertRaises(CareerError):
                WorkspaceApi(home=home, account_id=account_id('personal@example.test')).get('profile')
            self.assertEqual('jobs@example.test', WorkspaceApi(home=home, account_id=account_id('jobs@example.test')).get('profile')['emailAddress'])

    def test_ordinary_hermes_read_bridge_requires_explicit_account_and_rejects_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            script = home / 'skills/productivity/google-workspace/scripts/google_api.py'
            script.parent.mkdir(parents=True)
            script.write_text('''
import json,os
from pathlib import Path
TOKEN_PATH=Path(os.environ['HERMES_HOME'])/'google_token.json'
def _gws_binary(): return None
def _run_gws(*args,**kwargs): raise AssertionError('not used')
class Service:
    def users(self): return self
    def getProfile(self,**kwargs): return self
    def execute(self,**kwargs): return {'emailAddress':json.loads(TOKEN_PATH.read_text())['fixture_email']}
    def close(self): pass
def build_service(api,version): return Service()
def gmail_search(args): print(json.dumps({'mailbox':json.loads(TOKEN_PATH.read_text())['fixture_email']}))
def gmail_get(args): print(json.dumps({'id':args.message_id}))
'''.replace("Path(os.environ['HERMES_HOME'])", repr(str(home)) + ' and Path(' + repr(str(home)) + ')'), encoding='utf-8')
            store = GoogleAccounts(home)
            for email in ('jobs@example.test', 'personal@example.test'):
                store.save(email, {'fixture_email': email, 'scopes': [SCOPE]},
                           legacy_before='present' if (home / 'google_token.json').exists() else None)
            selected = account_id('personal@example.test')
            self.assertEqual('personal@example.test', read_account(home, {'account_id': selected, 'action': 'search', 'query': 'requested by user'})['result']['mailbox'])
            for payload in ({'action': 'search', 'query': 'all'}, {'account_id': selected, 'action': 'send'},
                            {'account_id': '../google_token.json', 'action': 'profile'}):
                with self.assertRaises(CareerError):
                    read_account(home, payload)


if __name__ == '__main__':
    unittest.main()
