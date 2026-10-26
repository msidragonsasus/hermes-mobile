package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Box bounds from the last placement, read when drawing arrows; state so a moved box redraws them. */
private class FlowGeometry(
    size: Int,
) {
    var rects by mutableStateOf(List(size) { Rect.Zero })
}

/** Top-down boxes and arrows. Boxes share each row's width; arrows run from a box's bottom to the next box's top. */
@Composable
internal fun NativeFlow(spec: FlowSpec) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val geometry = remember(spec) { FlowGeometry(spec.nodes.size) }
    val description = remember(spec) { flowDescription(spec) }
    val labeledRows =
        remember(spec) {
            val rowOf = IntArray(spec.nodes.size)
            spec.rows.forEachIndexed { r, row -> row.forEach { rowOf[it] = r } }
            val ids = spec.nodes.withIndex().associate { it.value.id to it.index }
            spec.edges
                .filter { it.label.isNotBlank() }
                .mapNotNull { ids[it.from]?.let { i -> rowOf[i] } }
                .toSet()
        }
    val lineColor = scheme.outline
    val labelColor = scheme.onSurfaceVariant
    val labelBackground = scheme.surfaceContainerLow
    val labelStyle = MaterialTheme.typography.labelSmall
    NativeHeader(spec.title)
    Layout(
        content = { spec.rows.flatten().forEach { FlowBox(spec.nodes[it]) } },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = description }
                .drawBehind {
                    drawFlowEdges(spec, geometry, lineColor, labelColor, labelBackground, labelStyle, measurer)
                }.testTag("${spec.id}-flow"),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val plainBand = 28.dp.roundToPx()
        val labelBand = 44.dp.roundToPx()
        val widest = 220.dp.roundToPx()
        val rects = MutableList(spec.nodes.size) { Rect.Zero }
        var k = 0
        var y = 0
        val placed =
            spec.rows.flatMapIndexed { r, row ->
                // Equal-width boxes, centred, never wider than [widest] so a lone box stays compact.
                val cell = ((width - gap * (row.size - 1)) / row.size).coerceIn(0, widest)
                val start = (width - cell * row.size - gap * (row.size - 1)) / 2
                val inRow = measurables.subList(k, k + row.size)
                k += row.size
                val height = inRow.maxOf { it.maxIntrinsicHeight(cell) }
                val items =
                    row.mapIndexed { j, node ->
                        val placeable = inRow[j].measure(Constraints.fixed(cell, height))
                        val x = start + j * (cell + gap)
                        rects[node] = Rect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + height).toFloat())
                        Triple(placeable, x, y)
                    }
                y += height
                if (r < spec.rows.lastIndex) {
                    val band = if (r in labeledRows) labelBand else plainBand
                    y += band
                }
                items
            }
        if (rects != geometry.rects) geometry.rects = rects
        layout(width, y) {
            placed.forEach { (placeable, x, top) -> placeable.place(x, top) }
        }
    }
}

@Composable
private fun FlowBox(node: FlowNode) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val content = if (node.accent) scheme.onPrimaryContainer else scheme.onSurface
    Column(
        Modifier
            .clip(shape)
            .background(if (node.accent) scheme.primaryContainer else scheme.surface)
            .border(1.dp, if (node.accent) scheme.primary else scheme.outlineVariant, shape)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            node.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            textAlign = TextAlign.Center,
        )
        if (node.sub.isNotBlank()) {
            Text(
                node.sub,
                style = MaterialTheme.typography.bodySmall,
                color = if (node.accent) content else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun DrawScope.drawFlowEdges(
    spec: FlowSpec,
    geometry: FlowGeometry,
    lineColor: Color,
    labelColor: Color,
    labelBackground: Color,
    labelStyle: TextStyle,
    measurer: TextMeasurer,
) {
    val ids = spec.nodes.withIndex().associate { it.value.id to it.index }
    val rects = geometry.rects
    val edges = spec.edges.mapNotNull { e -> ids[e.from]?.let { f -> ids[e.to]?.let { t -> Triple(f, t, e.label) } } }

    // Spread several arrows leaving (or entering) one box across its width, ordered by the other end's x.
    fun spread(
        box: Int,
        others: List<Int>,
        other: Int,
    ): Float {
        val r = rects[box]
        if (others.size <= 1) return r.center.x
        val order = others.sortedBy { rects[it].center.x }.indexOf(other)
        return r.left + r.width * (order + 1) / (others.size + 1)
    }
    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    val head = 5.dp.toPx()
    edges.forEach { (from, to, label) ->
        val s = rects[from]
        val t = rects[to]
        val start = Offset(spread(from, edges.filter { it.first == from }.map { it.second }, to), s.bottom)
        val end = Offset(spread(to, edges.filter { it.second == to }.map { it.first }, from), t.top)
        val dy = (end.y - start.y).coerceAtLeast(1f)
        val tip = Offset(end.x, end.y - 1.dp.toPx())
        val path =
            Path().apply {
                moveTo(start.x, start.y)
                cubicTo(start.x, start.y + dy / 2, tip.x, tip.y - dy / 2, tip.x, tip.y - head)
            }
        drawPath(path, lineColor, style = stroke)
        drawPath(
            Path().apply {
                moveTo(tip.x - head * 0.8f, tip.y - head * 1.4f)
                lineTo(tip.x + head * 0.8f, tip.y - head * 1.4f)
                lineTo(tip.x, tip.y)
                close()
            },
            lineColor,
        )
        if (label.isNotBlank()) {
            // In the band just below the source box, where the curve is still near its start.
            val fraction = ((s.bottom + 22.dp.toPx() - start.y) / dy).coerceIn(0f, 1f)
            val y = start.y + dy * fraction
            val x = start.x + (end.x - start.x) * (fraction * fraction * (3 - 2 * fraction))
            val text = measurer.measure(label, labelStyle.copy(color = labelColor))
            val w = text.size.width.toFloat()
            val h = text.size.height.toFloat()
            val pad = 4.dp.toPx()
            val left = (x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
            drawRoundRect(
                labelBackground,
                topLeft = Offset(left - pad, y - h / 2),
                size = Size(w + pad * 2, h),
                cornerRadius = CornerRadius(h / 2),
            )
            drawText(text, topLeft = Offset(left, y - h / 2))
        }
    }
}
