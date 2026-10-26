package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroupServerWireTest {
    @Before
    fun initializeCookies() {
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
    }

    @Test
    fun productionClientUpdatesOnlyTitleMetadataUsingTheGroupWireFormat() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = ApiClient.createTempService(server.url("/").toString(), "")
                server.enqueue(MockResponse().setBody(snapshot))
                api.renameGroupConversation("saved", GroupServerTitleRequest("team", "設計方針の比較", "old title"), "owner")
                val request = server.takeRequest()
                assertEquals("PATCH", request.method)
                assertEquals("/api/plugins/group_chat/conversations/saved?profile=owner", request.path)
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                assertEquals(setOf("groupName", "title", "expectedTitle"), body.keys)
                assertEquals("team", body["groupName"]?.jsonPrimitive?.content)
                assertEquals("設計方針の比較", body["title"]?.jsonPrimitive?.content)
                assertEquals("old title", body["expectedTitle"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun productionClientReadsNonemptyHistoryAndNestedMessages() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = ApiClient.createTempService(server.url("/").toString(), "")
                server.enqueue(
                    MockResponse().setBody(
                        """
                        {"conversations":[
                            {"id":"saved","groupName":"team","title":"Discussion","createdAt":10,"updatedAt":20}
                        ]}
                        """.trimIndent(),
                    ),
                )
                val history = api.groupConversations("default")
                assertEquals("team", history.conversations.single().groupName)
                assertEquals(20L, history.conversations.single().updatedAt)
                server.enqueue(MockResponse().setBody(snapshot))
                val saved = api.groupConversation("saved", "team", "default")
                assertEquals("team", saved.groupName)
                assertEquals("bot", saved.activeSpeaker)
                assertEquals("speaker", saved.messages.single().senderDisplayName)
                assertFalse(saved.messages.single().isUser)
                assertTrue(saved.running)
            }
        }

    @Test
    fun productionClientSendsServerFieldNamesIncludingImportedHistory() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = ApiClient.createTempService(server.url("/").toString(), "")
                server.enqueue(MockResponse().setBody(snapshot))
                val request =
                    GroupServerRequest(
                        requestId = "request",
                        groupName = "team",
                        text = "new turn",
                        members = listOf("bot"),
                        memberLabels = mapOf("bot" to "speaker"),
                        rounds = 1,
                        maxMessages = 3,
                        seed = listOf(GroupChatMessage("old", "bot", "speaker", false, text = "old turn")),
                    )
                assertEquals("team", api.sendGroupMessage("saved", request, "default").groupName)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals("request", body["requestId"]?.jsonPrimitive?.content)
                assertEquals("team", body["groupName"]?.jsonPrimitive?.content)
                assertFalse(body.containsKey("request_id"))
                val seed =
                    body
                        .getValue("seed")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("bot", seed["senderName"]?.jsonPrimitive?.content)
                assertEquals("false", seed["isUser"]?.jsonPrimitive?.content)
            }
        }

    private val snapshot =
        """
        {"id":"saved","groupName":"team","title":"Discussion","createdAt":10,
         "state":"running","activeSpeaker":"bot","messages":[
            {"id":"reply","senderName":"bot","senderDisplayName":"speaker",
             "isUser":false,"text":"answer","timestamp":20}
        ]}
        """.trimIndent()
}
