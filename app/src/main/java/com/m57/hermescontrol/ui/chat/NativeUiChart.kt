package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.LocalHermesChartColors
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun NativeChart(spec: ChartSpec) {
    when (spec.style) {
        "donut" -> NativeDonut(spec)
        "hbar" -> NativeHBar(spec)
        else -> NativeChart(spec.labels, spec.series, spec.style, spec.unit, spec.id, spec.target, spec.highlight)
    }
}

/** Series visibility shared by every multi-series chart: the last visible series cannot be hidden. */
private class SeriesVisibility(
    private val count: Int,
    private val hidden: Int,
    private val update: (Int) -> Unit,
) {
    val visible: List<Int> = (0 until count).filter { hidden and (1 shl it) == 0 }.ifEmpty { listOf(0) }

    fun toggle(index: Int) {
        if (index in visible && visible.size == 1) return
        update(if (index in visible) hidden or (1 shl index) else hidden and (1 shl index).inv())
    }
}

@Composable
private fun rememberSeriesVisibility(count: Int): SeriesVisibility {
    var hidden by rememberSaveable { mutableIntStateOf(0) }
    return SeriesVisibility(count, hidden) { hidden = it }
}

/** Line, bar, area and stacked charts: direct horizontal scrub, vertical movement left to the conversation. */
@Composable
internal fun NativeChart(
    labels: List<String>,
    series: List<ChartSeries>,
    style: String,
    unit: String,
    id: String,
    target: Double? = null,
    highlight: Int? = null,
    fillBetween: Boolean = false,
) {
    var selected by rememberSaveable { mutableIntStateOf(highlight ?: labels.lastIndex) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val index = selected.coerceIn(labels.indices)
    val visibility = rememberSeriesVisibility(series.size)
    val visible = visibility.visible
    val colors = LocalHermesChartColors.current
    val status = LocalHermesStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val barLike = style == "bar" || style == "stacked"
    val stacked = style == "stacked"

    fun total(i: Int) = visible.sumOf { series[it].values[i] }
    val dataValues =
        if (stacked) labels.indices.map(::total) else visible.flatMap { series[it].values }
    val ticks =
        niceTicks(
            minOf(dataValues.min(), target ?: dataValues.min()),
            maxOf(dataValues.max(), target ?: dataValues.max()),
        )
    val axisStyle = Tnum.copy(color = muted, fontSize = 11.sp)
    val tickLabels = ticks.values.map { NativeFormat.axisLabel(it, ticks.step, unit) }
    val tickLayouts = remember(tickLabels, axisStyle, density) { tickLabels.map { measurer.measure(it, axisStyle) } }
    val labelLayouts = remember(labels, axisStyle, density) { labels.map { measurer.measure(it, axisStyle) } }
    val boldLabel =
        remember(labels, highlight, axisStyle, density) {
            highlight?.let {
                measurer.measure(
                    labels[it],
                    axisStyle.copy(fontWeight = FontWeight.Bold, color = scheme.onSurface),
                )
            }
        }
    val leftPad = with(density) { (tickLayouts.maxOf { it.size.width } + 8.dp.toPx()) }
    val rightPad = with(density) { 8.dp.toPx() }

    fun format(v: Double) = NativeFormat.value(v, unit)
    val summary =
        labels[index] + "、" + visible.joinToString("、") { "${series[it].name} ${format(series[it].values[index])}" } +
            if (stacked && visible.size > 1) "、合計 ${format(total(index))}" else ""
    val tooltipLines =
        listOf(labels[index]) + visible.map { "${series[it].name} ${format(series[it].values[index])}" } +
            if (stacked && visible.size > 1) listOf("合計 ${format(total(index))}") else emptyList()
    val tooltipStyle = Tnum.copy(color = scheme.onSurface, fontSize = 12.sp)
    val tooltipLayouts = tooltipLines.map { measurer.measure(it, tooltipStyle) }
    val targetLayout = target?.let { measurer.measure("目標 ${format(it)}", axisStyle) }

    fun indexAt(
        x: Float,
        width: Float,
    ): Int {
        val plot = (width - leftPad - rightPad).coerceAtLeast(1f)
        val raw =
            if (barLike) {
                floor((x - leftPad) / plot * labels.size).toInt()
            } else {
                ((x - leftPad) / plot * labels.lastIndex).roundToInt()
            }
        return raw.coerceIn(labels.indices)
    }

    if (NativeFormat.axisUnit(unit).isNotEmpty()) NativeCaption(NativeFormat.axisUnit(unit))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .testTag("$id-plot")
            .semantics {
                contentDescription = summary
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        index.toFloat(),
                        0f..labels.lastIndex.toFloat(),
                        (labels.size - 2).coerceAtLeast(0),
                    )
                setProgress { value ->
                    selected = value.roundToInt().coerceIn(labels.indices)
                    touched = true
                    true
                }
            }.pointerInput(labels.size, style, leftPad) {
                detectTapGestures { point ->
                    selected = indexAt(point.x, size.width.toFloat())
                    touched = true
                }
            }.pointerInput(labels.size, style, leftPad) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    selected = indexAt(change.position.x, size.width.toFloat())
                    touched = true
                }
            },
    ) {
        val left = leftPad
        val right = size.width - rightPad
        val top = 8.dp.toPx()
        val labelHeight = labelLayouts.maxOf { it.size.height }
        val bottom = size.height - labelHeight - 6.dp.toPx()
        val slot = (right - left) / labels.size

        fun x(i: Int) =
            if (barLike) {
                left + slot * (i + .5f)
            } else {
                left +
                    (right - left) * i / labels.lastIndex.coerceAtLeast(1)
            }

        fun y(v: Double) = bottom - ((v - ticks.low) / (ticks.high - ticks.low)).toFloat() * (bottom - top)

        // Emphasis band: the user's selection once touched, otherwise the model's highlight.
        val band = if (touched) index else highlight
        if (barLike && band != null) {
            drawRect(scheme.onSurface.copy(alpha = .05f), Offset(left + slot * band, top), Size(slot, bottom - top))
        }
        ticks.values.forEachIndexed { t, v ->
            val zero = v == 0.0
            drawLine(
                if (zero) scheme.outline else scheme.outlineVariant,
                Offset(left, y(v)),
                Offset(right, y(v)),
                strokeWidth = if (zero) 1.5.dp.toPx() else 1.dp.toPx(),
            )
            val layout = tickLayouts[t]
            drawText(layout, topLeft = Offset(left - 6.dp.toPx() - layout.size.width, y(v) - layout.size.height / 2f))
        }
        if (fillBetween && visible.containsAll(listOf(0, 1))) {
            val upper = series[0].values
            val lower = series[1].values
            val path = Path()
            upper.indices.forEach { i ->
                if (i ==
                    0
                ) {
                    path.moveTo(x(i), y(upper[i]))
                } else {
                    path.lineTo(x(i), y(upper[i]))
                }
            }
            lower.indices.reversed().forEach { i -> path.lineTo(x(i), y(lower[i])) }
            path.close()
            val gain = upper.last() - lower.last()
            drawPath(path, (if (gain < 0) status.error else status.success).copy(alpha = .14f))
        }
        if (barLike) {
            val group = slot * .72f
            labels.indices.forEach { i ->
                var base = 0.0
                visible.forEachIndexed { j, s ->
                    val v = series[s].values[i]
                    if (stacked) {
                        val x0 = x(i) - group / 2
                        drawRect(
                            colors[s],
                            Offset(x0, y(base + v)),
                            Size(group, (y(base) - y(base + v)).coerceAtLeast(0f)),
                        )
                        base += v
                    } else {
                        val width = group / visible.size
                        val x0 = x(i) - group / 2 + j * width
                        val yTop = y(maxOf(v, 0.0))
                        drawRect(
                            colors[s],
                            Offset(x0 + width * .07f, yTop),
                            Size(width * .86f, y(minOf(v, 0.0)) - yTop),
                        )
                    }
                }
            }
        } else {
            visible.forEach { s ->
                val values = series[s].values
                val path = Path()
                values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                if (style == "area") {
                    val fill = Path().apply { addPath(path) }
                    fill.lineTo(x(values.lastIndex), y(0.0))
                    fill.lineTo(x(0), y(0.0))
                    fill.close()
                    drawPath(fill, colors[s].copy(alpha = .16f))
                }
                drawPath(
                    path,
                    colors[s],
                    style =
                        Stroke(
                            2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect =
                                if (s >
                                    0
                                ) {
                                    PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                } else {
                                    null
                                },
                        ),
                )
            }
        }
        if (target != null && targetLayout != null) {
            drawLine(
                scheme.outline,
                Offset(left, y(target)),
                Offset(right, y(target)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            val pad = 4.dp.toPx()
            val labelTop = (y(target) - targetLayout.size.height - 2.dp.toPx()).coerceAtLeast(0f)
            val labelLeft = right - targetLayout.size.width - pad
            drawRoundRect(
                scheme.surfaceContainerLow.copy(alpha = .92f),
                Offset(labelLeft - pad, labelTop),
                Size(targetLayout.size.width + pad * 2, targetLayout.size.height.toFloat()),
                CornerRadius(4.dp.toPx()),
            )
            drawText(targetLayout, topLeft = Offset(labelLeft, labelTop))
        }
        if (touched) {
            if (!barLike) {
                drawLine(muted.copy(alpha = .5f), Offset(x(index), top), Offset(x(index), bottom), 1.dp.toPx())
                visible.forEach { s ->
                    val point = Offset(x(index), y(series[s].values[index]))
                    drawCircle(scheme.surfaceContainerLow, 5.5.dp.toPx(), point)
                    drawCircle(colors[s], 4.dp.toPx(), point)
                }
            }
            drawTooltip(
                tooltipLayouts,
                visible.map { colors[it] },
                x(index),
                left,
                right,
                top,
                scheme.surfaceContainerHighest,
            )
        }
        drawXLabels(labelLayouts, boldLabel, highlight, ::x, bottom + 6.dp.toPx(), right - left, barLike)
    }
    if (series.size > 1) {
        NativeLegend(
            series.map { it.name },
            visibility,
            swatch = { s, color -> LegendSwatch(color, if (barLike) LegendShape.Square else LegendShape.Line, s > 0) },
        )
    }
    Text(summary, style = MaterialTheme.typography.bodySmall.merge(Tnum), modifier = Modifier.testTag("$id-selection"))
}

private fun DrawScope.drawTooltip(
    lines: List<androidx.compose.ui.text.TextLayoutResult>,
    swatches: List<Color>,
    anchor: Float,
    left: Float,
    right: Float,
    top: Float,
    background: Color,
) {
    val pad = 8.dp.toPx()
    val swatch = 8.dp.toPx()
    val width =
        lines.mapIndexed { i, l -> l.size.width + if (i in 1..swatches.size) swatch + 6.dp.toPx() else 0f }.max() +
            pad * 2
    val height = lines.sumOf { it.size.height } + pad * 2
    // Beside the selection line, never on top of the selected point.
    val gap = 8.dp.toPx()
    val preferred = if (anchor > (left + right) / 2) anchor - gap - width else anchor + gap
    val x0 = preferred.coerceIn(left, (right - width).coerceAtLeast(left))
    drawRoundRect(
        background.copy(alpha = .96f),
        Offset(x0, top),
        Size(width, height.toFloat()),
        CornerRadius(8.dp.toPx()),
    )
    var y = top + pad
    lines.forEachIndexed { i, layout ->
        var x = x0 + pad
        if (i in 1..swatches.size) {
            drawRect(swatches[i - 1], Offset(x, y + (layout.size.height - swatch) / 2), Size(swatch, swatch))
            x += swatch + 6.dp.toPx()
        }
        drawText(layout, topLeft = Offset(x, y))
        y += layout.size.height
    }
}

private fun DrawScope.drawXLabels(
    layouts: List<androidx.compose.ui.text.TextLayoutResult>,
    bold: androidx.compose.ui.text.TextLayoutResult?,
    highlight: Int?,
    x: (Int) -> Float,
    y: Float,
    plotWidth: Float,
    barLike: Boolean,
) {
    val n = layouts.size
    val widest = layouts.maxOf { it.size.width } + 12.dp.toPx()
    val fit = (plotWidth / widest).toInt().coerceIn(2, 5)
    val indices =
        if ((barLike && n <= 6) || n <= fit) {
            (0 until n).toList()
        } else {
            (0 until fit).map { k -> (k.toFloat() * (n - 1) / (fit - 1)).roundToInt() }.distinct()
        }
    indices.forEach { i ->
        val layout = if (i == highlight && bold != null) bold else layouts[i]
        val left = (x(i) - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(left, y))
    }
}

private enum class LegendShape { Line, Square }

@Composable
private fun LegendSwatch(
    color: Color,
    shape: LegendShape,
    dashed: Boolean,
) {
    Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
        if (shape == LegendShape.Square) {
            drawRect(color, Offset(3.dp.toPx(), 0f), Size(10.dp.toPx(), 10.dp.toPx()))
        } else {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                2.5.dp.toPx(),
                StrokeCap.Round,
                if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
    }
}

/** Toggle chips: swatch + name; hidden series keep only an outline and say 非表示. */
@Composable
private fun NativeLegend(
    names: List<String>,
    visibility: SeriesVisibility,
    swatch: @Composable (Int, Color) -> Unit,
) {
    val colors = LocalHermesChartColors.current
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { s, name ->
            val on = s in visibility.visible
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .toggleable(on, role = Role.Checkbox) { visibility.toggle(s) },
            ) {
                Row(
                    Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (on) swatch(s, colors[s])
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                    )
                    if (!on) {
                        Text(
                            "非表示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Ranking bars with the label above each bar so long Japanese names are never cut. */
@Composable
private fun NativeHBar(spec: ChartSpec) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val visibility = rememberSeriesVisibility(spec.series.size)
    val visible = visibility.visible
    val colors = LocalHermesChartColors.current
    val scheme = MaterialTheme.colorScheme
    val maxAbs =
        (visible.flatMap { spec.series[it].values } + listOfNotNull(spec.target)).maxOf { abs(it) }.takeIf { it > 0 }
            ?: 1.0

    fun format(v: Double) = NativeFormat.value(v, spec.unit)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        spec.labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            val emphasized = isSelected || i == spec.highlight
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) scheme.primary.copy(alpha = .10f) else Color.Transparent)
                    .selectable(isSelected) { selected = if (isSelected) -1 else i }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (emphasized) FontWeight.SemiBold else null,
                        modifier = Modifier.weight(1f),
                    )
                    if (visible.size == 1) {
                        Text(
                            format(spec.series[visible.single()].values[i]),
                            style = Tnum.copy(fontWeight = FontWeight.SemiBold),
                            color = if (emphasized) scheme.primary else scheme.onSurface,
                        )
                    }
                }
                visible.forEach { s ->
                    val value = spec.series[s].values[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerHighest)
                                .drawWithContent {
                                    drawContent()
                                    spec.target?.let {
                                        val x = size.width * (abs(it) / maxAbs).toFloat()
                                        drawLine(scheme.outline, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                                    }
                                },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((abs(value) / maxAbs).toFloat().coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(colors[s]),
                            )
                        }
                        if (visible.size > 1) {
                            Text(
                                format(value),
                                style = Tnum.copy(fontSize = 12.sp),
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(72.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (spec.series.size > 1) {
        NativeLegend(
            spec.series.map { it.name },
            visibility,
        ) { _, color -> LegendSwatch(color, LegendShape.Square, false) }
    }
    if (selected in spec.labels.indices) {
        Text(
            spec.labels[selected] + "、" +
                visible.joinToString("、") { "${spec.series[it].name} ${format(spec.series[it].values[selected])}" },
            style = MaterialTheme.typography.bodySmall.merge(Tnum),
            modifier = Modifier.testTag("${spec.id}-selection"),
        )
    }
}

/** Share of a whole (2..6 parts). Tapping a slice or legend row emphasizes it and shows its share. */
@Composable
private fun NativeDonut(spec: ChartSpec) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val values = spec.series.single().values
    val totalValue = values.sum()
    val chart = LocalHermesChartColors.current
    val scheme = MaterialTheme.colorScheme
    val colors =
        values.indices.map {
            if (it <
                4
            ) {
                chart[it]
            } else if (it == 4) {
                scheme.onSurfaceVariant
            } else {
                scheme.outline
            }
        }
    val active = selected.takeIf { it in values.indices }

    fun share(i: Int) = (values[i] / totalValue * 100).roundToInt()

    fun format(v: Double) = NativeFormat.value(v, spec.unit)
    val description =
        spec.labels.indices.joinToString(
            "、",
        ) { "${spec.labels[it]} ${format(values[it])}(${share(it)}%)" }
    val donut: @Composable () -> Unit = {
        Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(136.dp)
                    .testTag("${spec.id}-plot")
                    .semantics { contentDescription = description }
                    .pointerInput(values) {
                        detectTapGestures { point ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val distance = hypot(point.x - center.x, point.y - center.y)
                            if (distance < size.width * .22f || distance > size.width * .52f) return@detectTapGestures
                            var angle =
                                Math.toDegrees(
                                    atan2((point.y - center.y).toDouble(), (point.x - center.x).toDouble()),
                                ) +
                                    90
                            if (angle < 0) angle += 360
                            var start = 0.0
                            values.forEachIndexed { i, v ->
                                val sweep = v / totalValue * 360
                                if (angle >= start && angle < start + sweep) {
                                    selected = if (selected == i) -1 else i
                                    return@detectTapGestures
                                }
                                start += sweep
                            }
                        }
                    },
            ) {
                val stroke = 20.dp.toPx()
                val inset = 14.dp.toPx()
                var start = -90f
                values.forEachIndexed { i, v ->
                    val sweep = (v / totalValue * 360).toFloat()
                    val gap = if (sweep > 4f && values.count { it > 0 } > 1) 1.5f else 0f
                    val width = if (i == active) stroke + 6.dp.toPx() else stroke
                    drawArc(
                        colors[i].copy(alpha = if (active == null || i == active) 1f else .45f),
                        start + gap / 2,
                        (sweep - gap).coerceAtLeast(0f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(width),
                    )
                    start += sweep
                }
            }
            Column(
                Modifier.semantics(mergeDescendants = true) {}.testTag("${spec.id}-selection"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (active == null) NativeFormat.compact(totalValue, spec.unit) else "${share(active)}%",
                    style = Tnum.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    if (active == null) "合計" else spec.labels[active],
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    val legend: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            spec.labels.forEachIndexed { i, label ->
                val isSelected = i == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (isSelected) scheme.primary.copy(alpha = .10f) else Color.Transparent)
                        .selectable(isSelected) { selected = if (isSelected) -1 else i }
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).background(colors[i]))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        format(values[i]),
                        style = Tnum.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                    )
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        if (maxWidth >= 136.dp + 16.dp + 150.dp * fontScale) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                donut()
                legend(Modifier.weight(1f))
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                donut()
                legend(Modifier.fillMaxWidth())
            }
        }
    }
}
