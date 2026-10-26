# Group conversations and delivery reconciliation — 2026-10-18

**Distribution update:** the initial version 28 artifact below has been superseded by [verified version 29](GROUP_CONVERSATIONS_V29_20261018.md), with the previous personal-build connection declaration restored. The Library item's current download is version 29. The version 28 metadata below is historical evidence.

## Scope and baseline

- Checkout: `E:\OSHIIRE\software\Hermes\hermes-mobile`.
- Fork: `https://github.com/msidragonsasus/hermes-mobile.git`.
- Branch: `feat/career-coach`; starting HEAD: `719a809810f69503e2aafcc5ba5a0e295b63e27f`.
- The checkout already contained extensive Apps, career, transport and group-discussion work. Existing changes were retained. Baselines for the group screen, models, ViewModel and transport are under `.audit/group-conversations-20261018/baseline/`.
- No relevant repository `.agents/skills` directory was present. The user-level `.agents` skill inventory contained `reverse-engineer-anything`, which was not needed.
- Existing Navigation3, manual ViewModel construction, bot models, credentials, permissions, connection configuration, application ID and Room schema are retained.

## Findings

The starting implementation set delivery to UNKNOWN before submit, ACCEPTED after ACK, and COMPLETE after `message.complete`. Group RPC ACKs expired after five seconds; an expired ACK abandoned completion observation unless completion had already arrived. The longer activity deadline therefore did not help a turn abandoned at ACK timeout. There was no subsequent history reconciliation.

The group snapshot saved only the latest 32 confirmed entries. It excluded unconfirmed requests and incomplete output. View state, watermarks and member sessions were associated with a group name, with no selectable conversation identity. These findings explain possible client-side uncertainty; they do not establish what the production server did with the reported posts.

## Implementation

- A stable conversation UUID separates each conversation's history, draft and durable turn journal. A new conversation retains the group's roster, speaking order, discussion settings and prompt.
- An encrypted local archive is scoped to server endpoint, local connection profile, server profile and group. The current device's existing server snapshot is imported once into its first conversation. All available imported entries and subsequent local entries are retained; there is no 32-message truncation in the archive.
- The archive uses a separate encrypted preferences file and the existing Android security library. Read errors, unknown versions and commit errors stop sends without deleting or recreating storage. No Room migration is needed because existing Room data is not modified.
- A user entry is committed before orchestration; a turn's conversation, user ID, reply ID, member, runtime/stored session IDs and exact prompt hash are committed before submit. If the journal cannot be committed, the prompt is not submitted.
- New turns receive dedicated server sessions. The prompt is reconstructed from that conversation's confirmed history using the existing recent-message window. Reused server session identities are rejected before submit. No session resume, model change or automatic resubmit is introduced.
- A missing ACK continues observing the original session until completion or the existing activity deadline. Explicit JSON-RPC request/method/parameter rejections are FAILED; locally rejected sends are NOT_SENT; transport loss, timeout and incomplete history retain uncertainty.
- The conversation picker can pause local orchestration and switch while a turn is pending. It sends no interrupt when switching conversations. Pending requests remain with their original conversation. Delayed complete events can settle only their recorded session; the response is stored in the original conversation.
- Re-entry, startup, reconnection, timeout and the explicit status button perform read-only history checks. A matching original request hash and a terminal assistant entry without tool calls are required to infer completion. Missing, partial or unavailable history is never treated as a failure or permission to resend. Orchestration and summaries never restart automatically.
- The original conversation can still mirror confirmed history to the legacy group snapshot. New conversation logs do not overwrite that legacy room log. Conversation archives are currently local to this device; cross-device conversation synchronization is not implemented.
- State transitions run on the main dispatcher by default; synchronous busy flags, conversation epochs, connection checks and durable journaling protect repeated send/switch/reconnect operations. Tests inject a controlled dispatcher.

## Production read-only investigation

The existing explicit SSH key successfully connected to `keiya@100.103.190.68`; `home-hermes-dashboard.service` reported `active`. Reading `/var/lib/hermes/.hermes/state.db` was denied, and noninteractive sudo required a password. No credentials or authentication settings were changed.

Consequently, acceptance or execution of the 2026-10-18 18:39 JST posts `継続` and `@all` remains unverified. No uncertain post was resent, no new real-model request was made, and no server pending marker or history was removed.

The referenced Library image `libfile_e523f2a35d6881919e859c99788449b9` was requested through the official materialization route. The Windows helper failed while applying required extended attributes (`os.setxattr` unavailable). No alternative transfer was used, and the image was not viewed. UI decisions use the supplied textual facts and current app code.

## Limits

- Old versions did not persist unknown sends. Their absent request/session IDs, unsaved partial output and history already absent from the old 32-entry snapshot cannot be reconstructed by this migration. Server history access is needed to investigate those records.
- Full history is retained for viewing, while bot prompts use the existing recent confirmed-message window and current discussion-request retention. Hidden bot tool/session state is not resumed across turns.
- A user-only history row proves acceptance, not completion. If no terminal evidence arrives, that conversation stays blocked for further sends; the user can create a separate conversation. Authentication/read failures and history outside the fetched page remain UNKNOWN.
- Physical-device installation, authenticated live execution, server deployment/restart, Git push and PR publication are outside this work and were not performed.

## Validation

Final results and APK identity follow. Logs and reports are under `.audit/group-conversations-20261018/`.

- `ktlintCheck` was run with repository-compatible ktlint 1.8.0. It failed in 30 existing unrelated files, including `MessageLocalizer.kt`, channel UI and career tests. These unrelated files were not reformatted. The affected Kotlin files are checked separately.
- The isolated Android emulator tested encrypted persistence of 65 entries and pending records, unknown-version preservation, and conversation selection/new-conversation controls at 2× font scale. The three tests passed on the candidate build; final rerun is recorded below.

### Final results

- Focused unit tests: 81 passed; 0 failed, 0 skipped. Includes long-history migration, independent context, pending ACK persistence, restart/reconnect matching, late-response routing, failed journal commit, server identity changes, switching during reads, reused-session rejection and repeated sends.
- Final source: `assembleDebug`, `assembleDebugAndroidTest`, `checkColorLiterals`, `lintDebug` and `compileReleaseKotlin` passed (`final-validated.log`).
- All 11 affected Kotlin files pass ktlint 1.8.0 (`ktlint-final-validated.log`). Whole-repository formatting still fails in the 30 unrelated existing files above.
- Offline emulator: 3/3 tests passed again (`emulator-final.log`). App data was retained with `install -r`. The test app was stopped, the emulator's prior network settings restored, and the emulator shut down. No physical device was installed.
- Two interim runs were intentionally stopped after thread dumps identified obsolete fixed-session-ID test fixtures. The fixtures now complete the freshly created session; the final focused run passed.

### APK

- Path: `E:\OSHIIRE\software\Hermes\deliverables\Hermes_Group_Conversations_v28_20261018.apk`
- Size: 46401248 bytes
- applicationId: `com.m57.hermescontrol`
- versionCode: `28`; versionName: `1.0-group-conversations-local`
- Signed using the existing local Android Debug identity; verified with apksigner. The installed physical-device signing identity was not inspected, so upgrade compatibility is not claimed. No signing keys or configuration were changed.
- Certificate SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`
- APK SHA256: `bea836db8fd391d1f9bc459d9fb5cfd6c32ad46db8d08b788c87776921a98e63`
- The APK includes the current checkout and its retained prior Apps/career changes. It is not an isolated group-only clean-branch build.

### Library registration

- APK registration succeeded: `library_file_id=libfile_adf2c4114200819191d4835a2c7b5ebb`.
- Returned file ID: `file_0000000060ac81f6a89f2ccea031e01a`.
- [Download the registered APK](https://chatgpt.com/api/library/files/libfile_adf2c4114200819191d4835a2c7b5ebb/download).
- Applying the returned extended attributes to the original Windows file failed because the official helper requires `os.setxattr`. The APK is registered in Library; local extended-attribute identity was not persisted. No workaround was used.
