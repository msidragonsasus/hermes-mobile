"""Native Hermes tools, alongside the existing Dashboard API."""
import sys
from pathlib import Path

root = str(Path(__file__).resolve().parent)
if root not in sys.path:
    sys.path.insert(0, root)


def register(ctx):
    from group_agent_tools import register_tools
    register_tools(ctx)
