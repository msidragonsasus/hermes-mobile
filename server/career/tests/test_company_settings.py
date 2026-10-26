import tempfile
import unittest

from career_core.company_views import CompanyViews
from career_core.store import Store, CareerError


class CompanySettingsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.store.save(dict(operation_id="create-company-a", kind="companies", id="c1", expected_version=0,
                             data={"name": "企業", "notes": "保持するメモ", "official_urls": ["https://example.com/recruit"]}))

    def request(self, **changes):
        return dict(dict(operation_id="save-link-operation", kind="company_links", id="l1", expected_version=0,
                         data={"company_id": "c1", "type": "MyPage", "label": "2028卒", "url": "https://example.com/login"}), **changes)

    def test_link_save_read_update_and_idempotent_retry(self):
        request = self.request()
        saved = self.store.save(request)
        self.assertEqual(saved, self.store.save(request))
        updated = self.store.save(self.request(operation_id="update-link-operation", expected_version=1,
                                               data=dict(request["data"], url="https://example.com/new-login")))
        view = CompanyViews(Store(self.tmp.name)).detail("c1")
        self.assertEqual([updated], view["links"])
        self.assertEqual(["https://example.com/recruit"], view["company"]["data"]["official_urls"])
        self.assertEqual("保持するメモ", view["company"]["data"]["notes"])

    def test_stale_version_cannot_overwrite_link(self):
        self.store.save(self.request())
        with self.assertRaises(CareerError) as failure:
            self.store.save(self.request(operation_id="stale-link-operation"))
        self.assertEqual("version_conflict", failure.exception.code)
        self.assertEqual(1, CompanyViews(self.store).detail("c1")["links"][0]["version"])

    def test_invalid_urls_and_foreign_company_are_rejected(self):
        for url in ("", "http://example.com", "javascript:alert(1)", "https://user:password@example.com", "https://exa mple.com"):
            with self.subTest(url=url), self.assertRaises(CareerError):
                self.store.save(self.request(data=dict(self.request()["data"], url=url)))
        with self.assertRaises(CareerError):
            self.store.save(self.request(data=dict(self.request()["data"], company_id="missing")))

    def test_existing_links_are_scoped_without_rewriting_import_metadata(self):
        with self.store.transaction() as db:
            self.store.put(db, "companies", "c2", 0, {"name": "別企業"})
            self.store.put(db, "company_links", "legacy", 0,
                           dict(self.request()["data"], source={"system": "Shukatsu", "id": "keep"}))
            self.store.put(db, "company_links", "other", 0, dict(self.request()["data"], company_id="c2"))
        links = CompanyViews(self.store).detail("c1")["links"]
        self.assertEqual(["legacy"], [row["id"] for row in links])
        self.assertEqual("keep", links[0]["data"]["source"]["id"])
