package com.m57.hermescontrol.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogEnvVar
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.mcp.McpCatalogContent
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic catalog and callbacks; never installs or authorizes a service. */
class McpCatalogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun prepareNeedsOnlyTheCatalogEntryAndAnExplicitTap() {
        var requested = ""
        render(listOf(McpCatalogEntry("notion", description = "ページを探す")), onInstall = { requested = it.name })
        assertEquals("", requested)
        compose.onNodeWithTag("prepare_connection_notion").assertIsDisplayed().performClick()
        assertEquals("notion", requested)
    }

    @Test fun requiredInputIsCompletedBeforePreparation() {
        val entry =
            McpCatalogEntry(
                "example-service",
                env = listOf(McpCatalogEnvVar("CLIENT_SECRET", "接続用シークレット", required = true)),
            )
        var state by mutableStateOf(McpServersUiState(catalogSetupEntry = "example-service"))
        var installs = 0
        render(
            listOf(entry),
            state = { state },
            onInstall = { installs++ },
            onUpdate = { key, value -> state = state.copy(catalogInstallEnv = mapOf(key to value)) },
        )
        compose.onNodeWithTag("prepare_connection_example-service").assertIsNotEnabled()
        compose.onNodeWithTag("connection_field_CLIENT_SECRET").performTextInput("synthetic-secret")
        compose
            .onNodeWithTag("prepare_connection_example-service")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, installs)
    }

    @Test fun existingDisabledConnectionCannotBeReinstalledFromTheCatalog() {
        val state = McpServersUiState(servers = listOf(McpServer("notion", enabled = false)))
        render(listOf(McpCatalogEntry("notion")), state = { state })
        compose.onNodeWithTag("prepare_connection_notion").assertIsNotEnabled()
        compose.onNodeWithText("接続先は登録済み").assertIsDisplayed()
    }

    @Test fun refreshingInventoryDisablesPreparation() {
        render(listOf(McpCatalogEntry("notion")), state = { McpServersUiState(isLoading = true) })
        compose.onNodeWithTag("prepare_connection_notion").assertIsNotEnabled()
    }

    @Test fun sharedGoogleConnectionLightPreview() {
        render(listOf(McpCatalogEntry("google_workspace", description = "予定とファイルを扱う接続先")))
        compose
            .onNodeWithText("Google カレンダー・Google Drive で共用する接続先です。変更は各サービスに影響します。")
            .assertIsDisplayed()
        capture("workspace-method-light")
    }

    @Test fun maskedSetupDarkLargeTextPreview() {
        val entry =
            McpCatalogEntry(
                "example-service",
                description = "会話を検索する",
                env = listOf(McpCatalogEnvVar("CLIENT_SECRET", "接続用シークレット", required = true)),
            )
        val state =
            McpServersUiState(
                catalogSetupEntry = "example-service",
                catalogInstallEnv =
                    mapOf("CLIENT_SECRET" to "synthetic-secret"),
            )
        render(listOf(entry), state = { state }, dark = true, scale = 1.5f)
        compose.onNodeWithTag("connection_field_CLIENT_SECRET").assertIsDisplayed()
        capture("account-setup-dark-150")
    }

    private fun render(
        entries: List<McpCatalogEntry>,
        state: () -> McpServersUiState = { McpServersUiState() },
        dark: Boolean = false,
        scale: Float = 1f,
        onInstall: (McpCatalogEntry) -> Unit = {},
        onUpdate: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    HermesScaffold(title = { Text("接続方法を選ぶ") }, drawerGesturesEnabled = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            McpCatalogContent(state(), entries, {}, {}, onInstall, onUpdate)
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.filesDir, "connection-catalog-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
