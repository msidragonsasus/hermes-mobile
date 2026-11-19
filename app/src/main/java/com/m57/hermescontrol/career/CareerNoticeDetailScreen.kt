package com.m57.hermescontrol.career

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@Composable
fun CareerNoticeDetailScreen(key: CareerNoticeDetailKey) {
    var selected by rememberSaveable(key) { mutableStateOf(key.noticeId.takeIf { key.notices.size <= 1 }) }
    var content by remember(key, selected) { mutableStateOf<JsonObject?>(null) }
    var error by remember(key, selected) { mutableStateOf<String?>(null) }
    var retry by rememberSaveable(key, selected) { mutableIntStateOf(0) }
    val context by AuthManager.contextFlow.collectAsState()
    val scope = careerScope()
    LaunchedEffect(key, scope, context, selected, retry) {
        content = null
        error = null
        if (scope != key.scope) {
            error = "接続先が変わりました。就活Appを開き直してください。"
            return@LaunchedEffect
        }
        val id = selected ?: return@LaunchedEffect
        try {
            content =
                CareerCompanyRepository(scope).noticeContent(id).also {
                    check(it.text("company_id") == key.companyId)
                }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = "この案内を確認できません。企業の案内一覧を開いてください。"
        }
    }
    if (selected != null && scope == key.scope) {
        val back = { if (key.notices.size > 1) selected = null else NavigationController.goBack() }
        BackHandler(enabled = key.notices.size > 1, onBack = back)
        CareerSourceReaderScreen(
            title =
                content?.text("title") ?: key.notices
                    .find { it.id == selected }
                    ?.title
                    .orEmpty(),
            body = content?.text("body"),
            error = error,
            onBack = back,
            onOpenOrigin = {
                NavigationController.navigateTo(
                    CareerCompanyKey(key.companyId, CareerCompanyTab.OFFERS),
                )
            },
            onConsult = { NavigationController.navigateTo(CareerConversationKey("company:${key.companyId}")) },
            onRetry = { retry++ },
            url = content?.text("locator").orEmpty(),
        )
        return
    }
    HermesScaffold(title = { Text("就活の案内") }, navigationIcon = NavIcon.Back { NavigationController.goBack() }) {
        Column(Modifier.fillMaxSize()) {
            error?.let { Text(it) }
            if (scope == key.scope && selected == null) {
                CareerNoticeList(key.notices, Modifier.weight(1f)) { notice ->
                    when (notice.source_ref?.kind) {
                        "settings" -> NavigationController.navigateTo(CareerCompanySettingsKey(key.companyId))
                        "mail_settings" -> NavigationController.navigateTo(CareerMailSettingsKey)
                        else -> selected = notice.id
                    }
                }
            }
            if (scope == key.scope) {
                TextButton(onClick = {
                    NavigationController.navigateTo(CareerCompanyKey(key.companyId, CareerCompanyTab.OFFERS))
                }) { Text("企業の案内一覧を開く") }
            }
        }
    }
}

@Composable
internal fun CareerNoticeList(
    notices: List<CareerNotice>,
    modifier: Modifier = Modifier,
    onNoticeClick: (CareerNotice) -> Unit,
) {
    val spacing = LocalSpacing.current
    val ordered = remember(notices) { notices.sortedByDescending { it.seq } }
    LazyColumn(
        modifier.testTag("career_notice_list"),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                ordered.firstOrNull()?.company_name?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge)
                }
                Text("${notices.size}件の案内", style = MaterialTheme.typography.bodyMedium)
                Text("案内を選ぶと詳細を確認できます。", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(ordered, key = { it.id }) { notice ->
            Card(
                onClick = { onNoticeClick(notice) },
                modifier = Modifier.fillMaxWidth().testTag("career_notice_${notice.id}"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    notice.label?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(notice.title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                    if (notice.deadline_text.isNotBlank()) {
                        Text("期限（原文）", style = MaterialTheme.typography.labelMedium)
                        Text(
                            notice.deadline_text,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (notice.quote.isNotBlank() && notice.quote != notice.title &&
                        notice.quote != notice.deadline_text
                    ) {
                        Text("原文の抜粋", style = MaterialTheme.typography.labelMedium)
                        Text(
                            notice.quote,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (notice.source_ref?.kind in setOf("settings", "mail_settings")) "設定を開く" else "案内を読む",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
