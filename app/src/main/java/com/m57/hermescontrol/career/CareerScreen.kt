package com.m57.hermescontrol.career

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Composable
fun CareerConversationScreen(
    threadId: String = "global",
    initialPrompt: String = "",
    initialTarget: CareerTarget? = null,
    initialEntityId: String? = null,
    handoffId: String? = null,
    title: String = "就活の相談",
    readOnly: Boolean = false,
    legacyEsQuestionId: String? = null,
) = CareerWorkspaceScreen(
    threadId,
    initialPrompt,
    initialTarget,
    initialEntityId,
    settingsOnly = false,
    handoffId = handoffId,
    title = title,
    readOnly = readOnly,
    legacyEsQuestionId = legacyEsQuestionId,
)

@Composable
fun CareerSettingsScreen() = CareerWorkspaceScreen(settingsOnly = true)

@Composable
private fun CareerWorkspaceScreen(
    threadId: String = "global",
    initialPrompt: String = "",
    initialTarget: CareerTarget? = null,
    initialEntityId: String? = null,
    settingsOnly: Boolean,
    handoffId: String? = null,
    title: String = "就活の相談",
    readOnly: Boolean = false,
    legacyEsQuestionId: String? = null,
) {
    val context by AuthManager.contextFlow.collectAsState()
    val profile by AuthManager.activeProfileId.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val scope = remember(context, profile) { careerScope() }
    val newConversation = remember(threadId) { CareerNewConversation.fromThreadId(threadId) }
    if (threadId.startsWith("draft:") && newConversation == null) {
        HermesScaffold(title = {
            Text("相談")
        }, navigationIcon = NavIcon.Back { NavigationController.goBack() }, drawerGesturesEnabled = false) {
            EmptyState("下書きを開けません", subtitle = "相談一覧から開き直してください。保存データは保持しています。")
        }
        return
    }
    key(scope) {
        val model: CareerViewModel =
            viewModel(
                key = "career:$scope",
                factory =
                    viewModelFactory {
                        initializer {
                            CareerViewModel(
                                DashboardCareerRepository(scope),
                                CareerLocalStore(appContext, scope),
                            )
                        }
                    },
            )
        val state by model.state.collectAsState()
        val starter =
            remember(scope, threadId) {
                newConversation?.let { careerNewConversationRepository(it, appContext, scope) }
            }
        val coachLocalScope =
            if (legacyEsQuestionId != null && threadId.startsWith("offer:")) {
                "$scope/es-conversation:${threadId.removePrefix("offer:")}:$legacyEsQuestionId"
            } else if (threadId == "global") {
                scope
            } else {
                "$scope/thread/$threadId"
            }
        val storageGenerations by CareerCoachStorage.generations.collectAsState()
        val storageGeneration = storageGenerations[coachLocalScope] ?: 0
        val coach: CareerCoachViewModel =
            viewModel(
                key = "career-coach:$scope:$threadId:${legacyEsQuestionId.orEmpty()}:$storageGeneration",
                factory =
                    viewModelFactory {
                        initializer {
                            CareerCoachViewModel(
                                starter ?: DashboardCareerCoachRepository(scope, threadId),
                                CareerLocalStore(
                                    appContext,
                                    coachLocalScope,
                                ),
                                model::refresh,
                                defaults = CareerLocalStore(appContext, scope),
                                storageKey = coachLocalScope,
                            )
                        }
                    },
            )
        val observedCoachState by coach.state.collectAsState()
        val coachState = observedCoachState.copy(readOnly = readOnly || observedCoachState.snapshot?.archived == true)
        LaunchedEffect(starter, coachState.snapshot?.thread_id, coachState.busy, coachState.pending) {
            if (starter != null && !coachState.busy && coachState.pending == null &&
                coachState.snapshot?.thread_id?.startsWith("thread:") == true
            ) {
                try {
                    val created = starter.complete()
                    val current = NavigationController.backStack?.lastOrNull() as? CareerConversationKey
                    if (created != null && current?.threadId == threadId) {
                        NavigationController.goBack()
                        NavigationController.navigateTo(CareerConversationKey(created.id, title = created.title))
                    }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    coach.handoffFailed(careerError(failure))
                }
            }
        }
        var prepared by rememberSaveable(threadId, handoffId, initialPrompt, initialTarget) { mutableStateOf(false) }
        LaunchedEffect(
            coachState.initialized,
            coachState.busy,
            coachState.pending,
            handoffId,
            initialPrompt,
            initialTarget,
        ) {
            if (coachState.initialized && !prepared && !coachState.busy && coachState.pending == null) {
                try {
                    if (handoffId != null) {
                        val handoff = CareerLocalStore(appContext, scope).uiValue("handoff/$handoffId")
                        check(handoff != null) { "参照する回答を読み込めません。ES画面の下書きを保持しています。" }
                        val answerContext =
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(handoff)
                                .jsonObject
                        coach.prepareAnswerConversation(initialPrompt, initialTarget, answerContext)
                    } else if (initialPrompt.isNotBlank() || initialTarget != null) {
                        coach.prepare(initialPrompt, initialTarget)
                    }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    coach.handoffFailed(careerError(failure))
                }
                prepared = true
            }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, state.initialized, state.enabled, coach) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) coach.observe(state.initialized && state.enabled)
                    if (event == Lifecycle.Event.ON_STOP) coach.observe(false)
                }
            lifecycle.addObserver(observer)
            coach.observe(
                state.initialized && state.enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
            onDispose {
                lifecycle.removeObserver(observer)
                coach.observe(false)
            }
        }
        val refresh = {
            model.refresh()
            coach.refresh()
        }
        CareerContent(
            state,
            model::enable,
            refresh,
            model::send,
            model::retry,
            model::discardPending,
            coachState,
            CareerCoachActions(
                draft = coach::changeDraft,
                send = { if (!readOnly) coach.send(it) },
                clearTarget = { coach.target(null) },
                consult = coach::target,
                control = coach::control,
                refresh = refresh,
                older = coach::older,
                retry = coach::retry,
                discard = coach::discardPending,
                selectExecution = coach::selectExecution,
                refreshCapabilities = coach::refreshCapabilities,
                saveDailyExecution = coach::saveDailyExecution,
                revise = coach::revise,
                revisionDraft = coach::changeRevision,
                research = coach::research,
            ),
            loadDocument = remember(scope) { DashboardCareerRepository(scope) }::document,
            initialTab = if (settingsOnly) "設定" else "ホーム",
            settingsOnly = settingsOnly,
            initialEntityId = initialEntityId,
            screenTitle = title,
            newDraft = newConversation != null && !readOnly,
            onRetryInitialization = model::retryInitialization,
        )
    }
}

@Composable
internal fun CareerContent(
    state: CareerUiState,
    onEnable: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSend: (String, JsonObject) -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    coachState: CareerCoachUiState =
        CareerCoachUiState(initialized = true, snapshot = CareerCoachSnapshot(), stale = false),
    coachActions: CareerCoachActions = CareerCoachActions(),
    loadDocument: suspend (String, Int) -> JsonObject = { _, _ -> error("本文を取得できません。接続を確認してください。") },
    initialTab: String = "ホーム",
    settingsOnly: Boolean = false,
    screenTitle: String = "就活",
    initialEntityId: String? = null,
    newDraft: Boolean = false,
    onRetryInitialization: () -> Unit = onRefresh,
) {
    var selected by remember { mutableStateOf<CareerEntity?>(null) }
    var artifact by remember { mutableStateOf<CareerArtifact?>(null) }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(initialEntityId, state.snapshot) {
        if (initialEntityId != null) selected = state.snapshot?.entities?.firstOrNull { it.id == initialEntityId }
    }
    val canWrite = state.enabled && !state.busy && state.pending == null && !state.stale
    HermesScaffold(
        title = {
            Text(if (settingsOnly) "就活の設定" else screenTitle)
        },
        navigationIcon =
            NavIcon.Back {
                com.m57.hermescontrol.NavigationController
                    .goBack()
            },
        drawerGesturesEnabled = false,
    ) {
        when {
            state.initializationError != null -> {
                ErrorState(state.initializationError, onRetry = onRetryInitialization)
            }

            !state.initialized -> {
                LoadingState()
            }

            !state.enabled -> {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(LocalSpacing.current.lg),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
                ) {
                    Text("企業・会話は接続先サーバーへ保存します。本人情報・経験・ESは既存のprivate GitHub同期を使います。下書きと未完了操作は端末で暗号化して保持します。")
                    Button(
                        onClick = { onEnable(true) },
                        modifier = Modifier.testTag("career_enable"),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("有効にして開く") }
                }
            }

            settingsOnly -> {
                CareerSettingsContent(state, coachState, coachActions, onRefresh, onRetry, {
                    discard =
                        true
                }, onEnable, onSend) { selected = it }
            }

            else -> {
                val emptyDraft = newDraft && coachState.snapshot?.messages.isNullOrEmpty()
                // One call site so scroll/dialog state survives the first message; only the texts and
                // the one-time input focus depend on the draft being empty.
                CareerCoachPanel(
                    coachState,
                    state.snapshot,
                    coachActions,
                    onArtifact = { selected = it },
                    onSavedArtifact = { artifact = it },
                    emptyTitle = if (newDraft) "相談したいことを入力" else "就活コーチに話しかける",
                    emptySubtitle =
                        if (newDraft) {
                            "最初の送信で会話が始まり、内容から名前が付きます。送るまでは何も作成されません。"
                        } else {
                            "相談したいことや、仕上げたい文章をそのまま伝えてください。"
                        },
                    inputPlaceholder = if (newDraft) "相談したいことを入力" else "就活コーチに相談・依頼する",
                    focusInput = emptyDraft,
                )
            }
        }
    }
    selected?.let { entity ->
        val consult = {
            com.m57.hermescontrol.NavigationController.navigateTo(
                CareerThreadsKey(
                    entity.data.text("company_id"),
                    entity.data.text("offer_id"),
                    target = CareerTarget(entity.kind, entity.id, entity.data.text("version")?.toIntOrNull()),
                ),
            )
            selected = null
        }
        if (entity.kind == "documents" && "document_revision" !in coachState.capabilities?.features.orEmpty()) {
            CareerArtifactDialog(entity, onDismiss = { selected = null }, onConsult = {
                coachActions.consult(entity)
                selected = null
            })
        } else if (entity.kind == "documents") {
            CareerDocumentReadDialog(
                entity,
                "global",
                onDismiss = { selected = null },
                onConsultOverride = { consult() },
            )
        } else {
            CareerEntityDialog(entity, state.snapshot, canWrite, { selected = null }, onSend, consult)
        }
    }
    artifact?.let {
        CareerSavedArtifactDialog(
            it,
            coachState,
            coachActions,
            loadDocument,
            onDismiss = { artifact = null },
        )
    }
    if (discard) {
        AlertDialog(onDismissRequest = {
            discard = false
        }, title = {
            Text(
                "再試行を取りやめますか",
            )
        }, text = { Text("サーバーに保存済みの変更は取り消されません。保存情報を更新して確認してください。") }, confirmButton = {
            TextButton(onClick = {
                onDiscard()
                discard =
                    false
            }, shapes = ButtonDefaults.shapes()) { Text("再試行を取りやめる") }
        }, dismissButton = {
            TextButton(onClick = { discard = false }, shapes = ButtonDefaults.shapes()) { Text("戻る") }
        })
    }
}

internal fun newCareerEntity(
    kind: String,
    snapshot: CareerSnapshot,
): CareerEntity {
    val prefix =
        mapOf(
            "companies" to "COMP",
            "facts" to "FACT",
            "stories" to "ST",
            "hypotheses" to "HYP",
            "documents" to "DOC",
        ).getValue(kind)
    val next =
        snapshot.entities
            .filter { it.kind == kind }
            .mapNotNull {
                it.id
                    .substringAfter(
                        '-',
                    ).toIntOrNull()
            }.maxOrNull()
            ?.plus(1)
            ?: 1
    val id = "$prefix-${next.toString().padStart(3, '0')}"
    val data =
        buildJsonObject {
            put("id", id)
            put("title", "")
            when (kind) {
                "facts" -> {
                    put("statement", "")
                    put("status", "UNKNOWN")
                }

                "stories" -> {
                    put("fact_ids", JsonArray(emptyList()))
                    put("open_questions", JsonArray(emptyList()))
                }

                "hypotheses" -> {
                    put("statement", "")
                    put("status", "active")
                }

                "documents" -> {
                    put("status", "drafting")
                    put("body", "")
                    put("version", 1)
                }
            }
        }
    return CareerEntity(kind, id, 0, data)
}

internal fun careerKind(kind: String): String =
    mapOf(
        "companies" to "企業",
        "offers" to "募集",
        "applications" to "応募",
        "tasks" to "作業",
        "deadlines" to "締切",
        "events" to "予定",
        "profile" to "プロフィール",
        "facts" to "本人事実",
        "stories" to "経験",
        "hypotheses" to "未確認事項",
        "documents" to "ES",
        "proposals" to "保存案",
    )[kind] ?: kind

internal fun careerStatus(status: String?): String =
    mapOf(
        "synced" to "同期済み",
        "unsynced" to "未同期",
        "not_configured" to "未設定",
        "conflict" to "競合あり",
        "not_started" to "未実行",
        "completed" to "完了",
        "completed_with_failures" to "一部の企業で調査失敗",
        "paused" to "中断中",
        "failed" to "失敗",
        "running" to "実行中",
        "planned" to "応募予定",
        "preparing" to "準備中",
        "in_process" to "選考中",
        "offered" to "内定",
        "rejected" to "不合格",
        "withdrawn" to "辞退",
        "blocked" to "保留",
        "imported" to "要確認",
        "todo" to "未着手",
        "doing" to "進行中",
        "done" to "完了",
        "submitted" to "提出済み",
        "drafting" to "下書き",
        "ready" to "提出準備済み",
        "review" to "要確認",
        "apply" to "応募する",
        "dismiss" to "見送り",
        "imported" to "要確認",
        "unknown" to "要確認",
        "UNKNOWN" to "未確認",
        "CONFIRMED" to "本人確認済み",
        "INFERRED" to "推測",
        "SUPERSEDED" to "訂正前",
        "needs_review" to "再確認が必要",
        "unconfirmed" to "未確認",
        "pending" to "確認待ち",
        "confirmed" to "本人確認済み",
        "inferred" to "推測",
        "superseded" to "訂正前",
    )[status] ?: status ?: "未確認"

private fun careerNotice(type: String?): String =
    mapOf(
        "new_offer" to "新着募集",
        "offer_changed" to "募集変更",
        "research_failed" to "調査を確認してください",
        "import_complete" to "保存結果",
        "import_conflict" to "保存内容の競合",
    )[type] ?: "お知らせ"

private fun careerResearchReason(reason: String): String =
    mapOf(
        "quota_paused" to "Codexの利用上限で中断しました。次回ここから再開します。",
        "official_url_missing" to "企業の公式採用URLを登録してください。",
        "page_unavailable" to "公式ページを取得できませんでした。既存情報は保持しています。",
        "oauth_unavailable" to "サーバーのCodexログインを確認してください。",
        "model_not_configured" to "サーバーで調査用モデルの設定が必要です。",
        "source_changed" to "元データに変更があります。現在の内容を確認してください。",
        "source_deleted" to "元データの削除記録です。現在の内容は保持しています。",
    )[reason] ?: "保存状況を確認してください（$reason）。"
