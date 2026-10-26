package com.m57.hermescontrol.pcpower

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PcPowerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val awake =
        PcPowerUiState(
            loading = false,
            status = PcPowerStatus(1, "desktop-ryzen7", "awake", wake_available = true, sleep_available = true),
        )

    @Test
    fun sleepRequiresExplicitConfirmation() {
        var calls = 0
        compose.setContent { MaterialTheme { PcPowerScaffold(awake, {}, {}, { calls++ }) } }
        compose.onNodeWithTag("pc_power_sleep").assertIsEnabled().performClick()
        compose.onNodeWithText("PCをスリープしますか？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("pc_power_sleep_confirm").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun wakeButtonIsAvailableEvenWhenPcIsUnreachable() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PcPowerContent(
                    awake.copy(status = awake.status?.copy(power_state = "asleep_or_unreachable")),
                    {},
                    { calls++ },
                    {},
                )
            }
        }
        compose.onNodeWithTag("pc_power_wake").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun staleAwakeStatusDisablesSleepAndShowsRefreshInstruction() {
        compose.setContent {
            MaterialTheme { PcPowerContent(awake.copy(statusStale = true), {}, {}, {}) }
        }
        compose.onNodeWithTag("pc_power_sleep").assertIsNotEnabled()
        compose.onNodeWithTag("pc_power_wake").assertIsNotEnabled()
        compose.onNodeWithText("「状態を更新」で最新の状態を確認してください。").assertIsDisplayed()
        compose.onNodeWithTag("pc_power_refresh").assertIsEnabled()
    }

    @Test
    fun unavailableSleepCapabilityIsNotOverriddenByAwakeStatus() {
        compose.setContent {
            MaterialTheme {
                PcPowerContent(awake.copy(status = awake.status?.copy(sleep_available = false)), {}, {}, {})
            }
        }
        compose.onNodeWithTag("pc_power_sleep").assertIsNotEnabled()
        compose.onNodeWithText("対象PCのスリープ受信設定を確認してください。").assertIsDisplayed()
    }

    @Test
    fun receiverExecutionFailureIsVisibleAfterStatusRefresh() {
        compose.setContent {
            MaterialTheme {
                PcPowerContent(awake.copy(status = awake.status?.copy(sleep_error = "sleep_failed")), {}, {}, {})
            }
        }
        compose.onNodeWithTag("pc_power_receiver_error").assertIsDisplayed()
        compose
            .onNodeWithText("対象PCでスリープの実行に失敗しました。受信処理の診断記録を確認してください。")
            .assertIsDisplayed()
    }

    @Test
    fun actionTransitionDisablesAnotherPowerRequestEvenIfStatusIsStillAwake() {
        compose.setContent {
            MaterialTheme { PcPowerContent(awake.copy(checkingTransition = true), {}, {}, {}) }
        }
        compose.onNodeWithTag("pc_power_sleep").assertIsNotEnabled()
        compose.onNodeWithTag("pc_power_wake").assertIsNotEnabled()
        compose.onNodeWithTag("pc_power_refresh").assertIsNotEnabled()
        compose.onNodeWithText("PCの状態を自動で確認しています。").assertIsDisplayed()
    }

    @Test
    fun sleepConfirmationCannotSubmitAfterStatusBecomesStale() {
        val state = mutableStateOf(awake)
        var calls = 0
        compose.setContent { MaterialTheme { PcPowerScaffold(state.value, {}, {}, { calls++ }) } }
        compose.onNodeWithTag("pc_power_sleep").performClick()
        compose.runOnIdle { state.value = awake.copy(statusStale = true) }
        compose.onNodeWithTag("pc_power_sleep_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun refreshEffectReadsOnResumeAndStopsAfterDisposal() {
        val owner = TestLifecycleOwner()
        var statusCalls = 0
        val repository =
            object : PcPowerRepository {
                override suspend fun status(): PcPowerStatus {
                    statusCalls++
                    return requireNotNull(awake.status)
                }

                override suspend fun wake(): PcPowerActionResponse = error("No power action is allowed in this test")

                override suspend fun sleep(operationId: String): PcPowerActionResponse =
                    error("No power action is allowed in this test")
            }
        val model = PcPowerViewModel(repository)
        val shown = mutableStateOf(true)
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown.value) PcPowerRefreshEffect(model)
            }
        }
        compose.runOnIdle {
            assertEquals(1, statusCalls)
            owner.moveTo(Lifecycle.State.CREATED)
        }
        compose.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.runOnIdle {
            assertEquals(2, statusCalls)
            shown.value = false
        }
        compose.runOnIdle {
            model.refresh()
            owner.moveTo(Lifecycle.State.CREATED)
            owner.moveTo(Lifecycle.State.RESUMED)
        }
        compose.runOnIdle { assertEquals(2, statusCalls) }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
