package com.m57.hermescontrol.agents

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.UUID

/** Read-only deployment verification using the installed app's existing connection. */
class GroupServerConnectionTest {
    @Test
    fun authenticatedGroupHistoryAndMissingConversationAreReachable() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            AuthManager.init(instrumentation.targetContext)
            // Await existing encrypted state off the UI thread; do not alter the connection.
            AuthManager.getToken()
            withTimeout(30_000) {
                // This independent read interface also works against the currently
                // installed v36, so server activation can be checked before updating it.
                val client =
                    OkHttpProvider.base
                        .newBuilder()
                        .addInterceptor { chain ->
                            val builder = chain.request().newBuilder()
                            if (!AuthManager.isGatedMode()) {
                                AuthManager.getToken()?.takeIf { it.isNotBlank() }?.let {
                                    builder.header("Authorization", "Bearer $it")
                                }
                            }
                            chain.proceed(builder.build())
                        }.build()
                val api =
                    Retrofit
                        .Builder()
                        .baseUrl(AuthManager.endpointForBuild().baseUrl)
                        .client(client)
                        .build()
                        .create(GroupReadApi::class.java)
                val profile = AuthManager.activeProfileId.value ?: "default"
                val history = api.history(profile)
                val rawHistory = JSONObject(requireNotNull(history.body()).string()).getJSONArray("conversations")
                history.body()?.close()
                history.errorBody()?.close()
                assertEquals(200, history.code())
                // Exercise the actual app converter, including nonempty history and
                // sender fields; a bare HTTP 200 does not establish compatibility.
                val decoded = ApiClient.hermesApi.groupConversations(profile)
                assertEquals(rawHistory.length(), decoded.conversations.size)
                for (entry in decoded.conversations) {
                    val saved = ApiClient.hermesApi.groupConversation(entry.id, entry.groupName, profile)
                    assertEquals(entry.id, saved.id)
                    assertEquals(entry.groupName, saved.groupName)
                }
                val missing = api.conversation("probe-${UUID.randomUUID()}", "read-only-deployment-probe", profile)
                missing.body()?.close()
                missing.errorBody()?.close()
                assertEquals(404, missing.code())
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("group_server_authenticated_read", "passed")
                    putString("group_server_production_decoder", "passed")
                    putString("agent_execution", "not_requested")
                },
            )
        }
}

interface GroupReadApi {
    @GET("api/plugins/group_chat/conversations")
    suspend fun history(
        @Query("profile") profile: String,
    ): Response<ResponseBody>

    @GET("api/plugins/group_chat/conversations/{id}")
    suspend fun conversation(
        @Path("id") id: String,
        @Query("group") group: String,
        @Query("profile") profile: String,
    ): Response<ResponseBody>
}
