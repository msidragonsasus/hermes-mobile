from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from apk_fixtures import binary_manifest, build_apk, digest
from updates_core.apk import ApkError, inspect_apk

CERT = b"fake-certificate-der"


class ApkInspectionTests(unittest.TestCase):
    def test_reads_manifest_identity_and_v2_signer_digest(self):
        for utf8 in (True, False):
            with self.subTest(utf8=utf8):
                apk = build_apk(binary_manifest(version_code=7, version_name="1.0.7", utf8=utf8))
                identity = inspect_apk(apk)
                self.assertEqual("com.m57.hermescontrol", identity.application_id)
                self.assertEqual(7, identity.version_code)
                self.assertEqual("1.0.7", identity.version_name)
                self.assertEqual((digest(CERT),), identity.signer_sha256)

    def test_hex_version_code_is_accepted(self):
        apk = build_apk(binary_manifest(version_code=0x20, version_code_type=0x11))
        self.assertEqual(32, inspect_apk(apk).version_code)

    def test_matching_v3_signer_is_accepted(self):
        identity = inspect_apk(build_apk(v3=[CERT]))
        self.assertEqual((digest(CERT),), identity.signer_sha256)

    def test_unsigned_or_v1_only_archive_is_rejected(self):
        with self.assertRaises(ApkError) as raised:
            inspect_apk(build_apk(with_signing_block=False))
        self.assertEqual("apk_signature_block_missing", raised.exception.code)

    def test_key_rotation_and_v2_v3_disagreement_are_rejected(self):
        for apk, code in ((build_apk(v3=[CERT], rotation=True), "signer_rotation_unsupported"),
                          (build_apk(v3=[b"other-cert"]), "signer_rotation_unsupported")):
            with self.subTest(code=code), self.assertRaises(ApkError) as raised:
                inspect_apk(apk)
            self.assertEqual(code, raised.exception.code)

    def test_malformed_archives_fail_closed(self):
        apk = build_apk()
        cases = [
            b"not a zip",
            apk[:-10],
            build_apk(manifest=b"\x03\x00\x08\x00garbage"),
            build_apk(manifest=binary_manifest(root="application")),
            build_apk(certificates=()),
        ]
        for data in cases:
            with self.subTest(size=len(data)), self.assertRaises(ApkError):
                inspect_apk(data)

    def test_signing_block_length_corruption_is_rejected(self):
        apk = bytearray(build_apk())
        magic = apk.find(b"APK Sig Block 42")
        apk[magic - 8:magic] = (10 ** 12).to_bytes(8, "little")
        with self.assertRaises(ApkError):
            inspect_apk(bytes(apk))


if __name__ == "__main__":
    unittest.main()
