package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<CareerLocalStore>()
    private val repository = mockk<CareerRepository>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.enabled() } returns true
        coEvery { local.pending() } returns null
        coEvery { local.cached() } returns null
        coEvery { local.cache(any()) } returns Unit
        coEvery { local.savePending(any()) } returns Unit
        coEvery { repository.snapshot() } returns CareerSnapshot(1)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun disconnectedWriteIsRetriedWithSameOperationIdOnlyOnUserRequest() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } throws IOException()
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            model.send("save", buildJsonObject { put("id", "one") })
            advanceUntilIdle()
            val pending = requireNotNull(model.state.value.pending)
            assertNotNull(pending.payload.text("operation_id"))
            model.refresh()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.mutate("save", any()) }
            coEvery { repository.mutate(any(), any()) } returns JsonObject(emptyMap())
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.mutate("save", pending.payload) }
            assertNull(model.state.value.pending)
        }

    @Test
    fun restartedAppKeepsPendingWithoutAutomaticallySubmitting() =
        runTest(dispatcher) {
            val operation = CareerPendingOperation("save", buildJsonObject { put("operation_id", "previous-op") })
            coEvery { local.pending() } returns operation
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertEquals(operation, model.state.value.pending)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun journalFailurePreventsNetworkWrite() =
        runTest(dispatcher) {
            coEvery { local.savePending(any()) } throws IOException()
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            model.send("save", JsonObject(emptyMap()))
            advanceUntilIdle()
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { repository.mutate(any(), any()) }
        }

    @Test
    fun shukatsuImportSendsOnlyTheAllowlistedPayload() =
        runTest(dispatcher) {
            coEvery { repository.mutate(any(), any()) } returns JsonObject(emptyMap())
            val upload = buildJsonObject { put("operation_id", "import-operation") }
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()

            model.importShukatsu(ShukatsuImportResult(upload))
            advanceUntilIdle()

            coVerify(exactly = 0) { local.saveImportedLoginAccounts(any()) }
            coVerify(exactly = 1) { repository.mutate("import", upload) }
        }

    @Test
    fun offlineCacheIsMarkedStale() =
        runTest(dispatcher) {
            coEvery { local.cached() } returns CareerSnapshot(1)
            coEvery { repository.snapshot() } throws IOException()
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            assertTrue(model.state.value.stale)
            assertNotNull(model.state.value.snapshot)
        }

    @Test
    fun rejectedImportKeepsTheExactPayloadForRetryAfterServerUpdate() =
        runTest(dispatcher) {
            val payload = buildJsonObject { put("operation_id", "preserved-import") }
            coEvery { local.pending() } returns CareerPendingOperation("import", payload)
            coEvery { repository.mutate("import", payload) } throws
                retrofit2.HttpException(
                    retrofit2.Response.error<JsonObject>(
                        400,
                        okhttp3.ResponseBody.create(
                            null,
                            """{"detail":{"code":"unapproved_fields"}}""",
                        ),
                    ),
                )
            val model = CareerViewModel(repository, local)
            advanceUntilIdle()
            model.retry()
            advanceUntilIdle()
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("保存内容を確認できません"),
            )
            assertEquals(
                payload,
                model.state.value.pending
                    ?.payload,
            )
            coEvery { repository.mutate("import", payload) } returns
                buildJsonObject {
                    put("imported", 247)
                    put("unchanged", 0)
                }
            model.retry()
            advanceUntilIdle()
            assertNull(model.state.value.pending)
            assertTrue(
                model.state.value.message
                    .orEmpty()
                    .contains("保存しました。"),
            )
            coVerify(exactly = 2) { repository.mutate("import", payload) }
        }
}
