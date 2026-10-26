"""Loaded by Hermes Dashboard's plugin API mount, behind its cookie middleware."""
import sys
from pathlib import Path

# Hermes loads the dashboard entry by filename, outside the plugin package.
plugin_root = str(Path(__file__).resolve().parents[1])
if plugin_root not in sys.path:
    sys.path.insert(0, plugin_root)

from career_core.deployment import attach, prepare

deployment = prepare(plugin_root)

from career_core.api import create_router

router = create_router(run_worker=True, browser_consent=True)
attach(router, plugin_root, deployment)
