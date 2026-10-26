"""Synthetic regressions: ordinary consultation, safe readiness and durable history."""
import tempfile
import unittest
from unittest.mock import patch

from career_core.coach_runtime import HermesCoachRuntime, bounded_history, history_budget, retained_history
from career_core.coach_store import CoachStore
from career_core.coach_tools import CoachTools
from career_core.company_views import CompanyViews
from career_core.mypage_bot import MyPageBotStore, connection_id
from career_core.mail_store import MailStore
from career_core.store import CareerError, Store, encoded
from coach_fixture import configure_execution


class ConsultationContextTests(unittest.TestCase):
    def setUp(self):
        configure_execution(self)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = Store(self.tmp.name)
        self.coach = CoachStore(self.store)
        self.bot = MyPageBotStore(self.store)
        self.mail = MailStore(self.store)
        with self.store.transaction() as db:
            for identity in ("missing", "saved", "blocked"):
                self.store.put(db, "companies", identity, 0, {"name": "Synthetic " + identity})
            for identity, state in (("saved", "not_started"), ("blocked", "auth_required")):
                config = {"url": "https://portal.example.test/login", "secret_ref": "private-synthetic-ref",
                          "enabled": True, "verified": False}
                db.execute("INSERT INTO career_mypage_connections VALUES(?,?,?,?,?,?)",
                           (connection_id(identity, "default"), identity, "default", 1, encoded(config), encoded({"state": state})))
        self.tools = CoachTools(self.coach)

    def run_record(self, kind="message", company=None, identity="synthetic-run"):
        return {"id": identity, "profile": "default", "thread_id": "global", "kind": kind,
                "request": {"scope": {"company_id": company} if company else {}}}

    def test_ordinary_consultation_gets_all_company_states_without_secrets(self):
        with patch("career_core.secret_store.SecretStore.read", side_effect=AssertionError("must not decrypt")) as read:
            context = self.tools.context({}, self.run_record())
            read.assert_not_called()
        self.assertEqual(3, context["company_overview"]["total"])
        self.assertEqual(3, len(context["stored_updates"]))
        rows = {row["id"]: row for row in context["company_overview"]["companies"]}
        self.assertEqual("not_configured", rows["missing"]["mypage_readiness"]["credential_state"])
        self.assertEqual("unknown", rows["missing"]["mypage_readiness"]["website_registration"])
        self.assertEqual("unknown", rows["saved"]["mypage_readiness"]["login_state"])
        self.assertEqual("needs_attention", rows["blocked"]["mypage_readiness"]["login_state"])
        self.assertNotIn("private-synthetic-ref", encoded(context))
        self.assertNotIn("secret_ref", encoded(context))

    def test_missing_setup_is_actionable_and_snapshot_exposes_same_state(self):
        rows = CompanyViews(self.store).overview()["companies"]
        missing = next(row for row in rows if row["id"] == "missing")
        self.assertTrue(missing["needs_action"])
        self.assertIn("設定", missing["next_action"])
        snapshot = self.coach.snapshot()["company_context"]
        self.assertEqual(3, snapshot["total"])
        self.assertEqual(missing["mypage_readiness"], next(row for row in snapshot["companies"] if row["id"] == "missing")["mypage_readiness"])
        other = CompanyViews(self.store).overview("other")["companies"]
        self.assertTrue(all(row["mypage_readiness"]["credential_state"] == "not_configured" for row in other))

    def test_unscoped_consultation_can_read_a_company_but_scoped_cannot_read_another(self):
        general = self.tools.context({"company_id": "blocked"}, self.run_record())
        self.assertEqual("blocked", general["company"]["company"]["id"])
        scoped = self.run_record(company="saved", identity="synthetic-scoped")
        with self.assertRaises(CareerError) as error:
            self.tools.context({"company_id": "blocked"}, scoped)
        self.assertEqual("read_scope_conflict", error.exception.code)
        context = self.tools.context({}, scoped)
        self.assertEqual(["saved"], [row["id"] for row in context["company_overview"]["companies"]])
        self.assertEqual(["saved"], [row["company_id"] for row in context["stored_updates"]])

    def test_directory_is_frozen_per_run_and_paged_with_total(self):
        run = self.run_record()
        first = self.tools.context({"limit": 1}, run)["company_overview"]
        self.assertEqual(3, first["total"])
        self.assertEqual(1, first["next_offset"])
        with self.store.transaction() as db:
            self.store.put(db, "companies", "later", 0, {"name": "Synthetic later"})
        self.assertEqual(3, self.tools.context({}, run)["company_overview"]["total"])
        self.assertEqual(4, self.tools.context({}, self.run_record(identity="next-run"))["company_overview"]["total"])

    def test_saved_mail_and_portal_results_reach_normal_consultation(self):
        with self.store.transaction() as db:
            self.mail.save_message(db, "synthetic-account", "saved", {
                "id": "synthetic-mail", "sender_address": "hr@example.test", "subject": "Synthetic next step",
                "text": "Please confirm the saved appointment", "locator": "https://mail.google.com/mail/#all/synthetic",
                "received_at": "2026-10-01T00:00:00Z"})
        identity = connection_id("saved", "default")
        self.bot.enqueue(identity)
        run = self.bot.claim(identity)
        self.bot.finish(run, "completed", items=[{"title": "Synthetic deadline", "quote": "締切 2026年11月1日",
                                                  "url": "https://portal.example.test/notice"}])
        context = self.tools.context({}, self.run_record())
        saved = next(row for row in context["stored_updates"] if row["company_id"] == "saved")
        self.assertEqual("synthetic-mail", saved["mail"][0]["message_id"])
        self.assertEqual("Synthetic deadline", saved["notices"][0]["title"])
        rows = {row["id"]: row for row in context["company_overview"]["companies"]}
        self.assertEqual("login_observed", rows["saved"]["mypage_readiness"]["website_registration"])

    def test_company_detail_does_not_expose_another_profile_portal_evidence(self):
        original = self.bot.get(connection_id("saved", "default"))
        other_id = connection_id("saved", "other")
        with self.store.transaction() as db:
            db.execute("INSERT INTO career_mypage_connections VALUES(?,?,?,?,?,?)",
                       (other_id, "saved", "other", 1, encoded(original["config"]), encoded({"state": "not_started"})))
        self.bot.enqueue(other_id)
        run = self.bot.claim(other_id)
        self.bot.finish(run, "completed", items=[{"title": "Other profile private notice", "quote": "Synthetic other profile body",
                                                  "url": "https://portal.example.test/notice"}])
        context = self.tools.context({"company_id": "saved"}, self.run_record())
        self.assertEqual([], context["company"]["evidence"])
        self.assertEqual([], context["company"]["mypage"]["notices"])
        self.assertNotIn("Other profile private notice", encoded(context))

    def test_legacy_portal_sources_follow_their_grant_profile(self):
        from unittest.mock import Mock
        from career_core.mypage import MyPageStore
        from career_core.mypage_reader import HOME
        from career_core.conversations import accepted_sources
        grant = self.tools.collection.configure_pilot(None, enabled=False)
        reader = Mock(fetch=Mock(return_value={"authenticated": True, "recognized": True, "notices": [
            {"key": "private", "title": "Legacy private source", "body": "Synthetic legacy body", "url": HOME}]}))
        MyPageStore(self.store).collect(grant["company_id"], reader=reader)
        own = CompanyViews(self.store).detail(grant["company_id"], "default")
        self.assertTrue(own["evidence"])
        foreign = CompanyViews(self.store).detail(grant["company_id"], "other")
        self.assertEqual([], foreign["evidence"])
        run = self.run_record(identity="foreign-legacy-run")
        run["profile"] = "other"
        context = self.tools.context({"company_id": grant["company_id"]}, run)
        self.assertEqual([], context["company"]["evidence"])
        with self.store.connect() as db, self.assertRaises(CareerError):
            accepted_sources(db, {"company_id": grant["company_id"]}, [{"kind": "mypage", "id": own["evidence"][0]["id"]}], "other")
        self.assertEqual([], self.tools.collection.notices(profile="other")["notices"])

    def test_document_paging_never_skips_the_five_document_body_window(self):
        with self.store.transaction() as db:
            for number in range(12):
                identity = f"DOC-{number:03}"
                path = f".jobhunt/documents/{identity}.md"
                self.store.put(db, "documents", identity, 0, {"id": identity, "title": identity, "path": path, "version": 1})
                db.execute("INSERT INTO document_files VALUES(?,?,?,?)", (path, "Synthetic body", "synthetic", 1))
        run = self.run_record()
        offset, seen = 0, set()
        while offset is not None:
            context = self.tools.context({"kind": "documents", "offset": offset}, run)
            seen.update(item["id"] for item in context["saved_documents"])
            offset = context["page"]["next_offset"]
        self.assertEqual({f"DOC-{number:03}" for number in range(12)}, seen)

    def test_runtime_receives_known_company_data_before_any_tool_call(self):
        accepted = self.coach.submit({"text": "次に何をすればいい？", "operation_id": "prime-context-synthetic"})
        run = self.coach.run(accepted["id"])
        prompts = []
        class Agent:
            def run_conversation(inner, text, **kwargs):
                return {"final_response": "synthetic answer", "messages": kwargs["conversation_history"] + [
                    {"role": "user", "content": text}, {"role": "assistant", "content": "synthetic answer"}]}
            def close(inner):
                pass
        def factory(session, prompt, **kwargs):
            prompts.append(prompt)
            return Agent()
        self.assertEqual("synthetic answer", HermesCoachRuntime(self.coach, factory).run(run))
        self.assertIn('"known_career_context"', prompts[0])
        self.assertIn('"credential_state":"not_configured"', prompts[0])
        self.assertNotIn("private-synthetic-ref", prompts[0])

    def test_runtime_mutating_the_supplied_history_cannot_erase_the_new_turn(self):
        full = [{"role": "user", "content": "old question"}, {"role": "assistant", "content": "x" * 100000},
                {"role": "user", "content": "recent question"}, {"role": "assistant", "content": "recent answer"}]
        self.coach.save_history("default", full)
        accepted = self.coach.submit({"text": "next question", "operation_id": "mutable-history-synthetic"})
        run = self.coach.run(accepted["id"])
        new = [{"role": "user", "content": "next question"}, {"role": "assistant", "content": "next answer"}]
        class Agent:
            def run_conversation(inner, text, **kwargs):
                messages = kwargs["conversation_history"]
                messages.extend(new)
                return {"final_response": "next answer", "messages": messages}
            def close(inner):
                pass
        HermesCoachRuntime(self.coach, lambda *args, **kwargs: Agent()).run(run)
        self.assertEqual(full + new, self.coach.history("default"))

    def test_unsaved_answer_context_is_retrievable_after_initial_preview_truncation(self):
        run = self.run_record(company="saved")
        answer = {"offer_id": "synthetic", "question_id": "question", "body": "current unsaved answer"}
        run["request"]["answer_context"] = answer
        self.assertEqual(answer, self.tools.context({}, run)["answer_context"])

    def test_more_than_twelve_short_turns_are_not_arbitrarily_discarded(self):
        history = []
        for number in range(20):
            history += [{"role": "user", "content": str(number)}, {"role": "assistant", "content": "answer"}]
        self.assertEqual(history, bounded_history(history))

    def test_oversized_tool_turn_keeps_prose_without_orphan_tool_calls(self):
        turn = [{"role": "user", "content": "keep this question"},
                {"role": "assistant", "content": None, "tool_calls": [{"id": "call", "type": "function"}]},
                {"role": "tool", "tool_call_id": "call", "content": "x" * 100000},
                {"role": "assistant", "content": "keep this answer"}]
        selected = bounded_history(turn)
        self.assertEqual(["user", "assistant"], [item["role"] for item in selected])
        self.assertEqual("keep this question", selected[0]["content"])
        self.assertEqual("keep this answer", selected[-1]["content"])

    def test_escaped_large_prose_still_retains_the_question_and_answer(self):
        history = [{"role": "user", "content": "u" * 20000},
                   {"role": "assistant", "content": "\\\\\n" * 30000}]
        selected = bounded_history(history)
        self.assertEqual(["user", "assistant"], [item["role"] for item in selected])
        self.assertTrue(all(item["content"] for item in selected))
        self.assertLessEqual(len(encoded(selected)), 90000)

    def test_large_legal_company_records_do_not_create_an_unbounded_initial_prompt(self):
        from career_core.company_context import initial_brief
        with self.store.transaction() as db:
            for number in range(10):
                self.store.put(db, "offers", f"synthetic-offer-{number}", 0,
                               {"company_id": "saved", "title": "Synthetic offer", "requirements": "x" * 200000})
        full = self.tools.context({}, self.run_record(company="saved"))
        brief = initial_brief(full)
        self.assertLessEqual(len(encoded(brief)), 24000)
        self.assertTrue(brief["preview_only"])
        self.assertFalse(brief["context_constraints"]["external_generation_allowed"])
        self.assertTrue(brief["context_constraints"]["requires_full_read"])

    def test_history_budget_reserves_system_request_tools_and_output_and_model_capacity(self):
        from types import SimpleNamespace
        agent = SimpleNamespace(context_window=32768, max_tokens=4096, tools=[])
        budget = history_budget(agent, "system" * 100, "user" * 100)
        self.assertLess(budget, 16384)
        with self.assertRaises(CareerError) as error:
            history_budget(agent, "x" * 16000, "y" * 20000)
        self.assertEqual("context_budget_exceeded", error.exception.code)

    def test_saving_bounded_agent_return_preserves_full_older_history(self):
        full = [{"role": "user", "content": "old"}, {"role": "assistant", "content": "old-answer"},
                {"role": "user", "content": "recent"}, {"role": "assistant", "content": "recent-answer"}]
        supplied = full[2:]
        new = [{"role": "user", "content": "next"}, {"role": "assistant", "content": "next-answer"}]
        self.assertEqual(full + new, retained_history(full, supplied, supplied + new))
        self.assertEqual(full + new, retained_history(full, supplied, new))


if __name__ == "__main__":
    unittest.main()
