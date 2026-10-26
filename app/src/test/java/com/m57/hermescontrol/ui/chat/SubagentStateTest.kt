package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.chat.components.shouldShowProgressChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentStateTest {
    private val running =
        SubagentIndicator("subagent.start", goal = "Prepare Word fonts", subagentId = "sa-1", status = "running")

    @Test
    fun emptyLiveRosterRemovesMissedCompletionWithoutInventingSuccess() {
        val completed = running.copy(subagentId = "sa-2", type = "subagent.complete", status = "success")
        val result = reconcileSubagentSnapshot(listOf(running, completed), mapOf("subagents" to emptyList<Any>()))!!
        assertEquals(listOf(completed), result)
        assertFalse(shouldShowProgressChip(emptyList(), result))
    }

    @Test
    fun rosterKeepsLiveChildrenAndTheirLogsAndHydratesUnknownChildren() {
        val withLogs = running.copy(logs = listOf(SubagentLogLine(text = "Reading fonts")))
        val result =
            reconcileSubagentSnapshot(
                listOf(withLogs),
                mapOf(
                    "subagents" to
                        listOf(
                            mapOf("subagent_id" to "sa-1", "status" to "running"),
                            mapOf("subagent_id" to "sa-2", "status" to "queued", "goal" to "Check layout"),
                        ),
                ),
            )!!
        assertEquals(withLogs, result.first())
        assertEquals(2, result.size)
        assertTrue(result.last().isQueued)
        assertTrue(shouldShowProgressChip(emptyList(), result))
    }

    @Test
    fun malformedOrUnsupportedSnapshotIsNotAnEmptyRoster() {
        for (snapshot in listOf(
            null,
            Unit,
            emptyMap<String, Any>(),
            mapOf("subagents" to listOf("bad")),
            mapOf("subagents" to listOf(mapOf("subagent_id" to "sa-1", "status" to "new-status"))),
        )) {
            assertNull(reconcileSubagentSnapshot(listOf(running), snapshot))
        }
    }

    @Test
    fun snapshotNeverResurrectsCompletedIdentity() {
        val completed = running.copy(type = "subagent.complete", status = "success", summary = "Done")
        val result =
            reconcileSubagentSnapshot(
                listOf(completed),
                mapOf(
                    "subagents" to
                        listOf(
                            mapOf("subagent_id" to "sa-1", "status" to "running"),
                        ),
                ),
            )
        assertEquals(listOf(completed), result)
    }

    @Test
    fun delayedProgressCannotResurrectCompletedChild() {
        val state =
            ChatUiState(subagentIndicators = listOf(running.copy(type = "subagent.complete", status = "success")))
        for (type in listOf("subagent.start", "subagent.progress", "subagent.tool")) {
            val result = event(state, type, mapOf("subagent_id" to "sa-1", "status" to "running"))
            assertEquals(state.subagentIndicators, result.subagentIndicators)
            assertFalse(shouldShowProgressChip(emptyList(), result.subagentIndicators))
        }
    }

    @Test
    fun completionCanLearnStableIdAfterLegacyStart() {
        val legacy = running.copy(subagentId = null, taskIndex = 0)
        val result =
            event(
                ChatUiState(subagentIndicators = listOf(legacy)),
                "subagent.complete",
                mapOf("subagent_id" to "sa-1", "goal" to legacy.goal, "task_index" to 0, "status" to "success"),
            )
        assertEquals(1, result.subagentIndicators.size)
        assertTrue(result.subagentIndicators.single().isComplete)
    }

    @Test
    fun childSessionAliasConnectsStartAndCompletion() {
        val started =
            event(
                ChatUiState(),
                "subagent.start",
                mapOf(
                    "child_session_id" to "child-1",
                    "goal" to running.goal,
                ),
            )
        val result =
            event(started, "subagent.complete", mapOf("subagent_id" to "sa-1", "child_session_id" to "child-1"))
        assertEquals(1, result.subagentIndicators.size)
        assertEquals("sa-1", result.subagentIndicators.single().subagentId)
        assertTrue(result.subagentIndicators.single().isComplete)
    }

    @Test
    fun completionCanUpgradeChildSessionAliasWhenOnlyStableIdAndGoalArrive() {
        val started =
            event(
                ChatUiState(),
                "subagent.start",
                mapOf(
                    "child_session_id" to "child-1",
                    "goal" to running.goal,
                ),
            )
        val result = event(started, "subagent.complete", mapOf("subagent_id" to "sa-1", "goal" to running.goal))
        assertEquals(1, result.subagentIndicators.size)
        assertTrue(result.subagentIndicators.single().isComplete)
    }

    @Test
    fun taskIndexMatchesLegacyCompletionAndDoesNotMergeDifferentStableIds() {
        val state =
            ChatUiState(
                subagentIndicators =
                    listOf(
                        running.copy(taskIndex = 0),
                        running.copy(subagentId = "sa-2", taskIndex = 1),
                    ),
            )
        val completed = event(state, "subagent.complete", mapOf("task_index" to 0))
        assertEquals(2, completed.subagentIndicators.size)
        assertTrue(completed.subagentIndicators.first().isComplete)
        assertTrue(completed.subagentIndicators.last().isRunning)
        val different = event(state, "subagent.complete", mapOf("subagent_id" to "sa-3", "goal" to running.goal))
        assertEquals(3, different.subagentIndicators.size)
        assertTrue(different.subagentIndicators.first().isRunning)
    }

    @Test
    fun terminalStatusAliasesNeverCountAsRunningOrSuccessfulFailures() {
        for (status in listOf("success", "error", "timeout", "interrupted", "stopped")) {
            val indicator = running.copy(status = status)
            assertFalse(status, indicator.isRunning)
            if (status != "success") assertFalse(indicator.copy(type = "subagent.complete").isComplete)
        }
    }

    @Test
    fun parentCompletionDoesNotHideBackgroundChildren() {
        val result =
            ChatWsEventReducer.reduce(
                ChatUiState(subagentIndicators = listOf(running)),
                StreamingState(),
                WsEvent.MessageDone(null),
            )
        assertEquals(listOf(running), result.state.subagentIndicators)
    }

    private fun event(
        state: ChatUiState,
        type: String,
        payload: Map<String, Any?>,
    ): ChatUiState =
        ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.SubagentEvent(type, payload, null)).state
}
