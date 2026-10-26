package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RichAnswerHistoryTest {
    private val userText = "許可は押してみた"
    private val previousWire =
        (userText + requireNotNull(javaClass.getResource("/native-ui/legacy-display-context.txt")).readText())
            .substringBefore("\nVisual answer style:") +
            "\nVisual answer style: older design instructions.\n</hermes-mobile-display-context>"

    @Test
    fun restoredServerHistoryShowsOnlyUserInputAndPreservesOtherRoles() {
        val roles = listOf("user", "assistant", "system", "tool")
        val rows =
            roles.mapIndexed { index, role ->
                SessionMessage(id = index + 1, role = role, content = JsonPrimitive(previousWire))
            }
        val mapped = mapServerMessages("session", rows, 0, true, emptyList())

        assertEquals(listOf(userText, previousWire, previousWire, previousWire), mapped.map { it.content })
        assertEquals(
            listOf("rest-session-1", "rest-session-2", "rest-session-3", "rest-session-4"),
            mapped.map { it.id },
        )
    }

    @Test
    fun restoredAttachmentMessageShowsTypedTextAndTheFileAsACard() {
        mockkObject(AuthManager)
        try {
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            every { AuthManager.getToken() } returns "test-token"
            every { AuthManager.isGatedMode() } returns false
            val path = "/var/lib/hermes/.hermes/attachments/req-1/report.docx"
            val wire =
                RichAnswerPrompt.append("@file:$path\n\n要約して") +
                    "\n\n--- Attached Context ---\n\n" +
                    "📎 @file:$path (Word document, 18.2 KB) — binary file, not inlined as text. It is available on disk at $path"
            val row = SessionMessage(id = 1, role = "user", content = JsonPrimitive(wire))
            val mapped = mapServerMessages("session", listOf(row), 0, true, emptyList()).single()
            assertEquals("要約して", mapped.content)
            assertEquals("report.docx", mapped.attachments!!.single().name)
            val cached =
                ChatMessageEntity(id = "c", sessionId = "session", role = "USER", content = wire, timestamp = 1L)
            assertEquals("要約して", cached.toUiModel().content)
        } finally {
            unmockkObject(AuthManager)
        }
    }

    @Test
    fun existingCachedHistoryHidesContextWithoutRewritingStoredRows() {
        val roles = listOf("USER", "ASSISTANT", "SYSTEM", "TOOL")
        val rows =
            roles.map { role ->
                ChatMessageEntity(
                    id = "cached-$role",
                    sessionId = "session",
                    role = role,
                    content = previousWire,
                    timestamp = 1234L,
                )
            }
        val mapped = rows.map { it.toUiModel() }

        assertEquals(listOf(userText, previousWire, previousWire, previousWire), mapped.map { it.content })
        assertEquals(rows.map { it.id }, mapped.map { it.id })
        assertEquals(rows.map { it.timestamp }, mapped.map { it.timestamp })
        assertEquals(List(4) { previousWire }, rows.map { it.content })
    }
}
