package com.m57.hermescontrol.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.SettingsConnection
import com.m57.hermescontrol.WorkScreen
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.domain.hermes.AttentionItem
import com.m57.hermescontrol.domain.hermes.AttentionKind
import com.m57.hermescontrol.domain.hermes.AttentionStatus
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.navigation.AttentionDetailKey
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.CompactListRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InlineNotice
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.sharedAppUpdateViewModel
import com.m57.hermescontrol.worker.EscrowRecord
import com.m57.hermescontrol.worker.ScheduledWorkRow
import com.m57.hermescontrol.worker.WorkUiState
import com.m57.hermescontrol.worker.WorkViewModel
import com.m57.hermescontrol.worker.WorkerScheduledJob
import com.m57.hermescontrol.worker.WorkerSourceStatus
import com.m57.hermescontrol.worker.upcomingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@Composable
fun HomeScreen() {
    val app = LocalContext.current.applicationContext as HermesControlApp
    val repository = app.homeRepository
    val auth by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val epoch by AuthManager.connectionEpoch.collectAsStateWithLifecycle()
    val scope = remember(auth, epoch) { AppScope.connectionKey() }
    val chatScope = remember(auth, profile, epoch) { AuthManager.uiPreferenceScope() }
    val today = rememberTodayPlanState(scope, repository)
    val career = remember(scope, repository) { HomeCareerSource(scope, app, repository) }
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

    // The activity-scoped instance is shared with Work, including approval decisions.
    val worker: WorkViewModel = viewModel { WorkViewModel() }
    val workerSnapshot by worker.uiState.collectAsStateWithLifecycle()
    val workState = if (worker.ownsScope(scope)) workerSnapshot else WorkUiState()
    val workerToast = workState.toastMessageRes?.let { stringResource(it) }
    ToastEffect(toastMessage = workerToast, onClearToast = worker::clearToast)
    val linkState = connection.connection.state
    var now by remember(scope) { mutableStateOf(Instant.now()) }
    var manualToken by remember(scope) { mutableIntStateOf(0) }
    var showSettingsSheet by rememberSaveable(scope) { mutableStateOf(false) }
    var settingsError by remember(scope) { mutableStateOf<String?>(null) }
    val careerVisible = providers.firstOrNull { it.scope == scope && it.provider == "career" }?.enabled != false
    val chatVisible = chatProviders.firstOrNull { it.scope == chatScope && it.provider == "chat" }?.enabled != false
    val reloadKey = HomeReloadKey(epoch, linkState == LinkState.CONNECTED, careerVisible, manualToken)
    var isManualRefreshing by remember(scope, reloadKey) { mutableStateOf(false) }
    HomeRefreshEffect(
        scope = scope,
        reloadKey = reloadKey,
        lifecycle = LocalLifecycleOwner.current.lifecycle,
        today = today,
        loadCareer = career::load,
        refreshWorker = worker::refreshAndAwait,
        onManualRefreshChanged = { isManualRefreshing = it },
        onPoll = { now = it },
    )
    val metadata =
        providers
            .firstOrNull { it.scope == scope && it.provider == "career" }
            ?.metadata
            ?.takeIf { it.isNotEmpty() }
            ?.let { repository.json.decodeFromString<HomePage>(it) }
    val items =
        homeItems(
            (
                rows.filter { it.scope == scope && it.provider == "career" && careerVisible && career.careerEnabled } +
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
    val notice =
        homeStatusOf(
            linkState,
            storageError ?: settingsError,
            today.failure,
            career.failure,
            career.careerEnabled,
            careerVisible,
            workState.approvalsStatus,
            workState.routinesStatus,
        ) ?: (workState.partialErrorRes ?: workState.fatalErrorRes)?.let {
            HomeStatusNotice(NoticeTone.Warning, stringResource(it), HomeNoticeAction.Retry)
        }
    val retry: () -> Unit = { manualToken++ }
    val connectionSettings: () -> Unit = { NavigationController.navigateTo(SettingsConnection) }
    HermesScaffold(
        title = { Text("ホーム") },
        drawerGesturesEnabled = false,
        isRefreshing = isManualRefreshing,
        onRefresh = {
            retry()
            updater.checkForUpdate()
        },
        showRefreshAction = false,
        actions = {
            HomeUpdateAction(updateCandidate?.versionName, updater::openUpdateDialog)
            IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.testTag("home_settings")) {
                Icon(Icons.Outlined.Tune, contentDescription = "ホームの設定")
            }
        },
    ) {
        HomeFeedContent(
            scope = scope,
            actions = items.filter { it.category == "action" },
            results = items.filter { it.category == "result" }.sortedByDescending { it.updated_at },
            pending = pending,
            linkState = linkState,
            now = now,
            hasTodayFocus =
                today.settings?.enabled != false && today.plan
                    ?.groups(now)
                    ?.actionable
                    ?.isNotEmpty() == true,
            decisionsConfirmed =
                linkState == LinkState.CONNECTED &&
                    career.failure == null &&
                    workState.approvalsStatus in setOf(WorkerSourceStatus.Ok, WorkerSourceStatus.Unavailable),
            notice = notice,
            onNoticeAction = { action ->
                when (action) {
                    HomeNoticeAction.ConnectionSettings -> connectionSettings()
                    HomeNoticeAction.Retry -> retry()
                }
            },
            onOpen = { openHomeItem(it, scope) },
            onAttention = { NavigationController.navigateTo(AttentionDetailKey(it.ref)) },
            approvals = workState.escrows,
            busyApprovalIds = workState.busyEscrowIds,
            upcoming = upcomingOrder(workState.scheduledJobs).filter { it.enabled && !it.nextRunAt.isNullOrBlank() },
            subagentCount = workState.subagents.size,
            onOpenWork = { NavigationController.navigateTo(WorkScreen) },
            today = { focusOccupied, timeline ->
                TodayPlanPanel(
                    state = today,
                    now = now,
                    blockedByAuth =
                        epoch % 2L != 0L || linkState == LinkState.AUTH_REQUIRED ||
                            today.failure in
                            setOf(HomeFailure.AuthRequired, HomeFailure.NotGated, HomeFailure.ProfileUnavailable),
                    failureCovered = notice?.coversToday == true,
                    showFocus = !focusOccupied,
                    offline = linkState == LinkState.OFFLINE,
                    additionalTimeline = timeline,
                    onConfigure = { showSettingsSheet = true },
                    onReload = retry,
                )
            },
            threadTitles = career.threadTitles,
        )
    }
    if (showSettingsSheet) {
        HomeSettingsSheet(
            scope = scope,
            today = today,
            chatVisible = chatVisible,
            careerVisible = careerVisible,
            careerEnabled = career.careerEnabled,
            metadata = metadata,
            onProviderChange = { id, enabled ->
                coroutine.launch {
                    try {
                        repository.dao.setEnabled(if (id == "chat") chatScope else scope, id, enabled)
                        settingsError = null
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        settingsError = "表示設定を保存できませんでした。"
                    }
                }
            },
            saveSettings = { value -> coroutine.async { today.save(value) }.await() },
            onRetry = retry,
            onDismiss = { showSettingsSheet = false },
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
    onOpen: (HomeItem) -> Unit,
    onAttention: (AttentionItem) -> Unit,
    now: Instant = Instant.now(),
    hasTodayFocus: Boolean = false,
    decisionsConfirmed: Boolean = linkState == LinkState.CONNECTED,
    notice: HomeStatusNotice? = null,
    onNoticeAction: (HomeNoticeAction) -> Unit = {},
    approvals: List<EscrowRecord> = emptyList(),
    busyApprovalIds: Set<String> = emptySet(),
    upcoming: List<WorkerScheduledJob> = emptyList(),
    subagentCount: Int = 0,
    onOpenWork: () -> Unit = {},
    today: (@Composable (focusOccupied: Boolean, timeline: List<HomeTimelineItem>) -> Unit)? = null,
    threadTitles: Map<String, String> = emptyMap(),
) {
    var expandDecisions by rememberSaveable(scope) { mutableStateOf(false) }
    var expandActions by rememberSaveable(scope) { mutableStateOf(false) }
    var expandResults by rememberSaveable(scope) { mutableStateOf(false) }
    var openGroups by rememberSaveable(scope) { mutableStateOf(emptyList<String>()) }
    val spacing = LocalSpacing.current
    val expiryOrder = compareBy<HomeDecision> { it.expiresAt ?: Instant.MAX }
    // Career's Home feed publishes waiting_user runs as conversation actions.
    val careerReplies =
        actions.filter {
            it.provider == "career" && it.target.kind == "conversation" &&
                !it.target.run_id.isNullOrBlank()
        }
    val decisions =
        (
            pending.map { item ->
                HomeDecision(
                    title = item.title,
                    source = if (item.kind == AttentionKind.APPROVAL) "チャットの承認" else "チャットの回答",
                    status = item.status,
                    expiresAt = item.expiresAt?.let(Instant::ofEpochMilli),
                    approval = item.kind == AttentionKind.APPROVAL,
                    open = { onAttention(item) },
                )
            } +
                approvals.map { record ->
                    HomeDecision(
                        title =
                            record.display.title.trim().ifBlank {
                                stringResource(
                                    R.string.worker_approval_default_title,
                                )
                            },
                        source = record.job_name?.takeIf(String::isNotBlank) ?: "作業の承認",
                        status =
                            if (record.id in
                                busyApprovalIds
                            ) {
                                AttentionStatus.SUBMITTING
                            } else {
                                AttentionStatus.PENDING
                            },
                        expiresAt = homeDeadlineInstant(record.expires_at),
                        approval = true,
                        open = onOpenWork,
                    )
                } +
                careerReplies.map { item ->
                    HomeDecision(
                        title = item.target.thread_id?.let(threadTitles::get) ?: item.detail.ifBlank { item.title },
                        source = "就活の回答",
                        status = AttentionStatus.PENDING,
                        expiresAt = null,
                        approval = false,
                        open = { onOpen(item) },
                    )
                }
        ).sortedWith(expiryOrder)
    val urgent =
        decisions.filter {
            it.approval && it.status != AttentionStatus.SUBMITTING && it.expiresAt?.isHomeDecisionSoon(now) == true
        }
    val decisionFocus = urgent.firstOrNull()
    val japan = ZoneId.of("Asia/Tokyo")
    val date = now.atZone(japan).toLocalDate()

    fun dueToday(value: String?): Boolean =
        when (val deadline = todayDeadline(value)) {
            is TodayDeadline.At -> deadline.instant.atZone(japan).toLocalDate() == date
            is TodayDeadline.Day -> deadline.date == date
            else -> false
        }
    val careerDueToday = actions.filter { it.provider == "career" && dueToday(it.deadline) }
    val careerFocus =
        careerDueToday
            .firstOrNull { it.target.kind in setOf("company", "mail") }
            .takeIf { decisionFocus == null && !hasTodayFocus }
    val timelineActions = if (today == null) emptyList() else careerDueToday.filterNot { it == careerFocus }
    val timelineRoutines =
        if (today ==
            null
        ) {
            emptyList()
        } else {
            upcoming.filter {
                dueToday(it.nextRunAt) &&
                    homeDeadlineInstant(it.nextRunAt)?.isAfter(now) == true
            }
        }
    val timeline =
        timelineActions.map { item ->
            HomeTimelineItem(item.title, requireNotNull(item.deadline), "就活") { onOpen(item) }
        } +
            timelineRoutines.map { job ->
                HomeTimelineItem(job.name, requireNotNull(job.nextRunAt), "Hermes の定期作業", onOpenWork)
            }
    val remainingActions = actions.filterNot { it == careerFocus || it in timelineActions || it in careerReplies }
    val laterRoutines = upcoming - timelineRoutines.toSet()
    val hasActions = remainingActions.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home_list"),
        contentPadding = PaddingValues(start = spacing.md, end = spacing.md, top = spacing.sm, bottom = spacing.xl),
    ) {
        item(key = "decision_status") {
            HomeStatusPill(
                pendingCount =
                    decisions.count {
                        it.status == AttentionStatus.PENDING && it.expiresAt?.isAfter(now) != false
                    },
                hasDecisions = decisions.isNotEmpty(),
                confirmed = decisionsConfirmed,
                expanded = expandDecisions,
                onToggle = { expandDecisions = !expandDecisions },
            )
        }
        if (expandDecisions) {
            items(decisions) { decision ->
                HomeDecisionRow(decision, now, ready = linkState == LinkState.CONNECTED)
            }
        }
        notice?.let {
            item(key = "home_status") {
                InlineNotice(
                    it.text,
                    tone = it.tone,
                    actionLabel = it.action?.label,
                    onAction =
                        it.action?.let { action ->
                            { onNoticeAction(action) }
                        },
                )
            }
        }
        if (decisionFocus != null) {
            item(key = "decision_focus") {
                HomeFocusAction(
                    title = decisionFocus.title,
                    details = homeFocusDeadline(decisionFocus.expiresAt.toString(), now),
                    actionLabel = "承認内容を確認",
                    onOpen = decisionFocus.open,
                )
            }
        } else if (careerFocus != null) {
            item(key = "career_focus") {
                HomeFocusAction(
                    title = careerFocus.title,
                    details = homeFocusDeadline(careerFocus.deadline, now),
                    actionLabel = "内容を確認",
                    onOpen = { onOpen(careerFocus) },
                )
            }
        }
        if (!expandDecisions) {
            items(urgent.drop(1)) { decision ->
                HomeDecisionRow(decision, now, ready = linkState == LinkState.CONNECTED)
            }
        }
        if (today != null) {
            item(key = "today_plan") { today(decisionFocus != null || careerFocus != null, timeline) }
        }
        if (hasActions) {
            item(key = "actions_heading") { HomeSectionHeader("対応が必要", first = today == null) }
            val groups = homeActionGroups(remainingActions)
            val visibleGroups = if (expandActions) groups else groups.take(5)
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
            val rest = groups.size - 5
            if (rest > 0) {
                item(key = "more_actions") {
                    HomeFoldRow(
                        if (expandActions) "追加の対応を閉じる" else "残り${rest}件を表示",
                        expanded = expandActions,
                        onToggle = { expandActions = !expandActions },
                    )
                }
            }
        }
        if (results.isNotEmpty()) {
            item(key = "results_heading") { HomeSectionHeader("届いた結果", first = today == null && !hasActions) }
            items(if (expandResults) results else results.take(5), key = { "result:${it.provider}:${it.id}" }) {
                HomeEntry(it) { onOpen(it) }
            }
            if (results.size > 5) {
                item(key = "more_results") {
                    HomeFoldRow(
                        if (expandResults) "追加の結果を閉じる" else "残り${results.size - 5}件を表示",
                        expanded = expandResults,
                        onToggle = { expandResults = !expandResults },
                    )
                }
            }
        }
        if (laterRoutines.isNotEmpty() || subagentCount > 0) {
            item(key = "upcoming_heading") {
                HomeSectionHeader("このあと", first = today == null && !hasActions && results.isEmpty()) {
                    TextButton(onClick = onOpenWork) { Text("すべての作業") }
                }
            }
            items(laterRoutines.take(3), key = { "upcoming:${it.id}:${it.name}" }) { job ->
                ScheduledWorkRow(
                    job = job,
                    japanese = true,
                    busy = false,
                    onTogglePause = null,
                    onRequestDelete = null,
                    onClick = onOpenWork,
                )
            }
            if (subagentCount > 0) {
                item(key = "active_helpers") {
                    CompactListRow(onClick = onOpenWork, trailing = { HomeChevron() }) {
                        Text("手伝い中の作業 ${subagentCount}件", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    first: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    SectionHeader(
        title,
        contentPadding = PaddingValues(top = if (first) 8.dp else 24.dp, bottom = 4.dp),
        trailing = trailing,
    )
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
