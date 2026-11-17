package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.domain.hermes.SessionActivity
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
 * List and search rows share one title and status mapping.
 */
internal fun conversationHistoryRows(
    state: SessionsUiState,
    activity: Map<String, SessionActivity>,
    untitled: String,
    rechecking: Set<String> = emptySet(),
): List<HistoryRowModel> {
    fun row(
        id: String,
        title: String,
    ): HistoryRowModel {
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
