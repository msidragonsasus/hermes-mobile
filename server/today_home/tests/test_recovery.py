import copy
import json
import tempfile
import unittest
from datetime import timedelta

from today_core.store import PlanError, PlanStore, now, parse_plan_response


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.current = now()
        self.store = PlanStore(self.temp.name, lambda: self.current)
        self.store.configure(dict(email='wanted@example.org', manaba_url='', enabled=True))
        self.store.begin('daily')
        self.store.bind_session('daily', 'public', 'stored')
        self.store.prepare_submit('daily')
        self.store.record_admission('daily', 'admission')
        self.store.consume_call('daily', 'today_mail_read', 80)
        self.store.cache_source('daily', 'gmail', [dict(id='gmail:one', source='gmail', title='Evidence',
                                url='https://example.org', text='Tomorrow')], 'updated')
        self.answer = dict(tasks=[dict(task_key='company/entry', source_id='gmail:one', title='Prepare ES',
                                      reason='Due tomorrow', deadline=None, priority=2)],
                           verification_notes=['Mailbox coverage is partial'])
        self.receipt = dict(admission_id='admission', ref=dict(session_id='public'), status='terminal',
                            outcome='completed', execution_generation=7,
                            result=dict(final_response=json.dumps(self.answer)))
        self.store.record_terminal('daily', self.receipt)
        self.store.fail('daily')
        self.current += timedelta(minutes=20)

    def recover(self, receipt=None, answer=None):
        self.store.finish('daily', self.store.cached_inputs('daily'), answer or self.answer,
                          recovery_receipt=self.receipt if receipt is None else receipt)

    def test_known_notes_are_inert_but_unknown_output_and_bad_evidence_are_rejected(self):
        parsed = parse_plan_response(json.dumps(self.answer))
        self.assertEqual({'tasks'}, set(parsed))
        with self.assertRaises(PlanError):
            parse_plan_response(json.dumps(dict(self.answer, unexpected=[])))
        invalid = copy.deepcopy(self.answer)
        invalid['tasks'][0]['source_id'] = 'gmail:not-read'
        with self.assertRaises(PlanError):
            self.recover(answer=invalid)
        self.assertEqual('failed', self.store.snapshot()['state'])

    def test_same_completed_receipt_recovers_once_without_reopening_reads_or_retrieving(self):
        before = self.store.cached_inputs('daily')
        self.recover()
        self.assertEqual('complete', self.store.snapshot()['state'])
        self.assertEqual([], self.store.snapshot()['tasks'])
        self.assertEqual('Prepare ES', self.store.snapshot()['review_tasks'][0]['title'])
        self.assertEqual(before, self.store.cached_inputs('daily'))
        with self.store.db() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM requests').fetchone()[0])
            self.assertEqual(1, db.execute('SELECT used FROM run_calls').fetchone()[0])
            self.assertEqual('terminal', db.execute('SELECT phase FROM run_contexts').fetchone()[0])
        with self.assertRaises(PlanError):
            self.store.context_for_session('stored')
        with self.assertRaises(PlanError):
            self.recover()

    def test_mismatched_receipt_or_result_is_atomic_and_denied(self):
        for field, value in [('admission_id', 'other'), ('outcome', 'failed'), ('execution_generation', 8),
                             ('ref', dict(session_id='other'))]:
            with self.subTest(field=field):
                receipt = dict(self.receipt, **{field: value})
                with self.assertRaises(PlanError):
                    self.recover(receipt)
                self.assertEqual('failed', self.store.snapshot()['state'])
                self.assertEqual([], self.store.snapshot()['tasks'])
        changed = copy.deepcopy(self.answer)
        changed['tasks'][0]['title'] = 'Changed'
        with self.assertRaises(PlanError):
            self.recover(answer=changed)

    def test_newer_request_prevents_old_recovery(self):
        self.store.begin('newer')
        with self.assertRaises(PlanError):
            self.recover()
        self.assertEqual([], self.store.snapshot()['tasks'])

    def test_recovery_requires_present_strict_integer_generations_on_both_sides(self):
        token = self.store.run_record('daily')['token']
        for saved, delivered in [(None, None), (1, True), (7, 7.0), (True, 1), (7.0, 7)]:
            with self.subTest(saved=saved, delivered=delivered):
                with self.store.db() as db:
                    key = 'runtime:' + token
                    metadata = json.loads(db.execute('SELECT value FROM state WHERE key=?', (key,)).fetchone()[0])
                    metadata.pop('execution_generation', None)
                    if saved is not None:
                        metadata['execution_generation'] = saved
                    db.execute('UPDATE state SET value=? WHERE key=?', (json.dumps(metadata), key))
                receipt = dict(self.receipt)
                receipt.pop('execution_generation', None)
                if delivered is not None:
                    receipt['execution_generation'] = delivered
                with self.assertRaises(PlanError):
                    self.recover(receipt)
                self.assertEqual('failed', self.store.snapshot()['state'])
                self.assertEqual([], self.store.snapshot()['tasks'])

    def test_settings_change_or_missing_worker_generation_denies_recovery(self):
        opened = PlanStore(self.temp.name, lambda: self.current)
        with self.assertRaises(PlanError):
            opened.finish('daily', opened.cached_inputs('daily'), self.answer, recovery_receipt=self.receipt)
        self.store.configure(dict(email='other@example.org', manaba_url='', enabled=True))
        with self.assertRaises(PlanError):
            self.recover()
