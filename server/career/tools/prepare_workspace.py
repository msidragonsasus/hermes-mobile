"""Install Google Workspace's official Python dependencies in a separate runtime.

Reuse the existing OAuth files and google_api.py. Never run setup/login, change
Hermes's own venv, or enable collection. Requires the existing administrator.
"""
import os
from pathlib import Path
import pwd
import subprocess
import tempfile

from privileged_paths import prepare_stage, publish_stage

BASE = Path('/var/lib/hermes')
RUNTIME = BASE / 'career-google-2.194.0'
SERVICE = 'home-hermes-dashboard.service'
DROPIN = Path('/etc/systemd/system') / (SERVICE + '.d/75-career-google.conf')
PACKAGES = ['google-api-python-client==2.194.0', 'google-auth==2.55.1',
            'google-auth-oauthlib==1.3.1', 'google-auth-httplib2==0.3.1',
            'httplib2==0.32.0', 'pyasn1==0.6.4']
CHECK = '''
import importlib.metadata, json, sys
import googleapiclient.discovery, google.auth, google_auth_httplib2, httplib2
for requirement in json.loads(sys.argv[1]):
    package, version = requirement.split('==')
    assert importlib.metadata.version(package) == version
print('WORKSPACE_RUNTIME_OK: official client dependencies; no authentication performed')
'''


def call(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def as_hermes(args):
    return call(['runuser', '-u', 'hermes', '--', 'env', '-i', 'HOME=' + str(BASE),
                 'PATH=/usr/local/bin:/usr/bin:/bin', 'PIP_CONFIG_FILE=/dev/null',
                 'PYTHONDONTWRITEBYTECODE=1', *map(str, args)], timeout=600)


def main(install=False):
    import json
    if os.geteuid() != 0:
        raise SystemExit('Run with sudo on hermes-node.')
    os.umask(0o077)
    for path in (BASE, RUNTIME, DROPIN, DROPIN.parent, BASE / 'career-update-backups'):
        if path.is_symlink() or path.resolve() != path:
            raise SystemExit('Unexpected path; no changes made.')
    if not RUNTIME.exists():
        if not install:
            raise SystemExit('Workspace runtime is not prepared. No changes made.')
        user = pwd.getpwnam('hermes')
        stage, stage_identity = prepare_stage(BASE, 'career-google-stage-', user)
        try:
            as_hermes(['/usr/bin/python3', '-m', 'venv', stage])
            as_hermes([stage / 'bin/python', '-m', 'pip', 'install', '--disable-pip-version-check',
                       '--no-input', '--only-binary=:all:', '--index-url', 'https://pypi.org/simple', *PACKAGES])
            as_hermes([stage / 'bin/python', '-I', '-c', CHECK, json.dumps(PACKAGES)])
            publish_stage(stage, RUNTIME, stage_identity)
        except Exception:
            print('Preparation failed; existing Hermes environment retained. New files retained at:', stage)
            raise
    as_hermes([RUNTIME / 'bin/python', '-I', '-c', CHECK, json.dumps(PACKAGES)])
    desired = ('[Service]\nEnvironment=CAREER_WORKSPACE_PYTHON=' + str(RUNTIME / 'bin/python') + '\n').encode()
    previous = DROPIN.read_bytes() if DROPIN.exists() else None
    if previous == desired:
        print('WORKSPACE_RUNTIME_ALREADY_CONFIGURED')
        return
    backup = BASE / 'career-update-backups'
    backup.mkdir(mode=0o700, exist_ok=True)
    if previous is not None:
        with tempfile.NamedTemporaryFile(prefix='workspace-dropin-', suffix='.conf', dir=backup, delete=False) as saved:
            saved.write(previous)
    DROPIN.parent.mkdir(parents=True, exist_ok=True)
    try:
        DROPIN.write_bytes(desired)
        call(['systemctl', 'daemon-reload'], capture_output=True)
        call(['systemctl', 'restart', SERVICE], capture_output=True)
        call(['systemctl', 'is-active', '--quiet', SERVICE], capture_output=True)
    except Exception:
        if previous is None:
            DROPIN.unlink(missing_ok=True)
        else:
            DROPIN.write_bytes(previous)
        call(['systemctl', 'daemon-reload'], capture_output=True)
        call(['systemctl', 'restart', SERVICE], capture_output=True)
        raise SystemExit('Activation failed; previous service setting restored. OAuth and existing environments retained.') from None
    print('WORKSPACE_RUNTIME_CONFIGURED: existing OAuth files will be used by the read-only adapter.')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install', action='store_true')
    main(parser.parse_args().install)
