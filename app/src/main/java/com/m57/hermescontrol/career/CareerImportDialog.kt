package com.m57.hermescontrol.career

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Composable
internal fun CareerImportDialog(
    onDismiss: () -> Unit,
    onImport: (ShukatsuImportResult) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ShukatsuImportResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) {
                uri = chosen
                preview = null
                error = null
                fileName =
                    runCatching {
                        context.contentResolver
                            .query(
                                chosen,
                                arrayOf(OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null,
                            )?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            }
                    }.getOrNull()
            }
        }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Shukatsuから取り込む") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text("Shukatsuで書き出した .shukatsu ファイルを選び、書き出し時の合言葉を入力してください。")
                fileName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Button(
                    onClick = { launcher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        if (uri ==
                            null
                        ) {
                            "ファイルを選ぶ"
                        } else {
                            "ファイルを選び直す"
                        },
                    )
                }
                if (uri != null && preview == null) {
                    HermesTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            error = null
                        },
                        label = {
                            Text("合言葉")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
                if (busy) Text("端末内で復号しています…")
                error?.let { Text(it) }
                preview?.let { projected ->
                    val payload = projected.uploadPayload
                    Text("取り込み内容を確認")
                    payload
                        .getValue(
                            "records",
                        ).jsonArray
                        .groupingBy { it.jsonObject.text("type") }
                        .eachCount()
                        .forEach { (type, count) ->
                            Text("${shukatsuTypeLabel(type)}: $count 件")
                        }
                    Text("パスワード・OAuth情報・メール本文・マイページIDは取り込みません。表示対象の情報だけを保存します。")
                    payload.getValue("skipped").jsonArray.forEach {
                        if (it.jsonObject.text("type") != "CompanyLoginAccount") {
                            Text("取り込まない項目: ${shukatsuTypeLabel(it.jsonObject.text("type"))}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ready = preview
                if (ready != null) {
                    onImport(ready)
                } else {
                    val selected = uri ?: return@TextButton
                    val secret = passphrase.toCharArray()
                    passphrase = ""
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            preview =
                                withContext(Dispatchers.Default) {
                                    val bytes =
                                        withContext(Dispatchers.IO) {
                                            context.contentResolver.openInputStream(selected)?.use {
                                                ShukatsuImport.readFile(it)
                                            }
                                                ?: error("ファイルを開けません。")
                                        }
                                    try {
                                        ShukatsuImport.decrypt(bytes, secret)
                                    } finally {
                                        bytes.fill(0)
                                    }
                                }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error =
                                when (failure) {
                                    is java.io.IOException, is SecurityException -> {
                                        "ファイルを読み込めません。ファイルを選び直してください。"
                                    }

                                    is javax.crypto.BadPaddingException -> {
                                        "合言葉が違うか、ファイルが破損しています。書き出し時の合言葉を確認してください。"
                                    }

                                    is IllegalArgumentException -> {
                                        "転送ファイルの形式や履歴に対応していません。Shukatsuから書き出したファイルを確認してください。"
                                    }

                                    else -> {
                                        "取り込み内容を確認できませんでした。元ファイルは保持しています。"
                                    }
                                }
                        } finally {
                            secret.fill('\u0000')
                            busy = false
                        }
                    }
                }
            }, enabled = !busy && uri != null && (preview != null || passphrase.isNotBlank())) {
                Text(
                    if (preview ==
                        null
                    ) {
                        "復号して確認"
                    } else {
                        "確認して取り込む"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("戻る") } },
    )
}
