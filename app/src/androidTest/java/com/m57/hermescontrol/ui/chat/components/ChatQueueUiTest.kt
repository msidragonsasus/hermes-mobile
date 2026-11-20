package com.m57.hermescontrol.ui.chat.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.DraftState
import com.m57.hermescontrol.domain.hermes.OutgoingDraft
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ChatQueueUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Fixture(
        dark: Boolean,
        large: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPANESE) }
        val context = LocalContext.current.createConfigurationContext(config)
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalResources provides context.resources,
            LocalDensity provides Density(LocalDensity.current.density, if (large) 1.8f else 1f),
        ) {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun offlineComposerCanQueueAtLargeFont() {
        var sent = 0
        compose.setContent {
            Fixture(dark = false, large = true) {
                ChatInputBar(
                    inputFieldValue = TextFieldValue("接続できたら、この続きを進めて"),
                    onInputChange = {},
                    onSend = { sent++ },
                    onMicTap = {},
                    isListening = false,
                    isAgentTyping = false,
                    isConnected = false,
                    commandCatalog = CommandCatalog(),
                    queueOnSend = true,
                    allowOfflineQueue = true,
                )
            }
        }
        compose.onNodeWithContentDescription("キューに追加").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, sent) }
        screenshot("queue-composer-ja-large-light.png")
    }

    @Test
    fun busyComposerUsesTheSingleSendAction() {
        var sent = 0
        compose.setContent {
            Fixture(dark = true) {
                ChatInputBar(
                    inputFieldValue = TextFieldValue("終わったら次の作業をお願い"),
                    onInputChange = {},
                    onSend = { sent++ },
                    onMicTap = {},
                    isListening = false,
                    isAgentTyping = true,
                    isConnected = true,
                    commandCatalog = CommandCatalog(),
                )
            }
        }
        compose.onNodeWithContentDescription("キューに追加").assertDoesNotExist()
        compose.onNodeWithContentDescription("今の実行に追加").assertDoesNotExist()
        compose.onNodeWithTag("send_button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, sent) }
    }

    @Test
    fun queuedPanelExplainsAutomaticSendAndAllowsEditAndCancel() {
        var restored = ""
        compose.setContent {
            Fixture(dark = true, large = true) {
                Column {
                    OutgoingDraftPanel(
                        drafts =
                            listOf(
                                OutgoingDraft(
                                    "queued",
                                    ConnectionScope("fixture", null, null, "process", 1),
                                    "元の会話",
                                    null,
                                    "今の作業が終わったら、この依頼を進めてください。",
                                    emptyList(),
                                    1,
                                    state = DraftState.QUEUED,
                                    queued = true,
                                ),
                            ),
                        activeIds = emptySet(),
                        storageError = false,
                        onRestore = { restored = it },
                        onOpenConversation = {},
                        onDiscard = {},
                        onQueue = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("outgoing-drafts").performClick()
        compose.onNodeWithText("送信待ち（キュー）").performScrollTo().assertIsDisplayed()
        screenshot("queue-panel-ja-large-dark.png")
        compose.onNodeWithText("送信待ちを取り消す").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("キューから戻して編集").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("queued", restored) }
    }
}
