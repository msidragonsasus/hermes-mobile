package com.m57.hermescontrol.miniapp.workbench

import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WorkbenchRepositoryTest {
    private val api = mockk<HermesApiService>()
    private var current = true
    private val repository =
        DashboardWorkbenchRepository("https://synthetic.test\nselected\ndefault", "one", api) { current }
    private val json = "application/json".toMediaType()

    @Test fun oldServerAndDisabledGrantAreCategorizedWithoutWrites() =
        runTest {
            coEvery { api.careerWorkbench("one", "default") } returns Response.error(404, "{}".toResponseBody(json))
            try {
                repository.snapshot()
                error("expected refusal")
            } catch (error: WorkbenchUnavailable) {
                assertTrue(error.disabled)
            }
            coVerify(exactly = 0) { api.careerWorkbenchWrite(any(), any(), any()) }
        }

    @Test fun wrongCompanyResponseIsRejectedAsAWhole() =
        runTest {
            coEvery { api.careerWorkbench("one", "default") } returns
                Response.success(snapshotBody("two").toResponseBody(json))
            assertTrue(runCatching { repository.snapshot() }.isFailure)
        }

    @Test fun scopeChangingDuringReadDropsTheResult() =
        runTest {
            coEvery { api.careerWorkbench("one", "default") } answers {
                current = false
                Response.success(snapshotBody("one").toResponseBody(json))
            }
            assertTrue(runCatching { repository.snapshot() }.isFailure)
        }

    @Test fun staleScopeStopsBeforeAnyNetworkRequest() =
        runTest {
            current = false
            assertTrue(runCatching { repository.snapshot() }.isFailure)
            coVerify(exactly = 0) { api.careerWorkbench(any(), any()) }
        }

    @Test fun oversizedAndInvalidUtf8BodiesAreRejected() =
        runTest {
            coEvery { api.careerWorkbench("one", "default") } returns
                Response.success("x".repeat(240001).toResponseBody(json))
            assertTrue(runCatching { repository.snapshot() }.isFailure)
            coEvery { api.careerWorkbench("one", "default") } returns
                Response.success(byteArrayOf(0xc3.toByte(), 0x28).toResponseBody(json))
            assertTrue(runCatching { repository.snapshot() }.isFailure)
        }

    @Test fun snapshotAcceptsOnlyExactTypedContract() {
        assertEquals(fixture().active, WorkbenchParser.snapshot(snapshotBody("one"), "one", "default").active)
        for (body in listOf(
            snapshotBody("one").replace("\"protocol_version\":1", "\"protocol_version\":\"1\""),
            snapshotBody("one").replace("\"draft\":true", "\"draft\":\"true\""),
            snapshotBody("one").replace("\"revision\":1", "\"revision\":-1"),
        )) {
            assertTrue(runCatching { WorkbenchParser.snapshot(body, "one", "default") }.isFailure)
        }
    }

    @Test fun olderServerWithoutEditorEndpointKeepsManualWorkflow() =
        runTest {
            coEvery { api.careerWorkbenchEditor("one", "default") } returns
                Response.error(404, "{}".toResponseBody(json))
            assertEquals(null, repository.editor())
            coVerify(exactly = 0) { api.careerWorkbenchWrite(any(), any(), any()) }
        }

    private fun snapshotBody(company: String): String =
        buildJsonObject {
            put("protocol_version", 1)
            put("company_id", company)
            put("profile", "default")
            put("active", fixture().active)
            put("previous", JsonNull)
            put("revision", 1)
            put("draft", true)
            put("goal", "Synthetic goal")
            putJsonObject("files") { workbenchFiles.forEach { put(it, "synthetic source") } }
            put("diff", "synthetic diff")
            put("diff_truncated", false)
            put("tested", true)
        }.toString()
}
