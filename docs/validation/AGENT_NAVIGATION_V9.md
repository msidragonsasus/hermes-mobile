# Agent navigation candidate, 2026-10-06

The supplied screenshot has a left conversation menu containing only three links.
This candidate places actual Hermes chat history and search directly in that menu.
It preserves the existing three-tab shell and all Apps reselect/return behavior.

## Implemented

- Direct chat titles, current-session highlight, paginated older chats, server-backed
  search, query clear, empty/loading/error/retry states, hidden-session exclusion in
  the normal list, search-hit deduplication, new-chat entry and full-history fallback.
- Existing SessionsViewModel/API, keyed by authenticated UI preference scope and
  separately from the full-history screen; transient search clears on dismissal.
- Selection uses NavigationController.openChatSession, including its pending
  offline navigation handling. Selecting the current chat only closes the drawer.
- Labeled Settings entry, with conversation-only model/search actions separated
  from Hermes connection/profile/skills/tools/configuration and app-wide settings.
- Current model is also a visible one-line control above the conversation.
- Agent selector explains Hermes and bro choices. bro remains unavailable and
  does not trigger a connection or send. No new transport or credentials added.
- Search immediately clears previous results during its debounce, avoiding stale
  results being selectable under a newer query.

## Validation status

Cloud source audit: XML parse and EN/JA key parity, all new resource references,
changed-path allowlist, UTF-8/newline preservation, no literal colors, no changes
in career/relay/tab controller files, and exact hash-checked patch round-trip.

Android compile/tests/lint/instrumentation NOT RUN here. Wrapper bootstrapping is
blocked by services.gradle.org DNS/network access; no cached Gradle or Android SDK.
The initial Unix wrapper attempt also encountered the exported CRLF shell script;
the Java-wrapper retry avoided that and established the dependency bootstrap block.
Do not describe synthetic test source as a test pass or live-server verification.

## Desktop verification

Use the existing authorized verification thread and a separate candidate copy.
Validate every before hash (or nonexistence) before applying the transfer. Preserve
application ID, signing key, user data and unrelated working-tree changes.

Run compileDebugKotlin, compileReleaseKotlin, compileDebugAndroidTestKotlin,
testDebugUnitTest, lintDebug, checkColorLiterals and ktlintCheck when available.
Run AgentHistoryDrawerTest, existing ChatNavigationUiTest and AgentHubScreenTest
instrumentation on an emulator. Also run existing AgentNavigationTest unit tests.

Manually verify Japanese and English, light/dark, 1.5x font, narrow screen and IME:
- Open menu; history is visible directly and scrollable
- Search, quickly replace query, clear; no old-query results during debounce
- Choose a past/current chat; exactly one target and menu closes
- Load more, offline/retry, empty account and no-match query
- Back, scrim and swipe dismiss; composer receives taps afterward
- Model affects current conversation; settings links return to prior chat
- Agent selector preserves bro unavailable, Hermes identity and existing history
- Home/Agent/Apps switching; Apps reselect returns root but switch-back restores state

No actual message, credentials, remote settings, deployment or installation was
changed as part of producing this source candidate.
