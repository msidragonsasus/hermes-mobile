package com.m57.hermescontrol.ui.chat

/** Self-contained visual answers; no network, storage, native bridge, or host permissions. */
internal object IntelligentUiDocument {
    const val MAX_SOURCE_LENGTH = 240_000

    fun isInteractiveLanguage(language: String?): Boolean =
        language?.lowercase() in setOf("hermes-ui", "intelligent-ui", "liveui")

    fun canPreview(language: String?): Boolean =
        isInteractiveLanguage(language) || language?.lowercase() in setOf("html", "svg")

    private fun escapeAttribute(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun build(
        source: String,
        background: String,
        foreground: String,
        primary: String,
        dark: Boolean,
    ): String = build(source, IntelligentUiTheme(background, foreground, primary), dark)

    fun build(
        source: String,
        theme: IntelligentUiTheme,
        dark: Boolean,
    ): String {
        require(source.length <= MAX_SOURCE_LENGTH)
        val policy =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "img-src data:; connect-src 'none'; font-src 'none'; media-src 'none'; " +
                "frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        // The policy precedes generated markup so even an early script/base/meta cannot relax it.
        val child =
            """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$policy">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            :root{color-scheme:${if (dark) "dark" else "light"}}
            ${IntelligentUiStyle.css(theme)}
            </style><script>
            (()=>{
              const send=parent.postMessage.bind(parent);let pending=false,last=0;
              const report=()=>{pending=false;const body=document.body;if(!body)return;
                const height=Math.min(10000,Math.max(1,Math.ceil(body.getBoundingClientRect().height),body.scrollHeight));
                if(height!==last){last=height;send({kind:'hermes-height',height},'*')}};
              const schedule=()=>{if(!pending){pending=true;setTimeout(report,80)}};
              const observer=new ResizeObserver(schedule);
              addEventListener('DOMContentLoaded',()=>{observer.observe(document.body);schedule()},{once:true});
              addEventListener('resize',schedule);
            })();
            </script></head><body>$source</body></html>
            """.trimIndent()
        // Opaque iframe origin prevents access to the host DOM/cookies even if source tries parent/top.
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src 'self' about:; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100vh;overflow:hidden;background:${theme.background}}iframe{display:block;border:0;width:100%;height:100vh}</style>
            <script>
            (()=>{let height=0;
              Object.defineProperty(window,'__hermesPreviewHeight',{get:()=>height});
              addEventListener('message',event=>{const frame=document.querySelector('iframe');
                if(event.source!==frame?.contentWindow)return;
                const data=event.data;
                if(data?.kind==='hermes-height'&&typeof data.height==='number'&&Number.isFinite(data.height)&&data.height>0&&data.height<=10000){height=Math.ceil(data.height)}
              });
            })();
            </script>
            </head><body><iframe title="Interactive answer" sandbox="allow-scripts" referrerpolicy="no-referrer"
            srcdoc="${escapeAttribute(child)}"></iframe></body></html>
            """.trimIndent()
    }
}
