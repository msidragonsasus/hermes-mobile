# Notice reader and group delivery follow-up — 2026-10-18

This local version 30 candidate follows the version 29 group-conversation APK. Existing unrelated Apps, career and approval changes in the checkout were preserved. No production deployment, physical-device installation, real model submission, uncertain retry, history deletion or Git publication was performed.

## Notice reader

Home source details and recruitment notice details now share one full-width screen for loading, read errors, empty bodies and selectable linked body text. The app bar has a compact fixed title; the subject can be expanded in the scrollable content. Origin and consultation actions remain available in the bottom action area, including while loading or after a read failure. Consultation opens the existing conversation route without submitting a prompt. Retry uses the existing content endpoint. Existing scope checks, extraction, authentication and exact-version read marking remain in place.

Selection and expanded-subject state use saved state. Content is read again after activity/process recreation; offline body caching is not newly promised. Direct company source dialogs outside these two entry points were not rewritten.

The reference images could not be materialized on this Windows host by the official Library helper because `os.setxattr` is unavailable. They were not viewed locally; the supplied description and current code informed this change.

## Group delivery follow-up

The report that new posts also reproduce the uncertainty warning is retained as an unresolved live defect. It is not explained away as old state. The client now distinguishes unavailable history, authentication failure, missing server session, absent saved session ID, absent exact request evidence and accepted-but-incomplete history. These outcomes retain uncertainty; none resubmit a prompt or treat a missing row as failure/success.

Code-supported gaps repaired here:

- Reconciliation runs again when the socket becomes connected after the connection scope was already assigned.
- Thinking, reasoning and tool-progress activity extend the existing observation window, with the existing hard limit preserved.
- A completion carrying only `storedSessionId` can match the saved turn.
- HTTP 401/403 and 404 history responses no longer collapse into an unexplained null result.
- A safe numeric RPC code is available in the group screen. Conversation changes clear transient diagnostics.

Synthetic tests cover all four member routes through the real client, unknown ACK without replay, connection generation changes, late replies, persisted unknown turns, exact history reconciliation, migration and independent conversations. No production messages were used.

An authorized read-only SSH check confirmed the service is active, but returned zero readable journal records and could not correlate the reported posts. Earlier normal-account database reads were denied. Neither the original 18:39 JST posts nor the newly reported reproduction can be declared accepted, completed or failed from this evidence. The local reference backend is not proof of the deployed version.

Remaining diagnostic inputs are the installed app version, whether the already reproduced input was the first post of a new conversation or an additional post, elapsed time until the warning, per-bot delivery state, and the read-only status-check result/numeric RPC code. No message body or credential is needed. Exact request matching intentionally remains conservative; server-side text normalization, history pagination or compaction can leave a real send unresolved.

## Validation and artifact

Final results:

- Focused debug unit tests: **93 passed**, zero failures/errors/skips, including group persistence/routing/reconciliation, real-client synthetic socket tests, protected-instruction approval and notice presentation.
- Offline emulator: **9 portrait tests and 6 landscape tests passed**. These cover inline loading/content/error/empty states, retry, origin/consult/back callbacks, HTTPS source routing without network, 90-paragraph scrolling at 2x font size, saved-state subject restoration, encrypted archive retention and conversation selection. The zero-width end marker originally failed an area-based display assertion; the corrected test verifies its actual vertical bounds inside the viewport. Assertions on the actions remain unchanged. Initial failed runs are retained.
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`, `compileReleaseKotlin`, `checkColorLiterals`, `lintDebug` and `lintVitalRelease` passed. Targeted ktlint passed for 11 changed Kotlin files, with the final instrumentation file checked again after its assertion correction. Repository-wide ktlint still has previously observed unrelated baseline failures; no unrelated formatting was applied.
- Signature verification, ZIP CRC and 16 KB alignment passed. Manifest permissions/SDK, personal build configuration, all four ABIs and all 12 native libraries match the prior compact distribution. All nine protected-file approval source hashes match their retained reference. The compiled release version comparator recognizes v30 as newer than v29 and rejects the reverse comparison.
- No physical-device update/data-retention exercise, authenticated live notice fetch, actual in-flight activity rotation, production group send or live protected-approval request was performed. Saved-state recreation and both orientations were tested with synthetic content. Final instrumentation-only edits do not change the distribution APK.

Artifact:

- Path: `E:\OSHIIRE\software\Hermes\deliverables\Hermes_Notice_Reader_v30_20261018.apk`
- Application ID: `com.m57.hermescontrol`; versionCode **30**; versionName `1.0.30-notice-reader-local`.
- Release/R8 distribution, signed with the existing local Android Debug certificate; certificate SHA256 `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`.
- Size: **16,425,268 bytes**; SHA256 `1144791cf2f9a6a0f1638c211a97dc78c1bd1ce6bcb8d9af7c85abf7c0734a53`.
- Library item `libfile_adf2c4114200819191d4835a2c7b5ebb`, current version **2**, returned file ID `file_00000000c00081f58e2c4b8fc87ca448`. Registration succeeded. The official helper cannot persist local Windows xattrs because `os.setxattr` is unavailable; no workaround was used.
- [Download version 30](https://chatgpt.com/api/library/files/libfile_adf2c4114200819191d4835a2c7b5ebb/download).

Supporting logs and reproducible scripts are under `.audit/notice-inline-20261018/`: `distribution-v30.log`, `v30-validation.json`, `ktlint-final.log`, `ktlint-instrument-final.log`, `emulator-tests-verified-portrait.log`, `emulator-tests-verified-landscape.log`, `version-comparison.log`, `server-readonly-status.json`, `library-v30.json` and `library-xattrs.log`. The original group migration and compatibility evidence remains under `.audit/group-conversations-20261018/` and the adjacent group validation documents.
