package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ExposedDropdownField

private val priorityChoices = linkedMapOf("unknown" to "未設定", "high" to "高", "medium" to "中", "low" to "低")
private val stageChoices =
    linkedMapOf(
        "unknown" to "未設定",
        "considering" to "検討中",
        "preparing" to "準備中",
        "applied" to "応募済み",
        "interview" to "面接",
        "offer" to "内定",
        "closed" to "終了",
    )

internal fun LazyListScope.companySelectionPreferenceItems(
    state: CareerCompanyState,
    draft: CompanyManageDraft,
    onPreferences: (String, String, Int) -> Unit,
) {
    val detail = state.detail ?: return
    item(key = "manage_preferences") {
        ManageSection("企業全体の志望度・進捗") {
            val priority = if (draft.dirty) draft.priority else detail.company.data.text("user_priority") ?: "unknown"
            val stage = if (draft.dirty) draft.stage else detail.company.data.text("user_stage") ?: "unknown"
            ExposedDropdownField("志望度", priorityChoices.values.toList(), priorityChoices[priority] ?: "未設定", { label ->
                draft.select(detail.company, newPriority = priorityChoices.entries.first { it.value == label }.key)
            }, enabled = !state.loading)
            ExposedDropdownField("企業単位の進捗", stageChoices.values.toList(), stageChoices[stage] ?: "未設定", { label ->
                draft.select(detail.company, newStage = stageChoices.entries.first { it.value == label }.key)
            }, enabled = !state.loading)
            if (draft.dirty) {
                ManageStatus("未保存の変更", warning = true)
                val conflict =
                    state.preferenceWrite.phase == CompanyWritePhase.CONFLICT &&
                        state.preferenceWrite.requestVersion == draft.version
                if (draft.version != detail.company.version || conflict) {
                    Text("保存済みの記録が変更されています。入力を控えてから「元に戻す」で最新の記録を確認してください。")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                    Button(
                        onClick = { onPreferences(draft.priority, draft.stage, draft.version) },
                        enabled =
                            !state.loading && draft.version == detail.company.version &&
                                !conflict,
                        modifier = Modifier.testTag("company_preferences_save"),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (state.preferenceWrite.phase == CompanyWritePhase.SAVING) "保存中…" else "変更を保存") }
                    TextButton(
                        onClick = { draft.reset() },
                        enabled = !state.loading,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("元に戻す") }
                }
            }
            if (!draft.dirty ||
                state.preferenceWrite.requestVersion == draft.version
            ) {
                WriteFeedback(state.preferenceWrite)
            }
        }
    }
}

@Composable
private fun ManageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(LocalSpacing.current.lg),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ManageStatus(
    text: String,
    warning: Boolean = false,
    success: Boolean = false,
) {
    val colors = LocalHermesStatusColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm), verticalAlignment = Alignment.Top) {
        Icon(
            if (warning) {
                Icons.Default.Warning
            } else if (success) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.Info
            },
            contentDescription = null,
            tint =
                if (warning) {
                    colors.warning
                } else if (success) {
                    colors.success
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun WriteFeedback(write: CompanyWriteState) {
    write.message?.let {
        ManageStatus(
            it,
            warning =
                write.phase in setOf(CompanyWritePhase.FAILED, CompanyWritePhase.CONFLICT, CompanyWritePhase.UNKNOWN),
            success =
                write.phase == CompanyWritePhase.SAVED,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanySenderSheet(
    state: CareerCompanyState,
    draft: CompanyManageDraft,
    onSetSender: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().imePadding().testTag("company_sender_sheet"),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            companySenderItems(state, draft, onSetSender)
            item { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("閉じる") } }
        }
    }
}

internal fun LazyListScope.companySenderItems(
    state: CareerCompanyState,
    draft: CompanyManageDraft,
    onSetSender: (String, Boolean) -> Unit,
) {
    item(key = "company_mail_senders") {
        val spacing = LocalSpacing.current
        val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    "メール送信元",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "このアドレスから届いたメールを表示します。止めても保存済みのメールは残ります。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryText,
                )
                state.detail?.mail_senders.orEmpty().forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Text(rule.address, Modifier.weight(1f))
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onSetSender(rule.address, it) },
                            enabled = !state.loading,
                            modifier = Modifier.semantics { contentDescription = "${rule.address}からの取得" },
                        )
                    }
                }
                val invalid = draft.sender.isNotBlank() && !validCompanySender(draft.sender)
                OutlinedTextField(
                    value = draft.sender,
                    onValueChange = { draft.sender = it },
                    label = { Text("送信元メールアドレス") },
                    singleLine = true,
                    enabled = !state.loading,
                    isError = invalid,
                    supportingText = { if (invalid) Text("メールアドレスを確認してください。例：recruit@example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(
                        onClick = { onSetSender(draft.sender.trim(), true) },
                        enabled = !state.loading && validCompanySender(draft.sender),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(if (state.senderWrite.phase == CompanyWritePhase.SAVING) "保存中…" else "送信元を追加")
                    }
                }
                WriteFeedback(state.senderWrite)
            }
        }
    }
}
