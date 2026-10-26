package com.m57.hermescontrol.ui.chat

/**
 * Strict parser for the native "svg" component: a small diagram subset, no external anything.
 *
 * It is not a general XML parser. It reads elements, attributes, text, comments and the five
 * predefined entities (plus numeric character references). A DOCTYPE, ENTITY, CDATA or
 * processing instruction is rejected before anything is interpreted, so there is nothing to
 * expand or fetch (no XXE surface). Any element, attribute or value outside the allowlist rejects
 * the whole document; the caller shows the shared "cannot display" notice.
 */
internal object NativeSvgParser {
    const val MAX_SOURCE = 24_000
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 16

    private val elements =
        setOf("svg", "g", "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "tspan", "title")
    private val presentation =
        setOf(
            "transform",
            "fill",
            "stroke",
            "stroke-width",
            "stroke-dasharray",
            "stroke-linecap",
            "stroke-linejoin",
            "opacity",
            "fill-opacity",
            "stroke-opacity",
            "font-size",
            "font-weight",
            "text-anchor",
            "dominant-baseline",
        )
    private val geometry =
        mapOf(
            "svg" to setOf("viewBox", "width", "height", "xmlns"),
            "g" to emptySet(),
            "rect" to setOf("x", "y", "width", "height", "rx", "ry"),
            "circle" to setOf("cx", "cy", "r"),
            "ellipse" to setOf("cx", "cy", "rx", "ry"),
            "line" to setOf("x1", "y1", "x2", "y2"),
            "polyline" to setOf("points"),
            "polygon" to setOf("points"),
            "path" to setOf("d"),
            "text" to setOf("x", "y"),
            "tspan" to setOf("x", "y"),
            "title" to emptySet(),
        )
    private val number = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val length = Regex("(${number.pattern})(?:px)?")
    private val hex = Regex("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    private val name = Regex("[A-Za-z][A-Za-z0-9_.:-]*")
    private val pathData = Regex("[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]+")
    private val transformItem = Regex("\\s*(translate|scale|rotate)\\s*\\(([^()]*)\\)\\s*,?")
    private val separators = Regex("[\\s,]+")
    private val whitespace = Regex("\\s+")
    private val entity = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos);")
    private val themeWords = setOf("fg", "muted", "surface", "accent", "good", "bad", "warn", "line")

    fun parse(source: String): SvgDrawing {
        require(source.length <= MAX_SOURCE) { "too long" }
        val upper = source.uppercase()
        require("<!DOCTYPE" !in upper && "<!ENTITY" !in upper && "<![CDATA[" !in upper) { "declaration" }
        val root = Reader(source).document()
        require(root.name == "svg") { "root" }
        val box =
            root.attrs["viewBox"]
                ?.trim()
                ?.split(separators)
                ?.map(::toNumber)
                ?: throw IllegalArgumentException("viewBox")
        require(box.size == 4 && box[2] > 0 && box[3] > 0 && box.all { it.isFinite() }) { "viewBox" }
        root.attrs["xmlns"]?.let { require(it == "http://www.w3.org/2000/svg") { "xmlns" } }
        listOf("width", "height").forEach { key -> root.attrs[key]?.let { require(isSize(it)) { key } } }
        var count = 0

        fun convert(
            element: Element,
            depth: Int,
        ): SvgNode? {
            require(depth <= MAX_DEPTH) { "depth" }
            require(++count <= MAX_NODES) { "nodes" }
            val style = style(element)
            val transform = element.attrs["transform"]?.let(::transforms).orEmpty()

            fun n(key: String) = element.attrs[key]?.let(::toLength) ?: 0f
            return when (element.name) {
                "svg", "g" -> {
                    require(element.name == "g" || depth == 0) { "nested svg" }
                    val children = element.children.filterIsInstance<Element>().mapNotNull { convert(it, depth + 1) }
                    SvgNode.Group(transform, style, children)
                }

                "title" -> {
                    null
                }

                "rect" -> {
                    SvgNode.Shape(
                        SvgShape.Rect(n("x"), n("y"), n("width"), n("height"), n("rx"), n("ry")),
                        transform,
                        style,
                    )
                }

                "circle" -> {
                    SvgNode.Shape(SvgShape.Ellipse(n("cx"), n("cy"), n("r"), n("r")), transform, style)
                }

                "ellipse" -> {
                    SvgNode.Shape(SvgShape.Ellipse(n("cx"), n("cy"), n("rx"), n("ry")), transform, style)
                }

                "line" -> {
                    SvgNode.Shape(SvgShape.Line(n("x1"), n("y1"), n("x2"), n("y2")), transform, style)
                }

                "polyline", "polygon" -> {
                    val values =
                        element.attrs["points"]
                            ?.trim()
                            ?.split(separators)
                            ?.map(::toNumber)
                            .orEmpty()
                    require(values.size >= 4 && values.size % 2 == 0) { "points" }
                    SvgNode.Shape(SvgShape.Poly(values, closed = element.name == "polygon"), transform, style)
                }

                "path" -> {
                    val d = element.attrs["d"]?.trim().orEmpty()
                    require(d.isNotEmpty() && d.length <= 8_000 && pathData.matches(d) && d.first() in "Mm") { "d" }
                    SvgNode.Shape(SvgShape.Path(d), transform, style)
                }

                "text" -> {
                    val runs = mutableListOf<SvgTextRun>()
                    element.children.forEach { child ->
                        when (child) {
                            is String -> {
                                if (child.isNotBlank()) runs += SvgTextRun(child.collapse(), null, null, SvgStyle())
                            }

                            is Element -> {
                                require(child.name == "tspan" || child.name == "title") { "text child" }
                                require(++count <= MAX_NODES) { "nodes" }
                                if (child.name == "tspan") {
                                    require(child.children.all { it is String }) { "tspan child" }
                                    val text = child.children.joinToString("") { it as String }.collapse()
                                    runs +=
                                        SvgTextRun(
                                            text,
                                            child.attrs["x"]?.let(::toLength),
                                            child.attrs["y"]?.let(::toLength),
                                            style(child),
                                        )
                                }
                            }
                        }
                    }
                    require(runs.sumOf { it.text.length } <= 2_000) { "text" }
                    SvgNode.Text(n("x"), n("y"), runs, transform, style)
                }

                else -> {
                    // tspan outside text, or an element validated above but unexpected here.
                    throw IllegalArgumentException(element.name)
                }
            }
        }
        val drawing = convert(root, 0) as SvgNode.Group
        return SvgDrawing(box[0], box[1], box[2], box[3], drawing)
    }

    private fun String.collapse() = replace(whitespace, " ").let { if (it.isBlank()) "" else it }

    private fun isSize(value: String): Boolean {
        val v = value.trim()
        return length.matches(v) || (v.endsWith("%") && number.matches(v.dropLast(1)))
    }

    private fun toNumber(value: String): Float {
        require(number.matches(value)) { "number" }
        return value.toFloat().also { require(it.isFinite() && it in -1e6f..1e6f) { "range" } }
    }

    private fun toLength(value: String): Float {
        val match = length.matchEntire(value.trim()) ?: throw IllegalArgumentException("length")
        return toNumber(match.groupValues[1])
    }

    private fun transforms(value: String): List<SvgTransform> {
        var rest = value.trim()
        val result = mutableListOf<SvgTransform>()
        while (rest.isNotEmpty()) {
            val match = transformItem.matchAt(rest, 0) ?: throw IllegalArgumentException("transform")
            val args =
                match.groupValues[2]
                    .trim()
                    .split(separators)
                    .filter { it.isNotEmpty() }
                    .map(::toNumber)
            result +=
                when (match.groupValues[1]) {
                    "translate" -> {
                        require(args.size in 1..2) { "translate" }
                        SvgTransform.Translate(args[0], args.getOrElse(1) { 0f })
                    }

                    "scale" -> {
                        require(args.size in 1..2) { "scale" }
                        SvgTransform.Scale(args[0], args.getOrElse(1) { args[0] })
                    }

                    else -> {
                        require(args.size == 1 || args.size == 3) { "rotate" }
                        SvgTransform.Rotate(args[0], args.getOrElse(1) { 0f }, args.getOrElse(2) { 0f })
                    }
                }
            rest = rest.substring(match.range.last + 1).trim()
            require(result.size <= 8) { "transform" }
        }
        return result
    }

    private fun paint(value: String): SvgPaint {
        val v = value.trim()
        return when {
            v == "none" -> SvgPaint.None
            v in themeWords -> SvgPaint.Theme(v)
            hex.matches(v) -> SvgPaint.Hex(v)
            else -> throw IllegalArgumentException("color")
        }
    }

    private fun opacity(value: String) = toNumber(value.trim()).also { require(it in 0f..1f) { "opacity" } }

    private fun style(element: Element): SvgStyle {
        val a = element.attrs
        return SvgStyle(
            fill = a["fill"]?.let(::paint),
            stroke = a["stroke"]?.let(::paint),
            strokeWidth = a["stroke-width"]?.let(::toLength)?.also { require(it >= 0f) { "stroke-width" } },
            dash =
                a["stroke-dasharray"]?.trim()?.let { v ->
                    if (v == "none") {
                        emptyList()
                    } else {
                        v.split(separators).map(::toLength).also { d ->
                            require(d.size in 1..8 && d.all { it >= 0f } && d.any { it > 0f }) { "dasharray" }
                        }
                    }
                },
            cap = a["stroke-linecap"]?.trim()?.also { require(it in setOf("butt", "round", "square")) { "linecap" } },
            join =
                a["stroke-linejoin"]?.trim()?.also {
                    require(
                        it in setOf("miter", "round", "bevel"),
                    ) { "linejoin" }
                },
            opacity = a["opacity"]?.let(::opacity),
            fillOpacity = a["fill-opacity"]?.let(::opacity),
            strokeOpacity = a["stroke-opacity"]?.let(::opacity),
            fontSize = a["font-size"]?.let(::toLength)?.also { require(it in 1f..400f) { "font-size" } },
            bold =
                a["font-weight"]?.trim()?.let {
                    when (it) {
                        "normal" -> {
                            false
                        }

                        "bold" -> {
                            true
                        }

                        else -> {
                            (it.toIntOrNull()?.takeIf { w -> w in 100..900 && w % 100 == 0 } ?: error("font-weight")) >=
                                600
                        }
                    }
                },
            anchor =
                a["text-anchor"]?.trim()?.also {
                    require(
                        it in setOf("start", "middle", "end"),
                    ) { "text-anchor" }
                },
            baseline =
                a["dominant-baseline"]?.trim()?.also {
                    require(
                        it in setOf("auto", "alphabetic", "middle", "central", "hanging", "text-before-edge"),
                    ) { "baseline" }
                },
        )
    }

    private class Element(
        val name: String,
        val attrs: Map<String, String>,
        val children: List<Any>,
    )

    /** Minimal tokenizer for the subset above; anything unusual is an error, never skipped. */
    private class Reader(
        private val s: String,
    ) {
        private var i = 0

        fun document(): Element {
            skipMisc(allowDeclaration = true)
            val root = element()
            skipMisc(allowDeclaration = false)
            require(i == s.length) { "trailing" }
            return root
        }

        private fun skipMisc(allowDeclaration: Boolean) {
            var declaration = allowDeclaration
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                when {
                    s.startsWith("<!--", i) -> {
                        comment()
                    }

                    declaration && s.startsWith("<?xml", i) -> {
                        val end = s.indexOf("?>", i)
                        require(end > 0) { "declaration" }
                        i = end + 2
                    }

                    else -> {
                        return
                    }
                }
                declaration = false
            }
        }

        private fun comment() {
            val end = s.indexOf("-->", i + 4)
            require(end > 0) { "comment" }
            i = end + 3
        }

        private fun element(): Element {
            require(s.startsWith("<", i)) { "element" }
            i++
            val tag = readName()
            require(':' !in tag && tag in elements) { "element $tag" }
            val attrs = linkedMapOf<String, String>()
            while (true) {
                val spaced = skipSpace()
                if (s.startsWith("/>", i)) {
                    i += 2
                    return Element(tag, checked(tag, attrs), emptyList())
                }
                if (s.startsWith(">", i)) {
                    i++
                    break
                }
                require(spaced) { "attribute" }
                val key = readName()
                skipSpace()
                require(s.startsWith("=", i)) { "attribute" }
                i++
                skipSpace()
                val quote = s.getOrNull(i)
                require(quote == '"' || quote == '\'') { "attribute" }
                val end = s.indexOf(quote, i + 1)
                require(end > 0) { "attribute" }
                val raw = s.substring(i + 1, end)
                require('<' !in raw) { "attribute" }
                require(attrs.put(key, decode(raw)) == null) { "duplicate $key" }
                i = end + 1
            }
            val children = mutableListOf<Any>()
            val text = StringBuilder()
            while (true) {
                require(i < s.length) { "unclosed $tag" }
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        require(readName() == tag) { "mismatch" }
                        skipSpace()
                        require(s.startsWith(">", i)) { "close" }
                        i++
                        if (text.isNotEmpty()) children += text.toString()
                        return Element(tag, checked(tag, attrs), children)
                    }

                    s.startsWith("<!--", i) -> {
                        comment()
                    }

                    s.startsWith("<", i) -> {
                        require(!s.startsWith("<!", i) && !s.startsWith("<?", i)) { "markup" }
                        if (text.isNotEmpty()) {
                            children += text.toString()
                            text.clear()
                        }
                        children += element()
                    }

                    else -> {
                        val next = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        text.append(decode(s.substring(i, next)))
                        i = next
                    }
                }
            }
        }

        private fun checked(
            tag: String,
            attrs: Map<String, String>,
        ): Map<String, String> {
            attrs.keys.forEach { key ->
                val lower = key.lowercase()
                require(!lower.startsWith("on") && "href" !in lower && ':' !in key) { "attribute $key" }
                require(key in presentation || key in geometry.getValue(tag)) { "attribute $key" }
            }
            attrs.values.forEach { value ->
                val lower = value.lowercase()
                require("url(" !in lower && "javascript:" !in lower && "@import" !in lower) { "value" }
            }
            return attrs
        }

        private fun skipSpace(): Boolean {
            val start = i
            while (i < s.length && s[i].isWhitespace()) i++
            return i > start
        }

        private fun readName(): String {
            val match = name.matchAt(s, i) ?: throw IllegalArgumentException("name")
            i = match.range.last + 1
            return match.value
        }

        private fun decode(raw: String): String {
            if ('&' !in raw) return raw
            val out = StringBuilder()
            var p = 0
            while (p < raw.length) {
                val amp = raw.indexOf('&', p)
                if (amp < 0) {
                    out.append(raw, p, raw.length)
                    break
                }
                out.append(raw, p, amp)
                val match = entity.matchAt(raw, amp) ?: throw IllegalArgumentException("entity")
                val body = match.groupValues[1]
                when {
                    body.startsWith("#x") -> out.appendCodePoint(body.drop(2).toInt(16).also(::checkCodePoint))
                    body.startsWith("#") -> out.appendCodePoint(body.drop(1).toInt().also(::checkCodePoint))
                    body == "amp" -> out.append('&')
                    body == "lt" -> out.append('<')
                    body == "gt" -> out.append('>')
                    body == "quot" -> out.append('"')
                    else -> out.append('\'')
                }
                p = match.range.last + 1
            }
            return out.toString()
        }

        private fun checkCodePoint(cp: Int) {
            require(cp in 0x20..0x10FFFF && cp !in 0xD800..0xDFFF) { "character" }
        }
    }
}

internal data class SvgDrawing(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val root: SvgNode.Group,
)

internal sealed interface SvgNode {
    val transform: List<SvgTransform>
    val style: SvgStyle

    data class Group(
        override val transform: List<SvgTransform>,
        override val style: SvgStyle,
        val children: List<SvgNode>,
    ) : SvgNode

    data class Shape(
        val shape: SvgShape,
        override val transform: List<SvgTransform>,
        override val style: SvgStyle,
    ) : SvgNode

    data class Text(
        val x: Float,
        val y: Float,
        val runs: List<SvgTextRun>,
        override val transform: List<SvgTransform>,
        override val style: SvgStyle,
    ) : SvgNode
}

internal sealed interface SvgShape {
    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rx: Float,
        val ry: Float,
    ) : SvgShape

    data class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
    ) : SvgShape

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) : SvgShape

    data class Poly(
        val points: List<Float>,
        val closed: Boolean,
    ) : SvgShape

    data class Path(
        val d: String,
    ) : SvgShape
}

internal data class SvgTextRun(
    val text: String,
    val x: Float?,
    val y: Float?,
    val style: SvgStyle,
)

internal sealed interface SvgTransform {
    data class Translate(
        val x: Float,
        val y: Float,
    ) : SvgTransform

    data class Scale(
        val x: Float,
        val y: Float,
    ) : SvgTransform

    data class Rotate(
        val degrees: Float,
        val cx: Float,
        val cy: Float,
    ) : SvgTransform
}

internal sealed interface SvgPaint {
    data object None : SvgPaint

    /** fg, muted, surface, accent, good, bad, warn, line: resolved from the host theme when drawn. */
    data class Theme(
        val word: String,
    ) : SvgPaint

    data class Hex(
        val value: String,
    ) : SvgPaint
}

/** Declared presentation values; null means inherit (opacity is per element, as in SVG). */
internal data class SvgStyle(
    val fill: SvgPaint? = null,
    val stroke: SvgPaint? = null,
    val strokeWidth: Float? = null,
    val dash: List<Float>? = null,
    val cap: String? = null,
    val join: String? = null,
    val opacity: Float? = null,
    val fillOpacity: Float? = null,
    val strokeOpacity: Float? = null,
    val fontSize: Float? = null,
    val bold: Boolean? = null,
    val anchor: String? = null,
    val baseline: String? = null,
) {
    /** Child values override the inherited ones; opacity is never inherited. */
    fun inherit(parent: SvgStyle): SvgStyle =
        SvgStyle(
            fill = fill ?: parent.fill,
            stroke = stroke ?: parent.stroke,
            strokeWidth = strokeWidth ?: parent.strokeWidth,
            dash = dash ?: parent.dash,
            cap = cap ?: parent.cap,
            join = join ?: parent.join,
            opacity = opacity,
            fillOpacity = fillOpacity ?: parent.fillOpacity,
            strokeOpacity = strokeOpacity ?: parent.strokeOpacity,
            fontSize = fontSize ?: parent.fontSize,
            bold = bold ?: parent.bold,
            anchor = anchor ?: parent.anchor,
            baseline = baseline ?: parent.baseline,
        )
}
