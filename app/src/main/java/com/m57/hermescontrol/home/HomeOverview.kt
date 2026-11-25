package com.m57.hermescontrol.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.attention.attentionStatusLabelRes
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.worker.WorkerTimeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun homeOverviewTime(value: String): String =
    runCatching {
        val date = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Tokyo"))
        val pattern = if (date.year == LocalDate.now(ZoneId.of("Asia/Tokyo")).year) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        date.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPAN))
    }.getOrElse { todayDeadline(value)?.label(Instant.now()) ?: "不明" }

@Composable
internal fun HomeFoldRow(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = foldStateDescription(expanded) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HomeFoldIcon(expanded)
    }
}

internal fun foldStateDescription(expanded: Boolean) = if (expanded) "展開中" else "折りたたみ中"

@Composable
internal fun HomeFoldIcon(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun HomeChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HomeSourceIcon(chat: Boolean) {
    Icon(
        if (chat) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Description,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HomeRowTitle(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HomeRowMeta(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val HomeItem.isChat get() = target.kind in setOf("chat", "conversation") && provider != "career"
private val HomeItem.providerLabel get() = if (provider == "career") "就活" else "チャット"

@Composable
internal fun HomeEntry(
    item: HomeItem,
    context: String? = null,
    onOpen: () -> Unit,
) {
    val resultTime = item.updated_at.takeIf { item.category != "action" && it.isNotBlank() }
    CompactListRow(
        onClick = onOpen,
        leading = { HomeSourceIcon(item.isChat) },
        trailing =
            resultTime?.let {
                {
                    WorkerTimeLabel(
                        todayShortTime(it),
                        Modifier.widthIn(max = 132.dp).semantics { contentDescription = homeTime(it) },
                        maxLines = 2,
                    )
                }
            },
    ) {
        HomeRowTitle(item.title)
        HomeRowMeta(
            listOfNotNull(
                item.providerLabel,
                context?.takeIf(String::isNotBlank),
                item.detail.takeIf(String::isNotBlank),
            ).joinToString(" · "),
        )
        item.deadline?.let {
            val color =
                if (item.overdue ||
                    item.urgent
                ) {
                    LocalHermesStatusColors.current.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(
                (if (item.overdue) "期限超過・未完了：" else "期限：") + homeOverviewTime(it),
                color = color,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { contentDescription = homeTime(it) },
            )
        }
    }
}

internal data class HomeDecision(
    val title: String,
    val source: String,
    val status: AttentionStatus,
    val expiresAt: Instant?,
    val approval: Boolean,
    val open: () -> Unit,
)

@Composable
internal fun HomeDecisionRow(
    decision: HomeDecision,
    now: Instant,
    ready: Boolean,
) {
    CompactListRow(onClick = decision.open, trailing = { HomeChevron() }) {
        Text(decision.title, style = MaterialTheme.typography.bodyLarge)
        val status = stringResource(attentionStatusLabelRes(decision.status, ready))
        Text(
            "${decision.source} · $status",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        decision.expiresAt?.let {
            Text(
                homeFocusDeadline(it.toString(), now).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal data class HomeTimelineItem(
    val title: String,
    val time: String,
    val source: String,
    val open: () -> Unit,
)

@Composable
internal fun HomeTimelineRow(
    item: HomeTimelineItem,
    now: Instant,
) {
    CompactListRow(onClick = item.open) {
        HomeTimelineTime(item.time, now)
        Text(item.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            item.source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun HomeTimelineTime(
    deadline: String?,
    now: Instant,
) {
    val due = todayDeadline(deadline) ?: return
    val label =
        when {
            due == TodayDeadline.Unknown -> "期限不明"
            due.isPast(now) -> "期限切れ（${due.label(now)}）"
            due is TodayDeadline.Day && due.label(now) == "今日" -> "今日中"
            else -> due.label(now).removePrefix("今日 ")
        }
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Several actions with the same title: one row with a count, opened into short sub-rows. */
@Composable
internal fun HomeGroupEntry(
    group: List<HomeItem>,
    expanded: Boolean,
    labelOf: (HomeItem, Int) -> String,
    onToggle: () -> Unit,
    onOpen: (HomeItem) -> Unit,
) {
    val first = group.first()
    Column(Modifier.fillMaxWidth().testTag("home_group")) {
        CompactListRow(
            rowModifier = Modifier.semantics { stateDescription = foldStateDescription(expanded) },
            onClick = onToggle,
            onClickLabel = if (expanded) "一覧を閉じる" else "一覧を開く",
            leading = { HomeSourceIcon(first.isChat) },
            trailing = { HomeFoldIcon(expanded) },
        ) {
            HomeRowTitle(first.title)
            HomeRowMeta(first.providerLabel + " · " + group.size + "件")
        }
        if (expanded) {
            group.forEachIndexed { index, item ->
                CompactListRow(
                    modifier = Modifier.padding(start = 28.dp),
                    onClick = { onOpen(item) },
                    trailing = { HomeChevron() },
                ) {
                    HomeRowTitle(labelOf(item, index))
                }
            }
        }
    }
}
