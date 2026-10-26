# Agent integration

## Current behavior

The 2026-10-05 request supersedes the four-tab inventory candidate: Home, Agent,
Apps are the three primary tabs. Agent uses the existing ChatScreen route,
ChatViewModel, history, session IDs, server/profile settings, input controls and
live connection/error state. No new conversation database or transport is added.

The header selector lists Hermes first. bro is explicitly unavailable and opens
its explanation only. No bro submission, approval, cancellation or subscription
is implemented. The existing bro-to-Hermes relay does not prove Android-to-bro
connectivity. The Android app must never substitute another model for bro.

AgentCatalog remains a fail-closed boundary for future adapters, not the source
of Hermes Chat connectivity. Existing Chat controls use their original transport
and capability checks. Unknown agent IDs never fall back to Hermes.

## Navigation and preservation

- Agent has the original ChatScreen root and one conversation stack
- Legacy Chat tab selection, AgentScreen and Hermes detail routes resolve to it
- CHAT launch preferences migrate to AGENT, preserving stable history/settings
- Android saved stacks retain their old call positions; old hub details migrate
- History and notifications preserve their original session identifiers
- Agent tab presses reveal its root without creating a second conversation
- Apps reselect opens its root; returning from another tab restores its detail
- Authentication/profile scope, database, credentials and permissions are unchanged

The old AgentScreen composable entry delegates to Chat for compatibility. It is
not an additional tab. bro details remain read-only with history unavailable.

## Validation boundary

JVM tests cover three-tab order, aliases, preferences, stack migration, history,
notification session targets, management return and Apps return versus reselect.
Compose tests cover the selector, unavailable bro, unknown IDs, offline input,
three-tab chrome and absent duplicate Chat tab. Actual executed checks are listed
in the delivery report; source tests alone do not establish device behavior.

No server deployment, signing configuration or database migration is included.
This incremental patch targets the verified V4 source. The browser lifecycle
patch touches separate files and must be integrated before the combined build.
