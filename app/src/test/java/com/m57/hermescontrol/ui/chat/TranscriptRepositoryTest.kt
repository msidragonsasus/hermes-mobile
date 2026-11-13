package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranscriptRepositoryTest {
    private fun row(
        id: String,
        time: Long,
        text: String = id,
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = text, timestamp = time)

    @Test fun totalOrderAndRepeatedBodiesSurviveACompletelyNewRepository() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a", 10, "same"), row("b", 10, "same"), row("c", 0, "same"))
            val ledger =
                ResponseLedger(listOf(ResponseRecord(id = "r", messageIds = messages.map { it.id }, finishedAt = 77)))
            assertTrue(repo.save(repo.prepare(owner, messages, ledger)))
            val (_, restored) = ChatPersistenceRepository(dao).openTranscript("A", "s")
            assertEquals(messages, restored.messages)
            assertEquals(ledger, restored.payload.ledger)
        }

    @Test fun revisionIsReservedBeforeIoAndStaleWriteCannotTouchAnyBody() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val old = repo.prepare(owner, listOf(row("a", 10, "stale")), ResponseLedger())
            val newer = repo.prepare(owner, listOf(row("a", 0, "new")), ResponseLedger())
            assertTrue(repo.save(newer))
            assertFalse(repo.save(old))
            assertEquals(
                "new",
                repo
                    .readTranscript("A", "s")
                    .messages
                    .single()
                    .content,
            )
            assertEquals("new", dao.getMessagesForSession("s").single().content)
        }

    @Test fun delayedIoAfterUndoEpochCannotResurrectRowsOrOverwriteBodies() =
        runTest {
            val dao = FakeChatMessageDao()
            val oldRepo = ChatPersistenceRepository(dao)
            val (oldOwner, _) = oldRepo.openTranscript("A", "s")
            assertTrue(oldRepo.save(oldRepo.prepare(oldOwner, listOf(row("a", 10, "before")), ResponseLedger())))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            dao.beforeSnapshotSave = {
                entered.complete(Unit)
                release.await()
            }
            val delayed =
                async { oldRepo.save(oldRepo.prepare(oldOwner, listOf(row("a", 10, "resurrect")), ResponseLedger())) }
            entered.await()
            dao.beforeSnapshotSave = null
            val undoRepo = ChatPersistenceRepository(dao)
            val (newOwner, _) = undoRepo.openTranscript("A", "s")
            assertTrue(undoRepo.save(undoRepo.prepare(newOwner, emptyList(), ResponseLedger())))
            release.complete(Unit)
            assertFalse(delayed.await())
            assertTrue(undoRepo.readTranscript("A", "s").messages.isEmpty())
            assertEquals("before", dao.getMessagesForSession("s").single().content)
        }

    @Test fun sameSessionAndLocalIdsDoNotCollideAcrossTargetsOrAtoBtoA() =
        runTest {
            val dao = FakeChatMessageDao()
            val repo = ChatPersistenceRepository(dao)
            val (a, _) = repo.openTranscript("A", "s")
            assertTrue(repo.save(repo.prepare(a, listOf(row("same", 1, "A")), ResponseLedger())))
            val staleA = repo.prepare(a, listOf(row("same", 1, "old-A")), ResponseLedger())
            val (b, _) = repo.openTranscript("B", "s")
            assertTrue(repo.save(repo.prepare(b, listOf(row("same", 1, "B")), ResponseLedger())))
            val (_, restored) = repo.openTranscript("A", "s")
            assertEquals("A", restored.messages.single().content)
            assertFalse(repo.save(staleA))
            assertEquals(
                "B",
                repo
                    .readTranscript("B", "s")
                    .messages
                    .single()
                    .content,
            )
            assertNotEquals(canonicalMessageId("A", "s", 1), canonicalMessageId("B", "s", 1))
        }

    @Test fun pendingInputsAreNotReopenedAsConfirmedMessagesAndKeepTheirContinuation() =
        runTest {
            val repo = ChatPersistenceRepository(FakeChatMessageDao())
            val (owner, _) = repo.openTranscript("A", "s")
            val ledger =
                ResponseLedger(
                    responses = listOf(ResponseRecord(id = "r")),
                    activeId = "r",
                    pendingInputs = mapOf("u" to "r"),
                    pendingSubmissions = mapOf("draft" to "r"),
                )
            assertTrue(
                repo.save(
                    repo.prepare(
                        owner,
                        listOf(ChatMessage(id = "u", role = MessageRole.USER, content = "unknown")),
                        ledger,
                    ),
                ),
            )
            val restored = repo.readTranscript("A", "s")
            assertTrue(restored.messages.isEmpty())
            assertEquals(ledger, restored.payload.ledger)
        }
}
