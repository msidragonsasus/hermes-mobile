package com.m57.hermescontrol.ui.model

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ModelOptionsRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>()
    private val providers = listOf(ModelProvider("openai", "OpenAI", models = listOf("existing-model")))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager, ApiClient)
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { ApiClient.hermesApi } returns api
        coEvery { api.getModelOptions(true, false) } returns Response.success(ModelOptionsResponse(providers))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun refreshRetainsCandidatesAndFetchesOnlyCatalogOnceWhilePending() =
        runTest(dispatcher) {
            val viewModel = ModelViewModel(dispatcher)
            viewModel.refreshModelOptions()
            advanceUntilIdle()
            viewModel.openMainModelPicker()
            val response = CompletableDeferred<Response<ModelOptionsResponse>>()
            coEvery { api.getModelOptions(true, false) } coAnswers { response.await() }

            viewModel.refreshModelOptions()
            viewModel.refreshModelOptions()
            runCurrent()

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.showMainModelPicker)
            assertEquals(providers, viewModel.uiState.value.providers)
            coVerify(exactly = 2) { api.getModelOptions(true, false) }
            coVerify(exactly = 0) { api.getProfiles() }
            coVerify(exactly = 0) { api.getAuxiliaryModels() }

            val updated = listOf(providers.single().copy(models = listOf("new-model")))
            response.complete(Response.success(ModelOptionsResponse(updated)))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(updated, viewModel.uiState.value.providers)
        }

    @Test
    fun failedRefreshKeepsAuxPickerAndCandidatesAndAllowsRetry() =
        runTest(dispatcher) {
            val viewModel = ModelViewModel(dispatcher)
            viewModel.refreshModelOptions()
            advanceUntilIdle()
            viewModel.openAuxModelPicker("vision")
            coEvery { api.getModelOptions(true, false) } throws IOException("offline")

            viewModel.refreshModelOptions()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.showAuxModelPicker)
            assertEquals("vision", viewModel.uiState.value.auxPickerTask)
            assertEquals(providers, viewModel.uiState.value.providers)
            assertNotNull(viewModel.uiState.value.modelOptionsError)

            coEvery { api.getModelOptions(true, false) } returns Response.success(ModelOptionsResponse(providers))
            viewModel.refreshModelOptions()
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.modelOptionsError)
        }
}
