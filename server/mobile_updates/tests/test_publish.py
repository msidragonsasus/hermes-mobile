import contextlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from apk_fixtures import binary_manifest, build_apk, digest
from store_fixtures import APP_ID, CERT, make_root, write_release
from updates_core.publish import PublishError, main, publish
from updates_core.store import ReleaseStore, config_from_environment


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()
        self.work = self.root.parent / "work"
        self.work.mkdir()
        self.apk_bytes = build_apk(binary_manifest(version_code=3, version_name="1.0.3"))
        self.apk = self.work / "app-debug.apk"
        self.apk.write_bytes(self.apk_bytes)
        self.metadata = self.work / "release-input.json"
        self.write_metadata()

    def tearDown(self):
        self.holder.cleanup()

    def write_metadata(self, **overrides):
        value = {"application_id": APP_ID, "version_code": 3, "version_name": "1.0.3",
                 "signer_sha256": [digest(CERT)], "notes": "Fix login"}
        value.update(overrides)
        value = {k: v for k, v in value.items() if v is not None}
        self.metadata.write_text(json.dumps(value), encoding="utf-8")

    def store(self):
        return ReleaseStore(config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root)}))

    def entries(self):
        releases = self.root / "releases"
        return sorted(os.listdir(releases)) if releases.exists() else []

    def test_publish_copies_into_a_new_immutable_release_directory(self):
        with mock.patch("subprocess.Popen", side_effect=AssertionError("no shell")), \
                mock.patch("os.system", side_effect=AssertionError("no shell")):
            result = publish(self.root, self.apk, self.metadata, now="2026-10-27T01:02:03Z")
        release_id = f"v3-{digest(self.apk_bytes)[:16]}"
        self.assertEqual(release_id, result["release_id"])
        self.assertEqual([release_id], self.entries())
        self.assertEqual(result, self.store().latest())
        target = self.root / "releases" / release_id
        self.assertEqual(self.apk_bytes, (target / "app.apk").read_bytes())
        for name in ("app.apk", "release.json"):
            self.assertEqual(0, stat.S_IMODE(os.lstat(target / name).st_mode) & 0o222)
        self.assertEqual("2026-10-27T01:02:03Z", result["published_at"])
        self.assertTrue(self.apk.exists())

    def test_supplied_hash_and_size_must_match_the_apk(self):
        self.write_metadata(sha256=digest(self.apk_bytes), size_bytes=len(self.apk_bytes))
        publish(self.root, self.apk, self.metadata)
        self.assertEqual(1, len(self.entries()))

    def test_mismatching_or_unsafe_inputs_publish_nothing(self):
        outside = self.work / "link.apk"
        outside.symlink_to(self.apk)
        cases = [
            ({"version_code": 4}, self.apk, "metadata_apk_mismatch"),
            ({"version_name": "1.0.4"}, self.apk, "metadata_apk_mismatch"),
            ({"application_id": "com.example.other"}, self.apk, "application_id_mismatch"),
            ({"signer_sha256": ["0" * 64]}, self.apk, "metadata_apk_mismatch"),
            ({"sha256": "0" * 64}, self.apk, "sha256_mismatch"),
            ({"size_bytes": 1}, self.apk, "size_mismatch"),
            ({"unexpected": True}, self.apk, "metadata_invalid"),
            ({"notes": 5}, self.apk, "metadata_invalid"),
            ({}, outside, "apk_not_regular_file"),
            ({}, self.work / "missing.apk", "apk_not_regular_file"),
        ]
        for overrides, apk, code in cases:
            self.write_metadata(**overrides)
            with self.subTest(code=code, overrides=overrides), self.assertRaises(PublishError) as raised:
                publish(self.root, apk, self.metadata)
            self.assertEqual(code, raised.exception.code)
            self.assertEqual([], self.entries())

    def test_unsigned_apk_is_refused(self):
        self.apk.write_bytes(build_apk(binary_manifest(version_code=3, version_name="1.0.3"),
                                       with_signing_block=False))
        with self.assertRaises(PublishError) as raised:
            publish(self.root, self.apk, self.metadata)
        self.assertEqual("apk_signature_block_missing", raised.exception.code)
        self.assertEqual([], self.entries())

    def test_version_code_must_increase_and_releases_are_never_replaced(self):
        write_release(self.root, 3, version_name="1.0.3-old")
        with self.assertRaises(PublishError) as raised:
            publish(self.root, self.apk, self.metadata)
        self.assertEqual("version_code_not_increasing", raised.exception.code)
        self.assertEqual(1, len(self.entries()))

    def test_dry_run_validates_without_writing(self):
        result = publish(self.root, self.apk, self.metadata, dry_run=True)
        self.assertEqual(3, result["version_code"])
        self.assertEqual([], self.entries())

    def test_unsafe_root_is_refused(self):
        os.chmod(self.root, 0o777)
        with self.assertRaises(PublishError) as raised:
            publish(self.root, self.apk, self.metadata)
        self.assertEqual("updates_dir_writable_by_others", raised.exception.code)
        os.chmod(self.root, 0o750)

    def test_cli_prints_metadata_or_error_code(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(0, main(["--root", str(self.root), "--apk", str(self.apk),
                                      "--metadata", str(self.metadata)]))
            self.assertEqual(2, main(["--root", str(self.root), "--apk", str(self.apk),
                                      "--metadata", str(self.metadata)]))
        self.assertEqual(3, json.loads(out.getvalue())["version_code"])
        self.assertIn("version_code_not_increasing", err.getvalue())


if __name__ == "__main__":
    unittest.main()
