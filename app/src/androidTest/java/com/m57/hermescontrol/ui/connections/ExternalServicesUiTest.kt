package com.m57.hermescontrol.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.mcp.McpServersViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic data only. These tests never connect, authorize, or change an external service. */
class ExternalServicesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture =
        ExternalServicesState(
            loading = false,
            gmail =
                GoogleConnectionStatus(
                    "connected",
                    accounts = listOf(GoogleConnectedAccount("demo", "demo@example.test", "個人")),
                ),
            servers =
                listOf(
                    McpServer("slack", enabled = true),
                    McpServer("figma", enabled = true, error = "authorization required"),
                ),
        )

    @Test fun searchByJapaneseIntentAndOpenTheCorrectService() {
        var opened = ""
        render(onOpen = { opened = it })
        compose.onNode(hasSetTextAction()).performClick().performTextInput("日程")
        captureScreen("search-ime")
        compose.onNodeWithTag("service_calendar").assertIsDisplayed().performClick()
        compose.onNodeWithTag("service_gmail").assertDoesNotExist()
        assertEquals("calendar", opened)
    }

    @Test fun configuredFilterDoesNotShowUnconfiguredServices() {
        render()
        compose.onNodeWithText("設定済み").performClick()
        compose.onNodeWithTag("service_gmail").assertIsDisplayed()
        compose.onNodeWithTag("service_slack").assertIsDisplayed()
        compose.onNodeWithTag("service_calendar").assertDoesNotExist()
        compose.onNodeWithTag("service_figma").assertDoesNotExist()
    }

    @Test fun lightCatalogPreview() {
        render()
        capture("catalog-light")
    }

    @Test fun defaultCatalogFitsSixServiceRows() {
        render()
        listOf("gmail", "calendar", "drive", "figma", "slack", "dropbox").forEach {
            compose.onNodeWithTag("service_$it").assertIsDisplayed()
        }
    }

    @Test fun darkLargeTextCatalogPreview() {
        render(dark = true, scale = 2f)
        capture("catalog-dark-200")
    }

    @Test fun serviceDetailPreviewAndSettingsAction() {
        var setup = false
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) {
                HermesScaffold(title = { androidx.compose.material3.Text("Figma") }, drawerGesturesEnabled = false) {
                    ExternalServiceDetailContent(externalService("figma")!!, fixture, {}, {}, { setup = true })
                }
            }
        }
        capture("detail-figma-light")
        compose.onNodeWithText("Figmaを接続").performScrollTo().performClick()
        assertEquals(true, setup)
    }

    @Test fun connectionSettingsLargeTextPreview() {
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) {
                val density = LocalDensity.current
                val spacing = LocalSpacing.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    HermesScaffold(
                        title = { androidx.compose.material3.Text("Figmaの接続設定") },
                        drawerGesturesEnabled = false,
                    ) {
                        Column(
                            androidx.compose.ui.Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(spacing.lg),
                        ) {
                            com.m57.hermescontrol.ui.mcp.ServerCard(
                                server =
                                    McpServer(
                                        "figma",
                                        enabled = true,
                                        auth = "oauth",
                                        url = "https://example.test/mcp?token=hidden",
                                    ),
                                state =
                                    com.m57.hermescontrol.ui.mcp
                                        .McpServersUiState(),
                                viewModel = viewModel<McpServersViewModel>(),
                                spacing = spacing,
                                onClick = {},
                                onOpenBrowser = {},
                                connectionMode = true,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("example.test").assertIsDisplayed()
        capture("connection-settings-light-200")
    }

    private fun render(
        dark: Boolean = false,
        scale: Float = 1f,
        onOpen: (String) -> Unit = {},
    ) {
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    HermesScaffold(
                        title = { androidx.compose.material3.Text("外部サービス連携") },
                        drawerGesturesEnabled = false,
                    ) {
                        ExternalServicesContent(fixture, onOpen, {}, {}, {})
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "external-services-preview").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun captureScreen(name: String) {
        compose.runOnUiThread {
            androidx.core.view.WindowCompat
                .getInsetsController(
                    compose.activity.window,
                    compose.activity.window.decorView,
                ).show(
                    androidx.core.view.WindowInsetsCompat.Type
                        .ime(),
                )
        }
        compose.waitUntil(5_000) {
            var visible = false
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = androidx.core.view.ViewCompat
                    .getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(
                        androidx.core.view.WindowInsetsCompat.Type
                            .ime(),
                    ) == true
            }
            visible
        }
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "external-services-preview").apply { mkdirs() }
        val bitmap =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
