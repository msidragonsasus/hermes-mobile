package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeSvgTest {
    /** Chroma-key studio layout: green screen, dashed safe frame, subject, camera and labels. */
    private val chromaKey =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 260" width="400" height="260">
          <title>クロマキー配置図</title>
          <!-- background screen -->
          <rect x="40" y="20" width="320" height="120" rx="6" fill="#00b140" stroke="line" stroke-width="2"/>
          <rect x="70" y="35" width="260" height="90" fill="none" stroke="fg" stroke-width="2" stroke-dasharray="8 6"/>
          <g transform="translate(200, 180)">
            <circle cx="0" cy="-30" r="14" fill="accent"/>
            <ellipse cx="0" cy="0" rx="22" ry="12" fill="muted" opacity="0.8"/>
          </g>
          <polygon points="190,240 210,240 200,225" fill="warn"/>
          <line x1="200" y1="225" x2="200" y2="200" stroke="fg" stroke-linecap="round"/>
          <path d="M 20 250 L 80 250 Q 100 240 120 250" fill="none" stroke="good" stroke-opacity="0.6"/>
          <polyline points="300,200 330,210 360,200" fill="none" stroke="bad" stroke-linejoin="round"/>
          <text x="200" y="80" text-anchor="middle" dominant-baseline="middle" font-size="16" font-weight="bold" fill="surface">グリーンバック</text>
          <text x="200" y="152" text-anchor="middle" font-size="12" fill="fg">
            <tspan>被写体 </tspan><tspan font-weight="700" fill="accent">2m</tspan>
          </text>
          <g transform="rotate(-90 20 80) scale(1)"><text x="20" y="80" font-size="11" fill="muted">安全枠 &amp; 余白</text></g>
        </svg>
        """.trimIndent()

    @Test fun chromaKeyLayoutParsesWithEveryAllowedFeature() {
        val drawing = NativeSvgParser.parse(chromaKey)
        assertEquals(400f, drawing.width)
        assertEquals(260f, drawing.height)
        val nodes = flatten(drawing.root)
        val shapes = nodes.filterIsInstance<SvgNode.Shape>()
        val green = shapes.first { it.shape is SvgShape.Rect }
        assertEquals(SvgPaint.Hex("#00b140"), green.style.fill)
        assertEquals(SvgPaint.Theme("line"), green.style.stroke)
        val frame = shapes.filter { it.shape is SvgShape.Rect }[1]
        assertEquals(SvgPaint.None, frame.style.fill)
        assertEquals(listOf(8f, 6f), frame.style.dash)
        assertTrue(shapes.any { it.shape is SvgShape.Path })
        assertTrue(shapes.any { (it.shape as? SvgShape.Poly)?.closed == true })
        val group = nodes.filterIsInstance<SvgNode.Group>().first { it.transform.isNotEmpty() }
        assertEquals(listOf(SvgTransform.Translate(200f, 180f)), group.transform)
        val texts = nodes.filterIsInstance<SvgNode.Text>()
        assertEquals("グリーンバック", texts[0].runs.single().text)
        assertEquals("middle", texts[0].style.anchor)
        assertEquals(true, texts[0].style.bold)
        assertEquals(listOf("被写体 ", "2m"), texts[1].runs.map { it.text })
        assertEquals(true, texts[1].runs[1].style.bold)
        assertEquals("安全枠 & 余白", texts[2].runs.single().text)
        val rotated =
            nodes.filterIsInstance<SvgNode.Group>().first { g ->
                g.transform.any { it is SvgTransform.Rotate }
            }
        assertEquals(SvgTransform.Rotate(-90f, 20f, 80f), rotated.transform.first())
    }

    @Test fun componentRecordCarriesAltAndRejectsWithoutIt() {
        val record = record(chromaKey)
        val spec = NativeUiProtocol.parse(record, true).components.single() as SvgSpec
        assertEquals("緑の背景幕の前に被写体とカメラを置いた配置図", spec.alt)
        assertEquals(400f, spec.drawing.width)
        assertTrue(NativeUiProtocol.parse(record(chromaKey, alt = null), true).rejected)
        assertTrue(NativeUiProtocol.parse(record(chromaKey, alt = "  "), true).rejected)
        assertTrue(NativeUiProtocol.parse(record(chromaKey).dropLast(1) + ",\"href\":\"x\"}", true).rejected)
    }

    @Test fun disallowedElementsAttributesValuesAndDeclarationsRejectTheWholeDiagram() {
        val shape = "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\"/>"

        fun svg(inner: String) = "<svg viewBox=\"0 0 100 100\">$shape$inner</svg>"
        val rejected =
            mapOf(
                "script" to svg("<script>alert(1)</script>"),
                "style" to svg("<style>rect{fill:red}</style>"),
                "foreignObject" to svg("<foreignObject><div/></foreignObject>"),
                "image" to svg("<image width=\"10\" height=\"10\"/>"),
                "use" to svg("<use x=\"1\"/>"),
                "href" to svg("<path d=\"M0 0 L1 1\" href=\"#a\"/>"),
                "xlink:href" to svg("<path d=\"M0 0 L1 1\" xlink:href=\"#a\"/>"),
                "on attribute" to svg("<rect width=\"1\" height=\"1\" onclick=\"x()\"/>"),
                "onload" to "<svg viewBox=\"0 0 100 100\" onload=\"x()\">$shape</svg>",
                "url fill" to svg("<rect width=\"1\" height=\"1\" fill=\"url(#g)\"/>"),
                "url entity-encoded" to svg("<rect width=\"1\" height=\"1\" fill=\"&#117;rl(#g)\"/>"),
                "font-family" to svg("<text x=\"0\" y=\"10\" font-family=\"Evil\">a</text>"),
                "style attribute" to svg("<rect width=\"1\" height=\"1\" style=\"fill:red\"/>"),
                "class" to svg("<rect width=\"1\" height=\"1\" class=\"a\"/>"),
                "DOCTYPE" to "<!DOCTYPE svg [<!ENTITY x \"y\">]>" + svg(""),
                "ENTITY" to svg("<!ENTITY x SYSTEM \"file:///etc/passwd\">"),
                "external entity reference" to svg("<text x=\"0\" y=\"1\">&xxe;</text>"),
                "CDATA" to svg("<text x=\"0\" y=\"1\"><![CDATA[a]]></text>"),
                "processing instruction" to svg("<?php echo 1 ?>"),
                "matrix transform" to svg("<g transform=\"matrix(1 0 0 1 0 0)\"/>"),
                "skew transform" to svg("<g transform=\"skewX(10)\"/>"),
                "named color" to svg("<rect width=\"1\" height=\"1\" fill=\"red\"/>"),
                "nested svg" to svg("<svg viewBox=\"0 0 1 1\"/>"),
                "bad path" to svg("<path d=\"M0 0 url(x)\"/>"),
                "unclosed" to "<svg viewBox=\"0 0 100 100\"><g>",
            )
        rejected.forEach { (case, source) -> assertRejected(case, source) }
        assertRejected("missing viewBox", "<svg>$shape</svg>")
        assertRejected("zero viewBox", "<svg viewBox=\"0 0 0 10\">$shape</svg>")
        assertRejected("root is not svg", "<g>$shape</g>")
        assertRejected("wrong xmlns", "<svg xmlns=\"http://example.com/x\" viewBox=\"0 0 1 1\"/>")
    }

    @Test fun sourceIsLimitedTo24000Characters() {
        val head = "<svg viewBox=\"0 0 10 10\">"
        val tail = "</svg>"
        val exact = head + " ".repeat(NativeSvgParser.MAX_SOURCE - head.length - tail.length) + tail
        assertEquals(24_000, exact.length)
        NativeSvgParser.parse(exact)
        assertRejected("24,001 chars", head + " ".repeat(exact.length - head.length - tail.length + 1) + tail)
        assertTrue(NativeUiProtocol.parse(record(exact + " "), true).rejected)
        assertEquals(1, NativeUiProtocol.parse(record(exact), true).components.size)
    }

    @Test fun themeWordsResolveFromTheHostThemeAndHexIsExact() {
        val theme =
            mapOf(
                "fg" to Color(0.1f, 0.1f, 0.1f),
                "muted" to Color(0.2f, 0.2f, 0.2f),
                "surface" to Color(0.3f, 0.3f, 0.3f),
                "accent" to Color(0.4f, 0.4f, 0.4f),
                "good" to Color(0.5f, 0.5f, 0.5f),
                "bad" to Color(0.6f, 0.6f, 0.6f),
                "warn" to Color(0.7f, 0.7f, 0.7f),
                "line" to Color(0.8f, 0.8f, 0.8f),
            )
        theme.forEach { (word, color) -> assertEquals(color, resolveSvgPaint(SvgPaint.Theme(word), theme)) }
        assertEquals(Color(red = 0, green = 255, blue = 0), resolveSvgPaint(SvgPaint.Hex("#0f0"), theme))
        assertEquals(Color(red = 0, green = 177, blue = 64), resolveSvgPaint(SvgPaint.Hex("#00B140"), theme))
        assertEquals(
            Color(red = 0x11, green = 0x22, blue = 0x33, alpha = 0x44),
            resolveSvgPaint(SvgPaint.Hex("#11223344"), theme),
        )
        assertNull(resolveSvgPaint(SvgPaint.None, theme))
        // Every theme word parses; anything else is rejected.
        theme.keys.forEach {
            NativeSvgParser.parse("<svg viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\" fill=\"$it\"/></svg>")
        }
        assertRejected(
            "unknown word",
            "<svg viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\" fill=\"primary\"/></svg>",
        )
    }

    @Test fun inheritedStyleFollowsSvgRulesExceptOpacity() {
        val parent = SvgStyle(fill = SvgPaint.Theme("accent"), strokeWidth = 3f, opacity = 0.5f, fontSize = 20f)
        val child = SvgStyle(stroke = SvgPaint.Theme("fg")).inherit(parent)
        assertEquals(SvgPaint.Theme("accent"), child.fill)
        assertEquals(3f, child.strokeWidth)
        assertEquals(20f, child.fontSize)
        assertNull(child.opacity)
    }

    private fun record(
        svg: String,
        alt: String? = "緑の背景幕の前に被写体とカメラを置いた配置図",
    ): String =
        buildJsonObject {
            put("v", 1)
            put("id", "ck")
            put("type", "svg")
            put("title", "クロマキーの配置")
            put("svg", svg)
            if (alt != null) put("alt", alt)
        }.toString()

    private fun assertRejected(
        case: String,
        source: String,
    ) {
        try {
            NativeSvgParser.parse(source)
            fail("accepted: $case")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun flatten(node: SvgNode): List<SvgNode> =
        listOf(node) + if (node is SvgNode.Group) node.children.flatMap(::flatten) else emptyList()
}
