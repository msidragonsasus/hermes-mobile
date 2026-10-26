# Group session 404 follow-up — 2026-10-18

## What the version 30 message proves

`元のセッションが見つかりませんでした` was selected when `WsGroupSessionTransport.readHistory()` received HTTP 404 from `GET /api/sessions/{storedSessionId}/messages?profile={turn.member}`. Version 30 discarded the error body's distinction between a missing session, missing profile and missing route/proxy endpoint. It did not query the session list. Therefore a session-list pagination failure cannot directly produce this message. A missing prompt outside the first 200 messages produces a different status.

This is evidence of a failed exact-ID history read, not evidence that execution failed or that the session was deleted globally. The original post must remain UNKNOWN without a retransmission.

## Identifier and scope trace

- A matching `session.create` ACK supplies `session_id` (runtime route) and `stored_session_id` (durable DB key). The group socket capability binds both to the exact bot member and current connection generation. `prompt.submit` uses the runtime ID and fixed bot profile.
- Before submitting, `GroupTurnRecord` durably stores both IDs, bot member, conversation/message IDs and the exact prompt hash. History reads use the stored ID and original bot member. `ProfileScopeInterceptor` preserves explicit `profile=`; a selected global profile does not replace it.
- The Android archive key includes the configured server base URL, selected connection profile, active server profile and group. URL aliases are separate archive scopes; they are not guessed to be the same server. Reconnection changes process/generation routing capabilities, not archived runtime/stored IDs. A fresh turn gets a fresh session; a reconnect does not recreate or retransmit old turns.
- Legacy migration imports available log messages once; it does not invent session IDs or turn records. A legacy log entry without a saved turn cannot itself trigger this exact-key 404 path. Version 31 does not alter the version-1 archive format, encryption, settings or Room schema.

No direct runtime/stored-ID swap was found in this path. Server-side ID aliases, profile rename/removal, an unpersisted lazy session, failed server persistence, a different serving store and reverse-proxy/route 404 remain distinct possibilities; they are not established explanations for this user's actual post.

The retained local backend reference resolves REST history in the explicitly requested profile's DB, and creates durable rows lazily after session creation. Its WS profile resolver may fall back to the launch profile for an absent named home, while REST rejects an absent profile. This reference explains a possible mismatch, but is not proof of the deployed implementation or a reason to guess another profile. No fallback search or server change was added.

## Corrective scope

- Classify bounded structured 404 details. A known absent-profile response is separate. A generic/HTML/unknown route 404 is not a missing-session finding.
- Corroborate a session-specific 404 with the existing exact-ID detail endpoint in the same bot profile. Only two explicit `Session not found` responses produce the narrowly worded current-request/current-store absence status; this remains a server report, not proof of global deletion or correct server-side profile routing. Authentication/permission and service failures retain their own classifications.
- Decode the history response's optional canonical `session_id`. If it differs, reject the transcript without changing the saved IDs. A legitimate compaction/resume alias still requires lineage evidence; no other conversation's history is adopted by assumption.
- Read at most five 200-message pages when the server returns pagination ordering metadata, validating echoed offsets/counts when supplied. Combine latest pages chronologically, stop on repeated/malformed pages, and distinguish an incomplete window from an exhaustive read. Legacy unpaged responses remain usable evidence but do not establish completeness.
- All outcomes retain UNKNOWN unless the existing exact-request/completion evidence proves otherwise. No prompt retry, resume, session creation, model call, cross-profile scan, history deletion or marker removal occurs during reconciliation. Additional requests are GETs only.

## Live evidence and limits

Authorized normal-account SSH reads identified the active unit's working directory and executable location. Current source candidates were not readable and earlier DB reads were denied; no privilege bypass was attempted. No deployed response body, original session ID or per-bot DB row could be inspected. The user's original and newly reported posts remain unclassified as accepted/completed/failed.

Minimal requested context is whether the already reproduced post was the first post in a newly created conversation or an additional post, plus an already displayed numeric RPC code and per-bot state. No new model submission, message body or credential is needed. After a user-performed update, the existing **Check message status** action can collect the new read-only classification without resending.

## Validation and artifact

Final results:

- **106 focused unit tests passed**, zero failures/errors/skips. This includes 12 new Retrofit/MockWebServer tests with the actual profile interceptor and one new ViewModel test for incomplete history retaining UNKNOWN without resubmission. Existing migration, independent conversations, persisted unknown turns, reconnect, late replies, concurrent operations, real-client synthetic socket routes, protected-instruction approval and notice-presentation tests are included.
- **9 portrait and 6 landscape emulator tests passed** with synthetic content. Existing full-screen notice loading/error/long-body/actions/saved-state behavior and encrypted group archive/conversation selection are retained.
- The emulator was running version **30** before the `install -r` update. Its existing encrypted `hermes_group_conversations.xml` SHA256 was identical before and after updating to 31, before launching tests. No uninstall, clear-data or database recreation was used. This is emulator evidence; an actual phone update was not performed.
- Final local Gradle invocation completed successfully: `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `compileReleaseKotlin`, `checkColorLiterals`, `lintDebug` and `lintVitalRelease` (artifact reuse/up-to-date tasks included). Targeted ktlint passed on all eight changed Kotlin files. Repository-wide ktlint was not rerun; prior unrelated baseline failures are not claimed as fixed.
- Earlier attempts hit Kotlin heap exhaustion, then native JVM allocation failure while starting the emulator alongside the build. Those failed logs are retained. The final invocation used only process-scoped 2 GB/single-worker/in-process-compiler flags in a dedicated process; emulator tests ran after it exited. No host-wide memory, SDK or Gradle settings were changed and no shared daemon was stopped.
- APK signature, ZIP CRC and 16 KB alignment passed against the exact retained v30 APK. Application ID, Google client declaration and trusted-tailnet declaration match; permissions, SDK values, four ABIs and all 12 native libraries match. Git metadata changed from `719a809` to `719a8098`, both abbreviations of the same unchanged HEAD, not a connection/auth change. The compiled release comparator recognizes 31 as newer than 30 and rejects the reverse comparison.
- The saved v30 notice-reader/authentication/database/socket source hashes and all nine protected-approval source hashes match. Required approval/conversation/Room classes remain in the final DEX. No deployed model request, authenticated live history response, real protected approval, physical-device installation, production restart/change or push was performed.

Artifact:

- `E:\OSHIIRE\software\Hermes\deliverables\Hermes_Group_History_v31_20261018.apk`
- Application ID `com.m57.hermescontrol`; versionCode **31**; versionName `1.0.31-group-history-local`.
- Release/R8 APK, **16,427,948 bytes**, existing local Android Debug signing identity.
- APK SHA256: `b8cd3c48699ff222f8467316aa37c2a620c8408111fa0bef2747be202fbaeb55`.
- Certificate SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b` (same as v30).
- Library ID `libfile_adf2c4114200819191d4835a2c7b5ebb`, current version **3**; returned file ID `file_00000000ed3c81f5a2deacfe59a1ccbe`.
- [Download v31](https://chatgpt.com/api/library/files/libfile_adf2c4114200819191d4835a2c7b5ebb/download).

Library registration succeeded. The official helper again could not persist Windows xattrs because `os.setxattr` is unavailable; no workaround was used and local xattr persistence is not claimed.

Evidence and scripts are in `.audit/group-session-404-20261018/`: `build-v31-final.log`, `v31-validation.json`, `ktlint.log`, `version-comparison.log`, `emulator-before-version.txt`, `emulator-pref-preservation.json`, `emulator-verified-portrait.log`, `emulator-verified-landscape.log`, `library-v31.json` and the retained failed-build logs. v30 source baselines and hashes were saved before editing. The prior notice-reader, group-conversation and protected-approval evidence is retained.
