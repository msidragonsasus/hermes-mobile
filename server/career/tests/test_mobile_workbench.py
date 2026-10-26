"""Synthetic scoped workflow tests. No live account, worker or external agent."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router
from career_core.mobile_workbench import AppWorkbench, RestrictedRuntime, bot_mode, digest
from career_core.store import CareerError
import test_mobile_sdui as existing


class WorkbenchTests(unittest.TestCase):
    def setUp(self):
        existing.MobileApiTests.setUp(self)
        self.root = Path(self.tmp.name) / 'code-only'
        self.root.mkdir()
        self.workbench = AppWorkbench(self.root, 'one', 'default')
        self.client.close()
        self.client = self.make_client(self.workbench)
        self.base = '/api/plugins/career/mobile/workbench/one'

    def make_client(self, grant):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def session(request, call_next):
            if request.headers.get('x-test-session') == 'synthetic':
                request.state.session = {'id': 'synthetic'}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store, mobile_workbench=grant), prefix='/api/plugins/career')
        client = TestClient(app, headers={'x-test-session': 'synthetic', 'x-hermes-app-workbench': 'once'})
        self.addCleanup(client.close)
        return client

    def snapshot(self):
        response = self.client.get(self.base)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    def payload(self, action, snapshot=None, **extras):
        state = snapshot or self.snapshot()
        with self.workbench.locked() as db:
            count = db.execute('SELECT COUNT(*) FROM operations').fetchone()[0]
        return dict(protocol_version=1, action=action, operation_id=f'synthetic-operation-{count:06d}',
                    expected_active=state['active'], revision=state['revision'], **extras)

    def post(self, payload):
        response = self.client.post(self.base, json=payload)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    def request(self, **changes):
        fields = dict(goal='Improve the one-company check', title='Synthetic updated title', mode='enqueue_ready')
        fields.update(changes)
        return self.post(self.payload('request', **fields))

    def make_tested(self):
        self.request()
        return self.post(self.payload('test'))

    def test_full_request_edit_test_apply_runtime_rollback(self):
        start = self.snapshot()
        self.assertEqual('done', self.request()['state'])
        draft = self.snapshot()
        self.assertTrue(draft['draft'])
        self.assertIn('mypage_handler.py', draft['diff'])
        self.assertEqual('enqueue_ready', bot_mode(draft['files']['mypage_handler.py']))
        files = draft['files']
        screen = json.loads(files['mobile_screen.json']);screen['title'] = 'Edited synthetic title'
        files['mobile_screen.json'] = json.dumps(screen)
        self.assertEqual('done', self.post(self.payload('edit', files=files))['state'])
        self.assertEqual('test_required', self.post(self.payload('apply'))['code'])
        self.assertEqual('done', self.post(self.payload('test'))['state'])
        self.assertTrue(self.snapshot()['tested'])
        self.assertEqual('done', self.post(self.payload('apply'))['state'])
        updated = self.snapshot()
        self.assertNotEqual(start['active'], updated['active'])
        manifest = self.client.get('/api/plugins/career/mobile/manifest?company_id=one').json()
        self.assertEqual(updated['active'], manifest['release_id'])
        screen = self.client.get('/api/plugins/career/mobile/sdui/company/one').json()
        self.assertEqual('Edited synthetic title', screen['title'])
        self.assertEqual('bundled-1', self.client.get('/api/plugins/career/mobile/manifest?company_id=two').json()['release_id'])
        self.assertEqual('done', self.post(self.payload('rollback'))['state'])
        self.assertEqual(start['active'], self.snapshot()['active'])

    def test_real_existing_queue_adapter_and_other_company_isolation(self):
        self.make_tested();self.post(self.payload('apply'))
        payload = dict(protocol_version=1, company_id='one', release_id=self.snapshot()['active'], operation_id='synthetic-check-000001')
        response = self.client.post('/api/plugins/career/mobile/actions/mypage.check', json=payload)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual('queued', response.json()['state'])
        self.assertEqual(response.json(), self.client.post('/api/plugins/career/mobile/actions/mypage.check', json=payload).json())
        with self.store.connect() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM career_mypage_runs').fetchone()[0])

    def test_duplicates_concurrent_single_candidate_and_receipt_recovery(self):
        payload = self.payload('request', goal='Synthetic', title='Updated', mode='enqueue_existing')
        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: self.post(payload), range(6)))
        self.assertTrue(all(r == results[0] for r in results))
        self.assertEqual(1, self.snapshot()['revision'])
        recovered = self.client.get(self.base+'/operations/'+payload['operation_id']).json()
        self.assertEqual(results[0], recovered)
        self.assertEqual(digest(payload), recovered['request_hash'])
        changed = dict(payload, title='Other')
        self.assertEqual(409, self.client.post(self.base, json=changed).status_code)

    def test_restart_keeps_draft_receipt_and_active_pointer(self):
        self.make_tested();payload = self.payload('apply');receipt = self.post(payload)
        original = self.snapshot()
        restarted = AppWorkbench(self.root, 'one', 'default')
        self.assertEqual(original, restarted.snapshot('one', 'default'))
        self.assertEqual(receipt, restarted.receipt('one', 'default', payload['operation_id']))
        self.assertEqual(receipt, restarted.mutate('one', 'default', payload, lambda: True))

    def test_cancel_fences_unknown_late_operation_without_replaying(self):
        original = self.payload('request', goal='Synthetic', title='Late', mode='enqueue_existing')
        self.assertEqual('unknown', self.client.get(self.base+'/operations/'+original['operation_id']).json()['state'])
        fence = {k:original[k] for k in ('protocol_version', 'operation_id', 'expected_active', 'revision')}
        fence.update(action='fence', request_hash=digest(original), original_action='request')
        receipt = self.post(fence)
        self.assertEqual('cancelled', receipt['state'])
        self.assertEqual(receipt, self.post(original))
        self.assertFalse(self.snapshot()['draft'])

    def test_cancel_after_apply_recovers_existing_result_without_undoing(self):
        self.make_tested();original = self.payload('apply');receipt = self.post(original)
        fence = {k:original[k] for k in ('protocol_version', 'operation_id', 'expected_active', 'revision')}
        fence.update(action='fence', request_hash=digest(original), original_action='apply')
        self.assertEqual(receipt, self.post(fence))
        self.assertNotEqual(original['expected_active'], self.snapshot()['active'])

    def test_stale_edit_and_test_do_not_replace_newer_draft(self):
        self.request();old = self.snapshot();files = dict(old['files'])
        self.post(self.payload('test'))
        stale = self.payload('edit', snapshot=old, files=files)
        self.assertEqual('edit_conflict', self.post(stale)['code'])
        self.assertTrue(self.snapshot()['tested'])

    def test_test_failure_never_activates_and_edit_invalidates_test(self):
        self.make_tested();good = self.snapshot();files = dict(good['files'])
        files['mypage_handler.py'] = 'import os\n'
        self.post(self.payload('edit', files=files))
        self.assertFalse(self.snapshot()['tested'])
        self.assertEqual('test_failed', self.post(self.payload('test'))['code'])
        self.assertEqual(good['active'], self.snapshot()['active'])
        self.assertEqual('test_required', self.post(self.payload('apply'))['code'])

    def test_apply_failure_preserves_old_code_and_user_files(self):
        self.make_tested();before = self.snapshot()
        marker = Path(self.tmp.name)/'synthetic-user-data';marker.write_bytes(b'synthetic-private-fixture')
        before_hash = hashlib.sha256(marker.read_bytes()).hexdigest()
        def fail(_):
            raise RuntimeError('synthetic apply probe failure')
        self.workbench.apply_probe = fail
        self.assertEqual('apply_failed', self.post(self.payload('apply'))['code'])
        self.assertEqual(before, self.snapshot())
        self.assertEqual(before_hash, hashlib.sha256(marker.read_bytes()).hexdigest())

    def test_busy_blocks_apply_and_rollback_without_new_run(self):
        self.make_tested();before = self.snapshot()
        payload = self.payload('apply')
        result = self.workbench.mutate('one', 'default', payload, lambda: False)
        self.assertEqual('mypage_busy', result['code']);self.assertEqual(before, self.snapshot())
        self.post(self.payload('apply'))
        result = self.workbench.mutate('one', 'default', self.payload('rollback'), lambda: False)
        self.assertEqual('mypage_busy', result['code'])

    def test_cancel_draft_then_start_again(self):
        self.request();self.post(self.payload('cancel'));self.assertFalse(self.snapshot()['draft'])
        self.assertEqual('done', self.request()['state'])

    def test_off_by_default_session_origin_and_exact_scope_guards(self):
        disabled = self.make_client(None)
        self.assertEqual(403, disabled.get(self.base).status_code)
        self.assertEqual(403, self.client.get(self.base.replace('/one', '/two')).status_code)
        self.assertEqual(403, self.client.get(self.base+'?profile=other').status_code)
        payload = self.payload('test')
        self.assertEqual(403, self.client.post(self.base, json=payload, headers={'origin':'http://testserver'}).status_code)
        self.assertEqual(403, self.client.post(self.base, json=payload, headers={'x-hermes-app-workbench':'always'}).status_code)
        client = TestClient(self.client.app)
        self.addCleanup(client.close)
        self.assertEqual(401, client.get(self.base).status_code)
        with self.assertRaises(ValueError):
            AppWorkbench(self.root, 'two', 'default')

    def test_payload_limits_and_no_path_extension(self):
        self.request();files = self.snapshot()['files']
        files['../secret.py'] = 'arbitrary'
        self.assertEqual('invalid_candidate', self.post(self.payload('edit', files=files))['code'])
        payload = self.payload('test');payload['extra'] = True
        self.assertEqual(400, self.client.post(self.base, json=payload).status_code)
        self.assertEqual(413, self.client.post(self.base, content=b'x'*240001, headers={'content-type':'application/json'}).status_code)

    def test_untrusted_python_capabilities_are_rejected_without_execution(self):
        rejected = ['import os\ndef enqueue(adapter, company_id, profile):\n return adapter.enqueue_existing(company_id,profile)',
                    '@open("x")\ndef enqueue(adapter,company_id,profile):\n return adapter.enqueue_existing(company_id,profile)',
                    'def enqueue(adapter,company_id,profile):\n return __import__("os").system("x")',
                    'def enqueue(adapter,company_id,profile):\n return adapter.enqueue_existing(company_id,"other")',
                    'def enqueue(adapter,company_id,profile):\n adapter.enqueue_existing(company_id,profile)\n return adapter.enqueue_existing(company_id,profile)']
        for source in rejected:
            with self.subTest(source=source), self.assertRaises(ValueError):
                bot_mode(source)
        for mode in ('enqueue_ready', 'enqueue_existing'):
            self.assertEqual(mode, bot_mode(f'def enqueue(adapter,company_id,profile):\n return adapter.{mode}(company_id,profile)'))
