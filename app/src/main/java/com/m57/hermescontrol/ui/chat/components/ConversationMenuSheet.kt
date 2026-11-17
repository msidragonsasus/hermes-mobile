package com.m57.hermescontrol.ui.chat.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.domain.hermes.SessionActivity
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import kotlinx.coroutines.delay

/**
 * Conversation operations only; app-wide management has a separate shell entry point.
 *
 * Opened from the hamburger in the top-left, so it slides in from the left edge like a
 * navigation drawer (a bottom sheet under a "menu" icon reads as the wrong gesture).
 *
 * Visibility is driven only by [visible]. While it is `false` nothing of this composable is
 * in the tree, so it cannot sit invisibly over the screen and take touches away from the
 * chat input (a closed `ModalNavigationDrawer`, or a second dialog window, both can).
 * Tapping the scrim, swiping the sheet left or pressing back calls [onDismiss].
 */
@Composable
fun ConversationMenuSheet(
    currentModel: String?,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onModel: () -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean = true,
    historyState: SessionsUiState = SessionsUiState(),
    currentSessionId: String? = null,
    onSelectSession: (String) -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onRefreshHistory: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    selectionEnabled: Boolean = true,
    historyActivity: Map<String, SessionActivity> = emptyMap(),
    historyRechecking: Set<String> = emptySet(),
    historyStatusUnverified: Boolean = false,
    drawerModifier: Modifier = Modifier,
    title: String? = null,
    primaryLabel: String? = null,
    primaryIcon: ImageVector = Icons.Default.Add,
) {
    val spacing = LocalSpacing.current
    val untitled = stringResource(R.string.agent_history_untitled)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val swipeCloseDistance = with(LocalDensity.current) { 48.dp.toPx() }
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .testTag("conversation_menu_scrim")
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterStart),
            enter =
                slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()) { -it },
            exit =
                slideOutHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()) { -it },
        ) {
            ModalDrawerSheet(
                modifier =
                    drawerModifier.pointerInput(Unit) {
                        var travelled = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = { if (travelled < -swipeCloseDistance) currentOnDismiss() },
                            onDragCancel = { travelled = 0f },
                        ) { _, dragAmount -> travelled += dragAmount }
                    },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Text(
                        title ?: stringResource(R.string.agent_history_drawer_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
                    ListItem(
                        headlineContent = { Text(primaryLabel ?: stringResource(R.string.content_desc_new_chat)) },
                        leadingContent = { Icon(primaryIcon, contentDescription = null) },
                        colors = transparent,
                        modifier =
                            Modifier
                                .testTag(
                                    "conversation_new",
                                ).clickable(enabled = selectionEnabled, onClick = onNewConversation),
                    )
                    TextField(
                        value = historyState.searchQuery,
                        onValueChange = onSearchQuery,
                        placeholder = { Text(stringResource(R.string.agent_history_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (historyState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQuery("") }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.agent_history_clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = MaterialTheme.shapes.extraLarge,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = spacing.md,
                                ).heightIn(min = 48.dp)
                                .testTag("conversation_search"),
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("conversation_history_list"),
                    ) {
                        val searching = historyState.isSearchMode
                        val loading = if (searching) historyState.isSearching else historyState.isLoading
                        val error = if (searching) historyState.searchError else historyState.errorMessage
                        if (loading) {
                            item {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().testTag("conversation_history_loading"),
                                )
                            }
                        }
                        if (error != null) {
                            item {
                                Column(Modifier.padding(spacing.md)) {
                                    Text(
                                        stringResource(R.string.agent_history_error),
                                        modifier = Modifier.testTag("conversation_history_error"),
                                    )
                                    TextButton(
                                        onClick = onRefreshHistory,
                                        modifier = Modifier.testTag("conversation_history_retry"),
                                    ) {
                                        Text(stringResource(R.string.agent_history_retry))
                                    }
                                }
                            }
                        }
                        if (historyStatusUnverified) {
                            item(key = "conversation_status_notice") { HistoryStatusNotice() }
                        }
                        val rows =
                            conversationHistoryRows(
                                historyState,
                                historyActivity,
                                untitled,
                                historyRechecking,
                            )
                        if (!loading && error == null && rows.isEmpty()) {
                            item { HistoryEmpty(searching = searching) }
                        }
                        items(rows, key = { it.id }) { row ->
                            HistoryRow(
                                id = row.id,
                                title = row.title,
                                selected = row.id == currentSessionId,
                                onSelect = onSelectSession,
                                enabled = selectionEnabled,
                                activity = row.activity,
                                rechecking = row.rechecking,
                            )
                        }
                        if (!searching && historyState.hasMore) {
                            item {
                                TextButton(onClick = onLoadMore, enabled = !historyState.isLoadingMore) {
                                    Text(stringResource(R.string.agent_history_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmpty(searching: Boolean) {
    Text(
        stringResource(if (searching) R.string.agent_history_no_results else R.string.agent_history_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(LocalSpacing.current.lg).testTag("conversation_history_empty"),
    )
}

/**
 * One quiet line explaining that row statuses cannot be confirmed right now. A brief gap (the
 * first read after reopening) is not worth a layout change, so it appears only if it persists.
 */
@Composable
private fun HistoryStatusNotice() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1_500)
        shown = true
    }
    if (!shown) return
    val spacing = LocalSpacing.current
    Text(
        stringResource(R.string.agent_history_status_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("conversation_status_notice"),
    )
}

@Composable
internal fun HistoryRow(
    id: String,
    title: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    activity: SessionActivity? = null,
    rechecking: Boolean = false,
) {
    val label = activity?.let { stringResource(it.labelRes()) }
    val stateLabel =
        if (label != null &&
            rechecking
        ) {
            stringResource(R.string.agent_history_status_rechecking, label)
        } else {
            label
        }
    ListItem(
        headlineContent = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("conversation_title_$id"),
            )
        },
        // Always present, even when quiet, so every title ends at the same boundary.
        trailingContent = { HistoryStatusSlot(id, activity, rechecking) },
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics {
                    this.selected = selected
                    if (stateLabel != null) stateDescription = stateLabel
                }.testTag("conversation_session_$id")
                .clickable(enabled = enabled) { onSelect(id) },
    )
}

/**
 * Fixed 24dp slot; the row's state description carries the meaning, color only supports it.
 * Busy work spins (muted while its still-fresh status is rechecked); an unconfirmed status is a
 * quiet hollow ring explained by [HistoryStatusNotice], never a lone sync-problem glyph.
 */
@Composable
private fun HistoryStatusSlot(
    id: String,
    activity: SessionActivity?,
    rechecking: Boolean,
) {
    val statusColors = LocalHermesStatusColors.current
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(24.dp).testTag("conversation_status_$id"), contentAlignment = Alignment.Center) {
        val icon =
            when (activity) {
                null -> {
                    return@Box
                }

                SessionActivity.RUNNING, SessionActivity.STARTING, SessionActivity.BACKGROUND -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = if (rechecking) quiet else MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    return@Box
                }

                SessionActivity.UNCERTAIN -> {
                    Icon(
                        Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp).testTag("conversation_status_unconfirmed_$id"),
                    )
                    return@Box
                }

                SessionActivity.WAITING_APPROVAL -> {
                    Icons.Outlined.GppMaybe to statusColors.warning
                }

                SessionActivity.NEEDS_INPUT -> {
                    Icons.Outlined.QuestionAnswer to statusColors.warning
                }

                SessionActivity.WAITING -> {
                    Icons.Outlined.HourglassTop to quiet
                }
            }
        Icon(icon.first, contentDescription = null, tint = icon.second, modifier = Modifier.size(20.dp))
    }
}

private fun SessionActivity.labelRes(): Int =
    when (this) {
        SessionActivity.RUNNING -> R.string.activity_running
        SessionActivity.STARTING -> R.string.agent_history_status_starting
        SessionActivity.BACKGROUND -> R.string.agent_history_status_background
        SessionActivity.WAITING_APPROVAL -> R.string.activity_waiting_approval
        SessionActivity.NEEDS_INPUT -> R.string.activity_needs_input
        SessionActivity.WAITING -> R.string.agent_history_status_waiting
        SessionActivity.UNCERTAIN -> R.string.agent_history_status_unconfirmed
    }
