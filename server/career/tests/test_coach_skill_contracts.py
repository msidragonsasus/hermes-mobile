import copy
import json
import re
import unittest

from career_core.coach_skills import ROOT, catalog, read_skill
from career_core.vendor.v41.state_v4 import CLASSIFICATIONS, KIND_ROLES


class CoachSkillContractTests(unittest.TestCase):
    def resources(self, filename):
        return sorted(ROOT.glob("*/references/" + filename))

    def test_generic_patch_examples_preserve_unrelated_records_and_questions(self):
        original = {
            "tasks": [{"task_id": "TASK-001", "title": "既存作業", "status": "todo",
                       "due_precision": "unknown", "dependency_ids": ["TASK-002"]}],
            "last_session": {"open_questions": ["既存の未解決の問い"]},
        }
        for path in self.resources("state-patch.md"):
            with self.subTest(path=path):
                match = re.search(r"```json\s*(.*?)\s*```", path.read_text(encoding="utf-8"), re.S)
                patch = json.loads(match.group(1))
                updated = copy.deepcopy(original)
                for operation in patch["operations"]:
                    if operation["op"] == "upsert_record":
                        record = operation["record"]
                        updated[operation["collection"]] = [record]
                    else:
                        self.assertEqual("update_root_fields", operation["op"])
                        updated[operation["field"]].update(operation["values"])
                self.assertEqual(original["tasks"], updated["tasks"])
                self.assertEqual(original["last_session"]["open_questions"],
                                 updated["last_session"]["open_questions"])

    def test_memory_provenance_examples_match_schema(self):
        for path in self.resources("evidence-policy.md"):
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                for kind in ("CHATGPT_MEMORY_SURFACED", "USER_PASTED_MEMORY_SUMMARY"):
                    self.assertIn('source_kind="' + kind + '"', text)
                    self.assertEqual({"MODEL_SUMMARY", "UNKNOWN"}, KIND_ROLES[kind])
                self.assertIn("factのclassificationではない", text)
                self.assertNotIn("EXTERNAL", CLASSIFICATIONS)
                for classification in ("SENSITIVE_REQUIRES_OPT_IN", "SKIPPED_NOT_JOB_HUNTING"):
                    self.assertIn(classification, text)

    def test_storage_read_requires_separate_consent(self):
        for path in self.resources("github-chat-storage.md"):
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                private_load = text.split("## Private verification and load", 1)[1]
                self.assertLess(private_load.index("`github_state_read`"),
                                private_load.index("manifest.json"))
                consent = text.split("## Consent and setup", 1)[1].split("## SHA-safe", 1)[0]
                self.assertIn("`github_state_read`", consent)

    def test_exploration_delegation_uses_registered_skill(self):
        text = read_skill("operate-jp-job-hunting")["text"]
        self.assertIn("`build-jp-career-profile`", text)
        self.assertIn("build-jp-career-profile", {entry["name"] for entry in catalog()})

    def test_hermes_document_instructions_match_required_evidence(self):
        text = (ROOT / "HERMES.md").read_text(encoding="utf-8")
        self.assertIn("version_evidence", text)
        self.assertIn("自動生成しない", text)
        text = read_skill("review-jp-entry-sheets")["text"]
        self.assertIn("status:drafting", text)
        self.assertIn("document_offset", text)


if __name__ == "__main__":
    unittest.main()
