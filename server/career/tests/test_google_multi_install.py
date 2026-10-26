import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('google_multi_installer', Path(__file__).parents[1] / 'tools/install_google_multi_connection.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

SOURCE = '''def create_router():
    @router.get('/collection/connections')
    def collection_connections():
        return {'unchanged': True}
    def settings(payload):
        if payload["account_id"] != connected_account():
            raise ValueError()
    def unrelated_group_route():
        return 'KEEP_THIS'
'''


class MultiInstallerTests(unittest.TestCase):
    def release(self):
        before = installer.function_source(SOURCE)[1]
        return {'api_before': before, 'api_after': before.replace("'unchanged': True", "'multi': True")}

    def test_patch_changes_only_connection_route_and_validation_and_is_idempotent(self):
        release = self.release()
        changed = installer.patch_api(SOURCE, release)
        expected = SOURCE.replace(release['api_before'], release['api_after']).replace(
            'connected_account():', "connected_account(payload['account_id']):")
        self.assertEqual(expected, changed)
        self.assertEqual(changed, installer.patch_api(changed, release))

    def test_patch_preserves_crlf_and_refuses_an_intervening_route_change(self):
        changed = installer.patch_api(SOURCE.replace('\n', '\r\n'), self.release())
        self.assertNotIn('\n', changed.replace('\r\n', ''))
        with self.assertRaises(RuntimeError):
            installer.patch_api(SOURCE.replace("'unchanged': True", "'other_user_change': True"), self.release())

    def test_bundle_must_have_pinned_members_and_content(self):
        files = {name: b'synthetic = True\n' for name in installer.MODULES | installer.TESTS}
        files['SKILL.md'] = b'# Synthetic skill\n'
        release = {'feature': 'google-multi-account-v2', 'sha256': {name: installer.digest(data) for name, data in files.items()}}
        result = io.BytesIO()
        with zipfile.ZipFile(result, 'w') as archive:
            for name, data in files.items():
                archive.writestr(name, data)
            archive.writestr('RELEASE.json', json.dumps(release))
        content = result.getvalue()
        self.assertEqual(release, installer.read_bundle(content, installer.digest(content))[0])
        with self.assertRaises(RuntimeError):
            installer.read_bundle(content, 'wrong')


if __name__ == '__main__':
    unittest.main()
