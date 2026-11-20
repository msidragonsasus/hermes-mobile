# Interactive visual answers and agent images

Hermes Mobile can display self-contained interactive explanations, charts, diagrams,
calculators, and small tools inside assistant messages. This implements the experience
described as Intelligent UI in ChatGPT with the existing Hermes model and transport.
It does not implement a proprietary ChatGPT component protocol or require an OpenAI API key.

## Agent output contract

Chat sends the `mobile-v3` client surface on capable gateways, including for queued
prompts and attachments. The gateway supplies display instructions. Drafts keep
the user's text and restored messages hide historical display instructions.
Gateways that lack `mobile-v3` receive the legacy suffix on eligible text.
On capable gateways, the `session.redirect` RPC carries clean text and preserves
the explicit steer action. That RPC has no surface field.
Other agent entry points can emit the same formats without additional Mobile configuration.

Use `hermes-ui` fenced blocks for interactive answers. `intelligent-ui` and `liveui` are
aliases. Include HTML, inline CSS, inline JavaScript, SVG, or canvas in one block. A closed
block can appear while the rest of a reply streams. An unfinished block is never executed.
Ordinary `html` and `svg` code fences retain their code view and offer a Preview button.

````markdown
人数を変えて一人あたりの金額を確認できます。

```hermes-ui
<main class="viz">
<header class="viz-header"><h2>割り勘</h2><p class="viz-description">人数を変えて、一人分を確認。</p></header>
<section class="viz-result"><span class="viz-caption">一人あたり</span>
<output class="viz-value" id="amount" aria-live="polite">3,000 円</output></section>
<section class="viz-controls"><label>人数 <input id="people" type="range" min="1" max="10" value="4"></label></section>
</main>
<script>
document.getElementById('people').addEventListener('input', function () {
  document.getElementById('amount').textContent = Math.ceil(12000 / Number(this.value)).toLocaleString() + ' 円';
});
</script>
```
````

Prefer responsive, accessible controls. The host shares its typography sizes, shape,
spacing, foreground/muted colors, control contrast, surfaces, and six chart-series colors.
Use the built-in `viz-*` classes in the [authoring guide](../server/mobile_display/STYLE_GUIDE.md)
for consistent result/control hierarchy. This guide is also appended to Mobile display
context; tests keep its Android and agent copies equal.
Keep facts, assumptions, and an explanation in the surrounding answer. Simple text answers
should remain text. Source/reset actions live in an options menu below the answer.
Preview height follows measured content, with a 520dp inline cap and a bounded expanded view.
Height updates and expansion preserve the active input state;
Reset deliberately restarts the preview. Switching to code and back also restarts it.

## Images

Show real images using `![description](<https://example.test/image.png>)` or a gateway host
path such as `![plot](</home/user/work/plot.png>)`. Angle brackets support spaces and
parentheses. Images mixed into prose and multiple adjacent images retain surrounding text.
Standalone absolute image paths (including a path wrapped in backticks or a path-only
unlabelled/text code fence) are also displayed as images. This supports older replies that
say a screenshot was saved but return only its server path. Paths inside commands/prose,
language-labelled executable code fences, and unfinished fences remain text/code.
Remote `~/` paths stay remote rather than expanding to the phone's home.
Existing `MEDIA:` attachments continue to work. A host path resolves through the existing
authenticated managed-file download endpoint, never the phone's filesystem.

REST message content arrays and WebSocket completion payloads support text blocks, OpenAI
image URL blocks, and MCP/Anthropic base64 image blocks. Unknown structured content stays
readable as JSON. Images use the shared loader, enlarged viewer, save, and share functions.
Shared Markdown callers without their own image handler receive a local viewer automatically.
Failed thumbnail loads display a tappable error instead of an invisible blank area.

## Isolation and limits

Interactive source is capped at 240,000 characters and runs in an opaque-origin iframe with
only `allow-scripts`. It has no native bridge, host DOM, auth tokens, storage, external
navigation, downloads, uploads, popup windows, device permissions, or tool-call authority.
CSP and WebView request interception block network access. Images inside an interactive
preview must be embedded data URLs; ordinary chat images can use authenticated gateway
downloads or public HTTP(S) URLs. Inline UI controls update only local presentation state.
An isolated child reports content height to its own outer document. Android pulls one
finite bounded number from a fixed expression; it exposes no native object or tool/action
message handler to generated code. Polling stops when the WebView detaches or its window
is hidden. Child origin and payload checks keep other message sources out.
Sending email, updating records, or other agent actions continue through the conversation's
existing tools and approvals. Preview interaction state is transient; the source survives
through the existing message cache/history without a database migration.

## References considered before implementation

- [OpenAI Intelligent UI announcement](https://openai.com/index/gpt-6-for-everyone/): target experience.
- [Open Intelligent UI](https://github.com/thesysdev/open-intelligent-ui), MIT: reference for
  model-generated interactive answers. Its React/Next.js/OpenUI runtime would add a second
  rendering stack to this native client; the implementation reuses the shared Markdown host.
- [MCP Apps](https://github.com/modelcontextprotocol/ext-apps): separate tool/resource protocol,
  not required for self-contained visual answers. No MCP Apps compatibility is claimed.
- [Coil](https://github.com/coil-kt/coil): existing image loading dependency reused.
- [Android local WebView content](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content):
  inline document loading without a privileged file origin.

## Agent awareness

The [mobile_display server plugin](../server/mobile_display/README.md) registers this
output contract through Hermes Agent's native system-prompt section API. Its
`pre_llm_call` hook also supplies the current client's display context to continued
conversations. Detection uses the actual current Android request descriptor; a
historical Mobile message or the `tui` transport name alone does not establish the
current renderer. These instructions grant no new tool or account permissions.

The plugin is a separate server deliverable. An APK update alone does not load it.
Use its read-only preflight and native registration checks on the target host before
activation, then verify the running worker and actual image response separately.

## Verification

`IntelligentUiDocumentTest` writes the actual sandbox documents under
`app/build/reports/rich-answers/` for browser inspection (calculator in both themes and an
isolation probe). Unit tests cover complete/incomplete fences, markup escaping, source
limits, image/prose preservation, REST/live content normalization, and reversible display
instructions. Browser verification and Android WebView/device verification are distinct.
Building an APK does not deploy the app or update a running Hermes server.

`IntelligentUiPresentationTest` writes calculator, comparison-chart and circuit examples
under `app/build/reports/rich-answers/styled` with the production document builder.
Fixtures are in `app/src/test/resources/rich-answers`; browser checks cover interactions,
dynamic height, themes, narrow layouts, enlarged text and preserved isolation.
