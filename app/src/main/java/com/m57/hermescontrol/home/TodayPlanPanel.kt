package com.m57.hermescontrol.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SectionHeader
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TodayPlanPanel(
    state: TodayPlanState,
    now: Instant,
    blockedByAuth: Boolean,
    failureCovered: Boolean,
    showFocus: Boolean,
    offline: Boolean,
    additionalTimeline: List<HomeTimelineItem>,
    onConfigure: () -> Unit,
    onReload: () -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    Column(Modifier.testTag("today_plan")) {
        TodayPlanContent(
            plan = state.plan,
            busy = state.busy,
            failure = state.failure,
            enabled = state.settings?.enabled == true,
            settingsKnown = state.settings != null,
            blockedByAuth = blockedByAuth,
            failureCovered = failureCovered,
            showFocus = showFocus,
            offline = offline,
            additionalTimeline = additionalTimeline,
            onRefresh = { coroutine.launch { state.refresh() } },
            onConfigure = onConfigure,
            onReload = onReload,
            onChange = { task, change -> coroutine.launch { state.write(task, change) } },
            cacheWriteFailed = state.cacheWriteFailed,
            profile = AppScope.PROFILE,
            pending = state.pending,
            failures = state.failures,
            recent = state.recent,
            onRetry = { coroutine.launch { state.retry(it) } },
            now = now,
        )
    }
}

@Composable
internal fun TodayPlanContent(
    plan: TodayPlan?,
    busy: Boolean,
    failure: HomeFailure?,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onChange: (TodayTask, TodayChange) -> Unit,
    profile: String = "default",
    pending: Map<String, TodayChange> = emptyMap(),
    failures: Map<String, TodayRowFailure> = emptyMap(),
    recent: TodayRecent? = null,
    onRetry: (TodayTask) -> Unit = { task -> failures[task.id]?.let { onChange(task, it.change) } },
    now: Instant = Instant.now(),
    settingsKnown: Boolean = true,
    blockedByAuth: Boolean = false,
    failureCovered: Boolean = false,
    onReload: () -> Unit = {},
    showFocus: Boolean = true,
    offline: Boolean = false,
    additionalTimeline: List<HomeTimelineItem> = emptyList(),
    cacheWriteFailed: Boolean = false,
) {
    val spacing = LocalSpacing.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
    val source = plan ?: TodayPlan()
    val groups = remember(source, now) { source.groups(now) }
    val available = enabled || !settingsKnown
    val active = if (available) groups.actionable else emptyList()
    val completed = groups.done
    val running = plan?.state == "running"
    val runFailed = plan?.runFailed() == true
    val oldPlan = offline || failure != null || plan?.isCurrent(now) != true || runFailed
    var showReview by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun row(
        task: TodayTask,
        role: TodayRowRole,
        focus: Boolean = false,
    ) {
        TodayTaskRow(
            task = task,
            role = role,
            focus = focus,
            stale = oldPlan || task.stale,
            now = now,
            pending = pending[task.id],
            failure = failures[task.id],
            onChange = { onChange(task, it) },
            onRetry = { onRetry(task) },
        )
    }

    val focus = active.firstOrNull().takeIf { showFocus }
    val upcomingTasks = if (available) groups.today.filterNot { it.id == focus?.id } else emptyList()
    val overdue = if (available) groups.overdue.filterNot { it.id == focus?.id } else emptyList()
    if (focus != null) {
        row(focus, TodayRowRole.Active, focus = true)
    }
    SectionHeader(
        title = "今日のタイムライン",
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xs),
        trailing = {
            if (running) {
                Text("確認しています…", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                TextButton(
                    onClick = onRefresh,
                    enabled = enabled && !busy && !blockedByAuth && !offline,
                    modifier = Modifier.testTag("today_refresh"),
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = muted),
                ) { Text("今の情報で調べる") }
            }
        },
    )
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    val timeline =
        upcomingTasks.map { task ->
            TodayTimelineEntry(task.deadline) { row(task, TodayRowRole.Active) }
        } +
            additionalTimeline.map { item ->
                TodayTimelineEntry(item.time) { HomeTimelineRow(item, now) }
            }
    timeline.sortedBy { homeDeadlineInstant(it.time) ?: Instant.MAX }.forEach { it.content() }
    if (overdue.isNotEmpty()) {
        TodaySectionLabel("期限切れ")
        overdue.forEach { row(it, TodayRowRole.Active) }
    }
    if (!available) {
        Text(
            "メール・manabaの確認先を設定すると、今日の対応をここにまとめます。",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        TextButton(onClick = onConfigure, shapes = ButtonDefaults.shapes()) { Text("設定する") }
        return
    }
    val showStale = settingsKnown && failure != null && !failureCovered
    val unverified = (groups.legacy && groups.review.isNotEmpty()) || groups.unverified > 0
    val verified = !oldPlan && plan.coverage.all { it.state in setOf("updated", "checked") }
    if (!settingsKnown && failure != null && !failureCovered) {
        InlineNotice(
            text = "今日やることを読み込めませんでした。",
            tone = NoticeTone.Warning,
            actionLabel = "再試行",
            onAction = onReload,
        )
    }
    if (active.isEmpty() && plan == null && failure == null && !blockedByAuth && !offline) {
        LoadingState(modifier = Modifier.padding(vertical = spacing.md), subtitle = "今日の情報を確認しています…")
    } else if (active.isEmpty() && (plan != null || enabled)) {
        val emptyMessage =
            when {
                running -> {
                    "メールや課題を確認して、優先順位を整理しています。"
                }

                unverified -> {
                    "今日の対応として確認できた項目はありません。候補に必要な対応がないかは未確認です。"
                }

                verified -> {
                    if (completed.isNotEmpty()) "確認できた対応は完了しています。" else "確認した範囲では、今日必要な対応は見つかりませんでした。"
                }

                else -> {
                    "まだ確認できていない情報があります。「やることなし」とは判断していません。"
                }
            }
        if (verified && !unverified && !running) {
            if (timeline.isEmpty()) EmptyState(title = emptyMessage)
        } else {
            Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }
    val freshness =
        when {
            runFailed -> {
                "前回の一覧 · 確認を完了できませんでした"
            }

            showStale -> {
                "最新情報を確認できませんでした"
            }

            plan?.checked_at?.isNotBlank() == true -> {
                val label = if (plan.isCurrent(now) && failure == null) "最終確認 " else "前回の確認 "
                label + todayShortTime(plan.checked_at, today)
            }

            else -> {
                null
            }
        }
    if (freshness != null) {
        Text(
            freshness,
            modifier = Modifier.padding(top = spacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = if (runFailed || showStale) LocalHermesStatusColors.current.warning else muted,
        )
    }
    recent?.let { change ->
        (active + groups.review + groups.excluded + completed).firstOrNull { it.id == change.id }?.let { task ->
            TodayRecentLine(task, change, enabled = task.id !in pending, onChange = { onChange(task, it) })
        }
    }
    if (groups.review.isNotEmpty()) {
        HomeFoldRow(
            label = if (showReview) "候補を閉じる" else "候補${groups.review.size}件を表示",
            supporting =
                when {
                    groups.legacy -> "必要か未確認 ${groups.review.size}件 · サーバーが分類に未対応"
                    groups.unverified > 0 -> "必要か未確認 ${groups.unverified}件"
                    else -> null
                },
            expanded = showReview,
            onToggle = { showReview = !showReview },
            modifier = Modifier.testTag("today_review_toggle"),
        )
        AnimatedVisibility(showReview) {
            Column(Modifier.testTag("today_review")) {
                Text(
                    "今日の件数には含めていません。必要なものは「やることに追加」、関係ないものは「対象外」にできます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                groups.review.forEach { row(it, TodayRowRole.Candidate) }
            }
        }
    }
    if (groups.excluded.isNotEmpty()) {
        HomeFoldRow(
            label = if (showExcluded) "対象外を閉じる" else "対象外${groups.excluded.size}件を表示",
            expanded = showExcluded,
            onToggle = { showExcluded = !showExcluded },
            modifier = Modifier.testTag("today_excluded_toggle"),
        )
        AnimatedVisibility(showExcluded) {
            Column { groups.excluded.forEach { row(it, TodayRowRole.Excluded) } }
        }
    }
    if (completed.isNotEmpty()) {
        HomeFoldRow(
            label = if (showDone) "完了した項目を閉じる" else "完了した${completed.size}件を表示",
            expanded = showDone,
            onToggle = { showDone = !showDone },
        )
        AnimatedVisibility(showDone) {
            Column {
                completed.forEach { row(it, TodayRowRole.Done) }
            }
        }
    }
    TodayCoverageNotice(plan?.coverage.orEmpty(), profile)
    if (cacheWriteFailed) {
        Text("オフライン用の保存を確認できませんでした。", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

private data class TodayTimelineEntry(
    val time: String?,
    val content: @Composable () -> Unit,
)

/** Which list a row sits in; decides its controls so the same role behaves the same everywhere. */
private enum class TodayRowRole { Active, Candidate, Excluded, Done }

/**
 * The server's answer to the last change, worded from where the task actually landed. Undo is
 * offered only where it restores the previous state exactly.
 */
@Composable
private fun TodayRecentLine(
    task: TodayTask,
    change: TodayRecent,
    enabled: Boolean,
    onChange: (TodayChange) -> Unit,
) {
    val (text, undo) =
        when (val value = change.change) {
            is TodayChange.Done -> {
                "完了にしました" to TodayChange.Done(false).takeIf { value.done && task.done }
            }

            is TodayChange.Interest -> {
                when {
                    !value.interested -> {
                        "対象外にしました" to TodayChange.Interest(true).takeIf { change.wasActionable }
                    }

                    task.actionability == TODAY_ACTIONABLE -> {
                        "やることに追加しました" to null
                    }

                    else -> {
                        "選択を保存しました。サーバーの判定では候補のままです" to null
                    }
                }
            }
        }
    Row(
        Modifier.fillMaxWidth().testTag("today_recent"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Text(
            "$text：${if (task.source == "gmail") task.mailCopy().title else task.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        undo?.let {
            TextButton(onClick = { onChange(it) }, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text("元に戻す")
            }
        }
    }
}

/** Section break per DESIGN.md: labelMedium in `outline`, 16dp above, 4dp below. */
@Composable
private fun TodaySectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * The focus button and compact rows expand the same details and source link.
 * Completion is only for confirmed work; candidates are added or excluded instead.
 */
@Composable
private fun TodayTaskRow(
    task: TodayTask,
    role: TodayRowRole,
    focus: Boolean,
    stale: Boolean,
    now: Instant,
    pending: TodayChange?,
    failure: TodayRowFailure?,
    onChange: (TodayChange) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mail = remember(task) { if (task.source == "gmail") task.mailCopy() else null }
    val title = mail?.title ?: task.title
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var linkError by rememberSaveable(task.id) { mutableStateOf(false) }
    val checkable = role == TodayRowRole.Active || role == TodayRowRole.Done
    val candidate = role == TodayRowRole.Candidate || role == TodayRowRole.Excluded
    val leadingCheckbox = role == TodayRowRole.Done
    val inset = if (leadingCheckbox) 48.dp else 0.dp
    val idle = pending == null
    val titleLines =
        when {
            mail != null && title.length > 42 -> 2
            expanded || role == TodayRowRole.Active -> Int.MAX_VALUE
            else -> 3
        }

    @Composable
    fun completion() {
        Checkbox(
            checked = (pending as? TodayChange.Done)?.done ?: task.done,
            enabled = idle,
            onCheckedChange = { onChange(TodayChange.Done(it)) },
            modifier =
                Modifier.testTag("today_complete_${task.id}").semantics {
                    contentDescription = (if (task.done) "未完了に戻す：" else "完了にする：") + title
                    if (!idle) stateDescription = "保存中"
                },
        )
    }
    Column(Modifier.fillMaxWidth().testTag("today_task_${task.id}")) {
        if (focus) {
            val details =
                listOfNotNull(
                    homeFocusDeadline(task.deadline, now),
                    "メール".takeIf { mail != null },
                    "前回の情報".takeIf { stale && mail == null },
                    pending?.pendingText(),
                )
            HomeFocusAction(
                title = title,
                details = details.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                actionLabel = if (expanded) "詳細を閉じる" else "詳細を見る",
                actionModifier = Modifier.semantics { stateDescription = foldStateDescription(expanded) },
                onOpen = { expanded = !expanded },
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (expanded) "詳細を閉じる" else "詳細を開く") { expanded = !expanded }
                    .semantics { stateDescription = foldStateDescription(expanded) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (leadingCheckbox) completion()
                Column(
                    Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (role == TodayRowRole.Active) HomeTimelineTime(task.deadline, now)
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (task.done || candidate) muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta =
                        if (role == TodayRowRole.Active && mail != null) {
                            listOf("メール")
                        } else if (role == TodayRowRole.Active) {
                            listOfNotNull(
                                "manaba",
                                task.source_title.takeIf { it.isNotBlank() && it != task.title },
                                "前回の情報".takeIf { stale },
                            )
                        } else {
                            listOf(todayTaskMeta(task, stale, now, candidate))
                        } + listOfNotNull(pending?.pendingText())
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.padding(top = 14.dp, start = spacing.xs).size(20.dp),
                )
            }
        }
        failure?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = inset)
                    .testTag("today_task_error_${task.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                // An unsupported server or connection cannot be fixed by trying again.
                if (!it.unavailable) {
                    TextButton(
                        onClick = onRetry,
                        enabled = idle,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.testTag("today_retry_${task.id}"),
                    ) { Text("再試行") }
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = inset)
                    .padding(bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (checkable && !leadingCheckbox) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        completion()
                        Text("完了の記録", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                if (mail != null) {
                    mail.facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                } else {
                    if (task.source_title.isNotBlank() && task.source_title != task.title) {
                        Text("manaba：" + task.source_title, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    if (task.reason.isNotBlank()) {
                        Text(
                            task.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = muted,
                        )
                    }
                    val fetched =
                        buildList {
                            if (task.source_checked_at.isNotBlank()) {
                                add("情報取得 " + todayShortTime(task.source_checked_at))
                            }
                            if (stale) add("最新の状況は未確認")
                        }.joinToString(" · ")
                    if (fetched.isNotEmpty()) Text(fetched, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TodayRowActions(task, role, now, idle, onChange)
                    TextButton(
                        onClick = {
                            linkError =
                                runCatching {
                                    val uri = URI(task.url)
                                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
                                    uriHandler.openUri(task.url)
                                }.isFailure
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("元の情報を開く") }
                }
                if (linkError) Text("元の情報を開けませんでした。", color = MaterialTheme.colorScheme.error)
                if (mail != null && mail.additional.isNotEmpty()) {
                    var showAdditional by rememberSaveable(task.id) { mutableStateOf(false) }
                    HomeFoldRow(
                        label = if (showAdditional) "補足を閉じる" else "補足を見る",
                        expanded = showAdditional,
                        onToggle = { showAdditional = !showAdditional },
                    )
                    if (showAdditional) {
                        mail.additional.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                    }
                }
            }
        }
        if (!focus) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Persistent choices, saved by the server per task ID. 対象外 for confirmed work and candidates;
 * やることに追加 only where it makes sense (not expired or closed); an excluded row can be brought back.
 */
@Composable
private fun TodayRowActions(
    task: TodayTask,
    role: TodayRowRole,
    now: Instant,
    enabled: Boolean,
    onChange: (TodayChange) -> Unit,
) {
    @Composable
    fun action(
        label: String,
        tag: String,
        change: TodayChange,
    ) = TextButton(
        onClick = { onChange(change) },
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.testTag("${tag}_${task.id}"),
    ) { Text(label) }

    when (role) {
        TodayRowRole.Active -> {
            if (!task.done) action("対象外", "today_dismiss", TodayChange.Interest(false))
        }

        TodayRowRole.Candidate -> {
            if (task.canAdd(now)) action("やることに追加", "today_add", TodayChange.Interest(true))
            action("対象外", "today_dismiss", TodayChange.Interest(false))
        }

        TodayRowRole.Excluded -> {
            if (task.canAdd(now)) {
                action("やることに追加", "today_add", TodayChange.Interest(true))
            } else {
                action("候補に戻す", "today_add", TodayChange.Interest(true))
            }
        }

        TodayRowRole.Done -> {}
    }
}

private fun TodayChange.pendingText(): String =
    when (this) {
        is TodayChange.Done -> if (done) "完了を保存中…" else "未完了に戻しています…"
        is TodayChange.Interest -> if (interested) "やることに追加しています…" else "対象外として保存中…"
    }

/** One source summary with recovery instructions available on expansion. */
@Composable
private fun TodayCoverageNotice(
    coverage: List<TodayCoverage>,
    profile: String,
) {
    val spacing = LocalSpacing.current
    val problems = coverage.filter { it.state !in setOf("updated", "checked") }
    if (problems.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HomeFoldRow(
        label = "情報源の確認状況",
        supporting =
            problems.takeUnless { expanded }?.joinToString(" · ") { item ->
                val label = if (item.state == "partial") "一部未確認" else todayCoverageLabel(item.state)
                "${item.label}：$label"
            },
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = Modifier.testTag("today_coverage"),
    )
    AnimatedVisibility(expanded) {
        Column(Modifier.padding(start = spacing.sm)) {
            problems.forEach { item ->
                val fixable = item.state in setOf("auth_required", "setup_required", "not_configured")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (fixable) {
                                Modifier.clickable(onClickLabel = "対処方法") {
                                    help =
                                        if (help == item.id) null else item.id
                                }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Icon(
                        if (fixable) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                        contentDescription = null,
                        tint = if (fixable) LocalHermesStatusColors.current.warning else muted,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "${item.label}：${todayCoverageLabel(item.state)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (fixable) {
                        Text(
                            if (help == item.id) "閉じる" else "どこで？",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                AnimatedVisibility(help == item.id) {
                    TodayCoverageHelp(item, profile)
                }
            }
        }
    }
}

@Composable
private fun TodayCoverageHelp(
    item: TodayCoverage,
    profile: String,
) {
    val spacing = LocalSpacing.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(start = 28.dp, bottom = spacing.sm).testTag("today_coverage_help_${item.id}"),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (item.id == "manaba") {
            Text(
                "manabaのログインはアプリではなく、Hermesサーバーで1回だけ行います。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "1. Hermesサーバーでtoday_homeプラグインのフォルダに移り、次のコマンドを実行する（ブラウザ画面を出せる環境が必要）\n" +
                    "2. 開いたブラウザでmanabaにログインし、manabaのホームに戻る\n" +
                    "3. コマンドの画面でEnterを押す",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            SelectionContainer {
                Text(
                    "python -m today_core.manaba_login --profile $profile",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Text(
                "ログイン状態はサーバー内にだけ保存されます。パスワードをチャットに書く必要はありません。",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        } else {
            Text("メールはGoogle接続のアカウントで確認します。", style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { NavigationController.navigateTo(SettingsGoogleConnection) },
                shapes = ButtonDefaults.shapes(),
            ) { Text("Google接続を確認する") }
        }
    }
}

/** `今日 14:05` / `昨日 09:12` / `明日 17:00` / `10月21日 12:43` (Asia/Tokyo). */
internal fun todayShortTime(
    value: String,
    today: LocalDate = LocalDate.now(ZoneId.of("Asia/Tokyo")),
): String =
    runCatching {
        val time = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Tokyo"))
        val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN))
        when (time.toLocalDate()) {
            today -> "今日 $clock"
            today.minusDays(1) -> "昨日 $clock"
            today.plusDays(1) -> "明日 $clock"
            else -> homeOverviewTime(value)
        }
    }.getOrDefault(homeOverviewTime(value))
