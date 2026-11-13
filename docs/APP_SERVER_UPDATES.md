# Connected-server app updates

Hermes Mobile can update itself from the Hermes server it is signed in to,
instead of the public upstream (Hy4ri/hermes-mobile) GitHub releases. The
upstream APKs are signed with a different key than a private fork, so the
connected server is the default source and the upstream is an explicit,
verified opt-in.

Components:

- Server: `server/mobile_updates` Dashboard plugin (read-only routes) and an
  operator publish CLI. No build, sign, shell, deploy or restart endpoint exists.
- Android: the existing About → Update row, update dialog, `AppUpdateViewModel`,
  `AppUpdateChecker`, `AppUpdateCache` and `UpdateNoticeManager`, extended with a
  source choice and a single verification policy (`data/update/UpdatePolicy.kt`).

## Server setup (operator; nothing here is done by builds or tests)

1. Create a release directory owned by the Dashboard service account (or an
   operator account in its group), not group/world writable:

   ```sh
   install -d -m 0750 /srv/hermes-mobile-updates
   ```

   Symlinks are refused for the root, `releases/`, release directories and files.
2. Copy `server/mobile_updates/` (with `updates_core/`, `dashboard/`,
   `plugin.yaml`) to the Dashboard account's `$HERMES_HOME/plugins/mobile_updates/`
   and enable it with the installed Hermes version's normal plugin mechanism.
   The Dashboard loader mounts `dashboard/plugin_api.py` at
   `/api/plugins/mobile_updates`.
3. Prefer explicit profile-owned settings at `$HERMES_HOME/mobile-updates.json`:

   ```json
   {
     "schema": "hermes-mobile-updates-config",
     "protocol": 1,
     "enabled": true,
     "root": "/absolute/profile-owned/mobile-updates",
     "application_id": "com.m57.hermescontrol"
   }
   ```

   Use an effective-UID-owned regular `0600` file, a canonical service-owned
   root, and no symlink or group/world-writable ancestors. Missing settings keep
   the feature disabled. Settings are read for each request; a new backend-only
   plugin can use the Dashboard's supported first-request API mount without a
   service restart or profile-wide agent-plugin rewire. Existing nonempty
   `HERMES_MOBILE_UPDATES_DIR` retains precedence; changing an already-running
   service's environment still requires its separately authorized restart.
   Never put this nonsecret directory setting in `.env`.

   Verify the actual Mobile-facing HTTP owner separately from any internal
   native owner. A successful authenticated request to another process on the
   same host does not establish Mobile end-to-end availability.
4. Publish a reviewed, already built and signed APK (run from the plugin dir):

   ```sh
   cat > release-input.json <<'EOF'
   {"application_id": "com.m57.hermescontrol", "version_code": 2,
    "version_name": "1.0.2-dev", "notes": "What changed",
    "signer_sha256": ["<sha256 of the signing certificate DER, lowercase hex>"]}
   EOF
   python -m updates_core.publish --root /srv/hermes-mobile-updates \
       --apk app-debug.apk --metadata release-input.json --dry-run
   python -m updates_core.publish --root /srv/hermes-mobile-updates \
       --apk app-debug.apk --metadata release-input.json
   ```

   Optional `sha256` / `size_bytes` in the input must match the APK. The signer
   digest is what `apksigner verify --print-certs` prints as "SHA-256 digest".
   The CLI reads the APK's binary manifest (package, versionCode, versionName)
   and the APK Signature Scheme v2/v3 signer certificate, refuses any mismatch,
   unsigned/v1-only APKs, v3 key rotation, symlinked sources and a
   `version_code` not greater than every published release. It copies the APK
   into `releases/.staging-*`, writes `release.json`, fsyncs and renames the
   directory to `releases/v<version_code>-<sha256[:16]>/` (never replacing an
   existing one). Files are made read-only (0440).

   These server-side checks are consistency checks of identity *claims*; the
   server does not verify signatures cryptographically. Android does that.

## Protocol (protocol 1)

All routes are `GET`, require a provider-verified Dashboard session, parent-verified
hosted authentication, or a one-request native-owner grant already verified by
core middleware. Native authorization reuses `hermes_cli.web_server._require_token`;
a header or legacy static token alone is not update authority. Routes refuse a
foreign `Origin` and
`Sec-Fetch-Site: cross-site`, and send `Cache-Control: no-store`,
`X-Content-Type-Options: nosniff`. HTTP input never supplies a file path.

- `GET /api/plugins/mobile_updates/status` →
  `{"schema":"hermes-mobile-update-status","protocol":1,"enabled":true}`
- `GET /api/plugins/mobile_updates/latest` →

  ```json
  {"schema": "hermes-mobile-update-latest", "protocol": 1,
   "release": {"release_id": "v2-0123456789abcdef",
     "application_id": "com.m57.hermescontrol", "version_code": 2,
     "version_name": "1.0.2-dev", "sha256": "<64 hex>", "size_bytes": 12345678,
     "signer_sha256": ["<64 hex>"], "apk_path": "releases/v2-0123456789abcdef/apk",
     "notes": "What changed", "published_at": "2026-10-27T00:00:00Z"}}
  ```

  `release` is `null` when nothing is published. The newest valid release
  (highest `version_code`) is advertised; invalid releases are skipped and
  logged by name; two valid releases with the same `version_code` →
  `409 ambiguous_release`.
- `GET /api/plugins/mobile_updates/releases/{release_id}/apk` → the exact bytes,
  `application/vnd.android.package-archive`, `Content-Length`,
  `ETag: "sha256:<hex>"`. The id must match `^v[1-9][0-9]{0,9}-[0-9a-f]{16}$`;
  bytes are hashed through the same descriptor before streaming
  (`409 release_integrity_failed` on mismatch). Downloads are logged with
  release id, user id and client address only.

## Android behavior

- **Source.** About → Update source: "Connected server · host:port" (default)
  or "Public upstream (GitHub)". The dialog also names the source. Stored as
  `updateSource` in the existing server store (absent → connected server; no
  migration needed). A connected-server failure (not signed in, plugin missing,
  disabled, network) is shown as such; it never falls back to upstream.
- **Same origin, no credential leakage.** Metadata and APK URLs are built from
  the bound connection's base URL (keeping a reverse-proxy prefix); the server
  cannot choose a URL (`apk_path` must equal `releases/<release_id>/apk`).
  Update requests do **not** use the REST client. Before every request the
  checker asks for an `UpdateBinding` for the candidate's exact `UpdateScope`;
  if anything changed, nothing is sent (`CONNECTION_CHANGED`). The binding is an
  immutable snapshot (Bearer token in token mode, or the cookie-jar cookies for
  that server URL in gated mode) taken while the connection was stable.
  `boundUpdateClient()` attaches only that snapshot and only to the bound
  scheme/host/port under `<base path>/api/plugins/mobile_updates/`; any other
  URL is refused before a credential is attached. It has no live auth
  interceptor, no profile rewriting, no shared cookie jar
  (`CookieJar.NO_COOKIES`), no token-refresh authenticator, no redirects and no
  retries; any 3xx is refused. A switch after the request was bound can only
  send server A's own credential to server A; the result is then discarded.
- **Scope binding.** `UpdateScope` records base URL, selected/active profile,
  auth mode, `AuthManager.connectionEpoch` and a per-process salted credential
  fingerprint (never the secret, never persisted). `connectionEpoch` is an
  in-memory seqlock-style counter: odd while a connection setter runs, moved by
  real changes of server URL, selected or active profile, token, session cookie
  and auth mode only (theme, update-check timestamps and the update-source
  preference do not move it). Snapshots spanning a change are discarded. So
  logout, re-login, credential replacement, active-profile change and A→B→A
  all make earlier results stale. A process-wide `UpdateConnectionWatcher`
  (started by the Application) invalidates `AppUpdateCache` and bumps its
  generation even when no updater screen is open; view models observe the
  generation and cancel their attempt.
- **Attempts.** Each download is an attempt with its own file
  (`cache/updates/hermes-update-<id>.apk`, under the existing FileProvider
  `cache-path`). Only the current attempt may update state; a cancelled or
  replaced attempt's late progress, failure or cleanup deletes only its own
  file. Starting again while downloading/verifying is ignored. Leaving the
  screen (`onCleared`) cancels the attempt and deletes its file unless it was
  already handed to the system installer (then the next attempt's orphan sweep
  removes it). Files of dead attempts/processes are swept and never installed.
- **Before download.** Metadata must be well formed; `application_id` must be
  this app; `version_code` must exceed the installed `versionCode` (else "Up to
  date"); the advertised signer set must equal the installed signer set.
- **Download.** At most `size_bytes` (≤ 200 MiB) into the attempt's `.part` file,
  hashed while streaming, renamed only at the exact size. Cancellation cancels the
  HTTP call and deletes partial and final files; nothing is installed after cancel.
- **Before every installer launch** (including after the Unknown-sources
  permission screen and "Open installer again") the file at rest is re-hashed and
  read with PackageManager: exact SHA-256 and size, package name, archive
  `versionCode` > installed and equal to the advertised one, `versionName` equal,
  signer set strictly equal to the installed app's (API 28+: `SigningInfo`
  current signers; API 26-27: legacy `signatures`). Unsigned archives, unreadable
  identity, and key rotation (past signing certificates) fail closed.
- **Install.** The existing `FileProvider` (`cache-path`) URI and system
  installer intent; the user confirms in the system UI. The app shows
  "Confirm vX in the system installer" — launching the installer is not
  reported as a completed update. The app never uninstalls, clears data or
  changes its signing identity.
- **Upstream (opt-in).** Requires the GitHub asset `digest` (`sha256:`), then
  the same archive verification. An upstream APK signed by another key is
  rejected (`SIGNER_MISMATCH`) instead of handed to the installer.

## Prerequisites and signing (data preservation)

- Android only updates in place when the new APK has the same `applicationId`
  and the same signing certificate as the installed app. The app checks this
  before opening the installer; Android enforces it again. A different key
  would require uninstalling, which deletes app data — this updater never
  offers that.
- The **first** APK containing this updater must be installed by the existing
  method (e.g. `adb install -r` of a build signed with the *same* key as the
  currently installed app). From then on, updates can come from the server.
- Keep using the keystore that signed the installed app. A locally built debug
  APK is signed with that machine's debug keystore (`~/.android/debug.keystore`);
  a different machine or a regenerated keystore produces a different signer and
  is refused. Do not commit keystores.
- `versionCode` defaults to 1 for local builds. Each published build needs a
  strictly increasing explicit code, e.g. `./gradlew assembleDebug
  -PversionCode=2 -PversionName=1.0.2-dev`, then 3, 4, … (a small counter, not a
  timestamp: Android's maximum is 2100000000).

## Limitations

- Signing key rotation is not supported (strict equality of current signers).
- A session-cookie rotation through the shared cookie jar (Set-Cookie on any
  REST response) does not move `connectionEpoch`; it changes the credential
  fingerprint, so it is detected at the next use and the check must be rerun
  (fail closed, may require "check again" mid-flow).
- Leaving the About screen while the system installer is still showing keeps
  the handed-over file until the next attempt sweeps it.
- Server checks are claim consistency only; they do not validate signatures.
- Only the newest release is offered; downgrade/rollback is not possible
  through the updater (Android refuses lower `versionCode` in place anyway).
- Single Dashboard host; release files are read per request (hash cached per
  inode/mtime). Token-only (loopback) mode without a Dashboard session gets 401.
- The launch-time check and chat banner stay disabled in debug builds
  (`UpdateNoticeManager.enabled`); the About tab checks when opened.
- Android build verification is now recorded in
  [ANDROID_BUILD_VALIDATION.md](phase3/ANDROID_BUILD_VALIDATION.md): APK generation,
  2,493 JVM tests, Android lint and Debug/Release compilation passed. Task-scoped
  ktlint passed; global ktlint still reports proven pre-existing violations.
  Runtime UI/device testing and original-signing-key migration remain pending.
  Earlier `UPDATE_IMPLEMENTATION_RESULT.json` is a historical worker report;
  `ANDROID_BUILD_VERIFICATION.json` next to the repository is the final evidence.
