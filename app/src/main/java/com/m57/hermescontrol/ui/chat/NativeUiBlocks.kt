package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Headline numbers placed before the explanation: 2-column grid, full width for a single item. */
@Composable
internal fun NativeStat(spec: StatSpec) {
    NativeHeader(spec.title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        spec.items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { StatTile(it, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1 && spec.items.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(
    item: StatItem,
    modifier: Modifier,
) {
    val status = LocalHermesStatusColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodySmall, color = muted)
            Text(
                amountText(NativeFormat.value(item.value, item.unit), 14.sp),
                style = Tnum.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            )
            val delta = item.delta
            if (delta != null) {
                val (text, color, spoken) =
                    when {
                        delta == 0.0 -> {
                            Triple("± 0", MaterialTheme.colorScheme.outline, "変化なし")
                        }

                        else -> {
                            val up = delta > 0
                            val color =
                                when (item.good) {
                                    null -> muted
                                    "up" -> if (up) status.success else status.error
                                    else -> if (up) status.error else status.success
                                }
                            Triple(
                                (if (up) "▲" else "▼") + NativeFormat.value(abs(delta), item.unit),
                                color,
                                (if (up) "増加 " else "減少 ") + NativeFormat.value(abs(delta), item.unit),
                            )
                        }
                    }
                Text(
                    listOf(text, item.sub).filter { it.isNotBlank() }.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall.merge(Tnum),
                    color = color,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                listOf(spoken, item.sub).filter { it.isNotBlank() }.joinToString(" ")
                        },
                )
            } else if (item.sub.isNotBlank()) {
                Text(item.sub, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** 2..4 options compared by pros, cons and scores; one can be selected and added to the draft. */
@Composable
internal fun NativeChoices(spec: ChoicesSpec) {
    val host = LocalNativeUiHost.current
    val status = LocalHermesStatusColors.current
    val scheme = MaterialTheme.colorScheme
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = spec.options.firstOrNull { it.key == selected }
    NativeHeader(spec.title)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        spec.options.forEach { option ->
            val isSelected = option == current
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = scheme.surfaceContainerHigh,
                border = if (isSelected) BorderStroke(1.5.dp, scheme.primary) else null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("${spec.id}-option-${option.key}")
                        .selectable(isSelected, role = Role.RadioButton) { selected = option.key },
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (option.recommended) {
                            Text(
                                "おすすめ",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimaryContainer,
                                modifier =
                                    Modifier
                                        .background(scheme.primaryContainer, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    option.pros.forEach {
                        Text(
                            "+ $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = status.success,
                        )
                    }
                    option.cons.forEach {
                        Text(
                            "${NativeFormat.MINUS} $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = status.error,
                        )
                    }
                    if (option.scores.isNotEmpty()) {
                        Text(
                            option.scores.joinToString("   ") { (name, score) -> "$name $score/5" },
                            style = MaterialTheme.typography.bodySmall.merge(Tnum),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (host != null && current != null) {
        NativeDiscussButton(spec.action ?: NativeActionLabels.CHOICES, "${spec.id}-discuss") {
            choicesActionMessage(spec, current)
        }
    }
}

/** Read-only progress: done (filled check), now (outlined number), todo (faint number). */
@Composable
internal fun NativeSteps(spec: StepsSpec) {
    val scheme = MaterialTheme.colorScheme
    NativeHeader(spec.title)
    Column {
        spec.items.forEachIndexed { i, item ->
            val stateLabel =
                when (item.state) {
                    "done" -> "完了"
                    "now" -> "進行中"
                    else -> "未着手"
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { stateDescription = stateLabel }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val marker = Modifier.size(24.dp)
                when (item.state) {
                    "done" -> {
                        Box(marker.background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = scheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    "now" -> {
                        Box(marker.border(2.dp, scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    else -> {
                        Box(
                            marker.border(1.dp, scheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.state == "now") FontWeight.SemiBold else null,
                    )
                    val detail = listOf(item.detail, if (item.state == "now") "いまここ" else "").filter { it.isNotBlank() }
                    if (detail.isNotEmpty()) NativeCaption(detail.joinToString(" · "))
                }
            }
            if (i <
                spec.items.lastIndex
            ) {
                HorizontalDivider(Modifier.padding(start = 36.dp), color = scheme.outlineVariant)
            }
        }
    }
}

/** Tap-to-check list; checks are kept per key across revisions and saved like other inputs. */
@Composable
internal fun NativeChecklist(
    spec: ChecklistSpec,
    stateKey: String,
    persistent: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val cached = remember(stateKey) { if (persistent) decodeChecklist(NativeUiStateStore.peek(stateKey)) else null }
    var encoded by rememberSaveable { mutableStateOf(cached?.let(::encodeChecklist) ?: "") }
    var dirty by rememberSaveable { mutableStateOf(cached != null) }
    val persistence =
        rememberNativeUiPersistence(stateKey, persistent) { saved ->
            val restored = decodeChecklist(saved)
            if (!dirty && restored != null) {
                encoded = encodeChecklist(restored)
                dirty = true
            }
        }
    val tokens = decodeChecklist(encoded) ?: emptySet()
    val done = spec.items.count { checklistToken(it.key) in tokens }
    NativeHeader(spec.title) {
        Text(
            "$done / ${spec.items.size}",
            style = MaterialTheme.typography.labelLarge.merge(Tnum),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.testTag("${spec.id}-progress"),
        )
    }
    Column {
        spec.items.forEachIndexed { i, item ->
            val token = checklistToken(item.key)
            val checked = token in tokens
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("${spec.id}-item-${item.key}")
                    .toggleable(checked, role = Role.Checkbox) {
                        encoded = encodeChecklist(if (checked) tokens - token else tokens + token)
                        dirty = true
                        persistence.save(encoded)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (checked) scheme.onSurfaceVariant else scheme.onSurface,
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                    )
                    if (item.detail.isNotBlank()) NativeCaption(item.detail)
                }
            }
            if (i <
                spec.items.lastIndex
            ) {
                HorizontalDivider(Modifier.padding(start = 36.dp), color = scheme.outlineVariant)
            }
        }
    }
    NativeStorageWarning(persistence.failed)
}

/** Dated events in order; today's line and "あと N 日" come from the device clock, never the model. */
@Composable
internal fun NativeTimeline(spec: TimelineSpec) {
    val clock = LocalNativeUiClock.current
    val today = remember(clock) { LocalDate.now(clock) }
    val firstUpcoming =
        spec.events.indexOfFirst { !it.date.isBefore(today) }.let {
            if (it <
                0
            ) {
                spec.events.size
            } else {
                it
            }
        }
    NativeHeader(spec.title)
    Column {
        spec.events.forEachIndexed { i, event ->
            if (i == firstUpcoming) TodayLine(today, today)
            TimelineRow(event, daysFromToday(event.date, clock), today, isLast = i == spec.events.lastIndex)
        }
        if (firstUpcoming == spec.events.size) TodayLine(today, today)
    }
}

private fun monthDay(
    date: LocalDate,
    today: LocalDate,
): String = (if (date.year != today.year) "${date.year}/" else "") + "${date.monthValue}/${date.dayOfMonth}"

@Composable
private fun TodayLine(
    date: LocalDate,
    today: LocalDate,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("timeline-today"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("今日 ${monthDay(date, today)}", style = MaterialTheme.typography.labelSmall.merge(Tnum), color = primary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(primary))
    }
}

@Composable
private fun TimelineRow(
    event: TimelineEvent,
    days: Long,
    today: LocalDate,
    isLast: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val status = LocalHermesStatusColors.current
    val past = days < 0
    val ink = if (past) scheme.onSurfaceVariant else scheme.onSurface
    val rail = scheme.outlineVariant
    val dot =
        when (event.kind) {
            "deadline" -> status.error
            "done" -> status.success
            else -> scheme.outline
        }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.widthIn(min = 52.dp).padding(top = 2.dp), horizontalAlignment = Alignment.End) {
            Text(monthDay(event.date, today), style = MaterialTheme.typography.titleSmall.merge(Tnum), color = ink)
            Text(
                event.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPAN),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.width(24.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val y = 12.dp.toPx()
                if (!isLast) drawLine(rail, Offset(x, y), Offset(x, size.height), 2.dp.toPx())
                drawLine(rail, Offset(x, 0f), Offset(x, y), 2.dp.toPx())
                if (event.kind == "event") {
                    drawCircle(scheme.surfaceContainerLow, 6.dp.toPx(), Offset(x, y))
                    drawCircle(dot, 5.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(
                        dot.copy(
                            alpha =
                                if (past &&
                                    event.kind == "deadline"
                                ) {
                                    .5f
                                } else {
                                    1f
                                },
                        ),
                        6.dp.toPx(),
                        Offset(x, y),
                    )
                }
            },
        )
        Column(Modifier.weight(1f).padding(start = 4.dp, bottom = 16.dp)) {
            Text(event.label, style = MaterialTheme.typography.bodyLarge, color = ink)
            val relative =
                when {
                    event.kind == "done" -> "完了"
                    else -> relativeDayLabel(days)
                }
            val parts = listOf(event.time?.toString().orEmpty(), event.detail).filter { it.isNotBlank() }
            if (parts.isNotEmpty() || relative.isNotEmpty()) {
                Row {
                    if (parts.isNotEmpty()) {
                        NativeCaption(parts.joinToString(" · ") + if (relative.isNotEmpty()) " · " else "")
                    }
                    if (relative.isNotEmpty()) {
                        Text(
                            (if (event.kind == "done") "✓ " else "") + relative,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (event.kind == "deadline" && !past) FontWeight.SemiBold else null,
                            color =
                                when {
                                    event.kind == "deadline" && !past -> status.error
                                    event.kind == "done" -> status.success
                                    else -> scheme.onSurfaceVariant
                                },
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}
