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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareerBrowserLoginResolverTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<OkHttpClient>(relaxed = true)
    private val call = mockk<Call>(relaxed = true)
    private val callback = slot<Callback>()
    private val request = slot<Request>()
    private val base = MutableStateFlow("https://gateway.example.test/")
    private val profile = MutableStateFlow<String?>("phone")
    private val policy = checkNotNull(CareerBrowserPolicy.create("https://portal.example.test/company/login"))
    private var cookie = "synthetic-session"
    private val fields =
        Json.parseToJsonElement(
            """[{"index":0,"form":0,"type":"text","name":"UserAccount","id":"UserAccount","autocomplete":"","labelHints":[],"occupied":false},
            {"index":1,"form":0,"type":"password","name":"Password","id":"Password","autocomplete":"","labelHints":[],"occupied":false}]""",
        ) as JsonArray
    private val snapshot = CareerBrowserLoginSnapshot(fields, fields.toString())

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

    private fun answer(wrongRequest: Boolean = false) {
        val buffer = Buffer()
        request.captured.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertFalse(body.containsKey("password"))
        assertFalse(body.containsKey("login_id"))
        val response =
            buildJsonObject {
                put("company_id", "dummy-company")
                put("profile", "default")
                put("connection_version", 1)
                put("url", policy.loginUrl.toString())
                put("request_id", if (wrongRequest) JsonPrimitive("wrong") else body.getValue("request_id"))
                put("login_index", 0)
                put("password_index", 1)
            }
        callback.captured.onResponse(
            call,
            Response
                .Builder()
                .request(request.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Synthetic")
                .body(response.toString().toResponseBody())
                .build(),
        )
    }

    @Test fun exactResponseResolvesAndSnapshotRoundTripsWithoutFieldValues() =
        runTest(dispatcher) {
            val source = CareerBrowserCredentialSource(careerScope()) { client }
            val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot) { true } }
            runCurrent()
            answer()
            assertNotNull(operation.await())
            val encoded =
                JsonPrimitive(
                    buildJsonObject {
                        put("fields", fields)
                        put("signature", fields.toString())
                    }.toString(),
                ).toString()
            assertNotNull(CareerBrowserLoginSnapshot.parse(encoded))
        }

    @Test fun lateResponseAfterSessionOrDocumentChangeIsDropped() =
        runTest(dispatcher) {
            for (sessionChange in listOf(true, false)) {
                var current = true
                cookie = "synthetic-session"
                val source = CareerBrowserCredentialSource(careerScope()) { client }
                val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot) { current } }
                runCurrent()
                if (sessionChange) cookie = "another-session" else current = false
                answer()
                assertNull(operation.await())
            }
        }

    @Test fun anotherRequestAndUnobservedIndexesAreRefused() =
        runTest(dispatcher) {
            val source = CareerBrowserCredentialSource(careerScope()) { client }
            val operation = async { source.locateLogin("dummy-company", 1, policy, snapshot) { true } }
            runCurrent()
            answer(wrongRequest = true)
            assertNull(operation.await())
            assertNull(
                snapshot.selection(
                    JsonObject(
                        mapOf(
                            "login_index" to JsonPrimitive(999),
                            "password_index" to JsonPrimitive(1),
                        ),
                    ),
                ),
            )
        }
}
