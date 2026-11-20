package com.m57.hermescontrol.agents

import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.CanonicalSessionInfo
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SpecialistSetupSnapshot
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.ui.bots.BotsUiState
import com.m57.hermescontrol.ui.bots.BotsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class BotSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val profileId = "bot-31b01926a7"
    private val profile =
        ProfileInfo(
            name = profileId,
            description = "公開情報を調べ、根拠をまとめます。",
            model = "openai-codex/gpt-6.2-sol",
            canonical_session = CanonicalSessionInfo(id = "session-root", resolved_id = "session-latest"),
        )

    private fun row(id: String) =
        compose.onNodeWithTag("bot_detail_list").let { list ->
            val tag = "detail_row_$id"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag)
        }

    @Test
    fun settingsUseDisplayNameAndKeepExistingActions() {
        val state =
            MutableStateFlow(
                BotDetailUiState(
                    loading = false,
                    profile = profile,
                    approvalMode = "smart",
                    skills = List(57) { Skill(name = "skill-${it + 1}", enabled = it < 7) },
                    setupSnapshot =
                        SpecialistSetupSnapshot(
                            name = profileId,
                            setup_completed = true,
                            apps = emptyList(),
                        ),
                ),
            )
        val roster = MutableStateFlow(BotsUiState())
        val detailViewModel = mockk<BotDetailViewModel>(relaxed = true)
        every { detailViewModel.uiState } returns state
        val botsViewModel = mockk<BotsViewModel>(relaxed = true)
        every { botsViewModel.uiState } returns roster
        val preference = mutableStateOf(ThemePreference.DARK)
        val fontScale = mutableStateOf(1f)
        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns MutableStateFlow(profileId)
        val stack = NavBackStack<NavKey>(ChatScreen, BotSettingsKey(profileId))
        NavigationController.attachAuth(stack)
        try {
            compose.setContent {
                LaunchedEffect(preference.value) {
                    val bars =
                        if (preference.value == ThemePreference.DARK) {
                            SystemBarStyle.dark(Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        }
                    compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                val context = LocalContext.current
                val current = LocalConfiguration.current
                val configuration = remember(current) { Configuration(current).apply { setLocale(Locale.JAPAN) } }
                val localized = remember(context, configuration) { context.createConfigurationContext(configuration) }
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalContext provides localized,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value),
                ) {
                    HermesControlTheme(themePreference = preference.value) {
                        BotDetailScreen(profileId, botsViewModel, detailViewModel)
                    }
                }
            }
            compose.onNodeWithTag("detail_name").assertTextEquals("専門ボット")
            assertNoRawProfileId()

            compose.runOnIdle {
                roster.value =
                    BotsUiState(
                        profiles = listOf(profile.copy(display_name = "調査アシスタント")),
                        rosterScope = BotConversations.serverScope(),
                    )
            }
            compose.onNodeWithTag("detail_name").assertTextEquals("調査アシスタント").assertIsDisplayed()
            compose.onNodeWithTag("detail_open_chat").assertTextEquals("相談を開く").assertIsDisplayed()
            compose.onNodeWithText("設定済み").assertIsDisplayed()
            assertNoRawProfileId()
            capture("bot-settings-dark")
            compose.runOnIdle { preference.value = ThemePreference.LIGHT }
            capture("bot-settings-light")

            compose.runOnIdle { fontScale.value = 1.6f }
            compose.onNodeWithTag("detail_name").assertIsDisplayed()
            compose.onNodeWithTag("detail_open_chat").assertIsDisplayed()
            compose.runOnIdle {
                state.value =
                    state.value.copy(
                        profile = profile.copy(description = profile.description.orEmpty().repeat(5)),
                        setupSnapshot = state.value.setupSnapshot?.copy(setup_completed = false),
                    )
            }
            compose.onNodeWithText("セットアップ中").assertIsDisplayed()
            compose.onNodeWithText("もっと表示").performClick()
            compose.onNodeWithText("折りたたむ").assertIsDisplayed().performClick()
            compose.runOnIdle {
                state.value =
                    state.value.copy(
                        profile = profile,
                        setupSnapshot = state.value.setupSnapshot?.copy(setup_completed = true),
                    )
            }
            compose.onNodeWithTag("detail_row_soul").performClick()
            compose.onNodeWithTag("detail_desc_input").assertIsDisplayed()
            Espresso.pressBack()

            row("routines").performClick()
            compose.onNodeWithText("登録されている定例作業はありません。").assertIsDisplayed()
            Espresso.pressBack()
            row("skills").performClick()
            compose.onNodeWithText("skill-1").assertIsDisplayed()
            Espresso.pressBack()
            row("approvals").performClick()
            compose.onNodeWithText("危険な操作のみ確認（smart）").assertIsDisplayed()
            Espresso.pressBack()
            row("model").performClick()
            compose.runOnIdle {
                assertEquals(BotDefaultsKey(profileId), stack.last())
                NavigationController.goBack()
            }
            row("appearance").assertIsDisplayed()
            assertNoRawProfileId()
            row("appearance").performClick()
            compose.onNodeWithText("Botを編集").assertIsDisplayed()
            assertNoRawProfileId()
            Espresso.pressBack()
            row("apps").assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithText("なし").assertIsDisplayed()
            row("delete").performClick()
            assertNoRawProfileId()
            Espresso.pressBack()
            verify(exactly = 0) { detailViewModel.deleteBot(any()) }

            compose.onNodeWithTag("bot_detail_list").performScrollToIndex(0)
            compose.onNodeWithTag("detail_open_chat").performClick()
            compose.runOnIdle {
                assertEquals(ChatScreen, stack.last())
                val request = NavigationController.consumePendingChatNavigation(profileId)
                assertEquals(profileId, request?.profileName)
                assertEquals("session-latest", request?.sessionId)
            }
        } finally {
            NavigationController.clearPendingChatNavigationForMismatch("default")
            NavigationController.backStack = null
            unmockkObject(AuthManager)
        }
    }

    private fun assertNoRawProfileId() {
        compose.onNodeWithText(profileId, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(profileId, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder =
            File(
                instrumentation.targetContext.getExternalFilesDir(null),
                "bot-settings-evidence",
            ).apply { mkdirs() }
        File(
            folder,
            "$name.png",
        ).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
