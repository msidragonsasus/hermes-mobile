import tempfile
import unittest

from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools
from career_core.client_mail import ClientMailImporter
from career_core.company_views import CompanyViews
from career_core.mail_collector import MailCollector
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store
from coach_fixture import configure_execution


class FakeReader:
    messages = []

    def __init__(self, account_id, **unused):
        self.account_id = account_id

    @property
    def calls(self):
        return 0

    def profile(self):
        return {"historyId": "10"}

    def history_ids(self, history_id, page_token=None):
        return {"historyId": "11", "history": [{"messagesAdded": [{"message": {"id": m["id"]}} for m in self.messages]}]}

    def search_ids(self, query, page_token=None):
        return {"messages": [{"id": m["id"]} for m in self.messages]}

    def read_ids(self, ids, senders, cached, tuning):
        return [m for m in self.messages if m["id"] in ids and m["sender_address"] in senders], set(ids), []

    def changes(self, cursor=None, *, sender_addresses=None, max_messages=100):
        allowed = set(sender_addresses or [])
        return [message for message in self.messages if message["sender_address"] in allowed], {"history_id": "11"}

    def close(self):
        pass


def message(identity, sender, subject="選考のご案内"):
    return {"id": identity, "thread_id": "thread-" + identity, "sender_address": sender,
            "from": "採用担当 <" + sender + ">", "subject": subject, "snippet": "締切をご確認ください",
            "text": "応募締切は9月30日です。\nワンタイムコード: 123456\n本文の続き",
            "locator": "https://mail.google.com/mail/#all/" + identity, "gmail_unread": True,
            "received_at": "2026-09-14T01:00:00+00:00"}


class MailCollectionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        with self.store.transaction() as db:
            self.store.put(db, "companies", "company-a", 0, {"name": "A社"}, dirty=False)
            self.store.put(db, "companies", "company-b", 0, {"name": "B社"}, dirty=False)
        self.mail = MailStore(self.store)
        self.mail.configure("gmail-" + "a" * 32, True)
        with self.store.transaction() as db:
            self.store.set_meta(db, "mail:tuning:gmail-" + "a" * 32, {"verified": True, "batch_size": 20, "concurrency": 1})
        self.mail.configure_sender("company-a", "recruit-a@example.test", True)
        self.mail.configure_sender("company-b", "recruit-b@example.test", True)

    def test_program_classifies_by_exact_sender_and_preserves_readable_body(self):
        FakeReader.messages = [message("a1", "recruit-a@example.test"), message("b1", "recruit-b@example.test")]
        result = MailCollector(self.store, reader_factory=FakeReader).collect()
        self.assertEqual(2, result["changed"])
        self.assertEqual("a1", CompanyViews(self.store).detail("company-a")["mail"][0]["message_id"])
        body = CompanyViews(self.store).detail("company-a")["mail"][0]["body"]
        self.assertIn("本文の続き", body)
        self.assertNotIn("123456", body)
        self.assertEqual(1, CompanyViews(self.store).overview()["companies"][0]["new_count"])

    def test_repeat_is_deduplicated_and_disabled_rule_keeps_saved_mail(self):
        FakeReader.messages = [message("a1", "recruit-a@example.test")]
        collector = MailCollector(self.store, reader_factory=FakeReader)
        self.assertEqual(1, collector.collect()["changed"])
        self.assertEqual(0, collector.collect()["changed"])
        self.mail.configure_sender("company-a", "recruit-a@example.test", False)
        self.assertEqual(1, len(self.mail.messages("company-a")))

    def test_shared_sender_is_shown_in_each_company_and_deduplicated(self):
        self.mail.configure_sender("company-b", "recruit-a@example.test", True)
        FakeReader.messages = [message("a1", "recruit-a@example.test")]
        collector = MailCollector(self.store, reader_factory=FakeReader)
        self.assertEqual(2, collector.collect()["changed"])
        self.assertEqual(0, collector.collect()["changed"])
        for company_id in ("company-a", "company-b"):
            detail = CompanyViews(self.store).detail(company_id)
            self.assertEqual(["a1"], [item["message_id"] for item in detail["mail"]])
            self.assertEqual(1, len(collector.collection.evidence(company_id)))
        self.assertEqual([1, 1], [item["new_count"] for item in CompanyViews(self.store).overview()["companies"]])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM career_mail_messages").fetchone()[0])

    def test_existing_mail_and_evidence_remain_when_adding_shared_sender(self):
        FakeReader.messages = [message("a1", "recruit-a@example.test")]
        collector = MailCollector(self.store, reader_factory=FakeReader)
        collector.collect()
        original = self.mail.messages("company-a")[0]
        evidence_id = collector.collection.evidence("company-a")[0]["id"]
        # Simulate the old schema without deleting any existing message rows.
        with self.store.transaction() as db:
            db.execute("DROP TABLE career_mail_company_messages")
        self.mail = MailStore(self.store)
        self.assertEqual(original, self.mail.messages("company-a")[0])
        self.mail.configure_sender("company-b", "recruit-a@example.test", True)
        self.assertEqual(1, collector.collect()["changed"])
        self.assertEqual(evidence_id, collector.collection.evidence("company-a")[0]["id"])
        self.assertEqual(original["first_observed_at"], self.mail.messages("company-b")[0]["first_observed_at"])
        self.mail.configure_sender("company-a", "recruit-a@example.test", False)
        FakeReader.messages = [message("a2", "recruit-a@example.test")]
        collector.collect()
        self.assertEqual(["a1"], [row["message_id"] for row in self.mail.messages("company-a")])
        self.assertEqual({"a1", "a2"}, {row["message_id"] for row in self.mail.messages("company-b")})

    def test_agent_context_reads_saved_mail_but_cannot_trigger_mail_collection(self):
        configure_execution(self)
        FakeReader.messages = [message("a1", "recruit-a@example.test")]
        MailCollector(self.store, reader_factory=FakeReader).collect()
        coach = CoachStore(self.store)
        submitted = coach.submit({"text": "メールを確認", "operation_id": "mail-context",
                                  "thread_id": "company:company-a"})
        run = coach.claim()
        tools = CoachTools(coach)
        context = tools.context({}, run)
        self.assertEqual("a1", context["company"]["mail"][0]["message_id"])
        with self.assertRaises(CareerError) as error:
            tools.dispatch("career_collect", {"channel": "mail"},
                           {"task_id": submitted["id"], "session_id": "career-run:" + submitted["id"]})
        self.assertEqual("unknown_tool", error.exception.code)

    def test_career_context_pages_more_than_twenty_messages_with_one_snapshot(self):
        configure_execution(self)
        with self.store.transaction() as db:
            for index in range(45):
                self.mail.save_message(db, "gmail-a", "company-a", message(format(index, "x"), "recruit-a@example.test"))
        coach = CoachStore(self.store)
        coach.submit({"text": "保存済みメールを確認", "operation_id": "mail-pages", "thread_id": "company:company-a"})
        run = coach.claim()
        tools = CoachTools(coach)
        first = tools.context({}, run)
        self.assertEqual(20, len(first["company"]["mail"]))
        self.assertNotIn("companies", first["mail_snapshot"])
        with self.store.transaction() as db:
            self.mail.save_message(db, "gmail-a", "company-a", message("ffff", "recruit-a@example.test"))
        second = tools.context({"mail_company_id": "company-a", "mail_cursor": first["company"]["mail_next_cursor"]}, run)
        third = tools.context({"mail_company_id": "company-a", "mail_cursor": second["mail_page"]["next_cursor"]}, run)
        found = first["company"]["mail"] + second["mail_page"]["messages"] + third["mail_page"]["messages"]
        self.assertEqual(45, len({m["message_id"] for m in found}))
        self.assertNotIn("ffff", {m["message_id"] for m in found})
        with self.assertRaises(CareerError):
            tools.context({"mail_company_id": "company-b"}, run)

    def test_android_program_imports_without_google_token_and_sanitizes_body(self):
        payload = {
            "account_email": "jobhunt@example.test",
            "messages": [{
                "id": "a1", "thread_id": "b1", "sender_address": "recruit-a@example.test",
                "from_text": "採用担当 <recruit-a@example.test>", "subject": "面接のご案内",
                "snippet": "日程をご確認ください", "body": "面接は9月30日です。\n認証コード: 123456\n詳細 https://example.test/t/secret",
                "locator": "https://mail.google.com/mail/#all/a1", "gmail_unread": True,
                "received_at": "2026-09-14T01:00:00Z",
            }],
        }
        before = self.mail.status()
        result = ClientMailImporter(self.store).ingest(payload)
        self.assertEqual(before, self.mail.status())
        self.assertFalse(self.mail.status()["fresh"])
        self.assertEqual(1, result["changed"])
        saved = CompanyViews(self.store).detail("company-a")["mail"][0]
        self.assertIn("面接は9月30日です。", saved["body"])
        self.assertNotIn("123456", saved["body"])
        self.assertNotIn("secret", saved["body"])
        self.assertEqual(0, ClientMailImporter(self.store).ingest(payload)["changed"])
        self.mail.configure_sender("company-b", "recruit-a@example.test", True)
        self.assertEqual(1, ClientMailImporter(self.store).ingest(payload)["changed"])
        self.assertEqual(0, ClientMailImporter(self.store).ingest(payload)["changed"])
        for company_id in ("company-a", "company-b"):
            self.assertEqual(["a1"], [row["message_id"] for row in self.mail.messages(company_id)])
            self.assertEqual(1, len(MailCollector(self.store).collection.evidence(company_id)))

    def test_android_program_rejects_unregistered_sender(self):
        payload = {
            "account_email": "jobhunt@example.test",
            "messages": [{
                "id": "a1", "thread_id": "b1", "sender_address": "unknown@example.test",
                "from_text": "unknown@example.test", "subject": "件名", "snippet": "", "body": "本文",
                "locator": "https://mail.google.com/mail/#all/a1", "gmail_unread": False,
                "received_at": "2026-09-14T01:00:00Z",
            }],
        }
        with self.assertRaises(CareerError) as error:
            ClientMailImporter(self.store).ingest(payload)
        self.assertEqual("mail_source_unapproved", error.exception.code)


    def test_company_cache_reuses_shared_mail_without_cross_account_or_sender_leak(self):
        FakeReader.messages = [message("a1", "recruit-a@example.test")]
        MailCollector(self.store, reader_factory=FakeReader).collect()
        from career_core.gmail_reader import account_reference
        # Use a real account reference for the Android import/cache path.
        with self.store.transaction() as db:
            self.mail.save_message(db, account_reference("jobs@example.test"), "company-a", FakeReader.messages[0])
        importer = ClientMailImporter(self.store)
        request = {"account_email": "jobs@example.test", "company_id": "company-b", "message_ids": ["a1"]}
        self.assertEqual([], importer.reuse(request)["cached_ids"])
        self.mail.configure_sender("company-b", "recruit-a@example.test", True)
        # Fixture thread IDs must follow Gmail's validated hexadecimal format.
        with self.store.transaction() as db:
            db.execute("UPDATE career_mail_messages SET thread_id='b1' WHERE message_id='a1'")
        self.assertEqual(["a1"], importer.reuse(request)["cached_ids"])
        self.assertEqual(["a1"], [row["message_id"] for row in self.mail.messages("company-b")])
        self.assertEqual([], importer.reuse(dict(request, account_email="other@example.test"))["cached_ids"])
        self.assertEqual([], importer.reuse(dict(request, message_ids=["b2"]))["cached_ids"])

    def test_saved_mail_pagination_keeps_same_time_and_account_identities(self):
        with self.store.transaction() as db:
            for index in range(205):
                self.mail.save_message(db, "gmail-a", "company-a", message(format(index, "x"), "recruit-a@example.test"))
            self.mail.save_message(db, "gmail-b", "company-a", message("0", "recruit-a@example.test"))
        identities = []
        cursor = ""
        counts = []
        while True:
            page = self.mail.message_page("company-a", cursor)
            counts.append(len(page["messages"]))
            identities.extend((row["account_id"], row["message_id"]) for row in page["messages"])
            cursor = page["next_cursor"]
            if cursor is None:
                break
        self.assertEqual([100, 100, 6], counts)
        self.assertEqual(206, len(set(identities)))
        self.assertEqual([], self.mail.message_page("company-b")["messages"])
        self.assertIsNotNone(CompanyViews(self.store).detail("company-a")["mail_next_cursor"])
        with self.assertRaises(ValueError):
            self.mail.message_page("company-a", "not-a-cursor")

    def test_scoped_import_rejects_another_company_sender(self):
        value = message("a1", "recruit-a@example.test")
        value["thread_id"] = "b1"
        value["from_text"] = value.pop("from")
        value["body"] = value.pop("text")
        with self.assertRaises(CareerError) as error:
            ClientMailImporter(self.store).ingest({"account_email": "jobs@example.test", "company_id": "company-b", "messages": [value]})
        self.assertEqual("mail_source_unapproved", error.exception.code)
        self.assertEqual([], self.mail.messages("company-a"))


if __name__ == "__main__":
    unittest.main()
