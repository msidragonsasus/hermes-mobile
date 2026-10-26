package com.m57.hermescontrol.miniapp.sdui

import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSduiRepositoryTest {
    private val api = mockk<HermesApiService>()
    private var current = true
    private val scope = "https://hermes.test\nselected\nprofile-a"
    private val manifest =
        SduiManifest("career.mypage", 1, 1, "bundled-1", setOf("mypage.read", "mypage.check"), "company")
    private val screen =
        SduiScreen("career.mypage", 1, 1, "bundled-1", "c1", "profile-a", "マイページ", "ready", listOf(SduiText("本文")))
    private val json = "application/json".toMediaType()

    private fun repository(dispatcher: CoroutineDispatcher) =
        DashboardSduiRepository(scope, api, { current }, dispatcher)

    private fun ok(body: String): Response<ResponseBody> = Response.success(body.toResponseBody(json))

    private fun failure(code: Int): Response<ResponseBody> = Response.error(code, "{}".toResponseBody(json))

    private fun failure(
        code: Int,
        body: String,
    ): Response<ResponseBody> = Response.error(code, body.toResponseBody(json))

    private fun coded(code: String) = """{"detail":{"code":"$code"}}"""

    /** A body whose source is endless; [pulled] counts what the host actually asked it for. */
    private class EndlessBody(
        private val prefix: String = "",
    ) : ResponseBody() {
        var pulled = 0L
        private var prefixPending = prefix.isNotEmpty()

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val filler = ByteArray(byteCount.toInt()) { ' '.code.toByte() }
                    val bytes = if (prefixPending) prefix.toByteArray() + filler else filler
                    prefixPending = false
                    sink.write(bytes)
                    pulled += bytes.size
                    return bytes.size.toLong()
                }

                override fun timeout() = Timeout.NONE

                override fun close() = Unit
            }.buffer()
    }

    private fun screenBody(company: String = "c1") =
        """{"app_id":"career.mypage","protocol_version":1,"component_version":1,"release_id":"bundled-1",""" +
            """"company_id":"$company","profile":"profile-a","title":"マイページ","state":"ready",""" +
            """"components":[{"type":"Text","text":"本文"}]}"""

    private fun receiptBody(
        operation: String,
        release: String = "bundled-1",
    ) = """{"protocol_version":1,"release_id":"$release","company_id":"c1","profile":"profile-a",""" +
        """"operation_id":"$operation","state":"queued","run_id":"run-1"}"""

    /** Flips the connection when the host starts reading the body, or fails to read it when [text] is null. */
    private fun flipping(text: String?): ResponseBody =
        object : ResponseBody() {
            override fun contentType() = json

            override fun contentLength() = -1L

            override fun source(): BufferedSource {
                current = false
                if (text == null) throw IOException("unreadable")
                return Buffer().writeUtf8(text)
            }
        }

    // ── reads ───────────────────────────────────────────────────────────

    @Test
    fun readsUseTheProfileOfTheCapturedScope() =
        runTest {
            coEvery { api.careerMobileManifest("profile-a") } returns
                ok(
                    """{"app_id":"career.mypage","protocol_version":1,"component_version":1,""" +
                        """"release_id":"bundled-1","permissions":["mypage.read","mypage.check"],"route":"company"}""",
                )
            coEvery { api.careerMobileSduiCompany("c1", "profile-a") } returns ok(screenBody())
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            assertEquals(SduiFetch.Ok(manifest), repo.manifest())
            assertEquals(SduiFetch.Ok(screen), repo.screen(manifest, "c1"))
        }

    @Test
    fun httpStatusesMapToHostStates() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            coEvery { api.careerMobileManifest(any()) } returns failure(404)
            assertEquals(SduiFetch.NotFound, repo.manifest())
            coEvery { api.careerMobileManifest(any()) } returns failure(401)
            assertEquals(SduiFetch.Unauthorized, repo.manifest())
            coEvery { api.careerMobileManifest(any()) } returns failure(403)
            assertEquals(SduiFetch.Unauthorized, repo.manifest())
            coEvery { api.careerMobileManifest(any()) } returns failure(500)
            assertEquals(SduiFetch.Unavailable, repo.manifest())
            coEvery { api.careerMobileManifest(any()) } throws IOException("offline")
            assertEquals(SduiFetch.Unavailable, repo.manifest())
        }

    @Test
    fun serverErrorMessagesAreNeverSurfaced() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns
                Response.error(500, """{"detail":"secret-token-123"}""".toResponseBody(json))
            val result = repo.screen(manifest, "c1")
            assertEquals(SduiFetch.Unavailable, result)
            assertTrue("secret" !in result.toString())
        }

    @Test
    fun anOversizeBodyIsRefusedWithoutBeingParsed() =
        runTest {
            val padding = " ".repeat(SduiContract.MAX_BODY_BYTES)
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns ok(screenBody() + padding)
            val result = repository(UnconfinedTestDispatcher(testScheduler)).screen(manifest, "c1")
            assertEquals(SduiFetch.Rejected(SduiRejection.TOO_LARGE), result)
        }

    @Test
    fun aBodyForAnotherCompanyIsRejected() =
        runTest {
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns ok(screenBody(company = "other"))
            val result = repository(UnconfinedTestDispatcher(testScheduler)).screen(manifest, "c1")
            assertEquals(SduiFetch.Rejected(SduiRejection.IDENTITY_MISMATCH), result)
        }

    @Test
    fun aSwitchedConnectionDropsTheReadResult() =
        runTest {
            coEvery { api.careerMobileSduiCompany(any(), any()) } coAnswers
                {
                    current = false
                    ok(screenBody())
                }
            val result = repository(UnconfinedTestDispatcher(testScheduler)).screen(manifest, "c1")
            assertEquals(SduiFetch.ConnectionChanged, result)
        }

    @Test
    fun noReadIsMadeOnceTheConnectionHasChanged() =
        runTest {
            current = false
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            assertEquals(SduiFetch.ConnectionChanged, repo.manifest())
            coVerify(exactly = 0) { api.careerMobileManifest(any()) }
        }

    // ── the write ───────────────────────────────────────────────────────

    @Test
    fun checkSendsTheExactContractBody() =
        runTest {
            val body = slot<SduiMyPageCheckRequest>()
            coEvery { api.careerMobileMyPageCheck("profile-a", capture(body)) } returns ok(receiptBody("op-1"))
            val result = repository(UnconfinedTestDispatcher(testScheduler)).check(screen, "op-1")
            assertTrue(result is SduiCheckResult.Accepted)
            assertEquals(
                """{"protocol_version":1,"release_id":"bundled-1","company_id":"c1","operation_id":"op-1"}""",
                Json.encodeToString(body.captured),
            )
        }

    @Test
    fun checkMapsDefinitiveAndAmbiguousStatuses() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val expected =
                mapOf(
                    // A 409 without a trusted machine code is never a known answer.
                    409 to SduiCheckResult.Ambiguous,
                    401 to SduiCheckResult.Unauthorized,
                    403 to SduiCheckResult.Unauthorized,
                    404 to SduiCheckResult.NotFound,
                    400 to SduiCheckResult.Refused,
                    422 to SduiCheckResult.Refused,
                    429 to SduiCheckResult.Refused,
                    408 to SduiCheckResult.Ambiguous,
                    500 to SduiCheckResult.Ambiguous,
                    503 to SduiCheckResult.Ambiguous,
                )
            expected.forEach { (code, result) ->
                coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(code)
                assertEquals("HTTP $code", result, repo.check(screen, "op-1"))
            }
        }

    @Test
    fun checkTransportFailureIsAmbiguous() =
        runTest {
            coEvery { api.careerMobileMyPageCheck(any(), any()) } throws IOException("timeout")
            val result = repository(UnconfinedTestDispatcher(testScheduler)).check(screen, "op-1")
            assertEquals(SduiCheckResult.Ambiguous, result)
        }

    @Test
    fun anUntrustworthyReceiptIsAmbiguousNotAccepted() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns ok(receiptBody("someone-else"))
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns ok("not json")
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns ok("")
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
        }

    @Test
    fun checkIsNotSentWhenTheConnectionAlreadyChanged() =
        runTest {
            current = false
            val result = repository(UnconfinedTestDispatcher(testScheduler)).check(screen, "op-1")
            assertEquals(SduiCheckResult.NotSent, result)
            coVerify(exactly = 0) { api.careerMobileMyPageCheck(any(), any()) }
        }

    @Test
    fun aConnectionSwitchDuringTheWriteDropsTheReceipt() =
        runTest {
            coEvery { api.careerMobileMyPageCheck(any(), any()) } coAnswers
                {
                    current = false
                    ok(receiptBody("op-1"))
                }
            val result = repository(UnconfinedTestDispatcher(testScheduler)).check(screen, "op-1")
            assertEquals(SduiCheckResult.ConnectionChanged, result)
        }

    // ── the write: machine codes of HTTP errors ─────────────────────────

    @Test
    fun a409IsClassifiedOnlyByExactTrustedMachineCodes() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val expected =
                mapOf(
                    "mobile_release_changed" to SduiCheckResult.ReleaseMismatch,
                    "operation_conflict" to SduiCheckResult.OperationConflict,
                    "mypage_not_connected" to SduiCheckResult.Refused,
                    "company_closed" to SduiCheckResult.Refused,
                    "company_missing" to SduiCheckResult.Refused,
                    "operation_pending" to SduiCheckResult.Ambiguous,
                    "something_new" to SduiCheckResult.Ambiguous,
                    "MOBILE_RELEASE_CHANGED" to SduiCheckResult.Ambiguous,
                    "mobile_release_changed " to SduiCheckResult.Ambiguous,
                    "" to SduiCheckResult.Ambiguous,
                )
            expected.forEach { (code, result) ->
                coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(409, coded(code))
                assertEquals("409 $code", result, repo.check(screen, "op-1"))
            }
        }

    @Test
    fun anUnknownOrUnreadable409StaysAmbiguous() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val bodies =
                listOf(
                    "",
                    "{}",
                    "not json",
                    "[]",
                    """{"code":"mobile_release_changed"}""",
                    """{"detail":"mobile_release_changed"}""",
                    """{"detail":{"code":1}}""",
                    """{"detail":{"code":null}}""",
                    """{"detail":{"code":["mobile_release_changed"]}}""",
                    """{"detail":[{"code":"mobile_release_changed"}]}""",
                    """{"detail":{"detail":{"code":"mobile_release_changed"}}}""",
                )
            bodies.forEach { body ->
                coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(409, body)
                assertEquals(body, SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
            }
            // Not strict UTF-8.
            val invalid = """{"detail":{"code":"mobile_release_changed"}}""".toByteArray() + byteArrayOf(0xFF.toByte())
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns
                Response.error(409, invalid.toResponseBody(json))
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
        }

    @Test
    fun anErrorBodyIsReadOnlyUpToFourKiB() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val code = coded("mobile_release_changed")
            val atLimit = code + " ".repeat(SduiContract.MAX_ERROR_BODY_BYTES - code.length)
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(409, atLimit)
            assertEquals(SduiCheckResult.ReleaseMismatch, repo.check(screen, "op-1"))

            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(409, atLimit + " ")
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))

            val endless = EndlessBody(prefix = code)
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns Response.error(409, endless)
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
            assertTrue("pulled ${endless.pulled}", endless.pulled < SduiContract.MAX_ERROR_BODY_BYTES + 3 * 8192)
        }

    @Test
    fun otherStatusesAndCodesMapToTheirOwnOutcomes() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val expected =
                listOf(
                    Triple(503, coded("operation_pending"), SduiCheckResult.Ambiguous),
                    Triple(404, coded("company_missing"), SduiCheckResult.Refused),
                    Triple(404, coded("something_new"), SduiCheckResult.NotFound),
                    Triple(404, "not json", SduiCheckResult.NotFound),
                    Triple(400, coded("invalid_mobile_action"), SduiCheckResult.Refused),
                    Triple(413, coded("payload_too_large"), SduiCheckResult.Refused),
                    // Only 409 and 404 are looked into; a code on another status changes nothing.
                    Triple(400, coded("mobile_release_changed"), SduiCheckResult.Refused),
                    Triple(500, coded("mobile_release_changed"), SduiCheckResult.Ambiguous),
                )
            expected.forEach { (status, body, result) ->
                coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(status, body)
                assertEquals("$status $body", result, repo.check(screen, "op-1"))
            }
        }

    @Test
    fun errorBodyTextIsNeverSurfaced() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val known = """{"detail":{"code":"mobile_release_changed","message":"secret-token-123"}}"""
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns failure(409, known)
            assertTrue("secret" !in repo.check(screen, "op-1").toString())

            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns
                failure(409, """{"detail":"secret-token-123"}""")
            val unknown = repo.check(screen, "op-1")
            assertEquals(SduiCheckResult.Ambiguous, unknown)
            assertTrue("secret" !in unknown.toString())
        }

    // ── the write: the release the request names ────────────────────────

    @Test
    fun aRetryNamesTheOriginalReleaseAndOnlyThatReleaseIsAccepted() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val body = slot<SduiMyPageCheckRequest>()
            coEvery { api.careerMobileMyPageCheck("profile-a", capture(body)) } returns
                ok(receiptBody("op-1", release = "bundled-0"))
            // The screen on display is on bundled-1; the first request was sent against bundled-0.
            val result = repo.check(screen, "op-1", "bundled-0")
            assertEquals("bundled-0", (result as SduiCheckResult.Accepted).receipt.releaseId)
            assertEquals(
                """{"protocol_version":1,"release_id":"bundled-0","company_id":"c1","operation_id":"op-1"}""",
                Json.encodeToString(body.captured),
            )

            // A receipt for the displayed release is not the receipt of that request.
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns ok(receiptBody("op-1"))
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1", "bundled-0"))
        }

    // ── the write: a scope change while the host waits ──────────────────

    @Test
    fun aSwitchDuringAFailedPostIsAConnectionChangeNotAnAmbiguousAnswer() =
        runTest {
            coEvery { api.careerMobileMyPageCheck(any(), any()) } coAnswers
                {
                    current = false
                    throw IOException("reset")
                }
            val result = repository(UnconfinedTestDispatcher(testScheduler)).check(screen, "op-1")
            assertEquals(SduiCheckResult.ConnectionChanged, result)
        }

    @Test
    fun aSwitchWhileTheReceiptBodyIsReadDropsTheReceipt() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns
                Response.success(flipping(receiptBody("op-1")))
            assertEquals(SduiCheckResult.ConnectionChanged, repo.check(screen, "op-1"))

            current = true
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns Response.success(flipping(null))
            assertEquals(SduiCheckResult.ConnectionChanged, repo.check(screen, "op-1"))
        }

    @Test
    fun aSwitchWhileAnErrorBodyIsReadDropsTheAnswer() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val body = flipping(coded("mobile_release_changed"))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns Response.error(409, body)
            assertEquals(SduiCheckResult.ConnectionChanged, repo.check(screen, "op-1"))
        }

    // ── bounded reads ───────────────────────────────────────────────────

    @Test
    fun aBodyOfExactlyTheLimitIsAcceptedAndOneByteMoreIsNot() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val base = screenBody()
            val exact = base + " ".repeat(SduiContract.MAX_BODY_BYTES - base.toByteArray().size)
            assertEquals(SduiContract.MAX_BODY_BYTES, exact.toByteArray().size)
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns ok(exact)
            assertEquals(SduiFetch.Ok(screen), repo.screen(manifest, "c1"))

            coEvery { api.careerMobileSduiCompany(any(), any()) } returns ok("$exact ")
            assertEquals(SduiFetch.Rejected(SduiRejection.TOO_LARGE), repo.screen(manifest, "c1"))
        }

    @Test
    fun anEndlessBodyIsNeverReadBeyondTheLimit() =
        runTest {
            val repo = repository(UnconfinedTestDispatcher(testScheduler))
            val read = EndlessBody()
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns Response.success(read)
            assertEquals(SduiFetch.Rejected(SduiRejection.TOO_LARGE), repo.screen(manifest, "c1"))
            assertTrue("pulled ${read.pulled}", read.pulled < SduiContract.MAX_BODY_BYTES + 3 * 8192)

            val receipt = EndlessBody(prefix = receiptBody("op-1"))
            coEvery { api.careerMobileMyPageCheck(any(), any()) } returns Response.success(receipt)
            assertEquals(SduiCheckResult.Ambiguous, repo.check(screen, "op-1"))
            assertTrue("pulled ${receipt.pulled}", receipt.pulled < SduiContract.MAX_BODY_BYTES + 3 * 8192)
        }

    @Test
    fun aBodyThatIsNotStrictUtf8IsRejected() =
        runTest {
            val invalid = screenBody().toByteArray() + byteArrayOf(0xFF.toByte())
            coEvery { api.careerMobileSduiCompany(any(), any()) } returns Response.success(invalid.toResponseBody(json))
            val result = repository(UnconfinedTestDispatcher(testScheduler)).screen(manifest, "c1")
            assertEquals(SduiFetch.Rejected(SduiRejection.MALFORMED), result)
        }
}
