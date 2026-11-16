package com.m57.hermescontrol.worker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InListSectionHeaderPadding
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
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
    // Activity-scoped ViewModel: a delete dialog left open must not reappear on re-entry.
    DisposableEffect(Unit) { onDispose { viewModel.dismissDeleteJob() } }

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
                            WorkerApprovalObject(
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
                        items(upcomingOrder(state.scheduledJobs), key = { "job:${it.id}:${it.name}" }) { job ->
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
private fun LiveWorkRow(
    subagent: WorkerSubagent,
    busy: Boolean,
    onInterrupt: () -> Unit,
) {
    val queued = subagent.status == "queued"
    val statusLabel =
        stringResource(if (queued) R.string.worker_live_status_queued else R.string.worker_live_status_running)
    CompactListRow(
        modifier = Modifier.semantics { stateDescription = statusLabel },
        trailing = {
            // Fixed 24dp status slot (DESIGN.md row anatomy): spinner = running, hourglass = waiting.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (queued) {
                    Icon(
                        imageVector = Icons.Outlined.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onInterrupt,
                enabled = !busy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.StopCircle,
                    contentDescription = stringResource(R.string.worker_action_stop),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A routine row: name, readable schedule, and the next run in the trailing time column. Tap for actions. */
@Composable
internal fun ScheduledWorkRow(
    job: WorkerScheduledJob,
    japanese: Boolean,
    busy: Boolean,
    onTogglePause: (() -> Unit)?,
    onRequestDelete: (() -> Unit)?,
    onClick: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val scheduleReadable =
        job.scheduleText
            .takeIf { it.isNotBlank() }
            ?.let { CronExpressionFormatter.cronToHumanReadable(it, japanese) }
            .orEmpty()
    val pausedLabel = stringResource(R.string.worker_schedule_status_paused)
    val meta =
        listOfNotNull(
            scheduleReadable.takeIf { it.isNotBlank() },
            pausedLabel.takeIf { !job.enabled },
        ).joinToString(" · ")
    val next = if (job.enabled) workerClock(job.nextRunAt) else null
    val hasMenu = onTogglePause != null || onRequestDelete != null

    CompactListRow(
        onClick =
            when {
                busy -> null
                hasMenu -> ({ menuOpen = true })
                else -> onClick
            },
        trailing = {
            Box {
                when {
                    busy -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    next != null -> {
                        WorkerTimeLabel(
                            text = stringResource(R.string.worker_schedule_next_label, next),
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    onTogglePause?.let { toggle ->
                        DropdownMenuItem(
                            text = {
                                val label =
                                    if (job.enabled) R.string.worker_action_pause else R.string.worker_action_resume
                                Text(stringResource(label))
                            },
                            leadingIcon = {
                                Icon(
                                    if (job.enabled) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                toggle()
                            },
                        )
                    }
                    onRequestDelete?.let { delete ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                delete()
                            },
                        )
                    }
                }
            }
        },
    ) {
        Text(
            text = job.name,
            style = MaterialTheme.typography.titleSmall,
            color =
                if (job.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
