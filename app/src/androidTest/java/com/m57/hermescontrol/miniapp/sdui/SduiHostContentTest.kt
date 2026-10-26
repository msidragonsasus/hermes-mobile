package com.m57.hermescontrol.miniapp.sdui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic data only: no network, no ViewModel, no credentials. */
class SduiHostContentTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val screen =
        SduiScreen(
            appId = "career.mypage",
            protocolVersion = 1,
            componentVersion = 1,
            releaseId = "bundled-1",
            companyId = "c1",
            profile = "default",
            title = "マイページ",
            state = "ready",
            components =
                listOf(
                    SduiSection(
                        "状況",
                        listOf(
                            SduiStatus("前回の確認は成功しました", SduiTone.SUCCESS),
                            SduiKeyValue("最終確認", "今日 9:00"),
                            SduiText("一行目\n二行目"),
                        ),
                    ),
                    SduiButton("今すぐ確認する", SduiAction.MYPAGE_CHECK, true),
                    SduiButton("最新にする", SduiAction.REFRESH, true),
                    SduiButton("企業設定へ", SduiAction.NATIVE_SETTINGS, true),
                    SduiButton("ブラウザで開く", SduiAction.NATIVE_BROWSER, false),
                ),
        )

    private fun show(
        state: SduiUiState,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    SduiHostContent(
                        state = state,
                        onRefresh = { calls += "refresh" },
                        onReload = { calls += "reload" },
                        onSendCheck = { calls += "send" },
                        onRetryCheck = { calls += "retry" },
                        onOpenSettings = { calls += "settings" },
                        onOpenBrowser = { calls += "browser" },
                        onBack = { calls += "back" },
                    )
                }
            }
        }
    }

    private fun ready(check: SduiCheckState = SduiCheckState.Idle) =
        SduiUiState(SduiPhase.Ready(screen, canCheck = true), check)

    @Test
    fun rendersEveryAllowedComponentAndKeepsNewlines() {
        show(ready())
        compose.onNodeWithText("状況").assertIsDisplayed()
        compose.onNodeWithText("前回の確認は成功しました").assertIsDisplayed()
        compose.onNodeWithText("最終確認").assertIsDisplayed()
        compose.onNodeWithText("今日 9:00").assertIsDisplayed()
        compose.onNodeWithText("一行目\n二行目").assertIsDisplayed()
    }

    @Test
    fun buttonsReportOnlyTheirFixedActionAndNothingRunsOnOpen() {
        show(ready())
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().performClick()
        compose.onNodeWithTag("sdui_button_refresh").performScrollTo().performClick()
        compose.onNodeWithTag("sdui_button_native.settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("send", "refresh", "settings"), calls) }
    }

    @Test
    fun serverDisabledButtonCannotBeTapped() {
        show(ready())
        compose.onNodeWithTag("sdui_button_native.browser").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test
    fun checkButtonIsBlockedWhileSendingAndAfterAcceptance() {
        show(ready(SduiCheckState.Sending("op-1", retry = false)))
        compose.onNodeWithTag("sdui_check_sending").assertIsDisplayed()
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun acceptedReceiptShowsProgressAndBlocksAnotherCheckUntilRefreshed() {
        val receipt = SduiReceipt("bundled-1", "c1", "default", "op-1", SduiReceiptState.QUEUED, "run-1")
        show(ready(SduiCheckState.Accepted(receipt)))
        compose.onNodeWithText("確認を受け付けました。順番に確認します。").assertIsDisplayed()
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag("sdui_refresh")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(listOf("refresh"), calls) }
    }

    @Test
    fun ambiguousResultOffersAnExplicitSameRequestRetryAndBlocksANewOne() {
        show(ready(SduiCheckState.Ambiguous("op-1")))
        compose.onNodeWithTag("sdui_check_unknown").assertIsDisplayed()
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag("sdui_retry_check")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(listOf("retry"), calls) }
    }

    @Test
    fun blockedStateOffersNeitherANewCheckNorAResendButARefresh() {
        show(ready(SduiCheckState.Blocked))
        compose.onNodeWithTag("sdui_check_blocked").assertIsDisplayed()
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().assertIsNotEnabled()
        compose.onAllNodesWithTag("sdui_retry_check").assertCountEquals(0)
        compose.onNodeWithTag("sdui_refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("refresh"), calls) }
    }

    @Test
    fun staleReleaseAsksForARefreshBeforeAnotherCheck() {
        show(ready(SduiCheckState.NeedsReload(null)))
        compose.onNodeWithTag("sdui_button_mypage.check").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("sdui_refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("refresh"), calls) }
    }

    @Test
    fun unsupportedHostKeepsNativeRoutesAndBackReachable() {
        show(SduiUiState(SduiPhase.Unavailable(SduiUnavailable.HOST_UNSUPPORTED)))
        compose.onNodeWithTag("sdui_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("sdui_native_browser").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sdui_native_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sdui_back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("browser", "settings", "back"), calls) }
    }

    @Test
    fun changedConnectionOffersOnlyBack() {
        show(SduiUiState(SduiPhase.Unavailable(SduiUnavailable.CONNECTION_CHANGED)))
        compose.onNodeWithTag("sdui_back").assertIsDisplayed()
        compose.onAllNodesWithTag("sdui_native_browser").assertCountEquals(0)
        compose.onAllNodesWithTag("sdui_native_settings").assertCountEquals(0)
    }

    @Test
    fun loadFailureOffersRetry() {
        show(SduiUiState(SduiPhase.Unavailable(SduiUnavailable.LOAD_FAILED)))
        compose.onNodeWithTag("error_retry_button").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("reload"), calls) }
    }

    @Test
    fun largeFontKeepsControlsReachableAtTouchTargetSize() {
        show(ready(SduiCheckState.Ambiguous("op-1")), fontScale = 2f)
        listOf("sdui_button_mypage.check", "sdui_retry_check").forEach { tag ->
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
    }
}
