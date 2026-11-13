# Phase 3: one-company workbench preview

This is a bounded implementation, not acceptance of all Phase 3 or production
activation. The original deterministic preview has been extended by the
[natural-language editor job contract](EDITOR_JOB_CONTRACT.md) and the implemented
[explicit server activation path](SERVER_ACTIVATION.md). Those documents supersede
the old claims that no agent consumes the request or that the asynchronous adapter
is unimplemented. No production grant is enabled by these source changes.

## Implemented flow

Apps → Career → company → MyPage status → **画面・専用Botの改修** opens the
native workbench. Opening reads only. The manual request/title/mode workflow is
retained; a natural-language `propose` action can instead create a durable editor
job when an exact-target operator configuration supplies an explicit runner.
Without an injected runner it remains `awaiting_executor` and does not call a model.

The three payload files can be edited, tested, reviewed, explicitly applied and
rolled back. Local unsaved edits disable test/apply; large truncated diffs cannot
be applied from the UI. Generation creates only a candidate, never an activation.

The Dashboard auth/session and profile client are reused. A changed server,
profile or authenticated client drops stale results. Pending device persistence
contains only operation ID/action/request hash/base/revision, not source, request
prose or credentials.

The scoped GET/POST API is `/api/plugins/career/mobile/workbench/{company_id}`,
with GET `/operations/{operation_id}` and `/editor-job` for recovery. Native writes
require the fixed once header and reject browser Origin/Fetch Metadata; that is
not device attestation or a new authorization grant.

`create_router(..., mobile_workbench=None)` remains the library default. The
actual dashboard bootstrap now consumes a private, default-disabled plugin JSON
setting. Exactly one configured company/profile receives the scoped workbench;
other companies retain the bundled runtime. There is no HTTP grant/model/path
configuration, auto-discovery, generic shell, deployment or account-permission
editor. See [SERVER_ACTIVATION.md](SERVER_ACTIVATION.md) for the exact schema,
preallocated owned workspace, readiness conditions and disablement/rollback.

## Code and data boundary

The editable payload is exactly `protocol.json`, `mobile_screen.json` and
`mypage_handler.py`. Candidate Python is **never exec'd, imported or evaluated**.
The current restricted AST interpreter supports the documented scoped read_state,
conditions/branches and enqueue/refusal returns, not just the original two fixed
modes. General Python and broader scraper/credential/runtime editing remain out
of scope. No OS sandbox grant is implied by the restricted policy interpreter.
The fixed harness validates all supported statuses with a synthetic adapter and
never runs a portal worker.

A dedicated code-state SQLite DB stores immutable releases, candidate, revision,
test hash, active/previous pointer, operation receipts and editor jobs. State and
receipts commit atomically. Base/revision conflicts and untested edits cannot
apply. Observed active MyPage runs block apply/rollback; HTTP enqueue and pointer
changes share the code-state lock. Unrelated cron is not claimed to be globally
locked by this workbench. Failed apply retains the old pointer and draft.
Rollback verifies the old payload hash, without overwriting Career data,
credentials/recipes or restarting/deploying the service.

Lost replies are recovered by GET. Unknown does not mean unsent. Operation fences
prevent late unknown requests; executor-incarnation fencing prevents queued-job
replay after restart. Transactional leases prevent duplicate execution; cancelled
or shutdown-revoked leases reject late results. A delayed cancel callback targets
the exact cancelled job, not a newer current job. Old queued/running work requires
explicit cancellation and a new request, never automatic model resubmission.

## Remaining authorization and verification

Production company/profile, provider/model/effort, owned allocation, release
deployment and restart must each be reviewed on the actual host. Historical
SSH/authentication notes from earlier Windows work are not current access facts.
This slice did not select live target/model values, probe an actual model, install
a plugin, restart a service, deploy an APK or alter a grant. The initial legacy
full-suite run exposed insufficient fixture isolation (host metadata read paths);
the durable report records that caveat and subsequent sandboxed reruns explicitly.
Synthetic IDs/models are not production candidates. General Python,
arbitrary runtime editing and all-App discovery remain outside this slice.
The earlier `DSH_OK` does not authorize this job; no bro/DSH identity is reused.

## Validation

The task's durable implementation report records the actual managed-Python
commands, failing regressions, GREEN results, packaging and baseline full-suite
failures. Distinguish synthetic API/fake-agent checks from installed Dashboard,
provider entitlement/inference, Android/emulator/physical-device and production
verification. No live sign-in, portal submission, credentials or external agent
call forms part of this validation.
