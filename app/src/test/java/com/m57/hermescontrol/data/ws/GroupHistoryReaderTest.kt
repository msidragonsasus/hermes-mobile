package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.ProfileScopeInterceptor
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class GroupHistoryReaderTest {
    private lateinit var server: MockWebServer
    private lateinit var reader: GroupHistoryReader

    @Before
    fun setup() {
        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns MutableStateFlow("global-selected")
        server = MockWebServer().also { it.start() }
        val api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/proxy/"))
                .client(OkHttpClient.Builder().addInterceptor(ProfileScopeInterceptor).build())
                .addConverterFactory(
                    Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()),
                ).build()
                .create(HermesApiService::class.java)
        reader = GroupHistoryReader(api)
    }

    @After
    fun teardown() {
        server.shutdown()
        unmockkObject(AuthManager)
    }

    @Test
    fun absentSessionRequiresTwoExactKeyReadsInTheOriginalBotProfile() =
        runBlocking {
            repeat(2) { error(404, "Session not found") }
            assertEquals(GroupHistoryIssue.NOT_FOUND, reader.read("idea1", "saved-key") { true }.issue)
            assertRequest("/proxy/api/sessions/saved-key/messages", 0)
            assertRequest("/proxy/api/sessions/saved-key", null)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun genericRoute404CannotBeReportedAsAnAbsentSession() =
        runBlocking {
            error(404, "Not Found")
            server.enqueue(MockResponse().setBody("{\"session_id\":\"saved-key\"}"))
            assertEquals(GroupHistoryIssue.ENDPOINT_UNAVAILABLE, reader.read("idea1", "saved-key") { true }.issue)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun proxyHtmlAndUnknown404RemainEndpointFailures() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"))
            error(404, "Not Found")
            assertEquals(GroupHistoryIssue.ENDPOINT_UNAVAILABLE, reader.read("idea1", "saved-key") { true }.issue)
        }

    @Test
    fun missingProfileNeverFallsBackToDefaultOrEnumeratesSessions() =
        runBlocking {
            error(404, "Profile 'idea1' does not exist.")
            assertEquals(GroupHistoryIssue.PROFILE_NOT_FOUND, reader.read("idea1", "saved-key") { true }.issue)
            assertRequest("/proxy/api/sessions/saved-key/messages", 0)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun permissionAndUnavailableReadsAreNotMissingSessions() =
        runBlocking {
            for ((code, issue) in listOf(
                401 to GroupHistoryIssue.AUTH_REQUIRED,
                403 to GroupHistoryIssue.AUTH_REQUIRED,
                503 to GroupHistoryIssue.UNAVAILABLE,
            )) {
                error(code, "Synthetic unavailable")
                assertEquals(issue, reader.read("idea1", "saved-key") { true }.issue)
            }
            assertEquals(3, server.requestCount)
        }

    @Test
    fun changedCanonicalIdCannotInjectAnotherTranscript() =
        runBlocking {
            page(0, 1, id = "different-id")
            val result = reader.read("idea1", "saved-key") { true }
            assertEquals(GroupHistoryIssue.ID_MISMATCH, result.issue)
            assertNull(result.messages)
        }

    @Test
    fun latestPagesAreCombinedChronologicallyWithFixedProfileAndKey() =
        runBlocking {
            page(0, 200, start = 2)
            page(200, 1, start = 1)
            val result = reader.read("idea1", "saved-key") { true }
            assertTrue(result.completeHistory)
            assertEquals((1..201).toList(), result.messages!!.map { it.id })
            assertRequest("/proxy/api/sessions/saved-key/messages", 0)
            assertRequest("/proxy/api/sessions/saved-key/messages", 200)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun legacyUnpagedResponseDoesNotClaimAnExhaustiveSearch() =
        runBlocking {
            page(0, 200, echo = false)
            val result = reader.read("idea1", "saved-key") { true }
            assertEquals(200, result.messages!!.size)
            assertFalse(result.completeHistory)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun repeatedPagesStopRatherThanLoopOrClaimAllHistoryWasRead() =
        runBlocking {
            page(0, 200)
            page(200, 200)
            val result = reader.read("idea1", "saved-key") { true }
            assertFalse(result.completeHistory)
            assertEquals(200, result.messages!!.size)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun pagingIsBoundedWithoutPretendingTheRemainingHistoryIsAbsent() =
        runBlocking {
            repeat(5) { page(it * 200, 200, start = 1001 - it * 200) }
            val result = reader.read("idea1", "saved-key") { true }
            assertEquals(1000, result.messages!!.size)
            assertFalse(result.completeHistory)
            assertEquals(5, server.requestCount)
        }

    @Test
    fun connectionChangeDiscardsTheResponseAndDoesNotStartASecondRead() =
        runBlocking {
            page(0, 200)
            var checks = 0
            val result = reader.read("idea1", "saved-key") { ++checks == 1 }
            assertEquals(GroupHistoryIssue.CONNECTION_CHANGED, result.issue)
            assertNull(result.messages)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun oldResponsesWithoutCanonicalIdStillWorkAndEmptyHistoryIsNot404() =
        runBlocking {
            server.enqueue(MockResponse().setBody("{\"messages\":[]}"))
            val result = reader.read("idea1", "saved-key") { true }
            assertEquals(emptyList<Any>(), result.messages)
            assertNull(result.issue)
            assertTrue(result.completeHistory)
        }

    private fun error(
        code: Int,
        detail: String,
    ) {
        server.enqueue(
            MockResponse().setResponseCode(code).setBody(buildJsonObject { put("detail", detail) }.toString()),
        )
    }

    private fun page(
        offset: Int,
        size: Int,
        start: Int = 1,
        id: String = "saved-key",
        echo: Boolean = true,
    ) {
        val body =
            buildJsonObject {
                put("session_id", id)
                put(
                    "messages",
                    buildJsonArray {
                        repeat(size) { index ->
                            add(
                                buildJsonObject {
                                    put("id", start + index)
                                    put("role", "assistant")
                                    put("content", "Synthetic row")
                                },
                            )
                        }
                    },
                )
                if (echo) {
                    put(
                        "pagination",
                        buildJsonObject {
                            put("order", "latest")
                            put("offset", offset)
                            put("returned", size)
                            put("limit", 200)
                        },
                    )
                }
            }
        server.enqueue(MockResponse().setBody(body.toString()))
    }

    private fun assertRequest(
        path: String,
        offset: Int?,
    ) {
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(path, request.requestUrl!!.encodedPath)
        assertEquals("idea1", request.requestUrl!!.queryParameter("profile"))
        assertEquals(1, request.requestUrl!!.queryParameterValues("profile").size)
        if (offset != null) assertEquals(offset.toString(), request.requestUrl!!.queryParameter("offset"))
    }
}
