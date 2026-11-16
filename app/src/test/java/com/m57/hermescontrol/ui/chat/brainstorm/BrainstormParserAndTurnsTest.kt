package com.m57.hermescontrol.ui.chat.brainstorm

import com.m57.hermescontrol.data.model.BrainstormSnapshot
import com.m57.hermescontrol.data.model.BrainstormSpeakerSnapshot
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.CodeFenceRenderKind
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.hasActivity
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import com.m57.hermescontrol.ui.chat.fullbleed.visibleWhen
import com.m57.hermescontrol.ui.chat.resolveCodeFenceRenderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainstormParserAndTurnsTest {
    @Test
    fun parsesStructuredBrainstormCompletionWithNullToolNameAndIsolatedSpeakerFailure() {
        val rawJson =
            """
            {
              "type": "hermes.brainstorm.v1",
              "brainstorm_id": "bs-100",
              "session_id": "sess-main",
              "status": "partial",
              "speakers": [
                {
                  "profile": "researcher",
                  "name": "Research Scout",
                  "avatar": {"shape": "circle", "color": "#2563EB", "icon": "science"},
                  "status": "settled",
                  "reply": "## Findings\nPoint 1"
                },
                {
                  "profile": "critic",
                  "name": "Risk Critic",
                  "status": "failed",
                  "error": "Tool timeout"
                }
              ],
              "summary_input": "Source input only"
            }
            """.trimIndent()
        val msg =
            ChatMessage(
                id = "tool-bs-complete",
                role = MessageRole.TOOL,
                content = rawJson,
                toolName = null,
            )
        assertTrue(BrainstormParser.isBrainstormToolMessage(msg))
        assertEquals("bs-100", BrainstormParser.extractBrainstormId(msg))

        val parsed = BrainstormParser.parseFromToolMessage(msg)
        assertNotNull(parsed)
        assertEquals("bs-100", parsed?.id)
        assertEquals("partial", parsed?.status)
        assertEquals(2, parsed?.speakers?.size)
        assertEquals("Research Scout", parsed?.speakers?.get(0)?.displayName)
        assertEquals("settled", parsed?.speakers?.get(0)?.status)
        assertEquals("failed", parsed?.speakers?.get(1)?.status)
        assertEquals("Tool timeout", parsed?.speakers?.get(1)?.error)
        assertEquals("Source input only", parsed?.summary_input)
    }

    @Test
    fun parsesFanOutAckAndPresentationErrorFallbackAndPreservesUnknownOnMerge() {
        val ackJson =
            """
            {
              "brainstorm_id": "bs-200",
              "status": "queued",
              "fan_out": [
                {"to": "@scout", "delivery_id": "del-1", "status": "queued"},
                {"to": "@dev", "delivery_id": "del-2", "status": "queued"}
              ]
            }
            """.trimIndent()
        val ackMsg =
            ChatMessage(
                id = "tool-bs-ack",
                role = MessageRole.TOOL,
                content = ackJson,
                toolName = "message_agent",
                isStreaming = true,
            )
        val ackSnap = BrainstormParser.parseFromToolMessage(ackMsg)
        assertEquals("running", ackSnap?.status)
        assertEquals(listOf("scout", "dev"), ackSnap?.speakers?.map { it.profile })

        val degradedJson =
            """
            {
              "brainstorm_id": "bs-200",
              "presentation_error": "brainstorm_snapshot_unavailable",
              "replies": [
                {"to": "@scout", "reply": "Done", "status": "settled"},
                {"to": "@dev", "error": "Crashed"}
              ]
            }
            """.trimIndent()
        val degradedSnap =
            BrainstormParser.parseFromToolMessage(
                ChatMessage(id = "tool-bs-degraded", role = MessageRole.TOOL, content = degradedJson),
            )
        assertEquals("brainstorm_snapshot_unavailable", degradedSnap?.presentation_error)
        assertEquals("partial", degradedSnap?.status)
        assertEquals("failed", degradedSnap?.speakers?.get(1)?.status)

        val unknownFromServer =
            BrainstormSnapshot(
                id = "bs-200",
                status = "unknown",
                speakers = listOf(BrainstormSpeakerSnapshot(profile = "scout", name = "Scout", status = "pending")),
            )
        val mergedUnknown = BrainstormParser.mergeSnapshot(ackSnap, unknownFromServer)
        assertEquals("unknown", mergedUnknown?.status)
        // A stale "running" update must not overwrite "unknown"
        val mergedStaleRunning = BrainstormParser.mergeSnapshot(mergedUnknown, ackSnap)
        assertEquals("unknown", mergedStaleRunning?.status)
    }

    @Test
    fun brainstormToolRowNeverFoldsBehindActivityAndDeduplicatesByBrainstormId() {
        val ackMsg =
            ChatMessage(
                id = "tool-ack",
                role = MessageRole.TOOL,
                content = """{"brainstorm_id":"bs-1","fan_out":[{"to":"@scout","status":"queued"}]}""",
                toolName = "message_agent",
            )
        val bashMsg =
            ChatMessage(
                id = "tool-bash",
                role = MessageRole.TOOL,
                content = "ok",
                toolName = "terminal",
            )
        val finalMsg =
            ChatMessage(
                id = "tool-final",
                role = MessageRole.TOOL,
                content =
                    """{"type":"hermes.brainstorm.v1","brainstorm_id":"bs-1","status":"completed",""" +
                        """"speakers":[{"profile":"scout","name":"Scout","status":"settled","reply":"Done"}]}""",
                toolName = "message_agent",
            )
        val synthesisMsg =
            ChatMessage(
                id = "answer-1",
                role = MessageRole.ASSISTANT,
                content = "Main Hermes synthesis below.",
            )

        // Pure brainstorm turn does not show "推論・ツール" fold row
        val pureTurns = groupIntoTurns(listOf(finalMsg, synthesisMsg))
        val pureAgent = pureTurns.single() as ChatTurn.Agent
        assertFalse(pureAgent.hasActivity())
        assertTrue(AgentEntry.ToolRow(finalMsg).visibleWhen(activityExpanded = false))

        // Duplicate brainstorm rows drop earlier ack while keeping latest tool-final and accurate indices
        val mixedMessages = listOf(ackMsg, bashMsg, finalMsg, synthesisMsg)
        val mixedTurns = groupIntoTurns(mixedMessages)
        val duplicateIds = BrainstormParser.duplicateBrainstormMessageIds(mixedTurns)
        assertEquals(setOf("tool-ack"), duplicateIds)

        val mixedAgent = mixedTurns.single() as ChatTurn.Agent
        assertTrue(mixedAgent.hasActivity())
        val indices = messageIdToLazyIndex(mixedTurns)
        // Collapsed: [0] ActivityRow, [1] tool-final (brainstorm stays visible!), [2] answer-1 (synthesis)
        assertNull(indices["tool-ack"])
        assertNull(indices["tool-bash"])
        assertEquals(1, indices["tool-final"])
        assertEquals(2, indices["answer-1"])

        val expandedIndices = messageIdToLazyIndex(mixedTurns, expandedTurns = setOf("tool-ack"))
        // tabKey=tool-ack; expanding reveals tool-bash (1) and shifts tool-final -> 2, answer-1 -> 3
        assertNull(expandedIndices["tool-bash"])
        assertEquals(2, expandedIndices["tool-final"])
        assertEquals(3, expandedIndices["answer-1"])
    }

    @Test
    fun brainstormSpeakerMarkdownDisablesInteractiveHtmlAndHermesUi() {
        listOf("hermes-ui-v2", "hermes-ui", "html", "svg", "mermaid").forEach { lang ->
            assertEquals(
                CodeFenceRenderKind.CODE_BLOCK,
                resolveCodeFenceRenderKind(language = lang, complete = true, allowInteractiveContent = false),
            )
        }
        assertEquals(
            CodeFenceRenderKind.NATIVE_UI,
            resolveCodeFenceRenderKind(language = "hermes-ui-v2", complete = true, allowInteractiveContent = true),
        )
        assertEquals(
            CodeFenceRenderKind.INTELLIGENT_UI,
            resolveCodeFenceRenderKind(language = "html", complete = true, allowInteractiveContent = true),
        )
    }
}
