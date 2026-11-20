package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.SettingsPickerDialog
import com.m57.hermescontrol.ui.common.SettingsRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Composable
internal fun CareerEntityDialog(
    entity: CareerEntity,
    snapshot: CareerSnapshot?,
    canWrite: Boolean,
    onDismiss: () -> Unit,
    onSend: (String, JsonObject) -> Unit,
    onConsult: () -> Unit = {},
    initiallyEditing: Boolean = false,
    status: String? = null,
    retry: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(initiallyEditing || entity.version == 0) }
    var review by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(entity.data) }
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current
    val submitted = entity.kind == "documents" && entity.data.text("status") == "submitted"
    val proposal = entity.kind == "proposals"
    var confirmedFact by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val editable =
        when (entity.kind) {
            "companies" -> {
                listOf("name", "notes", "official_urls")
            }

            "applications" -> {
                listOf("title", "status", "deadline", "note")
            }

            "tasks" -> {
                listOf("title", "status", "deadline", "note")
            }

            "deadlines" -> {
                listOf("title", "deadline", "note")
            }

            "events" -> {
                listOf("title", "scheduledAt", "note")
            }

            "facts" -> {
                listOf(if ("claim" in data) "claim" else "statement", "category")
            }

            "stories" -> {
                listOf("title", "situation", "action", "result", "learning", "fact_ids", "open_questions")
            }

            "hypotheses" -> {
                listOf("statement", "status")
            }

            "documents" -> {
                listOf("title", "body", "status")
            }

            "profile" -> {
                (
                    entity.data.keys +
                        listOf(
                            "university",
                            "faculty",
                            "graduation_year",
                            "preferences",
                        )
                ).distinct()
            }

            else -> {
                emptyList()
            }
        }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (review) {
                    "変更内容を確認"
                } else if (editing) {
                    "${careerKind(entity.kind)}を編集"
                } else {
                    entity.title
                },
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                status?.let { Text(it) }
                retry?.let { TextButton(onClick = it, shapes = ButtonDefaults.shapes()) { Text("保存結果を再確認") } }
                if (proposal) {
                    Text(entity.data.text("reason") ?: "Hermesからの保存案です。")
                    val proposed = entity.data["data"] as? JsonObject ?: JsonObject(emptyMap())
                    val current =
                        snapshot?.entities?.firstOrNull {
                            it.kind == entity.data.text("kind") &&
                                it.id == entity.data.text("id")
                        }
                    CareerDiff(current?.data ?: JsonObject(emptyMap()), proposed)
                    if (entity.data.text("kind") == "facts") {
                        Row {
                            Checkbox(checked = confirmedFact, onCheckedChange = { confirmedFact = it })
                            Text("この内容を本人の事実として確認する")
                        }
                        Text("確認しない場合、新しい情報は未確認として残します。既存の本人事実の訂正には確認が必要です。")
                    }
                    Text("保存するとサーバーへ反映され、本人事実・経験・ESはGitHubへ同期されます。")
                } else if (review) {
                    CareerDiff(entity.data, data)
                    Text("保存先は接続中のHermesサーバーです。自己分析・ESはprivate GitHubへ同期します。")
                } else if (editing) {
                    editable.forEach { field ->
                        if (field == "status") {
                            val options =
                                when (entity.kind) {
                                    "applications" -> {
                                        listOf(
                                            "planned",
                                            "preparing",
                                            "submitted",
                                            "in_process",
                                            "offered",
                                            "rejected",
                                            "withdrawn",
                                            "closed",
                                        )
                                    }

                                    "documents" -> {
                                        listOf("drafting", "review", "ready", "submitted")
                                    }

                                    "hypotheses" -> {
                                        listOf("active", "supported", "weakened", "rejected", "retired")
                                    }

                                    else -> {
                                        listOf("todo", "doing", "blocked", "done", "cancelled")
                                    }
                                }
                            CareerStatusPicker(
                                data.text(field),
                                options,
                            ) { data = data.with(field to JsonPrimitive(it)) }
                        } else {
                            CareerField(field, data[field]) { data = data.with(field to it) }
                        }
                    }
                    if (entity.kind == "facts") Text("記述した内容は推測と区別して保存します。既存の本人事実を訂正すると、元の記述を残して新しい事実を作ります。")
                    if (entity.kind == "companies") Text("公式採用URLは1行に1件。HTTPSの公式サイトを指定してください。ログイン用URLは含めないでください。")
                } else {
                    Text(careerKind(entity.kind))
                    entity.data.filterKeys { it !in setOf("id", "source") }.forEach { (field, value) ->
                        Text(
                            "${fieldLabel(field)}: ${if (field == "title") {
                                entity.title
                            } else if (field == "status") {
                                careerStatus((value as? JsonPrimitive)?.content)
                            } else {
                                readable(value)
                            }}",
                        )
                    }
                    entity.data.text("source_url")?.takeIf { it.startsWith("https://") }?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("公式ページを開く") }
                    }
                    Text(
                        "版 ${entity.version}${if (entity.dirty != 0) " · 未同期" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (entity.kind == "offers") {
                        Row {
                            Button(
                                onClick = { onSend("decide", decision(entity, "apply")) },
                                enabled = canWrite,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("応募予定へ追加") }
                            TextButton(
                                onClick = { onSend("decide", decision(entity, "dismiss")) },
                                enabled = canWrite,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("見送る") }
                        }
                    }
                    TextButton(onClick = {
                        onConsult()
                        onDismiss()
                    }) { Text("この情報で相談する") }
                    if (editable.isNotEmpty()) {
                        TextButton(
                            onClick = { editing = true },
                            enabled = canWrite && !submitted,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("編集") }
                    }
                    if (entity.kind == "documents") {
                        TextButton(onClick = { history = true }, shapes = ButtonDefaults.shapes()) { Text("保存した版を見る") }
                        TextButton(onClick = {
                            data = data.with("status" to JsonPrimitive("drafting"))
                            editing = true
                        }, enabled = canWrite) { Text("新しい版を作る") }
                    }
                }
            }
        },
        confirmButton = {
            when {
                proposal -> {
                    TextButton(
                        onClick = { onSend("approve", proposalDecision(entity, true, confirmedFact)) },
                        enabled = canWrite,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("確認して反映") }
                }

                review -> {
                    TextButton(onClick = {
                        val action =
                            if (entity.kind ==
                                "documents"
                            ) {
                                "document"
                            } else if (entity.kind == "facts" &&
                                entity.version > 0
                            ) {
                                "correct"
                            } else {
                                "save"
                            }
                        onSend(
                            action,
                            buildJsonObject {
                                put("kind", entity.kind)
                                put("id", entity.id)
                                put("expected_version", entity.version)
                                put("data", data)
                            },
                        )
                    }, enabled = canWrite) { Text("確認して保存") }
                }

                editing -> {
                    TextButton(
                        onClick = { review = true },
                        enabled = canWrite && data != entity.data,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("差分を確認") }
                }

                else -> {
                    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("閉じる") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (proposal) {
                    onSend("approve", proposalDecision(entity, false))
                } else if (review) {
                    review = false
                } else {
                    onDismiss()
                }
            }) { Text(if (proposal) "反映しない" else "戻る") }
        },
    )
    if (history) CareerHistoryDialog(entity) { history = false }
}

@Composable
internal fun CareerField(
    field: String,
    value: JsonElement?,
    onChange: (JsonElement) -> Unit,
) {
    if (value is JsonObject) {
        Text(fieldLabel(field), style = MaterialTheme.typography.titleSmall)
        value.forEach { (child, item) -> CareerField(child, item) { onChange(value.with(child to it)) } }
    } else {
        val array = value is JsonArray || field in setOf("official_urls", "fact_ids", "open_questions")
        val text =
            if (value is JsonArray) {
                value.joinToString("\n") { readable(it) }
            } else {
                readable(value ?: JsonNull)
                    .takeUnless {
                        it ==
                            "未確認"
                    }.orEmpty()
            }
        var rawText by rememberSaveable(field) { mutableStateOf(text) }
        var projectedText by rememberSaveable(field) { mutableStateOf(text) }
        if (text != projectedText) {
            rawText = text
            projectedText = text
        }
        HermesTextField(
            value = if (array) rawText else text,
            onValueChange = { changed ->
                if (array) {
                    rawText = changed
                    projectedText = changed.lines().filter(String::isNotBlank).joinToString("\n")
                }
                onChange(
                    if (array) {
                        JsonArray(
                            changed.lines().filter(String::isNotBlank).map(::JsonPrimitive),
                        )
                    } else {
                        JsonPrimitive(changed)
                    },
                )
            },
            label = { Text(fieldLabel(field)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = if (field in setOf("body", "statement", "claim", "notes", "note")) 3 else 1,
        )
    }
}

@Composable
private fun CareerStatusPicker(
    value: String?,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = true },
            shapes = ButtonDefaults.shapes(),
        ) { Text("状況: ${careerStatus(value)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(careerStatus(option)) }, onClick = {
                    onSelect(option)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun CareerDiff(
    before: JsonObject,
    after: JsonObject,
) {
    (before.keys + after.keys).filter { before[it] != after[it] }.forEach { field ->
        Text(fieldLabel(field), style = MaterialTheme.typography.titleSmall)
        Text("変更前: ${readable(before[field] ?: JsonNull)}")
        Text("変更後: ${readable(after[field] ?: JsonNull)}")
    }
}

@Composable
internal fun CareerConflictSettingsRow(
    conflict: JsonObject,
    snapshot: CareerSnapshot,
    canWrite: Boolean,
    onSend: (String, JsonObject) -> Unit,
) {
    var review by remember { mutableStateOf(false) }
    val entity =
        snapshot.entities.firstOrNull {
            it.id == conflict.text("entity_id") && it.kind == conflict.text("kind")
        }
    SettingsRow(
        "差分を確認",
        subtitle = entity?.title ?: conflict.text("entity_id"),
        subtitleMaxLines = Int.MAX_VALUE,
        chevron = true,
        onClick = { review = true },
    )
    if (review) {
        SettingsPickerDialog("同期の競合", onDismiss = { review = false }) {
            Text("端末・サーバー側: ${readable(conflict["local"] ?: JsonNull)}")
            Text("GitHub側: ${readable(conflict["remote"] ?: JsonNull)}")
            listOf("local" to "サーバー側を残す", "remote" to "GitHub側を使う").forEach { (choice, label) ->
                SettingsRow(
                    title = label,
                    maxLines = Int.MAX_VALUE,
                    enabled = canWrite,
                    onClick = {
                        onSend(
                            "resolve",
                            buildJsonObject {
                                put("id", conflict.getValue("id"))
                                put(
                                    "expected_version",
                                    conflict["expected_version"] ?: JsonPrimitive(entity?.version ?: 0),
                                )
                                put("choice", choice)
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun decision(
    entity: CareerEntity,
    choice: String,
): JsonObject =
    buildJsonObject {
        put("id", entity.id)
        put("expected_version", entity.version)
        put("decision", choice)
    }

private fun proposalDecision(
    entity: CareerEntity,
    approve: Boolean,
    confirmedFact: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("id", entity.id)
        put("expected_version", entity.version)
        put("approve", approve)
        put("confirmed_by_user", confirmedFact)
    }

internal fun readable(value: JsonElement): String =
    when (value) {
        JsonNull -> "未確認"
        is JsonPrimitive -> if (value.content == "旧Shukatsuからの応募・選考") "応募・選考" else value.content
        is JsonArray -> value.joinToString("、") { readable(it) }
        is JsonObject -> value.entries.joinToString("\n") { "${fieldLabel(it.key)}: ${readable(it.value)}" }
    }

internal fun fieldLabel(field: String): String =
    mapOf(
        "title" to "タイトル",
        "name" to "名称",
        "statement" to "内容",
        "claim" to "本人の言葉",
        "category" to "分類",
        "status" to "状況",
        "deadline" to "締切（日時が不明なら日付のみ）",
        "notes" to "メモ",
        "note" to "メモ",
        "official_urls" to "公式採用URL",
        "source_url" to "公式URL",
        "target_year" to "対象年度",
        "observed_at" to "公式確認日時",
        "year_evidence" to "対象年度の根拠",
        "deadline_evidence" to "締切の根拠",
        "deadline_verification" to "締切の再確認状況",
        "previous_deadline_evidence" to "前回確認した締切の根拠",
        "body" to "本文",
        "situation" to "状況",
        "action" to "自分がしたこと",
        "result" to "結果",
        "learning" to "学び",
        "fact_ids" to "根拠の本人事実ID（1行1件）",
        "open_questions" to "未確認のこと（1行1件）",
        "university" to "大学",
        "faculty" to "学部・学科",
        "graduation_year" to "卒業予定年",
        "preferences" to "希望条件",
        "provenance" to "出典",
        "classification" to "確認状況",
        "needs_review" to "再確認",
        "scheduledAt" to "予定日時",
        "company_id" to "企業ID",
    )[field] ?: field
