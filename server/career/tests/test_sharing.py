"""Synthetic ancestry, publication, CAS, ACK recovery and frozen-run regressions."""
import base64
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from career_core.context_bundle import choose_context_bundle, constraints
from career_core.shared import current_state, save_document, validate_shared
from career_core.sharing import (CURRENT_JSON, CURRENT_MD, blob_sha, build_current, render_current,
                                save_context_patch, pin_run_context)
from career_core.store import CareerError, Store, encoded
from career_core.sync import GitHub, resolve_conflict, synchronize


def sources():
    from test_career import state
    value = state()
    value["revision"] = 8
    value["hypotheses"] = [{"id": "HYP-001", "status": "unconfirmed"}]
    value["stories"] = [{"id": "ST-006", "fact_ids": ["FACT-001"]}]
    base = {"format": "jobhunt-self-analysis-context/1", "snapshot_state_revision": 7,
            "source_boundaries": {"private_only": True, "raw_transcript_stored": False, "sensitive_details_stored": False},
            "generation_guards": [{"id": f"GUARD-{i:03}", "disposition": "do_not_use", "scope": ["causality"],
                                   "record_ids": ["FACT-001"], "topic": "synthetic", "reason": "synthetic"} for i in range(1, 11)],
            "corrections": [], "assessment_policy": {"withdrawn": ["HYP-001"]},
            "answered_topics": ["known synthetic answer"],
            "open_questions": [{"id": "OQ-001", "question": "old question", "status": "unanswered"}]}
    base["generation_guards"][7].update(disposition="hold_for_separate_review", story_ids=["ST-006"])
    latest = {"format": base["format"], "snapshot_state_revision": 8,
              "base_context_path": ".jobhunt/context/base.json", "source_boundaries": base["source_boundaries"],
              "generation_guards": [dict(base["generation_guards"][6], disposition="user_reported_totals_only_causality_unverified"),
                                    {"id": "GUARD-011", "disposition": "do_not_use", "scope": ["causality"], "topic": "synthetic", "reason": "synthetic"},
                                    {"id": "GUARD-012", "disposition": "withdrawn_assistant_inference", "scope": ["personality"], "topic": "synthetic", "reason": "synthetic"},
                                    {"id": "GUARD-013", "disposition": "not_verified_do_not_promote_to_fact", "scope": ["source"], "topic": "synthetic", "reason": "synthetic"}],
              "corrections": [{"id": "COR-009", "record_ids": ["FACT-001", "ST-006", "GUARD-007"],
                               "resolution": "historical_statement_retained_with_current_update", "current_statement": "synthetic estimate answered; causality unverified"}],
              "answered_topics": ["synthetic estimate answered"],
              "open_questions": [{"id": "OQ-001", "question": "new question", "status": "partially_answered_deferred_by_user"}]}
    manifest = {"schema_version": "4.0", "repository_marker": "jp-job-hunting-coach-state", "privacy_mode": "private-repo",
                "state_path": ".jobhunt/state.json", "context_entry_path": ".jobhunt/context/latest.json",
                "consents": {"github_state_read": True, "github_write_through": True}}
    return {".jobhunt/state.json": encoded(value) + "\n", ".jobhunt/manifest.json": encoded(manifest) + "\n",
            ".jobhunt/context/base.json": encoded(base) + "\n", ".jobhunt/context/latest.json": encoded(latest) + "\n"}


class Repository:
    def __init__(self):
        self.head = "a" * 40
        self.commits = {self.head: sources()}
        self.trees = {self.head: self.commits[self.head]}
        self.writes = []
        self.lost_ack = False
        self.reject_update = False
        self.fail_projection = False
        self.auth_failed = False

    def request(self, request):
        if self.auth_failed:
            return httpx.Response(401)
        path = request.url.path.removeprefix("/repos/synthetic/private")
        if request.method == "GET":
            if path == "":
                return httpx.Response(200, json={"private": True, "permissions": {"push": True}, "default_branch": "main"})
            if path.startswith("/commits/"):
                return httpx.Response(200, json={"sha": self.head, "commit": {"tree": {"sha": self.head}}})
            if path.startswith("/git/commits/"):
                return httpx.Response(200, json={"tree": {"sha": self.head}})
            if path.startswith("/contents/"):
                name = path.removeprefix("/contents/")
                commit = request.url.params.get("ref")
                files = self.commits[commit]
                if name == ".jobhunt/context":
                    return httpx.Response(200, json=[{"type": "file", "path": key} for key in files if key.startswith(name + "/")])
                if name not in files:
                    return httpx.Response(404)
                body = files[name]
                return httpx.Response(200, json={"type": "file", "size": len(body.encode()), "sha": blob_sha(body),
                                               "content": base64.b64encode(body.encode()).decode()})
        payload = json.loads(request.content)
        if request.method == "POST" and path == "/git/trees":
            tree = copy.deepcopy(self.trees[payload["base_tree"]])
            if self.fail_projection and any(item["path"] == CURRENT_JSON for item in payload["tree"]):
                self.fail_projection = False
                return httpx.Response(500)
            for item in payload["tree"]:
                tree[item["path"]] = item["content"]
            sha = f"{len(self.trees) + 1:040x}"
            self.trees[sha] = tree
            return httpx.Response(201, json={"sha": sha})
        if request.method == "POST" and path == "/git/commits":
            sha = f"{len(self.commits) + 100:040x}"
            self.commits[sha] = copy.deepcopy(self.trees[payload["tree"]])
            self.trees[sha] = self.commits[sha]
            self.writes.append({"sha": sha, "parent": payload["parents"][0], "message": payload["message"]})
            return httpx.Response(201, json={"sha": sha})
        if request.method == "PATCH" and path == "/git/refs/heads/main":
            assert payload["force"] is False
            parent = self.writes[-1]["parent"]
            if self.reject_update or parent != self.head:
                return httpx.Response(422)
            self.head = payload["sha"]
            if self.lost_ack:
                self.lost_ack = False
                raise httpx.ReadError("synthetic lost acknowledgement", request=request)
            return httpx.Response(200, json={"object": {"sha": self.head}})
        raise AssertionError((request.method, path))


class SharingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.repository = Repository()
        token = Path(self.tmp.name) / "test-token"
        token.write_text("synthetic-token", encoding="utf-8")
        self.token = token

    def remote(self):
        return GitHub(self.token, httpx.Client(transport=httpx.MockTransport(self.repository.request)), repository="synthetic/private")

    def sync(self):
        remote = self.remote()
        try:
            return synchronize(self.store, remote)
        finally:
            remote.client.close()

    def test_current_preserves_all_13_guards_and_known_answers(self):
        self.assertEqual("synced", self.sync()["state"])
        files = self.repository.commits[self.repository.head]
        value = json.loads(files[CURRENT_JSON])
        bundle = value["context"]
        self.assertEqual(13, len(bundle["generation_guards"]))
        self.assertEqual("user_reported_totals_only_causality_unverified", next(g for g in bundle["generation_guards"] if g["id"] == "GUARD-007")["disposition"])
        self.assertEqual("partially_answered_deferred_by_user", bundle["open_questions"][0]["status"])
        self.assertIn("known synthetic answer", bundle["answered_topics"])
        self.assertIn("synthetic estimate answered", bundle["answered_topics"])
        self.assertIn("ST-006", constraints(bundle, "creation")["blocked_ids"])
        self.assertIn("HYP-001", constraints(bundle, "creation")["blocked_ids"])
        self.assertEqual(render_current(value), files[CURRENT_MD])

    def test_unchanged_timer_does_not_generate_commits_or_revisions(self):
        self.sync()
        count, head = len(self.repository.writes), self.repository.head
        self.assertEqual("synced", self.sync()["state"])
        self.assertEqual((count, head), (len(self.repository.writes), self.repository.head))
        self.assertEqual(8, json.loads(self.repository.commits[head][".jobhunt/state.json"])["revision"])

    def edit_fact(self):
        with self.store.transaction() as db:
            row = self.store.entity(db, "facts", "FACT-001")
            self.store.put(db, "facts", "FACT-001", row["version"], dict(row["data"], statement="synthetic update"))

    def test_lost_source_ack_keeps_dirty_then_recovers_without_duplicate_revision(self):
        self.sync()
        self.edit_fact()
        self.repository.lost_ack = True
        self.assertEqual("unsynced", self.sync()["state"])
        self.assertEqual("synced", self.sync()["state"])
        self.assertEqual(9, json.loads(self.repository.commits[self.repository.head][".jobhunt/state.json"])["revision"])

    def test_projection_failure_keeps_pending_and_retries_source_once(self):
        self.sync()
        self.edit_fact()
        self.repository.fail_projection = True
        self.assertEqual("unsynced", self.sync()["state"])
        files = self.repository.commits[self.repository.head]
        self.assertEqual(9, json.loads(files[".jobhunt/state.json"])["revision"])
        self.assertEqual(8, json.loads(files[CURRENT_JSON])["source_revision"])
        self.assertEqual("synced", self.sync()["state"])
        self.assertEqual(9, json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["source_revision"])

    def test_nonforce_rejection_preserves_remote_and_dirty(self):
        self.sync()
        self.edit_fact()
        old_head = self.repository.head
        self.repository.reject_update = True
        self.assertEqual("unsynced", self.sync()["state"])
        self.assertEqual(old_head, self.repository.head)
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT dirty FROM entities WHERE kind='facts'").fetchone()[0])

    def test_same_revision_context_change_invalidates_current_hashes(self):
        self.sync()
        previous = json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])
        current = self.repository.commits[self.repository.head]
        latest = json.loads(current[".jobhunt/context/latest.json"])
        latest["answered_topics"].append("another synthetic answer")
        current[".jobhunt/context/latest.json"] = encoded(latest) + "\n"
        self.assertEqual("synced", self.sync()["state"])
        updated = json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])
        self.assertEqual(8, updated["source_revision"])
        self.assertNotEqual(previous["content_hash"], updated["content_hash"])

    def test_context_patch_updates_sources_atomically_and_preserves_ancestry(self):
        self.sync()
        with self.store.transaction() as db:
            result = save_context_patch(self.store, db, {"id": "OQ-002", "expected_version": 0,
                "data": {"section": "open_questions", "record": {"id": "OQ-002", "question": "synthetic next", "status": "unanswered"}}},
                {"message_id": "test-message", "quote": "synthetic next"})
        self.assertTrue(result["server_saved"])
        self.assertEqual("synced", self.sync()["state"])
        source = self.repository.commits[self.repository.writes[-2]["sha"]]
        context_path = json.loads(source[".jobhunt/manifest.json"])["context_entry_path"]
        self.assertTrue(context_path.startswith(".jobhunt/context/hermes-r9-"))
        self.assertIn(context_path, source)
        self.assertEqual(9, json.loads(source[".jobhunt/state.json"])["revision"])
        self.assertEqual(13, len(json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["context"]["generation_guards"]))
        count = len(self.repository.writes)
        self.sync()
        self.assertEqual(count, len(self.repository.writes))

    def test_missing_base_and_cycle_fail_without_external_generation(self):
        files = sources()
        state = json.loads(files[".jobhunt/state.json"])
        values = {key: json.loads(body) for key, body in files.items() if key.startswith(".jobhunt/context")}
        values[".jobhunt/context/latest.json"]["base_context_path"] = ".jobhunt/context/missing.json"
        with self.assertRaises(CareerError):
            choose_context_bundle(values, state)
        values[".jobhunt/context/latest.json"]["base_context_path"] = ".jobhunt/context/latest.json"
        with self.assertRaises(CareerError):
            choose_context_bundle(values, state)

    def test_legacy_fields_are_preserved_but_new_invalid_values_are_rejected(self):
        from test_career import state
        old = state()
        old["facts"][0]["provenance"] = {"source_kind": "CURRENT_USER_STATEMENT", "confidence": "high"}
        old["hypotheses"] = [{"id": "HYP-001", "status": "unconfirmed"}]
        value = copy.deepcopy(old)
        value["facts"][0]["statement"] = "synthetic correction"
        validate_shared(value, previous=old)
        self.assertEqual(old["facts"][0]["provenance"], value["facts"][0]["provenance"])
        value["hypotheses"][0]["status"] = "invalid-new-status"
        with self.assertRaises(CareerError):
            validate_shared(value, previous=old)

    def test_legacy_named_document_import_ids_remain_but_new_named_ids_fail(self):
        from test_career import state
        old = state()
        old["documents"] = [{"id": "DOC-SYNTHETIC-OLD", "path": ".jobhunt/documents/synthetic-old.md"}]
        old["imports"] = [{"id": "IMPORT-SYNTHETIC-OLD"}]
        validate_shared(old, previous=old)
        new = copy.deepcopy(old)
        new["imports"].append({"id": "IMP-SYNTHETIC-NEW"})
        with self.assertRaises(CareerError):
            validate_shared(new, previous=old)

    def test_auth_failure_retains_pending_then_retries(self):
        self.sync()
        self.edit_fact()
        self.repository.auth_failed = True
        self.assertEqual("unsynced", self.sync()["state"])
        with self.store.connect() as db:
            self.assertEqual(1, self.store.entity(db, "facts", "FACT-001")["dirty"])
        self.repository.auth_failed = False
        self.assertEqual("synced", self.sync()["state"])
        self.assertEqual(9, json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["source_revision"])

    def test_document_ack_loss_reconciles_immutable_outbox(self):
        self.sync()
        save_document(self.store, {"kind": "documents", "id": "DOC-001", "expected_version": 0,
            "operation_id": "document-synthetic-1", "data": {"title": "synthetic", "body": "synthetic body"}})
        self.repository.lost_ack = True
        self.assertEqual("unsynced", self.sync()["state"])
        with self.store.connect() as db:
            self.assertEqual(0, db.execute("SELECT synced FROM document_files").fetchone()[0])
        self.assertEqual("synced", self.sync()["state"])
        with self.store.connect() as db:
            self.assertEqual(1, db.execute("SELECT synced FROM document_files").fetchone()[0])
        self.assertEqual(9, json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["source_revision"])

    def test_fact_correction_updates_exact_sidecar_and_preserves_old_quote(self):
        files = self.repository.commits[self.repository.head]
        base = json.loads(files[".jobhunt/context/base.json"])
        base["corrections"] = [{"id": "COR-001", "record_id": "FACT-001", "current_statement": "original",
                                "previous_statement": "synthetic original history"}]
        files[".jobhunt/context/base.json"] = encoded(base) + "\n"
        self.sync()
        self.edit_fact()
        self.assertEqual("synced", self.sync()["state"])
        self.assertEqual("synced", self.sync()["state"])
        corrections = json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["context"]["corrections"]
        result = next(item for item in corrections if item["id"] == "COR-001")
        self.assertEqual("synthetic update", result["current_statement"])
        self.assertEqual("original", result["statement_history"][0]["statement"])
        self.assertEqual("synthetic original history", result["previous_statement"])

    def test_context_conflict_requires_explicit_choice_and_fresh_cas(self):
        self.sync()
        with self.store.transaction() as db:
            old = self.store.meta(db, "jobhunt_context_bundle")["generation_guards"][0]
            save_context_patch(self.store, db, {"id": old["id"], "expected_version": 1,
                "data": {"section": "generation_guards", "record": dict(old, topic="local synthetic")}},
                {"message_id": "synthetic", "quote": "local synthetic"})
        files = self.repository.commits[self.repository.head]
        base = json.loads(files[".jobhunt/context/base.json"])
        base["generation_guards"][0]["topic"] = "remote synthetic"
        files[".jobhunt/context/base.json"] = encoded(base) + "\n"
        self.assertEqual("conflict", self.sync()["state"])
        with self.store.connect() as db:
            self.assertTrue(self.store.meta(db, "jobhunt_context_pending"))
        choice = {"id": "generation_guards:GUARD-001", "choice": "local", "expected_version": 2,
                  "operation_id": "resolve-context-synthetic"}
        self.assertEqual(resolve_conflict(self.store, choice), resolve_conflict(self.store, choice))
        self.assertEqual("synced", self.sync()["state"])
        guards = json.loads(self.repository.commits[self.repository.head][CURRENT_JSON])["context"]["generation_guards"]
        self.assertEqual("local synthetic", next(g for g in guards if g["id"] == "GUARD-001")["topic"])
        self.assertEqual(13, len(guards))

    def test_new_context_fields_cannot_expand_private_storage(self):
        self.sync()
        with self.store.transaction() as db:
            with self.assertRaises(CareerError):
                save_context_patch(self.store, db, {"id": "ANSWER-NEW", "expected_version": 0,
                    "data": {"section": "answered_topics", "record": {"id": "ANSWER-NEW", "topic": "synthetic", "contacts": []}}},
                    {"message_id": "synthetic", "quote": "synthetic"})
            self.assertFalse(self.store.meta(db, "jobhunt_context_pending", {}))

    def test_run_reads_are_frozen_and_offline_run_excludes_shared_material(self):
        from coach_fixture import configure_execution
        from career_core.coach_store import CoachStore
        from career_core.coach_tools import CoachTools
        configure_execution(self)
        self.sync()
        coach = CoachStore(self.store)
        coach.submit({"text": "synthetic", "operation_id": "frozen-context-run"})
        run = coach.claim()
        pin_run_context(coach, run)
        tools = CoachTools(coach)
        before = tools.context({"kind": "facts", "purpose": "exploration"}, run)
        self.edit_fact()
        after = tools.context({"kind": "facts", "purpose": "exploration"}, run)
        self.assertEqual(before["material"], after["material"])
        self.assertEqual(before["records"], after["records"])
        with self.store.transaction() as db:
            self.store.set_meta(db, "sync_status", {"state": "unsynced"})
        pin_run_context(coach, run)
        offline = tools.context({"kind": "facts", "purpose": "creation"}, run)
        self.assertEqual([], offline["records"])
        self.assertEqual([], offline["material"]["facts"])
        self.assertFalse(offline["context_constraints"]["external_generation_allowed"])

    def test_offline_advice_uses_last_verified_reference_without_allowing_generation(self):
        from coach_fixture import configure_execution
        from career_core.coach_store import CoachStore
        from career_core.coach_tools import CoachTools
        configure_execution(self)
        self.sync()
        coach = CoachStore(self.store)
        coach.submit({"text": "synthetic advice", "operation_id": "cached-advice-synthetic"})
        run = coach.claim()
        with self.store.transaction() as db:
            self.store.set_meta(db, "sync_status", {"state": "unsynced", "reason": "synthetic-network"})
        pinned = pin_run_context(coach, run)
        self.assertEqual("unavailable", pinned["status"]["state"])
        advice = CoachTools(coach).context({"purpose": "exploration"}, run)
        self.assertTrue(advice["material"]["facts"])
        self.assertEqual("cached_reference", advice["context_constraints"]["state"])
        self.assertFalse(advice["context_constraints"]["external_generation_allowed"])
        creation = CoachTools(coach).context({"purpose": "creation"}, run)
        self.assertEqual([], creation["material"]["facts"])
        self.assertFalse(creation["context_constraints"]["external_generation_allowed"])
        with self.store.transaction() as db:
            self.store.set_meta(db, "jobhunt_context_conflicts", [{"id": "synthetic-conflict"}])
        self.assertIsNone(pin_run_context(coach, run)["cached_reference"])

    def test_save_after_preflight_cannot_reuse_cached_synced_status(self):
        from coach_fixture import configure_execution
        from career_core.coach_store import CoachStore
        from career_core.coach_tools import CoachTools
        configure_execution(self)
        self.sync()
        coach = CoachStore(self.store)
        coach.submit({"text": "synthetic", "operation_id": "preflight-save-race"})
        run = coach.claim()
        self.edit_fact()
        # A second writer can save after preflight, before context is pinned.
        with self.store.transaction() as db:
            self.store.set_meta(db, "sync_status", {"state": "synced"})
        self.assertEqual("unsynced", self.store.snapshot()["sync"]["state"])
        self.assertEqual("unsynced", coach.snapshot()["sync"]["state"])
        pinned = pin_run_context(coach, run)
        self.assertEqual("unavailable", pinned["status"]["state"])
        material = CoachTools(coach).context({"kind": "facts", "purpose": "creation"}, run)
        self.assertEqual([], material["records"])
        self.assertFalse(material["context_constraints"]["external_generation_allowed"])
        self.sync()
        self.assertEqual("verified", pin_run_context(coach, run)["status"]["state"])
        # Context/checkpoint-only writes also need publication before reuse.
        for key in ("jobhunt_context_pending", "coach_checkpoint_pending"):
            with self.store.transaction() as db:
                self.store.set_meta(db, key, {"synthetic": True})
                self.assertEqual("unsynced", self.store.sharing_status(db)["state"])
                self.store.set_meta(db, key, {})


if __name__ == "__main__":
    unittest.main()
