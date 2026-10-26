package com.m57.hermescontrol.career

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.theme.LocalSpacing

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
    var expanded by remember(group.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("career_action_" + group.id)) {
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(vertical = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (!largeText) {
                CareerAgendaDateRail(date, Modifier.testTag("career_action_date_" + group.id))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (largeText) CareerAgendaDateLine(date)
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                group.identity?.let {
                    Text(
                        listOf(it.program, it.cohort, it.round).joinToString("・"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.detail.isNotBlank()) Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        date.date != null -> "期限"
                        else -> "期限は元の案内で確認"
                    } + (date.time?.let { "：$it まで（日本時間）" } ?: if (date.date != null) "・時刻は元の案内で確認" else ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (group.sources.size > 1) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.testTag("career_action_sources_" + group.id),
                    ) {
                        Text(if (expanded) "元メールを閉じる" else "元メール " + group.sources.size + "件")
                    }
                } else {
                    TextButton(onClick = { onOpen(item) }, shapes = ButtonDefaults.shapes()) { Text("詳細を見る") }
                }
            }
        }
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
                            Text(
                                com.m57.hermescontrol.home
                                    .homeTime(source.updated_at),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
