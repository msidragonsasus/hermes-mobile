# OpenClaw conversation and bot settings

## User routes

- Agent → agent name → OpenClaw: native text conversation, history, new conversation,
  stop, model and effort controls. The gear opens OpenClaw connection and default settings.
- Agent → グループ: existing Hermes bot groups, opened directly without application management.
  The 会話 tab returns to the originating Hermes or OpenClaw conversation.
- Hermes/bot conversation → gear → bot's 既定モデル・推論強度: defaults for new
  individual and group conversations. The same page is reachable from bot editing and
  each group's participant settings. Editing another bot does not switch the active profile.
- Composer model/effort controls change the current conversation. Existing session settings
  take precedence over defaults. Hermes retains its existing supported reset behavior.

OpenClaw uses the existing Hermes `ChatInputBar`, `ChatBubble`, `FullBleedAgentMessage`,
`ConversationHeaderActions`, `ConversationMenuSheet`, and scroll controller. Its history
drawer lists only OpenClaw sessions; its search filters those loaded titles. The model and
effort controls appear inside the shared composer while composing, and are also available
under settings in **この会話**. New-conversation defaults have a separate settings section.
The saved connection's credential editor opens only when changing the connection. Healthy
connections do not occupy a permanent status/action row in the timeline. Gateway history
currently supplies text only to the UI; absent timestamps are not replaced with invented times.

OpenClaw is a separate Gateway connection, not a Hermes model/provider. Supply its actual
Gateway URL and token/password in the app. These credentials are never taken from Hermes.
The initial device appears as **Hermes Mobile** in OpenClaw pairing; approval is required.
Changing persistent OpenClaw defaults requires operator.admin, while conversation overrides
require operator.write. No server startup, authentication-policy changes or device installation
are performed by adding these screens.

## Transport and persistence

The integration follows the public [OpenClaw Gateway protocol](https://docs.openclaw.ai/gateway/protocol)
and references the [official Android implementation](https://github.com/openclaw/openclaw/tree/main/apps/android).
It uses existing OkHttp, Kotlin serialization and Bouncy Castle dependencies; no additional
paid service or external relay is introduced. OpenClaw's configured model route continues to
determine chat inference costs. Optional conversation naming uses a separate OpenRouter request
to `openrouter/free`, with zero prompt, completion and per-request price ceilings. Its dedicated
API key can be explicitly overridden in OpenClaw settings and encrypted on the device. Without
an override, the existing authenticated key-management API reads `OPENROUTER_API_KEY` metadata
and reveals only that configured key into memory. The value is never displayed, logged, or
persisted as a second copy. Owner checks before/after retrieval reject a changed connection.
The Gateway token is never used as an OpenRouter credential.

The client negotiates protocol 3–4 with challenge-bound Ed25519 device authentication and
req/res/event frames. It uses chat.history/send/abort, sessions.list/patch, models.list,
agents.list and the hash-guarded config.get/patch surface. Unadvertised methods and ungranted
permissions do not enable controls. Supported OpenClaw effort IDs come from Gateway metadata;
missing metadata does not produce guessed choices. A separate no-cookie HTTP client prevents
Hermes credentials being sent to OpenClaw. Plaintext WS is restricted at DNS resolution to
loopback, LAN and Tailscale/private IPv6 addresses; normal certificate validation applies to WSS.

Gateway credentials, device identity and issued device tokens use separate Keystore-backed
encrypted preferences scoped to the selected Hermes server connection. Switching Hermes bot
does not switch OpenClaw's endpoint or conversations. Changing server/logout closes the old
socket. OpenClaw messages are read from its own Gateway; Hermes sessions and local chat DB
are not repurposed. Drafts are retained separately per conversation in saved UI state.
Failed/unknown sends retain input and require history reconciliation before manual resending;
there is no automatic resubmission. Socket retirement settles pending RPCs, and events are
filtered by socket generation, session, run and per-run sequence.

OpenClaw persistent config writes patch the selected default agent using the current config
hash. Legacy agents.list arrays retain sibling entries and model fallbacks; newer agents.entries
use a sparse entry patch. A save does not retroactively rewrite existing conversations.
The first version supports individual text chat; OpenClaw participation in Hermes bot groups
and voice calling are outside this change.

## Connection recovery, names and notifications (2026-10-19)

The application owns the OpenClaw connection across navigation and activity recreation. A lost
socket reconnects with a 1–30 second capped backoff and reloads the selected history. Network
changes retire the old socket. Pairing, authentication, permission and protocol failures stop
retries and remain visible. Logout/connection changes cancel the old connection and title calls.
No send, abort, session patch or config write is automatically replayed.

Runs are tracked separately per thread in encrypted storage, so another conversation can be
opened while a reply is pending. Reconciliation only declares a missed completion when history
contains the exact new user turn and an explicit terminal assistant stop reason. Intermediate
tool output and an earlier identical prompt do not prove that the pending run finished.

Each opened thread receives a persisted first-message title. Using the existing server's
OpenRouter key or an explicit local override, its first user message (up to 1,200 characters) is sent to the free router for a short name.
Successful names stay stable. Unavailable/rate-limited naming keeps the first-message title,
with an hour between automatic attempts. The title request disables optional reasoning and
allows up to 1,024 output tokens: the earlier 96-token cap could finish with `length` and empty
visible content on reasoning models. A new attempt namespace retries previously failed names
once after this fix. Names are local to this app and Gateway endpoint;
the normal conversation model and server defaults are unchanged.

OpenClaw settings include background notifications. While enabled, a remoteMessaging foreground
service shares the same socket and shows a quiet connection indicator. Chat terminal events
(response, failure, abort) and `exec.approval.requested` produce private, per-thread notifications.
Streaming deltas are silent. Updates in the visible conversation are acknowledged without an
OS alert. Delivery IDs suppress repeated run/approval events. Notification taps carry only an
opaque token; its encrypted route must match the current owner and Gateway endpoint. They open
the original thread without approving a command. Lock-screen public content has no conversation
title or response text. Android notification permission is requested through the system UI.

The agent itself continues to run on the Gateway. This client does not introduce scheduled jobs,
a push server, or a guarantee after Android force-stop/process termination. Background receipt
requires the foreground listener and reachable Gateway. The service is retired on foreground
return or when notification listening is disabled.

## Verification boundary

Physical-phone follow-up (2026-10-19): installed version 36 (`1.0.36-openclaw-recovery`) on the
SC-51F with `adb install -r --no-incremental`. The signing certificate matched the installed
version, all 21 private-data file hashes were identical immediately before/after replacement,
and the original first-install timestamp stayed 2026-09-06 23:04:00. An encrypted-data backup
and the prior APK were retained outside the repository.

Live checks confirmed configured OpenRouter key metadata, free title generation from a
synthetic Japanese prompt without key reentry, socket-close recovery without the reconnect
button, selected-session/history retention, the background foreground-service indicator, a
private notification with generic public content, and opening the delivered notification into
the OpenClaw conversation via the actual system shade. The first notification test queried
delivery too soon; its later direct `PendingIntent.send()` also hit Android's background-launch
restrictions. System-shade group expansion and a real row tap established the UI route.
The notification payload used public synthetic test content; no paid chat run or live tool
approval was requested. The latest local OpenClaw unit tests passed 26 cases. The final whole-project Lint rerun was not completed
after host memory pressure; no final full-project Lint pass is claimed. The instrumented notification
route test now supports an external or human system-shade tap and observes foreground/session
flows together; the previous automatic shade drivers were unreliable on this Samsung device.

Earlier local-only stage: the connection/notification changes passed 101 selected unit tests (23 OpenClaw tests),
`assembleDebug`, `compileReleaseKotlin`, `checkColorLiterals` and `lintDebug`. ktlint 1.8.0
passes for all changed code. The full `ktlintCheck` currently reports 258 formatting violations
in 30 other, untouched files. Recovery tests include remote WebSocket close/reconnect without
send replay, timeout/auth/cancellation behavior, pending runs across thread changes, logout
retirement, free-only title request shape, HTTP rate-limit failure and title-call cancellation.
At that earlier stage no emulator/phone was connected; the physical-phone follow-up above supersedes
that earlier live Gateway, naming, notification-display and installation verification boundary.

Local protocol tests exercise a real mock WebSocket Gateway, challenge signing, RPC
correlation, rejected pairing and pending-request cancellation. Other tests cover explicit
Hermes bot scoping, sparse config patches, URL/network policy and stream ordering. Compose
tests cover direct group access and return to the selected conversation.

Live OpenClaw compatibility requires the installed Gateway version, reachable URL and approved
device. On 2026-10-19, APK32 was verified on the physical phone: authenticated connection,
message/response, process-stop/restart, restored history and a second message/response.
The subsequent shared-UI correction has separate emulator fixtures and interaction tests;
those screenshots do not establish phone installation or authenticated communication.
Hermes protected-file approval delivery, approval.respond and operation resumption remain a
separate verification path. Gateway autostart is also separate from this client UI.
