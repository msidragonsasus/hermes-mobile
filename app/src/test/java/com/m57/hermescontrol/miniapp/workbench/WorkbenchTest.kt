package com.m57.hermescontrol.miniapp.workbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val disk = FakeDisk()
    private var nextId = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    private fun model() = WorkbenchViewModel(repository, disk) { "synthetic-operation-${++nextId}" }

    private class FakeDisk : WorkbenchPendingStore {
        var pending: WorkbenchPending? = null
        var failRead = false
        var failCommit = false
        var failClear = false

        override fun read(): WorkbenchPending? {
            if (failRead) error("synthetic unreadable disk")
            return pending
        }

        override fun commit(pending: WorkbenchPending): Boolean {
            if (failCommit) return false
            this.pending = pending
            return true
        }

        override fun clear(pending: WorkbenchPending): Boolean {
            if (failClear || this.pending != pending) return false
            this.pending = null
            return true
        }
    }

    private class FakeRepository : WorkbenchRepository {
        var current = true
        var snapshot = fixture()
        val writes = mutableListOf<JsonObject>()
        var recoveryCalls = 0
        var reply: suspend () -> WorkbenchReceipt = { WorkbenchReceipt("unknown", "") }
        var recovered = WorkbenchReceipt("unknown", "")

        override fun isCurrent() = current

        override suspend fun snapshot() = snapshot

        override suspend fun mutate(
            payload: JsonObject,
            pending: WorkbenchPending,
        ): WorkbenchReceipt {
            writes +=
                payload
            return reply()
        }

        override suspend fun recover(pending: WorkbenchPending): WorkbenchReceipt {
            recoveryCalls++
            return recovered
        }
    }

    @Test fun openingOnlyReadsAndRapidTapsSendOnce() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            assertTrue(repository.writes.isEmpty())
            model.send("test")
            model.send("test")
            advanceUntilIdle()
            assertEquals(1, repository.writes.size)
            assertNotNull(disk.pending)
        }

    @Test fun lostReplyAndRestartRecoverByGetWithoutReplay() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            repository.reply = { throw WorkbenchUnavailable() }
            model.send("apply")
            advanceUntilIdle()
            val pending = disk.pending
            model.release()
            val restored = model()
            restored.load()
            advanceUntilIdle()
            assertEquals(pending, restored.state.value.pending)
            assertEquals(1, repository.writes.size)
            assertEquals(1, repository.recoveryCalls)
        }

    @Test fun unknownOperationFenceUsesOriginalIdAndHash() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("test")
            advanceUntilIdle()
            val pending = disk.pending!!
            repository.reply = { WorkbenchReceipt("cancelled", "") }
            model.fence()
            advanceUntilIdle()
            val fence = repository.writes.last()
            assertEquals(pending.operationId, fence.getValue("operation_id").jsonPrimitive.content)
            assertEquals(pending.requestHash, fence.getValue("request_hash").jsonPrimitive.content)
            assertEquals("fence", fence.getValue("action").jsonPrimitive.content)
            assertNull(disk.pending)
        }

    @Test fun alreadyAppliedFenceReportsDoneInsteadOfUndoing() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            repository.reply = { WorkbenchReceipt("done", "") }
            model.fence()
            advanceUntilIdle()
            assertNull(disk.pending)
            assertTrue(
                model.state.value.message
                    .contains("完了"),
            )
            assertEquals(
                "fence",
                repository.writes
                    .last()
                    .getValue("action")
                    .jsonPrimitive.content,
            )
        }

    @Test fun staleReplyAfterConnectionChangeCannotReplaceStateOrClearPending() =
        runTest {
            val deferred = CompletableDeferred<WorkbenchReceipt>()
            repository.reply = { deferred.await() }
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            val pending = disk.pending
            repository.current = false
            model.connectionChanged()
            deferred.complete(WorkbenchReceipt("done", ""))
            advanceUntilIdle()
            assertNull(model.state.value.snapshot)
            assertTrue(model.state.value.changedConnection)
            assertEquals(pending, disk.pending)
        }

    @Test fun cancellationKeepsPendingForLaterRecovery() =
        runTest {
            val deferred = CompletableDeferred<WorkbenchReceipt>()
            repository.reply = { deferred.await() }
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("edit")
            advanceUntilIdle()
            val pending = disk.pending
            model.release()
            advanceUntilIdle()
            assertEquals(pending, disk.pending)
            assertFalse(model.state.value.busy)
        }

    @Test fun storageFailureBlocksBeforeSending() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            disk.failCommit = true
            model.send("apply")
            advanceUntilIdle()
            assertTrue(repository.writes.isEmpty())
            assertTrue(model.state.value.blocked)
        }

    @Test fun corruptPendingNeverAllowsNewOperation() =
        runTest {
            disk.failRead = true
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            assertTrue(model.state.value.blocked)
            assertTrue(repository.writes.isEmpty())
        }

    @Test fun receiptCannotBeAcknowledgedWhenDiskClearFails() =
        runTest {
            repository.reply = { WorkbenchReceipt("done", "") }
            disk.failClear = true
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("test")
            advanceUntilIdle()
            assertNotNull(disk.pending)
            assertTrue(model.state.value.blocked)
        }

    @Test fun refusalClearsPendingButKeepsCurrentCodeAndExplainsFailure() =
        runTest {
            repository.reply = { WorkbenchReceipt("refused", "apply_failed") }
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            assertNull(disk.pending)
            assertEquals(fixture(), model.state.value.snapshot)
            assertTrue(
                model.state.value.message
                    .contains("稼働中の版"),
            )
        }

    @Test fun returningToOriginalConnectionAfterLeavingRecoversPending() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            repository.current = false
            model.connectionChanged()
            model.release()
            repository.current = true
            repository.recovered = WorkbenchReceipt("done", "")
            model.load()
            advanceUntilIdle()
            assertFalse(model.state.value.changedConnection)
            assertNotNull(model.state.value.snapshot)
            assertNull(disk.pending)
            assertEquals(1, repository.writes.size)
        }

    @Test fun noSendAfterScopeMovesBeforeTap() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            repository.current = false
            model.send("apply")
            advanceUntilIdle()
            assertTrue(repository.writes.isEmpty())
            assertNull(disk.pending)
        }
}

internal fun fixture(): WorkbenchSnapshot =
    WorkbenchSnapshot(
        "wb-" + "a".repeat(48),
        null,
        1,
        true,
        "Synthetic goal",
        workbenchFiles.associateWith {
            "synthetic source"
        },
        "synthetic diff",
        false,
        true,
    )

class WorkbenchParserTest {
    private val pending =
        WorkbenchPending("synthetic-operation-0001", "b".repeat(64), "test", "wb-" + "a".repeat(48), 1)

    private fun receipt(
        state: String = "done",
        hash: String = pending.requestHash,
        company: String = "one",
        profile: String = "default",
    ): String =
        buildJsonObject {
            put("protocol_version", 1)
            put("company_id", company)
            put("profile", profile)
            put("operation_id", pending.operationId)
            put("state", state)
            put("request_hash", hash)
            put("code", "")
        }.toString()

    @Test fun pendingRoundTripContainsOnlyRecoveryMetadata() {
        val text = WorkbenchParser.encode(pending)
        assertEquals(pending, WorkbenchParser.pending(text))
        assertFalse(text.contains("files"))
        assertFalse(text.contains("goal"))
    }

    @Test fun receiptBindsExactOperationScopeAndContent() {
        assertEquals("done", WorkbenchParser.receipt(receipt(), "one", "default", pending).state)
        for (body in listOf(
            receipt(hash = "c".repeat(64)),
            receipt(company = "two"),
            receipt(profile = "other"),
            receipt(state = "invented"),
        )) {
            assertTrue(runCatching { WorkbenchParser.receipt(body, "one", "default", pending) }.isFailure)
        }
    }

    @Test fun missingReceiptIsUnknownRatherThanNotSent() {
        assertEquals(
            "unknown",
            WorkbenchParser.receipt(receipt(state = "unknown", hash = ""), "one", "default", pending).state,
        )
    }

    @Test fun pendingRejectsUnknownFieldsAndInvalidIds() {
        assertTrue(
            runCatching {
                WorkbenchParser.pending(WorkbenchParser.encode(pending).replace("\"test\"", "\"shell\""))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                WorkbenchParser.pending(WorkbenchParser.encode(pending).dropLast(1) + ",\"files\":{}}")
            }.isFailure,
        )
    }

    @Test fun canonicalRequestHashMatchesPythonAndIgnoresKeyOrder() {
        val request = JsonObject(mapOf("z" to JsonPrimitive("日本語\n"), "a" to JsonPrimitive(1)))
        assertEquals(
            "c82144914771f4e4dbf865268ce2b0c5dacf6236a8ef08648e4dc265edf80b49",
            WorkbenchParser.digest(request),
        )
        assertEquals(WorkbenchParser.digest(request), WorkbenchParser.digest(JsonObject(request.toSortedMap())))
    }
}
