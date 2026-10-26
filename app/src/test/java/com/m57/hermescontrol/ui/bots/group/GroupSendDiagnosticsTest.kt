package com.m57.hermescontrol.ui.bots.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSendDiagnosticsTest {
    private val scope = "https://user:USER_SECRET@host.test:9119/proxy/?token=QUERY_SECRET#FRAGMENT_SECRET\u0000profile"
    private val message = GroupChatMessage("user", "user", "PRIVATE_NAME", true, text = "PRIVATE_BODY", timestamp = 0)
    private val boss = GroupTurnRecord("turn-boss", "user", "reply-boss", "boss", "runtime-boss", "stored-boss", "HASH")
    private val idea =
        GroupTurnRecord("turn-idea", "user", "reply-idea", "idea1", "runtime-idea", "stored-idea", "HASH")

    @Test
    fun selectedBotExportIsAnExplicitWhitelistWithNoContentOrOtherBot() {
        val conversation =
            GroupConversation(
                title = "PRIVATE_TITLE",
                draft = "PRIVATE_DRAFT",
                messages = listOf(message, message.copy(id = "other", text = "OTHER_BODY")),
                turns = listOf(boss, idea, idea.copy(id = "other-turn", userMessageId = "other")),
            )
        val before = conversation.copy()
        val snapshot = requireNotNull(groupDiagnosticSnapshot(conversation, "user", scope))
        assertEquals(listOf("boss", "idea1"), snapshot.choices.map { it.diagnostic.bot })
        val text =
            snapshot.choices
                .single { it.turnId == boss.id }
                .diagnostic
                .copyText()
        val fields = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            setOf(
                "schema",
                "bot",
                "runtime_session_id",
                "stored_session_id",
                "delivery",
                "message_recorded_at_utc",
                "turn_saved_at",
                "recorded_submit_ack",
                "endpoint_provenance",
                "endpoint",
            ),
            fields.keys,
        )
        assertEquals("boss", fields["bot"]!!.jsonPrimitive.content)
        assertEquals("stored-boss", fields["stored_session_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("host", "port", "base_path"), fields["endpoint"]!!.jsonObject.keys)
        for (forbidden in listOf("PRIVATE", "SECRET", "HASH", "idea1", "runtime-idea", "stored-idea", "other-turn")) {
            assertFalse(forbidden, text.contains(forbidden))
        }
        assertEquals(before, conversation)
    }

    @Test
    fun urlComponentsExcludeAllUserinfoQueryAndFragment() {
        assertEquals(
            GroupDiagnosticEndpoint("host.test", 9119, "/proxy/"),
            groupDiagnosticEndpoint(scope.substringBefore('\u0000')),
        )
        assertEquals(GroupDiagnosticEndpoint("::1", 443, "/"), groupDiagnosticEndpoint("https://[::1]/?ticket=SECRET"))
        assertNull(groupDiagnosticEndpoint("file:///PRIVATE"))
        assertNull(groupDiagnosticEndpoint("not a URL SECRET"))
    }

    @Test
    fun encodedSensitivePathAndControlCharactersAreNotExported() {
        for (path in listOf("/%74oken/PATH_SECRET", "/proxy/secret-value", "/%0aPRIVATE")) {
            assertNull(groupDiagnosticEndpoint("https://host.test$path")!!.path)
        }
    }

    @Test
    fun assistantMissingOrAmbiguousPostsDoNotYieldMetadata() {
        assertNull(
            groupDiagnosticSnapshot(GroupConversation(messages = listOf(message.copy(isUser = false))), "user", scope),
        )
        assertNull(groupDiagnosticSnapshot(GroupConversation(messages = listOf(message)), "missing", scope))
        assertNull(groupDiagnosticSnapshot(GroupConversation(messages = listOf(message, message)), "user", scope))
        val ambiguous = GroupConversation(messages = listOf(message), turns = listOf(boss, idea.copy(id = boss.id)))
        assertTrue(groupDiagnosticSnapshot(ambiguous, "user", scope)!!.choices.isEmpty())
    }

    @Test
    fun legacyPostDoesNotInventIdsAndCannotSelectAnotherPostsTurn() {
        val conversation =
            GroupConversation(messages = listOf(message), turns = listOf(boss.copy(userMessageId = "different")))
        assertTrue(groupDiagnosticSnapshot(conversation, "user", scope)!!.choices.isEmpty())
    }

    @Test
    fun timestampAndAckProvenanceAreHonestAndUnsafeIdsRemainMissing() {
        val turn =
            boss.copy(
                runtimeSessionId = "runtime?token=ID_SECRET",
                storedSessionId = null,
                delivery = GroupDelivery.ACCEPTED,
            )
        val conversation = GroupConversation(messages = listOf(message), turns = listOf(turn))
        val text =
            groupDiagnosticSnapshot(
                conversation,
                "user",
                "invalid endpoint",
            )!!.choices.single().diagnostic.copyText()
        val fields = Json.parseToJsonElement(text).jsonObject
        assertEquals("1970-01-01T00:00:00Z", fields["message_recorded_at_utc"]!!.jsonPrimitive.content)
        for (key in listOf(
            "turn_saved_at",
            "recorded_submit_ack",
            "runtime_session_id",
            "stored_session_id",
            "endpoint",
        )) {
            assertEquals("not_recorded", fields[key]!!.jsonPrimitive.content)
        }
        assertFalse(text.contains("ID_SECRET"))
        assertEquals("ACCEPTED", fields["delivery"]!!.jsonPrimitive.content)
    }

    @Test
    fun identicalMessageIdsInDifferentConversationsHaveDifferentScopeTagsAndIndependentIds() {
        val first = GroupConversation(id = "first", messages = listOf(message), turns = listOf(boss))
        val second = first.copy(id = "second", turns = listOf(idea))
        val a = groupDiagnosticSnapshot(first, "user", scope)!!
        val b = groupDiagnosticSnapshot(second, "user", scope)!!
        assertFalse(a.scopeTag == b.scopeTag)
        assertEquals(
            "stored-boss",
            a.choices
                .single()
                .diagnostic.storedId,
        )
        assertEquals(
            "stored-idea",
            b.choices
                .single()
                .diagnostic.storedId,
        )
        assertFalse(a.scopeTag == groupDiagnosticSnapshot(first, "user", "https://other.test")!!.scopeTag)
    }
}
