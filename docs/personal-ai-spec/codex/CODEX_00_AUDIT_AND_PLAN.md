# Codex Task 00 — Repository Audit and Implementation Plan

## Role

You are the senior Android architect preparing an existing Hermes Android client
for a Personal AI Shell and Mini App platform.

## Repository baseline

Adopted fork baseline (upstream: Hy4ri/hermes-mobile):

```text
msidragonsasus/hermes-mobile
branch: dev
reference commit: fd485a69990b358f81d8dd4c12c761e7d2555d24
```

Do not assume the working tree exactly matches this commit.
Report the actual commit first. Do not reset to the original specification
reference 5c8ab7e. When an accepted audit exists, check HEAD changes and only
refresh affected evidence; do not rerun this whole task for documentation PR 00.

## Read first

Read, in this order:

1. root `AGENTS.md`
2. root `DESIGN.md`
3. this specification package:
   - `README.md`
   - `00_DECISION.md`
   - `PRODUCT_SPEC.md`
   - `UX_SPEC.md`
   - `ARCHITECTURE.md`
   - `MINIAPP_PLATFORM.md`
   - `SECURITY.md`
   - `ROADMAP.md`
   - `DESIGN_REVIEW.md`
   - `adr/*.md`
   - `codex/AGENTS_ADDENDUM.md`

## Objective

Produce a code-grounded implementation plan for Phase 0 and Phase 1.
Do not implement the product in this task unless a tiny documentation-only
or test-only correction is required to complete the audit.

## Required inspection

### Build and repository

- current branch and commit
- git status
- Gradle modules
- Android/Kotlin/Compose/Room versions and actual DI (Hilt currently absent)
- CI jobs
- build, unit-test, lint, ktlint commands
- license
- secrets and signing setup

### Navigation and UI

- current root navigation
- `ScreenRegistry`
- `NavigationController`
- drawer/bottom navigation
- chat/session/activity-related screens
- reusable components
- theme constraints
- process recreation behavior

### Hermes integration

- REST clients
- WebSocket/TUI Gateway clients
- auth modes
- session creation/resume
- streaming events
- tool events
- approval/clarification
- cron/jobs
- notifications
- capability/version endpoints
- reconnect logic

### Persistence

- Room entities/DAOs
- encryption
- credentials
- draft/message state
- migration strategy

### Feasibility

Verify whether the proposed:

- `HermesTransport`
- capability negotiation
- domain event normalization
- `AppContribution` registry
- 4-tab shell
- Advanced screen grouping
- manifest parser

fit the existing code without a rewrite.

## Hermes version verification

Inspect the current Hermes Agent docs/source or the locally installed Hermes version.
Do not hard-code assumptions from this document.

Specifically verify the current status of:

```text
NousResearch/hermes-agent#84406
```

If unresolved, plan around it with protocol/capability fallbacks.

## Deliverable

Create or update:

```text
docs/personal-ai/AUDIT_REPORT.md
docs/personal-ai/IMPLEMENTATION_PLAN.md
docs/personal-ai/RISK_REGISTER.md
```

### AUDIT_REPORT.md must include

- actual baseline
- architecture map
- endpoint/protocol map
- current feature inventory
- build/test results
- gaps against the product spec
- discrepancies in these documents

### IMPLEMENTATION_PLAN.md must include

- proposed files/modules to change
- sequence of small PRs
- each PR's acceptance criteria
- migration strategy that preserves current behavior
- explicit stop points
- rough complexity: S/M/L, not time estimates

### RISK_REGISTER.md must include

- risk
- probability
- impact
- detection
- mitigation
- owner/component

## Constraints

- Do not replace the app with a blank project.
- Do not start a big-bang multi-module conversion.
- Do not copy GPL code.
- Do not load dynamic native code.
- Do not add external analytics.
- Do not expose Hermes publicly.
- Do not invent endpoints.
- Do not silently contradict upstream `AGENTS.md` or `DESIGN.md`.
- Ask only when a required secret, device, or irreversible decision is missing.

## Verification commands

Run all commands that are feasible in the environment.
At minimum attempt the repository's documented:

```text
./gradlew ktlintCheck
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

If Android SDK is unavailable, say exactly which commands could not run and why.

## Final response

Return:

1. audit summary
2. highest-risk finding
3. recommended first PR
4. commands and results
5. created document paths
6. unresolved questions that truly block implementation
