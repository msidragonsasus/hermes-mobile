package com.m57.hermescontrol.career

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun CareerHistoryDialog(
    entity: CareerEntity,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val origin = remember { careerScope() }
    var body by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ESの保存版") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
            ) {
                Text("過去の本文は変更せず保持されています。")
                (entity.data["history_paths"] as? JsonArray).orEmpty().forEach { element ->
                    val path = (element as? JsonPrimitive)?.content.orEmpty()
                    val version = path.substringAfterLast("-v").substringBefore('.').toIntOrNull()
                    if (version != null) {
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    body = DashboardCareerRepository(origin).document(entity.id, version).text("body")
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    body = careerError(error)
                                } finally {
                                    busy = false
                                }
                            }
                        }, enabled = !busy) { Text("第${version}版を見る") }
                    }
                }
                if (busy) Text("読み込み中…")
                body?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("閉じる") } },
    )
}
