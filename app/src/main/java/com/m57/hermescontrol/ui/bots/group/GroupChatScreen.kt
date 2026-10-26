package com.m57.hermescontrol.ui.bots.group

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.AgentModeTabs
import com.m57.hermescontrol.agents.ConversationTitleSelector
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.ConversationHeaderActions
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.components.tailContentKey
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import com.m57.hermescontrol.ui.common.AutoScrollingTitleText
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.util.BidiUtils
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel

@Composable
fun GroupChatScreen(
    groupName: String,
    conversationId: String? = null,
    newConversationId: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupChatViewModel = groupConversationViewModel(),
    bots: BotsViewModel =
        composeViewModel(
            key = "group-history-${AuthManager.uiPreferenceScope()}",
        ) {
            BotsViewModel()
        },
    conversationReturn: NavKey = ChatScreen,
) {
    val state by viewModel.uiState.collectAsState()
    val botsState by bots.uiState.collectAsState()
    LaunchedEffect(bots) { bots.loadBots(isRefresh = true) }
    val readerId = remember(viewModel) { UUID.randomUUID().toString() }
    DisposableEffect(viewModel, readerId) {
        onDispose { viewModel.leaveGroup(viewModel.uiState.value.groupName, readerId) }
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val scrollController = rememberChatScrollController(listState, scrollScope)
    var inputFieldValue by rememberSaveable(state.conversationId, state.groupName, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    var choosingGroup by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var groupNameDraft by remember(state.displayName) { mutableStateOf(state.displayName) }
    var showConversations by remember { mutableStateOf(false) }
    var diagnosticMessageId by remember(state.groupName, state.conversationId) { mutableStateOf<String?>(null) }
    val diagnosticClipboard = LocalClipboard.current
    diagnosticMessageId?.let { messageId ->
        val snapshot = viewModel.sendDiagnostics(messageId)
        GroupSendDiagnosticDialog(
            snapshot = snapshot,
            onCopy = { choice ->
                // Recheck the selected post/scope at the explicit copy, including after a switch.
                val current = viewModel.sendDiagnostics(messageId)
                if (current?.scopeTag == snapshot?.scopeTag && choice in current?.choices.orEmpty()) {
                    diagnosticClipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(null, choice.diagnostic.copyText())),
                    )
                    true
                } else {
                    false
                }
            },
            onDismiss = { diagnosticMessageId = null },
        )
    }
    val openingGroup = groupName.isNotBlank() && state.groupName != groupName
    val canSend =
        !openingGroup && state.groupName.isNotBlank() && !state.isLoading &&
            !state.isReconciling && !state.serverPending &&
            state.sendIssue != GroupSendIssue.STORAGE_UNAVAILABLE &&
            state.conversations.find { it.id == state.conversationId }?.turns.orEmpty().none {
                it.delivery == GroupDelivery.UNKNOWN || it.delivery == GroupDelivery.ACCEPTED
            }

    LaunchedEffect(inputFieldValue.text, state.conversationId) {
        if (state.conversationId.isNotEmpty()) {
            viewModel.updateDraft(
                inputFieldValue.text,
                readerId,
                state.conversationId,
            )
        }
    }

    val handleSend = {
        val text = inputFieldValue.text
        if ((canSend || isGroupStopCommand(text)) && !state.isSavingGroupSettings && text.isNotBlank() &&
            (!state.isSending || isGroupStopCommand(text))
        ) {
            scrollController.jumpToBottom()
            viewModel.sendMessage(text)
            inputFieldValue = TextFieldValue("")
        }
    }

    LaunchedEffect(viewModel, readerId, groupName, conversationId, newConversationId) {
        val name = groupName.ifBlank { viewModel.uiState.value.groupName }
        if (name.isNotBlank()) viewModel.openConversation(name, conversationId, newConversationId, readerId)
    }

    LaunchedEffect(Unit) {
        scrollController.observeUserScrollPosition()
    }

    val streamingMessage = state.messages.find { it.isStreaming }
    val tailKey =
        remember(
            state.messages.size,
            streamingMessage?.id,
            streamingMessage?.text?.length,
            state.activeSpeaker,
        ) {
            tailContentKey(
                messages = state.messages,
                streamingMessage = streamingMessage?.text,
                isThinking = state.activeSpeaker != null,
                subagentIndicators = emptyList<Any>(),
                clarifyRequest = null,
            )
        }

    LaunchedEffect(tailKey) {
        scrollController.onTailChanged(
            tailKey = tailKey,
            messageCount = state.messages.size,
        )
    }

    HermesScaffold(
        pinTopBar = true,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConversationTitleSelector(
                    title = state.displayName.ifBlank { "グループ" },
                    onClick = { choosingGroup = true },
                )
                AutoScrollingTitleText(
                    text =
                        state.conversations
                            .find { it.id == state.conversationId }
                            ?.title
                            .orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        navigationIcon =
            NavIcon.Menu(
                onOpen = { showConversations = true },
                contentDescription = stringResource(R.string.chat_open_conversation_menu),
                tonal = true,
            ),
        drawerGesturesEnabled = false,
        showShellMenu = false,
        overlay = {
            if (showConversations) {
                GroupHistoryDrawer(
                    currentGroupName = state.groupName,
                    currentConversationId = state.conversationId,
                    onDismiss = { showConversations = false },
                    bots = bots,
                )
            }
        },
        actions = {
            ConversationHeaderActions(
                onNew = { choosingGroup = true },
                onSettings = {
                    groupNameDraft = state.displayName
                    showSettingsDialog = true
                },
                settingsEnabled = state.groupName.isNotBlank() && !state.isSending && !state.isSavingGroupSettings,
                settingsTag = "group_chat_settings_button",
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        var travelled = 0f
                        detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, amount ->
                            travelled += amount
                            if (travelled > 64.dp.toPx() && !showConversations) {
                                change.consume()
                                showConversations = true
                            }
                        }
                    }.imePadding(),
        ) {
            AgentModeTabs(groups = true, conversation = conversationReturn)
            if (state.groupName.isNotBlank() && (!canSend || state.errorMessage != null) &&
                state.sendIssue != GroupSendIssue.STORAGE_UNAVAILABLE
            ) {
                TextButton(
                    onClick = viewModel::reconcilePending,
                    enabled = !state.isReconciling && !state.isSending,
                    modifier = Modifier.testTag("group_reconcile_button"),
                ) {
                    Text(
                        stringResource(
                            if (state.isReconciling) R.string.group_reconciling else R.string.group_reconcile,
                        ),
                    )
                }
            }
            state.reconciliationStatus?.let { status ->
                val resource =
                    when (status) {
                        GroupReconciliationStatus.OFFLINE -> {
                            R.string.group_check_offline
                        }

                        GroupReconciliationStatus.READ_UNAVAILABLE -> {
                            R.string.group_check_read_unavailable
                        }

                        GroupReconciliationStatus.AUTH_REQUIRED -> {
                            R.string.group_check_auth_required
                        }

                        GroupReconciliationStatus.SESSION_NOT_FOUND -> {
                            R.string.group_check_session_missing
                        }

                        GroupReconciliationStatus.PROFILE_NOT_FOUND -> {
                            R.string.group_check_profile_missing
                        }

                        GroupReconciliationStatus.HISTORY_ENDPOINT_UNAVAILABLE -> {
                            R.string.group_check_endpoint_unavailable
                        }

                        GroupReconciliationStatus.SESSION_ID_MISMATCH -> {
                            R.string.group_check_id_mismatch
                        }

                        GroupReconciliationStatus.HISTORY_INCOMPLETE -> {
                            R.string.group_check_history_incomplete
                        }

                        GroupReconciliationStatus.SESSION_ID_MISSING -> {
                            R.string.group_check_id_missing
                        }

                        GroupReconciliationStatus.REQUEST_NOT_FOUND -> {
                            R.string.group_check_request_missing
                        }

                        GroupReconciliationStatus.ACCEPTED_NOT_COMPLETE -> {
                            R.string.group_check_accepted
                        }

                        GroupReconciliationStatus.COMPLETED -> {
                            R.string.group_check_completed
                        }

                        GroupReconciliationStatus.NO_PENDING -> {
                            R.string.group_check_none
                        }

                        GroupReconciliationStatus.CONNECTION_CHANGED -> {
                            R.string.group_check_connection_changed
                        }
                    }
                Text(
                    stringResource(resource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                            ).testTag("group_reconciliation_status"),
                )
            }
            state.sendRpcErrorCode?.let { code ->
                Text(
                    stringResource(R.string.group_send_rpc_code, code),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("group_send_rpc_code"),
                )
            }
            val issueText =
                state.sendIssue?.let { issue ->
                    stringResource(
                        when (issue) {
                            GroupSendIssue.UNAVAILABLE -> R.string.group_send_unavailable
                            GroupSendIssue.INVALID_MEMBERS -> R.string.group_send_invalid_members
                            GroupSendIssue.MEMBERS_MISSING -> R.string.group_send_members_missing
                            GroupSendIssue.PROFILE_LIST_UNAVAILABLE -> R.string.group_send_profiles_unavailable
                            GroupSendIssue.LEASE_HELD -> R.string.group_send_lease_held
                            GroupSendIssue.LEASE_UNCONFIRMED -> R.string.group_send_lease_unconfirmed
                            GroupSendIssue.MEMBER_FAILED -> R.string.group_send_member_failed
                            GroupSendIssue.UNKNOWN -> R.string.group_send_unknown
                            GroupSendIssue.CONNECTION_CHANGED -> R.string.group_send_connection_changed
                            GroupSendIssue.STORAGE_UNAVAILABLE -> R.string.group_send_storage_unavailable
                        },
                    )
                }
                    ?: if (state.settingsSaveFailed) {
                        stringResource(
                            R.string.group_order_save_failed,
                        )
                    } else {
                        state.errorMessage
                    }
            issueText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ).testTag("group_chat_send_issue"),
                )
            }
            when {
                openingGroup ||
                    (
                        state.groupName.isNotBlank() &&
                            (state.isLoading || (state.isReconciling && state.messages.isEmpty()))
                    ) -> {
                    LoadingState(Modifier.weight(1f))
                }

                state.groupName.isBlank() -> {
                    GroupLanding(
                        groups = botsState.allGroups,
                        loading = botsState.isLoading || botsState.isRefreshing,
                        error = botsState.errorMessage,
                        onSelect = { group -> viewModel.openConversation(group.name, null, null, readerId) },
                        onCreate = { choosingGroup = true },
                        onRefresh = { bots.loadBots(isRefresh = true) },
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    )
                }

                state.messages.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.group_conversation_empty),
                        modifier = Modifier.weight(1f).testTag("group_conversation_empty"),
                    )
                }

                else -> {
                    val currentDensity = LocalDensity.current
                    val chatFontScale = LocalChatFontScale.current
                    val chatDensity =
                        remember(currentDensity, chatFontScale) {
                            Density(
                                density = currentDensity.density,
                                fontScale = currentDensity.fontScale * chatFontScale,
                            )
                        }

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        CompositionLocalProvider(LocalDensity provides chatDensity) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(
                                    items = state.messages,
                                    key = { it.id },
                                ) { message ->
                                    GroupMessageCard(
                                        message = message,
                                        onDiagnostics = { diagnosticMessageId = message.id },
                                    )
                                }

                                state.activeSpeaker?.let { speaker ->
                                    item(key = "active_speaker") {
                                        val thinkingText = stringResource(R.string.group_chat_thinking, speaker)
                                        val isThinkingRtl = remember(thinkingText) { BidiUtils.isRtlText(thinkingText) }
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = thinkingText,
                                                style =
                                                    MaterialTheme.typography.bodySmall.copy(
                                                        textDirection =
                                                            if (isThinkingRtl) {
                                                                TextDirection.Rtl
                                                            } else {
                                                                TextDirection.Ltr
                                                            },
                                                    ),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ChatScrollToBottomFab(
                            show = !scrollController.isFollowingBottom && state.messages.isNotEmpty(),
                            pendingCount = scrollController.pendingCount,
                            onScrollToBottom = { scrollController.resumeFollowing() },
                        )
                    }
                }
            }

            // ── MENTION SUGGESTION CHIP BAR (Horizontal scrollable chips) ──
            val mentionQuery =
                remember(inputFieldValue.text, inputFieldValue.selection.end) {
                    ChatInputPolicy.extractMentionQuery(
                        inputFieldValue.text,
                        inputFieldValue.selection.end,
                    )
                }

            val groupBots =
                remember(state.members) {
                    state.members.distinctBy { it.name.lowercase() }
                }

            val showAllOption =
                remember(mentionQuery, groupBots) {
                    if (mentionQuery == null || groupBots.isEmpty()) {
                        false
                    } else {
                        "all".startsWith(mentionQuery, ignoreCase = true) ||
                            "everyone".startsWith(mentionQuery, ignoreCase = true)
                    }
                }

            val filteredBots =
                remember(mentionQuery, groupBots) {
                    if (mentionQuery == null) {
                        emptyList()
                    } else {
                        groupBots.filter { bot ->
                            bot.name.startsWith(mentionQuery, ignoreCase = true) ||
                                bot.effectiveTitle.contains(mentionQuery, ignoreCase = true)
                        }
                    }
                }

            AnimatedVisibility(
                visible = mentionQuery != null && (showAllOption || filteredBots.isNotEmpty()),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .testTag("group_chat_mention_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showAllOption) {
                        item(key = "mention_all") {
                            SuggestionChip(
                                onClick = {
                                    inputFieldValue = ChatInputPolicy.applyMention(inputFieldValue, "all")
                                },
                                label = {
                                    Text(
                                        text = "@all",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Filled.Groups,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("mention_chip_all"),
                            )
                        }
                    }

                    items(filteredBots, key = { it.name }) { bot ->
                        SuggestionChip(
                            onClick = {
                                inputFieldValue = ChatInputPolicy.applyMention(inputFieldValue, bot.name)
                            },
                            label = {
                                Text(
                                    text = "@${bot.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            icon = {
                                BotAvatar(
                                    name = bot.name,
                                    avatar = bot.botMeta()?.avatar,
                                    size = 18.dp,
                                    showPresence = false,
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("mention_chip_${bot.name}"),
                        )
                    }
                }
            }

            ChatInputBar(
                inputFieldValue = inputFieldValue,
                onInputChange = { inputFieldValue = it },
                onSend = handleSend,
                onMicTap = {},
                isListening = false,
                isAgentTyping = state.isSending,
                isConnected = true,
                commandCatalog =
                    CommandCatalog(),
                attachmentsEnabled = false,
                microphoneEnabled = false,
                showModelControls = false,
                showCommandSuggestions = false,
                sendEnabled = canSend && !state.isLoading && !state.isSavingGroupSettings,
                onStop = viewModel::stopGeneration,
                onImeSend = handleSend,
                inputTestTag = "group_chat_input",
                sendTestTag = "group_chat_send_button",
                stopTestTag = "group_chat_stop_button",
            )
        }
    }

    if (choosingGroup) {
        GroupNewConversationFlow(bots = bots, onDismiss = { choosingGroup = false })
    }

    if (showSettingsDialog) {
        GroupChatSettingsDialog(
            currentGroupName = groupNameDraft,
            onGroupNameChange = { groupNameDraft = it },
            currentMaxMessages = state.maxBotMessages,
            currentMaxPasses = state.maxContinuationPasses,
            currentSystemPrompt = state.systemPrompt,
            members = state.members,
            currentSpeakingOrder = state.speakingOrder,
            currentSummaryMember = state.finalSummaryMember,
            currentDiscussionRounds = state.discussionRounds,
            onExecutionSettings = { member ->
                showSettingsDialog = false
                com.m57.hermescontrol.NavigationController.navigateTo(
                    com.m57.hermescontrol.agents
                        .BotDefaultsKey(member),
                )
            },
            onDismiss = { showSettingsDialog = false },
            onSave = { maxMsgs, maxPasses, prompt, order, summary, rounds ->
                viewModel.updateGroupLimits(maxMsgs, maxPasses, prompt, order, summary, rounds, groupNameDraft)
                showSettingsDialog = false
            },
        )
    }
}

@Composable
internal fun GroupChatSettingsDialog(
    currentGroupName: String? = null,
    onGroupNameChange: (String) -> Unit = {},
    currentMaxMessages: Int,
    currentMaxPasses: Int,
    currentSystemPrompt: String?,
    members: List<ProfileInfo>,
    currentSpeakingOrder: List<String>?,
    currentSummaryMember: String?,
    currentDiscussionRounds: Int = DEFAULT_DISCUSSION_ROUNDS,
    onExecutionSettings: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String?, List<String>?, String?, Int) -> Unit,
) {
    var discussionRounds by remember(currentDiscussionRounds) {
        mutableFloatStateOf(currentDiscussionRounds.coerceIn(1, MAX_ALLOWED_DISCUSSION_ROUNDS).toFloat())
    }
    var maxMessages by remember(currentMaxMessages) { mutableStateOf(currentMaxMessages.toFloat()) }
    var maxPasses by remember(currentMaxPasses) { mutableStateOf(currentMaxPasses.toFloat()) }
    var systemPrompt by remember(currentSystemPrompt) { mutableStateOf(currentSystemPrompt.orEmpty()) }
    var showHighLimitsConfirmation by remember { mutableStateOf(false) }
    var controlledOrder by remember(currentSpeakingOrder) { mutableStateOf(currentSpeakingOrder != null) }
    var orderedMembers by remember(members, currentSpeakingOrder) {
        mutableStateOf(orderedGroupMembers(members, currentSpeakingOrder.orEmpty()))
    }
    var summaryMember by remember(currentSummaryMember) { mutableStateOf(currentSummaryMember) }

    val isHighLimit =
        maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES ||
            maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.group_chat_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                currentGroupName?.let { name ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = onGroupNameChange,
                        label = { Text(stringResource(R.string.bots_create_group_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("group_name_input"),
                    )
                }
                onExecutionSettings?.let { open ->
                    Text("参加botの既定モデル・推論強度", fontWeight = FontWeight.SemiBold)
                    Text("このグループの新しい会話で使う設定です。", style = MaterialTheme.typography.bodySmall)
                    members.forEach { member ->
                        TextButton(
                            onClick = { open(member.name) },
                            modifier = Modifier.testTag("group_member_model_${member.name}"),
                        ) {
                            Text("${member.effectiveTitle}：${member.model ?: "既定モデル"}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = controlledOrder, onCheckedChange = {
                        controlledOrder = it
                    }, modifier = Modifier.testTag("group_control_order"))
                    Text(stringResource(R.string.group_order_control))
                }
                Text(
                    stringResource(
                        if (controlledOrder) R.string.group_order_control_desc else R.string.group_order_legacy_desc,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (controlledOrder) {
                    Text(
                        stringResource(R.string.group_discussion_rounds, discussionRounds.roundToInt()),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.group_discussion_rounds_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = discussionRounds,
                        onValueChange = { discussionRounds = it },
                        valueRange = 1f..MAX_ALLOWED_DISCUSSION_ROUNDS.toFloat(),
                        steps = MAX_ALLOWED_DISCUSSION_ROUNDS - 2,
                        modifier = Modifier.testTag("discussion_rounds_slider"),
                    )
                    orderedMembers.forEachIndexed { index, member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${member.effectiveTitle}",
                                modifier = Modifier.weight(1f).testTag("group_order_member_${member.name}"),
                            )
                            TextButton(onClick = {
                                orderedMembers =
                                    orderedMembers.toMutableList().apply { add(index - 1, removeAt(index)) }
                            }, enabled = index > 0, modifier = Modifier.testTag("group_order_up_${member.name}")) {
                                Text(stringResource(R.string.group_order_up))
                            }
                            TextButton(
                                onClick = {
                                    orderedMembers =
                                        orderedMembers.toMutableList().apply { add(index + 1, removeAt(index)) }
                                },
                                enabled = index < orderedMembers.lastIndex,
                                modifier =
                                    Modifier.testTag(
                                        "group_order_down_${member.name}",
                                    ),
                            ) {
                                Text(stringResource(R.string.group_order_down))
                            }
                        }
                    }
                    Text(stringResource(R.string.group_order_summary), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.group_order_summary_desc), style = MaterialTheme.typography.bodySmall)
                    (listOf<ProfileInfo?>(null) + orderedMembers).forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = summaryMember == member?.name, onClick = {
                                summaryMember =
                                    member?.name
                            }, modifier = Modifier.testTag("group_summary_${member?.name ?: "none"}"))
                            Text(member?.effectiveTitle ?: stringResource(R.string.group_order_summary_none))
                        }
                    }
                }
                // Max Bot Messages Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxMessages.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                if (controlledOrder) {
                                    R.string.group_discussion_turn_cap_desc
                                } else {
                                    R.string.group_chat_settings_max_messages_desc
                                },
                                maxMessages.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxMessages,
                        onValueChange = { maxMessages = it },
                        valueRange = 1f..MAX_ALLOWED_BOT_MESSAGES.toFloat(),
                        steps = MAX_ALLOWED_BOT_MESSAGES - 2,
                        modifier = Modifier.testTag("max_messages_slider"),
                    )
                }

                // Max Continuation Handoffs Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_handoffs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxPasses.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                if (controlledOrder) {
                                    R.string.group_discussion_handoff_cap_desc
                                } else {
                                    R.string.group_chat_settings_max_handoffs_desc
                                },
                                maxPasses.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxPasses,
                        onValueChange = { maxPasses = it },
                        valueRange = 0f..MAX_ALLOWED_CONTINUATION_PASSES.toFloat(),
                        steps = MAX_ALLOWED_CONTINUATION_PASSES - 1,
                        modifier = Modifier.testTag("max_handoffs_slider"),
                    )
                }

                // Live In-Dialog Warning Banner (Option C)
                AnimatedVisibility(
                    visible = isHighLimit,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("high_limits_warning_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.group_chat_settings_high_limits_warning_desc,
                                        maxMessages.roundToInt(),
                                        maxPasses.roundToInt(),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Room Instructions / System Prompt
                Column {
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.group_chat_settings_system_prompt_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 160.dp)
                                .testTag("room_system_prompt_input"),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isHighLimit) {
                        showHighLimitsConfirmation = true
                    } else {
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPrompt.trim().ifBlank { null },
                            if (controlledOrder) orderedMembers.map { it.name } else null,
                            if (controlledOrder) summaryMember else null,
                            discussionRounds.roundToInt(),
                        )
                    }
                },
                modifier = Modifier.testTag("save_settings_button"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.group_chat_settings_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        discussionRounds = DEFAULT_DISCUSSION_ROUNDS.toFloat()
                        maxMessages = DEFAULT_MAX_BOT_MESSAGES.toFloat()
                        maxPasses = DEFAULT_MAX_CONTINUATION_PASSES.toFloat()
                        systemPrompt = ""
                        controlledOrder = false
                        summaryMember = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.group_chat_settings_reset_default))
                }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )

    if (showHighLimitsConfirmation) {
        AlertDialog(
            onDismissRequest = { showHighLimitsConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.group_chat_settings_high_limits_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text =
                        stringResource(
                            R.string.group_chat_settings_high_limits_warning_desc,
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHighLimitsConfirmation = false
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPrompt.trim().ifBlank { null },
                            if (controlledOrder) orderedMembers.map { it.name } else null,
                            if (controlledOrder) summaryMember else null,
                            discussionRounds.roundToInt(),
                        )
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.testTag("confirm_high_limits_button"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHighLimitsConfirmation = false },
                    modifier = Modifier.testTag("dismiss_high_limits_button"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_adjust))
                }
            },
        )
    }
}

@Composable
private fun GroupMessageCard(
    message: GroupChatMessage,
    modifier: Modifier = Modifier,
    onDiagnostics: () -> Unit = {},
) {
    val chatMessage =
        ChatMessage(
            id = message.id,
            role = if (message.isUser) MessageRole.USER else MessageRole.ASSISTANT,
            content = message.text,
            timestamp = message.timestamp,
            isStreaming = message.isStreaming,
        )
    Column(modifier = modifier.fillMaxWidth()) {
        when {
            message.isSystem -> {
                val displayText =
                    when {
                        message.isPass -> {
                            stringResource(
                                R.string.group_chat_bot_passed,
                                message.senderDisplayName.ifBlank {
                                    message.senderName
                                },
                            )
                        }

                        message.text == STOPPED_SYSTEM_TEXT -> {
                            stringResource(R.string.group_chat_stopped_by_user)
                        }

                        message.text == CAPPED_SYSTEM_TEXT -> {
                            stringResource(R.string.group_chat_capped_limit)
                        }

                        else -> {
                            message.text
                        }
                    }
                Text(
                    displayText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            message.isUser -> {
                ChatBubble(message = chatMessage)
                if (message.deliveries.isNotEmpty()) {
                    val labels =
                        mapOf(
                            GroupDelivery.PENDING to stringResource(R.string.group_delivery_pending),
                            GroupDelivery.ACCEPTED to stringResource(R.string.group_delivery_accepted),
                            GroupDelivery.COMPLETE to stringResource(R.string.group_delivery_complete),
                            GroupDelivery.NOT_SENT to stringResource(R.string.group_delivery_not_sent),
                            GroupDelivery.UNKNOWN to stringResource(R.string.group_delivery_unknown),
                            GroupDelivery.FAILED to stringResource(R.string.group_delivery_failed),
                        )
                    Text(
                        message.deliveries.entries.joinToString(" · ") { (name, status) -> "$name: ${labels[status]}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("group_delivery_${message.id}"),
                    )
                }
                TextButton(
                    onClick = onDiagnostics,
                    modifier = Modifier.align(Alignment.End).testTag("group_diagnostics_${message.id}"),
                ) {
                    Text(stringResource(R.string.group_send_diagnostics))
                }
            }

            else -> {
                val spacing = LocalSpacing.current
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = spacing.md).testTag("group_message_${message.id}"),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    BotAvatar(
                        name = message.senderDisplayName.ifBlank { message.senderName },
                        avatar = message.avatarMeta,
                        size = 32.dp,
                        showPresence = false,
                    )
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(vertical = spacing.md)) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    message.senderDisplayName.ifBlank { message.senderName },
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f).testTag("group_sender_${message.id}"),
                                )
                                Text(
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (message.toolCalls.isNotEmpty()) {
                                Column(
                                    Modifier.padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    message.toolCalls.forEach { tool -> GroupChatToolChip(tool = tool) }
                                }
                            }
                            FullBleedAgentMessage(
                                message = chatMessage,
                                showTurnHeader = false,
                                showTimestamp = false,
                                speakerName = message.senderDisplayName,
                                isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupChatToolChip(
    tool: GroupChatToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val isTerminal =
        tool.name.equals("terminal", ignoreCase = true) ||
            tool.name.equals("execute_code", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border =
            BorderStroke(
                1.dp,
                if (tool.isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val icon =
                    when (tool.name.lowercase()) {
                        "terminal", "execute_code" -> Icons.Filled.Terminal
                        "web_search", "search_files" -> Icons.Filled.Search
                        "read_file", "write_file", "patch" -> Icons.Filled.Description
                        else -> Icons.Filled.Build
                    }
                Icon(
                    imageVector = icon,
                    contentDescription = tool.name,
                    modifier = Modifier.size(13.dp),
                    tint =
                        if (tool.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                val label =
                    if (!tool.summary.isNullOrBlank()) {
                        "${tool.name}: ${tool.summary}"
                    } else {
                        tool.name
                    }
                val isLabelRtl = remember(label) { BidiUtils.isRtlText(label) }
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            textDirection = if (isLabelRtl) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tool.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!tool.command.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = if (isTerminal) "$ ${tool.command}" else tool.command,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            textDirection = TextDirection.Ltr,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    val outputText =
                        when {
                            tool.isRunning -> stringResource(R.string.group_chat_tool_running)
                            !tool.output.isNullOrBlank() -> tool.output.trim()
                            tool.exitCode == 0 -> stringResource(R.string.group_chat_tool_no_output_success)
                            else -> stringResource(R.string.group_chat_tool_no_output)
                        }
                    val isOutputRtl = remember(outputText) { BidiUtils.isRtlText(outputText) }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = outputText,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection =
                                            if (isOutputRtl) {
                                                TextDirection.Rtl
                                            } else {
                                                TextDirection.Ltr
                                            },
                                    ),
                                color =
                                    if (tool.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                maxLines = 15,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
