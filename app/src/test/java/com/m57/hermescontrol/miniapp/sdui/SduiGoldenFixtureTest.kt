package com.m57.hermescontrol.miniapp.sdui

import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The JSON bytes the backend contract test generated in `src/test/resources/sdui/`, pushed through
 * the real parser and repository. Expectations are read from the fixtures themselves, so this fails
 * when either side drifts, not when a fixture value is merely changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SduiGoldenFixtureTest {
    private val json = "application/json".toMediaType()

    private fun fixture(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/sdui/$name.json")
        assertNotNull("golden fixture sdui/$name.json is missing; the backend contract test generates it", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun field(
        bytes: ByteArray,
        key: String,
    ): String =
        Json
            .parseToJsonElement(String(bytes, Charsets.UTF_8))
            .jsonObject
            .getValue(key)
            .jsonPrimitive.content

    private fun manifest(): SduiManifest {
        val parsed = SduiParser.parseManifest(fixture("manifest"))
        assertTrue("manifest: $parsed", parsed is SduiParsed.Valid)
        return (parsed as SduiParsed.Valid).value
    }

    private fun withExtraField(bytes: ByteArray): ByteArray {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        return JsonObject(root + ("unexpected" to JsonPrimitive(1))).toString().toByteArray(Charsets.UTF_8)
    }

    // ── parser ──────────────────────────────────────────────────────────

    @Test
    fun theBackendManifestIsUnderstood() {
        val manifest = manifest()
        assertEquals(SduiContract.APP_ID, manifest.appId)
        assertEquals(SduiContract.ROUTE, manifest.route)
        assertTrue(SduiContract.PERMISSION_READ in manifest.permissions)
        assertEquals(field(fixture("manifest"), "release_id"), manifest.releaseId)
    }

    @Test
    fun theBackendScreenIsUnderstoodForItsOwnCompanyAndProfile() {
        val bytes = fixture("screen")
        val company = field(bytes, "company_id")
        val profile = field(bytes, "profile")
        val parsed = SduiParser.parseScreen(bytes, manifest(), company, profile)
        assertTrue("screen: $parsed", parsed is SduiParsed.Valid)
        val screen = (parsed as SduiParsed.Valid).value
        assertEquals(manifest().releaseId, screen.releaseId)
        assertTrue(screen.components.isNotEmpty())

        // The same bytes for any other company or profile are not this screen.
        assertEquals(
            SduiParsed.Rejected(SduiRejection.IDENTITY_MISMATCH),
            SduiParser.parseScreen(bytes, manifest(), company + "-other", profile),
        )
        assertEquals(
            SduiParsed.Rejected(SduiRejection.IDENTITY_MISMATCH),
            SduiParser.parseScreen(bytes, manifest(), company, profile + "-other"),
        )
    }

    @Test
    fun theBackendReceiptIsUnderstoodForItsOwnRequestOnly() {
        val bytes = fixture("receipt")
        val expected =
            SduiReceiptExpectation(
                releaseId = field(bytes, "release_id"),
                companyId = field(bytes, "company_id"),
                profile = field(bytes, "profile"),
                operationId = field(bytes, "operation_id"),
            )
        val parsed = SduiParser.parseReceipt(bytes, expected)
        assertTrue("receipt: $parsed", parsed is SduiParsed.Valid)
        assertEquals(expected.operationId, (parsed as SduiParsed.Valid).value.operationId)

        assertEquals(
            SduiParsed.Rejected(SduiRejection.IDENTITY_MISMATCH),
            SduiParser.parseReceipt(bytes, expected.copy(operationId = expected.operationId + "-x")),
        )
        assertEquals(
            SduiParsed.Rejected(SduiRejection.RELEASE_MISMATCH),
            SduiParser.parseReceipt(bytes, expected.copy(releaseId = expected.releaseId + "-x")),
        )
    }

    @Test
    fun anAddedFieldMakesEveryGoldenPayloadRefused() {
        val screenBytes = fixture("screen")
        val receiptBytes = fixture("receipt")
        assertEquals(
            SduiParsed.Rejected(SduiRejection.UNKNOWN_FIELD),
            SduiParser.parseManifest(withExtraField(fixture("manifest"))),
        )
        assertEquals(
            SduiParsed.Rejected(SduiRejection.UNKNOWN_FIELD),
            SduiParser.parseScreen(
                withExtraField(screenBytes),
                manifest(),
                field(screenBytes, "company_id"),
                field(screenBytes, "profile"),
            ),
        )
        assertEquals(
            SduiParsed.Rejected(SduiRejection.UNKNOWN_FIELD),
            SduiParser.parseReceipt(
                withExtraField(receiptBytes),
                SduiReceiptExpectation(
                    field(receiptBytes, "release_id"),
                    field(receiptBytes, "company_id"),
                    field(receiptBytes, "profile"),
                    field(receiptBytes, "operation_id"),
                ),
            ),
        )
    }

    @Test
    fun goldenPayloadsFitTheBodyLimit() {
        listOf("manifest", "screen", "receipt").forEach {
            assertTrue(it, fixture(it).size <= SduiContract.MAX_BODY_BYTES)
        }
    }

    // ── through the repository ──────────────────────────────────────────

    @Test
    fun theRepositoryReadsAndWritesAgainstTheBackendBytes() =
        runTest {
            val screenBytes = fixture("screen")
            val receiptBytes = fixture("receipt")
            val company = field(screenBytes, "company_id")
            val profile = field(screenBytes, "profile")
            assertEquals("receipt and screen disagree about the profile", profile, field(receiptBytes, "profile"))
            assertEquals("receipt and screen disagree about the company", company, field(receiptBytes, "company_id"))

            val api = mockk<HermesApiService>()
            coEvery { api.careerMobileManifest(profile) } returns
                Response.success(fixture("manifest").toResponseBody(json))
            coEvery { api.careerMobileSduiCompany(company, profile) } returns
                Response.success(screenBytes.toResponseBody(json))
            val request = slot<SduiMyPageCheckRequest>()
            coEvery { api.careerMobileMyPageCheck(profile, capture(request)) } returns
                Response.success(receiptBytes.toResponseBody(json))
            val repository =
                DashboardSduiRepository(
                    scope = "https://hermes.test\nselected\n$profile",
                    api = api,
                    current = { true },
                    io = UnconfinedTestDispatcher(testScheduler),
                )

            val manifest = (repository.manifest() as SduiFetch.Ok).value
            val screen = (repository.screen(manifest, company) as SduiFetch.Ok).value
            val operation = field(receiptBytes, "operation_id")
            val result = repository.check(screen, operation, field(receiptBytes, "release_id"))

            assertTrue("check: $result", result is SduiCheckResult.Accepted)
            assertEquals(operation, request.captured.operation_id)
            assertEquals(company, request.captured.company_id)
            assertEquals(field(receiptBytes, "release_id"), request.captured.release_id)
            assertEquals(SduiContract.PROTOCOL_VERSION, request.captured.protocol_version)
        }
}
