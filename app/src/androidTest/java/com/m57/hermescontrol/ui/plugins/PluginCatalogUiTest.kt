package com.m57.hermescontrol.ui.plugins

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginCatalogCapabilities
import com.m57.hermescontrol.data.model.PluginCatalogEntry
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Catalog browsing, review sheet and install confirmation with synthetic entries; no server calls. */
class PluginCatalogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val catalog =
        listOf(
            PluginCatalogEntry(
                name = "memory-wiki",
                title = "Memory Wiki",
                tier = "official",
                category = "memory",
                maintainer = "Nous Research",
                version = "1.2.0",
                repo = "https://github.com/example/memory-wiki",
                description = "Keeps a searchable wiki of what Hermes learns. Pages are plain Markdown.",
                capabilities =
                    PluginCatalogCapabilities(
                        providesTools = listOf("wiki_search", "wiki_write"),
                        providesHooks = listOf("on_session_end"),
                    ),
            ),
            PluginCatalogEntry(
                name = "spotify",
                title = "Spotify",
                tier = "verified",
                category = "tools",
                maintainer = "example",
                description = "Control music playback from chat.",
                capabilities =
                    PluginCatalogCapabilities(
                        providesTools = listOf("play"),
                        requiresEnv = listOf("SPOTIFY_TOKEN"),
                    ),
                installed = true,
            ),
            PluginCatalogEntry(
                name = "janitor",
                tier = "community",
                category = "tools",
                maintainer = "someone",
                description = "Finds leftover background processes.",
                capabilities = PluginCatalogCapabilities(providesHooks = listOf("post_tool_call")),
            ),
            PluginCatalogEntry(name = "accent", title = "Accent picker", tier = "official", category = "desktop"),
        )

    private var theme by mutableStateOf(ThemePreference.DARK)
    private var scale by mutableStateOf(1f)
    private var opened by mutableStateOf<PluginCatalogEntry?>(null)
    private var installs = 0

    private fun start() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesControlTheme(themePreference = theme) {
                    HermesScaffold(
                        title = { Text(context.getString(R.string.plugins_catalog_title)) },
                        navigationIcon = NavIcon.Back {},
                        drawerGesturesEnabled = false,
                    ) {
                        PluginCatalogContent(catalog = catalog, onOpen = { opened = it })
                    }
                    opened?.let { entry ->
                        PluginCatalogSheet(
                            entry = entry,
                            installing = false,
                            onInstall = { installs++ },
                            onDismiss = { opened = null },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun recommendedShelvesHideCommunityAndDesktopEntries() {
        start()
        compose.onNodeWithText("Memory Wiki").assertIsDisplayed()
        compose.onNodeWithText("Spotify").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("janitor").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Accent picker").fetchSemanticsNodes().size)
        capture("catalog-dark")
        compose.onNodeWithText(context.getString(R.string.plugins_catalog_filter_all)).performClick()
        compose.onNodeWithText("janitor").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Accent picker").fetchSemanticsNodes().size)
    }

    @Test
    fun reviewSheetExplainsCapabilitiesAndInstallNeedsConfirmation() {
        start()
        compose.onNodeWithTag("catalog_memory-wiki").performClick()
        compose.onNodeWithText(context.getString(R.string.plugins_cap_tools, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugins_cap_hook_sessions)).assertIsDisplayed()
        capture("sheet-dark", sheet = true)
        compose.onNodeWithTag("plugin_catalog_install").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithTag("plugin_install_confirm").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test
    fun installedEntryCannotBeInstalledAgain() {
        start()
        compose.onNodeWithTag("catalog_spotify").performClick()
        compose.onNodeWithTag("plugin_catalog_install").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun largeLightTextKeepsRowsReadable() {
        theme = ThemePreference.LIGHT
        scale = 2f
        start()
        compose.onNodeWithText("Memory Wiki").assertIsDisplayed()
        capture("catalog-light-2x")
        compose.onNodeWithTag("catalog_memory-wiki").performClick()
        compose.onNodeWithTag("plugin_catalog_sheet").assertIsDisplayed()
        capture("sheet-light-2x", sheet = true)
    }

    @Test
    fun installedPluginRowSaysWhatItAdds() {
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.DARK) {
                Column(Modifier.padding(16.dp)) {
                    PluginCard(
                        plugin = PluginInfo("spotify", "Control music playback from chat.", runtimeStatus = "enabled"),
                        state = PluginsUiState(),
                        viewModel = PluginsViewModel(),
                        onClick = {},
                        catalogEntry = catalog[1],
                    )
                }
            }
        }
        compose.onNodeWithText("Spotify").assertIsDisplayed()
        capture("installed-row-dark")
    }

    private fun capture(
        name: String,
        sheet: Boolean = false,
    ) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "plugin-catalog").apply { mkdirs() }
        // The sheet lives in its own window, so it is captured by its node rather than the root.
        val node = if (sheet) compose.onNodeWithTag("plugin_catalog_sheet") else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
