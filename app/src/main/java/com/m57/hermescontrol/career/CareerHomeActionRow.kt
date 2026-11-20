package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.homeTime
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow

/** Reuse the agenda date rail; opening a group always selects one exact source. */
@Composable
internal fun CareerHomeActionRow(
    group: CareerActionGroup,
    onOpen: (HomeItem) -> Unit,
) {
    val item = group.item
    val spacing = LocalSpacing.current
    val date = agendaDate(item.deadline)
    val largeText = LocalDensity.current.fontScale > 1.3f
    val deadlineMeta =
        when {
            date.time != null -> "${date.time}まで（日本時間）"
            date.date == null && "期限は元の案内で確認" !in item.detail && "期限未確定" !in item.detail -> "期限未確定"
            else -> null
        }
    val meta =
        listOfNotNull(
            group.identity?.let { listOf(it.program, it.cohort, it.round).joinToString("・") },
            item.detail.takeIf { it.isNotBlank() },
            deadlineMeta,
        ).joinToString(" · ")
    var expanded by remember(group.id) { mutableStateOf(false) }
    val sourceToggle: (@Composable () -> Unit)? =
        if (group.sources.size > 1) {
            {
                TextButton(
                    onClick = { expanded = !expanded },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = PaddingValues(horizontal = spacing.sm, vertical = spacing.xs),
                    modifier = Modifier.testTag("career_action_sources_" + group.id),
                ) {
                    Text(
                        if (expanded) "元メールを閉じる" else "元メール " + group.sources.size + "件",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            null
        }
    CompactListRow(
        modifier = Modifier.testTag("career_action_" + group.id),
        onClick = { onOpen(item) },
        leading =
            if (date.date != null && !largeText) {
                { CareerAgendaDateRail(date, Modifier.testTag("career_action_date_" + group.id), compact = true) }
            } else {
                null
            },
        trailing =
            if (!largeText && sourceToggle != null) {
                { sourceToggle() }
            } else {
                null
            },
        footer = {
            if (expanded) {
                group.sources.forEach { source ->
                    TextButton(
                        onClick = { onOpen(source) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().testTag("career_action_source_" + source.id),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(source.title, style = MaterialTheme.typography.bodyMedium)
                            if (source.updated_at.isNotBlank()) {
                                Text(homeTime(source.updated_at), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    ) {
        if (largeText && date.date != null) CareerAgendaDateLine(date)
        CareerHomeRowText(item.title, meta)
        if (largeText && sourceToggle != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { sourceToggle() }
        }
    }
}

@Composable
internal fun CareerHomeRowText(
    title: String,
    meta: String,
) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    if (meta.isNotBlank()) {
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
