package com.m57.hermescontrol.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real localhost transport: small inherited bounds keep failures finite. */
class UpdateCallTransportTest {
    private fun client(millis: Long) =
        OkHttpClient
            .Builder()
            .callTimeout(millis, TimeUnit.MILLISECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    private suspend fun metadata(
        server: MockWebServer,
        base: OkHttpClient,
    ): ServerLatest {
        val scope = TEST_SERVER_SCOPE.copy(baseUrl = server.url("/").toString())
        val scopes = FakeUpdateScopes(serverScope = scope)
        return AppUpdateChecker(
            upstreamClient = base,
            bindings = scopes::bind,
            serverClientFactory = { boundUpdateClient(it, base) },
        ).fetchServerLatest(scope)
    }

    @Test
    fun metadataTrickleCannotKeepWholeCallAlive() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(
                    MockResponse()
                        .setBody(" ".repeat(100))
                        .throttleBody(1, 80, TimeUnit.MILLISECONDS),
                )
                val start = System.nanoTime()
                try {
                    metadata(server, client(400))
                    fail("expected whole-call timeout")
                } catch (_: IOException) {
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun metadataCancellationCancelsActualBlockedCall() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            val actual = AtomicReference<Call>()
            val base =
                client(1500)
                    .newBuilder()
                    .eventListener(
                        object : EventListener() {
                            override fun callStart(call: Call) {
                                actual.set(call)
                            }
                        },
                    ).build()
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val job = async { metadata(server, base) }
                withTimeout(3000) { while (server.requestCount == 0) delay(10) }
                val start = System.nanoTime()
                job.cancel()
                try {
                    job.await()
                    fail("cancellation must propagate")
                } catch (_: CancellationException) {
                }
                assertTrue("actual blocking call must be cancelled", actual.get().isCanceled())
                assertTrue(
                    "cancellation must not wait for inherited deadline",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900,
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun metadataCancellationDuringBodyCancelsActualBlockedCall() =
        runBlocking {
            blockedBodyCancellation(apk = false)
        }

    @Test
    fun apkCancellationDuringBodyCancelsActualBlockedCallAndCleansFiles() =
        runBlocking {
            blockedBodyCancellation(apk = true)
        }

    private suspend fun blockedBodyCancellation(apk: Boolean) {
        val server = MockWebServer().apply { start() }
        val dir = Files.createTempDirectory("update-blocked-cancel").toFile()
        val dest = File(dir, "attempt.apk")
        val headers = CountDownLatch(1)
        val actual = AtomicReference<Call>()
        val base =
            client(2000)
                .newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            actual.set(call)
                        }

                        override fun responseHeadersEnd(
                            call: Call,
                            response: okhttp3.Response,
                        ) {
                            headers.countDown()
                        }
                    },
                ).build()
        try {
            server.enqueue(MockResponse().setBody("test").setBodyDelay(5, TimeUnit.SECONDS))
            kotlinx.coroutines.supervisorScope {
                val job =
                    async {
                        if (apk) {
                            UpdateDownloader(base).download(server.url("/apk"), 4, dest) {}
                        } else {
                            metadata(server, base)
                        }
                    }
                withTimeout(3000) { while (headers.count != 0L) delay(10) }
                // The response headers arrived, but its first body byte is blocked.
                delay(50)
                val start = System.nanoTime()
                job.cancel()
                try {
                    job.await()
                    fail("cancellation must propagate")
                } catch (_: CancellationException) {
                }
                assertTrue(actual.get().isCanceled())
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900)
                assertFalse(dest.exists())
                assertFalse(File(dest.path + ".part").exists())
            }
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun apkTrickleTimesOutAndDeletesAttemptFiles() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            val dir = Files.createTempDirectory("apk-trickle").toFile()
            try {
                server.enqueue(
                    MockResponse()
                        .setBody("x".repeat(100))
                        .throttleBody(1, 80, TimeUnit.MILLISECONDS),
                )
                val dest = File(dir, "attempt.apk")
                val progress = mutableListOf<Float>()
                try {
                    UpdateDownloader(client(400)).download(server.url("/apk"), 100, dest) { progress.add(it) }
                    fail("expected timeout")
                } catch (e: UpdateException) {
                    assertEquals(UpdateFailure.NETWORK, e.failure)
                }
                assertTrue(progress.isNotEmpty())
                assertTrue(progress.all { it < 1f })
                assertFalse(dest.exists())
                assertFalse(File(dest.path + ".part").exists())
            } finally {
                server.shutdown()
                dir.deleteRecursively()
            }
        }

    @Test
    fun chunkedExpectedBytesWithoutEofNeverReportCompletionAndTimeOut() =
        runBlocking {
            chunkedFixture(finish = false)
        }

    @Test
    fun chunkedCompletionIsReportedOnlyAfterEofAndRename() =
        runBlocking {
            chunkedFixture(finish = true)
        }

    private suspend fun chunkedFixture(finish: Boolean) {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        val sent = CountDownLatch(1)
        val worker =
            thread(name = "updater-withheld-eof") {
                listener.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val output = socket.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n4\r\ntest\r\n"
                            .toByteArray(),
                    )
                    output.flush()
                    sent.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    if (finish) {
                        output.write("0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
        val dir = Files.createTempDirectory("apk-eof").toFile()
        val dest = File(dir, "attempt.apk")
        val progress = Collections.synchronizedList(mutableListOf<Float>())
        try {
            kotlinx.coroutines.supervisorScope {
                val job =
                    async {
                        UpdateDownloader(client(if (finish) 2000 else 500)).download(
                            "http://127.0.0.1:${listener.localPort}/apk".toHttpUrl(),
                            4,
                            dest,
                        ) {
                            if (it == 1f) {
                                assertTrue("completion requires renamed destination", dest.exists())
                                assertFalse(File(dest.path + ".part").exists())
                            }
                            progress.add(it)
                        }
                    }
                withTimeout(3000) { while (sent.count != 0L || progress.isEmpty()) delay(10) }
                assertTrue("expected bytes are not EOF", progress.all { it < 1f })
                assertFalse(dest.exists())
                if (finish) {
                    release.countDown()
                    val result = job.await()
                    assertEquals("test", result.file.readText())
                    assertEquals(sha256Hex(dest), result.sha256)
                    assertEquals(1f, progress.last())
                } else {
                    try {
                        job.await()
                        fail("withheld EOF must time out")
                    } catch (
                        e: UpdateException,
                    ) {
                        assertEquals(UpdateFailure.NETWORK, e.failure)
                    }
                    assertFalse(dest.exists())
                    assertFalse(File(dest.path + ".part").exists())
                }
            }
        } finally {
            release.countDown()
            listener.close()
            worker.join(4000)
            dir.deleteRecursively()
        }
    }
}
