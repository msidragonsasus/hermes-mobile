package com.m57.hermescontrol.ui.chat.fullbleed

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.ThinkingIndicator
import com.m57.hermescontrol.ui.chat.components.ThinkingLabel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The 「思考中」 indicator in the real full-bleed chat list and its rendered shimmer. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ThinkingIndicatorUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val turn =
        listOf(
            ChatMessage(id = "u", role = MessageRole.USER, content = "調べて"),
            ChatMessage(id = "c", role = MessageRole.ASSISTANT, content = "まず確認します。"),
            ChatMessage(id = "t", role = MessageRole.TOOL, content = "{}", toolStatus = ToolStatus.COMPLETED),
        )

    @Composable
    private fun Japanese(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPANESE) }
        val japanese = context.createConfigurationContext(configuration)
        CompositionLocalProvider(
            LocalContext provides japanese,
            LocalConfiguration provides configuration,
            content = content,
        )
    }

    private fun renderList(
        showThinkingIndicator: Boolean,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                    Japanese {
                        val listState = LazyListState()
                        FullBleedChatList(
                            messages = turn,
                            streamingMessage = null,
                            searchState = ChatSearchState(),
                            typingEffectEnabled = false,
                            typingEffectDelayMs = 30,
                            isLoading = false,
                            isLoadingOlder = false,
                            isDark = dark,
                            listState = listState,
                            scrollController =
                                com.m57.hermescontrol.ui.chat.components.ChatScrollController(
                                    listState = listState,
                                    scope = CoroutineScope(Dispatchers.Main.immediate),
                                ),
                            lastAnimatedMessageId = null,
                            onLastAnimatedMessageIdChange = {},
                            viewModel = mockk<ChatViewModel>(relaxed = true),
                            showThinkingIndicator = showThinkingIndicator,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun japaneseResourceIsTheExactRequestedLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.JAPANESE) }
        val japanese = context.createConfigurationContext(configuration)
        assertEquals("思考中", japanese.getString(R.string.chat_thinking_indicator))
    }

    @Test
    fun indicatorSitsBelowInterimCommentaryAndTheFoldedToolGroup() {
        renderList(showThinkingIndicator = true)
        val commentary = composeTestRule.onNodeWithText("まず確認します。").assertIsDisplayed()
        composeTestRule.onNodeWithTag("agent_turn_activity").assertIsDisplayed()
        val label = composeTestRule.onNodeWithText("思考中").assertIsDisplayed()
        assertTrue(
            label.getUnclippedBoundsInRoot().top >= commentary.getUnclippedBoundsInRoot().bottom,
        )
    }

    @Test
    fun finishedTurnRendersNoIndicatorButKeepsItsText() {
        renderList(showThinkingIndicator = false)
        composeTestRule.onNodeWithText("まず確認します。").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thinking_indicator").assertDoesNotExist()
    }

    @Test
    fun darkThemeAndDoubleFontKeepTheWholeLabelReadable() {
        renderList(showThinkingIndicator = true, dark = true, fontScale = 2f)
        val label = composeTestRule.onNodeWithText("思考中").assertIsDisplayed()
        val bounds = label.getUnclippedBoundsInRoot()
        assertTrue("Large text grows the row instead of clipping", (bounds.bottom - bounds.top).value >= 40f)
    }

    private fun renderLabel(
        dark: Boolean,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                Japanese {
                    Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
    }

    /** Average luminance of glyph pixels in each third of the label, left to right. */
    private fun ImageBitmap.thirdsContrast(backgroundLuminance: Float): List<Float> {
        val pixels = toPixelMap()
        return (0 until 3).map { third ->
            val xs = (width * third / 3) until (width * (third + 1) / 3)
            val glyphs =
                xs
                    .flatMap { x -> (0 until height).map { y -> pixels[x, y].luminance() } }
                    .map { kotlin.math.abs(it - backgroundLuminance) }
                    .filter { it > 0.05f }
            if (glyphs.isEmpty()) 0f else glyphs.average().toFloat()
        }
    }

    private fun assertBandMovesInReadingOrder(dark: Boolean) {
        val phase = mutableFloatStateOf(0.2f)
        var background = 0f
        renderLabel(dark) {
            background = MaterialTheme.colorScheme.background.luminance()
            ThinkingLabel(phase = { phase.floatValue })
        }
        val label = composeTestRule.onNodeWithText("思考中").assertIsDisplayed()
        val early = label.captureToImage().thirdsContrast(background)
        composeTestRule.runOnIdle { phase.floatValue = 0.8f }
        composeTestRule.waitForIdle()
        val late = composeTestRule.onNodeWithTag("thinking_indicator").captureToImage().thirdsContrast(background)
        assertTrue("dark=$dark early=$early", early.first() > early.last())
        assertTrue("dark=$dark late=$late", late.last() > late.first())
    }

    @Test
    fun pinnedPhasesMoveTheLightnessBandAcrossTheLettersInLightTheme() = assertBandMovesInReadingOrder(dark = false)

    @Test
    fun pinnedPhasesMoveTheLightnessBandAcrossTheLettersInDarkTheme() = assertBandMovesInReadingOrder(dark = true)

    @Test
    fun disabledAnimationIsStaticAndReadable() {
        renderLabel(dark = false) { ThinkingIndicator(animate = false) }
        composeTestRule.mainClock.autoAdvance = false
        val first =
            composeTestRule
                .onNodeWithText("思考中")
                .assertIsDisplayed()
                .captureToImage()
                .toPixelMap()
        composeTestRule.mainClock.advanceTimeBy(1_200)
        val later = composeTestRule.onNodeWithTag("thinking_indicator").captureToImage().toPixelMap()
        assertEquals(first.width, later.width)
        val changed =
            (0 until first.width).sumOf { x -> (0 until first.height).count { y -> first[x, y] != later[x, y] } }
        assertEquals("Nothing moves with animations off", 0, changed)
    }

    @Test
    fun runningAnimationMovesWithTheClock() {
        renderLabel(dark = true) { ThinkingIndicator(animate = true) }
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.mainClock.advanceTimeBy(600)
        val first = composeTestRule.onNodeWithTag("thinking_indicator").captureToImage().toPixelMap()
        composeTestRule.mainClock.advanceTimeBy(900)
        val later = composeTestRule.onNodeWithTag("thinking_indicator").captureToImage().toPixelMap()
        val changed =
            (0 until first.width).sumOf { x -> (0 until first.height).count { y -> first[x, y] != later[x, y] } }
        assertNotEquals(0, changed)
    }
}
