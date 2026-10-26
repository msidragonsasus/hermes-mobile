"""Ordering contract on temporary stores only; no production data or network."""
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from fastapi import FastAPI
from fastapi.testclient import TestClient
from career_core.api import create_router
from career_core.company_order import order_key, save_order
from career_core.company_views import CompanyViews
from career_core.store import Store, CareerError, SHARED


class CompanyOrderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        with self.store.transaction() as db:
            for key, name in [('a', 'Alpha'), ('b', 'Beta'), ('c', 'Gamma')]:
                self.store.put(db, 'companies', key, 0, {'name': name})

    def save(self, ids, version=0, operation='dummy-order-operation', profile='phone'):
        return save_order(self.store, {'operation_id': operation, 'expected_version': version, 'company_ids': ids}, profile)

    def ids(self, store=None, profile='phone'):
        result = CompanyViews(store or self.store).overview(profile)
        return [r['id'] for r in result['companies']], result['order_version']

    def test_default_order_and_saved_order_survive_new_store_instance(self):
        self.assertEqual((['a', 'b', 'c'], 0), self.ids())
        self.save(['c', 'a', 'b'])
        self.assertEqual((['c', 'a', 'b'], 1), self.ids(Store(self.tmp.name)))

    def test_profile_orders_are_independent_and_not_shared(self):
        self.save(['c', 'b', 'a'])
        self.assertEqual((['a', 'b', 'c'], 0), self.ids(profile='desktop'))
        self.assertNotIn('companies', SHARED)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute('SELECT SUM(dirty) FROM entities').fetchone()[0])

    def test_same_operation_retry_returns_original_ack_without_overwriting_new_order(self):
        first = self.save(['c', 'b', 'a'])
        self.save(['b', 'a', 'c'], 1, 'second-dummy-order')
        self.assertEqual(first, self.save(['c', 'b', 'a']))
        self.assertEqual((['b', 'a', 'c'], 2), self.ids())
        with self.assertRaises(CareerError) as e:
            self.save(['a', 'b', 'c'])
        self.assertEqual(409, e.exception.status)

    def test_conflict_does_not_modify_metadata(self):
        self.save(['c', 'b', 'a'])
        with self.assertRaises(CareerError) as e:
            self.save(['a', 'b', 'c'], 0, 'stale-dummy-order')
        self.assertEqual(409, e.exception.status)
        self.assertEqual((['c', 'b', 'a'], 1), self.ids())

    def test_add_delete_append_new_and_ignore_deleted_while_detecting_stale_edit(self):
        self.save(['c', 'b', 'a'])
        with self.store.transaction() as db:
            self.store.put(db, 'companies', 'd', 0, {'name': 'Delta'})
            self.store.put(db, 'companies', 'b', 1, {'name': 'Beta', 'deleted_at': 'dummy-date'})
        self.assertEqual((['c', 'a', 'd'], 1), self.ids())
        with self.assertRaises(CareerError):
            self.save(['c', 'b', 'a'], 1, 'changed-set-dummy')
        self.save(['d', 'a', 'c'], 1, 'current-set-dummy')
        self.assertEqual((['d', 'a', 'c'], 2), self.ids())

    def test_invalid_partial_duplicate_unknown_bool_and_extra_keys_rejected(self):
        for ids in (['a', 'b'], ['a', 'a', 'c'], ['a', 'b', 'unknown'], ['a', 'b', None]):
            with self.assertRaises(CareerError):
                self.save(ids)
        with self.assertRaises(CareerError):
            self.save(['a', 'b', 'c'], True)
        with self.assertRaises(CareerError):
            save_order(self.store, {'operation_id': 'dummy-order', 'expected_version': 0, 'company_ids': ['a', 'b', 'c'], 'unexpected': 1}, 'phone')
        self.assertEqual((['a', 'b', 'c'], 0), self.ids())

    def test_corrupt_metadata_preserved_and_readonly_default_used(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, order_key('phone'), {'version': 'bad', 'company_ids': ['a', 'a']})
        self.assertEqual((['a', 'b', 'c'], None), self.ids())
        with self.assertRaises(CareerError):
            self.save(['c', 'b', 'a'])
        with self.store.connect() as db:
            self.assertEqual('bad', self.store.meta(db, order_key('phone'))['version'])

    def test_concurrent_compare_and_swap_only_one_succeeds(self):
        def run(n):
            try:
                return self.save(['c', 'b', 'a'] if n == 0 else ['b', 'c', 'a'], operation=f'dummy-concurrent-{n}')
            except CareerError as e:
                return e.status
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(run, [0, 1]))
        self.assertEqual(1, sum(isinstance(r, dict) for r in results))
        self.assertIn(409, results)

    def test_http_route_uses_existing_gate_and_profile_and_handles_large_order(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware('http')
        async def fixture_gate(request, next):
            if request.headers.get('x-dummy-session') == 'yes':
                request.state.session = {'id': 'dummy-session'}
            return await next(request)
        app.include_router(create_router(lambda: self.store), prefix='/career')
        client = TestClient(app)
        payload = {'operation_id': 'dummy-route-order', 'expected_version': 0, 'company_ids': ['c', 'b', 'a']}
        self.assertEqual(401, client.post('/career/collection/company-order?profile=phone', json=payload).status_code)
        self.assertEqual(200, client.post('/career/collection/company-order?profile=phone', json=payload, headers={'x-dummy-session': 'yes'}).status_code)

        self.assertEqual((['c', 'b', 'a'], 1), self.ids())
        # Body cap belongs to the new action, not unrelated client-mail-cache.
        with self.store.transaction() as db:
            for i in range(200):
                self.store.put(db, 'companies', 'dummy-' + str(i).zfill(3) + '-' + 'x'*100, 0, {'name': f'Dummy {i}'})
        ids = self.ids()[0]
        payload.update(operation_id='dummy-large-order', expected_version=1, company_ids=list(reversed(ids)))
        self.assertEqual(200, client.post('/career/collection/company-order?profile=phone', json=payload, headers={'x-dummy-session': 'yes'}).status_code)

    def test_active_set_uses_snapshot_lifecycle_predicate_exactly(self):
        with self.store.transaction() as db:
            self.store.put(db, 'companies', 'c', 1, {'name': 'Gamma', 'deleted_at': 'dummy-date'})
            # Existing lifecycle also hides entities referring to a removed company.
            self.store.put(db, 'companies', 'b', 1, {'name': 'Beta', 'company_id': 'c'})
        self.assertEqual((['a'], 0), self.ids())
        self.save(['a'])
        self.assertEqual((['a'], 1), self.ids())

    def test_corrupt_order_and_version_cap_are_definite_422_failures(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, order_key('phone'), {'version': 'bad', 'company_ids': []})
        self.assertEqual('unavailable', CompanyViews(self.store).overview('phone')['order_state'])
        with self.assertRaises(CareerError) as e:
            self.save(['a', 'b', 'c'])
        self.assertEqual(422, e.exception.status)
        with self.store.transaction() as db:
            self.store.set_meta(db, order_key('phone'), {'version': 2147483647, 'company_ids': []})
        with self.assertRaises(CareerError) as e:
            self.save(['a', 'b', 'c'], version=2147483647)
        self.assertEqual(422, e.exception.status)


if __name__ == '__main__':
    unittest.main()
