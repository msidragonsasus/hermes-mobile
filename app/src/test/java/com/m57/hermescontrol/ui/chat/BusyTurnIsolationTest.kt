package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.MAIN_CHAT_WAITING_KEY
import com.m57.hermescontrol.ui.chat.fullbleed.RenderPlanningCache
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.planMainChatTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyTurnIsolationTest {
    @Test
    fun waitingStaysAtTailWhenTheQueuedTurnBecomesActive() {
        val first = user("first", "A")
        val followUp = user("second", "B")
        val ledger =
            ResponseLedger(
                responses =
                    listOf(
                        ResponseRecord(id = "responseA", userIds = listOf(first.id)),
                        ResponseRecord(id = "responseB", userIds = listOf(followUp.id), serverFollowUp = true),
                    ),
            )
        for (owner in listOf("responseA", "responseB")) {
            val rows =
                planMainChatTimeline(
                    listOf(first, followUp),
                    null,
                    ledger.copy(activeId = owner),
                    waitingForFirstText = true,
                )
            assertEquals(listOf(MAIN_CHAT_WAITING_KEY, "user-second", "user-first"), rows.map { it.key })
        }
    }

    private fun user(
        id: String,
        text: String,
    ) = ChatMessage(id = id, role = MessageRole.USER, content = text)

    private fun assistant(
        id: String,
        text: String,
        streaming: Boolean = false,
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = text, isStreaming = streaming)

    private fun tool(
        id: String,
        name: String,
    ) = ChatMessage(id = id, role = MessageRole.TOOL, content = name, toolName = name)

    @Test
    fun directive_separateTopicsNeverInterruptActiveTurn() {
        assertEquals(BusyTurnAction.SEPARATE, BusyTurnDirective.classify("明日の天気教えて"))
        assertEquals(BusyTurnAction.SEPARATE, BusyTurnDirective.classify("Bのサーバーを止めて確認して"))
        assertEquals(BusyTurnAction.SEPARATE, BusyTurnDirective.classify("別件だけどREADMEも見て"))
        assertEquals(BusyTurnAction.SEPARATE, BusyTurnDirective.classify("前の指示を変更すると何が起きる？"))
        assertEquals(BusyTurnAction.SEPARATE, BusyTurnDirective.classify("Can you also check deployment logs?"))
    }

    @Test
    fun directive_explicitStopAndAmendAreRecognized() {
        assertEquals(BusyTurnAction.STOP, BusyTurnDirective.classify("止めて"))
        assertEquals(BusyTurnAction.STOP, BusyTurnDirective.classify("中止して！"))
        assertEquals(BusyTurnAction.STOP, BusyTurnDirective.classify("さっきの止めて。"))
        assertEquals(BusyTurnAction.STOP, BusyTurnDirective.classify("Stop"))
        assertEquals(BusyTurnAction.STOP, BusyTurnDirective.classify("cancel"))

        assertEquals(BusyTurnAction.AMEND, BusyTurnDirective.classify("今の修正: JSONで出して"))
        assertEquals(BusyTurnAction.AMEND, BusyTurnDirective.classify("さっきの条件を変更して、3件だけにして"))
        assertEquals(BusyTurnAction.AMEND, BusyTurnDirective.classify("anything", explicitSteer = true))
    }

    @Test
    fun turns_replyForAArrivingAfterBStaysAnchoredUnderA() {
        val uA = user("uA", "Aの依頼")
        val a1 = assistant("a1", "Aの途中経過")
        val uB = user("uB", "別件B")
        val aTool = tool("aTool", "search")
        val aFinal = assistant("aFinal", "Aの最終回答")

        val ledger =
            ResponseLedger(
                responses =
                    listOf(
                        ResponseRecord(
                            id = "respA",
                            userIds = listOf(uA.id),
                            messageIds = listOf(a1.id, aTool.id, aFinal.id),
                            finishedAt = 100L,
                        ),
                        ResponseRecord(
                            id = "respB",
                            userIds = listOf(uB.id),
                            serverFollowUp = true,
                        ),
                    ),
                activeId = "respA",
                requiresCorrelation = true,
            )

        val turns = groupIntoTurns(listOf(uA, a1, uB, aTool, aFinal), ledger)
        assertEquals(3, turns.size)
        assertEquals(ChatTurn.User(uA), turns[0])
        val agentA = turns[1] as ChatTurn.Agent
        assertEquals(
            listOf(AgentEntry.Prose(a1), AgentEntry.ToolRow(aTool), AgentEntry.Prose(aFinal)),
            agentA.entries,
        )
        assertEquals(ChatTurn.User(uB), turns[2])
    }

    @Test
    fun turns_liveStreamForAStaysAboveBAndUpdatesInRenderCache() {
        val uA = user("uA", "Aの依頼")
        val uB = user("uB", "別件B")
        val live1 = assistant("liveA", "Aを回答中", streaming = true)
        val live2 = live1.copy(content = "Aを回答中…続き")

        val ledger =
            ResponseLedger(
                responses =
                    listOf(
                        ResponseRecord(id = "respA", userIds = listOf(uA.id)),
                        ResponseRecord(id = "respB", userIds = listOf(uB.id), serverFollowUp = true),
                    ),
                activeId = "respA",
                requiresCorrelation = true,
            )

        val messages = listOf(uA, uB)
        val turns = groupIntoTurnsWithStreaming(messages, live1, ledger)
        assertEquals(3, turns.size)
        assertEquals(ChatTurn.User(uA), turns[0])
        assertEquals(ChatTurn.Agent(listOf(AgentEntry.Prose(live1))), turns[1])
        assertEquals(ChatTurn.User(uB), turns[2])

        val cache = RenderPlanningCache()
        val lifecycle = ReplyLifecycle(ledger = ledger)
        val plan1 = cache.plan(messages, live1, lifecycle, "s1", 0, emptySet())
        val plan2 = cache.plan(messages, live2, lifecycle, "s1", 0, emptySet())
        assertEquals(3, plan1.turns.size)
        assertEquals(ChatTurn.Agent(listOf(AgentEntry.Prose(live2))), plan2.turns[1])
        assertEquals(ChatTurn.User(uB), plan2.turns[2])
    }

    @Test
    fun turns_earlyReplyForBWhileAContinuesKeepsBothIsolated() {
        val uA = user("uA", "重い作業A")
        val a1 = assistant("a1", "Aをバックグラウンド開始")
        val uB = user("uB", "軽い質問B")
        val bFinal = assistant("bFinal", "Bの回答")
        val a2 = assistant("a2", "Aの完了報告")

        val ledger =
            ResponseLedger(
                responses =
                    listOf(
                        ResponseRecord(
                            id = "respA",
                            userIds = listOf(uA.id),
                            messageIds = listOf(a1.id, a2.id),
                        ),
                        ResponseRecord(
                            id = "respB",
                            userIds = listOf(uB.id),
                            serverFollowUp = true,
                            messageIds = listOf(bFinal.id),
                            finishedAt = 200L,
                        ),
                    ),
                activeId = "respA",
                requiresCorrelation = true,
            )

        val turns = groupIntoTurns(listOf(uA, a1, uB, bFinal, a2), ledger)
        assertEquals(4, turns.size)
        assertEquals(ChatTurn.User(uA), turns[0])
        assertEquals(
            listOf(AgentEntry.Prose(a1), AgentEntry.Prose(a2)),
            (turns[1] as ChatTurn.Agent).entries,
        )
        assertEquals(ChatTurn.User(uB), turns[2])
        assertEquals(
            listOf(AgentEntry.Prose(bFinal)),
            (turns[3] as ChatTurn.Agent).entries,
        )
    }
}
