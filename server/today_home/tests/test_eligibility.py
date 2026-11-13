"""Task relevance regressions; synthetic data and clocks, no live state."""
import hashlib
import json
import tempfile
import unittest
from datetime import datetime, timedelta
from typing import Any

from today_core.store import JST, PlanError, PlanStore


class EligibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.current = datetime(2026, 10, 28, 10, tzinfo=JST)
        self.store = PlanStore(self.temp.name, lambda: self.current)
        self.store.configure(dict(email='student@example.org', manaba_url='https://university.example/ct/home', enabled=True))

    def legacy(self, key, source='gmail', done=False) -> dict[str, Any]:
        row = dict(id=hashlib.sha256(key.encode()).hexdigest()[:32], task_key=key,
                   source_id=source + ':one', source=source, title='同じ締切のお知らせ',
                   reason='今日必須と旧モデルが推測', deadline='2026-10-23', priority=1,
                   url='https://example.org', source_title='原資料', stale=True,
                   source_checked_at='2026-10-23T10:00:00+09:00', resolved=False, day='2026-10-23')
        with self.store.db() as db:
            db.execute('INSERT INTO tasks(id,payload,done) VALUES(?,?,?)', (row['id'], json.dumps(row), int(done)))
        return row

    def publish(self, key, kind, *, status='pending', policy='remains_actionable', deadline=None, request=None) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        proof = '本人の申込を受付済み。次の面接の準備をしてください。'
        source = dict(id='gmail:proof', source='gmail', title='同じ締切のお知らせ',
                      url='https://example.org', text=proof, checked_at=self.current.isoformat(),
                      retrieved_in_current_run=True)
        row = dict(task_key=key, source_id=source['id'], title='同じ締切のお知らせ',
                   reason='行動の理由', deadline=deadline, priority=1,
                   eligibility=dict(kind=kind, source_id=source['id'], quote=proof, user_intent='confirmed',
                                    action_status=status, deadline_policy=policy))
        request = request or key
        self.assertTrue(self.store.begin(request))
        inputs = [dict(id='gmail', label='mail', state='checked', checked_at=self.current.isoformat(),
                       day=self.current.date().isoformat(), records=[source])]
        self.store.finish(request, inputs, dict(tasks=[row]))
        return row, inputs

    def test_same_title_is_not_a_classifier_and_optional_or_unknown_never_becomes_required(self):
        for kind in ('confirmed_obligation', 'user_application', 'user_goal', 'coursework', 'optional', 'unknown'):
            self.publish(kind, kind)
        snapshot = self.store.snapshot()
        self.assertEqual({'confirmed_obligation', 'user_application', 'user_goal', 'coursework'},
                         {t['eligibility']['kind'] for t in snapshot['tasks']})
        self.assertEqual({'optional', 'unknown'}, {t['eligibility']['kind'] for t in snapshot['review_tasks']})
        self.assertTrue(all(t['priority'] == 3 for t in snapshot['review_tasks']))

    def test_expired_optional_and_closed_application_do_not_carry_but_overdue_obligations_do(self):
        self.publish('optional', 'optional', policy='closes', deadline='2026-10-27')
        self.publish('application', 'user_application', policy='closes', deadline='2026-10-28T09:00:00')
        self.publish('obligation', 'confirmed_obligation', deadline='2026-10-23')
        self.publish('coursework', 'coursework', deadline='2026-10-23')
        self.current += timedelta(days=1)
        self.assertTrue(self.store.begin('outage'))
        self.store.fail('outage')
        snapshot = self.store.snapshot()
        self.assertEqual({'obligation', 'coursework'}, {t['task_key'] for t in snapshot['tasks']})
        self.assertEqual({'expired'}, {t['review_reason'] for t in snapshot['review_tasks']})
        self.assertTrue(all(t['stale'] for t in snapshot['tasks']))
        self.assertEqual([], snapshot['resolved_tasks'])
        self.assertEqual([], self.store.resolution_history())

    def test_unknown_submission_state_is_review_not_an_unsubmitted_task(self):
        self.publish('unknown-status', 'user_application', status='unknown')
        snapshot = self.store.snapshot()
        self.assertEqual([], snapshot['tasks'])
        self.assertEqual('status_unknown', snapshot['review_tasks'][0]['review_reason'])
        self.assertEqual('unknown', snapshot['review_tasks'][0]['eligibility']['action_status'])

    def test_inferred_interest_is_not_confirmed_personal_work(self):
        row, inputs = self.publish('confirmed', 'user_application')
        row['eligibility']['user_intent'] = 'inferred'
        self.store.begin('inferred')
        self.store.finish('inferred', inputs, dict(tasks=[row]))
        self.assertEqual([], self.store.snapshot()['tasks'])
        self.assertEqual('interest_unconfirmed', self.store.snapshot()['review_tasks'][0]['review_reason'])

    def test_interest_api_persists_dismissal_across_refresh_and_is_separate_from_completion(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from unittest.mock import patch
        from today_core.api import create_router

        self.publish('chosen', 'optional', policy='closes', deadline='2026-10-30')
        identity = self.store.snapshot()['review_tasks'][0]['id']
        app = FastAPI()
        app.include_router(create_router(resolver=lambda profile: (self.temp.name, 'default')))
        with patch('today_core.api.PlanStore', return_value=self.store), \
                patch('today_core.api.admitted_for_sensitive_route', return_value=True):
            client = TestClient(app)
            response = client.put('/tasks/' + identity + '/interest', json={'interested': False})
            self.assertEqual(200, response.status_code)
            self.assertEqual([], response.json()['tasks'])
            self.assertEqual([], response.json()['review_tasks'])
            self.assertEqual('dismissed', response.json()['dismissed_tasks'][0]['actionability'])
            # Even a newer model asserting mandatory work cannot erase a user's decision.
            self.publish('chosen', 'confirmed_obligation', request='regenerate')
            reopened = PlanStore(self.temp.name, lambda: self.current)
            self.assertIn(identity, {t['id'] for t in reopened.snapshot()['dismissed_tasks']})
            done = client.patch('/tasks/' + identity, json={'done': True})
            self.assertEqual(200, done.status_code)
            self.assertTrue(next(t for t in done.json()['dismissed_tasks'] if t['id'] == identity)['done'])
            readback = client.get('/plan')
            self.assertTrue(next(t for t in readback.json()['dismissed_tasks'] if t['id'] == identity)['done'])
            persisted = PlanStore(self.temp.name, lambda: self.current).snapshot()
            self.assertTrue(next(t for t in persisted['dismissed_tasks'] if t['id'] == identity)['done'])
            selected = client.put('/tasks/' + identity + '/interest', json={'interested': True})
            self.assertEqual(200, selected.status_code)
            chosen = next(t for t in selected.json()['tasks'] if t['id'] == identity)
            self.assertTrue(chosen['user_interest'])
            self.assertTrue(chosen['done'])
            self.assertEqual('confirmed_obligation', chosen['eligibility']['kind'])
            self.assertEqual(422, client.put('/tasks/' + identity + '/interest', json={'interested': 'false'}).status_code)
            self.assertEqual(404, client.put('/tasks/missing/interest', json={'interested': False}).status_code)
            self.assertEqual(422, client.put('/tasks/' + identity + '/interest', json={'interested': False, 'done': True}).status_code)
            self.assertEqual(403, client.put('/tasks/' + identity + '/interest', json={'interested': False},
                                            headers={'origin': 'https://hostile.example'}).status_code)
            with patch('today_core.api.admitted_for_sensitive_route', return_value=False):
                self.assertEqual(401, client.put('/tasks/' + identity + '/interest', json={'interested': False}).status_code)
            self.assertTrue(self.store.snapshot()['tasks'][0]['user_interest'])
        self.assertEqual([], self.store.resolution_history())

    def test_stale_commitment_evidence_cannot_make_a_new_deadline_fully_fresh(self):
        row, inputs = self.publish('original', 'user_application')
        source = dict(inputs[0]['records'][0], id='gmail:old-intent', retrieved_in_current_run=False,
                      checked_at='2026-10-23T10:00:00+09:00')
        inputs[0]['records'].append(source)
        row['eligibility']['source_id'] = source['id']
        self.store.begin('mixed-evidence')
        self.store.finish('mixed-evidence', inputs, {'tasks': [row]})
        self.assertTrue(self.store.snapshot()['tasks'][0]['stale'])
        self.assertFalse(self.store.snapshot()['fresh'])
        # A run spanning JST midnight must check the citation's date too.
        source['retrieved_in_current_run'] = True
        self.store.begin('previous-day-citation')
        self.store.finish('previous-day-citation', inputs, {'tasks': [row]})
        self.assertTrue(self.store.snapshot()['tasks'][0]['stale'])

    def test_schema_rejects_unsupported_classifications_and_fabricated_citations_atomically(self):
        import copy
        row, inputs = self.publish('valid', 'coursework')
        before = self.store.snapshot()['tasks']
        invalid = [('kind', 'mandatory_because_deadline'), ('kind', []), ('user_intent', True),
                   ('action_status', 'unsubmitted_by_assumption'), ('deadline_policy', 'forever'),
                   ('source_id', 'gmail:not-read'), ('quote', 'not present in actual evidence'), ('quote', '')]
        for index, (field, value) in enumerate(invalid):
            with self.subTest(field=field, value=value):
                changed = copy.deepcopy(row)
                changed['eligibility'][field] = value
                request = 'invalid-' + str(index)
                self.store.begin(request)
                with self.assertRaises(PlanError):
                    self.store.finish(request, inputs, {'tasks': [changed]})
                self.store.fail(request)
                self.assertEqual(before, self.store.snapshot()['tasks'])
        # A known citation from a different source is not proof for this record.
        changed = copy.deepcopy(row)
        other = dict(inputs[0]['records'][0], id='manaba:other', source='manaba')
        inputs[0]['records'].append(other)
        changed['eligibility']['source_id'] = other['id']
        self.store.begin('cross-source')
        with self.assertRaises(PlanError):
            self.store.finish('cross-source', inputs, {'tasks': [changed]})

    def test_interest_is_scoped_to_source_account_and_model_cannot_write_it(self):
        row, inputs = self.publish('stable', 'optional')
        identity = self.store.snapshot()['review_tasks'][0]['id']
        self.store.interest(identity, False)
        row['user_interest'] = True
        self.store.begin('model-cannot-choose')
        with self.assertRaises(PlanError):
            self.store.finish('model-cannot-choose', inputs, {'tasks': [row]})
        self.store.fail('model-cannot-choose')
        settings = self.store.settings()
        self.store.configure(dict(settings, email='different@example.org'))
        with self.store.db() as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM task_preferences').fetchone()[0])
        self.publish('stable', 'optional', request='different-account')
        self.assertIsNone(self.store.snapshot()['review_tasks'][0]['user_interest'])

    def test_citation_limit_counts_raw_whitespace_too(self):
        row, inputs = self.publish('short-proof', 'coursework')
        quote = ' ' * 2000 + 'x'
        inputs[0]['records'][0]['text'] = quote
        row['eligibility']['quote'] = quote
        self.store.begin('oversize-proof')
        with self.assertRaises(PlanError):
            self.store.finish('oversize-proof', inputs, {'tasks': [row]})

    def test_prompt_requires_evidence_not_recipient_registration_or_a_list_membership(self):
        from today_core.runtime import build_prompt
        prompt = build_prompt(self.store)
        for token in ('"eligibility"', '"user_intent"', '"action_status"', '"deadline_policy"',
                      'review_tasks', 'dismissed_tasks', '受信', '会員登録', '代表者', '未提出'):
            self.assertIn(token, prompt)

    def test_explicit_selection_of_legacy_future_or_undated_work_is_a_verification_action(self):
        for index, deadline in enumerate((None, '2026-10-29')):
            with self.subTest(deadline=deadline):
                old = self.legacy('legacy-selected-' + str(index))
                old['deadline'] = deadline
                with self.store.db() as db:
                    db.execute('UPDATE tasks SET payload=? WHERE id=?', (json.dumps(old), old['id']))
                self.store.interest(old['id'], True)
                snapshot = self.store.snapshot()
                selected = next(t for t in snapshot['tasks'] if t['id'] == old['id'])
                self.assertTrue(selected['verification_required'])
                self.assertTrue(selected['user_interest'])
                self.assertNotIn('eligibility', selected)
                self.assertEqual(3, selected['priority'])
                self.assertEqual('確認する：' + old['title'], selected['title'])
                self.assertIn('状態は未確認', selected['reason'])
                with self.store.db() as db:
                    persisted = json.loads(db.execute('SELECT payload FROM tasks WHERE id=?', (old['id'],)).fetchone()[0])
                self.assertEqual(old, persisted)
        expired = self.legacy('expired-selected')
        self.store.interest(expired['id'], True)
        self.assertNotIn(expired['id'], {t['id'] for t in self.store.snapshot()['tasks']})
        self.assertEqual('expired', next(t for t in self.store.snapshot()['review_tasks'] if t['id'] == expired['id'])['review_reason'])

    def test_selection_of_unknown_status_is_only_verification_and_cannot_reopen_closed_work(self):
        self.publish('status-to-verify', 'unknown', status='unknown', policy='closes', deadline='2026-10-29')
        identity = self.store.snapshot()['review_tasks'][0]['id']
        self.store.interest(identity, True)
        selected = self.store.snapshot()['tasks'][0]
        self.assertTrue(selected['verification_required'])
        self.assertEqual('unknown', selected['eligibility']['action_status'])
        self.publish('status-to-verify', 'unknown', status='closed', request='closed')
        self.assertEqual([], self.store.snapshot()['tasks'])
        self.assertEqual('closed', self.store.snapshot()['review_tasks'][0]['review_reason'])

    def test_interest_promotes_an_open_optional_task_but_does_not_reopen_a_closed_opportunity(self):
        self.publish('optional-selection', 'optional', policy='closes', deadline='2026-10-29')
        identity = self.store.snapshot()['review_tasks'][0]['id']
        self.store.interest(identity, True)
        selected = self.store.snapshot()['tasks'][0]
        self.assertEqual('optional', selected['eligibility']['kind'])
        self.assertTrue(selected['user_interest'])
        self.current += timedelta(days=2)
        self.assertEqual([], self.store.snapshot()['tasks'])
        self.assertEqual('expired', self.store.snapshot()['review_tasks'][0]['review_reason'])

    def test_legacy_sources_move_to_review_without_deleting_or_resolving(self):
        mail = self.legacy('old-mail', done=True)
        coursework = self.legacy('old-coursework', 'manaba')
        with self.store.db() as db:
            before = [tuple(r) for r in db.execute('SELECT * FROM tasks ORDER BY id')]
        for _ in range(2):
            snapshot = self.store.snapshot()
            self.assertEqual([], snapshot['tasks'])
            self.assertEqual({coursework['id'], mail['id']}, {t['id'] for t in snapshot['review_tasks']})
            self.assertTrue(next(t for t in snapshot['review_tasks'] if t['id'] == mail['id'])['done'])
            self.assertEqual('unclassified', snapshot['review_tasks'][0]['review_reason'])
            self.assertEqual([], snapshot['resolved_tasks'])
            self.current += timedelta(days=1)
        with self.store.db() as db:
            self.assertEqual(before, [tuple(r) for r in db.execute('SELECT * FROM tasks ORDER BY id')])
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM resolution_events').fetchone()[0])
