package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.ReplyLifecycle
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DisplaySourceBodyIntegrationTest {
    private val physical = "Obsolete physical prose\nMEDIA:/artifacts/physical.png"
    private val visible = "Selected display prose\nMEDIA:/artifacts/visible.png"

    @Before fun gateway() {
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } returns "https://fixture.invalid"
        every { AuthManager.getToken() } returns "SYNTHETIC_LIVE_ONLY_TOKEN"
        every { AuthManager.isGatedMode() } returns false
    }

    @After fun release() = unmockkObject(AuthManager)

    @Test fun canonicalCheckpointUsesSelectedDisplaySourceNotPhysicalMedia() =
        runTest {
            val rows =
                listOf(
                    SessionMessage(
                        id = 11,
                        role = "assistant",
                        content = JsonPrimitive(physical),
                        display_content = JsonPrimitive(visible),
                    ),
                    SessionMessage(
                        id = 12,
                        role = "assistant",
                        content = JsonPrimitive(physical),
                        display_kind = "hidden",
                    ),
                    SessionMessage(
                        id = 13,
                        role = "assistant",
                        content = JsonPrimitive(physical),
                        display_content = JsonPrimitive(""),
                    ),
                    SessionMessage(
                        id = 14,
                        role = "assistant",
                        content = JsonPrimitive(physical),
                        display_metadata =
                            kotlinx.serialization.json.Json
                                .parseToJsonElement("{\"model_only\":true}"),
                    ),
                )
            val next = reconcileTranscript(ChatUiState(), "target", "session", rows, 0, true)
            val displayed = next.messages.single { it.id == canonicalMessageId("target", "session", 11) }
            assertEquals(visible, displayed.sourceBody)
            assertEquals("Selected display prose", displayed.content)
            for (hidden in next.messages.filter { it.id != displayed.id }) {
                assertNull(hidden.sourceBody)
                assertEquals("hidden", hidden.displayKind)
                assertEquals("", hidden.bodyForStorage())
            }
            val repo = ChatPersistenceRepository(FakeChatMessageDao())
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, next.messages, next.responseLedger)))
            val saved = repo.readTranscript("target", "session").messages
            assertEquals(visible, saved.single { it.id == displayed.id }.content)
            assertTrue(saved.none { it.content.contains("physical.png") })
            assertTrue(next.responseLedger.responses.isEmpty())
        }

    @Test fun exactPhysicalCanonicalMediaIsSupersededBySelectedOrHiddenSource() =
        runTest {
            for (kind in listOf("selected", "hidden", "empty")) {
                val original = SessionMessage(id = 11, role = "assistant", content = JsonPrimitive(physical))
                val initial =
                    reconcileTranscript(ChatUiState(), "target", "session", listOf(original), 0, true)
                        .let { it.copy(messages = it.messages.map { m -> m.copy(finishTimestamp = 77L) }) }
                assertEquals(physical, initial.messages.single().bodyForStorage())
                val incoming =
                    when (kind) {
                        "hidden" -> original.copy(display_kind = "hidden")
                        "empty" -> original.copy(display_content = JsonPrimitive(""))
                        else -> original.copy(display_content = JsonPrimitive(visible))
                    }
                val next = reconcileTranscript(initial, "target", "session", listOf(incoming), 0, true)
                assertEquals(
                    "Obsolete physical MEDIA must not survive as a disputed version: $kind",
                    1,
                    next.messages.size,
                )
                assertEquals(initial.messages.single().id, next.messages.single().id)
                assertEquals(77L, next.messages.single().finishTimestamp)
                assertEquals(if (kind == "selected") visible else "", next.messages.single().bodyForStorage())
                assertFalse(next.messages.any { it.bodyForStorage().contains("physical.png") })
                val dao = FakeChatMessageDao()
                val repo = ChatPersistenceRepository(dao)
                val (owner, _) = repo.openTranscript("target", "session")
                assertTrue(repo.save(repo.prepare(owner, next.messages, next.responseLedger)))
                val saved = ChatPersistenceRepository(dao).readTranscript("target", "session")
                assertEquals(next.messages.single().bodyForStorage(), saved.messages.single().bodyForStorage())
                val repeated =
                    reconcileTranscript(
                        ChatUiState(messages = saved.messages, responseLedger = saved.payload.ledger),
                        "target",
                        "session",
                        listOf(incoming),
                        0,
                        true,
                    )
                assertEquals(next.messages.map { it.id }, repeated.messages.map { it.id })
                assertTrue(repeated.responseLedger.responses.isEmpty())
            }
        }

    private fun acceptedMediaState(): Pair<ChatUiState, List<SessionMessage>> {
        val user = ChatMessage(id = "user", role = MessageRole.USER, content = "ask")
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "local answer")
        val admitted =
            ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf(answer.id))))
                .acceptInput(
                    "attempt",
                    "A",
                    null,
                    true,
                    "rpc",
                    "STREAMING",
                    "target",
                    "session",
                    1L,
                    user.id,
                    10L,
                    null,
                )
        val record =
            admitted.responses.single().admitTerminal(
                TerminalEvidence(
                    77L,
                    2L,
                    listOf(10L, 11L),
                    10L,
                    11L,
                    complete = true,
                ),
            )
        val rows =
            listOf(
                SessionMessage(id = 10, role = "user", content = JsonPrimitive("ask")),
                SessionMessage(id = 11, role = "assistant", content = JsonPrimitive(physical)),
            )
        return ChatUiState(
            messages = listOf(user, answer),
            responseLedger = admitted.copy(responses = listOf(record)),
        ) to
            rows
    }

    @Test fun acceptedCanonicalPhysicalMediaCanBeRetiredWithoutChangingReceiptProof() {
        val (initial, rows) = acceptedMediaState()
        val before = reconcileTranscript(initial, "target", "session", rows, 0, true)
        assertEquals(
            ReplyAuthorityDecision.ReadyCanonical,
            before.responseLedger.responses
                .single()
                .authorityDecision(before.messages.map { it.id }.toSet()),
        )
        val next =
            reconcileTranscript(
                before,
                "target",
                "session",
                rows.map {
                    if (it.id == 11) it.copy(display_content = JsonPrimitive(visible)) else it
                },
                0,
                true,
            )
        assertEquals(listOf("ask", "Selected display prose"), next.messages.map { it.content })
        assertEquals(
            listOf(visible),
            next.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.bodyForStorage() },
        )
        val r = next.responseLedger.responses.single()
        assertEquals(
            before.responseLedger.responses
                .single()
                .authority.opening,
            r.authority.opening,
        )
        assertEquals(
            before.responseLedger.responses
                .single()
                .authority.terminals,
            r.authority.terminals,
        )
        assertEquals(77L, r.finishedAt)
        assertEquals(ReplyAuthorityDecision.ReadyCanonical, r.authorityDecision(next.messages.map { it.id }.toSet()))
    }

    @Test fun quarantinedCanonicalPhysicalVersionIsSupersededButUnrelatedHumanRootRemains() {
        val (initial, rows) = acceptedMediaState()
        val before = reconcileTranscript(initial, "target", "session", rows, 0, true)
        for (hidden in listOf(false, true)) {
            val page =
                rows.map { row ->
                    if (row.id == 10) {
                        row.copy(role = "assistant")
                    } else if (hidden) {
                        row.copy(display_kind = "hidden")
                    } else {
                        row.copy(display_content = JsonPrimitive(visible))
                    }
                }
            val next = reconcileTranscript(before, "target", "session", page, 0, true)
            val id = canonicalMessageId("target", "session", 11)
            assertEquals(if (hidden) "" else visible, next.messages.single { it.id == id }.bodyForStorage())
            assertFalse(next.messages.any { it.bodyForStorage().contains("physical.png") })
            assertEquals(
                before.messages.single { it.role == MessageRole.USER },
                next.messages.single {
                    it.id == canonicalMessageId("target", "session", 10)
                },
            )
            assertEquals(
                ReplyAuthorityDecision.Quarantined,
                next.responseLedger.responses
                    .single()
                    .authorityDecision(next.messages.map { it.id }.toSet()),
            )
            assertEquals(
                77L,
                next.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            assertTrue(
                groupIntoTurns(next.messages, next.responseLedger)
                    .replyFooters(ReplyLifecycle(ledger = next.responseLedger))
                    .all { it == null },
            )
        }
    }

    @Test fun exactAliasPhysicalSourceIsReplacedButExtraHumanCardIsNotDiscarded() {
        val original = SessionMessage(id = 11, role = "assistant", content = JsonPrimitive(physical))
        val mapped =
            reconcileTranscript(
                ChatUiState(),
                "target",
                "session",
                listOf(original),
                0,
                true,
            ).messages.single()
        val local = mapped.copy(id = "opaque-alias", finishTimestamp = 77L)
        val state =
            ChatUiState(
                messages = listOf(local),
                responseLedger =
                    ResponseLedger(
                        aliases =
                            mapOf("11" to local.id),
                    ),
            )
        val selected = original.copy(display_content = JsonPrimitive(visible))
        val next = reconcileTranscript(state, "target", "session", listOf(selected), 0, true)
        assertEquals(listOf(local.id), next.messages.map { it.id })
        assertEquals(visible, next.messages.single().bodyForStorage())
        assertEquals(77L, next.messages.single().finishTimestamp)
        val custom =
            local.copy(
                attachments =
                    local.attachments.orEmpty() +
                        com.m57.hermescontrol.data.model.Attachment(
                            uri = "content://independent",
                            name = "human.txt",
                            mimeType = "text/plain",
                        ),
            )
        val preserved =
            reconcileTranscript(state.copy(messages = listOf(custom)), "target", "session", listOf(selected), 0, true)
        assertEquals(custom, preserved.messages.single { it.id == local.id })
        assertEquals(2, preserved.messages.size)
    }

    @Test fun projectedAndHiddenRowsCannotEraseDisputedUnrelatedHumanAliases() {
        val (initial, rows) = acceptedMediaState()
        for (hidden in listOf(false, true)) {
            val before =
                initial.copy(
                    messages =
                        initial.messages.map {
                            if (it.role == MessageRole.ASSISTANT) {
                                it.copy(
                                    content = "Independent human body",
                                    sourceBody = "Independent human body\nMEDIA:/artifacts/independent.png",
                                    finishTimestamp = 77L,
                                )
                            } else {
                                it
                            }
                        },
                )
            val disputed =
                rows.map {
                    if (it.id ==
                        10
                    ) {
                        it.copy(role = "assistant", display_content = JsonPrimitive("projected contradictory root"))
                    } else if (hidden) {
                        it.copy(display_kind = "hidden")
                    } else {
                        it.copy(display_content = JsonPrimitive(visible))
                    }
                }
            val next = reconcileTranscript(before, "target", "session", disputed, 0, true)
            assertEquals(before.messages.single { it.id == "answer" }, next.messages.single { it.id == "answer" })
            assertEquals("ask", next.messages.single { it.id == "user" }.content)
            assertEquals(
                ReplyAuthorityDecision.Quarantined,
                next.responseLedger.responses
                    .single()
                    .authorityDecision(next.messages.map { it.id }.toSet()),
            )
            assertEquals(
                77L,
                next.responseLedger.responses
                    .single()
                    .finishedAt,
            )
            assertTrue(
                groupIntoTurns(next.messages, next.responseLedger)
                    .replyFooters(ReplyLifecycle(ledger = next.responseLedger))
                    .all { it == null },
            )
            val again = reconcileTranscript(next, "target", "session", disputed, 0, true)
            assertEquals(next.messages.map { it.id }, again.messages.map { it.id })
        }
    }

    @Test fun exactLiveProjectionClearsObsoleteSourceBeforeCheckpoint() =
        runTest {
            val current =
                ChatMessage(
                    id = "live",
                    role = MessageRole.ASSISTANT,
                    content = "Obsolete physical prose",
                    sourceBody = physical,
                    reasoningText = "private reasoning",
                    finishTimestamp = 77L,
                )
            val incoming =
                ChatMessage(
                    id = "rest",
                    role = MessageRole.ASSISTANT,
                    content = "Selected display prose",
                    hasServerDisplayProjection = true,
                )
            val projected = preferServerDisplayProjection(incoming, current)
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, listOf(projected), ResponseLedger())))
            val saved = ChatPersistenceRepository(dao).readTranscript("target", "session").messages.single()
            assertEquals("Selected display prose", saved.content)
            assertEquals("live", saved.id)
            assertEquals(77L, saved.finishTimestamp)
            assertEquals("", saved.reasoningText)
            assertNull(projected.sourceBody)
        }
}
