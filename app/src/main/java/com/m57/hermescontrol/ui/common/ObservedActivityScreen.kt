package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.HistoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ObservedRun
import com.m57.hermescontrol.domain.hermes.RunSnapshot
import com.m57.hermescontrol.theme.LocalSpacing
import java.text.DateFormat
import java.util.Date

@Composable
fun ObservedActivityScreen(today: Boolean) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val snapshot by app.runRepository.snapshot.collectAsState()
    ObservedActivityContent(snapshot, today)
}

@Composable
internal fun ObservedActivityContent(
    snapshot: RunSnapshot,
    today: Boolean,
) {
    val spacing = LocalSpacing.current
    val needsYou =
        snapshot.runs.filter {
            it.lastConfirmedState == ExecutionState.NEEDS_INPUT ||
                it.lastConfirmedState == ExecutionState.WAITING_APPROVAL
        }
    val otherRuns = snapshot.runs.filterNot { it in needsYou }
    HermesScaffold(
        title = { Text(stringResource(if (today) R.string.shell_today else R.string.shell_activity)) },
        drawerGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Text(
                    stringResource(connectionLabel(snapshot.connection.state)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(stringResource(R.string.activity_observation_scope), style = MaterialTheme.typography.bodySmall)
            }
            if (today) {
                item {
                    Button(
                        onClick = { NavigationController.navigateTo(ChatScreen) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.shell_open_chat))
                    }
                }
            }
            if (snapshot.loading) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.activity_loading))
                }
            }
            if (snapshot.readFailure != null) {
                item { Text(stringResource(R.string.activity_read_unavailable)) }
            }
            item { Text(stringResource(R.string.activity_needs_you), style = MaterialTheme.typography.titleMedium) }
            if (needsYou.isEmpty()) item { Text(stringResource(R.string.activity_no_observed_requests)) }
            items(needsYou) { ObservedRunCard(it, snapshot) }
            item {
                Text(
                    stringResource(R.string.activity_observed_sessions),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (otherRuns.isEmpty()) item { Text(stringResource(R.string.activity_no_observations)) }
            items(if (today) otherRuns.take(3) else otherRuns) { ObservedRunCard(it, snapshot) }
            if (!today) {
                item { Text(stringResource(R.string.activity_timeline), style = MaterialTheme.typography.titleMedium) }
                items(snapshot.events) { event ->
                    Column {
                        Text(stringResource(eventLabel(event.observation.kind)))
                        Text(
                            stringResource(
                                R.string.activity_observed_at,
                                DateFormat
                                    .getDateTimeInstance(
                                        DateFormat.SHORT,
                                        DateFormat.SHORT,
                                    ).format(Date(event.observedAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(event.observation.session?.runtimeId ?: stringResource(R.string.activity_event_gap))
                    }
                }
            }
            item {
                TextButton(onClick = { NavigationController.navigateTo(HistoryScreen) }) {
                    Text(stringResource(R.string.screen_history))
                }
            }
        }
    }
}

@Composable
private fun ObservedRunCard(
    run: ObservedRun,
    snapshot: RunSnapshot,
) {
    val spacing = LocalSpacing.current
    val title = snapshot.sessions.firstOrNull { it.savedId == run.session.savedId }?.title
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(title ?: stringResource(R.string.activity_session), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(executionLabel(run.lastConfirmedState)))
            Text(stringResource(eventLabel(run.lastKind)), style = MaterialTheme.typography.bodySmall)
            if (run.uncertain) Text(stringResource(R.string.activity_last_confirmed))
            Text(run.session.savedId ?: run.session.runtimeId, style = MaterialTheme.typography.bodySmall)
            if (run.session.savedId == null) Text(stringResource(R.string.activity_session_unavailable))
            TextButton(enabled = run.session.savedId != null, onClick = {
                run.session.savedId?.let(NavigationController::openChatSession)
            }) {
                Text(stringResource(R.string.activity_open_session))
            }
        }
    }
}

private fun connectionLabel(state: LinkState): Int =
    when (state) {
        LinkState.OFFLINE -> R.string.activity_offline
        LinkState.CONNECTING -> R.string.activity_connecting
        LinkState.CONNECTED -> R.string.activity_connected
        LinkState.RECONNECTING -> R.string.activity_reconnecting
        LinkState.AUTH_REQUIRED -> R.string.activity_auth_required
    }

private fun executionLabel(state: ExecutionState): Int =
    when (state) {
        ExecutionState.UNKNOWN -> R.string.activity_unknown
        ExecutionState.RUNNING -> R.string.activity_running
        ExecutionState.NEEDS_INPUT -> R.string.activity_needs_input
        ExecutionState.WAITING_APPROVAL -> R.string.activity_waiting_approval
        ExecutionState.COMPLETED -> R.string.activity_completed
        ExecutionState.FAILED -> R.string.activity_failed
        ExecutionState.CANCELED -> R.string.activity_canceled
    }

private fun eventLabel(kind: ObservationKind): Int =
    when (kind) {
        ObservationKind.STARTED -> R.string.activity_event_started
        ObservationKind.COMPLETED -> R.string.activity_completed
        ObservationKind.TOOL_STARTED -> R.string.activity_event_tool_started
        ObservationKind.TOOL_COMPLETED -> R.string.activity_event_tool_completed
        ObservationKind.APPROVAL -> R.string.activity_waiting_approval
        ObservationKind.QUESTION -> R.string.activity_needs_input
        ObservationKind.QUESTION_EXPIRED -> R.string.activity_event_expired
        ObservationKind.GAP -> R.string.activity_event_gap
    }
