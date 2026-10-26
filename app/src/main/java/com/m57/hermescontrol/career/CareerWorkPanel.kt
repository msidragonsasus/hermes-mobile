package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.theme.LocalSpacing

@Composable
internal fun CareerWorkPanel(
    state: CareerCoachUiState,
    records: CareerSnapshot?,
    actions: CareerCoachActions,
    onRegisterCompany: (CareerEntity) -> Unit,
) {
    val jobs =
        listOf(
            Triple("self_analysis", "自己分析", "経験を整理して、自分の強みを一緒に考えたいです。"),
            Triple("es_document", "ESの下書き・保存", "ESの下書きを作り、文書として保存したいです。"),
            Triple("official_research", "登録企業の募集確認", "登録した企業の公式採用URLを確認して、募集の変更点を整理してください。"),
        )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(LocalSpacing.current.md),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        items(jobs, key = { it.first }) { (id, title, prompt) ->
            val capability = state.capabilities?.work?.firstOrNull { it.id == id }
            val status = if (!state.capabilitiesChecked) "checking" else capability?.state ?: "unsupported"
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(LocalSpacing.current.md),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (status) {
                            "available" -> "利用可能"
                            "setup_required" -> "設定が必要"
                            "checking" -> "確認中"
                            else -> "未対応"
                        },
                    )
                    if (id == "official_research") Text("登録した公式URLの募集情報を確認します。")
                    if (status == "available") {
                        TextButton(
                            onClick = {
                                if (id ==
                                    "official_research"
                                ) {
                                    actions.research()
                                } else {
                                    actions.send(prompt)
                                }
                            },
                            enabled =
                                !state.busy && state.pending == null,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("依頼する") }
                    } else if (id == "official_research" && records != null &&
                        (
                            capability?.missing_company_ids.orEmpty().isNotEmpty() ||
                                records.entities.none { it.kind == "companies" }
                        )
                    ) {
                        val missing =
                            records.entities.filter {
                                it.kind == "companies" &&
                                    it.id in capability?.missing_company_ids.orEmpty()
                            }
                        missing.forEach { company ->
                            TextButton(
                                onClick = { onRegisterCompany(company) },
                                enabled = !state.stale,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("${company.title} の公式URLを登録") }
                        }
                        if (records.entities.none {
                                it.kind == "companies"
                            }
                        ) {
                            TextButton(onClick = {
                                onRegisterCompany(newCareerEntity("companies", records))
                            }, enabled = !state.stale) { Text("企業と公式URLを登録") }
                        }
                    } else {
                        TextButton(
                            onClick = actions.refreshCapabilities,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("準備状態を再確認") }
                    }
                }
            }
        }
    }
}
