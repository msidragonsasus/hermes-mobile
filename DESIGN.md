---
version: "beta"
name: "Hermes Mobile Design System"
description: "Quiet, dense Material 3 foundation for the Personal AI Shell: rows over cards, type over boxes"
omitted: []
colors:
  # Slate Dark Baseline (SlateScheme.kt). Mono Expressive values live in DefaultScheme.kt.
  bg-root: "#0F1416"
  bg-surface: "#0F1416"
  bg-surface-container-lowest: "#0F1416"
  bg-surface-container-low: "#171C1E"
  bg-surface-container: "#1B2022"
  bg-surface-container-high: "#252B2D"
  bg-surface-container-highest: "#303638"
  bg-surface-variant: "#171C1E"

  # Foreground / Text Tokens
  text-primary: "#DEE3E5"
  text-secondary: "#CEE7EC"
  text-muted: "#BEC8CA"
  text-on-primary: "#0F1416"
  text-on-secondary: "#0F1416"
  text-on-tertiary: "#0F1416"
  text-outline: "#889294"

  # Accents & Containers (Slate Bright Dark Mode)
  primary: "#80D5D2"
  primary-container: "#303638"
  on-primary-container: "#DEE3E5"
  secondary: "#B2CBD0"
  secondary-container: "#252B2D"
  on-secondary-container: "#CEE7EC"
  tertiary: "#A3C9EC"
  tertiary-container: "#303638"
  on-tertiary-container: "#DEE3E5"

  # Semantic Status Tokens (HermesStatusColors.kt)
  status-success: "#81D692"
  status-success-container: "#252B2D"
  status-on-success: "#0F1416"
  status-warning: "#ECC248"
  status-warning-container: "#252B2D"
  status-on-warning: "#0F1416"
  status-error: "#FFB4AB"
  status-error-container: "#252B2D"
  status-on-error: "#0F1416"
  status-on-error-container: "#FFB4AB"
  status-info: "#8ECEFF"
  status-info-container: "#252B2D"
  status-on-info: "#0F1416"

  # Code Block & Terminal Tokens (Color.kt)
  terminal-bg: "#1E1E1E"
  terminal-border: "#333333"
  terminal-text: "#D4D4D4"
  terminal-muted: "#808080"
  code-keyword: "#569CD6"
  code-string: "#CE9178"
  code-comment: "#6A9955"
  code-number: "#B5CEA8"

  # Diff Viewer Tokens
  diff-add-bg: "rgba(61, 220, 132, 0.15)"
  diff-add-text: "#3DDC84"
  diff-delete-bg: "rgba(255, 92, 92, 0.15)"
  diff-delete-text: "#FF5C5C"

typography:
  font-sans: "Roboto, 'Noto Sans CJK JP', system-ui, sans-serif"
  font-mono: "'JetBrains Mono', 'Roboto Mono', monospace"
  # Type.kt. Japanese text uses 0 letter spacing; wide tracking makes kana look loose.
  headline-medium: { fontSize: "24sp", lineHeight: "32sp", fontWeight: 400 }
  headline-small:  { fontSize: "20sp", lineHeight: "28sp", fontWeight: 400 }
  title-large:     { fontSize: "20sp", lineHeight: "28sp", fontWeight: 400 }
  title-medium:    { fontSize: "16sp", lineHeight: "22sp", fontWeight: 500 }
  title-small:     { fontSize: "14sp", lineHeight: "20sp", fontWeight: 500 }
  body-large:      { fontSize: "16sp", lineHeight: "24sp", fontWeight: 400 }
  body-medium:     { fontSize: "14sp", lineHeight: "20sp", fontWeight: 400 }
  body-small:      { fontSize: "12sp", lineHeight: "16sp", fontWeight: 400 }
  label-large:     { fontSize: "14sp", lineHeight: "20sp", fontWeight: 500 }
  label-medium:    { fontSize: "12sp", lineHeight: "16sp", fontWeight: 500 }
  label-small:     { fontSize: "11sp", lineHeight: "16sp", fontWeight: 500 }

rounded:
  # Shapes.kt. Daily UI uses only small / medium / full; the larger steps are for
  # dialogs and sheets that the platform owns.
  none: "0dp"
  extra-small: "4dp"
  small: "8dp"
  medium: "12dp"
  large: "16dp"
  extra-large: "28dp"
  full: "9999dp"

spacing:
  # Spacing.kt (4dp grid)
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
  xxl: "32dp"

components:
  list-row:
    backgroundColor: "transparent"
    padding: "10dp 0"
    divider: "1dp {colors.outlineVariant}"
    minHeight: "48dp"
  section-label:
    textStyle: "{typography.label-medium}"
    textColor: "{colors.text-outline}"
    padding: "16dp 0 4dp"
  search-field:
    backgroundColor: "{colors.bg-surface-container}"
    rounded: "{rounded.full}"
    height: "44dp"
  inline-notice:
    backgroundColor: "{colors.bg-surface-container-low}"
    rounded: "{rounded.small}"
    padding: "6dp 8dp 6dp 12dp"
  primary-button:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.text-on-primary}"
    rounded: "{rounded.full}"
    height: "40dp"
  chat-user-bubble:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.large}"
    padding: "8dp 12dp"
  chat-assistant-message:
    backgroundColor: "transparent"
    textColor: "{colors.text-primary}"
    padding: "0"
  code-block:
    backgroundColor: "{colors.terminal-bg}"
    textColor: "{colors.terminal-text}"
    rounded: "{rounded.small}"
    padding: "8dp 12dp"
---

## Overview

Hermes Mobile (`com.m57.hermescontrol`) is a Jetpack Compose / Material 3 app.
Personal AI Shell adds a one-handed, Japanese-first daily interaction model on top
of the existing Chat and Advanced management surfaces.

**Target feel:** a calm working tool, in the spirit of the Claude and Codex apps.
Content sits directly on the page; structure comes from type, alignment and thin
dividers, not from boxes. A screen should look like a well-set document or an
inbox, not a dashboard of tiles.

Three tests every screen must pass before it ships:

1. **Density.** On a 390 × 844dp phone at default font size, a list screen shows at
   least 6 rows (single-line rows: 9+) below its header and filters. If it shows 3,
   the rows or the header are too fat.
2. **Squint.** Blur your eyes: you should see text columns and a few hairlines, not
   a stack of grey rectangles. More than one filled surface per screen region is a
   smell.
3. **One loud thing.** At most one high-emphasis control per screen (the primary
   action). Everything else is text buttons, icons or menu items.

## Design continuity and inputs

**First criterion: the whole app must feel governed by one set of rules.** A
feature is not complete because its own screen looks acceptable; compare it with
existing screens that serve the same role.

- The same role gets the same hierarchy, spacing, type, shape, color meaning, icon
  treatment, action placement and wording, through shared components.
- The same action and state get the same feedback, enabled conditions, loading,
  empty, error, retry and completion behavior.
- Improve the shared component instead of copying its markup. Old screens that
  break these rules are debt, not precedent: do not copy them, and fix them when
  you touch them.

The user's explicit preferences override Material defaults, older screenshots and
existing code. A Material import or a rounded rectangle does not prove compliance.

### User decisions (do not undo)

- 2026-11-17: daily settings use one shared directory with eleven entries: models,
  skills, plugins, memory, usage limits, connectors, login information, permissions,
  server connection, notifications, and appearance. The drawer and conversation
  settings gear open this directory. Keep technical configuration under Details.
  The initial flat directory was rejected on 2026-11-17. Follow Muse/ChatGPT:
  inset rounded groups, thin internal dividers, outline icons, one-line labels
  and quiet chevrons. Reuse `SettingsGroup` and `SettingsRow`, with 16dp page
  margins and 60dp minimum navigation rows; allow rows to grow with larger text.
  Keep the three small labels outside their groups; Details/About form a final
  group. Do not add a fabricated profile, subscription or upgrade banner.
  This also applies to the eleven destination screens: shared rounded form
  groups, continuous grouped catalog rows, 16dp page margins, 60dp minimum
  rows, bodyLarge titles and bodyMedium secondary text. Use `SettingsPanel`
  for compound forms and `SettingsListItem` for lazy grouped rows. Avoid
  nested cards; model/profile selection uses check/radio state rather than
  decorative outlines. Keep existing actions, confirmations and save paths.
  2026-11-17 follow-up ("徹底的に詰めて"): the destinations share one row and
  page grammar, specified in [Settings destinations](#settings-destinations).
  Usage limits show remaining percentage and time to reset per returned window;
  never substitute token totals, context occupancy or estimated spend. Unknown,
  failed, stale and elapsed-window data must not imply available quota.
- 2026-11-16: image attachments must be photo-first. Do not restore the small
  thumbnail plus filename chip for pending photos, or a padded, tinted text
  bubble around sent photos. Keep filenames in accessibility labels and the
  existing viewer. Preserve image aspect ratio in the conversation.
- 2026-10-19: no box-like, square input fields and no decorative outlines on
  inputs. Conversation entry reuses `ChatComposer`.
- 2026-11-18: form fields use option C, a rounded filled surface with no outline
  or indicator line; only focus and error draw a ring. Every form field is
  `HermesTextField`; do not reintroduce `OutlinedTextField` or per-screen shapes.
- 2026-10-24: **no big rectangular cards.** The user called the card-stacked,
  generously padded look "ダサい". Lists are flat rows with dividers; groups are
  separated by a small label and whitespace; nothing is wrapped in a filled or
  outlined card unless it is a genuinely separate object (see
  [When a surface is allowed](#when-a-surface-is-allowed)).
- 2026-10-23: inside conversations only (Intelligent UI action buttons), buttons
  are simple solid pills, light-on-dark in dark theme and dark-on-light in light
  theme, with short labels. 2026-10-24 clarification: this does not apply to the
  rest of the app, where primary buttons follow Mono Expressive (blue `primary`).
- 2026-10-24: the screen for **one connected service** (Gmail, Slack, other
  connections) follows the ChatGPT connector screen the user called "理想":
  grouped rounded sections with hairlines inside. See
  [Connected service screens](#connected-service-screens). The 2026-11-17
  settings-directory decision also permits this grouping for settings navigation.

- 2026-10-28: the server terminal has **no secret-input mode or button**. Saved
  strings and passwords are inserted from 「保存した入力」; masking follows an inserted
  password, not a toggle. Terminal text keeps a real inset from the edges.
- 2026-10-29: while a chat turn still works, the conversation tail shows the fixed
  label 「思考中」 (`ThinkingIndicator`) whose letters carry a slow, same-hue,
  lightness-only shimmer. Rejected: bouncing dots, spinners, whole-label blinking,
  colored or rainbow gradients, card backgrounds and large enclosing UI. This
  loading shimmer on glyphs is the only gradient allowed in chat; surfaces stay flat.

- 2026-11-16: the autonomous worker follows a Muse-like "楽" model: Home is the
  hub. Worker approvals are decided in place at the top of 「対応が必要」
  (`WorkerApprovalObject`, shared with Work), and 「このあと」 lists the next
  routines. Worker rows put time in one muted, tabular trailing column
  (`WorkerTimeLabel`: `04:30`, `明日 04:30`). Routine actions (pause, resume,
  delete) live in the row's menu, not as per-row colored icons and badges.
- 2026-11-17: chat approvals are decided in place (`ApprovalRequestCard`, same
  structure as `WorkerApprovalObject`): heading, the exact operation in monospace,
  「詳細」, 「拒否」 and the primary 「今回だけ許可」. A closed request folds to one quiet
  line (「承認依頼・失効」) that still opens details. Rejected: an italic caption with a
  status label and a lone 「依頼の詳細を確認」 link that forced a screen change to decide.
  The detail screen names missing server fields once in one muted line, puts
  server/profile/session/request IDs under 「技術情報」 with per-line labels, shows a
  notice only when a response is blocked, and hides decision buttons once closed.
  Rejected: a stack of identical 「不明（サーバーから未提供）」 rows, unlabeled ID dumps,
  disabled buttons on an expired request, and a permanently disabled 「修正」 button.

- 2026-11-20: the default-profile and specialist chats share a left menu and a
  centered avatar and status pill for the current bot. The pill opens the status
  sheet, which also holds specialist settings, setup editing and the return to
  the main chat. The right side stays empty. Both timelines show waiting dots
  before the first reply text and hide thinking and tool execution rows.
  Approvals, clarifications, brainstorm cards and specialist setup reviews
  remain inline. The shared Composer opens its controls while typing and
  collapses to one pill for an empty draft when the keyboard closes.

When the user rejects a design, record it here in the same change.

### Inputs

- **Search:** use `SearchBar` (`ui/common/SharedComponents.kt`): a 44–48dp pill on
  `surfaceContainer` with a leading search icon and a placeholder. Never an
  `OutlinedTextField` with a label for search.
- **Form fields:** `HermesTextField` (`ui/common/HermesTextField.kt`): a filled
  `shapes.large` surface without outline or indicator line, label inside, a 2dp
  `primary` ring while focused and an `error` ring on error, readable disabled
  state. Never `OutlinedTextField`, the default 4dp square outline, or a
  screen-local shape or color override.
- Multi-line composer: `ChatComposer` only.

### Conversation layout (2026-11-16)

Following the Muse UI investigation, conversation screens use a shared measured
`ConversationLayout`: the timeline and the bottom composer are separate slots.
The dock's actual height (attachments, model controls and notices included) defines
the available viewport. Do not estimate it with a fixed spacer or a previous
frame's `onSizeChanged` result. The enclosing scaffold owns system bars; the
conversation layout owns IME padding.

`LocalConversationStyle` supplies the common 840dp maximum reading width, 24dp
composer radius, 56dp minimum input height and 48dp action targets. The composer
is the explicit exception to the daily surface-radius scale below. Colors,
typography and motion continue to come from the host Material theme. No Muse
assets, proprietary code, new palette or content shadows are introduced.

Attachments and command suggestions sit above the input capsule; model and
reasoning controls sit below it on the page. Configured controls remain reachable
with an empty draft. Normal chat, groups, OpenClaw and Career conversations reuse
this layout and composer while retaining their own sending/authentication rules.

In main and specialist chat, long-press settled text in `MessageActionHost` to
select it in place using the system handles and copy toolbar. The adjacent
message actions button opens the shared rounded menu for Reply and Copy whole
message. Embedded links and controls retain their own tap handling, and actions
remain unavailable during streaming. A reply previews the quoted text above
the editable draft and adds the markdown quote when the user sends. With the
keyboard closed, the composer shows its one-line draft preview and hides the
reply preview and model controls. All surfaces use host theme tokens.

Pending photos use an 88dp image strip above the composer, with a small circular
remove affordance inside a 48dp target. Photos have no visible filename chip.
Conversation photos sit directly on the page; user captions keep their own text
bubble, and photo-only messages have no empty text surface. The shared image
renderer fits the complete image within 320dp width and 360dp height, preserving
portrait and landscape proportions. It also handles Markdown images, GIF controls,
loading and failure states. The existing full-screen viewer remains the detail
view. Theme shapes and colors apply to every image surface.

## Layout

- **Screen edge:** 12dp (`spacing.md`) horizontal. Do not add a second inner
  padding inside rows; rows align to the same left edge as headings.
- **List rows:** 10–12dp vertical padding, 48dp minimum touch height, one
  `HorizontalDivider(outlineVariant)` between rows (`CompactListRow` in
  `ui/common/QuietComponents.kt`, or `FlatListRow`). No `spacedBy` gap between rows in addition to padding.
- **Row anatomy:** title (`titleSmall`/`bodyLarge`, 1–2 lines) → one meta line
  (`bodySmall`, `onSurfaceVariant`, items joined by ` · `). Put counts and state on
  the trailing side as small text or a small badge. Trailing icons are 20dp inside
  a 40dp target; do not let a 48dp `IconButton` dictate row height. Chat history rows (2026-10-29) always reserve one fixed 24dp trailing status slot, even when quiet, so single-line titles end at one boundary; spinner = running/starting/background child work (muted while a still-fresh status is being rechecked after one failed refresh), shield = approval, speech = input, hourglass = generic wait, quiet hollow ring = status unconfirmed, nothing = idle/no evidence (never a success badge); the state is also the row's state description. The user rejected the unexplained sync-problem (↺!) icon (2026-10-29): an unconfirmed status feed is explained once by a quiet one-line `bodySmall` notice above the list, never by a lone icon or a long trailing label.
- **Section breaks:** a `labelMedium` label in `outline` color with 16dp above and
  4dp below. Not a card, not a `headlineMedium` title.
- **Vertical rhythm:** 4 / 8 / 12 / 16 / 24dp only. Between major sections use 24dp
  of whitespace; never stack padding + spacer + card margin.
- **Headers:** the top bar plus at most one compact control row (search, filters,
  sort). Intro paragraphs belong in empty states, not above live content.
- **Filter chips:** one horizontally scrolling row, 32dp chips, `FilterChip` with
  no border when unselected (`FilterChipDefaults.filterChipBorder(enabled, selected,
  borderColor = Color.Transparent)`) or the shared row helper.
- **Scaffolding (`HermesScaffold`):** content lambdas must **never** apply
  `Modifier.padding(paddingValues)` on root children; the scaffold already offsets.
- Dropdown pickers instead of cycle buttons.

## When a surface is allowed

Default is **no surface**: content on `colorScheme.background`.

A filled surface (`surfaceContainerLow`/`surfaceContainer`, `shapes.medium` or
smaller, no border, no shadow) is allowed only for:

- a thing the user acts on as one object and that must read as separate from the
  stream: an approval request, a clarification question, a tool-call/result block
  in chat, a code block, an attachment chip, an Intelligent UI component;
- dialogs, bottom sheets and menus (platform-owned shapes);
- the composer.

Settings navigation and connected-service details are an explicit exception:
use the shared rounded `SettingsGroup` surface described in the user decisions
above. This does not permit card containers for feeds or catalog lists.

Never: a card per list item, a card around a whole section, a card inside a card,
an outlined card used as a divider, a full-width card for a single sentence, or a
tinted "hero" card. Stat tiles become a row of label/value pairs.

### Connected service screens

User decision 2026-10-24 (ChatGPT connector screen). Applies only to the detail
screen of one external service (`GoogleConnectionScreen` for Gmail,
`ExternalServiceDetailContent` for the rest), not to lists or feeds.

- Components: `SettingsGroup`, `SettingsRow`, `SettingsGroupLabel`,
  `SettingsAvatar`, `SettingsServiceMark`, `SettingsOverflowMenu`
  (`ui/common/GroupedSettings.kt`). Do not rebuild them per screen.
- Top bar: back button only; the service is named by the header group.
- Order: header group (48dp mark, name, one-line use, ︙ menu) → 接続済みアカウント
  (avatar, address, 用途名; ︙ per account; last row "+ 別のアカウントをつなげる")
  → 権限 (one row, value on the right) → この接続を使っている機能 → 接続先 →
  アクション. Omit a section the server does not report; never guess it.
- Group: `surfaceContainer`, `shapes.extraLarge`, no border, one
  `outlineVariant` hairline between rows, none after the last. Rows 60dp min,
  16dp inside padding, title `bodyLarge`, meta `bodyMedium onSurfaceVariant`.
- Labels sit outside the group: `titleSmall onSurfaceVariant`, 24dp above, 8dp below.
- No filled buttons. Add/connect is a row with a leading + icon; row actions live
  in the ︙ menu. Explanations are one `bodySmall` footnote under a group.
- Connecting or adding never navigates to another page (user decision 2026-10-24).
  The add row opens `ServiceConnectSheet`, a bottom sheet: Hermes mark ••• service
  mark, "〇〇を接続する", a short grouped explanation and one full-width primary
  "〇〇に進む" that goes straight to the account choice (Google picker, Slack or
  provider login). What it starts comes from `serviceConnectPlan`; when the server
  offers nothing, the sheet says why and only closes. Manual setup is the ︙ menu's
  "詳細設定".

### Settings destinations

User decision 2026-11-17. Applies to the eleven screens opened from the settings
directory and to the connected-service screens. Components live in
`ui/common/GroupedSettings.kt`; extend them instead of drawing a row locally.

- Page: 16dp side margins. At most one control row (search, then one chip row)
  above the content. Every group has a `SettingsGroupLabel` above it, including
  the first. The final unlabeled group, 24dp below the rest, holds advanced links
  or a destructive action (for example "ログアウト" in `error` color).
- Group: `SettingsGroup`, hairlines inset 16dp (`SettingsDividerInset`) in every
  group. A lazy list splits one group into segments with `first`/`last` instead
  of stacking separate cards. A compound form is a group of rows: fields padded
  16dp, switch rows, and `SettingsFormSubmit` as the last row.
- Row (`SettingsRow`): bodyLarge title, bodyMedium meta line, 60dp minimum.
  Leading: radio for selection, 24dp outline icon, or avatar. Trailing, in this
  order: value, `status` badge, chevron, `checked` switch, `actions` ︙ menu.
  Navigating rows always show a chevron. Per-item actions (edit, delete,
  uninstall, update, show/hide) go in the ︙ menu, never as a row of icons or
  outlined buttons. A single positive action on a catalog item (インストール) may
  be a trailing text button. Add is the last row with a leading +.
- Status: badges only for states worth noticing (connected, needs attention);
  the default "not set" state shows nothing. A need that blocks use (such as
  authentication) goes in the meta line with warning color, not as a badge
  that squeezes the title.
- Text: explanations and results are one `SettingsFootnote` below a group, not an
  intro paragraph above live content. Settings descriptions grow with text size;
  only catalog descriptions may be cut to two lines. No raw server tokens
  ("enabled", "no reasoning") in rows.
- Unavailable advanced features are omitted rather than shown as disabled rows.
- Plugins (user decision 2026-11-17, "何ができるんか全くわからん"): each installed row
  says what it adds in plain words (shelf, tool count, automatic steps, setup
  needed) when it came from the curated catalog. "プラグインを探す" browses that
  catalog (`/api/dashboard/plugins/catalog`) by shelf, official and verified
  first; community entries only behind "すべて". Desktop-app and other-OS
  entries are hidden because they cannot run on the server. Installing always
  goes through a review sheet (what it can do, needed keys, maker, repository)
  and a confirmation. Provider and install-from-URL settings live under 詳細設定.

## Notices and status

- Status (offline, stale, login required, error) is an **inline notice**: one row,
  20dp icon + `bodySmall` text + trailing text button, on `surfaceContainerLow` with
  `shapes.small`, 6–8dp vertical padding. One notice per area at most
  (`careerStatusOf` collapses duplicates).
- Never a tall rounded banner that pushes content down. Never two notices saying the
  same thing.
- Status always uses icon + text + semantic color, never color alone.
- **Times are human-readable.** Show `今日 14:05`, `昨日 09:12`, `10月21日 12:43`
  (`careerThreadUpdatedAt` style). Never render raw ISO-8601 strings such as
  `2026-09-24T09:10:51.270814+00:00`. Keep the full instant in semantics when it
  matters.

## Buttons and actions

- **Primary action:** `PrimaryPillButton` (`ui/common/QuietComponents.kt`): one
  solid pill per screen in Mono Expressive `primary` (`onPrimary` text), 40dp
  high, sized to its label. It does not stretch full width unless it is the only
  content (empty state, form submit). The inverse black/white pill is reserved
  for conversation components (see user decisions).
- **Secondary:** `TextButton` or an icon in the top bar / control row.
- **Destructive:** text button in `error` color, behind confirmation.
- **No FABs, including Extended FABs.** Put "add" in the top bar or the control row
  as an icon button with a content description. The Chat scroll-to-latest control
  is the only exception.
- Disabled: `enabled = false`, visibly greyed.

## Typography

System sans with Japanese fallback; respect font scaling (grow or scroll, never
clip decision-critical text).

- Japanese text uses **0 letter spacing** (`Type.kt`). Latin-tuned tracking
  (0.25–0.5sp) makes kana look loose and childish.
- Screen title: `titleLarge` 20sp. Row title: `titleSmall` 14sp/500 or `bodyLarge`.
  Body: `bodyMedium` 14sp; chat body: `bodyLarge` 16sp/24. Meta: `bodySmall` 12sp
  in `onSurfaceVariant`.
- `headline*` and `display*` are for empty states and a single key number, not for
  section titles in a list.
- Hierarchy comes from weight (400 vs 500) and color (`onSurface` vs
  `onSurfaceVariant`), not from size jumps. Use at most three text styles in a row.
- Code, logs, payloads: `JetBrains Mono` / monospace.

## Colors

The default look is **Mono Expressive**: monochrome base with one blue accent
(`#2848EE` light / `#BBC3FF` dark). Blue means "this is a link / this is moving";
selection and secondary emphasis stay grey (`secondaryContainer`); red is only for
errors. Primary buttons use blue `primary`; conversation components use the
inverse monochrome pill.

The app supports 7 presets via `PaletteTemplate.kt` (`Default` = Mono Expressive,
`Slate`, `Monochrome`, `Gruvbox`, `Catppuccin`, `AMOLED`, `Nord`) plus dynamic
color. The front-matter hex values describe **Slate**. Use `MaterialTheme`
roles and `LocalHermesStatusColors` at runtime; never hard-code hex in screens.

- Badges: small, `labelSmall`, neutral (`surfaceContainerHighest` +
  `onSurfaceVariant`) unless the count needs attention; an unread count may use
  `primary`. Cap at `99+`.
- Status colors keep ≥3:1 against their container; every preset defines
  `onErrorContainer`.

## Shapes and elevation

- Daily UI uses three radii: `small` 8dp (notices, code, chips), `medium` 12dp
  (allowed surfaces, form fields, menus), `full` (buttons, search, avatars).
  `large`+ are for sheets and dialogs only.
- No drop shadows on content. Depth = one surface tier step. Borders only where a
  field needs a focus state.
- Shimmer only for loading. No gradients on chat or message surfaces.
- Motion: `MotionScheme.expressive()` from `HermesControlTheme`; no new easings.
- Requires `androidx.compose.material3:material3:1.5.0-alpha29`.

## Components

- **Top app bar:** flat, background color, title + up to two icon actions; extra
  actions in the overflow menu. Prefer icons with content descriptions over text
  buttons once there are two or more actions.
- **Lists:** `FlatListRow` / `CompactListRow`; see [layout](#layout).
- **Chat timeline:** assistant messages are plain text on the background with full
  Markdown (no bubble); user messages are a right-aligned `secondaryContainer`
  bubble, not saturated primary. Tool calls and reasoning are
  closed, one-line summaries that expand on tap (existing decision); expanded
  content uses one `surfaceContainerLow` block, not nested cards.
- **Chips:** represent real state (model, profile, filter). Selected:
  `secondaryContainer`; unselected: transparent with text color.
- **Dialogs & sheets:** expose only operations the resource, server capability and
  permission support. Write/destructive operations use the host approval policy.
  Every managed resource has a discoverable navigation surface.
- **Empty states:** icon (optional), one `titleMedium` line, one `bodyMedium`
  sentence, at most one button. Centered in the remaining space.

## Interactive answers (Intelligent UI)

(2026-10-21 / 10-23 decisions, unchanged.) A working HTML preview is not a finished
visual design. Reuse host type, spacing, rounded controls, semantic colors and chart
series through `IntelligentUiStyle`. The chart, diagram or result has the primary
visual role. For simulators and calculators keep the result as a compact summary
(about 96dp) above the controls, then the graph, so values stay readable while a
finger drags. Show reset in the header only when inputs differ from defaults;
source actions go in the options menu; source URLs are not opened from components.
Fit previews to content with a bounded scrollable height; no fixed empty canvases,
repeated headings or nested cards. Validate both themes, narrow layouts and large
text. Keep the agent's authoring guide consistent with host classes and examples.

Native components use the versioned Hermes component catalog for savings,
comparisons and charts. A strict Material look is not required inside answers.
Favor aligned numbers, compact conditions and a readable graph over nested cards.
Controls update results locally while the finger is down; completed components can
appear before the response ends; user input survives later model updates. The
action button (savings/table/choices) is a full-width inverse-surface pill with a
short label (model `action`, else a default) that sends the shown conditions as the
next user message in one tap. It is disabled while generating, offline or queued,
and never touches the composer draft or attachments. Keep the isolated HTML
renderer for historical answers and bespoke diagrams.

## Personal AI Shell interaction model

External service settings (2026-10-24) have a dedicated directory under App
management and App settings: purpose icon, service name, short Japanese use case,
factual connection state, as flat rows. Open service details before account or
endpoint configuration. Long plugin descriptions and switches do not substitute
for connection management. Keep provider authorization separate from plugin
enablement, MCP configuration and transport health; Gmail authorization does not
establish Calendar/Drive authorization. Technical setup is progressively disclosed
and reuses the existing Google account and MCP operations.

Account routing (2026-10-24): keep service-specific account choices. The user may
centralize Calendar and Drive in one account while reading Gmail from others. Do
not replace this with a Google-wide selection or merge grants automatically.
Account/service enable controls must be backed by routing enforcement; a shared MCP
server switch affects all services using it and must say so. Prefer catalog-backed
setup before endpoint/command entry.

Primary navigation is **Home / Chat / Apps / Agent**. Agent is a read-only
connection inventory until each adapter is verified. Home shows App-selected
actions and results; Activity is a diagnostic destination under App management.
Keep every management/detail screen reachable through Advanced; preserve
NavigationController deduplication and screen-owned drawer gestures. Tab switches
must not create duplicate sessions or sockets. See
[UX_SPEC.md](docs/personal-ai-spec/UX_SPEC.md).

- Human decisions (Needs You, approval, clarification) precede passive progress.
- Connection state is separate from Run state. A disconnected client must not
  label a running or unknown Run as failed; show last confirmed state and resync.
- Run/status, approval, clarification and artifact components are shared by Home,
  Chat, diagnostics and Mini Apps, with the same labels and behavior. Reuse
  StateViews, SharedComponents, DetailDialog, DetailRows and Chat components.
- Approval shows action, target, effect, source and expiry (unknown when absent),
  binds to the original request/session, keeps Responding until confirmed, and
  offers once/deny only. High-impact actions open details. See
  [SECURITY.md](docs/personal-ai-spec/SECURITY.md).
- Artifacts expose safe summaries and supported preview/open actions; do not imply
  missing content is downloaded or generated.
- Mini Apps use host tokens, components and approval surfaces; no separate color or
  spacing system. Stages are bounded by
  [MINIAPP_PLATFORM.md](docs/personal-ai-spec/MINIAPP_PLATFORM.md).

## Today Home task selection and completion

The daily list is the user's own work, not an inbox of recruitment advertising.
Receiving a deadline notice does not establish interest, an application, or a
personal obligation. Put unconfirmed opportunities and legacy unclassified
records in a discoverable, folded review section, outside today's actionable
count. Say explicitly when coursework or submission status remains unverified;
an empty confirmed list is not proof that no work exists. User-selected work and
confirmed personal obligations take precedence. Keep overdue obligations distinct
from closed optional opportunities; never label an expired item "今日必須".

Completion and "対象外" are different, reversible, server-persisted decisions.
A checkbox must respond even during background polling, show saving/failure at
the affected row, and never pretend a failed write succeeded. Keep other rows
usable. Reuse flat task rows and progressive disclosure; show useful titles and
human-readable deadlines, with full details on expansion. Collapsed counts must
remain accurate and final rows must be reachable above the navigation bar.

## Accessibility and input

- Japanese strings wrap naturally; Android plurals; system font scaling. Never fix
  text heights or ellipsize decision-critical target/effect/expiry text. Density
  rules never override accessibility: at 200% text, rows grow and the list scrolls.
- Touch targets ≥48dp (the target, not the visible shape); key actions reachable
  one-handed. TalkBack labels, state descriptions, logical focus order.
- Contrast ≥4.5:1 for text, ≥3:1 for status and controls, across all presets and
  dynamic color. Test light/dark, large fonts and Japanese text.
- System bars, bottom navigation and IME insets have explicit owners; reuse
  `HermesScaffold`, avoid double padding, keep Composer and approval actions above
  the IME.

## Do's and don'ts

### Do
- Flat rows + hairline dividers for every list.
- One inline notice, one primary pill, human-readable times.
- `SearchBar` for search; `shapes.medium` for form fields.
- Material vector icons (`Icons.AutoMirrored.*` for directional ones).
- Android `<plurals>` for counts.
- Before a UI change: name the rule and shared component you are using, then check
  the rendered screen (both themes, 200% text, IME) for cards, borders, density and
  raw data leaking into the UI. Record unverified states.

### Don't
- Big rectangular cards, cards per row, nested cards, outlined cards as dividers.
- Tall rounded banners, duplicated warnings, raw ISO timestamps or JSON in daily UI.
- Outlined or square inputs (`OutlinedTextField`); labeled text fields for search.
- FABs or Extended FABs (Chat scroll-to-latest excepted).
- Full-width primary buttons in lists; more than one loud button per screen.
- `headlineMedium` for section titles; Latin letter-spacing on Japanese text.
- Emoji glyphs as icons; gradients; drop shadows on content.
- `Modifier.padding(paddingValues)` inside `HermesScaffold` content.
