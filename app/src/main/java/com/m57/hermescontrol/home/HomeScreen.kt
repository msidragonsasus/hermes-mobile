package com.m57.hermescontrol.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.career.CareerHomeProvider
import com.m57.hermescontrol.career.CareerLocalStore
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.DiffViewCard
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.OverviewHeading
import com.m57.hermescontrol.ui.common.OverviewRow
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.sharedAppUpdateViewModel
import com.m57.hermescontrol.worker.EscrowDecision
import com.m57.hermescontrol.worker.EscrowRecord
import com.m57.hermescontrol.worker.ScheduledWorkRow
import com.m57.hermescontrol.worker.WorkViewModel
import com.m57.hermescontrol.worker.WorkerApprovalObject
import com.m57.hermescontrol.worker.WorkerScheduledJob
import com.m57.hermescontrol.worker.upcomingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import retrofit2.HttpException
import java.time.Instant

@Composable
fun HomeScreen() {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val repository = app.homeRepository
    val auth by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val scope = remember(auth) { AppScope.connectionKey() }
    val chatScope = remember(auth, profile) { AuthManager.uiPreferenceScope() }
    val today = rememberTodayPlanState(scope, repository)
    val rows by remember(scope) { repository.dao.observe(scope) }.collectAsState(emptyList())
    val reads by remember(scope) { repository.dao.reads(scope) }.collectAsState(emptyList())
    val providers by remember(scope) { repository.dao.providers(scope) }.collectAsState(emptyList())
    val chatRows by remember(chatScope) { repository.dao.observe(chatScope) }.collectAsState(emptyList())
    val chatReads by remember(chatScope) { repository.dao.reads(chatScope) }.collectAsState(emptyList())
    val chatProviders by remember(chatScope) { repository.dao.providers(chatScope) }.collectAsState(emptyList())
    val attention by app.attentionRepository.items.collectAsState()
    val connection by app.runRepository.snapshot.collectAsState()
    val storageError by repository.error.collectAsState()
    val updater = sharedAppUpdateViewModel()
    val updateCandidate by updater.homeUpdateCandidate.collectAsStateWithLifecycle()
    val coroutine = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    // Same activity-scoped instance as the Work screen, so a decision made here is gone there too.
    val worker: WorkViewModel = viewModel { WorkViewModel() }
    val workState by worker.uiState.collectAsStateWithLifecycle()
    val workerToast = workState.toastMessageRes?.let { stringResource(it) }
    ToastEffect(toastMessage = workerToast, onClearToast = worker::clearToast)
    // Routines and helpers come over the socket: a cold start polls before it is connected,
    // so reload once the link comes up instead of waiting for the next minute's poll.
    val linkState = connection.connection.state
    LaunchedEffect(linkState) {
        if (linkState == LinkState.CONNECTED) worker.refresh()
    }
    var error by remember(scope) { mutableStateOf<String?>(null) }
    var loading by remember(scope) { mutableStateOf(false) }
    var refresh by remember(scope) { mutableStateOf(0) }
    var careerEnabled by remember(scope) { mutableStateOf(false) }
    var now by remember(scope) { mutableStateOf(Instant.now()) }
    val careerVisible = providers.firstOrNull { it.scope == scope && it.provider == "career" }?.enabled != false
    val chatVisible = chatProviders.firstOrNull { it.scope == chatScope && it.provider == "chat" }?.enabled != false
    LaunchedEffect(scope, refresh, careerVisible, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                now = Instant.now()
                worker.refresh()
                try {
                    careerEnabled = CareerLocalStore(app, careerScope()).enabled()
                    if (careerEnabled && careerVisible) {
                        loading = true
                        val page = CareerHomeProvider().read()
                        if (scope != AppScope.connectionKey()) return@repeatOnLifecycle
                        repository.dao.replace(
                            scope,
                            page.items.map { HomeRow(scope, "career", it.id, repository.json.encodeToString(it)) },
                            repository.json.encodeToString(page.copy(items = emptyList())),
                        )
                    }
                    error = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error =
                        when {
                            failure is HttpException && failure.code() == 404 -> "就活のホーム連携にはサーバーの更新が必要です。"

                            failure is HttpException && failure.code() in
                                setOf(
                                    401,
                                    403,
                                )
                            -> "就活情報を確認できません。接続設定を確認してください。"

                            else -> "就活情報を読み直せません。前回の情報を表示しています。"
                        }
                } finally {
                    loading = false
                }
                delay(60_000)
            } while (true)
        }
    }
    val metadata =
        providers
            .firstOrNull { it.scope == scope && it.provider == "career" }
            ?.metadata
            ?.takeIf { it.isNotEmpty() }
            ?.let { repository.json.decodeFromString<HomePage>(it) }
    val items =
        homeItems(
            (
                rows.filter { it.scope == scope && it.provider == "career" && careerVisible && careerEnabled } +
                    chatRows.filter { it.scope == chatScope && it.provider == "chat" && chatVisible }
            ).map { repository.json.decodeFromString<HomeItem>(it.payload) },
            (
                reads.filter { it.scope == scope && it.key.startsWith("career:") } +
                    chatReads.filter { it.scope == chatScope && it.key.startsWith("chat:") }
            ).associate { it.key to it.version },
            now,
        )
    val pending =
        attention.filter {
            it.ref.scope == connection.connection.scope &&
                it.status !in setOf(AttentionStatus.CONFIRMED, AttentionStatus.EXPIRED)
        }
    val actions = items.filter { it.category == "action" }
    // Career action titles are often generic ("相談への回答が必要です"); show which consultation it is.
    val threadIds = actions.mapNotNull { it.target.thread_id }.toSet()
    var threadTitles by remember(scope) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(scope, threadIds) {
        if (threadIds.isEmpty() || threadIds.all { it in threadTitles }) return@LaunchedEffect
        try {
            val list = ApiClient.hermesApi.careerThreads(AppScope.PROFILE)
            if (scope == AppScope.connectionKey()) {
                threadTitles = list.threads.filter { it.title.isNotBlank() }.associate { it.id to it.title }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }
    val results = items.filter { it.category == "result" }.sortedByDescending { it.updated_at }
    HermesScaffold(
        title = { Text("ホーム") },
        drawerGesturesEnabled = false,
        actions = { HomeUpdateAction(updateCandidate?.versionName, updater::openUpdateDialog) },
    ) {
        HomeFeedContent(
            scope = scope,
            actions = actions,
            results = results,
            pending = pending,
            linkState = connection.connection.state,
            loading = loading,
            error = error,
            storageError = storageError,
            metadata = metadata,
            careerVisible = careerVisible,
            careerEnabled = careerEnabled,
            chatVisible = chatVisible,
            onRefresh = {
                refresh++
                if (today.settings?.enabled == true) coroutine.launch { today.refresh() }
                updater.checkForUpdate()
            },
            onProviderChange = { id, enabled ->
                coroutine.launch {
                    try {
                        repository.dao.setEnabled(if (id == "chat") chatScope else scope, id, enabled)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = "表示設定を保存できませんでした。"
                    }
                }
            },
            onOpen = { openHomeItem(it, scope) },
            onAttention = { NavigationController.navigateTo(AttentionDetailKey(it.ref)) },
            onConnectionSettings = { NavigationController.navigateTo(com.m57.hermescontrol.SettingsConnection) },
            approvals = workState.escrows,
            busyApprovalIds = workState.busyEscrowIds,
            onDecide = worker::decideEscrow,
            upcoming = upcomingOrder(workState.scheduledJobs).filter { it.enabled && !it.nextRunAt.isNullOrBlank() },
            onOpenWork = { NavigationController.navigateTo(com.m57.hermescontrol.WorkScreen) },
            today = today,
            threadTitles = threadTitles,
        )
    }
}

@Composable
internal fun HomeFeedContent(
    scope: String,
    actions: List<HomeItem>,
    results: List<HomeItem>,
    pending: List<AttentionItem>,
    linkState: LinkState,
    loading: Boolean,
    error: String?,
    storageError: String?,
    metadata: HomePage?,
    careerVisible: Boolean,
    careerEnabled: Boolean,
    chatVisible: Boolean,
    onRefresh: () -> Unit,
    onProviderChange: (String, Boolean) -> Unit,
    onOpen: (HomeItem) -> Unit,
    onAttention: (AttentionItem) -> Unit,
    onConnectionSettings: () -> Unit,
    approvals: List<EscrowRecord> = emptyList(),
    busyApprovalIds: Set<String> = emptySet(),
    onDecide: (EscrowRecord, EscrowDecision) -> Unit = { _, _ -> },
    upcoming: List<WorkerScheduledJob> = emptyList(),
    onOpenWork: () -> Unit = {},
    today: TodayPlanState? = null,
    threadTitles: Map<String, String> = emptyMap(),
) {
    var settings by rememberSaveable(scope) { mutableStateOf(false) }
    var showCoverage by rememberSaveable(scope) { mutableStateOf(false) }
    var expandActions by rememberSaveable(scope) { mutableStateOf(false) }
    var expandResults by rememberSaveable(scope) { mutableStateOf(false) }
    var openGroups by rememberSaveable(scope) { mutableStateOf(emptyList<String>()) }
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.testTag("home_list"),
        contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.md, bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (today != null) {
            item(key = "today_plan") { TodayPlanPanel(scope, today) }
        }
        item {
            HomeToolbar(linkState, loading, onRefresh) { settings = !settings }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (settings) {
            item {
                listOf("chat" to "チャット", "career" to "就活").forEach { (id, name) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$name をホームに表示", Modifier.weight(1f))
                        Switch(
                            checked = if (id == "career") careerVisible else chatVisible,
                            onCheckedChange = { onProviderChange(id, it) },
                        )
                    }
                }
                Text("取得・通知設定は変更しません。承認・回答待ちは表示を続けます。")
            }
        }
        if (approvals.isNotEmpty() || pending.isNotEmpty() || actions.isNotEmpty()) {
            item { OverviewHeading("対応が必要") }
            // Worker approvals are decided in place: the card already shows everything the decision binds to.
            items(approvals, key = { "escrow:${it.id}" }) { record ->
                WorkerApprovalObject(
                    record = record,
                    busy = record.id in busyApprovalIds,
                    onApprove = { onDecide(record, EscrowDecision.APPROVE) },
                    onDeny = { onDecide(record, EscrowDecision.DENY) },
                )
            }
            items(if (expandActions) pending else pending.take(5)) {
                HomeAttentionEntry(it, ready = linkState == LinkState.CONNECTED) { onAttention(it) }
            }
            // Same-titled actions collapse into one row with a count.
            val groups = homeActionGroups(actions)
            val visibleGroups = if (expandActions) groups else groups.take((5 - pending.size).coerceAtLeast(0))
            visibleGroups.forEach { group ->
                val first = group.first()
                if (group.size == 1) {
                    item(key = "action:" + first.id) {
                        HomeEntry(first, context = first.target.thread_id?.let(threadTitles::get)) { onOpen(first) }
                    }
                } else {
                    val groupKey = first.provider + ":" + first.title
                    item(key = "group:$groupKey") {
                        HomeGroupEntry(
                            group,
                            expanded = groupKey in openGroups,
                            labelOf = { item, index ->
                                item.target.thread_id?.let(threadTitles::get)
                                    ?: item.detail.ifBlank { "${index + 1}件目" }
                            },
                            onToggle = {
                                openGroups =
                                    if (groupKey in openGroups) openGroups - groupKey else openGroups + groupKey
                            },
                            onOpen = onOpen,
                        )
                    }
                }
            }
            if (!expandActions && pending.size + groups.size > 5) {
                item {
                    TextButton(
                        onClick = { expandActions = true },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("残り${pending.size + groups.size - 5}件を表示") }
                }
            }
        }
        // Background work reports in the main conversation (AUTONOMOUS_WORKER.md §8.3), not here.
        if (results.isNotEmpty()) {
            item { OverviewHeading("届いた結果") }
            val visibleCount = if (expandResults) results.size else 5
            items(results.take(visibleCount), key = {
                "result:${it.provider}:${it.id}"
            }) { HomeEntry(it) { onOpen(it) } }
            val totalResults = results.size
            if (!expandResults && totalResults > 5) {
                item {
                    TextButton(
                        onClick = { expandResults = true },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("残り${totalResults - 5}件を表示") }
                }
            }
        }
        if (upcoming.isNotEmpty()) {
            item { OverviewHeading("このあと") }
            items(upcoming.take(3), key = { "upcoming:${it.id}:${it.name}" }) { job ->
                ScheduledWorkRow(
                    job = job,
                    japanese = true,
                    busy = false,
                    onTogglePause = null,
                    onRequestDelete = null,
                    onClick = onOpenWork,
                )
            }
            item {
                TextButton(
                    onClick = onOpenWork,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("すべての作業") }
            }
        }
        if (!loading &&
            results.isEmpty() &&
            actions.isEmpty() &&
            pending.isEmpty() &&
            approvals.isEmpty()
        ) {
            item {
                Text(
                    if (error != null ||
                        metadata?.coverage?.any { it.state !in setOf("updated", "unchanged", "disabled") } == true
                    ) {
                        "一部の情報を確認できていません。下の確認状況をご覧ください。"
                    } else {
                        "確認できた情報には、ホームに表示する対応・結果はありません。"
                    },
                )
            }
        }
        item {
            storageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (careerVisible) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = onConnectionSettings,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("接続設定を開く")
                    }
                }
                metadata?.takeIf { careerEnabled }?.let { page ->
                    val incomplete = page.coverage.any { it.state !in setOf("updated", "unchanged", "disabled") }
                    OverviewHeading("確認状況")
                    Text(
                        if (incomplete) "就活：一部の情報を確認できていません" else "就活：保存情報を確認済み",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { showCoverage = !showCoverage },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (showCoverage) "確認状況を閉じる" else "確認状況") }
                    if (showCoverage) {
                        Text("ホーム確認：${homeTime(page.checked_at)}", style = MaterialTheme.typography.bodySmall)
                        page.coverage.forEach { coverage ->
                            Text(
                                "${coverage.label}：${coverageLabel(coverage.state)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            coverage.last_success_at?.let {
                                Text(
                                    "最終取得：${homeTime(it)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Text("チャットは端末が受信した回答が対象です。", style = MaterialTheme.typography.bodySmall)
            Text("日時は日本時間で表示しています。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Groups same provider + title actions without a deadline, keeping list order. */
internal fun homeActionGroups(actions: List<HomeItem>): List<List<HomeItem>> {
    val groups = LinkedHashMap<String, MutableList<HomeItem>>()
    actions.forEach { item ->
        val key = if (item.deadline == null) "same:" + item.provider + ":" + item.title else "one:" + item.id
        groups.getOrPut(key) { mutableListOf() }.add(item)
    }
    return groups.values.toList()
}

private fun coverageLabel(state: String): String =
    when (state) {
        "updated", "unchanged" -> "取得済み"
        "running" -> "取得中"
        "partial" -> "一部取得・未確認の情報があります"
        "failed" -> "最新情報を取得できていません"
        "auth_required" -> "接続の確認が必要"
        "disabled" -> "取得停止中"
        else -> "まだ確認できていません"
    }

@Composable
internal fun HomeCard(
    item: HomeItem,
    scope: String,
) {
    HomeEntry(item) { openHomeItem(item, scope) }
}

private fun openHomeItem(
    item: HomeItem,
    scope: String,
) {
    if (item.target.kind == "chat") {
        item.target.session_id?.let(NavigationController::openChatResult)
    } else {
        NavigationController.navigateTo(HomeDetailKey(scope, item))
    }
}
