package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.m57.hermescontrol.theme.LocalSpacing
import java.util.UUID

@Composable
internal fun AddCompanyDialog(
    state: CareerCompanyState,
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val write = state.companyWrite.takeIf { it.requestId == requestId }
    val unknown = write?.phase == CompanyWritePhase.UNKNOWN
    LaunchedEffect(write?.phase) {
        if (write?.phase == CompanyWritePhase.SAVED) onDismiss()
    }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!state.loading && !unknown) onDismiss() },
        title = { Text("企業を追加") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("企業名") },
                    singleLine = true,
                    enabled = !state.loading && !unknown,
                    isError = name.trim().length > 200,
                    modifier = Modifier.fillMaxWidth().testTag("company_add_name"),
                )
                Text("企業名を接続中のHermesサーバーに保存します。")
                write?.message?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), requestId) },
                enabled = !state.loading && name.isNotBlank() && name.trim().length <= 200,
                modifier = Modifier.testTag("company_add_confirm"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    if (state.loading) {
                        "追加中…"
                    } else if (unknown) {
                        "同じ内容で再試行"
                    } else {
                        "追加"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.loading && !unknown,
                shapes = ButtonDefaults.shapes(),
            ) { Text("キャンセル") }
        },
    )
}

@Composable
internal fun DeleteCompanyDialog(
    state: CareerCompanyState,
    onDelete: (Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val company = state.detail?.company ?: return
    val requestId = rememberSaveable(company.id) { UUID.randomUUID().toString() }
    val version = rememberSaveable(company.id) { company.version }
    val write = state.companyWrite.takeIf { it.requestId == requestId }
    AlertDialog(
        onDismissRequest = { if (!state.loading) onDismiss() },
        title = { Text("${company.title}を削除") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text("企業一覧とホームの表示対象から外し、この企業へのメール取得を停止します。保存済みのES・メール・応募記録は保持します。")
                write?.message?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDelete(version, requestId) },
                enabled = !state.loading && write?.phase != CompanyWritePhase.CONFLICT,
                modifier = Modifier.testTag("company_delete_confirm"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    if (state.loading) {
                        "削除中…"
                    } else if (write?.phase == CompanyWritePhase.UNKNOWN) {
                        "再試行"
                    } else {
                        "削除"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.loading,
                shapes = ButtonDefaults.shapes(),
            ) { Text("キャンセル") }
        },
    )
}
