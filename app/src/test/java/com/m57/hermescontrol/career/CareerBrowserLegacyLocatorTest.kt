package com.m57.hermescontrol.career

import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Old servers have credential access but no optional login-locator endpoint. */
@OptIn(ExperimentalCoroutinesApi::class)
class CareerBrowserLegacyLocatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val request = slot<Request>()
    private val base = MutableStateFlow("https://gateway.example.test/")
    private val profile = MutableStateFlow<String?>("phone")
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))
    private var cookie = "synthetic-session"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } answers { base.value }
        every { AuthManager.baseUrlFlow } returns base
        every { AuthManager.activeProfileId } returns profile
        every { AuthManager.selectedProfileFlow } returns profile
        every { AuthManager.isGatedMode() } returns true
        every { AuthManager.getSessionCookie() } answers { cookie }
        every { client.newCall(capture(request)) } returns call
        every { call.enqueue(capture(callback)) } returns Unit
    }

    @After fun cleanup() {
        unmockkObject(AuthManager)
        Dispatchers.resetMain()
    }

    private fun snapshot(
        extra: String = "",
        passwordAutocomplete: String = "",
        occupied: Boolean = false,
    ): CareerBrowserLoginSnapshot {
        val fields =
            Json.parseToJsonElement(
                """[{"index":0,"form":0,"type":"text","name":"UserAccount","id":"UserAccount","autocomplete":"","labelHints":[],"occupied":$occupied},
            {"index":1,"form":0,"type":"password","name":"Password","id":"Password","autocomplete":"$passwordAutocomplete","labelHints":[],"occupied":false}$extra]""",
            ) as JsonArray
        return CareerBrowserLoginSnapshot(fields, fields.toString())
    }

    private fun answer(
        status: Int,
        body: String = "{}",
    ) {
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(request.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Synthetic")
                .body(body.toResponseBody())
                .build(),
        )
    }

    @Test fun absentLocatorUsesOneEmptyLoginPairOnOldServers() =
        runTest(dispatcher) {
            for (status in listOf(404, 405, 501)) {
                val source = CareerBrowserCredentialSource(careerScope()) { client }
                val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot()) { true } }
                runCurrent()
                answer(status)
                assertNotNull(
                    "Missing optional locator status $status must not block this login pair",
                    operation.await(),
                )
                assertNull(source.lastFailure)
            }
        }

    @Test fun authorizationAndServerErrorsDoNotUseCompatibilityFallback() =
        runTest(dispatcher) {
            for (status in listOf(400, 401, 403, 409, 500, 503)) {
                val source = CareerBrowserCredentialSource(careerScope()) { client }
                val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot()) { true } }
                runCurrent()
                answer(status)
                assertNull(operation.await())
            }
        }

    @Test fun notFoundCompanyIsNotTreatedAsAnAbsentLocator() =
        runTest(dispatcher) {
            val source = CareerBrowserCredentialSource(careerScope()) { client }
            val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot()) { true } }
            runCurrent()
            answer(404, """{"detail":{"code":"company_missing"}}""")
            assertNull(operation.await())
        }

    @Test fun lateMissingEndpointAfterDocumentOrSessionChangeDoesNotSelect() =
        runTest(dispatcher) {
            for (sessionChange in listOf(true, false)) {
                cookie = "synthetic-session"
                var current = true
                val source = CareerBrowserCredentialSource(careerScope()) { client }
                val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot()) { current } }
                runCurrent()
                if (sessionChange) cookie = "changed-session" else current = false
                answer(404)
                assertNull(operation.await())
            }
        }

    @Test fun occupiedSignupAndAmbiguousFormsAreNotLocalLoginPairs() =
        runTest(dispatcher) {
            val extraText =
                """
                ,
                {"index":2,"form":0,"type":"text","name":"Other","id":"Other",
                 "autocomplete":"","labelHints":[],"occupied":false}
                """.trimIndent()
            val extraPassword =
                """
                ,
                {"index":2,"form":0,"type":"password","name":"RepeatPassword","id":"RepeatPassword",
                 "autocomplete":"","labelHints":[],"occupied":false}
                """.trimIndent()
            val snapshots =
                listOf(
                    snapshot(occupied = true),
                    snapshot(passwordAutocomplete = "new-password"),
                    snapshot(extraText),
                    snapshot(extraPassword),
                )
            for (value in snapshots) {
                val source = CareerBrowserCredentialSource(careerScope()) { client }
                val operation = async { source.locateLogin("dummy-company", 1, policy, value) { true } }
                runCurrent()
                answer(404)
                assertNull(operation.await())
            }
        }
}
