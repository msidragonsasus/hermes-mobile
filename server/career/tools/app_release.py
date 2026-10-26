"""Synthetic App release workspace, review bundle, activation and code rollback.

Deliberately fixture-only: no production directory, service, credential, database,
shell command or mobile edit/apply endpoint. Filesystem boundaries constrain
artifacts, not execution privileges of trusted Python. Production needs a
separately authorized deployment adapter and OS isolation for an App editor.
"""
import argparse
from contextlib import contextmanager
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

FILES = ('protocol.json', 'mobile_screen.json', 'mypage_handler.py')
MARKER = {'kind': 'career-mypage-synthetic-release-root', 'version': 1}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def atomic_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.active-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def safe_directory(root):
    root = Path(root).absolute()
    # A marker cannot authorize an arbitrary existing production tree.
    if root.is_symlink() or root.name != 'career-mypage-synthetic':
        raise ValueError('fixture_root_required')
    for parent in [root, *root.parents]:
        if parent.is_symlink():
            raise ValueError('symlink_root')
    if json.loads((root / 'fixture.json').read_text(encoding='utf-8')) != MARKER:
        raise ValueError('fixture_marker_required')
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name
            if path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction()):
                raise ValueError('linked_fixture_path')
    return root


def release_directory(root, identity):
    if not isinstance(identity, str) or len(identity) != 64 or any(x not in '0123456789abcdef' for x in identity):
        raise ValueError('invalid_release_id')
    return root / 'releases' / identity


@contextmanager
def locked(root):
    lock = root / '.release.lock'
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise ValueError('release_operation_in_progress') from None
    try:
        os.close(fd)
        yield
    finally:
        lock.unlink()


def payload_hashes(directory):
    directory = Path(directory)
    if directory.is_symlink():
        raise ValueError('symlink_payload')
    expected = set(FILES)
    seen = set()
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file() or path.name not in expected:
            raise ValueError('out_of_scope_file')
        if path.stat().st_size > 65536:
            raise ValueError('payload_too_large')
        seen.add(path.name)
    if seen != expected:
        raise ValueError('incomplete_payload')
    return {name: digest(directory / name) for name in FILES}


def validate_payload(directory):
    from career_core.mobile_contract import validate_components, validate_protocol
    hashes = payload_hashes(directory)
    protocol = json.loads((directory / 'protocol.json').read_text(encoding='utf-8'))
    validate_protocol(protocol)
    template = json.loads((directory / 'mobile_screen.json').read_text(encoding='utf-8'))
    if set(template) != {'title', 'components'} or not isinstance(template['title'], str) or not template['title'].strip() or len(template['title']) > 200:
        raise ValueError('invalid_template')
    def bind(value):
        if isinstance(value, dict):
            return {k: bind(v) for k, v in value.items()}
        if isinstance(value, list):
            return [bind(v) for v in value]
        return {'$company_name': 'Synthetic Company', '$status_text': 'Synthetic',
                '$status_tone': 'info', '$can_check': True}.get(value, value) if isinstance(value, str) else value
    validate_components(bind(template['components']))
    compile((directory / 'mypage_handler.py').read_text(encoding='utf-8'), str(directory / 'mypage_handler.py'), 'exec')
    return hashes


def release_record(directory):
    record = json.loads((directory / 'release.json').read_text(encoding='utf-8'))
    if directory.is_symlink() or (directory / 'payload').is_symlink() or record['hashes'] != payload_hashes(directory / 'payload'):
        raise ValueError('release_hash_mismatch')
    material = json.dumps({'base': record['base'], 'hashes': record['hashes']}, sort_keys=True).encode()
    if record['id'] != hashlib.sha256(material).hexdigest():
        raise ValueError('release_id_mismatch')
    return record


def active(root):
    pointer = json.loads((root / 'active.json').read_text(encoding='utf-8'))
    identity = pointer['id']
    if not isinstance(identity, str) or len(identity) != 64 or any(x not in '0123456789abcdef' for x in identity):
        raise ValueError('invalid_active_release')
    release_record(release_directory(root, identity))
    return pointer


def initialize(root, source):
    root = Path(root).absolute()
    if root.name != 'career-mypage-synthetic' or root.exists() or any(p.is_symlink() for p in root.parents):
        raise ValueError('new_fixture_root_required')
    hashes = validate_payload(source)
    root.mkdir(parents=True)
    write_json(root / 'fixture.json', MARKER)
    material = json.dumps({'base': None, 'hashes': hashes}, sort_keys=True).encode()
    identity = hashlib.sha256(material).hexdigest()
    directory = root / 'releases' / identity
    directory.mkdir(parents=True)
    shutil.copytree(source, directory / 'payload')
    write_json(directory / 'release.json', {'id': identity, 'base': None, 'hashes': hashes})
    write_json(directory / 'validation.json', {'payload_checks': True, 'fixture_tests': False})
    atomic_json(root / 'active.json', {'id': identity, 'previous': None})
    return identity


def workcopy(root):
    root = safe_directory(root)
    with locked(root):
        current = active(root)['id']
        work = root / 'workcopy'
        if work.exists():
            base = json.loads((work / 'base.json').read_text(encoding='utf-8'))['id']
            if base == current:
                raise ValueError('workcopy_already_exists')
            archive = Path(tempfile.mkdtemp(prefix='workcopy-retained-', dir=root))
            work.rename(archive / 'workcopy')
        work.mkdir()
        shutil.copytree(root / 'releases' / current / 'payload', work / 'payload')
        write_json(work / 'base.json', {'id': current})
        return work


def package(root):
    root = safe_directory(root)
    with locked(root):
        work = root / 'workcopy'
        if work.is_symlink() or (work / 'base.json').is_symlink():
            raise ValueError('symlink_workcopy')
        base = json.loads((work / 'base.json').read_text(encoding='utf-8'))['id']
        if active(root)['id'] != base:
            raise ValueError('stale_workcopy')
        hashes = validate_payload(work / 'payload')
        old = root / 'releases' / base / 'payload'
        if hashes == payload_hashes(old):
            raise ValueError('empty_release')
        material = json.dumps({'base': base, 'hashes': hashes}, sort_keys=True).encode()
        identity = hashlib.sha256(material).hexdigest()
        directory = release_directory(root, identity)
        if directory.exists():
            release_record(directory)
            return identity
        directory.mkdir()
        shutil.copytree(work / 'payload', directory / 'payload')
        diff = []
        for name in FILES:
            diff.extend(difflib.unified_diff((old / name).read_text(encoding='utf-8').splitlines(True),
                        (directory / 'payload' / name).read_text(encoding='utf-8').splitlines(True), 'before/'+name, 'after/'+name))
        (directory / 'review.diff').write_text(''.join(diff), encoding='utf-8')
        write_json(directory / 'release.json', {'id': identity, 'base': base, 'hashes': hashes})
        write_json(directory / 'validation.json', {'payload_checks': True, 'fixture_tests': False})
        return identity


def test_release(root, identity):
    """Trusted fixed synthetic harness, not candidate-supplied test commands.

    Executes reviewed App Python in this local process; no OS sandbox claim.
    """
    from career_core.mobile_sdui import MobileRuntime
    root = safe_directory(root)
    with locked(root):
        directory = release_directory(root, identity)
        record = release_record(directory)
        runtime = MobileRuntime(directory / 'payload', release_id=identity)
        class Adapter:
            def __init__(self):
                self.calls = []
            def enqueue_existing(self, company_id, profile):
                self.calls.append((company_id, profile))
                return 'synthetic-row', {'id': 'synthetic-run', 'state': 'queued'}, True
        adapter = Adapter()
        result = runtime.enqueue(adapter, 'synthetic-company', 'synthetic-profile')
        if adapter.calls != [('synthetic-company', 'synthetic-profile')] or result != ('synthetic-row', {'id': 'synthetic-run', 'state': 'queued'}, True):
            raise ValueError('bot_contract_failed')
        for status in ['idle', 'queued', 'running', 'auth_required']:
            screen = runtime.screen({'id': 'synthetic-company', 'data': {'name': 'Synthetic'}}, 'synthetic-profile', {'status': {'state': status}})
            if screen['company_id'] != 'synthetic-company' or screen['profile'] != 'synthetic-profile':
                raise ValueError('screen_contract_failed')
        if record['hashes'] != payload_hashes(directory / 'payload'):
            raise ValueError('payload_changed_during_tests')
        write_json(directory / 'validation.json', {'payload_checks': True, 'fixture_tests': True, 'tested_hashes': record['hashes']})


def activate(root, identity, expected_active, reviewed_hash):
    root = safe_directory(root)
    with locked(root):
        previous = active(root)['id']
        if previous != expected_active or identity != reviewed_hash:
            raise ValueError('review_or_baseline_mismatch')
        directory = release_directory(root, identity)
        record = release_record(directory)
        checks = json.loads((directory / 'validation.json').read_text(encoding='utf-8'))
        if record['base'] != previous or not checks.get('fixture_tests') or checks.get('tested_hashes') != record['hashes']:
            raise ValueError('untested_or_stale_release')
        atomic_json(root / 'active.json', {'id': identity, 'previous': previous})


def rollback(root, expected_active):
    root = safe_directory(root)
    with locked(root):
        current = active(root)
        if current['id'] != expected_active or not current['previous']:
            raise ValueError('rollback_baseline_mismatch')
        release_record(release_directory(root, current['previous']))
        atomic_json(root / 'active.json', {'id': current['previous'], 'previous': current['id']})
        return current['previous']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'workcopy', 'package', 'test', 'activate', 'rollback'])
    parser.add_argument('--fixture-root', required=True)
    parser.add_argument('--source')
    parser.add_argument('--release')
    parser.add_argument('--expected-active')
    parser.add_argument('--reviewed-hash')
    args = parser.parse_args()
    root = Path(args.fixture_root)
    if args.action == 'init':
        result = initialize(root, Path(args.source))
    elif args.action == 'workcopy':
        result = str(workcopy(root))
    elif args.action == 'package':
        result = package(root)
    elif args.action == 'test':
        result = test_release(root, args.release)
    elif args.action == 'activate':
        result = activate(root, args.release, args.expected_active, args.reviewed_hash)
    else:
        result = rollback(root, args.expected_active)
    print(json.dumps({'action': args.action, 'result': result}))


if __name__ == '__main__':
    main()
