package com.m57.hermescontrol.miniapp.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun CareerMyPageWorkbenchScreen(key: CareerMyPageWorkbenchKey) {
    val context by AuthManager.contextFlow.collectAsState()
    val currentScope = remember(context) { careerScope() }
    val entryScope = rememberSaveable(key) { careerScope() }
    val appContext = LocalContext.current.applicationContext
    val model: WorkbenchViewModel =
        viewModel(
            key = "workbench:$entryScope:${key.companyId}",
            factory =
                viewModelFactory {
                    initializer {
                        WorkbenchViewModel(
                            DashboardWorkbenchRepository(entryScope, key.companyId),
                            PreferencesWorkbenchPendingStore.forScope(appContext, entryScope, key.companyId),
                        )
                    }
                },
        )
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(model, currentScope, entryScope) { if (currentScope != entryScope) model.connectionChanged() }
    DisposableEffect(model) { onDispose { model.release() } }
    HermesScaffold(
        title = { Text("マイページの改修") },
        navigationIcon = NavIcon.Back { NavigationController.goBack() },
        drawerGesturesEnabled = false,
        pinTopBar = true,
    ) {
        WorkbenchContent(key.companyId, state, model::load, model::send, model::fence)
    }
}

@Composable
internal fun WorkbenchContent(
    company: String,
    state: WorkbenchUiState,
    onLoad: () -> Unit,
    onSend: (String, Map<String, kotlinx.serialization.json.JsonElement>) -> Unit,
    onFence: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val snapshot = state.snapshot
    var confirmation by remember { mutableStateOf<String?>(null) }
    val operationEnabled =
        !state.busy && state.pending == null && !state.blocked && !state.changedConnection && !state.disabled
    val enabled = operationEnabled && state.editor?.job?.active != true
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md)
            .testTag("workbench_screen"),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text("この会社の画面と専用Botの確認動作を変更します。依頼後に編集・テスト・差分確認を行い、反映できます。", style = MaterialTheme.typography.bodyMedium)
        if (state.message.isNotEmpty()) Text(state.message, Modifier.testTag("workbench_message"))
        if (state.busy && snapshot == null) LoadingState(subtitle = "読み込んでいます…")
        OutlinedButton(
            onClick = onLoad,
            enabled = !state.busy && !state.changedConnection,
            modifier = Modifier.fillMaxWidth().testTag("workbench_refresh"),
        ) {
            Text(if (state.pending == null) "表示を更新" else "前回の操作結果を確認")
        }
        if (state.pending != null) {
            Text("前回の操作結果が確定するまで、新しい操作は開始できません。取り消し時、受付済みの操作は結果を回収します。")
            OutlinedButton(
                onClick = onFence,
                enabled = !state.busy && !state.blocked && !state.changedConnection,
                modifier = Modifier.fillMaxWidth().testTag("workbench_fence"),
            ) {
                Text("未受付の操作を取り消す")
            }
        }
        state.editor?.let { editor ->
            val job = editor.job
            Text(
                when (job?.state) {
                    "awaiting_executor" -> "依頼は保存済みです。専用改修Botの実行設定を待っています。"
                    "queued" -> "専用Botの実行開始待ちです。表示を更新して確認できます。"
                    "running" -> "専用Botが画面とBot処理の候補を生成しています。"
                    "proposed" -> "候補が届きました。差分を確認し、固定テストを実行してください。"
                    "tested" -> "現在の候補はテスト済みです。差分を確認して反映できます。"
                    "applied" -> "候補の反映を確認しました。"
                    "rolled_back" -> "前の版へ復旧しました。"
                    "discarded", "cancelled" -> "前の依頼・候補は取り消されています。"
                    "failed" -> "候補の生成を完了できませんでした。依頼を確認して新しく送れます。"
                    "conflict" -> "生成中に基準が変わりました。表示を更新してから新しく依頼してください。"
                    else -> if (editor.executorConfigured) "専用改修Botの設定があります。" else "専用改修Botの実行設定は承認待ちです。依頼文を保存できます。"
                },
                Modifier.testTag("workbench_editor_status"),
            )
            job?.execution?.let {
                Text(
                    "${it.provider} / ${it.model} / ${it.effort}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (job?.active == true) {
                OutlinedButton(onClick = {
                    onSend("cancel_editor", emptyMap())
                }, enabled = operationEnabled, modifier = Modifier.fillMaxWidth().testTag("workbench_cancel_editor")) {
                    Text("候補の生成を取り消す")
                }
            }
        }
        if (snapshot != null) {
            Text("稼働版: ${snapshot.active}", style = MaterialTheme.typography.labelSmall)
            if (!snapshot.draft) {
                var goal by remember { mutableStateOf("") }
                var title by remember { mutableStateOf("マイページの確認") }
                var readyOnly by remember { mutableStateOf(true) }
                HermesTextField(goal, {
                    if (it.length <=
                        2000
                    ) {
                        goal = it
                    }
                }, enabled = enabled, label = {
                    Text(
                        "改修の目的",
                    )
                }, modifier = Modifier.fillMaxWidth().testTag("workbench_goal"))
                if (state.editor != null) {
                    Button(
                        onClick = { onSend("propose", mapOf("goal" to JsonPrimitive(goal))) },
                        enabled = enabled && goal.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("workbench_propose"),
                    ) { Text(if (state.editor.executorConfigured) "専用Botに改修を依頼" else "依頼を保存（実行設定待ち）") }
                    Text("依頼文から画面と条件付きBot処理の候補を作ります。候補を確認・編集・テストしてから反映します。", style = MaterialTheme.typography.bodySmall)
                    Text("手動で下書きを作る", style = MaterialTheme.typography.titleSmall)
                }
                HermesTextField(title, {
                    if (it.length <=
                        200
                    ) {
                        title = it
                    }
                }, enabled = enabled, label = {
                    Text(
                        "画面タイトル",
                    )
                }, modifier = Modifier.fillMaxWidth().testTag("workbench_title"))
                OutlinedButton(onClick = {
                    readyOnly = !readyOnly
                }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("workbench_mode")) {
                    Text(if (readyOnly) "Bot: ログイン待ち・確認中は開始しない" else "Bot: 既存の確認受付を使う")
                }
                Text("この手動操作はタイトルと既存の確認動作から下書きを作ります。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        onSend(
                            "request",
                            mapOf(
                                "goal" to JsonPrimitive(goal),
                                "title" to JsonPrimitive(title),
                                "mode" to JsonPrimitive(if (readyOnly) "enqueue_ready" else "enqueue_existing"),
                            ),
                        )
                    },
                    enabled = enabled && goal.isNotBlank() && title.isNotBlank(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(
                                "workbench_request",
                            ),
                ) {
                    Text("手動の下書きを作成")
                }
                if (snapshot.previous != null) {
                    OutlinedButton(
                        onClick = {
                            confirmation = "rollback"
                        },
                        enabled = enabled,
                        modifier =
                            Modifier.fillMaxWidth().testTag(
                                "workbench_rollback",
                            ),
                    ) { Text("前の版へ戻す") }
                }
            } else {
                Text(snapshot.goal)
                var selected by remember { mutableStateOf(workbenchFiles.first()) }
                var editedFiles by remember(snapshot.active, snapshot.revision) { mutableStateOf(snapshot.files) }
                val edited = editedFiles != snapshot.files
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    workbenchFiles.forEachIndexed { index, file ->
                        TextButton(
                            onClick = {
                                selected = file
                            },
                            modifier =
                                Modifier.testTag(
                                    "workbench_file_$index",
                                ),
                        ) { Text(listOf("契約", "画面", "Bot")[index]) }
                    }
                }
                Text(selected, style = MaterialTheme.typography.labelMedium)
                HermesTextField(
                    value = editedFiles.getValue(selected),
                    onValueChange = {
                        if (it.toByteArray().size <=
                            65536
                        ) {
                            editedFiles = editedFiles + (selected to it)
                        }
                    },
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 400.dp).testTag("workbench_editor"),
                )
                Text(
                    "Botは状態の読み取り・条件分岐・確認受付・理由付きの拒否を編集できます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        onSend(
                            "edit",
                            mapOf("files" to JsonObject(editedFiles.mapValues { JsonPrimitive(it.value) })),
                        )
                    },
                    enabled =
                        enabled && edited,
                    modifier = Modifier.fillMaxWidth().testTag("workbench_save"),
                ) { Text("編集を保存") }
                OutlinedButton(
                    onClick = {
                        onSend("test", emptyMap())
                    },
                    enabled = enabled && !edited,
                    modifier =
                        Modifier.fillMaxWidth().testTag(
                            "workbench_test",
                        ),
                ) { Text("固定テストを実行") }
                Text(if (snapshot.tested) "保存した編集のテスト: 合格" else "保存した編集のテスト: 未合格")
                Text("差分", style = MaterialTheme.typography.titleMedium)
                Text(
                    snapshot.diff.ifBlank {
                        "変更はありません。"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                if (snapshot.diffTruncated) Text("差分が表示上限を超えています。変更を小さくして保存・テストしてください。")
                Button(
                    onClick = { confirmation = "apply" },
                    enabled =
                        enabled && !edited && snapshot.tested && snapshot.diff.isNotEmpty() && !snapshot.diffTruncated,
                    modifier = Modifier.fillMaxWidth().testTag("workbench_apply"),
                ) { Text("差分を確認して反映") }
                OutlinedButton(onClick = {
                    confirmation = "cancel"
                }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("workbench_cancel")) { Text("下書きを破棄") }
            }
        }
    }
    val action = confirmation
    if (action != null && snapshot != null && enabled) {
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    when (action) {
                        "apply" -> "この差分を反映しますか？"
                        "rollback" -> "前の版へ戻しますか？"
                        else -> "下書きを破棄しますか？"
                    },
                )
            },
            text = {
                Text(
                    "対象: $company\n稼働版: ${snapshot.active}\n" +
                        (if (action == "rollback") "復旧先: ${snapshot.previous}\n" else "") +
                        "画面定義と専用Botだけが対象です。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    onSend(action, emptyMap())
                }, modifier = Modifier.testTag("workbench_confirm")) { Text("実行") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("戻る") } },
        )
    }
}
