import json
import sqlite3
import sys
import tempfile
import types
import unittest
import threading
from contextlib import contextmanager, closing
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

from today_core.agent_tools import DailyTools, register_tools
from today_core.store import PlanError, PlanStore, now


class RunContextTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.current = now()
        self.store = PlanStore(self.temp.name, lambda: self.current)
        self.settings = dict(email="wanted@example.org", manaba_url="", enabled=True)
        self.store.configure(self.settings)

    def test_registered_handler_uses_core_session_metadata_and_revokes_at_terminal(self):
        handlers = {}
        ctx = types.SimpleNamespace(register_tool=lambda **value: handlers.update({value['name']: value['handler']}))
        constants = types.ModuleType('hermes_constants')
        constants.get_hermes_home = lambda: self.temp.name
        with patch.dict(sys.modules, {'hermes_constants': constants}):
            register_tools(ctx)
        self.store.begin('daily')
        self.store.bind_session('daily', 'public-session', 'stored-session')
        with patch('today_core.agent_tools.search_mail', return_value=({'messages': []}, 'checked')) as search:
            call = handlers['today_mail_search']
            denied = json.loads(call({'query': 'x', 'session_id': 'public-session'}, session_id='other-session'))
            self.assertEqual('active_daily_run_required', denied['error'])
            search.assert_not_called()
            self.assertNotIn('error', json.loads(call({'query': 'x'}, session_id='stored-session')))
            self.store.record_terminal('daily', {'status': 'terminal', 'outcome': 'completed'})
            self.assertEqual('active_daily_run_required', json.loads(call({'query': 'x'}, session_id='public-session'))['error'])
            self.assertEqual('active_daily_run_required', json.loads(handlers['today_sources']({}, session_id='stored-session'))['error'])
            self.assertIn('cache', json.loads(handlers['today_sources']({}, session_id='ordinary-owner-chat')))
            self.assertEqual(1, search.call_count)

    def test_limits_survive_handler_recreation_and_reset_for_a_new_run(self):
        self.store.begin('first')
        with patch('today_core.agent_tools.search_mail', return_value=({'messages': []}, 'checked')) as search:
            for _ in range(8):
                self.assertNotIn('error', DailyTools(self.temp.name, 'first').call('today_mail_search', {'query': 'x'}))
            self.assertEqual('daily_read_limit', DailyTools(self.temp.name, 'first').call('today_mail_search', {'query': 'x'})['error'])
            self.assertEqual(8, search.call_count)
            self.store.fail('first')
            self.store.begin('second')
            self.assertNotIn('error', DailyTools(self.temp.name, 'second').call('today_mail_search', {'query': 'x'}))

    def test_expiry_config_change_and_reused_id_cannot_reauthorize_old_worker(self):
        self.store.begin('same')
        self.store.bind_session('same', 'old-public', 'old-stored')
        context = self.store.run_context('same')
        old = DailyTools(self.temp.name, 'same', context['token'])
        old.store.clock = lambda: self.current
        self.current += timedelta(minutes=16)
        with self.assertRaises(PlanError):
            old.store.require_run('same')
        self.store.configure(dict(self.settings, email='other@example.org'))
        self.store.begin('same')
        self.store.bind_session('same', 'new-public', 'new-stored')
        with self.assertRaises(PlanError):
            self.store.context_for_session('old-stored')
        self.assertTrue(self.store.was_daily_session('old-stored'))
        with self.assertRaises(PlanError):
            old.store.cache_source('same', 'gmail', [], 'checked')
        old.store.fail('same')
        self.assertEqual('running', self.store.snapshot()['state'])

    def test_binding_requires_accepting_worker_and_unknown_revokes_reads(self):
        self.store.begin('daily')
        stranger = PlanStore(self.temp.name)
        with self.assertRaises(PlanError):
            stranger.bind_session('daily', 'public', 'stored')
        self.store.bind_session('daily', 'public', 'stored')
        self.store.record_admission('daily', 'admission')
        with self.assertRaises(PlanError):
            self.store.bind_session('daily', 'compressed', 'changed')
        self.store.uncertain('daily')
        with self.assertRaises(PlanError):
            self.store.context_for_session('stored')
        self.assertEqual('uncertain', self.store.snapshot()['state'])
        self.assertFalse(self.store.begin('daily'))

    def test_revocation_between_reservation_and_adapter_prevents_new_read(self):
        self.store.begin('daily')
        context = self.store.run_context('daily')
        tools = DailyTools(self.temp.name, 'daily', context['token'])
        reserve = tools.store.consume_call
        def switched(*args):
            frozen = reserve(*args)
            self.store.uncertain('daily')
            self.store.configure(dict(self.settings, email='other@example.org'))
            return frozen
        tools.store.consume_call = switched
        with patch('today_core.agent_tools.search_mail') as search:
            result = tools.call('today_mail_search', {'query': 'x'})
        search.assert_not_called()
        self.assertEqual('active_daily_run_required', result['error'])

    def test_inflight_read_keeps_reserved_account_and_discards_revoked_result(self):
        self.store.begin('daily')
        context = self.store.run_context('daily')
        tools = DailyTools(self.temp.name, 'daily', context['token'])
        selected = []
        def adapter(home, frozen, message):
            self.store.uncertain('daily')
            self.store.configure(dict(self.settings, email='other@example.org'))
            selected.append(frozen['email'])
            return dict(id='gmail:old', source='gmail', title='private', url='https://example.org', text='old account body'), 'updated'
        with patch('today_core.agent_tools.read_mail', side_effect=adapter):
            result = tools.call('today_mail_read', {'message_id': 'abc'})
        self.assertEqual(['wanted@example.org'], selected)
        self.assertEqual('active_daily_run_required', result['error'])
        self.assertNotIn('body', json.dumps(result))
        self.assertEqual([], self.store.cache_index())

    def test_reused_request_id_never_makes_previous_generation_fresh_or_resolvable(self):
        self.store.begin('same')
        record = dict(id='gmail:one', source='gmail', title='old', url='https://example.org', text='old evidence')
        self.store.cache_source('same', 'gmail', [record], 'checked')
        self.store.finish('same', self.store.cached_inputs('same'), {'tasks': [dict(task_key='entry', source_id='gmail:one',
                          title='ESを提出する', reason='締切を確認', deadline=None, priority=1)]})
        self.store.configure(dict(self.settings, enabled=False))
        self.store.configure(self.settings)
        self.store.begin('same')
        inputs = self.store.cached_inputs('same')
        self.assertFalse(inputs[0]['records'][0]['retrieved_in_current_run'])
        self.assertFalse(self.store.cached_records(['gmail:one'], 'same')[0]['retrieved_in_current_run'])
        with self.assertRaises(PlanError) as error:
            self.store.finish('same', inputs, {'tasks': [], 'resolved': [dict(task_key='entry', source_id='gmail:one', reason='not current')]})
        self.assertEqual('unconfirmed_resolution', error.exception.code)
        self.assertEqual(1, len(self.store.snapshot()['review_tasks']))

    def test_legacy_request_id_marker_migrates_as_unconfirmed_evidence(self):
        home = Path(self.temp.name) / 'legacy'
        root = home / 'today_home'
        root.mkdir(parents=True)
        record = dict(id='gmail:legacy', source='gmail', title='legacy', url='https://example.org', text='keep this')
        with closing(sqlite3.connect(root / 'plan.sqlite3')) as db:
            db.execute('CREATE TABLE state(key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute('INSERT INTO state VALUES(?,?)', ('settings', json.dumps(self.settings)))
            db.execute('CREATE TABLE source_cache(id TEXT PRIMARY KEY,source TEXT,payload TEXT,checked_at TEXT,last_run TEXT)')
            db.execute('INSERT INTO source_cache VALUES(?,?,?,?,?)', (record['id'], 'gmail', json.dumps(record), self.current.isoformat(), 'same'))
            db.commit()
        migrated = PlanStore(home, lambda: self.current)
        migrated.begin('same')
        evidence = migrated.cached_inputs('same')[0]['records'][0]
        self.assertEqual('keep this', evidence['text'])
        self.assertFalse(evidence['retrieved_in_current_run'])
        self.assertEqual('', evidence['retrieval_run_token'])

    def test_terminal_writes_serialize_with_configuration_and_request_id_reuse(self):
        for operation in ('fail', 'uncertain', 'record_terminal'):
            with self.subTest(operation=operation):
                home = Path(self.temp.name) / operation
                owner = PlanStore(home, lambda: self.current)
                owner.configure(self.settings)
                owner.begin('same')
                owner.bind_session('same', operation + '-old', operation + '-old-stored')
                old = PlanStore(home, lambda: self.current)
                old.lease_tokens['same'] = owner.run_context('same')['token']
                original_db = old.db
                ready, release, new_started, new_done = (threading.Event() for _ in range(4))
                errors, intercepted = [], []
                class Proxy:
                    def __init__(self, db):
                        self.db = db
                    def __getattr__(self, name):
                        return getattr(self.db, name)
                    def execute(self, sql, *args):
                        if sql.startswith('UPDATE ') and not intercepted:
                            intercepted.append(True)
                            ready.set()
                            if not release.wait(3):
                                raise AssertionError('test writer was not released')
                        return self.db.execute(sql, *args)
                @contextmanager
                def guarded_db():
                    with original_db() as db:
                        yield Proxy(db)
                old.db = guarded_db
                self.current += timedelta(minutes=16)
                def finish_old():
                    try:
                        if operation == 'record_terminal':
                            old.record_terminal('same', dict(status='terminal', outcome='completed'))
                        else:
                            getattr(old, operation)('same')
                    except Exception as error:
                        errors.append(error)
                def replace_run():
                    new_started.set()
                    try:
                        owner.configure(dict(self.settings, email='other@example.org'))
                        owner.begin('same')
                        owner.bind_session('same', operation + '-new', operation + '-new-stored')
                    except Exception as error:
                        errors.append(error)
                    finally:
                        new_done.set()
                first = threading.Thread(target=finish_old)
                second = threading.Thread(target=replace_run)
                first.start()
                try:
                    self.assertTrue(ready.wait(2))
                    second.start()
                    self.assertTrue(new_started.wait(2))
                    self.assertFalse(new_done.wait(0.05))
                finally:
                    release.set()
                    first.join(3)
                    if second.ident is not None:
                        second.join(3)
                self.assertFalse(first.is_alive() or second.is_alive())
                self.assertEqual([], errors)
                old.fail('same')
                old.uncertain('same')
                with self.assertRaises(PlanError):
                    old.record_terminal('same', dict(status='terminal', outcome='completed'))
                self.assertEqual('running', owner.snapshot()['state'])
                self.assertNotEqual(old.lease_tokens['same'], owner.run_context('same')['token'])
