package com.m57.hermescontrol.career

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.home.HomeDetailKey
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FlatListRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun careerDataModel(scope: String): CareerViewModel {
    val app = LocalContext.current.applicationContext
    return viewModel(
        key = "career:$scope",
        factory =
            viewModelFactory {
                initializer { CareerViewModel(DashboardCareerRepository(scope), CareerLocalStore(app, scope)) }
            },
    )
}

@Composable
fun CareerAppScreen() {
    val context by AuthManager.contextFlow.collectAsState()
    val scope = remember(context) { careerScope() }
    key(scope) {
        val model = careerDataModel(scope)
        val state by model.state.collectAsState()
        val app = LocalContext.current.applicationContext
        val local = remember(scope) { CareerLocalStore(app, scope) }
        val jobs = rememberCoroutineScope()
        var area by rememberSaveable(scope) { mutableStateOf("ホーム") }
        var restored by remember(scope) { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var dashboardRefresh by remember { mutableStateOf(0) }
        // One refresh action for every area; each area reloads its own data when this changes.
        var refreshTick by remember { mutableStateOf(0) }
        val refreshAll: () -> Unit = {
            model.refresh()
            dashboardRefresh++
            refreshTick++
        }
        var selected by remember { mutableStateOf<CareerEntity?>(null) }
        LaunchedEffect(scope) {
            try {
                local.uiValue("area")?.takeIf { it in listOf("ホーム", "企業", "相談", "資料") }?.let { area = it }
            } catch (
                failure: Exception,
            ) {
                if (failure is CancellationException) throw failure
                error =
                    careerError(failure)
            }
            restored = true
        }
        val areaState =
            androidx.compose.runtime.saveable
                .rememberSaveableStateHolder()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.refresh() } }
        HermesScaffold(
            title = { Text("就活") },
            navigationIcon = NavIcon.Back { NavigationController.goBack() },
            drawerGesturesEnabled = false,
            pinTopBar = true,
            actions = {
                IconButton(onClick = refreshAll, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "更新")
                }
                IconButton(onClick = { NavigationController.navigateTo(CareerSettingsKey) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "設定")
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                CareerAreaTabs(area, restored) { label ->
                    area = label
                    jobs.launch {
                        try {
                            local.saveUiValue("area", label)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = careerError(failure)
                        }
                    }
                }
                error?.let { Text(it) }
                areaState.SaveableStateProvider(area) {
                    when {
                        state.initializationError != null -> {
                            ErrorState(state.initializationError.orEmpty(), onRetry = model::retryInitialization)
                        }

                        !state.initialized -> {
                            LoadingState()
                        }

                        !state.enabled -> {
                            Column(Modifier.padding(LocalSpacing.current.lg)) {
                                Text("企業・締切・応募状況・会話は接続先のサーバーへ保存します。本人情報・経験・ESは既存のprivate GitHub同期を使います。")
                                Text("端末には暗号化したキャッシュと未完了の操作を保持します。")
                                Button(
                                    onClick = { model.enable(true) },
                                    shapes = ButtonDefaults.shapes(),
                                ) { Text("就活を開く") }
                            }
                        }

                        area == "企業" -> {
                            CareerScreen(embedded = true, refreshKey = refreshTick)
                        }

                        area == "相談" -> {
                            val threads = careerThreadsModel(scope)
                            val threadState by threads.state.collectAsState()
                            LaunchedEffect(threads, refreshTick) { threads.refresh() }
                            CareerThreadsContent(threadState, threads)
                        }

                        area == "資料" -> {
                            CareerLibraryContent(state, model::refresh) { selected = it }
                        }

                        else -> {
                            CareerDashboard(state, dashboardRefresh, refreshAll) { selected = it }
                        }
                    }
                }
            }
        }
        selected?.let { entity -> CareerEntityPresentation(entity, state, model, { selected = null }) }
    }
}

@Composable
internal fun CareerDashboard(
    state: CareerUiState,
    refreshKey: Int,
    refresh: () -> Unit,
    select: (CareerEntity) -> Unit,
) {
    val feed = remember { CareerHomeFeed { CareerHomeProvider().read() } }
    val home by feed.state.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, refreshKey, state.snapshot) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                now = Instant.now()
                feed.refresh()
                delay(60_000)
            } while (true)
        }
    }
    val entities = state.snapshot?.entities.orEmpty()
    CareerDashboardContent(
        state = state,
        page = home.page,
        pageError = home.error,
        pageLoading = home.loading,
        today = now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate(),
        now = now,
        onSelect = select,
        onTask = { entity ->
            val company =
                entity.data.text("company_id")
                    ?: entities
                        .firstOrNull {
                            it.kind == "offers" && it.id ==
                                entity.data.text(
                                    "offer_id",
                                )
                        }?.data
                        ?.text("company_id")
            if (company != null) {
                NavigationController.navigateTo(
                    CareerCompanyKey(
                        company,
                        if (entity.kind == "offers") CareerCompanyTab.OFFERS else CareerCompanyTab.SELECTION,
                        entity.id,
                    ),
                )
            } else {
                select(entity)
            }
        },
        onAttention = { NavigationController.navigateTo(CareerConversationKey(it.target.thread_id ?: "global")) },
        onResult = { NavigationController.navigateTo(HomeDetailKey(AppScope.connectionKey(), it)) },
        onSettings = { NavigationController.navigateTo(CareerSettingsKey) },
        onRefresh = refresh,
    )
}

internal fun careerMaterialCategory(entity: CareerEntity): String =
    when (entity.kind) {
        "profile", "facts" -> {
            "本人情報"
        }

        "stories" -> {
            "経験"
        }

        "hypotheses" -> {
            "自己分析"
        }

        else -> {
            if (entity.data.text("type", "document_type") in
                setOf("self_analysis", "self-analysis", "自己分析")
            ) {
                "自己分析"
            } else {
                "ES"
            }
        }
    }

@Composable
internal fun CareerLibraryContent(
    state: CareerUiState,
    refresh: () -> Unit,
    select: (CareerEntity) -> Unit,
) {
    val spacing = LocalSpacing.current
    var category by rememberSaveable { mutableStateOf("自己分析") }
    val snapshot = state.snapshot
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = spacing.md)) {
            listOf("自己分析", "ES", "本人情報", "経験").forEach { label ->
                FilterChip(category == label, { category = label }, label = { Text(label) })
            }
        }
        when {
            snapshot == null && state.loading -> {
                LoadingState()
            }

            snapshot == null && state.error != null -> {
                ErrorState(state.error, refresh)
            }

            snapshot == null -> {
                EmptyState("資料はまだありません")
            }

            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = spacing.md),
                ) {
                    item {
                        CareerStatusBanner(
                            careerStatusOf(listOf(state.error), state.stale, "保存済みの資料を表示しています。"),
                            refresh,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    select(
                                        newCareerEntity(
                                            when (category) {
                                                "本人情報" -> "facts"
                                                "経験" -> "stories"
                                                "自己分析" -> "hypotheses"
                                                else -> "documents"
                                            },
                                            snapshot,
                                        ),
                                    )
                                },
                                enabled =
                                    !state.stale && !state.busy && state.pending == null,
                                shapes = ButtonDefaults.shapes(),
                            ) { Text("追加") }
                        }
                    }
                    val rows =
                        snapshot.entities.filter {
                            it.kind in
                                setOf("profile", "facts", "stories", "hypotheses", "documents") &&
                                careerMaterialCategory(it) == category
                        }
                    items(rows, key = { it.kind + it.id }) { entity -> CareerEntityRow(entity) { select(entity) } }
                    if (rows.isEmpty()) item { EmptyState("この分類の資料はまだありません") }
                }
            }
        }
    }
}

@Composable
internal fun CareerEntityRow(
    entity: CareerEntity,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    FlatListRow(onClick = onClick) {
        Column(Modifier.padding(vertical = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(entity.title, style = MaterialTheme.typography.bodyLarge)
            val meta =
                listOfNotNull(
                    careerStatus(entity.data.text("classification", "status", "decision")),
                    entity.data.text("deadline", "scheduledAt"),
                )
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CareerEntityPresentation(
    entity: CareerEntity,
    state: CareerUiState,
    model: CareerViewModel,
    dismiss: () -> Unit,
) {
    var editingDocument by remember(entity.id) { mutableStateOf<CareerEntity?>(null) }
    var submitted by remember(entity.id) { mutableStateOf(false) }
    LaunchedEffect(state.busy, state.message, state.pending) {
        if (submitted && !state.busy && state.pending == null && state.message != null) dismiss()
    }
    val target = CareerTarget(entity.kind, entity.id, entity.data.text("version")?.toIntOrNull())
    val company = entity.data.text("company_id") ?: (entity.data["career_context"] as? JsonObject)?.text("company_id")
    val offer = entity.data.text("offer_id") ?: (entity.data["career_context"] as? JsonObject)?.text("offer_id")
    val questions = ((entity.data["career_context"] as? JsonObject)?.get("question_refs") as? JsonArray).orEmpty()
    val questionId = (questions.singleOrNull() as? JsonObject)?.text("id")
    val consult = {
        NavigationController.navigateTo(CareerThreadsKey(company, offer, target = target))
        dismiss()
    }
    if (editingDocument != null) {
        CareerEntityDialog(
            editingDocument!!,
            state.snapshot,
            state.enabled && !state.stale && !state.busy && state.pending == null,
            { editingDocument = null },
            { action, payload ->
                submitted = true
                model.send(action, payload)
            },
            initiallyEditing = true,
            status = state.error ?: state.message,
            retry = if (state.pending != null) model::retry else null,
        )
    } else if (entity.kind == "documents" && entity.version > 0) {
        CareerDocumentReadDialog(
            entity,
            "global",
            onDismiss = dismiss,
            onConsultOverride = { version ->
                NavigationController.navigateTo(
                    CareerThreadsKey(company, offer, target = target.copy(version = version)),
                )
                dismiss()
            },
            onEdit = { _, body ->
                if (company != null && offer != null) {
                    NavigationController.navigateTo(CareerEsKey(company, offer, questionId))
                    dismiss()
                } else {
                    editingDocument = entity.copy(data = entity.data.with("body" to JsonPrimitive(body)))
                }
            },
        )
    } else {
        CareerEntityDialog(
            entity,
            state.snapshot,
            state.enabled && !state.stale && !state.busy && state.pending == null,
            dismiss,
            { action, payload ->
                submitted = true
                model.send(action, payload)
            },
            consult,
            status = state.error ?: state.message,
            retry = if (state.pending != null) model::retry else null,
        )
    }
}

@Composable
fun CareerEntityScreen(entityId: String) {
    val origin = remember { NavigationController.backStack?.last() }
    val connection by AuthManager.contextFlow.collectAsState()
    val scope = remember(connection) { careerScope() }
    val model = careerDataModel(scope)
    val state by model.state.collectAsState()
    HermesScaffold(title = {
        Text("詳細")
    }, navigationIcon = NavIcon.Back { NavigationController.goBack() }, drawerGesturesEnabled = false) {
        state.error?.let { Text(it) }
        if (state.loading) LoadingState()
        val entity = state.snapshot?.entities?.firstOrNull { it.id == entityId }
        if (entity != null) {
            CareerEntityPresentation(entity, state, model) {
                // A consultation pushes a new destination before the dialog closes; keep its return entry.
                if (NavigationController.backStack?.last() == origin) NavigationController.goBack()
            }
        } else if (!state.loading) {
            EmptyState("対象を確認できません", actionLabel = "更新", onAction = model::refresh)
        }
    }
}
