"""Packaging only: synthetic files and private-name sentinels in a temporary tree."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("career_release_package", Path(__file__).resolve().parents[1] / "tools/package_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PackageTests(unittest.TestCase):
    def test_real_release_contains_mobile_host_api_and_app_payload_dependencies(self):
        career = Path(__file__).resolve().parents[1]
        names = set(json.loads((career / "tools/release_files.json").read_text(encoding="utf-8"))["files"])
        self.assertTrue({"server/career/career_core/mobile_contract.py", "server/career/career_core/mobile_sdui.py",
                         "server/career/app_runtime/protocol.json", "server/career/app_runtime/mobile_screen.json",
                         "server/career/app_runtime/mypage_handler.py", "server/career/tools/app_release.py",
                         "server/career/tests/test_mobile_sdui.py", "docs/phase3/IMPLEMENTATION.md"} <= names)

    def test_real_release_contains_mail_and_settings_dependencies(self):
        career = Path(__file__).resolve().parents[1]
        names = set(json.loads((career / "tools/release_files.json").read_text(encoding="utf-8"))["files"])
        self.assertTrue({"server/career/career_core/mail_store.py", "server/career/career_core/mail_collector.py",
                         "server/career/career_core/coach_app.py", "server/career/tests/test_coach_app.py",
                         "server/career/career_core/mypage_request.py", "server/career/tests/test_coach_mypage_connection.py",
                         "server/career/career_core/home.py", "server/career/tests/test_home.py",
                         "server/career/career_core/notice_policy.py", "server/career/tests/test_notice_policy.py",
                         "server/career/career_core/threads.py", "server/career/tests/test_threads.py",
                         "server/career/career_core/mail_worker.py", "server/career/tests/test_settings_import.py",
                         "server/career/tests/test_mail_collection.py", "server/career/tests/test_mail_sync.py",
                         "server/career/career_core/mail_sync.py", "server/career/career_core/mail_transport.py",
                         "server/career/career_core/mail_context.py", "server/career/tools/benchmark_mail.py",
                         "server/career/tools/apply_mail_tuning.py"} <= names)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.career = self.root / "server/career"
        (self.career / "tools").mkdir(parents=True)
        (self.career / "dashboard").mkdir()
        (self.career / "plugin.yaml").write_text("version: 0.4.0\n", encoding="utf-8")
        (self.career / "dashboard/manifest.json").write_text('{"version":"0.4.0"}', encoding="utf-8")
        self.names = ["server/career/plugin.yaml", "server/career/dashboard/manifest.json", "server/career/tools/release_files.json"]
        self.allowlist = self.career / "tools/release_files.json"
        self.allowlist.write_text(json.dumps({"files": self.names}), encoding="utf-8")

    def test_reproducible_archive_excludes_unlisted_secrets_and_source_files(self):
        for name in ("google_token.json", "secret.env", "career.sqlite3", "private-note.md", "unexpected.py"):
            (self.career / "tools" / name).write_text("synthetic-do-not-package", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            first = release.package(self.root / "first.zip", self.root)
            second = release.package(self.root / "second.zip", self.root)
        self.assertEqual(first, second)
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertEqual(set(self.names) | {"RELEASE.json"}, set(archive.namelist()))
            self.assertNotIn(b"synthetic-do-not-package", b"".join(archive.read(n) for n in archive.namelist()))

    def test_output_is_not_overwritten_and_escaping_paths_are_rejected(self):
        output = self.root / "existing.zip"
        output.write_bytes(b"preserve")
        with self.assertRaises(FileExistsError), contextlib.redirect_stdout(io.StringIO()):
            release.package(output, self.root)
        self.assertEqual(b"preserve", output.read_bytes())
        self.allowlist.write_text(json.dumps({"files": ["../outside"]}), encoding="utf-8")
        with self.assertRaises(ValueError):
            release.package(self.root / "invalid.zip", self.root)
        self.assertFalse((self.root / "invalid.zip").exists())


if __name__ == "__main__":
    unittest.main()
