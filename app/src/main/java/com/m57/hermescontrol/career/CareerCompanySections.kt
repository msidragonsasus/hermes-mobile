package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.initializer
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Composable
internal fun CareerCompanyOverview(
    detail: CareerCompanyDetail,
    accounts: List<ShukatsuLoginAccount>,
    select: (CareerEntity) -> Unit,
) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)) {
        CompanyPortalActions(
            detail,
            accounts,
        ) { NavigationController.navigateTo(CareerCompanySettingsKey(detail.company.id)) }
        Text(detail.company.title, style = MaterialTheme.typography.headlineSmall)
        Text("募集中 ${detail.current_offer_count}件")
        detail.offers
            .mapNotNull { it.data.text("deadline") }
            .minOrNull()
            ?.let { Text("次の募集締切 $it") }
        detail.records
            .filter {
                it.kind == "applications"
            }.forEach { Text("${it.title} · ${careerStatus(it.data.text("status"))}") }
        val urls =
            (detail.company.data["official_urls"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)
                    ?.content
            }
        urls.filter { it.startsWith("https://") }.forEach { url ->
            TextButton(onClick = { uri.openUri(url) }, shapes = ButtonDefaults.shapes()) { Text("公式サイトを開く") }
        }
        Text("関連するES・資料", style = MaterialTheme.typography.titleMedium)
        detail.documents.forEach { document -> TextButton(onClick = { select(document) }) { Text(document.title) } }
        if (detail.documents.isEmpty()) Text("保存済みの資料はありません。募集・締切からESを開けます。")
    }
}

@Composable
internal fun CareerSelectionActions(
    companyId: String,
    offerId: String?,
    select: (CareerEntity) -> Unit,
) {
    FlowRow {
        listOf("applications" to "応募状況を追加", "tasks" to "作業を追加", "events" to "予定・結果を追加").forEach { (kind, label) ->
            TextButton(onClick = {
                select(
                    CareerEntity(
                        kind,
                        UUID.randomUUID().toString(),
                        0,
                        buildJsonObject {
                            put("company_id", companyId)
                            offerId?.let { put("offer_id", it) }
                            put("title", "")
                            put("status", if (kind == "applications") "planned" else "todo")
                        },
                    ),
                )
            }) { Text(label) }
        }
    }
}

@Composable
internal fun careerNoteModel(company: CareerEntity): CareerNoteViewModel {
    val app = LocalContext.current.applicationContext
    val connectionScope = careerScope()
    val local = remember(connectionScope) { CareerLocalStore(app, connectionScope) }
    return androidx.lifecycle.viewmodel.compose.viewModel(
        key = "company-note:$connectionScope:${company.id}",
        factory =
            androidx.lifecycle.viewmodel.viewModelFactory {
                initializer { CareerNoteViewModel(company, local) }
            },
    )
}

@Composable
internal fun CareerCompanyNotes(
    company: CareerEntity,
    state: CareerUiState,
    model: CareerViewModel,
) {
    val noteModel = careerNoteModel(company)
    val note by noteModel.state.collectAsState()
    var reviewing by remember { mutableStateOf(false) }
    val body = note.body
    val canResolve =
        state.pending == null ||
            (state.pending.payload.text("kind") == "companies" && state.pending.payload.text("id") == company.id)
    val latest = state.snapshot?.entities?.firstOrNull { it.kind == "companies" && it.id == company.id }
    LaunchedEffect(latest) { noteModel.acknowledge(latest) }
    Column {
        Text("企業メモ", style = MaterialTheme.typography.titleMedium)
        HermesTextField(body, {
            noteModel.change(it)
        }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("気づいたこと・確認したいこと") }, enabled = note.loaded)
        note.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(if (note.savedLocally) "端末に下書き保存済み" else "端末へ保存中")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it) }
        TextButton(
            onClick = model::refresh,
            enabled = !state.loading,
            shapes = ButtonDefaults.shapes(),
        ) { Text("保存情報を再読込") }
        if (latest != null && latest.version != note.version && !state.stale && canResolve) {
            TextButton(
                onClick = { reviewing = true },
                enabled = !state.busy,
                shapes = ButtonDefaults.shapes(),
            ) { Text("最新版と下書きを比較") }
        }
        if (state.pending != null) TextButton(onClick = model::retry, enabled = !state.busy) { Text("保存結果を再確認") }
        TextButton(onClick = {
            noteModel.persist {
                model.send(
                    "save",
                    buildJsonObject {
                        put("kind", "companies")
                        put("id", company.id)
                        put("expected_version", note.version)
                        put("data", JsonObject((latest ?: company).data + ("notes" to JsonPrimitive(body))))
                    },
                )
            }
        }, enabled = note.loaded && !state.stale && !state.busy && state.pending == null) { Text("メモを保存") }
        TextButton(onClick = {
            noteModel.persist {
                NavigationController.navigateTo(
                    CareerThreadsKey(
                        company.id,
                        prompt = "この企業のメモについて相談したいです。\n$body",
                        target = CareerTarget("companies", company.id),
                    ),
                )
            }
        }) { Text("この内容について相談") }
    }
    if (reviewing && latest != null) {
        AlertDialog(
            onDismissRequest = { reviewing = false },
            title = { Text("企業メモの保存競合") },
            text = {
                Column {
                    Text("サーバーの最新版：${latest.data.text("notes").orEmpty()}")
                    Text("端末の下書き：$body")
                    Text("下書きを保持して最新版を編集の基準にします。未完了の再試行は取りやめます。既に保存された変更は取り消しません。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.discardPendingAndThen { noteModel.useReviewedVersion(latest.version) }
                    reviewing = false
                }, enabled = !state.busy && !state.stale && canResolve) { Text("確認した最新版を基準にする") }
            },
            dismissButton = { TextButton(onClick = { reviewing = false }) { Text("戻る") } },
        )
    }
}
