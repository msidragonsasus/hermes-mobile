"""Operator-only exact-target editor activation; never reads settings from HTTP.

The fixed private JSON file is a plugin setting, not a Hermes core config key.
Missing/disabled is inert. An invalid enabled setting fails the plugin mount closed.
Directories are preallocated by the operator; no grants or directories are created.
"""
import json
import os
from pathlib import Path
import re
import stat

from .mobile_editor import HermesEditorRunner, execution
from .mobile_workbench import AppWorkbench

CONFIG_NAME = 'career-app-editor.json'
LIMIT = 8192


def invalid():
    raise ValueError('invalid_editor_activation')


def private_path(path, *, directory=False):
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if (not expected(info.st_mode) or info.st_uid != os.geteuid()
            or stat.S_IMODE(info.st_mode) != (0o700 if directory else 0o600)
            or (not directory and info.st_nlink != 1)):
        invalid()


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            invalid()
        value[key] = item
    return value


def configured_workbench(home=None):
    """Build once at Dashboard mount, with no credential resolution or model call."""
    if home is None:
        from hermes_constants import get_hermes_home
        home = get_hermes_home()
    home = Path(home)
    if not home.is_absolute() or '..' in home.parts:
        invalid()
    config = home / CONFIG_NAME
    if not os.path.lexists(config):
        return None
    try:
        if any(path.is_symlink() for path in [home, *home.parents]):
            invalid()
        private_path(home, directory=True)
        private_path(config)
        descriptor = os.open(config, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            if os.fstat(descriptor).st_size > LIMIT:
                invalid()
            value = json.loads(os.read(descriptor, LIMIT + 1), object_pairs_hook=unique_object)
        finally:
            os.close(descriptor)
        if (not isinstance(value, dict) or type(value.get('schema_version')) is not int
                or value['schema_version'] != 1 or type(value.get('enabled')) is not bool):
            invalid()
        if value['enabled'] is False:
            if set(value) != {'schema_version', 'enabled'}:
                invalid()
            return None
        if set(value) != {'schema_version', 'enabled', 'company_id', 'profile', 'execution', 'auto_run'}:
            invalid()
        for key in ('company_id', 'profile'):
            if not isinstance(value[key], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', value[key]):
                invalid()
        if type(value['auto_run']) is not bool:
            invalid()
        selected = execution(value['execution'])
        # Reject normalization: review must name exactly what will be dispatched.
        if selected != value['execution']:
            invalid()
        root = home / 'app-workspaces' / 'career.mypage' / value['profile'] / value['company_id']
        for name in ('app-workspaces', 'app-workspaces/career.mypage',
                     f'app-workspaces/career.mypage/{value["profile"]}'):
            private_path(home / name, directory=True)
        private_path(root, directory=True)
        allowed = {'code-state.sqlite', 'code-state.sqlite-journal', 'code-state.sqlite-wal', 'code-state.sqlite-shm'}
        for child in root.iterdir():
            if child.name not in allowed:
                invalid()
            private_path(child)
        database = root / 'code-state.sqlite'
        if not database.exists():
            descriptor = os.open(database, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            os.close(descriptor)
        runner = HermesEditorRunner(selected)
        return AppWorkbench(root, value['company_id'], value['profile'], editor_runner=runner,
                            editor_execution=selected, editor_auto_run=value['auto_run'])
    except Exception:
        # Do not expose settings, paths, database contents or provider exceptions.
        raise ValueError('invalid_editor_activation') from None
