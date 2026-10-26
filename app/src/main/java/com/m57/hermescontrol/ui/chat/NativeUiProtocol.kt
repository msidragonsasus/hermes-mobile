package com.m57.hermescontrol.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.pow

/** Hermes catalog hermes.native/3 (records stay v:1), not the A2UI wire protocol. No expressions, remote code or action authority. */
internal object NativeUiProtocol {
    const val LANGUAGE = "hermes-ui-v2"
    const val MAX_SOURCE = 64_000

    // One record may carry an svg of up to 24,000 chars plus its JSON escaping.
    const val MAX_RECORD = 40_000
    const val MAX_COMPONENTS = 12
    const val MAX_ACTION = 24
    private val lineBreaks = setOf(CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)
    private val json = Json { isLenient = false }
    private val idPattern = Regex("[A-Za-z0-9_-]{1,48}")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2})?")
    private val chartStyles = setOf("line", "bar", "area", "stacked", "hbar", "donut")
    private val fencePattern = Regex("(?m)^\\s*(`{3,}|~{3,})hermes-ui-v2\\s*$")

    fun hasFence(text: String): Boolean = fencePattern.containsMatchIn(text)

    fun isNative(language: String?): Boolean = language?.lowercase() == LANGUAGE

    /** Replay complete NDJSON records. Incomplete tails wait; invalid updates retain prior values. */
    fun parse(
        source: String,
        complete: Boolean,
    ): NativeUiDocument {
        if (source.length > MAX_SOURCE) return parse(source.take(MAX_SOURCE), false).copy(rejected = true)
        val nodes = linkedMapOf<String, NativeUiSpec>()
        var rejected = false
        var pending = false
        val lines = source.lineSequence().toList()
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val lastUnterminated = index == lines.lastIndex && !complete && !source.endsWith('\n')
            val spec = runCatching { decode(line) }.getOrNull()
            if (spec == null) {
                if (lastUnterminated) pending = true else rejected = true
                continue
            }
            val previous = nodes[spec.id]
            if (previous != null && (spec.revision <= previous.revision || spec.kind != previous.kind)) continue
            if (previous == null && nodes.size >= MAX_COMPONENTS) {
                rejected = true
                continue
            }
            nodes[spec.id] = spec
        }
        return NativeUiDocument(nodes.values.toList(), rejected, pending || !complete)
    }

    private fun decode(line: String): NativeUiSpec {
        require(line.length <= MAX_RECORD)
        // Bound nesting before calling the JSON parser (including malformed deep payloads).
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in line) {
            if (escaped) {
                escaped = false
                continue
            }
            if (quoted && char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') quoted = !quoted
            if (!quoted) {
                if (char == '{' || char == '[') {
                    depth++
                    require(depth <= 8)
                }
                if (char == '}' || char == ']') {
                    depth--
                    require(depth >= 0)
                }
            }
        }
        val obj = json.parseToJsonElement(line) as? JsonObject ?: error("object")
        require(obj.int("v", 0) == 1)
        val id = obj.text("id", 48)
        require(idPattern.matches(id))
        val revision = obj.int("rev", 0).also { require(it in 0..1_000_000) }
        val kind = obj.text("type", 24)
        val title = obj.text("title", 160)
        val common = setOf("v", "id", "rev", "type", "title", "note")
        val note = obj.text("note", 1000, "")
        return when (kind) {
            "savings" -> {
                obj.keysOnly(common + setOf("monthly", "years", "rate", "monthlyMax", "cap", "initial", "action"))
                val max = obj.number("monthlyMax", 100_000.0, 1.0..10_000_000.0)
                SavingsSpec(
                    id,
                    revision,
                    title,
                    note,
                    obj.number("monthly", 30_000.0, 0.0..max),
                    obj.int("years", 20).also { require(it in 1..60) },
                    obj.number("rate", 5.0, -20.0..30.0),
                    max,
                    obj.number("cap", 18_000_000.0, 0.0..1_000_000_000.0),
                    obj.number("initial", 0.0, 0.0..1_000_000_000.0),
                    obj.action(),
                )
            }

            "comparison" -> {
                obj.keysOnly(common + setOf("columns", "rows", "action"))
                val columns = obj.strings("columns", 2..5, 100)
                val rows =
                    obj.array("rows", 1..30).map { row ->
                        (row as? JsonArray ?: error("row"))
                            .also { require(it.size == columns.size) }
                            .map { it.string(300) }
                    }
                ComparisonSpec(id, revision, title, note, columns, rows, obj.action())
            }

            "chart" -> {
                obj.keysOnly(common + setOf("labels", "series", "style", "unit", "target", "highlight"))
                val style = obj.text("style", 8, "line").also { require(it in chartStyles) }
                val labels = obj.strings("labels", if (style == "donut") 2..6 else 2..120, 80)
                if (style == "hbar") require(labels.size <= 12)
                val series =
                    obj.array("series", if (style == "donut") 1..1 else 1..4).map { item ->
                        val seriesObj = item as? JsonObject ?: error("series")
                        seriesObj.keysOnly(setOf("name", "values"))
                        ChartSeries(
                            seriesObj.text("name", 100),
                            seriesObj.array("values", labels.size..labels.size).map { it.finite() },
                        )
                    }
                if (style == "donut" || style == "stacked") require(series.all { s -> s.values.all { it >= 0 } })
                if (style == "donut") require(series.single().values.sum() > 0)
                ChartSpec(
                    id,
                    revision,
                    title,
                    note,
                    labels,
                    series,
                    style,
                    obj.text("unit", 30, ""),
                    obj["target"]?.finite(),
                    obj["highlight"]?.let { obj.int("highlight", 0).also { i -> require(i in labels.indices) } },
                )
            }

            "stat" -> {
                obj.keysOnly(common + setOf("items"))
                val items =
                    obj.array("items", 1..4).map { item ->
                        val o = item as? JsonObject ?: error("item")
                        o.keysOnly(setOf("label", "value", "unit", "delta", "good", "sub"))
                        StatItem(
                            o.text("label", 40),
                            o["value"]?.finite() ?: error("value"),
                            o.text("unit", 8, ""),
                            o["delta"]?.finite(),
                            o["good"]?.string(4)?.also { require(it in setOf("up", "down")) },
                            o.text("sub", 60, ""),
                        )
                    }
                StatSpec(id, revision, title, note, items)
            }

            "table" -> {
                obj.keysOnly(common + setOf("columns", "rows", "action"))
                val columns =
                    obj.array("columns", 2..5).map { item ->
                        val o = item as? JsonObject ?: error("column")
                        o.keysOnly(setOf("name", "highlight"))
                        TableColumn(o.text("name", 100), o.bool("highlight"))
                    }
                require(columns.count { it.highlight } <= 1)
                val rows =
                    obj.array("rows", 1..30).map { row ->
                        (row as? JsonArray ?: error("row"))
                            .also { require(it.size == columns.size) }
                            .map { it.cell() }
                    }
                TableSpec(id, revision, title, note, columns, rows, obj.action())
            }

            "choices" -> {
                obj.keysOnly(common + setOf("options", "action"))
                val options =
                    obj.array("options", 2..4).map { item ->
                        val o = item as? JsonObject ?: error("option")
                        o.keysOnly(setOf("key", "label", "pros", "cons", "scores", "recommended"))
                        val scores =
                            (o["scores"] ?: JsonObject(emptyMap())).let { raw ->
                                val map = raw as? JsonObject ?: error("scores")
                                require(map.size <= 3)
                                map.map { (name, score) ->
                                    require(name.isNotBlank() && name.length <= 12)
                                    val n =
                                        (score as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                                            ?: error("score")
                                    require(n in 1..5)
                                    name to n
                                }
                            }
                        ChoiceOption(
                            o.key(),
                            o.text("label", 80),
                            o.optionalStrings("pros", 3, 80),
                            o.optionalStrings("cons", 3, 80),
                            scores,
                            o.bool("recommended"),
                        )
                    }
                require(options.count { it.recommended } <= 1)
                require(options.map { it.key }.distinct().size == options.size)
                ChoicesSpec(id, revision, title, note, options, obj.action())
            }

            "steps" -> {
                obj.keysOnly(common + setOf("items"))
                val items =
                    obj.array("items", 1..12).map { item ->
                        val o = item as? JsonObject ?: error("step")
                        o.keysOnly(setOf("label", "detail", "state"))
                        StepItem(
                            o.text("label", 80),
                            o.text("detail", 120, ""),
                            o.text("state", 4).also { require(it in setOf("done", "now", "todo")) },
                        )
                    }
                StepsSpec(id, revision, title, note, items)
            }

            "checklist" -> {
                obj.keysOnly(common + setOf("items"))
                val items =
                    obj.array("items", 1..20).map { item ->
                        val o = item as? JsonObject ?: error("check")
                        o.keysOnly(setOf("key", "label", "detail"))
                        CheckItem(o.key(), o.text("label", 80), o.text("detail", 120, ""))
                    }
                require(items.map { it.key }.distinct().size == items.size)
                ChecklistSpec(id, revision, title, note, items)
            }

            "timeline" -> {
                obj.keysOnly(common + setOf("events"))
                val events =
                    obj.array("events", 1..20).map { item ->
                        val o = item as? JsonObject ?: error("event")
                        o.keysOnly(setOf("date", "label", "detail", "kind"))
                        val raw = o.text("date", 16)
                        require(datePattern.matches(raw))
                        val date = LocalDate.parse(raw.take(10))
                        val time = raw.drop(11).takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) }
                        TimelineEvent(
                            date,
                            time,
                            o.text("label", 80),
                            o.text("detail", 120, ""),
                            o.text("kind", 8, "event").also { require(it in setOf("event", "deadline", "done")) },
                        )
                    }
                TimelineSpec(id, revision, title, note, events.sortedBy { it.date.atTime(it.time ?: LocalTime.MIN) })
            }

            "svg" -> {
                obj.keysOnly(common + setOf("svg", "alt"))
                val svg = obj.text("svg", NativeSvgParser.MAX_SOURCE)
                val alt = obj.text("alt", 300).also { require(it.isNotBlank()) }
                SvgSpec(id, revision, title, note, svg, alt, NativeSvgParser.parse(svg))
            }

            "flow" -> {
                obj.keysOnly(common + setOf("nodes", "edges"))
                val nodes =
                    obj.array("nodes", 2..NativeFlowLayout.MAX_NODES).map { item ->
                        val o = item as? JsonObject ?: error("node")
                        o.keysOnly(setOf("id", "label", "sub", "accent"))
                        FlowNode(
                            o.text("id", 48).also { require(idPattern.matches(it)) },
                            o.text("label", 40).also { require(it.isNotBlank()) },
                            o.text("sub", 60, ""),
                            o.bool("accent"),
                        )
                    }
                val edges =
                    (if (obj["edges"] == null) JsonArray(emptyList()) else obj.array("edges", 0..24)).map { item ->
                        val o = item as? JsonObject ?: error("edge")
                        o.keysOnly(setOf("from", "to", "label"))
                        FlowEdge(o.text("from", 48), o.text("to", 48), o.text("label", 16, ""))
                    }
                FlowSpec(id, revision, title, note, nodes, edges, NativeFlowLayout.rows(nodes, edges))
            }

            else -> {
                error("unknown component")
            }
        }
    }

    /** Optional one-line button label that sends the shown conditions; 1..24 chars, no control characters. */
    private fun JsonObject.action(): String? =
        get("action")?.string(MAX_ACTION)?.also { label ->
            require(label.isNotBlank() && label.none { it.isISOControl() || it.category in lineBreaks })
        }

    private fun JsonElement.finite(): Double {
        val primitive = this as? JsonPrimitive ?: error("number")
        require(!primitive.isString)
        return (primitive.doubleOrNull ?: error("number")).also { require(it.isFinite() && it in -1e12..1e12) }
    }

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: error("boolean") }
            ?: false

    private fun JsonObject.key(): String = text("key", 48).also { require(idPattern.matches(it)) }

    private fun JsonObject.optionalStrings(
        key: String,
        max: Int,
        limit: Int,
    ): List<String> = if (get(key) == null) emptyList() else array(key, 0..max).map { it.string(limit) }

    private fun JsonElement.cell(): TableCell {
        if (this is JsonPrimitive) return TableCell(string(300))
        val o = this as? JsonObject ?: error("cell")
        o.keysOnly(setOf("text", "num", "unit", "mark"))
        val cell =
            TableCell(
                o["text"]?.string(300),
                o["num"]?.finite(),
                o.text("unit", 8, ""),
                o["mark"]?.string(4)?.also { require(it in setOf("good", "bad")) },
            )
        require(cell.text != null || cell.num != null)
        return cell
    }

    private fun JsonObject.keysOnly(keys: Set<String>) {
        require(this.keys.all { it in keys })
    }

    private fun JsonElement.string(limit: Int): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { require(it.length <= limit) }
            ?: error("string")

    private fun JsonObject.text(
        key: String,
        limit: Int,
        default: String? = null,
    ): String = get(key)?.string(limit) ?: default ?: error("missing")

    private fun JsonObject.int(
        key: String,
        default: Int,
    ): Int =
        get(key)?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: error("integer") }
            ?: default

    private fun JsonObject.number(
        key: String,
        default: Double,
        range: ClosedFloatingPointRange<Double>,
    ): Double =
        (
            get(key)?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull ?: error("number") }
                ?: default
        ).also { require(it.isFinite() && it in range) }

    private fun JsonObject.array(
        key: String,
        size: IntRange,
    ): JsonArray = (get(key) as? JsonArray ?: error("array")).also { require(it.size in size) }

    private fun JsonObject.strings(
        key: String,
        size: IntRange,
        limit: Int,
    ): List<String> = array(key, size).map { it.string(limit) }
}

internal data class NativeUiDocument(
    val components: List<NativeUiSpec>,
    val rejected: Boolean,
    val pending: Boolean,
)

internal sealed interface NativeUiSpec {
    val id: String
    val revision: Int
    val title: String
    val note: String
    val kind: String
}

internal data class SavingsSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val monthly: Double,
    val years: Int,
    val rate: Double,
    val monthlyMax: Double,
    val cap: Double,
    val initial: Double,
    val action: String? = null,
) : NativeUiSpec {
    override val kind = "savings"
}

internal data class ComparisonSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val action: String? = null,
) : NativeUiSpec {
    override val kind = "comparison"

    /** Legacy comparison JSON renders through the table component. */
    fun asTable(): TableSpec =
        TableSpec(
            id,
            revision,
            title,
            note,
            columns.map { TableColumn(it) },
            rows.map { row ->
                row.map { TableCell(it) }
            },
            action,
        )
}

internal data class ChartSeries(
    val name: String,
    val values: List<Double>,
)

internal data class ChartSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val labels: List<String>,
    val series: List<ChartSeries>,
    val style: String,
    val unit: String,
    val target: Double? = null,
    val highlight: Int? = null,
) : NativeUiSpec {
    override val kind = "chart"
}

internal data class StatItem(
    val label: String,
    val value: Double,
    val unit: String,
    val delta: Double?,
    val good: String?,
    val sub: String,
)

internal data class StatSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val items: List<StatItem>,
) : NativeUiSpec {
    override val kind = "stat"
}

internal data class TableColumn(
    val name: String,
    val highlight: Boolean = false,
)

/** A text cell, or a typed cell whose number is formatted by the app and used for sorting. */
internal data class TableCell(
    val text: String?,
    val num: Double? = null,
    val unit: String = "",
    val mark: String? = null,
) {
    val display: String get() = text ?: num?.let { NativeFormat.value(it, unit) }.orEmpty()
}

internal data class TableSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val columns: List<TableColumn>,
    val rows: List<List<TableCell>>,
    val action: String? = null,
) : NativeUiSpec {
    override val kind = "table"
}

internal data class ChoiceOption(
    val key: String,
    val label: String,
    val pros: List<String>,
    val cons: List<String>,
    val scores: List<Pair<String, Int>>,
    val recommended: Boolean,
)

internal data class ChoicesSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val options: List<ChoiceOption>,
    val action: String? = null,
) : NativeUiSpec {
    override val kind = "choices"
}

internal data class StepItem(
    val label: String,
    val detail: String,
    val state: String,
)

internal data class StepsSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val items: List<StepItem>,
) : NativeUiSpec {
    override val kind = "steps"
}

internal data class CheckItem(
    val key: String,
    val label: String,
    val detail: String,
)

internal data class ChecklistSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val items: List<CheckItem>,
) : NativeUiSpec {
    override val kind = "checklist"
}

internal data class TimelineEvent(
    val date: LocalDate,
    val time: LocalTime?,
    val label: String,
    val detail: String,
    val kind: String,
)

internal data class TimelineSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val events: List<TimelineEvent>,
) : NativeUiSpec {
    override val kind = "timeline"
}

/** A diagram in the strict SVG subset; [drawing] is the validated form that is drawn. */
internal data class SvgSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val svg: String,
    val alt: String,
    val drawing: SvgDrawing,
) : NativeUiSpec {
    override val kind = "svg"
}

internal data class FlowNode(
    val id: String,
    val label: String,
    val sub: String,
    val accent: Boolean,
)

internal data class FlowEdge(
    val from: String,
    val to: String,
    val label: String,
)

/** Boxes and arrows; [rows] holds node indexes top to bottom, computed and checked by [NativeFlowLayout]. */
internal data class FlowSpec(
    override val id: String,
    override val revision: Int,
    override val title: String,
    override val note: String,
    val nodes: List<FlowNode>,
    val edges: List<FlowEdge>,
    val rows: List<List<Int>>,
) : NativeUiSpec {
    override val kind = "flow"
}

internal data class SavingsPoint(
    val year: Int,
    val principal: Double,
    val value: Double,
)

internal data class SavingsResult(
    val points: List<SavingsPoint>,
    val capMonth: Int?,
) {
    val last: SavingsPoint get() = points.last()
}

/** Fixed month-end annuity; cap applies to new contributions, initial assets are separate. */
internal fun simulateSavings(
    monthly: Double,
    years: Int,
    rate: Double,
    cap: Double,
    initial: Double,
): SavingsResult {
    require(monthly.isFinite() && monthly in 0.0..10_000_000.0 && years in 1..60)
    require(rate.isFinite() && rate in -20.0..30.0 && cap.isFinite() && cap in 0.0..1e9)
    require(initial.isFinite() && initial in 0.0..1e9)
    val r = (1 + rate / 100).pow(1.0 / 12) - 1
    var value = initial
    var contributed = 0.0
    var capMonth: Int? = null
    val points = mutableListOf(SavingsPoint(0, initial, value))
    for (month in 1..years * 12) {
        val pay = minOf(monthly, (cap - contributed).coerceAtLeast(0.0))
        value = value * (1 + r) + pay
        contributed += pay
        if (capMonth == null && cap > 0 && contributed >= cap) capMonth = month
        if (month % 12 == 0) points += SavingsPoint(month / 12, initial + contributed, value)
    }
    return SavingsResult(points, capMonth)
}
