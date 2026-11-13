package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.transport.readBackgroundWork
import com.m57.hermescontrol.data.transport.readLiveSessions
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.LiveRosterPoller
import com.m57.hermescontrol.domain.hermes.RunSnapshot
import com.m57.hermescontrol.domain.hermes.projectHistoryActivity
import com.m57.hermescontrol.domain.hermes.sessionActivityNowMs
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.group.openGroupHistory
import com.m57.hermescontrol.ui.bots.group.rememberGroupHistory
import com.m57.hermescontrol.ui.sessions.SessionsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Reuses the existing authenticated, paginated history API without sharing search/selection UI state. */
@Composable
fun ConversationHistoryDrawer(
    visible: Boolean,
    currentSessionId: String?,
    currentModel: String?,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onModel: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val history: SessionsViewModel = viewModel(key = "agent-history-${AuthManager.uiPreferenceScope()}")
    val state by history.uiState.collectAsStateWithLifecycle()
    val bots: BotsViewModel =
        viewModel(key = "agent-history-groups-${AuthManager.uiPreferenceScope()}") { BotsViewModel() }
    val botsState by bots.uiState.collectAsStateWithLifecycle()
    var groupHistoryRevision by remember { mutableIntStateOf(0) }
    val groups = rememberGroupHistory(botsState.allGroups, groupHistoryRevision)
    val untitled = stringResource(R.string.group_new_conversation)
    val merged =
        mergeConversationHistory(
            state,
            groups.result.entries,
            groupLoading = groups.isLoading || botsState.isLoading || botsState.isRefreshing,
            groupUnavailable = botsState.errorMessage != null || groups.result.failedGroups.isNotEmpty(),
            untitled = untitled,
        )
    // Per-row status: one shared roster read and the shared request repository, never per-row calls.
    val app = LocalContext.current.applicationContext as? HermesControlApp
    val runSnapshot = remember(app) { app?.runRepository?.snapshot ?: MutableStateFlow(RunSnapshot()) }
    val connection by remember(runSnapshot) { runSnapshot.map { it.connection } }
        .collectAsStateWithLifecycle(RunSnapshot().connection)
    val attentionItems =
        remember(app) { app?.attentionRepository?.items ?: MutableStateFlow(emptyList<AttentionItem>()) }
    val attention by attentionItems.collectAsStateWithLifecycle()
    // Background checks reuse the same poller: the current chat and recently busy owners only.
    val poller =
        remember {
            LiveRosterPoller(
                ::readLiveSessions,
                readBackground = ::readBackgroundWork,
                currentRuntime = ActiveSessionHolder::activeRuntimeId,
            )
        }
    val roster by poller.roster.collectAsStateWithLifecycle()
    var clock by remember { mutableLongStateOf(sessionActivityNowMs()) }
    val status =
        remember(connection, roster, attention, clock) {
            projectHistoryActivity(connection, roster, attention, sessionActivityNowMs())
        }
    LaunchedEffect(status.validUntil) {
        // Re-project when evidence expires even if no new roster value arrives (hung or failing reads).
        val until = status.validUntil ?: return@LaunchedEffect
        delay((until - sessionActivityNowMs()).coerceAtLeast(0L))
        clock = sessionActivityNowMs()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(visible, runSnapshot, lifecycleOwner) {
        // Only while the drawer is open and the app is started; leaving cancels the in-flight read.
        if (visible) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                poller.poll(runSnapshot.map { it.connection })
            }
        }
    }
    LaunchedEffect(visible, history) {
        if (visible) {
            groupHistoryRevision++
            history.loadSessions()
            bots.loadBots(isRefresh = true)
        } else {
            history.setSearchQuery("")
        }
    }
    DisposableEffect(history) {
        onDispose { history.setSearchQuery("") }
    }
    ConversationMenuSheet(
        currentModel = currentModel,
        visible = visible,
        onHistory = onHistory,
        onNewConversation = onNewConversation,
        onModel = onModel,
        onDismiss = onDismiss,
        historyState = merged,
        currentSessionId = currentSessionId,
        onSelectSession = { id ->
            val group = groups.result.entries.find { it.key == id }
            if (group != null) {
                onDismiss()
                openGroupHistory(group)
            } else {
                onSelectSession(id)
            }
        },
        onSearchQuery = history::setSearchQuery,
        onRefreshHistory = {
            groupHistoryRevision++
            if (state.isSearchMode) history.setSearchQuery(state.searchQuery) else history.loadSessions()
            bots.loadBots(isRefresh = true)
        },
        onLoadMore = history::loadMore,
        historyActivity = status.activity,
        historyRechecking = status.rechecking,
        historyStatusUnverified = status.unverified,
        groupKeys = groups.result.entries.mapTo(mutableSetOf()) { it.key },
    )
}
