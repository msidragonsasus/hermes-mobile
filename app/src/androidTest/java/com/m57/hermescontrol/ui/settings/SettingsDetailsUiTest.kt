package com.m57.hermescontrol.ui.settings

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.model.AccountLimitWindow
import com.m57.hermescontrol.data.model.AccountLimitsResponse
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.config.AgentSettingsList
import com.m57.hermescontrol.ui.config.AgentSettingsUiState
import com.m57.hermescontrol.ui.connections.ExternalServicesContent
import com.m57.hermescontrol.ui.connections.ExternalServicesState
import com.m57.hermescontrol.ui.model.ModelSettingsSection
import com.m57.hermescontrol.ui.model.ProviderCard
import com.m57.hermescontrol.ui.plugins.PluginCard
import com.m57.hermescontrol.ui.plugins.PluginsUiState
import com.m57.hermescontrol.ui.plugins.PluginsViewModel
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.components.ConnectionCheckGroup
import com.m57.hermescontrol.ui.settings.components.ConnectionProfilesGroup
import com.m57.hermescontrol.ui.settings.limits.AccountLimitsContent
import com.m57.hermescontrol.ui.settings.limits.AccountLimitsState
import com.m57.hermescontrol.ui.settings.limits.LimitsStatus
import com.m57.hermescontrol.ui.skills.HubSkillCard
import com.m57.hermescontrol.ui.skills.SkillCard
import com.m57.hermescontrol.vault.VaultContent
import com.m57.hermescontrol.vault.VaultRepository
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Native rendering and callbacks with synthetic fixtures; no live APIs, settings writes or credentials. */
class SettingsDetailsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scene = mutableStateOf(Scene.APPEARANCE)
    private val theme = mutableStateOf(ThemePreference.DARK)
    private val scale = mutableStateOf(1f)
    private val expanded = mutableStateOf<String?>(null)
    private val selectedProfile = mutableStateOf("home")
    private val skillEnabled = mutableStateOf(true)
    private var modelSelections = 0
    private var pins = 0
    private var mainPicker = 0
    private var editedSkill = 0
    private var editedProfile = ""
    private var chosenSetting = ""
    private var confirmationRequested = false
    private val plugins = PluginsViewModel() // Constructor only; never load or invoke its API callbacks.
    private val modelName = "sample-model-with-a-long-but-readable-name"
    private val values =
        mapOf(
            "memory.memory_enabled" to JsonPrimitive(true),
            "memory.user_profile_enabled" to JsonPrimitive(true),
            "memory.write_approval" to JsonPrimitive(true),
            "approvals.mode" to JsonPrimitive("manual"),
        )
    private val vault =
        VaultRepository(rpc = { method, _ ->
            check(method == "vault.list")
            mapOf(
                "items" to
                    listOf(
                        mapOf(
                            "id" to "fixture",
                            "kind" to "login",
                            "label" to "サンプルサービス",
                            "origin" to "https://example.test",
                            "identifier" to "sample-user",
                            "backend" to "local",
                        ),
                    ),
            )
        })

    private enum class Scene(
        val title: String,
    ) {
        MODELS("モデル"),
        SKILLS("スキル"),
        PLUGINS("プラグイン"),
        MEMORY("メモリ"),
        LIMITS("利用制限"),
        CONNECTORS("コネクター"),
        LOGINS("ログイン情報"),
        PERMISSIONS("アクセス許可"),
        SERVER("接続先"),
        NOTIFICATIONS("通知"),
        APPEARANCE("外観"),
    }

    @Test
    fun allElevenDestinationsRenderInDarkAndLargeLightLayouts() {
        start()
        for ((preference, fontScale) in listOf(ThemePreference.DARK to 1f, ThemePreference.LIGHT to 2f)) {
            for (target in Scene.entries) {
                compose.runOnIdle {
                    theme.value = preference
                    scale.value = fontScale
                    scene.value = target
                }
                val text = primaryText(target)
                compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(text).assertIsDisplayed()
                compose.onNodeWithTag("back_button", useUnmergedTree = true).assertIsDisplayed()
                capture("${target.name.lowercase()}-${preference.name.lowercase()}.png")
            }
        }
    }

    @Test
    fun modelPinAndSelectionDoNotTriggerEachOther() {
        scene.value = Scene.MODELS
        start()
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.content_desc_pin_model),
            ).performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(1, pins)
            assertEquals(0, modelSelections)
        }
        compose.onNodeWithText(modelName).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, modelSelections) }
        compose.onNodeWithText(context.getString(R.string.model_main_label)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, mainPicker) }
    }

    @Test
    fun skillAndConnectionRowsRetainIndependentActions() {
        scene.value = Scene.SKILLS
        start()
        compose.onNode(isToggleable() and hasContentDescription("予定を整理する")).performClick()
        compose.runOnIdle { assertEquals(false, skillEnabled.value) }
        compose.onNodeWithContentDescription(context.getString(R.string.settings_actions_for, "予定を整理する")).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_action_edit)).performClick()
        compose.runOnIdle {
            assertEquals(1, editedSkill)
            assertEquals(false, skillEnabled.value)
        }
        compose.runOnIdle { scene.value = Scene.SERVER }
        compose.onNodeWithTag("connection_profile_away").performClick()
        compose.runOnIdle { assertEquals("away", selectedProfile.value) }
        compose.onNodeWithContentDescription(context.getString(R.string.settings_actions_for, "自宅")).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_action_edit)).performClick()
        compose.runOnIdle {
            assertEquals("home", editedProfile)
            assertEquals("away", selectedProfile.value)
        }
    }

    @Test
    fun memoryAndPermissionControlsKeepTheirSaveAndConfirmationIntent() {
        scene.value = Scene.MEMORY
        start()
        compose.onNodeWithTag("agent_setting_memory").performClick()
        compose.runOnIdle { assertEquals("memory", chosenSetting) }
        compose.runOnIdle { scene.value = Scene.PERMISSIONS }
        compose.onNodeWithTag("agent_setting_approvals").performClick()
        compose.onNodeWithText("確認しない").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("approvals", chosenSetting)
            assertTrue(confirmationRequested)
        }
    }

    private fun start() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                HermesControlTheme(themePreference = theme.value) {
                    when (scene.value) {
                        Scene.LOGINS -> {
                            VaultContent(vault, "sample-profile")
                        }

                        Scene.NOTIFICATIONS -> {
                            SettingsNotificationsPage()
                        }

                        else -> {
                            HermesScaffold(
                                title = { Text(scene.value.title) },
                                navigationIcon = NavIcon.Back {},
                                drawerGesturesEnabled = false,
                            ) { content() }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun content() {
        when (scene.value) {
            Scene.MEMORY, Scene.PERMISSIONS -> {
                AgentSettingsList(
                    state = AgentSettingsUiState(values = values),
                    values = values,
                    settingIds =
                        if (scene.value ==
                            Scene.MEMORY
                        ) {
                            setOf("memory", "user_profile", "memory_write_approval")
                        } else {
                            setOf("approvals")
                        },
                    query = "",
                    onQueryChange = {},
                    expandedId = expanded.value,
                    onExpandedChange = { setting, open -> expanded.value = setting.id.takeIf { open } },
                    onChoose = { setting, option ->
                        chosenSetting = setting.id
                        confirmationRequested = option.confirmMessage != null
                        expanded.value = null
                    },
                    onRestore = {},
                )
            }

            Scene.CONNECTORS -> {
                ExternalServicesContent(ExternalServicesState(loading = false), {}, {}, {}, {})
            }

            Scene.LIMITS -> {
                val now = Instant.parse("2026-11-17T01:00:00Z")
                AccountLimitsContent(
                    AccountLimitsState(
                        LimitsStatus.READY,
                        AccountLimitsResponse(
                            available = true,
                            provider = "Codex",
                            plan = "Plus",
                            fetched_at = now.toString(),
                            windows = listOf(AccountLimitWindow("Session", 25.0, now.plusSeconds(7200).toString())),
                        ),
                    ),
                    now,
                    {},
                )
            }

            else -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(
                            rememberScrollState(),
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    when (scene.value) {
                        Scene.APPEARANCE -> {
                            AppearanceSection(theme.value, { theme.value = it }, false, {}, ThemePreset.DEFAULT, {})
                            ChatSection(
                                false,
                                {},
                                20,
                                {},
                                showTypingControls = false,
                                label = context.getString(R.string.settings_group_chat_text),
                            )
                        }

                        Scene.SERVER -> {
                            ConnectionProfilesGroup(
                                profiles =
                                    listOf(
                                        ConnectionProfile(id = "home", name = "自宅"),
                                        ConnectionProfile(id = "away", name = "外出先"),
                                    ),
                                selectedProfileId = selectedProfile.value,
                                baseUrl = "http://127.0.0.1:9119",
                                onSelect = { selectedProfile.value = it },
                                onEdit = { editedProfile = it },
                                onDelete = {},
                                onAdd = {},
                            )
                            ConnectionCheckGroup(isTesting = false, testResult = "✅ 接続できました", onTest = {})
                        }

                        Scene.MODELS -> {
                            ModelSettingsSection("sample", "サンプルモデル", null, false, "", { mainPicker++ }, {}, {}, {})
                            SettingsGroupLabel(context.getString(R.string.model_group_models))
                            ProviderCard(
                                provider = ModelProvider("sample", "サンプル提供元", models = listOf(modelName)),
                                isExpanded = true,
                                isCurrent = false,
                                query = "",
                                activeProfile = null,
                                pinnedModels = emptyList(),
                                onToggleExpand = {},
                                onModelClick = { _, _ -> modelSelections++ },
                                onPin = { _, _ -> pins++ },
                                onUnpin = { _, _ -> },
                                last = false,
                            )
                            ProviderCard(
                                provider = ModelProvider("other", "別の提供元", models = listOf("other-model")),
                                isExpanded = false,
                                isCurrent = true,
                                query = "",
                                activeProfile = null,
                                pinnedModels = emptyList(),
                                onToggleExpand = {},
                                onModelClick = { _, _ -> },
                                onPin = { _, _ -> },
                                onUnpin = { _, _ -> },
                                first = false,
                            )
                        }

                        Scene.SKILLS -> {
                            SettingsGroupLabel(context.getString(R.string.skills_installed_count, 2))
                            SkillCard(
                                Skill("メールを要約する", "未読メールの要点を短くまとめます。", enabled = false),
                                onToggle = {},
                                onAction = {},
                                onUninstall = {},
                                isUninstalling = false,
                                onClick = {},
                                last = false,
                            )
                            SkillCard(
                                Skill("予定を整理する", "今日の予定と優先順位をまとめます。", enabled = skillEnabled.value),
                                onToggle = { skillEnabled.value = !skillEnabled.value },
                                onAction = { editedSkill++ },
                                onUninstall = null,
                                isUninstalling = false,
                                onClick = {},
                                first = false,
                            )
                            SettingsGroupLabel(context.getString(R.string.skills_hub_featured_title))
                            HubSkillCard(
                                HubSkill(name = "議事録を作る", description = "会議メモから決定事項と宿題を整理します。"),
                                onInstall = {},
                                isInstalling = false,
                                onClick = {},
                            )
                        }

                        Scene.PLUGINS -> {
                            SettingsGroupLabel(context.getString(R.string.plugins_installed_count, 2))
                            PluginCard(
                                PluginInfo(
                                    "メモ同期",
                                    "メモアプリと内容を同期します。",
                                    version = "0.3",
                                    runtimeStatus = "disabled",
                                    authRequired = true,
                                    authCommand = "hermes auth memo",
                                    canRemove = true,
                                ),
                                PluginsUiState(),
                                plugins,
                                {},
                                last = false,
                            )
                            PluginCard(
                                PluginInfo(
                                    "カレンダー",
                                    "予定を確認するプラグインです。",
                                    version = "1.0",
                                    runtimeStatus = "enabled",
                                    canRemove = true,
                                ),
                                PluginsUiState(),
                                plugins,
                                {},
                                first = false,
                            )
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            }
        }
    }

    private fun primaryText(target: Scene): String =
        when (target) {
            Scene.APPEARANCE -> context.getString(R.string.settings_item_theme)
            Scene.MODELS -> context.getString(R.string.model_main_label)
            Scene.SKILLS -> "予定を整理する"
            Scene.PLUGINS -> "カレンダー"
            Scene.MEMORY -> "大事なことを覚える"
            Scene.PERMISSIONS -> "危険な操作の前に確認"
            Scene.CONNECTORS -> "Gmail"
            Scene.LOGINS -> "サンプルサービス"
            Scene.SERVER -> "自宅"
            Scene.NOTIFICATIONS -> context.getString(R.string.settings_notification_android)
            Scene.LIMITS -> context.getString(R.string.settings_limits_remaining, "75")
        }

    private fun capture(name: String) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "settings-details").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
