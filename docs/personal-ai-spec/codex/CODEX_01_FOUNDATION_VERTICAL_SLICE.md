# Codex Task 01 — Personal AI Shell Foundation Vertical Slice

## Preconditions

- Task 00 audit is accepted and PR 00 documentation alignment is reviewed.
- A new explicit request authorizes the specific implementation PR; this file is a Phase 1 reference, not permission to implement all sections at once. Follow IMPLEMENTATION_PLAN.md.
- Keep single :app, manual DI, Room 3, package/signing identity, and existing data.
- Read:
  - root `AGENTS.md`
  - root `DESIGN.md`
  - `docs/personal-ai/AUDIT_REPORT.md`
  - `docs/personal-ai/IMPLEMENTATION_PLAN.md`
  - `codex/AGENTS_ADDENDUM.md`
  - all architecture/spec files in this package
- Work on a new feature branch.
- Report current commit and clean/dirty status before editing.

## Goal

Implement the smallest production-quality vertical slice that proves:

1. the existing Hermes client can support a Personal AI Shell;
2. protocol details can be isolated behind a transport boundary;
3. attention items can be rendered safely;
4. future features can contribute without extending one giant ScreenRegistry;
5. Mini App manifests can be parsed and validated.

Do not implement the full roadmap.

## Required scope

### A. Transport boundary

Introduce a minimal `HermesTransport` abstraction around the existing live client.

Requirements:

- preserve current behavior
- adapt existing REST/WS implementation; do not rewrite networking
- expose connection state
- expose capability state
- expose normalized events
- add `FakeHermesTransport` for tests/previews
- no raw endpoint calls from newly added ViewModels

Suggested domain events:

```text
MessageDelta
MessageCompleted
ToolStarted
ToolProgress
ToolCompleted
ApprovalRequested
ClarificationRequested
RunStateChanged
ConnectionChanged
ProtocolError
```

Use actual repository conventions and types; names may be adjusted if justified.

### B. Capability negotiation

At connection/startup, detect available Hermes features using actual supported
health/capability/handshake surfaces.

Requirements:

- store Hermes version if available
- do not crash on old/partial servers
- disabled controls explain why
- transport-specific fallback stays inside transport layer
- add unit tests for full, partial, and malformed capability payloads

### C. Contribution registry

Replace or wrap the hard dependency on the single hard-coded screen list
with an injectable/contributable registry.

Requirements:

- preserve all existing screens
- no big-bang Gradle module extraction yet
- use manual registry/constructor injection; Hilt is absent and not required in Phase 1
- navigation deduplication behavior must remain intact
- create a path toward Navigation 3 feature `api/impl` modules
- add tests for duplicate IDs/routes and deterministic ordering

### D. Four-tab shell

Add primary navigation:

```text
Today / Chat / Activity / Apps
```

Requirements:

- Chat reuses the existing chat implementation
- existing management/config screens remain reachable through Advanced
- do not delete or duplicate current screens
- preserve deep links/notification session navigation
- explicitly validate saved tab/stack and pending notification recreation; current remember-only stack does not already provide this guarantee
- maintain one-handed access and accessibility

### E. Today

Implement an initial native Today screen with:

- Needs You
- Active runs
- recent completion
- global composer entry point
- empty/loading/offline/error states

Data may use live repositories where available and deterministic fake data in
previews/tests. Do not fabricate live server state.

### F. Activity

Implement a basic timeline with:

- Live now
- Needs input
- Recent
- Run status
- tool step summary
- stop/open actions where capability allows

### G. Approval and clarification cards

Implement structured UI.

Approval must show:

- action
- target
- effect/risk
- source run
- expiry if available
- Reject / Modify / Approve once

The fork already offers session/always approval. The separate approval-policy PR
must restrict UI and transport to once/deny, bind the exact original request, and
wait for resolution ACK. Modify is a supported review/revision flow, not an invented
endpoint. Gate it when unsupported.

Clarification must support:

- options if present
- free text
- response bound to the original request/run

If the live Hermes protocol cannot support a path safely, gate it and prove UI
with `FakeHermesTransport`. Document the backend limitation.

### H. Mini App manifest foundation

Implement:

- Kotlin serializable model
- parser
- validator
- permission type
- entry types: native / sdui / web
- load the three sample manifests as test fixtures
- no SDUI renderer yet
- no WebView yet
- no remote install UI or manifest network fetch yet
- strict camelCase decoding follows additionalProperties:false; reject unknown fields and duplicate IDs, and resolve only registered built-in native feature IDs

Use `schemas/miniapp-manifest.schema.json` as the contract.
Kotlin validation must enforce security-critical constraints even if JSON Schema
is not executed on-device.

## Explicit non-goals

- no remote Mini App catalog
- no SDUI renderer
- no WebView host
- no FCM
- no widget
- no new voice/camera features; preserve existing functionality
- no full Gradle modularization
- no package rename
- no Play Store work
- no autonomous external writes
- no Hermes backend changes; propose a separate compatibility contract and stop if one is needed

## Required tests

### Unit

- event mapping
- capability parsing
- capability gating
- duplicate contribution rejection
- manifest validation
- permission parsing
- attention lifecycle
- unknown/expired approval handling

### UI/Compose

- Today empty/loading/offline/content
- Activity live/attention/recent
- Approval card
- Clarification card
- bottom navigation
- large font or accessibility semantics for core controls

### Regression

- existing session opens from notification
- existing chat streams
- existing Advanced screens remain discoverable
- logout/login reconnect behavior remains correct

## Quality gates

Run the repository-required checks, including:

```text
./gradlew ktlintCheck
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Run any existing instrumented/CI checks feasible in the environment.

## Commit structure

Prefer atomic commits:

1. follow the accepted small-PR sequence (PR 00 docs/ADR alignment is separate)
2. transport/capability foundation
3. contribution registry
4. shell navigation
5. Today/Activity/attention UI
6. Mini App manifest foundation
7. tests/fixes

Do not mix large formatting churn with behavior changes.

## Required documentation

Update:

```text
docs/personal-ai/IMPLEMENTATION_STATUS.md
docs/personal-ai/PROTOCOL_COMPATIBILITY.md
docs/personal-ai/SCREEN_MAP.md
```

Record:

- implemented
- intentionally deferred
- backend limitations
- test results
- screenshots
- migration notes

## Stop condition

Stop after this vertical slice passes quality gates.
Do not continue to Phase 2 or Phase 3 without a new task.

## Final response

Provide:

1. summary
2. architecture changes
3. UX changes
4. test/build results
5. screenshots/artifacts
6. known backend limitations
7. exact next recommended task
