package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.ChatTranscriptState
import com.m57.hermescontrol.data.local.TranscriptRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** JVM transaction contract fixture; not an Android/Room runtime rollback test. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranscriptDirtyBodyTransactionTest {
    private fun row(
        id: String,
        content: String = id,
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = content, timestamp = 10L)

    @Test fun partialBodyFailureRollsBackThenFreshRevisionRetriesEveryChangedBody() = failureRetry(false)

    @Test fun payloadFailureRollsBackBodiesAndIndexThenFreshRevisionRetries() = failureRetry(true)

    private fun failureRetry(failPayload: Boolean) =
        runTest {
            val dao = TransactionalTranscriptFixture()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val original = listOf(row("a"), row("b"))
            assertTrue(repo.save(repo.prepare(owner, original, ResponseLedger())))
            val before = dao.readTranscript("A", "s")
            val edited = listOf(row("a", "edited-a"), row("b", "edited-b"))
            val ledger = ResponseLedger(aliases = mapOf("server-a" to "a"))
            val failed = repo.prepare(owner, edited.reversed(), ledger)
            val failure = IllegalStateException("injected transaction failure")
            if (failPayload) dao.afterStateWrite = { throw failure } else dao.afterBodyWrite = { throw failure }
            dao.writtenIds.clear()
            try {
                repo.save(failed)
                fail("Storage failure must propagate, never acknowledge persistence")
            } catch (actual: IllegalStateException) {
                assertEquals(failure.message, actual.message)
                // Coroutine stack-trace recovery may copy the exception and retain it as cause.
                assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
            }
            assertEquals(if (failPayload) 2 else 1, dao.writtenIds.size)
            dao.afterStateWrite = null
            dao.afterBodyWrite = null
            assertEquals("Fixture must restore both body rows and state", before, dao.readTranscript("A", "s"))
            assertEquals(original, repo.readTranscript("A", "s").messages)

            dao.writtenIds.clear()
            val retry = repo.prepare(owner, edited.reversed(), ledger)
            assertTrue(retry.revision > failed.revision)
            assertTrue(repo.save(retry))
            assertEquals(2, dao.writtenIds.size)
            assertEquals(2, dao.writtenIds.distinct().size)
            assertEquals(retry.snapshot, ChatPersistenceRepository(dao).readTranscript("A", "s"))
            assertEquals(retry.revision, dao.transcriptState("A", "s")!!.revision)
        }

    @Test fun payloadOnlyFailureDoesNotAdvanceIndexAndRetryStillWritesNoBodies() =
        runTest {
            val dao = TransactionalTranscriptFixture()
            val repo = ChatPersistenceRepository(dao)
            val (owner, _) = repo.openTranscript("A", "s")
            val messages = listOf(row("a"))
            assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
            val before = dao.readTranscript("A", "s")
            dao.writtenIds.clear()
            dao.afterStateWrite = { throw IllegalStateException("payload-only failure") }
            val changed = messages.map { it.copy(finishTimestamp = 77L) }
            try {
                repo.save(repo.prepare(owner, changed, ResponseLedger()))
                fail("Payload failure must propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("payload-only failure", expected.message)
            }
            assertEquals(before, dao.readTranscript("A", "s"))
            assertTrue(dao.writtenIds.isEmpty())
            dao.afterStateWrite = null
            val retry = repo.prepare(owner, changed, ResponseLedger())
            assertTrue(repo.save(retry))
            assertTrue(dao.writtenIds.isEmpty())
            assertEquals(retry.snapshot, repo.readTranscript("A", "s"))
        }

    @Test fun legacyEditAndDeletionWaitForComparisonTransactionThenNextSaveRepairsThem() =
        runTest {
            for (delete in listOf(false, true)) {
                val dao = TransactionalTranscriptFixture()
                val repo = ChatPersistenceRepository(dao)
                val external = ChatPersistenceRepository(dao)
                val (owner, _) = repo.openTranscript("A", "s")
                val messages = listOf(row("a"))
                assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
                val physical = dao.getMessagesForSession("s").single()
                val readEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                dao.afterSessionRead = {
                    readEntered.complete(Unit)
                    release.await()
                }
                dao.writtenIds.clear()
                val saving = async { repo.save(repo.prepare(owner, messages, ResponseLedger())) }
                readEntered.await()
                val externalStarted = CompletableDeferred<Unit>()
                val legacy =
                    async {
                        externalStarted.complete(Unit)
                        if (delete) {
                            external.clearMessagesForSession("s")
                        } else {
                            external.persistMessage(row(physical.id, "legacy edit"), "s")
                        }
                    }
                externalStarted.await()
                runCurrent()
                assertFalse("Legacy primitive must share transaction isolation", legacy.isCompleted)
                assertFalse(saving.isCompleted)
                dao.afterSessionRead = null
                release.complete(Unit)
                assertTrue(saving.await())
                legacy.await()
                val externallyChanged = dao.getMessagesForSession("s")
                if (delete) {
                    assertTrue(externallyChanged.isEmpty())
                } else {
                    assertEquals("legacy edit", externallyChanged.single().content)
                }
                dao.writtenIds.clear()
                assertTrue(repo.save(repo.prepare(owner, messages, ResponseLedger())))
                assertEquals(listOf(physical.id), dao.writtenIds)
                assertEquals(messages, repo.readTranscript("A", "s").messages)
            }
        }
}

/**
 * Calls the production DAO defaults, never reimplements the dirty comparison.
 * Snapshot/restore models rollback. All suspend primitives share the transaction lock,
 * including external legacy writes. Instrumentation records attempts even on rollback.
 */
private class TransactionalTranscriptFixture : ChatMessageDao {
    private val messages = linkedMapOf<String, ChatMessageEntity>()
    private val states = linkedMapOf<Pair<String, String>, ChatTranscriptState>()
    private val mutex = Mutex()
    val writtenIds = mutableListOf<String>()
    var afterBodyWrite: (() -> Unit)? = null
    var afterStateWrite: (() -> Unit)? = null
    var afterSessionRead: (suspend () -> Unit)? = null

    private class TransactionOwner(
        val fixture: TransactionalTranscriptFixture,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<TransactionOwner>
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        if (currentCoroutineContext()[TransactionOwner]?.fixture === this) {
            block()
        } else {
            mutex.withLock { block() }
        }

    private suspend fun <T> atomic(block: suspend () -> T): T =
        mutex.withLock {
            val oldMessages = messages.toMap()
            val oldStates = states.toMap()
            try {
                withContext(TransactionOwner(this)) { block() }
            } catch (failure: Throwable) {
                messages.clear()
                messages.putAll(oldMessages)
                states.clear()
                states.putAll(oldStates)
                throw failure
            }
        }

    override suspend fun claimTranscript(
        target: String,
        session: String,
    ): TranscriptRows = atomic { super<ChatMessageDao>.claimTranscript(target, session) }

    override suspend fun saveTranscript(
        state: ChatTranscriptState,
        rows: List<ChatMessageEntity>,
    ): Boolean = atomic { super<ChatMessageDao>.saveTranscript(state, rows) }

    override suspend fun readTranscript(
        target: String,
        session: String,
    ): TranscriptRows = atomic { super<ChatMessageDao>.readTranscript(target, session) }

    override suspend fun transcriptState(
        target: String,
        session: String,
    ): ChatTranscriptState? = locked { states[target to session] }

    override suspend fun putTranscriptState(state: ChatTranscriptState) =
        locked {
            states[state.targetKey to state.savedSessionId] = state
            afterStateWrite?.invoke()
            Unit
        }

    override suspend fun sessionExists(sessionId: String): Boolean =
        locked { messages.values.any { it.sessionId == sessionId } }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> =
        locked {
            val rows = messages.values.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }
            afterSessionRead?.invoke()
            rows
        }

    override suspend fun upsert(message: ChatMessageEntity) = locked { writeBody(message) }

    override suspend fun upsertAll(messages: List<ChatMessageEntity>) =
        locked {
            messages.forEach { writeBody(it) }
        }

    private fun writeBody(message: ChatMessageEntity) {
        messages[message.id] = message
        writtenIds.add(message.id)
        afterBodyWrite?.invoke()
    }

    override suspend fun deleteMessagesForSession(sessionId: String) =
        locked {
            messages.values.removeAll { it.sessionId == sessionId }
            Unit
        }
}
