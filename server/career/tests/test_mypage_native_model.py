"""MyPage model isolation; no account or site access."""
from pathlib import Path
import tempfile
import unittest

from career_core.mypage_agent import selected_mypage_execution
from career_core.store import CareerError, Store


PUBLIC = {"provider": "openrouter", "model": "nvidia/nemotron-3-super-120b-a12b:free",
          "reasoning_effort": "default"}
PRIVATE = {"provider": "openai-codex", "model": "gpt-5.6-luna", "reasoning_effort": "high"}


class MyPageModelTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.store = Store(Path(temp.name) / 'career')

    def test_private_mypage_model_does_not_change_apps_automation(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, 'coach_daily:default', {'version': 2, 'execution': PUBLIC})
            self.store.set_meta(db, 'career_mypage_execution:default', PRIVATE)
        self.assertEqual(PRIVATE, selected_mypage_execution(self.store, 'default'))
        with self.store.connect() as db:
            self.assertEqual(PUBLIC, self.store.meta(db, 'coach_daily:default')['execution'])

    def test_public_model_without_override_is_rejected(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, 'coach_daily:default', {'version': 2, 'execution': PUBLIC})
        with self.assertRaises(CareerError) as caught:
            selected_mypage_execution(self.store, 'default')
        self.assertEqual('mypage_private_model_required', caught.exception.code)

    def test_override_is_profile_scoped(self):
        with self.store.transaction() as db:
            self.store.set_meta(db, 'career_mypage_execution:default', PRIVATE)
            self.store.set_meta(db, 'coach_daily:other', {'version': 1, 'execution': PUBLIC})
        with self.assertRaises(CareerError) as caught:
            selected_mypage_execution(self.store, 'other')
        self.assertEqual('mypage_private_model_required', caught.exception.code)


if __name__ == '__main__':
    unittest.main()
