package com.m57.hermescontrol.ui.bots.group

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GroupConversationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedArchiveSurvivesRecreationWithoutTruncatingUnconfirmedHistory() {
        val key = "synthetic-test/${UUID.randomUUID()}"
        val turn = GroupTurnRecord("turn", "user", "reply", "synthetic", "runtime", "stored", "hash")
        val conversation =
            GroupConversation(
                messages =
                    (1..65).map {
                        GroupChatMessage(
                            "$it",
                            "user",
                            "You",
                            true,
                            text = "synthetic-private-$it",
                            deliveries =
                                mapOf("synthetic" to GroupDelivery.UNKNOWN),
                        )
                    },
                turns = listOf(turn),
            )
        val archive = GroupConversationArchive(activeId = conversation.id, conversations = listOf(conversation))
        EncryptedGroupConversationStore(context).write(key, archive)
        assertEquals(archive, EncryptedGroupConversationStore(context).read(key))
        val raw = context.getSharedPreferences("hermes_group_conversations", 0).all
        assertTrue(raw.values.none { it.toString().contains("synthetic-private-") })
        assertTrue(raw.keys.none { it.contains(key) })
    }

    @Test
    fun unsupportedArchiveIsRetainedAndReadFailsRecoverably() {
        val key = "synthetic-unsupported/${UUID.randomUUID()}"
        val conversation = GroupConversation()
        val archive =
            GroupConversationArchive(version = 99, activeId = conversation.id, conversations = listOf(conversation))
        val store = EncryptedGroupConversationStore(context)
        store.write(key, archive)
        val before = context.getSharedPreferences("hermes_group_conversations", 0).all.toMap()
        assertTrue(runCatching { EncryptedGroupConversationStore(context).read(key) }.isFailure)
        assertEquals(before, context.getSharedPreferences("hermes_group_conversations", 0).all)
    }

    @Test
    fun pickerSelectsOriginalConversationAndCreatesNewAtLargeFont() {
        val original = GroupConversation(title = "Original synthetic conversation")
        val current = GroupConversation(title = "Current synthetic conversation")
        var selected: String? = null
        var created = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    GroupConversationPicker(
                        state =
                            GroupChatUiState(
                                conversationId = current.id,
                                conversations = listOf(original, current),
                            ),
                        onSelect = { selected = it },
                        onNew = { created = true },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("group_new_conversation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(created) }
        compose
            .onNodeWithTag(
                "group_conversations_list",
            ).performScrollToNode(hasTestTag("group_conversation_${original.id}"))
        compose.onNodeWithTag("group_conversation_${original.id}").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(original.id, selected) }
    }
}
