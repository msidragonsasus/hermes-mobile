package com.m57.hermescontrol.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SettingsFootnote
import com.m57.hermescontrol.ui.common.SettingsRow
import kotlinx.serialization.json.JsonElement

/** Long lists (timezones) are searched rather than scrolled inside the page. */
private const val SEARCH_THRESHOLD = 12
private const val MAX_SEARCH_RESULTS = 30

/** On/off setting: the whole row toggles, saved on tap. */
@Composable
internal fun AgentSwitchItem(
    setting: AgentSetting,
    checked: Boolean,
    saving: Boolean,
    showDescription: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingsRow(
        title = setting.title,
        subtitle = setting.description.takeIf { showDescription },
        subtitleMaxLines = Int.MAX_VALUE,
        checked = checked,
        onCheckedChange = onToggle,
        enabled = !saving,
        modifier = Modifier.testTag("agent_setting_${setting.id}"),
    )
}

/**
 * Choice setting that opens in place: tapping the row reveals its options right underneath
 * (no sheet sliding up), picking one saves it and folds the row again.
 */
@Composable
internal fun AgentChoiceItem(
    setting: AgentSetting,
    schemaOptions: List<AgentSettingOption>,
    current: AgentSettingOption?,
    summary: String,
    saving: Boolean,
    expanded: Boolean,
    canRestore: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (AgentSettingOption) -> Unit,
    onRestore: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            title = setting.title,
            subtitle = summary,
            enabled = !saving,
            onClick = { onExpandedChange(!expanded) },
            status = {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(rotation),
                )
            },
            modifier = Modifier.testTag("agent_setting_${setting.id}"),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            var filter by remember { mutableStateOf("") }
            val searchable = schemaOptions.size > SEARCH_THRESHOLD
            val shown =
                if (searchable) {
                    val q = filter.trim()
                    val matches =
                        if (q.isEmpty()) {
                            schemaOptions
                        } else {
                            schemaOptions.filter {
                                it.label.contains(
                                    q,
                                    ignoreCase = true,
                                )
                            }
                        }
                    // Keep the current choice visible even when it is past the cut-off.
                    (matches.take(MAX_SEARCH_RESULTS) + listOfNotNull(current?.takeIf { it in matches })).distinct()
                } else {
                    schemaOptions
                }
            // Options continue the same row anatomy: 16dp text edge, leading radio, one meta line.
            Column(Modifier.padding(bottom = spacing.sm)) {
                SettingsFootnote(setting.description, Modifier.padding(bottom = spacing.xs))
                if (current == null) {
                    SettingsFootnote(
                        "今は詳細設定で個別の値が入っています。ここで選ぶと上書きされます。",
                        Modifier.padding(bottom = spacing.xs),
                    )
                }
                if (searchable) {
                    SearchBar(
                        query = filter,
                        onQueryChange = { filter = it },
                        placeholder = "探す",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = spacing.xs),
                    )
                }
                shown.forEach { option ->
                    val selected = option == current
                    SettingsRow(
                        title = option.label,
                        subtitle = option.description,
                        leading = { RadioButton(selected = selected, onClick = null) },
                        modifier =
                            Modifier.selectable(selected = selected, enabled = !saving, role = Role.RadioButton) {
                                if (selected) onExpandedChange(false) else onPick(option)
                            },
                    )
                }
                if (canRestore) {
                    SettingsRow(
                        title = "既定に戻す",
                        titleColor = MaterialTheme.colorScheme.primary,
                        onClick = onRestore,
                    )
                }
            }
        }
    }
}

/** Renders one catalog setting with the right control; shared by エージェント設定 and chat settings. */
@Composable
internal fun AgentSettingItem(
    setting: AgentSetting,
    state: AgentSettingsUiState,
    values: Map<String, JsonElement>,
    expanded: Boolean,
    showDescription: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChoose: (AgentSettingOption) -> Unit,
    onRestore: () -> Unit,
) {
    val saving = setting.id in state.savingIds
    val current = setting.currentOption(state.schema, values, state.defaults)
    when (setting.control) {
        AgentSettingControl.SWITCH -> {
            AgentSwitchItem(
                setting = setting,
                checked = current == setting.options[0],
                saving = saving,
                showDescription = showDescription,
                onToggle = { on -> onChoose(setting.options[if (on) 0 else 1]) },
            )
        }

        AgentSettingControl.CHOICE -> {
            AgentChoiceItem(
                setting = setting,
                schemaOptions = setting.resolvedOptions(state.schema),
                current = current,
                summary = if (saving) "保存中…" else setting.summary(state.schema, values, state.defaults),
                saving = saving,
                expanded = expanded,
                canRestore = setting.defaultWrites(values, state.defaults) != null,
                onExpandedChange = onExpandedChange,
                onPick = onChoose,
                onRestore = onRestore,
            )
        }
    }
}
