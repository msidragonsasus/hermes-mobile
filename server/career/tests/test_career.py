import os
import base64
import copy
import json
import tempfile
import unittest
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.context_bundle import constraints, select_context
from career_core.importing import import_bundle
from career_core.research import research, validate_offer
from career_core.shared import correct_fact, current_state, save_document
from career_core.store import CareerError, Store
from career_core.sync import GitHub, merge_fields, synchronize
from career_core.vendor.v40.state_v4 import initial_state


def state():
    return dict(initial_state("2026-09-07 09:00 JST"), **{"schema_version": "4.0", "revision": 1, "candidate": {"university": "test"},
            "facts": [{"id": "FACT-001", "statement": "original", "status": "CONFIRMED"}],
            "stories": [], "hypotheses": [], "documents": [], "tasks": [{"id": "TASK-001"}],
            "last_session": {"next_topic": "preserve"}, "decisions": [{"id": "DEC-001"}]})


class Remote:
    def __init__(self):
        self.state = state()
        self.sha = "one"
        self.fail = False
        self.files = {}

    def read(self):
        if self.fail:
            raise CareerError("github_auth", "expired", 503)
        return copy.deepcopy(self.state), self.sha

    def write(self, value, sha):
        if sha != self.sha:
            raise CareerError("github_changed", "conflict", 409)
        self.state, self.sha = copy.deepcopy(value), self.sha + "x"
        return self.sha

    def write_document(self, path, body):
        if path in self.files and self.files[path] != body:
            raise CareerError("immutable_document_conflict", "different body", 409)
        self.files[path] = body

    def document(self, path):
        return self.files.get(path)


class ContextRemote(Remote):
    def __init__(self):
        super().__init__()
        self.state["hypotheses"] = [{"id": "HYP-001", "statement": "withdrawn", "status": "unconfirmed"}]
        self.state["stories"] = [{"id": "ST-006", "fact_ids": ["FACT-001"], "status": "partial"}]
        self.state["documents"] = [{"id": "DOC-LEGACY-SAMPLE", "status": "drafting",
                                     "path": ".jobhunt/documents/2026-01-01-synthetic-summary.md"}]
        self.files[".jobhunt/documents/2026-01-01-synthetic-summary.md"] = "synthetic summary"
        self.context_bundle = {
            "format": "jobhunt-self-analysis-context/1",
            "snapshot_state_revision": 1,
            "imported_on": "2026-09-13",
            "source_boundaries": {"private_only": True, "raw_transcript_stored": False,
                                  "sensitive_details_stored": False},
            "generation_guards": [
                {"id": "GUARD-001", "scope": ["personality"], "topic": "withdrawn hypothesis",
                 "disposition": "withdrawn_assistant_inference", "reason": "user correction",
                 "record_ids": ["HYP-001"]},
                {"id": "GUARD-002", "scope": ["external_draft"], "topic": "restricted story",
                 "disposition": "hold_for_separate_review", "reason": "separate review required",
                 "story_ids": ["ST-006"]},
            ],
            "corrections": [],
            "answered_topics": ["known answer"],
            "open_questions": [{"id": "OQ-001", "question": "next question", "status": "unanswered"}],
            "assessment_policy": {"withdrawn": ["HYP-001"]},
        }
        self.context_status = {"state": "verified", "commit": "a" * 40,
                               "path": ".jobhunt/context/self-analysis.json", "state_revision": 1}


class CareerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def save(self, kind, entity_id, data, version=0, operation="operation-1"):
        return self.store.save({"kind": kind, "id": entity_id, "data": data,
                                "expected_version": version, "operation_id": operation})

    def test_retry_and_cas(self):
        first = self.save("companies", "one", {"title": "test"})
        self.assertEqual(first, self.save("companies", "one", {"title": "test"}))
        with self.assertRaises(CareerError):
            self.save("companies", "one", {"title": "different"})
        with self.assertRaises(CareerError):
            self.save("companies", "one", {"title": "stale"}, operation="operation-2")
        self.assertEqual(1, self.store.snapshot()["entities"][0]["version"])

    def test_submitted_is_immutable(self):
        request = {"kind": "documents", "id": "DOC-001", "data": {"status": "submitted", "body": "saved"}, "expected_version": 0, "operation_id": "document-1"}
        save_document(self.store, request)
        with self.assertRaises(CareerError):
            self.save("documents", "DOC-001", {"status": "drafting", "body": "changed"}, 1, "operation-2")

    def test_decision_creates_tasks_once(self):
        self.save("companies", "one", {"title": "test"})
        with self.store.transaction() as db:
            self.store.put(db, "offers", "offer", 0, {"company_id": "one", "title": "course", "requirements": [{"title": "ES"}]})
        request = {"id": "offer", "decision": "apply", "expected_version": 1, "operation_id": "decision-1"}
        self.store.decide(request)
        self.store.decide(request)
        snapshot = self.store.snapshot()
        self.assertEqual(1, sum(e["kind"] == "applications" for e in snapshot["entities"]))
        self.assertEqual(1, sum(e["kind"] == "tasks" for e in snapshot["entities"]))

    def bundle(self):
        return {"operation_id": "import-op-1", "format_version": 1, "file_sha256": "a" * 64,
                "bundle_id": "sample", "records": [{"type": "Company", "source_id": "one", "source_version": "v1",
                                                     "data": {"name": "test", "status": "unknown"}}]}

    def test_import_repeat_and_source_change_preserved(self):
        request = self.bundle()
        self.assertEqual(1, import_bundle(self.store, request)["imported"])
        request["operation_id"] = "import-op-2"
        self.assertEqual(1, import_bundle(self.store, request)["unchanged"])
        request["operation_id"] = "import-op-3"
        request["records"][0]["source_version"] = "v2"
        self.assertEqual(1, len(import_bundle(self.store, request)["conflicts"]))
        self.assertFalse(any(e["kind"] == "applications" for e in self.store.snapshot()["entities"]))

    def test_reject_secret_import(self):
        request = self.bundle()
        request["records"][0]["data"]["password"] = "excluded"
        with self.assertRaises(CareerError):
            import_bundle(self.store, request)
        self.assertEqual([], self.store.snapshot()["entities"])

    def test_sync_preserves_unrelated_and_40(self):
        remote = Remote()
        synchronize(self.store, remote)
        self.save("profile", "candidate", dict(remote.state["candidate"], faculty="edited"), 1)
        remote.state["candidate"]["other"] = "remote update"
        self.assertEqual("synced", synchronize(self.store, remote)["state"])
        self.assertEqual("4.0", remote.state["schema_version"])
        self.assertEqual(state()["tasks"], remote.state["tasks"])
        self.assertEqual(state()["last_session"], remote.state["last_session"])
        self.assertEqual("edited", remote.state["candidate"]["faculty"])
        self.assertEqual("remote update", remote.state["candidate"]["other"])

    def test_sync_conflict_and_auth_failure_keep_dirty(self):
        remote = Remote()
        synchronize(self.store, remote)
        self.save("profile", "candidate", dict(remote.state["candidate"], university="local"), 1)
        remote.state["candidate"]["university"] = "remote"
        self.assertEqual("conflict", synchronize(self.store, remote)["state"])
        self.assertEqual("remote", remote.state["candidate"]["university"])
        remote.fail = True
        self.assertEqual("unsynced", synchronize(self.store, remote)["state"])
        self.assertTrue(next(e for e in self.store.snapshot()["entities"] if e["kind"] == "profile")["dirty"])

    def test_sync_unchanged_does_not_write(self):
        remote = Remote()
        synchronize(self.store, remote)
        self.assertEqual("one", remote.sha)
        synchronize(self.store, remote)
        self.assertEqual("one", remote.sha)

    def test_context_sidecar_and_legacy_document_are_imported_without_schema_rewrite(self):
        remote = ContextRemote()
        self.assertEqual("synced", synchronize(self.store, remote)["state"])
        with self.store.connect() as db:
            state_value = current_state(self.store, db)
            bundle = self.store.meta(db, "jobhunt_context_bundle")
            status = self.store.meta(db, "jobhunt_context_status")
        material, policy = select_context(state_value, "creation", bundle=bundle, status=status)
        self.assertEqual("4.0", state_value["schema_version"])
        self.assertEqual("verified", policy["state"])
        self.assertEqual(["known answer"], policy["answered_topics"])
        self.assertEqual(["HYP-001", "ST-006"], policy["blocked_ids"])
        self.assertEqual([], material["hypotheses"])
        self.assertEqual([], material["stories"])
        document = next(e for e in self.store.snapshot()["entities"] if e["kind"] == "documents")
        self.assertEqual("synthetic summary", document["data"]["body"])

    def test_missing_context_blocks_external_generation_but_not_operations(self):
        self.assertFalse(constraints(None, "creation")["external_generation_allowed"])
        self.assertTrue(constraints(None, "operations")["external_generation_allowed"])

    def test_legacy_document_paths_are_read_only(self):
        path = ".jobhunt/documents/2026-01-01-synthetic-summary.md"
        self.assertTrue(GitHub.valid_document_path(path))
        self.assertFalse(GitHub.valid_document_path(path, writable=True))
        self.assertFalse(GitHub.valid_document_path(".jobhunt/documents/../state.json"))

    def test_github_reads_manifest_state_and_context_from_one_commit(self):
        remote = ContextRemote()
        commit = "b" * 40
        manifest = {"schema_version": "4.0", "repository_marker": "jp-job-hunting-coach-state",
                    "privacy_mode": "private-repo", "state_path": ".jobhunt/state.json",
                    "consents": {"github_state_read": True}}
        context_path = ".jobhunt/context/self-analysis.json"

        def blob(value, sha):
            encoded_value = base64.encodebytes(json.dumps(value).encode()).decode()
            return {"type": "file", "size": len(encoded_value), "sha": sha, "content": encoded_value}

        def route(request):
            path = request.url.path
            if path.endswith("/repos/msidragonsasus/jp-es-memory"):
                return httpx.Response(200, json={"private": True, "permissions": {"push": True}, "default_branch": "main"})
            if path.endswith("/commits/main"):
                return httpx.Response(200, json={"sha": commit})
            self.assertEqual(commit, request.url.params.get("ref"))
            if path.endswith("/.jobhunt/manifest.json"):
                return httpx.Response(200, json=blob(manifest, "manifest-sha"))
            if path.endswith("/.jobhunt/state.json"):
                return httpx.Response(200, json=blob(remote.state, "state-sha"))
            if path.endswith("/.jobhunt/context"):
                return httpx.Response(200, json=[{"type": "file", "path": context_path}])
            if path.endswith("/" + context_path):
                return httpx.Response(200, json=blob(remote.context_bundle, "context-sha"))
            return httpx.Response(404)

        token = Path(self.tmp.name) / "github-token"
        token.write_text("synthetic-token", encoding="utf-8")
        client = httpx.Client(transport=httpx.MockTransport(route))
        self.addCleanup(client.close)
        github = GitHub(token_file=token, client=client)
        loaded, sha = github.read()
        self.assertEqual(remote.state, loaded)
        self.assertEqual("state-sha", sha)
        self.assertEqual(commit, github.context_status["commit"])
        self.assertEqual("verified", github.context_status["state"])

    def test_correction_preserves_claim_and_marks_dependents(self):
        remote = Remote()
        remote.state["stories"] = [{"id": "ST-001", "fact_ids": ["FACT-001"]}]
        synchronize(self.store, remote)
        result = correct_fact(self.store, {"kind": "facts", "id": "FACT-001", "expected_version": 1,
            "data": dict(remote.state["facts"][0], statement="corrected"), "operation_id": "correction-1"})
        self.assertEqual("FACT-002", result["replacement"]["id"])
        entities = {e["id"]: e["data"] for e in self.store.snapshot()["entities"]}
        self.assertEqual("original", entities["FACT-001"]["statement"])
        self.assertEqual("SUPERSEDED", entities["FACT-001"]["status"])
        self.assertTrue(entities["ST-001"]["needs_review"])
        self.assertEqual("synced", synchronize(self.store, remote)["state"])

    def test_document_versions_and_submitted_record_remain_immutable(self):
        remote = Remote()
        synchronize(self.store, remote)
        first = {"kind": "documents", "id": "DOC-001", "expected_version": 0, "operation_id": "document-1",
                 "data": {"status": "drafting", "body": "draft one"}}
        save_document(self.store, first)
        second = dict(first, expected_version=1, operation_id="document-2", data={"status": "submitted", "body": "submitted two"})
        save_document(self.store, second)
        self.assertEqual("synced", synchronize(self.store, remote)["state"])
        self.assertEqual("draft one", remote.files[".jobhunt/documents/DOC-001-v001.md"])
        self.assertEqual("submitted two", remote.files[".jobhunt/documents/DOC-001-v002.md"])
        third = dict(first, expected_version=2, operation_id="document-3", data={"status": "drafting", "body": "new draft"})
        result = save_document(self.store, third)
        self.assertEqual("DOC-002", result["id"])
        saved = next(e for e in self.store.snapshot()["entities"] if e["id"] == "DOC-001")
        self.assertEqual("submitted", saved["data"]["status"])
        self.assertEqual("submitted two", saved["data"]["body"])

    def test_lost_sync_ack_reconciles_without_second_commit(self):
        remote = Remote()
        synchronize(self.store, remote)
        self.save("profile", "candidate", {"university": "edited"}, 1)
        write = remote.write
        def lost_ack(value, sha):
            write(value, sha)
            raise CareerError("github_unreachable", "lost acknowledgement", 503)
        remote.write = lost_ack
        self.assertEqual("unsynced", synchronize(self.store, remote)["state"])
        self.assertEqual(2, remote.state["revision"])
        self.assertEqual("synced", synchronize(self.store, remote)["state"])
        self.assertEqual(2, remote.state["revision"])

    def test_proposal_requires_review_and_does_not_promote_model_claim(self):
        proposal = self.store.propose({"kind": "facts", "id": "FACT-001", "expected_version": 0,
            "operation_id": "proposal-1", "reason": "test", "data": {"id": "FACT-001", "statement": "a model guess", "status": "CONFIRMED"}})
        self.assertFalse(any(e["kind"] == "facts" for e in self.store.snapshot()["entities"]))
        self.store.approve({"id": proposal["id"], "expected_version": 1, "operation_id": "approve-1", "approve": True})
        fact = next(e for e in self.store.snapshot()["entities"] if e["kind"] == "facts")
        self.assertEqual("UNKNOWN", fact["data"]["status"])
        self.assertEqual("MODEL_SUMMARY", fact["data"]["provenance"]["source_role"])

    def test_research_rejects_invented_year_and_time(self):
        pages = [{"url": "https://example.com/recruit", "text": "2027卒 2026年10月1日"}]
        offer = {"title": "course", "source_url": pages[0]["url"], "target_year": 2028, "year_evidence": "2027卒", "deadline": None}
        with self.assertRaises(CareerError):
            validate_offer(offer, pages)
        offer.update(target_year=2027, deadline="2026-10-01T23:59:00+09:00", deadline_evidence="2026年10月1日")
        with self.assertRaises(CareerError):
            validate_offer(offer, pages)
        offer["deadline"] = "2026-10-01"
        self.assertEqual("2026-10-01", validate_offer(offer, pages)["deadline"])

    def test_import_recruit_links_seed_research_but_interested_is_not_applied(self):
        request = self.bundle()
        request["records"][0]["data"]["status"] = "Interested"
        request["records"].append({"type": "CompanyLink", "source_id": "link", "source_version": "v-link",
            "data": {"companySyncId": "one", "type": "Recruit", "url": "https://example.com/recruit"}})
        import_bundle(self.store, request)
        entities = self.store.snapshot()["entities"]
        self.assertEqual(["https://example.com/recruit"], next(e for e in entities if e["kind"] == "companies")["data"]["official_urls"])
        self.assertFalse(any(e["kind"] == "applications" for e in entities))

    def test_migration_is_read_only(self):
        from career_core.migration import preview
        before = state()
        unchanged = copy.deepcopy(before)
        result = preview(before)
        self.assertTrue(result["preview_only"])
        self.assertEqual(unchanged, before)
        self.assertEqual("4.0", before["schema_version"])

    def test_disjoint_fields_merge_nested_arrays_atomic(self):
        self.assertEqual(({"a": 2, "b": 3}, []), merge_fields({"a": 1, "b": 1}, {"a": 2, "b": 1}, {"a": 1, "b": 3}))
        self.assertEqual(["a"], merge_fields({"a": []}, {"a": [1]}, {"a": [2]})[1])

    def test_api_auth_and_origin(self):
        app = FastAPI()
        app.state.auth_required = True
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        @app.middleware("http")
        async def authenticated(request, call_next):
            if request.headers.get("x-test-session") == "test":
                request.state.session = object()
            return await call_next(request)
        # New app client after test middleware registration.
        client = TestClient(app)
        self.assertEqual(401, client.get("/api/plugins/career/snapshot").status_code)
        self.assertEqual(401, client.post("/api/plugins/career/save", json={}).status_code)
        self.assertEqual(200, client.get("/api/plugins/career/snapshot", headers={"x-test-session": "test"}).status_code)
        self.assertEqual(403, client.post("/api/plugins/career/save", json={}, headers={"x-test-session": "test", "origin": "https://other.example"}).status_code)

    def test_api_auth_when_hosted_in_plugin_host(self):
        # A plugin-host child app has no gated session middleware; only the parent's decision arrives.
        app = FastAPI()
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        def hosted(decision):
            async def scoped(scope, receive, send):
                await app({**scope, "state": {**(scope.get("state") or {}), "hosted_http_authenticated": decision}}, receive, send)
            return scoped
        self.assertEqual(200, TestClient(hosted(True)).get("/api/plugins/career/snapshot").status_code)
        self.assertEqual(401, TestClient(hosted(False)).get("/api/plugins/career/snapshot").status_code)
        self.assertEqual(401, TestClient(app).get("/api/plugins/career/snapshot").status_code)

    def test_profile_falls_back_to_plugin_host_profile(self):
        # The Dashboard strips ?profile= when forwarding to a profile's plugin host.
        import sys, types
        from unittest import mock
        from career_core.api import request_profile
        fake = types.ModuleType("hermes_constants")
        fake.get_hermes_home = lambda: "/var/lib/hermes/.hermes/profiles/career-coach"
        fake.profile_name_for_home = lambda home: home.rsplit("/", 1)[-1]
        with mock.patch.dict(sys.modules, {"hermes_constants": fake}):
            with mock.patch.dict(os.environ, {"HERMES_PLUGIN_HOST_PROCESS": "1"}):
                self.assertEqual("career-coach", request_profile(None))
                self.assertEqual("other", request_profile("other"))
            with mock.patch.dict(os.environ, {"HERMES_PLUGIN_HOST_PROCESS": ""}):
                self.assertEqual("default", request_profile(None))
        app = FastAPI()
        app.include_router(create_router(lambda: self.store), prefix="/api/plugins/career")
        async def hosted(scope, receive, send):
            await app({**scope, "state": {"hosted_http_authenticated": True}}, receive, send)
        with mock.patch.dict(sys.modules, {"hermes_constants": fake}),                 mock.patch.dict(os.environ, {"HERMES_PLUGIN_HOST_PROCESS": "1"}):
            response = TestClient(hosted).get("/api/plugins/career/coach/threads")
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("career-coach", response.json()["profile"])

    def test_research_pause_resume_year_and_change(self):
        self.save("companies", "one", {"name": "test", "source": {"type": "Company"}, "official_urls": ["https://example.com/recruit"]})
        class Extractor:
            paused = True
            deadline = "2026-10-01"
            year = 2027
            def extract(inner, company, pages):
                if inner.paused:
                    raise CareerError("quota_paused", "quota", 429)
                return [{"title": "course", "source_url": pages[0]["url"], "target_year": inner.year,
                         "year_evidence": str(inner.year), "deadline": inner.deadline, "deadline_evidence": inner.deadline}]
        extractor = Extractor()
        fetch = lambda url: {"url": url, "text": "2027 2028 2026-10-01 2026-10-02", "links": []}
        self.assertEqual("paused", research(self.store, extractor, fetch)["state"])
        extractor.paused = False
        research(self.store, extractor, fetch)
        self.assertEqual(1, len(self.store.snapshot()["notices"]) - 1)
        before = len(self.store.snapshot()["notices"])
        research(self.store, extractor, fetch)
        self.assertEqual(before, len(self.store.snapshot()["notices"]))
        extractor.deadline = "2026-10-02"
        research(self.store, extractor, fetch)
        self.assertEqual(before + 1, len(self.store.snapshot()["notices"]))
        extractor.year = 2028
        research(self.store, extractor, fetch)
        self.assertEqual(2, sum(e["kind"] == "offers" for e in self.store.snapshot()["entities"]))


    def test_deadline_changes_preserve_manual_edits_and_unknown_previous_value(self):
        self.save("companies", "one", {"name": "test", "source": {"type": "Company"}, "official_urls": ["https://example.com/recruit"]})
        class Extractor:
            deadline = "2026-10-01"
            def extract(inner, company, pages):
                return [{"title": "course", "source_url": pages[0]["url"], "target_year": 2027,
                         "year_evidence": "2027", "deadline": inner.deadline, "deadline_evidence": inner.deadline}]
        extractor = Extractor()
        fetch = lambda url: {"url": url, "text": "2027 2026-10-01 2026-10-02 2026-10-03", "links": []}
        def entity(kind):
            return next(e for e in self.store.snapshot()["entities"] if e["kind"] == kind)
        research(self.store, extractor, fetch)
        offer = entity("offers")
        self.store.decide({"id": offer["id"], "expected_version": offer["version"], "decision": "apply", "operation_id": "apply-deadline"})
        self.assertEqual("2026-10-01", entity("tasks")["data"]["deadline"])
        extractor.deadline = "2026-10-02"
        research(self.store, extractor, fetch)
        self.assertEqual("2026-10-02", entity("applications")["data"]["deadline"])
        task = entity("tasks")
        self.save("tasks", task["id"], dict(task["data"], deadline="2026-09-30"), task["version"], operation="manual-deadline")
        extractor.deadline = "2026-10-03"
        research(self.store, extractor, fetch)
        self.assertEqual("2026-10-03", entity("applications")["data"]["deadline"])
        self.assertEqual("2026-09-30", entity("tasks")["data"]["deadline"])
        extractor.deadline = None
        research(self.store, extractor, fetch)
        self.assertEqual("unconfirmed", entity("offers")["data"]["deadline_verification"])
        self.assertEqual("2026-10-03", entity("applications")["data"]["deadline"])

    def test_approval_rejects_truthy_non_boolean_without_writing(self):
        with self.assertRaises(CareerError):
            self.store.approve({"id": "none", "approve": "false", "operation_id": "approve-invalid"})
        self.assertEqual([], self.store.snapshot()["entities"])

    def test_job_lock_excludes_second_runner_and_releases(self):
        from career_core.locking import job_lock
        path = Path(self.tmp.name) / "test.lock"
        with job_lock(path) as first:
            self.assertTrue(first)
            with job_lock(path) as second:
                self.assertFalse(second)
        with job_lock(path) as third:
            self.assertTrue(third)

    def test_backup_is_consistent_and_never_overwrites(self):
        import os
        import subprocess
        import sqlite3
        import sys
        self.save("companies", "one", {"name": "synthetic"})
        target = Path(self.tmp.name) / "backup.sqlite3"
        command = [sys.executable, str(Path(__file__).resolve().parents[1] / "backup.py"), str(target)]
        env = dict(os.environ, CAREER_DATA_DIR=self.tmp.name)
        result = subprocess.run(command, env=env, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        original = target.read_bytes()
        self.assertNotEqual(0, subprocess.run(command, env=env, capture_output=True).returncode)
        self.assertEqual(original, target.read_bytes())
        from contextlib import closing
        with closing(sqlite3.connect(target)) as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual(1, db.execute("SELECT count(*) FROM entities").fetchone()[0])

    def test_codex_paid_fallback_is_blocked_before_network(self):
        import sys
        import types
        from unittest.mock import patch
        from career_core.research import CodexExtractor
        auth = types.ModuleType("hermes_cli.auth")
        auth.resolve_codex_runtime_credentials = lambda: {"provider": "openai", "base_url": "https://api.openai.com/v1", "api_key": "synthetic"}
        with patch.dict(sys.modules, {"hermes_cli.auth": auth}), patch("career_core.research.httpx.Client") as client:
            with self.assertRaises(CareerError) as error:
                CodexExtractor().extract({}, [])
            self.assertEqual("paid_fallback_blocked", error.exception.code)
            client.assert_not_called()

    def test_malformed_research_is_visible_and_preserves_existing(self):
        self.save("companies", "one", {"name": "test", "source": {"type": "Company"}, "official_urls": ["https://example.com/recruit"]})
        class Extractor:
            def extract(inner, company, pages):
                return [None]
        result = research(self.store, Extractor(), lambda url: {"url": url, "text": "test", "links": []})
        self.assertEqual("completed_with_failures", result["state"])
        self.assertEqual(1, len(self.store.snapshot()["entities"]))
        self.assertEqual("invalid_research", self.store.snapshot()["notices"][0]["data"]["reason"])

    def test_missing_token_file_retains_unsynced_status(self):
        from unittest.mock import patch
        from career_core.api import sync_job
        with patch.dict("os.environ", {"CAREER_GITHUB_TOKEN_FILE": str(Path(self.tmp.name) / "missing-token")}):
            self.assertEqual("unsynced", sync_job(self.store)["state"])
        self.assertEqual("github_not_configured", self.store.snapshot()["sync"]["reason"])

if __name__ == "__main__":
    unittest.main()
