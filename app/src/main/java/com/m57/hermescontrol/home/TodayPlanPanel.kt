package com.m57.hermescontrol.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.OverviewHeading
import kotlinx.coroutines.delay
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
    scope: String,
    state: TodayPlanState,
) {
    val coroutine = rememberCoroutineScope()
    val spacing = LocalSpacing.current
    var configure by rememberSaveable(scope) { mutableStateOf(false) }
    val settings = state.settings
    // Deadlines expire while Home stays open; re-evaluate labels each minute.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.testTag("today_plan")) {
        TodayPlanContent(
            plan = state.plan,
            busy = state.busy,
            error = state.error,
            enabled = settings?.enabled == true,
            onRefresh = { coroutine.launch { state.refresh() } },
            onConfigure = { configure = !configure },
            onChange = { task, change -> coroutine.launch { state.write(task, change) } },
            profile = AppScope.PROFILE,
            pending = state.pending,
            failures = state.failures,
            recent = state.recent,
            onRetry = { coroutine.launch { state.retry(it) } },
            now = now,
        )
        AnimatedVisibility(configure || settings?.enabled == false) {
            if (settings != null) {
                TodayPlanSettings(scope, settings, state.busy || state.plan?.state == "running") {
                    coroutine.launch { state.save(it) }
                }
            }
        }
    }
}

@Composable
internal fun TodayPlanContent(
    plan: TodayPlan?,
    busy: Boolean,
    error: String?,
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
) {
    val spacing = LocalSpacing.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
    val date = today.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPAN))
    val source = plan ?: TodayPlan()
    val groups = remember(source, now) { source.groups(now) }
    val active = groups.actionable
    val completed = groups.done
    val running = plan?.state == "running"
    val oldPlan = error != null || plan?.plan_day != today.toString()
    var showReview by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun row(
        task: TodayTask,
        role: TodayRowRole,
    ) {
        TodayTaskRow(
            task = task,
            role = role,
            stale = oldPlan || task.stale,
            now = now,
            pending = pending[task.id],
            failure = failures[task.id],
            onChange = { onChange(task, it) },
            onRetry = { onRetry(task) },
        )
    }

    OverviewHeading("今日やること", date)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Button(
            onClick = onRefresh,
            enabled = enabled && !busy && !running,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("today_refresh"),
        ) { Text(if (running) "確認しています…" else "今の情報で調べる") }
        TextButton(onClick = onConfigure, shapes = ButtonDefaults.shapes()) { Text("確認する情報源") }
    }
    if (running || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    // One quiet summary line instead of separate count / timestamp lines.
    val summary =
        buildList {
            if (groups.today.isNotEmpty()) add("今日 ${groups.today.size}件")
            if (groups.overdue.isNotEmpty()) add("期限切れ ${groups.overdue.size}件")
            if (groups.review.isNotEmpty()) add("候補 ${groups.review.size}件")
            if (plan?.checked_at?.isNotBlank() == true) {
                val label = if (plan.isCurrent(now) && error == null) "整理 " else "整理（一部未確認） "
                add(label + todayShortTime(plan.checked_at, today))
            }
        }.joinToString(" · ")
    if (summary.isNotEmpty()) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (plan?.state in setOf("failed", "interrupted")) {
        Text("確認を完了できませんでした。前回の一覧を残しています。", color = MaterialTheme.colorScheme.error)
    }
    // Unverified candidates are disclosed, never silently folded away: an empty today list
    // must not read as "no coursework due".
    val unverifiedNotice =
        when {
            groups.legacy && groups.review.isNotEmpty() -> {
                "このサーバーは項目の分類に未対応のため、${groups.review.size}件を必要か未確認の候補にしています。" +
                    "課題など必要な対応が含まれている可能性があります。"
            }

            groups.unverified > 0 -> {
                "必要か確認できていない候補が${groups.unverified}件あります。課題など必要な対応が含まれている可能性があります。"
            }

            else -> {
                null
            }
        }
    unverifiedNotice?.let {
        InlineNotice(
            text = it,
            tone = NoticeTone.Warning,
            actionLabel = if (showReview) null else "候補を表示",
            onAction = { showReview = true },
            modifier = Modifier.testTag("today_unverified_notice"),
        )
    }
    if (active.isEmpty()) {
        Text(
            when {
                !enabled -> {
                    "メール・manabaの確認先を設定すると、今日の対応をここにまとめます。"
                }

                running -> {
                    "メールや課題を確認して、優先順位を整理しています。"
                }

                unverifiedNotice != null -> {
                    "今日の対応として確認できた項目はありません。候補に必要な対応がないかは未確認です。"
                }

                plan?.isCurrent(now) == true && error == null -> {
                    if (completed.isNotEmpty()) "確認できた対応は完了しています。" else "確認した範囲では、今日必要な対応は見つかりませんでした。"
                }

                else -> {
                    "まだ確認できていない情報があります。「やることなし」とは判断していません。"
                }
            },
        )
    } else {
        // Every confirmed task is listed, overdue ones included: candidates were moved out, so
        // nothing serious hides behind a fold here.
        val firstOverdue = groups.overdue.firstOrNull()?.id
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            active.forEach { task ->
                if (task.id == firstOverdue) TodaySectionLabel("期限切れ")
                row(task, TodayRowRole.Active)
            }
        }
    }
    recent?.let { change ->
        (active + groups.review + groups.excluded + completed).firstOrNull { it.id == change.id }?.let { task ->
            TodayRecentLine(task, change, enabled = task.id !in pending, onChange = { onChange(task, it) })
        }
    }
    if (groups.review.isNotEmpty()) {
        TextButton(
            onClick = { showReview = !showReview },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("today_review_toggle"),
        ) { Text(if (showReview) "候補を閉じる" else "候補${groups.review.size}件を表示") }
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
        TextButton(
            onClick = { showExcluded = !showExcluded },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("today_excluded_toggle"),
        ) { Text(if (showExcluded) "対象外を閉じる" else "対象外${groups.excluded.size}件を表示") }
        AnimatedVisibility(showExcluded) {
            Column { groups.excluded.forEach { row(it, TodayRowRole.Excluded) } }
        }
    }
    if (completed.isNotEmpty()) {
        TextButton(onClick = { showDone = !showDone }, shapes = ButtonDefaults.shapes()) {
            Text(if (showDone) "完了した項目を閉じる" else "完了した${completed.size}件を表示")
        }
        AnimatedVisibility(showDone) {
            Column {
                completed.forEach { row(it, TodayRowRole.Done) }
            }
        }
    }
    TodayCoverageNotice(plan?.coverage.orEmpty(), profile)
}

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
            "$text：${task.title}",
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
 * Title + one meta line. Reason, full title, fetch time, the row's actions and the source link
 * open on tap. Completion is only for confirmed work; candidates are added or excluded instead.
 */
@Composable
private fun TodayTaskRow(
    task: TodayTask,
    role: TodayRowRole,
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
    val warning = LocalHermesStatusColors.current.warning
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var linkError by rememberSaveable(task.id) { mutableStateOf(false) }
    val expired = todayDeadline(task.deadline)?.isPast(now) == true
    val checkable = role == TodayRowRole.Active || role == TodayRowRole.Done
    val candidate = role == TodayRowRole.Candidate || role == TodayRowRole.Excluded
    val urgent = role == TodayRowRole.Active && !task.done && (expired || task.priority == 1)
    val inset = if (checkable) 48.dp else 0.dp
    val idle = pending == null
    Column(Modifier.fillMaxWidth().testTag("today_task_${task.id}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "詳細を閉じる" else "詳細を開く") { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (checkable) {
                // Shows the requested value at once; the plan itself changes only after the server confirms.
                Checkbox(
                    checked = (pending as? TodayChange.Done)?.done ?: task.done,
                    enabled = idle,
                    onCheckedChange = { onChange(TodayChange.Done(it)) },
                    modifier =
                        Modifier.testTag("today_complete_${task.id}").semantics {
                            contentDescription = (if (task.done) "未完了に戻す：" else "完了にする：") + task.title
                            if (!idle) stateDescription = "保存中"
                        },
                )
            }
            Column(
                Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Confirmed work is the point of the list: its title is never cut, at any font size.
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done || candidate) muted else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded || role == TodayRowRole.Active) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    todayTaskMeta(task, stale, now, candidate) + (pending?.let { " · " + it.pendingText() } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (urgent) warning else muted,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.padding(top = 14.dp, start = spacing.xs).size(20.dp),
            )
        }
        failure?.let {
            Row(
                Modifier.fillMaxWidth().padding(start = inset).testTag("today_task_error_${task.id}"),
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
                Modifier.fillMaxWidth().padding(start = inset, bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (task.source_title.isNotBlank() && task.source_title != task.title) {
                    Text(
                        (if (task.source == "gmail") "メール：" else "manaba：") + task.source_title,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                if (task.reason.isNotBlank()) {
                    Text(task.reason, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                val fetched =
                    buildList {
                        if (task.source_checked_at.isNotBlank()) add("情報取得 " + todayShortTime(task.source_checked_at))
                        if (stale) add("最新の状況は未確認")
                    }.joinToString(" · ")
                if (fetched.isNotEmpty()) Text(fetched, style = MaterialTheme.typography.bodySmall, color = muted)
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
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/** Only sources that are not fully checked appear, as one quiet notice. */
@Composable
private fun TodayCoverageNotice(
    coverage: List<TodayCoverage>,
    profile: String,
) {
    val spacing = LocalSpacing.current
    val problems = coverage.filter { it.state !in setOf("updated", "checked") }
    if (problems.isEmpty()) return
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(horizontal = spacing.md, vertical = 6.dp)
            .testTag("today_coverage"),
    ) {
        problems.forEach { item ->
            val fixable = item.state in setOf("auth_required", "setup_required", "not_configured")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .then(
                        if (fixable) {
                            Modifier.clickable(onClickLabel = "対処方法") { help = if (help == item.id) null else item.id }
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

@Composable
private fun TodayPlanSettings(
    scope: String,
    settings: TodaySettings,
    busy: Boolean,
    onSave: (TodaySettings) -> Unit,
) {
    val spacing = LocalSpacing.current
    var email by rememberSaveable(scope, settings) { mutableStateOf(settings.email) }
    var manaba by rememberSaveable(scope, settings) { mutableStateOf(settings.manaba_url) }
    var enabled by rememberSaveable(scope, settings) { mutableStateOf(settings.enabled) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text("確認する情報源", style = MaterialTheme.typography.titleMedium)
        HermesTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("確認するメールアドレス") },
            supportingText = { Text("Google接続に登録済みのアドレス") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !busy,
        )
        TextButton(
            onClick = { NavigationController.navigateTo(SettingsGoogleConnection) },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text("Google接続を確認する")
        }
        HermesTextField(
            value = manaba,
            onValueChange = { manaba = it },
            label = { Text("manabaのホームURL") },
            supportingText = { Text("ログイン情報を含まない https:// で始まるURL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
        )
        TextButton(
            onClick = { manaba = "https://manaba.hit-u.ac.jp/ct/home" },
            enabled = !busy,
            shapes = ButtonDefaults.shapes(),
        ) { Text("一橋大学のmanabaを使う") }
        Text("manabaの初回ログインはサーバー側の準備が必要です。未接続の間は未確認と表示します。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ホームを開いた日に情報を確認する", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
        }
        Text("1日1回、LLMがメール・課題と前回のキャッシュを見て整理します。完了チェックはこの一覧だけに保存します。", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onSave(TodaySettings(email.trim(), manaba.trim(), enabled)) },
            enabled = !busy && (!enabled || email.isNotBlank() || manaba.isNotBlank()),
            shapes = ButtonDefaults.shapes(),
        ) { Text("保存する") }
    }
}
