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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Arrow and label geometry calculated from the last measurement. */
private class FlowGeometry {
    var routes by mutableStateOf(emptyList<FlowRoute>())
}

private data class IndexedFlowEdge(
    val from: Int,
    val to: Int,
    val sourceRow: Int,
    val skipsRows: Boolean,
    val label: String,
)

private data class FlowRoute(
    val points: List<Offset>,
    val label: TextLayoutResult?,
    val labelBounds: Rect?,
)

internal fun flowRoutePoints(
    start: Offset,
    end: Offset,
    bendY: Float,
    gutterX: Float?,
    entryY: Float,
): List<Offset> =
    buildList {
        add(start)
        add(Offset(start.x, bendY))
        if (gutterX != null) {
            add(Offset(gutterX, bendY))
            add(Offset(gutterX, entryY))
            add(Offset(end.x, entryY))
        } else {
            add(Offset(end.x, bendY))
        }
        add(end)
    }

internal fun flowLabelBounds(
    center: Offset,
    width: Float,
    height: Float,
    contentWidth: Float,
): Rect {
    val left = (center.x - width / 2).coerceIn(0f, (contentWidth - width).coerceAtLeast(0f))
    return Rect(left, center.y - height / 2, left + width, center.y + height / 2)
}

/** Top-down boxes and arrows. Boxes share each row's width; arrows run from a box's bottom to the next box's top. */
@Composable
internal fun NativeFlow(spec: FlowSpec) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val geometry = remember(spec) { FlowGeometry() }
    val description = remember(spec) { flowDescription(spec) }
    val edges =
        remember(spec) {
            val rowOf = IntArray(spec.nodes.size)
            spec.rows.forEachIndexed { r, row -> row.forEach { rowOf[it] = r } }
            val ids = spec.nodes.withIndex().associate { it.value.id to it.index }
            spec.edges.map { edge ->
                val from = ids.getValue(edge.from)
                val to = ids.getValue(edge.to)
                IndexedFlowEdge(from, to, rowOf[from], rowOf[to] > rowOf[from] + 1, edge.label)
            }
        }
    val outgoing = remember(edges) { edges.groupBy { it.from } }
    val incoming = remember(edges) { edges.groupBy { it.to } }
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
                    drawFlowEdges(geometry.routes, lineColor, labelBackground)
                }.testTag("${spec.id}-flow"),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val plainBand = 28.dp.roundToPx()
        val labelGap = 6.dp.roundToPx()
        val gutterCount = edges.count { it.skipsRows }
        val gutter = if (gutterCount == 0) 0 else minOf(36.dp.roundToPx(), width / 6)
        val contentWidth = width - gutter
        val labelPadding = minOf(4.dp.roundToPx(), contentWidth / 4)
        val labels =
            edges.filter { it.label.isNotBlank() }.associateWith {
                measurer.measure(
                    it.label,
                    labelStyle.copy(color = labelColor),
                    constraints = Constraints(maxWidth = contentWidth - 2 * labelPadding),
                )
            }
        val labelsByRow = edges.filter { it in labels }.groupBy { it.sourceRow }
        val labelCenters = mutableMapOf<IndexedFlowEdge, Float>()
        val widest = 220.dp.roundToPx()
        val rects = MutableList(spec.nodes.size) { Rect.Zero }
        var k = 0
        var y = 0
        val placed =
            spec.rows.flatMapIndexed { r, row ->
                // Equal-width boxes, centred, never wider than [widest] so a lone box stays compact.
                val cell = ((contentWidth - gap * (row.size - 1)) / row.size).coerceIn(0, widest)
                val start = (contentWidth - cell * row.size - gap * (row.size - 1)) / 2
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
                    y += plainBand / 2
                    labelsByRow[r].orEmpty().forEach { edge ->
                        val height = labels.getValue(edge).size.height
                        labelCenters[edge] = y + height / 2f
                        y += height + labelGap
                    }
                    y += plainBand - plainBand / 2
                }
                items
            }
        var gutterLane = 0
        geometry.routes =
            edges.map { edge ->
                val source = rects[edge.from]
                val target = rects[edge.to]
                val start = Offset(portX(source, outgoing.getValue(edge.from), edge) { rects[it.to] }, source.bottom)
                val end =
                    Offset(
                        portX(target, incoming.getValue(edge.to), edge) { rects[it.from] },
                        target.top - 1.dp.toPx(),
                    )
                val gutterX =
                    if (edge.skipsRows) contentWidth + gutter * (++gutterLane).toFloat() / (gutterCount + 1) else null
                val bendY = labelCenters[edge] ?: (source.bottom + plainBand / 2f)
                val points = flowRoutePoints(start, end, bendY, gutterX, target.top - plainBand / 3f)
                val label = labels[edge]
                val bounds =
                    label?.let {
                        val labelX = if (gutterX == null) (start.x + end.x) / 2 else (start.x + contentWidth) / 2
                        flowLabelBounds(
                            Offset(labelX, bendY),
                            (it.size.width + 2 * labelPadding).toFloat(),
                            it.size.height.toFloat(),
                            contentWidth.toFloat(),
                        )
                    }
                FlowRoute(points, label, bounds)
            }
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

private fun portX(
    box: Rect,
    edges: List<IndexedFlowEdge>,
    edge: IndexedFlowEdge,
    otherBox: (IndexedFlowEdge) -> Rect,
): Float {
    val index = edges.sortedBy { otherBox(it).center.x }.indexOf(edge)
    return box.left + box.width * (index + 1) / (edges.size + 1)
}

private fun DrawScope.drawFlowEdges(
    routes: List<FlowRoute>,
    lineColor: Color,
    labelBackground: Color,
) {
    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    val head = 5.dp.toPx()
    routes.forEach { route ->
        val start = route.points.first()
        val tip = route.points.last()
        val path =
            Path().apply {
                moveTo(start.x, start.y)
                route.points
                    .drop(1)
                    .dropLast(1)
                    .forEach { lineTo(it.x, it.y) }
                lineTo(tip.x, tip.y - head)
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
    }
    routes.forEach { route ->
        val label = route.label ?: return@forEach
        val bounds = route.labelBounds ?: return@forEach
        drawRoundRect(
            labelBackground,
            bounds.topLeft,
            Size(bounds.width, bounds.height),
            CornerRadius(bounds.height / 2),
        )
        drawText(label, topLeft = Offset(bounds.center.x - label.size.width / 2f, bounds.top))
    }
}
