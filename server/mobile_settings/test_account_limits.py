from datetime import datetime, timezone
from types import SimpleNamespace
import unittest

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

from mobile_account_limits import create_router, fetch_configured, project
from prepare_install import patch


def sample(**overrides):
    values = dict(provider="openai-codex", plan="Plus", fetched_at=datetime.now(timezone.utc),
                  windows=(SimpleNamespace(label="Weekly", used_percent=25, reset_at=datetime.now(timezone.utc)),),
                  raw={"secret": "never-forward"}, details=("private",), identity="private-account")
    return SimpleNamespace(**(values | overrides))


class Backend:
    def __init__(self):
        self.profiles = []

    def authorize(self, request: Request):
        if request.headers.get("authorization") != "Bearer fixture":
            raise HTTPException(401)

    def read(self, profile):
        self.profiles.append(profile)
        if profile == "failed":
            raise ValueError("private-provider-error")
        if profile == "missing":
            raise HTTPException(404)
        return project(sample(), "openai-codex")


class AccountLimitsTests(unittest.TestCase):
    def test_projection_excludes_provider_payloads_and_identity(self):
        result = project(sample(), "openai-codex")
        self.assertTrue(result["available"])
        self.assertEqual(25.0, result["windows"][0]["used_percent"])
        self.assertNotIn("private", str(result))
        self.assertNotIn("never-forward", str(result))
        self.assertEqual({"available", "provider", "plan", "fetched_at", "windows"}, set(result))

    def test_unknown_percent_never_becomes_zero_and_bad_sample_is_unavailable(self):
        for used in (None, float("nan"), float("inf"), -1, 101, True, "20"):
            window = SimpleNamespace(label="Session", used_percent=used, reset_at=None)
            result = project(sample(windows=(window,)), "openai-codex")
            self.assertIsNone(result["windows"][0]["used_percent"])
        self.assertFalse(project(sample(provider="anthropic"), "openai-codex")["available"])
        self.assertFalse(project(sample(fetched_at=None), "openai-codex")["available"])
        self.assertFalse(project(sample(unavailable_reason="private-error"), "openai-codex")["available"])

    def test_only_explicit_supported_provider_and_readonly_native_contract_are_called(self):
        calls = []

        def fetcher(provider, *, read_only=False):
            calls.append((provider, read_only))
            return sample(provider=provider)

        for config in ({}, {"model": "gpt"}, {"model": {"provider": "auto"}}, {"model": {"provider": "custom"}}):
            self.assertFalse(fetch_configured(config, fetcher)["available"])
        self.assertEqual([], calls)
        self.assertTrue(fetch_configured({"model": {"provider": "openai-codex"}}, fetcher)["available"])
        self.assertEqual([("openai-codex", True)], calls)
        self.assertFalse(fetch_configured({"model": {"provider": "openai-codex"}}, lambda p: sample())["available"])

    def test_authentication_precedes_read_and_profile_scope_is_explicit(self):
        backend = Backend()
        app = FastAPI()
        app.include_router(create_router(backend))
        with TestClient(app) as client:
            url = "/api/mobile-settings/account-limits"
            self.assertEqual(401, client.get(url).status_code)
            self.assertEqual([], backend.profiles)
            result = client.get(url, params={"profile": "research"}, headers={"Authorization": "Bearer fixture"})
            self.assertEqual(200, result.status_code)
            self.assertEqual(["research"], backend.profiles)
            failed = client.get(url, params={"profile": "failed"}, headers={"Authorization": "Bearer fixture"})
            self.assertFalse(failed.json()["available"])
            self.assertNotIn("private-provider-error", failed.text)
            self.assertEqual(404, client.get(url, params={"profile": "missing"}, headers={"Authorization": "Bearer fixture"}).status_code)

    def test_install_patch_preserves_newlines_and_is_idempotent(self):
        for newline in ("\n", "\r\n"):
            source = newline.join(("from fastapi import APIRouter", "router = APIRouter()", "UNRELATED = 1", ""))
            candidate = patch(source)
            self.assertEqual(candidate, patch(candidate))
            self.assertIn("UNRELATED = 1" + newline, candidate)
            self.assertEqual(source.count("\r\n") + (2 if newline == "\r\n" else 0), candidate.count("\r\n"))
        with self.assertRaises(ValueError):
            patch("router = something_else()")


if __name__ == "__main__":
    unittest.main()
