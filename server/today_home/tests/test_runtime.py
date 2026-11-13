import asyncio
from contextlib import asynccontextmanager
import json
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from today_core.agent_tools import register_tools
from today_core.runtime import synthesize_async
from today_core.store import PlanError, PlanStore


class GatewayDouble:
    def __init__(self, store, *, mode='success'):
        self.store, self.mode = store, mode
        self.endpoint = object()
        self.calls, self.connections, self.queues = [], 0, []
        self.receipt_failed = False
        self.handler = None

    def discover(self, home, *, timeout):
        if home != self.store.root.parent:
            raise AssertionError('wrong profile home')
        return types.SimpleNamespace(state='ready', endpoint=self.endpoint)

    @asynccontextmanager
    async def connect(self, endpoint):
        if endpoint is not self.endpoint:
            raise AssertionError('must connect to the explicit discovered owner')
        self.connections += 1
        events = asyncio.Queue(maxsize=1)
        self.queues.append(events)
        yield types.SimpleNamespace(events=events, rpc=lambda method, **params: self.rpc(events, method, params))

    async def rpc(self, events, method, params):
        self.calls.append((method, params))
        if method == 'session.create':
            return dict(session_id='public', stored_session_id='stored')
        if method == 'prompt.submit':
            self.store.context_for_session('stored')  # Must be durable before a turn can start.
            # More frames than the bounded queue holds: a receipt-only client still drains events.
            await events.put({'private_delta': 'not persisted'})
            await events.put({'private_delta': 'not persisted either'})
            if self.mode == 'lost-submit-ack':
                raise OSError('private transport diagnostic')
            if self.handler:
                self.handler()
            return dict(admission_id='admission', status='queued', outcome=None)
        if method == 'prompt.receipt':
            if self.mode == 'disconnect' and not self.receipt_failed:
                self.receipt_failed = True
                raise OSError('private transport diagnostic')
            if self.mode == 'timeout':
                return dict(admission_id='admission', status='started', outcome=None)
            return dict(admission_id='admission', status='terminal',
                        outcome='failed' if self.mode == 'rejected' else 'completed',
                        result={'final_response': json.dumps(self.answer), 'completed': self.mode != 'rejected'},
                        usage={'api_calls': 1, 'input_tokens': 120, 'private_payload': 'never cache this'})
        raise AssertionError(method)

    @property
    def answer(self):
        return {'tasks': []}


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = PlanStore(self.temp.name)
        self.store.configure(dict(email='wanted@example.org', manaba_url='', enabled=True))
        self.store.begin('daily')

    async def execute(self, gateway, **kwargs):
        return await asyncio.wait_for(synthesize_async(self.store, 'daily', discover=gateway.discover,
                                      connect=gateway.connect, interval=0.001, **kwargs), 2)

    async def test_direct_rpc_binds_before_submit_drains_events_and_persists_receipt(self):
        gateway = GatewayDouble(self.store)
        self.assertEqual({'tasks': []}, await self.execute(gateway))
        self.assertEqual(['session.create', 'prompt.submit', 'prompt.receipt'], [m for m, _ in gateway.calls])
        create, submit = gateway.calls[0][1], gateway.calls[1][1]
        context = self.store.run_record('daily')
        self.assertEqual(context['token'], create['request_id'])
        self.assertEqual(context['input_id'], submit['input_id'])
        self.assertEqual(['today_home'], create['toolsets'])
        self.assertEqual('oneshot', create['source'])
        self.assertTrue(submit['finite'])
        self.assertFalse({'provider', 'model', 'api_key', 'yolo', 'unattended'} & (set(create) | set(submit)))
        with self.store.db() as db:
            receipt = json.loads(db.execute('SELECT value FROM state WHERE key=?', ('runtime:' + context['token'],)).fetchone()[0])
        self.assertEqual({'api_calls': 1, 'input_tokens': 120}, receipt['usage'])
        self.assertNotIn('private', json.dumps(receipt))
        with self.assertRaises(PlanError):
            self.store.context_for_session('stored')

    async def test_disconnect_recovers_the_same_admission_without_another_prompt(self):
        gateway = GatewayDouble(self.store, mode='disconnect')
        self.assertEqual({'tasks': []}, await self.execute(gateway))
        self.assertEqual(2, gateway.connections)
        self.assertEqual(1, sum(m == 'session.create' for m, _ in gateway.calls))
        self.assertEqual(1, sum(m == 'prompt.submit' for m, _ in gateway.calls))
        self.assertTrue(all(p['admission_id'] == 'admission' for m, p in gateway.calls if m == 'prompt.receipt'))

    async def test_lost_submit_ack_is_unknown_and_never_replayed(self):
        gateway = GatewayDouble(self.store, mode='lost-submit-ack')
        with self.assertRaises(PlanError) as error:
            await self.execute(gateway)
        self.assertEqual('runtime_uncertain', error.exception.code)
        self.assertEqual('uncertain', self.store.snapshot()['state'])
        self.assertEqual(1, gateway.connections)
        self.assertEqual(1, sum(m == 'prompt.submit' for m, _ in gateway.calls))
        self.assertFalse(self.store.begin('daily'))
        with self.assertRaises(PlanError):
            self.store.context_for_session('stored')

    async def test_terminal_capacity_refusal_fails_without_a_long_wait_or_retry(self):
        gateway = GatewayDouble(self.store, mode='rejected')
        with self.assertRaises(PlanError) as error:
            await self.execute(gateway)
        self.assertEqual('synthesis_failed', error.exception.code)
        self.assertEqual(1, gateway.connections)
        self.assertEqual(3, len(gateway.calls))

    async def test_timeout_revokes_read_permission_and_preserves_admission_for_inspection(self):
        gateway = GatewayDouble(self.store, mode='timeout')
        with self.assertRaises(PlanError):
            await self.execute(gateway, budget=0.02)
        self.assertEqual('uncertain', self.store.snapshot()['state'])
        self.assertEqual('admission', self.store.run_record('daily')['admission_id'])
        self.assertEqual(1, sum(m == 'prompt.submit' for m, _ in gateway.calls))

    async def test_unready_owner_is_not_started_or_replaced(self):
        gateway = GatewayDouble(self.store)
        with self.assertRaises(PlanError) as error:
            await synthesize_async(self.store, 'daily', discover=lambda *args, **kwargs:
                                   types.SimpleNamespace(state='absent', endpoint=None), connect=gateway.connect)
        self.assertEqual('gateway_not_ready', error.exception.code)
        self.assertEqual(0, gateway.connections)

    async def test_rpc_turn_can_use_registered_tools_and_publish_cached_evidence(self):
        handlers = {}
        constants = types.ModuleType('hermes_constants')
        constants.get_hermes_home = lambda: self.temp.name
        ctx = types.SimpleNamespace(register_tool=lambda **value: handlers.update({value['name']: value['handler']}))
        with patch.dict(sys.modules, {'hermes_constants': constants}):
            register_tools(ctx)
        record = dict(id='gmail:one', source='gmail', title='締切', url='https://example.org', text='申込受付済み。明日の面接に備えてください。')
        class EvidenceGateway(GatewayDouble):
            @property
            def answer(self):
                return {'tasks': [dict(task_key='company/entry', source_id='gmail:one', title='ESを見直す',
                                      reason='明日の締切に備える', priority=2, deadline=None,
                                      eligibility=dict(kind='user_application', source_id='gmail:one', quote=record['text'],
                                                       user_intent='confirmed', action_status='pending', deadline_policy='closes'))]}
        gateway = EvidenceGateway(self.store)
        gateway.handler = lambda: self.assertNotIn('error', json.loads(handlers['today_mail_read'](
            {'message_id': 'abc'}, session_id='stored')))
        with patch('today_core.agent_tools.read_mail', return_value=(record, 'updated')):
            answer = await self.execute(gateway)
        self.store.finish('daily', self.store.cached_inputs('daily'), answer)
        plan = self.store.snapshot()
        self.assertEqual('ESを見直す', plan['tasks'][0]['title'])
        self.assertTrue(plan['fresh'])
