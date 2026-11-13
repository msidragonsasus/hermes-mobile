package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Display-key reuse cannot supply producer receipts or collapse independent prose. */
class TranscriptDisplayBodyIntegrationTest {
    private val physical = """{"result":"physical tool output"}"""

    private fun tool(
        id: String = "uuid-tool",
        call: String = "call",
    ) = ChatMessage(
        id = id,
        role = MessageRole.TOOL,
        content = physical,
        toolCallId = call,
        toolName = "terminal",
        toolStatus = ToolStatus.COMPLETED,
        timestamp = 10L,
        finishTimestamp = 42L,
        toolOutputRiskData = ToolOutputRiskData("medium", listOf("synthetic risk finding"), true),
    )

    private fun row(
        id: Int = 11,
        call: String = "call",
    ) = SessionMessage(
        id = id,
        role = "tool",
        content = JsonPrimitive(physical),
        tool_call_id = call,
        display_content = JsonPrimitive("safe projected output"),
    )

    private fun sync(
        state: ChatUiState,
        rows: List<SessionMessage>,
    ) = reconcileTranscript(state, "target", "session", rows, 0, true)

    private fun state(vararg messages: ChatMessage) =
        ChatUiState(messages = messages.toList(), currentSessionId = "session")

    @Test fun scopedProjectionReusesOneToolKeyWithoutManufacturingAResponse() {
        for (call in listOf("call", "")) {
            val old = tool(call = call)
            val next = sync(state(old), listOf(row()))
            assertEquals(listOf(old.id), next.messages.map { it.id })
            assertEquals("safe projected output", next.messages.single().content)
            assertTrue(next.messages.single().hasServerDisplayProjection)
            assertEquals(old.toolName, next.messages.single().toolName)
            assertEquals(old.toolStatus, next.messages.single().toolStatus)
            assertEquals(old.toolOutputRiskData, next.messages.single().toolOutputRiskData)
            assertEquals(old.timestamp, next.messages.single().timestamp)
            assertEquals(old.finishTimestamp, next.messages.single().finishTimestamp)
            assertEquals(old.id, next.responseLedger.aliases["11"])
            assertTrue(next.responseLedger.responses.isEmpty())
            assertEquals(next.messages.map { it.id }, sync(next, listOf(row())).messages.map { it.id })
        }
    }

    @Test fun materializationKeysAgreeWithCanonicalCoverageAndRepeatedHydration() =
        runTest {
            val old = tool()
            val user = ChatMessage(id = "user", role = MessageRole.USER, content = "ask")
            val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "final")
            val rows =
                listOf(
                    SessionMessage(id = 10, role = "user", content = JsonPrimitive("ask")),
                    row(),
                    SessionMessage(id = 12, role = "assistant", content = JsonPrimitive("final")),
                )
            val admitted =
                ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf(old.id, answer.id))))
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
                    TerminalEvidence(77L, 2L, listOf(10L, 11L, 12L), 10L, 12L, complete = true),
                )
            assertEquals("target", record.authority.opening!!.target)
            assertEquals("session", record.authority.opening!!.session)
            val initial = state(user, old, answer).copy(responseLedger = admitted.copy(responses = listOf(record)))
            val next = sync(initial, rows)
            assertEquals(old.id, next.messages.single { it.role == MessageRole.TOOL }.id)
            assertEquals(old.toolOutputRiskData, next.messages.single { it.id == old.id }.toolOutputRiskData)
            assertEquals(old.finishTimestamp, next.messages.single { it.id == old.id }.finishTimestamp)
            val r = next.responseLedger.responses.single()
            assertEquals(
                ReplyAuthorityDecision.ReadyCanonical,
                r.authorityDecision(next.messages.map { it.id }.toSet()),
            )
            assertEquals(next.messages.map { it.id }, r.authority.canonical!!.memberIds)
            assertEquals(next.messages.filter { it.role != MessageRole.USER }.map { it.id }, r.messageIds)
            assertEquals(old.id, next.responseLedger.aliases["11"])
            assertEquals(listOf(10L, 11L, 12L), r.authority.canonical!!.rowIds)
            val repeated = sync(next, rows)
            assertEquals(next.messages.map { it.id }, repeated.messages.map { it.id })
            assertEquals(
                next.responseLedger.responses
                    .single()
                    .authority,
                repeated.responseLedger.responses
                    .single()
                    .authority,
            )
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, next.messages, next.responseLedger)))
            val saved = ChatPersistenceRepository(dao).readTranscript("target", "session")
            val cold =
                sync(
                    ChatUiState(
                        messages = saved.messages,
                        currentSessionId = "session",
                        responseLedger = saved.payload.ledger,
                    ),
                    rows,
                )
            assertEquals(next.messages.map { it.id }, cold.messages.map { it.id })
            assertEquals(old.id, cold.responseLedger.aliases["11"])
            assertEquals(
                ReplyAuthorityDecision.ReadyCanonical,
                cold.responseLedger.responses
                    .single()
                    .authorityDecision(cold.messages.map { it.id }.toSet()),
            )
        }

    @Test fun selectedToolBodiesCannotSupplyMissingOrIneligibleProducerProof() {
        val old = tool()
        val user = ChatMessage(id = "user", role = MessageRole.USER, content = "ask")
        val answer = ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "final")
        val admitted =
            ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf(old.id, answer.id))))
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
                TerminalEvidence(77L, 2L, listOf(10L, 11L, 12L), 10L, 12L, complete = true),
            )
        val rows =
            listOf(
                SessionMessage(id = 10, role = "user", content = JsonPrimitive("ask")),
                row(),
                SessionMessage(id = 12, role = "assistant", content = JsonPrimitive("final")),
            )
        val variants =
            listOf(
                admitted.copy(responses = listOf(record.copy(authority = record.authority.copy(opening = null)))),
                admitted,
                admitted.copy(
                    responses =
                        listOf(
                            record.copy(
                                authority =
                                    record.authority.copy(
                                        terminals =
                                            record.authority.terminals.map { it.copy(partial = true) },
                                    ),
                            ),
                        ),
                ),
                admitted.copy(responses = listOf(record), pendingInputs = mapOf("unresolved" to "A")),
                admitted.copy(
                    responses =
                        listOf(
                            record.copy(
                                authority =
                                    record.authority.copy(
                                        controls =
                                            listOf(
                                                ControlInputEvidence("steer", "rpc-steer", "STEER", userRowId = 99L),
                                            ),
                                    ),
                            ),
                        ),
                ),
            )
        for (ledger in variants) {
            val next = sync(state(user, old, answer).copy(responseLedger = ledger), rows)
            val r = next.responseLedger.responses.single()
            assertFalse(
                r
                    .authorityDecision(
                        next.messages
                            .map {
                                it.id
                            }.toSet(),
                        next.responseLedger.blocksAuthority(r.id),
                    ).copyReady,
            )
            assertEquals(
                ledger.responses
                    .single()
                    .authority.opening,
                r.authority.opening,
            )
            assertEquals(
                ledger.responses
                    .single()
                    .authority.terminals,
                r.authority.terminals,
            )
        }
    }

    @Test fun ambiguousCallIdsAndStructuredResultsNeverConsumeAnOldTool() {
        for (call in listOf("call", "")) {
            val first = tool("first", call)
            val second = tool("second", call)
            val next = sync(state(first, second), listOf(row()))
            assertEquals(3, next.messages.size)
            assertEquals(physical, next.messages.single { it.id == first.id }.content)
            assertEquals(physical, next.messages.single { it.id == second.id }.content)
            val duplicateRows = sync(state(first), listOf(row(), row(12)))
            assertEquals(3, duplicateRows.messages.size)
            assertEquals(physical, duplicateRows.messages.single { it.id == first.id }.content)
        }
    }

    @Test fun mismatchedScopeMissingAddressConflictingAliasAndKnownOtherOwnerBlockFallback() {
        val old = tool()
        val cases =
            listOf(
                state(old).copy(currentSessionId = "other-session"),
                state(old.copy(id = canonicalMessageId("other-target", "session", 11))),
                state(old.copy(id = canonicalMessageId("target", "other-session", 11))),
                state(old.copy(id = "legacy-uuid-tool")),
                state(old).copy(responseLedger = ResponseLedger(aliases = mapOf("99" to old.id))),
                state(old).copy(
                    responseLedger =
                        ResponseLedger(
                            responses =
                                listOf(
                                    ResponseRecord(
                                        id = "other",
                                        messageIds = listOf(old.id),
                                        canonicalRowIds = listOf(99L),
                                    ),
                                ),
                        ),
                ),
            )
        for (initial in cases) {
            val next = sync(initial, listOf(row()))
            assertEquals(2, next.messages.size)
            assertEquals(physical, next.messages.single { it.id == initial.messages.single().id }.content)
            assertNull(next.responseLedger.aliases["11"])
        }
        val noAddress = sync(state(old), listOf(row().copy(id = null)))
        assertEquals(2, noAddress.messages.size)
        val mismatchedCalls = sync(state(old.copy(toolCallId = "different")), listOf(row()))
        assertEquals(2, mismatchedCalls.messages.size)
        val unstructured =
            sync(
                state(old.copy(toolCallId = "", content = "physical prose")),
                listOf(row().copy(content = JsonPrimitive("physical prose"))),
            )
        assertEquals(2, unstructured.messages.size)
    }

    @Test fun missingServerCallIdDoesNotEraseKnownLiveToolMetadata() {
        val old = tool()
        val next = sync(state(old), listOf(row(call = "")))
        assertEquals(listOf(old.id), next.messages.map { it.id })
        assertEquals("safe projected output", next.messages.single().content)
        assertEquals(old.toolCallId, next.messages.single().toolCallId)
        assertEquals(old.toolOutputRiskData, next.messages.single().toolOutputRiskData)
    }

    @Test fun hiddenToolTombstoneCanBeReplacedByFreshSelectedBodyAtItsExactAlias() {
        val old = tool()
        val hidden = sync(state(old), listOf(row().copy(display_kind = "hidden")))
        assertEquals(listOf(old.id), hidden.messages.map { it.id })
        assertEquals("hidden", hidden.messages.single().displayKind)
        assertEquals("", hidden.messages.single().content)
        val selected = sync(hidden, listOf(row()))
        assertEquals(listOf(old.id), selected.messages.map { it.id })
        assertEquals("safe projected output", selected.messages.single().content)
        assertNull("A stale tombstone cannot hide a fresh selected body", selected.messages.single().displayKind)
        assertTrue(selected.responseLedger.responses.isEmpty())
    }

    @Test fun equalIndependentHumanSegmentsNeverProvideDisplayIdentity() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            val old = ChatMessage(id = "human", role = role, content = "equal prose")
            val next =
                sync(
                    state(old),
                    listOf(
                        SessionMessage(
                            id = 11,
                            role = role.name.lowercase(),
                            content = JsonPrimitive("equal prose"),
                            display_content = JsonPrimitive("safe prose"),
                        ),
                    ),
                )
            assertEquals(2, next.messages.size)
            assertEquals("equal prose", next.messages.single { it.id == old.id }.content)
            assertTrue(next.responseLedger.responses.isEmpty())
        }
    }
}
