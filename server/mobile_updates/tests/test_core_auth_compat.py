"""Actual core mint/redeem middleware + actual password login, no live bootstrap.

Only web_server's unchanged, AST-extracted authorization/token definitions are
loaded instead of its side-effectful application bootstrap. Native issuer,
validator, profile scope, provider, login routes and session middleware are real.
"""
import ast
import hmac
import importlib.util
import os
from pathlib import Path
import secrets
import sys
import types
import unittest
from unittest.mock import patch

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient

from updates_core.api import create_router
from store_fixtures import make_root, write_release

try:
    import hermes_cli.dashboard_auth.native_http as native
    from hermes_cli.dashboard_auth import clear_providers, register_provider
    from hermes_cli.dashboard_auth.middleware import gated_auth_middleware
    from hermes_cli.dashboard_auth.routes import router as auth_router
    from hermes_cli.dashboard_auth.public_paths import PUBLIC_API_PATHS
    from gateway.runtime_bootstrap import TicketStore
    from gateway.session_authorities import SessionAuthorities
    from plugins.dashboard_auth.basic import BasicAuthProvider, hash_password
except ImportError:
    native = None

PREFIX = '/api/plugins/mobile_updates'


def core_auth_definitions():
    """Extract exact definitions; never import live web_server or mount plugins."""
    spec = importlib.util.find_spec('hermes_cli.web_server')
    tree = ast.parse(Path(spec.origin).read_text())
    names = {'_require_token', '_has_valid_session_token', '_has_valid_query_token', 'auth_middleware'}
    nodes = [node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
             and node.name in names]
    assert {node.name for node in nodes} == names
    for node in nodes:
        node.decorator_list = []
    module = types.ModuleType('hermes_cli.web_server')
    module.__dict__.update(Request=Request, HTTPException=HTTPException,
                           JSONResponse=JSONResponse, hmac=hmac,
                           _SESSION_TOKEN=secrets.token_urlsafe(32),
                           _SESSION_HEADER_NAME='x-hermes-session-token',
                           _PUBLIC_API_PATHS=PUBLIC_API_PATHS,
                           _QUERY_TOKEN_API_PATHS=frozenset({'/api/files/download'}))
    exec(compile(ast.Module(body=nodes, type_ignores=[]), spec.origin, 'exec'), module.__dict__)
    return module


class SocketPeer:
    """Synthetic ASGI transport captures the actual peer, never forwarded headers."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        scope['hermes.gateway_socket_peer'] = scope.get('client')
        await self.app(scope, receive, send)


@unittest.skipIf(native is None, 'installed core required for isolated integration')
class CoreAuthCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()
        self.addCleanup(self.holder.cleanup)
        self.home = self.root.parent
        self.env = patch.dict(os.environ, {'HERMES_HOME': str(self.home)})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.core = core_auth_definitions()
        self.modules = patch.dict(sys.modules, {'hermes_cli.web_server': self.core})
        self.modules.start()
        self.addCleanup(self.modules.stop)
        clear_providers()
        self.addCleanup(clear_providers)
        self.password = secrets.token_urlsafe(24)
        register_provider(BasicAuthProvider(username='synthetic-user',
                          password_hash=hash_password(self.password), secret=secrets.token_bytes(32)))
        self.store = TicketStore('synthetic-instance', frozenset({str(self.home)}))
        registry = SessionAuthorities(self.home)
        registry.add(self.home, types.SimpleNamespace(profile_id=str(self.home), instance_id=self.store.instance_id))
        self.runner = types.SimpleNamespace(session_authorities=registry, session_ticket_store=self.store,
            _draining=False, session_runtime_descriptor={'state': 'ready', 'instance_id': self.store.instance_id,
                                                       'served_profiles': registry.served_profiles()})
        app = FastAPI()
        app.state.auth_required = True
        app.state.gateway_runner = self.runner
        app.include_router(auth_router)
        app.include_router(create_router(), prefix=PREFIX)

        @app.middleware('http')
        async def real_auth(request, call_next):
            rejection = await native.authenticate_native_http(request)
            if rejection is not None:
                return rejection
            async def legacy_token_gate(request):
                return await self.core.auth_middleware(request, call_next)
            with native.native_profile_scope(request):
                return await gated_auth_middleware(request, legacy_token_gate)

        self.app = app
        self.client = TestClient(SocketPeer(app), client=('127.0.0.1', 40601))
        self.addCleanup(self.client.close)

    def headers(self, purpose='native-http', home=None):
        ticket = self.store.mint(profile_id=str(home or self.home), subject='synthetic-owner', purpose=purpose)
        return {native.HEADER: ticket}

    def config(self, enabled=True):
        import json
        path = self.home / 'mobile-updates.json'
        path.write_text(json.dumps({'schema': 'hermes-mobile-updates-config', 'protocol': 1,
                                   'enabled': enabled, 'root': str(self.root),
                                   'application_id': 'com.m57.hermescontrol'}))
        path.chmod(0o600)

    def test_real_native_grant_all_read_routes_and_hot_configuration(self):
        with patch.object(self.core, '_require_token', wraps=self.core._require_token) as guard:
            response = self.client.get(PREFIX + '/status', headers=self.headers())
            guard.assert_called_once()
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(False, response.json()['enabled'])
        self.config()
        _, record, apk = write_release(self.root, 2)
        for path in ('/status', '/latest', f"/releases/{record['release_id']}/apk"):
            with self.subTest(path=path):
                response = self.client.get(PREFIX + path, headers=self.headers())
                self.assertEqual(200, response.status_code, response.text)
                if path.endswith('/apk'):
                    self.assertEqual(apk, response.content)
        self.config(False)
        self.assertFalse(self.client.get(PREFIX + '/status', headers=self.headers()).json()['enabled'])
        self.assertEqual(503, self.client.get(PREFIX + '/latest', headers=self.headers()).status_code)

    def test_real_password_login_session_and_invalid_credentials(self):
        self.config()
        self.assertEqual(401, self.client.get(PREFIX + '/latest').status_code)
        response = self.client.post('/auth/password-login', json={'provider': 'basic',
                                    'username': 'synthetic-user', 'password': 'wrong-synthetic-value'})
        self.assertEqual(401, response.status_code)
        response = self.client.post('/auth/password-login', json={'provider': 'basic',
                                    'username': 'synthetic-user', 'password': self.password})
        self.assertEqual(200, response.status_code, response.text)
        self.assertTrue(response.json()['ok'])
        _, record, apk = write_release(self.root, 2)
        for path in ('/status', '/latest', f"/releases/{record['release_id']}/apk"):
            response = self.client.get(PREFIX + path)
            self.assertEqual(200, response.status_code, response.text)
            if path.endswith('/apk'):
                self.assertEqual(apk, response.content)
        self.assertEqual(403, self.client.get(PREFIX + '/latest', headers={'origin': 'https://evil.example'}).status_code)
        self.client.cookies.clear()
        self.assertEqual(401, self.client.get(PREFIX + '/latest', headers={'authorization': 'Bearer unsigned'}).status_code)

    def test_forged_headers_unsigned_wrong_purpose_replay_and_origin_refused(self):
        for headers in ({'native_http_principal': 'true'}, {'hosted_http_authenticated': 'true'},
                        {'x-hermes-session-token': self.core._SESSION_TOKEN},
                        {native.HEADER: 'unsigned'}, self.headers('interactive'),
                        self.headers() | {'origin': 'http://testserver'},
                        self.headers() | {'origin': ''}):
            with self.subTest(headers=list(headers)):
                self.assertEqual(401, self.client.get(PREFIX + '/status', headers=headers).status_code)
        ticket = self.headers()
        response = self.client.get(PREFIX + '/status', headers=ticket)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(401, self.client.get(PREFIX + '/status', headers=ticket).status_code)
        self.assertEqual(403, self.client.get(PREFIX + '/status', headers=self.headers() | {'sec-fetch-site': 'cross-site'}).status_code)

    def test_native_grant_requires_transport_peer_and_ready_runner(self):
        with TestClient(SocketPeer(self.app), client=('198.51.100.2', 40602)) as remote:
            self.assertEqual(401, remote.get(PREFIX + '/status', headers=self.headers() | {'x-forwarded-for': '127.0.0.1'}).status_code)
        self.runner._draining = True
        self.assertEqual(401, self.client.get(PREFIX + '/status', headers=self.headers()).status_code)
        self.runner._draining = False
        self.runner.session_runtime_descriptor['state'] = 'starting'
        self.assertEqual(401, self.client.get(PREFIX + '/status', headers=self.headers()).status_code)

    def test_secondary_native_profile_uses_only_its_trusted_home(self):
        other = self.home / 'secondary'
        other.mkdir(mode=0o700)
        registry = self.runner.session_authorities
        registry.add(other, types.SimpleNamespace(profile_id=str(other), instance_id=self.store.instance_id))
        self.store.profile_ids = registry.profile_ids()
        self.runner.session_runtime_descriptor['served_profiles'] = registry.served_profiles()
        self.config()
        self.assertTrue(self.client.get(PREFIX + '/status', headers=self.headers()).json()['enabled'])
        self.assertFalse(self.client.get(PREFIX + '/status', headers=self.headers(home=other)).json()['enabled'])
        self.assertEqual(503, self.client.get(PREFIX + '/latest', headers=self.headers(home=other)).status_code)
        # No HTTP root/home selector can activate the secondary from the launch home.
        response = self.client.get(PREFIX + '/status', params={'home': str(self.home), 'root': str(self.root)},
                                   headers=self.headers(home=other))
        self.assertFalse(response.json()['enabled'])
        from hermes_constants import get_hermes_home
        self.assertEqual(self.home, get_hermes_home())

    def test_duplicate_expired_and_wrong_instance_tickets_refused(self):
        first, second = self.headers(), self.headers()
        response = self.client.get(PREFIX + '/status', headers=[(native.HEADER, first[native.HEADER]),
                                                              (native.HEADER, second[native.HEADER])])
        self.assertEqual(401, response.status_code)
        with patch.object(TicketStore, 'TTL_SECONDS', -1):
            expired = self.headers()
        self.assertEqual(401, self.client.get(PREFIX + '/status', headers=expired).status_code)
        self.runner.session_runtime_descriptor['instance_id'] = 'wrong-instance'
        self.assertEqual(401, self.client.get(PREFIX + '/status', headers=self.headers()).status_code)

    def test_legacy_static_token_mode_is_not_update_authority(self):
        self.app.state.auth_required = False
        for headers in ({}, {'x-hermes-session-token': self.core._SESSION_TOKEN},
                        {'authorization': 'Bearer ' + self.core._SESSION_TOKEN},
                        {'native_http_principal': 'true'}, {'hosted_http_authenticated': 'true'}):
            with self.subTest(headers=list(headers)):
                self.assertEqual(401, self.client.get(PREFIX + '/status', headers=headers).status_code)
        self.assertEqual(200, self.client.get(PREFIX + '/status', headers=self.headers()).status_code)


if __name__ == '__main__':
    unittest.main()
