package com.m57.hermescontrol.career

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerConversationStateTest {
    private val execution = CareerExecution("openai-codex", "test-model", "medium")
    private val ready =
        CareerCoachUiState(
            initialized = true,
            draft = "相談する",
            execution = execution,
            capabilitiesChecked = true,
            capabilities =
                CareerCapabilities(
                    state = "available",
                    features = listOf("execution_config"),
                    models =
                        listOf(
                            CareerModelOption(
                                execution.provider,
                                execution.model,
                                reasoning_efforts = listOf("medium"),
                            ),
                        ),
                ),
        )

    @Test
    fun sendRequiresFreshSupportedExecutionButDoesNotPreventComposingWhileChecking() {
        assertTrue(careerCanSend(ready))
        val checking = ready.copy(capabilitiesChecked = false)
        assertTrue(careerDraftEditable(checking))
        assertFalse(careerCanSend(checking))
        assertFalse(careerCanSend(ready.copy(capabilities = ready.capabilities?.copy(features = emptyList()))))
        assertFalse(careerCanSend(ready.copy(execution = execution.copy(reasoning_effort = "unsupported"))))
        assertFalse(careerCanSend(ready.copy(capabilities = ready.capabilities?.copy(state = "unavailable"))))
        assertFalse(careerCanSend(ready.copy(draft = " ")))
        assertNull(careerSendPreparation(ready))
    }

    @Test
    fun sendCannotBypassReadOnlyArchivedOrPendingState() {
        assertFalse(careerCanSend(ready.copy(readOnly = true)))
        assertFalse(careerCanSend(ready.copy(localWritable = false)))
        assertFalse(careerCanSend(ready.copy(snapshot = CareerCoachSnapshot(archived = true))))
        assertFalse(careerCanSend(ready.copy(busy = true)))
        assertFalse(
            careerCanSend(
                ready.copy(
                    pending = CareerPendingOperation("message", kotlinx.serialization.json.JsonObject(emptyMap())),
                ),
            ),
        )
    }

    @Test
    fun latestPageUpdatesDuplicatesAndMetadataWithoutRemovingReadHistory() {
        val oldMessage = CareerCoachMessage("old", "r1", "assistant", "履歴", 1)
        val duplicate = CareerCoachMessage("same", "r2", "assistant", "前の表示", 2)
        val previous = CareerCoachSnapshot(messages = listOf(oldMessage, duplicate), older_before = 1)
        val latest =
            CareerCoachSnapshot(messages = listOf(duplicate.copy(text = "サーバーの現行表示")), cursor = 7, older_before = 2)
        val merged = mergeCareerConversation(previous, latest, keepOlderCursor = true)
        assertEquals(listOf("old", "same"), merged.messages.map { it.id })
        assertEquals("サーバーの現行表示", merged.messages.last().text)
        assertEquals(1L, merged.older_before)
        assertEquals(7L, merged.cursor)
        assertEquals(latest, mergeCareerConversation(previous.copy(thread_id = "another"), latest, true))
        assertEquals(latest, mergeCareerConversation(previous.copy(profile = "another"), latest, true))
    }

    @Test
    fun runningRunIsSelectedBeforeOldPausedWorkAndOfflineStopIsDisabled() {
        val paused = CareerCoachRun("paused", "message", "paused")
        val running = CareerCoachRun("running", "message", "running")
        val active = ready.copy(stale = false, snapshot = CareerCoachSnapshot(runs = listOf(paused, running)))
        assertEquals(running, careerCurrentRun(active))
        assertTrue(careerCanStop(active, running))
        assertFalse(careerCanStop(active.copy(stale = true), running))
        assertFalse(careerCanStop(active.copy(readOnly = true), running))
        assertFalse(careerCanStop(active, running.copy(cancel_requested = 1)))
        assertFalse(careerCanStop(active, running.copy(state = "completed")))
    }
}
