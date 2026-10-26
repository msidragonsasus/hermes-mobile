# Group send metadata preview — v32, 2026-10-19

## Result and user flow

Each existing user post in a group conversation has a **送信診断 / Send diagnostics** button. The dialog first asks the user to select one saved bot turn, then previews only that turn's metadata. **診断情報をコピー / Copy diagnostics** explicitly copies exactly the preview. It does not automatically share, send, reconcile, resume, or invoke a model. Multiple discussion turns from the same bot remain individually selectable.

The copied JSON contains only a schema version, bot name, saved runtime/stored session IDs, saved delivery state, the message's recorded UTC time, missing-field declarations, and sanitized endpoint host/port/base path. It contains no message/prompt/response/tool content, title, draft, prompt hash, credential, cookie, encryption key, URL userinfo/query/fragment, or another turn's metadata. Sensitive-labelled or control-character URL paths are omitted. Invalid IDs are marked missing rather than copied as arbitrary text.

The endpoint is explicitly labelled as the archive configuration, not a captured historical request. The message timestamp is not presented as a send/ACK/save timestamp. v31 did not persist individual turn-save times or ACK details; these remain `not_recorded` even when delivery is ACCEPTED/COMPLETE. No information is reconstructed from candidate IDs, delivery state, message contents, or the current clock.

A legacy post without a saved turn record displays that the metadata is unavailable and cannot be recovered here. There is no copy action in that state. The feature cannot restore identifiers that an earlier app never saved.

The copy callback rechecks the conversation scope, selected post and exact projected turn immediately before copying. Switching conversation/scope clears the selection. No new navigation route, exported component, Android permission, dependency, authentication change, DB migration, archive-schema change, or persistence write was added. Existing activity-scoped ViewModel lifecycle and scaffold/inset behavior are retained.

## Change boundaries

Added `GroupSendDiagnostics.kt` (explicit read-only projection and URL sanitization), `GroupSendDiagnosticDialog.kt`, translated strings, seven projection/privacy tests, one ViewModel non-mutation test, and three UI tests. Existing `GroupChatScreen.kt` and `GroupChatViewModel.kt` only connect the entry and read accessor. Existing group transport, history reconciliation, encrypted storage, model/connection settings, notice reader, approval handling and parallel Apps changes are retained.

Before-edit source hashes and exact screen/ViewModel copies are retained in `.audit/group-diagnostics-20261019/`. The selected checkout remains the user fork `msidragonsasus/hermes-mobile`, branch `feat/career-coach`, HEAD `719a809810f69503e2aafcc5ba5a0e295b63e27f`. No commit, push, PR publication, server update or physical-device operation is part of this implementation turn.

## Validation

Final results:

- **114 focused unit tests passed**, zero failures/errors/skips, including seven new projection/privacy tests and the ViewModel non-mutation test. Existing group conversation/history/reconnect, typed socket routes, protected instruction approval and notice presentation cases are included. This is the selected suite, not every repository unit test.
- Final Gradle invocation: **BUILD SUCCESSFUL in 10m 24s**. `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `compileReleaseKotlin`, `checkColorLiterals`, `lintDebug` and `lintVitalRelease` passed (including valid cache/up-to-date reuse).
- Targeted ktlint **1.8.0** passed for all seven changed Kotlin files. Repository-wide ktlint was not rerun; unrelated prior baseline issues are not claimed as fixed.
- Three new UI instrumentation tests compile: explicit selection/copy at large font, scope-switch selection clearing, and missing legacy records. They were **not executed**, because device UI operation was excluded. No physical-device or emulator UI validation is claimed for v32.
- All tests use synthetic data; no real model request was made. Unit assertions verify unchanged archive/UI state, store-write count, history-read list, submissions and interrupts across repeated diagnostic reads.
- Compared against the retained v31 APK: same application ID, signing certificate, permissions/SDK, configured Google client/trusted endpoint declarations, four ABIs and all 12 native libraries (byte identical). Signature verification, ZIP CRC and 16 KB alignment passed. Both git abbreviations identify the same HEAD. No installed-app update/data-preservation test was performed for v32.
- Before-edit hashes cover 836 existing Android source/resource/build files. Only the intended screen, ViewModel and ViewModel-test files changed; six source/resource/test files were added. UTF-8 and original LF/no-BOM encoding were checked. All other baseline files are unchanged.
- The old October 16 protected-source guard initially flagged `ChatViewModel.kt` and `ChatViewModelTest.kt`. Both already differed before this implementation and still exactly match this task's before-edit hashes. Their parallel changes were preserved. All nine protected-source files match either the explicit pre-task snapshot or their unchanged historical hash, and required approval/conversation/Room classes remain in the release DEX. The historical differences are retained explicitly in `v32-validation.json`; they are not presented as new edits or old-hash matches.

Evidence: `.audit/group-diagnostics-20261019/build-v32.log`, `ktlint.log`, `v32-validation.json`, source snapshots and retained first-attempt build log. The first attempt found an unnecessary UI-test import; it was removed before the final build.

## Artifact

- Local APK: `E:\OSHIIRE\software\Hermes\deliverables\Hermes_Group_Diagnostics_v32_20261019.apk`
- Application ID: `com.m57.hermescontrol`; versionCode **32**; versionName `1.0.32-group-diagnostics-local`.
- Release/R8 APK, **16,499,328 bytes**, signed with the existing local Android Debug identity, identical to v31.
- Certificate SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`.
- APK SHA256: `bcb7bd95b150c2f8fb65e200608e6129475dbf598f9044abda38092d129dd9f1`.
- Library registration succeeded: `libfile_adf2c4114200819191d4835a2c7b5ebb`, current version **4**, filename `Hermes_Group_Diagnostics_v32_20261019.apk`.
- [Download the current APK](https://chatgpt.com/api/library/files/libfile_adf2c4114200819191d4835a2c7b5ebb/download).

Library result metadata is retained as `.audit/group-diagnostics-20261019/library-v32.json`. No physical installation, device UI operation, production deployment, push or new model execution was performed in this implementation turn.

## Remaining incident investigation

This is a metadata acquisition feature, not a demonstrated fix for the original unknown sends. The existing phone observation was SESSION_NOT_FOUND in a single aggregate label, insufficient to attribute the result to every bot. The service-owner findings received through the parent's authorized Slack exchange agree that runtime IDs route prompt submission and stored IDs address `sessions.id` in the explicit bot profile. No actual phone turn ID has yet been correlated with those server findings.

After a separately authorized update, the minimum user action is to open the affected existing conversation, choose the affected post's **送信診断**, select one bot turn, inspect the metadata-only preview and explicitly copy it to the investigator. If no saved turn exists, stop that client-side route. Further Hermes requests must go through the parent using Slack. Do not resend the post or explore another bot/profile's history by assumption.
