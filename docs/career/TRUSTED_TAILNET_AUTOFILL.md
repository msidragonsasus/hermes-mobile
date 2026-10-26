# Explicit connection-scoped HTTP credential transport

## Configuration

Configure the Hermes destination through the existing connection URL in app settings. No individual endpoint is embedded in browser trust code and no rebuild or build property is required.

HTTPS credential retrieval works without additional transport permission. For a signed-in Dashboard connection using a canonical literal CGNAT IPv4 address in 100.64.0.0/10 and a root HTTP URL, Connection settings offers an explicit saved-login HTTP retrieval switch. It defaults to off, including existing installations. LAN addresses, DNS names, IPv6, path prefixes and public HTTP destinations cannot enable this exception.

Enabling opens a native confirmation showing the exact configured destination and active Hermes profile. It explains that login IDs, passwords and the dashboard session could be exposed if the HTTP route is not protected. Cancel sends nothing and saves no permission. Saving must succeed before permission activates. This is connection transport configuration, separate from company-specific one-use server grants.

## Scope and revocation

The encrypted local permission record contains only the exact canonical endpoint, local connection and active server-profile scope, and a random grant identifier. It contains no cookie, fingerprint, login ID or password. An app restart can restore the saved setting only for the same exact context.

Changing the active endpoint, local connection or server profile synchronously revokes the old permission before publishing the new context. Returning to the former context does not restore its permission. Changes through login, connection editing and other ServerStore writers follow the same boundary. A failed revocation save prevents publishing that connection change. Turning the switch off also revokes the active permission. Failed persistence is reported; in-memory permission remains disabled.

An in-memory generation invalidates old sources even for rapid changes away and back or revoke/re-enable sequences. A confirmation opened before such a change cannot grant the returned or replacement context.

## Runtime boundary

- The active canonical base URL must equal the explicitly permitted endpoint.
- Each source captures the connection/profile scope, permission generation and current session-cookie fingerprint in memory.
- Gated dashboard session-cookie authentication remains required, without Bearer or basic-auth fallback.
- Dispatch, cookie selection, network send, response decoding and browser insertion recheck the current scope/session/permission.
- Only the five exact prepare, credentials, revoke, login-ID and login-locator API URLs are eligible.
- HTTP credential calls bypass system HTTP proxies per client, disable redirects and retries, and use no disk cache or HTTP logging.
- Redirect-enabled injected clients, redirected responses and foreign response URLs are rejected.
- The cookie jar sends only the matching non-Secure dashboard session cookie to the exact permitted URL and never persists response cookies.
- HTTPS certificate verification, exact HTTPS portal login policy, company/profile/version checks, one-use nonce, expiry, document/foreground guards and transient-secret wiping remain unchanged.

## Server requirement and residual trust

The client does not cryptographically attest Tailscale routing. CGNAT address membership or an enabled VPN is not proof of a trusted route. The setting requires an explicit user decision for one endpoint; it does not trust an entire address range.

The server must independently allow that exact transport, authenticate the session and retain company-specific approval checks. The documented server configuration name is CAREER_MYPAGE_BROWSER_TRUSTED_TAILNET_ORIGIN; client settings do not configure or verify that server counterpart. Server denials remain visible as fixed transport diagnostics.

## Validation

Synthetic tests cover default-off behavior, strict eligible-origin parsing, explicit confirmation and cancel, save failure, exact context scoping, restart restoration, rapid context changes away and back, revoke/regrant, stale confirmations, cookie filtering, the five exact API paths, session invalidation, redirects and foreign responses. Existing browser credential/autofill/login-resolver tests remain regression checks.

Unit/build validation, rendered UI checks, authenticated end-to-end behavior and physical-device/server deployment are separate verification levels. No live credential requests are required for these synthetic tests.
