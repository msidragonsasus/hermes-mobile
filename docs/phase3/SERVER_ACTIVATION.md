# Career App editor: explicit server activation

This is an implemented, synthetic-tested activation path, **not an activation or
production/model readiness claim**. No service, installed plugin, live database,
credentials, configuration, grant or model login was changed during implementation.
Android/APK deployment and in-app updater delivery are separate workstreams.

## Actual bootstrap and default

`server/career/dashboard/manifest.json` names `dashboard/plugin_api.py`. The
Dashboard loads that entry by filename and mounts its `router` at
`/api/plugins/career`. That entry now calls
`career_core.mobile_activation.configured_workbench()` and passes its result to
`create_router(..., mobile_workbench=...)` before deployment receipt attachment.
This is not an unused factory or an assumed Hermes core setting.

The reader uses `hermes_constants.get_hermes_home()` (the active host/profile home)
and the fixed filename **`career-app-editor.json`** there. This is a new plugin-owned
JSON setting; it is not a `config.yaml` key, environment enable flag or HTTP API.
Missing file means disabled, with no workspace allocation or model access.
An explicitly disabled file is exactly:

```json
{"schema_version": 1, "enabled": false}
```

An invalid file fails this plugin mount closed with `invalid_editor_activation`;
never silently fall back to another company/model/workspace. The host's handling
of a failed plugin mount must be checked on the actual installed Dashboard.

## Exact enabled format

The following is a **syntax example only**, not approved production values:

```json
{
  "schema_version": 1,
  "enabled": true,
  "company_id": "REPLACE_WITH_REVIEWED_COMPANY_ID",
  "profile": "REPLACE_WITH_REVIEWED_PROFILE",
  "execution": {
    "provider": "REPLACE_WITH_REVIEWED_PROVIDER",
    "model": "REPLACE_WITH_REVIEWED_MODEL",
    "reasoning_effort": "REPLACE_WITH_REVIEWED_EFFORT"
  },
  "auto_run": true
}
```

All fields are required, unknown/duplicate keys are refused, and booleans and schema
version have strict types. Company/profile must match `[A-Za-z0-9][A-Za-z0-9_-]{0,79}`
exactly; no slashes, dots, paths or normalization. Execution is the existing exact
provider/model/effort contract; `auto`/`moa`, missing values and whitespace rewriting
are refused. Provider/model entitlement is **not** verified by reading this file.
The existing Hermes runtime adapter validates the actual explicit route when an
approved POST dispatches; Codex retains its existing OAuth-only/no-paid-fallback
checks, and no alternate-provider fallback is added.

`auto_run: true` permits dispatch only for a new explicit `propose` POST accepted
by this executor incarnation. It does not schedule work during import, startup,
GET, receipt recovery or restart. `auto_run: false` deliberately accepts queued
jobs without automatic execution; there is no generic HTTP run/resume endpoint.
Cancel such a job and submit a fresh request after reviewed enabling; changing
configuration alone must not revive it.

## Filesystem prerequisites (operator allocation only)

The config and workspace must be owned by the service effective UID. The config
is a regular single-link `0600` file, maximum 8192 bytes. The active Hermes home
must be `0700`, with no symlink ancestor. The only workspace location is:

```text
<active Hermes home>/app-workspaces/career.mypage/<profile>/<company_id>/
```

Every directory from `app-workspaces` through the target is pre-existing,
service-owned `0700`, not a symlink. The reader never creates directories. The
workspace must be dedicated: initially empty, or containing only
`code-state.sqlite` and its SQLite `-journal`, `-wal`, `-shm` siblings. Existing
files must be regular, single-link, service-owned `0600`; symlinks/hardlinks,
shared directories, unrelated files and missing directories are refused **before
SQLite opens**. The factory creates the code-state DB at `0600` if absent and
initializes only code/releases/receipts/editor-job tables. Use a restrictive
service umask (`0077`) for SQLite temporary siblings. Do not change permissions
on a live Hermes home blindly; review its existing consumers first.

These checks assume the service UID/operator is trusted; they are not isolation
against a compromised same-UID process or root. The model has no filesystem tools
or path parameter. It receives only request prose, the three payload files and
nonsecret execution/target metadata. No Career records, account credentials,
portal bodies, browser tools or general Python executor are exposed.

## Readiness / authorization checklist (not performed here)

1. Read-only verify the actual installed Hermes version and Dashboard loader,
   authenticated plugin mount, active home, service user, and shutdown lifespan
   propagation. Historical SSH notes elsewhere are not current access evidence.
2. Obtain an exact existing active company/profile and reviewed provider/model/
   effort. Synthetic IDs/models used in tests are not deployment candidates.
   Existing authentication/billing selection must be reviewed independently.
3. Review grants, service writable paths, private workspace allocation, config
   contents and the code-only release allowlist. No general shell/Python sandbox
   or credential grant is implied by this feature.
4. Back up existing code/config using private operator procedures; use SQLite
   backup API for live data. Drain/check existing work before an independently
   approved deployment/restart of the shared Dashboard. Preserve data/credentials.
5. Deploy the reviewed code-only artifact through the existing release mechanism,
   allocate the exact directory and atomically install the reviewed private JSON
   file. Restart only with explicit authorization; existing REST/WS clients and
   other in-process workers are affected. This document is not that authorization.
6. Check authenticated exact-target GET and `/editor-job`: executor configured,
   selected execution (after an accepted job), and intended auto-run state. Other
   company/profile workbenches must be 403; anonymous requests must remain 401.
   GET/import must not produce a model call or MyPage run. Mount/deployment
   receipts prove only their documented lifecycle, not inference or entitlement.
7. Only with separate model-call approval submit one bounded request. Verify its
   operation receipt and job; repeated same ID must not execute again. Review,
   test and explicitly apply the displayed candidate hash. Generation is not
   activation. Perform actual account/device verification separately.

## Lifecycle and uncertainty

* Each job pins an executor-incarnation UUID, target, base/revision, source hash
  and execution. A transactional lease permits one claim. Concurrent requests,
  duplicate workers and lost replies cannot claim an already running/completed job.
* Remount/restart does not claim pre-restart queued jobs, including replayed POST
  receipts. Old jobs without an incarnation are equally non-dispatchable. Running
  jobs are never re-run. Recovery is read-only; cancellation plus a **new operation
  ID** is the explicit path to a new request, not automatic retry.
* Cancellation revokes the lease in the same transaction as its receipt. The
  receipt pins `editor_job_id`; the later interruption callback addresses that
  exact old job, even if a newer request has already changed the current pointer.
  This additive receipt field contains only an opaque job ID.
* Router shutdown closes claims, revokes running leases, records
  `editor_executor_stopped` and best-effort interrupts the associated runner.
  Late results cannot create a candidate. The unresolved job remains `running`,
  visibly uncertain and explicitly cancellable; shutdown is not evidence that a
  remote request stopped or was never billed. FastAPI/ASGI may first wait for
  background work before shutdown; no hard deadline or force-kill is claimed.
* An abrupt process death has no shutdown guarantee. Durable running state and
  incarnation fencing still prevent re-execution. There is no startup scan,
  model probe, paid fallback, background retry or fake recovered success.

## Rollback

For candidate rollback use the existing reviewed code-only rollback action; it
keeps the Career data DB, credentials and recipes untouched and needs no restart.

For editor disablement, drain/cancel active jobs, privately preserve the JSON and
code-state DB, atomically install the minimal disabled JSON (or remove only that
setting file), then perform a separately approved restart. No hot reload is
implemented. Disabling removes the scoped workbench runtime override, so the
bundled App runtime is used again; retain the code-state DB for later reviewed
re-enablement. Re-enabling the same target keeps releases/receipts and does not
resume pending model jobs. Never replace or restore the live Career data DB to
roll back code. Older plugin versions ignore this setting; preserving the code
workspace does not make an older version lifecycle-compatible.

## Local validation

Use the managed interpreter; no installation or live provider probe is needed:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=server/career:server/career/tests \
  /var/lib/hermes/venv/bin/python -m unittest discover \
  -s server/career/tests -p 'test_mobile_*.py' -v
```

Tests use owned temporary homes, private config/workspaces, synthetic Career DBs
and fake agents. `test_mobile_activation` executes the actual dashboard entry
with deployment hooks/router creation intercepted to prove configuration injection,
and exercises the configured real runner/API path with a fake agent. Lifecycle
regressions cover concurrent claims, remount/replay, cancellation pointer races,
shutdown and late replies. Packaging tests cover the exact dependency allowlist.
The durable task report records commands, RED/GREEN outcomes, full-suite baseline
failures, sandbox isolation and limits. No test result here establishes live
Dashboard, model, Android or production readiness.
