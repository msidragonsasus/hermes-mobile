# Android internal MyPage browser

Implementation checkout: `E:/OSHIIRE/software/2026-10-02/task-5/hermes-mobile-browser`
Source checkout: `E:/OSHIIRE/software/Hermes/hermes-mobile`, user fork
`msidragonsasus/hermes-mobile`, branch `feat/career-coach`, HEAD `719a809`.
Work was performed on Desktop-Ryzen7 through shell/file operations.

## Behavior

The existing company MyPage button opens the new Navigation3 destination. Its
arguments contain company and link IDs only. The screen fetches current company
detail, resolves that saved link, and displays the HTTPS origin without URL query
or page title. Registered HTTPS login pages can receive one automatic input
attempt per explicit opening. A secret-free probe waits up to 10 seconds for
a dynamically mounted supported form before preparing or retrieving credentials.
Submitting the form always requires a user action.
Existing input is preserved. Unsupported, ambiguous, cross-origin, iframe, GET,
or foreign-action forms receive no values. There are no input/change events,
automatic login submission, applications, reservations, or extra paid APIs.

Navigation is restricted to the registered origin and tenant directory. Automatic
input additionally requires the complete registered URL (tenant path and query),
company, profile, connection version, and a native-served CSP document. The fill
script rechecks the top frame, Location and per-document native Server-Timing
nonce when it executes. The native response marker is consumed atomically; it is
not cleared by onPageStarted. A POST/replacement with the same URL and no matching
header receives no values. Root-level login URLs have no tenant directory, so
navigation stays on that exact URL; encoded path separators are refused. Redirect cookies are
preserved; redirect placeholders are excluded from filling and redirect history
is collapsed. Opening again fetches a fresh connection. Back navigates within the
browser if possible; close returns to its parent destination. Scoped, one-shot
close ignores stale or repeated callbacks rather than popping that parent.
A failed login is not retried
or resubmitted automatically.

The WebView has no native JS bridge. File/content access, mixed content, third
party cookies, workers, file downloads, WebView debugging, autofill providers,
content capture, and copying through selection action mode are disabled. Native
main-frame GETs enforce CSP (same-origin resources/connect/form-action, no
frames/workers/objects) and no-referrer. Only intercepted native GET documents get this injected CSP:
user-submitted POST result documents do not, and WebView interception does not
cover their WebSocket connections. Those documents never receive automatic
credential input. WebRTC STUN/TURN traffic is outside CSP connect-src and WebView
interception even on native documents; arbitrary portal networking is not fully confined.
The gateway cookie jar is never copied to
the portal: Hermes uses its existing encrypted OkHttp jar and the portal uses the
separate Android WebView jar. This app currently has no other WebViews; a lease
serializes portal creation and asynchronous cookie cleanup. Adding another WebView
later requires revisiting this global WebView cookie/storage/service-worker policy.

Secret buffers, responses and references are kept transient; owned byte/char
arrays are cleared where possible. JSON, JS and JNI strings cannot be guaranteed
erased and remain subject to GC/renderer lifetime. A trusted portal can read the
fields it receives, as in any browser. The host does not log or persist passwords,
copy them, put them in navigation/saved state/URLs, or enable WebView inspection.
Screenshot capture is allowed by explicit user request. The browser no longer
adds FLAG_SECURE or SecureOn to its windows, and does not clear restrictions
owned by another screen or device policy. Site fields visible on screen can
therefore appear in screenshots. Native view hierarchy saving is disabled. Cookies/cache/WebStorage are cleared on normal
close and before entry; abrupt process death can leave portal cookies until the
next entry. Backgrounding closes the session directly in the ON_PAUSE/ON_STOP observer,
without waiting for a Compose frame, including for SMS/2FA. Compose owns a stable
FrameLayout host; disposal detaches and destroys only
its WebView child, so later Compose layout/release cannot access a destroyed
WebView. Disposal also cancels native calls registered before execution and
closes tracked response streams; later release is idempotent. Late provider
callbacks are ignored, and provider construction failure shows a recoverable
status instead of escaping from the AndroidView factory.
The suspension/attempt booleans are saved across activity restoration without
saving any URL, credentials or WebView hierarchy. ON_PAUSE records suspension
before state saving on API 26/27 as well. A scope change allows returning
to the company screen only. Releasing the view also marks the session suspended,
except when caused by the user's explicit refresh; an outgoing host cannot
cancel a newer host's fill work. The shell bottom bar is hidden.
Renderer death disposes directly before notifying
the screen, so queued native callbacks cannot reuse the dead WebView.
The existing manifest has `allowBackup=false`; abrupt process death still leaves
a documented cookie/cache cleanup gap until the next opening.
The next entry clears the HTTP cache before loading. A failed WebView-provider
cookie cleanup callback keeps the lease held (fails closed); no timeout releases
another session onto an unconfirmed-clean cookie jar.

## Credential retrieval remains OFF by default

The plugin now wires `BrowserConsentAccess` to the real middleware-verified
Dashboard `Session`. Installing this source does not change the operator flag.
With `CAREER_MYPAGE_BROWSER_CREDENTIALS_ENABLED` absent/OFF, the App keeps manual input.
Production activation, deployment and installation require separate authorization.

After authorized activation, registered-target autofill follows the user's explicit
request to fill without another native confirmation dialog. Android prepares a
short-lived reference for the exact company/profile/connection version/full
registered HTTPS login URL and immediately uses it while the current native
document is still foreground and unchanged. Preparation reads metadata only.
The existing server protocol selects `allow_target` when `requires_confirmation`
is true and `use_target` when false; neither mode removes target, authenticated
session, expiry, single-use reference or response checks. An initial exact-target
grant remains memory-only for at most 30 minutes. Expiry, token rotation, restart,
revocation or target/version changes require a fresh checked prepare/use exchange,
not another dialog. Navigation, backgrounding, cancellation and profile/server
changes still cancel local input. The login button remains a separate user action.

The **自動入力を停止** action immediately disposes this browser and cancels input,
then requests revocation of the company/profile's pending and active grants.
Reopening explicitly resumes registered-target autofill with fresh checks; stopping
does not promise a future confirmation dialog or a permanent server setting.

The server binds the random confirmation to an in-memory salted HMAC of the
verified Session's opaque access token and provider. It does not read raw cookies,
decode token claims, use user IDs as login IDs, or retain token/refresh values.
This scope is deliberately narrower than an entire login: token refresh/rotation
requires a fresh checked grant. It is not device attestation. Session expiry is
checked again before reading the existing sealed `SecretStore` value.

`POST /mypage/browser-access/prepare` validates current metadata and creates one
60-second confirmation/request reference. Preparation reports whether the exact
target already has a valid permission. `POST /mypage/browser-credentials` requires its exact
`approval_id`, consumes it once, rechecks the current DB target, and issues/consumes
the existing session-object-bound grant in that same request. Native requests
require `X-Hermes-Browser-Consent: once`; requests carrying any Origin or Fetch
Metadata header are rejected, including same-origin Dashboard JavaScript. These
checks close ambient browser-cookie access; they do not prove a physical button
press against a compromised authenticated native client.

One process holds the existing OS file lock for this store's consent ledger.
Other workers fail closed instead of acknowledging ineffective revocation.
Restart discards every pending confirmation. No persistent or permanent target permission,
generic enable route, new Android permission, vault key or auth infrastructure
was added. See [access controls](MYPAGE_BROWSER_ACCESS_CONTROL.md) for cancellation,
capacity and operational requirements.

## Compatibility and remaining checks

- The saved portal URL must be the actual login URL. A redirect to another login
  URL, different tenant query or ambiguous fields deliberately prevents input.
- Generic support is limited to one visible password field and one visible
  recognized ID/email field in one POST form with the exact registered action.
- SSO, off-origin CDN dependencies, captcha iframes, popup/file flows, and SPA
  forms requiring change/input events may require manual entry or a reviewed
  site adapter. No live-site compatibility is claimed.
- Dummy local Chromium/Edge fixtures validate JS guards and CSP, including a
  manually submitted cross-origin 307 redirect. This is not Android WebView proof.
- No connected Android device or existing AVD was available. Actual WebView
  interception order, native Server-Timing exposure, rendering, cookies, back/reentry, process death and 2FA
  require a dummy-fixture emulator/device check before distributing the feature.
- Preserve applicationId, signing identity, DB and installed data. No push,
  deployment or device installation was performed. Integrate only the supplied
  scope patch after checking the original source hashes; unrelated dirty work
  is outside this feature.

Detailed check results and Claude audit evidence accompany the delivery report.

Android implementation references:
[WebView API](https://developer.android.com/reference/android/webkit/WebView) and
[native bridge security guidance](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges).

## Existing Mono Expressive UI

The browser and status-details dialog inherit `HermesControlTheme`. The canonical
roles are documented in [DESIGN.md](../../DESIGN.md) and implemented in
`theme/presets/DefaultScheme.kt`, `Type.kt`, `Shapes.kt` and
`Theme.kt` (`MaterialExpressiveTheme` / `MotionScheme.expressive()`).
The redundant autofill approval dialog, its 700 ms arming delay and its pending
callbacks have been removed. The status-details dialog and stop control remain.
No common theme/component, global-chat UI or company-order file was changed.

## Browser repair validation (2026-10-04)

The focused repair adds JVM readiness/navigation regressions, real
AndroidView/WebView ownership instrumentation, and 26 Chromium DOM fixtures
using synthetic credentials. Generated JavaScript syntax and patch whitespace
checks passed in cloud. Android compilation/JVM/instrumentation and actual DOM
execution remain unverified: this executor cannot download Gradle or launch
Chromium sockets. Do not interpret test source as a runtime pass. A device trace
is still needed to confirm the cause of the reported installed-app crash.
Background/rotation still closes the session under the existing privacy policy;
reopening is explicit. No state restoration saves a WebView, URL or secret.

## Browser revision 2 (2026-10-05)

The browser toolbar uses a compact screen-only height, company/address in one
block, icon actions for fill/close, and a one-line status with full details in
the overflow menu. Other screens retain the scaffold defaults.

The key action explicitly retries a supported fill after a transient failure.
Backend errors are mapped from allowlisted codes into fixed diagnostic text
(server feature disabled, endpoint missing, TLS/HTTPS, session, saved connection,
target/version, approval, network). Raw response text and secrets are not shown.
The server credential-access switch is still not enabled by an APK update.

Two-step filling is limited to the same exact registered login URL with a current
native document marker. An identifier-only success establishes a transient
password continuation for that opening. The next stage uses a fresh approval
request and rechecks document/target. If the identifier remains visible, its
value must exactly match the saved ID before only the empty password is filled.
Different IDs, occupied passwords, changed URLs and missing markers fail closed.
No click/submit/input/change events are generated. A POST-rendered second page
without a native marker, different-URL step or event-dependent framework remains
manual. This is not universal login or verified live-site compatibility.

## Browser visual refresh (2026-10-08)

Visual-only change. The status line gains a tone icon (in progress, done,
needs attention, guidance) and opens the status details when tapped. Its
hairline turns into the WebView's own load progress while a document loads.
Popup tabs are input-chip pills, and the active tab becomes a full pill.
Closed, unsupported-URL and loading states are centered with a shaped icon
and one action instead of sitting at the top. The status details dialog shows
labeled rows. Fill, policy, lifecycle and tab ownership logic are unchanged.
