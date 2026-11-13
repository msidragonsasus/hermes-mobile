package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRetentionTest {
    private val target = "retention-target"
    private val session = "retention-session"

    private fun row(
        id: Int,
        role: String,
        text: String,
    ) = SessionMessage(id = id, role = role, content = JsonPrimitive(text))

    @Test
    fun aNewerPartialServerPageDoesNotRetireUnmatchedLegacyBodiesOnReopen() =
        runTest {
            val old =
                (0 until 200).map { index ->
                    ChatMessage(
                        id = "legacy-$index",
                        role = MessageRole.ASSISTANT,
                        content = "offline cached $index",
                        timestamp = index.toLong(),
                    )
                }
            val page = (1000 until 1050).map { index -> row(index, "assistant", "server $index") }
            val result = reconcileTranscript(ChatUiState(messages = old), target, session, page, 0, true)
            val retained = result.messages.filter { it.id.startsWith("legacy-") }
            assertEquals("No unmatched legacy row may disappear", old.size, retained.size)
            old.zip(retained).forEach { (expected, actual) -> assertEquals(expected, actual) }
            assertTrue(page.all { raw -> result.messages.any { it.content == raw.contentText } })
            val repo = ChatPersistenceRepository(FakeChatMessageDao())
            val (owner, _) = repo.openTranscript(target, session)
            assertTrue(
                repo.save(repo.prepare(owner, result.messages, result.responseLedger, result.stagedCanonicalMessages)),
            )
            val restored = repo.readTranscript(target, session)
            assertEquals(old, restored.messages.filter { it.id.startsWith("legacy-") })
            assertTrue(
                restored.payload.ledger.responses
                    .isEmpty(),
            )
        }

    @Test
    fun anIncompleteReceiptDoesNotHideCanonicalProseOrClaimPartialCopyAsWhole() =
        runTest {
            val prefix =
                ChatMessage(
                    id = "local-answer",
                    role = MessageRole.ASSISTANT,
                    content = "streamed prefix",
                    timestamp = 1000L,
                )
            val user = ChatMessage(id = "local-user", role = MessageRole.USER, content = "original", timestamp = 1000L)
            val response =
                ResponseRecord(
                    id = "response",
                    messageIds = listOf(prefix.id),
                    userIds = listOf(user.id),
                    userRowIds = listOf(10L),
                    canonicalRowIds = listOf(10L, 11L),
                    canonicalComplete = false,
                    finishedAt = 2000L,
                )
            val state =
                ChatUiState(
                    messages = listOf(user, prefix),
                    responseLedger =
                        ResponseLedger(responses = listOf(response), aliases = mapOf("10" to user.id)),
                )
            val fullText = "streamed prefix plus canonical final prose"
            val result =
                reconcileTranscript(
                    state,
                    target,
                    session,
                    listOf(row(10, "user", "original"), row(11, "assistant", fullText)),
                    0,
                    true,
                )
            assertTrue(
                "Every fetched body must remain accessible even without exhaustive replacement proof",
                result.messages.any { it.content == fullText },
            )
            assertFalse(
                "Showing unbound canonical prose must not leave a falsely complete prefix-only copy",
                result.responseLedger.responses
                    .single()
                    .coverage,
            )
            assertTrue(
                groupIntoTurns(result.messages).replyFooters(ReplyLifecycle(ledger = result.responseLedger)).all {
                    it ==
                        null
                },
            )
            val repo = ChatPersistenceRepository(FakeChatMessageDao())
            val (owner, _) = repo.openTranscript(target, session)
            assertTrue(
                repo.save(repo.prepare(owner, result.messages, result.responseLedger, result.stagedCanonicalMessages)),
            )
            val restored = repo.readTranscript(target, session)
            assertTrue(restored.messages.any { it.content == fullText })
            assertFalse(
                restored.payload.ledger.responses
                    .single()
                    .coverage,
            )
        }

    private suspend fun stagedBodyRemainsReadable(completeReceipt: Boolean) {
        val body =
            ChatMessage(
                id = canonicalMessageId(target, session, 11L),
                role = MessageRole.ASSISTANT,
                content = "canonical body before a missing older page",
                timestamp = 1000L,
            )
        val state =
            ChatUiState(
                messages =
                    listOf(
                        ChatMessage(
                            id = "local",
                            role = MessageRole.ASSISTANT,
                            content = "old prefix",
                            timestamp = 1000L,
                        ),
                    ),
                stagedCanonicalMessages = listOf(body),
                responseLedger =
                    ResponseLedger(
                        responses =
                            listOf(
                                ResponseRecord(
                                    id = "response",
                                    messageIds = listOf("local"),
                                    userRowIds = listOf(10L),
                                    canonicalRowIds = listOf(10L, 11L),
                                    canonicalComplete = completeReceipt,
                                    coverage = false,
                                    finishedAt = 2000L,
                                ),
                            ),
                        canonicalRoles = mapOf(11L to "assistant"),
                        canonicalVisibleRows = listOf(11L),
                    ),
            )
        val result = reconcileTranscript(state, target, session, emptyList(), 0, true)
        assertTrue(result.messages.any { it.id == body.id && it.content == body.content })
        assertFalse(
            result.responseLedger.responses
                .single()
                .coverage,
        )
        val repo = ChatPersistenceRepository(FakeChatMessageDao())
        val (owner, _) = repo.openTranscript(target, session)
        assertTrue(
            repo.save(repo.prepare(owner, result.messages, result.responseLedger, result.stagedCanonicalMessages)),
        )
        assertTrue(repo.readTranscript(target, session).messages.any { it.id == body.id && it.content == body.content })
    }

    @Test
    fun previouslyStagedPartialReceiptProseIsReleasedWithoutAnotherPage() =
        runTest {
            stagedBodyRemainsReadable(completeReceipt = false)
        }

    @Test
    fun fullReceiptWithoutObservedCoverageDoesNotHideAnAvailableBodyWhileAnotherPageIsMissing() =
        runTest {
            stagedBodyRemainsReadable(completeReceipt = true)
        }
}
