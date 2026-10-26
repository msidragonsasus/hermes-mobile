import sys
import tempfile
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from group_api import create_router
from group_service import GroupService
from test_group_service import Runtime, request


class GroupApiTest(unittest.TestCase):
    def test_title_metadata_is_authenticated_profile_and_group_scoped(self):
        response = self.client.post(self.root + "/one/messages?profile=owner", json=request())
        payload = dict(groupName="開発会議", title="設計方針の比較", expectedTitle=response.json()["title"])
        self.assertEqual(404, self.client.patch(self.root + "/one?profile=other", json=payload).status_code)
        self.assertEqual(404, self.client.patch(self.root + "/one?profile=owner", json=payload | {"groupName": "other"}).status_code)
        renamed = self.client.patch(self.root + "/one?profile=owner", json=payload)
        self.assertEqual(200, renamed.status_code)
        self.assertEqual("設計方針の比較", renamed.json()["title"])
        self.assertEqual(200, self.client.patch(self.root + "/one?profile=owner", json=payload).status_code)
        self.assertEqual(409, self.client.patch(self.root + "/one?profile=owner", json=payload | {"title": "stale result"}).status_code)
        self.assertEqual([], self.client.get(self.root + "?profile=other").json()["conversations"])
        self.authenticated = False
        self.assertEqual(401, self.client.patch(self.root + "/one?profile=owner", json=payload).status_code)

    def test_title_metadata_rejects_cross_origin_and_invalid_payload(self):
        self.assertEqual(403, self.client.patch(self.root + "/one", json={}, headers={"origin": "https://other.test"}).status_code)
        self.assertEqual(422, self.client.patch(self.root + "/one", content="{").status_code)
        self.assertEqual(422, self.client.patch(self.root + "/one", json=[]).status_code)
        self.assertEqual({}, self.services)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.services = {}
        self.authenticated = True
        app = FastAPI()
        app.state.auth_required = True

        @app.middleware("http")
        async def session(request, call_next):
            if self.authenticated:
                request.state.session = {"id": "synthetic"}
            return await call_next(request)

        def factory(profile):
            value = GroupService(Path(self.temp.name) / profile / "history.db", Runtime(), start=False)
            self.services[profile] = value
            return value

        app.include_router(create_router(factory), prefix="/api/plugins/group_chat")
        self.client = TestClient(app)
        self.root = "/api/plugins/group_chat/conversations"

    def tearDown(self):
        self.client.close()
        for value in self.services.values():
            value.close()
        self.temp.cleanup()

    def test_requires_parent_dashboard_session(self):
        self.authenticated = False
        self.assertEqual(401, self.client.get(self.root).status_code)
        self.assertEqual({}, self.services)

    def test_cross_origin_write_is_rejected_before_submission(self):
        response = self.client.post(self.root + "/one/messages", json=request(), headers={"origin": "https://other.test"})
        self.assertEqual(403, response.status_code)
        self.assertEqual({}, self.services)

    def test_response_disconnect_does_not_own_discussion_and_profiles_stay_separate(self):
        response = self.client.post(self.root + "/one/messages?profile=owner", json=request())
        self.assertEqual(200, response.status_code)
        self.assertEqual("queued", response.json()["state"])
        # The response has ended. The next member still runs on the server.
        self.services["owner"].run_one()
        result = self.client.get(self.root + "/one", params={"profile": "owner", "group": "開発会議"})
        self.assertEqual("complete", result.json()["state"])
        self.assertEqual(6, len(result.json()["messages"]))
        other = self.client.get(self.root, params={"profile": "other"})
        self.assertEqual([], other.json()["conversations"])

    def test_invalid_json_and_profile_do_not_create_work(self):
        self.assertEqual(422, self.client.post(self.root + "/one/messages", content="{").status_code)
        self.assertEqual(422, self.client.get(self.root, params={"profile": "../secret"}).status_code)
        self.assertEqual({}, self.services)


if __name__ == "__main__":
    unittest.main()
