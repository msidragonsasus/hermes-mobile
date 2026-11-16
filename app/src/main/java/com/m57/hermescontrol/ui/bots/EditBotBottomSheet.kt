package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.theme.parseHexColor
import com.m57.hermescontrol.ui.common.BotAvatar

internal val BOT_AVATAR_SHAPES = listOf("circle", "square", "rounded", "hexagon")

/** Avatar symbols; null keeps the name's initials. Keys resolve through [resolveAvatarIcon]. */
internal val BOT_AVATAR_ICONS = listOf(null, "robot", "brain", "code", "tool", "science", "bolt", "sensors", "plugin")
internal val BOT_AVATAR_COLORS =
    listOf(
        "#4F46E5", // Indigo
        "#2563EB", // Blue
        "#0D9488", // Teal
        "#16A34A", // Green
        "#D97706", // Amber
        "#EA580C", // Orange
        "#DC2626", // Red
        "#DB2777", // Pink
        "#9333EA", // Purple
        "#4B5563", // Gray
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBotBottomSheet(
    bot: ProfileInfo,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, shape: String, color: String, icon: String?) -> Unit,
    onDelete: () -> Unit,
    onExecutionSettings: (() -> Unit)? = null,
) {
    val meta = bot.botMeta()
    var title by remember { mutableStateOf(meta?.title ?: bot.effectiveTitle) }
    var description by remember { mutableStateOf(meta?.description ?: bot.effectiveDescription) }
    var selectedShape by remember { mutableStateOf(meta?.avatar?.shape ?: "circle") }
    var selectedColor by remember { mutableStateOf(meta?.avatar?.color ?: BOT_AVATAR_COLORS[0]) }
    var selectedIcon by remember { mutableStateOf(meta?.avatar?.icon) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.bots_edit_delete_confirm_title, bot.effectiveTitle),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.bots_edit_delete_confirm_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.bots_edit_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.bots_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Live Avatar Preview Header
            onExecutionSettings?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Text("モデル（model）・推論強度（effort）の設定")
                }
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                BotAvatar(
                    name = bot.name,
                    avatar =
                        BotAvatarMeta(
                            shape = selectedShape,
                            color = selectedColor,
                            icon = selectedIcon,
                        ),
                    size = 64.dp,
                    showPresence = false,
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.bots_create_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.bots_create_desc_label)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.bots_avatar_icon),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_ICONS.forEach { icon ->
                    Box(
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .clickable { selectedIcon = icon }
                                .then(
                                    if (selectedIcon == icon) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ).padding(3.dp)
                                .testTag("bot_icon_option_${icon ?: "initials"}"),
                    ) {
                        BotAvatar(
                            name = title.ifBlank { bot.name },
                            avatar = BotAvatarMeta(shape = "circle", color = selectedColor, icon = icon),
                            size = 36.dp,
                            showPresence = false,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.bots_create_avatar_shape),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_SHAPES.forEach { shape ->
                    FilterChip(
                        selected = selectedShape == shape,
                        onClick = { selectedShape = shape },
                        label = { Text(shape.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.bots_create_avatar_color),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_COLORS.take(5).forEach { hex ->
                    val color = parseHexColor(hex, Color.Unspecified)
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hex }
                                .then(
                                    if (selectedColor == hex) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOT_AVATAR_COLORS.drop(5).forEach { hex ->
                    val color = parseHexColor(hex, Color.Unspecified)
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { selectedColor = hex }
                                .then(
                                    if (selectedColor == hex) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    onSave(title.trim(), description.trim(), selectedShape, selectedColor, selectedIcon)
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.bots_edit_save))
            }

            if (bot.name != "default") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.bots_edit_delete))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
