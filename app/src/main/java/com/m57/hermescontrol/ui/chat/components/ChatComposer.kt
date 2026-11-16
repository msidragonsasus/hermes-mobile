package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.LocalConversationStyle
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.util.BidiUtils

@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    inputEnabled: Boolean = true,
    sendEnabled: Boolean = true,
    placeholder: String? = null,
    inputModifier: Modifier = Modifier,
    inputTestTag: String = "chat_input",
    sendTestTag: String = "send_button",
    keyboardOptions: KeyboardOptions? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    val displayedValue =
        if (fieldValue.text == value) {
            fieldValue
        } else {
            TextFieldValue(value, TextRange(value.length))
        }
    ChatInputBar(
        inputFieldValue = displayedValue,
        onInputChange = {
            fieldValue = it
            onValueChange(it.text)
        },
        onSend = onSend,
        onMicTap = {},
        isListening = false,
        isAgentTyping = false,
        isConnected = true,
        commandCatalog = remember { CommandCatalog() },
        attachmentsEnabled = false,
        microphoneEnabled = false,
        showModelControls = false,
        showCommandSuggestions = false,
        sendEnabled = inputEnabled && sendEnabled,
        inputTestTag = inputTestTag,
        sendTestTag = sendTestTag,
        inputEnabled = inputEnabled,
        inputModifier = inputModifier,
        inputPlaceholder = placeholder,
        showAttachmentButton = false,
        showSendWhenEmpty = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
    )
}

/**
 * The chat input bar. Idle it is a single slim row — attach, text, mic — and the model and
 * thinking-depth controls sit in their own quiet row beneath the input capsule.
 */
@Composable
fun ChatInputBar(
    inputFieldValue: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    isListening: Boolean,
    isAgentTyping: Boolean,
    isConnected: Boolean,
    commandCatalog: CommandCatalog,
    slashUsageCounts: Map<String, Int> = emptyMap(),
    pendingAttachments: List<Attachment> = emptyList(),
    onCameraTap: () -> Unit = {},
    onImageTap: () -> Unit = {},
    onFileTap: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onPreviewAttachment: (Attachment) -> Unit = {},
    // NEW: composer toolbar wiring
    currentSessionModel: String? = null,
    reasoningLevel: String? = null,
    onModelTap: () -> Unit = {},
    onReasoningTap: (String?) -> Unit = {},
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
    responseStatusUnknown: Boolean = false,
    attachmentsEnabled: Boolean = true,
    microphoneEnabled: Boolean = true,
    showModelControls: Boolean = true,
    showCommandSuggestions: Boolean = true,
    sendEnabled: Boolean = true,
    onStop: (() -> Unit)? = null,
    onImeSend: (() -> Unit)? = null,
    inputTestTag: String = "chat_input",
    sendTestTag: String = "send_button",
    stopTestTag: String = "chat_stop_button",
    modelControlsEnabled: Boolean = true,
    onReasoningPickerTap: (() -> Unit)? = null,
    reasoningDisplayName: String? = null,
    queueOnSend: Boolean = false,
    allowOfflineQueue: Boolean = false,
    onSteer: (() -> Unit)? = null,
    inputEnabled: Boolean = isConnected,
    inputModifier: Modifier = Modifier,
    inputPlaceholder: String? = null,
    showAttachmentButton: Boolean = true,
    showSendWhenEmpty: Boolean = false,
    keyboardOptions: KeyboardOptions? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val style = LocalConversationStyle.current
    val replyFocus = LocalConversationComposerFocus.current
    val spacing = LocalSpacing.current
    val canSend = ChatInputPolicy.canSend(inputFieldValue.text, pendingAttachments, isConnected || allowOfflineQueue)

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // An unconfigured conversation reveals options when the user starts composing.
    val composing = isFocused || inputFieldValue.text.isNotEmpty() || pendingAttachments.isNotEmpty()
    var showReasoning by remember { mutableStateOf(false) }
    var reasoningDismissedAt by remember { mutableStateOf(0L) }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = style.maxWidth)
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            // Floating reasoning slider above the composer. A popup so that any tap outside it
            // closes it; it is not focusable, so the keyboard stays up while it is open.
            Box(Modifier.fillMaxWidth()) {
                if (showReasoning) {
                    Popup(
                        alignment = Alignment.BottomCenter,
                        onDismissRequest = {
                            showReasoning = false
                            reasoningDismissedAt = android.os.SystemClock.uptimeMillis()
                        },
                        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
                    ) {
                        ReasoningLevelPanel(
                            reasoningLevel = reasoningLevel,
                            canDisableReasoning = canDisableReasoning,
                            onSelect = onReasoningTap,
                        )
                    }
                }
            }
            // Commands hidden from the suggestion menu — desktop/CLI-only and
            // TUI-only commands that don't function on mobile (issue #574).
            // Single source of truth: CommandBlocklist.UNSUPPORTED, which is
            // also enforced at dispatch time (issue #576, deliverable #3).
            val hiddenSlashDisplay = CommandBlocklist.UNSUPPORTED
            val commandNames =
                (
                    commandCatalog.pairs.map { it[0] } +
                        listOf("/btw", "/queue", "/fork", "/model", "/new", "/stop")
                ).distinct()
                    .filter { it.lowercase() !in hiddenSlashDisplay }

            androidx.compose.animation.AnimatedVisibility(
                visible =
                    showCommandSuggestions && inputFieldValue.text.startsWith("/") &&
                        !inputFieldValue.text.contains(" "),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                val filteredCommands =
                    ChatInputPolicy.sortSlashSuggestions(
                        commandNames.filter { it.startsWith(inputFieldValue.text, ignoreCase = true) },
                        slashUsageCounts,
                    )
                if (filteredCommands.isNotEmpty()) {
                    androidx.compose.material3.Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = spacing.sm),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 200.dp),
                        ) {
                            items(filteredCommands, key = { it }) { cmd ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            cmd,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = { onInputChange(ChatInputPolicy.commandFieldValue(cmd)) },
                                )
                            }
                        }
                    }
                }
            }

            // Shared attachment strip; images are photo-first thumbnails.
            AnimatedVisibility(
                visible = pendingAttachments.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(pendingAttachments) { index, attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onPreview = { onPreviewAttachment(attachment) },
                            onRemove = { onRemoveAttachment(index) },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().testTag("chat_composer_surface"),
                shape = style.composerShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    // ── Input row: [attach] [text] [mic | send] ──
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.xs),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        // Anchor the popup to its own button, independently of the input field.
                        if (showAttachmentButton) {
                            Box {
                                IconButton(
                                    onClick = { showAttachmentMenu = true },
                                    enabled = isConnected && attachmentsEnabled,
                                    modifier = Modifier.size(style.actionSize).testTag("chat_attach_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.chat_attach_file),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                AttachmentPickerMenu(
                                    expanded = showAttachmentMenu,
                                    onDismiss = { showAttachmentMenu = false },
                                    onPhoto = {
                                        showAttachmentMenu = false
                                        onImageTap()
                                    },
                                    onCamera = {
                                        showAttachmentMenu = false
                                        onCameraTap()
                                    },
                                    onFile = {
                                        showAttachmentMenu = false
                                        onFileTap()
                                    },
                                )
                            }
                        }

                        val placeholderText =
                            inputPlaceholder ?: when {
                                responseStatusUnknown -> {
                                    stringResource(R.string.chat_input_placeholder_status_unknown)
                                }

                                queueOnSend -> {
                                    stringResource(R.string.draft_queue_placeholder)
                                }

                                !isConnected -> {
                                    stringResource(R.string.chat_input_placeholder_not_connected)
                                }

                                onStop == null &&
                                    ChatInputPolicy.showQueuePlaceholder(inputFieldValue.text, isAgentTyping) -> {
                                    stringResource(R.string.chat_input_placeholder_queue)
                                }

                                isAgentTyping -> {
                                    stringResource(R.string.chat_input_placeholder_waiting)
                                }

                                else -> {
                                    stringResource(R.string.chat_input_placeholder_type_message)
                                }
                            }

                        val ambientLayoutDirection = LocalLayoutDirection.current
                        val inputLayoutDirection =
                            remember(inputFieldValue.text, ambientLayoutDirection) {
                                BidiUtils.resolveLayoutDirection(
                                    inputFieldValue.text,
                                    fallback = ambientLayoutDirection,
                                )
                            }
                        val isInputRtl = inputLayoutDirection == LayoutDirection.Rtl

                        CompositionLocalProvider(LocalLayoutDirection provides inputLayoutDirection) {
                            BasicTextField(
                                value = inputFieldValue,
                                onValueChange = onInputChange,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = style.composerMinHeight, max = style.inputMaxHeight)
                                        .then(replyFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .testTag(inputTestTag)
                                        .semantics {
                                            if (inputFieldValue.text.isEmpty()) contentDescription = placeholderText
                                        }.then(inputModifier),
                                enabled = inputEnabled,
                                visualTransformation = visualTransformation,
                                keyboardOptions =
                                    keyboardOptions ?: if (onImeSend !=
                                        null
                                    ) {
                                        KeyboardOptions(
                                            imeAction = ImeAction.Send,
                                        )
                                    } else {
                                        KeyboardOptions.Default
                                    },
                                keyboardActions =
                                    if (onImeSend !=
                                        null
                                    ) {
                                        KeyboardActions(onSend = { onImeSend() })
                                    } else {
                                        KeyboardActions.Default
                                    },
                                textStyle =
                                    MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                                        textDirection = if (isInputRtl) TextDirection.Rtl else TextDirection.Ltr,
                                    ),
                                singleLine = false,
                                maxLines = 5,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                                        contentAlignment =
                                            if (isInputRtl) Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        if (inputFieldValue.text.isEmpty()) {
                                            Text(
                                                text = placeholderText,
                                                style = MaterialTheme.typography.bodyLarge,
                                                textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }

                        // Send replaces the mic as soon as a send is possible — text typed OR an
                        // attachment pending (issue #956).
                        if (canSend && onSteer != null) {
                            IconButton(
                                onClick = onSteer,
                                enabled = sendEnabled,
                                modifier = Modifier.size(style.actionSize).testTag("chat_steer_button"),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.draft_steer),
                                )
                            }
                        }
                        AnimatedContent(
                            targetState = canSend || showSendWhenEmpty,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.8f) + fadeIn())
                                    .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut())
                            },
                            label = "send_toggle",
                        ) { showSend ->
                            if (isAgentTyping && onStop != null) {
                                FilledIconButton(
                                    onClick = onStop,
                                    shapes = IconButtonDefaults.shapes(),
                                    modifier = Modifier.size(style.actionSize).testTag(stopTestTag),
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.group_chat_action_stop),
                                    )
                                }
                            } else if (showSend) {
                                FilledIconButton(
                                    onClick = onSend,
                                    enabled = canSend && sendEnabled,
                                    shapes = IconButtonDefaults.shapes(),
                                    modifier = Modifier.size(style.actionSize).testTag(sendTestTag),
                                ) {
                                    Icon(
                                        imageVector =
                                            if (queueOnSend) Icons.Default.Schedule else Icons.AutoMirrored.Filled.Send,
                                        contentDescription =
                                            stringResource(
                                                if (queueOnSend) R.string.draft_add_queue else R.string.chat_send_desc,
                                            ),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else if (isListening) {
                                FilledIconButton(
                                    onClick = onMicTap,
                                    shapes = IconButtonDefaults.shapes(),
                                    colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError,
                                        ),
                                    modifier = Modifier.size(style.actionSize).testTag("mic_stop_button"),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop listening")
                                }
                            } else {
                                IconButton(
                                    onClick = onMicTap,
                                    enabled = isConnected && microphoneEnabled,
                                    modifier = Modifier.size(style.actionSize).testTag("mic_button"),
                                ) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = "Mic",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Keep configured model controls discoverable without changing the input capsule.
            AnimatedVisibility(
                visible = showModelControls && (composing || currentSessionModel != null || reasoningLevel != null),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ComposerOptionsRow(
                    currentSessionModel = currentSessionModel,
                    reasoningLevel = reasoningLevel,
                    onModelTap = onModelTap,
                    onReasoningTap = {
                        if (onReasoningPickerTap != null) {
                            onReasoningPickerTap()
                            return@ComposerOptionsRow
                        }
                        // The tap that closed the popup from outside also lands on this
                        // button; don't reopen it straight away.
                        val justClosed = android.os.SystemClock.uptimeMillis() - reasoningDismissedAt < 300
                        showReasoning = !showReasoning && !justClosed
                    },
                    supportsReasoning = supportsReasoning,
                    enabled = modelControlsEnabled,
                    reasoningDisplayName = reasoningDisplayName,
                    modifier = Modifier.testTag("chat_composer_toolbar"),
                )
            }
        }
    }
}

/**
 * Reusable attachment chip composable for showing a pending attachment
 * with a remove button.
 */
@Composable
fun AttachmentChip(
    attachment: Attachment,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalConversationStyle.current
    if (attachment.isImage) {
        Box(modifier.size(style.pendingImageSize)) {
            GifImageThumbnail(
                model = attachment.uri,
                contentDescription = attachment.name,
                isGif = attachment.isGif,
                onClick = onPreview,
                compact = true,
            )
            AttachmentRemoveButton(
                name = attachment.name,
                onRemove = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
                overImage = true,
            )
        }
        return
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = attachment.name,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            AttachmentRemoveButton(attachment.name, onRemove)
        }
    }
}

@Composable
private fun AttachmentRemoveButton(
    name: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    overImage: Boolean = false,
) {
    IconButton(
        onClick = onRemove,
        modifier = modifier.size(LocalConversationStyle.current.actionSize).testTag("attachment_remove"),
    ) {
        Surface(
            shape = CircleShape,
            color = if (overImage) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
            contentColor =
                if (overImage) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, stringResource(R.string.chat_attach_remove_named, name), Modifier.size(18.dp))
            }
        }
    }
}
