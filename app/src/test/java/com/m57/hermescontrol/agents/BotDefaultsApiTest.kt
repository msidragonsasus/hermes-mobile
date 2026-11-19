package com.m57.hermescontrol.agents

import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.saveProfileModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class BotDefaultsApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun api(server: MockWebServer): HermesApiService =
        Retrofit
            .Builder()
            .baseUrl(server.url("/hermes/"))
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType()),
            ).build()
            .create(HermesApiService::class.java)

    @Test
    fun allDiscoveryCallsCarryTheSelectedBotExplicitly() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val api = api(server)
                server.enqueue(MockResponse().setBody("{}"))
                api.getConfig("selected-bot")
                assertEquals("selected-bot", server.takeRequest().requestUrl!!.queryParameter("profile"))
                server.enqueue(MockResponse().setBody("""{"fields":{},"category_order":[]}"""))
                api.getConfigSchema("selected-bot")
                assertEquals("selected-bot", server.takeRequest().requestUrl!!.queryParameter("profile"))
                server.enqueue(MockResponse().setBody("""{"providers":[]}"""))
                api.getModelOptions(profile = "selected-bot")
                assertEquals("selected-bot", server.takeRequest().requestUrl!!.queryParameter("profile"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun defaultWritesTargetOneBotAndDoNotCarryOtherSettings() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val api = api(server)
                server.enqueue(
                    MockResponse().setBody("""{"ok":true,"provider":"connected","model":"selected-model"}"""),
                )
                assertNull(api.saveProfileModel("selected-bot", "connected", "selected-model"))
                val model = server.takeRequest()
                assertEquals("/hermes/api/profiles/selected-bot/model", model.path)
                assertEquals("""{"provider":"connected","model":"selected-model"}""", model.body.readUtf8())
                server.enqueue(MockResponse().setResponseCode(204))
                api.updateConfig(
                    ConfigUpdateRequest(
                        mapOf("agent" to JsonObject(mapOf("reasoning_effort" to JsonPrimitive("high")))),
                        "selected-bot",
                    ),
                )
                val effort = json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
                assertEquals(JsonPrimitive("selected-bot"), effort["profile"])
                assertEquals(
                    JsonObject(mapOf("agent" to JsonObject(mapOf("reasoning_effort" to JsonPrimitive("high"))))),
                    effort["config"],
                )
                assertFalse(effort.containsKey("model"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun emptySuccessAndRejectedWriteUseHttpStatusWithoutExposingDetails() =
        runBlocking {
            MockWebServer().use { server ->
                val api = api(server)
                server.enqueue(MockResponse().setResponseCode(204))
                assertNull(api.saveProfileModel("selected-bot", "openrouter", "anthropic/claude-fable-5.2"))
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"private server value"}"""))
                val error = api.saveProfileModel("selected-bot", "openrouter", "anthropic/claude-fable-5.2").orEmpty()
                assertTrue(error.contains("HTTP 403"))
                assertFalse(error.contains("private server value"))
                assertEquals(2, server.requestCount)
            }
        }
}
