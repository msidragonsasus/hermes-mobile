package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedChatList
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test

class PromptNoticeListUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        messages: List<ChatMessage>,
        search: ChatSearchState = ChatSearchState(),
    ) {
        compose.setContent {
            HermesControlTheme {
                val scope = rememberCoroutineScope()
                val list = LazyListState()
                Box(Modifier.height(500.dp)) {
                    FullBleedChatList(
                        messages = messages,
                        streamingMessage = null,
                        searchState = search,
                        typingEffectEnabled = false,
                        typingEffectDelayMs = 30,
                        isLoading = false,
                        isLoadingOlder = false,
                        isDark = false,
                        listState = list,
                        scrollController = ChatScrollController(list, scope),
                        lastAnimatedMessageId = null,
                        onLastAnimatedMessageIdChange = {},
                        viewModel = mockk<ChatViewModel>(relaxed = true),
                    )
                }
            }
        }
    }

    @Test
    fun routineRowsAreAbsentButErrorsAndOutputsRemain() {
        render(
            listOf(
                ChatMessage(id = "c", role = MessageRole.SYSTEM, content = "Connected to Hermes"),
                ChatMessage(id = "r", role = MessageRole.SYSTEM, content = "Session resumed"),
                ChatMessage(id = "e", role = MessageRole.SYSTEM, content = "Connection failed: rejected"),
                ChatMessage(id = "u", role = MessageRole.USER, content = "質問"),
                ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "回答は表示したまま"),
            ),
        )
        compose.onNodeWithTag("chat_connection_events").assertDoesNotExist()
        compose.onNodeWithText("Connected to Hermes").assertDoesNotExist()
        compose.onNodeWithText("Connection failed: rejected").assertIsDisplayed()
        compose.onNodeWithText("回答は表示したまま").assertIsDisplayed()
    }

    @Test
    fun tailSearchForcesPromptOpenPastHiddenNotice() {
        val content = (1..40).joinToString("\n") { "Line $it content" } + "\nTAIL_MATCH"
        val search =
            ChatSearchState().apply {
                isActive = true
                query = "TAIL_MATCH"
                currentIndex = 0
                matchIndices = listOf(1)
                matchOffsets = listOf(content.indexOf(query))
                currentMatchId = "u"
                matchedIds = setOf("u")
            }
        render(
            listOf(
                ChatMessage(id = "c", role = MessageRole.SYSTEM, content = "Connected to Hermes"),
                ChatMessage(id = "u", role = MessageRole.USER, content = content),
                ChatMessage(id = "a", role = MessageRole.ASSISTANT, content = "reply"),
            ),
            search,
        )
        compose.onNodeWithTag("prompt_disclosure_toggle").assertDoesNotExist()
        compose.onNodeWithTag("prompt_disclosure_fade").assertDoesNotExist()
        compose.onNodeWithTag("chat_connection_events").assertDoesNotExist()
        compose.onNodeWithTag("prompt_body").assertIsDisplayed()
    }
}
