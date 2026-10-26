package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainActivity
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** One explicitly approved real prompt. No mocked socket, title response, reducer or UI. */
class HermesTitleEndToEndPhoneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun oneApprovedPromptGetsPersistedAndDisplayedModelTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("hermesTitleSendLive") == "true")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
        val assignment = runBlocking { ApiClient.hermesApi.getAuxiliaryModels() }
        assertEquals(200, assignment.code())
        // Use only the already configured subscription route for this one chat test.
        assertEquals("openai-codex", assignment.body()?.main?.provider)
        val previousIds = sessions().map { it.getString("id") }.toSet()
        compose.onNodeWithTag("conversation_new_button").performClick()
        compose.onNodeWithTag("chat_input").performTextInput(
            "明日の旅行の持ち物を整理したい。自動命名テストです。ツールや外部操作は使わず『確認できました』とだけ返して",
        )
        compose.onNodeWithTag("send_button").performClick()
        result("synthetic_prompt_send_count", "1")

        var named: JSONObject? = null
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline && named == null) {
            named =
                sessions().firstOrNull {
                    it.getString("id") !in previousIds && it.optString("source") == "desktop" &&
                        it.optString("title_source") == "llm" && it.optString("title").isNotBlank()
                }
            if (named == null) SystemClock.sleep(1000)
        }
        assertTrue("The new synthetic session must persist a model title", named != null)
        val title = requireNotNull(named).getString("title")
        result("synthetic_session_id", requireNotNull(named).getString("id"))
        result("persisted_title_source", "llm")
        result("synthetic_title", title)
        // Do not manually reload sessions: the real notification must update the header.
        compose.waitUntil(30_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        result("live_title_visible_without_manual_refresh", "passed")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "hermes-title-endtoend.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun sessions(): List<JSONObject> {
        val endpoint = AuthManager.endpointForBuild().baseUrl
        val url =
            endpoint
                .newBuilder()
                .addPathSegments("api/sessions")
                .addQueryParameter("limit", "20")
                .addQueryParameter("profile", AuthManager.activeProfileId.value ?: "default")
                .build()
        val builder = Request.Builder().url(url)
        if (!AuthManager.isGatedMode()) builder.header("Authorization", "Bearer ${AuthManager.getToken()}")
        return OkHttpProvider.base.newCall(builder.build()).execute().use { response ->
            assertEquals(200, response.code)
            val rows = JSONObject(requireNotNull(response.body).string()).getJSONArray("sessions")
            (0 until rows.length()).map { rows.getJSONObject(it) }
        }
    }

    private fun result(
        name: String,
        value: String,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(name, value) })
    }
}
