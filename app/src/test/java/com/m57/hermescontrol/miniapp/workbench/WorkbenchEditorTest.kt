package com.m57.hermescontrol.miniapp.workbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val disk = FakeDisk()
    private var ids = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    private fun model() = WorkbenchViewModel(repository, disk) { "synthetic-editor-operation-${++ids}" }

    private class FakeDisk : WorkbenchPendingStore {
        var pending: WorkbenchPending? = null
        var failClear = false

        override fun read() = pending

        override fun commit(pending: WorkbenchPending): Boolean {
            this.pending = pending
            return true
        }

        override fun clear(pending: WorkbenchPending): Boolean {
            if (failClear) return false
            this.pending = null
            return true
        }
    }

    private class FakeRepository : WorkbenchRepository {
        var current = true
        var metadata: EditorSnapshot? = EditorSnapshot(false, false, null)
        var metadataRead: suspend () -> EditorSnapshot? = { metadata }
        val writes = mutableListOf<JsonObject>()

        override fun isCurrent() = current

        override suspend fun snapshot() = fixture()

        override suspend fun editor() = metadataRead()

        override suspend fun recover(pending: WorkbenchPending) = WorkbenchReceipt("done", "")

        override suspend fun mutate(
            payload: JsonObject,
            pending: WorkbenchPending,
        ): WorkbenchReceipt {
            writes += payload
            return WorkbenchReceipt("done", "")
        }
    }

    @Test fun naturalRequestSendsOnlyGoalAndScopedAcceptanceFields() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("propose", mapOf("goal" to JsonPrimitive("ログイン待ちと実行中を分けて直して")))
            model.send("propose", mapOf("goal" to JsonPrimitive("duplicate")))
            advanceUntilIdle()
            assertEquals(1, repository.writes.size)
            val payload = repository.writes.single()
            assertEquals(
                setOf("protocol_version", "operation_id", "action", "expected_active", "revision", "goal"),
                payload.keys,
            )
            assertEquals("propose", payload.getValue("action").jsonPrimitive.content)
            assertNull(disk.pending)
            assertTrue(
                model.state.value.message
                    .contains("改修依頼"),
            )
        }

    @Test fun reviewedApplyBindsTheDisplayedCodeHash() =
        runTest {
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("apply")
            advanceUntilIdle()
            val payload = repository.writes.single()
            assertEquals("apply_reviewed", payload.getValue("action").jsonPrimitive.content)
            val expected = WorkbenchParser.digest(JsonObject(fixture().files.mapValues { JsonPrimitive(it.value) }))
            assertEquals(expected, payload.getValue("review_hash").jsonPrimitive.content)
        }

    @Test fun generatingJobBlocksManualWritesAndAllowsExplicitCancellation() =
        runTest {
            repository.metadata = EditorSnapshot(true, true, editorJob("running"))
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("edit")
            model.send("apply")
            model.send("propose")
            advanceUntilIdle()
            assertTrue(repository.writes.isEmpty())
            model.send("cancel_editor")
            advanceUntilIdle()
            assertEquals(
                "cancel_editor",
                repository.writes
                    .single()
                    .getValue("action")
                    .jsonPrimitive.content,
            )
        }

    @Test fun staleEditorMetadataCannotCrossTheConnectionBoundary() =
        runTest {
            val reply = CompletableDeferred<EditorSnapshot?>()
            repository.metadataRead = { reply.await() }
            val model = model()
            model.load()
            advanceUntilIdle()
            repository.current = false
            model.connectionChanged()
            reply.complete(EditorSnapshot(true, true, editorJob("running")))
            advanceUntilIdle()
            assertNull(model.state.value.snapshot)
            assertNull(model.state.value.editor)
            assertTrue(model.state.value.changedConnection)
        }

    @Test fun knownReceiptRecoversFromARepairedPendingStoreWithoutReplaying() =
        runTest {
            disk.failClear = true
            val model = model()
            model.load()
            advanceUntilIdle()
            model.send("test")
            advanceUntilIdle()
            assertTrue(model.state.value.blocked)
            disk.failClear = false
            model.load()
            advanceUntilIdle()
            assertFalse(model.state.value.blocked)
            assertNull(disk.pending)
            assertEquals(1, repository.writes.size)
        }
}

internal fun editorJob(state: String) =
    EditorJob(
        "synthetic-editor-job-0001",
        state,
        fixture().active,
        EditorExecution("synthetic", "synthetic-editor", "default"),
        null,
        null,
        null,
        "",
    )

class WorkbenchEditorParserTest {
    private fun body(
        company: String = "one",
        profile: String = "default",
        state: String = "queued",
    ): String =
        buildJsonObject {
            put("protocol_version", 1)
            put("company_id", company)
            put("profile", profile)
            put("executor_configured", true)
            put("auto_run_enabled", false)
            putJsonObject("job") {
                put("id", "synthetic-editor-job-0001")
                put("state", state)
                put("base", fixture().active)
                putJsonObject("execution") {
                    put("provider", "synthetic")
                    put("model", "synthetic-editor")
                    put("reasoning_effort", "default")
                }
                for (key in listOf(
                    "proposal_hash",
                    "tested_hash",
                    "applied_hash",
                )) {
                    put(key, kotlinx.serialization.json.JsonNull)
                }
                put("error", "")
                put("created_at", "2026-10-12T00:00:00Z")
                put("updated_at", "2026-10-12T00:00:00Z")
            }
        }.toString()

    @Test fun exactScopedMetadataAndNoJobAreAccepted() {
        assertEquals("queued", WorkbenchEditorParser.parse(body(), "one", "default").job?.state)
        val json = Json.parseToJsonElement(body()).jsonObject
        val empty = JsonObject(json + ("job" to kotlinx.serialization.json.JsonNull)).toString()
        assertNull(WorkbenchEditorParser.parse(empty, "one", "default").job)
    }

    @Test fun unknownFieldsVersionsStatesAndWrongScopeAreRejected() {
        for (value in listOf(
            body(company = "two"),
            body(profile = "other"),
            body(state = "shell"),
            body().replace("\"protocol_version\":1", "\"protocol_version\":2"),
            body().dropLast(1) + ",\"extra\":true}",
        )) {
            assertTrue(runCatching { WorkbenchEditorParser.parse(value, "one", "default") }.isFailure)
        }
    }

    @Test fun invalidModelMetadataAndNonBooleanConfigurationAreRejected() {
        for (value in listOf(
            body().replace("\"executor_configured\":true", "\"executor_configured\":\"true\""),
            body().replace("\"model\":\"synthetic-editor\"", "\"model\":\"\""),
            body().replace("\"proposal_hash\":null", "\"proposal_hash\":\"wrong\""),
        )) {
            assertTrue(runCatching { WorkbenchEditorParser.parse(value, "one", "default") }.isFailure)
        }
    }
}
