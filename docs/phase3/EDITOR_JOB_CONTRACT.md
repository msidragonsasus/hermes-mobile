# Natural-language App editor job contract

Implemented locally, with mocked model execution only. Production grants and
external model execution remain disabled. No completed APK is distributed.

The existing one-company workbench now accepts `propose` with a natural-language
goal, immutable base release/revision and operation UUID. A durable editor job
pins company/profile, three input code files and source hash, and an explicitly
configured provider/model/reasoning setting. With no injected runner it remains
`awaiting_executor`; it does not infer a default model or enable itself.

The new `career-app-editor` is an ephemeral role/session, not an existing agent
instance or bro/DSH identity. `HermesEditorRunner` reuses the existing
`coach_runtime.make_agent` adapter and its existing configured authentication/
billing path. It enables no tools, history, context files, memory or trajectories.
Only the request plus `protocol.json`, `mobile_screen.json`, `mypage_handler.py`
and nonsecret target/execution metadata are supplied. The host, rather than the
model, stores proposed code in the dedicated workbench code-state database.
Stored portal credentials, Career facts/documents/messages and page bodies are
not part of this input. No filesystem/shell/browser/deployment tools are offered.

The initial supported Bot language now includes scoped `read_state`, if/else,
Boolean/comparison conditions and early enqueue/refusal returns. It can change
real state-dependent Bot behavior, rather than merely selecting two modes.
It is an AST interpreter: no eval/exec/import/loops or arbitrary Python. General
Python and changes to the wider scraper/credential/runtime modules remain outside
this editor contract; enabling those requires a separately reviewed isolated
executor and scope. The supported interpreted policy needs no OS sandbox grant.

Generation commits a candidate, not an activation. The same job records proposal,
tested and applied hashes, manual correction invalidates its test, and a reviewed
apply names the exact displayed files hash. The fixed harness renders all allowed
states and executes interpreted Bot decisions through a synthetic scoped adapter.
Apply preserves the existing data DB/credentials/recipes. The old release pointer
remains active until explicit test/review/apply; rollback is code-only.

Every job has one transactional claim/lease. Duplicate requests/workers cannot
duplicate model execution. Cancellation revokes the lease before interrupting a
configured runner; late results cannot overwrite a cancelled or newer candidate.
Old base/revision/changed execution produces a conflict. Recovery reads receipts
and job state. Startup does not blindly re-run a queued/running/unknown model job.
An unresolved running job can be explicitly cancelled and a new request created.
Native pending persistence remains opaque ID/action/hash/base/revision only.

The authenticated API adds GET
`/api/plugins/career/mobile/workbench/{company_id}/editor-job` and fixed workbench
actions `propose`, `cancel_editor`, `apply_reviewed`. The existing v1 snapshot,
manual actions, auth/session/origin guard and SDUI contract remain compatible.
An old server without the editor endpoint retains the manual workflow. No HTTP
grant/model/directory creation or arbitrary action/path exists.

## Concrete approval facts and remaining unknowns (historical)

The following host/SSH observations belong to the earlier Windows-agent approval
record; they are not fresh access or runtime findings for this implementation.
The source now implements the exact-target bootstrap described in
[SERVER_ACTIVATION.md](SERVER_ACTIVATION.md). Activation still requires separately
reviewed real target/execution values and deployment; no production target/model
was selected or validated. The implementation report records a legacy full-suite
fixture isolation issue separately from the synthetic activation checks.

* Host: `100.103.190.68`; verified running service
  `home-hermes-dashboard.service`; verified service user `hermes`.
* SSH now works as `keiya` with the existing local identity
  `C:/Users/keima/.ssh/hermes_desktop_ed25519`. The earlier denial used defaults
  that were absent; the existing key resolved authentication. No key/agent/
  account/permission change was needed or performed.
* The plugin files and `/var/lib/hermes/career/career.sqlite3` are unreadable to
  that SSH user. Existing `sudo -n -u hermes` requires a password. Current registered
  company/profile, selected model/effort, runtime version and active job counts
  therefore remain unverified. Synthetic IDs/models are not production candidates.
* Minimal remaining human step is one read-only metadata command under the existing
  `hermes` account using the user's existing sudo password. The prepared
  `run_readonly_metadata.ps1` uses interactive SSH for that password; it is not
  stored or sent to an agent. Its Python script selects one existing active MyPage
  company/profile and only whitelisted model/service fields using SQLite mode=ro.
  It never imports the live Store/credential resolver or invokes a model.
* Source-supported billing: an explicit `openai-codex` selection uses the existing
  Codex OAuth/ChatGPT backend path, with paid API fallback blocked by the existing
  adapter. Other explicitly configured providers use their existing Hermes routes.
  No actual provider/model is selected for the new role until the real metadata
  and a specific choice are approved. No new credentials are required by design;
  validity of the existing model login has not been checked or refreshed.
* After actual company/profile are supplied, the proposed exact allocation is
  `/var/lib/hermes/.hermes/app-workspaces/career.mypage/<profile>/<company_id>/`
  owned by the existing `hermes` service user. Only `code-state.sqlite` and its
  scoped code/job records are writable there. No live plugin/data directory is
  available to the model. No arbitrary command or general Python executor grant
  is needed for this interpreted contract.
* Initial activation requires reviewed deployment of the changed code into
  `/var/lib/hermes/.hermes/plugins/career/`, the above target-specific allocation,
  explicit runner/execution/auto-run injection, and one approved service restart.
  Restart affects this shared Dashboard's REST/WebSocket connections and in-process
  App workers; drain/check active jobs first. Other host services are outside scope.
  Subsequent interpreted App payload apply/rollback does not restart the service.
* The future model call sends request/code to the explicitly approved existing
  provider. The model receives no credential values. The host transport uses its
  existing authentication internally. Nothing was sent to a real model during
  local/mock verification, and bro/DSH was not executed.

Do not activate using placeholder IDs or guess a model. Metadata readback is the
remaining prerequisite for an exact one-time approval request.
