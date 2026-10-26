package com.m57.hermescontrol.career

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.home.HomeItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared by the company's source reader and its Home deep link. Reading is never completion. */
@Composable
internal fun CareerSourceResolution(sourceIdentity: String) {
    var item by remember(sourceIdentity) { mutableStateOf<HomeItem?>(null) }
    var status by remember(sourceIdentity) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = remember(sourceIdentity) { CareerCompanyRepository() }
    LaunchedEffect(sourceIdentity) {
        try {
            item = CareerHomeProvider().read().items.firstOrNull { it.id == sourceIdentity && it.category == "action" }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            status = "対応状況を確認できません。"
        }
    }
    item?.let { selected ->
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    repository.mutate(
                        "source-resolved",
                        buildJsonObject {
                            put("id", selected.id)
                            put("version", selected.version)
                        },
                    )
                    item = null
                    status = "対応済みとして記録しました。"
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    status = "対応済みの記録を保存できませんでした。"
                } finally {
                    busy = false
                }
            }
        }) { Text("この案内への対応を終えた") }
        Text("予約・提出そのものは行いません。実際の対応後に記録してください。")
    }
    status?.let { Text(it) }
}
