package com.m57.hermescontrol.ui.bots.group

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class GroupSendDiagnosticTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun snapshot(): GroupDiagnosticSnapshot {
        val message = GroupChatMessage("post", "user", "User", true, text = "PRIVATE_BODY")
        val turns =
            listOf("boss", "idea1").map { bot ->
                GroupTurnRecord(bot, "post", "reply-$bot", bot, "runtime-$bot", "stored-$bot", "PRIVATE_HASH")
            }
        return groupDiagnosticSnapshot(
            GroupConversation(messages = listOf(message), turns = turns),
            "post",
            "https://user:PRIVATE_AUTH@host.test/proxy/?token=PRIVATE_TOKEN",
        )!!
    }

    @Test
    fun selectingOneBotPreviewsOnlyItsMetadataAndCopiesOnlyAfterExplicitClick() {
        val snapshot = snapshot()
        val copied = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    GroupSendDiagnosticDialog(snapshot, onCopy = {
                        copied.add(it.diagnostic.copyText())
                        true
                    }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag("group_diagnostic_copy").assertDoesNotExist()
        compose.onNodeWithTag("group_diagnostic_choice_boss").performScrollTo().performClick()
        compose.onNodeWithTag("group_diagnostic_preview").assertTextContains("runtime-boss", substring = true)
        compose.runOnIdle { assertEquals(0, copied.size) }
        compose.onNodeWithTag("group_diagnostic_copy").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, copied.size)
            assertFalse(copied.single().contains("PRIVATE"))
            assertFalse(copied.single().contains("idea1"))
        }
    }

    @Test
    fun changingConversationScopeClearsTheSelectedBotAndCopyAction() {
        val state = mutableStateOf(snapshot())
        compose.setContent {
            MaterialTheme { GroupSendDiagnosticDialog(state.value, onCopy = { true }, onDismiss = {}) }
        }
        compose.onNodeWithTag("group_diagnostic_choice_boss").performScrollTo().performClick()
        compose.onNodeWithTag("group_diagnostic_copy").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(scopeTag = "different-conversation") }
        compose.onNodeWithTag("group_diagnostic_preview").assertDoesNotExist()
        compose.onNodeWithTag("group_diagnostic_copy").assertDoesNotExist()
    }

    @Test
    fun legacyPostWithoutTurnRecordsShowsUnrecoverableMessageAndNoCopyAction() {
        compose.setContent {
            MaterialTheme {
                GroupSendDiagnosticDialog(
                    GroupDiagnosticSnapshot("legacy", emptyList()),
                    onCopy = { true },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.group_diagnostics_missing)).assertIsDisplayed()
        compose.onNodeWithTag("group_diagnostic_copy").assertDoesNotExist()
    }
}
