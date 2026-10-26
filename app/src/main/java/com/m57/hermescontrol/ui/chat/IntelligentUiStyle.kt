package com.m57.hermescontrol.ui.chat

/** Host-owned presentation tokens. Values are validated before entering generated HTML. */
internal data class IntelligentUiTheme(
    val background: String,
    val foreground: String,
    val primary: String,
    val onPrimary: String = background,
    val muted: String = foreground,
    val surface: String = background,
    val secondary: String = primary,
    val tertiary: String = primary,
    val success: String = primary,
    val info: String = primary,
    val warning: String = primary,
    val error: String = primary,
    val corner: Float = 24f,
    val spacing: Float = 16f,
    val bodySize: Float = 14f,
    val titleSize: Float = 16f,
) {
    init {
        require(
            listOf(
                background,
                foreground,
                primary,
                onPrimary,
                muted,
                surface,
                secondary,
                tertiary,
                success,
                info,
                warning,
                error,
            ).all { it.matches(Regex("#[0-9a-fA-F]{6}")) },
        )
        require(listOf(corner, spacing, bodySize, titleSize).all { it.isFinite() && it in 4f..64f })
    }
}

/** Small, local design vocabulary for model-authored answers, shared by all chat callers. */
internal object IntelligentUiStyle {
    fun css(theme: IntelligentUiTheme): String =
        with(theme) {
            """
            :root{
              --hermes-background:$background;--hermes-foreground:$foreground;
              --hermes-primary:$primary;--hermes-on-primary:$onPrimary;--hermes-muted:$muted;
              --hermes-surface:$surface;--hermes-error:$error;--hermes-success:$success;
              --hermes-radius:${corner}px;--hermes-space:${spacing}px;
              --viz-series-1:$primary;--viz-series-2:$secondary;--viz-series-3:$tertiary;
              --viz-series-4:$info;--viz-series-5:$success;--viz-series-6:$warning;
              --viz-soft:color-mix(in srgb,$foreground 6%,transparent);
              --viz-line:color-mix(in srgb,$foreground 14%,transparent);
              --viz-small:.875rem;--viz-title:${titleSize}px;--viz-result:2.2rem;
            }
            *{box-sizing:border-box}html{font-size:${bodySize}px;color:var(--hermes-foreground);background:var(--hermes-background)}
            body{display:flow-root;margin:0;padding:var(--hermes-space);min-width:0;
              font:${bodySize}px/1.5 system-ui,sans-serif;overflow-wrap:anywhere;-webkit-text-size-adjust:100%}
            h1,h2,h3,p,figure{margin:0}h1,h2{font-size:var(--viz-title);font-weight:650;line-height:1.3;letter-spacing:-.025em}
            h3{font-size:1rem;line-height:1.4;font-weight:600}p{margin-block:.5em}
            svg,canvas,img{display:block;max-width:100%}svg,img{height:auto}
            button,input,select,textarea{font:inherit;accent-color:var(--hermes-primary)}
            button{min-height:48px;border:0;border-radius:var(--hermes-radius);padding:8px 16px;
              background:var(--hermes-primary);color:var(--hermes-on-primary);font-weight:600;cursor:pointer}
            button.viz-secondary{background:var(--viz-soft);color:var(--hermes-foreground)}
            input:not([type=range]):not([type=checkbox]):not([type=radio]),select,textarea{
              display:block;width:100%;min-height:48px;border:0;border-radius:var(--hermes-radius);
              padding:12px 16px;color:var(--hermes-foreground);background:var(--viz-soft);max-width:100%}
            input[type=number]{font-variant-numeric:tabular-nums}
            input[type=range]{display:block;width:100%;min-width:0;height:44px;margin:0;cursor:pointer}
            label{display:grid;gap:6px;min-width:0;font-size:var(--viz-small);color:var(--hermes-muted)}
            label:has(input[type=checkbox]),label:has(input[type=radio]){display:flex;align-items:center;gap:12px;min-height:48px}
            :focus-visible{outline:2px solid var(--hermes-primary);outline-offset:3px}
            :disabled{opacity:.5;cursor:default}a{color:var(--hermes-primary)}
            output{font-variant-numeric:tabular-nums}small,figcaption{font-size:var(--viz-small);color:var(--hermes-muted)}
            .viz{display:grid;gap:16px;min-width:0}.viz-header{display:grid;gap:4px}
            .viz-description,.viz-caption{color:var(--hermes-muted);font-size:var(--viz-small);line-height:1.6}
            .viz-header p{margin:0}.viz-result{display:grid;gap:4px;padding-block:2px}
            .viz-value{display:inline-block;font-size:var(--viz-result);font-weight:650;line-height:1.15;
              letter-spacing:-.045em;color:var(--hermes-foreground);font-variant-numeric:tabular-nums}
            .viz-unit{font-size:1rem;font-weight:450;letter-spacing:0;color:var(--hermes-muted)}
            .viz-controls{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,180px),1fr));
              gap:12px;padding-top:12px;border-top:1px solid var(--viz-line)}
            .viz-row{display:flex;align-items:center;justify-content:space-between;gap:8px 12px;flex-wrap:wrap}
            .viz-columns{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,160px),1fr));gap:16px}
            .viz-chart{width:100%;min-width:0}.viz-chart text{fill:var(--hermes-muted);font-family:inherit}
            .viz-legend{display:flex;flex-wrap:wrap;gap:8px 16px;font-size:var(--viz-small);color:var(--hermes-muted)}
            .viz-dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-inline-end:6px;background:var(--hermes-primary)}
            .viz-panel{padding:14px;border:1px solid var(--viz-line);border-radius:var(--hermes-radius);min-width:0}
            .viz-panel.viz-controls{border-top:1px solid var(--viz-line)}
            .viz-metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;min-width:0}
            .viz-stat{display:grid;gap:4px;min-width:0}.viz-stat>span{font-size:var(--viz-small);color:var(--hermes-muted)}
            .viz-stat>strong{font-size:1.15rem;font-variant-numeric:tabular-nums;line-height:1.35}
            .viz-segmented{display:flex;flex-wrap:wrap;gap:4px}
            .viz-segmented button{flex:1;min-width:44px;min-height:44px;padding:6px 10px;background:transparent;color:var(--hermes-muted)}
            .viz-segmented button[aria-pressed=true]{background:var(--viz-soft);color:var(--hermes-foreground)}
            .viz-action{min-height:44px;padding:6px 10px;background:transparent;color:var(--hermes-muted);font-size:var(--viz-small)}
            .viz-positive{color:var(--hermes-success)}.viz-negative{color:var(--hermes-error)}
            .viz-details{border-top:1px solid var(--viz-line);padding-top:12px}
            .viz-details summary{cursor:pointer;min-height:44px;align-content:center;font-weight:550}
            progress{width:100%;height:6px;border:0;border-radius:99px;accent-color:var(--hermes-primary)}
            progress::-webkit-progress-bar{background:var(--viz-soft);border-radius:99px}
            progress::-webkit-progress-value{background:var(--hermes-primary);border-radius:99px}
            table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
            th,td{text-align:start;padding:10px 6px;border-bottom:1px solid var(--viz-line)}
            th{font-size:var(--viz-small);font-weight:500;color:var(--hermes-muted)}
            @media(max-width:340px){:root{--viz-result:2rem}.viz{gap:14px}.viz-panel{padding:12px}}
            @media(prefers-reduced-motion:no-preference){button,input,select{transition:background-color .16s,opacity .16s}}
            @media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important}}
            """.trimIndent()
        }

    val authoringInstructions =
        """
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
        """.trimIndent()
}
