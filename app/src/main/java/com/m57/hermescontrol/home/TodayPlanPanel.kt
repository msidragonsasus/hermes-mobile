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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.SettingsGoogleConnection
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.OverviewHeading
import kotlinx.coroutines.launch
import java.net.URI
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
    val profile by AuthManager.activeProfileId.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.testTag("today_plan")) {
        TodayPlanContent(
            plan = state.plan,
            busy = state.busy,
            error = state.error,
            enabled = settings?.enabled == true,
            onRefresh = { coroutine.launch { state.refresh() } },
            onConfigure = { configure = !configure },
            onComplete = { coroutine.launch { state.complete(it) } },
            profile = profile ?: "default",
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
    onComplete: (TodayTask) -> Unit,
    profile: String = "default",
) {
    val spacing = LocalSpacing.current
    val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
    val date = today.format(DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPAN))
    val active = plan?.tasks.orEmpty().filterNot { it.done }
    val completed = plan?.tasks.orEmpty().filter { it.done }
    val running = plan?.state == "running"
    val oldPlan = error != null || plan?.plan_day != today.toString()
    var showDone by rememberSaveable { mutableStateOf(false) }
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
            if (active.isNotEmpty()) add("未完了 ${active.size}件")
            if (plan?.checked_at?.isNotBlank() == true) {
                add((if (plan.isCurrent() && error == null) "整理 " else "整理（一部未確認） ") + todayShortTime(plan.checked_at))
            }
        }.joinToString(" · ")
    if (summary.isNotEmpty()) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (plan?.state in setOf("failed", "interrupted")) {
        Text("確認を完了できませんでした。前回の一覧を残しています。", color = MaterialTheme.colorScheme.error)
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

                plan?.isCurrent() == true && error == null -> {
                    if (completed.isNotEmpty()) "確認できた対応は完了しています。" else "確認した範囲では、今日必要な対応は見つかりませんでした。"
                }

                else -> {
                    "まだ確認できていない情報があります。「やることなし」とは判断していません。"
                }
            },
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            active.forEach { TodayTaskRow(it, busy, oldPlan, onComplete) }
        }
    }
    if (completed.isNotEmpty()) {
        TextButton(onClick = { showDone = !showDone }, shapes = ButtonDefaults.shapes()) {
            Text(if (showDone) "完了した項目を閉じる" else "完了した${completed.size}件を表示")
        }
        AnimatedVisibility(showDone) {
            Column {
                completed.forEach { TodayTaskRow(it, busy, oldPlan, onComplete) }
            }
        }
    }
    TodayCoverageNotice(plan?.coverage.orEmpty(), profile)
}

/** Title + one meta line. Reason, fetch time and the source link open on tap. */
@Composable
private fun TodayTaskRow(
    task: TodayTask,
    busy: Boolean,
    oldPlan: Boolean,
    onComplete: (TodayTask) -> Unit,
) {
    val spacing = LocalSpacing.current
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var linkError by rememberSaveable(task.id) { mutableStateOf(false) }
    val stale = oldPlan || task.stale
    Column(Modifier.fillMaxWidth().testTag("today_task_${task.id}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "詳細を閉じる" else "詳細を開く") { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = task.done,
                enabled = !busy,
                onCheckedChange = { onComplete(task) },
                modifier =
                    Modifier.testTag("today_complete_${task.id}").semantics {
                        contentDescription = (if (task.done) "未完了に戻す：" else "完了にする：") + task.title
                    },
            )
            Column(
                Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) muted else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    todayTaskMeta(task, stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.priority == 1 && !task.done) LocalHermesStatusColors.current.warning else muted,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.padding(top = 14.dp, start = spacing.xs).size(20.dp),
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 48.dp, bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (task.reason.isNotBlank()) {
                    Text(task.reason, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                val fetched =
                    buildList {
                        if (task.source_checked_at.isNotBlank()) add("情報取得 " + todayShortTime(task.source_checked_at))
                        if (stale) add("最新の状況は未確認")
                    }.joinToString(" · ")
                if (fetched.isNotEmpty()) Text(fetched, style = MaterialTheme.typography.bodySmall, color = muted)
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
                if (linkError) Text("元の情報を開けませんでした。", color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
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

private fun todayTaskMeta(
    task: TodayTask,
    stale: Boolean,
): String =
    buildList {
        add(
            when (task.priority) {
                1 -> "今日必須"
                2 -> "今日準備"
                else -> "要確認"
            },
        )
        task.deadline?.let { add("期限 " + todayShortTime(it)) }
        add(if (task.source == "gmail") "メール" else "manaba")
        if (stale) add("前回の情報")
    }.joinToString(" · ")

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
        TextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("確認するメールアドレス") },
            supportingText = { Text("Google接続に登録済みのアドレス") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
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
        TextField(
            value = manaba,
            onValueChange = { manaba = it },
            label = { Text("manabaのホームURL") },
            supportingText = { Text("ログイン情報を含まない https:// で始まるURL") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
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
