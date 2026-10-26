package com.m57.hermescontrol.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fake callbacks and account state only; no live Slack login or workspace changes. */
class SlackLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loginIsOneExplicitActionWithoutASecretForm() {
        var starts = 0
        render(
            SlackConnectionUiState(loading = false, status = SlackConnectionStatus("ready")),
            onConnect = { starts++ },
        )
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(0, starts)
        capture("slack-login-light")
        compose.onNodeWithTag("slack_login").assertIsDisplayed().performClick()
        assertEquals(1, starts)
    }

    @Test fun unpreparedServerDoesNotAskForClientSecretOrShowAFakeLogin() {
        render(SlackConnectionUiState(loading = false, status = SlackConnectionStatus("setup_required")))
        compose.onNodeWithText("管理者によるSlack連携の初回設定が必要です。").assertIsDisplayed()
        compose.onNodeWithTag("slack_login").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun pendingAuthorizationCanBeCancelled() {
        var cancels = 0
        render(SlackConnectionUiState(loading = false, connecting = true), onCancel = { cancels++ })
        compose.onNodeWithText("認証を中止").performClick()
        assertEquals(1, cancels)
        compose.onNodeWithTag("slack_login").assertDoesNotExist()
    }

    @Test fun darkDoubleTextLoginPreview() {
        render(
            SlackConnectionUiState(loading = false, status = SlackConnectionStatus("ready")),
            dark = true,
            scale = 2f,
        )
        compose.onNodeWithTag("slack_login").performScrollTo().assertIsDisplayed()
        capture("slack-login-dark-200")
    }

    private fun render(
        state: SlackConnectionUiState,
        dark: Boolean = false,
        scale: Float = 1f,
        onConnect: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    HermesScaffold(title = { Text("Slack") }, drawerGesturesEnabled = false) {
                        ExternalServiceDetailContent(
                            externalService("slack")!!,
                            ExternalServicesState(
                                loading = false,
                                servers =
                                    if (state.status?.state in setOf("ready", "authorized")) {
                                        listOf(McpServer("slack", enabled = true, auth = "oauth"))
                                    } else {
                                        emptyList()
                                    },
                            ),
                            {},
                            {},
                            {},
                            slackState = state,
                            onSlackConnect = onConnect,
                            onSlackCancel = onCancel,
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "slack-login-preview").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
