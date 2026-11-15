package com.m57.hermescontrol.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsAbout
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.SettingsExternalServices
import com.m57.hermescontrol.SettingsPasswords
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.settings.components.AppearanceSection
import com.m57.hermescontrol.ui.settings.components.BehaviorSection
import com.m57.hermescontrol.ui.settings.components.ChatSection
import com.m57.hermescontrol.ui.settings.components.LanguageSection

/**
 * App settings on one page. The appearance, language, chat and behaviour controls used to sit
 * behind separate drill-downs of one to three items each; they are now laid out inline under
 * section headings, and only the connection and About pages remain separate destinations.
 */
@Composable
fun SettingsScreen(
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_settings)) },
        navigationIcon = onBack?.let { NavIcon.Back(it) } ?: onOpenDrawer?.let { NavIcon.Menu(it) },
        drawerGesturesEnabled = onBack == null,
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_sec_appearance))
            AppearanceSection(
                themePreference = state.themePreference,
                onThemeChange = viewModel::onThemeChange,
                useDynamicColors = state.useDynamicColors,
                onUseDynamicColorsChange = viewModel::onUseDynamicColorsChange,
                themePreset = state.themePreset,
                onThemePresetChange = viewModel::onThemePresetChange,
            )
            SectionDivider()
            SectionHeader(stringResource(R.string.settings_sec_language))
            LanguageSection(
                appLanguage = state.appLanguage,
                onAppLanguageChange = viewModel::onAppLanguageChange,
            )
            SectionDivider()
            SectionHeader(stringResource(R.string.settings_sec_chat))
            ChatSection(
                typingEffectEnabled = state.typingEffectEnabled,
                onTypingEffectEnabledChange = viewModel::onTypingEffectEnabledChange,
                typingEffectDelayMs = state.typingEffectDelayMs,
                onTypingEffectDelayMsChange = viewModel::onTypingEffectDelayMsChange,
                chatFontScale = state.chatFontScale,
                onChatFontScaleChange = viewModel::onChatFontScaleChange,
            )
            SectionDivider()
            SectionHeader(stringResource(R.string.settings_sec_behavior))
            BehaviorSection(
                autoReconnect = state.autoReconnect,
                onAutoReconnectChange = viewModel::onAutoReconnectChange,
                pushConfigured = state.pushConfigured,
            )
            SectionDivider()
            LinkRow(
                icon = Icons.Filled.Link,
                label = stringResource(R.string.settings_sec_connection),
                onClick = { NavigationController.navigateTo(SettingsConnection) },
            )
            LinkRow(
                icon = Icons.Filled.Link,
                label = stringResource(R.string.management_external_services),
                onClick = { NavigationController.navigateTo(SettingsExternalServices) },
            )
            LinkRow(
                icon = Icons.Filled.Key,
                label = stringResource(R.string.vault_title),
                onClick = { NavigationController.navigateTo(SettingsPasswords) },
            )
            LinkRow(
                icon = Icons.Filled.Info,
                label = stringResource(R.string.settings_sec_about),
                onClick = { NavigationController.navigateTo(SettingsAbout) },
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Flat drill-down row matching the App management directory (no card, same chevron). */
@Composable
private fun LinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(text = label) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
    )
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Settings group body. Flat on the page background (no card); headings and dividers separate groups. */
@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        content()
    }
}
