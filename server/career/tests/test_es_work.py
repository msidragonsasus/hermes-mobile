"""Synthetic question editing; no network, mailbox, model or production data."""
import unittest
import tempfile

from career_core.store import Store, CareerError
from career_core.collection_store import CollectionStore, PILOT
from career_core.collector import Collector
from career_core.es_work import mutate_answer, answer_summary
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools
from test_collection import PortalReader, MailReader
from coach_fixture import configure_execution


class EsWorkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        collection = CollectionStore(self.store)
        grant = collection.configure_pilot("gmail-" + "a" * 32, enabled=True)
        collector = Collector(self.store, secrets=object(), mail_factory=MailReader, portal_factory=PortalReader)
        collector.collect(PILOT, "portal")
        self.offer_id = collector.offer_id(grant)
        with self.store.connect() as db:
            self.question = self.store.entity(db, "offers", self.offer_id)["data"]["questions"][0]
        self.request = {"operation_id": "manual-answer-001", "offer_id": self.offer_id,
            "question_id": self.question["id"], "question_version": self.question["version"],
            "expected_work_version": 0, "expected_version": 0, "body": "私の下書き"}

    def test_save_retry_restart_and_review_are_distinct_from_submission(self):
        first = mutate_answer(self.store, self.request)
        self.assertEqual(first, mutate_answer(self.store, self.request))
        self.assertEqual("drafting", first["document"]["data"]["status"])
        self.assertIsNone(first["work"]["reviewed"])
        restarted = Store(self.tmp.name)
        with restarted.connect() as db:
            self.assertEqual(first["work"], answer_summary(restarted, db, self.offer_id)[self.question["id"]])
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM document_files").fetchone()[0])
        reviewed = mutate_answer(restarted, dict(self.request, operation_id="review-answer-001", intent="review",
            expected_work_version=1, document_id=first["document"]["id"], document_version=1))
        self.assertEqual(reviewed["work"]["adopted"], reviewed["work"]["reviewed"])
        self.assertEqual("drafting", reviewed["document"]["data"]["status"])

    def test_save_conflict_keeps_versions_and_original_body(self):
        first = mutate_answer(self.store, self.request)
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, dict(self.request, operation_id="stale-save-001", body="消してはいけない"))
        self.assertEqual("answer_version_conflict", error.exception.code)
        with self.store.connect() as db:
            self.assertEqual("私の下書き", db.execute("SELECT body FROM document_files").fetchone()[0])
        second = mutate_answer(self.store, dict(self.request, operation_id="manual-answer-002", body="修正後",
            expected_work_version=1, document_id=first["document"]["id"], expected_version=first["document"]["version"]))
        self.assertEqual(2, second["work"]["adopted"]["version"])

    def test_invalid_limit_or_changed_question_does_not_save(self):
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, dict(self.request, body="あ" * 401))
        self.assertEqual("document_too_long", error.exception.code)
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, dict(self.request, question_version="old"))
        self.assertEqual("question_version_conflict", error.exception.code)
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM document_files").fetchone()[0])

    def test_consultation_freezes_unsaved_body_and_ai_does_not_adopt(self):
        configure_execution(self)
        first = mutate_answer(self.store, self.request)
        coach = CoachStore(self.store)
        context = {k: self.request[k] for k in ("offer_id", "question_id", "question_version")}
        context["body"] = "まだ保存していない本文"
        receipt = coach.submit({"operation_id": "consult-answer-001", "text": "短くして", "thread_id": "offer:" + self.offer_id,
            "target": {"kind": "documents", "id": first["document"]["id"], "version": 1}, "answer_context": context})
        self.assertEqual(context["body"], coach.run(receipt["id"])["request"]["answer_context"]["body"])
        run = coach.claim()
        candidate = CoachTools(coach).save({"kind": "documents", "id": first["document"]["id"], "expected_version": first["document"]["version"],
            "data": {"title": "ES", "body": "候補の本文"}}, run)
        self.assertEqual(2, candidate["data"]["version"])
        with self.store.connect() as db:
            self.assertEqual(1, answer_summary(self.store, db, self.offer_id)[self.question["id"]]["adopted"]["version"])

    def test_other_offer_context_is_rejected(self):
        configure_execution(self)
        with self.assertRaises(CareerError) as error:
            CoachStore(self.store).submit({"operation_id": "wrong-offer-001", "text": "相談", "thread_id": "offer:" + self.offer_id,
                "answer_context": {"offer_id": "other", "question_id": self.question["id"], "question_version": self.question["version"], "body": "本文"}})
        self.assertEqual("target_scope_conflict", error.exception.code)

    def test_v41_manual_versions_keep_history_without_inventing_fact_support(self):
        from career_core.vendor.v41.state_v4 import initial_state
        from career_core.shared import current_state, validate_shared
        with self.store.transaction() as db:
            self.store.set_meta(db, "github_base", initial_state())
        first = mutate_answer(self.store, self.request)
        second = mutate_answer(self.store, dict(self.request, operation_id="v41-second-save", body="直した下書き",
            expected_work_version=1, document_id=first["document"]["id"], expected_version=first["document"]["version"]))
        self.assertEqual(2, second["document"]["data"]["version"])
        self.assertEqual(2, len(second["document"]["data"]["version_evidence"]))
        self.assertEqual([], second["document"]["data"]["verified_fact_ids"])
        self.assertTrue(second["document"]["data"]["needs_review"])
        with self.store.connect() as db:
            validate_shared(current_state(self.store, db))
            self.assertEqual(2, db.execute("SELECT COUNT(*) FROM document_files").fetchone()[0])

    def test_adopting_another_question_is_rejected_without_rebinding(self):
        first = mutate_answer(self.store, self.request)
        with self.store.transaction() as db:
            offer = self.store.entity(db, "offers", self.offer_id)
            offer["data"]["questions"].append(dict(self.question, id="second-question"))
            self.store.put(db, "offers", self.offer_id, offer["version"], offer["data"], dirty=False)
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, dict(self.request, operation_id="wrong-question-adopt", intent="adopt",
                question_id="second-question", document_id=first["document"]["id"], document_version=1))
        self.assertEqual("target_scope_conflict", error.exception.code)

    def test_line_break_constraint_and_auth_failure_preserve_draft(self):
        with self.store.transaction() as db:
            offer = self.store.entity(db, "offers", self.offer_id)
            offer["data"]["questions"][0]["no_line_breaks"] = True
            self.store.put(db, "offers", self.offer_id, offer["version"], offer["data"], dirty=False)
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, dict(self.request, body="一行\n二行"))
        self.assertEqual("line_break_forbidden", error.exception.code)
        CollectionStore(self.store).update_status(PILOT, "portal", "auth_required")
        with self.assertRaises(CareerError) as error:
            mutate_answer(self.store, self.request)
        self.assertEqual("questions_not_verified", error.exception.code)

    def test_http_save_company_read_and_reopen_use_existing_document_contract(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from unittest.mock import patch
        from career_core.api import create_router
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            if request.headers.get("x-test-session"):
                request.state.session = {"user": "test"}
            return await call_next(request)

        app.include_router(create_router(lambda: self.store))
        client = TestClient(app)
        self.assertEqual(401, client.post("/answer", json=self.request).status_code)
        headers = {"x-test-session": "synthetic"}
        with patch("career_core.api.sync_job") as sync:
            first = client.post("/answer", json=self.request, headers=headers)
            self.assertEqual(200, first.status_code)
            self.assertEqual(first.json(), client.post("/answer", json=self.request, headers=headers).json())
            self.assertTrue(sync.called)
        saved = first.json()["document"]
        with self.store.connect() as db:
            company_id = self.store.entity(db, "offers", self.offer_id)["data"]["company_id"]
        self.store = Store(self.tmp.name)
        detail = client.get("/companies/" + company_id, headers=headers).json()
        self.assertTrue(detail["es_workspace"])
        self.assertEqual(saved["id"], detail["answer_states"][self.offer_id][self.question["id"]]["adopted"]["id"])
        document = client.get("/document/" + saved["id"] + "/1", headers=headers).json()
        self.assertEqual(self.request["body"], document["body"])
        self.assertTrue(document["server_saved"])
        self.assertFalse(document["github_synced"])


if __name__ == "__main__":
    unittest.main()
