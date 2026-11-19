package com.m57.hermescontrol.agents

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.SpecialistRequestScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class BotDefaultsViewModelTest {
    @Test
    fun reopeningAfterAuthRefreshCanSaveForTheSameBot() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val profile = "bot-7387a02b59fb459ab86d76b12f6bec5e"
                val provider = "openrouter"
                val model = "anthropic/claude-fable-5.2"
                val epoch = MutableStateFlow(2L)
                val api = mockk<HermesApiService>()
                var bot = ProfileInfo(profile, model = "gpt-6.1-sol", provider = "openai-codex")
                coEvery { api.getProfiles() } answers { Response.success(ProfilesResponse(listOf(bot))) }
                coEvery { api.getConfig(profile) } returns Response.success(emptyMap())
                coEvery { api.getConfigSchema(profile) } returns
                    Response.success(ConfigSchemaResponse(emptyMap(), emptyList()))
                coEvery { api.getModelOptions(profile = profile) } returns
                    Response.success(
                        ModelOptionsResponse(listOf(ModelProvider(provider, "OpenRouter", models = listOf(model)))),
                    )
                coEvery {
                    api.updateProfileModel(
                        profile,
                        UpdateProfileModelRequest(provider, model),
                        SpecialistRequestScope(4L),
                    )
                } answers {
                    bot = bot.copy(model = model, provider = provider)
                    Response.success(Unit)
                }

                val beforeRefresh = BotDefaultsViewModel(profile, epoch, api, dispatcher)
                beforeRefresh.load()
                advanceUntilIdle()
                assertEquals("gpt-6.1-sol", beforeRefresh.uiState.value.model)
                assertEquals(
                    listOf(provider),
                    beforeRefresh.uiState.value.providers
                        .map { it.slug },
                )

                // A refreshed token used to invalidate the ViewModel for the lifetime of its URL/profile key.
                epoch.value = 4L
                beforeRefresh.setModel(provider, model)
                advanceUntilIdle()
                coVerify(exactly = 0) { api.updateProfileModel(any(), any(), any()) }
                assertTrue(
                    beforeRefresh.uiState.value.error
                        .orEmpty()
                        .contains("接続状態"),
                )

                val afterRefresh = BotDefaultsViewModel(profile, epoch, api, dispatcher)
                afterRefresh.load()
                advanceUntilIdle()
                afterRefresh.setModel(provider, model)
                advanceUntilIdle()
                coVerify(exactly = 1) {
                    api.updateProfileModel(
                        profile,
                        UpdateProfileModelRequest(provider, model),
                        SpecialistRequestScope(4L),
                    )
                }
                assertEquals(model, afterRefresh.uiState.value.model)
                assertNull(afterRefresh.uiState.value.error)
                assertTrue(
                    afterRefresh.uiState.value.notice
                        .orEmpty()
                        .contains("保存しました"),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun botLabelUsesRosterTitleThenDisplayNameAndFinallyId() {
        val id = "bot-7387a02b59fb459ab86d76b12f6bec5e"
        val fallback = ProfileInfo(id, display_name = "就活")
        val roster =
            fallback.copy(
                ui_meta =
                    mapOf("hermes-bots" to buildJsonObject { put("title", JsonPrimitive("就活調査")) }),
            )
        assertEquals("就活調査", BotConversations.label(id, roster, fallback))
        assertEquals("就活", BotConversations.label(id, ProfileInfo(id), fallback))
        assertEquals(id, BotConversations.label(id, roster.copy(name = "another-bot")))
        assertEquals("Hermes", BotConversations.label("default"))
    }
}
