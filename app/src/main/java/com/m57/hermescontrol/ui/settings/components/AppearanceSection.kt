package com.m57.hermescontrol.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.ui.common.ConnectedButtonGroup
import com.m57.hermescontrol.ui.common.SettingsGroup
import com.m57.hermescontrol.ui.common.SettingsGroupLabel
import com.m57.hermescontrol.ui.common.SettingsRow
import com.m57.hermescontrol.ui.settings.SectionCard
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSection(
    themePreference: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    useDynamicColors: Boolean,
    onUseDynamicColorsChange: (Boolean) -> Unit,
    themePreset: ThemePreset,
    onThemePresetChange: (ThemePreset) -> Unit,
) {
    var presetsExpanded by remember { mutableStateOf(false) }
    SettingsGroupLabel(stringResource(R.string.settings_group_display))
    Box(Modifier.fillMaxWidth()) {
        SettingsGroup {
            row {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_item_theme), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    ConnectedButtonGroup(
                        options = ThemePreference.entries,
                        selected = themePreference,
                        onSelect = onThemeChange,
                        label = { pref ->
                            stringResource(
                                when (pref) {
                                    ThemePreference.SYSTEM -> R.string.theme_system
                                    ThemePreference.LIGHT -> R.string.theme_light
                                    ThemePreference.DARK -> R.string.theme_dark
                                },
                            )
                        },
                    )
                }
            }
            row {
                SettingsRow(
                    title = stringResource(R.string.settings_colors),
                    subtitle =
                        if (useDynamicColors) {
                            stringResource(
                                R.string.settings_item_use_dynamic_colors,
                            )
                        } else {
                            themePresetLabel(themePreset)
                        },
                    chevron = true,
                    onClick = { presetsExpanded = true },
                )
            }
        }
        DropdownMenu(expanded = presetsExpanded, onDismissRequest = { presetsExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_item_use_dynamic_colors)) },
                onClick = {
                    onUseDynamicColorsChange(true)
                    presetsExpanded = false
                },
            )
            ThemePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(themePresetLabel(preset)) },
                    onClick = {
                        onUseDynamicColorsChange(false)
                        onThemePresetChange(preset)
                        presetsExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun themePresetLabel(preset: ThemePreset): String =
    stringResource(
        when (preset) {
            ThemePreset.DEFAULT -> R.string.theme_preset_default
            ThemePreset.MONOCHROME -> R.string.theme_preset_monochrome
            ThemePreset.GRUVBOX -> R.string.theme_preset_gruvbox
            ThemePreset.CATPPUCCIN -> R.string.theme_preset_catppuccin
            ThemePreset.AMOLED -> R.string.theme_preset_amoled
            ThemePreset.NORD -> R.string.theme_preset_nord
            ThemePreset.SLATE -> R.string.theme_preset_slate
        },
    )

@Composable
internal fun ChatSection(
    typingEffectEnabled: Boolean,
    onTypingEffectEnabledChange: (Boolean) -> Unit,
    typingEffectDelayMs: Int,
    onTypingEffectDelayMsChange: (Int) -> Unit,
    chatFontScale: Float = 1.0f,
    onChatFontScaleChange: (Float) -> Unit = {},
    showTypingControls: Boolean = true,
    showFontControls: Boolean = true,
    label: String? = null,
) {
    val fontScaleOptions = listOf(0.85f, 1.0f, 1.15f, 1.30f, 1.50f)
    val currentIndex =
        fontScaleOptions
            .indexOfFirst { abs(it - chatFontScale) < 0.05f }
            .takeIf { it >= 0 } ?: 1
    val scaleLabel =
        when (currentIndex) {
            0 -> stringResource(R.string.settings_font_scale_small)
            1 -> stringResource(R.string.settings_font_scale_default)
            2 -> stringResource(R.string.settings_font_scale_large)
            3 -> stringResource(R.string.settings_font_scale_xlarge)
            else -> stringResource(R.string.settings_font_scale_huge)
        }

    label?.let { SettingsGroupLabel(it) }
    SettingsGroup {
        if (showFontControls) {
            // Title and current value stack like every other row, so large text never squeezes the title.
            row {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_item_chat_font_size),
                        subtitle = scaleLabel,
                    )
                    Slider(
                        value = currentIndex.toFloat(),
                        onValueChange = { onChatFontScaleChange(fontScaleOptions[it.roundToInt()]) },
                        valueRange = 0f..(fontScaleOptions.size - 1).toFloat(),
                        steps = fontScaleOptions.size - 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "0.85×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "1.5×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            row { ChatFontPreview(chatFontScale) }
        }
        if (showTypingControls) {
            row {
                SettingsRow(
                    title = stringResource(R.string.settings_item_typing_effect),
                    subtitle = stringResource(R.string.settings_desc_typing_effect),
                    checked = typingEffectEnabled,
                    onCheckedChange = onTypingEffectEnabledChange,
                )
            }
            if (typingEffectEnabled) {
                row {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
                        SettingsRow(
                            title = stringResource(R.string.settings_item_typing_delay, typingEffectDelayMs),
                        )
                        Slider(
                            value = typingEffectDelayMs.toFloat(),
                            onValueChange = { onTypingEffectDelayMsChange(it.toInt()) },
                            valueRange = 10f..100f,
                            steps = 8, // 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_delay_10ms),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.settings_delay_100ms),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Two sample bubbles at the chosen chat scale, drawn directly on the group surface. */
@Composable
private fun ChatFontPreview(chatFontScale: Float) {
    val currentDensity = LocalDensity.current
    val previewDensity =
        remember(currentDensity, chatFontScale) {
            Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale * chatFontScale,
            )
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_chat_preview_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompositionLocalProvider(LocalDensity provides previewDensity) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = stringResource(R.string.settings_chat_preview_user),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = stringResource(R.string.settings_chat_preview_agent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
