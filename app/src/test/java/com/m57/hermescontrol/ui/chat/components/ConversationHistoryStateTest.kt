package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.domain.hermes.LiveRead
import com.m57.hermescontrol.domain.hermes.LiveRosterPoller
import com.m57.hermescontrol.domain.hermes.LiveSession
import com.m57.hermescontrol.domain.hermes.LiveStatus
import com.m57.hermescontrol.domain.hermes.SessionActivity
import com.m57.hermescontrol.domain.hermes.TransportConnection
import com.m57.hermescontrol.domain.hermes.projectHistoryActivity
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationHistoryStateTest {
    @Test
    fun listAndSearchRowsShareOneStatusMapping() {
        val activity =
            mapOf(
                "ordinary" to SessionActivity.RUNNING,
                "hidden" to SessionActivity.NEEDS_INPUT,
            )
        val ordinary =
            SessionsUiState(
                sessions =
                    listOf(
                        SessionInfo("ordinary", "Saved chat"),
                        SessionInfo("hidden", "Hidden", hidden = true),
                    ),
            )
        val listRows = conversationHistoryRows(ordinary, activity, "Untitled")
        val searchRows =
            conversationHistoryRows(
                ordinary.copy(
                    searchQuery = "saved",
                    searchResults =
                        listOf(
                            SessionSearchResult("ordinary", "snippet"),
                            SessionSearchResult("ordinary", "again"),
                        ),
                ),
                activity,
                "Untitled",
            )

        val expected = listOf("ordinary" to SessionActivity.RUNNING)
        assertEquals(expected, listRows.map { it.id to it.activity })
        assertEquals(expected, searchRows.map { it.id to it.activity })
        assertEquals(listRows.first().title, searchRows.first().title)
    }

    @Test
    fun compressedLineageIdsMapLiveStatusToTheSameRowInListAndSearch() {
        val decoded =
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SessionInfo>(
                """{"id":"tip","title":"圧縮後","_lineage_root_id":"root","_lineage_ids":["root","middle","tip"]}""",
            )
        assertEquals("root", decoded.lineage_root_id)
        val state = SessionsUiState(sessions = listOf(decoded, SessionInfo("other", "別")))
        val activity = mapOf("middle" to SessionActivity.RUNNING, "root" to SessionActivity.WAITING)

        val list = conversationHistoryRows(state, activity, "Untitled")
        val search =
            conversationHistoryRows(
                state.copy(searchQuery = "圧縮", searchResults = listOf(SessionSearchResult("tip", "圧縮後"))),
                activity,
                "Untitled",
            )

        // Highest-priority evidence across the chain wins; unrelated rows stay quiet.
        assertEquals(listOf("tip" to SessionActivity.WAITING, "other" to null), list.map { it.id to it.activity })
        assertEquals(listOf("tip" to SessionActivity.WAITING), search.map { it.id to it.activity })
    }

    @Test
    fun compressionRotationWhileWorkingKeepsTheSpinnerOnTheCachedHistoryRowInListAndSearch() =
        runTest {
            val scope = ConnectionScope("server", "connection", "profile", "process", 1)
            val connection = MutableStateFlow(TransportConnection(scope, LinkState.CONNECTED))
            // Legacy gateway: same runtime id, session_key follows agent.session_id across /compress.
            var live = listOf(LiveSession("runtime-stable", "saved-root", LiveStatus.WORKING))
            val poller = LiveRosterPoller({ LiveRead.Available(it, live) }, { currentTime })
            // Cached REST history has not caught up: the row only knows the pre-compression id.
            val cached = SessionsUiState(sessions = listOf(SessionInfo("saved-root", "圧縮前"), SessionInfo("other", "別")))
            val search =
                cached.copy(searchQuery = "圧縮", searchResults = listOf(SessionSearchResult("saved-root", "圧縮前")))

            fun rows(state: SessionsUiState): List<Pair<String, SessionActivity?>> {
                val projected = projectHistoryActivity(connection.value, poller.roster.value, emptyList(), currentTime)
                return conversationHistoryRows(state, projected.activity, "Untitled", rechecking = projected.rechecking)
                    .map { it.id to it.activity }
            }
            val visible = launch { poller.poll(connection) }
            runCurrent()
            assertEquals(listOf("saved-root" to SessionActivity.RUNNING, "other" to null), rows(cached))

            live = listOf(LiveSession("runtime-stable", "saved-tip", LiveStatus.WORKING))
            advanceTimeBy(4_001)
            assertEquals(listOf("saved-root" to SessionActivity.RUNNING, "other" to null), rows(cached))
            assertEquals(listOf("saved-root" to SessionActivity.RUNNING), rows(search))
            // Once REST lineage catches up, the tip row maps directly, too.
            val refreshed =
                SessionsUiState(
                    sessions =
                        listOf(
                            SessionInfo(
                                "saved-tip",
                                "圧縮後",
                                lineage_root_id = "saved-root",
                                lineage_ids = listOf("saved-root", "saved-tip"),
                            ),
                        ),
                )
            assertEquals(listOf("saved-tip" to SessionActivity.RUNNING), rows(refreshed))

            // Authoritative idle settles every alias; a later turn does not resurrect the settled one.
            live = listOf(LiveSession("runtime-stable", "saved-tip", LiveStatus.IDLE))
            advanceTimeBy(4_000)
            assertEquals(listOf("saved-root" to null, "other" to null), rows(cached))
            live = listOf(LiveSession("runtime-stable", "saved-tip", LiveStatus.WORKING))
            advanceTimeBy(4_000)
            assertEquals(listOf("saved-root" to null, "other" to null), rows(cached))
            visible.cancel()
        }

    @Test
    fun recheckingFlagFollowsTheSameMapping() {
        val state =
            SessionsUiState(
                sessions =
                    listOf(
                        SessionInfo("tip", "圧縮後", lineage_root_id = "root", lineage_ids = listOf("root", "tip")),
                        SessionInfo("quiet", "静か"),
                    ),
            )
        val rows =
            conversationHistoryRows(
                state,
                mapOf("root" to SessionActivity.RUNNING),
                "Untitled",
                rechecking = setOf("root", "quiet"),
            )
        // A rechecking ID without status (quiet) claims nothing.
        assertEquals(
            listOf(
                Triple("tip", SessionActivity.RUNNING, true),
                Triple("quiet", null, false),
            ),
            rows.map { Triple(it.id, it.activity, it.rechecking) },
        )
    }
}
