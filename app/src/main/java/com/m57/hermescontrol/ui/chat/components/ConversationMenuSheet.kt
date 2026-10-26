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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.sessions.SessionsUiState

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
) {
    val spacing = LocalSpacing.current
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
                    Modifier.pointerInput(Unit) {
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
                        stringResource(R.string.agent_history_drawer_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    )
                    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.content_desc_new_chat)) },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
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
                        val groupError = historyState.groupHistoryUnavailable
                        if (loading) {
                            item {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().testTag("conversation_history_loading"),
                                )
                            }
                        }
                        if (error != null || groupError) {
                            item {
                                Column(Modifier.padding(spacing.md)) {
                                    if (error != null) {
                                        Text(
                                            stringResource(R.string.agent_history_error),
                                            modifier = Modifier.testTag("conversation_history_error"),
                                        )
                                    }
                                    if (groupError) {
                                        Text(
                                            stringResource(R.string.agent_group_history_error),
                                            modifier = Modifier.testTag("conversation_group_history_error"),
                                        )
                                    }
                                    TextButton(
                                        onClick = onRefreshHistory,
                                        modifier = Modifier.testTag("conversation_history_retry"),
                                    ) {
                                        Text(stringResource(R.string.agent_history_retry))
                                    }
                                }
                            }
                        }
                        if (searching) {
                            val results = historyState.searchResults.distinctBy { it.session_id }
                            if (!loading && error == null && !groupError && results.isEmpty()) {
                                item { HistoryEmpty(searching = true) }
                            }
                            items(results, key = { it.session_id }) { result ->
                                HistoryRow(
                                    id = result.session_id,
                                    title =
                                        conversationHistoryTitle(
                                            historyState.sessions.firstOrNull { it.id == result.session_id }?.title,
                                            result.snippet,
                                            stringResource(R.string.agent_history_untitled),
                                        ),
                                    selected = result.session_id == currentSessionId,
                                    onSelect = onSelectSession,
                                    enabled = selectionEnabled,
                                )
                            }
                        } else {
                            if (!loading && error == null && !groupError && historyState.displaySessions.isEmpty()) {
                                item { HistoryEmpty(searching = false) }
                            }
                            items(historyState.displaySessions, key = { it.id }) { session ->
                                HistoryRow(
                                    id = session.id,
                                    title =
                                        conversationHistoryTitle(
                                            session.title,
                                            session.preview,
                                            stringResource(R.string.agent_history_untitled),
                                        ),
                                    selected = session.id == currentSessionId,
                                    onSelect = onSelectSession,
                                    enabled = selectionEnabled,
                                )
                            }
                            if (historyState.hasMore) {
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

@Composable
internal fun HistoryRow(
    id: String,
    title: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        },
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { this.selected = selected }
                .testTag("conversation_session_$id")
                .clickable(enabled = enabled) { onSelect(id) },
    )
}
