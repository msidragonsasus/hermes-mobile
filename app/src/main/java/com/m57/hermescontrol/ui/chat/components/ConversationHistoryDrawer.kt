package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.group.openGroupHistory
import com.m57.hermescontrol.ui.bots.group.rememberGroupHistory
import com.m57.hermescontrol.ui.sessions.SessionsViewModel

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
    )
}
