package com.m57.hermescontrol.ui.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class IntelligentUiRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Volatile private var historyOpened = false

    @Before
    fun connectAccessibilityBeforeCreatingAnyWebView() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
    }

    @Test
    fun lightCalculatorFitsContentAndOptionsAreAvailable() {
        show("calculator", dark = false)
        waitForMeasuredHeight()
        capture("calculator-light")
        compose.onNodeWithContentDescription("図解のオプション").performClick()
        try {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("コード").assertIsDisplayed() }.isSuccess
            }
        } finally {
            capture("calculator-menu")
        }
        compose.onNodeWithText("コード").assertIsDisplayed().performClick()
        compose.onNodeWithText("プレビュー").assertIsDisplayed().performClick()
        waitForMeasuredHeight()
    }

    @Test
    fun darkComparisonUsesTheNativeTheme() {
        show("comparison", dark = true)
        waitForMeasuredHeight()
        capture("comparison-dark")
    }

    @Test
    fun longPreviewHasAVisibleExpansionActionAndKeepsItsDocument() {
        showSource(
            "<main class='viz'><h2>長い図解</h2><input aria-label='保持する値' value='kept'>" +
                "<p>読み取れる説明</p>".repeat(65) + "</main>",
        )
        waitForMeasuredHeight()
        var originalView: IntelligentPreviewWebView? = null
        compose.runOnIdle { originalView = webView(compose.activity.window.decorView) }
        compose.onNodeWithTag("intelligent_ui_expand").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            var expanded = false
            compose.runOnIdle {
                val view = requireNotNull(webView(compose.activity.window.decorView))
                expanded = view.height / view.resources.displayMetrics.density > IntelligentUiSizing.INLINE_LIMIT
            }
            expanded
        }
        compose.runOnIdle { assertSame(originalView, webView(compose.activity.window.decorView)) }
        capture("long-preview-expanded")
    }

    @Test
    fun nisaSliderAcceptsFingerDragAndRecalculates() {
        show("nisa", dark = true)
        waitForMeasuredHeight()
        val slider = waitForVisibleRange()
        capture("nisa-dark-before")
        dragSlider(slider)
        waitForChangedContribution()
        capture("nisa-dark-after-touch")
    }

    @Test
    fun sliderUpdatesContinuouslyWhileFingerIsHeldWithoutOpeningHistory() {
        show("nisa", dark = true)
        waitForMeasuredHeight()
        val bounds = Rect().also(waitForVisibleRange()::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        var fraction = .3f
        var latest = liveSnapshot()
        val samples = mutableListOf("before=$latest")

        fun inject(
            action: Int,
            position: Float,
        ) {
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.left + bounds.width() * position,
                    bounds.exactCenterY() + (position - .3f) * 12,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, fraction)
        try {
            for (target in listOf(.44f, .58f, .74f)) {
                val start = fraction
                repeat(6) { step ->
                    fraction = start + (target - start) * (step + 1) / 6
                    inject(MotionEvent.ACTION_MOVE, fraction)
                    SystemClock.sleep(16)
                }
                val previous = latest
                compose.waitUntil(2_000) {
                    latest = liveSnapshot()
                    latest.first > previous.first && latest.second != previous.second
                }
                samples += "pointerDown=true,value=${latest.first},total=${latest.second},historyOpened=$historyOpened"
                assertTrue("Dragging a slider must not open conversation history", !historyOpened)
            }
            capture("nisa-live-held")
        } finally {
            inject(MotionEvent.ACTION_UP, fraction)
            samples += "released,value=${liveSnapshot()},historyOpened=$historyOpened"
            val root =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "rich-answers",
                )
            root.mkdirs()
            File(root, "nisa-live-samples.txt").writeText(samples.joinToString("\n"))
        }
    }

    private fun liveSnapshot(): Pair<Int, String> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val root = requireNotNull(automation.rootInActiveWindow)
        val monthly =
            accessibilityNode(root) { it.text?.toString()?.matches(Regex("[0-9,]+円")) == true }
                ?.text
                ?.toString()
                ?.removeSuffix("円")
                ?.replace(",", "")
                ?.toIntOrNull() ?: 0
        val total =
            accessibilityNode(root) { it.text?.toString()?.matches(Regex("[0-9,]+\\.\\d(?:万円)?")) == true }
                ?.text
                ?.toString()
                .orEmpty()
        return monthly to total
    }

    @Test
    fun historySwipeStillWorksOutsideInteractivePreview() {
        val message = "通常の会話の本文からは右スワイプで履歴を開けます。"
        showSource(message, dark = true, asMarkdown = true)
        compose.onNodeWithText(message).assertIsDisplayed().performTouchInput {
            swipe(Offset(20f, centerY), Offset(280f, centerY), durationMillis = 320)
        }
        compose.waitUntil(2_000) { historyOpened }
    }

    @Test
    fun narrowLargeTextNisaKeepsItsControlsOperable() {
        show("nisa", dark = false, width = 320, fontScale = 1.3f)
        waitForMeasuredHeight()
        val slider = waitForVisibleRange()
        capture("nisa-large-text-before")
        dragSlider(slider)
        waitForChangedContribution()
        capture("nisa-large-text-after-touch")
    }

    @Test
    fun legacyControlsBelowTheChartCanBeReachedAndDragged() {
        val original =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("rich-answers/nisa.html")
                .bufferedReader()
                .use { it.readText() }
        val controls =
            Regex("(?s)<section class=\"viz-panel\" aria-label=\"シミュレーション条件\">.*?</section>")
                .find(original)
                ?.value ?: error("conditions not found")
        val moved =
            original.replace(controls, "").replace(
                "<section class=\"viz-panel\" aria-label=\"非課税保有限度額の利用状況\">",
                controls + "<section class=\"viz-panel\" aria-label=\"非課税保有限度額の利用状況\">",
            )
        showSource(moved)
        waitForMeasuredHeight()
        capture("nisa-legacy-before-scroll")
        var slider = visibleRange()
        repeat(5) {
            if (slider == null) {
                val screen = Rect()
                compose.runOnIdle {
                    compose.activity.window.decorView
                        .getWindowVisibleDisplayFrame(screen)
                }
                fingerDrag(
                    screen.centerX().toFloat(),
                    screen.top + screen.height() * .78f,
                    screen.centerX().toFloat(),
                    screen.top + screen.height() * .32f,
                )
                compose.waitForIdle()
                slider = visibleRange()
            }
        }
        capture("nisa-legacy-controls-visible")
        assertTrue("Conditions below the chart must be reachable by vertical scrolling", slider != null)
        dragSlider(requireNotNull(slider))
        waitForChangedContribution()
        capture("nisa-legacy-after-touch")
    }

    private fun visibleRange(): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val viewport = Rect()
        compose.runOnIdle {
            compose.activity.window.decorView
                .getWindowVisibleDisplayFrame(viewport)
        }
        return automation.rootInActiveWindow?.let { root ->
            accessibilityNode(root) { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.className?.toString()?.endsWith("SeekBar") == true &&
                    node.isVisibleToUser && bounds.width() > 80 && bounds.top >= viewport.top &&
                    bounds.bottom < viewport.bottom
            }
        }
    }

    private fun waitForVisibleRange(): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        compose.waitUntil(10_000) {
            node = visibleRange()
            node != null
        }
        return requireNotNull(node)
    }

    private fun dragSlider(slider: AccessibilityNodeInfo) {
        val bounds = Rect().also(slider::getBoundsInScreen)
        fingerDrag(
            bounds.left + bounds.width() * .3f,
            bounds.exactCenterY(),
            bounds.left + bounds.width() * .8f,
            bounds.exactCenterY(),
        )
    }

    private fun fingerDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action =
                when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    12 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val fraction = step / 12f
            val event =
                MotionEvent.obtain(
                    down,
                    down + step * 24L,
                    action,
                    startX + (endX - startX) * fraction,
                    startY + (endY - startY) * fraction,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(24)
        }
    }

    private fun waitForChangedContribution() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(10_000) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            automation.rootInActiveWindow?.let { root ->
                accessibilityNode(root) { node ->
                    node.text?.toString()?.matches(Regex("[0-9,]+円")) == true &&
                        node.text
                            .toString()
                            .removeSuffix("円")
                            .replace(",", "")
                            .toIntOrNull()
                            ?.let { it >= 60_000 } ==
                        true
                }
            } != null
        }
    }

    @Test
    fun narrowLargeTextCircuitKeepsAccessibleOptions() {
        show("circuit", dark = false, width = 320, fontScale = 1.5f)
        waitForMeasuredHeight()
        capture("circuit-large-text")
        // Footer can be below the viewport for large content; the preview itself is capped.
        compose.onNodeWithTag("intelligent_ui_preview").assertIsDisplayed()
    }

    @Test
    fun aShortPreviewDoesNotReserveTheOldFixedHeight() {
        showSource("<main class='viz'><h2>短い図解</h2><p>ここまでが内容です。</p></main>")
        waitForMeasuredHeight()
        compose.runOnIdle {
            val view = requireNotNull(webView(compose.activity.window.decorView))
            assertTrue(view.height / view.resources.displayMetrics.density < 220)
        }
    }

    @Test
    fun chatTextPreferenceCombinesWithSystemFontScale() {
        showSource("<main class='viz'><h2>文字サイズ</h2></main>", fontScale = 1.25f, chatScale = 1.2f)
        waitForMeasuredHeight()
        compose.runOnIdle {
            assertTrue(requireNotNull(webView(compose.activity.window.decorView)).settings.textZoom == 150)
        }
    }

    @Test
    fun calculatorAcceptsNativeTextInputAndUpdatesItsResult() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        show("calculator", dark = false)
        waitForMeasuredHeight()
        var input: AccessibilityNodeInfo? = null
        compose.waitUntil(10_000) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            input =
                automation.rootInActiveWindow?.let { accessibilityNode(it) { node -> node.isEditable } }
            input != null
        }
        assertTrue(requireNotNull(input).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val text =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "24000",
                )
            }
        assertTrue(requireNotNull(input).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text))
        try {
            compose.waitUntil(5_000) {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                automation.rootInActiveWindow?.let {
                    accessibilityNode(it) { node -> (node.text ?: node.contentDescription)?.contains("6,000") == true }
                } != null
            }
        } finally {
            capture("calculator-input")
        }
    }

    private fun accessibilityNode(
        node: AccessibilityNodeInfo,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> accessibilityNode(child, matches)?.let { return it } }
        }
        return null
    }

    private fun show(
        example: String,
        dark: Boolean,
        width: Int = 380,
        fontScale: Float = 1f,
    ) {
        val source =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("rich-answers/$example.html")
                .bufferedReader()
                .use { it.readText() }
        showSource(source, dark, width, fontScale)
    }

    private fun showSource(
        source: String,
        dark: Boolean = false,
        width: Int = 380,
        fontScale: Float = 1f,
        chatScale: Float = 1f,
        asMarkdown: Boolean = false,
    ) {
        compose.setContent {
            HermesControlTheme(
                themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT,
                chatFontScale = chatScale,
            ) {
                val density = LocalDensity.current
                val context = LocalContext.current
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.JAPAN) }
                val japaneseContext =
                    remember(context, configuration) { context.createConfigurationContext(configuration) }
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalContext provides japaneseContext,
                    LocalConfiguration provides configuration,
                    LocalResources provides japaneseContext.resources,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .conversationHistorySwipe { historyOpened = true }
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            "アシスタント",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        MarkdownText(
                            if (asMarkdown) source else "```hermes-ui\n$source\n```",
                            MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(width.dp),
                        )
                    }
                }
            }
        }
    }

    private fun waitForMeasuredHeight() {
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle {
                val view = webView(compose.activity.window.decorView)
                ready = view != null && view.height > 0 &&
                    abs(view.height / view.resources.displayMetrics.density - IntelligentUiSizing.INITIAL_HEIGHT) > 3
            }
            ready
        }
    }

    private fun webView(view: View): IntelligentPreviewWebView? {
        if (view is IntelligentPreviewWebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun capture(name: String) {
        val root =
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "rich-answers")
        root.mkdirs()
        val drawn = CountDownLatch(1)
        val measured = CountDownLatch(1)
        val frameHeight = AtomicInteger()
        compose.runOnIdle {
            val view = requireNotNull(webView(compose.activity.window.decorView))
            view.postVisualStateCallback(
                1,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        view.invalidate()
                        drawn.countDown()
                    }
                },
            )
            view.evaluateJavascript(
                "document.querySelector('iframe').offsetHeight",
            ) {
                frameHeight.set(it.toIntOrNull() ?: 0)
                measured.countDown()
            }
        }
        assertTrue("WebView's visual state must be ready", drawn.await(10, TimeUnit.SECONDS))
        assertTrue("Viewport measurement must complete", measured.await(10, TimeUnit.SECONDS))
        assertTrue(
            "The sandbox iframe must remain visible inside AndroidView",
            frameHeight.get() >= IntelligentUiSizing.MIN_HEIGHT,
        )
        compose.mainClock.advanceTimeByFrame()
        File(root, "$name.png").outputStream().use {
            compose.waitForIdle()
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
