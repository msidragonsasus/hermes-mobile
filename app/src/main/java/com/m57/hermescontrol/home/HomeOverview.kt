package com.m57.hermescontrol.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.attention.AttentionStatusLabel
import com.m57.hermescontrol.ui.common.OverviewIcon
import com.m57.hermescontrol.ui.common.OverviewRow
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
    }.getOrDefault(value)

@Composable
internal fun HomeToolbar(
    state: LinkState,
    loading: Boolean,
    refresh: () -> Unit,
    settings: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalHermesStatusColors.current
    val label =
        when (state) {
            LinkState.CONNECTED -> "接続中"
            LinkState.CONNECTING -> "接続待ち"
            LinkState.RECONNECTING -> "再接続中"
            LinkState.AUTH_REQUIRED -> "認証が必要"
            LinkState.OFFLINE -> "オフライン"
        }
    val color =
        when (state) {
            LinkState.CONNECTED -> MaterialTheme.colorScheme.primary
            LinkState.AUTH_REQUIRED -> colors.warning
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = spacing.sm).testTag("home_toolbar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.padding(end = spacing.sm).testTag("home_connection"),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Circle, null, Modifier.size(10.dp), tint = color)
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(
            onClick = refresh,
            enabled = !loading,
            modifier = Modifier.testTag("home_refresh"),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            Text("更新", Modifier.padding(start = spacing.sm))
        }
        FilledTonalButton(
            onClick = settings,
            modifier = Modifier.testTag("home_providers"),
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
            Text("表示するApp", Modifier.padding(start = spacing.sm))
        }
    }
}

@Composable
internal fun HomeEntry(
    item: HomeItem,
    context: String? = null,
    onOpen: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val status = LocalHermesStatusColors.current
    OverviewRow(
        if (item.target.kind in
            setOf("chat", "conversation")
        ) {
            Icons.AutoMirrored.Outlined.Chat
        } else {
            Icons.Outlined.Description
        },
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        compact = true,
    ) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        val provider = if (item.provider == "career") "就活" else "チャット"
        Text(
            listOfNotNull(
                provider,
                context?.takeIf { it.isNotBlank() },
                item.detail.takeIf { item.provider == "career" && it.isNotBlank() },
            ).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (item.provider != "career" && item.detail.isNotBlank()) {
            Text(
                item.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.deadline?.let {
            val color = if (item.overdue || item.urgent) status.warning else MaterialTheme.colorScheme.onSurfaceVariant
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = color)
                Text(
                    (if (item.overdue) "期限超過・未完了：" else "期限：") + homeOverviewTime(it),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = homeTime(it) },
                )
            }
        }
        // The whole row opens the item; no extra "詳細を見る" button per row.
        if (item.category != "action" && item.updated_at.isNotBlank()) {
            Text(
                homeOverviewTime(item.updated_at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = homeTime(item.updated_at) },
            )
        }
    }
}

@Composable
internal fun HomeAttentionEntry(
    item: AttentionItem,
    ready: Boolean,
    onOpen: () -> Unit,
) {
    OverviewRow(Icons.AutoMirrored.Outlined.Chat, Modifier.fillMaxWidth().clickable(onClick = onOpen), compact = true) {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        AttentionStatusLabel(item.status, ready)
    }
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
    val spacing = LocalSpacing.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val first = group.first()
    Column(Modifier.fillMaxWidth().testTag("home_group")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "閉じる" else "一覧を開く", onClick = onToggle)
                .padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            val chat = first.target.kind in setOf("chat", "conversation")
            OverviewIcon(if (chat) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Description)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(first.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (first.provider == "career") "就活" else "チャット") + " · ${group.size}件",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.padding(top = spacing.sm).size(20.dp),
            )
        }
        if (expanded) {
            group.forEachIndexed { index, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { onOpen(item) }
                        .padding(start = 52.dp, top = spacing.xs, bottom = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        labelOf(item, index),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
