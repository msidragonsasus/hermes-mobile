package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAcceptanceRegressionTest {
    private val target = "acceptance-target"
    private val session = "acceptance-session"

    private fun row(
        id: Int?,
        role: String,
        text: String,
        kind: String? = null,
    ) = SessionMessage(
        id = id,
        role = role,
        content = JsonPrimitive(text),
        display_kind = kind,
        timestamp = JsonPrimitive(1.0),
    )

    private fun local(): ChatUiState {
        val user = ChatMessage(id = "origin", role = MessageRole.USER, content = "original", timestamp = 1000L)
        val prefix = ChatMessage(id = "prefix", role = MessageRole.ASSISTANT, content = "old prefix", timestamp = 1000L)
        val final =
            ChatMessage(id = "final", role = MessageRole.ASSISTANT, content = "canonical final", timestamp = 1000L)
        return ChatUiState(
            messages = listOf(user, prefix, final),
            responseLedger =
                ResponseLedger(
                    responses =
                        listOf(
                            ResponseRecord(
                                id = "A",
                                messageIds = listOf(prefix.id, final.id),
                                userIds = listOf(user.id),
                                userRowIds = listOf(10L),
                                canonicalRowIds = listOf(10L, 13L),
                                canonicalFinalAssistantRowId = 13L,
                                canonicalWindowAllowed = true,
                                finishedAt = 2000L,
                                authority =
                                    acceptedAuthorityFixture(
                                        10L,
                                        13L,
                                        listOf(10L, 13L),
                                        2000L,
                                        listOf(prefix.id, final.id),
                                    ),
                            ),
                        ),
                    aliases = mapOf("10" to user.id, "13" to final.id),
                ),
        )
    }

    private fun page() =
        listOf(
            row(10, "user", "original"),
            row(11, "assistant", "canonical interim"),
            row(12, "tool", "canonical tool"),
            row(13, "assistant", "canonical final"),
        )

    private fun reconcile(
        state: ChatUiState,
        raw: List<SessionMessage>,
        latest: Boolean = true,
    ) = reconcileTranscript(state, target, session, raw, 0, latest)

    private fun record(state: ChatUiState) = state.responseLedger.responses.single { it.id == "A" }

    private fun assertNoFooter(state: ChatUiState) {
        assertTrue(
            groupIntoTurns(state.messages).replyFooters(ReplyLifecycle(ledger = state.responseLedger)).all {
                it ==
                    null
            },
        )
    }

    private fun assertUnsafe(state: ChatUiState) {
        assertFalse("Unbound prose must withhold whole-response copy", record(state).coverage)
        assertFalse(record(state).canonicalWindowVerified)
        assertFalse("Wire complete=false is immutable", record(state).canonicalComplete)
        assertEquals(2000L, record(state).finishedAt)
        assertNoFooter(state)
    }

    @Test fun aSoleAckRootWithoutAnyReceiptWithholdsPrefixOnlyFooter() {
        val initial =
            local().let { state ->
                state.copy(
                    responseLedger =
                        state.responseLedger.update("A") {
                            it.copy(canonicalRowIds = emptyList(), canonicalFinalAssistantRowId = null)
                        },
                )
            }
        val result =
            reconcile(initial, listOf(row(10, "user", "original"), row(11, "assistant", "unknown continuation")))
        assertUnsafe(result)
        assertTrue(result.messages.containsAll(initial.messages))
        assertTrue(result.messages.any { it.content == "unknown continuation" })
        assertEquals(record(initial).messageIds, record(result).messageIds)
    }

    @Test fun nullAddressInsideTheWindowIsNotConfusedWithNullAddressOutside() {
        val result =
            reconcile(
                local(),
                listOf(
                    row(null, "assistant", "outside prose"),
                    row(10, "user", "original"),
                    row(null, "assistant", "inside prose"),
                    row(13, "assistant", "canonical final"),
                ),
            )
        assertUnsafe(result)
        assertTrue(result.messages.any { it.content == "outside prose" })
        assertTrue(result.messages.any { it.content == "inside prose" })
        assertTrue(result.messages.any { it.content == "old prefix" })
        assertEquals(listOf("prefix", "final"), record(result).messageIds)
    }

    @Test fun filteredReasoningRowsDoNotShiftRawProvenanceOrLoseReasoning() {
        val result =
            reconcile(
                local(),
                listOf(
                    row(null, "assistant", "outside prose"),
                    row(10, "user", "original"),
                    row(null, "assistant", "").copy(reasoning = JsonPrimitive("retained reasoning")),
                    row(null, "assistant", "inside prose"),
                    row(13, "assistant", "canonical final"),
                ),
            )
        assertUnsafe(result)
        assertEquals("retained reasoning", result.messages.single { it.content == "inside prose" }.reasoningText)
        assertTrue(result.messages.any { it.content == "outside prose" })
    }

    @Test fun aliasedCanonicalProseStillCarriesItsRawPosition() {
        val state =
            local().let {
                it.copy(
                    messages =
                        it.messages +
                            ChatMessage(
                                id = "unowned-alias",
                                role = MessageRole.ASSISTANT,
                                content = "unowned aliased prose",
                                timestamp = 1000L,
                            ),
                    responseLedger =
                        it.responseLedger.copy(
                            aliases = it.responseLedger.aliases + ("11" to "unowned-alias"),
                        ),
                )
            }
        val result =
            reconcile(state, listOf(row(10, "user", "original"), row(11, "assistant", "unowned aliased prose")))
        assertUnsafe(result)
        assertTrue(result.messages.any { it.content == "unowned aliased prose" })
    }

    @Test fun aReleasedAddressRetainsItsOwnershipEvidenceWithoutRawPositions() {
        val body =
            ChatMessage(
                id = canonicalMessageId(target, session, 11L),
                role = MessageRole.ASSISTANT,
                content = "released canonical prose",
                timestamp = 1000L,
            )
        val state =
            local().let { s ->
                s.copy(
                    stagedCanonicalMessages = listOf(body),
                    responseLedger =
                        s.responseLedger
                            .update("A") {
                                it.copy(
                                    canonicalRowIds = listOf(10L, 11L),
                                    canonicalFinalAssistantRowId = null,
                                    canonicalWindowAllowed = false,
                                )
                            }.copy(
                                canonicalRoles = mapOf(10L to "user", 11L to "assistant"),
                                canonicalVisibleRows = listOf(11L),
                            ),
                )
            }
        val result = reconcile(state, emptyList())
        assertUnsafe(result)
        assertTrue(result.messages.contains(body))
        assertTrue(result.stagedCanonicalMessages.isEmpty())
    }

    @Test fun danglingAliasCannotChangeThePageOrderOrDropItsBody() {
        val state =
            local().let {
                it.copy(
                    responseLedger =
                        it.responseLedger.copy(
                            aliases =
                                it.responseLedger.aliases + ("11" to "missing-local"),
                        ),
                )
            }
        val result = reconcile(state, listOf(row(10, "user", "original"), row(11, "assistant", "dangling alias prose")))
        assertUnsafe(result)
        assertTrue(result.messages.any { it.content == "dangling alias prose" })
    }

    @Test fun nullAddressOutsideAProvenWindowDoesNotRevokeValidCoverage() {
        val result = reconcile(local(), listOf(row(null, "assistant", "outside prose")) + page())
        assertTrue(record(result).canonicalWindowVerified)
        assertTrue(record(result).coverage)
        assertTrue(result.messages.any { it.content == "outside prose" })
        val footers =
            groupIntoTurns(
                result.messages,
            ).replyFooters(ReplyLifecycle(ledger = result.responseLedger)).filterNotNull()
        assertEquals(listOf("canonical interim\n\ncanonical final"), footers.map { it.text })
        assertEquals(listOf(2000L), footers.map { it.finishedAt })
    }

    @Test fun anotherNormalUserBoundsTheSingleRootScopeWithoutInventingMembership() {
        val state =
            local().let { s ->
                s.copy(
                    responseLedger =
                        s.responseLedger.update("A") {
                            it.copy(canonicalRowIds = emptyList(), canonicalFinalAssistantRowId = null)
                        },
                )
            }
        val result =
            reconcile(
                state,
                listOf(
                    row(10, "user", "original"),
                    row(20, "user", "other question"),
                    row(21, "assistant", "other answer"),
                ),
            )
        assertTrue(record(result).coverage)
        assertFalse(record(result).canonicalWindowVerified)
        assertEquals(record(state).messageIds, record(result).messageIds)
        assertTrue(result.messages.any { it.content == "other answer" })
    }

    private fun assertNoFallbackCollision(latest: Boolean) {
        val raw = listOf(row(1, "assistant", "addressed prose"), row(null, "assistant", "unaddressed prose"))
        val result = reconcile(ChatUiState(), raw, latest)
        assertEquals(listOf("addressed prose", "unaddressed prose"), result.messages.map { it.content })
        assertEquals(canonicalMessageId(target, session, 1L), result.messages.first().id)
        assertEquals(
            2,
            result.messages
                .map { it.id }
                .distinct()
                .size,
        )
        val repeated = reconcile(result, raw, latest)
        assertTrue(repeated.messages.containsAll(result.messages))
        assertEquals(1, repeated.messages.count { it.id == canonicalMessageId(target, session, 1L) })
    }

    @Test fun newestMapperFallbackCannotAliasARealRowAddress() = assertNoFallbackCollision(true)

    @Test fun legacyMapperKeepsDistinctProvenance() = assertNoFallbackCollision(false)

    @Test fun realAddressTenCannotCollideWithAMissingAddressAtRawIndexTen() {
        val raw =
            listOf(row(10, "assistant", "addressed ten")) +
                (1..9).map { row(100 + it, "assistant", "") } + row(null, "assistant", "unaddressed ten")
        val result = reconcile(ChatUiState(), raw)
        assertEquals(listOf("addressed ten", "unaddressed ten"), result.messages.map { it.content })
        assertEquals(canonicalMessageId(target, session, 10L), result.messages.first().id)
    }

    @Test fun missingAddressesOnLaterPagesCannotOverwriteEarlierObservationsAtTheSameIndex() {
        val first = reconcile(ChatUiState(), listOf(row(null, "assistant", "first missing-ID body")))
        val second = reconcile(first, listOf(row(null, "assistant", "second missing-ID body")))
        assertEquals(listOf("first missing-ID body", "second missing-ID body"), second.messages.map { it.content })
        assertTrue(second.messages.containsAll(first.messages))
        assertEquals(
            2,
            second.messages
                .map { it.id }
                .distinct()
                .size,
        )
    }

    @Test fun equalProseDoesNotProveTheIdentityOfMissingAddressObservations() {
        val first = reconcile(ChatUiState(), listOf(row(null, "assistant", "identical prose")))
        val second = reconcile(first, listOf(row(null, "assistant", "identical prose")))
        assertEquals(listOf("identical prose", "identical prose"), second.messages.map { it.content })
        assertEquals(
            2,
            second.messages
                .map { it.id }
                .distinct()
                .size,
        )
    }

    private fun withForeignClaim(
        state: ChatUiState,
        complete: Boolean = false,
    ): ChatUiState =
        state.copy(
            responseLedger =
                state.responseLedger.copy(
                    responses =
                        state.responseLedger.responses.map {
                            it.copy(canonicalComplete = complete)
                        } +
                            ResponseRecord(
                                id = "B",
                                canonicalRowIds = listOf(11L),
                                coverage = false,
                                authority = acceptedAuthorityFixture(20L, 21L, listOf(20L, 11L, 21L), time = 2001L),
                            ),
                ),
        )

    @Test fun aLateExplicitConflictRevokesPreviouslySuccessfulIndependentProof() {
        val verified = reconcile(local(), page())
        assertTrue(record(verified).canonicalWindowVerified)
        val result = reconcile(withForeignClaim(verified), emptyList())
        assertUnsafe(result)
        assertEquals(verified.messages, result.messages)
        assertEquals(listOf(10L, 11L, 12L, 13L), record(result).canonicalRowIds)
    }

    @Test fun merelyMissingLaterPageEvidencePreservesTheDurableProof() {
        val verified = reconcile(local(), page())
        val result = reconcile(verified, listOf(row(30, "assistant", "unrelated later prose")))
        assertTrue(record(result).canonicalWindowVerified)
        assertTrue(record(result).coverage)
        assertFalse(record(result).canonicalComplete)
        assertEquals(2000L, record(result).finishedAt)
        assertTrue(result.messages.containsAll(verified.messages))
        assertTrue(result.messages.any { it.content == "unrelated later prose" })
    }

    private fun assertConflictCannotReplace(complete: Boolean) {
        val verified = reconcile(local(), page())
        val extra =
            ChatMessage(
                id = "retained-local",
                role = MessageRole.ASSISTANT,
                content = "retained local prose",
                timestamp = 1000L,
            )
        val conflict =
            withForeignClaim(verified, complete).let { s ->
                s.copy(
                    messages = s.messages + extra,
                    responseLedger =
                        s.responseLedger.update("A") {
                            it.copy(messageIds = it.messageIds + extra.id)
                        },
                )
            }
        val result = reconcile(conflict, page())
        assertTrue("Conflicting ownership cannot authorize replacement", result.messages.contains(extra))
        assertFalse(record(result).coverage)
        assertFalse(record(result).canonicalWindowVerified)
        assertEquals(complete, record(result).canonicalComplete)
        assertEquals(2000L, record(result).finishedAt)
        assertTrue(page().all { r -> result.messages.any { it.content == r.contentText } })
        assertNoFooter(result)
    }

    @Test fun lateConflictCannotReplaceLocalBodiesUsingStaleIndependentProof() = assertConflictCannotReplace(false)

    @Test fun overlappingCompleteReceiptsCannotReplaceLocalBodies() = assertConflictCannotReplace(true)

    private fun assertConflictCannotConsume(complete: Boolean) {
        val verified = reconcile(local(), page())
        val staged = verified.messages.single { it.id == canonicalMessageId(target, session, 11L) }
        val conflict =
            withForeignClaim(verified, complete).copy(
                messages =
                    verified.messages.filterNot {
                        it.id in
                            setOf(
                                staged.id,
                                canonicalMessageId(target, session, 12L),
                            )
                    },
                stagedCanonicalMessages = listOf(staged),
            )
        val result = reconcile(conflict, emptyList())
        assertTrue(
            "A contradictory claim cannot keep fetched prose hidden in staging",
            result.messages.contains(staged),
        )
        assertTrue(result.stagedCanonicalMessages.isEmpty())
        assertFalse(record(result).coverage)
        assertFalse(record(result).canonicalWindowVerified)
        assertEquals(complete, record(result).canonicalComplete)
        assertNoFooter(result)
    }

    @Test fun lateConflictCannotConsumeWithStaleIndependentProof() = assertConflictCannotConsume(false)

    @Test fun overlappingCompleteReceiptsCannotConsumeFetchedBodies() = assertConflictCannotConsume(true)

    private fun assertTaggedOriginCannotProve(kind: String) {
        val result = reconcile(local(), page().map { if (it.id == 10) it.copy(display_kind = kind) else it })
        assertUnsafe(result)
        assertTrue(result.messages.any { it.content == "old prefix" })
        assertTrue(result.messages.any { it.content == "canonical interim" })
    }

    @Test fun rawSteerCannotBeTheNormalAckOrigin() = assertTaggedOriginCannotProve("steer")

    @Test fun rawOtherControlCannotBeTheNormalAckOrigin() = assertTaggedOriginCannotProve("model_switch")

    @Test fun contradictoryRawOriginRevokesDurableProofEvenWithoutTheFinalOnThisPage() {
        val verified = reconcile(local(), page())
        val result = reconcile(verified, listOf(row(10, "user", "original", "steer")))
        assertUnsafe(result)
        assertTrue(result.messages.any { it.content == "canonical interim" })
    }
}
