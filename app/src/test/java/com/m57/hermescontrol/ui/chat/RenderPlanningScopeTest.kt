package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.ui.chat.fakes.FakeSubmissionTransport
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class RenderPlanningScopeTest {
    @Test
    fun renderScopeTracksSessionSelectionAndConnectionBoundaries() {
        val harness = ChatViewModelTest()
        harness.setUp()
        var vm: ChatViewModel? = null
        try {
            val created =
                ChatViewModelTest::class.java
                    .getDeclaredMethod("createViewModel", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(harness, false) as ChatViewModel
            vm = created
            val method =
                ChatViewModel::class.java.declaredMethods.singleOrNull {
                    it.name.substringBefore('$') == "renderPlanningScope" && it.parameterCount == 0
                }
            assertNotNull("Renderer planning requires an explicit session/connection-generation scope", method)
            method!!.isAccessible = true

            fun scope(): Any = method.invoke(created)
            val initial = scope()
            assertEquals(initial, scope())
            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(created) as MutableStateFlow<ChatUiState>
            state.value = state.value.copy(currentSessionId = "session-a")
            val sessionA = scope()
            assertNotEquals(initial, sessionA)
            state.value = state.value.copy(currentSessionId = "session-b")
            assertNotEquals(sessionA, scope())
            state.value = state.value.copy(currentSessionId = "session-a")
            val generation =
                ChatViewModel::class.java
                    .getDeclaredField(
                        "sessionGeneration",
                    ).apply { isAccessible = true }
            generation.setLong(created, generation.getLong(created) + 1)
            assertNotEquals(sessionA, scope())
            val selected = ChatViewModel::class.java.getDeclaredField("sessionScope").apply { isAccessible = true }
            val connection = ConnectionScope("server-a", "connection-a", "profile-a", "process-a", 1)
            selected.set(created, connection)
            val connected = scope()
            for (changed in listOf(
                connection.copy(serverIdentity = "server-b"),
                connection.copy(connectionProfileId = "connection-b"),
                connection.copy(serverProfileId = "profile-b"),
                connection.copy(processId = "process-b"),
                connection.copy(generation = 2),
            )) {
                selected.set(created, changed)
                assertNotEquals(connected, scope())
            }
            val submission =
                ChatViewModelTest::class.java
                    .getDeclaredField("fakeSubmission")
                    .apply { isAccessible = true }
                    .get(harness) as FakeSubmissionTransport
            val configured = scope()
            submission.scope = submission.scope.copy(generation = submission.scope.generation + 1)
            assertNotEquals(configured, scope())
        } finally {
            vm?.viewModelScope?.cancel()
            harness.tearDown()
        }
    }
}
