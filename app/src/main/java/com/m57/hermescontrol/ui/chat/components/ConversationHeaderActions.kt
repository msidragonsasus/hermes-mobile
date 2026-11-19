package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.SettingsMenuItem
import com.m57.hermescontrol.ui.common.SettingsOverflowMenu

/** Existing conversation controls, shared by individual and group conversations. */
@Composable
internal fun ConversationHeaderActions(
    onNew: () -> Unit,
    onSettings: () -> Unit,
    settingsEnabled: Boolean = true,
    settingsTag: String = "chat_settings_button",
    newEnabled: Boolean = true,
    showNew: Boolean = true,
    extraSettingsAction: SettingsMenuItem? = null,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row {
            if (showNew) {
                IconButton(
                    onClick = onNew,
                    enabled = newEnabled,
                    modifier = Modifier.testTag("conversation_new_button"),
                ) {
                    Icon(painterResource(R.drawable.edit_square_24), stringResource(R.string.content_desc_new_chat))
                }
            }
            val settingsLabel = stringResource(R.string.chat_settings_title)
            if (extraSettingsAction != null) {
                SettingsOverflowMenu(
                    items = listOf(SettingsMenuItem(settingsLabel, settingsEnabled, onSettings), extraSettingsAction),
                    contentDescription = stringResource(R.string.profiles_more_options),
                    testTag = settingsTag,
                )
            } else {
                IconButton(onClick = onSettings, enabled = settingsEnabled, modifier = Modifier.testTag(settingsTag)) {
                    Icon(painterResource(R.drawable.settings_24), settingsLabel)
                }
            }
        }
    }
}
