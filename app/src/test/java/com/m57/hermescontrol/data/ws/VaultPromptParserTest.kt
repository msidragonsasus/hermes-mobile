package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPromptParserTest {
    @Test
    fun serverRequestWithIdIsNotAnRpcResult() {
        val rpc =
            OkHttpProvider.json.decodeFromString<JsonRpcResponse>(
                """{"jsonrpc":"2.0","id":"srq-login","method":"vault.save_login","params":{"session_id":"chat","origin":"https://example.test","site":"Example"}}""",
            )
        val event = EventParser.parse(rpc) as WsEvent.VaultRequest
        assertEquals("srq-login", event.request.id)
        assertEquals("chat", event.request.sessionId)
        assertEquals(VaultPromptKind.LOGIN, event.request.kind)
        assertEquals("https://example.test", event.request.origin)
    }

    @Test
    fun codeUnlockAndCancellationUseSameRequestIdentity() {
        for (kind in VaultPromptKind.entries) {
            val event =
                EventParser.parseServerRequest(
                    "id",
                    kind.method,
                    mapOf("session_id" to "chat"),
                ) as WsEvent.VaultRequest
            assertEquals(kind, event.request.kind)
        }
        assertEquals(
            WsEvent.ServerRequestCancel("id", "chat"),
            EventParser.parseParams(
                mapOf(
                    "type" to "request.cancel",
                    "session_id" to "chat",
                    "payload" to mapOf("id" to "id"),
                ),
            ),
        )
        assertTrue(
            EventParser.parseServerRequest(
                "id",
                "vault.save_login",
                emptyMap<String, Any>(),
            ) is WsEvent.UnsupportedServerRequest,
        )
        assertTrue(
            EventParser.parseServerRequest(
                "id",
                "future.prompt",
                mapOf("session_id" to "chat"),
            ) is WsEvent.UnsupportedServerRequest,
        )
    }

    @Test
    fun approvalServerRequestBecomesApprovalCard() {
        val rpc =
            OkHttpProvider.json.decodeFromString<JsonRpcResponse>(
                """{"jsonrpc":"2.0","id":"srq-approval","method":"approval","params":{"session_id":"chat","request_id":"req-1","command":"rm -rf build","description":"dangerous","choices":["once","deny"]}}""",
            )
        val event = EventParser.parse(rpc) as WsEvent.ApprovalRequest
        assertEquals("chat", event.sessionId)
        assertEquals("req-1", event.requestId)
        assertEquals("rm -rf build", event.command)
        assertEquals(listOf("once", "deny"), event.choices)
        assertTrue(
            EventParser.parseServerRequest(
                "srq-approval",
                "approval",
                mapOf("session_id" to "chat"),
            ) is WsEvent.UnsupportedServerRequest,
        )
    }

    @Test
    fun singleClarifyServerRequestBecomesQuestionCard() {
        val rpc =
            OkHttpProvider.json.decodeFromString<JsonRpcResponse>(
                """{"jsonrpc":"2.0","id":"srq-q1","method":"clarify","params":{"session_id":"chat","question":"どちらにしますか?","choices":["A","B"]}}""",
            )
        val event = EventParser.parse(rpc) as WsEvent.ClarifyRequest
        assertEquals("srq-q1", event.clarifyId)
        assertEquals("chat", event.sessionId)
        assertEquals("どちらにしますか?", event.text)
        assertEquals(listOf("A", "B"), event.options)
        assertEquals(null, event.questionId)
        assertTrue(event.questions.isEmpty())
    }

    @Test
    fun batchClarifyServerRequestKeepsQuestionIds() {
        val event =
            EventParser.parseServerRequest(
                "srq-q2",
                "clarify",
                mapOf(
                    "session_id" to "chat",
                    "answers" to mapOf("a" to "x", "unknown" to "ignored"),
                    "questions" to
                        listOf(
                            mapOf("qid" to "a", "question" to "一つ目", "choices" to listOf("x"), "multi_select" to false),
                            mapOf(
                                "qid" to "b",
                                "question" to "二つ目",
                                "choices" to listOf("y", "z"),
                                "multi_select" to true,
                            ),
                        ),
                ),
            ) as WsEvent.ClarifyRequest
        assertEquals(listOf("a", "b"), event.questions.map { it.qid })
        assertEquals(mapOf("a" to "x"), event.lockedAnswers)
        assertTrue(event.questions[1].multiSelect)
        assertEquals("srq-q2", event.clarifyId)
    }

    @Test
    fun clarifyServerRequestWithoutSessionOrQuestionIsRejected() {
        assertTrue(
            EventParser.parseServerRequest("srq-q3", "clarify", mapOf("question" to "x")) is
                WsEvent.UnsupportedServerRequest,
        )
        assertTrue(
            EventParser.parseServerRequest("srq-q3", "clarify", mapOf("session_id" to "chat")) is
                WsEvent.UnsupportedServerRequest,
        )
    }

    @Test
    fun resolvedClarifyRequestIsDistinctFromExpiry() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "request.cancel",
                    "session_id" to "chat",
                    "payload" to mapOf("id" to "srq-q1", "method" to "clarify", "reason" to "resolved"),
                ),
            )
        assertEquals(WsEvent.ClarifyResolved("srq-q1", "chat"), event)
    }

    @Test
    fun cancelledClarifyRequestExpiresTheCard() {
        val rpc =
            OkHttpProvider.json.decodeFromString<JsonRpcResponse>(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"chat","payload":{"id":"srq-q1","method":"clarify","reason":"timeout"}}}""",
            )
        val event = EventParser.parse(rpc) as WsEvent.ClarifyExpire
        assertEquals("srq-q1", event.clarifyId)
        assertEquals("chat", event.sessionId)
    }
}
