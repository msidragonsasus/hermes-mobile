# Phase 3: one-company workbench preview

This is a functional, bounded preview, not acceptance of complete Phase 3 or
natural-language autonomous Bot editing. The existing UI and v25 browser/group
fixes remain the baseline. No production grant is enabled by this change.

## Implemented flow

Apps → Career → company → MyPage status → **画面・専用Botの改修** opens the
native workbench. Opening reads only. An explicit request records its purpose
and creates a candidate using a title and one of two fixed enqueue behaviors.
The three files can be edited, tested, reviewed, applied and rolled back from
the same scoped screen. Local unsaved edits disable test/apply. Apply, rollback
and draft discard require a target-specific confirmation. Large truncated
diffs cannot be applied from this UI.

The existing Dashboard auth/session and profile client are reused. A changed
server, profile or authenticated client drops stale results. The device persists
only operation ID, action, request hash, base release and revision; source,
request prose and credentials are not persisted in this pending record.

The API is `/api/plugins/career/mobile/workbench/{company_id}` (GET/POST), with
GET `/operations/{operation_id}` for recovery. Writes additionally require the
fixed native header and reject browser Origin/Fetch Metadata. These are not
device attestation or a new authorization grant.

`create_router(..., mobile_workbench=None)` is the default. An operator must
explicitly inject `AppWorkbench(root, company_id, profile)` for exactly one
company and profile. There is no HTTP grant creation, auto-discovery, environment
enablement, generic shell, arbitrary file path, deployment command or account
permission editor. Other companies retain the bundled runtime. New clients ask
for a company-scoped manifest; the SDUI v1 action/component contract is unchanged.

## Code and data boundary

Editable payload: `protocol.json`, `mobile_screen.json`, `mypage_handler.py`.
The candidate Python is parsed as a restricted adapter call and **never exec'd,
imported or evaluated**. Only `enqueue_existing` and `enqueue_ready` are allowed;
the latter refuses login-required/busy connections before the existing queue.
This is not a general Python sandbox and is not arbitrary Bot implementation.
Fixed validation binds every supported status, checks the contract, and invokes
the interpreted entry with a synthetic adapter without running a worker.

The dedicated code-state SQLite DB contains immutable release payloads, a
candidate, revision, test hash, active/previous pointer and operation receipts.
State and receipts commit atomically. Base/revision conflicts and untested
edits cannot apply. Observed active MyPage runs block apply/rollback. The MyPage
HTTP enqueue and pointer changes share this code-state lock; unrelated external
cron scheduling is not claimed to be globally locked by the workbench.
An apply probe failure retains the old pointer and draft. Rollback verifies the
old payload hash. No Career DB migration, credentials/recipes/data overwrite,
service restart or server deployment is performed by apply/rollback.

Lost replies are recovered by GET, not replayed. `unknown` never means not sent.
A fence using the original ID/hash returns an existing result, or commits a
cancellation tombstone that rejects a later arrival of that request. A corrupt
or unwritable pending record blocks new writes. There is no blanket reset.

## Explicitly unfinished

Natural-language intent is recorded, but no autonomous agent consumes it.
The current candidate builder is deterministic. It does not call Hermes, DSH,
Claude, a model API or arbitrary tools. Fixed behavior selection alone does not
fulfil the requested App-specific Bot-internal editing scope.

Before that phase can be activated, the parent must approve and identify:

* The production company ID and profile. They cannot be inferred from synthetic
  fixtures. Production read-only SSH currently fails authentication.
* A dedicated `career-app-editor` Hermes instance using the existing
  `coach_runtime.make_agent` adapter and an explicitly selected provider/model/
  reasoning setting, with no fallback to another agent. Proposed session scope:
  `career-app-edit:<operation_id>`; only request prose and these three code files
  are supplied, without account data or portal credentials.
* A dedicated candidate directory beneath
  `/var/lib/hermes/.hermes/app-workspaces/career.mypage/<profile>/<company_id>/`,
  scoped code-read/write and fixed-test capabilities, plus an isolated test
  executor if general Python replaces the restricted interpreter. The agent
  receives no live plugin/data directory, generic shell, network browsing,
  credentials, global Chat tools, deployment or apply authority.
* The exact reviewed agent/API implementation and cancellation/restart contract.
  The natural-language asynchronous generation adapter is still unimplemented;
  approval alone does not make it available.
* Reviewed deployment of this API/native-compatible plugin to
  `/var/lib/hermes/.hermes/plugins/career/`, its required release file allowlist,
  and an explicit restart of `home-hermes-dashboard.service`. Existing SSH
  access must work; this change creates no keys or new authentication.
* Separate once-only approval for the final production code pointer change.
  APK delivery does not enable any of the above capabilities.

The earlier one-shot `DSH_OK` does not authorize this new job. DSH is optional;
normal authorized local implementation and tests do not depend on it.

## Verification

Use the source/validation artifact for the exact frozen code and result counts.
Android checks: scoped workbench + SDUI tests, full `testDebugUnitTest`,
`assembleDebug`, instrumentation APK, `ktlintCheck`, `checkColorLiterals`,
`lintDebug`, `compileReleaseKotlin`. Backend: synthetic workbench + existing
SDUI/release tests and full test discovery, with bytecode writes disabled for
immutable fixture payloads. Distinguish failures reproduced on the pre-task
baseline, local/emulator results, physical-phone testing, and production testing.
No live sign-in, site submissions, actual credentials or external agent calls
are part of this validation.
