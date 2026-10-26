package com.m57.hermescontrol.ui.bots.group

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Synthetic state only. These tests cannot send a prompt or open a connection. */
class GroupDeliveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun cleanup() {
        unmockkAll()
    }

    private fun show(state: GroupChatUiState): Pair<GroupChatViewModel, MutableStateFlow<GroupChatUiState>> {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        val flow = MutableStateFlow(state)
        every { vm.uiState } returns flow
        compose.setContent { HermesControlTheme { GroupChatScreen("Four", onBack = {}, viewModel = vm) } }
        return vm to flow
    }

    @Test fun partialFailureShowsPerMemberStatusAndReason() {
        show(
            GroupChatUiState(
                groupName = "Four",
                isLoading = false,
                sendIssue = GroupSendIssue.MEMBER_FAILED,
                members = listOf(ProfileInfo(name = "boss"), ProfileInfo(name = "idea1")),
                messages =
                    listOf(
                        GroupChatMessage(
                            id = "synthetic-input",
                            senderName = "user",
                            senderDisplayName = "You",
                            isUser = true,
                            text = "Synthetic input",
                            deliveries =
                                mapOf(
                                    "boss" to GroupDelivery.COMPLETE,
                                    "idea1" to GroupDelivery.NOT_SENT,
                                ),
                        ),
                    ),
            ),
        )
        compose
            .onNodeWithTag("group_chat_send_issue")
            .assertIsDisplayed()
            .assertTextContains(compose.activity.getString(R.string.group_send_member_failed))
        compose
            .onNodeWithTag("group_delivery_synthetic-input", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains(
                "boss: " + compose.activity.getString(R.string.group_delivery_complete),
                substring = true,
            ).assertTextContains(
                "idea1: " + compose.activity.getString(R.string.group_delivery_not_sent),
                substring = true,
            )
    }

    @Test fun unknownStateShowsUnconfirmedLabelWithoutRetryAction() {
        val (vm, _) =
            show(
                GroupChatUiState(
                    groupName = "Four",
                    isLoading = false,
                    sendIssue = GroupSendIssue.UNKNOWN,
                    messages =
                        listOf(
                            GroupChatMessage(
                                id = "synthetic-input",
                                senderName = "user",
                                senderDisplayName = "You",
                                isUser = true,
                                text = "Synthetic input",
                                deliveries = mapOf("boss" to GroupDelivery.UNKNOWN),
                            ),
                        ),
                ),
            )
        compose
            .onNodeWithTag("group_chat_send_issue")
            .assertIsDisplayed()
            .assertTextContains(compose.activity.getString(R.string.group_send_unknown))
        compose
            .onNodeWithTag("group_delivery_synthetic-input", useUnmergedTree = true)
            .assertTextContains("boss: " + compose.activity.getString(R.string.group_delivery_unknown))
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test fun malformedLegacyRosterDisplaysExactlyFourNamedStatuses() {
        val room =
            kotlinx.serialization.json.Json.decodeFromString<com.m57.hermescontrol.data.model.GroupChatRoomMeta>(
                """{"members":["boss","idea1","idea2","deny",""]}""",
            )
        val names = room.memberNames
        val (vm, _) =
            show(
                GroupChatUiState(
                    groupName = "Four",
                    isLoading = false,
                    members = names.map { ProfileInfo(name = it) },
                    messages =
                        listOf(
                            GroupChatMessage(
                                id = "legacy-input",
                                senderName = "user",
                                senderDisplayName = "You",
                                isUser = true,
                                text = "Synthetic input",
                                deliveries = names.associateWith { GroupDelivery.COMPLETE },
                            ),
                        ),
                ),
            )
        compose.onNodeWithTag("group_delivery_legacy-input", useUnmergedTree = true).assertTextEquals(
            names.joinToString(" · ") { "$it: " + compose.activity.getString(R.string.group_delivery_complete) },
        )
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test fun membershipAndProfileFailuresHaveDistinctVisibleReasons() {
        val (vm, flow) =
            show(
                GroupChatUiState(groupName = "Four", isLoading = false, sendIssue = GroupSendIssue.INVALID_MEMBERS),
            )
        for ((issue, resource) in listOf(
            GroupSendIssue.INVALID_MEMBERS to R.string.group_send_invalid_members,
            GroupSendIssue.MEMBERS_MISSING to R.string.group_send_members_missing,
            GroupSendIssue.PROFILE_LIST_UNAVAILABLE to R.string.group_send_profiles_unavailable,
            GroupSendIssue.UNAVAILABLE to R.string.group_send_unavailable,
        )) {
            compose.runOnIdle { flow.value = flow.value.copy(sendIssue = issue) }
            compose
                .onNodeWithTag(
                    "group_chat_send_issue",
                ).assertIsDisplayed()
                .assertTextContains(compose.activity.getString(resource))
        }
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test fun preparingTurnCanBeStoppedBeforeActiveSpeakerAndKeepsDraft() {
        val (vm, flow) = show(GroupChatUiState(groupName = "Four", isLoading = false, isSending = true))
        val stopButtons = compose.onAllNodesWithTag("group_chat_stop_button")
        stopButtons.assertCountEquals(1)
        stopButtons[0].assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.stopGeneration() }
        compose.onNodeWithTag("group_chat_input").performTextInput("fresh synthetic draft")
        verify(exactly = 0) { vm.sendMessage(any()) }
        compose.runOnIdle { flow.value = flow.value.copy(isSending = false) }
        compose.onNodeWithTag("group_chat_input").assertTextContains("fresh synthetic draft")
    }

    @Test fun settingsCanReorderMembersAndChooseExistingSummaryWithoutSending() {
        var savedOrder: List<String>? = null
        var savedSummary: String? = null
        compose.setContent {
            HermesControlTheme {
                GroupChatSettingsDialog(
                    currentMaxMessages = 6,
                    currentMaxPasses = 2,
                    currentSystemPrompt = null,
                    members = listOf(ProfileInfo(name = "boss"), ProfileInfo(name = "deny")),
                    currentSpeakingOrder = null,
                    currentSummaryMember = null,
                    onDismiss = {},
                    onSave = { _, _, _, order, summary, _ ->
                        savedOrder = order
                        savedSummary = summary
                    },
                )
            }
        }
        compose.onNodeWithTag("group_control_order").performClick()
        compose.onNodeWithTag("group_order_up_deny").performScrollTo().performClick()
        compose.onNodeWithTag("group_order_member_deny").assertTextEquals("1. deny")
        compose.onNodeWithTag("group_summary_boss").performScrollTo().performClick()
        compose.onNodeWithTag("save_settings_button").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(listOf("deny", "boss"), savedOrder)
            org.junit.Assert.assertEquals("boss", savedSummary)
        }
    }

    @Test fun settingsCanDisableControlAndSummaryTogether() {
        var saved = false
        compose.setContent {
            HermesControlTheme {
                GroupChatSettingsDialog(
                    currentMaxMessages = 6,
                    currentMaxPasses = 2,
                    currentSystemPrompt = null,
                    members = listOf(ProfileInfo(name = "boss"), ProfileInfo(name = "deny")),
                    currentSpeakingOrder = listOf("deny", "boss"),
                    currentSummaryMember = "boss",
                    onDismiss = {},
                    onSave = { _, _, _, order, summary, _ ->
                        org.junit.Assert.assertNull(order)
                        org.junit.Assert.assertNull(summary)
                        saved = true
                    },
                )
            }
        }
        compose.onNodeWithTag("group_order_member_deny").performScrollTo().assertTextEquals("1. deny")
        compose.onNodeWithTag("group_control_order").performScrollTo().performClick()
        compose.onNodeWithTag("save_settings_button").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(saved) }
    }

    @Test fun pendingSettingsSaveKeepsComposerDraftWithoutSending() {
        val (vm, flow) = show(GroupChatUiState(groupName = "Four", isLoading = false, isSavingGroupSettings = true))
        compose.onNodeWithTag("group_chat_input").performTextInput("settings-save draft")
        compose.onNodeWithTag("group_chat_input").performImeAction()
        compose.onNodeWithTag("group_chat_input").assertTextContains("settings-save draft")
        compose.onNodeWithTag("group_chat_settings_button").assertIsNotEnabled()
        verify(exactly = 0) { vm.sendMessage(any()) }
        compose.runOnIdle { flow.value = flow.value.copy(isSavingGroupSettings = false) }
        compose.onNodeWithTag("group_chat_input").assertTextContains("settings-save draft")
    }

    @Test fun settingsCanChooseThreeRoundsAndKeepBoundedHandoffControls() {
        var savedRounds: Int? = null
        compose.setContent {
            HermesControlTheme {
                GroupChatSettingsDialog(
                    currentMaxMessages = 15,
                    currentMaxPasses = 2,
                    currentSystemPrompt = null,
                    members = listOf(ProfileInfo(name = "boss"), ProfileInfo(name = "deny")),
                    currentSpeakingOrder = listOf("boss", "deny"),
                    currentSummaryMember = "boss",
                    currentDiscussionRounds = 1,
                    onDismiss = {},
                    onSave = { messages, handoffs, _, order, summary, rounds ->
                        org.junit.Assert.assertEquals(15, messages)
                        org.junit.Assert.assertEquals(2, handoffs)
                        org.junit.Assert.assertEquals(listOf("boss", "deny"), order)
                        org.junit.Assert.assertEquals("boss", summary)
                        savedRounds = rounds
                    },
                )
            }
        }
        compose
            .onNodeWithTag(
                "discussion_rounds_slider",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) {
                it(3f)
            }
        compose.onNodeWithTag("max_handoffs_slider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save_settings_button").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(3, savedRounds) }
    }

    @Test fun groupScreenSavesRoundsThroughViewModelWithoutSendingDraft() {
        val (vm, _) =
            show(
                GroupChatUiState(
                    groupName = "Four",
                    isLoading = false,
                    members = listOf(ProfileInfo(name = "boss")),
                    speakingOrder = listOf("boss"),
                    discussionRounds = 2,
                ),
            )
        compose.onNodeWithTag("group_chat_input").performTextInput("preserved draft")
        compose.onNodeWithTag("group_chat_settings_button").performClick()
        compose
            .onNodeWithTag(
                "discussion_rounds_slider",
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) {
                it(3f)
            }
        compose.onNodeWithTag("save_settings_button").performClick()
        verify(exactly = 1) { vm.updateGroupLimits(6, 2, null, listOf("boss"), null, 3, "Four") }
        verify(exactly = 0) { vm.sendMessage(any()) }
        compose.onNodeWithTag("group_chat_input").assertTextContains("preserved draft")
    }
}
