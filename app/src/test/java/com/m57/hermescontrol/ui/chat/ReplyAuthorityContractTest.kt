package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.replyFooters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReplyAuthorityContractTest {
    private fun ledger() =
        ResponseLedger(listOf(ResponseRecord(id = "A", messageIds = listOf("answer"))), activeId = "A")

    private fun ResponseLedger.accept(
        disposition: String = "STREAMING",
        ordinary: Boolean = true,
        p: String? = "A",
        a: String? = null,
        row: Long? = 10L,
        attempt: String = "attempt",
    ) = acceptInput(attempt, p, a, ordinary, "rpc", disposition, "target", "session", 1L, null, row, null)

    private fun ResponseRecord.end(safe: Boolean = true) =
        admitTerminal(TerminalEvidence(77L, 2L, listOf(10L, 11L), 10L, 11L, complete = true, partial = !safe))

    private fun raw(rootKind: String? = null) =
        listOf(
            SessionMessage(id = 10, role = "user", content = JsonPrimitive("question"), display_kind = rootKind),
            SessionMessage(id = 11, role = "assistant", content = JsonPrimitive("answer")),
        )

    private fun state(record: ResponseRecord) =
        ChatUiState(
            messages =
                listOf(
                    ChatMessage(id = "user", role = MessageRole.USER, content = "question"),
                    ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "answer"),
                ),
            responseLedger = ResponseLedger(listOf(record.copy(userIds = listOf("user")))),
        )

    private fun reconcile(
        s: ChatUiState,
        page: List<SessionMessage> = raw(),
    ) = reconcileTranscript(s, "target", "session", page, 0, true)

    @Test fun acceptedWithoutUserBubbleUsesAttemptAndReplaysIdempotently() {
        val first = ledger().accept()
        assertEquals(
            "attempt",
            first.responses
                .single()
                .authority.opening!!
                .attemptUid,
        )
        assertTrue(
            first.responses
                .single()
                .userIds
                .isEmpty(),
        )
        assertEquals(first, first.accept())
        assertEquals(
            ReplyAuthorityDecision.ReadyLocal,
            first.responses
                .single()
                .end()
                .authorityDecision(setOf("answer")),
        )
    }

    @Test fun ambiguousStreamingDoesNotPromoteAnExistingOwner() {
        val record =
            ledger()
                .accept(ordinary = false, a = "A")
                .responses
                .single()
                .end()
        assertEquals(ReplyAuthorityDecision.Unproven, record.authorityDecision(setOf("answer")))
    }

    @Test fun acceptedControlBindsCapturedANotActiveBAndKeepsOpening() {
        val initial =
            ledger().accept().copy(
                responses = ledger().accept().responses + ResponseRecord(id = "B"),
                activeId = "B",
            )
        val result = initial.accept("REDIRECTED", false, null, "A", 12L, "control")
        assertEquals(
            "attempt",
            result.responses
                .first()
                .authority.opening!!
                .attemptUid,
        )
        assertEquals(
            "control",
            result.responses
                .first()
                .authority.controls
                .single()
                .attemptUid,
        )
        assertNull(
            result.responses
                .last()
                .authority.opening,
        )
    }

    @Test fun acceptedControlWithoutCapturedTargetNeverPicksActive() {
        val result = ledger().accept("STEERED", false, null, null)
        assertNull(
            result.responses
                .single()
                .authority.opening,
        )
        assertTrue(
            result.responses
                .single()
                .authority.controls
                .isEmpty(),
        )
    }

    @Test fun queuedNoKeyAllocatesPAndOnlyReleasesPriorPossibleContinuation() {
        val original =
            ledger().accept().let {
                it.copy(
                    responses = listOf(it.responses.single().end()),
                    possibleContinuations =
                        mapOf("queue" to "A", "other" to "A"),
                )
            }
        val queued = original.accept("QUEUED", false, "A", "A", null, "queue")
        assertEquals(mapOf("other" to "A"), queued.possibleContinuations)
        assertEquals(77L, queued.responses.first().finishedAt)
        val p = queued.responses.last()
        assertNotEquals("A", p.id)
        assertNull(p.authority.opening)
        assertEquals("queue", p.authority.queued!!.attemptUid)
        assertEquals(ReplyAuthorityDecision.Unproven, p.end().authorityDecision(emptySet()))
    }

    @Test fun safetyAndPendingGateBothLocalAndCanonical() {
        val local =
            ledger()
                .accept()
                .responses
                .single()
                .end()
        val canonical =
            local.copy(
                authority =
                    local.authority.copy(
                        canonical = CoverageEvidence("complete-manifest", listOf("answer"), listOf(10L, 11L)),
                    ),
            )
        for (record in listOf(local, canonical)) {
            assertTrue(record.authorityDecision(setOf("answer")).copyReady)
            assertEquals(ReplyAuthorityDecision.AwaitingCoverage, record.authorityDecision(setOf("answer"), true))
            for (unsafe in listOf(
                TerminalEvidence(99, 3, partial = true),
                TerminalEvidence(99, 3, transformed = true),
                TerminalEvidence(99, 3, status = "error"),
            )) {
                assertFalse(record.admitTerminal(unsafe).authorityDecision(setOf("answer")).copyReady)
                assertEquals(77L, record.admitTerminal(unsafe).finishedAt)
            }
        }
    }

    @Test fun missingBodyRecoversWithoutRevocationButQuarantineCannotRecover() {
        val ready =
            reconcile(
                state(
                    ledger()
                        .accept()
                        .responses
                        .single()
                        .end(),
                ),
            )
        val record = ready.responseLedger.responses.single()
        assertEquals(
            ReplyAuthorityDecision.ReadyCanonical,
            record.authorityDecision(ready.messages.map { it.id }.toSet()),
        )
        assertEquals(
            ReplyAuthorityDecision.AwaitingCoverage,
            record.copy(authority = record.authority.copy(local = null)).authorityDecision(emptySet()),
        )
        val gap =
            ready.copy(
                messages =
                    ready.messages.filterNot {
                        it.role == MessageRole.ASSISTANT
                    },
                responseLedger =
                    ready.responseLedger.update(
                        "A",
                    ) { it.copy(authority = it.authority.copy(local = null)) },
            )
        val recovered = reconcile(gap)
        assertTrue(
            recovered.responseLedger.responses
                .single()
                .authorityDecision(
                    recovered.messages
                        .map {
                            it.id
                        }.toSet(),
                ).copyReady,
        )
        val quarantined = reconcile(ready, raw("steer"))
        val corrected = reconcile(reconcile(quarantined, emptyList()))
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            corrected.responseLedger.responses
                .single()
                .authorityDecision(
                    corrected.messages
                        .map {
                            it.id
                        }.toSet(),
                ),
        )
    }

    @Test fun prospectiveClaimsAreSymmetricBeforeEitherSideRetires() {
        val a =
            ledger()
                .accept()
                .responses
                .single()
                .end()
        val b =
            a.copy(
                id = "B",
                messageIds = listOf("other"),
                authority = a.authority.copy(opening = a.authority.opening!!.copy(attemptUid = "B")),
            )
        for (records in listOf(listOf(a, b), listOf(b, a))) {
            val initial =
                state(a).copy(
                    responseLedger = ResponseLedger(records),
                    messages =
                        state(a).messages + ChatMessage(id = "other", role = MessageRole.ASSISTANT, content = "other"),
                )
            val result = reconcile(initial)
            assertTrue(result.messages.any { it.id == "answer" })
            assertTrue(result.messages.any { it.id == "other" })
            assertTrue(
                result.responseLedger.responses.all {
                    it.authorityDecision(result.messages.map { m -> m.id }.toSet()) ==
                        ReplyAuthorityDecision.Quarantined
                },
            )
        }
    }

    @Test fun ackRootCanOnlyBeStrengthenedByCompleteRawWitness() {
        val record =
            ledger()
                .accept(row = null)
                .responses
                .single()
                .end()
        assertEquals(
            10L,
            reconcile(state(record))
                .responseLedger.responses
                .single()
                .authority.opening!!
                .userRowId,
        )
        val partial =
            record.copy(
                authority =
                    record.authority.copy(
                        terminals =
                            record.authority.terminals.map {
                                it.copy(complete = false)
                            },
                    ),
            )
        assertNull(
            reconcile(state(partial))
                .responseLedger.responses
                .single()
                .authority.opening!!
                .userRowId,
        )
    }

    @Test fun conflictingNewAckBindingRevokesAndDoesNotRewriteOrigin() {
        val result = ledger().accept().accept(row = 20L)
        assertEquals(
            10L,
            result.responses
                .single()
                .authority.opening!!
                .userRowId,
        )
        assertEquals(ReplyAuthorityDecision.Quarantined, result.responses.single().authorityDecision(emptySet()))
    }

    @Test fun conflictingPartialReceiptRelativeOrderRevokesWholeCopyAuthority() {
        val admitted = ledger().accept().responses.single()
        val first = admitted.admitTerminal(TerminalEvidence(77, 2, listOf(10, 11, 13), 10, 13, complete = false))
        val reversed = first.admitTerminal(TerminalEvidence(99, 3, listOf(10, 13, 11), 10, 13, complete = false))
        val s = state(reversed)
        val footers =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(s.messages, s.responseLedger)
                .replyFooters(
                    com.m57.hermescontrol.ui.chat.fullbleed
                        .ReplyLifecycle(ledger = s.responseLedger),
                ).filterNotNull()
        assertTrue("Concrete relative-order contradiction is not merely a different partial subset", footers.isEmpty())
        assertEquals(ReplyAuthorityDecision.Quarantined, reversed.authorityDecision(setOf("answer")))
        assertEquals(77L, reversed.finishedAt)
    }

    @Test fun terminalEnrichmentPreservesLiteralObservationsAndOriginalTime() {
        val first =
            ledger().accept().responses.single().admitTerminal(
                TerminalEvidence(77, 2, listOf(10, 11), 10, 11, complete = false),
            )
        val enriched = first.admitTerminal(TerminalEvidence(99, 3, listOf(10, 11), 10, 11, complete = true))
        assertEquals(listOf(false, true), enriched.authority.terminals.map { it.complete })
        assertEquals(77L, enriched.finishedAt)
        assertTrue(enriched.authority.revocations.isEmpty())
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            enriched.admitTerminal(TerminalEvidence(100, 4, finalRowId = 12)).authorityDecision(setOf("answer")),
        )
    }

    @Test fun newlyExposedAnonymousOverlapSuspendsCanonicalReadinessUntilFreshWindow() {
        val ready =
            reconcile(
                state(
                    ledger()
                        .accept()
                        .responses
                        .single()
                        .end(),
                ),
            )
        val uncertain =
            reconcile(
                ready,
                listOf(
                    raw().first(),
                    SessionMessage(role = "assistant", content = JsonPrimitive("unclassified continuation")),
                    raw().last(),
                ),
            )
        assertTrue(uncertain.messages.any { it.content == "unclassified continuation" })
        assertFalse(
            uncertain.responseLedger.responses
                .single()
                .authorityDecision(
                    uncertain.messages
                        .map {
                            it.id
                        }.toSet(),
                ).copyReady,
        )
        val absent = reconcile(uncertain, emptyList())
        assertFalse(
            absent.responseLedger.responses
                .single()
                .authorityDecision(absent.messages.map { it.id }.toSet())
                .copyReady,
        )
        assertTrue(
            absent.responseLedger.responses
                .single()
                .authority.revocations
                .isEmpty(),
        )
        val fresh = reconcile(absent)
        assertTrue(
            fresh.responseLedger.responses
                .single()
                .authorityDecision(fresh.messages.map { it.id }.toSet())
                .copyReady,
        )
    }

    @Test fun quarantinedReplyCannotRegainAuthorityFromTagRemovalAndDoesNotPoisonB() {
        val quarantined =
            reconcile(
                reconcile(
                    state(
                        ledger()
                            .accept()
                            .responses
                            .single()
                            .end(),
                    ),
                ),
                raw("steer"),
            )
        val removed = quarantined.copy(messages = quarantined.messages.filterNot { it.role == MessageRole.USER })
        val persisted = reconcile(removed, emptyList())
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            persisted.responseLedger.responses
                .single()
                .authorityDecision(
                    persisted.messages
                        .map {
                            it.id
                        }.toSet(),
                ),
        )
        val b =
            ResponseRecord(
                id = "B",
                messageIds = listOf("b-answer"),
                userIds = listOf("b-user"),
                authority = acceptedAuthorityFixture(20L, 21L, listOf(20, 21), 88L, listOf("b-answer")),
            )
        val combined =
            persisted.copy(
                messages =
                    persisted.messages +
                        listOf(
                            ChatMessage(id = "b-user", role = MessageRole.USER, content = "new question"),
                            ChatMessage(id = "b-answer", role = MessageRole.ASSISTANT, content = "new answer"),
                        ),
                responseLedger = persisted.responseLedger.copy(responses = persisted.responseLedger.responses + b),
            )
        val footers =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(combined.messages, combined.responseLedger)
                .let { turns ->
                    with(
                        com.m57.hermescontrol.ui.chat.fullbleed
                            .ReplyLifecycle(ledger = combined.responseLedger),
                    ) {
                        turns.replyFooters(this).filterNotNull()
                    }
                }
        assertEquals(listOf("B"), footers.map { it.responseId })
        assertEquals(listOf("new answer"), footers.map { it.text })
    }

    @Test fun coldVersionTwoRestorationRetainsRevocationBodiesOrderAndTime() =
        runTest {
            val s =
                reconcile(
                    reconcile(
                        state(
                            ledger()
                                .accept()
                                .responses
                                .single()
                                .end(),
                        ),
                    ),
                    raw("steer"),
                )
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            assertTrue(repo.save(repo.prepare(owner, s.messages, s.responseLedger)))
            val (_, cold) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            assertEquals(2, cold.payload.version)
            assertEquals(s.messages.map { it.id to it.content }, cold.messages.map { it.id to it.content })
            assertEquals(s.responseLedger, cold.payload.ledger)
            assertEquals(
                ReplyAuthorityDecision.Quarantined,
                cold.payload.ledger.responses
                    .single()
                    .authorityDecision(
                        cold.messages
                            .map {
                                it.id
                            }.toSet(),
                    ),
            )
        }

    @Test fun aCompleteManifestCannotRetireAnAcceptedControlItOmits() {
        val admitted = ledger().accept()
        val controlled =
            admitted.acceptInput(
                "control",
                null,
                "A",
                false,
                "control-rpc",
                "STEERED",
                "target",
                "session",
                3,
                "control-body",
                12,
                null,
            )
        val record = controlled.responses.single().end()
        val initial =
            state(record).copy(
                messages =
                    state(record).messages +
                        ChatMessage(
                            id = "control-body",
                            role = MessageRole.USER,
                            content = "must retain control",
                            displayKind = "steer",
                        ),
                responseLedger =
                    controlled.copy(
                        responses = listOf(record.copy(userIds = listOf("user", "control-body"))),
                    ),
            )
        val result = reconcile(initial)
        assertTrue(
            "An exhaustive manifest cannot delete a separately accepted exact control body",
            result.messages.any {
                it.id ==
                    "control-body"
            },
        )
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            result.responseLedger.responses
                .single()
                .authorityDecision(
                    result.messages
                        .map {
                            it.id
                        }.toSet(),
                ),
        )
        val terminalBeforeAck =
            admitted
                .copy(responses = listOf(admitted.responses.single().end()))
                .acceptInput(
                    "control",
                    null,
                    "A",
                    false,
                    "control-rpc",
                    "STEERED",
                    "target",
                    "session",
                    3,
                    "control-body",
                    12,
                    null,
                )
        assertEquals(
            ReplyAuthorityDecision.Quarantined,
            terminalBeforeAck.responses.single().authorityDecision(setOf("answer")),
        )
    }

    @Test fun queuedCandidateBecomesExecutionRootOnlyWithNormalCompleteRawWitness() {
        val queued =
            ledger()
                .accept("QUEUED", row = 20L)
                .responses
                .single()
                .admitTerminal(TerminalEvidence(77, 2, listOf(20, 21), 20, 21, complete = true))
        assertNull(queued.authority.opening)
        val page =
            listOf(
                SessionMessage(id = 20, role = "user", content = JsonPrimitive("question")),
                SessionMessage(id = 21, role = "assistant", content = JsonPrimitive("queued answer")),
            )
        val proved = reconcile(state(queued), page).responseLedger.responses.single()
        assertEquals("attempt", proved.authority.opening!!.attemptUid)
        assertEquals("complete-manifest", proved.authority.opening!!.rowBindingSource)
        val tagged = reconcile(state(queued), page.map { if (it.id == 20) it.copy(display_kind = "steer") else it })
        assertNull(
            tagged.responseLedger.responses
                .single()
                .authority.opening,
        )
        assertTrue(
            tagged.responseLedger.responses
                .single()
                .authority.revocations
                .isEmpty(),
        )
    }

    @Test fun canonicalFooterProjectsTheWitnessNotAnUnretiredLocalPrefix() {
        val record =
            ledger()
                .accept()
                .responses
                .single()
                .end()
        val root = canonicalMessageId("target", "session", 10L)
        val final = canonicalMessageId("target", "session", 11L)
        val initial =
            state(record).copy(
                messages =
                    listOf(
                        ChatMessage(id = "user", role = MessageRole.USER, content = "question"),
                        ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "local prefix"),
                        ChatMessage(id = root, role = MessageRole.USER, content = "question"),
                        ChatMessage(id = final, role = MessageRole.ASSISTANT, content = "whole canonical answer"),
                    ),
                responseLedger =
                    ResponseLedger(
                        listOf(
                            record.copy(
                                userIds = listOf("user"),
                                authority =
                                    record.authority.copy(
                                        local = null,
                                        canonical =
                                            CoverageEvidence(
                                                "complete-manifest",
                                                listOf(root, final),
                                                listOf(10, 11),
                                            ),
                                    ),
                            ),
                        ),
                    ),
            )
        val footers =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(initial.messages, initial.responseLedger)
                .replyFooters(
                    com.m57.hermescontrol.ui.chat.fullbleed
                        .ReplyLifecycle(ledger = initial.responseLedger),
                ).filterNotNull()
        assertEquals(listOf("whole canonical answer"), footers.map { it.text })
    }

    @Test fun restoredAcceptedOriginDoesNotRequireItsOldRenderedRootToReappear() {
        val original =
            state(
                ledger()
                    .accept()
                    .responses
                    .single()
                    .end(),
            )
        val result = reconcile(original.copy(messages = emptyList()))
        val footers =
            com.m57.hermescontrol.ui.chat.fullbleed
                .groupIntoTurns(result.messages, result.responseLedger)
                .replyFooters(
                    com.m57.hermescontrol.ui.chat.fullbleed
                        .ReplyLifecycle(ledger = result.responseLedger),
                ).filterNotNull()
        assertEquals(listOf("answer"), footers.map { it.text })
        assertEquals(
            "attempt",
            result.responseLedger.responses
                .single()
                .authority.opening!!
                .attemptUid,
        )
        assertEquals(77L, footers.single().finishedAt)
    }

    @Test fun versionOneStagedBodiesUseExactCanonicalOrderWithoutInventingAuthority() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")

            fun body(row: Long) =
                ChatMessage(
                    id = canonicalMessageId("target", "session", row),
                    role =
                        if (row ==
                            10L
                        ) {
                            MessageRole.USER
                        } else {
                            MessageRole.ASSISTANT
                        },
                    content = "body-$row",
                    timestamp = 100L - row,
                )
            val legacy =
                ResponseLedger(
                    listOf(
                        ResponseRecord(
                            id = "legacy",
                            canonicalRowIds = listOf(10, 11, 12, 13),
                            canonicalComplete = true,
                            finishedAt = 77,
                        ),
                    ),
                )
            val write = repo.prepare(owner, listOf(body(10), body(13)), legacy, listOf(body(12), body(11)))
            assertTrue(
                repo.save(
                    write.copy(snapshot = write.snapshot.copy(payload = write.snapshot.payload.copy(version = 1))),
                ),
            )
            val (_, restored) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            assertEquals(listOf("body-10", "body-11", "body-12", "body-13"), restored.messages.map { it.content })
            assertEquals(listOf(90L, 89L, 88L, 87L), restored.messages.map { it.timestamp })
            assertEquals(
                ReplyAuthorityDecision.Unproven,
                restored.payload.ledger.responses
                    .single()
                    .authorityDecision(
                        restored.messages
                            .map {
                                it.id
                            }.toSet(),
                    ),
            )
        }

    @Test fun versionOneOptimismAndStagedProseRemainReadableWithoutAuthority() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("target", "session")
            val s =
                state(
                    ledger()
                        .accept()
                        .responses
                        .single()
                        .end(),
                )
            val staged =
                ChatMessage(id = "staged", role = MessageRole.ASSISTANT, content = "must stay readable", timestamp = 1)
            val prepared = repo.prepare(owner, s.messages, s.responseLedger, listOf(staged))
            assertTrue(
                repo.save(
                    prepared.copy(
                        snapshot = prepared.snapshot.copy(payload = prepared.snapshot.payload.copy(version = 1)),
                    ),
                ),
            )
            val (_, cold) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            assertEquals(listOf("user", "answer", "staged"), cold.messages.map { it.id })
            assertTrue(cold.stagedMessages.isEmpty())
            assertEquals(
                77L,
                cold.payload.ledger.responses
                    .single()
                    .finishedAt,
            )
            assertEquals(
                ReplyAuthorityDecision.Unproven,
                cold.payload.ledger.responses
                    .single()
                    .authorityDecision(
                        cold.messages
                            .map {
                                it.id
                            }.toSet(),
                    ),
            )
            val stored = dao.transcriptState("target", "session")!!
            dao.putTranscriptState(
                stored.copy(
                    payload =
                        JsonObject(Json.parseToJsonElement(stored.payload).jsonObject - "version").toString(),
                ),
            )
            val (_, omittedVersion) = ChatPersistenceRepository(dao).openTranscript("target", "session")
            assertEquals(cold.messages.map { it.id to it.content }, omittedVersion.messages.map { it.id to it.content })
            assertEquals(
                ReplyAuthorityDecision.Unproven,
                omittedVersion.payload.ledger.responses
                    .single()
                    .authorityDecision(
                        omittedVersion.messages
                            .map {
                                it.id
                            }.toSet(),
                    ),
            )
        }
}
