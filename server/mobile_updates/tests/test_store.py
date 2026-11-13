import json
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from apk_fixtures import binary_manifest, build_apk, digest
from store_fixtures import APP_ID, make_root, write_release
from updates_core.store import ConfigurationError, ReleaseStore, StoreError, config_from_environment

PUBLIC_KEYS = {"release_id", "application_id", "version_code", "version_name", "sha256", "size_bytes",
               "signer_sha256", "apk_path", "notes", "published_at"}


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()

    def tearDown(self):
        self.holder.cleanup()

    def test_disabled_unless_operator_configures_a_directory(self):
        self.assertIsNone(config_from_environment({}))
        self.assertIsNone(config_from_environment({"HERMES_MOBILE_UPDATES_DIR": "  "}))
        config = config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root)})
        self.assertEqual(self.root, config.root)
        self.assertEqual(APP_ID, config.application_id)

    def test_unsafe_directories_are_rejected(self):
        link = self.root.parent / "link"
        link.symlink_to(self.root, target_is_directory=True)
        open_dir = self.root.parent / "open"
        open_dir.mkdir()
        os.chmod(open_dir, 0o777)
        for value in ["relative/path", str(self.root / "missing"), str(link), str(open_dir)]:
            with self.subTest(value=value), self.assertRaises(ConfigurationError):
                config_from_environment({"HERMES_MOBILE_UPDATES_DIR": value})

    def test_application_id_must_be_a_package_name(self):
        with self.assertRaises(ConfigurationError):
            config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root),
                                     "HERMES_MOBILE_UPDATES_APPLICATION_ID": "../evil"})


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.holder, self.root = make_root()
        self.store = ReleaseStore(config_from_environment({"HERMES_MOBILE_UPDATES_DIR": str(self.root)}))

    def tearDown(self):
        self.holder.cleanup()

    def test_no_release_directory_means_nothing_published(self):
        self.assertIsNone(self.store.latest())
        (self.root / "releases").mkdir()
        self.assertIsNone(self.store.latest())

    def test_latest_is_highest_valid_version_with_public_shape(self):
        write_release(self.root, 2)
        _, record, apk = write_release(self.root, 5)
        write_release(self.root, 3)
        latest = self.store.latest()
        self.assertEqual(PUBLIC_KEYS, set(latest))
        self.assertEqual(5, latest["version_code"])
        self.assertEqual(digest(apk), latest["sha256"])
        self.assertEqual(f"releases/{record['release_id']}/apk", latest["apk_path"])
        self.assertNotIn(str(self.root), json.dumps(latest))

    def test_invalid_releases_are_never_advertised(self):
        write_release(self.root, 2)
        bad = []
        target, _, _ = write_release(self.root, 10)
        (target / "app.apk").write_bytes(b"tampered")
        bad.append(target)
        target, _, _ = write_release(self.root, 11)
        with open(target / "app.apk", "ab") as handle:
            handle.write(b"x")
        target, _, apk = write_release(self.root, 12)
        (target / "app.apk").unlink()
        outside = self.root.parent / "outside.apk"
        outside.write_bytes(apk)
        (target / "app.apk").symlink_to(outside)
        write_release(self.root, 13, directory="v13-0000000000000000")
        write_release(self.root, 14, application_id="com.example.other")
        write_release(self.root, 15, unexpected="field")
        write_release(self.root, 16, size_bytes=1)
        write_release(self.root, 17, signer_sha256=[])
        write_release(self.root, 18, apk=build_apk(binary_manifest(version_code=99)))
        write_release(self.root, 22, release_id="v22-0000000000000000", directory="v22-0000000000000000")
        write_release(self.root, 23, apk=build_apk(binary_manifest(version_code=23), certificates=[b"other"]))
        target, _, _ = write_release(self.root, 19)
        (target / "extra.sh").write_text("echo")
        target, _, _ = write_release(self.root, 20)
        (target / "release.json").write_text("{not json")
        real, record, _ = write_release(self.root, 21, directory="hidden")
        (self.root / "releases" / record["release_id"]).symlink_to(real, target_is_directory=True)
        with self.assertLogs("hermes.mobile_updates", "WARNING"):
            latest = self.store.latest()
        self.assertEqual(2, latest["version_code"])

    def test_duplicate_version_codes_fail_closed(self):
        write_release(self.root, 4)
        write_release(self.root, 4, version_name="1.0.4-other", apk=None, notes="different apk")
        with self.assertRaises(StoreError) as raised:
            self.store.latest()
        self.assertEqual("ambiguous_release", raised.exception.code)

    def test_open_apk_rejects_traversal_and_unknown_ids_without_opening_paths(self):
        write_release(self.root, 2)
        for release_id in ["../releases", "v2-..", "v2-ABCDEF0123456789", "v0-0123456789abcdef",
                           "v2-0123456789abcdef/../../x", "v2-0123456789abcdef"]:
            with self.subTest(release_id=release_id), self.assertRaises(StoreError) as raised:
                self.store.open_apk(release_id)
            self.assertEqual(404, raised.exception.status)

    def test_open_apk_returns_verified_bytes_and_detects_tampering(self):
        target, record, apk = write_release(self.root, 2)
        handle, meta = self.store.open_apk(record["release_id"])
        with handle:
            self.assertEqual(apk, handle.read())
        self.assertEqual(record["sha256"], meta["sha256"])
        (target / "app.apk").write_bytes(apk[:-1] + bytes([apk[-1] ^ 1]))
        with self.assertRaises(StoreError) as raised:
            self.store.open_apk(record["release_id"])
        self.assertEqual("release_integrity_failed", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
