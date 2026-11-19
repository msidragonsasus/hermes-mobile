package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SpecialistCapabilitiesResponse
import com.m57.hermescontrol.data.model.SpecialistPendingTurn
import com.m57.hermescontrol.data.model.SpecialistSetupDraft
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpecialistChatUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun markdownReplyWrapsAndCurrentDraftCanBeConfirmedAfterTurnFailure() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val draft = SpecialistSetupDraft("draft", description = "企業研究を担当")
        val pending = SpecialistPendingTurn("turn-question", "xurlで本当に取れる？", Double.MAX_VALUE)
        var chat by mutableStateOf(
            SpecialistChatState(
                sessionId = "bot-chat",
                capabilities = SpecialistCapabilitiesResponse(setup_conversation = true, setup_apply = true),
                snapshot = SpecialistSetupSnapshot("bot", draft = draft, pending_turn = pending),
            ),
        )
        var useDarkTheme by mutableStateOf(false)
        rule.setContent {
            HermesControlTheme(themePreference = if (useDarkTheme) ThemePreference.DARK else ThemePreference.LIGHT) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        FullBleedAgentMessage(
                            message =
                                ChatMessage(
                                    id = "specialist:reply",
                                    role = MessageRole.ASSISTANT,
                                    content = "投稿検索に対応しています。\n\n**X APIの認証**と検索権限が必要です。",
                                    timestamp = 0L,
                                ),
                            showTurnHeader = false,
                            isDarkTheme = useDarkTheme,
                            showFooter = false,
                            allowInteractiveContent = false,
                        )
                        SpecialistReviewCard(chat, canUseSession = true, onConfirm = { _, _ -> })
                        SpecialistChatActions(chat, true, true, {}, {}, {}, {}, {})
                    }
                }
            }
        }

        rule.onNodeWithText("投稿検索に対応しています。", substring = true).assertIsDisplayed()
        rule.onNodeWithText("X APIの認証", substring = true).assertIsDisplayed()
        val confirm = rule.onNodeWithText(resources.getString(R.string.specialist_chat_confirm))
        confirm.performScrollTo().assertIsNotEnabled()
        rule
            .onNodeWithText(
                resources.getString(R.string.specialist_chat_thinking),
            ).performScrollTo()
            .assertIsDisplayed()

        rule.runOnIdle {
            useDarkTheme = true
            chat =
                chat.copy(
                    snapshot = chat.snapshot?.copy(pending_turn = pending.copy(expires_at = 0.0)),
                    error = "invalid_specialist_model_output",
                )
        }
        confirm.performScrollTo().assertIsEnabled()
        rule.onNodeWithText(resources.getString(R.string.action_retry)).performScrollTo().assertIsEnabled()
    }
}
