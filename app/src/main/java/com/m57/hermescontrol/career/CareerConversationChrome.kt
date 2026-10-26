package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.m57.hermescontrol.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareerReferenceButton(
    state: CareerCoachUiState,
    records: CareerSnapshot?,
    actions: CareerCoachActions,
) {
    val target = state.target
    val answer = state.answerContext
    if (target == null && answer == null) return
    var open by rememberSaveable { mutableStateOf(false) }
    val entity = records?.entities?.firstOrNull { it.kind == target?.kind && it.id == target.id }
    TextButton(
        onClick = { open = true },
        shapes = ButtonDefaults.shapes(),
    ) {
        Text(
            "参照: " + (
                entity?.title ?: if (answer !=
                    null
                ) {
                    "編集中の回答"
                } else {
                    target?.let { careerKind(it.kind) }.orEmpty()
                }
            ) +
                target
                    ?.source_refs
                    .orEmpty()
                    .size
                    .takeIf { it > 0 }
                    ?.let { " · 関連資料${it}件" }
                    .orEmpty(),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(LocalSpacing.current.md),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
            ) {
                Text("この相談で参照する内容", style = MaterialTheme.typography.titleMedium)
                target?.let {
                    Text(entity?.title ?: "${careerKind(it.kind)}：${it.id}")
                    it.version?.let { version -> Text("第${version}版") }
                    it.source_refs.forEachIndexed { index, ref ->
                        Text("${if (ref.kind == "mail") "保存済みメール" else "保存済み案内"} ${index + 1}：${ref.id}")
                    }
                }
                answer?.let {
                    Text("設問 ${it.text("question_id")} · ${it.text("question_version").orEmpty()}")
                    Text("相談へ持ち込んだ回答")
                    androidx.compose.foundation.text.selection
                        .SelectionContainer { Text(it.text("body").orEmpty()) }
                }
                TextButton(
                    onClick = {
                        actions.clearTarget()
                        open = false
                    },
                    enabled = !state.readOnly && !state.busy && state.pending == null,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("参照を外す") }
                TextButton(onClick = { open = false }, shapes = ButtonDefaults.shapes()) { Text("会話へ戻る") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareerModelButton(
    state: CareerCoachUiState,
    actions: CareerCoachActions,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = {
        open = true
    }, enabled = !state.readOnly) {
        Text(
            state.execution?.model ?: "モデルを選択",
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(LocalSpacing.current.md)) {
                Text("この会話のAI設定", style = MaterialTheme.typography.titleMedium)
                CareerExecutionControls(
                    state.execution,
                    state.capabilities,
                    state.capabilitiesChecked && !state.busy && state.pending == null,
                    actions.selectExecution,
                    actions.refreshCapabilities,
                )
                state.capabilitiesError?.let { Text(it) }
                Text("変更は次に送る依頼に適用されます。受理済みの作業の設定は変わりません。")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CareerConversationStatus(
    state: CareerCoachUiState,
    actions: CareerCoachActions,
    discard: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val runs = state.snapshot?.runs.orEmpty()
    val current = careerCurrentRun(state)
    val latestRun = current ?: runs.firstOrNull()
    val shared = state.snapshot?.shared_context
    val sharingState = state.snapshot?.sync?.text("state")
    val used = latestRun?.result?.get("context_used") as? kotlinx.serialization.json.JsonObject
    val sharingText =
        when {
            state.stale && state.snapshot != null -> {
                "共有状態は最終確認時の表示です"
            }

            sharingState == "conflict" -> {
                "共有データに競合があります"
            }

            sharingState == "synced" && shared?.text("state") == "verified" -> {
                "共有データ r${shared.text("state_revision") ?: "?"}" +
                    (used?.text("source_revision")?.let { " · この回答 r$it" } ?: "")
            }

            sharingState != null -> {
                "共有データは反映待ちです"
            }

            else -> {
                null
            }
        }
    sharingText?.let {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.md),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = true }, shapes = ButtonDefaults.shapes()) { Text("共有情報") }
        }
    }
    val text =
        when {
            state.readOnly || state.snapshot?.archived == true -> "履歴の閲覧中です。送信するには相談を復元してください。"
            state.busy -> "依頼を送信しています"
            state.pending != null -> "送信結果が未確認です。入力は保持しています"
            state.stale && state.snapshot != null -> "保存済みの会話を表示中 · 現在の実行状態は未確認"
            current?.cancel_requested != null && current.cancel_requested != 0 -> "停止を確認しています"
            current != null -> careerRunStatus(current.state)
            else -> careerSendPreparation(state)
        }
    val preparation =
        careerSendPreparation(state).takeIf {
            state.initialized && !state.busy && state.pending == null && !state.readOnly &&
                state.snapshot?.archived != true
        }
    val issue = state.error ?: state.capabilitiesError ?: preparation
    val progress = current?.takeIf { !state.stale && it.state == "running" }?.let { state.progress[it.id] }
    val failure =
        runs
            .firstOrNull()
            ?.takeIf {
                it.state in
                    setOf(
                        "failed",
                        "cancelled",
                        "paused",
                    )
            }?.result
            ?.text("message")
    if (text != null || issue != null || failure != null) {
        Surface(
            color =
                if (issue !=
                    null
                ) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            contentColor =
                if (issue !=
                    null
                ) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier =
                Modifier.fillMaxWidth().testTag("career_coach_status").semantics {
                    liveRegion =
                        LiveRegionMode.Polite
                },
        ) {
            Column(Modifier.padding(horizontal = LocalSpacing.current.md, vertical = LocalSpacing.current.xs)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (current != null && current.state in setOf("queued", "running", "paused") &&
                        current.cancel_requested == 0
                    ) {
                        TextButton(
                            onClick = { if (careerCanStop(state, current)) actions.control(current, "stop") },
                            enabled = careerCanStop(state, current),
                            modifier = Modifier.testTag("career_coach_stop"),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("停止")
                        }
                    }
                    TextButton(onClick = { open = true }, shapes = ButtonDefaults.shapes()) { Text("詳細") }
                }
                issue?.takeIf { it != text }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("career_coach_error"),
                    )
                }
                if (issue == null) failure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (current?.state == "running" && !state.stale) {
                    Text("回答は完了後に表示します", style = MaterialTheme.typography.labelSmall)
                }
                if (state.pending != null && !state.busy) {
                    TextButton(onClick = actions.retry, shapes = ButtonDefaults.shapes()) { Text("同じ依頼の結果を再確認") }
                } else if (issue != null || state.stale) {
                    TextButton(onClick = {
                        actions.refresh()
                        actions.refreshCapabilities()
                    }, enabled = !state.loading && !state.busy, shapes = ButtonDefaults.shapes()) { Text("接続・モデルを再確認") }
                }
                if (current?.state == "paused") {
                    TextButton(
                        onClick = { actions.control(current, "resume") },
                        enabled =
                            !state.stale && !state.busy && state.pending == null && !state.readOnly &&
                                state.snapshot?.archived != true,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("保存済みの結果から続行") }
                }
            }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(LocalSpacing.current.lg),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text("実行状況", style = MaterialTheme.typography.titleMedium)
                sharingText?.let { Text(it) }
                shared?.text("commit")?.let { Text("共有元 commit: $it", style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it) }
                state.capabilitiesError?.let { Text(it) }
                if (state.stale) Text("接続が戻るまで現在の実行状態は未確認です。")
                TextButton(onClick = actions.refresh, shapes = ButtonDefaults.shapes()) { Text("状態を更新") }
                if (state.pending != null) {
                    TextButton(
                        onClick = actions.retry,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("同じ依頼の結果を再確認") }
                    TextButton(
                        onClick = discard,
                        enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("再送を取りやめる") }
                }
                runs.forEach { run ->
                    Text(careerRunStatus(run.state))
                    val contextUsed = run.result["context_used"] as? kotlinx.serialization.json.JsonObject
                    contextUsed?.text("source_revision")?.let { Text("この回答に使用: r$it") }
                    contextUsed
                        ?.text(
                            "source_commit",
                        )?.let { Text("使用 commit: $it", style = MaterialTheme.typography.bodySmall) }
                    val sharingSave = run.result["sharing_save"] as? kotlinx.serialization.json.JsonObject
                    sharingSave?.text("state")?.let {
                        Text(if (it == "synced") "GitHub反映済み" else "サーバー保存を保持 · GitHub反映待ち")
                    }
                    run.execution?.let {
                        Text(
                            "${it.provider} · ${it.model} · ${it.reasoning_effort}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.progress[run.id]?.let { Text(it) }
                    run.result.text("message")?.let { Text(it) }
                    if (run.state ==
                        "paused"
                    ) {
                        TextButton(
                            onClick = { actions.control(run, "resume") },
                            enabled =
                                !state.stale && !state.busy && state.pending == null && !state.readOnly,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("保存済みの結果から続行") }
                    }
                    if (run.state in setOf("queued", "running", "paused") &&
                        run.cancel_requested == 0
                    ) {
                        TextButton(
                            onClick = { actions.control(run, "stop") },
                            enabled =
                                !state.stale && !state.busy && state.pending == null && !state.readOnly,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("この作業を停止") }
                    }
                }
            }
        }
    }
}
