package com.m57.hermescontrol.career

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Composable
fun CareerScreen(
    companyId: String? = null,
    initialTab: Int = 0,
    embedded: Boolean = false,
    settingsOnly: Boolean = false,
    initialEntityId: String? = null,
    refreshKey: Int = 0,
) {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { careerScope() }
    val appContext = LocalContext.current.applicationContext
    key(scope) {
        val model: CareerCompanyViewModel =
            viewModel(
                key = "career-company:$scope:$companyId",
                factory =
                    viewModelFactory {
                        initializer {
                            CareerCompanyViewModel(
                                CareerCompanyRepository(scope),
                                CareerLocalStore(appContext, scope),
                                companyId,
                            )
                        }
                    },
            )
        val state by model.state.collectAsState()
        LaunchedEffect(state.companyDeleted, state.loading) {
            if (state.companyDeleted && !state.loading) NavigationController.goBack()
        }
        LaunchedEffect(model, refreshKey) { if (refreshKey > 0) model.refresh() }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, model) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.refresh() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(lifecycle, model, companyId) {
            if (companyId == null) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(if (model.state.value.stale) 15_000 else 5_000)
                    val latest = model.state.value
                    if (latest.detail
                            ?.mypage
                            ?.status
                            ?.text("state") in
                        setOf("queued", "running", "collecting", "analyzing")
                    ) {
                        model.refresh()
                    }
                }
            }
        }
        CareerCompanyContent(
            state,
            companyId,
            model::enable,
            model::refresh,
            onCollect = { NavigationController.navigateTo(CareerMailSettingsKey) },
            onCollectMyPage = model::collectMyPage,
            onResumeMyPageAnalysis = model::resumeMyPageAnalysis,
            onSetSender = model::setMailSender,
            onPreferences = model::preferences,
            initialTab = initialTab,
            mailActions =
                companyId?.let { id ->
                    { CareerCompanyGmailActions(scope, id, model::refreshAfterMailSaved) }
                },
            onLoadSavedMail = model::loadMoreMail,
            onAddCompany = model::addCompany,
            onDeleteCompany = model::deleteCompany,
            onSaveOrder = model::saveCompanyOrder,
            onClearOrderWrite = model::clearCompanyOrderWrite,
            onRetryInitialization = model::retryInitialization,
            embedded = embedded,
            settingsOnly = settingsOnly,
            initialEntityId = initialEntityId,
        )
    }
}

@Composable
internal fun CareerCompanyContent(
    state: CareerCompanyState,
    companyId: String?,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onCollect: () -> Unit,
    onCollectMyPage: () -> Unit = {},
    onSetSender: (String, Boolean) -> Unit = { _, _ -> },
    onPreferences: (String, String, Int) -> Unit = { _, _, _ -> },
    initialTab: Int = 0,
    mailActions: (@Composable () -> Unit)? = null,
    onLoadSavedMail: () -> Unit = {},
    onAddCompany: (String, String) -> Unit = { _, _ -> },
    onDeleteCompany: (Int, String) -> Unit = { _, _ -> },
    embedded: Boolean = false,
    settingsOnly: Boolean = false,
    initialEntityId: String? = null,
    onResumeMyPageAnalysis: () -> Unit = {},
    onSaveOrder: (List<String>, Int, String) -> Unit = { _, _, _ -> },
    onClearOrderWrite: () -> Unit = {},
    onRetryInitialization: () -> Unit = {},
) {
    if (state.initializationError != null || !state.initialized) {
        CareerCompanyListFrame(
            embedded,
            title = { Text(if (companyId == null) "就活の企業" else "企業詳細") },
            navigationIcon = NavIcon.Back { NavigationController.goBack() },
            drawerGesturesEnabled = false,
            isRefreshing = false,
            actions = {},
        ) {
            if (state.initializationError != null) {
                ErrorState(state.initializationError, onRetry = onRetryInitialization)
            } else {
                LoadingState()
            }
        }
        return
    }

    val spacing = LocalSpacing.current
    val colors = LocalHermesStatusColors.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var onlyNew by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var draftOrder by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var draftVersion by rememberSaveable { mutableStateOf(-1) }
    var orderRequestId by rememberSaveable { mutableStateOf("") }
    var orderNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val drag = rememberCompanyDrag()
    val allRows = orderedCompanies(state.companies?.companies.orEmpty(), draftOrder)
    val rows =
        allRows.filter {
            it.matchesCompanyFilter(search, onlyNew, filter)
        }
    val visibleIds = rows.map { it.id }

    fun currentVisibleOrder(): List<String> =
        orderedCompanies(state.companies?.companies.orEmpty(), draftOrder)
            .filter {
                it.matchesCompanyFilter(search, onlyNew, filter)
            }.map { it.id }
    val orderUnknown = state.orderWrite.phase == CompanyWritePhase.UNKNOWN
    val canMove =
        draftOrder != null && !state.loading && !state.stale && !orderUnknown &&
            state.orderWrite.phase != CompanyWritePhase.CONFLICT && state.companies?.order_state != "unavailable"

    fun closeOrder() {
        drag.stop()
        draftOrder = null
        onClearOrderWrite()
        if (orderUnknown) onRefresh()
    }
    BackHandler(enabled = draftOrder != null && companyId == null) { if (!state.loading) closeOrder() }
    DisposableEffect(drag) { onDispose { drag.stop() } }
    LaunchedEffect(canMove, search, filter, onlyNew) { drag.stop() }
    LaunchedEffect(state.companies, state.orderWrite) {
        val draft = draftOrder ?: return@LaunchedEffect
        if (state.orderWrite.phase == CompanyWritePhase.SAVED && state.orderWrite.requestId == orderRequestId) {
            drag.stop()
            draftOrder = null
        } else if (!state.loading && !orderUnknown && state.companies != null &&
            (
                state.companies.order_version != draftVersion ||
                    state.companies.companies
                        .map { it.id }
                        .toSet() != draft.toSet()
            )
        ) {
            closeOrder()
            orderNotice =
                if (state.companies.companies.map { it.id } == draft.toList()) {
                    "順序は保存済みです。"
                } else {
                    "会社一覧または順序が更新されたため、編集を終了しました。もう一度並べ替えてください。"
                }
        }
    }
    val detail = state.detail
    if (companyId != null) {
        key(companyId) {
            CareerCompanyPager(
                state,
                initialTab,
                onRefresh,
                onCollect,
                onCollectMyPage,
                onSetSender,
                onPreferences,
                onConnections = { NavigationController.navigateTo(CareerMailSettingsKey) },
                mailActions = mailActions,
                onLoadSavedMail = onLoadSavedMail,
                onDeleteCompany = onDeleteCompany,
                settingsOnly = settingsOnly,
                initialEntityId = initialEntityId,
                onResumeMyPageAnalysis = onResumeMyPageAnalysis,
            )
        }
        return
    }
    if (showAdd) {
        AddCompanyDialog(state, onAddCompany) { showAdd = false }
    }
    LaunchedEffect(state.companyWrite) {
        if (state.companyWrite.phase == CompanyWritePhase.SAVED) {
            search = ""
            onlyNew = false
        }
    }
    CareerCompanyListFrame(
        embedded,
        title = { Text(if (detail == null) "就活の企業" else "企業詳細") },
        navigationIcon =
            NavIcon.Back {
                if (draftOrder != null) {
                    if (!state.loading) closeOrder()
                } else {
                    NavigationController.goBack()
                }
            },
        drawerGesturesEnabled = false,
        isRefreshing = state.loading,
        actions = {
            if (companyId == null) {
                TextButton(
                    onClick = { NavigationController.navigateTo(CareerMailSettingsKey) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("メール接続") }
            }
            TextButton(
                onClick = { NavigationController.navigateTo(CareerSettingsKey) },
                shapes = ButtonDefaults.shapes(),
            ) { Text("設定") }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("career_company_list"),
                state = listState,
                // Rows carry their own padding and divider; no extra gap between them.
                contentPadding =
                    PaddingValues(start = spacing.md, end = spacing.md, top = spacing.sm, bottom = spacing.xl),
            ) {
                item {
                    CareerStatusBanner(
                        careerStatusOf(
                            listOf(state.error),
                            state.stale && (detail != null || state.companies != null),
                        ),
                        onRefresh,
                        Modifier.padding(bottom = spacing.sm),
                    )
                }
                state.message?.let { item { Text(it) } }
                state.companyWrite.message?.let { item { Text(it) } }
                state.orderWrite.message?.let { item { Text(it) } }
                orderNotice?.let { item { Text(it) } }
                if (state.initialized && !state.enabled) {
                    item {
                        Text("企業ごとに、募集中の情報と締切を確認", style = MaterialTheme.typography.headlineSmall)
                        Text("ログイン後のマイページの案内と、企業からのメールを分けて確認できます。")
                        Button(
                            onClick = onEnable,
                            modifier = Modifier.testTag("career_enable"),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("就活を開く") }
                    }
                } else if (state.enabled && companyId == null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SearchBar(search, { search = it }, Modifier.weight(1f), placeholder = "企業を探す")
                            if (draftOrder == null) {
                                IconButton(
                                    onClick = {
                                        onClearOrderWrite()
                                        orderNotice = null
                                        draftOrder =
                                            ArrayList(
                                                state.companies
                                                    ?.companies
                                                    .orEmpty()
                                                    .map { it.id },
                                            )
                                        draftVersion = state.companies?.order_version ?: -1
                                        orderRequestId = UUID.randomUUID().toString()
                                    },
                                    enabled =
                                        !state.loading && !state.stale && state.companies?.order_version != null &&
                                            state.companies.order_state != "unavailable" && allRows.size > 1,
                                    modifier = Modifier.testTag("company_order_begin"),
                                ) { Icon(Icons.Outlined.SwapVert, contentDescription = "並べ替え") }
                                // Add lives in the control row instead of a floating button (DESIGN.md: no FABs).
                                IconButton(
                                    onClick = { showAdd = true },
                                    modifier = Modifier.testTag("company_add"),
                                ) { Icon(Icons.Outlined.Add, contentDescription = "企業を追加") }
                            }
                        }
                        Row(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            careerApplicationFilters.forEach { (value, label) ->
                                FilterChip(filter == value, { filter = value }, label = { Text(label) })
                            }
                            FilterChip(selected = onlyNew, onClick = { onlyNew = !onlyNew }, label = { Text("新着あり") })
                            careerConnectionFilters.forEach { (value, label) ->
                                FilterChip(filter == value, { filter = value }, label = { Text(label) })
                            }
                        }
                        if (draftOrder == null) {
                            if (!state.stale && state.companies != null &&
                                (state.companies.order_version == null || state.companies.order_state == "unavailable")
                            ) {
                                Text(
                                    if (state.companies.order_state ==
                                        "unavailable"
                                    ) {
                                        "保存済みの順序を確認できません。サーバーの保存データを確認してください。"
                                    } else {
                                        "並べ替えにはサーバーの更新が必要です。"
                                    },
                                )
                            }
                        } else {
                            Text("長押しして移動、または「上へ」「下へ」で並べ替え")
                            if (search.isNotBlank() || onlyNew || filter != "all") Text("表示中の会社だけを並べ替えます。")
                            FlowRow {
                                Button(
                                    onClick = {
                                        draftOrder?.let {
                                            onSaveOrder(
                                                it.toList(),
                                                draftVersion,
                                                orderRequestId,
                                            )
                                        }
                                    },
                                    enabled = !state.loading && (canMove || orderUnknown),
                                    modifier = Modifier.testTag("company_order_save"),
                                ) { Text(if (orderUnknown) "同じ内容で再試行" else "順序を保存") }
                                TextButton(
                                    onClick = ::closeOrder,
                                    enabled = !state.loading,
                                ) { Text(if (orderUnknown) "閉じて再読込" else "キャンセル") }
                                if (state.stale &&
                                    !orderUnknown
                                ) {
                                    TextButton(onClick = {
                                        onRefresh()
                                    }, enabled = !state.loading) { Text("再読込") }
                                }
                            }
                        }
                    }
                    if (rows.isEmpty() && !state.loading) {
                        item {
                            Text(
                                if (search.isNotBlank() ||
                                    onlyNew || filter != "all"
                                ) {
                                    "該当する企業はありません。"
                                } else {
                                    "企業はまだ登録されていません。「企業を追加」から登録できます。"
                                },
                            )
                        }
                    }
                    items(rows, key = { it.id }) { company ->
                        CompanyOrderCard(
                            editing = draftOrder != null,
                            onOpen = { NavigationController.navigateTo(CareerCompanyKey(company.id)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("career_company_${company.id}")
                                    .zIndex(if (drag.id == company.id) 1f else 0f)
                                    .graphicsLayer { translationY = drag.translation(listState, company.id) },
                        ) {
                            Column(
                                Modifier.padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (draftOrder !=
                                    null
                                ) {
                                    CompanyOrderHandle(
                                        company,
                                        visibleIds,
                                        canMove,
                                        listState,
                                        drag,
                                        ::currentVisibleOrder,
                                    ) { from, to ->
                                        val firstKey =
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull()
                                                ?.key
                                        if (firstKey == from || firstKey == to) {
                                            listState.requestScrollToItem(
                                                listState.firstVisibleItemIndex,
                                                listState.firstVisibleItemScrollOffset,
                                            )
                                        }
                                        draftOrder =
                                            draftOrder?.let { current ->
                                                ArrayList(moveVisibleCompany(current, currentVisibleOrder(), from, to))
                                            }
                                        orderRequestId = UUID.randomUUID().toString()
                                    }
                                }
                                val readiness = company.mypage_readiness
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        company.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (company.new_count > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                            Text("新着 ${careerCount(company.new_count)}")
                                        }
                                    }
                                    if (readiness?.next_action != null && draftOrder == null) {
                                        IconButton(
                                            onClick = {
                                                NavigationController.navigateTo(CareerCompanySettingsKey(company.id))
                                            },
                                            modifier =
                                                Modifier
                                                    .size(40.dp)
                                                    .testTag("company_login_settings_${company.id}"),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Key,
                                                contentDescription = "ログイン設定を開く",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    company.next_action,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val facts =
                                    buildList {
                                        // The next action already names a missing login; only add the state otherwise.
                                        if (readiness != null &&
                                            readiness.next_action == null
                                        ) {
                                            add("マイページ：${readiness.label}")
                                        }
                                        readiness?.last_success_at?.let { add("最終取得 ${careerReadableTime(it)}") }
                                        company.application_counts.forEach { (status, count) ->
                                            add("${careerApplicationFilters[status] ?: "要確認"} $count")
                                        }
                                        company.deadline?.let { add("締切 $it") }
                                        if (company.offer_count > 1) add("${company.offer_count}件の募集")
                                    }
                                if (facts.isNotEmpty()) {
                                    Text(
                                        facts.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                            if (readiness != null) {
                                                Modifier.testTag("company_login_status_${company.id}")
                                            } else {
                                                Modifier
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Server instants become "今日 14:05" style text; unparseable values are shown as given. */
internal fun careerReadableTime(value: String): String =
    runCatching { java.time.OffsetDateTime.parse(value) }.getOrNull()?.let { careerThreadUpdatedAt(value) } ?: value

/** Large unread totals stop carrying meaning; cap them the way badges usually do. */
internal fun careerCount(count: Int): String = if (count > 99) "99+" else "$count"

internal fun collectionStateLabel(value: String?): String =
    when (value) {
        "updated", "unchanged", "available" -> "取得済み"
        "partial" -> "続きの取得待ち"
        "running" -> "取得中"
        "no_senders" -> "送信元の登録待ち"
        "disabled" -> "自動取得は停止中"
        "auth_required" -> "Gmail接続の確認が必要"
        "failed" -> "接続を確認してください"
        else -> "取得前"
    }

@Composable
private fun CareerEvidenceCard(evidence: JsonObject) {
    val spacing = LocalSpacing.current
    val uri = LocalUriHandler.current
    var expanded by rememberSaveable(evidence.text("id")) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(evidence.text("title").orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text(
                "${if (evidence.text(
                        "kind",
                    ) == "mail"
                ) {
                    "メール"
                } else {
                    "公式情報"
                }} · ${evidence.text("occurred_at", "first_observed_at").orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { expanded = !expanded },
                shapes = ButtonDefaults.shapes(),
            ) { Text(if (expanded) "原文を閉じる" else "原文を読む") }
            if (expanded) SelectionContainer { Text(evidence.text("excerpt").orEmpty()) }
            evidence.text("locator")?.let { locator ->
                if (locator.startsWith("https://")) TextButton(onClick = { uri.openUri(locator) }) { Text("元の案内を開く") }
            }
        }
    }
}

@Composable
private fun CareerCompanyListFrame(
    embedded: Boolean,
    title: @Composable () -> Unit,
    navigationIcon: NavIcon,
    drawerGesturesEnabled: Boolean,
    isRefreshing: Boolean,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (embedded) {
        content()
    } else {
        HermesScaffold(
            title = title,
            navigationIcon = navigationIcon,
            drawerGesturesEnabled = drawerGesturesEnabled,
            isRefreshing = isRefreshing,
            actions = actions,
        ) {
            content()
        }
    }
}

internal val careerConnectionFilters =
    linkedMapOf(
        "login_missing" to "ログイン未設定",
        "login_unverified" to "ログイン成功未確認",
        "login_attention" to "ログイン要確認",
    )

internal val careerApplicationFilters =
    linkedMapOf(
        "all" to "すべて",
        "preparing" to "応募予定・準備中",
        "in_process" to "応募済み・選考中",
        "offered" to "内定",
        "closed" to "終了",
        "review" to "要確認",
    )
