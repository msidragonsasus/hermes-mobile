package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichAnswerPromptTest {
    @Test
    fun hintsAreIdempotentAndHistoryPreservesExactlyWhatUserTyped() {
        val text = "画像を見せて\n\nスライダーも付けて。  "
        val wire = RichAnswerPrompt.append(text)
        assertTrue(wire.startsWith(text))
        assertTrue(wire.contains("hermes-ui-v2"))
        assertTrue(wire.contains("Native catalog hermes.native/3"))
        assertEquals(wire, RichAnswerPrompt.append(wire))
        assertEquals(text, RichAnswerPrompt.visibleText(wire))
    }

    @Test
    fun historySentWithThePreviousNativeCatalogStillHidesItsDescriptor() {
        val previous = NativeUiInstructions.previousDescriptors.single { it.contains("Native catalog hermes.native/1") }
        val wire = "先月の家計\n\n<hermes-mobile-display-context>\n$previous\n</hermes-mobile-display-context>"
        assertEquals("先月の家計", RichAnswerPrompt.visibleText(wire))
    }

    @Test
    fun historySentWithTheDraftOnlyDiscussDescriptorStillHidesIt() {
        val previous =
            NativeUiInstructions.previousDescriptors.single {
                it.contains("Native catalog hermes.native/2") && it.contains("The discuss button prepares a draft")
            }
        assertTrue(NativeUiInstructions.descriptor.contains("tapping it sends the shown conditions"))
        assertTrue(previous != NativeUiInstructions.descriptor)
        val wire = "比較して\n\n<hermes-mobile-display-context>\n$previous\n</hermes-mobile-display-context>"
        assertEquals("比較して", RichAnswerPrompt.visibleText(wire))
    }

    @Test
    fun historySentWithTheSvgCatalogBeforeFlowStillHidesIt() {
        val previous =
            NativeUiInstructions.previousDescriptors.single {
                it.contains("Native catalog hermes.native/2") && it.contains("svg: svg:")
            }
        assertTrue(!previous.contains("flow:"))
        assertTrue(NativeUiInstructions.descriptor.contains("flow: nodes:"))
        assertTrue(NativeUiInstructions.descriptor.contains("Never draw diagrams as ASCII"))
        val wire = "構成を図にして\n\n<hermes-mobile-display-context>\n$previous\n</hermes-mobile-display-context>"
        assertEquals("構成を図にして", RichAnswerPrompt.visibleText(wire))
    }

    @Test
    fun slashCommandsAndBlankPromptsAreUnchanged() {
        listOf("/help", " /queue next", "/model openai/gpt-example", "", "  ").forEach {
            assertEquals(it, RichAnswerPrompt.append(it))
        }
    }

    @Test
    fun userAuthoredContextLikeTextIsNeverStripped() {
        val text = "request\n\n<hermes-mobile-display-context>\nmy words\n</hermes-mobile-display-context>"
        assertEquals(text, RichAnswerPrompt.visibleText(text))
    }

    @Test
    fun previousVersionDisplayContextStillDisappearsFromRestoredHistory() {
        val wire = legacyWire("before update")
        val previousWire = wire.substringBefore("\nMobile image delivery:") + "\n</hermes-mobile-display-context>"
        assertEquals("before update", RichAnswerPrompt.visibleText(previousWire))
    }

    @Test
    fun imageOnlyDisplayContextFromBeforeDesignUpdateIsStillHidden() {
        val wire = legacyWire("before style update")
        val previousWire = wire.substringBefore("\nVisual answer style:") + "\n</hermes-mobile-display-context>"
        assertEquals("before style update", RichAnswerPrompt.visibleText(previousWire))
        assertTrue(wire.contains("viz-controls"))
        assertTrue(wire.contains("No hardcoded page colors"))
    }

    @Test
    fun changedStyleGuideDoesNotLeakIntoRestoredUserText() {
        val userText = "許可は押してみた\n  "
        val previousWire =
            legacyWire(userText).substringBefore("\nVisual answer style:") +
                "\nVisual answer style: older design instructions.\n</hermes-mobile-display-context>"
        assertEquals(userText, RichAnswerPrompt.visibleText(previousWire))
    }

    @Test
    fun restoredContextAcceptsCrLfAndTrailingWhitespaceWithoutChangingUserText() {
        val userText = "許可は押してみた\r\n  "
        val suffix = RichAnswerPrompt.append("request").removePrefix("request").replace("\n", "\r\n")
        assertEquals(userText, RichAnswerPrompt.visibleText(userText + suffix + "\r\n  "))
    }

    @Test
    fun quotedAndIncompleteContextsArePreserved() {
        val wire = RichAnswerPrompt.append("example")
        listOf(wire + "\nここまでが引用です", wire.removeSuffix("\n</hermes-mobile-display-context>")).forEach {
            assertEquals(it, RichAnswerPrompt.visibleText(it))
        }
    }

    private val hostPath = "/var/lib/hermes/.hermes/attachments/2026-10-23/req-1/report.docx"

    /** What the server stores for a message sent with an attachment (app prefix + block + server section). */
    private fun attachmentWire(text: String): String =
        RichAnswerPrompt.append("@file:$hostPath\n\n$text") +
            "\n\n--- Attached Context ---\n\n" +
            "📎 @file:$hostPath (Word document, 18.2 KB) — binary file, not inlined as text. It is available on disk at $hostPath"

    @Test
    fun attachedContextAfterTheDescriptorAndTheLeadingFileRefAreHidden() {
        val shown = RichAnswerPrompt.displayText(attachmentWire("この資料を要約して\n2行目"))
        assertEquals("この資料を要約して\n2行目", shown.text)
        assertEquals(listOf(hostPath), shown.fileRefs)
        // Attachment-only messages show no text, only the card.
        val section = attachmentWire("x").substringAfter("</hermes-mobile-display-context>")
        val attachmentOnly = RichAnswerPrompt.displayText(RichAnswerPrompt.append("@file:$hostPath") + section)
        assertEquals("", attachmentOnly.text)
        assertEquals(listOf(hostPath), attachmentOnly.fileRefs)
        // CRLF storage behaves the same.
        assertEquals(
            "要約",
            RichAnswerPrompt.visibleText(attachmentWire("要約").replace("\n", "\r\n")).replace("\r\n", "\n"),
        )
    }

    @Test
    fun contextWarningsBeforeTheAttachedContextAreHiddenToo() {
        val wire =
            RichAnswerPrompt.append("@file:$hostPath\n\n比べて @file:missing.md") +
                "\n\n--- Context Warnings ---\n- @file:missing.md: file not found" +
                "\n\n--- Attached Context ---\n\n📎 @file:$hostPath (Word document, 18.2 KB) — binary file"
        // The core keeps the reference the user typed; only the app prefix and server sections go.
        assertEquals("比べて @file:missing.md", RichAnswerPrompt.visibleText(wire))
        val warningsOnly =
            RichAnswerPrompt.append("読んで @file:missing.md") +
                "\n\n--- Context Warnings ---\n- @file:missing.md: file not found"
        assertEquals("読んで @file:missing.md", RichAnswerPrompt.visibleText(warningsOnly))
    }

    @Test
    fun leadingFileRefIsHiddenWithoutTheServerSectionOnlyForHostAttachmentPaths() {
        assertEquals("本文", RichAnswerPrompt.visibleText(RichAnswerPrompt.append("@file:$hostPath\n\n本文")))
        val shown = RichAnswerPrompt.displayText(RichAnswerPrompt.append("@image:$hostPath\n@file:$hostPath\n\n本文"))
        assertEquals("本文", shown.text)
        assertEquals(listOf(hostPath, hostPath), shown.fileRefs)
        // A reference the user typed (relative path, or text on the same line) stays visible.
        listOf("@file:notes.md\n\n読んで", "@file:$hostPath を読んで", "@file:/home/me/notes.md\n\n読んで").forEach {
            assertEquals(it, RichAnswerPrompt.visibleText(RichAnswerPrompt.append(it)))
        }
        // Without any app/server wrapper nothing is touched.
        assertEquals("@file:$hostPath\n\n本文", RichAnswerPrompt.visibleText("@file:$hostPath\n\n本文"))
    }

    @Test
    fun unknownCatalogVersionBlocksAreHidden() {
        val future =
            "相談\n\n<hermes-mobile-display-context>\n" +
                "The user is reading this conversation in Hermes Mobile, " +
                "which can render interactive visual answers.\n" +
                "Native catalog hermes.native/9. Something new.\n</hermes-mobile-display-context>"
        assertEquals("相談", RichAnswerPrompt.visibleText(future))
        assertEquals(
            "相談",
            RichAnswerPrompt.visibleText(future + "\n\n--- Attached Context ---\n\n📎 @file:$hostPath — x"),
        )
    }

    @Test
    fun userAuthoredBlocksAndSectionsStayVisible() {
        val typed = "タグの例です\n\n<hermes-mobile-display-context>\n自分のメモ\n</hermes-mobile-display-context>"
        assertEquals(typed, RichAnswerPrompt.visibleText(typed))
        // The app's real block after a typed one: only the app's block goes.
        assertEquals(typed, RichAnswerPrompt.visibleText(RichAnswerPrompt.append(typed)))
        // A typed block in the middle of the text is never cut.
        val middle = RichAnswerPrompt.append("前") + "\n\n後ろの文"
        assertEquals(middle, RichAnswerPrompt.visibleText(middle))
        // A typed "Attached Context" heading without server references stays.
        val heading = "見出し\n\n--- Attached Context ---\n\nただのメモ"
        assertEquals(heading, RichAnswerPrompt.visibleText(heading))
        assertEquals(heading, RichAnswerPrompt.visibleText(RichAnswerPrompt.append(heading)))
    }

    @Test
    fun localAndHistoryCopiesOfAnAttachmentMessageMergeIntoOneBubble() {
        val local = ChatMessage(id = "local", role = MessageRole.USER, content = "この資料を要約して", timestamp = 1)
        val history =
            ChatMessage(
                id = "rest-s-1",
                role = MessageRole.USER,
                content = RichAnswerPrompt.visibleText(attachmentWire("この資料を要約して")),
                timestamp = 1,
            )
        val merged = mergeTranscriptWithLive(listOf(history), listOf(local))
        assertEquals(1, merged.size)
        assertEquals("この資料を要約して", merged.single().content)
    }

    @Test
    fun truncatedSessionPreviewsShowOnlyTheTypedText() {
        val stored = attachmentWire("面接の準備")
        assertEquals("面接の準備", RichAnswerPrompt.previewText(stored.take(120)))
        assertEquals("面接の準備", RichAnswerPrompt.previewText(stored.take(78).replace("\n\n", " ")).trim())
        assertEquals("ふつうの相談", RichAnswerPrompt.previewText("ふつうの相談"))
        assertEquals(
            "面接の準備",
            com.m57.hermescontrol.ui.chat.components
                .conversationHistoryTitle(null, stored.take(200), "Untitled"),
        )
    }

    private fun legacyWire(text: String): String =
        text + requireNotNull(javaClass.getResource("/native-ui/legacy-display-context.txt")).readText()
}
