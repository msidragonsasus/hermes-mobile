package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupMessage
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.ui.chat.APPROVAL_MESSAGE_PREFIX
import com.m57.hermescontrol.ui.chat.ApprovalHistoryAnswer
import com.m57.hermescontrol.ui.chat.ApprovalPlacement
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchController
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ResponseLedger
import com.m57.hermescontrol.ui.chat.ResponseRecord
import com.m57.hermescontrol.ui.chat.SPECIALIST_SETUP_REVIEW
import com.m57.hermescontrol.ui.chat.SpecialistChatState
import com.m57.hermescontrol.ui.chat.projectMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainChatTimelinePlanTest {
    private val requestA = ChatMessage(id = "A", role = MessageRole.USER, content = "First request")
    private val requestB = ChatMessage(id = "B", role = MessageRole.USER, content = "Next request")
    private val partial =
        ChatMessage(id = "reply-A", role = MessageRole.ASSISTANT, content = "First", isStreaming = true)
    private val ledger =
        ResponseLedger(
            activeId = "response-A",
            responses =
                listOf(
                    ResponseRecord(id = "response-A", userIds = listOf(requestA.id)),
                    ResponseRecord(id = "response-B", userIds = listOf(requestB.id), serverFollowUp = true),
                ),
        )

    @Test
    fun waitingAndLiveTextRemainUnderTheActivePrompt() {
        val transcript = listOf(requestA, requestB)
        val waiting = planMainChatTimeline(transcript, null, ledger, waitingForFirstText = true)
        assertEquals(listOf("user-B", MAIN_CHAT_WAITING_KEY, "user-A"), waiting.map { it.key })

        val slot = partial.mainChatStreamingSlot()
        val live = planMainChatTimeline(transcript, slot, ledger, waitingForFirstText = false)
        assertEquals(listOf("user-B", "prose-reply-A", "user-A"), live.map { it.key })
        assertTrue(live[1].live)
        assertEquals("", live[1].message?.content)
        assertEquals(
            slot,
            partial.copy(content = "First response continued", reasoningText = "hidden").mainChatStreamingSlot(),
        )

        val committed = partial.copy(content = "First response completed", isStreaming = false)
        val committedLedger =
            ledger.copy(
                responses =
                    listOf(
                        ledger.responses.first().copy(messageIds = listOf(committed.id)),
                        ledger.responses.last(),
                    ),
            )
        val result = planMainChatTimeline(transcript + committed, slot, committedLedger, waitingForFirstText = false)
        assertEquals(live.map { it.key }, result.map { it.key })
        assertEquals(live[1].nativeIdentity, result[1].nativeIdentity)
        assertEquals(committed, result[1].message)
        assertFalse(result[1].live)
    }

    @Test
    fun approvalAndItsPreviewStayTogetherAboveTheQueuedUser() {
        val approval =
            ChatMessage(
                id = "approval",
                role = MessageRole.SYSTEM,
                content =
                    APPROVAL_MESSAGE_PREFIX + "operation",
            )
        val restored = partial.copy(id = "restored", content = "First response", isStreaming = false)
        val approvalLedger =
            ledger.copy(
                approvalPlacements = mapOf(approval.id to ApprovalPlacement("response-A")),
                approvalHistoryAnswer = ApprovalHistoryAnswer("response-A", restored.id),
            )
        val rows =
            planMainChatTimeline(
                listOf(requestA, requestB, approval, restored),
                partial.mainChatStreamingSlot(),
                approvalLedger,
                waitingForFirstText = false,
                showsLivePreview = true,
            )

        assertEquals(listOf("user-B", "prose-reply-A", "sys-approval", "user-A"), rows.map { it.key })
        assertTrue(rows[1].live)
    }

    @Test
    fun brainstormCardRemainsOnceWhileOtherToolsAreHidden() {
        val acknowledgement =
            ChatMessage(id = "ack", role = MessageRole.TOOL, content = """{"brainstorm_id":"brainstorm-1"}""")
        val completion = acknowledgement.copy(id = "complete")
        val other = acknowledgement.copy(id = "other", content = "tool progress")

        val rows = planMainChatTimeline(listOf(requestA, acknowledgement, other, completion), null, ledger, false)

        assertEquals(listOf("brainstorm-complete", "user-A"), rows.map { it.key })
        assertEquals("brainstorm-1", rows.first().brainstorm?.id)
    }

    @Test
    fun specialistSetupAndBusyTurnKeepTheirOwnTimelineRows() {
        val setup =
            SpecialistChatState(
                snapshot =
                    SpecialistSetupSnapshot(
                        name = "research",
                        greeting = SpecialistSetupMessage("hello", "assistant", "Tell me what to research"),
                        draft = SpecialistSetupDraft("draft", description = "Research support"),
                    ),
            )
        val tool = ChatMessage(id = "internal-tool", role = MessageRole.TOOL, content = "Internal tool output")
        val clarify =
            ChatMessage(id = "clarify", role = MessageRole.SYSTEM, content = "", clarifyInfo = ClarifyUi("Which date?"))
        val busyLedger =
            ledger.copy(
                responses =
                    listOf(
                        ledger.responses.first().copy(messageIds = listOf(tool.id, clarify.id)),
                        ledger.responses.last(),
                    ),
            )
        val rows =
            planMainChatTimeline(
                setup.projectMessages(listOf(requestA, tool, clarify, requestB)),
                null,
                busyLedger,
                true,
            )

        assertEquals(
            listOf(
                "user-B",
                MAIN_CHAT_WAITING_KEY,
                "sys-clarify",
                "user-A",
                "sys-$SPECIALIST_SETUP_REVIEW",
                "prose-specialist:hello",
            ),
            rows.map { it.key },
        )
        assertEquals(clarify, rows.first { it.key == "sys-clarify" }.message)
    }

    @Test
    fun olderPagesAppendWithoutChangingTheExistingLazyKeysOrIndices() {
        val older = requestA.copy(id = "older")
        val answer = partial.copy(isStreaming = false)
        val current = listOf(requestA, answer, requestB)

        val rows = planMainChatTimeline(current, null, ResponseLedger(), false)
        val paged = planMainChatTimeline(listOf(older) + current, null, ResponseLedger(), false)

        assertEquals(listOf("user-B", "prose-reply-A", "user-A"), rows.map { it.key })
        assertEquals(rows.map { it.key } + "user-older", paged.map { it.key })
    }

    @Test
    fun hiddenProcessRowsDoNotCreateSlotsOrSearchHits() {
        val reasoning = partial.copy(content = "", reasoningText = "hidden step")
        val process =
            listOf(
                reasoning.copy(isStreaming = false),
                ChatMessage(id = "tool", role = MessageRole.TOOL, content = "hidden tool"),
                ChatMessage(id = "event", role = MessageRole.SYSTEM, content = "hidden event"),
                ChatMessage(
                    id = "model",
                    role = MessageRole.USER,
                    content = "hidden switch",
                    displayKind = "model_switch",
                ),
            )
        val transcript = listOf(requestA) + process
        assertNull(reasoning.mainChatStreamingSlot())
        assertEquals(listOf("user-A"), planMainChatTimeline(transcript, null, ledger, false).map { it.key })
        assertEquals(0, ChatSearchController().findMatches(transcript, "hidden", mainChatOnly = true).totalMatches)
    }
}
