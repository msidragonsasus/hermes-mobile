# Server Terminal App

Human-operated Linux PTY in the native Hermes Mobile Apps launcher. Input goes
directly from the phone to this Dashboard plugin, without an LLM, chat message,
agent tool, Relay, extra public port, or external terminal service.

## Mobile interaction

- Compose `ChatComposer` provides normal selection, paste, Japanese IME and editing.
- Send commits the edited text. The Enter toggle controls whether to append Enter.
  With it off, text is delivered without submitting it; the separate Enter key submits.
- Enter, Tab, Esc, Ctrl+C/D/Z/L and arrows are explicit buttons.
- There is no secret-input mode. Frequently used strings and passwords are saved by the
  user on the phone (「保存した入力」), per exact server + profile, in the app's
  AndroidKeyStore-backed encrypted preferences. They are excluded from backup and device
  transfer and never sent to the server except when the user sends the editor text.
  Tapping one only inserts it into the editor; sending stays explicit. Password entries
  are listed by label, masked in the editor (reveal is optional in the entry editor), and
  an inserted password is cleared when the app goes to the background or the session or
  connection changes. Unreadable saved data fails closed and is not overwritten.
  Input/output stays in volatile memory; this App has no command or output history.
- keiya: the shell logs in through local `ssh keiya@127.0.0.1`. Save keiya's password once
  (「keiyaのパスワードを保存」), then at the SSH password prompt tap 「keiyaのパスワード」 and send.
  It is never sent automatically. Reopening a running session from the list avoids a new login.
- Touch: one-finger drag scrolls the output (with fling); long-press opens native text
  selection at the pressed word, keeping the visible position. While older output is shown,
  new output does not move the view, and page / 「最新へ」 controls appear. Arrow keys
  remain explicit shell keys and are never used for scrolling.
- Output supports ANSI/TUI rendering, scrollback, font size, text selection and
  explicit HTTPS link opening in the system browser. Clipboard writes are manual.
- Sessions survive leaving the screen and can be selected again. Idle sessions
  expire after 30 minutes without authenticated reading/input/resize. Dashboard
  restarts and explicit session termination close the PTYs. Termination sends
  SIGHUP to every process in the shell's session (including background and `nohup` jobs),
  then SIGKILL after 1 second. A tmux server detaches into its own session and survives.
  Up to four running sessions per login (across profiles) and 16 running in total; exited
  shells stay readable but free their slot (hard cap 32 including exited ones).
  1 MiB output per session, 3,000 rendered lines.
- `tmux` shortcut prepares `tmux new -As mobile` in the editor. Execution remains
  explicit. Install/use tmux on the server if longer process persistence is needed.

## Claude Code login

Use the Claude login shortcut, review `claude auth login`, then send. Open the
printed HTTPS authorization URL using the output menu and complete the browser
login yourself. If Claude asks for a code, type or paste it into the editor and send
it to that terminal prompt. Do not paste it into AI chat. CLI installation, account
entitlement and authentication remain owned by the server's service account;
this plugin does not install Claude, purchase access or select an API billing key.

Official reference: https://code.claude.com/docs/en/authentication

## Install (administrator action; not performed by local builds)

Copy this directory to the **actual Dashboard service account's**
`$HERMES_HOME/plugins/mobile-terminal/`, including `terminal_core`, `dashboard`
and `plugin.yaml`. The existing Dashboard plugin loader mounts `dashboard/plugin_api.py`
at `/api/plugins/mobile-terminal`. Enable the plugin through the installed
Hermes version's normal plugin management mechanism.

Set `HERMES_MOBILE_TERMINAL=1` in the Dashboard service environment, then restart
only that service through the host's approved deployment workflow. The feature
is disabled without this flag and is supported only on Linux. Keep the existing
trusted LAN/Tailscale binding and Dashboard session authentication.
The shell defaults to `/bin/bash` (or `/bin/sh` when Bash is absent). Administrators
can set an absolute executable path with `HERMES_MOBILE_TERMINAL_SHELL`; a service
account's `nologin` login shell is not launched by this capability.

This creates an unrestricted interactive shell **as the Dashboard OS account**,
in its home directory. It does not run as the selected agent profile or as a
sandbox. Shell startup files and explicitly started programs can access that
account's files and may maintain their own logs/history; this plugin does not
change existing dotfiles. The child inherits a small environment allowlist,
not Dashboard API credentials. Agent tools cannot list/read/write these terminals through this API. However, if the
Hermes agent can run commands as the same OS account, it can change `~/.bashrc`, PATH
entries or binaries this shell uses and so observe what is typed, including secret input.
Run the Dashboard under an account the agent cannot write to, or treat terminal input as
visible to the agent.
PTY output is never written to disk by this plugin. Create, close and expiry events are
logged at WARNING on the `hermes.mobile_terminal` logger with session id, user id, provider
and client address only; input and output are never logged.

Verify on the real host before deploying: `hermes_cli.dashboard_auth.base.Session`,
`hermes_cli.web_server._require_token`, `app.state.auth_required` and the plugin
route loader must match this adapter. A missing/expired Dashboard session fails
closed. The login identity (provider, user id, org) and profile bind session ownership,
so Dashboard access-token refresh keeps the terminal. Another user cannot see it. Logging out prevents further
requests but does not immediately kill already-running remote commands.

The APK and server plugin must both be updated. A local APK build does not deploy
the plugin or authenticate Claude Code.
PTY ownership is in one Dashboard worker's memory. Use a single Dashboard worker;
this version does not support load balancing requests across multiple workers.

## Protocol and failure handling

- `GET /status`, `GET /sessions`
- `POST /sessions` with UUID `id`, `cols`, `rows` (idempotent within live ownership)
- `GET /sessions/{id}?offset=N` returns base64 bytes, absolute offsets and last input sequence
- `POST /sessions/{id}/input` with monotonic `sequence` and base64 `data` (max 16 KiB)
- `POST /sessions/{id}/size` with bounded dimensions
- `DELETE /sessions/{id}` terminates that shell process group

Shell writes are serialized. A sequence is reserved before writing; an identical
last sequence/payload returns its existing result, never replays it. A partial
write leaves `uncertain` state and disables input in Mobile. End that session and
open another. The client never automatically retries input or follows redirects.
Output is replayable by offset; buffer loss is explicit. Closing the App never
queues a delayed command. Polling is active only while the screen is resumed.

Mobile refuses plain http except on loopback and Tailscale (100.64.0.0/10, *.ts.net)
addresses, so commands and codes are never sent unencrypted.
All endpoints require the current authenticated Dashboard session and reject
foreign origins/cross-site requests. Successful responses have `Cache-Control: no-store`.
The renderer uses only bundled, integrity-verified MIT xterm.js 6.0.0 and addon-fit
0.11.0. Upstream licenses/provenance are in `app/src/main/assets/terminal/`.
Reference implementations reviewed before coding: https://github.com/xtermjs/xterm.js
and https://github.com/tsl0922/ttyd . The existing authenticated plugin transport
was reused rather than deploying a separate ttyd service.

WebView has no Dashboard cookies/tokens, network, file/content access, arbitrary
navigation, popups, downloads, generic JS bridge or remote scripts. A fixed host
protocol feeds base64 bytes to the VT parser. Only bounded cursor/device status
replies matching a strict allowlist can return automatically to the PTY; OSC 52
clipboard and OSC 8 links are disabled. Remote terminal output is never HTML/JS.
The native screen applies `FLAG_SECURE` while visible, including recent-app previews.

## Tests

```text
python -m unittest discover -s server/mobile_terminal/tests -v
```

Run on Linux to include real PTY tests (Unicode, controlling terminal, resize,
interrupt, ownership, offsets, bounded memory and duplicate-input suppression).
API/auth tests require FastAPI and httpx. Windows skips PTY tests.

## Agent-created Apps

`docs/personal-ai-spec/MINIAPP_PLATFORM.md` describes a future SDUI/Web runtime.
The current host has offline manifest parsing/validation and compiled native
routes; it does not discover/install arbitrary agent-generated Apps. This
terminal is a compiled native feature and requires an APK update. A future
agent-created App needs a separately implemented discovery, permission and
runtime host. Do not claim that writing a manifest alone installs an App.

Android sources: `E:\OSHIIRE\software\Hermes\hermes-mobile` on the development PC,
https://github.com/msidragonsasus/hermes-mobile . Uncommitted local work is not
automatically available from that repository URL or on the server.
