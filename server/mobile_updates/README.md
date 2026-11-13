# Mobile Updates plugin

Read-only Dashboard API that serves operator-published Hermes Mobile APKs to the
signed-in app over the same trusted connection. No agent tools, hooks, build,
signing, shell, deploy or publish routes.

Setup, protocol, Android verification and signing prerequisites:
[docs/APP_SERVER_UPDATES.md](../../docs/APP_SERVER_UPDATES.md).

- Disabled unless `HERMES_MOBILE_UPDATES_DIR` names an absolute, non-symlink,
  owner-writable-only directory, or the trusted active server profile explicitly
  enables the private JSON configuration below. A nonempty environment root
  retains precedence, including its existing validation/application-ID behavior.
- Without that environment root, the factory reads
  `get_hermes_home()/mobile-updates.json` on each request. No service restart is
  needed for a configuration change after these routes have been mounted. This
  does not itself install, register, or activate the plugin.

## Profile-private configuration

All five fields are required; no unknown or duplicate keys are accepted:

```json
{
  "schema": "hermes-mobile-updates-config",
  "protocol": 1,
  "enabled": true,
  "root": "/absolute/profile-owned/mobile-updates",
  "application_id": "com.m57.hermescontrol"
}
```

The operator must replace the example root with the reviewed real directory.
`enabled` must be a JSON boolean, `protocol` an integer (not a boolean), and
`root`/`application_id` strings. An absent file or explicit `enabled: false`
stays disabled; malformed/unsafe configuration fails closed. Use mode `0600`
for the file and `0700` for its profile home and update root. The file, home,
and root must be owned by the server's effective UID, absolute, canonical,
non-symlink paths and not group/world writable. All ancestor directories must
also be non-symlink, not group/world writable, and owned by root or that UID.
The file is bounded, opened with no-follow, and checked against its pre-open
identity. JSON-configured stores pin and recheck the root device/inode/owner.
These settings are not accepted from HTTP parameters or model tool arguments;
the trusted core profile scope controls `get_hermes_home()`.

## Authentication

Provider-verified Dashboard sessions and parent-verified hosted request state
retain their existing behavior. In-process same-user native HTTP requests may
also use the core's already-redeemed one-request principal and standard
`hermes_cli.web_server._require_token` authorization seam. The plugin never
redeems tickets itself and never treats headers as a principal. Static
loopback tokens alone do not authorize updates; anonymous, unsigned,
wrong-purpose, replayed or origin-bearing native tickets remain refused by
the core. Plugin same-origin and cross-site checks are unchanged.

After authorized installation/configuration, read back the exact config bytes,
ownership/modes and canonical root, then use ordinary authenticated GETs to
`/api/plugins/mobile_updates/status` and `/latest`. Verify the enabled flag,
release identity/hash/signers, and exact APK bytes (if published), and confirm
anonymous/fake-header access fails. A 401 alone does not verify route mounting.
No device installation or signing continuity is established by server tests.

- Publishing is the operator CLI `python -m updates_core.publish` (atomic copy of a
  reviewed APK into a new immutable `releases/v<code>-<sha16>/` directory).

## Tests

```text
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s server/mobile_updates/tests -v
```

Requires FastAPI and httpx. Core-auth integration additionally requires the actual
Hermes source root on `PYTHONPATH` (for example `.../app`); without it those
integration tests are skipped, not verified. Run `activation/update-compat/run_tests.py`
from the task workspace for the pinned managed-interpreter/synthetic-home command.
All fixtures are synthetic (fake certificates, no real signatures); tests never
touch a live release directory. The core integration uses actual ticket minting,
redemption, native auth/profile middleware, Basic provider, password-login routes
and session verifier. Only the side-effectful web-server bootstrap is avoided by
AST-extracting its unchanged authorization/token definitions.
