package com.m57.hermescontrol.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ConfigScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.ProfilesScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SkillsScreen
import com.m57.hermescontrol.ToolsetsScreen
import com.m57.hermescontrol.navigation.AdvancedScreen
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.config.AgentSetting
import com.m57.hermescontrol.ui.config.AgentSettingItem
import com.m57.hermescontrol.ui.config.AgentSettingOption
import com.m57.hermescontrol.ui.config.AgentSettingsCatalog
import com.m57.hermescontrol.ui.config.AgentSettingsNoticeEffect
import com.m57.hermescontrol.ui.config.AgentSettingsViewModel
import com.m57.hermescontrol.ui.config.isAvailable

/** The reply settings that matter while chatting, editable right where you chat. */
private val inChatSettingIds = listOf("show_reasoning", "busy_input", "approvals")

/**
 * Chat settings, opened from the gear in the conversation header as a normal page.
 * Reply settings are changed in place (saved on tap); everything else is one row away.
 */
@Composable
fun ChatSettingsScreen(settingsViewModel: AgentSettingsViewModel = viewModel { AgentSettingsViewModel() }) {
    val spacing = LocalSpacing.current
    val state by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val activeProfile by com.m57.hermescontrol.data.local.AuthManager.activeProfileId
        .collectAsStateWithLifecycle()
    val activeBot = activeProfile?.takeIf { it.isNotBlank() && it != "default" }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<AgentSetting, AgentSettingOption>?>(null) }

    // Show the server's current values, not a copy from the last visit.
    LaunchedEffect(Unit) { settingsViewModel.load() }
    AgentSettingsNoticeEffect(state.notice, snackbar, settingsViewModel::consumeNotice, settingsViewModel::undo)

    fun choose(
        setting: AgentSetting,
        option: AgentSettingOption,
    ) {
        expandedId = null
        if (option.confirmMessage != null) confirm = setting to option else settingsViewModel.select(setting, option)
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.chat_settings_title)) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        showShellMenu = false,
        snackbarHost = { SnackbarHost(snackbar) },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("chat_settings")) {
            val values = state.values
            val quick =
                inChatSettingIds
                    .mapNotNull(AgentSettingsCatalog::byId)
                    .filter { values != null && it.isAvailable(state.schema, values) }
            if (values != null && quick.isNotEmpty()) {
                item("h_replies") {
                    SectionHeader(stringResource(R.string.chat_settings_section_replies))
                }
                items(quick, key = { it.id }) { setting ->
                    AgentSettingItem(
                        setting = setting,
                        state = state,
                        values = values,
                        expanded = expandedId == setting.id,
                        showDescription = false,
                        onExpandedChange = { open -> expandedId = if (open) setting.id else null },
                        onChoose = { choose(setting, it) },
                        onRestore = {
                            expandedId = null
                            settingsViewModel.restoreDefault(setting)
                        },
                    )
                }
            }

            // Talking to a bot (not plain Hermes): its own settings are one tap away.
            item("bot_defaults") {
                LinkRow(
                    Icons.Default.Tune,
                    "${BotConversations.label(activeProfile ?: "default")}の既定モデル・推論強度",
                    "新しい会話・グループで使う設定",
                    "chat_settings_bot_defaults",
                ) { NavigationController.navigateTo(BotDefaultsKey(activeProfile ?: "default")) }
            }
            activeBot?.let { bot ->
                item("this_bot") {
                    LinkRow(
                        Icons.Default.SmartToy,
                        stringResource(R.string.chat_settings_this_bot, bot),
                        null,
                        "agent_settings_this_bot",
                    ) { NavigationController.navigateTo(BotSettingsKey(bot)) }
                }
            }
            item("h_more") {
                HorizontalDivider(Modifier.padding(top = spacing.sm))
                SectionHeader(stringResource(R.string.chat_settings_section_more))
            }
            val links: List<Triple<ImageVector, Int, Pair<String, NavKey>>> =
                listOf(
                    Triple(
                        Icons.Default.SmartToy,
                        R.string.chat_settings_manage_bots,
                        "agent_settings_bots" to BotsScreen,
                    ),
                    Triple(
                        Icons.Default.Tune,
                        R.string.chat_settings_all_agent,
                        "agent_settings_config" to ConfigScreen,
                    ),
                    Triple(
                        Icons.Default.Link,
                        R.string.agent_controls_connection,
                        "agent_settings_connection" to SettingsConnection,
                    ),
                    Triple(
                        Icons.Default.AccountCircle,
                        R.string.agent_controls_profile,
                        "agent_settings_profile" to ProfilesScreen,
                    ),
                    Triple(
                        Icons.Default.Inventory2,
                        R.string.agent_controls_skills,
                        "agent_settings_skills" to SkillsScreen,
                    ),
                    Triple(
                        Icons.Default.Build,
                        R.string.agent_controls_tools,
                        "agent_settings_tools" to ToolsetsScreen,
                    ),
                    Triple(Icons.Default.Apps, R.string.chat_settings_app, "agent_settings_all" to AdvancedScreen),
                )
            items(links, key = { it.third.first }) { (icon, label, target) ->
                LinkRow(
                    icon,
                    stringResource(label),
                    null,
                    target.first,
                ) { NavigationController.navigateTo(target.second) }
            }
        }
    }

    confirm?.let { (setting, option) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("「${option.label}」にしますか？") },
            text = { Text(option.confirmMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    settingsViewModel.select(setting, option)
                }) { Text("変更する") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("やめる") } },
        )
    }
}

/** Flat drill-down row: page background, optional icon, value on the second line. */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    value: String?,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it, color = MaterialTheme.colorScheme.primary) } },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
    )
}
