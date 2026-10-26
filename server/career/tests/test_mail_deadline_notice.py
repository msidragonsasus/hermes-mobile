"""Synthetic negated-extension/event-date regressions. No private mail or model calls."""
import json
import tempfile
import unittest
from datetime import datetime, timezone

from career_core.action_classification import ActionSource, validate_result
from career_core.home import HomeView
from career_core.mail_deadline_notice import correct_deadline_notice
from career_core.mail_store import MailStore
from career_core.notice_policy import classify
from career_core.store import Store, CareerError
from career_core.collection_store import digest


TITLE = "合成信託 秋2Days 一次募集 ESを提出してください"
DUE = "2026-10-05T15:00+09:00"
BODY = ("2026年度 秋2Days 一次募集\nエントリーシート提出をお願いします。\n"
        "応募締切：2026-10-05 15:00 日本時間。\n")
REFUSAL = "アクセス混雑を理由とした応募期限の延長はいたしかねます。"


def proof(source, value, quote=None):
    quote = value if quote is None else quote
    start = source.text.index(quote)
    return {"value": value, "evidence": {"start": start, "end": start + len(quote), "quote": quote}}


def result(source):
    return dict(status="matched", source_id=source.id, source_version=source.version, confidence=0.99,
                program=proof(source, "秋2Days"), cohort=proof(source, "2026年度"),
                round=proof(source, "一次募集"), action=proof(source, "submit_es", "エントリーシート提出"),
                deadline=proof(source, DUE, "応募締切：2026-10-05 15:00 日本時間"))


class MailDeadlineNoticeTests(unittest.TestCase):
    def test_refusal_is_not_extension_and_remains_es_action(self):
        self.assertEqual(("action", "期限変更の案内"), classify(TITLE, BODY + REFUSAL))
        self.assertEqual(("action", "提出・回答の依頼"),
                         correct_deadline_notice(TITLE, BODY + REFUSAL, classify(TITLE, BODY + REFUSAL)))

    def test_negation_variants_are_not_actual_changes(self):
        for ending in ("はいたしかねます", "はしません", "しない", "はできません", "なし", "は行いません"):
            body = BODY + "応募期限の延長" + ending + "。"
            self.assertNotEqual(("action", "期限変更の案内"), correct_deadline_notice(TITLE, body, classify(TITLE, body)))

    def test_actual_extension_or_shortening_stays_change_notice(self):
        for sentence in ("応募期限を延長しました。", "提出締切を変更いたしました。", "回答期限を短縮しました。"):
            body = BODY + sentence
            self.assertEqual(("action", "期限変更の案内"), correct_deadline_notice(TITLE, body, classify(TITLE, body)))

    def test_refusal_does_not_hide_separate_affirmative_change(self):
        body = BODY + REFUSAL + "\n書類の提出期限を延長しました。"
        self.assertEqual(("action", "期限変更の案内"), correct_deadline_notice(TITLE, body, classify(TITLE, body)))

    def test_changed_event_date_and_autumn_winter_participation_do_not_change_application_deadline(self):
        body = BODY + REFUSAL + "\n開催日程を2026-10-20から2026-10-21に変更しました。\n秋から冬の参加区分は変更できます。"
        self.assertEqual(("action", "提出・回答の依頼"),
                         correct_deadline_notice(TITLE, body, classify(TITLE, body)))
        source = ActionSource("synthetic-final", "v1", "synthetic-company", TITLE + "\n" + body, DUE)
        self.assertEqual(DUE, validate_result(source, result(source))["deadline"])
        malformed = result(source)
        malformed["deadline"] = proof(source, "2026-10-21", "2026-10-21")
        with self.assertRaises(CareerError):
            validate_result(source, malformed)

    def test_september_announcement_and_october_reminder_share_same_unchanged_action_key(self):
        first = ActionSource("synthetic-sep4", "v1", "synthetic-company", TITLE + "\n" + BODY, DUE)
        final = ActionSource("synthetic-oct5", "v2", "synthetic-company",
                             "最終案内 " + TITLE + "\n" + BODY + REFUSAL, DUE)
        self.assertEqual(validate_result(first, result(first))["key"], validate_result(final, result(final))["key"])
        self.assertNotEqual(first.id, final.id)

    def test_cross_sentence_event_change_is_not_deadline_change(self):
        body = "応募期限。開催日変更のご案内。\n" + BODY
        self.assertEqual(("action", "提出・回答の依頼"), correct_deadline_notice(TITLE, body, classify(TITLE, body)))

    def test_home_keeps_source_body_due_and_preexisting_completion_version(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            MailStore(store)
            body = BODY + REFUSAL + "\n開催日程を2026-10-21に変更しました。"
            with store.transaction() as db:
                store.put(db, "companies", "c", 0, {"name": "合成信託", "user_priority": "high"})
                store.set_meta(db, "mail:settings", {"enabled": True, "account_id": "fixture"})
                db.execute("INSERT INTO career_mail_messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           ("fixture", "m", "t", "c", "fixture@example.org", "合成信託", TITLE, "", body,
                            "https://example.org/synthetic", "2026-10-05", 1, "2026-10-05", "2026-10-05"))
            view = HomeView(store, lambda: datetime(2026, 10, 5, tzinfo=timezone.utc))
            item = view.read()["items"][0]
            self.assertEqual(DUE, item["deadline"])
            self.assertNotIn("期限変更", item["detail"])
            self.assertEqual(body, view.content(item["id"], item["version"])["body"])
            old_detail = "合成信託 · 期限変更の案内"
            old_version = digest("action", TITLE, item["target"], DUE, old_detail, digest(body))
            self.assertEqual(old_version, item["version"])
            with store.transaction() as db:
                store.set_meta(db, "home-resolved:" + digest("default", item["id"]), old_version)
            self.assertEqual([], view.read()["items"])


if __name__ == "__main__":
    unittest.main()
