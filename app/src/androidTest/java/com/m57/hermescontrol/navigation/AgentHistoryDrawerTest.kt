package com.m57.hermescontrol.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.components.ComposerOptionsRow
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only: never contacts a server or sends a chat. */
class AgentHistoryDrawerTest {
    @Test
    fun rawTransportTitlesAreReadableAndStillSelectOriginalSessionId() {
        var selected: String? = null
        compose.setContent {
            HermesControlTheme {
                ConversationMenuSheet(
                    null,
                    {},
                    {},
                    {},
                    {},
                    historyState =
                        SessionsUiState(
                            sessions =
                                listOf(
                                    SessionInfo("slack", "[Slack message from U123456789 in C123456789] 修正を続けて"),
                                    SessionInfo("file", "/tmp/private/context.txt", preview = "履歴を整理して"),
                                ),
                        ),
                    currentSessionId = "slack",
                    onSelectSession = { selected = it },
                )
            }
        }
        compose.onNodeWithText("修正を続けて").assertIsDisplayed()
        compose.onNodeWithText("履歴を整理して").assertIsDisplayed()
        compose.onNodeWithTag("conversation_session_slack").assertIsSelected()
        compose.onNodeWithTag("conversation_session_file").performClick()
        compose.onNodeWithText("/tmp/private/context.txt").assertDoesNotExist()
        compose.onNodeWithTag("conversation_model").assertDoesNotExist()
        compose.onNodeWithTag("conversation_history").assertDoesNotExist()
        compose.runOnIdle { assertEquals("file", selected) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun historyIsDirectSelectableAndCurrentSessionIsHighlighted() {
        var selected: String? = null
        compose.setContent {
            HermesControlTheme {
                ConversationMenuSheet(
                    currentModel = "test-model",
                    onHistory = {},
                    onNewConversation = {},
                    onModel = {},
                    onDismiss = {},
                    historyState =
                        SessionsUiState(
                            sessions =
                                listOf(
                                    SessionInfo("one", "First"),
                                    SessionInfo("two", "Second"),
                                    SessionInfo("hidden", "Hidden", hidden = true),
                                ),
                        ),
                    currentSessionId = "two",
                    onSelectSession = { selected = it },
                )
            }
        }
        compose.onNodeWithTag("conversation_session_one").assertIsDisplayed().performClick()
        compose.onNodeWithTag("conversation_session_two").assertIsSelected()
        compose.onNodeWithTag("conversation_session_hidden").assertDoesNotExist()
        compose.runOnIdle { assertEquals("one", selected) }
    }

    @Test
    fun searchRoutesSelectedResultBySessionIdAndDeduplicatesMessages() {
        var selected: String? = null
        compose.setContent {
            HermesControlTheme {
                var state by remember { mutableStateOf(SessionsUiState()) }
                ConversationMenuSheet(
                    null,
                    {},
                    {},
                    {},
                    {},
                    historyState = state,
                    onSearchQuery = {
                        state =
                            state.copy(
                                searchQuery = it,
                                searchResults =
                                    listOf(
                                        SessionSearchResult("match", "One"),
                                        SessionSearchResult("match", "Two"),
                                    ),
                            )
                    },
                    onSelectSession = { selected = it },
                )
            }
        }
        compose.onNodeWithTag("conversation_search").performTextInput("query")
        compose.onNodeWithTag("conversation_session_match").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("match", selected) }
    }

    @Test
    fun failedHistoryCanRetryWithoutBeingPresentedAsEmpty() {
        var retries = 0
        compose.setContent {
            HermesControlTheme {
                ConversationMenuSheet(
                    null,
                    {},
                    {},
                    {},
                    {},
                    historyState = SessionsUiState(errorMessage = "offline"),
                    onRefreshHistory = { retries++ },
                )
            }
        }
        compose.onNodeWithTag("conversation_history_error").assertIsDisplayed()
        compose.onNodeWithTag("conversation_history_empty").assertDoesNotExist()
        compose.onNodeWithTag("conversation_history_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun loadingDoesNotPretendTheHistoryIsEmpty() {
        compose.setContent {
            HermesControlTheme {
                ConversationMenuSheet(null, {}, {}, {}, {}, historyState = SessionsUiState(isLoading = true))
            }
        }
        compose.onNodeWithTag("conversation_history_loading").assertIsDisplayed()
        compose.onNodeWithTag("conversation_history_empty").assertDoesNotExist()
    }

    @Test
    fun startingANewChatDoesNotSelectOldHistoryOrChangeModel() {
        var newChat = 0
        var selected = 0
        var model = 0
        compose.setContent {
            HermesControlTheme {
                ConversationMenuSheet(null, {}, { newChat++ }, { model++ }, {}, onSelectSession = { selected++ })
            }
        }
        compose.onNodeWithTag("conversation_new").performClick()
        compose.runOnIdle { assertEquals(listOf(1, 0, 0), listOf(newChat, selected, model)) }
    }

    @Test
    fun closedDrawerDoesNotInterceptChat() {
        compose.setContent {
            HermesControlTheme { ConversationMenuSheet(null, {}, {}, {}, {}, visible = false) }
        }
        compose.onNodeWithTag("conversation_menu_scrim").assertDoesNotExist()
        compose.onNodeWithTag("conversation_session_one").assertDoesNotExist()
    }

    @Test
    fun conversationModelAndReasoningControlsInvokeOnlyTheirOwnActions() {
        var model = 0
        var reasoning = 0
        compose.setContent {
            HermesControlTheme {
                ComposerOptionsRow(
                    currentSessionModel = "test-model",
                    reasoningLevel = "high",
                    onModelTap = { model++ },
                    onReasoningTap = { reasoning++ },
                )
            }
        }
        compose.onNodeWithTag("model_chip").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 0), listOf(model, reasoning)) }
        compose.onNodeWithTag("reasoning_chip").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1), listOf(model, reasoning)) }
    }
}
