package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.domain.hermes.SessionActivity
import com.m57.hermescontrol.ui.bots.group.GroupHistoryEntry
import com.m57.hermescontrol.ui.sessions.SessionsUiState

/**
 * One drawer row; [activity] is null when there is no status evidence. [rechecking] marks
 * still-fresh evidence whose latest refresh failed: shown, but not as newly confirmed.
 */
internal data class HistoryRowModel(
    val id: String,
    val title: String,
    val activity: SessionActivity?,
    val rechecking: Boolean = false,
)

/**
 * List and search rows share one title and status mapping. Group conversations
 * ([groupKeys]) have no run/request evidence in this protocol and stay quiet.
 */
internal fun conversationHistoryRows(
    state: SessionsUiState,
    activity: Map<String, SessionActivity>,
    untitled: String,
    groupKeys: Set<String> = emptySet(),
    rechecking: Set<String> = emptySet(),
): List<HistoryRowModel> {
    fun row(
        id: String,
        title: String,
    ): HistoryRowModel {
        if (id in groupKeys) return HistoryRowModel(id, title, null)
        val session = state.sessions.firstOrNull { it.id == id }
        val ids = listOf(id) + listOfNotNull(session?.lineage_root_id) + session?.lineage_ids.orEmpty()
        val shown = ids.mapNotNull { activity[it] }.minOrNull() ?: return HistoryRowModel(id, title, null)
        // Rechecking only when the shown status itself comes from evidence awaiting a refresh.
        return HistoryRowModel(id, title, shown, ids.any { activity[it] == shown && it in rechecking })
    }
    return if (state.isSearchMode) {
        state.searchResults.distinctBy { it.session_id }.map { result ->
            val title = state.sessions.firstOrNull { it.id == result.session_id }?.title
            row(result.session_id, conversationHistoryTitle(title, result.snippet, untitled))
        }
    } else {
        state.displaySessions.map { session ->
            row(session.id, conversationHistoryTitle(session.title, session.preview, untitled))
        }
    }
}

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
