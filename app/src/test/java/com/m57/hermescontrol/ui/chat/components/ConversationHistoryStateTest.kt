package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.ui.bots.group.GroupHistoryEntry
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryStateTest {
    @Test
    fun groupFailureDoesNotTurnSuccessfulOrdinaryHistoryIntoAnError() {
        val ordinary = SessionsUiState(sessions = listOf(SessionInfo("ordinary", "Saved chat")), total = 1)
        val merged = mergeConversationHistory(ordinary, listOf(group), false, true, "Untitled")

        assertNull(merged.errorMessage)
        assertNull(merged.searchError)
        assertTrue(merged.groupHistoryUnavailable)
        assertEquals(listOf("ordinary", group.key), merged.sessions.map { it.id })
        assertEquals(ordinary.total, merged.total)
    }

    @Test
    fun ordinaryFailureRemainsVisibleAlongsideUsableGroupHistory() {
        val ordinary = SessionsUiState(errorMessage = "unavailable", searchError = "search unavailable")
        val merged = mergeConversationHistory(ordinary, listOf(group), false, false, "Untitled")

        assertEquals("unavailable", merged.errorMessage)
        assertEquals("search unavailable", merged.searchError)
        assertFalse(merged.groupHistoryUnavailable)
        assertEquals(group.key, merged.sessions.single().id)
    }

    @Test
    fun searchKeepsOrdinaryResultsWhenTheGroupSourceFailsAndRecoveryClearsOnlyItsWarning() {
        val ordinary =
            SessionsUiState(
                searchQuery = "saved",
                searchResults = listOf(SessionSearchResult("ordinary", "Saved result")),
                hasMore = true,
            )
        val failed = mergeConversationHistory(ordinary, listOf(group), false, true, "Untitled")
        assertEquals(listOf("ordinary", group.key), failed.searchResults.map { it.session_id })
        assertNull(failed.searchError)
        assertTrue(failed.hasMore)

        val recovered = mergeConversationHistory(ordinary, listOf(group), false, false, "Untitled")
        assertFalse(recovered.groupHistoryUnavailable)
        assertEquals(failed.searchResults, recovered.searchResults)
    }

    private val group = GroupHistoryEntry("team", "saved", "Team", "Saved discussion", 1)
}
