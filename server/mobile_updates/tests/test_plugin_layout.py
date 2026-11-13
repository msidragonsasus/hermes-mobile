import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PluginLayoutTests(unittest.TestCase):
    def test_dashboard_manifest_mounts_read_only_router_at_mobile_updates(self):
        manifest = json.loads((ROOT / "dashboard" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("mobile_updates", manifest["name"])
        self.assertEqual("plugin_api.py", manifest["api"])
        self.assertTrue(manifest["tab"]["hidden"])
        module = load(ROOT / "dashboard" / "plugin_api.py", "mobile_updates_plugin_api")
        routes = {(route.path, tuple(sorted(route.methods))) for route in module.router.routes}
        self.assertEqual({("/status", ("GET",)), ("/latest", ("GET",)),
                          ("/releases/{release_id}/apk", ("GET",))}, routes)

    def test_plugin_registers_no_agent_tools_or_hooks(self):
        sys.path.insert(0, str(ROOT.parent))
        try:
            module = load(ROOT / "__init__.py", "mobile_updates_plugin")
        finally:
            sys.path.remove(str(ROOT.parent))
        calls = []
        context = type("Context", (), {"__getattr__": lambda self, name: lambda *a, **k: calls.append(name)})()
        self.assertIsNone(module.register(context))
        self.assertEqual([], calls)
        self.assertIn("name: mobile_updates", (ROOT / "plugin.yaml").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
