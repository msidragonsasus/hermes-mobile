package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalWindowProofTest {
    private fun checkWindow(
        allowed: Boolean = true,
        final: Long? = 13L,
        rootAlias: Boolean = true,
        controlKind: String? = "steer",
        foreignClaim: Boolean = false,
        expected: Boolean,
    ) {
        val user = ChatMessage(id = "origin", role = MessageRole.USER, content = "original")
        val interim = ChatMessage(id = "interim", role = MessageRole.ASSISTANT, content = "old prefix")
        val steer = ChatMessage(id = "control", role = MessageRole.USER, content = "control", displayKind = "steer")
        val answer = ChatMessage(id = "final", role = MessageRole.ASSISTANT, content = "canonical final")
        val response =
            ResponseRecord(
                id = "reply",
                messageIds = listOf(interim.id, answer.id),
                userIds = listOf(user.id, steer.id),
                userRowIds = listOf(10L, 12L),
                canonicalRowIds = listOf(10L, 13L),
                canonicalComplete = false,
                canonicalFinalAssistantRowId = final,
                canonicalWindowAllowed = allowed,
                finishedAt = 1000L,
                authority =
                    acceptedAuthorityFixture(
                        if (rootAlias) 10L else null,
                        final,
                        listOf(10L, 13L),
                        1000L,
                        listOf(interim.id, answer.id),
                        safe = allowed,
                    ),
            )
        val aliases = mapOf("12" to steer.id, "13" to answer.id) + if (rootAlias) mapOf("10" to user.id) else emptyMap()
        val ledger =
            ResponseLedger(
                responses =
                    listOf(response) +
                        if (foreignClaim) {
                            listOf(
                                ResponseRecord(
                                    id = "other",
                                    canonicalRowIds = listOf(11L),
                                    coverage = false,
                                    authority = acceptedAuthorityFixture(11L),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                aliases = aliases,
            )
        val raw =
            listOf(
                SessionMessage(id = 10, role = "user", content = JsonPrimitive("original")),
                SessionMessage(id = 11, role = "assistant", content = JsonPrimitive("complete canonical interim")),
                SessionMessage(id = 12, role = "user", content = JsonPrimitive("control"), display_kind = controlKind),
                SessionMessage(id = 13, role = "assistant", content = JsonPrimitive("canonical final")),
            )
        val result =
            reconcileTranscript(
                ChatUiState(messages = listOf(user, interim, steer, answer), responseLedger = ledger),
                "target",
                "session",
                raw,
                0,
                true,
            )
        val restored = result.responseLedger.responses.single { it.id == response.id }
        assertEquals(expected, restored.canonicalWindowVerified)
        assertFalse("A partial wire receipt must never be upgraded", restored.canonicalComplete)
        assertTrue(
            "Canonical prose stays accessible regardless of proof",
            result.messages.any {
                it.content ==
                    "complete canonical interim"
            },
        )
        if (expected) {
            assertTrue(restored.coverage)
            assertEquals(
                listOf("complete canonical interim", "canonical final"),
                result.messages
                    .filter {
                        it.role ==
                            MessageRole.ASSISTANT
                    }.map { it.content },
            )
        } else {
            assertFalse(
                "Unbound prose within the known response span makes a whole-copy claim unsafe",
                restored.coverage,
            )
        }
    }

    @Test fun typedSteerInsideExactOriginToExplicitFinalWindowHasIndependentCoverage() = checkWindow(expected = true)

    @Test fun unsafeTerminalCannotAuthorizeWindowCoverage() = checkWindow(allowed = false, expected = false)

    @Test fun lastReceiptAddressCannotSubstituteForMissingExplicitFinal() = checkWindow(final = null, expected = false)

    @Test fun anOriginWithoutAnExactAckAliasCannotAuthorizeCoverage() = checkWindow(rootAlias = false, expected = false)

    @Test fun ordinaryUserInsideTheWindowIsNotInventedAsASteer() = checkWindow(controlKind = null, expected = false)

    @Test fun aWindowCannotConsumeAnotherResponsesProvenAddress() = checkWindow(foreignClaim = true, expected = false)
}
