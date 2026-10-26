import unittest
from types import SimpleNamespace
from unittest import mock

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from pc_power.api import create_router


class PowerStatusApiTests(unittest.TestCase):
    def client(self, controller):
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def test_session(request: Request, call_next):
            request.state.session = {"test": True}
            return await call_next(request)

        config = SimpleNamespace(target_name="test-desktop")
        app.include_router(create_router(lambda: config, lambda _: controller))
        return TestClient(app)

    def test_authenticated_receiver_enables_sleep_when_ping_is_blocked(self):
        controller = mock.Mock()
        controller.status.return_value = "asleep_or_unreachable"
        controller.receiver_health.return_value = {"ready": True}
        with self.client(controller) as client:
            result = client.get("/status")
        self.assertEqual(200, result.status_code)
        self.assertEqual("awake", result.json()["power_state"])
        self.assertTrue(result.json()["sleep_available"])

    def test_failure_and_missing_privilege_are_visible_without_exposing_native_details(self):
        controller = mock.Mock()
        controller.status.return_value = "awake"
        for health, expected, available in (
            ({"ready": False, "error": "shutdown_privilege_missing"}, "shutdown_privilege_missing", False),
            ({"ready": True, "last_operation": {"state": "failed", "native_error": 1314}}, "sleep_failed", True),
            ({"ready": False}, None, False),
        ):
            controller.receiver_health.return_value = health
            with self.client(controller) as client:
                result = client.get("/status").json()
            self.assertEqual(expected, result["sleep_error"])
            self.assertEqual(available, result["sleep_available"])
            self.assertNotIn("native_error", result)
