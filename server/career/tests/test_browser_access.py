"""Issuer/control checks on a temporary dummy DB only; never production sessions/vault."""
import os
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.browser_access import BrowserAccessManager
from career_core.store import CareerError
import test_mypage_browser_credentials as dummy_fixture


class BrowserAccessTests(unittest.TestCase):
    def setUp(self):
        self.fixture = dummy_fixture.BrowserCredentialTests('test_disabled_by_default_does_not_touch_vault')
        self.fixture.setUp()
        self.clock = 100.0
        self.manager = BrowserAccessManager(
            session_key=lambda s: s.get('id') if isinstance(s, dict) and s.get('verified') is True else None,
            authorize_connection=lambda s, target: target == ('dummy-company', 'phone', 1, self.fixture.payload['url']),
            clock=lambda: self.clock)
        self.session = {'id': 'dummy-authenticated-session', 'verified': True}

    def tearDown(self):
        self.fixture.tearDown()
        self.fixture.doCleanups()

    def client(self):
        app = FastAPI()
        app.state.auth_required = True
        app.state.career_browser_access = self.manager
        @app.middleware('http')
        async def verified_dummy_session(request, call_next):
            request.state.session = dict(self.session)
            return await call_next(request)
        app.include_router(create_router(lambda: self.fixture.store), prefix='/api/plugins/career')
        return TestClient(app, base_url='https://dummy-gateway.example.test')

    def issue(self):
        return self.manager.issue(self.fixture.store, self.fixture.payload, self.session)

    def test_default_disabled_missing_adapters_and_flag_alone_deny_before_metadata(self):
        with self.assertRaises(ValueError):
            BrowserAccessManager().enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}), patch('career_core.browser_access.MyPageBotStore.get', side_effect=AssertionError('must not read')):
            with self.assertRaises(CareerError):
                self.issue()
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {}, clear=True), patch('career_core.browser_access.MyPageBotStore.get', side_effect=AssertionError('must not read')):
            with self.assertRaises(CareerError):
                self.issue()

    def test_dummy_grant_same_request_one_use_and_status_has_no_secret_url_or_session_id(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}), patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
            grant = self.issue()
            with self.assertRaises(CareerError):
                grant.consume(grant.target, dict(self.session))
            grant.consume(grant.target, self.session)
            with self.assertRaises(CareerError):
                grant.consume(grant.target, self.session)
            status = self.manager.status(self.session)
        self.assertTrue(status['grants'][0]['used'])
        for value in ('dummy-password', 'dummy-id', self.session['id'], 'https://'):
            self.assertNotIn(value, str(status))

    def test_expiry_and_disable_invalidate_outstanding_grants(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            expired = self.issue()
            self.clock += 61
            with self.assertRaises(CareerError):
                expired.consume(expired.target, self.session)
            active = self.issue()
            self.manager.disable()
            self.manager.enable(single_process=True, fresh_approval=True)
            with self.assertRaises(CareerError):
                active.consume(active.target, self.session)

    def test_revoke_is_session_scoped_and_resume_does_not_revive_old_grants(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            old = self.issue()
            self.manager.revoke({'id': 'different-dummy-session', 'verified': True})
            self.assertFalse(self.manager.status(self.session)['grants'][0]['revoked'])
            self.manager.revoke(self.session, company_id='dummy-company', profile='phone')
            self.manager.resume(self.session, company_id='dummy-company', profile='phone')
            with self.assertRaises(CareerError):
                old.consume(old.target, self.session)
            fresh = self.issue()
            fresh.consume(fresh.target, self.session)

    def test_unverified_session_permission_and_current_snapshot_deny_before_vault(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}), patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
            with self.assertRaises(CareerError):
                self.manager.issue(self.fixture.store, self.fixture.payload, {'id': 'forged', 'verified': False})
            with self.assertRaises(CareerError):
                self.manager.issue(self.fixture.store, dict(self.fixture.payload, expected_version=2), self.session)
            self.manager._authorize = lambda s, t: 'truthy-is-not-permission'
            with self.assertRaises(CareerError):
                self.issue()

    def test_authenticated_dummy_route_issues_consumes_and_revoke_prevents_new_reads(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}), patch('career_core.mypage_bot.MyPageBotStore.secret_store', return_value=self.fixture.secrets):
            client = self.client()
            route = '/api/plugins/career/mypage/browser-credentials'
            result = client.post(route, json=self.fixture.payload)
            self.assertEqual(200, result.status_code)
            self.assertEqual('dummy-id', result.json()['login_id'])
            self.assertIn('no-store', result.headers['cache-control'])
            status = client.get('/api/plugins/career/mypage/browser-access')
            self.assertTrue(status.json()['grants'][0]['used'])
            self.assertNotIn('dummy-password', status.text)
            self.assertIn('no-store', status.headers['cache-control'])
            self.assertEqual(200, client.post('/api/plugins/career/mypage/browser-access/revoke', json={}).status_code)
            with patch.object(self.fixture.secrets, 'read', side_effect=AssertionError('must not read')):
                self.assertEqual(403, client.post(route, json=self.fixture.payload).status_code)

    def test_control_route_rejects_null_scope_wrong_type_and_oversized_requests(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            client = self.client()
            route = '/api/plugins/career/mypage/browser-access/revoke'
            self.assertEqual(400, client.post(route, json={'company_id': None, 'profile': None}).status_code)
            self.assertEqual(415, client.post(route, content='{}', headers={'content-type': 'text/plain'}).status_code)
            self.assertEqual(413, client.post(route, content=' '*3000, headers={'content-type': 'application/json'}).status_code)
            self.assertEqual(413, client.post('/api/plugins/career/mypage/browser-credentials', content=' '*9000, headers={'content-type': 'application/json'}).status_code)

    def test_enable_requires_single_process_and_fresh_explicit_approval(self):
        with self.assertRaises(ValueError):
            self.manager.enable()
        with self.assertRaises(ValueError):
            self.manager.enable(single_process=True)
        with patch('career_core.browser_access.os.getpid', return_value=-1):
            with self.assertRaises(ValueError):
                self.manager.enable(single_process=True, fresh_approval=True)

    def test_target_block_survives_session_revoke_and_session_only_resume(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            self.manager.revoke(self.session, company_id='dummy-company', profile='phone')
            self.manager.revoke(self.session)
            self.manager.resume(self.session)
            with patch.object(self.manager, '_authorize', side_effect=AssertionError('must not authorize')):
                with self.assertRaises(CareerError):
                    self.issue()
            self.manager.resume(self.session, company_id='dummy-company', profile='phone')
            self.issue()

    def test_generation_rejects_pre_disable_authorization_and_flag_off_denies_consume(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        def old_authorization(session, target):
            self.manager.disable()
            self.manager.enable(single_process=True, fresh_approval=True)
            return True
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            with patch.object(self.manager, '_authorize', old_authorization):
                with self.assertRaises(CareerError):
                    self.issue()
            grant = self.issue()
            with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '0'}):
                with self.assertRaises(CareerError):
                    grant.consume(grant.target, self.session)

    def test_per_owner_ticket_capacity_does_not_starve_another_session(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            for _ in range(8):
                self.issue()
            with self.assertRaises(CareerError):
                self.issue()
            other = dict(self.session, id='another-dummy-session')
            grant = self.manager.issue(self.fixture.store, self.fixture.payload, other)
            grant.consume(grant.target, other)

    def test_revoke_capacity_fails_closed_and_session_resume_never_revives_old_ticket(self):
        self.manager.enable(single_process=True, fresh_approval=True)
        with patch.dict(os.environ, {'CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED': '1'}):
            grant = self.issue()
            self.manager.revoke(self.session)
            self.manager.resume(self.session)
            with self.assertRaises(CareerError):
                grant.consume(grant.target, self.session)
            fresh = self.issue()
            for i in range(32):
                self.manager.revoke(self.session, company_id=f'dummy-{i}', profile='phone')
            result = self.manager.revoke(self.session, company_id='dummy-company', profile='phone')
            self.assertFalse(result['disabled'])
            with self.assertRaises(CareerError):
                fresh.consume(fresh.target, self.session)
            with patch.object(self.manager, '_authorize', side_effect=AssertionError('must not authorize')), patch('career_core.browser_access.MyPageBotStore.get', side_effect=AssertionError('must not read')):
                with self.assertRaises(CareerError):
                    self.issue()
            other = dict(self.session, id='another-dummy-session')
            self.manager.issue(self.fixture.store, self.fixture.payload, other)
            self.manager._blocked_sessions = {bytes(str(i), 'ascii') for i in range(256)}
            self.assertTrue(self.manager.revoke(self.session)['disabled'])
            self.assertFalse(self.manager.status(self.session)['enabled'])


if __name__ == '__main__':
    unittest.main()
