package com.m57.hermescontrol.data.update

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Connected-server transport through the production [boundUpdateClient]:
 * credentials are an immutable snapshot bound to one origin and the plugin
 * path, never read live at request time, never sent after a redirect, never
 * refreshed, and never taken from the ambient cookie jar. Two servers stand
 * for "server A" and "server B" during a connection switch.
 */
class ServerUpdateTransportTest {
    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var dir: File
    private lateinit var scopes: FakeUpdateScopes

    /** A base client whose ambient jar would leak a cookie if the bound client used it. */
    private val leakyJar =
        object : CookieJar {
            override fun saveFromResponse(
                url: HttpUrl,
                cookies: List<Cookie>,
            ) = Unit

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                listOf(
                    Cookie
                        .Builder()
                        .name("ambient")
                        .value("leaked")
                        .domain(url.host)
                        .build(),
                )
        }
    private val ambientBase = OkHttpClient.Builder().cookieJar(leakyJar).build()

    private fun scopeFor(
        server: MockWebServer,
        epoch: Long,
        token: String,
    ) = UpdateScope(
        UpdateSource.CONNECTED_SERVER,
        server.url("/hermes/").toString(),
        "default",
        null,
        gatedAuth = false,
        credentialPresent = true,
        connectionEpoch = epoch,
        credentialFingerprint = "fp-$token",
    )

    private lateinit var scopeA: UpdateScope
    private lateinit var scopeB: UpdateScope

    private fun checker(onClientCreated: () -> Unit = {}) =
        AppUpdateChecker(
            upstreamClient = ambientBase,
            bindings = scopes::bind,
            serverClientFactory = { binding ->
                boundUpdateClient(binding, ambientBase).also { onClientCreated() }
            },
        )

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("update-test").toFile()
        scopeA = scopeFor(serverA, 2, "token-A")
        scopeB = scopeFor(serverB, 4, "token-B")
        scopes = FakeUpdateScopes(serverScope = scopeA)
        scopes.bearers[scopeA] = "token-A"
        scopes.bearers[scopeB] = "token-B"
    }

    @After
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
        dir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private fun candidate(
        bytes: ByteArray,
        size: Long = bytes.size.toLong(),
        scope: UpdateScope = scopeA,
    ) = UpdateCandidate(
        UpdateSource.CONNECTED_SERVER,
        scope,
        0,
        "1.0.2",
        2,
        sha(bytes),
        size,
        setOf("a".repeat(64)),
        serverUpdatesUrl(scope, "releases/v2-0123456789abcdef/apk").toString(),
    )

    private fun expectFailure(
        expected: UpdateFailure,
        block: suspend () -> Unit,
    ) = runBlocking {
        try {
            block()
            fail("expected $expected")
        } catch (e: UpdateException) {
            assertEquals(expected, e.failure)
        }
    }

    private val emptyLatest = """{"schema":"hermes-mobile-update-latest","protocol":1,"release":null}"""

    // ── Credential binding ──────────────────────────────────────────────

    @Test
    fun switchAfterClientCreation_requestStillCarriesOnlyTheBoundServersCredential() =
        runBlocking {
            serverA.enqueue(MockResponse().setBody(emptyLatest))
            // The connection switches to B after the client exists but before the request runs.
            val result = checker(onClientCreated = { scopes.connect(scopeB) }).fetchServerLatest(scopeA)

            assertEquals(ServerLatest.NoRelease, result)
            val request = serverA.takeRequest()
            assertEquals("/hermes/api/plugins/mobile_updates/latest", request.path)
            assertEquals("Bearer token-A", request.getHeader("Authorization"))
            assertNull("ambient cookie jar must not be used", request.getHeader("Cookie"))
            assertEquals(0, serverB.requestCount)
        }

    @Test
    fun switchBeforeBinding_refusesWithoutContactingEitherServer() {
        scopes.connect(scopeB)
        expectFailure(UpdateFailure.CONNECTION_CHANGED) { checker().fetchServerLatest(scopeA) }
        expectFailure(UpdateFailure.CONNECTION_CHANGED) {
            checker().download(candidate(ByteArray(10)), File(dir, "x.apk")) {}
        }
        assertEquals(0, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun boundClient_refusesAnyUrlOutsideBoundOriginOrPluginPath() {
        val binding = scopes.bind(scopeA)!!
        val client = boundUpdateClient(binding, ambientBase)
        for (url in listOf(
            serverB.url("/hermes/api/plugins/mobile_updates/latest"),
            serverA.url("/hermes/api/sessions"),
            serverA.url("/api/plugins/mobile_updates/latest"),
        )) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("expected refusal for $url")
            } catch (e: IOException) {
                assertTrue(e is BoundRequestRefusedException)
            }
        }
        assertEquals(0, serverA.requestCount)
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun gatedBinding_sendsOnlySnapshotCookieNeverBearerOrAmbientCookie() =
        runBlocking {
            val gated = scopeA.copy(gatedAuth = true, credentialFingerprint = "fp-cookie")
            scopes.connect(gated)
            scopes.cookies[gated] = "hermes_session_at=snapshot"
            serverA.enqueue(MockResponse().setBody(emptyLatest))

            checker().fetchServerLatest(gated)

            val request = serverA.takeRequest()
            assertEquals("hermes_session_at=snapshot", request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }

    @Test
    fun redirectIsRefusedAndTheOtherServerReceivesNothing() {
        serverA.enqueue(MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/latest").toString()))
        expectFailure(UpdateFailure.REDIRECT_REFUSED) { checker().fetchServerLatest(scopeA) }
        serverA.enqueue(MockResponse().setResponseCode(307).setHeader("Location", serverB.url("/apk").toString()))
        expectFailure(UpdateFailure.REDIRECT_REFUSED) {
            checker().download(candidate(ByteArray(10)), File(dir, "x.apk")) {}
        }
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun unauthorized_isNotRefreshedOrRetried() {
        serverA.enqueue(MockResponse().setResponseCode(401))
        serverA.enqueue(MockResponse().setBody(emptyLatest))
        expectFailure(UpdateFailure.LOGIN_REQUIRED) { checker().fetchServerLatest(scopeA) }
        assertEquals(1, serverA.requestCount)
    }

    // ── Metadata endpoint ───────────────────────────────────────────────

    @Test
    fun fetchServerLatest_mapsStatusesWithoutFallback() {
        for ((code, expected) in listOf(
            403 to UpdateFailure.LOGIN_REQUIRED,
            404 to UpdateFailure.SERVER_UPDATES_MISSING,
            503 to UpdateFailure.SERVER_UPDATES_DISABLED,
            500 to UpdateFailure.CHECK_FAILED,
        )) {
            serverA.enqueue(MockResponse().setResponseCode(code))
            expectFailure(expected) { checker().fetchServerLatest(scopeA) }
        }
    }

    @Test
    fun fetchServerLatest_boundsTheMetadataBody() {
        serverA.enqueue(MockResponse().setBody("x".repeat(300_000)))
        expectFailure(UpdateFailure.INVALID_METADATA) { checker().fetchServerLatest(scopeA) }
    }

    // ── APK download ────────────────────────────────────────────────────

    @Test
    fun download_hashesExactBytesWithBoundCredential() =
        runBlocking {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            serverA.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val dest = File(dir, "hermes-update-1.apk")
            var last = 0f
            val result = checker().download(candidate(bytes), dest) { last = it }
            assertEquals(sha(bytes), result.sha256)
            assertTrue(dest.readBytes().contentEquals(bytes))
            assertEquals(1f, last)
            assertFalse(File(dest.path + ".part").exists())
            val request = serverA.takeRequest()
            assertEquals("/hermes/api/plugins/mobile_updates/releases/v2-0123456789abcdef/apk", request.path)
            assertEquals("Bearer token-A", request.getHeader("Authorization"))
        }

    @Test
    fun download_rejectsSizeMismatchesAndCleansUp() {
        val bytes = ByteArray(1000) { 7 }
        val dest = File(dir, "hermes-update-1.apk")
        serverA.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        expectFailure(UpdateFailure.SIZE_MISMATCH) { checker().download(candidate(bytes, size = 999), dest) {} }
        serverA.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 100))
        expectFailure(UpdateFailure.SIZE_MISMATCH) { checker().download(candidate(bytes, size = 500), dest) {} }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun download_refusesUrlOutsideTheBoundServerOrigin() {
        val foreign = candidate(ByteArray(10)).copy(downloadUrl = serverB.url("/apk").toString())
        expectFailure(UpdateFailure.CONNECTION_CHANGED) { checker().download(foreign, File(dir, "x.apk")) {} }
        assertEquals(0, serverB.requestCount)
    }

    @Test
    fun download_cancellationDeletesPartialFile() =
        runBlocking {
            val bytes = ByteArray(64 * 1024 * 4) { 1 }
            serverA.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(16 * 1024, 1, TimeUnit.SECONDS))
            val dest = File(dir, "hermes-update-1.apk")
            val job = async { checker().download(candidate(bytes), dest) {} }
            withTimeout(5_000) {
                while (!File(dest.path + ".part").exists()) delay(20)
            }
            job.cancel()
            runCatching { job.await() }
            assertTrue(job.isCancelled)
            assertFalse(dest.exists())
            assertFalse(File(dest.path + ".part").exists())
        }
}
