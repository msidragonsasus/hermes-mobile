"""Profile-private activation, using only synthetic homes beneath TMPDIR."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from updates_core.api import environment_store_factory
from updates_core.store import config_from_environment, DEFAULT_APPLICATION_ID, ConfigurationError, StoreError


class ActivationConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name) / 'profile'
        self.home.mkdir(mode=0o700)
        self.root = self.home / 'updates'
        self.root.mkdir(mode=0o700)
        self.path = self.home / 'mobile-updates.json'
        self.environment = patch.dict(os.environ, {'HERMES_HOME': str(self.home)}, clear=False)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.document = {'schema': 'hermes-mobile-updates-config', 'protocol': 1,
                         'enabled': True, 'root': str(self.root),
                         'application_id': DEFAULT_APPLICATION_ID}

    def write(self, document=None):
        self.path.write_text(json.dumps(self.document if document is None else document))
        self.path.chmod(0o600)

    def test_explicit_profile_config_is_read_on_each_factory_call(self):
        factory = environment_store_factory()
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop('HERMES_MOBILE_UPDATES_DIR', None)
            self.assertIsNone(factory())
            self.write()
            store = factory()
            self.assertIsNotNone(store)
            self.assertEqual(self.root, store.config.root)
            self.assertEqual(DEFAULT_APPLICATION_ID, store.config.application_id)
            self.write(self.document | {'enabled': False})
            self.assertIsNone(factory())
            self.path.unlink()
            self.assertIsNone(factory())


    def test_rejects_nonexact_schema_and_types(self):
        bad = [[], {}, self.document | {'extra': 1},
               self.document | {'enabled': 'true'}, self.document | {'enabled': 1},
               self.document | {'protocol': True}, self.document | {'protocol': 2},
               self.document | {'schema': 'wrong'}, self.document | {'root': None},
               self.document | {'root': 'relative'}, self.document | {'application_id': []},
               self.document | {'application_id': '../bad'}]
        for document in bad:
            with self.subTest(document=document):
                self.write(document)
                with self.assertRaises(ConfigurationError):
                    config_from_environment({})
        self.path.write_text('{broken')
        with self.assertRaises(ConfigurationError):
            config_from_environment({})
        self.path.write_text(json.dumps(self.document).replace('"enabled": true', '"enabled": false, "enabled": true'))
        with self.assertRaises(ConfigurationError):
            config_from_environment({})

    def test_rejects_unsafe_paths_and_owners(self):
        for target in (self.path, self.home, self.root):
            self.write()
            original = target.stat().st_mode & 0o777
            target.chmod(original | 0o020)
            with self.subTest(target=target), self.assertRaises(ConfigurationError):
                config_from_environment({})
            target.chmod(original)
        self.write()
        for target in (self.path, self.root, self.home):
            moved = target.with_name(target.name + '-real')
            target.rename(moved)
            target.symlink_to(moved, target_is_directory=moved.is_dir())
            with self.subTest(symlink=target), self.assertRaises(ConfigurationError):
                config_from_environment({})
            target.unlink()
            moved.rename(target)
        with patch('updates_core.store.os.geteuid', return_value=os.geteuid() + 1):
            with self.assertRaises(ConfigurationError):
                config_from_environment({})

    def test_environment_still_has_precedence_over_json(self):
        self.write({'bad': 'config'})
        config = config_from_environment({'HERMES_MOBILE_UPDATES_DIR': str(self.root)})
        self.assertEqual(self.root, config.root)
        with self.assertRaises(ConfigurationError):
            config_from_environment({'HERMES_MOBILE_UPDATES_DIR': 'relative'})

    def test_configured_root_is_pinned_and_rechecked(self):
        self.write()
        store = environment_store_factory()()
        self.assertIsNotNone(store)
        moved = self.root.with_name('old-updates')
        self.root.rename(moved)
        self.root.mkdir(mode=0o700)
        with self.assertRaises(StoreError):
            store.latest()


if __name__ == '__main__':
    unittest.main()
