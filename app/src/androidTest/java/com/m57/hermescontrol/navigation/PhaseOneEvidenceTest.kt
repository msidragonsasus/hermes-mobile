package com.m57.hermescontrol.navigation

import android.Manifest
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.MainNavigation
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.ConnectionScope
import com.m57.hermescontrol.domain.hermes.ExecutionState
import com.m57.hermescontrol.domain.hermes.ObservationKind
import com.m57.hermescontrol.domain.hermes.ObservedRun
import com.m57.hermescontrol.domain.hermes.RequestRef
import com.m57.hermescontrol.domain.hermes.RunSnapshot
import com.m57.hermescontrol.domain.hermes.SessionRef
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.apps.MiniAppsScreen
import com.m57.hermescontrol.ui.attention.AttentionDetailContent
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatScreen
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.common.ActionProgressController
import com.m57.hermescontrol.ui.common.AdvancedScreenContent
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.ObservedActivityContent
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Actual device captures of production composables; inputs are explicitly synthetic, never a live server. */
class PhaseOneEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = ConnectionScope("synthetic", "connection", "profile", "process", 1)
    private val request = RequestRef(scope, SessionRef("runtime-example", "saved-example"), "approval-example")

    @Before
    fun allowSyntheticTestNotifications() {
        instrumentation.runOnMainSync {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @Composable
    private fun Japanese(
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.JAPANESE)) }
        // Keep the Activity in the base-context chain for result launchers and back dispatch.
        val context = ContextThemeWrapper(LocalContext.current, 0).apply { applyOverrideConfiguration(configuration) }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides Density(density.density, fontScale),
        ) {
            HermesControlTheme(themePreference = ThemePreference.LIGHT, content = content)
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // The system IME/surface compositor is outside Compose's test clock.
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory =
            (output?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "phase1-evidence"))
                .apply { mkdirs() }
        File(
            directory,
            "$name.png",
        ).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test
    fun japaneseTabsRenderOfflineObservationsAndRetainExpandedManifest() {
        lateinit var shell: ShellNavigation
        val snapshot =
            RunSnapshot(
                runs =
                    listOf(
                        ObservedRun(
                            scope,
                            request.session,
                            ExecutionState.RUNNING,
                            ObservationKind.TOOL_STARTED,
                            1,
                            uncertain = true,
                        ),
                    ),
            )
        compose.setContent {
            Japanese {
                shell = rememberShellNavigation(true)!!
                NavigationController.attachShell(shell)
                DisposableEffect(shell) { onDispose { NavigationController.detachShell(shell) } }
                val holder = rememberSaveableStateHolder()
                CompositionLocalProvider(LocalShellMenu provides { ShellMenu() }) {
                    Scaffold(contentWindowInsets = WindowInsets.navigationBars, bottomBar = {
                        ShellBottomBar(shell.selectedTab)
                    }) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                            holder.SaveableStateProvider(shell.selectedTab.name) {
                                when (shell.selectedTab) {
                                    ShellTab.APPS -> MiniAppsScreen()
                                    else -> ObservedActivityContent(snapshot, shell.selectedTab == ShellTab.TODAY)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("shell_tab_today").assertIsSelected()
        capture("01-today-ja-offline")
        compose.onNodeWithTag("shell_tab_activity").performClick().assertIsSelected()
        capture("02-activity-ja-uncertain")
        compose.onNodeWithTag("shell_tab_apps").performClick().assertIsSelected()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("miniapps_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("miniapps_list").performScrollToNode(hasTestTag("miniapp_details_career"))
        compose.onNodeWithTag("miniapp_details_career").performClick()
        capture("03-apps-ja-example")
        compose.onNodeWithTag("shell_tab_today").performClick()
        compose.onNodeWithTag("shell_tab_apps").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("miniapps_list").fetchSemanticsNodes().isNotEmpty() }
        val config =
            Configuration(
                instrumentation.targetContext.resources.configuration,
            ).apply { setLocale(Locale.JAPANESE) }
        val message =
            instrumentation.targetContext
                .createConfigurationContext(
                    config,
                ).getString(R.string.miniapps_permissions_not_granted)
        compose.onNodeWithText(message).assertExists()
        compose.runOnIdle { assertEquals(listOf(AppsScreen), shell.backStack.toList()) }
    }

    @Test
    fun largeJapaneseApprovalRetainsDisabledControlsAfterSingleDecision() {
        val item = mutableStateOf(AttentionItem(request, AttentionKind.APPROVAL, "合成テスト：確認済みの文書を公開する"))
        val decisions = mutableListOf<String>()
        compose.setContent {
            Japanese(2f) {
                AttentionDetailContent(request, item.value, true, {
                    decisions.add(it)
                    item.value = item.value.copy(status = AttentionStatus.SUBMITTING)
                }, {}, {})
            }
        }
        compose.onNodeWithTag("attention_once").assertIsDisplayed()
        capture("04-approval-ja-font200")
        compose.onNodeWithTag("attention_once").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("attention_deny").assertIsNotEnabled()
        capture("05-approval-ja-awaiting-ack")
        compose.runOnIdle { assertEquals(listOf("once"), decisions) }
    }

    @Test
    fun actualMainNavigationRetainsChatViewModelAcrossTabsAndClearsShellOnLogout() {
        val jobs = CoroutineScope(Dispatchers.Main)
        val token = MutableStateFlow<String?>("synthetic-ui-only")
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.getCloseable<AutoCloseable>(any()) } returns null
        every { viewModel.uiState } returns MutableStateFlow(ChatUiState(connectionStatus = ConnectionStatus.CONNECTED))
        every { viewModel.streamingState } returns MutableStateFlow(StreamingState())
        every { viewModel.actionProgress } returns ActionProgressController(jobs)
        val chatFactory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(viewModel)!!
            }
        mockkObject(AuthManager)
        every { AuthManager.tokenFlow } returns token
        try {
            compose.setContent {
                // Seed only Chat in the real Activity store; keep Application/saved-state extras for other ViewModels.
                ViewModelProvider(
                    checkNotNull(LocalViewModelStoreOwner.current),
                    chatFactory,
                )[ChatViewModel::class.java]
                Japanese { MainNavigation() }
            }
            compose.onNodeWithTag("shell_tab_today").assertIsSelected()
            compose.onNodeWithTag("shell_tab_chat").performClick().assertIsSelected()
            compose.onNodeWithTag("chat_input").performClick().performTextInput("タブを戻っても残る下書き")
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("shell_tab_chat").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("chat_input").assertIsDisplayed()
            capture("11-main-shell-ja-ime")
            androidx.test.espresso.Espresso
                .closeSoftKeyboard()
            compose.onNodeWithTag("shell_tab_activity").performClick().assertIsSelected()
            compose.onNodeWithTag("shell_tab_apps").performClick().assertIsSelected()
            compose.onNodeWithTag("shell_tab_chat").performClick().assertIsSelected()
            compose.onNodeWithText("タブを戻っても残る下書き").assertIsDisplayed()
            capture("08-main-shell-ja-chat")
            compose.onNodeWithTag("shell_menu").performClick()
            capture("09-main-shell-ja-menu")
            compose.onNodeWithText("Advanced").performClick()
            compose.onNodeWithText("Advanced").assertIsDisplayed()
            capture("19-main-shell-ja-advanced")
            androidx.test.espresso.Espresso
                .pressBack()
            compose.onNodeWithTag("shell_tab_chat").assertIsSelected()
            compose.onNodeWithText("タブを戻っても残る下書き").assertIsDisplayed()
            compose.runOnIdle { token.value = null }
            compose.onNodeWithTag("shell_tab_today").assertDoesNotExist()
            capture("10-main-shell-ja-logged-out")
            compose.runOnIdle { token.value = "synthetic-ui-only" }
            compose.onNodeWithTag("shell_tab_today").assertIsSelected()
            verify(exactly = 0) { viewModel.createNewSession(any()) }
        } finally {
            unmockkObject(AuthManager)
            jobs.cancel()
        }
    }

    @Test
    fun japaneseChatAcceptsInputWhileRenderingStreamingUpdates() {
        val jobs = CoroutineScope(Dispatchers.Main)
        val streaming = MutableStateFlow(StreamingState())
        val viewModel = mockk<ChatViewModel>(relaxed = true)
        every { viewModel.uiState } returns
            MutableStateFlow(
                ChatUiState(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    currentSessionId = "synthetic-session",
                    messages = listOf(ChatMessage(role = MessageRole.USER, content = "予定の確認をお願いします")),
                    typingEffectEnabled = false,
                ),
            )
        every { viewModel.streamingState } returns streaming
        every { viewModel.actionProgress } returns ActionProgressController(jobs)
        try {
            compose.setContent { Japanese { ChatScreen(onOpenDrawer = {}, sessionId = null, viewModel = viewModel) } }
            compose.onNodeWithTag("chat_input").performClick().performTextInput("来週の予定を確認したい")
            compose.onNodeWithTag("send_button").assertIsDisplayed()
            compose.runOnIdle {
                streaming.value =
                    StreamingState(
                        streamingMessage =
                            ChatMessage(
                                id = "synthetic-stream",
                                role = MessageRole.ASSISTANT,
                                content = "予定を確認しています。これは画面検証用の合成データです。",
                                isStreaming = true,
                            ),
                    )
            }
            compose.onNodeWithText("予定を確認しています。これは画面検証用の合成データです。").assertIsDisplayed()
            capture("07-chat-ja-streaming-input")
            compose.onNodeWithTag("chat_input").assertIsDisplayed()
            compose.onNodeWithTag("send_button").assertIsDisplayed()
            verify(exactly = 0) { viewModel.createNewSession(any()) }
        } finally {
            jobs.cancel()
        }
    }

    @Test
    fun advancedListsAllLegacyDestinationsInOriginalOrder() {
        val stack = NavBackStack<NavKey>(AdvancedScreen)
        NavigationController.backStack = stack
        compose.setContent { Japanese { AdvancedScreenContent() } }
        capture("06-advanced-ja")
        assertEquals(27, ScreenRegistry.ALL_SCREENS.size)
        val config =
            Configuration(
                instrumentation.targetContext.resources.configuration,
            ).apply { setLocale(Locale.JAPANESE) }
        val context = instrumentation.targetContext.createConfigurationContext(config)
        try {
            for (screen in ScreenRegistry.ALL_SCREENS) {
                val entry = hasText(context.getString(screen.labelRes)) and hasClickAction()
                compose.onNode(hasScrollAction()).performScrollToNode(entry)
                compose.onNode(entry).performClick()
                compose.runOnIdle {
                    assertEquals(screen.key, stack.last())
                    NavigationController.goBack()
                }
            }
        } finally {
            NavigationController.backStack = null
        }
    }
}
