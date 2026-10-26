"""Accept systemd's service-group credential without permitting broad key access."""
import stat
import struct
import types
import unittest

from career_core.secret_store import credential_permissions


class CredentialPermissionTests(unittest.TestCase):
    def allowed(self, mode, owner=0, group=988, kind=stat.S_IFREG, acl=None):
        info = types.SimpleNamespace(st_mode=kind | mode, st_uid=owner, st_gid=group)
        return credential_permissions(info, 988, 988, acl)

    def acl(self, uid=988, extra=(), permission=4):
        undefined = 0xffffffff
        entries = [(1, 4, undefined), (2, permission, uid), (4, 0, undefined),
                   (16, 4, undefined), (32, 0, undefined), *extra]
        return struct.pack('<I', 2) + b''.join(struct.pack('<HHI', *entry) for entry in entries)

    def test_systemd_root_group_acl_grants_only_service_user_read_access(self):
        self.assertTrue(self.allowed(0o440, group=0, acl=self.acl()))
        self.assertFalse(self.allowed(0o440, group=0))

    def test_other_acl_users_groups_write_access_and_malformed_data_are_rejected(self):
        for acl in (self.acl(uid=1000), self.acl(extra=[(2, 4, 1000)]),
                    self.acl(extra=[(8, 4, 1000)]), self.acl(permission=6),
                    self.acl()[:-1], b'unknown', bytes(44)):
            with self.subTest(acl=acl):
                self.assertFalse(self.allowed(0o440, group=0, acl=acl))

    def test_systemd_root_owned_service_group_can_read(self):
        self.assertTrue(self.allowed(0o440))
        self.assertTrue(self.allowed(0o400))
        self.assertTrue(self.allowed(0o400, owner=988))
        self.assertTrue(self.allowed(0o600, owner=988))

    def test_group_writable_world_readable_or_executable_credentials_are_rejected(self):
        for mode in (0o444, 0o640, 0o660, 0o441, 0o450, 0o777):
            with self.subTest(mode=mode):
                self.assertFalse(self.allowed(mode))

    def test_different_owner_group_and_non_regular_files_are_rejected(self):
        self.assertFalse(self.allowed(0o440, group=1000))
        self.assertFalse(self.allowed(0o440, owner=1000))
        self.assertFalse(self.allowed(0o440, owner=988))
        self.assertFalse(self.allowed(0o400, owner=1000))
        self.assertFalse(self.allowed(0o440, kind=stat.S_IFDIR))
        self.assertFalse(self.allowed(0o440, kind=stat.S_IFLNK))
