"""Synthetic sessions and temporary dummy vault only; no installed auth or real secrets."""
from dataclasses import dataclass, replace
from concurrent.futures import ThreadPoolExecutor
import os
import sys
import time
import types
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.browser_consent import BrowserConsentAccess, dashboard_session_key
from career_core.mypage_browser_credentials import browser_credentials
from career_core.store import CareerError
import test_mypage_browser_credentials as dummy_fixture


@dataclass(frozen=True)
class DummySession:
    # Exact public Session contract from the local Dashboard source.
    user_id: str = 'dummy-user'
    email: str = ''
    display_name: str = ''
    org_id: str = ''
    provider: str = 'dummy-provider'
    expires_at: int = 4102444800
    access_token: str = 'dummy-opaque-access-A'
    refresh_token: str = 'dummy-refresh-never-used'


class BrowserConsentTests(unittest.TestCase):
    def setUp(self):
        self.fixture = dummy_fixture.BrowserCredentialTests('test_disabled_by_default_does_not_touch_vault')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        base = types.ModuleType('hermes_cli.dashboard_auth.base')
        base.Session = DummySession
        session_contract = patch.dict(sys.modules, {'hermes_cli.dashboard_auth.base': base})
        session_contract.start()
        self.addCleanup(session_contract.stop)
        self.clock = 100.0
        self.manager = BrowserConsentAccess(clock=lambda: self.clock)
        self.addCleanup(self.manager.close)
        self.session = DummySession()
        self.flag = patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'})
        self.flag.start()
        self.addCleanup(self.flag.stop)

    def prepare(self, session=None):
        return self.manager.prepare(self.fixture.store, self.fixture.payload, session or self.session)['approval_id']

    def grant(self, approval_id, payload=None, session=None):
        return self.manager.approve_and_issue(self.fixture.store, payload or self.fixture.payload, session or self.session, approval_id)

    def client(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def verified_dummy_session(request, call_next):
            if self.session is not None:
                request.state.session = self.session
            return await call_next(request)
        app.include_router(create_router(lambda: self.fixture.store, browser_consent=True), prefix='/api/plugins/career')
        return TestClient(app, base_url='https://dummy-gateway.example.test')

    def test_installed_adapter_and_flag_alone_never_read_secrets(self):
        with self.client() as client, patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
            self.assertFalse(client.get('/api/plugins/career/mypage/browser-access').json()['enabled'])
            route = '/api/plugins/career/mypage/browser-credentials'
            self.assertEqual(400, client.post(route, json=self.fixture.payload, headers={'x-hermes-browser-consent': 'once'}).status_code)
            with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '0'}):
                self.assertEqual(403, client.post('/api/plugins/career/mypage/browser-access/prepare', json=self.fixture.payload, headers={'x-hermes-browser-consent': 'once'}).status_code)

    def test_prepare_reads_only_metadata_and_requires_click_before_issue(self):
        with patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
            self.prepare()
            self.assertFalse(self.manager.status(self.session)['enabled'])
            with self.assertRaises(CareerError):
                self.manager.issue(self.fixture.store, self.fixture.payload, self.session)

    def test_session_adapter_uses_verified_token_not_identity_cookie_or_refresh(self):
        key = dashboard_session_key(b'dummy-salt')
        self.assertIsNone(key({'provider': 'dummy', 'access_token': 'forged'}))
        self.assertIsNone(key(replace(self.session, expires_at=0)))
        self.assertIsNone(key(replace(self.session, access_token='')))
        self.assertNotEqual(key(self.session), key(replace(self.session, access_token='dummy-other-login')))
        self.assertEqual(key(self.session), key(replace(self.session, refresh_token='different-refresh')))
        self.assertNotIn(self.session.access_token, key(self.session))

    def test_route_full_once_flow_no_store_and_replay_denial(self):
        headers = {'x-hermes-browser-consent': 'once'}
        with self.client() as client, patch('career_core.mypage_bot.MyPageBotStore.secret_store', return_value=self.fixture.secrets):
            with patch.object(self.fixture.secrets, 'read', wraps=self.fixture.secrets.read) as read:
                prepared = client.post('/api/plugins/career/mypage/browser-access/prepare', json=self.fixture.payload, headers=headers)
                self.assertEqual(200, prepared.status_code)
                read.assert_not_called()
                self.assertIn('no-store', prepared.headers['cache-control'])
                payload = dict(self.fixture.payload, approval_id=prepared.json()['approval_id'], consent_mode='allow_target')
                result = client.post('/api/plugins/career/mypage/browser-credentials', json=payload, headers=headers)
                self.assertEqual(200, result.status_code)
                self.assertEqual('dummy-id', result.json()['login_id'])
                self.assertIn('no-store', result.headers['cache-control'])
                self.assertEqual(403, client.post('/api/plugins/career/mypage/browser-credentials', json=payload, headers=headers).status_code)
                self.assertEqual(1, read.call_count)

    def test_rotation_another_login_and_expiry_do_not_share_approval(self):
        approval = self.prepare()
        for session in (replace(self.session, access_token='dummy-rotated'), replace(self.session, provider='other'), replace(self.session, expires_at=0)):
            with self.assertRaises(CareerError):
                self.grant(approval, session=session)
        self.clock += 60
        with self.assertRaises(CareerError):
            self.grant(approval)

    def test_mismatched_url_tenant_query_profile_or_version_denies_before_vault(self):
        for changes in ({'url': self.fixture.payload['url'] + '&tenant=other'}, {'profile': 'other'}, {'expected_version': 2}, {'company_id': 'other-company'}):
            with self.subTest(changes=changes), patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
                approval = self.prepare()
                with self.assertRaises(CareerError):
                    self.grant(approval, dict(self.fixture.payload, **changes))
                with self.assertRaises(CareerError):
                    self.grant(approval)

    def test_revoke_cancels_pending_and_issued_but_new_explicit_consent_works(self):
        pending = self.prepare()
        old = self.grant(self.prepare())
        self.manager.revoke(self.session, company_id='dummy-company', profile='phone')
        with self.assertRaises(CareerError):
            self.grant(pending)
        fresh = self.grant(self.prepare())
        with self.assertRaises(CareerError):
            old.consume(old.target, self.session)
        fresh.consume(fresh.target, self.session)

    def test_restart_other_worker_and_wrong_worker_revoke_fail_closed(self):
        pending = self.prepare()
        other = BrowserConsentAccess()
        self.addCleanup(other.close)
        with self.assertRaises(CareerError) as error:
            other.prepare(self.fixture.store, self.fixture.payload, self.session)
        self.assertEqual(503, error.exception.status)
        with self.assertRaises(CareerError):
            other.revoke(self.session)
        self.manager.close()
        with self.assertRaises(CareerError):
            other.approve_and_issue(self.fixture.store, self.fixture.payload, self.session, pending)

    def test_concurrent_confirmation_is_consumed_once(self):
        approval = self.prepare()
        def attempt(_):
            try:
                grant = self.grant(approval)
                return browser_credentials(self.fixture.store, self.fixture.payload, secrets=self.fixture.secrets, grant=grant, session=self.session)['login_id']
            except CareerError:
                return None
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(attempt, range(2)))
        self.assertEqual(1, results.count('dummy-id'))

    def test_http_cross_origin_simple_request_unverified_tls_and_size_are_rejected(self):
        route = '/api/plugins/career/mypage/browser-access/prepare'
        headers = {'x-hermes-browser-consent': 'once'}
        with self.client() as client:
            self.assertEqual(403, client.post(route, json=self.fixture.payload).status_code)
            self.assertEqual(403, client.post(route, json=self.fixture.payload, headers=dict(headers, origin='https://evil.example.test')).status_code)
            self.assertEqual(403, client.post(route, json=self.fixture.payload, headers=dict(headers, origin='http://dummy-gateway.example.test')).status_code)
            self.assertEqual(403, client.post(route, json=self.fixture.payload, headers=dict(headers, origin='https://dummy-gateway.example.test')).status_code)
            self.assertEqual(403, client.post(route, json=self.fixture.payload, headers=dict(headers, **{'sec-fetch-site': 'same-origin'})).status_code)
            self.assertEqual(403, client.post(route, json=self.fixture.payload, headers=dict(headers, **{'sec-fetch-mode': 'cors'})).status_code)
            self.assertEqual(403, client.post('/api/plugins/career/mypage/browser-credentials', json=dict(self.fixture.payload, approval_id='a'*64), headers=dict(headers, origin='https://dummy-gateway.example.test')).status_code)
            self.assertEqual(415, client.post(route, content='{}', headers=headers).status_code)
            self.assertEqual(413, client.post(route, content=' '*9000, headers=dict(headers, **{'content-type': 'application/json'})).status_code)
            self.session = None
            self.assertEqual(401, client.post(route, json=self.fixture.payload, headers=headers).status_code)

    def test_pending_capacity_and_session_expiry_at_final_consume(self):
        for _ in range(8):
            self.prepare()
        with self.assertRaises(CareerError):
            self.prepare()
        self.clock += 61
        grant = self.grant(self.prepare())
        with patch('career_core.browser_consent.time.time', return_value=4102444801):
            with self.assertRaises(CareerError):
                grant.consume(grant.target, self.session)

    def test_disable_discards_old_confirmation_and_flag_off_denies_consume(self):
        pending = self.prepare()
        grant = self.grant(self.prepare())
        self.manager.disable()
        with self.assertRaises(CareerError):
            self.grant(pending)
        with self.assertRaises(CareerError):
            grant.consume(grant.target, self.session)
        with self.assertRaises(CareerError):
            self.prepare()

    def test_close_is_sticky_and_fork_revoke_is_not_acknowledged(self):
        self.prepare()
        with patch('career_core.browser_consent.os.getpid', return_value=-1):
            with self.assertRaises(CareerError) as error:
                self.manager.revoke(self.session)
            self.assertEqual(503, error.exception.status)
        self.manager.close()
        with self.assertRaises(CareerError):
            self.prepare()

    def test_prepare_os_failure_has_only_sanitized_generic_error(self):
        with self.client() as client, patch('career_core.browser_consent.job_lock', side_effect=OSError('dummy-private-path')):
            result = client.post('/api/plugins/career/mypage/browser-access/prepare', json=self.fixture.payload, headers={'x-hermes-browser-consent': 'once'})
            self.assertEqual(503, result.status_code)
            self.assertNotIn('dummy-private-path', result.text)

    def test_first_confirmation_allows_automatic_reopen_until_30_minutes_or_revoke(self):
        self.grant(self.prepare())
        prepared = self.manager.prepare(self.fixture.store, self.fixture.payload, self.session)
        self.assertFalse(prepared['requires_confirmation'])
        self.assertLessEqual(prepared['permission_expires_in'], 1800)
        grant = self.manager.approve_and_issue(self.fixture.store, self.fixture.payload, self.session,
                                             prepared['approval_id'], 'use_target')
        grant.consume(grant.target, self.session)
        self.clock += 1801
        self.assertTrue(self.manager.prepare(self.fixture.store, self.fixture.payload, self.session)['requires_confirmation'])
        self.grant(self.prepare())
        self.manager.revoke(self.session)
        self.assertTrue(self.manager.prepare(self.fixture.store, self.fixture.payload, self.session)['requires_confirmation'])

    def test_auto_mode_cannot_create_permission_and_cancelled_scope_does_not_resume_silently(self):
        approval = self.prepare()
        with self.assertRaises(CareerError):
            self.manager.approve_and_issue(self.fixture.store, self.fixture.payload, self.session, approval, 'use_target')
        self.grant(self.prepare())
        pending = self.manager.prepare(self.fixture.store, self.fixture.payload, self.session)
        self.manager.revoke(self.session)
        with self.assertRaises(CareerError):
            self.manager.approve_and_issue(self.fixture.store, self.fixture.payload, self.session, pending['approval_id'], 'use_target')
        self.assertTrue(self.manager.prepare(self.fixture.store, self.fixture.payload, replace(self.session, access_token='dummy-rotated'))['requires_confirmation'])

    def test_native_http_revoke_clears_target_permission_and_web_revoke_is_rejected(self):
        headers = {'x-hermes-browser-consent': 'once'}
        with self.client() as client, patch('career_core.mypage_bot.MyPageBotStore.secret_store', return_value=self.fixture.secrets):
            route = '/api/plugins/career/mypage/browser-access/prepare'
            prepared = client.post(route, json=self.fixture.payload, headers=headers).json()
            client.post('/api/plugins/career/mypage/browser-credentials', json=dict(self.fixture.payload, approval_id=prepared['approval_id'], consent_mode='allow_target'), headers=headers)
            revoke = '/api/plugins/career/mypage/browser-access/revoke'
            self.assertEqual(403, client.post(revoke, json={}, headers=dict(headers, origin='https://dummy-gateway.example.test')).status_code)
            self.assertEqual(200, client.post(revoke, json={'company_id': 'dummy-company', 'profile': 'phone'}, headers=headers).status_code)
            self.assertTrue(client.post(route, json=self.fixture.payload, headers=headers).json()['requires_confirmation'])


if __name__ == '__main__':
    unittest.main()
