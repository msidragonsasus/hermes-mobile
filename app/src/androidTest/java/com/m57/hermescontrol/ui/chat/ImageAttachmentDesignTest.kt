package com.m57.hermescontrol.ui.chat

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.GifImageThumbnail
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import com.m57.hermescontrol.ui.common.HermesScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Local decoded images and the real shared UI. Never connects to a server or sends a message. */
class ImageAttachmentDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var landscape: Attachment
    private lateinit var portrait: Attachment
    private val pending = mutableStateOf(emptyList<Attachment>())
    private val draft = mutableStateOf(TextFieldValue("この写真について教えて"))
    private val theme = mutableStateOf(ThemePreference.DARK)
    private val width = mutableStateOf(390f)
    private val scale = mutableStateOf(1f)
    private var imeBottom = 0
    private var previewed: String? = null
    private var sends = 0
    private val selected = mutableStateOf<ImageViewerModel?>(null)

    @Before
    fun fixtures() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        landscape = image("旅行の写真_横向き_20261116.png", 960, 600)
        portrait = image("旅行の写真_縦向き_20261116.png", 600, 800)
    }

    private fun image(
        name: String,
        width: Int,
        height: Int,
    ): Attachment {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / 960f, height / 600f)
        canvas.drawColor(Color.rgb(236, 224, 201))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(215, 137, 94)
        canvas.drawCircle(735f, 135f, 62f, paint)
        paint.color = Color.rgb(131, 153, 133)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, 400f)
                lineTo(290f, 140f)
                lineTo(620f, 470f)
                lineTo(960f, 260f)
                lineTo(960f, 600f)
                lineTo(0f, 600f)
                close()
            },
            paint,
        )
        paint.color = Color.rgb(55, 91, 75)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, 500f)
                lineTo(460f, 300f)
                lineTo(790f, 510f)
                lineTo(960f, 430f)
                lineTo(960f, 600f)
                lineTo(0f, 600f)
                close()
            },
            paint,
        )
        paint.color = Color.rgb(203, 186, 146)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, 600f)
                cubicTo(250f, 480f, 620f, 610f, 960f, 510f)
                lineTo(960f, 600f)
                close()
            },
            paint,
        )
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Attachment(file.toURI().toString(), name, "image/png")
    }

    private fun showConversation() {
        pending.value = listOf(landscape, portrait)
        compose.setContent {
            val density = Density(compose.activity.resources.displayMetrics.widthPixels / width.value, scale.value)
            CompositionLocalProvider(LocalDensity provides density) {
                HermesControlTheme(themePreference = theme.value) {
                    val bars =
                        if (theme.value == ThemePreference.DARK) {
                            SystemBarStyle.dark(Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        }
                    SideEffect { compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        HermesScaffold(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 844.dp),
                            title = { Text("Hermes") },
                            showShellMenu = false,
                            pinTopBar = true,
                        ) {
                            val keyboardBottom = WindowInsets.ime.getBottom(density)
                            SideEffect { imeBottom = keyboardBottom }
                            ConversationLayout(composer = {
                                ChatInputBar(
                                    inputFieldValue = draft.value,
                                    onInputChange = { draft.value = it },
                                    onSend = { sends++ },
                                    onMicTap = {},
                                    isListening = false,
                                    isAgentTyping = false,
                                    isConnected = true,
                                    commandCatalog = CommandCatalog(),
                                    pendingAttachments = pending.value,
                                    onRemoveAttachment = { index ->
                                        pending.value =
                                            pending.value.filterIndexed { i, _ -> i != index }
                                    },
                                    onPreviewAttachment = {
                                        previewed = it.uri
                                        selected.value = ImageViewerModel(it.uri, it.name, it.mimeType)
                                    },
                                )
                            }) {
                                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                    item {
                                        ChatBubble(
                                            ChatMessage(
                                                role = MessageRole.USER,
                                                content = "週末はこの場所へ行きたい",
                                                attachments = listOf(landscape),
                                            ),
                                            showTimestamp = false,
                                            onImageClick = { selected.value = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    selected.value?.let { ImageViewerDialog(it, onDismiss = { selected.value = null }) }
                }
            }
        }
        waitLoaded(3)
    }

    private fun waitLoaded(count: Int = 1) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("conversation_image_loaded", useUnmergedTree = true).fetchSemanticsNodes().size >=
                count
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "image-attachments").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun pendingPhotosPreviewAndRemoveIndependentlyWithoutLosingDraft() {
        showConversation()
        compose.onNodeWithText(landscape.name).assertDoesNotExist()
        compose.onAllNodesWithTag("attachment_preview")[0].performTouchInput { click(percentOffset(0.2f, 0.8f)) }
        compose.runOnIdle {
            assertEquals(landscape.uri, previewed)
            assertEquals(2, pending.value.size)
        }
        pressBack()
        compose
            .onAllNodesWithTag("attachment_remove")[0]
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(portrait), pending.value)
            assertEquals(0, sends)
        }
        compose.onNodeWithTag("chat_input").assertTextEquals("この写真について教えて")
    }

    @Test
    fun sentPhotosKeepProportionsAndSeparateCaptionFromMedia() {
        val attachment = mutableStateOf(portrait)
        val caption = mutableStateOf("")
        var opened: ImageViewerModel? = null
        compose.setContent {
            HermesControlTheme {
                ChatBubble(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = caption.value,
                        attachments = listOf(attachment.value),
                    ),
                    showTimestamp = false,
                    onImageClick = { opened = it },
                )
            }
        }
        waitLoaded()
        compose.onNodeWithTag("chat_user_text_surface").assertDoesNotExist()
        val portraitBounds = compose.onNodeWithTag("conversation_image").fetchSemanticsNode().boundsInRoot
        assertEquals(0.75f, portraitBounds.width / portraitBounds.height, 0.02f)
        screenshot("sent-portrait")
        compose.onNodeWithTag("conversation_image").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(portrait.uri, opened?.model)
            attachment.value = landscape
            caption.value = "この景色について教えて"
        }
        waitLoaded()
        val text = compose.onNodeWithTag("chat_user_text_surface").fetchSemanticsNode().boundsInRoot
        val photo = compose.onNodeWithTag("conversation_image").fetchSemanticsNode().boundsInRoot
        assertTrue("caption and photo must not share an enclosing bubble", text.bottom < photo.top)
        assertEquals(1.6f, photo.width / photo.height, 0.02f)
        screenshot("sent-landscape")
    }

    @Test
    fun bothThemesNarrowLargeTextAndKeyboardKeepPhotosAndInputAccessible() {
        showConversation()
        screenshot("dark")
        compose.runOnIdle { theme.value = ThemePreference.LIGHT }
        screenshot("light")
        compose.runOnIdle {
            width.value = 320f
            scale.value = 2f
        }
        compose.onAllNodesWithTag("attachment_preview")[0].assertIsDisplayed()
        compose.onNodeWithTag("send_button").assertIsDisplayed()
        screenshot("narrow-font-200")
        compose.onNodeWithTag("chat_input").performClick()
        compose.waitUntil(8_000) { imeBottom > 0 }
        // Prepare the dedicated emulator's IME if its Japanese layout chooser is shown on first use.
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service

        fun setupButtons(): List<AccessibilityNodeInfo> =
            automation.windows
                .flatMap { it.root?.findAccessibilityNodeInfosByText("スキップ").orEmpty() }
                .filter { it.packageName?.toString()?.contains("inputmethod") == true }
        setupButtons().forEach { button ->
            var target = button
            while (!target.isClickable && target.parent != null) target = target.parent
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        compose.waitUntil(8_000) { setupButtons().isEmpty() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("attachment_preview")[0].assertIsDisplayed()
        val photo = compose.onAllNodesWithTag("attachment_preview")[0].fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("chat_composer_surface").fetchSemanticsNode().boundsInRoot
        assertTrue(photo.bottom <= input.top)
        compose.onNodeWithTag("send_button").assertIsDisplayed()
        screenshot("keyboard")
    }

    @Test
    fun failureCanOpenAndRecoverWithoutClippingLargeText() {
        val model = mutableStateOf("file:///missing-fixture.png")
        var opens = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesControlTheme {
                    GifImageThumbnail(model.value, "画像", false, { opens++ })
                }
            }
        }
        compose.waitUntil(
            10_000,
        ) {
            compose
                .onAllNodesWithTag(
                    "conversation_image_error",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        val error =
            compose
                .onNodeWithTag(
                    "conversation_image_error",
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        val surface = compose.onNodeWithTag("conversation_image").fetchSemanticsNode().boundsInRoot
        assertTrue(error.top >= surface.top && error.bottom <= surface.bottom)
        screenshot("error-font-200")
        compose.onNodeWithTag("conversation_image").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, opens)
            model.value = landscape.uri
        }
        waitLoaded()
        compose.onNodeWithTag("conversation_image_error", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun assistantAttachmentsAndMarkdownUseTheSameImageSurface() {
        val markdown = mutableStateOf(false)
        val data = Base64.encodeToString(File(context.cacheDir, landscape.name).readBytes(), Base64.NO_WRAP)
        val markdownModel = "data:image/png;base64,$data"
        var opened: ImageViewerModel? = null
        compose.setContent {
            HermesControlTheme {
                FullBleedAgentMessage(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = if (markdown.value) "![風景]($markdownModel)" else "こちらの写真です。",
                        attachments = if (markdown.value) null else listOf(landscape),
                    ),
                    showTurnHeader = true,
                    isDarkTheme = true,
                    showTimestamp = false,
                    onImageClick = { opened = it },
                )
            }
        }
        waitLoaded()
        screenshot("assistant-image")
        compose.onNodeWithTag("conversation_image").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(landscape.uri, opened?.model)
            markdown.value = true
        }
        waitLoaded()
        val bounds = compose.onNodeWithTag("conversation_image").fetchSemanticsNode().boundsInRoot
        assertEquals(1.6f, bounds.width / bounds.height, 0.02f)
        compose.onNodeWithTag("conversation_image").performTouchInput { click() }
        compose.runOnIdle { assertEquals(markdownModel, opened?.model) }
        screenshot("markdown-image")
    }

    @Test
    fun gifPlaybackControlDoesNotOpenImage() {
        val file = File(context.cacheDir, "fixture-animated.gif")
        instrumentation.context.assets.open("image-fixtures/animated.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        var opens = 0
        compose.setContent {
            HermesControlTheme {
                GifImageThumbnail(
                    file.toURI().toString(),
                    "GIF",
                    true,
                    { opens++ },
                )
            }
        }
        waitLoaded()
        compose.onNodeWithContentDescription(context.getString(R.string.gif_pause)).assertIsDisplayed()
        compose.onNodeWithTag("conversation_gif_toggle").performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.gif_play)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opens) }
        screenshot("gif-paused")
        compose.onNodeWithTag("conversation_gif_toggle").performTouchInput { click() }
        compose.onNodeWithContentDescription(context.getString(R.string.gif_pause)).assertIsDisplayed()
        compose.onNodeWithTag("conversation_image").performTouchInput { click(percentOffset(0.8f, 0.2f)) }
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
