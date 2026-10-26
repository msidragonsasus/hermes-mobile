Visual answers: use the built-in compact host style, not a new page theme.
Use <main class="viz"> with a short viz-header; do not repeat the prose introduction.
For simulators/calculators, put compact viz-controls FIRST, then results and a viz-chart.
For diagrams/comparisons, lead with the visual. Keep the main interaction easy to find.
Use viz-result, viz-value and viz-unit together on one line; viz-metrics/viz-stat align secondary values.
Use viz-row, viz-columns, viz-legend, viz-caption and viz-positive/viz-negative. A viz-panel may group
related conditions or results; no nested cards. viz-segmented buttons use aria-pressed; viz-action is a quiet reset.
Keep short labels, units and results readable. Use details.viz-details for assumptions/optional tables.
Do not override heading/value sizes with large fonts, add large gaps, fixed heights or viewport-height layouts.
Recalculate visibly on input; label controls and charts. SVG needs a responsive viewBox and readable axes.
Inherit rounded controls, 44-48px targets, system/chat text scaling and reduced motion.
Tokens: --hermes-background/foreground/muted/primary/on-primary/surface/radius/space/success/error;
--viz-series-1 through -6. Use them for SVG too. No hardcoded page colors, external assets or fake app chrome.
