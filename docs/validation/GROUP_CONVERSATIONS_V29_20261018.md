# Group conversations: distribution compatibility check — 2026-10-18

This version 29 APK supersedes this task's initial version 28 APK. Do not use the initial group-conversation version 28 as the distribution candidate.

## Verified reference and update compatibility

The supplied SHA256 exactly matches the locally retained prior compact APK:

`52e707cfab9d98bb7b9c03f817a062907817b30d910485f957ceea20555375b1`

- Both artifacts have applicationId `com.m57.hermescontrol` and the same verified signing certificate SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`.
- The final versionCode is **29**, greater than the reference APK's 28. No other active local build or reserved 29 was found in the inspected task/artifact inventory; shared Gradle defaults were not changed.
- The versionName is `1.0.29-group-conversations-local`. The app's actual compiled `isNewerVersion()` function was invoked locally: it recognizes this as newer than `1.0-dev` and rejects the reverse comparison (`v29-version-comparison.log`). The in-app release feed still points to its existing source; no release was published there.
- The initial group-conversation 28 debug candidate had omitted the previous personal-build connection declaration. It was withdrawn. Version 29 restores that declaration and uses the same release/R8 distribution approach as the reference compact APK, signed by the existing local Android Debug identity. No keystore, application source or shared build settings were changed for this follow-up.
- Application ID, Google client declaration, trusted-tailnet browser declaration and Git SHA match the prior distribution's generated configuration. The Google and endpoint strings were also checked in both actual APK DEX contents without exposing their values.
- Manifest permissions, minSdk 26 and targetSdk 37 match the reference APK. All four ABIs and all 12 native libraries are retained and byte-identical. Signature verification, ZIP CRC and 16 KB alignment checks passed.
- The saved authentication and Room database source files also match the prior distribution's source snapshot. No database reset, uninstall, data clear, physical-device install or server deployment was performed. Compatibility is established against the supplied APK; an actual on-device update and post-update data retention were not exercised.

## Protected-file approvals retained

All nine files recorded for the prior protected-file approval change match their saved SHA256, including EventParser, DashboardAttentionNormalizer, AttentionRepository, ChatViewModel and their approval tests. Their source was not changed by this follow-up.

The new APK contains the approval event model, parser, coordinator, response coroutine, Chat ViewModel, conversation ViewModel, encrypted conversation store and Room implementation. R8's mapping retains the original repository observation/response methods; both old and new compact APKs optimize the standalone repository class away.

Fresh release-variant validation: **91 tests passed, zero failures/errors/skips** across the prior seven approval suites plus AppUpdateCheckerTest. This includes once/deny choices, exact request scope, session snapshots, ACK handling, replay and duplicate-response protection. `assembleRelease` and `lintVitalRelease` passed. Group-conversation source is unchanged from the earlier 81 focused unit tests and three isolated emulator tests; those were not rerun merely for version metadata. No new live approval request or model execution was made.

## UI operations

1. Open the existing group from the group list. Tap the **history/clock icon at the top right**, labelled **会話一覧 / Conversations**.
2. Tap **新しい会話 / New conversation**. The bot roster and group discussion settings remain the same; the new conversation starts with independent history and context.
3. To return, open **会話一覧** again and tap the conversation title. The selected conversation has a check mark. Switching while a turn is pending pauses local orchestration and retains that turn in its original conversation.
4. In a conversation with unresolved delivery, tap **メッセージの状態を確認 / Check message status**. Startup/re-entry/reconnection also attempt read-only reconciliation. This never resubmits the message or restarts the discussion. If evidence remains unavailable, keep the original conversation unresolved and use a new conversation for independent work.

## Exact migration coverage

On the initial successful load, every entry actually present in the selected group's returned legacy `snapshot.log` is imported, in order, into its first conversation. IDs, sender identity, message text, timestamps and thread labels are carried over; missing identifiers/timestamps receive local defaults. A 70-entry available legacy log was tested, so the new importer itself does not truncate at 32.

Older clients ordinarily wrote only their latest 32 confirmed entries. Entries already absent from that server snapshot, unsaved unknown deliveries, partial streams, old hidden bot-session/tool state and data omitted or unavailable in the server response are outside the migration guarantee. Legacy import is one-time; later remote group changes are not continuously merged into local conversation archives. New conversations and subsequent history are stored on this device, without cross-device conversation synchronization.

The two reported 18:39 JST posts remain unverified because server DB reads require additional authorization. This build cannot reconstruct session IDs that the old app never persisted.

## Final artifact

- Path: `E:\OSHIIRE\software\Hermes\deliverables\Hermes_Group_Conversations_v29_20261018.apk`
- Size: **16,398,220 bytes**
- SHA256: `4f238e3f6e56fb6fdb803c8df0642733498a3935d8586fb3701f62d8d28f8e49`
- Library ID: `libfile_adf2c4114200819191d4835a2c7b5ebb`, current version **1**.
- Returned file ID: `file_0000000035008230b90fad94fe4146f4`.
- [Download the final version 29 APK](https://chatgpt.com/api/library/files/libfile_adf2c4114200819191d4835a2c7b5ebb/download).

The existing Library item was updated so the initial group-conversation 28 is no longer its current download. Registration succeeded; the official helper could not persist Windows extended attributes because `os.setxattr` is unavailable. Local xattr persistence is not claimed.

Evidence: `.audit/group-conversations-20261018/v29-validation.json`, `distribution-v29.log`, `v29-version-comparison.log`, `v29-signature.txt`, `v29-badging.txt`, `v29-alignment.txt` and the preserved earlier group-conversation validation logs.
