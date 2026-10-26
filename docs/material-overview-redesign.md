# Material overview redesign — 2026-09-28

## Approved direction

The user selected the attached Career agenda and global Home list mockups, with
the connection indicator moved into the refresh/display-settings row. Both
screens must support light and dark mode. This implements those two surfaces in
the existing native Compose application; the supplied mockups contain sample
data, not content to seed into the app.

## Existing components and public references

- Reuse `HermesScaffold`, `MaterialTheme`, `LocalSpacing`, semantic status colors,
  Navigation 3 routes, the saved Career area, and existing data repositories.
- Material `PrimaryTabRow` / `PrimaryScrollableTabRow`, tonal buttons and vector
  icons supply the controls. No new dependency or custom raster asset is needed.
- [Google Compose samples / Reply](https://github.com/android/compose-samples/tree/main/Reply)
  and [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
  were checked before coding for reusable navigation, theming and list patterns.

## Implementation decisions

- Career retains Home / Companies / Conversations / Library, now as Material tabs.
  Larger system text uses horizontally scrollable tabs.
- Career Home shows a dated agenda with company names, deadlines, events and
  existing detail actions. Missing dates and times remain explicitly unknown.
  Offset timestamps use Japan time, including day changes across midnight.
- Human decisions retain priority over passive results. A failed or stale read
  must not produce a confirmed "nothing pending" state.
- Global Home uses flat rows, semantic headings, circular Material icons, and
  tonal detail actions. Connection status shares the toolbar row at regular font
  size; it may wrap with enlarged text to preserve legibility.
- All colors resolve from the active theme. Light, dark, system preference,
  existing presets and dynamic colors continue through `HermesControlTheme`.
- Existing navigation, read tracking, provider visibility, loading/error states,
  and persistence are retained. No server changes or physical-device deployment.

## Intentional differences from generated images

- Native system bars/insets remain system-owned. Layout grows and scrolls for
  larger fonts; screenshot text is never baked into bitmap UI.
- Results use their actual source timestamp; source UI text is retained where a
  mock's wording would imply an unimplemented capability or misdescribe data.
- Exact generated gradients and decorative timeline strokes are omitted in
  favor of the existing flat theme and grouped date column. Material icons are
  vectors from the installed library.

Validation evidence and visual comparison are recorded in `../design-qa.md`.
