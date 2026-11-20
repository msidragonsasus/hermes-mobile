package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.components.conversationHistoryTitle
import com.m57.hermescontrol.ui.chat.components.messageActionText
import com.m57.hermescontrol.ui.chat.components.quotedReplyDraft
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

    private fun recordedImageTurn(): String =
        requireNotNull(javaClass.getResource("/mobile-context/raw-user-51060.txt")).readText().replace("\r\n", "\n")

    @Test fun multimodalHistoryHidesTheLegacyTextEnvelopeBeforeTheImagePart() {
        val fixture =
            requireNotNull(javaClass.getResource("/mobile-context/multimodal-user-with-generated-context.json"))
        val row = Json.decodeFromString<SessionMessage>(fixture.readText())
        val caption = "画像を確認して"
        val image = "![Image](<data:image/png;base64,AA==>)"
        val expected = "$caption\n\n$image"
        assertTrue(row.contentText.contains("</hermes-mobile-display-context>\n\n$image"))
        assertEquals(DisplayedUserText(expected, emptyList()), RichAnswerPrompt.displayText(row.contentText))
        assertEquals(expected, mapServerMessages("s", listOf(row), 0, false, emptyList()).single().content)
        val cached =
            ChatMessageEntity(id = "u", sessionId = "s", role = "USER", content = row.contentText, timestamp = 1)
        assertEquals(expected, cached.toUiModel().content)
        val loaded = ChatMessage(role = MessageRole.USER, content = row.contentText)
        assertEquals(expected, messageActionText(loaded))
        assertEquals("> $caption\n> \n> $image\n\n続き", quotedReplyDraft(loaded, "続き"))
        assertEquals(
            0,
            ChatSearchController().findMatches(listOf(loaded), "hermes-mobile-generated-context").totalMatches,
        )
        assertEquals(1, ChatSearchController().findMatches(listOf(loaded), caption).totalMatches)
        val truncated = row.contentText.substringBefore("<hermes-mobile-display-context>").trimEnd()
        assertEquals(caption, RichAnswerPrompt.previewText(truncated))
        assertEquals(caption, conversationHistoryTitle(truncated.replace("\n\n", " "), null, "Untitled"))
    }

    @Test fun crlfRefsWarningsAndRepeatedEnvelopesCanSurroundTheCaption() {
        val caption = "この画像を確認して\r\n  2行目  "
        val file = "/var/lib/hermes/.hermes/attachments/report.txt"
        val wire =
            RichAnswerPrompt.append("@file:$file\n\n$caption") +
                "\n\n--- Attached Context ---\n\n📎 @file:$file (text file)\n\n" +
                RichAnswerPrompt.append("--- Context Warnings ---\n- @file:missing.md: file not found") +
                "\n\n@image:$path"
        val crlf = wire.replace("\r\n", "\n").replace("\n", "\r\n")
        assertEquals(DisplayedUserText(caption, listOf(file, path)), RichAnswerPrompt.displayText(crlf))
    }

    @Test fun generatedPrefixWithAnImageRefAndTrailingEnvelopeStillShowsTheCaption() {
        val generated = RichAnswerPrompt.append("ignored").substringAfter("ignored\n\n")
        val wire = "$generated\n@image:$path\n\n$ask\n\n$generated"
        assertEquals(DisplayedUserText(ask, listOf(path)), RichAnswerPrompt.displayText(wire))
    }

    @Test fun captionFirstImageRefsSurviveLegacyAndCleanHistory() {
        val quotedPath = "/srv/profiles/bot/images/upload_a space.png"
        val imageRef = "@image:\"$quotedPath\""
        val expected = DisplayedUserText(ask, listOf(quotedPath))
        assertEquals(expected, RichAnswerPrompt.displayText(RichAnswerPrompt.append(ask) + "\n" + imageRef))
        assertEquals(expected, RichAnswerPrompt.displayText("$ask\n$imageRef"))
        assertEquals(expected, RichAnswerPrompt.displayText("$ask\n$imageRef\n[screenshot]"))
        assertEquals(
            expected,
            RichAnswerPrompt.displayText("$ask\n$imageRef\n\n![Image](<data:image/png;base64,AA==>)"),
        )
        assertEquals(DisplayedUserText("", listOf(quotedPath)), RichAnswerPrompt.displayText(imageRef))
        val typed = "$ask\n@image:my-image.png"
        assertEquals(DisplayedUserText(typed, emptyList()), RichAnswerPrompt.displayText(typed))
    }

    @Test fun mergedAttachmentTurnHidesEarlierMarkedBlocksToo() {
        val wire = RichAnswerPrompt.append("1通目") + "\n\n" + RichAnswerPrompt.append("2通目") + "\n@image:$path"
        assertEquals(DisplayedUserText("1通目\n\n2通目", listOf(path)), RichAnswerPrompt.displayText(wire))
    }

    @Test fun exactStoredLeadingImageAndTrailingDescriptorProjectToHumanAskAndMedia() {
        val shown = RichAnswerPrompt.displayText(recordedImageTurn())
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
            assertEquals(recordedImageTurn(), row.contentText)
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
