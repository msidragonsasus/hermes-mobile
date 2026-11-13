package com.m57.hermescontrol.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Uses the real HTTP producer; no device/network failure is inferred from these fixtures. */
class UpdateTimeoutPolicyTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        directory = Files.createTempDirectory("update-deadline").toFile()
    }

    @After
    fun cleanup() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun downloadTimeout(inheritedSeconds: Long): Long =
        runBlocking {
            val observed = AtomicLong(-1L)
            val bytes = "synthetic transfer body".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val client =
                OkHttpClient
                    .Builder()
                    .callTimeout(inheritedSeconds, TimeUnit.SECONDS)
                    .addNetworkInterceptor { chain ->
                        observed.set(chain.call().timeout().timeoutNanos())
                        chain.proceed(chain.request())
                    }.build()
            val result =
                UpdateDownloader(
                    client,
                ).download(server.url("/apk"), bytes.size.toLong(), File(directory, "candidate.apk")) {
                }
            assertEquals(bytes.size.toLong(), result.sizeBytes)
            assertTrue(bytes.contentEquals(result.file.readBytes()))
            observed.get()
        }

    private fun metadataTimeout(inheritedSeconds: Long): Long =
        runBlocking {
            val observed = AtomicLong(-1L)
            server.enqueue(
                MockResponse().setBody("""{"schema":"hermes-mobile-update-latest","protocol":1,"release":null}"""),
            )
            val updateScope = TEST_SERVER_SCOPE.copy(baseUrl = server.url("/").toString())
            val scopes = FakeUpdateScopes(serverScope = updateScope)
            val checker =
                AppUpdateChecker(
                    upstreamClient = OkHttpClient.Builder().build(),
                    bindings = scopes::bind,
                    serverClientFactory = { binding ->
                        boundUpdateClient(
                            binding,
                            OkHttpClient.Builder().callTimeout(inheritedSeconds, TimeUnit.SECONDS).build(),
                        ).newBuilder()
                            .addNetworkInterceptor { chain ->
                                observed.set(chain.call().timeout().timeoutNanos())
                                chain.proceed(chain.request())
                            }.build()
                    },
                )
            assertEquals(ServerLatest.NoRelease, checker.fetchServerLatest(updateScope))
            observed.get()
        }

    private fun upstreamMetadataTimeout(inheritedSeconds: Long): Long =
        runBlocking {
            val observed = AtomicLong(-1L)
            server.enqueue(MockResponse().setBody("{}"))
            // Redirect only this fixture's upstream URL to localhost; never contact GitHub.
            val client =
                OkHttpClient
                    .Builder()
                    .callTimeout(inheritedSeconds, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .url(server.url("/latest"))
                                .build(),
                        )
                    }.addNetworkInterceptor { chain ->
                        observed.set(chain.call().timeout().timeoutNanos())
                        chain.proceed(chain.request())
                    }.build()
            AppUpdateChecker(upstreamClient = client).fetchLatestRelease()
            assertEquals(1, server.requestCount)
            observed.get()
        }

    @Test
    fun upstreamMetadataAlsoHasTheThirtySecondCeiling() {
        assertEquals(TimeUnit.SECONDS.toNanos(30), upstreamMetadataTimeout(0))
    }

    @Test
    fun upstreamMetadataPreservesStricterInheritedDeadline() {
        assertEquals(TimeUnit.SECONDS.toNanos(1), upstreamMetadataTimeout(1))
    }

    @Test
    fun downloadHasAFiniteWholeCallDeadlineEvenWhenTheBaseClientIsUnbounded() {
        val actual = downloadTimeout(0)
        assertTrue("APK transfer needs a whole-call deadline, not only a between-read timeout", actual > 0L)
        assertTrue(actual <= TimeUnit.MINUTES.toNanos(5))
    }

    @Test
    fun checkingMetadataHasAFiniteWholeCallDeadline() {
        val actual = metadataTimeout(0)
        assertTrue("Home cannot wait indefinitely for update metadata", actual > 0L)
        assertTrue(actual <= TimeUnit.SECONDS.toNanos(30))
    }

    @Test
    fun downloadDoesNotExtendAnAlreadyStricterDeadline() {
        assertEquals(TimeUnit.SECONDS.toNanos(1), downloadTimeout(1))
    }

    @Test
    fun metadataDoesNotExtendAnAlreadyStricterDeadline() {
        assertEquals(TimeUnit.SECONDS.toNanos(1), metadataTimeout(1))
    }
}
