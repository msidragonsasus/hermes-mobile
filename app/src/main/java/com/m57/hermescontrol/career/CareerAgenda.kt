package com.m57.hermescontrol.career

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.OverviewIcon
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AgendaDate(
    val date: LocalDate?,
    val time: String?,
    val original: String?,
)

/** Respect offsets; date-only and missing values must never turn into a fabricated time. */
internal fun agendaDate(value: String?): AgendaDate {
    if (value.isNullOrBlank()) return AgendaDate(null, null, null)
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Tokyo")) }.getOrNull()?.let {
        return AgendaDate(it.toLocalDate(), it.format(timeFormat), value)
    }
    runCatching { LocalDateTime.parse(value) }.getOrNull()?.let {
        return AgendaDate(it.toLocalDate(), it.format(timeFormat), value)
    }
    return AgendaDate(runCatching { LocalDate.parse(value) }.getOrNull(), null, value)
}

internal fun agendaCompany(
    entity: CareerEntity,
    entities: List<CareerEntity>,
): String? {
    val offer = entities.firstOrNull { it.kind == "offers" && it.id == entity.data.text("offer_id") }
    val companyId = entity.data.text("company_id") ?: offer?.data?.text("company_id")
    return entities.firstOrNull { it.kind == "companies" && it.id == companyId }?.title
        ?: entity.data.text("company_name")
}

/** Past entries remain in company records, but do not occupy the home agenda. */
internal fun careerHomeAgenda(
    entities: List<CareerEntity>,
    today: LocalDate,
): List<CareerEntity> =
    entities
        .filter {
            (
                it.kind in setOf("tasks", "deadlines", "events") ||
                    (
                        it.kind == "offers" && it.data.text("deadline") != null &&
                            it.data.text("is_current") != "false"
                    )
            ) &&
                it.data.text("status") !in setOf("done", "submitted", "completed", "cancelled", "closed") &&
                agendaDate(it.data.text("deadline", "scheduledAt")).date?.isBefore(today) != true
        }.sortedWith(
            compareBy<CareerEntity> {
                agendaDate(it.data.text("deadline", "scheduledAt")).date ?: LocalDate.MAX
            }.thenBy { agendaDate(it.data.text("deadline", "scheduledAt")).time.orEmpty() },
        )

@Composable
internal fun CareerAgendaRow(
    entity: CareerEntity,
    company: String?,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val date = agendaDate(entity.data.text("deadline", "scheduledAt"))
    val largeText = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(vertical = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (!largeText) {
                CareerAgendaDateRail(date)
                OverviewIcon(if (entity.kind == "events") Icons.Outlined.Event else Icons.Outlined.Description)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (largeText) {
                    CareerAgendaDateLine(date)
                }
                Text(entity.title, style = MaterialTheme.typography.titleMedium)
                company?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (entity.kind == "offers") {
                    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text("募集・締切を開く") }
                }
                val timing =
                    date.time?.let { if (entity.kind == "events") it else "$it まで" }
                        ?: if (date.date == null) date.original ?: "期限未設定" else "時刻未設定"
                Text(timing, style = MaterialTheme.typography.bodyMedium)
                entity.data.text("classification", "status", "decision")?.let {
                    Text(
                        careerStatus(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entity.kind != "offers") {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun CareerAgendaDateRail(
    date: AgendaDate,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        modifier.width(54.dp).clearAndSetSemantics {
            contentDescription = date.original ?: "期限未設定"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        date.date?.let {
            if (it.year != LocalDate.now(ZoneId.of("Asia/Tokyo")).year) {
                Text("${it.year}年", style = MaterialTheme.typography.labelSmall)
            }
            if (compact) {
                Text("${it.monthValue}/${it.dayOfMonth}", style = MaterialTheme.typography.titleSmall)
            } else {
                Text("${it.monthValue}月", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%02d".format(it.dayOfMonth), style = MaterialTheme.typography.displaySmall)
            }
            Text(
                it.format(DateTimeFormatter.ofPattern("E", Locale.JAPAN)),
                style = if (compact) MaterialTheme.typography.labelSmall else LocalTextStyle.current,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text("日付\n未確定", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CareerAgendaDateLine(date: AgendaDate) {
    Text(
        date.date?.format(DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN))
            ?: date.original ?: "期限未設定",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}
