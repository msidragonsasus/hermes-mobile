package com.m57.hermescontrol.openclaw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenClawTitlesTest {
    private fun client(server: MockWebServer): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                assertEquals("https://openrouter.ai/api/v1/chat/completions", chain.request().url.toString())
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .url(server.url("/title"))
                        .build(),
                )
            }.build()

    @Test
    fun freeTitleIsReadWithoutSendingGatewayCredentials() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"「旅行の準備」"}}]}"""))
            server.start()
            try {
                assertEquals("旅行の準備", OpenClawTitles(client(server)).generate("title-key", "旅行を計画したい"))
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("Bearer title-key", request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                assertFalse(request.body.readUtf8().contains("gateway-token"))
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun freeRateLimitFailsWithoutPaidFallbackOrAutomaticRetry() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(429).setBody("secret server response"))
            server.start()
            try {
                val result = runCatching { OpenClawTitles(client(server)).generate("key", "hello") }
                assertTrue(result.isFailure)
                assertEquals("TITLE_UNAVAILABLE", result.exceptionOrNull()?.message)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun retiringOwnerCancelsOutstandingTitleRequest() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            try {
                val request = async { OpenClawTitles(client(server)).generate("key", "hello") }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                }
                request.cancel()
                try {
                    withTimeout(1000) { request.await() }
                    fail("Cancelled title generation must not complete")
                } catch (_: CancellationException) {
                }
            } finally {
                server.shutdown()
            }
        }
}
