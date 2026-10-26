"""Mounted by Hermes Dashboard behind its existing session middleware."""
import sys
from pathlib import Path

plugin_root = str(Path(__file__).resolve().parents[1])
if plugin_root not in sys.path:
    sys.path.insert(0, plugin_root)

from pc_power.api import create_router

router = create_router()
