# Agent-side Mobile display knowledge

This is a Hermes Agent plugin, not an Android rendering change. It registers the
`hermes-mobile-display` **system prompt section** through the host's native
`register_system_prompt_section()` API. The agent learns Mobile's supported image
and interactive UI formats before answering. It also registers `pre_llm_call` to
clarify the current display context for already-existing conversations whose cached
system prompt predates the plugin. It never edits history or invalidates live prompts.

The plugin has no tools, model calls, credentials, network calls, configuration
writes, or background workers. It does not grant tool-override permission. `tui`
alone is never treated as proof of a graphical client or a text-only terminal.

Version 0.3.1 updates the [shared authoring guide](STYLE_GUIDE.md): calculators put
controls first, values stay beside their units, and meaningful groups use compact
panels with readable charts. It retains the host's `viz-*` components and theme
tokens. `SYSTEM_CONTEXT.md` and this guide are
registered together within the native 4,000-character budget. Android/agent guide
equality is checked by a local test; neither side requires a second model or paid API.
The continued-conversation hook reads this same guide, so a cached system prompt
cannot introduce an older, contradictory layout rule.

## Compatibility and verification

On an authorized server checkout, `python preflight.py --agent-root /path/to/hermes-agent`
checks source contracts without importing the agent or changing anything. Both
native system prompt sections and `pre_llm_call` must be present. An unsupported
host is reported explicitly; no monkey-patching or global prompt replacement is used.
Source inspection does not establish the running worker loaded the plugin.

Local tests cover real context detection, multimodal text inputs, resumed/unknown
clients, preserved permissions, bounded static instructions, and fail-closed
registration on unsupported hosts. The optional native-dispatch contract tests
exercise the actual host prompt-section renderer and hook dispatcher without any
provider/model calls. They do not prove a live model's answer or a device display.

## Production application

Any request to Hermes must use Slack **#hermes-bro**, workspace `T0C7AE5UBGC`, channel
`C0C74STS7D4`, Hermes `U0C7537MQTG`. Do not use Relay or an alternate request route.
Prepare and review the package before requesting permission to send that request.

After explicit authorization, the server operator should:

1. Run preflight on the installed checkout and verify package hashes. If unsupported,
   report the missing contracts rather than modifying Hermes core as a fallback.
2. Back up any existing `mobile_display` directory and the active profile's plugin
   configuration. Stage only this plugin, then enable it through the host's native
   plugin manager with `allow_tool_override=false`, preserving all other entries.
3. Activate it only at a safe worker boundary. Do not interrupt active browser/login
   work, erase sessions, restart an active turn, or change models/authentication.
4. Verify the running host actually registered the named static section and turn hook.
   Check a new agent's assembled system prompt for the section without logging the
   full prompt. Check a continued Mobile request gets the current-client hook context.
5. Verify a response with a harmless existing/generated test image references that
   image in Markdown/MEDIA. Do not repeat the user's Google login attempt or post
   authentication screenshots into Slack as a test. Use the current authorized model
   only, and distinguish deterministic hook checks from a live model response.
6. Report hashes, profile scope, registration proof, and any pending idle-boundary
   activation. Local source registration alone is not production completion.

Rollback disables only `mobile_display` and restores its previous directory and
config entries from the backup at a safe boundary; preserve all user/session data.

## References inspected before implementation

- [Hermes plugin API](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/developer-guide/plugins/index.md)
- [Hermes hook contract](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/hooks.md)
- The checked-out host implementation in `hermes_cli/plugins.py` and
  `hermes_cli/plugins_dispatch.py` is the source for the static-section API and budgets.
