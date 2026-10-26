"""Explicit synthetic account catalog: no network or real model calls in unit tests."""
import os
from unittest.mock import patch

from career_core.store import now

FIRST = {"provider": "openai-codex", "model": "test-codex-a", "reasoning_effort": "medium"}
SECOND = {"provider": "openai-codex", "model": "test-codex-b", "reasoning_effort": "high"}


def configure_execution(test):
    catalog = {"models": [{"provider": "openai-codex", "id": name, "label": name,
                            "reasoning_efforts": ["low", "medium", "high"],
                            "source": "account_catalog", "inference_verified": False}
                           for name in (FIRST["model"], SECOND["model"])],
               "checked_at": now(), "state": "available"}
    for fixture in (patch.dict(os.environ, {"CAREER_CODEX_MODEL": FIRST["model"], "CAREER_CODEX_REASONING": "medium"}),
                    patch("career_core.coach_execution.discover_models", return_value=catalog),
                    patch("career_core.coach_execution.wire_effort", side_effect=lambda model, effort: effort)):
        fixture.start()
        test.addCleanup(fixture.stop)
