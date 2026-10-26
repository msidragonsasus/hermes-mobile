"""Synthetic SDUI/API and release checks. No real credentials/network/worker."""
import copy
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router
from career_core.mobile_contract import validate_components, validate_screen
from career_core.mobile_sdui import MobileRuntime, RUNTIME_ROOT, ExistingMyPageAdapter, create_mobile_router
from career_core.mypage_bot import MyPageBotStore, connection_id
from career_core.store import Store, encoded

spec = importlib.util.spec_from_file_location('app_release', Path(__file__).resolve().parents[1] / 'tools/app_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class MobileApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(Path(self.tmp.name) / 'data')
        self.records = MyPageBotStore(self.store)
        with self.store.transaction() as db:
            for identity in ['one', 'two', 'unconnected', 'closed']:
                self.store.put(db, 'companies', identity, 0, {'name': 'Synthetic '+identity, 'user_stage': 'closed' if identity == 'closed' else 'considering'})
            for identity in ['one', 'two', 'closed']:
                db.execute('INSERT INTO career_mypage_connections VALUES(?,?,?,?,?,?)',
                           (connection_id(identity, 'default'), identity, 'default', 1, encoded({'enabled': False, 'url': 'https://portal.example.test/'}), encoded({'state': 'idle'})))
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def session(request, call_next):
            if request.headers.get('x-test-session') == 'synthetic':
                request.state.session = {'id': 'synthetic'}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store), prefix='/api/plugins/career')
        self.client = TestClient(app, headers={'x-test-session': 'synthetic'})
        self.addCleanup(self.client.close)
        self.base = '/api/plugins/career/mobile'

    def post(self, company='one', operation='synthetic-operation-0001', **changes):
        payload = dict(protocol_version=1, release_id='bundled-1', company_id=company, operation_id=operation)
        payload.update(changes)
        return self.client.post(self.base+'/actions/mypage.check', json=payload)

    def test_display_and_manifest_do_not_enqueue_or_expose_private_fields(self):
        manifest = self.client.get(self.base+'/manifest').json()
        self.assertEqual('career.mypage', manifest['app_id'])
        response = self.client.get(self.base+'/sdui/company/one')
        self.assertEqual(200, response.status_code)
        validate_screen(response.json())
        self.assertNotIn('secret_ref', response.text)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])

    def test_same_operation_recovers_receipt_and_other_operation_deduplicates_active_run(self):
        first = self.post().json()
        self.assertEqual(first, self.post().json())
        second = self.post(operation='synthetic-operation-0002').json()
        self.assertEqual(first['run_id'], second['run_id'])
        self.assertEqual('queued', first['state'])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])

    def test_operation_id_cannot_be_rebound_to_other_company(self):
        self.assertEqual(200, self.post().status_code)
        response = self.post(company='two')
        self.assertEqual(409, response.status_code)
        self.assertEqual('operation_conflict', response.json()['detail']['code'])

    def test_concurrent_taps_cannot_duplicate_runs(self):
        with ThreadPoolExecutor(max_workers=6) as pool:
            responses = list(pool.map(lambda _: self.post(), range(6)))
        self.assertTrue(any(r.status_code == 200 for r in responses))
        self.assertTrue(all(r.status_code in {200, 503} for r in responses))
        with self.store.connect() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])

    def test_wrong_release_invalid_type_extra_field_and_oversize_reject_before_enqueue(self):
        self.assertEqual(409, self.post(release_id='future-2').status_code)
        self.assertEqual(400, self.post(protocol_version=True).status_code)
        self.assertEqual(400, self.post(command='terminal').status_code)
        self.assertEqual(413, self.client.post(self.base+'/actions/mypage.check', content=b'x'*4097).status_code)
        self.assertEqual(404, self.client.post(self.base+'/actions/terminal', json={}).status_code)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])

    def test_auth_origin_and_profile_boundaries_remain(self):
        self.assertEqual(401, self.client.get(self.base+'/manifest', headers={'x-test-session': ''}).status_code)
        self.assertEqual(403, self.client.post(self.base+'/actions/mypage.check', headers={'origin': 'https://foreign.example.test'}, json={}).status_code)
        payload = dict(protocol_version=1, release_id='bundled-1', company_id='one', operation_id='synthetic-operation-0001')
        self.assertEqual(409, self.client.post(self.base+'/actions/mypage.check?profile=other', json=payload).status_code)
        self.assertEqual('not_connected', self.client.get(self.base+'/sdui/company/one?profile=other').json()['state'])
        self.assertEqual(200, self.post().status_code)
        self.assertEqual(400, self.client.get(self.base+'/manifest?profile=../other').status_code)

    def test_missing_closed_and_unconnected_never_fallback_collect(self):
        self.assertEqual(404, self.post(company='missing').status_code)
        self.assertEqual(409, self.post(company='unconnected').status_code)
        self.assertEqual(409, self.post(company='closed').status_code)
        self.assertFalse(self.client.get(self.base+'/sdui/company/closed').json()['components'][1]['children'][0]['enabled'])

    def test_existing_native_routes_still_available(self):
        self.assertEqual(200, self.client.get('/api/plugins/career/companies').status_code)
        self.assertEqual(200, self.client.get('/api/plugins/career/companies/one').status_code)

    def test_broken_app_payload_does_not_disable_native_router(self):
        with patch('career_core.mobile_sdui.MobileRuntime', side_effect=ValueError('synthetic bad release')):
            app = FastAPI()
            app.state.auth_required = True
            @app.middleware('http')
            async def session(request, call_next):
                request.state.session = {'id': 'synthetic'}
                return await call_next(request)
            app.include_router(create_router(lambda: self.store), prefix='/api/plugins/career')
            with TestClient(app) as client:
                self.assertEqual(503, client.get(self.base+'/manifest').status_code)
                self.assertEqual(200, client.get('/api/plugins/career/companies').status_code)

    def test_worker_is_not_started_in_fixture_default(self):
        with patch('career_core.mypage_cron.HermesMyPageCron.claim', side_effect=AssertionError('must remain OFF')):
            self.assertEqual(200, self.post().status_code)

    def test_adapter_cannot_change_target_and_multiple_calls_reuse_same_result(self):
        adapter = ExistingMyPageAdapter(self.records, 'one', 'default')
        first = adapter.enqueue_existing('one', 'default')
        self.assertEqual(first, adapter.enqueue_existing('one', 'default'))
        from career_core.store import CareerError
        with self.assertRaises(CareerError):
            adapter.enqueue_existing('two', 'default')

    def test_lost_accepted_receipt_recovers_after_release_update(self):
        receipt = self.post().json()
        runtime = MobileRuntime(release_id='updated-2')
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def session(request, call_next):
            request.state.session = {'id': 'synthetic'}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store, mobile_runtime=runtime), prefix='/api/plugins/career')
        with TestClient(app) as client:
            self.assertEqual('updated-2', client.get(self.base+'/manifest').json()['release_id'])
            payload = dict(protocol_version=1, release_id='bundled-1', company_id='one', operation_id='synthetic-operation-0001')
            response = client.post(self.base+'/actions/mypage.check', json=payload)
            self.assertEqual(200, response.status_code)
            self.assertEqual(receipt, response.json())
            payload['operation_id'] = 'synthetic-operation-0002'
            self.assertEqual(409, client.post(self.base+'/actions/mypage.check', json=payload).status_code)


class SchemaTests(unittest.TestCase):
    def test_unknown_component_action_field_and_non_boolean_rejected(self):
        for node in [{'type': 'WebView', 'url': 'https://example.test'},
                     {'type': 'Button', 'label': 'X', 'enabled': True, 'action': 'shell'},
                     {'type': 'Text', 'text': 'X', 'url': 'https://example.test'},
                     {'type': 'Button', 'label': 'X', 'enabled': 'true', 'action': 'refresh'}]:
            with self.assertRaises(ValueError):
                validate_components([node])

    def test_node_and_depth_limits(self):
        with self.assertRaises(ValueError):
            validate_components([{'type': 'Text', 'text': 'X'}]*65)
        node = {'type': 'Text', 'text': 'X'}
        for _ in range(7):
            node = {'type': 'Column', 'children': [node]}
        with self.assertRaises(ValueError):
            validate_components([node])

    def test_labels_match_native_host_limits(self):
        for label in ['', 'x'*201]:
            with self.assertRaises(ValueError):
                validate_components([{'type': 'Button', 'label': label, 'enabled': True, 'action': 'refresh'}])


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'career-mypage-synthetic'
        self.source = Path(self.tmp.name) / 'source'
        self.source.mkdir()
        for name in release.FILES:
            shutil.copy2(RUNTIME_ROOT / name, self.source / name)
        self.initial = release.initialize(self.root, self.source)

    def candidate(self):
        work = release.workcopy(self.root)
        path = work / 'payload/mobile_screen.json'
        template = json.loads(path.read_text(encoding='utf-8'))
        template['title'] = '更新した合成画面'
        release.write_json(path, template)
        return release.package(self.root)

    def test_review_hash_fixed_tests_activation_and_rollback_preserve_data(self):
        identity = self.candidate()
        private = self.root / 'synthetic-data.json'
        private.write_text('{"observation":"created after code update"}', encoding='utf-8')
        before = private.read_bytes()
        with self.assertRaises(ValueError):
            release.activate(self.root, identity, self.initial, identity)
        release.test_release(self.root, identity)
        with self.assertRaises(ValueError):
            release.activate(self.root, identity, self.initial, 'wrong-reviewed-hash')
        release.activate(self.root, identity, self.initial, identity)
        active_runtime = MobileRuntime(self.root / 'releases' / identity / 'payload', release_id=identity)
        self.assertEqual('更新した合成画面', active_runtime.template['title'])
        self.assertEqual(identity, release.active(self.root)['id'])
        self.assertEqual(self.initial, release.rollback(self.root, identity))
        self.assertEqual(before, private.read_bytes())

    def test_bot_internal_change_goes_in_same_bundle_and_fixed_harness_checks_it(self):
        work = release.workcopy(self.root)
        (work/'payload/mypage_handler.py').write_text('def enqueue(adapter, company_id, profile):\n    target = str(company_id)\n    return adapter.enqueue_existing(target, profile)\n', encoding='utf-8')
        identity = release.package(self.root)
        release.test_release(self.root, identity)
        release.activate(self.root, identity, self.initial, identity)
        diff = (self.root/'releases'/identity/'review.diff').read_text(encoding='utf-8')
        self.assertIn('mypage_handler.py', diff)

    def test_outside_file_unknown_action_and_version_reject_packaging(self):
        work = release.workcopy(self.root)
        outside = work/'payload/hermes_core.py'
        outside.write_text('pass', encoding='utf-8')
        with self.assertRaises(ValueError):
            release.package(self.root)
        outside.unlink()
        protocol = json.loads((work/'payload/protocol.json').read_text(encoding='utf-8'))
        protocol['component_version'] = 2
        release.write_json(work/'payload/protocol.json', protocol)
        with self.assertRaises(ValueError):
            release.package(self.root)

    def test_tamper_and_stale_active_cannot_promote_or_rollback(self):
        identity = self.candidate()
        release.test_release(self.root, identity)
        with self.assertRaises(ValueError):
            release.activate(self.root, identity, 'wrong-active', identity)
        (self.root/'releases'/identity/'payload/mypage_handler.py').write_text('pass', encoding='utf-8')
        with self.assertRaises(ValueError):
            release.activate(self.root, identity, self.initial, identity)
        with self.assertRaises(ValueError):
            release.rollback(self.root, 'wrong-active')

    def test_bot_contract_failure_does_not_change_active(self):
        work = release.workcopy(self.root)
        (work/'payload/mypage_handler.py').write_text('def enqueue(adapter, company_id, profile):\n    return None\n', encoding='utf-8')
        identity = release.package(self.root)
        with self.assertRaises(ValueError):
            release.test_release(self.root, identity)
        self.assertEqual(self.initial, release.active(self.root)['id'])

    def test_existing_production_path_refused_and_lock_serializes(self):
        with self.assertRaises(ValueError):
            release.initialize(Path(self.tmp.name)/'hermes', self.source)
        with release.locked(self.root):
            with self.assertRaises(ValueError):
                release.workcopy(self.root)

    def test_path_traversal_release_id_rejected(self):
        with self.assertRaises(ValueError):
            release.test_release(self.root, '../source')


if __name__ == '__main__':
    unittest.main()
