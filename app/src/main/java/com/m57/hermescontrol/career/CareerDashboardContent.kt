package com.m57.hermescontrol.career

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.home.HomeItem
import com.m57.hermescontrol.home.HomePage
import com.m57.hermescontrol.home.homeTime
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesPrimaryIndicator
import com.m57.hermescontrol.ui.common.OverviewHeading
import com.m57.hermescontrol.ui.common.OverviewRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun CareerAreaTabs(
    area: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val areas = listOf("ホーム", "企業", "相談", "資料")
    val index = areas.indexOf(area).coerceAtLeast(0)
    val tabs: @Composable () -> Unit = {
        areas.forEach { label ->
            Tab(
                selected = area == label,
                onClick = { onSelect(label) },
                enabled = enabled,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                text = { Text(label) },
                modifier = Modifier.testTag("career_area_$label"),
            )
        }
    }
    if (LocalDensity.current.fontScale > 1.3f) {
        PrimaryScrollableTabRow(
            selectedTabIndex = index,
            edgePadding = 0.dp,
            indicator = { HermesPrimaryIndicator(index) },
            tabs = tabs,
        )
    } else {
        PrimaryTabRow(selectedTabIndex = index, indicator = { HermesPrimaryIndicator(index) }, tabs = tabs)
    }
}

/** Read-only presentation; routes and loading remain owned by the existing screen. */
@Composable
internal fun CareerDashboardContent(
    state: CareerUiState,
    page: HomePage?,
    pageError: String?,
    pageLoading: Boolean,
    onSelect: (CareerEntity) -> Unit,
    onTask: (CareerEntity) -> Unit,
    onAttention: (HomeItem) -> Unit,
    onResult: (HomeItem) -> Unit,
    onSettings: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now(ZoneId.of("Asia/Tokyo")),
    now: Instant = today.atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant(),
) {
    val spacing = LocalSpacing.current
    val entities = state.snapshot?.entities.orEmpty()
    val tasks = careerHomeAgenda(entities, today)
    val actions = careerHomeActions(page, now)
    val attention = actions.filter { it.target.kind == "conversation" }
    val otherActions = careerActionGroups(actions.filter { it.target.kind != "conversation" })
    val proposals = entities.filter { it.kind == "proposals" && it.data.text("status") == "pending" }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("career_agenda"),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.lg, bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            if (state.loading || pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val pageMessage =
                pageError?.let {
                    if (page == null) {
                        "対応が必要な項目・結果を取得できません。"
                    } else {
                        "対応が必要な項目・結果を更新できません。前回取得した内容を表示します。"
                    }
                }
            CareerStatusBanner(careerStatusOf(listOf(state.error, pageMessage, pageError), state.stale), onRefresh)
            if (state.pending != null) TextButton(onClick = onSettings) { Text("未完了の保存操作を確認") }
        }
        if (attention.isEmpty() && proposals.isEmpty()) {
            item {
                val confirmed =
                    page != null && !pageLoading && pageError == null &&
                        state.snapshot != null && !state.loading && !state.stale && state.error == null
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (confirmed) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Text("回答・判断待ち", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    confirmed -> "回答・判断待ちはありません。"
                                    pageLoading -> "最新の回答・判断待ちを確認中です。"
                                    else -> "最新の回答・判断待ちは未確認です。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            item { OverviewHeading("回答・判断待ち") }
            items(attention, key = { "attention:${it.id}" }) { item ->
                OverviewRow(Icons.AutoMirrored.Outlined.Chat, Modifier.clickable { onAttention(item) }) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    if (item.detail.isNotBlank()) Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onAttention(item) }, shapes = ButtonDefaults.shapes()) { Text("相談を開く") }
                }
            }
            items(proposals, key = { "proposal:${it.id}" }) { CareerEntityRow(it) { onSelect(it) } }
        }
        if (otherActions.isNotEmpty()) {
            item { OverviewHeading("対応が必要", "ホームと同じ、締切・案内などの要対応項目です。") }
            items(otherActions, key = { "action:${it.id}" }) { item ->
                CareerHomeActionRow(item, onResult)
            }
        }
        tasks
            .groupBy {
                val date = agendaDate(it.data.text("deadline", "scheduledAt")).date
                when {
                    date == today -> "今日の締切・作業"
                    date != null && !date.isAfter(today.plusDays(7)) -> "1週間以内"
                    else -> "その後・期限未設定"
                }
            }.forEach { (heading, group) ->
                item { OverviewHeading(heading, if (heading == "1週間以内") "直近の選考・提出予定です。" else null) }
                items(group, key = { "agenda:${it.kind}:${it.id}" }) { entity ->
                    CareerAgendaRow(entity, agendaCompany(entity, entities)) { onTask(entity) }
                }
            }
        if (tasks.isEmpty() && otherActions.isEmpty()) {
            item {
                Text(
                    if (page == null || pageLoading || pageError != null || state.snapshot == null ||
                        state.loading || state.stale || state.error != null
                    ) {
                        "最新の作業・予定は未確認です。更新するか企業から募集・締切を確認してください。"
                    } else {
                        "これからの作業・予定はありません。企業から募集・締切を確認できます。"
                    },
                )
            }
        }
        val results = page?.items.orEmpty().filter { it.category == "result" }
        item { OverviewHeading("届いた成果物・結果") }
        if (results.isEmpty()) {
            item {
                Text(
                    if (page == null) "接続後に表示します。" else "まだ届いていません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(results, key = { "result:${it.id}" }) { item ->
            OverviewRow(Icons.Outlined.Description, Modifier.clickable { onResult(item) }) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                if (item.detail.isNotBlank()) Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.updated_at.isNotBlank()) {
                    Text(
                        homeTime(item.updated_at),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
