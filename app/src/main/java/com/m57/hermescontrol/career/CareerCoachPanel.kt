package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.ChatComposer
import com.m57.hermescontrol.ui.chat.components.ConversationLayout
import com.m57.hermescontrol.ui.chat.components.quotedReplyDraft
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.LoadingState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class CareerCoachActions(
    val draft: (String) -> Unit = {},
    val send: (String) -> Unit = {},
    val clearTarget: () -> Unit = {},
    val consult: (CareerEntity) -> Unit = {},
    val control: (CareerCoachRun, String) -> Unit = { _, _ -> },
    val refresh: () -> Unit = {},
    val older: () -> Unit = {},
    val retry: () -> Unit = {},
    val discard: () -> Unit = {},
    val selectExecution: (CareerExecution) -> Unit = {},
    val refreshCapabilities: () -> Unit = {},
    val saveDailyExecution: (CareerExecution) -> Unit = {},
    val revise: (CareerArtifact, String) -> Unit = { _, _ -> },
    val revisionDraft: (CareerArtifact, String) -> Unit = { _, _ -> },
    val research: () -> Unit = {},
)

@Composable
internal fun CareerCoachPanel(
    state: CareerCoachUiState,
    records: CareerSnapshot?,
    actions: CareerCoachActions,
    onArtifact: (CareerEntity) -> Unit,
    onSavedArtifact: (CareerArtifact) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    emptyTitle: String = "就活コーチに話しかける",
    emptySubtitle: String = "相談したいことや、仕上げたい文章をそのまま伝えてください。",
    inputPlaceholder: String = "就活コーチに相談・依頼する",
    focusInput: Boolean = false,
) {
    val spacing = LocalSpacing.current
    val snapshot = state.snapshot
    CareerConversationHomeReadEffect(snapshot, listState)
    val inputEnabled = careerDraftEditable(state)
    val inputFocus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusInput, inputEnabled) {
        if (!focusInput || !inputEnabled || focusHandled) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            runCatching { inputFocus.requestFocus() }
            withFrameNanos { }
            if (inputFocused) {
                focusHandled = true
                keyboard?.show()
                return@LaunchedEffect
            }
        }
    }
    val scrollScope = rememberCoroutineScope()
    val scroll = rememberChatScrollController(listState, scrollScope)
    var openedAtLatest by rememberSaveable(snapshot?.thread_id) { mutableStateOf(false) }
    var olderAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(scroll) { scroll.observeUserScrollPosition() }
    val messages = snapshot?.messages.orEmpty()
    val withoutReply =
        snapshot?.runs.orEmpty().filter { run ->
            messages.none {
                it.run_id == run.id &&
                    it.role == "assistant"
            }
        }
    val tailKey =
        listOf(
            messages.lastOrNull()?.id,
            messages.lastOrNull()?.text,
            withoutReply.flatMap { it.artifacts }.map { it.path },
        )
    LaunchedEffect(tailKey, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (!openedAtLatest) {
            openedAtLatest = true
            scroll.jumpToBottom()
        }
        // Prepending a page does not change this key, so it never counts as a new reply.
        scroll.onTailChanged(tailKey, messages.size)
    }
    LaunchedEffect(state.loadingOlder, messages.firstOrNull()?.id) {
        val anchor = olderAnchor ?: return@LaunchedEffect
        if (state.loadingOlder) return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == anchor.first }
        if (index >= 0) {
            withFrameNanos { }
            scroll.scrollToItem(index + if (snapshot?.older_before != null) 1 else 0, anchor.second)
        }
        olderAnchor = null
    }
    var discardReview by remember { mutableStateOf(false) }
    ConversationLayout(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars),
        onReply =
            if (inputEnabled) {
                { message -> actions.draft(quotedReplyDraft(message, state.draft)) }
            } else {
                null
            },
        composer = {
            // An inline dock, not a new FAB. Returning here explicitly resumes tail following.
            if (scroll.showFab(messages.isNotEmpty())) {
                TextButton(
                    onClick = scroll::resumeFollowing,
                    modifier = Modifier.align(Alignment.End).testTag("career_coach_latest"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    Text(if (scroll.pendingCount > 0) "最新へ · 新しい返信 ${scroll.pendingCount}件" else "最新へ")
                }
            }
            CareerReferenceButton(state, records, actions)
            ChatComposer(
                value = state.draft,
                onValueChange = actions.draft,
                onSend = {
                    if (careerCanSend(state)) {
                        scroll.jumpToBottom()
                        actions.send(state.draft)
                    }
                },
                inputEnabled = inputEnabled,
                sendEnabled = careerCanSend(state),
                placeholder = inputPlaceholder,
                inputModifier =
                    Modifier
                        .focusRequester(inputFocus)
                        .onFocusChanged { inputFocused = it.hasFocus }
                        .semantics { contentDescription = "相談の入力欄" },
                inputTestTag = "career_coach_input",
                sendTestTag = "career_coach_send",
            )
            CareerModelButton(state, actions)
        },
    ) {
        CareerConversationStatus(state, actions) { discardReview = true }
        snapshot?.let { CareerConsultationContextButton(it.company_context, state.stale) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("career_coach_conversation"),
            contentPadding = PaddingValues(vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (!state.initialized || (state.loading && snapshot == null)) item(key = "loading") { LoadingState() }
            if (snapshot?.older_before != null) {
                item(key = "older") {
                    TextButton(
                        onClick = {
                            val visible =
                                listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    messages.any {
                                        it.id ==
                                            item.key
                                    }
                                }
                            olderAnchor = visible?.let { it.key.toString() to -it.offset }
                            actions.older()
                        },
                        enabled = !state.loading,
                        modifier = Modifier.testTag("career_coach_older"),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (state.loadingOlder) "以前の会話を読み込んでいます" else "以前の会話を読む") }
                }
            }
            if (state.initialized && messages.isEmpty() && state.error == null) {
                item(key = "empty") { EmptyState(emptyTitle, subtitle = emptySubtitle) }
            }
            items(messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth()) {
                    CareerConversationMessage(message)
                    if (message.role == "assistant") {
                        val run = snapshot?.runs?.firstOrNull { it.id == message.run_id }
                        if (run?.artifacts.orEmpty().isNotEmpty()) {
                            run?.artifacts.orEmpty().forEach { artifact ->
                                CareerArtifactCard(artifact, onOpen = { onSavedArtifact(artifact) })
                            }
                        } else if ("artifact_receipts" !in state.capabilities?.features.orEmpty()) {
                            val ids =
                                (run?.result?.get("artifact_ids") as? JsonArray).orEmpty().mapNotNull {
                                    (it as? JsonPrimitive)?.contentOrNull
                                }
                            ids.forEach { id ->
                                val entity = records?.entities?.firstOrNull { it.id == id }
                                if (entity != null) {
                                    TextButton(
                                        onClick = { onArtifact(entity) },
                                        modifier = Modifier.testTag("career_artifact_$id"),
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("成果物を開く: ${entity.title}") }
                                } else {
                                    TextButton(
                                        onClick = actions.refresh,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("成果物を再読込") }
                                }
                            }
                        }
                    }
                }
            }
            withoutReply.forEach { run ->
                items(run.artifacts, key = { "receipt:${run.id}:${it.path}" }) { artifact ->
                    CareerArtifactCard(artifact, onOpen = { onSavedArtifact(artifact) })
                }
            }
        }
    }
    if (discardReview) {
        AlertDialog(
            onDismissRequest = { discardReview = false },
            title = { Text("再送を取りやめますか") },
            text = { Text("届いた依頼はサーバーで続くことがあります。更新して結果を確認してください。入力文は保持します。") },
            confirmButton = {
                TextButton(onClick = {
                    actions.discard()
                    discardReview = false
                }, shapes = ButtonDefaults.shapes()) { Text("再送を取りやめる") }
            },
            dismissButton = {
                TextButton(onClick = { discardReview = false }, shapes = ButtonDefaults.shapes()) { Text("戻る") }
            },
        )
    }
}

@Composable
private fun CareerArtifactCard(
    artifact: CareerArtifact,
    onOpen: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.md).testTag("career_artifact_${artifact.id}"),
    ) {
        Column(
            Modifier.padding(LocalSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            Text("${artifact.title} · 第${artifact.version}版", style = MaterialTheme.typography.titleMedium)
            Text(artifact.preview, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${artifact.characters}文字 · ${if (artifact.server_saved) "サーバー保存済み" else "保存を未確認"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(if (artifact.github_synced) "GitHub同期済み" else "GitHub未同期", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen, shapes = ButtonDefaults.shapes()) { Text("全文・コピー・修正") }
        }
    }
}

internal fun careerRunStatus(value: String): String =
    when (value) {
        "queued" -> "待機中"
        "running" -> "実行中 · Appを閉じても続行します"
        "waiting_user" -> "あなたの回答を待っています"
        "paused" -> "中断中 · 保存済みの結果は保持しています"
        "completed" -> "完了"
        "cancelled" -> "停止済み"
        "failed" -> "失敗 · 保存済みの結果は保持しています"
        else -> value
    }

@Composable
internal fun CareerArtifactDialog(
    entity: CareerEntity,
    onDismiss: () -> Unit,
    onConsult: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entity.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("会話へ戻る") }
                }
                Text("${careerStatus(entity.data.text("status"))} · 第${entity.data.text("version") ?: entity.version}版")
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    MarkdownText(
                        entity.data.text("body") ?: "本文を読み込めません。会話に戻って更新してください。",
                        MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = onConsult,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text("この文書について相談") }
            }
        }
    }
}
