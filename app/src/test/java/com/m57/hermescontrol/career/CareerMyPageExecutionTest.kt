package com.m57.hermescontrol.career

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CareerMyPageExecutionTest {
    private val repository = mockk<CareerMyPageExecutionRepository>()
    private val dispatcher = StandardTestDispatcher()
    private val execution = CareerExecution("openai-codex", "selected-model", "medium")

    private fun capabilities(
        billing: String = "subscription",
        selectable: Boolean = true,
    ) = CareerMyPageCapabilities(
        models =
            listOf(
                CareerMyPageModelOption(
                    provider = execution.provider,
                    id = execution.model,
                    reasoning_efforts = listOf("medium"),
                    connection_state = "available",
                    billing = billing,
                    selectable = selectable,
                    private_content_allowed = selectable,
                ),
            ),
        catalog_state = "available",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.settings() } returns CareerMyPageExecutionSettings(version = 4)
        coEvery { repository.capabilities(any()) } returns capabilities()
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun unsetModelIsNotImplicitlySelected() =
        runTest(dispatcher) {
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            assertNull(
                model.state.value.settings
                    ?.analysis,
            )
            model.save(CareerMyPageExecutionSettings())
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.save(any()) }
            assertNotNull(model.state.value.error)
        }

    @Test
    fun existingDedicatedSettingsShownAndSaveUsesVersion() =
        runTest(dispatcher) {
            val original = CareerMyPageExecutionSettings(version = 8, analysis = execution, source = "legacy")
            coEvery { repository.settings() } returns original
            coEvery { repository.save(any()) } returns original.copy(version = 9)
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            assertEquals(
                execution,
                model.state.value.settings
                    ?.analysis,
            )
            model.save(original)
            advanceUntilIdle()
            coVerify {
                repository.save(
                    match { it.expected_version == 8 && it.analysis == execution && !it.paid_enabled },
                )
            }
            assertNull(model.state.value.pending)
            assertEquals(
                9,
                model.state.value.settings
                    ?.version,
            )
        }

    @Test
    fun uncertainSaveRetryReusesIdempotencyKey() =
        runTest(dispatcher) {
            coEvery { repository.save(any()) } throws IOException()
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.save(CareerMyPageExecutionSettings(analysis = execution))
            advanceUntilIdle()
            val pending = checkNotNull(model.state.value.pending)
            coEvery { repository.save(pending) } returns
                CareerMyPageExecutionSettings(version = 5, analysis = execution)
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.save(pending) }
            assertNull(model.state.value.pending)
        }

    @Test
    fun conflictRequiresReloadBeforeAnotherSave() =
        runTest(dispatcher) {
            coEvery { repository.save(any()) } throws
                HttpException(Response.error<Any>(409, "{\"detail\":{\"code\":\"version_conflict\"}}".toResponseBody()))
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.save(CareerMyPageExecutionSettings(analysis = execution))
            advanceUntilIdle()
            assertNull(model.state.value.pending)
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("再読込"),
            )
        }

    @Test
    fun definitivePolicyAndBudgetRejectionsDoNotTrapPendingSave() =
        runTest(dispatcher) {
            for ((status, code, expected) in listOf(
                Triple(409, "private_content_forbidden", "個別案内"),
                Triple(400, "invalid_budget", "料金上限"),
                Triple(409, "model_unsupported", "モデル"),
            )) {
                val body = "{\"detail\":{\"code\":\"$code\"}}"
                coEvery { repository.save(any()) } throws
                    HttpException(Response.error<Any>(status, body.toResponseBody()))
                val model = CareerMyPageExecutionViewModel(repository)
                model.refresh()
                advanceUntilIdle()
                model.save(CareerMyPageExecutionSettings(analysis = execution))
                advanceUntilIdle()
                assertNull(model.state.value.pending)
                assertTrue(
                    model.state.value.error
                        .orEmpty()
                        .contains(expected),
                )
                assertFalse(
                    model.state.value.error
                        .orEmpty()
                        .contains("別の操作"),
                )
            }
        }

    @Test
    fun serverFailureKeepsOriginalIdempotentSave() =
        runTest(dispatcher) {
            coEvery { repository.save(any()) } throws HttpException(Response.error<Any>(503, "{}".toResponseBody()))
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()
            model.save(CareerMyPageExecutionSettings(analysis = execution))
            advanceUntilIdle()
            val pending = checkNotNull(model.state.value.pending)
            model.retry()
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.save(pending) }
            assertEquals(pending, model.state.value.pending)
        }

    @Test
    fun connectionFailureIsVisibleWithoutModelOptions() {
        val capabilities =
            CareerMyPageCapabilities(
                connections = listOf(CareerMyPageModelConnection("openai-codex", "oauth", "authentication_required")),
                provider_errors = listOf(CareerProviderError("openai-codex", "auth_required")),
            )
        val lines = myPageConnectionLines(capabilities)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("openai-codex"))
        assertTrue(lines[0].contains("再認証"))
    }

    @Test
    fun unsupportedBackendDoesNotAllowSavingPreviouslyLoadedSettings() =
        runTest(dispatcher) {
            val settings = CareerMyPageExecutionSettings(version = 4, analysis = execution)
            coEvery { repository.settings() } returns settings
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()

            coEvery { repository.settings() } throws HttpException(Response.error<Any>(404, "{}".toResponseBody()))
            model.refresh()
            advanceUntilIdle()

            assertFalse(model.state.value.supported)
            assertEquals(settings, model.state.value.settings)
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("サーバーの更新"),
            )
            model.save(settings)
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun unavailableCapabilitiesEndpointDoesNotInventAnyModelSelection() =
        runTest(dispatcher) {
            coEvery { repository.capabilities(any()) } throws
                HttpException(Response.error<Any>(405, "{}".toResponseBody()))
            val model = CareerMyPageExecutionViewModel(repository)
            model.refresh()
            advanceUntilIdle()

            assertFalse(model.state.value.supported)
            assertNull(model.state.value.settings)
            assertNull(model.state.value.capabilities)
            assertTrue(
                model.state.value.error
                    .orEmpty()
                    .contains("対応していません"),
            )
            coVerify(exactly = 0) { repository.save(any()) }
        }

    @Test
    fun paidRequiresBothBudgetsAndExplicitSelection() {
        val settings = CareerMyPageExecutionSettings(analysis = execution)
        assertNotNull(settings.validationError(capabilities("paid")))
        assertNotNull(
            settings
                .copy(
                    paid_enabled = true,
                    budget = CareerMyPageBudget(1.0, 0.0),
                ).validationError(capabilities("paid")),
        )
        assertNull(
            settings
                .copy(
                    paid_enabled = true,
                    budget = CareerMyPageBudget(1.0, 2.0),
                ).validationError(capabilities("paid")),
        )
        assertNotNull(settings.copy(budget = CareerMyPageBudget(Double.NaN, 0.0)).validationError(capabilities()))
    }

    @Test
    fun prohibitedPrivateEndpointAndUnsupportedEffortAreRejected() {
        assertFalse(capabilities(selectable = false).supports(execution))
        assertFalse(capabilities().supports(execution.copy(reasoning_effort = "ultra")))
        assertNotNull(
            CareerMyPageExecutionSettings(analysis = execution).validationError(capabilities(selectable = false)),
        )
    }

    @Test
    fun distinctLearningModelMustBeConfigured() {
        assertNotNull(
            CareerMyPageExecutionSettings(
                analysis = execution,
                use_analysis_for_learning = false,
            ).validationError(capabilities()),
        )
        assertNull(
            CareerMyPageExecutionSettings(
                analysis = execution,
                learning = execution,
                use_analysis_for_learning = false,
            ).validationError(capabilities()),
        )
    }
}
