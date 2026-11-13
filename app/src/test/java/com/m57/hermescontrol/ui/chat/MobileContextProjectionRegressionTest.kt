package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileContextProjectionRegressionTest {
    private val block =
        "<hermes-mobile-display-context>\n" + NativeUiInstructions.descriptor + "\n</hermes-mobile-display-context>"
    private val ask = "⬆これなんとかならんの？入れなあかんの？skillとかそっちに逃がせられへんの"
    private val path = "/var/lib/hermes/.hermes/images/upload_18drbm3w.jpg"
    private val raw = "$block\n@image:$path\n\n\n$ask\n\n$block"

    @Test fun exactStoredLeadingImageAndTrailingDescriptorProjectToHumanAskAndMedia() {
        val captured = requireNotNull(javaClass.getResource("/mobile-context/raw-user-51060.txt")).readText()
        assertEquals(raw, captured)
        val shown = RichAnswerPrompt.displayText(captured)
        assertEquals("\n$ask", shown.text)
        assertEquals(listOf(path), shown.fileRefs)
    }

    @Test fun repeatedTrailingDescriptorsAreAllRemoved() {
        assertEquals(ask, RichAnswerPrompt.visibleText("$ask\n\n$block\n\n$block\n\n$block"))
    }

    @Test fun leadingKnownDescriptorAloneDoesNotHideFollowingHumanText() {
        assertEquals(ask, RichAnswerPrompt.visibleText("$block\n\n$ask"))
    }

    @Test fun leadingAndTrailingCrLfKeepHumanLineEndingsAndWhitespace() {
        val crlf = block.replace("\n", "\r\n")
        val human = "  日本語\r\n二行目  "
        assertEquals(human, RichAnswerPrompt.visibleText("$crlf\r\n\r\n$human\r\n\r\n$crlf\r\n "))
    }

    @Test fun completePrefixKeepsRequestAfterTruncatedTrailingDescriptorInPreview() {
        val wire = "$block\n\n$ask\n\n$block"
        assertEquals(ask, RichAnswerPrompt.previewText(wire.dropLast(100)))
        assertEquals(ask, RichAnswerPrompt.previewText(wire))
    }

    @Test fun knownPrefixWithNoClosingTagMustNotSwallowRequestOrTrailingDescriptor() {
        val human = block.removeSuffix("\n</hermes-mobile-display-context>") + "\n\n$ask"
        assertEquals(human, RichAnswerPrompt.visibleText("$human\n\n$block"))
    }

    @Test fun arbitraryUserTagAtStartStaysEvenWithRealTrailingDescriptor() {
        val human = "<hermes-mobile-display-context>\nmy words\n</hermes-mobile-display-context>\n\n$ask"
        assertEquals(human, RichAnswerPrompt.visibleText("$human\n\n$block"))
    }

    @Test fun preambleAloneCannotAuthenticateLeadingHumanBlock() {
        val human = "<hermes-mobile-display-context>\n${NativeUiInstructions.descriptor.substringBefore(
            '\n',
        )}\nmy quoted notes\n</hermes-mobile-display-context>\n\n$ask"
        assertEquals(human, RichAnswerPrompt.visibleText("$human\n\n$block"))
    }

    @Test fun fencedAndBlockquotedRealDescriptorsStayVerbatim() {
        for (human in listOf(
            "```xml\n$block\n```",
            block.lines().joinToString("\n") {
                "> $it"
            },
            "Here is the example:\n\n$block\n\nEnd quote.",
        )) {
            assertEquals(human, RichAnswerPrompt.visibleText("$human\n\n$block"))
        }
    }

    @Test fun actualDescriptorWithinOpenCodeFenceAtMessageEndIsHumanText() {
        val human = "Explain this example:\n```xml\n\n$block"
        assertEquals(human, RichAnswerPrompt.visibleText(human))
    }

    @Test fun apiClientNoteTypedByHumanIsNeverRemoved() {
        val note =
            "Current response client: Hermes Mobile, native catalog hermes.native/3. " +
                "Follow the current request's hermes-ui-v2 NDJSON schema, not legacy HTML style guidance."
        val human = "Explain this log:\n\n$note"
        assertEquals(human, RichAnswerPrompt.visibleText("$human\n\n$block"))
        assertEquals(human, RichAnswerPrompt.visibleText(human))
    }

    @Test fun previewsPreserveCompleteHumanQuotesAndIncompleteFencedExamples() {
        for (human in listOf(
            "Example:\n\n$block\n\nEnd quote.",
            "```xml\n\n$block\n```",
            "```xml\n\n${block.dropLast(100)}",
            "タグの例\n\n<hermes-mobile-display-context>\nmy words\n</hermes-mobile-display-context>",
        )) {
            assertEquals(human, RichAnswerPrompt.previewText(human))
        }
    }

    @Test fun physicalPayloadAndOutboundDescriptorRemainUnmodified() {
        val before = raw
        RichAnswerPrompt.displayText(raw)
        assertEquals(before, raw)
        val outbound = RichAnswerPrompt.append(ask)
        assertEquals("$ask\n\n<!--hermes-mobile-generated-context:v1-->\n\n$block", outbound)
        assertEquals(outbound, RichAnswerPrompt.append(outbound))
    }

    @Test fun restMappingUsesSameProjectionAndPreservesHiddenAuthority() {
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "http://127.0.0.1:9999"
        every { AuthManager.getToken() } returns "synthetic-test-token"
        every { AuthManager.isGatedMode() } returns false
        try {
            val serialized = requireNotNull(javaClass.getResource("/mobile-context/api-user-51060.json")).readText()
            assertTrue(serialized.contains("Current response client: Hermes Mobile"))
            val row = Json { ignoreUnknownKeys = true }.decodeFromString(SessionMessage.serializer(), serialized)
            assertEquals(raw, row.contentText)
            val mapped = mapServerMessages("s", listOf(row), 0, false, emptyList()).single()
            assertEquals("\n$ask", mapped.content)
            assertEquals(1, mapped.attachments!!.size)
            val projected = row.copy(display_content = JsonPrimitive("authoritative human"))
            assertEquals(
                "authoritative human",
                mapServerMessages("s", listOf(projected), 0, false, emptyList()).single().content,
            )
            val hidden = row.copy(display_kind = "hidden")
            assertTrue(mapServerMessages("s", listOf(hidden), 0, false, emptyList()).single().content.isEmpty())
        } finally {
            unmockkObject(AuthManager)
        }
    }

    @Test fun roomRestoreSharesProjectionWhileAssistantQuotesAreNotRewritten() {
        val entity = ChatMessageEntity(id = "u", sessionId = "s", role = "USER", content = raw, timestamp = 1)
        assertEquals("\n$ask", entity.toUiModel().content)
        assertEquals(raw, entity.copy(role = "ASSISTANT").toUiModel().content)
    }
}
