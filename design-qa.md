# Material overview QA — 2026-09-28

**Findings**

No actionable P0/P1/P2 findings remain in the scoped native Career Home and
global Home presentation. This is a native implementation of the selected
direction, not a pixel-identical reproduction of generated art.

**Visual truth and implementation**

- Career source: [selected image](../ui-material-20260928/references/career-selected.png).
- Home source: [selected image with aligned connection status](../ui-material-20260928/references/home-selected.png).
- Career implementation: [light](../ui-material-20260928/screens-final/career-light.png),
  [dark](../ui-material-20260928/screens-final/career-dark.png),
  [200% text](../ui-material-20260928/screens-final/career-large.png),
  [200% scrolled](../ui-material-20260928/screens-final/career-large-scrolled.png).
- Home implementation: [light](../ui-material-20260928/screens-final/home-light.png),
  [dark](../ui-material-20260928/screens-final/home-dark.png),
  [200% text](../ui-material-20260928/screens-final/home-large.png).
- Sources are 853 × 1844 pixels, approximately 390 × 844 logical pixels.
  Compose captures are 1170 × 2532 pixels, 390 × 844 dp at density 3.
  There is no CSS viewport in this native implementation. Full app-only views
  were compared at equivalent visual widths, accounting for the different
  densities; raw pixel coordinates were not treated as matching measurements.
- State: deterministic sample data resembling the supplied content, Career Home
  and connected global Home, captured from the actual production presentation
  composables inside the shared scaffold on an Android emulator. These are
  fixture screenshots, not authenticated production data.
- Each source was opened together with its corresponding rendered light/dark
  captures in the same comparison input. The full views show the header,
  toolbar/tabs, agenda or action list, and results. Text and controls were
  readable in those views, so separate focused crops were unnecessary. Toolbar
  alignment was also asserted numerically in the UI test.

**Comparison history**

1. Initial comparison: blocked.
   - [P1] Career tabs scrolled out of reach; three UI tests failed when switching
     after scrolling. Pin the existing scaffold top bar. Final scrolled captures
     and all three Career UI tests confirm the controls remain reachable.
   - [P2] Fixture system insets duplicated the header spacing, changing the
     reference proportions. Mirror production inset ownership and capture only
     the app region. The second captures remove the duplicate gap.
   - [P2] Status/icon fills appeared too gray and Home timestamps wrapped enough
     to push results down. Use theme-primary alpha fills and compact JST dates
     while retaining full date information in accessibility semantics.
   - Evidence: `../ui-material-20260928/screens-first/` and
     `../ui-material-20260928/ui-tests-first.log`.
2. Second comparison: blocked for Home density.
   - [P2] Home repeated metadata and generous row padding still displaced result
     content. Combine provider/detail metadata, use compact row padding, and
     apply the native medium button shape. Also use neutral unselected Career
     tabs and tighter status padding.
   - Evidence: `../ui-material-20260928/screens-second/`; six UI tests passed
     here, but visual refinement continued before acceptance.
3. Final comparison: passed.
   - Both normal-size Home themes show the result row, with connection status
     beside Refresh and app visibility. Career retains its date column, four
     tabs, pending state, and results hierarchy.
   - At 200% text, controls wrap or scroll and remain readable; Career dates
     become full-width labels. Long content scrolls without horizontal clipping.
   - Evidence: linked final captures and
     [final UI log](../ui-material-20260928/ui-tests-final.log), six tests passed.

**Required fidelity surfaces**

| Surface | Assessment |
| --- | --- |
| Fonts / typography | Existing native theme font and Japanese fallback retained. Material heading/body hierarchy, weights and line spacing replace generated glyph shapes. Titles, dates and controls are legible in both themes; 200% wrapping was checked. |
| Spacing / layout | Flat separated lists, leading circular icons, date column and toolbar alignment follow the selected structure. Native touch sizes and real metadata add some height: the Home coverage footer can require scrolling. No persistent controls are hidden. |
| Colors / tokens | Background, foreground, primary, outlines and semantic warning colors come from the active theme. Primary alpha fills preserve the teal direction without fixed light-only colors. Both forced themes and palette unit tests pass. |
| Image quality / assets | Installed Material vector icons are sharp at capture density. No photos, logos, illustrations or bespoke raster assets are required. The mock's decorative timeline stroke and generated background texture are intentionally omitted. |
| Copy / content | Real repository content and existing routes retained; sample text exists only in tests. Unknown times stay unknown. Failed/stale data never becomes a confirmed empty pending state. Existing shell labels remain unchanged. |

**Validation**

- Debug APK and instrumentation APK built; Release Kotlin compiled.
- Targeted unit suite: 13 passed (3 agenda, 4 Home items, 6 theme palette).
- Emulator UI suite: 6 passed (Career/Home × light/dark/200% text), including
  refresh, app visibility, navigation callbacks, stale/offline states and scroll.
- Scoped ktlint and color-literal check passed. Android lint: 0 errors,
  159 warnings and 18 hints across the shared tree; this is not a warning-free
  baseline. [Build/check log](../ui-material-20260928/checks-final.log).
- Existing system/light/dark preference and dynamic-color wiring were verified
  in code. Appearance settings themselves were not exercised through an
  authenticated end-to-end session.

**Open questions / limits**

- No authenticated backend, physical-device deployment or manual TalkBack run
  was performed. Fixture coverage does not establish production data behavior.
- Native system bars remain system-owned; app-only captures exclude them.

**Implementation checklist**

- [x] Preserve repositories, navigation, read state and provider visibility.
- [x] Match the selected structure using existing Material components.
- [x] Support light/dark themes and enlarged system text.
- [x] Re-capture and compare after each visual correction.
- [x] Pass scoped build, lint, unit and emulator checks.

**Follow-up polish**

- [P3 / accepted] Stock Material tab-indicator width, native icon variants and
  existing shell labels differ slightly from the generated reference.
- [P3 / accepted] Flat theme surfaces omit the reference's decorative texture
  and timeline stroke; this keeps the design consistent with the native app.

final result: passed
