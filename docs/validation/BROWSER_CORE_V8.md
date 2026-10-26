# Internal browser core candidate (2026-10-06)

## Scope

This candidate extends the installed v7 browser without changing application ID,
credentials, navigation shell, database, or signing identity. It is a source
candidate, not evidence of a built or installed APK.

- Public HTTPS browsing, CDN resources and frames no longer inherit the old
  tenant-only allowlist or a synthetic same-origin CSP. The site's own CSP and
  referrer policy are preserved. Registered credential targets remain exact.
- Native mobile WebView user agent is retained. DOM storage, viewport sizing,
  overview mode, pinch zoom, selection/copy, and standards-compliant caching are
  enabled. Service-worker networking remains blocked to avoid background work
  outliving this ephemeral session.
- User-initiated new windows open as temporary internal tabs (six total), sharing
  the root WebView cookie store. Tabs can be selected or closed. Back traverses
  the active tab's history, then closes a child, then leaves the browser. The root
  stays as the session anchor. Window.close may close only child tabs.
- The address label shows the current tab's HTTPS origin. Popup tabs do not
  receive Hermes credentials, even if they navigate to the registered login URL.
- File inputs open Android's system document picker. Image/MIME/extension filters,
  single and multiple selection, cancel, unavailable picker, stale results and
  activity recreation are handled. URI access is limited to external content
  providers with a read grant returned by the picker. No persistent grants,
  storage/camera permission, file-byte inspection, or automatic file selection.
- Switching to Android's picker or another app keeps pages/forms alive and
  suspends secret-bearing work. Navigation, tab/session replacement and disposal
  cancel affected picker callbacks. A pending result cannot acquire a new owner.

## Preserved boundaries

TLS errors still fail closed, Safe Browsing remains enabled, insecure mixed
content remains blocked, unsafe schemes/local literal destinations are rejected,
and no JavaScript-native bridge is added. The URL checks are not a complete network
sandbox: Chromium may follow resource redirects without another interception
callback, and public hostnames can resolve to private addresses. TLS validation,
mixed-content blocking, same-origin policy and the site's CSP still apply. Camera, microphone and geolocation are
not automatically granted. The backend's own cookie jar is not shared with web
pages. Root session disposal still clears ephemeral browser cookies/storage.

Autofill remains exact registered URL + native document token + current session,
foreground, form/action and non-overwrite checks. There is no new password manager
or restored autofill confirmation dialog. Screenshots and Home/Agent/Apps remain
as in v7.

## Limits

Universal website compatibility is not promised. Third-party cookies remain
blocked, some identity providers reject embedded WebViews, insecure HTTP pages,
client-certificate auth, downloads/PDF viewing, capture-only camera workflows,
service-worker-dependent offline apps, DRM/passkeys and browser extensions are outside this candidate. Selecting a file
returns it to the requesting webpage; a website may upload immediately on change,
as it would in an ordinary browser. Tests use synthetic documents only.

## Verification required on current toolchain

The cloud execution workspace has JDK 21 but no Android SDK/Gradle dependency
cache. At source handoff, Gradle compilation, lint and tests are NOT claimed to
have run. The verification executor must apply the hash-locked patch to a copy of
this exact source, never to an unverified main working tree, then run:

- testDebugUnitTest (all existing and new tests)
- compileDebugKotlin, compileReleaseKotlin and compileDebugAndroidTestKotlin
- ktlintCheck (format changed Kotlin files if necessary, then rerun)
- checkColorLiterals, lintDebug and assembleDebug
- Connected instrumentation for CareerBrowserTabsTest,
  CareerBrowserFileChooserTest, CareerBrowserHostTest, CareerBrowserRenderingTest
  and CareerBrowserChromeTest when an authorized test device/emulator is available

Test interruption/repetition, actual OS picker cancel/selection, real target=_blank
and window.open flows, cookies after child-close, large fonts, and Android Back.
Do not use a real personal photo or submit an external form during synthetic QA.
Do not uninstall or clear user app data. Preserve the existing signing identity
if deployment is later separately authorized.
