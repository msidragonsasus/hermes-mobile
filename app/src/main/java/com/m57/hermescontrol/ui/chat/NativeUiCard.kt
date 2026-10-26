package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Validated native catalog, shared by restored and streaming Markdown in every chat renderer. */
@Composable
internal fun NativeUiCard(
    source: String,
    complete: Boolean,
    identity: String?,
    streaming: Boolean,
) {
    val document = remember(source, complete) { NativeUiProtocol.parse(source, complete) }
    val host = LocalNativeUiHost.current
    val persistent = host != null && identity != null
    DisableSelection {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            document.components.forEach { spec ->
                val stateKey = nativeUiKey(host?.scope.orEmpty(), identity.orEmpty(), spec.id)
                key(stateKey) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (spec) {
                                is SavingsSpec -> {
                                    NativeSavings(spec, stateKey, persistent)
                                }

                                is ComparisonSpec -> {
                                    NativeTable(spec.asTable())
                                }

                                is TableSpec -> {
                                    NativeTable(spec)
                                }

                                is ChartSpec -> {
                                    NativeHeader(spec.title)
                                    NativeChart(spec)
                                }

                                is StatSpec -> {
                                    NativeStat(spec)
                                }

                                is ChoicesSpec -> {
                                    NativeChoices(spec)
                                }

                                is StepsSpec -> {
                                    NativeSteps(spec)
                                }

                                is ChecklistSpec -> {
                                    NativeChecklist(spec, stateKey, persistent)
                                }

                                is TimelineSpec -> {
                                    NativeTimeline(spec)
                                }

                                is SvgSpec -> {
                                    NativeSvg(spec)
                                }

                                is FlowSpec -> {
                                    NativeFlow(spec)
                                }
                            }
                            if (spec.note.isNotBlank()) NativeCaption(spec.note)
                        }
                    }
                }
            }
            if (document.rejected) {
                Text(
                    "一部の図解を表示できません。対応していない形式か、設定値に問題があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalHermesStatusColors.current.warning,
                )
            }
            if (document.pending) {
                Text(
                    if (streaming) "続きを生成中…" else "図解の生成が途中で終了しました。表示できた部分は操作できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tabular figures for every number the native catalog shows. */
internal val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** Shared component header; [trailing] reserves button height so values never jump under a finger. */
@Composable
internal fun NativeHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (trailing != null) 40.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Composable
internal fun NativeSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun NativeCaption(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * One-tap send of the shown conditions through [NativeUiHost.submit]. Inverse-surface pill:
 * light text on dark in the light theme, dark on light in the dark theme. Disabled while the
 * host cannot send; a tap is ignored until the host accepts new input again.
 */
@Composable
internal fun NativeDiscussButton(
    text: String,
    tag: String,
    message: () -> String,
) {
    val host = LocalNativeUiHost.current ?: return
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = { if (host.canSubmit) host.submit(message()) },
        enabled = host.canSubmit,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = scheme.onSurface,
                contentColor = scheme.surface,
                disabledContainerColor = scheme.onSurface.copy(alpha = .12f),
                disabledContentColor = scheme.onSurface.copy(alpha = .38f),
            ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag(tag),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun NativeStorageWarning(failed: Boolean) {
    if (failed) {
        Text(
            "入力値を保存できませんでした。現在の画面では操作を続けられます。",
            style = MaterialTheme.typography.bodySmall,
            color = LocalHermesStatusColors.current.warning,
        )
    }
}

/** Large number with a smaller trailing unit: 1,542 万円. */
internal fun amountText(
    formatted: String,
    unitSize: TextUnit,
): AnnotatedString {
    val (number, unit) = NativeFormat.split(formatted)
    return buildAnnotatedString {
        append(number)
        if (unit.isNotEmpty()) {
            withStyle(
                SpanStyle(fontSize = unitSize, fontWeight = FontWeight.SemiBold),
            ) { append(unit) }
        }
    }
}

private fun exactYen(value: Double): String = NativeFormat.number(value, 0) + "円"

@Composable
private fun NativeSavings(
    spec: SavingsSpec,
    stateKey: String,
    persistent: Boolean,
) {
    val host = LocalNativeUiHost.current
    val cached =
        remember(stateKey) {
            if (persistent) restoreSavingsInputs(NativeUiStateStore.peek(stateKey), spec.monthlyMax) else null
        }
    var monthly by rememberSaveable { mutableDoubleStateOf(cached?.monthly ?: spec.monthly) }
    var years by rememberSaveable { mutableIntStateOf(cached?.years ?: spec.years) }
    var rate by rememberSaveable { mutableDoubleStateOf(cached?.rate ?: spec.rate) }
    var dirty by rememberSaveable { mutableStateOf(cached != null) }

    fun snapshot() = "$monthly,$years,$rate"
    val persistence =
        rememberNativeUiPersistence(stateKey, persistent) { saved ->
            val values = restoreSavingsInputs(saved, spec.monthlyMax)
            if (!dirty && values != null) {
                monthly = values.monthly
                years = values.years
                rate = values.rate
                dirty = true
            }
        }
    LaunchedEffect(dirty) { if (dirty) persistence.retain(snapshot()) }
    LaunchedEffect(spec) {
        if (!dirty) {
            monthly = spec.monthly
            years = spec.years
            rate = spec.rate
        }
        monthly = monthly.coerceIn(0.0, spec.monthlyMax)
    }

    fun edited() {
        dirty = true
        persistence.retain(snapshot())
    }
    val result =
        remember(monthly, years, rate, spec.cap, spec.initial) {
            simulateSavings(monthly.coerceIn(0.0, spec.monthlyMax), years, rate, spec.cap, spec.initial)
        }
    val changed = monthly != spec.monthly || years != spec.years || rate != spec.rate
    val rateRange =
        remember(spec.rate) {
            minOf(-5.0, floor(spec.rate)).toFloat()..maxOf(15.0, ceil(spec.rate)).toFloat()
        }
    val status = LocalHermesStatusColors.current
    val total = result.last.value
    val principal = result.last.principal
    val gain = total - principal

    NativeHeader(spec.title) {
        if (changed) {
            TextButton(
                onClick = {
                    monthly = spec.monthly
                    years = spec.years
                    rate = spec.rate
                    dirty = true
                    persistence.save(snapshot())
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag("${spec.id}-reset").semantics { contentDescription = "初期値に戻す" },
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("初期値")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${years}年後の評価額(試算)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    amountText(NativeFormat.yen(total), 18.sp),
                    style = Tnum.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("${spec.id}-total"),
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SavingsMetric(
                        "元本",
                        NativeFormat.yen(principal),
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.weight(1f),
                    )
                    SavingsMetric(
                        "運用益",
                        (if (gain < 0) "" else "+") + NativeFormat.yen(gain),
                        if (gain < 0) status.error else status.success,
                        Modifier.weight(1f),
                    )
                    SavingsMetric(
                        "元本の倍率",
                        if (principal > 0) NativeFormat.number(total / principal, 2) + "倍" else "—",
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeSectionLabel("条件")
            NativeRange(
                "毎月の積立額",
                exactYen(monthly),
                monthly.toFloat(),
                0f..spec.monthlyMax.toFloat(),
                "${spec.id}-monthly",
                "0円",
                NativeFormat.yen(spec.monthlyMax),
                onChange = {
                    monthly =
                        if (it >= spec.monthlyMax.toFloat()) {
                            spec.monthlyMax
                        } else {
                            ((it / 1_000).roundToInt() * 1_000.0).coerceAtMost(spec.monthlyMax)
                        }
                    edited()
                },
                onFinished = { persistence.save(snapshot()) },
            )
            NativeRange(
                "想定年率",
                "${NativeFormat.number(rate)}%",
                rate.toFloat(),
                rateRange,
                "${spec.id}-rate",
                "${NativeFormat.number(rateRange.start.toDouble())}%",
                "${NativeFormat.number(rateRange.endInclusive.toDouble())}%",
                mark = (-rateRange.start / (rateRange.endInclusive - rateRange.start)).takeIf { it > 0f && it < 1f },
                markLabel = "0%",
                onChange = {
                    rate = (it * 2).roundToInt() / 2.0
                    edited()
                },
                onFinished = { persistence.save(snapshot()) },
            )
            NativeRange(
                "運用期間",
                "${years}年",
                years.toFloat(),
                1f..60f,
                "${spec.id}-years",
                "1年",
                "60年",
                onChange = {
                    years = it.roundToInt()
                    edited()
                },
                onFinished = { persistence.save(snapshot()) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NativeSectionLabel("推移")
            NativeChart(
                result.points.map { "${it.year}年" },
                listOf(
                    ChartSeries("評価額", result.points.map { it.value / 10_000 }),
                    ChartSeries("元本", result.points.map { it.principal / 10_000 }),
                ),
                "line",
                "万円",
                "${spec.id}-growth",
                fillBetween = true,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NativeCaption(
                "毎月末積立・年率を月率へ換算。開始資産 ${NativeFormat.yen(spec.initial)}、" +
                    "新規積立元本の上限 ${NativeFormat.yen(spec.cap)}。手数料・税金・物価変動は含みません。",
            )
            result.capMonth?.let {
                Text(
                    "${it / 12}年${it % 12}か月で積立上限に到達。その後は運用のみ継続します。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (host != null) {
            NativeDiscussButton(spec.action ?: NativeActionLabels.SAVINGS, "${spec.id}-discuss") {
                savingsActionMessage(spec, monthly, years, rate, result)
            }
        }
        NativeStorageWarning(persistence.failed)
    }
}

@Composable
private fun SavingsMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = Tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeRange(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    tag: String,
    minLabel: String,
    maxLabel: String,
    mark: Float? = null,
    markLabel: String? = null,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // Press/drag state is read only while drawing, so starting a drag never recomposes the row.
    val pressed = interaction.collectIsPressedAsState()
    val dragged = interaction.collectIsDraggedAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            BasicText(
                valueLabel,
                style = Tnum.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = { if (pressed.value || dragged.value) primary else onSurface },
                modifier = Modifier.testTag("$tag-value"),
            )
        }
        val state = remember(range) { SliderState(value.coerceIn(range), 0, range) }
        state.value = value.coerceIn(range)
        Slider(
            state = state,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            interactionSource = interaction,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = 48.dp,
                    ).testTag(tag)
                    .semantics { contentDescription = label },
            thumb = {
                Box(
                    Modifier.size(20.dp).drawBehind {
                        if (pressed.value || dragged.value) {
                            drawCircle(
                                primary.copy(alpha = .18f),
                                radius = size.minDimension / 2 + 10.dp.toPx(),
                            )
                        }
                        drawCircle(primary)
                    },
                )
            },
            track = { state ->
                val span = state.trackRange.endInclusive - state.trackRange.start
                val fraction = if (span > 0f) ((state.value - state.trackRange.start) / span).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val y = size.height / 2
                    val stroke = 4.dp.toPx()
                    drawLine(inactive, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                    drawLine(primary, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
                    mark?.let {
                        val x = size.width * it
                        drawLine(outline, Offset(x, y - 5.dp.toPx()), Offset(x, y + 5.dp.toPx()), 1.5.dp.toPx())
                    }
                }
            },
        )
        // Pull the range labels up to the track; the slider keeps its 48dp touch height.
        Box(Modifier.fillMaxWidth().offset(y = (-6).dp).padding(horizontal = 2.dp)) {
            val small = Tnum.copy(fontSize = 11.sp, color = outline)
            Text(minLabel, style = small, modifier = Modifier.align(Alignment.CenterStart))
            if (mark != null && markLabel != null) {
                Text(
                    markLabel,
                    style = small,
                    modifier =
                        Modifier.padding(horizontal = 8.dp).layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0))
                            val width = constraints.maxWidth
                            layout(width, placeable.height) {
                                val x = (width * mark - placeable.width / 2f).roundToInt()
                                placeable.place(x.coerceIn(0, (width - placeable.width).coerceAtLeast(0)), 0)
                            }
                        },
                )
            }
            Text(maxLabel, style = small, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
