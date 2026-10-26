"""Synthetic recruiting actions only; no network, credentials, mailbox or paid inference."""
import copy
import json
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from career_core.action_classification import (ActionSource, HermesActionClassifier, HermesLunaClassifier, cache_key, cached_identity,
                                               classify_saved_mail_actions, validate_result)
from career_core.home import HomeView
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store


def fixture_source(identity="s", round_name="一次募集", due="2026-09-30T23:59+09:00",
                   program="冬季3days", action="submit_es", prefix=""):
    date = due[:10]
    clause = "エントリーシート提出" if action == "submit_es" else "参加後アンケート回答"
    text = prefix + program + "\n2026年度\n" + round_name + "\n" + clause + "\n締切：" + date + " 23:59 日本時間\nhttps://example.org/mypage/com/21"
    return ActionSource(identity, "v1", "company", text, due)


def matched(source, action="submit_es"):
    def field(value, quote=None):
        quote = value if quote is None else quote
        start = source.text.index(quote)
        return {"value": value, "evidence": {"start": start, "end": start + len(quote), "quote": quote}}
    program = "1Day" if "1Day" in source.text else "冬季3days"
    round_name = next(r for r in ("一次募集", "二次募集", "参加後") if r in source.text)
    return dict(status="matched", source_id=source.id, source_version=source.version, confidence=0.99,
                program=field(program), cohort=field("2026年度"), round=field(round_name),
                action=field(action, "エントリーシート提出" if action == "submit_es" else "参加後アンケート回答"),
                deadline=field(source.deadline, "締切：" + source.deadline[:10] + " 23:59 日本時間"))


class ClassificationTests(unittest.TestCase):
    def test_five_reminder_subjects_one_identity_all_evidence_binds_original_source(self):
        identities = [validate_result(s, matched(s)) for s in
                      [fixture_source("reminder-" + str(n), prefix="リマインド" + str(n) + "\n") for n in range(5)]]
        self.assertEqual(1, len({i["key"] for i in identities}))
        self.assertEqual(5, len({i["source_id"] for i in identities}))
        for result in identities:
            self.assertEqual(5, len(result["evidence"]))
            self.assertTrue(all(e["source_id"] == result["source_id"] and e["source_version"] == "v1" for e in result["evidence"]))

    def test_same_form_second_round_and_one_day_survey_remain_distinct(self):
        first = fixture_source()
        second = fixture_source("second", "二次募集", "2026-10-22T23:59+09:00")
        survey = fixture_source("survey", "参加後", "2026-08-14T23:59+09:00", "1Day", "answer_survey")
        keys = [validate_result(s, matched(s, a))["key"] for s, a in
                ((first, "submit_es"), (second, "submit_es"), (survey, "answer_survey"))]
        self.assertEqual(3, len(set(keys)))

    def test_company_deadline_cannot_replace_survey_deadline(self):
        source = fixture_source("survey", "参加後", "2026-08-14T23:59+09:00", "1Day", "answer_survey")
        result = matched(source, "answer_survey")
        result["deadline"]["value"] = "2026-09-30"
        with self.assertRaises(CareerError):
            validate_result(source, result)

    def test_multiple_dates_unknown_year_abstention_and_low_confidence_do_not_group(self):
        source = fixture_source()
        result = matched(source)
        for modified in (ActionSource(source.id, source.version, source.company_id,
                                      source.text + "\n締切：2026-10-22 23:59 日本時間", source.deadline),
                         ActionSource(source.id, source.version, source.company_id, source.text.replace("2026-09-30", "9月30日"), source.deadline)):
            with self.assertRaises(CareerError):
                validate_result(modified, result)
        result["confidence"] = 0.9
        with self.assertRaises(CareerError):
            validate_result(source, result)
        self.assertIsNone(validate_result(source, {"status": "abstain", "source_id": source.id, "source_version": source.version}))

    def test_fabricated_quotes_offsets_cross_source_stale_version_extra_fields_fail_closed(self):
        source = fixture_source()
        variants = []
        for field, value in (("source_id", "other"), ("source_version", "old"), ("confidence", float("nan")), ("extra", "ignored")):
            output = matched(source)
            output[field] = value
            variants.append(output)
        for field, value in (("quote", "invented"), ("start", True), ("end", 999999)):
            output = matched(source)
            output["program"]["evidence"][field] = value
            variants.append(output)
        output = matched(source)
        output["round"]["value"] = "二次募集"
        variants.append(output)
        for output in variants:
            with self.assertRaises(CareerError):
                validate_result(source, output)

    def test_catalog_validation_and_existing_runtime_only_no_fallback(self):
        calls = []
        execution = {"provider": "connected-fixture", "model": "fixture-luna", "reasoning_effort": "default"}
        class Policy:
            def validate(self, requested):
                calls.append(("validate", requested))
                if requested["model"] not in {"fixture-luna", "fixture-budget"}:
                    raise CareerError("model_unsupported", "Unknown configured model", 409)
                return requested
        class Agent:
            def run_conversation(self, payload):
                calls.append(("input", json.loads(payload)))
                return {"completed": True, "final_response": json.dumps(matched(fixture_source()))}
            def close(self):
                calls.append(("close",))
        def factory(session, prompt, **kwargs):
            calls.append(("factory", kwargs))
            return Agent()
        classifier = HermesLunaClassifier(execution, Policy(), factory)
        self.assertEqual("matched", classifier.classify(fixture_source())["status"])
        self.assertEqual(("validate", execution), calls[0])
        self.assertFalse(calls[1][1]["tools_enabled"])
        self.assertEqual(execution, calls[1][1]["execution"])
        self.assertEqual(("close",), calls[-1])
        # A different small configured model is accepted; an invented model still fails before dispatch.
        budget = HermesActionClassifier(dict(execution, model="fixture-budget"), Policy(), factory)
        self.assertEqual("fixture-budget", budget.execution["model"])
        with self.assertRaises(CareerError):
            HermesActionClassifier(dict(execution, model="other-model"), Policy(), factory)

    def test_runtime_failure_closes_agent_and_does_not_echo_private_error(self):
        class Policy:
            def validate(self, execution): return execution
        class Agent:
            closed = False
            def run_conversation(self, payload): return {"failed": True, "error": "private body/token"}
            def close(self): self.closed = True
        agent = Agent()
        classifier = HermesLunaClassifier({"provider": "fixture", "model": "fixture-luna"}, Policy(), lambda *a, **k: agent)
        with self.assertRaises(CareerError) as error:
            classifier.classify(fixture_source())
        self.assertNotIn("private", str(error.exception))
        self.assertTrue(agent.closed)


class ProjectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(self.temp.name)
        MailStore(self.store)
        with self.store.transaction() as db:
            self.store.put(db, "companies", "company", 0, {"name": "合成企業", "user_priority": "high",
                                                        "deadline": "2026-09-30"})
            self.store.set_meta(db, "mail:settings", {"enabled": True, "account_id": "fixture-account"})
        self.view = HomeView(self.store, lambda: datetime(2026, 9, 29, tzinfo=timezone.utc))
        # Deadline fixtures exercise grouping while still actionable; expiry is tested separately.
        home_view = patch("career_core.home.HomeView", return_value=self.view)
        home_view.start()
        self.addCleanup(home_view.stop)

    def save(self, source, identity):
        title, body = source.text.split("\n", 1)
        # Ensure the broad action detector sees the action in the subject too.
        title += " エントリーシート提出をお願いします" if "エントリーシート提出" in body else "アンケート回答をお願いします"
        with self.store.transaction() as db:
            db.execute("INSERT OR REPLACE INTO career_mail_messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       ("fixture-account", identity, "fixture-thread", "company", "fixture@example.org", "合成企業",
                        title, "", body, "https://example.org/mail/" + identity,
                        "2026-09-30T00:00:00+00:00", 1, "2026-09-30", "2026-09-30"))

    def test_cache_enriches_originals_without_deletion_and_each_source_resolves_separately(self):
        for n in range(5):
            self.save(fixture_source(prefix="再案内" + str(n) + "\n"), str(n))
        before = self.view.read()["items"]
        class FixtureClassifier:
            def classify(self, source): return matched(source)
        receipt = classify_saved_mail_actions(self.store, "default", FixtureClassifier())
        after = self.view.read()["items"]
        self.assertEqual(5, receipt["matched"])
        self.assertEqual(0, receipt["source_messages_modified"])
        self.assertEqual([(i["id"], i["version"], i["deadline"]) for i in before],
                         [(i["id"], i["version"], i["deadline"]) for i in after])
        self.assertEqual(1, len({i["action_identity"]["key"] for i in after}))
        with self.store.connect() as db:
            self.assertEqual(5, db.execute("SELECT count(*) FROM career_mail_messages").fetchone()[0])
            before_read = list(db.iterdump())
        self.view.read()
        with self.store.connect() as db:
            self.assertEqual(before_read, list(db.iterdump()))
        for item in after:
            self.assertTrue(self.view.content(item["id"], item["version"])["body"])
        self.view.resolve_source(after[0]["id"], after[0]["version"])
        self.assertEqual(4, len(self.view.read()["items"]))
        # Model projection is profile-scoped, and completion of one source is not inferred for others.
        self.assertTrue(all("action_identity" not in i for i in self.view.read("other")["items"]))

    def test_survey_uses_only_its_own_deadline_and_classification_does_not_invent_dates(self):
        self.view.clock = lambda: datetime(2026, 8, 14, tzinfo=timezone.utc)
        self.save(fixture_source("survey", "参加後", "2026-08-14T23:59+09:00", "1Day", "answer_survey"), "survey")
        self.assertEqual("2026-08-14T23:59+09:00", self.view.read()["items"][0]["deadline"])
        self.assertNotEqual("2026-09-30", self.view.read()["items"][0]["deadline"])

    def test_mail_explicit_time_is_preserved_without_inventing_timezone(self):
        source = fixture_source()
        source = ActionSource(source.id, source.version, source.company_id,
                              source.text.replace(" 日本時間", ""), "2026-09-30T23:59")
        self.save(source, "without-zone")
        self.assertEqual("2026-09-30T23:59", self.view.read()["items"][0]["deadline"])

    def test_explicit_time_upgrade_preserves_preexisting_source_completion_version(self):
        from career_core.collection_store import digest
        source = fixture_source()
        source = ActionSource(source.id, source.version, source.company_id,
                              source.text.replace(" 日本時間", ""), "2026-09-30T23:59")
        self.save(source, "resolved")
        item = self.view.read()["items"][0]
        content = self.view.content(item["id"], item["version"])
        legacy_version = digest("action", content["title"], item["target"], "2026-09-30", item["detail"],
                                digest(content["body"]))
        self.assertEqual(legacy_version, item["version"])
        with self.store.transaction() as db:
            self.store.set_meta(db, "home-resolved:" + digest("default", item["id"]), legacy_version)
        self.assertEqual([], self.view.read()["items"])

    def test_multiple_action_deadlines_stay_unknown_instead_of_using_company_deadline(self):
        source = fixture_source()
        source = ActionSource(source.id, source.version, source.company_id,
                              source.text + "\n別の作業の締切：2026-08-14 23:59 日本時間", source.deadline)
        self.save(source, "ambiguous")
        item = self.view.read()["items"][0]
        self.assertIsNone(item["deadline"])
        class NoInference:
            def classify(self, source):
                raise AssertionError("Ambiguous source must not be sent to a model")
        self.assertEqual(0, classify_saved_mail_actions(self.store, "default", NoInference())["classified"])

    def test_classification_change_invalidates_cursor_but_not_original_content_versions(self):
        for n in range(101):
            self.save(fixture_source(prefix="再案内" + str(n) + "\n"), str(n))
        page = self.view.read()
        original = page["items"][0]
        class FixtureClassifier:
            def classify(self, source): return matched(source)
        classify_saved_mail_actions(self.store, "default", FixtureClassifier(), limit=1)
        with self.assertRaises(CareerError) as error:
            self.view.read(cursor=page["next_cursor"])
        self.assertEqual("home_changed", error.exception.code)
        self.assertTrue(self.view.content(original["id"], original["version"])["body"])

    def test_content_change_invalidates_old_classification_and_preserves_new_mail(self):
        self.save(fixture_source(), "m")
        class FixtureClassifier:
            def classify(self, source): return matched(source)
        classify_saved_mail_actions(self.store, "default", FixtureClassifier())
        item = self.view.read()["items"][0]
        self.assertIn("action_identity", item)
        self.save(fixture_source(round_name="二次募集", due="2026-10-01T23:59+09:00"), "m")
        updated = self.view.read()["items"][0]
        self.assertNotEqual(item["version"], updated["version"])
        self.assertNotIn("action_identity", updated)

    def test_invalid_cached_output_is_ignored_and_explicit_reclassification_can_repair_projection(self):
        self.save(fixture_source(), "m")
        class FixtureClassifier:
            def classify(self, source): return matched(source)
        classify_saved_mail_actions(self.store, "default", FixtureClassifier())
        item = self.view.read()["items"][0]
        content = self.view.content(item["id"], item["version"])
        source = ActionSource(item["id"], item["version"], "company", content["title"] + "\n" + content["body"], item["deadline"])
        with self.store.transaction() as db:
            receipt = self.store.meta(db, cache_key("default", source))
            receipt["result"]["program"]["evidence"]["quote"] = "invented"
            self.store.set_meta(db, cache_key("default", source), receipt)
        self.assertNotIn("action_identity", self.view.read()["items"][0])
        self.assertEqual(1, classify_saved_mail_actions(self.store, "default", FixtureClassifier())["matched"])
        self.assertIn("action_identity", self.view.read()["items"][0])

    def test_mid_classification_content_change_rolls_back_all_cache_writes(self):
        self.save(fixture_source(), "m")
        outer = self
        class FixtureClassifier:
            def classify(self, source):
                outer.save(fixture_source(round_name="二次募集", due="2026-10-01T23:59+09:00"), "m")
                return matched(source)
        with self.assertRaises(CareerError):
            classify_saved_mail_actions(self.store, "default", FixtureClassifier())
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT count(*) FROM metadata WHERE key LIKE 'home-action-classification:%'").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT count(*) FROM career_mail_messages").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
