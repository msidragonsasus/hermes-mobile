package com.m57.hermescontrol.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.theme.HermesControlTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test

class RichAnswerHistoryRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restoredServerAndCachedUserBubblesShowOnlyTheTypedMessage() {
        val userText = "許可は押してみた"
        val wire =
            RichAnswerPrompt.append(userText).substringBefore("\nVisual answer style:") +
                "\nVisual answer style: previous client instructions.\n</hermes-mobile-display-context>"
        val restored =
            mapServerMessages(
                "fixture",
                listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive(wire))),
                0,
                true,
                emptyList(),
            ).single()
        val cached =
            ChatMessageEntity(
                id = "cached",
                sessionId = "fixture",
                role = "USER",
                content = wire,
                timestamp = 1234L,
            ).toUiModel()

        compose.setContent {
            HermesControlTheme {
                Column {
                    ChatBubble(restored)
                    ChatBubble(cached)
                }
            }
        }

        compose.onAllNodesWithText(userText).assertCountEquals(2)
        compose.onAllNodesWithText(userText)[0].assertIsDisplayed()
        compose.onAllNodesWithText(userText)[1].assertIsDisplayed()
        compose.onNodeWithText("<hermes-mobile-display-context>", substring = true).assertDoesNotExist()
        compose.onNodeWithText("The user is reading this conversation", substring = true).assertDoesNotExist()
    }
}
