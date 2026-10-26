"""Account-scale and interruption tests; all provider responses are synthetic."""
import base64
import json
import re
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.gmail_reader import GmailReader, account_reference
from career_core.mail_collector import MailCollector
from career_core.mail_context import page, prepare, snapshot
from career_core.mail_store import MailStore
from career_core.mail_sync import DAY, INTERVAL, MailSync, status_view
from career_core.mail_transport import MailReadError, provider_error, read_batch
from career_core.store import CareerError, Store
from career_core.workspace_reader import execute
from tools.benchmark_mail import select


class Mailbox:
    def __init__(self, stamp):
        self.stamp, self.messages, self.history, self.reads, self.calls = stamp, {}, [], [], []
        self.error, self.expired, self.page_expired, self.hook = None, False, False, None

    def add(self, identity, sender, age=0):
        self.messages[identity] = {
            'id': identity, 'threadId': identity, 'internalDate': str(int((self.stamp - age) * 1000)),
            'labelIds': ['INBOX'], 'snippet': 'synthetic', 'payload': {
                'mimeType': 'text/plain', 'headers': [{'name': 'From', 'value': sender}, {'name': 'Subject', 'value': identity}],
                'body': {'data': base64.urlsafe_b64encode(b'synthetic original mail').decode()}}}
        self.history.append(identity)

    def get(self, resource, **params):
        self.calls.append((resource, params))
        if resource == 'profile':
            return {'emailAddress': 'synthetic@example.test', 'historyId': str(len(self.history))}
        if resource == 'history':
            if self.expired:
                self.expired = False
                raise MailReadError('mail_cursor_expired')
            ids = self.history[int(params['startHistoryId']):]
        else:
            senders = set(re.findall(r'from:([^ }]+)', params['q']))
            after, before = re.search(r'after:(\d+)', params['q']), re.search(r'before:(\d+)', params['q'])
            ids = [identity for identity, msg in self.messages.items()
                   if msg['payload']['headers'][0]['value'] in senders
                   and (not after or int(msg['internalDate']) > int(after[1]) * 1000)
                   and (not before or int(msg['internalDate']) < int(before[1]) * 1000)]
        if self.page_expired and params.get('pageToken'):
            self.page_expired = False
            raise MailReadError('mail_page_expired')
        offset = int(params.get('pageToken', 0))
        selected = ids[offset:offset + 100]
        result = ({'history': [{'messagesAdded': [{'message': {'id': identity}} for identity in selected]}],
                   'historyId': str(len(self.history))} if resource == 'history'
                  else {'messages': [{'id': identity} for identity in selected]})
        if offset + 100 < len(ids):
            result['nextPageToken'] = str(offset + 100)
        return result

    def get_many(self, ids, *, format, **tuning):
        self.reads.append((format, list(ids), tuning))
        result = []
        for identity in ids:
            if self.error and identity == self.error[0] and (len(self.error) == 3 or format == self.error[3]):
                result.append({'id': identity, 'error': self.error[1], 'retry_after': self.error[2]})
            else:
                msg = self.messages[identity]
                if format == 'metadata':
                    msg = dict(msg, payload={'headers': msg['payload']['headers']})
                result.append({'id': identity, 'data': msg})
        if self.hook:
            hook, self.hook = self.hook, None
            hook()
        return result

    def close(self):
        pass


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.mail = MailStore(self.store)
        self.account = account_reference('synthetic@example.test')
        self.mail.configure(self.account, True)
        self.clock = time.time()
        self.api = Mailbox(self.clock)
        self.sender('a', 'jobs@example.test')
        with self.store.transaction() as db:
            self.store.set_meta(db, 'mail:tuning:' + self.account, {'verified': True, 'batch_size': 20, 'concurrency': 1})

    def sender(self, company, address):
        with self.store.transaction() as db:
            if not self.store.entity(db, 'companies', company):
                self.store.put(db, 'companies', company, 0, {'name': company}, dirty=False)
        self.mail.configure_sender(company, address, True)

    def step(self):
        # Construct new objects every time, as after a process restart.
        collector = MailCollector(self.store, reader_factory=lambda account, **kw: GmailReader(account, api=self.api, **kw))
        return MailSync(collector, clock=lambda: self.clock).step()

    def state(self):
        with self.store.connect() as db:
            return self.store.meta(db, 'mail:sync:' + self.account, {})

    def view(self):
        return status_view(self.state(), self.mail.settings(), self.mail.active_assignments(), at=self.clock)

    def drain(self, bound=300):
        for _ in range(bound):
            result = self.step()
            if self.view()['backfill_complete'] and self.view()['fresh']:
                return
        self.fail('Sync did not finish')

    def test_130_senders_large_pages_recent_first_and_constant_idle_calls(self):
        for i in range(130):
            self.sender('c' + str(i), f's{i}@example.test')
            self.api.add(format(i + 1, 'x'), f's{i}@example.test')
        for i in range(350):
            self.api.add(format(i + 1000, 'x'), 'jobs@example.test', age=DAY)
        for i in range(600):
            self.api.add(format(i + 2000, 'x'), 'jobs@example.test', age=100 * DAY)
        self.step()  # Capture the initial history position.
        while not self.view()['recent_complete']:
            self.step()
        self.assertEqual(350, len(self.mail.messages('a', limit=2000)))
        self.assertFalse(self.view()['backfill_complete'])
        self.step()
        self.clock += INTERVAL + 1
        self.api.stamp = self.clock
        self.api.add('ffff', 'jobs@example.test')
        self.step()
        self.assertIn('ffff', {m['message_id'] for m in self.mail.messages('a', limit=2000)})
        self.assertFalse(self.view()['backfill_complete'])
        self.drain()
        self.assertEqual(951, len(self.mail.messages('a', limit=2000)))
        self.assertEqual(1081, self.view()['saved_messages'])
        self.assertTrue(all(len(p['q']) < 6000 for r, p in self.api.calls if r == 'messages'))
        count = len(self.api.calls)
        self.step()
        self.assertEqual(count, len(self.api.calls))  # Even profile is not queried while idle.
        self.clock += INTERVAL + 1
        self.step()
        self.assertEqual(['profile', 'history'], [r for r, _ in self.api.calls[count:]])

    def test_partial_error_keeps_cursor_retries_only_missing_and_honors_wait(self):
        self.drain()
        before = self.state()['delta']['history_id']
        self.api.add('a1', 'jobs@example.test')
        self.api.add('a2', 'jobs@example.test')
        self.api.error = ('a2', 'mail_rate_limited', 120)
        self.mail.request_sync()
        self.step()
        self.assertEqual(before, self.state()['delta']['history_id'])
        self.assertFalse(self.view()['fresh'])
        self.assertEqual(10, self.state()['tuning']['batch_size'])
        calls = len(self.api.calls)
        self.step()
        self.assertEqual(calls, len(self.api.calls))
        self.assertGreaterEqual(self.state()['retry_at'], self.clock + 120)
        self.api.error = None
        self.clock += 121
        self.step()
        self.assertTrue(self.view()['fresh'])
        self.assertEqual({'a1', 'a2'}, {m['message_id'] for m in self.mail.messages('a')})

    def test_atomic_commit_recovers_pending_ids_after_save_crash(self):
        self.api.add('aa', 'jobs@example.test')
        self.step()
        original = self.mail.save_message
        def interrupted(*args):
            original(*args)
            raise RuntimeError('simulated process failure during transaction')
        with patch.object(MailStore, 'save_message', side_effect=interrupted):
            with self.assertRaises(CareerError):
                self.step()
        self.assertEqual([], self.mail.messages('a'))
        self.assertTrue(any(j.get('pending') == ['aa'] for j in self.state()['jobs']))
        self.clock += 10
        self.drain()
        self.assertEqual(1, len(self.mail.messages('a')))

    def test_partial_full_response_commits_success_without_advancing_cursor(self):
        self.drain()
        self.api.add('a1', 'jobs@example.test')
        self.api.add('a2', 'jobs@example.test')
        self.api.error = ('a2', 'workspace_unavailable', 0, 'full')
        ticket = self.mail.request_sync()
        self.step()
        self.assertEqual(['a1'], [m['message_id'] for m in self.mail.messages('a')])
        self.assertEqual(['a2'], self.state()['delta']['pending'])
        self.assertLess(self.state()['completed_generation'], ticket['generation'])
        self.clock += 3
        self.api.error = None
        self.step()
        self.assertEqual(1, sum(ids.count('a1') for kind, ids, _ in self.api.reads if kind == 'full'))

    def test_initial_history_never_notifies_even_with_old_notification_baseline(self):
        from career_core.collection_store import digest
        self.api.add('a1', 'jobs@example.test', age=DAY)
        self.api.messages['a1']['payload']['headers'][1]['value'] = '面接の日程について'
        with self.store.transaction() as db:
            self.store.set_meta(db, 'notice-mail-start:' + digest('a', self.account), '2020-01-01T00:00:00+00:00')
        self.drain()
        with self.store.connect() as db:
            details = [json.loads(r[0]) for r in db.execute('SELECT data FROM career_notice_details')]
        self.assertTrue(details)
        self.assertFalse(any(item['eligible'] for item in details))

    def test_sender_disabled_mid_request_prevents_body_read(self):
        self.api.add('a1', 'jobs@example.test')
        self.step()
        self.api.hook = lambda: self.mail.configure_sender('a', 'jobs@example.test', False)
        with self.assertRaises(CareerError):
            self.step()
        self.assertFalse(any(kind == 'full' for kind, _, _ in self.api.reads))

    def test_expired_history_and_page_restart_use_cache(self):
        for i in range(220):
            self.api.add(format(i + 1, 'x'), 'jobs@example.test')
        self.step()
        for _ in range(5):
            self.step()
        self.api.page_expired = True
        self.drain()
        full = sum(len(ids) for kind, ids, _ in self.api.reads if kind == 'full')
        self.assertEqual(220, full)
        self.clock += INTERVAL + 1
        self.api.expired = True
        self.step()
        self.assertFalse(self.view()['fresh'])
        self.api.add('ffff', 'jobs@example.test')
        self.drain()
        self.assertEqual(221, sum(len(ids) for kind, ids, _ in self.api.reads if kind == 'full'))
        self.assertEqual(221, len(self.mail.messages('a', 500)))

    def test_new_company_shared_sender_reuses_body_without_rewinding_history(self):
        self.api.add('a1', 'jobs@example.test', age=60 * DAY)
        self.drain()
        cursor = self.state()['delta']['history_id']
        self.sender('b', 'jobs@example.test')
        reads = len(self.api.reads)
        self.drain()
        self.assertEqual(cursor, self.state()['delta']['history_id'])
        self.assertEqual(reads, len(self.api.reads))
        self.assertEqual('a1', self.mail.messages('b')[0]['message_id'])
        self.mail.configure_sender('a', 'jobs@example.test', False)
        self.mail.configure_sender('b', 'jobs@example.test', False)
        self.step()
        self.api.add('a2', 'jobs@example.test')
        self.sender('a', 'jobs@example.test')
        self.drain()
        self.assertEqual(2, len(self.mail.messages('a')))
        self.assertEqual(1, len(self.mail.messages('b')))

    def test_disable_or_account_change_between_metadata_and_body_cancels(self):
        self.api.add('a1', 'jobs@example.test')
        self.step()
        self.api.hook = lambda: self.mail.configure(self.account, False)
        with self.assertRaises(CareerError) as error:
            self.step()
        self.assertEqual('collection_cancelled', error.exception.code)
        self.assertFalse(any(kind == 'full' for kind, _, _ in self.api.reads))
        self.assertEqual([], self.mail.messages('a'))

    def test_first_connection_failure_is_visible_and_legacy_success_is_not_fresh(self):
        self.mail.update_status('updated', success=True)
        self.assertFalse(self.mail.status()['fresh'])
        with patch.object(self.api, 'get', side_effect=MailReadError('authentication_required')):
            with self.assertRaises(CareerError):
                self.step()
        self.assertEqual('auth_required', self.view()['state'])
        self.assertEqual('authentication_required', self.mail.sync_view()['status']['reason'])
        self.clock += INTERVAL + 1
        self.drain()
        with self.store.connect() as db:
            notice = self.store.meta(db, 'notice-auth:mail:' + self.account + ':a', {})
        self.assertFalse(notice['active'])  # Recovery also clears attention when there is no new mail.

    def test_agent_reads_recent_first_even_when_older_mail_is_saved_later(self):
        self.api.add('a1', 'jobs@example.test', age=DAY)
        self.api.add('a2', 'jobs@example.test', age=100 * DAY)
        self.drain()
        run = {'id': 'order', 'kind': 'daily', 'request': {'scope': {}}}
        self.assertEqual(['a1', 'a2'], [row['message_id'] for row in page(self.store, run, 'a')['messages']])

    def test_agent_pagination_pins_bound_and_timeout_keeps_saved_data(self):
        for i in range(45):
            self.api.add(format(i + 1, 'x'), 'jobs@example.test')
        self.drain()
        run = {'id': 'run-a', 'kind': 'daily', 'request': {'scope': {}}}
        with patch('career_core.mail_worker.worker', return_value=Mock()):
            pinned = prepare(self.store, run, timeout=0)
        self.assertEqual('mail_sync_pending', pinned['refresh_issue'])
        self.assertIn('未確認を新着なし', pinned['guidance'])
        first = page(self.store, run, 'a')
        self.api.add('ffff', 'jobs@example.test')
        self.step()
        second = page(self.store, run, 'a', first['next_cursor'])
        third = page(self.store, run, 'a', second['next_cursor'])
        all_mail = first['messages'] + second['messages'] + third['messages']
        self.assertEqual([20, 20, 5], [len(p['messages']) for p in (first, second, third)])
        self.assertEqual(45, len({m['message_id'] for m in all_mail}))
        self.assertNotIn('ffff', {m['message_id'] for m in all_mail})
        self.assertEqual(pinned, snapshot(self.store, run))
        with self.assertRaises(CareerError):
            page(self.store, run, 'other')

    def test_http_status_is_local_and_updates_only_queue(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def session(request: Request, call_next):
            request.state.session = {'user': 'synthetic'}
            return await call_next(request)
        app.include_router(create_router(lambda: self.store))
        client = TestClient(app)
        with patch('career_core.workspace_reader.WorkspaceApi', side_effect=AssertionError('No Gmail for status or enqueue')):
            result = client.get('/collection/mail-status')
            self.assertEqual(200, result.status_code)
            first = client.post('/collection/check', json={'channel': 'mail'}).json()
            second = client.post('/collection/check', json={'channel': 'mail'}).json()
        self.assertEqual(first['generation'] + 1, second['generation'])
        self.step()
        self.assertEqual(second['generation'], self.state()['completed_generation'])

    def test_measured_request_frequency_is_a_ceiling_even_after_recovery(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, 'mail:tuning:' + self.account,
                                {'verified': True, 'batch_size': 10, 'concurrency': 1, 'requests_per_second': .5})
        for index in range(100):
            self.api.add(format(index, 'x'), 'jobs@example.test')
        first = self.step()
        self.assertGreater(first['delay'], 3.5)
        state = self.state()
        state['requests_per_second'], state['success_steps'] = 4, 4
        with self.store.transaction() as db:
            self.store.set_meta(db, 'mail:sync:' + self.account, state)
        self.step()
        self.assertEqual(.5, self.state()['requests_per_second'])
        for _ in range(6):
            self.step()
            self.assertLessEqual(self.state()['requests_per_second'], .5)

    def test_backoff_clock_starts_when_failure_arrives(self):
        def slow_failure(*args, **kwargs):
            self.clock += 30
            raise MailReadError('mail_rate_limited')
        self.api.get = slow_failure
        with self.assertRaises(MailReadError):
            self.step()
        self.assertEqual(self.clock + 60, self.state()['retry_at'])

    def test_applying_measured_tuning_requires_three_trials_and_same_account(self):
        from tools.apply_mail_tuning import apply
        trials = [{'batch_size': 10, 'concurrency': 1, 'error_codes': [], 'messages_per_second': 3.5}
                  for _ in range(3)]
        report = {'state': 'measured', 'account_id': self.account, 'trials': trials, 'selected': select(trials)}
        with self.assertRaises(CareerError):
            apply(self.store, dict(report, trials=trials[:2]))
        with self.assertRaises(CareerError):
            apply(self.store, dict(report, account_id='different'))
        self.assertEqual(10, apply(self.store, report)['batch_size'])
        with self.store.connect() as db:
            saved = self.store.meta(db, 'mail:tuning:' + self.account, {})
        self.assertTrue(saved['verified'])
        self.assertEqual(64, len(saved['report_sha256']))
        paced = [dict(row, requests_per_second=.5) for row in trials]
        paced_report = dict(report, trials=paced, selected=select(paced))
        self.assertEqual(.5, apply(self.store, paced_report)['requests_per_second'])
        invalid = [dict(row, requests_per_second=8) for row in trials]
        with self.assertRaises(CareerError):
            apply(self.store, dict(report, trials=invalid, selected=dict(report['selected'], requests_per_second=8)))


class TransportTests(unittest.TestCase):
    def test_official_python_can_be_selected_for_the_entire_sync_path(self):
        service = Mock()
        service.users.return_value.getProfile.return_value.execute.return_value = {'historyId': '1'}
        module = Mock()
        module.build_service.return_value = service
        self.assertEqual({'historyId': '1'}, execute(module, 'profile', {}, prefer_python=True))
        module._run_gws.assert_not_called()

    def test_tuning_requires_three_clean_trials_and_selects_lowest_load_within_90_percent(self):
        trials = [{'batch_size': b, 'concurrency': c, 'error_codes': [], 'messages_per_second': speed}
                  for b, c, speed in [(5, 1, 9.1), (20, 1, 10), (20, 2, 9.5)] for _ in range(3)]
        self.assertEqual(5, select(trials)['batch_size'])
        self.assertIsNone(select(trials[:2]))
        trials[0]['error_codes'] = ['mail_rate_limited']
        self.assertEqual(20, select(trials)['batch_size'])

    def test_retry_deadline_and_retry_info_are_preserved_without_raw_messages(self):
        from datetime import datetime, timedelta, timezone
        error = RuntimeError('sensitive provider text')
        error.resp = type('Response', (dict,), {'status': 429})({'retry-after': '10'})
        due = (datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat()
        error.content = json.dumps({'error': {'message': 'Too many concurrent requests for user private@example.test. Retry after ' + due}}).encode()
        value = provider_error(error, 'messages/ab')
        self.assertEqual('concurrent', value['limit_kind'])
        self.assertEqual(429, value['http_status'])
        self.assertGreater(value['retry_after'], 119)
        self.assertNotIn('private', json.dumps(value))
        error.content = json.dumps({'error': {'details': [
            {'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': '240.5s'}]}}).encode()
        self.assertEqual(240.5, provider_error(error, 'messages/ab')['retry_after'])

    def test_daily_limit_is_not_a_short_rate_retry_even_for_http_429(self):
        from career_core.mail_transport import retry_wait
        error = RuntimeError()
        error.resp = type('Response', (dict,), {'status': 429})({})
        error.content = json.dumps({'error': {'errors': [{'reason': 'dailyLimitExceeded'}]}}).encode()
        result = provider_error(error, 'messages/ab')
        self.assertEqual('mail_daily_limit', result['error'])
        self.assertEqual('daily', result['limit_kind'])
        self.assertGreaterEqual(retry_wait(MailReadError(result['error'], result['retry_after'])), 3600)
        self.assertEqual([60, 120, 240], [retry_wait(MailReadError('mail_rate_limited'), n) for n in (1, 2, 3)])
        self.assertEqual(2000, retry_wait(MailReadError('mail_rate_limited', 2000)))

    def test_error_classes_and_retry_after_are_preserved_without_content(self):
        for status, reason, expected in [(403, 'userRateLimitExceeded', 'mail_rate_limited'),
                                        (403, 'insufficientPermissions', 'mail_scope_unsupported'),
                                        (403, 'domainPolicy', 'mail_domain_policy'),
                                        (403, 'forbidden', 'workspace_permission_denied'),
                                        (401, 'authError', 'authentication_required'),
                                        (429, '', 'mail_rate_limited'), (503, '', 'workspace_unavailable')]:
            error = RuntimeError('sensitive provider text')
            error.resp = type('Response', (dict,), {'status': status})({'retry-after': '90'})
            error.content = json.dumps({'error': {'errors': [{'reason': reason}], 'message': 'private'}}).encode()
            result = provider_error(error, 'messages/ab')
            self.assertEqual(expected, result['error'])
            self.assertEqual(90, result['retry_after'])
            self.assertNotIn('private', json.dumps(result))

    def test_batch_enforces_inner_limit_and_owns_one_service_per_lane(self):
        module = Mock()
        services = []
        class Service:
            closed = False
            def users(self): return self
            def messages(self): return self
            def get(self, **args): return args
            def new_batch_http_request(self, callback):
                class Batch:
                    def __init__(self): self.items = []
                    def add(self, args, request_id): self.items.append((request_id, args))
                    def execute(self):
                        for identity, args in self.items: callback(identity, args, None)
                return Batch()
            def close(self): self.closed = True
        def build(*args):
            services.append(Service())
            return services[-1]
        module.build_service.side_effect = build
        result = read_batch(module, [format(i, 'x') for i in range(40)], 'metadata', 20, 2)
        self.assertEqual(40, len(result['items']))
        self.assertEqual(2, len(services))
        self.assertTrue(all(s.closed for s in services))
        with self.assertRaises(ValueError):
            read_batch(module, ['a'], 'metadata', 40, 2)
        with self.assertRaises(ValueError):
            read_batch(module, ['a'], 'raw', 1, 1)


class BenchmarkTests(unittest.TestCase):
    class Clock:
        value = 0.0
        def monotonic(self): return self.value
        def sleep(self, seconds): self.value += seconds

    class Reader:
        def __init__(self, clock, *, seconds=lambda n: 17, fail_batch=None):
            from types import SimpleNamespace
            self.clock, self.seconds, self.fail_batch = clock, seconds, fail_batch
            self.calls, self.reads = 0, []
            self.api = SimpleNamespace(response_bytes=0, http_requests=0)
        def profile(self):
            self.calls += 1
            self.clock.value += 1
        def read_ids(self, ids, senders, cached, tuning):
            self.reads.append((self.clock.value, list(ids), dict(tuning)))
            self.calls += 2 * len(ids)
            self.api.http_requests += 2 * tuning['concurrency']
            self.api.response_bytes += 100 * len(ids)
            self.clock.value += self.seconds(len(ids)) - 1
            if self.fail_batch == tuning['batch_size']:
                self.fail_batch = None
                return [], set(), [MailReadError('mail_rate_limited', 23)]
            return [{'id': identity} for identity in ids], set(ids), []

    def run_benchmark(self, reader, **options):
        from tools.benchmark_mail import run_trials
        with patch('tools.benchmark_mail.time.monotonic', reader.clock.monotonic), patch(
                'tools.benchmark_mail.time.sleep', reader.clock.sleep):
            return run_trials(reader, [format(i, 'x') for i in range(40)], {'jobs@example.test'}, **options)

    def test_short_sweep_then_three_identical_full_samples_per_candidate(self):
        reader = self.Reader(self.Clock())
        result = self.run_benchmark(reader)
        sweep = [r for r in result['trials'] if r['phase'] == 'sweep']
        self.assertEqual([1, 2, 5, 10, 20, 40, 40], [r['planned_messages'] for r in sweep])
        verification = [r for r in result['trials'] if r['phase'] == 'verification']
        self.assertEqual(6, len(verification))
        self.assertTrue(all(r['complete'] and r['planned_messages'] == 40 for r in verification))
        full_sample = [format(i, 'x') for i in range(40)]
        self.assertTrue(all(ids == full_sample for _, ids, _ in reader.reads[7:]))
        self.assertTrue(all(t['batch_size'] * t['concurrency'] <= 40 for _, _, t in reader.reads))
        self.assertEqual({'batch_size': 40, 'concurrency': 1, 'verified': True,
                          'median_messages_per_second': 1.975, 'requests_per_second': 4}, result['selected'])
        self.assertEqual('measured', result['state'])

    def test_partial_time_budget_stop_is_not_a_gmail_error_or_a_verified_trial(self):
        reader = self.Reader(self.Clock(), seconds=lambda n: n)
        result = self.run_benchmark(reader, max_seconds=100)
        self.assertEqual('benchmark_budget_reached', result['stop']['code'])
        last = result['trials'][-1]
        self.assertEqual('verification', last['phase'])
        self.assertFalse(last['complete'])
        self.assertGreater(last['completed_ids'], 0)
        self.assertLess(last['completed_ids'], 40)
        self.assertEqual([], last['error_codes'])
        self.assertEqual(0, last['error_rate'])
        self.assertIsNone(result['selected'])

    def test_request_budget_includes_preflight_and_current_trial_before_sending(self):
        reader = self.Reader(self.Clock())
        result = self.run_benchmark(reader, initial_requests=3, max_requests=10)
        self.assertEqual(1, len(reader.reads))
        self.assertEqual(6, result['requests'])
        self.assertEqual('benchmark_budget_reached', result['stop']['code'])
        self.assertTrue(all(not row['error_codes'] for row in result['trials']))

    def test_rate_limit_stops_increases_and_waits_before_lower_candidate(self):
        reader = self.Reader(self.Clock(), fail_batch=5)
        result = self.run_benchmark(reader)
        self.assertEqual([1, 2, 5, 1, 2], [r['batch_size'] for r in result['trials'] if r['phase'] == 'sweep'])
        self.assertTrue(all(t['batch_size'] <= 2 and t['concurrency'] == 1 for _, _, t in reader.reads[3:]))
        self.assertGreaterEqual(reader.reads[3][0] - reader.reads[2][0], 17 + 60)
        self.assertIsNone(result['stop'])
        self.assertEqual('mail_rate_limited', result['recoveries'][0]['code'])
        self.assertEqual(2, result['selected']['batch_size'])

    def test_sweeps_and_incomplete_attempts_cannot_certify_tuning(self):
        row = {'batch_size': 5, 'concurrency': 1, 'error_codes': [], 'messages_per_second': 9,
               'complete': True, 'phase': 'sweep'}
        self.assertIsNone(select([dict(row) for _ in range(3)]))
        verified = [dict(row, phase='verification') for _ in range(2)]
        partial = dict(row, phase='verification', complete=False, stop_code='benchmark_budget_reached')
        self.assertIsNone(select(verified + [partial, row]))
        self.assertTrue(select(verified + [partial, dict(row, phase='verification')])['verified'])


    def test_resume_uses_failed_envelope_but_verifies_a_new_fixed_sample(self):
        from tools.benchmark_mail import recovery_hint
        report = {'account_id': 'account', 'trials': [
            {'batch_size': b, 'concurrency': 1, 'error_codes': ['mail_rate_limited']} for b in (40, 20)],
                  'stop': {'code': 'mail_rate_limited', 'retry_after': 0}}
        hint = recovery_hint(report, 'account')
        self.assertEqual(60, hint.pop('wait_seconds'))
        self.assertEqual({'max_batch': 10, 'initial_rate': 1, 'limited': True}, hint)
        with self.assertRaises(MailReadError):
            recovery_hint(report, 'different')
        reader = self.Reader(self.Clock())
        result = self.run_benchmark(reader, **hint)
        self.assertTrue(all(t['batch_size'] <= 10 and t['concurrency'] == 1 for _, _, t in reader.reads))
        verified = [r for r in result['trials'] if r['phase'] == 'verification']
        self.assertEqual(3, len(verified))
        self.assertTrue(all(r['planned_messages'] == 40 and r['complete'] for r in verified))
        self.assertEqual(1, result['selected']['requests_per_second'])
        self.assertEqual([1604, 1604, 1604], [r['quota_units_estimate'] for r in verified])

    def test_repeated_limits_automatically_reduce_both_load_and_frequency(self):
        class Repeated(self.Reader):
            failures = [40, 20, 10]
            def read_ids(self, ids, senders, cached, tuning):
                if self.failures and tuning['batch_size'] == self.failures[0]:
                    self.fail_batch = self.failures.pop(0)
                return super().read_ids(ids, senders, cached, tuning)
        reader = Repeated(self.Clock(), seconds=lambda n: 2)
        result = self.run_benchmark(reader, max_seconds=4000, max_requests=4000)
        self.assertEqual([20, 10, 5], [r['next_max_batch'] for r in result['recoveries']])
        self.assertEqual([2, 1, .5], [r['next_requests_per_second'] for r in result['recoveries']])
        self.assertEqual([60, 120, 240], [r['wait_seconds'] for r in result['recoveries']])
        self.assertEqual(5, result['selected']['batch_size'])
        self.assertEqual(.5, result['selected']['requests_per_second'])
        self.assertIsNone(result['stop'])

    def test_connection_probe_does_not_switch_routes_after_a_limit(self):
        from tools.benchmark_mail import connection_probe
        api = Mock()
        api.status.return_value = {'ready': True, 'backend': 'python'}
        api.get.side_effect = MailReadError('mail_daily_limit', 3600, http_status=403, limit_kind='daily')
        with patch('tools.benchmark_mail.WorkspaceApi', return_value=api) as factory:
            result = connection_probe('synthetic')
        self.assertEqual(1, factory.call_count)
        self.assertEqual(1, api.get.call_count)
        self.assertEqual('daily', result['workspace_default']['limit_kind'])
        self.assertNotIn('official_python', result)

    def test_daily_limit_stops_the_experiment_without_a_lower_load_retry(self):
        class Daily(self.Reader):
            def read_ids(self, ids, senders, cached, tuning):
                super().read_ids(ids, senders, cached, tuning)
                return [], set(), [MailReadError('mail_daily_limit', 3600, http_status=403, limit_kind='daily')]
        reader = Daily(self.Clock())
        result = self.run_benchmark(reader)
        self.assertEqual(1, len(reader.reads))
        self.assertEqual([], result['recoveries'])
        self.assertEqual('mail_daily_limit', result['stop']['code'])
        self.assertEqual('daily', result['stop']['limit_kind'])
        self.assertIsNone(result['selected'])

    def test_different_frequencies_or_superseded_trials_cannot_be_combined(self):
        row = {'phase': 'verification', 'batch_size': 5, 'concurrency': 1, 'requests_per_second': 1,
               'messages_per_second': .5, 'error_codes': [], 'complete': True}
        self.assertIsNone(select([dict(row), dict(row), dict(row, requests_per_second=.5)]))
        self.assertIsNone(select([dict(row, superseded=True) for _ in range(3)]))
        self.assertEqual(1, select([dict(row) for _ in range(3)])['requests_per_second'])
