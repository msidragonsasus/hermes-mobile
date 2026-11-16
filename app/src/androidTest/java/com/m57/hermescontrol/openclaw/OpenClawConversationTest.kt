package com.m57.hermescontrol.openclaw

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.ConversationTitleSelector
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Presentation fixtures only: no Hermes requests, Gateway connection, credentials or inference. */
class OpenClawConversationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun useProductionWindowInsets() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private val initial =
        ClawUiState(
            configured = true,
            connection = ClawConnection.CONNECTED,
            sessionKey = "one",
            canSend = true,
            canPatch = true,
            model = "gpt-6.1-sol",
            effort = "medium",
            sessions = listOf(ClawSession("one", "旅行の計画"), ClawSession("two", "今週の予定")),
            messages =
                listOf(
                    ClawMessage("u", "user", "京都の旅行プランを一緒に考えて。"),
                    ClawMessage(
                        "a",
                        "assistant",
                        "もちろん。まずは **日程と行きたい場所** を決めましょう。\n\n" +
                            "- 何泊の予定ですか？\n- 食事と観光、どちらを重視しますか？",
                    ),
                ),
        )

    @Composable
    private fun Conversation(
        state: ClawUiState = initial,
        onSend: (String, () -> Unit) -> Unit = { _, _ -> },
        onStop: () -> Unit = {},
        onSelect: (String) -> Unit = {},
        onModel: () -> Unit = {},
        onEffort: () -> Unit = {},
    ) {
        OpenClawConversation(
            state = state,
            owner = "fixture",
            onSend = onSend,
            onStop = onStop,
            onNew = {},
            onSelectSession = onSelect,
            onRefresh = {},
            onReconnect = {},
            onSettings = {},
            onModel = onModel,
            onEffort = onEffort,
            title = { ConversationTitleSelector(title = "OpenClaw", onClick = {}) },
        )
    }

    @Test
    fun usesHermesMessageComposerAndHistoryComponents() {
        var modelClicks = 0
        var effortClicks = 0
        var selected = ""
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.DARK) {
                Conversation(onSelect = { selected = it }, onModel = { modelClicks++ }, onEffort = { effortClicks++ })
            }
        }
        compose.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        compose.onNodeWithTag("fullbleed_copy").assertDoesNotExist()
        compose.onNodeWithTag("fullbleed_agent_message").performTouchInput { longClick(center) }
        compose.onNodeWithTag("message_action_copy").assertIsDisplayed().performClick()
        compose.onNodeWithText("この会話だけの設定").assertDoesNotExist()
        compose.onNodeWithText("OpenClaw 接続済み").assertDoesNotExist()
        screenshot("conversation-dark.png")
        compose.onNodeWithTag("openclaw_input").performClick()
        compose.onNodeWithTag("model_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, modelClicks)
            assertEquals(1, effortClicks)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_open_conversation_menu)).performClick()
        compose.onNodeWithTag("conversation_search").performTextInput("今週")
        compose.onNodeWithText("旅行の計画").assertDoesNotExist()
        compose.onNodeWithTag("conversation_session_two").assertIsDisplayed()
        screenshot("conversation-history.png")
        compose.onNodeWithTag("conversation_session_two").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }

    @Test
    fun keepsDraftUntilAcceptanceAndRetainsNewerText() {
        var acknowledgement: (() -> Unit)? = null
        var sent = ""
        compose.setContent {
            HermesControlTheme {
                Conversation(onSend = { text, accepted ->
                    sent = text
                    acknowledgement = accepted
                })
            }
        }
        compose.onNodeWithTag("openclaw_input").performTextInput("確認したい")
        compose.onNodeWithTag("openclaw_send").performClick()
        compose.runOnIdle { assertEquals("確認したい", sent) }
        compose.onNodeWithTag("openclaw_input").assertTextContains("確認したい")
        compose.onNodeWithTag("openclaw_input").performTextInput("追加")
        compose.runOnIdle { requireNotNull(acknowledgement).invoke() }
        compose.onNodeWithTag("openclaw_input").assertTextContains("確認したい追加")
        compose.onNodeWithTag("openclaw_send").performClick()
        compose.runOnIdle { requireNotNull(acknowledgement).invoke() }
        compose.onNodeWithTag("openclaw_input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithTag("openclaw_send").assertDoesNotExist()
    }

    @Test
    fun draftsStayWithTheirConversationAcrossHistorySwitches() {
        val state = mutableStateOf(initial)
        compose.setContent { HermesControlTheme { Conversation(state = state.value) } }
        compose.onNodeWithTag("openclaw_input").performTextInput("一つ目")
        compose.runOnIdle { state.value = initial.copy(sessionKey = "two") }
        compose.onNodeWithTag("openclaw_input").performTextInput("二つ目")
        compose.runOnIdle { state.value = initial }
        compose.onNodeWithTag("openclaw_input").assertTextContains("一つ目")
        compose.runOnIdle { state.value = initial.copy(sessionKey = "two") }
        compose.onNodeWithTag("openclaw_input").assertTextContains("二つ目")
    }

    @Test
    fun busyConversationOffersStopAndDisablesSessionChanges() {
        var stops = 0
        compose.setContent {
            HermesControlTheme {
                Conversation(state = initial.copy(busy = true, streaming = "調べています"), onStop = { stops++ })
            }
        }
        compose.onNodeWithTag("openclaw_stop").assertIsDisplayed().performClick()
        compose.onNodeWithTag("openclaw_send").assertDoesNotExist()
        compose.onNodeWithTag("conversation_new_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun lightThemeAndLargeFontKeepComposerAndOptionsReachable() {
        compose.setContent {
            HermesControlTheme(themePreference = ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Conversation()
                }
            }
        }
        compose.onNodeWithTag("openclaw_input").performTextInput("来週末の予定です")
        compose.onNodeWithTag("model_chip").assertIsDisplayed()
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed()
        compose.onNodeWithTag("openclaw_send").assertIsDisplayed()
        screenshot("conversation-light-large-ime.png")
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("effort: medium", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val text = layout.layoutInput.text
        val lastGlyph = layout.getBoundingBox(text.lastIndex)
        assertFalse("Effort must not be ellipsized", layout.isLineEllipsized(0))
        assertEquals("All effort characters must be on the visible line", text.length, layout.getLineEnd(0, true))
        // TextLayoutResult compares fractional paragraph width with rounded pixel size. Check
        // the rendered final glyph instead, allowing only the one-pixel rounding boundary.
        assertTrue(
            "Final effort glyph must fit: $lastGlyph in ${layout.size}",
            lastGlyph.right <= layout.size.width + 1f,
        )
        assertTrue("Effort line must fit vertically", lastGlyph.bottom <= layout.size.height + 1f)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(context.getExternalFilesDir(null), name)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
