# AGENTS.md

Repository-specific guidance for Hermes Mobile, a Jetpack Compose / Material 3
Android client for Hermes Agent over trusted LAN or Tailscale. Package:
`com.m57.hermescontrol`. Verify current behavior in code; specifications also
contain target designs and historical audit findings.

## First principle: consistency

Visual and behavioral consistency is the first design and implementation criterion.
The same user intent and state must follow the same rules across features. Reuse
shared components and logic; do not copy a feature and let its behavior drift.
User preferences and required correctness, accessibility, security, and data
boundaries remain binding. Details belong in DESIGN.md and ARCHITECTURE.md.

## Working boundaries

- Before editing, confirm repository root, remote, branch, HEAD, and existing diff.
  Work in the user's fork (`msidragonsasus/hermes-mobile`), not the Git-free parent
  or an `.audit` checkout. Do not reset to a historical specification commit.
- Preserve unrelated changes and stay within the requested scope. Plans and later
  phases are context, not authorization to start more work.
- Push, PR publication, merge, device/server deployment, host-wide settings, and
  external writes require user authorization. Use authorization already given
  in the conversation; prepare and validate authorized local work before asking.
- Preserve installed app data, applicationId, signing identity, settings, and DB.
  Never fix failures by uninstalling, clearing data, or destructive DB recreation.
  Use explicit migrations; if preservation fails, stop with a recoverable error.
- Keep text files in UTF-8 and preserve existing BOM/newlines. Verify decoding
  before attempting an encoding repair.
- Reuse the app, shared components, navigation, and dependencies. No blank-project
  replacement, bulk rewrite, GPL code copying, or remote DEX/JAR/SO loading.

## Read for the affected area

Read the relevant sections, not every document for every task:

- UI, including copy, states, accessibility, navigation, and theme:
  [DESIGN.md](DESIGN.md) and [UX_SPEC.md](docs/personal-ai-spec/UX_SPEC.md).
  Flag contradictions with design decisions explicitly before diverging.
- Architecture, transport, authentication, or persistence:
  [ARCHITECTURE.md](docs/personal-ai-spec/ARCHITECTURE.md).
- Security, approvals, external effects, or data handling:
  [SECURITY.md](docs/personal-ai-spec/SECURITY.md). Requirements in this document
  are not evidence that the current implementation enforces them.
- Mini Apps: [MINIAPP_PLATFORM.md](docs/personal-ai-spec/MINIAPP_PLATFORM.md).
- Product scope: [PRODUCT_SPEC.md](docs/personal-ai-spec/PRODUCT_SPEC.md).
- Plan and audit history: [IMPLEMENTATION_PLAN.md](docs/personal-ai/IMPLEMENTATION_PLAN.md).
  Additional document map: [CODEX_START_HERE.md](docs/personal-ai-spec/codex/CODEX_START_HERE.md).
- Project overview: [README.md](README.md). Contribution conventions:
  [CONTRIBUTING.md](CONTRIBUTING.md); its publication steps apply only when authorized.

## UI design continuity

- Follow the user's explicit design preferences first, then [DESIGN.md](DESIGN.md)
  and UX_SPEC, then shared components/theme tokens. Existing UI and Material
  defaults do not override a rejected design or authorize its reintroduction.
- Use the existing native Material 3 Expressive system. Before UI implementation,
  read [design continuity](DESIGN.md#design-continuity-and-inputs) and identify the
  shared component to reuse; do not invent a separate look for each feature.
- Do not introduce box-like, square input fields or decorative enclosing outlines.
  Reuse the shared ChatComposer for conversations and theme-based rounded Material
  fields for forms. Material imports alone do not establish visual compliance.
- Check the rendered affected UI against these rules, including relevant themes,
  enlarged text, and keyboard states. Report visual checks that were not run.

## Shared implementation and change impact

- Before adding behavior, trace existing implementations and their callers. Reuse
  or extend the canonical component, state handling, validation, or operation;
  do not create a screen-local copy. Check reusable public implementations before
  writing new code, as instructed by the workspace policy.
- Keep genuinely different backend capabilities and security/data scopes explicit;
  share the common contract without pretending different operations are identical.
- For shared changes, identify affected callers and verify relevant behavior there,
  including failure states. Fix shared causes centrally; avoid per-screen patches.
  See [consistency rules](docs/personal-ai-spec/ARCHITECTURE.md#implementation-consistency).

## Implementation guardrails

- Keep Navigation3, the single `:app` structure, and existing manual construction /
  ViewModel factories. Module extraction or Hilt adoption requires separate scope.
- Route screen navigation through `NavigationController.navigateTo()`; never add
  screen keys directly to the back stack from UI callbacks. Preserve deduplication.
- Drawer gestures are screen-owned: use `HermesScaffold(drawerGesturesEnabled = ...)`
  or `DisableDrawerGestures()` for entry screens without a scaffold. Do not add
  global gesture overrides or defensive drawer-closing effects.
- Use `HermesScaffold` and shared loading/error/empty states. Its content already
  receives insets: do not apply `paddingValues` again, including on state views.
  Outside this scaffold, inspect the actual inset owner. See [layout rules](DESIGN.md#layout).
- Activity-scoped ViewModels survive navigation. Clear transient screen flags on
  disposal using the existing cleanup pattern so re-entry does not reuse stale state.
- Preserve socket/auth lifecycle: disconnect `HermesWsClient` before clearing
  tokens on logout; connect after `ApiClient.rebuild()` on login. Keep Bearer,
  cookie, token-WS, and one-time-ticket authentication paths distinct.
- Use host theme tokens and `LocalHermesStatusColors` for semantic status colors.
  Avoid hardcoded colors; `checkColorLiterals` defines the allowed exceptions.
  Read composable theme values before entering non-composable lambdas.
- Preserve required experimental API opt-ins. Dependencies imported by `main/`
  must be available to release builds, not only through `debugImplementation`.
- Read dependency/tool versions from [the version catalog](gradle/libs.versions.toml)
  and [app build configuration](app/build.gradle.kts); inspect the current DB schema
  and migrations in code rather than copying historical version numbers.

## Security essentials

- HTTP/WS is intentional only on trusted LAN/Tailscale; do not expose Hermes publicly.
  Manifest permissions do not prove runtime host/origin enforcement.
- Never log tokens, cookies, secrets, message contents, or payloads, even in debug.
  Do not enable BODY/HEADERS logging; check URL, exception, and export paths too.
- Do not commit signing keystores or credentials. Preserve existing credential storage.

## Validation

Run checks appropriate to the diff. With a local Android SDK and JDK 21:

```sh
./gradlew assembleDebug          # APK build
./gradlew testDebugUnitTest      # unit tests
./gradlew ktlintCheck            # Kotlin formatting
./gradlew checkColorLiterals     # semantic color guard
./gradlew lintDebug              # Android lint
./gradlew compileReleaseKotlin   # release variant compilation
```

On Windows use `gradlew.bat`, UTF-8, and matching ktlint plus Git sh in the process
PATH. Formatting follows `.editorconfig` and the configured ktlint version.
If the SDK is unavailable, report unavailable checks; do not push just to use CI.

For documentation-only changes, check links, specification/schema consistency,
and diff scope; do not rerun Android checks without a reason. For code changes,
choose relevant tests/checks and stop expanding verification after they pass
unless a change, failure, or unresolved concern warrants more. Never weaken tests.
Report failures and untested areas accurately; distinguish local checks, emulator,
physical device, authenticated/live behavior, and deployment. Dated audit evidence
and skipped CI jobs are not new passes. CI details live in
[android.yml](.github/workflows/android.yml); release details in
[release.yml](.github/workflows/release.yml).

## Authorized Git publication

Integrated changes require a PR targeting the user fork's `dev`; never push directly
to `dev` or `main`. Follow [CONTRIBUTING.md](CONTRIBUTING.md). Use Conventional Commits
with a subject and at most two body lines; annotate bug fixes with their tracking ID.
Keep default Git authorship; no `--author` override, `Co-Authored-By`, or
`Generated with` trailers.
