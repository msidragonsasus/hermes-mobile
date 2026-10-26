"""Mounted only inside the authenticated Hermes Dashboard."""
import sys
from pathlib import Path

root = str(Path(__file__).resolve().parents[1])
if root not in sys.path:
    sys.path.insert(0, root)

from group_api import create_router

router = create_router()
