package com.m57.hermescontrol.career

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.miniapp.sdui.CareerMyPageSduiKey
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.HermesSecondaryIndicator
import com.m57.hermescontrol.ui.common.HermesTextField
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val companyTabs = listOf("概要", "募集・締切", "メール", "選考", "メモ")

/** All reader state lives above the pager. Composing a page never starts work. */
@Composable
internal fun CareerCompanyPager(
    state: CareerCompanyState,
    initialTab: Int,
    onRefresh: () -> Unit,
    onCollectMail: () -> Unit,
    onCollectMyPage: () -> Unit,
    onSetSender: (String, Boolean) -> Unit,
    onPreferences: (String, String, Int) -> Unit,
    onConnections: () -> Unit,
    mailActions: (@Composable () -> Unit)? = null,
    onLoadSavedMail: () -> Unit = {},
    onDeleteCompany: (Int, String) -> Unit = { _, _ -> },
    settingsOnly: Boolean = false,
    initialEntityId: String? = null,
    onResumeMyPageAnalysis: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val detail = state.detail
    val readKeys = rememberCareerMailReadKeys()
    var dirtySections by rememberSaveable { mutableStateOf(listOf<String>()) }
    val uri = LocalUriHandler.current
    val notesModel = detail?.company?.let { careerNoteModel(it) }
    val dataModel = careerDataModel(careerScope())
    val dataState by dataModel.state.collectAsState()
    var selectedEntity by remember { mutableStateOf<CareerEntity?>(null) }
    LaunchedEffect(initialEntityId, detail) {
        if (initialEntityId !=
            null
        ) {
            selectedEntity =
                (detail?.records.orEmpty() + detail?.offers.orEmpty()).firstOrNull { it.id == initialEntityId }
        }
    }

    val pager =
        rememberPagerState(
            initialPage = if (settingsOnly) 0 else initialTab.coerceIn(0, 4),
        ) { if (settingsOnly) 1 else 5 }
    val scope = rememberCoroutineScope()
    val listStates = List(5) { rememberLazyListState() }
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var selectedOffer by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMail by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var referenceTitle by rememberSaveable { mutableStateOf("") }
    val manageDraft = rememberSaveable(saver = CompanyManageDraft.Saver) { CompanyManageDraft() }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showSenders by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var leaveToResults by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.preferenceWrite) {
        if (state.preferenceWrite.phase == CompanyWritePhase.SAVED &&
            state.preferenceWrite.requestVersion == manageDraft.version
        ) {
            manageDraft.reset()
        }
    }
    LaunchedEffect(state.senderWrite) {
        val write = state.senderWrite
        if (write.phase == CompanyWritePhase.SAVED && write.requestId != manageDraft.consumedSenderRequest) {
            if (manageDraft.sender.trim().equals(write.address, ignoreCase = true)) manageDraft.sender = ""
            manageDraft.consumedSenderRequest = write.requestId
        }
    }

    val managementSaving =
        "login-saving" in dirtySections || "mypage-saving" in dirtySections || dataState.busy ||
            state.preferenceWrite.phase == CompanyWritePhase.SAVING ||
            state.senderWrite.phase == CompanyWritePhase.SAVING

    fun finishLeaving() {
        if (leaveToResults && detail != null) {
            NavigationController.navigateTo(CareerCompanyKey(detail.company.id, CareerCompanyTab.OFFERS))
        } else {
            NavigationController.goBack()
        }
    }

    fun leave(toResults: Boolean = false) {
        leaveToResults = toResults
        if (dirtySections.isNotEmpty() || dataState.busy || manageDraft.dirty || manageDraft.sender.isNotBlank() ||
            managementSaving
        ) {
            confirmLeave = true
        } else if (notesModel?.state?.value?.loaded == true) {
            notesModel.persist { finishLeaving() }
        } else {
            finishLeaving()
        }
    }
    BackHandler(enabled = !showSenders) {
        leave()
    }
    val offer = detail?.offers?.firstOrNull { it.id == selectedOffer }
    val mail = detail?.mail?.firstOrNull { "${it.account_id}:${it.message_id}" == selectedMail }
    val notice =
        detail?.mypage?.notices?.firstOrNull { it.text("id") == selectedNotice }
            ?: detail?.mypage?.history?.firstOrNull { it.text("id") == selectedNotice }

    fun prepare(
        ref: CareerSourceRef,
        title: String,
    ) {
        if (detail == null) return
        selectedMail = null
        selectedNotice = null
        NavigationController.navigateTo(
            CareerThreadsKey(
                detail.company.id,
                offer?.id,
                target =
                    CareerTarget(offer?.kind ?: "companies", offer?.id ?: detail.company.id, source_refs = listOf(ref)),
            ),
        )
    }
    HermesScaffold(
        title = {
            Text(
                if (settingsOnly) "企業設定" else detail?.company?.title ?: "企業詳細",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            if (!settingsOnly && detail != null) {
                TextButton(onClick = {
                    NavigationController.navigateTo(CareerThreadsKey(detail.company.id, selectedOffer))
                }) { Text("AI相談") }
                TextButton(
                    onClick = { NavigationController.navigateTo(CareerCompanySettingsKey(detail.company.id)) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null)
                    Text("企業設定")
                }
            }
        },
        navigationIcon = NavIcon.Back { leave() },
        drawerGesturesEnabled = false,
        pinTopBar = true,
        isRefreshing = state.loading,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!settingsOnly) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = pager.currentPage,
                    modifier = Modifier.fillMaxWidth().testTag("company_tabs"),
                    indicator = { HermesSecondaryIndicator(pager.currentPage) },
                ) {
                    companyTabs.forEachIndexed { index, label ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("company_tab_$index"),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier
                                        .padding(horizontal = spacing.sm, vertical = spacing.md)
                                        .testTag("company_tab_label_$index"),
                            )
                        }
                    }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).testTag("company_pager"), key = { it }) { page ->
                LazyColumn(
                    state = listStates[page],
                    modifier = Modifier.fillMaxSize().testTag("company_page_$page"),
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    if (detail == null) {
                        item { Text(if (state.loading) "企業情報を読み込んでいます…" else "企業情報を確認できません。") }
                    }
                    state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    state.message?.let { item { Text(it) } }
                    if (state.stale &&
                        detail != null
                    ) {
                        item { Text("保存済みの情報を表示しています。", color = MaterialTheme.colorScheme.error) }
                    }
                    when (if (settingsOnly) 5 else page) {
                        0 -> {
                            item {
                                detail?.let {
                                    CareerCompanyOverview(
                                        it,
                                        state.loginAccounts,
                                    ) { selectedEntity = it }
                                }
                            }
                            // Additive: the native MyPage browser and settings stay where they are.
                            item {
                                detail?.let {
                                    OutlinedButton(
                                        onClick = {
                                            NavigationController.navigateTo(CareerMyPageSduiKey(it.company.id))
                                        },
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("company_mypage_sdui"),
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("マイページの状況を見る") }
                                }
                            }
                        }

                        1 -> {
                            item { Text("募集・締切", style = MaterialTheme.typography.titleMedium) }
                            items(detail?.offers.orEmpty(), key = { it.id }) { offer ->
                                CareerEntityRow(offer) { selectedEntity = offer }
                                Text("対象年度: ${offer.data.text("target_year") ?: "未確認"}")
                                offer.data.text("source_url", "url")?.takeIf { it.startsWith("https://") }?.let { url ->
                                    TextButton(
                                        onClick = { uri.openUri(url) },
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("出典を開く") }
                                }
                                TextButton(onClick = {
                                    detail?.let {
                                        NavigationController.navigateTo(
                                            CareerEsKey(it.company.id, offer.id),
                                        )
                                    }
                                }, modifier = Modifier.testTag("company_es_${offer.id}")) { Text("関連ESを開く") }
                            }
                            if (detail?.offers.isNullOrEmpty()) item { Text("保存済みの募集はありません。") }
                            item { Text("保存済みのマイページ案内", style = MaterialTheme.typography.titleMedium) }
                            item {
                                SourceStatus(
                                    "マイページ",
                                    detail?.mypage?.status,
                                    detail?.mypage?.notices?.size ?: 0,
                                    state.collecting == "mypage",
                                )
                                MyPageRunProgress(detail?.mypage)
                                TextButton(
                                    onClick = onRefresh,
                                    enabled = !state.loading,
                                    modifier = Modifier.testTag("mypage_results_refresh"),
                                    shapes = ButtonDefaults.shapes(),
                                ) { Text("取得結果を更新") }
                                FlowRow {
                                    if (detail?.mypage?.analysis_resumable == true) {
                                        TextButton(
                                            onClick = onResumeMyPageAnalysis,
                                            enabled =
                                                !state.loading && detail.mypage.status.text("state") !in
                                                    setOf("queued", "running", "collecting", "analyzing"),
                                            modifier = Modifier.testTag("mypage_resume_analysis"),
                                            shapes = ButtonDefaults.shapes(),
                                        ) { Text("保存済み本文の解析を再開") }
                                    }
                                    val reason =
                                        detail
                                            ?.mypage
                                            ?.status
                                            ?.text("reason")
                                            .orEmpty()
                                    if (detail?.mypage?.analysis_resumable == true || reason.startsWith("model_") ||
                                        reason in
                                        setOf(
                                            "mypage_execution_unset",
                                            "mypage_model_unset",
                                            "provider_unavailable",
                                        )
                                    ) {
                                        TextButton(
                                            onClick = { NavigationController.navigateTo(CareerSettingsKey) },
                                            shapes = ButtonDefaults.shapes(),
                                        ) { Text("取得モデルを設定（就活設定→AI）") }
                                    }
                                    TextButton(
                                        onClick = onCollectMyPage,
                                        enabled =
                                            !state.loading && detail?.mypage?.supported == true,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text(if (detail?.mypage?.connection_version != 0) "今すぐ確認" else "案内を取得") }
                                    // The top bar already opens company settings; offer it here only when a
                                    // decision is waiting there or MyPage is not set up.
                                    if (detail?.mypage?.listing_proposal != null) {
                                        Button(onClick = {
                                            scope.launch {
                                                NavigationController.navigateTo(
                                                    CareerCompanySettingsKey(detail.company.id),
                                                )
                                            }
                                        }) { Text("一覧の提案を確認") }
                                    } else if (detail?.mypage?.supported == false) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    NavigationController.navigateTo(
                                                        CareerCompanySettingsKey(detail.company.id),
                                                    )
                                                }
                                            },
                                            shapes = ButtonDefaults.shapes(),
                                        ) { Text("マイページを設定") }
                                    }
                                }
                                if (detail?.mypage == null) Text("マイページ表示には就活サーバーの更新が必要です。")
                                detail?.mypage?.next_run_at?.let { Text("次回確認：${careerDisplayTime(it)}") }
                                detail?.mypage?.schedule_error?.let { Text(companyCollectionReason(it).orEmpty()) }
                                MyPageRunHistory(detail?.mypage)
                                if (detail?.mypage?.automation == "enabled" && detail.mypage.verified.not()) {
                                    Text("接続確認に成功すると、毎日9時・18時の定期確認を開始します。")
                                }
                            }
                            itemsIndexed(
                                detail?.mypage?.notices.orEmpty(),
                                key = { index, item -> "notice:$index:${item.text("id").orEmpty()}" },
                            ) { _, item ->
                                Card(
                                    onClick = { selectedNotice = item.text("id") },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        Modifier.padding(spacing.md),
                                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                                    ) {
                                        Text(item.text("title").orEmpty(), style = MaterialTheme.typography.titleMedium)
                                        item.text("target_year")?.let { Text("対象年度：$it") }
                                        val dates = item["deadlines"] as? JsonArray
                                        dates.orEmpty().forEach { date ->
                                            Text(
                                                (date as? JsonObject)?.text("original_text").orEmpty(),
                                                style = MaterialTheme.typography.titleLarge,
                                            )
                                        }
                                        if (dates.isNullOrEmpty()) {
                                            Text(
                                                "締切の記載は確認できていません。",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Text(
                                            item.text("excerpt").orEmpty(),
                                            maxLines = 2,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text("案内の詳細・原文を読む", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            item {
                                Text(
                                    detail?.mypage?.coverage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        2 -> {
                            item {
                                SourceStatus(
                                    "メール",
                                    detail?.mail_status,
                                    detail?.mail?.size ?: 0,
                                    state.collecting == "mail",
                                )
                                if (mailActions == null) {
                                    TextButton(
                                        onClick = onCollectMail,
                                        enabled = !state.loading,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("全企業を更新") }
                                }
                                mailActions?.invoke()
                                HermesTextField(query, {
                                    query = it
                                }, label = { Text("メールを検索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                if (detail?.mail?.isNotEmpty() == true ||
                                    (
                                        detail?.mail_status?.text("last_success_at") != null &&
                                            detail.mail_status.text("state") !in setOf("failed", "auth_required")
                                    )
                                ) {
                                    Text(
                                        "未読 ${detail?.mail.orEmpty().count { it.isUnread(readKeys) }}件（表示中）",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                FilterChip(unreadOnly, { unreadOnly = !unreadOnly }, label = { Text("未読のみ") })
                                if (detail?.mail_senders.orEmpty().none { it.enabled }) {
                                    Text("送信元メールアドレスが未設定です。")
                                    TextButton(
                                        onClick = {
                                            detail?.let {
                                                NavigationController.navigateTo(
                                                    CareerCompanySettingsKey(it.company.id),
                                                )
                                            }
                                        },
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text("送信元メールアドレスを設定")
                                    }
                                }
                            }
                            val messages =
                                detail?.mail.orEmpty().filter {
                                    (!unreadOnly || it.isUnread(readKeys)) &&
                                        listOf(it.subject, it.from_text, it.body, it.snippet).any { text ->
                                            text.contains(query, true)
                                        }
                                }
                            if (messages.isEmpty() &&
                                (query.isNotBlank() || unreadOnly)
                            ) {
                                item { Text("検索条件に一致するメールはありません。") }
                            }
                            items(messages, key = { "${it.account_id}:${it.message_id}" }) { item ->
                                CareerMailRow(item, item.isUnread(readKeys)) {
                                    selectedMail =
                                        "${item.account_id}:${item.message_id}"
                                }
                            }
                            if (detail?.mail_next_cursor != null) {
                                item {
                                    TextButton(
                                        onClick = onLoadSavedMail,
                                        enabled = !state.loading,
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text("保存済みメールをさらに表示")
                                    }
                                }
                            }
                        }

                        3 -> {
                            companySelectionPreferenceItems(state, manageDraft, onPreferences)
                            item { Text("募集ごとの選考・次の作業", style = MaterialTheme.typography.titleMedium) }
                            val offers = detail?.offers.orEmpty()
                            offers.forEach { offer ->
                                item { Text(offer.title, style = MaterialTheme.typography.titleSmall) }
                                items(
                                    detail?.records.orEmpty().filter { it.data.text("offer_id") == offer.id },
                                    key = { it.id },
                                ) { entity ->
                                    CareerEntityRow(entity) {
                                        selectedEntity =
                                            entity
                                    }
                                }
                                item {
                                    detail?.let {
                                        CareerSelectionActions(it.company.id, offer.id) {
                                            selectedEntity =
                                                it
                                        }
                                    }
                                }
                            }
                            item { Text("企業全体・募集未指定", style = MaterialTheme.typography.titleSmall) }
                            items(
                                detail?.records.orEmpty().filter { record ->
                                    offers.none {
                                        it.id ==
                                            record.data.text("offer_id")
                                    }
                                },
                                key = { it.id },
                            ) { entity ->
                                CareerEntityRow(entity) {
                                    selectedEntity =
                                        entity
                                }
                            }
                            item { detail?.let { CareerSelectionActions(it.company.id, null) { selectedEntity = it } } }
                        }

                        4 -> {
                            item { detail?.let { CareerCompanyNotes(it.company, dataState, dataModel) } }
                        }

                        5 -> {
                            if (dataState.stale) {
                                item {
                                    Text("保存済みの情報を表示しています。接続後に更新してください。")
                                    TextButton(
                                        onClick = dataModel::refresh,
                                        enabled = !dataState.loading,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("保存情報を更新") }
                                }
                            }
                            if (dataState.pending != null && dataState.pending?.action != "save") {
                                item {
                                    Text("保存状況の確認", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "前の操作が保留中です。再確認するか、再試行を取りやめてから編集できます。",
                                    )
                                    TextButton(
                                        onClick = dataModel::retry,
                                        enabled = !dataState.busy,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("保存結果を再確認") }
                                    TextButton(
                                        onClick = dataModel::discardPending,
                                        enabled = !dataState.busy,
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text("再試行を取りやめる") }
                                }
                            }
                            detail?.let {
                                companySettingsEditors(
                                    it,
                                    state.loginAccounts,
                                    dataState,
                                    dataModel,
                                    onRefresh,
                                    onShowMyPageResults = { leave(toResults = true) },
                                ) { id, dirty ->
                                    dirtySections = if (dirty) (dirtySections + id).distinct() else dirtySections - id
                                }
                            }
                            companySenderItems(state, manageDraft, onSetSender)
                            item {
                                TextButton(
                                    onClick = onConnections,
                                    shapes = ButtonDefaults.shapes(),
                                ) { Text("Gmail接続設定") }
                                TextButton(
                                    onClick = { showDelete = true },
                                    modifier = Modifier.testTag("company_delete"),
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text("企業を削除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedEntity?.let { entity ->
        CareerEntityPresentation(entity, dataState, dataModel) {
            selectedEntity = null
            onRefresh()
        }
    }
    if (showDelete) DeleteCompanyDialog(state, onDeleteCompany) { showDelete = false }
    if (showSenders) CompanySenderSheet(state, manageDraft, onSetSender) { showSenders = false }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (managementSaving) "処理中です" else "未保存の変更があります") },
            text = {
                Text(
                    when {
                        managementSaving -> "保存の結果を確認してから移動してください。"
                        leaveToResults -> "変更を破棄して取得結果へ進みますか？"
                        else -> "変更を破棄して企業詳細を閉じますか？"
                    },
                )
            },
            confirmButton = {
                if (!managementSaving) {
                    TextButton(onClick = {
                        manageDraft.reset()
                        manageDraft.sender = ""
                        confirmLeave = false
                        finishLeaving()
                    }) { Text(if (leaveToResults) "変更を破棄して結果を見る" else "変更を破棄して戻る") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("編集を続ける") } },
        )
    }
    mail?.let { item ->
        CareerMailBodySheet(item, onDismiss = { selectedMail = null }) {
            prepare(CareerSourceRef("mail", item.message_id, item.account_id), item.subject)
        }
    }
    notice?.let { item ->
        SourceReadDialog(item.text("title").orEmpty(), item.text("excerpt").orEmpty(), item.text("locator").orEmpty(), {
            selectedNotice =
                null
        }, sourceIdentity = item.text("source_key")?.let { "source:$it" }) {
            prepare(CareerSourceRef("mypage", item.text("id").orEmpty()), item.text("title").orEmpty())
        }
    }
}

@Composable
internal fun SourceStatus(
    label: String,
    status: JsonObject?,
    cachedCount: Int,
    collecting: Boolean = false,
) {
    if (label == "メール" && status?.text("version") == "2") {
        CareerMailSyncStatus(status, cachedCount)
        return
    }
    val phase = if (collecting) "running" else status?.text("state")
    val success = status?.text("last_success_at")
    val failed = phase in setOf("failed", "auth_required")
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(LocalSpacing.current.md),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
        ) {
            Text(
                when {
                    failed && cachedCount > 0 -> "$label を更新できませんでした。前回取得した情報を表示しています。"
                    failed -> "$label を取得できませんでした。件数は不明です。"
                    phase == "queued" -> "$label の確認を受け付けました。順番に確認します。"
                    phase == "running" -> if (cachedCount > 0) "$label を取得中です。前回の情報を表示しています。" else "$label を取得中です。"
                    phase == "partial" -> "$label は一部取得済みです。続きの確認が必要です。"
                    phase in setOf("analysis_pending", "pending_analysis") -> "$label の本文は保存済みです。AIでの整理を再開できます。"
                    success != null && cachedCount == 0 -> "確認した範囲に$label はありませんでした。"
                    success != null || cachedCount > 0 -> "$label $cachedCount 件"
                    else -> "まだ$label を取得していません。"
                },
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (cachedCount > 0 &&
                phase in setOf("failed", "auth_required", "queued", "running", "partial")
            ) {
                Text("保存済み $cachedCount 件")
            }
            success?.let { Text("最終更新 ${careerDisplayTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (phase == "auth_required") Text("認証の確認が必要です。管理から接続状態を確認してください。")
            companyCollectionReason(status?.text("reason"))?.let { Text(it) }
        }
    }
}

@Composable
internal fun SourceReadDialog(
    title: String,
    body: String,
    url: String,
    onDismiss: () -> Unit,
    sourceIdentity: String? = null,
    onConsult: () -> Unit,
) {
    val uri = LocalUriHandler.current
    sourceIdentity?.let { CareerHomeReadEffect(it, title + "\n" + body) }
    BackHandler(onBack = onDismiss)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(body) }
                sourceIdentity?.let { CareerSourceResolution(it) }
                if (url.startsWith("https://")) TextButton(onClick = { uri.openUri(url) }) { Text("元の案内を開く") }
            }
        },
        confirmButton = { TextButton(onClick = onConsult, shapes = ButtonDefaults.shapes()) { Text("この内容について相談") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("閉じる") } },
    )
}
