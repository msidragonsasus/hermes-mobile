package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/** The phone observes work; leaving the screen never cancels a server run. */
class CareerCoachViewModel(
    private val repository: CareerCoachRepository,
    private val local: CareerLocalStore,
    private val onArtifactsChanged: () -> Unit = {},
    private val defaults: CareerLocalStore? = null,
    private val storageKey: String? = null,
) : ViewModel() {
    constructor(
        repository: CareerCoachRepository,
        local: CareerLocalStore,
        onArtifactsChanged: () -> Unit,
    ) : this(repository, local, onArtifactsChanged, null)

    private val mutableState = MutableStateFlow(CareerCoachUiState())
    val state = mutableState.asStateFlow()
    private val persistence = Mutex()
    private var polling: Job? = null
    private var draftSave: Job? = null
    private var localReady = false
    private var lastStoreKey: String? = null
    private var lastStoreGeneration = 0L
    private val initialStoreGeneration = CareerCoachStorage.generation(storageKey)
    private val storageWriter = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            try {
                val store = coachStore()
                val cached =
                    CareerCoachStorage.locked(store.key) {
                        check(
                            store.key != storageKey ||
                                CareerCoachStorage.generation(store.key) == initialStoreGeneration,
                        ) {
                            "別の画面で下書きを引き継ぎました。相談一覧から開き直してください。"
                        }
                        store.local.coach().also {
                            lastStoreKey = store.key
                            lastStoreGeneration = CareerCoachStorage.generation(store.key)
                        }
                    }
                localReady = true
                mutableState.value =
                    CareerCoachUiState(
                        initialized = true,
                        snapshot = cached?.snapshot,
                        draft = cached?.draft.orEmpty(),
                        target = cached?.target,
                        pending = cached?.pending,
                        execution = cached?.execution ?: defaults?.coach()?.execution,
                        capabilities = cached?.capabilities,
                        dailySettings = cached?.capabilities?.daily_settings,
                        revisionDrafts = cached?.revisionDrafts.orEmpty(),
                        answerContext = cached?.answerContext,
                        historyExpanded =
                            cached?.historyExpanded == true || cached
                                ?.snapshot
                                ?.messages
                                .orEmpty()
                                .size > 80,
                    )
                recordLocalEdits()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        initialized = true,
                        localWritable = false,
                        error = "端末内の会話を読めません。保存データは保持しています。",
                    )
                }
            }
            refreshCapabilities()
        }
    }

    fun refreshCapabilities() {
        viewModelScope.launch { loadCapabilities(refresh = true) }
    }

    private suspend fun loadCapabilities(refresh: Boolean = false) {
        try {
            val capabilities = repository.capabilities(refresh)
            mutableState.update {
                it.copy(
                    capabilities = capabilities,
                    capabilitiesChecked = true,
                    capabilitiesError =
                        if ("execution_config" in
                            capabilities.features
                        ) {
                            careerPreparationReason(capabilities.reason)
                        } else {
                            "就活サーバーを更新してください。モデル選択に対応していません。"
                        },
                    execution = it.execution ?: capabilities.default_execution,
                    dailySettings = capabilities.daily_settings,
                )
            }
            persistSafely()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update {
                it.copy(
                    capabilitiesChecked = false,
                    capabilitiesError =
                        careerCapabilityError(
                            error,
                            it.snapshot != null,
                        ),
                )
            }
        }
    }

    fun selectExecution(execution: CareerExecution) {
        if (!supported(execution)) return
        mutableState.update { it.copy(execution = execution) }
        viewModelScope.launch { persistSafely() }
    }

    private fun supported(execution: CareerExecution): Boolean {
        val current = state.value
        if (!current.capabilitiesChecked || current.capabilities?.supports(execution) != true ||
            current.capabilities.state != "available"
        ) {
            mutableState.update { it.copy(error = "モデル候補を再確認し、対応するモデルと推論強度を選択してください。") }
            return false
        }
        return true
    }

    fun saveDailyExecution(execution: CareerExecution) {
        if (state.value.busy || state.value.pending != null || !supported(execution)) return
        if ("daily_settings" !in
            state.value.capabilities
                ?.features
                .orEmpty()
        ) {
            return
        }
        val settings = state.value.dailySettings ?: return
        execute(
            CareerPendingOperation(
                "daily-settings",
                buildJsonObject {
                    put("operation_id", UUID.randomUUID().toString())
                    put("expected_version", settings.version)
                    put("execution", Json.encodeToJsonElement(execution))
                },
            ),
        )
    }

    fun observe(enabled: Boolean) {
        polling?.cancel()
        polling = null
        if (!enabled) return
        polling =
            viewModelScope.launch {
                while (!state.value.initialized) delay(50)
                refresh()
                while (state.value.loading) delay(50)
                while (true) {
                    delay(if (state.value.stale) 10000 else 2500)
                    if (!state.value.loading) poll()
                }
            }
    }

    fun changeDraft(value: String) {
        if (state.value.busy || state.value.pending != null) return
        if (!writerCurrent()) return
        mutableState.update { it.copy(draft = value.take(20000)) }
        recordLocalEdits()
        draftSave?.cancel()
        draftSave =
            viewModelScope.launch {
                delay(250)
                persistSafely()
            }
    }

    fun prepare(
        prompt: String,
        target: CareerTarget?,
    ) {
        if (state.value.busy || state.value.pending != null) return
        if (state.value.draft.isNotBlank()) {
            if (!target?.source_refs.isNullOrEmpty()) {
                mutableState.update {
                    val previous = it.target ?: target!!
                    it.copy(
                        target = previous.copy(source_refs = (previous.source_refs + target!!.source_refs).distinct()),
                    )
                }
                viewModelScope.launch { persistSafely() }
            }
            return
        }
        mutableState.update {
            it.copy(
                target = target,
                answerContext =
                    it.answerContext.takeIf { _ ->
                        it.target ==
                            target
                    },
            )
        }
        changeDraft(prompt)
    }

    fun prepareAnswerContext(context: JsonObject) {
        if (state.value.busy || state.value.pending != null) return
        if (state.value.draft.isNotBlank() && state.value.answerContext != null &&
            state.value.answerContext != context
        ) {
            mutableState.update { it.copy(error = "別の設問の下書きがあります。新しい相談を作成してください。") }
            return
        }
        mutableState.update { it.copy(answerContext = context) }
        recordLocalEdits()
        viewModelScope.launch { persistSafely() }
    }

    fun prepareAnswerConversation(
        prompt: String,
        target: CareerTarget?,
        context: JsonObject,
    ) {
        if (state.value.busy || state.value.pending != null) return
        if (state.value.draft.isNotBlank() && state.value.answerContext != context) {
            handoffFailed("入力中の相談があるため設問を変更していません。新しい相談を作成してください。")
            return
        }
        if (state.value.draft.isBlank()) {
            mutableState.update { it.copy(draft = prompt.take(20000), target = target, answerContext = context) }
            recordLocalEdits()
            viewModelScope.launch { persistSafely() }
        } else {
            prepare(prompt, target)
            prepareAnswerContext(context)
        }
    }

    fun handoffFailed(message: String) {
        mutableState.update { it.copy(error = message) }
    }

    fun target(entity: CareerEntity?) {
        if (state.value.busy || state.value.pending != null) return
        mutableState.update {
            it.copy(
                target =
                    entity?.let { value ->
                        CareerTarget(value.kind, value.id)
                    },
                answerContext = null,
            )
        }
        recordLocalEdits()
        viewModelScope.launch { persistSafely() }
    }

    fun revise(
        artifact: CareerArtifact,
        text: String,
    ) {
        if (state.value.busy || state.value.pending != null || text.isBlank()) return
        if ("document_revision" !in
            state.value.capabilities
                ?.features
                .orEmpty()
        ) {
            mutableState.update { it.copy(error = "文書修正に対応したサーバーへ更新してください。") }
            return
        }
        send(text, CareerTarget("documents", artifact.id, artifact.version))
    }

    fun changeRevision(
        artifact: CareerArtifact,
        text: String,
    ) {
        if (state.value.busy || state.value.pending != null) return
        if (!writerCurrent()) return
        mutableState.update {
            it.copy(
                revisionDrafts =
                    it.revisionDrafts + ("${artifact.id}:${artifact.version}" to text.take(20000)),
            )
        }
        recordLocalEdits()
        draftSave?.cancel()
        draftSave =
            viewModelScope.launch {
                delay(250)
                persistSafely()
            }
    }

    fun refresh() {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val snapshot = repository.conversation()
                mutableState.update {
                    it.copy(
                        snapshot = mergeCareerConversation(it.snapshot, snapshot, it.historyExpanded),
                        stale = false,
                        error = if (it.pending == null) null else it.error,
                    )
                }
                loadCapabilities()
                persist()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(stale = true, error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun older() {
        val before = state.value.snapshot?.older_before ?: return
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, loadingOlder = true) }
        viewModelScope.launch {
            try {
                val earlier = repository.conversation(before)
                mutableState.update { current ->
                    val latest = current.snapshot ?: earlier
                    check(latest.thread_id == earlier.thread_id && latest.profile == earlier.profile) {
                        "別の相談の履歴は読み込みません。相談を開き直してください。"
                    }
                    current.copy(
                        snapshot =
                            latest.copy(
                                messages =
                                    (earlier.messages + latest.messages)
                                        .associateBy {
                                            it.id
                                        }.values
                                        .sortedBy { it.seq },
                                older_before = earlier.older_before,
                            ),
                        historyExpanded = true,
                    )
                }
                persist()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(loading = false, loadingOlder = false) }
            }
        }
    }

    private suspend fun poll() {
        try {
            val previous = state.value.snapshot
            val events = repository.events(previous?.cursor ?: 0)
            if (previous == null || state.value.stale || events.events.isNotEmpty()) {
                val latest = repository.conversation()
                val progress = events.events.filter { it.type in setOf("working", "skill", "source", "saved") }
                mutableState.update {
                    it.copy(
                        snapshot = mergeCareerConversation(it.snapshot, latest, it.historyExpanded),
                        stale = false,
                        error = if (it.pending == null) null else it.error,
                        progress =
                            it.progress +
                                progress.associate { event ->
                                    event.run_id to
                                        when (event.type) {
                                            "skill" -> "相談に合う手順を確認"
                                            "source" -> "公式情報を確認"
                                            "saved" -> "成果を保存"
                                            else -> "保存情報を使って作業"
                                        }
                                },
                    )
                }
                persist()
                if (latest.cursor != previous?.cursor) onArtifactsChanged()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(stale = true, error = careerError(error)) }
        }
    }

    fun research() = send("登録企業の公式採用情報を調べ、変更点と次の行動を整理してください。", target = null, action = "research")

    fun send(
        text: String = state.value.draft,
        target: CareerTarget? = state.value.target,
        action: String = "message",
        answerContext: JsonObject? = state.value.answerContext,
    ) {
        if (text.isBlank()) return
        val current = state.value
        if (!careerDraftEditable(current)) return
        val preparation = careerSendPreparation(current)
        if (preparation != null) {
            mutableState.update { it.copy(error = preparation) }
            return
        }
        val execution = checkNotNull(current.execution)
        val payload =
            buildJsonObject {
                put("operation_id", UUID.randomUUID().toString())
                put("text", text)
                put("execution", Json.encodeToJsonElement(execution))
                answerContext?.let { put("answer_context", it) }
                target?.let {
                    put(
                        "target",
                        buildJsonObject {
                            put("kind", it.kind)
                            put("id", it.id)
                            it.version?.let { version -> put("version", version) }
                            if (it.source_refs.isNotEmpty()) {
                                put(
                                    "source_refs",
                                    Json.encodeToJsonElement(it.source_refs),
                                )
                            }
                        },
                    )
                }
            }
        execute(CareerPendingOperation(action, payload))
    }

    fun control(
        run: CareerCoachRun,
        control: String,
    ) {
        if (state.value.busy || state.value.pending != null || state.value.readOnly ||
            state.value.snapshot?.archived == true
        ) {
            return
        }
        val confirmation = if (control == "resume" && run.execution == null) state.value.execution else null
        if (control == "resume" && run.execution == null && (confirmation == null || !supported(confirmation))) {
            mutableState.update { it.copy(error = "以前の作業です。モデルと推論強度を選択してから続行してください。") }
            return
        }
        execute(
            CareerPendingOperation(
                "control",
                buildJsonObject {
                    put("id", run.id)
                    put("control", control)
                    put("thread_id", state.value.snapshot?.thread_id ?: "global")
                    put("operation_id", UUID.randomUUID().toString())
                    confirmation?.let { put("execution", Json.encodeToJsonElement(it)) }
                },
            ),
        )
    }

    fun retry() {
        if (!state.value.busy) state.value.pending?.let(::execute)
    }

    fun discardPending() {
        if (state.value.busy) return
        viewModelScope.launch {
            val old = state.value.pending
            try {
                mutableState.update { it.copy(pending = null, error = null) }
                persist()
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(pending = old, error = careerError(error)) }
            }
        }
    }

    private fun execute(operation: CareerPendingOperation) {
        if (state.value.busy) return
        if (!writerCurrent()) return
        mutableState.update { it.copy(busy = true, pending = operation, error = null) }
        recordLocalEdits()
        viewModelScope.launch {
            try {
                persist()
                val needsExecution = operation.payload["execution"] != null
                if (needsExecution &&
                    (
                        !state.value.capabilitiesChecked ||
                            "execution_config" !in
                            state.value.capabilities
                                ?.features
                                .orEmpty()
                    )
                ) {
                    error("モデル指定に対応するサーバーか確認できません。更新・再接続してから同じ操作を再送してください。")
                }
                val result = repository.mutate(operation.action, operation.payload)
                if (operation.action in setOf("message", "research") && needsExecution &&
                    result["execution"] != operation.payload["execution"]
                ) {
                    error("受理されたモデル設定を照合できません。サーバーを確認してください。依頼は保持しています。")
                }
                val revision = (operation.payload["target"] as? JsonObject)?.takeIf { it["version"] != null }
                val clearsDraft = operation.action == "message" && revision == null
                mutableState.update {
                    val revisionKey = revision?.let { target -> "${target.text("id")}:${target.text("version")}" }
                    it.copy(
                        pending = null,
                        draft = if (clearsDraft && it.draft == operation.payload.text("text")) "" else it.draft,
                        // A selected company/material remains the context for follow-up turns.
                        // Version-pinned revision targets and frozen answer context are not rewritten.
                        target = it.target,
                        revisionDrafts =
                            if (revisionKey != null &&
                                it.revisionDrafts[revisionKey] == operation.payload.text("text")
                            ) {
                                it.revisionDrafts -
                                    revisionKey
                            } else {
                                it.revisionDrafts
                            },
                    )
                }
                persist()
                if (operation.action in setOf("message", "research")) {
                    (repository as? CareerCoachLocalRepository)?.acknowledged()
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Preserve the exact operation even when the response or local acknowledgement is lost.
                mutableState.update { it.copy(pending = operation, error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun persist() =
        persistence.withLock {
            check(localReady) { "端末内の保存データを読めません。既存データを保持するため上書きを止めています。" }
            val current = state.value
            val store = coachStore()
            CareerCoachStorage.locked(store.key) {
                check(
                    if (store.key == lastStoreKey) {
                        CareerCoachStorage.generation(store.key) == lastStoreGeneration
                    } else {
                        !CareerCoachStorage.hasEdits(store.key)
                    },
                ) {
                    "別の画面で相談の下書きを引き継ぎました。相談一覧から開き直してください。入力は保持しています。"
                }
                store.local.saveCoach(
                    CareerCoachCache(
                        current.snapshot?.takeUnless { it.thread_id.startsWith("draft:") }
                            ?: store.local.coach()?.snapshot,
                        current.draft,
                        current.target,
                        current.pending,
                        current.execution,
                        current.capabilities,
                        current.revisionDrafts,
                        current.answerContext,
                        historyExpanded = current.historyExpanded,
                    ),
                )
                if (store.key != lastStoreKey) CareerCoachStorage.editing(lastStoreKey, storageWriter, false)
                lastStoreKey = store.key
                lastStoreGeneration = CareerCoachStorage.generation(store.key)
                recordLocalEdits()
            }
        }

    private suspend fun coachStore(): CareerCoachStore =
        (repository as? CareerCoachLocalRepository)?.localStore() ?: CareerCoachStore(local, storageKey)

    private fun recordLocalEdits() {
        val current = state.value
        CareerCoachStorage.editing(
            lastStoreKey,
            storageWriter,
            current.draft.isNotBlank() || current.pending != null || current.revisionDrafts.isNotEmpty() ||
                current.target != null || current.answerContext != null,
        )
    }

    private fun writerCurrent(): Boolean {
        if (CareerCoachStorage.generation(lastStoreKey) == lastStoreGeneration) return true
        mutableState.update { it.copy(error = "相談の下書きを引き継ぎました。相談一覧から開き直してください。") }
        return false
    }

    override fun onCleared() {
        // Back may clear the VM before the 250 ms draft debounce finishes. Flush locally;
        // never retry a server operation here, and retain the writer marker until saved.
        viewModelScope.launch(NonCancellable) {
            try {
                if (localReady) persistSafely()
            } finally {
                CareerCoachStorage.editing(lastStoreKey, storageWriter, false)
            }
        }
        super.onCleared()
    }

    private suspend fun persistSafely() {
        try {
            persist()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(error = "入力を端末内に保存できません。画面を閉じずに内容を確認してください。") }
        }
    }
}
