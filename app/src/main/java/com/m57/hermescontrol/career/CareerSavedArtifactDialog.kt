package com.m57.hermescontrol.career

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.ChatComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Composable
internal fun CareerSavedArtifactDialog(
    artifact: CareerArtifact,
    state: CareerCoachUiState,
    actions: CareerCoachActions,
    loadDocument: suspend (String, Int) -> JsonObject,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var body by remember(artifact.path) { mutableStateOf<String?>(null) }
    var error by remember(artifact.path) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var synced by remember(artifact.path) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(artifact.path, reload) {
        try {
            val document = loadDocument(artifact.id, artifact.version)
            body = document.text("body") ?: error("本文がありません。")
            synced = (document["github_synced"] as? JsonPrimitive)?.booleanOrNull
            error = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = careerError(failure)
        }
    }
    body?.let { CareerHomeReadEffect("document:${artifact.id}", "${artifact.version}\n$it") }
    val draft = state.revisionDrafts["${artifact.id}:${artifact.version}"].orEmpty()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text("${artifact.title} · 第${artifact.version}版", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("会話へ戻る") }
                        TextButton(
                            onClick = {
                                body?.let { text ->
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText(artifact.title, text)),
                                        )
                                    }
                                }
                            },
                            enabled =
                                body != null,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("本文をコピー") }
                    }
                    Text(
                        "${body?.let {
                            it.codePointCount(
                                0,
                                it.length,
                            )
                        } ?: artifact.characters}文字 · サーバー保存済み · ${when (synced) {
                            true -> "GitHub同期済み"
                            false -> "GitHub未同期"
                            null -> "GitHub同期を未確認"
                        }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer(
                        Modifier
                            .weight(
                                1f,
                            ).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag("career_artifact_body"),
                    ) {
                        MarkdownText(body ?: error ?: "本文を読み込んでいます…", MaterialTheme.colorScheme.onSurface)
                    }
                    if (error != null) TextButton(onClick = { reload++ }) { Text("本文を再読込") }
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.pending !=
                        null
                    ) {
                        TextButton(
                            onClick = actions.retry,
                            enabled = !state.busy,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("同じ依頼の送信結果を確認") }
                    }
                    ChatComposer(
                        value = draft,
                        onValueChange = { actions.revisionDraft(artifact, it) },
                        onSend = { actions.revise(artifact, draft) },
                        inputEnabled = !state.busy && state.pending == null,
                        sendEnabled = body != null && draft.isNotBlank() && !state.busy && state.pending == null,
                        placeholder = "この第${artifact.version}版をどう直す？",
                        inputTestTag = "career_revision_input",
                        sendTestTag = "career_revision_send",
                    )
                }
            }
        }
    }
}
