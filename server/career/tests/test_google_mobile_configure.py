import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('google_mobile_setup', Path(__file__).parents[1] / 'tools/configure_google_mobile.py')
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class GoogleMobileConfigurationTest(unittest.TestCase):
    def test_rejects_wrong_hash_and_wrong_application(self):
        content = json.dumps({'web': {'client_id': setup.CLIENT_ID, 'project_id': 'ageless-arcanum-508105-m2',
                                      'client_secret': 'GOCSPX-SYNTHETIC_ONLY'}}).encode()
        setup.validate_client(content, setup.digest(content))
        with self.assertRaises(ValueError):
            setup.validate_client(content, 'wrong')
        wrong = content.replace(setup.CLIENT_ID.encode(), b'other-client')
        with self.assertRaises(ValueError):
            setup.validate_client(wrong, setup.digest(wrong))

    def test_original_credential_is_backed_up_and_unrelated_config_untouched(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(setup.os, 'chown', create=True):
            home = Path(directory)
            (home / 'google_token.json').write_bytes(b'OLD_SYNTHETIC_TOKEN')
            (home / 'config.yaml').write_bytes(b'UNCHANGED')
            setup.configure(home, home / 'backup', b'NEW_SYNTHETIC_CLIENT', uid=0, gid=0,
                            token_hash=setup.digest(b'OLD_SYNTHETIC_TOKEN'))
            self.assertEqual((home / 'backup/google_token.json').read_bytes(), b'OLD_SYNTHETIC_TOKEN')
            self.assertFalse((home / 'google_token.json').exists())
            self.assertEqual((home / 'google_mobile_client_secret.json').read_bytes(), b'NEW_SYNTHETIC_CLIENT')
            self.assertEqual((home / 'config.yaml').read_bytes(), b'UNCHANGED')

    def test_existing_configuration_and_changed_token_are_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / 'google_token.json').write_bytes(b'CHANGED')
            with self.assertRaises(RuntimeError):
                setup.configure(home, home / 'backup', b'NEW', uid=0, gid=0, token_hash=setup.digest(b'OLD'))
            (home / 'google_mobile_client_secret.json').write_bytes(b'EXISTING')
            with self.assertRaises(RuntimeError):
                setup.configure(home, home / 'backup', b'NEW', uid=0, gid=0, token_hash=setup.digest(b'CHANGED'))
            self.assertEqual((home / 'google_token.json').read_bytes(), b'CHANGED')
            self.assertEqual((home / 'google_mobile_client_secret.json').read_bytes(), b'EXISTING')

    def test_archive_failure_rolls_back_new_configuration(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(setup.os, 'chown', create=True), \
                patch.object(setup.os, 'rename', side_effect=OSError('synthetic failure')):
            home = Path(directory)
            (home / 'google_token.json').write_bytes(b'OLD')
            with self.assertRaises(OSError):
                setup.configure(home, home / 'backup', b'NEW', uid=0, gid=0, token_hash=setup.digest(b'OLD'))
            self.assertEqual((home / 'google_token.json').read_bytes(), b'OLD')
            self.assertFalse((home / 'google_mobile_client_secret.json').exists())


if __name__ == '__main__':
    unittest.main()
