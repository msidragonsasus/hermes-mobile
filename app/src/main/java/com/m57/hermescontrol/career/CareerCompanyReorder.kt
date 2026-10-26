package com.m57.hermescontrol.career

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class CompanyDragState {
    var id by mutableStateOf<String?>(null)
    var center by mutableFloatStateOf(0f)
    var scrolling: Job? = null

    fun stop() {
        scrolling?.cancel()
        scrolling = null
        id = null
    }

    fun translation(
        list: LazyListState,
        companyId: String,
    ): Float {
        if (id != companyId) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == companyId } ?: return 0f
        return center - item.offset - item.size / 2f
    }
}

@Composable
internal fun CompanyOrderHandle(
    company: CareerCompanyRow,
    visibleIds: List<String>,
    enabled: Boolean,
    list: LazyListState,
    drag: CompanyDragState,
    currentVisible: () -> List<String>,
    onMove: (String, String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val latestMove by rememberUpdatedState(onMove)
    val latestVisible by rememberUpdatedState(currentVisible)
    val scope = rememberCoroutineScope()
    val index = visibleIds.indexOf(company.id)

    fun move(delta: Int): Boolean {
        if (!enabled) return false
        val current = latestVisible()
        val target = current.getOrNull(current.indexOf(company.id) + delta) ?: return false
        latestMove(company.id, target)
        return true
    }
    val actions =
        if (enabled) {
            listOfNotNull(
                if (index > 0) CustomAccessibilityAction("上へ移動") { move(-1) } else null,
                if (index < visibleIds.lastIndex) CustomAccessibilityAction("下へ移動") { move(1) } else null,
            )
        } else {
            emptyList()
        }

    fun crossTarget() {
        val current = latestVisible()
        val slots =
            list.layoutInfo.visibleItemsInfo
                .filter { it.key in current }
                .sortedBy { it.index }
                .map { CompanyOrderSlot(it.key as String, it.offset + it.size / 2f) }
        val target = companyDragTarget(current, slots, company.id, drag.center) ?: return
        latestMove(company.id, target)
    }
    Row {
        Text(
            "≡",
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .padding(spacing.md)
                .testTag("company_order_handle_${company.id}")
                .semantics {
                    contentDescription = "${company.name}、長押しして移動"
                    stateDescription = "${visibleIds.size}社中${index + 1}番目"
                    customActions = actions
                }.pointerInput(company.id, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            drag.stop()
                            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == company.id }
                            if (item != null) {
                                drag.id = company.id
                                drag.center = item.offset + item.size / 2f
                                drag.scrolling =
                                    scope.launch {
                                        while (drag.id == company.id) {
                                            val info = list.layoutInfo
                                            val margin = 80f
                                            val delta =
                                                when {
                                                    drag.center < info.viewportStartOffset + margin -> -16f
                                                    drag.center > info.viewportEndOffset - margin -> 16f
                                                    else -> 0f
                                                }
                                            if (delta != 0f) {
                                                list.scrollBy(delta)
                                                crossTarget()
                                            }
                                            delay(16)
                                        }
                                    }
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (drag.id == company.id) {
                                drag.center += amount.y
                                crossTarget()
                            }
                        },
                        onDragEnd = drag::stop,
                        onDragCancel = drag::stop,
                    )
                },
        )
        TextButton(
            onClick = {
                move(-1)
            },
            enabled = enabled && index > 0,
            modifier =
                Modifier.testTag("company_order_up_${company.id}").semantics {
                    contentDescription =
                        "${company.name}を上へ移動"
                },
        ) { Text("上へ") }
        TextButton(
            onClick = {
                move(1)
            },
            enabled = enabled && index < visibleIds.lastIndex,
            modifier =
                Modifier
                    .testTag(
                        "company_order_down_${company.id}",
                    ).semantics { contentDescription = "${company.name}を下へ移動" },
        ) {
            Text("下へ")
        }
    }
}

@Composable
internal fun rememberCompanyDrag(): CompanyDragState = remember { CompanyDragState() }

@Composable
internal fun CompanyOrderCard(
    editing: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Flat row with a divider: the company list is scanned, not browsed as cards.
    Column(modifier) {
        Box(if (editing) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onOpen)) { content() }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
