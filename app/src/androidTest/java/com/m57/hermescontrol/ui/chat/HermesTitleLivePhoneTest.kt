package com.m57.hermescontrol.ui.chat

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.StorageStartup
import com.m57.hermescontrol.data.local.StorageStartupState
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in reads only. Never submits an agent prompt or exports credentials or conversation text. */
class HermesTitleLivePhoneTest {
    @Test
    fun inspectConfiguredTitlingAndPersistedTitles() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("hermesTitleLive") == "true")
            withTimeout(60_000) { StorageStartup.state.first { it == StorageStartupState.Ready } }
            assertFalse(AuthManager.getToken().isNullOrBlank())
            val profile = AuthManager.activeProfileId.value ?: "default"
            result("checked_profile", profile)
            val config = ApiClient.hermesApi.getConfig(profile)
            assertEquals(200, config.code())
            val auxiliary = ApiClient.hermesApi.getAuxiliaryModels()
            assertEquals(200, auxiliary.code())
            val titleTask = auxiliary.body()?.tasks?.firstOrNull { it.task == "title_generation" }
            val configJson = JSONObject(JsonObject(config.body().orEmpty()).toString())
            val configRoot = configJson.optJSONObject("config") ?: configJson
            val titleConfig = configRoot.optJSONObject("auxiliary")?.optJSONObject("title_generation")
            result("title_enabled", titleConfig?.optString("enabled", "default-true") ?: "default-true")
            result("title_task_present", (titleTask != null).toString())
            result("title_provider", titleTask?.provider.orEmpty())
            result("title_model", titleTask?.model.orEmpty())

            val endpoint =
                AuthManager
                    .endpointForBuild()
                    .baseUrl
                    .toString()
                    .trimEnd('/')
            val url =
                "$endpoint/api/sessions"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("order", "recent")
                    .addQueryParameter("profile", profile)
                    .build()
            val request = Request.Builder().url(url)
            if (!AuthManager.isGatedMode()) request.header("Authorization", "Bearer ${AuthManager.getToken()}")
            OkHttpProvider.base.newCall(request.build()).execute().use { response ->
                assertEquals(200, response.code)
                val sessions = JSONObject(requireNotNull(response.body).string()).getJSONArray("sessions")
                val counts = mutableMapOf<String, Int>()
                var titled = 0
                var regular = 0
                for (index in 0 until sessions.length()) {
                    val row = sessions.getJSONObject(index)
                    if (row.optString("source") !in setOf("desktop", "mobile", "tui")) continue
                    regular++
                    if (row.optString("title").let { it.isNotBlank() && it != "null" }) titled++
                    val source = row.optString("title_source", "unreported")
                    counts[source] = (counts[source] ?: 0) + 1
                }
                result("recent_regular_sessions", regular.toString())
                result("recent_regular_titled", titled.toString())
                for (source in listOf("derived", "llm", "user", "unreported", "null")) {
                    result("title_source_$source", (counts[source] ?: 0).toString())
                }
            }
            val logs = ApiClient.hermesApi.getLogs(lines = 1000)
            result("log_read_status", logs.code().toString())
            val lines = logs.body()?.let { it.lines ?: it.logs }.orEmpty()
            result(
                "recent_title_error_lines",
                lines
                    .count {
                        it.contains("title", true) &&
                            (it.contains("fail", true) || it.contains("error", true))
                    }.toString(),
            )
        }

    private fun result(
        name: String,
        value: String,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(name, value) })
    }
}
