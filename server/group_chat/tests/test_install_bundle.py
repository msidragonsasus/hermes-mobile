import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("group_chat_installer", Path(__file__).resolve().parents[1] / "tools/install_server.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def bundle(extra=None):
    files = {"group_chat/" + name: b"synthetic" for name in [
        "group_service.py", "group_api.py", "dashboard/plugin_api.py", "dashboard/manifest.json", "plugin.yaml"]}
    files.update(extra or {})
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr("RELEASE.json", json.dumps({
            "version": "0.1.0", "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}))
    return output.getvalue()


class InstallBundleTest(unittest.TestCase):
    def test_accepts_only_pinned_complete_package(self):
        data = bundle()
        self.assertEqual(5, len(installer.read_bundle(data, hashlib.sha256(data).hexdigest())))

    def test_rejects_changed_archive_before_installation(self):
        with self.assertRaises(RuntimeError):
            installer.read_bundle(bundle(), "0" * 64)

    def test_refuses_path_traversal(self):
        data = bundle({"group_chat/../../config.yaml": b"synthetic"})
        with self.assertRaises(RuntimeError):
            installer.read_bundle(data, hashlib.sha256(data).hexdigest())

    def test_refuses_unexpected_runtime_data(self):
        data = bundle({"group_chat/history.db": b"synthetic"})
        with self.assertRaises(RuntimeError):
            installer.read_bundle(data, hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    unittest.main()
