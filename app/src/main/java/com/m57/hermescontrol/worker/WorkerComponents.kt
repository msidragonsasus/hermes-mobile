package com.m57.hermescontrol.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.home.homeOverviewTime
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.attention.AttentionStatusLabel
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val WorkerZone: ZoneId = ZoneId.of("Asia/Tokyo")
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The worker's time column: today → `04:30`, tomorrow → `明日 04:30`, later → `11月18日 04:30`.
 * Returns null when the server value cannot be read, so raw ISO never reaches the UI.
 */
internal fun workerClockLabel(
    value: String?,
    now: ZonedDateTime,
    tomorrow: (String) -> String,
): String? {
    val instant =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(WorkerZone) }.getOrNull()
        } ?: return null
    val today = now.withZoneSameInstant(WorkerZone).toLocalDate()
    val clock = instant.format(ClockFormat)
    return when (instant.toLocalDate()) {
        today -> clock
        today.plusDays(1) -> tomorrow(clock)
        else -> homeOverviewTime(value)
    }
}

/** Running routines first by their next run, paused ones after, so the list reads as the worker's day. */
internal fun upcomingOrder(jobs: List<WorkerScheduledJob>): List<WorkerScheduledJob> {
    fun nextOf(job: WorkerScheduledJob) =
        job.nextRunAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    return jobs.sortedWith(
        compareBy<WorkerScheduledJob> { !it.enabled }
            .thenBy(nullsLast()) { nextOf(it) }
            .thenBy { it.name },
    )
}

@Composable
internal fun workerClock(
    value: String?,
    now: ZonedDateTime = ZonedDateTime.now(WorkerZone),
): String? {
    val tomorrowPattern = stringResource(R.string.worker_time_tomorrow, "%s")
    return workerClockLabel(value, now) { tomorrowPattern.replace("%s", it) }
}

/** Trailing time column shared by Home and Work rows; muted, tabular, right-aligned. */
@Composable
internal fun WorkerTimeLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/**
 * One escrowed operation awaiting the user's decision. It is the one allowed surface in worker
 * lists (DESIGN.md "When a surface is allowed"): what will run, its effect, where it came from,
 * and when it expires, with deny as text and approve as the single primary pill.
 */
@Composable
internal fun WorkerApprovalObject(
    record: EscrowRecord,
    busy: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val title =
        record.display.title
            .trim()
            .ifBlank { stringResource(R.string.worker_approval_default_title) }
    val meta =
        listOfNotNull(
            record.job_name
                ?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.worker_approval_source_label, it) },
            record.expires_at
                ?.takeIf { it.isNotBlank() }
                ?.let { workerClock(it) }
                ?.let { stringResource(R.string.worker_approval_expires_label, it) },
        )

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = spacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            record.display.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            record.display.command?.takeIf { it.isNotBlank() }?.let { command ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = command,
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            record.display.impact?.takeIf { it.isNotBlank() }?.let { impact ->
                Text(
                    text = stringResource(R.string.worker_approval_impact_label, impact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            record.display.alternative?.takeIf { it.isNotBlank() }?.let { alt ->
                Text(
                    text = stringResource(R.string.worker_approval_alternative_label, alt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.display.deny_only) {
                Text(
                    text = stringResource(R.string.worker_approval_deny_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    AttentionStatusLabel(status = AttentionStatus.SUBMITTING, ready = true)
                }
                TextButton(
                    onClick = onDeny,
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.worker_action_deny))
                }
                if (!record.display.deny_only) {
                    PrimaryPillButton(
                        onClick = onApprove,
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.worker_action_approve))
                    }
                }
            }
        }
    }
}
