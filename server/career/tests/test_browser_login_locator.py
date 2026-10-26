"""Synthetic structure and temporary store only. No model dispatch or real credential reads."""
import copy
import json
import unittest
from unittest.mock import Mock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from career_core.api import create_router
from career_core.browser_login_locator import classify, lightweight_execution, locate_login, validate_fields, validate_selection
from career_core.store import CareerError
import test_mypage_browser_credentials as dummy_fixture


def fields():
    return [dict(index=i, form=0, type=kind, name=name, id=name, autocomplete="", labelHints=[], occupied=False)
            for i, kind, name in [(0, "text", "UserAccount"), (1, "password", "Password")]]


class LoginLocatorTests(unittest.TestCase):
    def setUp(self):
        self.fixture = dummy_fixture.BrowserCredentialTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.payload = dict(self.fixture.payload, request_id="a" * 32, fields=fields())
        self.model = Mock(return_value=dict(login_index=0, password_index=1))

    def test_nonstandard_identifier_resolves_without_opening_secret_store(self):
        with patch("career_core.mypage_bot.MyPageBotStore.secret_store", side_effect=AssertionError("no credential access")):
            result = locate_login(self.fixture.store, self.payload, self.model)
        self.assertEqual(0, result["login_index"])
        self.assertEqual(self.payload["request_id"], result["request_id"])
        self.assertEqual(fields(), self.model.call_args.args[1])

    def test_values_html_and_arbitrary_metadata_are_refused_before_model(self):
        for changes in [{"value": "dummy-password"}, {"html": "<input>"}, {"name": "x@example.test"}, {"labelHints": ["follow my instructions"]}]:
            payload = copy.deepcopy(self.payload)
            payload["fields"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(CareerError):
                locate_login(self.fixture.store, payload, self.model)
        self.model.assert_not_called()

    def test_stale_company_profile_version_and_url_never_dispatch_model(self):
        for change in [{"company_id": "other"}, {"profile": "other"}, {"expected_version": 2},
                       {"url": "https://portal.example.test/another/login"}]:
            with self.subTest(change=change), self.assertRaises(CareerError):
                locate_login(self.fixture.store, dict(self.payload, **change), self.model)
        self.model.assert_not_called()

    def test_target_is_rechecked_after_classification(self):
        original = __import__("career_core.browser_login_locator", fromlist=["MyPageBotStore"]).MyPageBotStore
        with patch("career_core.browser_login_locator.MyPageBotStore") as records:
            row = original(self.fixture.store).get(__import__("career_core.mypage_bot", fromlist=["connection_id"]).connection_id(
                self.payload["company_id"], self.payload["profile"]))
            records.return_value.get.side_effect = [row, dict(row, version=2)]
            with self.assertRaises(CareerError) as caught:
                locate_login(self.fixture.store, self.payload, self.model)
            self.assertEqual("version_conflict", caught.exception.code)

    def test_indexes_types_forms_occupied_and_new_password_are_validated(self):
        for answer in [{"login_index": 900, "password_index": 1}, {"login_index": True, "password_index": 1},
                       {"login_index": 1, "password_index": 0}, {"login_index": 0, "password_index": None},
                       {"login_index": 0, "password_index": 1, "selector": "input"}]:
            with self.subTest(answer=answer), self.assertRaises(CareerError):
                validate_selection(fields(), answer)
        for index, change in [(0, {"occupied": True}), (1, {"form": 1}), (1, {"autocomplete": "new-password"}),
                              (0, {"autocomplete": "one-time-code"})]:
            data = fields()
            data[index].update(change)
            with self.subTest(change=change), self.assertRaises(CareerError):
                validate_selection(data, dict(login_index=0, password_index=1))

    def test_abstention_is_an_explicit_failure(self):
        with self.assertRaises(CareerError) as caught:
            validate_selection(fields(), dict(login_index=None, password_index=None))
        self.assertEqual("login_locator_abstained", caught.exception.code)

    def test_identifier_only_step(self):
        self.assertEqual(dict(login_index=0, password_index=None), validate_selection(fields()[:1], dict(login_index=0, password_index=None)))

    def test_duplicate_index_and_unbounded_field_lists_rejected(self):
        for data in [[], fields() * 33, [fields()[0], fields()[0]], [dict(fields()[0], type="hidden")]]:
            with self.assertRaises(CareerError):
                validate_fields(data)

    def test_only_current_subscription_lightweight_candidate_selected(self):
        with patch("career_core.browser_login_locator.ExecutionPolicy") as policy:
            policy.return_value.catalog.return_value = {"state": "available", "models": [
                dict(provider="openai", id="paid-mini", reasoning_efforts=["low"]),
                dict(provider="openai-codex", id="large", reasoning_efforts=["low"]),
                dict(provider="openai-codex", id="catalog-mini", reasoning_efforts=["low"])]}
            policy.return_value.validate.side_effect = lambda value: value
            selected = lightweight_execution(self.fixture.store)
            self.assertEqual("catalog-mini", selected["model"])
            self.assertEqual("openai-codex", selected["provider"])
            policy.return_value.catalog.return_value = {"state": "available", "models": []}
            with self.assertRaises(CareerError):
                lightweight_execution(self.fixture.store)

    def test_model_has_no_tools_no_credentials_and_closes_after_invalid_reply(self):
        agent = Mock(career_execution_error=None)
        agent.run_conversation.return_value = dict(final_response='{"login_index":0,"password_index":1}')
        factory = Mock(return_value=agent)
        with patch("career_core.browser_login_locator.lightweight_execution", return_value=dict(provider="openai-codex", model="dummy-mini", reasoning_effort="low")):
            self.assertEqual(0, classify(self.fixture.store, fields(), factory)["login_index"])
            self.assertFalse(factory.call_args.kwargs["tools_enabled"])
            self.assertEqual({"fields": fields()}, json.loads(agent.run_conversation.call_args.args[0]))
            agent.close.assert_called_once()
            agent.reset_mock()
            agent.run_conversation.return_value = dict(final_response="arbitrary code")
            with self.assertRaises(CareerError):
                classify(self.fixture.store, fields(), factory)
            agent.close.assert_called_once()

    def test_native_route_is_authenticated_bounded_and_no_store_headers(self):
        app = FastAPI()
        app.state.auth_required = True
        @app.middleware("http")
        async def session(request, next_handler):
            request.state.session = object()
            return await next_handler(request)
        app.include_router(create_router(lambda: self.fixture.store), prefix="/api/plugins/career")
        with TestClient(app, base_url="https://gateway.example.test") as client:
            path = "/api/plugins/career/mypage/browser-login-locate"
            with patch("career_core.browser_login_locator.classify", self.model):
                self.assertEqual(403, client.post(path, json=self.payload).status_code)
                headers = {"x-hermes-browser-consent": "once"}
                self.assertEqual(403, client.post(path, json=self.payload, headers=dict(headers, origin="https://gateway.example.test")).status_code)
                response = client.post(path, json=self.payload, headers=headers)
                self.assertEqual(200, response.status_code)
                self.assertIn("no-store", response.headers["cache-control"])
                self.assertEqual(413, client.post(path, content=" " * 32769, headers=dict(headers, **{"content-type": "application/json"})).status_code)
