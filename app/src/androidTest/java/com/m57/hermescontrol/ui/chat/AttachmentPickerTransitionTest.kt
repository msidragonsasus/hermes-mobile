package com.m57.hermescontrol.ui.chat

import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AttachmentPickerTransitionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun touchingAttachmentWithTheImeOpenKeepsTheComposerExpandedAndTimelinePinned() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var composerHeight = 0
        var keyboardVisible = false
        var listPosition = 0 to 0
        var recording = false
        val heights = mutableListOf<Int>()
        val imeStates = mutableListOf<Boolean>()
        val listPositions = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            HermesControlTheme {
                val ime = WindowInsets.ime
                val density = LocalDensity.current
                val listState = rememberLazyListState()
                var draft by remember { mutableStateOf(TextFieldValue("An unsent draft")) }
                LaunchedEffect(ime, density) {
                    snapshotFlow { ime.getBottom(density) > 0 }.collect { visible ->
                        keyboardVisible = visible
                        if (recording) imeStates += visible
                    }
                }
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect {
                        listPosition = it
                        if (recording) listPositions += it
                    }
                }
                ConversationLayout(
                    composer = {
                        Box(
                            Modifier.fillMaxWidth().onSizeChanged {
                                composerHeight = it.height
                                if (recording) heights += it.height
                            },
                        ) {
                            ChatInputBar(
                                inputFieldValue = draft,
                                onInputChange = { draft = it },
                                onSend = {},
                                onMicTap = {},
                                isListening = false,
                                isAgentTyping = false,
                                isConnected = true,
                                commandCatalog = CommandCatalog(),
                                collapseDraftWhenImeCloses = true,
                            )
                        }
                    },
                ) {
                    LazyColumn(modifier = Modifier.weight(1f), state = listState, reverseLayout = true) {
                        items(60) { Text("Message $it") }
                    }
                }
            }
        }
        val collapsedHeight = compose.runOnIdle { composerHeight }
        compose.onNodeWithTag("chat_input").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.runOnUiThread { keyboardVisible } }
        compose.onNodeWithTag("chat_composer_toolbar").assertIsDisplayed()
        val expandedHeight =
            compose.runOnIdle {
                recording = true
                heights += composerHeight
                imeStates += keyboardVisible
                listPositions += listPosition
                composerHeight
            }
        assertTrue(expandedHeight > collapsedHeight)

        try {
            compose.onNodeWithTag("chat_attach_button").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.runOnUiThread { !keyboardVisible } }
            compose.onNodeWithTag("chat_attachment_menu").assertIsDisplayed()
            compose.onNodeWithTag("chat_attachment_photo").assertIsDisplayed()
            compose.onNodeWithTag("chat_attachment_file").assertIsDisplayed()
            compose.onNodeWithTag("chat_composer_toolbar").assertIsDisplayed()
            compose.onNodeWithText("Message 0").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("Composer height during IME hide and picker opening", listOf(expandedHeight), heights)
                assertEquals("One visible to hidden IME transition", listOf(true, false), imeStates)
                assertEquals("The reverse list stays at its latest item", listOf(0 to 0), listPositions)
            }
        } finally {
            compose.runOnUiThread {
                recording = false
                Log.i(
                    "AttachmentPickerTest",
                    "Composer heights=$heights, IME visible=$imeStates, timeline index/offset=$listPositions",
                )
            }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("chat_attachment_menu").assertDoesNotExist()
        compose.onNodeWithTag("chat_composer_toolbar").assertDoesNotExist()
    }
}
