"""Deployment planning tests do not contact a host or modify installed code."""
import hashlib
import io
import json
from pathlib import Path
import runpy
import unittest
import zipfile

INSTALL = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'tools/install_google_connection.py'))


class GoogleInstallTests(unittest.TestCase):
    def test_narrow_router_patch_preserves_unrelated_code_and_is_idempotent(self):
        original = 'UNRELATED = 1\r\ndef create_router(store_factory=None):\r\n    router = APIRouter(dependencies=[Depends(require_session)])\r\n    return router\r\n'
        result = INSTALL['patch_api'](original)
        self.assertIn('UNRELATED = 1\r\n', result)
        self.assertIn('    return router\r\n', result)
        self.assertEqual(original, result.replace('    from .google_connection import create_google_router\r\n    router.include_router(create_google_router())\r\n', ''))
        self.assertEqual(result, INSTALL['patch_api'](result))

    def test_unknown_auth_contract_stops_before_modification(self):
        with self.assertRaises(RuntimeError):
            INSTALL['patch_api']('def create_router():\n    router = APIRouter()\n    return router\n')

    def test_bundle_hash_and_members_are_pinned(self):
        files = {name: b'# synthetic\n' for name in INSTALL['MEMBERS']}
        release = {'feature': 'google-native-connect-v1', 'sha256': {name: hashlib.sha256(value).hexdigest() for name, value in files.items()}}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, value in files.items():
                archive.writestr(name, value)
            archive.writestr('RELEASE.json', json.dumps(release))
        data = stream.getvalue()
        self.assertEqual(files, INSTALL['read_bundle'](data, hashlib.sha256(data).hexdigest()))
        with self.assertRaises(RuntimeError):
            INSTALL['read_bundle'](data, 'wrong')
