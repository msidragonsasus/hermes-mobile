package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptDirtyBodyPersistenceTest {
    private fun row(id: String) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = id, timestamp = 10L)

    @Test fun directDaoDuplicateRowsStillApplyInOrderWhenLastRowEqualsStoredBody() =
        runTest {
            val dao = FakeChatMessageDao()
            val claimed = dao.claimTranscript("A", "s").state!!
            val original =
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    "physical",
                    "s",
                    "ASSISTANT",
                    "old",
                    timestamp = 10L,
                )
            assertTrue(dao.saveTranscript(claimed.copy(revision = 1), listOf(original)))
            dao.bodyWriteBatches.clear()
            val intermediate = original.copy(content = "intermediate")
            assertTrue(dao.saveTranscript(claimed.copy(revision = 2, payload = "next"), listOf(intermediate, original)))
            assertEquals(listOf(original), dao.getMessagesForSession("s"))
            assertEquals(listOf(intermediate, original), dao.bodyWriteBatches.single())
            assertEquals("next", dao.transcriptState("A", "s")!!.payload)
        }

    @Test fun oneBodyEditWritesOnlyThatScopedPhysicalRowAndReadsSessionOnce() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a"), row("b"), row("c"))
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            val physicalId = dao.getMessagesForSession("s").single { it.content == "b" }.id
            dao.bodyWriteBatches.clear()
            dao.sessionReads.clear()
            val edited = messages.map { if (it.id == "b") it.copy(content = "new body") else it }
            assertTrue(repo.save(repo.prepare(owner, edited, ResponseLedger())))
            assertEquals(listOf("s"), dao.sessionReads)
            assertEquals(listOf(physicalId), dao.bodyWriteBatches.single().map { it.id })
            assertEquals(edited, ChatPersistenceRepository(dao).readTranscript("A", "s").messages)
        }

    @Test fun everyPersistedColumnParticipatesInDirtyComparison() =
        runTest {
            val base = ChatMessageEntity("physical", "s", "ASSISTANT", "body", timestamp = 10L)
            val variants =
                listOf(
                    base.copy(content = "changed"),
                    base.copy(reasoningText = "reasoning"),
                    base.copy(displayKind = "hidden"),
                    base.copy(toolStatus = "COMPLETED"),
                    base.copy(toolName = "terminal"),
                    base.copy(toolCallId = "call"),
                    base.copy(timestamp = 11L),
                    base.copy(role = "TOOL"),
                    base.copy(isStreaming = true),
                    base.copy(sessionId = "other-session"),
                    base.copy(id = "other-physical"),
                )
            for (changed in variants) {
                val dao = FakeChatMessageDao()
                val claim = dao.claimTranscript("A", "s").state!!
                assertTrue(dao.saveTranscript(claim.copy(revision = 1), listOf(base)))
                dao.bodyWriteBatches.clear()
                assertTrue(dao.saveTranscript(claim.copy(revision = 2), listOf(changed)))
                assertEquals(
                    "Changed column must not be skipped: $changed",
                    listOf(changed),
                    dao.bodyWriteBatches.single(),
                )
                assertTrue(dao.getMessagesForSession(changed.sessionId).contains(changed))
            }
        }

    @Test fun orderLedgerProvenanceAndFinishOnlyChangesCommitWithoutBodyWrites() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a"), row("b"))
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            val reversed = messages.reversed()
            val ledger =
                ResponseLedger(
                    responses =
                        listOf(
                            ResponseRecord(id = "response", messageIds = listOf("a", "b"), finishedAt = 77L),
                        ),
                    aliases = mapOf("11" to "a"),
                )
            val changes =
                listOf(
                    repo.prepare(owner, reversed, ResponseLedger()),
                    repo.prepare(owner, reversed, ledger),
                    repo.prepare(owner, reversed.map { it.copy(finishTimestamp = 99L) }, ledger),
                ).toMutableList()
            val provenance = repo.prepare(owner, reversed, ledger)
            changes.add(
                provenance.copy(
                    snapshot =
                        provenance.snapshot.copy(
                            payload =
                                provenance.snapshot.payload.copy(
                                    provenance = mapOf("a" to "unknown", "b" to "A"),
                                ),
                        ),
                ),
            )
            for (write in changes) {
                dao.bodyWriteBatches.clear()
                assertTrue(repo.save(write))
                assertTrue(dao.bodyWriteBatches.isEmpty())
                assertEquals(write.revision, dao.transcriptState("A", "s")!!.revision)
                val reopened = ChatPersistenceRepository(dao).readTranscript("A", "s")
                assertEquals(write.snapshot.messages, reopened.messages)
                assertEquals(write.snapshot.payload, reopened.payload)
            }
        }

    @Test fun stagedAdditionPromotionAndRemovalReuseBodiesWithoutDeletingUnreferencedRows() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val visible = row("visible")
            val staged = row("staged")
            assertTrue(repo.save(repo.prepare(owner, listOf(visible), ResponseLedger())))
            dao.bodyWriteBatches.clear()
            assertTrue(repo.save(repo.prepare(owner, listOf(visible), ResponseLedger(), listOf(staged))))
            assertEquals(listOf("staged"), dao.bodyWriteBatches.single().map { it.content })
            assertEquals(listOf(staged), repo.readTranscript("A", "s").stagedMessages)

            dao.bodyWriteBatches.clear()
            val promoted = repo.prepare(owner, listOf(visible, staged), ResponseLedger())
            assertTrue(repo.save(promoted))
            assertTrue(dao.bodyWriteBatches.isEmpty())
            assertEquals(promoted.snapshot, ChatPersistenceRepository(dao).readTranscript("A", "s"))
            assertTrue(repo.save(repo.prepare(owner, emptyList(), ResponseLedger())))
            assertTrue(dao.bodyWriteBatches.isEmpty())
            assertEquals(2, dao.getMessagesForSession("s").size)
            assertTrue(repo.readTranscript("A", "s").messages.isEmpty())
        }

    @Test fun pendingInputIsExcludedUntilConfirmedAndVisibleWinsOverDuplicateStagedBody() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val input = row("user").copy(role = MessageRole.USER)
            val assistant = row("answer")
            val messages = listOf(input, assistant)
            val pending = ResponseLedger(pendingInputs = mapOf(input.id to "response"))
            assertTrue(repo.save(repo.prepare(owner, messages, pending)))
            assertEquals(listOf("answer"), dao.bodyWriteBatches.single().map { it.content })
            assertEquals(listOf(assistant), repo.readTranscript("A", "s").messages)
            dao.bodyWriteBatches.clear()
            assertTrue(
                repo.save(repo.prepare(owner, messages, ResponseLedger(), listOf(assistant.copy(content = "shadow")))),
            )
            assertEquals(listOf("user"), dao.bodyWriteBatches.single().map { it.content })
            val reopened = ChatPersistenceRepository(dao).readTranscript("A", "s")
            assertEquals(messages, reopened.messages)
            assertEquals(listOf(assistant), reopened.stagedMessages)
        }

    @Test fun selectedSourceBodyNotJustVisibleTextControlsDirtyComparison() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val original = row("a").copy(content = "visible", sourceBody = "visible\nMEDIA:/first.png")
            assertTrue(repo.save(repo.prepare(owner, listOf(original), ResponseLedger())))
            dao.bodyWriteBatches.clear()
            val selected = original.copy(sourceBody = "visible\nMEDIA:/selected.png", hasServerDisplayProjection = true)
            assertTrue(repo.save(repo.prepare(owner, listOf(selected), ResponseLedger())))
            assertEquals(listOf(selected.bodyForStorage()), dao.bodyWriteBatches.single().map { it.content })
            assertEquals(
                selected.bodyForStorage(),
                repo
                    .readTranscript("A", "s")
                    .messages
                    .single()
                    .content,
            )
            dao.bodyWriteBatches.clear()
            assertTrue(repo.save(repo.prepare(owner, listOf(selected.copy(finishTimestamp = 77L)), ResponseLedger())))
            assertTrue(dao.bodyWriteBatches.isEmpty())
            val hidden = selected.copy(content = "", sourceBody = null, displayKind = "hidden")
            assertTrue(repo.save(repo.prepare(owner, listOf(hidden), ResponseLedger())))
            assertEquals(
                "",
                dao.bodyWriteBatches
                    .single()
                    .single()
                    .content,
            )
            assertEquals(
                "hidden",
                repo
                    .readTranscript("A", "s")
                    .messages
                    .single()
                    .displayKind,
            )
        }

    @Test fun legacyDeletionAndSamePhysicalIdEditAreRepairedFromCurrentRowsNotCachedState() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val legacy = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a"), row("b"))
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            val physical = dao.getMessagesForSession("s").single { it.content == "a" }
            legacy.persistMessage(row(physical.id).copy(content = "external edit"), "s")
            dao.bodyWriteBatches.clear()
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            assertEquals(listOf(physical), dao.bodyWriteBatches.single())
            assertEquals(messages, repo.readTranscript("A", "s").messages)

            legacy.clearMessagesForSession("s")
            dao.bodyWriteBatches.clear()
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            assertEquals(2, dao.bodyWriteBatches.single().size)
            assertEquals(messages, ChatPersistenceRepository(dao).readTranscript("A", "s").messages)
        }

    @Test fun absentStateWrongEpochAndNonIncreasingRevisionRejectBeforeBodyReadOrWrite() =
        runTest {
            val dao = FakeChatMessageDao()
            val state = dao.claimTranscript("A", "s").state!!
            val body = ChatMessageEntity("physical", "s", "ASSISTANT", "saved", timestamp = 10L)
            val current = state.copy(revision = 2, payload = "saved")
            assertTrue(dao.saveTranscript(current, listOf(body)))
            for (rejected in listOf(
                current.copy(targetKey = "missing", revision = 3),
                current.copy(writeEpoch = current.writeEpoch + 1, revision = 3),
                current.copy(revision = 2),
                current.copy(revision = 1),
            )) {
                dao.bodyWriteBatches.clear()
                dao.sessionReads.clear()
                assertFalse(dao.saveTranscript(rejected, listOf(body.copy(content = "stale"))))
                assertTrue(dao.bodyWriteBatches.isEmpty())
                assertTrue(dao.sessionReads.isEmpty())
                assertEquals(current, dao.transcriptState("A", "s"))
            }
            assertEquals(listOf(body), dao.getMessagesForSession("s"))
        }

    @Test fun otherRepositoryClaimFencesOldOwnerAcrossAtoBtoAWithoutTouchingOtherTarget() =
        runTest {
            val dao = FakeChatMessageDao()
            val first = ChatPersistenceRepository(dao)
            val second = ChatPersistenceRepository(dao)
            val (a, _) = first.openTranscript("A", "s")
            val aBody = row("same").copy(content = "A")
            assertTrue(first.save(first.prepare(a, listOf(aBody), ResponseLedger())))
            val stale = first.prepare(a, listOf(aBody.copy(content = "stale A")), ResponseLedger())
            val (b, _) = second.openTranscript("B", "s")
            val bBody = row("same").copy(content = "B")
            assertTrue(second.save(second.prepare(b, listOf(bBody), ResponseLedger())))
            val (newA, reopened) = second.openTranscript("A", "s")
            assertEquals(listOf(aBody), reopened.messages)
            dao.bodyWriteBatches.clear()
            dao.sessionReads.clear()
            assertFalse(first.save(stale))
            assertTrue(dao.bodyWriteBatches.isEmpty())
            assertTrue(dao.sessionReads.isEmpty())
            assertTrue(second.save(second.prepare(newA, listOf(aBody), ResponseLedger())))
            assertTrue(dao.bodyWriteBatches.isEmpty())
            assertEquals(listOf(bBody), second.readTranscript("B", "s").messages)
            assertEquals(2, dao.getMessagesForSession("s").size)
        }

    @Test fun identicalBodiesAdvancePayloadWithoutAnyBodyWriteCall() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a"), row("b"))
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            val physical = dao.getMessagesForSession("s").associateBy { it.content }
            assertEquals(listOf(physical.getValue("a"), physical.getValue("b")), dao.bodyWriteBatches.single())
            dao.bodyWriteBatches.clear()

            val ledger = ResponseLedger(aliases = mapOf("server-a" to "a"))
            val next = repo.prepare(owner, messages, ledger)
            assertTrue(repo.save(next))
            assertEquals("No SQL body batch, not even an empty batch", emptyList<Any>(), dao.bodyWriteBatches)
            assertEquals(next.revision, dao.transcriptState("A", "s")!!.revision)
            val reopened = ChatPersistenceRepository(dao).readTranscript("A", "s")
            assertEquals(messages, reopened.messages)
            assertEquals(next.snapshot.payload, reopened.payload)
        }
}
