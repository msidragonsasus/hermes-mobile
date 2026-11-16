package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.BotConversations
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ObservedRun
import com.m57.hermescontrol.domain.hermes.RunSnapshot
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.attention.AttentionSummaryCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ObservedActivityScreen(today: Boolean) {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val snapshot by app.runRepository.snapshot.collectAsState()
    val attention by app.attentionRepository.items.collectAsState()
    ObservedActivityContent(snapshot, today, attention)
}

@Composable
internal fun ObservedActivityContent(
    snapshot: RunSnapshot,
    today: Boolean,
    attention: List<AttentionItem> = emptyList(),
) {
    val spacing = LocalSpacing.current
    val resolvedRuns =
        snapshot.runs.filter { run ->
            val ref = run.request
            val matching =
                attention.filter {
                    ref != null && it.ref.scope == ref.scope && it.ref.session.runtimeId == ref.session.runtimeId &&
                        it.ref.requestId == ref.requestId
                }
            matching.isNotEmpty() &&
                matching.all { it.status in setOf(AttentionStatus.CONFIRMED, AttentionStatus.EXPIRED) }
        }
    val needsYou =
        snapshot.runs.filter {
            it !in resolvedRuns && (
                it.lastConfirmedState == ExecutionState.NEEDS_INPUT ||
                    it.lastConfirmedState == ExecutionState.WAITING_APPROVAL
            )
        }
    val otherRuns =
        snapshot.runs.filterNot { it in needsYou }.map {
            if (it in resolvedRuns) it.copy(uncertain = true) else it
        }
    val requests =
        attention.filter {
            it.ref.scope == snapshot.connection.scope && it.status != AttentionStatus.CONFIRMED &&
                it.status != AttentionStatus.EXPIRED
        }
    HermesScaffold(
        title = { Text(if (today) "ホーム" else "実行イベント") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                Column(modifier = Modifier.padding(top = spacing.sm)) {
                    Text(
                        stringResource(connectionLabel(snapshot.connection.state)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.activity_observation_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (today) {
                item {
                    Button(
                        onClick = { NavigationController.navigateTo(ChatScreen) },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
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
            item {
                SectionHeader(
                    stringResource(R.string.activity_needs_you),
                    contentPadding = InListSectionHeaderPadding,
                )
            }
            if (needsYou.isEmpty() &&
                requests.isEmpty()
            ) {
                item { EmptyNote(stringResource(R.string.activity_no_observed_requests)) }
            }
            items(requests) { AttentionSummaryCard(it, ready = snapshot.connection.state == LinkState.CONNECTED) }
            items(needsYou.filterNot { run -> requests.any { it.ref.session.runtimeId == run.session.runtimeId } }) {
                ObservedRunCard(it, snapshot)
            }
            item {
                SectionHeader(
                    stringResource(R.string.activity_observed_sessions),
                    contentPadding = InListSectionHeaderPadding,
                )
            }
            if (otherRuns.isEmpty()) item { EmptyNote(stringResource(R.string.activity_no_observations)) }
            items(if (today) otherRuns.take(3) else otherRuns) { ObservedRunCard(it, snapshot) }
            if (!today) {
                item {
                    SectionHeader(
                        stringResource(R.string.activity_timeline),
                        contentPadding = InListSectionHeaderPadding,
                    )
                }
                // A run of consecutive gap markers says the same thing once.
                val timeline =
                    snapshot.events.filterIndexed { index, event ->
                        !(
                            event.observation.kind == ObservationKind.GAP && index > 0 &&
                                snapshot.events[index - 1].observation.kind == ObservationKind.GAP
                        )
                    }
                items(timeline) { event ->
                    Column {
                        Text(
                            stringResource(eventLabel(event.observation.kind)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Gap events have no session; their label already says what happened.
                        event.observation.session?.runtimeId?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
    // Which bot (Hermes profile) did this work; its session only opens inside that bot.
    val owner = run.scope.serverProfileId
    val ownerLabel = BotConversations.label(owner)
    val otherBot = BotConversations.isOtherBot(owner)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(title ?: stringResource(R.string.activity_session), style = MaterialTheme.typography.titleMedium)
            ownerLabel?.let {
                Text(
                    stringResource(R.string.activity_run_by, it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(executionLabel(run.lastConfirmedState)))
            Text(stringResource(eventLabel(run.lastKind)), style = MaterialTheme.typography.bodySmall)
            if (run.uncertain) Text(stringResource(R.string.activity_last_confirmed))
            Text(run.session.savedId ?: run.session.runtimeId, style = MaterialTheme.typography.bodySmall)
            if (run.session.savedId == null) Text(stringResource(R.string.activity_session_unavailable))
            val failedMessage = ownerLabel?.let { stringResource(R.string.activity_open_failed, it) }
            TextButton(enabled = run.session.savedId != null, onClick = {
                val sessionId = run.session.savedId ?: return@TextButton
                scope.launch {
                    if (!BotConversations.open(owner, sessionId) && failedMessage != null) {
                        android.widget.Toast
                            .makeText(context, failedMessage, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }) {
                Text(
                    if (otherBot && ownerLabel != null) {
                        stringResource(R.string.activity_open_in_bot, ownerLabel)
                    } else {
                        stringResource(R.string.activity_open_session)
                    },
                )
            }
        }
    }
}

/** Section heading styled like the App management directory. */
@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
