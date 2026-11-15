package com.m57.hermescontrol.worker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.home.homeOverviewTime
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.attention.AttentionStatusLabel
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InListSectionHeaderPadding
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.PrimaryPillButton
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.rememberHasLoaded
import com.m57.hermescontrol.util.CronExpressionFormatter

@Composable
fun WorkScreen(
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: WorkViewModel = viewModel { WorkViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val japanese = LocalConfiguration.current.locales[0].language == "ja"
    val hasLoaded = rememberHasLoaded(state.isLoading)

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    val toastText = state.toastMessageRes?.let { stringResource(it) }
    ToastEffect(toastMessage = toastText, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_work)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.refresh() },
    ) {
        when {
            state.isLoading && state.isAllEmpty && !hasLoaded -> {
                SkeletonListState()
            }

            state.fatalErrorRes != null && state.isAllEmpty -> {
                ErrorState(
                    message = stringResource(state.fatalErrorRes!!),
                    onRetry = { viewModel.refresh() },
                )
            }

            state.isAllEmpty && !state.isLoading -> {
                EmptyState(
                    icon = Icons.Filled.TaskAlt,
                    title = stringResource(R.string.worker_work_empty_title),
                    subtitle = stringResource(R.string.worker_work_empty_subtitle),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            horizontal = spacing.md,
                            vertical = spacing.sm,
                        ),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    state.partialErrorRes?.let { noticeRes ->
                        item(key = "partial_notice") {
                            InlineNotice(
                                text = stringResource(noticeRes),
                                tone = NoticeTone.Warning,
                            )
                        }
                    }

                    if (state.escrows.isNotEmpty()) {
                        item(key = "section_approvals") {
                            SectionHeader(
                                title = stringResource(R.string.worker_section_approvals),
                                contentPadding = InListSectionHeaderPadding,
                            )
                        }
                        items(state.escrows, key = { "escrow:${it.id}" }) { record ->
                            EscrowApprovalObject(
                                record = record,
                                busy = record.id in state.busyEscrowIds,
                                onApprove = { viewModel.decideEscrow(record, EscrowDecision.APPROVE) },
                                onDeny = { viewModel.decideEscrow(record, EscrowDecision.DENY) },
                            )
                        }
                    }

                    if (state.subagents.isNotEmpty()) {
                        item(key = "section_live") {
                            SectionHeader(
                                title = stringResource(R.string.worker_section_live),
                                contentPadding = InListSectionHeaderPadding,
                            )
                        }
                        items(state.subagents, key = { "subagent:${it.subagentId}" }) { subagent ->
                            LiveWorkRow(
                                subagent = subagent,
                                busy = subagent.subagentId in state.busySubagentIds,
                                onInterrupt = { viewModel.interruptSubagent(subagent.subagentId) },
                            )
                        }
                    }

                    if (state.scheduledJobs.isNotEmpty()) {
                        item(key = "section_scheduled") {
                            SectionHeader(
                                title = stringResource(R.string.worker_section_scheduled),
                                contentPadding = InListSectionHeaderPadding,
                            )
                        }
                        items(state.scheduledJobs, key = { "job:${it.id}:${it.name}" }) { job ->
                            ScheduledWorkRow(
                                job = job,
                                japanese = japanese,
                                busy = job.name in state.busyJobNames,
                                onTogglePause = { viewModel.toggleJobPause(job) },
                                onRequestDelete = { viewModel.requestDeleteJob(job) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteJob() },
            title = { Text(stringResource(R.string.worker_schedule_delete_title)) },
            text = { Text(stringResource(R.string.worker_schedule_delete_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteJob() },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissDeleteJob() },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EscrowApprovalObject(
    record: EscrowRecord,
    busy: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val title =
        record.display.title
            .trim()
            .ifBlank { stringResource(R.string.worker_approval_default_title) }
    val status = if (busy) AttentionStatus.SUBMITTING else AttentionStatus.PENDING

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
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
            AttentionStatusLabel(status = status, ready = true)

            record.job_name?.takeIf { it.isNotBlank() }?.let { jobName ->
                Text(
                    text = jobName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            record.display.alternative?.takeIf { it.isNotBlank() }?.let { alt ->
                Text(
                    text = stringResource(R.string.worker_approval_alternative_label, alt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            record.expires_at?.takeIf { it.isNotBlank() }?.let { expiresAt ->
                Text(
                    text = stringResource(R.string.worker_approval_expires_label, homeOverviewTime(expiresAt)),
                    style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun LiveWorkRow(
    subagent: WorkerSubagent,
    busy: Boolean,
    onInterrupt: () -> Unit,
) {
    val statusLabel =
        when (subagent.status) {
            "queued" -> stringResource(R.string.worker_live_status_queued)
            else -> stringResource(R.string.worker_live_status_running)
        }
    CompactListRow(
        trailing = {
            StatusBadge(text = statusLabel, status = StatusBadgeType.INFO)
            IconButton(
                onClick = onInterrupt,
                enabled = !busy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.worker_action_stop),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        Text(
            text = subagent.goal ?: stringResource(R.string.worker_live_default_title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subagent.model?.let { model ->
            Text(
                text = model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScheduledWorkRow(
    job: WorkerScheduledJob,
    japanese: Boolean,
    busy: Boolean,
    onTogglePause: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val scheduleReadable =
        job.scheduleText
            .takeIf { it.isNotBlank() }
            ?.let { CronExpressionFormatter.cronToHumanReadable(it, japanese) }
            .orEmpty()
    val metaParts =
        listOfNotNull(
            scheduleReadable.takeIf { it.isNotBlank() },
            job.nextRunAt?.takeIf { job.enabled && it.isNotBlank() }?.let(::homeOverviewTime),
        )

    CompactListRow(
        trailing = {
            StatusBadge(
                text =
                    stringResource(
                        if (job.enabled) {
                            R.string.worker_schedule_status_active
                        } else {
                            R.string.worker_schedule_status_paused
                        },
                    ),
                status = if (job.enabled) StatusBadgeType.SUCCESS else StatusBadgeType.NEUTRAL,
            )
            IconButton(
                onClick = onTogglePause,
                enabled = !busy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (job.enabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        stringResource(
                            if (job.enabled) {
                                R.string.worker_action_pause
                            } else {
                                R.string.worker_action_resume
                            },
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onRequestDelete,
                enabled = !busy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        Text(
            text = job.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (metaParts.isNotEmpty()) {
            Text(
                text = metaParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
