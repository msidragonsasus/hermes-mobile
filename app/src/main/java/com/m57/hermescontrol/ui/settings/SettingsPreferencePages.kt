package com.m57.hermescontrol.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.MemoryScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsExternalServices
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.config.AgentSettingsScreen
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.BehaviorSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.components.LanguageSection

@Composable
internal fun SettingsAppearancePage(viewModel: SettingsViewModel = viewModel { SettingsViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PreferencePage(stringResource(R.string.settings_sec_appearance)) {
        AppearanceSection(
            state.themePreference,
            viewModel::onThemeChange,
            state.useDynamicColors,
            viewModel::onUseDynamicColorsChange,
            state.themePreset,
            viewModel::onThemePresetChange,
        )
        ChatSection(
            typingEffectEnabled = state.typingEffectEnabled,
            onTypingEffectEnabledChange = viewModel::onTypingEffectEnabledChange,
            typingEffectDelayMs = state.typingEffectDelayMs,
            onTypingEffectDelayMsChange = viewModel::onTypingEffectDelayMsChange,
            chatFontScale = state.chatFontScale,
            onChatFontScaleChange = viewModel::onChatFontScaleChange,
            showTypingControls = false,
            label = stringResource(R.string.settings_group_chat_text),
        )
    }
}

/** Preserve existing preferences and their editors without exposing technical controls on the home. */
@Composable
internal fun SettingsAppAdvancedPage(viewModel: SettingsViewModel = viewModel { SettingsViewModel() }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PreferencePage(stringResource(R.string.settings_app_advanced)) {
        SectionHeader(stringResource(R.string.settings_sec_language))
        LanguageSection(state.appLanguage, viewModel::onAppLanguageChange)
        SectionHeader(stringResource(R.string.settings_sec_chat))
        ChatSection(
            state.typingEffectEnabled,
            viewModel::onTypingEffectEnabledChange,
            state.typingEffectDelayMs,
            viewModel::onTypingEffectDelayMsChange,
            showFontControls = false,
        )
        SectionHeader(stringResource(R.string.settings_sec_behavior))
        BehaviorSection(state.autoReconnect, viewModel::onAutoReconnectChange, pushConfigured = state.pushConfigured)
    }
}

@Composable
internal fun SettingsNotificationsPage() {
    val context = LocalContext.current
    PreferencePage(stringResource(R.string.settings_notifications)) {
        SettingsGroupLabel(stringResource(R.string.settings_group_notifications))
        SettingsGroup {
            row {
                SettingsLinkRow(
                    label = stringResource(R.string.settings_notification_android),
                    description = stringResource(R.string.settings_notification_android_desc),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun SettingsPermissionsPage() {
    val context = LocalContext.current
    AgentSettingsScreen(
        title = stringResource(R.string.settings_permissions),
        settingIds = setOf("approvals"),
        onBack = { NavigationController.goBack() },
        headerContent = {
            SettingsGroupLabel(stringResource(R.string.settings_group_android_service))
            SettingsGroup {
                row {
                    SettingsLinkRow(
                        label = stringResource(R.string.settings_android_permissions),
                        description = stringResource(R.string.settings_android_permissions_desc),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    )
                }
                row {
                    SettingsLinkRow(
                        label = stringResource(R.string.settings_service_permissions),
                        description = stringResource(R.string.settings_service_permissions_desc),
                        onClick = { NavigationController.navigateTo(SettingsExternalServices) },
                    )
                }
            }
        },
    )
}

@Composable
internal fun SettingsMemoryPage() {
    AgentSettingsScreen(
        title = stringResource(R.string.settings_memory),
        settingIds = setOf("memory", "user_profile", "memory_write_approval"),
        onBack = { NavigationController.goBack() },
        headerContent = {
            SettingsGroupLabel(stringResource(R.string.settings_group_memory_content))
            SettingsGroup {
                row {
                    SettingsLinkRow(
                        label = stringResource(R.string.settings_memory_content),
                        description = stringResource(R.string.settings_memory_content_desc),
                        onClick = { NavigationController.navigateTo(MemoryScreen) },
                    )
                }
            }
        },
    )
}

@Composable
private fun PreferencePage(
    title: String,
    content: @Composable () -> Unit,
) {
    HermesScaffold(
        title = { Text(title) },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            content()
        }
    }
}
