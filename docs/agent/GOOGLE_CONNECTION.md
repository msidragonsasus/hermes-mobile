# Google connection from Hermes Mobile

Implemented on 2026-10-19. Version 39 adds multiple Gmail accounts with separate
credentials, purpose labels, explicit reconnect/disconnect, and Career account
selection. The multi-account server and phone updates are installed. Authenticated
status reports one connected account; additional account consent remains pending.
Outlook/Calendar/Drive support is outside this implementation.

## User flow

Top-right gear -> Google接続 (first entry in the Settings group) -> Googleに接続
or 別のアカウントを接続 -> select an account and approve Gmail read.
The management directory indexes this entry for Google/Gmail searches. It is also
available from app settings, agent settings, and Career mail settings.
Connected rows show the actual verified email and an optional purpose label.
Each row's menu changes its label, reconnects that same account, or disconnects
that account after confirmation. Connecting another account preserves existing
connections; choosing an already connected address reports a duplicate.
Mobile sends a one-use authorization code to its authenticated Dashboard. The
server exchanges it with Google, checks the granted scopes and Gmail profile, then
stores a separate credential for the verified account. The first connection uses
the standard Hermes `google_token.json` for the existing Workspace skill; later
accounts use private files under `google_connections/`. The Career collector and
the new `google-connected-accounts` skill select a connection by its stable ID.
Connecting does not enable Career collection or retrieve mailbox contents; the
user chooses automatic collection separately in Career mail settings. Selecting
a different Career account disables automatic collection until explicitly enabled
again; it never silently fetches the newly selected mailbox.

## One-time operator setup

Use the existing Hermes Google Cloud project where possible. This project setup
belongs to the operator; ordinary users should not create their own OAuth client.

1. Enable Gmail API, configure the OAuth consent screen and intended users.
2. Register the Android OAuth client for `com.m57.hermescontrol` and the actual
   installed APK signing certificate. Debug and release signatures may differ.
3. In the **same project**, create a Web application OAuth client. Store its
   downloaded JSON on the server at
   `HERMES_HOME/google_mobile_client_secret.json`, readable only by the Hermes
   service user (0600 on Linux). Do not put the secret in the APK or chat.
   The existing `google_client_secret.json` is also accepted only if it contains a
   `web` client; an `installed` Desktop client cannot substitute for this client.
4. Install the official Hermes `google-workspace` skill and its normal Python
   dependencies. Deploy the updated Career plugin and Mobile APK, preserving
   existing credentials, signing identity, data, and running jobs. No new public
   endpoint is needed: native SDK consent sends the code over the app's existing
   authenticated LAN/Tailscale Dashboard connection.
5. Check the screen, complete consent on the device, verify the connected address
   and an authenticated Workspace profile read. Verify refresh separately before
   claiming unattended operation works on that installation.

This flow receives the public web client ID from the authenticated server; it does
not require rebuilding the APK with `GOOGLE_WEB_CLIENT_ID` for each server.
The old phone-only Career Gmail flow remains separate.

## Compatibility and boundaries

- Routes: `GET /api/plugins/career/connections/google`,
  `POST .../begin`, `POST .../complete`, and `POST .../manage`, protected by the parent Dashboard
  session. Mutation requests require a native consent header and reject browser
  Origin/Fetch Metadata. Codes and flow IDs are carried in POST JSON, never URLs.
- Pending consent is bound to the verified Dashboard session lifetime, expires in
  five minutes, is single-use, and is held only in server process memory. A server
  restart, changed session, or routing to another worker requires starting again.
- The granted scope must include `gmail.readonly` and contain no write scopes.
  The code, client secret, access token and refresh token are not put in UI errors,
  logs, DB, pending queues, or response bodies. Status responses disable caching.
- Credentials retain the official Workspace skill's private file format. Files
  are 0600 and directories 0700 on Linux; metadata and credentials are atomic and
  guarded by a process/file lock. Account IDs derive from the verified email;
  request data never supplies a credential path. The limit is 20 active accounts.
- An existing legacy Workspace connection is verified before adoption. An explicit
  reconnect binds the flow to the original account and revision, backs up the
  old credential, and rejects another Google account or a stale consent result.
  Disconnect archives only that account's local credential, preserves cached mail,
  and does not revoke Google-side permission. A late callback cannot reconnect it.
- Per-account Workspace requests run in isolated child processes against the
  installed official skill. They never swap the global credential file. One
  account's authentication failure marks that account only. Managed status is
  last-verified state; it is not a new live refresh proof for every listed row.
- The general Hermes skill lists metadata and requires an explicit account ID for
  bounded profile/search/get reads. Ambiguous account choice must be clarified.
  Career coaching continues using saved evidence and its sender-limited collector.
  Neither path permits send/delete/modify or broad unattended mailbox collection.
- A Google OAuth project left in Testing can issue refresh tokens that expire
  after seven days. Personal-use verification exceptions do not remove the need
  for correct Cloud project, consent-screen and client configuration. Public
  distribution using restricted Gmail scopes can require verification and a
  security assessment depending on data handling.
- Outlook requires a separate Microsoft OAuth integration; connecting Google
  does not enable Outlook. Calendar/Drive likewise require their own requested
  scopes and tool support.

## Sources checked before implementation

- Existing implementation:
  [Hermes Google Workspace skill](https://github.com/NousResearch/hermes-agent/tree/main/skills/productivity/google-workspace)
  and [Google Workspace CLI](https://github.com/googleworkspace/cli).
  Both still require OAuth client setup; adding a CLI alone does not remove it.
  Current Google Workspace CLI removed its multi-account command support. Version
  39 therefore reuses the official skill in an isolated process per account.
- [Google AuthorizationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder):
  native offline access returns a server code; use the existing Play services SDK.
- [Google server-side OAuth](https://developers.google.com/identity/protocols/oauth2/web-server)
  and [OAuth token lifetime](https://developers.google.com/identity/protocols/oauth2).
- [OpenAI Docs: Plugins](https://learn.chatgpt.com/docs/plugins): managed plugins
  provide reusable service connections; the OAuth application setup is handled
  by the integration operator instead of being part of the ordinary user flow.

## Validation

Server tests use a synthetic Google transport and temporary directories, covering
scope rejection, missing refresh token, actual profile verification, single use,
expiration, session binding, existing/concurrent credential preservation, native
request guards and secret-free responses. Android tests cover consent/verification
states, duplicate taps, immediate results, cancelled/late callbacks and sanitized
failure. Live Google consent, installed-server compatibility, real token refresh,
and physical-device behavior require deployment and user consent.

Local results on 2026-10-19: Google connection server tests 9/9, existing Workspace
adapter tests 14/14, existing collection tests 30/30, new Android state tests 5/5;
debug/release Kotlin compilation, debug APK assembly, Android lint and color
literal checks passed. Ktlint 1.8 passed for all touched Kotlin files. A whole-tree
ktlint run still reports 258 diagnostics in other existing files; those were not
reformatted. Python test dependencies were installed in a separate validation
directory, not into Hermes or its production runtime.

## Deployment preparation, 2026-10-19

- The installed phone was independently verified as version 37
  (`1.0.37-group-server`), signed with the same certificate as the candidate.
  A version-1 development APK was not installed over it. A new version 38
  (`1.0.38-google-connect`) was built from the current shared checkout; 14 group
  conversation source files matched the deployed version's recorded hashes.
- Version 38 passed 80 selected Android tests, debug/APK and Android-test APK
  assembly, and release Kotlin compilation. An initial isolated parallel build
  hit compiler memory limits; a separate build with one worker and a 4 GiB
  in-process compiler passed without stopping unrelated processes.
- Candidate APK SHA-256:
  `3d807ab2954639151b42b0039f6de4bc2211e430b1709e31d0a95570e73e553d`.
  After USB was reconnected, the approved version-38 update succeeded. The
  installed APK matches this hash and preserves the original signing identity
  and first-install time (`2026-09-06 23:04:00`). A private version-37 APK and
  persistent-data backup were captured before installation. Of 26 original
  files, 23 matched byte-for-byte after installation; the encrypted SQLite DB
  and its WAL/SHM changed during checkpointing. On-device SQLCipher comparison
  of the staged before/after copies verified every table's rows were identical
  and both integrity checks passed. The key and private row contents were not
  exported. The installed Google settings page passed an unlocked-device UI test.
- The narrow server installer and the code-only bundle are staged under
  `/home/keiya/hermes-google-connect-20261019/`. The bundle SHA-256 is
  `f6babb231e1c4d92cbc967bdb78cd7149471b14f7c2ebd14c6f5caf5d07b27f6`.
  Installer tests passed (3/3). The operator ran the privileged installer and
  returned `state=installed`, `host_synthetic_tests=passed`, `service_active=true`,
  `other_plugin_code_preserved=true`, and `credentials_changed=false`. Backup:
  `/var/backups/hermes/google-connect-20261019-192659`. The server has an existing
  Google token, but initially no dedicated mobile client. Authenticated Android
  verification returned `reauth_required`; the existing Workspace adapter returned
  `authentication_required`. No mailbox contents were requested.
- In Google Cloud, the account's existing `Hermes` project
  (`voltaic-layout-508306-g8`) had no OAuth configuration. With the user's explicit
  approval of Google's user-data policy, the `Hermes Mobile` OAuth branding was
  registered there. No OAuth client or client secret was created: Google rejected
  the Android package/signature pair because it is already registered elsewhere.
- The user switched to the managing account in a separate Chrome tab. Project
  `Hermes` (`ageless-arcanum-508105-m2`, number `123067273555`) contains the APK's
  existing web client `Hermes Career Google Sign-In` and Android client
  `Hermes Career Android Debug`. The Android package and SHA-1 match version 38.
  No Android client or signing identity was replaced.
- Google no longer exposes the existing web client secret for retrieval. With
  explicit at-action approval, one new secret was added to that web client. The
  existing secret remains enabled. The new value was saved to an access-restricted
  file outside Git and staged over SSH in a private server directory, without
  printing the value or using the clipboard.
- `tools/configure_google_mobile.py` is a separate administrator-only operator
  action, not part of the Android connection flow. Its four synthetic tests cover
  application/hash validation, exact original-token retention, no replacement of
  intervening files, and rollback when archiving fails. It refuses to archive a
  connection unless the real adapter returns `authentication_required` and refuses
  active Career jobs. It preserves the existing client secret and does not restart
  the Dashboard or invoke the agent.
- After explicit approval, the operator ran this setup and returned `mobile_ready`,
  `google_status=disconnected`, with the original token retained at
  `/var/lib/hermes/google-connect-credentials-backup-20261019-194505/google_token.json`.
  The authenticated Android check independently confirmed `disconnected`. Actual
  account choice and Gmail read consent are pending on the phone.
- At the version-38 setup stage, the project's publishing status was Testing,
  with one registered test user. Testing refresh-token lifetime restrictions still
  apply. Additional test users were registered later as recorded below; production
  publication has not been performed.

### Operator command for the staged server update

Run in the server's terminal and return the final JSON result. The operator enters
their sudo password locally; do not paste passwords or credential files into chat.

```sh
sudo /usr/bin/python3 -I /home/keiya/hermes-google-connect-20261019/install_google_connection.py /home/keiya/hermes-google-connect-20261019/google-connect-server-v1.zip --sha256 f6babb231e1c4d92cbc967bdb78cd7149471b14f7c2ebd14c6f5caf5d07b27f6 --apply
```

The operator command above completed successfully. The phone update script
verified version 37 and its installed APK hash, created a private backup, updated
with `adb install -r`, and verified the installed version-38 APK and first-install
time. Raw SQLite hashes differed, so it deliberately stopped before launching
the activity. The subsequent `InstalledDatabasePreservationReadTest` compared
copies with the on-device SQLCipher key and verified identical rows and integrity.
No live DB was restored or replaced. `GoogleConnectionReadTest` verified the
authenticated production API, and `GoogleConnectionInstalledUiTest` verified the
new page on the unlocked phone without starting Google consent.

## Multi-account deployment and validation, version 39

- Reused the installed official Workspace skill instead of a global token swap
  or a separate OAuth application for every mailbox. The new general Hermes
  skill is installed alongside it. No agent execution was requested or performed.
- The operator ran `install_google_multi_connection.py` with bundle
  `google-multi-server-v2.zip`, SHA-256
  `fa1caabbe31fb1ee63e68de823b5cd0a254cfe008a132d24277e969248d674a4`.
  Receipt: installed, host isolation tests passed, service active, credentials
  unchanged, other plugin code preserved. Backup:
  `/var/backups/hermes/google-multi-20261019-201832`.
- 108 selected server tests passed, including account separation, duplicate add,
  wrong-account reconnect, stale consent after disconnect, credential rollback,
  read-only CLI restrictions, installer scope, and existing mail collection.
  Ten selected Kotlin unit tests passed. Debug and Android-test APK assembly,
  Android lint, color literal check, release Kotlin compilation, and targeted
  ktlint 1.8 formatting checks passed.
- Installed `1.0.39-google-accounts` using a signer-compatible `adb install -r`.
  Installed APK SHA-256:
  `646d18f71debb4da614745f2340c7b676af133fffcf5b48a1605b7580ebbe6ca`.
  The original 26 persistent files were privately backed up. The 23 non-core-DB
  files were byte-identical; on-device SQLCipher comparison verified identical
  rows in every DB table and successful integrity checks. The original signer
  and first-install time (`2026-09-06 23:04:00`) were preserved. No key or row
  contents were exported and no live database was restored.
- Three synthetic-only physical-device UI tests passed: five-account list/add,
  selected-account label/disconnect interactions, and Career's saved account
  selection. The screenshots use example.test addresses; they do not prove five
  real accounts are connected.
- Two installed-app tests passed against the actual authenticated server and
  unlocked phone: the Google page was visible, multi-account protocol was true,
  state was connected with one account, and Career's Workspace reason was ready.
  The tests did not start consent or read any mailbox contents. Additional real
  account consent and long-lived refresh remain unverified.
- With explicit at-action approval, four additional user-specified addresses
  were registered as test users in the existing Hermes Google Cloud project.
  The visible list confirms five total test users. This permits consent to start;
  it does not itself connect or authorize any additional mailbox. The installed
  phone flow was handed to the user for those individual account approvals.
- Evidence is under `../output/google-connect-validation/`: server regression
  log, `v39-final-build.log`, `phone-installation-v39.json`, preservation log,
  `phone-v39-multi-ui.log`, and `phone-v39-live-status-ui.log`.

## Account chooser correction, version 40

The version-39 native request used `Prompt.CONSENT` alone. That requests consent
but does not force account selection, so Google's existing default account could
be reused when the user chose "別のアカウントを接続". The synthetic list tests and
installed-page visibility tests did not exercise Google's own account chooser.
Google Cloud's Testing status restricts eligible users and authorization lifetime;
it does not substitute for requesting an account chooser.

Version 40 adds the official `Prompt.SELECT_ACCOUNT` flag alongside `CONSENT`
using the documented bitwise combination. It retains the same SDK, offline code
flow, read-only scope, server account checks, and existing credentials. No server
or Google Cloud configuration change is required for this correction.

- [Google AuthorizationRequest.Builder: setPrompt](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder#setPrompt(int))
- [Google: Manage App Audience](https://support.google.com/cloud/answer/15549945?hl=en)

`GoogleAccountPickerInstalledTest` uses the installed screen's actual add button,
checks Google's system chooser heading and account rows without recording their
values, then cancels. It does not choose an account, approve access, or read mail.
Physical-device results on 2026-10-19:

- Installed `1.0.40-google-picker`, SHA-256
  `27e245ae6ed005c5ad3de42e18724caa53a95db63221a5f83f05171a1de6fb0e`,
  using the existing signer. All 26 original persistent files were byte-identical
  immediately after update; the logical SQLCipher row comparison and integrity
  checks also passed. First-install time remained `2026-09-06 23:04:00`.
- The real installed-screen add button opened Google's system account chooser
  with eight account rows. The test cancelled without choosing an account or
  accepting permission. A separate authenticated status check then confirmed the
  original one-account connection remained ready. Neither test read messages.
- Six existing Google ViewModel tests passed, and the debug app/test APKs were
  assembled. The first combined check process ran out of native memory during
  lint. Retrying remaining checks with a 2 GiB heap and two JVM processors passed
  Android lint, the color literal check, and release Kotlin compilation without
  stopping other processes. Targeted ktlint checks also passed.
- Evidence: `phone-installation-v40.json`, `phone-v40-real-picker.log`,
  `phone-v40-live-status.log`, `v40-picker-build.log`, and
  `v40-picker-remaining-checks.log` in the same local validation directory.
