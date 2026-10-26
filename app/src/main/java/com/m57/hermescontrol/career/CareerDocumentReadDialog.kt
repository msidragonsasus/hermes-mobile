package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@Composable
internal fun CareerDocumentReadDialog(
    document: CareerEntity,
    companyThread: String,
    onDismiss: () -> Unit,
    onAdopt: ((Int) -> Unit)? = null,
    sourceRefs: List<CareerSourceRef> = emptyList(),
    onConsultOverride: ((Int) -> Unit)? = null,
    onEdit: ((Int, String) -> Unit)? = null,
) {
    val latest = document.data.text("version")?.toIntOrNull() ?: 1
    var version by remember(document.id) { mutableStateOf(latest) }
    var body by remember(document.id, version) { mutableStateOf<String?>(null) }
    var status by remember(document.id, version) { mutableStateOf("読み込み中") }
    val repository = remember { DashboardCareerRepository() }
    val spacing = LocalSpacing.current
    val density = LocalDensity.current
    LaunchedEffect(document.id, version) {
        try {
            val result = repository.document(document.id, version)
            body = result.text("body")
            status = if (result.text("github_synced") == "true") "サーバー保存済み・GitHub同期済み" else "サーバー保存済み・GitHub未同期"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            status = "本文を取得できません。接続後に開き直してください。"
        }
    }
    body?.let { CareerHomeReadEffect("document:${document.id}", "$version\n$it") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    Modifier.safeDrawingPadding().padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(document.title, style = MaterialTheme.typography.titleLarge)
                    Text("第$version 版 · $status", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextButton(
                            onClick = { version-- },
                            enabled = version > 1,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("前の版") }
                        TextButton(
                            onClick = { version++ },
                            enabled = version < latest,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("次の版") }
                        TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("戻る") }
                        onEdit?.let { edit ->
                            TextButton(
                                onClick = { body?.let { edit(version, it) } },
                                enabled =
                                    body != null && version == latest,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("編集") }
                        }
                    }
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        MarkdownText(body.orEmpty(), textColor = MaterialTheme.colorScheme.onSurface)
                    }
                    body?.let {
                        Text(
                            "${it.codePointCount(0, it.length)}字",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (onAdopt != null) {
                        Button(
                            onClick = { onAdopt(version) },
                            enabled = body != null,
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("この版を採用") }
                    } else {
                        Button(onClick = {
                            val offer = (document.data["career_context"] as? JsonObject)?.text("offer_id")
                            if (onConsultOverride != null) {
                                onConsultOverride(version)
                            } else {
                                onDismiss()
                                NavigationController.navigateTo(
                                    CareerThreadsKey(
                                        companyThread
                                            .removePrefix(
                                                "company:",
                                            ).takeIf { companyThread.startsWith("company:") }
                                            ?: document.data.text("company_id"),
                                        offer,
                                        "このESを読んで、修正の相談をしたいです。",
                                        CareerTarget("documents", document.id, version, source_refs = sourceRefs),
                                    ),
                                )
                            }
                        }, enabled = body != null && version == latest) { Text("このESの修正を相談") }
                    }
                }
            }
        }
    }
}
