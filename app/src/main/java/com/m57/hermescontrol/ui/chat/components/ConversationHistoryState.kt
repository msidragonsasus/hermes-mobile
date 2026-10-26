package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.ui.bots.group.GroupHistoryEntry
import com.m57.hermescontrol.ui.sessions.SessionsUiState

/** Keep independent history sources readable when only one source fails. */
internal fun mergeConversationHistory(
    sessions: SessionsUiState,
    groups: List<GroupHistoryEntry>,
    groupLoading: Boolean,
    groupUnavailable: Boolean,
    untitled: String,
): SessionsUiState =
    sessions.copy(
        isLoading = sessions.isLoading || groupLoading,
        groupHistoryUnavailable = groupUnavailable,
        sessions = sessions.sessions + groups.map { SessionInfo(id = it.key, title = it.label(untitled)) },
        searchResults =
            sessions.searchResults +
                groups
                    .filter { it.label(untitled).contains(sessions.searchQuery.trim(), ignoreCase = true) }
                    .map { SessionSearchResult(session_id = it.key, snippet = it.label(untitled)) },
    )
