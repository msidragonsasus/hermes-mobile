import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_catalog import GroupCatalog
from group_service import GroupError


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.rooms = {"id:research": {"name": "research", "displayName": "ゼミ研究報告会",
            "roomId": "research", "members": ["alice", {"name": "bob"}],
            "speakingOrder": ["bob", "alice"], "finalSummaryMember": "alice",
            "systemPrompt": "保存済み進行ルール", "log": [{"text": "private history"}]}}
        self.snapshot = {"rooms": self.rooms}
        self.profiles = [
            {"name": "default", "ui_meta": {"hermes-bots-groups": self.snapshot}},
            {"name": "alice", "ui_meta": {"hermes-bots": {"title": "Alice"}}},
            {"name": "bob"},
            # A stale legacy tag must not expand an explicit saved room roster.
            {"name": "unrelated", "ui_meta": {"hermes-bots": {"groups": ["research"]}}}]
        self.catalog = GroupCatalog(lambda: self.profiles)

    def error(self, code, args):
        with self.assertRaises(GroupError) as failure:
            self.catalog.resolve(args)
        self.assertEqual(code, failure.exception.code)

    def test_uses_existing_identity_roster_order_and_settings(self):
        for selection in ({"groupId": "id:research"}, {"groupName": "ゼミ研究報告会"}):
            value = self.catalog.resolve(selection)
            self.assertEqual("research", value["groupName"])
            self.assertEqual("id:research", value["groupId"])
            self.assertEqual(["bob", "alice"], value["members"])
            self.assertEqual("alice", value["summaryMember"])
            self.assertEqual("保存済み進行ルール", value["systemPrompt"])

    def test_discovery_excludes_history_and_private_settings(self):
        output = json.dumps(self.catalog.list())
        self.assertNotIn("private history", output)
        self.assertNotIn("systemPrompt", output)
        self.assertEqual(["bob", "alice"], self.catalog.list()[0]["members"])

    def test_unknown_name_never_creates_group_or_uses_all_profiles(self):
        self.error("group_not_found", {"groupName": "invented", "members": ["alice", "bob", "unrelated"]})
        self.assertEqual(["id:research"], list(self.rooms))

    def test_model_cannot_expand_the_saved_roster(self):
        self.error("group_members_mismatch", {"groupId": "id:research", "members": ["bob", "alice", "unrelated"]})

    def test_same_display_names_require_explicit_identity(self):
        self.rooms["id:second"] = dict(self.rooms["id:research"], name="second", roomId="second")
        self.error("group_ambiguous", {"groupName": "ゼミ研究報告会"})
        self.assertEqual("second", self.catalog.resolve({"groupId": "id:second"})["groupName"])
        self.error("group_selection_conflict", {"groupId": "id:second", "groupName": "research"})

    def test_deleted_and_tombstone_rooms_do_not_return_via_legacy_tags(self):
        for change in (lambda: self.snapshot.update(deleted={"id:research": 1}),
                       lambda: self.rooms["id:research"].update(tombstone=True)):
            change()
            self.assertEqual([], self.catalog.list())
            self.error("group_not_found", {"groupName": "research"})

    def test_legacy_memberships_only_use_explicit_tags(self):
        self.rooms.clear()
        value = self.catalog.resolve({"groupName": "research"})
        self.assertEqual(["unrelated"], value["members"])
        self.assertEqual("name:research", value["groupId"])

    def test_known_empty_name_room_uses_only_matching_profile_tags(self):
        self.rooms["id:research"].update(members=[{"name": ""}], speakingOrder=[], finalSummaryMember=None)
        value = self.catalog.resolve({"groupId": "id:research"})
        self.assertEqual(["unrelated"], value["members"])
        self.assertNotIn("default", value["members"])
        self.assertNotIn("alice", value["members"])

    def test_bad_or_missing_members_fail_without_partial_roster(self):
        for members in ([], ["alice", "missing"], ["alice", {}], [123],
                        [{"name": "alice", "source": "foreign-host"}]):
            with self.subTest(members=members):
                self.rooms["id:research"]["members"] = members
                row = self.catalog.list()[0]
                self.assertFalse(row["runnable"])
                self.assertEqual([], row["members"])
                self.error(row["error"], {"groupId": "id:research"})

    def test_resolves_latest_metadata_after_discovery(self):
        self.catalog.list()
        self.rooms["id:research"].update(members=["alice"], speakingOrder=[], finalSummaryMember="alice")
        self.assertEqual(["alice"], self.catalog.resolve({"groupId": "id:research"})["members"])

    def test_exact_profile_identity_wins_and_case_collisions_are_not_guessed(self):
        self.profiles.append({"name": "ALICE"})
        self.rooms["id:research"].update(speakingOrder=[], finalSummaryMember=None)
        self.assertEqual(["alice", "bob"], self.catalog.resolve({"groupId": "id:research"})["members"])
        self.rooms["id:research"]["members"] = ["ALICE", "bob"]
        self.assertEqual(["ALICE", "bob"], self.catalog.resolve({"groupId": "id:research"})["members"])
        self.rooms["id:research"]["members"] = ["Alice", "bob"]
        self.error("group_member_unavailable", {"groupId": "id:research"})


if __name__ == "__main__":
    unittest.main()
