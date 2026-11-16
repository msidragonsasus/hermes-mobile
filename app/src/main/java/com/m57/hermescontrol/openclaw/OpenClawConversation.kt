package com.m57.hermescontrol.openclaw

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.agents.AgentModeTabs
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.components.ChatConnectionBanner
import com.m57.hermescontrol.ui.chat.components.ChatInputBar
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.ConversationHeaderActions
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.ConversationMenuSheet
import com.m57.hermescontrol.ui.chat.components.quotedReplyField
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.sessions.SessionsUiState

/** OpenClaw owns its transport; presentation uses the same conversation components as Hermes. */
@Composable
internal fun OpenClawConversation(
    state: ClawUiState,
    owner: String,
    onSend: (String, () -> Unit) -> Unit,
    onStop: () -> Unit,
    onNew: () -> Unit,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onSettings: () -> Unit,
    onModel: () -> Unit,
    onEffort: () -> Unit,
    title: @Composable () -> Unit,
) {
    var drafts by rememberSaveable(owner) { mutableStateOf(mapOf<String, String>()) }
    var input by rememberSaveable(owner, state.sessionKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(drafts[state.sessionKey].orEmpty()))
    }
    var history by rememberSaveable(owner) { mutableStateOf(false) }
    var search by rememberSaveable(owner) { mutableStateOf("") }
    val spacing = LocalSpacing.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scroll = rememberChatScrollController(list, scope)
    val connected = state.connection == ClawConnection.CONNECTED
    val canChangeSession = state.canSend && !state.loading && !state.busy && !state.saving
    val canChangeOptions = state.canPatch && !state.loading && !state.busy && !state.saving
    val handleNew = {
        if (canChangeSession) {
            history = false
            search = ""
            onNew()
        }
    }
    val handleSend = {
        if (state.canSend && !state.loading && !state.saving && !state.busy && input.text.isNotBlank()) {
            val sent = input.text
            val key = state.sessionKey
            scroll.jumpToBottom()
            onSend(sent) {
                // A delayed acknowledgement must not clear text typed in another conversation.
                if (drafts[key] == sent) drafts = drafts - key
                if (key == state.sessionKey && input.text == sent) input = TextFieldValue("")
            }
        }
    }
    LaunchedEffect(owner, state.sessionKey) {
        if (state.sessionKey.isNotBlank() && drafts[""].orEmpty().isNotBlank()) {
            val restored = drafts[state.sessionKey].orEmpty() + drafts[""].orEmpty()
            drafts = (drafts - "") + (state.sessionKey to restored)
            input = TextFieldValue(restored)
        }
        scroll.jumpToBottom()
    }
    LaunchedEffect(scroll) { scroll.observeUserScrollPosition() }
    LaunchedEffect(state.messages.lastOrNull(), state.streaming, state.busy) {
        scroll.onTailChanged(
            tailKey = Triple(state.messages.lastOrNull(), state.streaming, state.busy),
            messageCount = state.messages.size + if (state.busy || state.streaming.isNotBlank()) 1 else 0,
        )
    }
    HermesScaffold(
        pinTopBar = true,
        title = title,
        navigationIcon =
            NavIcon.Menu(
                onOpen = { history = true },
                contentDescription = stringResource(R.string.chat_open_conversation_menu),
                tonal = true,
            ),
        drawerGesturesEnabled = false,
        showShellMenu = false,
        actions = {
            ConversationHeaderActions(onNew = handleNew, newEnabled = canChangeSession, onSettings = onSettings)
        },
        overlay = {
            ConversationMenuSheet(
                visible = history,
                currentSessionId = state.sessionKey,
                currentModel = state.model,
                historyState =
                    SessionsUiState(
                        sessions = state.sessions.map { SessionInfo(id = it.key, title = it.title) },
                        isLoading = state.loading,
                        searchQuery = search,
                        searchResults =
                            state.sessions
                                .filter { it.title.contains(search.trim(), ignoreCase = true) }
                                .map { SessionSearchResult(session_id = it.key, snippet = it.title) },
                    ),
                onSelectSession = {
                    if (canChangeSession) {
                        history = false
                        search = ""
                        onSelectSession(it)
                    }
                },
                onNewConversation = handleNew,
                selectionEnabled = canChangeSession,
                onSearchQuery = { search = it },
                onDismiss = { history = false },
                onRefreshHistory = onRefresh,
                onHistory = {},
                onModel = onModel,
            )
        },
    ) {
        val swipeDistance = with(LocalDensity.current) { 64.dp.toPx() }
        ConversationLayout(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(swipeDistance) {
                        var travelled = 0f
                        detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, amount ->
                            travelled += amount
                            if (travelled > swipeDistance) {
                                change.consume()
                                history = true
                            }
                        }
                    }.testTag("openclaw_chat"),
            onReply =
                if (connected) {
                    { message ->
                        input = quotedReplyField(message, input)
                        drafts = drafts + (state.sessionKey to input.text)
                    }
                } else {
                    null
                },
            composer = {
                ChatInputBar(
                    inputFieldValue = input,
                    onInputChange = {
                        input = it
                        drafts = drafts + (state.sessionKey to it.text)
                    },
                    onSend = handleSend,
                    onImeSend = handleSend,
                    onStop = onStop,
                    onMicTap = {},
                    isListening = false,
                    isAgentTyping = state.busy,
                    isConnected = connected,
                    commandCatalog = CommandCatalog(),
                    attachmentsEnabled = false,
                    microphoneEnabled = false,
                    showCommandSuggestions = false,
                    sendEnabled = state.canSend && !state.loading && !state.saving && !state.busy,
                    currentSessionModel = state.model,
                    reasoningLevel = state.effort,
                    reasoningDisplayName = state.effort ?: "既定値",
                    onModelTap = onModel,
                    onReasoningPickerTap = onEffort,
                    modelControlsEnabled = canChangeOptions,
                    inputTestTag = "openclaw_input",
                    sendTestTag = "openclaw_send",
                    stopTestTag = "openclaw_stop",
                )
            },
        ) {
            AgentModeTabs(groups = false, conversation = OpenClawChatKey)
            if (state.configured) {
                ChatConnectionBanner(
                    connectionStatus =
                        when (state.connection) {
                            ClawConnection.CONNECTED -> ConnectionStatus.CONNECTED
                            ClawConnection.CONNECTING -> ConnectionStatus.CONNECTING
                            ClawConnection.DISCONNECTED -> ConnectionStatus.DISCONNECTED
                        },
                    onReconnect = onReconnect,
                    onReloginClick = onSettings,
                    reloginLabel = "接続設定",
                )
            }
            state.error?.let { Text(it, Modifier.padding(spacing.md), color = MaterialTheme.colorScheme.error) }
            state.notice?.let {
                Text(it, Modifier.padding(horizontal = spacing.md), style = MaterialTheme.typography.bodySmall)
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.configured -> {
                        EmptyState(
                            title = "OpenClawと話す",
                            subtitle = "接続先を設定して会話を始めましょう",
                            actionLabel = "接続を設定",
                            onAction = onSettings,
                        )
                    }

                    state.messages.isEmpty() && !state.busy && !state.loading -> {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                title = stringResource(R.string.chat_empty_title),
                                subtitle = stringResource(R.string.chat_empty_subtitle),
                            )
                        }
                    }

                    else -> {
                        val density = LocalDensity.current
                        val fontScale = LocalChatFontScale.current
                        val chatDensity =
                            remember(density, fontScale) {
                                Density(density.density, density.fontScale * fontScale)
                            }
                        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                        CompositionLocalProvider(LocalDensity provides chatDensity) {
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("openclaw_messages"),
                                state = list,
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(state.messages, key = {
                                    index,
                                    message,
                                    ->
                                    "$index:${message.id}"
                                }) { _, entry ->
                                    val message =
                                        remember(entry) {
                                            ChatMessage(
                                                id = entry.id,
                                                role =
                                                    if (entry.role ==
                                                        "user"
                                                    ) {
                                                        MessageRole.USER
                                                    } else {
                                                        MessageRole.ASSISTANT
                                                    },
                                                content = entry.text,
                                            )
                                        }
                                    if (message.role == MessageRole.USER) {
                                        ChatBubble(message, showTimestamp = false)
                                    } else {
                                        FullBleedAgentMessage(
                                            message,
                                            showTurnHeader = true,
                                            speakerName = "OpenClaw",
                                            isDarkTheme = dark,
                                            showTimestamp = false,
                                        )
                                    }
                                }
                                if (state.busy || state.streaming.isNotBlank()) {
                                    item(key = "streaming") {
                                        FullBleedAgentMessage(
                                            ChatMessage(
                                                id = "streaming",
                                                role = MessageRole.ASSISTANT,
                                                content = state.streaming,
                                                isStreaming = true,
                                            ),
                                            showTurnHeader = true,
                                            speakerName = "OpenClaw",
                                            isDarkTheme = dark,
                                            showTimestamp = false,
                                        )
                                    }
                                }
                            }
                        }
                        ChatScrollToBottomFab(
                            show = scroll.showFab(state.messages.isNotEmpty()),
                            pendingCount = scroll.pendingCount,
                            onScrollToBottom = scroll::resumeFollowing,
                        )
                    }
                }
            }
        }
    }
}
