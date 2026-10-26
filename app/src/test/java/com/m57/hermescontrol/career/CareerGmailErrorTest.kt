package com.m57.hermescontrol.career

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareerGmailErrorTest {
    @Test
    fun pageRequestIsBoundedSenderScopedAndHasNoDateCutoff() =
        kotlinx.coroutines.runBlocking {
            var observed: okhttp3.HttpUrl? = null
            val client =
                CareerGmailClient(
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            observed = chain.request().url
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("""{"messages":[{"id":"abc"}],"nextPageToken":"next"}""".toResponseBody())
                                .build()
                        }.build(),
                )
            val page = client.listPage("test", listOf("sender@example.test"), "page+/=")
            assertEquals("25", observed?.queryParameter("maxResults"))
            assertEquals("page+/=", observed?.queryParameter("pageToken"))
            assertEquals("{from:sender@example.test} -in:spam -in:trash", observed?.queryParameter("q"))
            assertEquals(listOf("abc"), page.ids)
            assertEquals("next", page.nextToken)
        }

    @Test
    fun sharedSendersAreFetchedOnceWithoutRejectingCompanies() {
        val rule = CareerMailSenderRule("rule1", 1, "company1", "sender@example.com")
        assertEquals(
            listOf(rule.address),
            gmailApprovedSenders(listOf(rule, rule.copy(id = "rule2", company_id = "company2"))),
        )
        assertEquals(
            listOf(rule.address),
            gmailApprovedSenders(listOf(rule, rule.copy(address = " SENDER@example.com "))),
        )
        assertEquals(listOf(rule.address), gmailApprovedSenders(listOf(rule, rule.copy(id = "duplicate"))))
        assertEquals(
            listOf(rule.address),
            gmailApprovedSenders(listOf(rule, rule.copy(company_id = "company2", enabled = false))),
        )
    }

    @Test
    fun retriesOnlyFailedRequestAfterRateLimit() =
        runTest {
            var calls = 0
            val client =
                fakeClient {
                    calls++
                    if (calls == 1) 403 to RATE_LIMIT else 200 to "{}"
                }
            assertEquals("{}", client.getJson("test-token", "profile").toString())
            assertEquals(2, calls)
            assertEquals(1000L, testScheduler.currentTime)
        }

    @Test
    fun permanentRateLimitHasBoundedRetries() =
        runTest {
            var calls = 0
            val client =
                fakeClient {
                    calls++
                    403 to RATE_LIMIT
                }
            val failure = runCatching { client.getJson("test-token", "profile") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(7, calls)
            assertEquals(63_000L, testScheduler.currentTime)
        }

    @Test
    fun permissionFailuresAreNotRetried() =
        runTest {
            var calls = 0
            val client =
                fakeClient {
                    calls++
                    403 to """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}"""
                }
            assertTrue(runCatching { client.getJson("test-token", "profile") }.isFailure)
            assertEquals(1, calls)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun respectsRetryAfterWithoutWaitingUnboundedly() =
        runTest {
            for (seconds in listOf("5", "120")) {
                var calls = 0
                val start = testScheduler.currentTime
                val client =
                    fakeClient(seconds) {
                        calls++
                        if (calls == 1) 429 to "{}" else 200 to "{}"
                    }
                val result = runCatching { client.getJson("test-token", "profile") }
                assertEquals(seconds == "5", result.isSuccess)
                assertEquals(if (seconds == "5") 2 else 1, calls)
                assertEquals(if (seconds == "5") 5000L else 0L, testScheduler.currentTime - start)
            }
        }

    @Test
    fun distinguishesDisabledApiFromMissingPermission() {
        val disabled = gmailApiErrorMessage(403, """{"error":{"details":[{"reason":"SERVICE_DISABLED"}]}}""")
        val scope = gmailApiErrorMessage(403, """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""")
        assertTrue(disabled.contains("Gmail APIが無効"))
        assertTrue(scope.contains("読み取り許可が不足"))
    }

    @Test
    fun rateLimitDoesNotSuggestReauthorization() {
        val result = gmailApiErrorMessage(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        assertTrue(result.contains("時間をおいて再試行"))
        assertFalse(result.contains("選び直"))
    }

    @Test
    fun unknownOrMalformedErrorsNeverExposeResponseContents() {
        for (body in listOf(
            "private-token",
            """{"error":{"message":"private-token","errors":[{"reason":"private-token"}]}}""",
        )) {
            val result = gmailApiErrorMessage(403, body)
            assertTrue(result.contains("403"))
            assertFalse(result.contains("private-token"))
        }
    }

    private fun fakeClient(
        retryAfter: String? = null,
        reply: () -> Pair<Int, String>,
    ): CareerGmailClient =
        CareerGmailClient(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (code, body) = reply()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody())
                        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                        .build()
                }.build(),
        )

    private companion object {
        const val RATE_LIMIT = """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""
    }
}
