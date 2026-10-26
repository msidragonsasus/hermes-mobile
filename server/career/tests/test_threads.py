import tempfile
import unittest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router
from career_core.coach_store import CoachStore
from career_core.company_views import CompanyViews
from career_core.store import CareerError, Store
from career_core.threads import create_thread, list_threads, update_thread
from coach_fixture import configure_execution


class ThreadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.coach = CoachStore(self.store)
        configure_execution(self)
        with self.store.transaction() as db:
            self.store.put(db, 'companies', 'one', 0, {'name': 'Synthetic One'})
            self.store.put(db, 'companies', 'two', 0, {'name': 'Synthetic Two'})
            self.store.put(db, 'offers', 'offer', 0, {'company_id': 'one', 'title': 'Synthetic Offer'})

    def create(self, key='create-0001', company='one'):
        return create_thread(self.coach, dict(operation_id=key, title='面接練習', company_id=company), 'default')

    def send(self, thread, key='message-001'):
        return self.coach.submit(dict(operation_id=key, thread_id=thread, text='synthetic question'))

    def test_multiple_company_and_unscoped_threads_keep_history_separate(self):
        first, second, global_thread = self.create(), self.create('create-0002'), self.create('create-0003', None)
        self.assertEqual(3, len({first['id'], second['id'], global_thread['id']}))
        self.send(first['id'])
        self.send(second['id'], 'message-002')
        self.assertEqual(1, len(self.coach.snapshot(thread_id=first['id'])['messages']))
        self.assertEqual([], self.coach.snapshot(thread_id=global_thread['id'])['messages'])
        self.assertEqual([], self.coach.history('default', second['id']))

    def test_runtime_resumes_each_thread_with_only_its_own_history(self):
        from career_core.coach_runtime import HermesCoachRuntime
        observed = []
        class Agent:
            def run_conversation(self, text, *, task_id, conversation_history):
                observed.append(list(conversation_history))
                return dict(final_response='synthetic reply', messages=conversation_history + [
                    {'role': 'user', 'content': text}, {'role': 'assistant', 'content': 'synthetic reply'}])
            def close(self):
                pass
        runtime = HermesCoachRuntime(self.coach, factory=lambda *args, **kwargs: Agent())
        first, second = self.create(), self.create('create-0002')
        for index, identity in enumerate([first['id'], first['id'], second['id']]):
            run = self.send(identity, f'runtime-message-{index}')
            reply = runtime.run(self.coach.run(run['id']))
            self.coach.finish(run['id'], 'completed', reply=reply)
        self.assertEqual([0, 2, 0], list(map(len, observed)))
        self.assertEqual(4, len(self.coach.history('default', first['id'])))
        self.assertEqual(2, len(self.coach.history('default', second['id'])))

    def test_retry_does_not_duplicate_and_changed_request_conflicts(self):
        self.assertEqual(self.create(), self.create())
        with self.assertRaises(CareerError):
            create_thread(self.coach, dict(operation_id='create-0001', title='changed'), 'default')

    def test_profile_and_target_boundaries(self):
        first = self.create()
        with self.assertRaises(CareerError):
            self.coach.snapshot('another', thread_id=first['id'])
        with self.assertRaises(CareerError):
            self.coach.submit(dict(operation_id='message-001', thread_id=first['id'], text='test', target={'kind': 'companies', 'id': 'two'}))
        with self.assertRaises(CareerError):
            create_thread(self.coach, dict(operation_id='create-0002', company_id='two', offer_id='offer'), 'default')

    def test_rename_version_archive_restore_and_readonly_history(self):
        thread = self.create()
        changed = update_thread(self.coach, thread['id'], dict(operation_id='rename-0001', expected_version=1, title='企業研究'), 'default')
        self.assertEqual(2, changed['version'])
        with self.assertRaises(CareerError):
            update_thread(self.coach, thread['id'], dict(operation_id='rename-0002', expected_version=1, title='競合'), 'default')
        update_thread(self.coach, thread['id'], dict(operation_id='archive-001', expected_version=2, archived=True), 'default')
        self.assertEqual([], self.coach.snapshot(thread_id=thread['id'])['messages'])
        with self.assertRaises(CareerError):
            self.send(thread['id'])
        update_thread(self.coach, thread['id'], dict(operation_id='restore-001', expected_version=3, archived=False), 'default')
        self.send(thread['id'])

    def test_active_run_blocks_archive_and_wrong_thread_control(self):
        first, second = self.create(), self.create('create-0002')
        run = self.send(first['id'])
        with self.assertRaises(CareerError):
            update_thread(self.coach, first['id'], dict(operation_id='archive-001', expected_version=1, archived=True), 'default')
        with self.assertRaises(CareerError):
            self.coach.control(dict(operation_id='control-001', id=run['id'], thread_id=second['id'], control='stop'), 'default')
        self.coach.control(dict(operation_id='control-002', id=run['id'], thread_id=first['id'], control='stop'), 'default')
        update_thread(self.coach, first['id'], dict(operation_id='archive-002', expected_version=1, archived=True), 'default')

    def test_legacy_history_and_missing_company_survive_metadata_migration(self):
        self.send('company:one')
        self.coach.save_history('default', [{'role': 'user', 'content': 'retained'}], 'company:one')
        before = self.coach.snapshot(thread_id='company:one')['messages']
        with self.store.transaction() as db:
            db.execute("DELETE FROM entities WHERE kind='companies' AND id='one'")
        rows = list_threads(self.coach, 'default')['threads']
        self.assertTrue(next(t for t in rows if t['id'] == 'company:one')['context_missing'])
        self.assertEqual(before, self.coach.snapshot(thread_id='company:one')['messages'])
        self.assertEqual(1, len(self.coach.history('default', 'company:one')))
        self.assertEqual(len(rows), len(list_threads(CoachStore(self.store), 'default')['threads']))

    def test_company_filters_count_multiple_offers_and_unknown_without_rewriting(self):
        with self.store.transaction() as db:
            for key, status in [('a', 'in_process'), ('b', 'offered'), ('c', 'imported')]:
                self.store.put(db, 'applications', key, 0, dict(company_id='one', status=status))
        row = next(r for r in CompanyViews(self.store).overview()['companies'] if r['id'] == 'one')
        self.assertEqual({'in_process': 1, 'offered': 1, 'review': 1}, row['application_counts'])

    def test_archiving_legacy_chat_does_not_disable_scheduled_research(self):
        list_threads(self.coach, 'default')
        update_thread(self.coach, 'global', dict(operation_id='archive-global', expected_version=1, archived=True), 'default')
        self.assertTrue(self.coach.snapshot()['archived'])
        with self.assertRaises(CareerError):
            self.send('global')
        run = self.coach.submit(dict(operation_id='daily-synthetic', text='synthetic daily'), kind='daily')
        self.assertEqual('queued', run['state'])

    def test_malformed_thread_ids_are_rejected_without_database_errors(self):
        for value in (None, [], {}, 123, 'x' * 241):
            with self.assertRaises(CareerError):
                self.coach.snapshot(thread_id=value)

    def test_http_routes_and_capability(self):
        app = FastAPI()
        app.include_router(create_router(lambda: self.store, run_worker=False), prefix='/api/plugins/career')
        app.state.auth_required = True
        @app.middleware('http')
        async def session(request, call_next):
            request.state.session = 'synthetic'
            return await call_next(request)
        client = TestClient(app)
        from career_core.collection_store import CollectionStore, bind_document
        CollectionStore(self.store)
        with self.store.transaction() as db:
            self.store.put(db, 'documents', 'DOC-001', 0, dict(title='Synthetic ES', version=1))
            bind_document(db, 'DOC-001', 1, dict(company_id='one', offer_id='offer'), [dict(id='q1', version='v1')])
        document = next(e for e in client.get('/api/plugins/career/snapshot').json()['entities'] if e['kind'] == 'documents')
        self.assertEqual('offer', document['data']['career_context']['offer_id'])
        self.assertEqual('q1', document['data']['career_context']['question_refs'][0]['id'])
        raw = next(e for e in self.store.snapshot()['entities'] if e['kind'] == 'documents')
        self.assertNotIn('career_context', raw['data'])
        created = client.post('/api/plugins/career/coach/threads', json={'operation_id': 'http-create-1', 'title': 'テーマ'})
        self.assertEqual(200, created.status_code, created.text)
        identity = created.json()['id']
        self.assertEqual(200, client.get('/api/plugins/career/coach/threads').status_code)
        updated = client.patch('/api/plugins/career/coach/threads/' + identity, json={'operation_id': 'http-rename-1', 'expected_version': 1, 'title': '更新'})
        self.assertEqual(200, updated.status_code, updated.text)
        self.assertIn('conversation_threads', client.get('/api/plugins/career/coach/capabilities').json()['features'])


if __name__ == '__main__':
    unittest.main()
