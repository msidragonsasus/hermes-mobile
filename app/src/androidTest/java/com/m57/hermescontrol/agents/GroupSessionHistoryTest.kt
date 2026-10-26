package com.m57.hermescontrol.agents

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.GroupChatKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.navigation.ShellNavigation
import com.m57.hermescontrol.navigation.ShellTab
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.bots.BotsUiState
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.GroupInfo
import com.m57.hermescontrol.ui.bots.group.EncryptedGroupConversationStore
import com.m57.hermescontrol.ui.bots.group.GroupChatScreen
import com.m57.hermescontrol.ui.bots.group.GroupChatUiState
import com.m57.hermescontrol.ui.bots.group.GroupChatViewModel
import com.m57.hermescontrol.ui.bots.group.GroupConversation
import com.m57.hermescontrol.ui.bots.group.GroupConversationArchive
import com.m57.hermescontrol.ui.bots.group.GroupNewConversationDialog
import com.m57.hermescontrol.ui.bots.group.groupConversationScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic conversations only; no server request or message submission. */
class GroupSessionHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var shell: ShellNavigation? = null

    @Test
    fun generatedNamesUpdateTheActiveHeaderWithoutRenamingAnotherSession() {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        val bots = mockk<BotsViewModel>(relaxed = true)
        val first = GroupConversation(id = "first", title = "first prompt")
        val second = GroupConversation(id = "second", title = "second prompt")
        val state =
            MutableStateFlow(
                GroupChatUiState(
                    groupName = "team",
                    isLoading = false,
                    conversationId = second.id,
                    conversations = listOf(first, second),
                ),
            )
        every { vm.uiState } returns state
        every { bots.uiState } returns MutableStateFlow(BotsUiState())
        compose.setContent {
            HermesControlTheme { GroupChatScreen("team", "second", onBack = {}, viewModel = vm, bots = bots) }
        }
        compose.onNodeWithText("second prompt").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    conversations = listOf(first.copy(title = "設計方針の比較", generatedTitle = "設計方針の比較"), second),
                )
        }
        compose.onNodeWithText("second prompt").assertIsDisplayed()
        compose.onNodeWithText("設計方針の比較").assertDoesNotExist()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    conversations = listOf(first, second.copy(title = "履歴の安定化", generatedTitle = "履歴の安定化")),
                )
        }
        compose.onNodeWithText("履歴の安定化").assertIsDisplayed()
        compose.onNodeWithText("second prompt").assertDoesNotExist()
        compose.waitForIdle()
        saveImage("group-auto-title.png")
    }

    @Test
    fun groupMessagesHaveSenderAvatarsAndSeparateContainers() {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        val bots = mockk<BotsViewModel>(relaxed = true)
        every { bots.uiState } returns MutableStateFlow(BotsUiState())
        every { vm.uiState } returns
            MutableStateFlow(
                GroupChatUiState(
                    groupName = "development",
                    displayName = "開発会議",
                    isLoading = false,
                    messages =
                        listOf(
                            com.m57.hermescontrol.ui.bots.group.GroupChatMessage(
                                "first",
                                "alice",
                                "Alice",
                                false,
                                text = "設計案を確認しました。",
                            ),
                            com.m57.hermescontrol.ui.bots.group.GroupChatMessage(
                                "second",
                                "bob",
                                "Bob",
                                false,
                                text = "実装の観点から意見があります。",
                            ),
                        ),
                ),
            )
        compose.setContent {
            HermesControlTheme { GroupChatScreen("", onBack = {}, viewModel = vm, bots = bots) }
        }
        compose.onNodeWithTag("group_message_first").assertIsDisplayed()
        compose.onNodeWithTag("group_message_second").assertIsDisplayed()
        compose.onNodeWithTag("group_sender_first").assertTextContains("Alice")
        compose.onNodeWithTag("group_sender_second").assertTextContains("Bob")
        compose.onNodeWithTag("bot_avatar_Alice").assertIsDisplayed()
        compose.onNodeWithTag("bot_avatar_Bob").assertIsDisplayed()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "group-server-ui").apply { mkdirs() }
        File(directory, "messages.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun groupTabDoesNotReplaceAnIncomingHistoryRouteWhenSharedStateChanges() {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        val bots = mockk<BotsViewModel>(relaxed = true)
        val state = MutableStateFlow(GroupChatUiState(groupName = "first", isLoading = false))
        every { vm.uiState } returns state
        every { bots.uiState } returns MutableStateFlow(BotsUiState())
        compose.setContent { HermesControlTheme { AgentGroupsScreen(bots = bots, conversation = vm) } }
        verify(exactly = 1) { vm.openConversation("first", null, null, any()) }
        compose.runOnIdle { state.value = state.value.copy(groupName = "second", conversationId = "saved-session") }
        compose.waitForIdle()
        verify(exactly = 1) { vm.openConversation(any(), any(), any(), any()) }
        verify(exactly = 0) { vm.leaveGroup(any(), any()) }
    }

    @Test
    fun selectedHistoryNeverReturnsToGroupSelectionWhileLoadingOrEmpty() {
        // Match MainActivity so opening the IME resizes content instead of panning history offscreen.
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        val bots = mockk<BotsViewModel>(relaxed = true)
        val state =
            MutableStateFlow(
                GroupChatUiState(isLoading = false),
            )
        every { vm.uiState } returns state
        every { bots.uiState } returns MutableStateFlow(BotsUiState())
        compose.setContent {
            HermesControlTheme {
                GroupChatScreen("development", "saved-session", onBack = {}, viewModel = vm, bots = bots)
            }
        }
        compose.onNodeWithTag("group_landing").assertDoesNotExist()
        compose.onNodeWithTag("loading_state").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    groupName = "development",
                    conversationId = "saved-session",
                    isReconciling = true,
                )
        }
        compose.onNodeWithTag("group_landing").assertDoesNotExist()
        compose.onNodeWithTag("loading_state").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    isReconciling = false,
                    errorMessage = "History unavailable",
                )
        }
        compose.onNodeWithTag("group_landing").assertDoesNotExist()
        compose.onNodeWithTag("group_chat_send_issue").assertTextContains("History unavailable")
        compose.runOnIdle { state.value = state.value.copy(errorMessage = null) }
        compose.onNodeWithTag("group_landing").assertDoesNotExist()
        compose.onNodeWithTag("group_conversation_empty").assertIsDisplayed()
        compose.onNodeWithTag("group_chat_input").performTextInput("saved draft")
        compose.waitForIdle()
        verify(exactly = 0) { vm.setGroup(any()) }
        verify(exactly = 0) { vm.newConversation(any()) }
        saveImage("group-selected-empty.png")
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    messages =
                        listOf(
                            com.m57.hermescontrol.ui.bots.group
                                .GroupChatMessage("reply", "bot", "Bot", false, text = "Loaded history"),
                        ),
                )
        }
        compose.waitForIdle()
        saveImage("group-loaded-history.png")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Loaded history").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText("Loaded history").assertIsDisplayed()
        compose.onNodeWithTag("group_landing").assertDoesNotExist()
    }

    @Test
    fun emptyGroupConversationOffersRealGroupsAndSelectsTheTappedGroup() {
        var selected = ""
        val groups =
            listOf(
                GroupInfo("meeting", listOf(ProfileInfo("alice")), displayName = "会議"),
                GroupInfo("development", listOf(ProfileInfo("bob")), displayName = "開発会議"),
            )
        compose.setContent {
            HermesControlTheme {
                com.m57.hermescontrol.ui.bots.group.GroupLanding(
                    groups = groups,
                    loading = false,
                    error = null,
                    onSelect = { selected = it.name },
                    onCreate = {},
                    onRefresh = {},
                )
            }
        }
        compose.onNodeWithText("会議").assertIsDisplayed()
        compose.onNodeWithText("開発会議").performClick()
        compose.runOnIdle { assertEquals("development", selected) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "group-server-ui").apply { mkdirs() }
        File(directory, "groups.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun cleanup() {
        shell?.let(NavigationController::detachShell)
        unmockkAll()
    }

    @Test
    fun historyTapNavigatesDirectlyToTheChosenSession() {
        val stack = NavBackStack<NavKey>(ChatScreen, AgentGroupsKey())
        val navigation =
            ShellNavigation(
                mutableStateOf(ShellTab.AGENT),
                ShellTab.primary.associateWith { if (it == ShellTab.AGENT) stack else NavBackStack<NavKey>(it.root) },
            )
        shell = navigation
        NavigationController.attachShell(navigation)
        val profiles = listOf(ProfileInfo(name = "synthetic-a"), ProfileInfo(name = "synthetic-b"))
        // Match the production group resolver's name-based legacy group representation.
        val name = "synthetic-a, synthetic-b"
        val first = GroupConversation(id = "first", title = "最初の長い投稿", generatedTitle = "設計を相談", createdAt = 1)
        val second = GroupConversation(id = "second", title = "実装の確認", createdAt = 2)
        EncryptedGroupConversationStore(compose.activity).write(
            "${groupConversationScope()}\u0000$name",
            GroupConversationArchive(activeId = second.id, conversations = listOf(first, second), displayName = "開発会議"),
        )
        val bots = mockk<BotsViewModel>(relaxed = true)
        // A sync room makes the group discoverable without modifying bot profiles or calling an API.
        val json = kotlinx.serialization.json.Json
        val snapshot =
            com.m57.hermescontrol.data.model.GroupChatSyncSnapshot(
                rooms =
                    mapOf(
                        "name:$name" to
                            com.m57.hermescontrol.data.model
                                .GroupChatRoomMeta(name = name),
                    ),
            )
        val metadata =
            ProfileInfo(
                name = "default",
                is_default = true,
                ui_meta =
                    mapOf(
                        "hermes-bots-groups" to
                            json.encodeToJsonElement(
                                com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
                                    .serializer(),
                                snapshot,
                            ),
                    ),
            )
        every { bots.uiState } returns MutableStateFlow(BotsUiState(profiles = profiles + metadata))
        val groupVm = mockk<GroupChatViewModel>(relaxed = true)
        every { groupVm.uiState } returns MutableStateFlow(GroupChatUiState(isLoading = false))
        compose.setContent { HermesControlTheme { AgentGroupsScreen(bots = bots, conversation = groupVm) } }
        compose.onNodeWithTag("menu_button", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation_new_button").assertIsDisplayed()
        compose.onNodeWithTag("group_history_search").assertDoesNotExist()
        compose.onNodeWithTag("agent_group_history").assertDoesNotExist()
        saveImage("group-conversation.png")
        compose.onNodeWithTag("menu_button", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("開発会議：設計を相談"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("開発会議：実装の確認").assertIsDisplayed()
        saveImage("group-history.png")
        compose.onNodeWithText("開発会議：設計を相談").performClick()
        compose.runOnIdle { assertEquals(GroupChatKey(name, conversationId = "first"), stack.last()) }
    }

    @Test
    fun choosingAGroupStartsANewConversationWithOneTap() {
        var chosen: GroupInfo? = null
        val group = GroupInfo("stable-key", listOf(ProfileInfo(name = "bot")), displayName = "開発会議")
        compose.setContent {
            HermesControlTheme {
                GroupNewConversationDialog(
                    listOf(group),
                    onCreateGroup = {},
                    onDismiss = {},
                    onSelect = { chosen = it },
                )
            }
        }
        compose.onNodeWithText("開発会議").assertIsDisplayed()
        saveImage("group-new.png")
        compose.onNodeWithTag("group_new_stable-key").performClick()
        compose.runOnIdle { assertEquals(group, chosen) }
    }

    @Test
    fun groupNameIsEditableWithoutSendingTheComposerDraft() {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(GroupChatUiState(groupName = "stable-key", isLoading = false))
        compose.setContent { HermesControlTheme { GroupChatScreen("stable-key", onBack = {}, viewModel = vm) } }
        compose.onNodeWithTag("group_chat_input").performTextInput("preserved draft")
        compose.onNodeWithTag("group_chat_settings_button").performClick()
        compose.onNodeWithTag("group_name_input").performTextReplacement("開発会議")
        compose.onNodeWithTag("group_name_input").assertTextContains("開発会議")
        androidx.test.espresso.Espresso
            .closeSoftKeyboard()
        compose.waitForIdle()
        saveImage("group-name.png")
        compose.onNodeWithTag("save_settings_button").assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.updateGroupLimits(6, 2, null, null, null, 1, "開発会議") }
        verify(exactly = 0) { vm.sendMessage(any()) }
    }

    @Test
    fun groupConversationUsesNormalControlsAndMessageRenderers() {
        val vm = mockk<GroupChatViewModel>(relaxed = true)
        every { vm.uiState } returns
            MutableStateFlow(
                GroupChatUiState(
                    groupName = "team",
                    displayName = "開発会議",
                    isLoading = false,
                    messages =
                        listOf(
                            com.m57.hermescontrol.ui.bots.group
                                .GroupChatMessage("user", "user", "You", true, text = "画面を揃えて"),
                            com.m57.hermescontrol.ui.bots.group.GroupChatMessage(
                                "reply",
                                "bot",
                                "開発Bot",
                                false,
                                text = "共通の会話画面です。",
                            ),
                        ),
                ),
            )
        compose.setContent { HermesControlTheme { GroupChatScreen("team", onBack = {}, viewModel = vm) } }
        compose.waitForIdle()
        saveImage("group-shared-chat.png")
        compose.onNodeWithTag("menu_button", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation_new_button").assertIsDisplayed()
        compose.onNodeWithTag("group_chat_settings_button").assertIsDisplayed()
        compose.onNodeWithTag("chat_attach_button").assertIsDisplayed()
        compose.onNodeWithTag("mic_button").assertIsDisplayed()
        compose.onNodeWithTag("chat_bubble_user").assertIsDisplayed()
        compose.onNodeWithTag("fullbleed_agent_message").assertIsDisplayed()
        compose.onNodeWithText("開発Bot").assertIsDisplayed()
        compose.onNodeWithTag("group_history_search").assertDoesNotExist()
        compose.onNodeWithTag("agent_group_history").assertDoesNotExist()
        saveImage("group-shared-chat.png")
    }

    private fun saveImage(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
