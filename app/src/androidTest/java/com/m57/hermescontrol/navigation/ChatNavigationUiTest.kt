package com.m57.hermescontrol.navigation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.common.AdvancedScreenContent
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LocalManagementScope
import com.m57.hermescontrol.ui.common.LocalShellMenu
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ShellBottomBar
import com.m57.hermescontrol.ui.common.ShellMenu
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Production navigation components with synthetic content; no authenticated server or real chat data. */
class ChatNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    private fun configureActivity(fontScale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity ->
            val resources = activity.resources
            val configuration =
                Configuration(resources.configuration).apply {
                    setLocale(Locale.JAPAN)
                    this.fontScale = fontScale
                }
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
    }

    @Before
    fun configureJapaneseActivity() = configureActivity()

    @Composable
    private fun JapaneseTheme(
        light: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val localizedConfiguration =
            remember(configuration) {
                Configuration(configuration).apply { setLocale(Locale.JAPAN) }
            }
        val localized =
            remember(context, localizedConfiguration) {
                context.createConfigurationContext(localizedConfiguration)
            }
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalConfiguration provides localizedConfiguration,
            LocalContext provides localized,
            LocalDensity provides Density(density.density, fontScale),
        ) {
            HermesControlTheme(
                themePreference = if (light) ThemePreference.LIGHT else ThemePreference.DARK,
                content = content,
            )
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // UiAutomation captures compositor pixels, which can lag Compose's idle semantics.
        SystemClock.sleep(200)
        val folder =
            File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "chat-ux-evidence",
            ).apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test
    fun conversationMenuUsesInlineHistoryAndKeepsModelInComposer() {
        var history = 0
        var newConversation = 0
        var model = 0
        compose.setContent {
            JapaneseTheme {
                ConversationMenuSheet(
                    currentModel = "test-model",
                    onHistory = { history++ },
                    onNewConversation = { newConversation++ },
                    onModel = { model++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("conversation_search").assertIsDisplayed()
        compose.onNodeWithTag("conversation_history_list").assertIsDisplayed()
        compose.onNodeWithTag("conversation_model").assertDoesNotExist()
        compose.onNodeWithTag("conversation_history").assertDoesNotExist()
        capture("conversation-menu-dark")
        compose.onNodeWithTag("conversation_new").performClick()
        compose.runOnIdle { assertEquals(listOf(0, 1, 0), listOf(history, newConversation, model)) }
    }

    @Test
    fun conversationMenuKeepsActionsVisibleWithLargeJapaneseText() {
        configureActivity(1.5f)
        compose.setContent {
            JapaneseTheme(light = true, fontScale = 1.5f) {
                ConversationMenuSheet(null, {}, {}, {}, {})
            }
        }
        capture("conversation-menu-light-large-text")
        compose.onNodeWithTag("conversation_new").assertIsDisplayed()
        compose.onNodeWithTag("conversation_search").assertIsDisplayed()
    }

    @Test
    fun managementHidesPrimaryTabsAndBackReturnsThroughDirectoryToConversation() {
        lateinit var shell: ShellNavigation
        compose.setContent {
            JapaneseTheme {
                shell =
                    remember {
                        ShellNavigation(
                            mutableStateOf(ShellTab.CHAT),
                            ShellTab.primary.associateWith { NavBackStack<NavKey>(it.root) },
                        )
                    }
                NavigationController.attachShell(shell)
                DisposableEffect(shell) { onDispose { NavigationController.detachShell(shell) } }
                val menu: (@Composable () -> Unit)? = if (shell.isAtRoot) ({ ShellMenu() }) else null
                CompositionLocalProvider(
                    LocalManagementScope provides shell.isInManagement,
                    LocalShellMenu provides menu,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f)) {
                            key(shell.backStack.last()) {
                                when (shell.backStack.last()) {
                                    ChatScreen -> {
                                        HermesScaffold(title = { Text("テスト会話") }) { Text("入力途中のテスト内容") }
                                    }

                                    AdvancedScreen -> {
                                        AdvancedScreenContent()
                                    }

                                    CronJobsScreen -> {
                                        HermesScaffold(
                                            title = { Text("定期実行") },
                                            navigationIcon = NavIcon.Menu({}),
                                        ) { Text("合成データ: 毎朝8:00") }
                                    }
                                }
                            }
                        }
                        if (!shell.isInManagement) ShellBottomBar(shell.selectedTab)
                    }
                }
            }
        }
        compose.onNodeWithTag("shell_menu").performClick()
        compose.onNodeWithText("アプリ管理").assertIsDisplayed()
        compose.onNodeWithText("接続と認証").assertIsDisplayed()
        compose.onNodeWithTag("shell_tab_agent").assertDoesNotExist()
        compose.onNodeWithTag("shell_menu").assertDoesNotExist()
        capture("app-management-dark")
        compose.onNodeWithTag("app_management_list").performScrollToNode(hasTestTag("management_CronJobsScreen"))
        compose.onNodeWithTag("management_CronJobsScreen").performClick()
        compose.runOnIdle { assertEquals(CronJobsScreen, shell.backStack.last()) }
        capture("management-destination-no-chat-tab")
        compose.onNodeWithTag("shell_tab_agent").assertDoesNotExist()
        compose.onNodeWithContentDescription("戻る").assertIsDisplayed()
        compose.onNodeWithTag("menu_button").assertDoesNotExist()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("アプリ管理").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("テスト会話").assertIsDisplayed()
        compose.onNodeWithTag("shell_tab_agent").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ChatScreen), shell.backStack.toList()) }
        capture("returned-to-conversation")
    }

    @Test
    fun managementRemainsScrollableWithLargeJapaneseText() {
        configureActivity(1.5f)
        compose.setContent { JapaneseTheme(light = true, fontScale = 1.5f) { AdvancedScreenContent() } }
        compose.onNodeWithText("アプリ管理").assertIsDisplayed()
        capture("app-management-light-large-text")
        compose.onNodeWithTag("app_management_list").performScrollToNode(hasText("定期実行"))
        compose.onNodeWithText("決まった時刻に動く処理の一覧と設定").assertIsDisplayed()
        compose.onNodeWithTag("app_management_list").performScrollToNode(hasText("Appサンプル"))
        compose.onNodeWithText("Appサンプル").assertIsDisplayed()
    }
}
