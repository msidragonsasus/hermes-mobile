package com.m57.hermescontrol.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Compile-checked on host; execution requires separately authorized Android test device. */
class PromptDisclosureUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val longText = (1..40).joinToString("\n") { "設問 $it：大切にしたい価値観を説明してください。" }
    private val message = mutableStateOf(ChatMessage(id = "u-long", role = MessageRole.USER, content = longText))
    private val searching = mutableStateOf(false)
    private val visible = mutableStateOf(true)

    private fun render(dark: Boolean = false) {
        compose.setContent {
            val context = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPANESE) }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalConfiguration provides config,
            ) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    val holder = rememberSaveableStateHolder()
                    if (visible.value) {
                        holder.SaveableStateProvider("prompt") {
                            Box(Modifier.width(300.dp)) {
                                ChatBubble(
                                    message = message.value,
                                    searchQuery = if (searching.value) "設問 40" else "",
                                    isCurrentMatch = searching.value,
                                    showTimestamp = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun longPromptStartsCollapsedAndCanExpandThenCollapse() {
        render()
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
        compose.onNodeWithTag("prompt_disclosure_fade").assertExists()
        val initialBounds = compose.onNodeWithTag("prompt_body").getUnclippedBoundsInRoot()
        val initial = initialBounds.bottom - initialBounds.top
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.onNodeWithText("折りたたむ").assertExists()
        compose.onNodeWithTag("prompt_disclosure_fade").assertDoesNotExist()
        val expandedBounds = compose.onNodeWithTag("prompt_body").getUnclippedBoundsInRoot()
        val expanded = expandedBounds.bottom - expandedBounds.top
        assertTrue(expanded > initial)
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }

    @Test
    fun disposingAndRestoringUnchangedPromptPreservesExpansion() {
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("折りたたむ").assertExists()
    }

    @Test
    fun editedWhileDisposedPromptRejectsRestoredExpansion() {
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.runOnIdle { message.value = message.value.copy(content = longText + "\n変更") }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }

    @Test
    fun shortPromptHasNoDisclosureControl() {
        message.value = message.value.copy(content = "短い質問")
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.onNodeWithText("短い質問").assertIsDisplayed()
    }

    @Test
    fun searchTemporarilyRevealsTailWithoutDestroyingManualState() {
        render()
        compose.runOnIdle { searching.value = true }
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.onNodeWithTag("prompt_disclosure_fade").assertDoesNotExist()
        compose.runOnIdle { searching.value = false }
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }

    @Test
    fun changingMessageIdentityResetsExpandedPreference() {
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.runOnIdle { message.value = message.value.copy(id = "different") }
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }

    @Test
    fun editedContentResetsExpandedPreference() {
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").performClick()
        compose.runOnIdle { message.value = message.value.copy(content = longText + "\n修正") }
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }

    @Test
    fun assistantOutputIsNeverFoldedEvenThroughSharedBubble() {
        message.value = message.value.copy(role = MessageRole.ASSISTANT)
        render()
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.onNodeWithTag("prompt_disclosure_fade").assertDoesNotExist()
    }

    @Test
    fun wrappedJapaneseWithoutNewlinesAlsoCollapsesInDarkTheme() {
        message.value = message.value.copy(content = "長い日本語の質問".repeat(600))
        render(dark = true)
        compose.onNodeWithText("もっと表示").assertIsDisplayed()
    }
}
