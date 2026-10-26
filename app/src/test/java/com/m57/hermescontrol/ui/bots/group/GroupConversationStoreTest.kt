package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupConversationStoreTest {
    private val prompt = "synthetic exact request"
    private val turn = GroupTurnRecord("turn", "user", "reply", "bot", "runtime", "stored", groupPromptHash(prompt))

    @Test
    fun archiveRoundTripRetainsLongHistoryPendingTurnsAndDraft() {
        val messages =
            (1..70).map { index ->
                GroupChatMessage(
                    "$index",
                    "user",
                    "You",
                    true,
                    text = "synthetic $index",
                    deliveries =
                        mapOf("bot" to GroupDelivery.UNKNOWN),
                )
            }
        val first = GroupConversation(messages = messages, turns = listOf(turn), draft = "synthetic draft")
        val second = GroupConversation()
        val archive =
            GroupConversationArchive(activeId = second.id, conversations = listOf(first, second), legacyImported = true)
        assertEquals(archive, Json.decodeFromString<GroupConversationArchive>(Json.encodeToString(archive)))
        assertEquals(
            70,
            archive.conversations
                .first()
                .messages.size,
        )
    }

    @Test
    fun processRecoveryDistinguishesNotAttemptedAndUnknown() {
        val message =
            GroupChatMessage(
                "user",
                "user",
                "You",
                true,
                text = prompt,
                deliveries =
                    mapOf(
                        "pending" to GroupDelivery.PENDING,
                        "accepted" to GroupDelivery.ACCEPTED,
                        "unknown" to GroupDelivery.UNKNOWN,
                        "done" to GroupDelivery.COMPLETE,
                    ),
            )
        val room =
            GroupConversation(messages = listOf(message), turns = listOf(turn.copy(delivery = GroupDelivery.ACCEPTED)))
        val recovered = room.recoverInterrupted()
        assertEquals(GroupDelivery.NOT_SENT, recovered.messages.first().deliveries["pending"])
        assertEquals(GroupDelivery.UNKNOWN, recovered.messages.first().deliveries["accepted"])
        assertEquals(GroupDelivery.UNKNOWN, recovered.turns.first().delivery)
        assertEquals(GroupDelivery.COMPLETE, recovered.messages.first().deliveries["done"])
        assertEquals(recovered, recovered.recoverInterrupted())
    }

    @Test
    fun exactRequestAndTerminalAssistantAreRequired() {
        val request = SessionMessage(role = "user", content = JsonPrimitive(prompt))
        val response = SessionMessage(role = "assistant", content = JsonPrimitive("synthetic final"))
        assertEquals(GroupDelivery.UNKNOWN, reconcileGroupTurn(turn, emptyList()).delivery)
        assertEquals(GroupDelivery.UNKNOWN, reconcileGroupTurn(turn, listOf(response)).delivery)
        assertEquals(GroupDelivery.ACCEPTED, reconcileGroupTurn(turn, listOf(request)).delivery)
        assertEquals(GroupDelivery.COMPLETE, reconcileGroupTurn(turn, listOf(request, response)).delivery)
        assertEquals("synthetic final", reconcileGroupTurn(turn, listOf(request, response)).reply)
        assertEquals(
            GroupDelivery.UNKNOWN,
            reconcileGroupTurn(
                turn,
                listOf(request.copy(content = JsonPrimitive("different request")), response),
            ).delivery,
        )
    }

    @Test
    fun repeatedPromptCannotBindEitherTurnsReply() {
        val request = SessionMessage(role = "user", content = JsonPrimitive(prompt))
        val first = SessionMessage(role = "assistant", content = JsonPrimitive("first reply"))
        val second = SessionMessage(role = "assistant", content = JsonPrimitive("second reply"))
        val history = listOf(request, first, request, second)
        assertEquals(GroupDelivery.UNKNOWN, reconcileGroupTurn(turn, history).delivery)
        assertEquals(null, reconcileGroupTurn(turn, history).reply)
        assertEquals(GroupDelivery.UNKNOWN, reconcileGroupTurn(turn.copy(id = "later-turn"), history).delivery)
    }

    @Test
    fun toolCallAndSubsequentUserNeverBecomeCompletionEvidence() {
        val request = SessionMessage(role = "user", content = JsonPrimitive(prompt))
        val tool =
            SessionMessage(
                role = "assistant",
                content = JsonPrimitive("working"),
                tool_calls = JsonArray(listOf(JsonPrimitive("tool"))),
            )
        val response = SessionMessage(role = "assistant", content = JsonPrimitive("final"))
        assertEquals(GroupDelivery.ACCEPTED, reconcileGroupTurn(turn, listOf(request, tool)).delivery)
        assertEquals(
            GroupDelivery.ACCEPTED,
            reconcileGroupTurn(
                turn,
                listOf(request, request.copy(content = JsonPrimitive("other request")), response),
            ).delivery,
        )
    }

    @Test
    fun scopedArchivesAndNewConversationsNeverReuseHistoryOrRoutes() {
        val store = MemoryGroupConversationStore()
        val original =
            GroupConversation(
                messages = listOf(GroupChatMessage("user", "user", "You", true, text = prompt)),
                turns = listOf(turn),
            )
        val fresh = GroupConversation()
        store.write(
            "server-a/profile/group",
            GroupConversationArchive(activeId = fresh.id, conversations = listOf(original, fresh)),
        )
        assertEquals(null, store.read("server-b/profile/group"))
        assertTrue(
            store
                .read("server-a/profile/group")!!
                .conversations
                .last()
                .messages
                .isEmpty(),
        )
        assertTrue(
            store
                .read("server-a/profile/group")!!
                .conversations
                .last()
                .turns
                .isEmpty(),
        )
        assertFalse(original.id == fresh.id)
    }
}
