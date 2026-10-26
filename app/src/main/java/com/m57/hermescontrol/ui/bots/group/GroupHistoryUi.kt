package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.GroupChatKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.AgentGroupsKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.bots.CreateGroupChatDialog
import com.m57.hermescontrol.ui.bots.GroupInfo
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class GroupHistoryUiState(
    val isLoading: Boolean = true,
    val result: GroupHistoryResult = GroupHistoryResult(),
)

@Composable
internal fun rememberGroupHistory(
    groups: List<GroupInfo>,
    revision: Int = 0,
): GroupHistoryUiState {
    val server by AuthManager.baseUrlFlow.collectAsStateWithLifecycle()
    val connection by AuthManager.selectedProfileFlow.collectAsStateWithLifecycle()
    val profile by AuthManager.activeProfileId.collectAsStateWithLifecycle()
    val scope = listOf(server, connection, profile).joinToString("\u0000")
    val context = LocalContext.current.applicationContext
    val store = remember(context) { EncryptedGroupConversationStore(context) }
    var state by remember(scope) { mutableStateOf(GroupHistoryUiState()) }
    LaunchedEffect(scope, groups, revision) {
        state = GroupHistoryUiState()
        val result =
            withContext(Dispatchers.IO) {
                val cached = readGroupHistory(groups, scope, store)
                try {
                    val remote =
                        ApiClient.hermesApi.groupConversations().conversations.map { entry ->
                            GroupHistoryEntry(
                                groupName = entry.groupName,
                                conversationId = entry.id,
                                groupLabel = groups.find { it.name == entry.groupName }?.displayName ?: entry.groupName,
                                conversationTitle = entry.title,
                                lastActivity = entry.updatedAt,
                            )
                        }
                    cached.copy(
                        entries = mergeGroupHistory(remote, cached.entries),
                    )
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    cached.copy(failedGroups = cached.failedGroups + "server")
                }
            }
        state = GroupHistoryUiState(isLoading = false, result = result)
    }
    return state
}

internal fun openGroupHistory(entry: GroupHistoryEntry) {
    NavigationController.navigateTo(GroupChatKey(entry.groupName, conversationId = entry.conversationId))
}

@Composable
internal fun GroupNewConversationDialog(
    groups: List<GroupInfo>,
    onCreateGroup: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (GroupInfo) -> Unit = { group ->
        NavigationController.navigateTo(GroupChatKey(group.name, newConversationId = UUID.randomUUID().toString()))
    },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_new_conversation)) },
        text = {
            Column {
                Text("グループを選択")
                LazyColumn(Modifier.heightIn(max = 380.dp).testTag("group_new_picker")) {
                    items(groups, key = { it.name }) { group ->
                        ListItem(
                            headlineContent = { Text(group.displayName) },
                            supportingContent = { Text(group.members.joinToString("・") { it.effectiveTitle }) },
                            modifier =
                                Modifier.fillMaxWidth().testTag("group_new_${group.name}").clickable {
                                    onDismiss()
                                    onSelect(group)
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateGroup, modifier = Modifier.testTag("group_create_from_picker")) {
                Text(stringResource(R.string.bots_create_group_title))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun GroupNewConversationFlow(
    bots: BotsViewModel,
    onDismiss: () -> Unit,
) {
    val state by bots.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(bots) { bots.loadBots(isRefresh = true) }
    if (creating) {
        CreateGroupChatDialog(
            availableBots = state.displayProfiles,
            onDismiss = onDismiss,
            onCreateGroup = { name, members ->
                bots.createGroupChat(name, members) {
                    onDismiss()
                    NavigationController.navigateTo(
                        GroupChatKey(name, newConversationId = UUID.randomUUID().toString()),
                    )
                }
            },
        )
    } else {
        GroupNewConversationDialog(
            groups = state.allGroups,
            onCreateGroup = { creating = true },
            onDismiss = onDismiss,
        )
    }
}

/** The same drawer, search field, rows and selection state used by ordinary agent history. */
@Composable
internal fun GroupHistoryDrawer(
    currentGroupName: String,
    currentConversationId: String,
    onDismiss: () -> Unit,
    bots: BotsViewModel =
        viewModel(key = "group-history-${com.m57.hermescontrol.data.local.AuthManager.uiPreferenceScope()}") {
            BotsViewModel()
        },
) {
    val botsState by bots.uiState.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    val history = rememberGroupHistory(botsState.allGroups, revision)
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(bots) { bots.loadBots(isRefresh = true) }
    val untitled = stringResource(R.string.group_new_conversation)
    val entries = history.result.entries
    val filtered = entries.filter { it.label(untitled).contains(query.trim(), ignoreCase = true) }
    val error = botsState.errorMessage ?: if (history.result.failedGroups.isNotEmpty()) "履歴を読み込めませんでした" else null
    val loading = botsState.isLoading || botsState.isRefreshing || history.isLoading
    ConversationMenuSheet(
        currentModel = null,
        onHistory = {
            onDismiss()
            NavigationController.navigateTo(AgentGroupsKey())
        },
        onNewConversation = { choosing = true },
        onModel = {},
        onDismiss = onDismiss,
        currentSessionId = "$currentGroupName\u0000$currentConversationId",
        historyState =
            SessionsUiState(
                isLoading = loading,
                isSearching = loading,
                errorMessage = error,
                searchError = error,
                searchQuery = query,
                sessions = filtered.map { SessionInfo(id = it.key, title = it.label(untitled)) },
                searchResults = filtered.map { SessionSearchResult(session_id = it.key, snippet = it.label(untitled)) },
            ),
        onSearchQuery = { query = it },
        onSelectSession = { id ->
            entries.find { it.key == id }?.let {
                onDismiss()
                openGroupHistory(it)
            }
        },
        onRefreshHistory = {
            revision++
            bots.loadBots(isRefresh = true)
        },
    )
    if (choosing) {
        GroupNewConversationDialog(
            groups = botsState.allGroups,
            onCreateGroup = {
                choosing = false
                creating = true
            },
            onDismiss = { choosing = false },
            onSelect = { group ->
                onDismiss()
                NavigationController.navigateTo(
                    GroupChatKey(group.name, newConversationId = UUID.randomUUID().toString()),
                )
            },
        )
    }
    if (creating) {
        CreateGroupChatDialog(
            availableBots = botsState.displayProfiles,
            onDismiss = { creating = false },
            onCreateGroup = { name, members ->
                bots.createGroupChat(name, members) {
                    creating = false
                    onDismiss()
                    NavigationController.navigateTo(
                        GroupChatKey(name, newConversationId = UUID.randomUUID().toString()),
                    )
                }
            },
        )
    }
}
