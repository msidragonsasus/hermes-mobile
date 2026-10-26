package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

interface CareerEsRepository {
    suspend fun company(id: String): CareerCompanyDetail

    suspend fun document(
        id: String,
        version: Int,
    ): JsonObject

    suspend fun save(payload: JsonObject): JsonObject
}

class DashboardCareerEsRepository(
    scope: String,
) : CareerEsRepository {
    private val companies = CareerCompanyRepository(scope)
    private val documents = DashboardCareerRepository(scope)

    override suspend fun company(id: String) = companies.company(id)

    override suspend fun document(
        id: String,
        version: Int,
    ) = documents.document(id, version)

    override suspend fun save(payload: JsonObject) = documents.mutate("answer", payload)
}

class CareerEsViewModel(
    private val repository: CareerEsRepository,
    private val local: CareerLocalStore,
    private val companyId: String,
    val offerId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareerEsState())
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val persistence = Mutex()
    private var localReady = false

    init {
        viewModelScope.launch {
            try {
                val cache = local.esCache(offerId)?.let { json.decodeFromString<CareerEsCache>(it) } ?: CareerEsCache()
                val detail = local.companyCache(companyId)?.let { json.decodeFromString<CareerCompanyDetail>(it) }
                localReady = true
                mutableState.update { it.copy(initialized = true, cache = cache, detail = detail, localSaved = true) }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(initialized = true, error = "端末の編集内容を読めません。保存情報は保持しています。") }
            }
        }
    }

    fun refresh() {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val detail = repository.company(companyId)
                local.saveCompanyCache(companyId, json.encodeToString(detail))
                mutableState.update { it.copy(detail = detail, stale = false, error = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(stale = true, error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun open(questionId: String) {
        if (!localReady || state.value.busy || state.value.editingLoading) return
        val question = question(questionId) ?: return
        mutableState.update {
            it.copy(
                selected = questionId,
                cache = it.cache.copy(lastQuestion = questionId),
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                persist()
                if (state.value.cache.drafts
                        .containsKey(questionId)
                ) {
                    return@launch
                }
                mutableState.update { it.copy(editingLoading = true) }
                val work = state.value.detail?.answerWork(offerId, questionId)
                val adopted = work?.get("adopted") as? JsonObject
                val entity =
                    documents(questionId).firstOrNull { it.id == adopted?.text("id") }
                        ?: documents(questionId).firstOrNull()
                val version =
                    adopted?.text("version")?.toIntOrNull() ?: entity?.data?.text("version")?.toIntOrNull() ?: 0
                val body =
                    if (entity ==
                        null
                    ) {
                        ""
                    } else {
                        repository.document(entity.id, version).text("body") ?: error("本文がありません")
                    }
                setDraft(
                    questionId,
                    CareerAnswerDraft(
                        body,
                        body,
                        entity?.id,
                        version,
                        entity?.version ?: 0,
                        question.text("version").orEmpty(),
                        question.text("text").orEmpty(),
                    ),
                )
                persist()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = careerError(error)) }
            } finally {
                mutableState.update { it.copy(editingLoading = false) }
            }
        }
    }

    fun changeBody(body: String) {
        val id = state.value.selected ?: return
        val draft = state.value.cache.drafts[id] ?: return
        if (state.value.busy || state.value.cache.pending != null) return
        setDraft(id, draft.copy(body = body))
        viewModelScope.launch { persistSafely() }
    }

    private fun setDraft(
        id: String,
        value: CareerAnswerDraft,
    ) {
        mutableState.update {
            it.copy(
                cache = it.cache.copy(drafts = it.cache.drafts + (id to value)),
                localSaved = false,
            )
        }
    }

    fun prepareConsultation(onDone: () -> Unit) {
        viewModelScope.launch { if (persistSafely()) onDone() }
    }

    fun leave(onDone: () -> Unit) {
        viewModelScope.launch {
            if (persistSafely()) {
                mutableState.update { it.copy(selected = null) }
                onDone()
            }
        }
    }

    fun save(
        intent: String = "save",
        document: CareerEntity? = null,
        version: Int? = null,
    ) {
        val id = state.value.selected ?: return
        question(id) ?: return
        val draft = state.value.cache.drafts[id] ?: return
        if (state.value.busy || state.value.stale || state.value.cache.pending != null ||
            state.value.detail?.es_workspace != true
        ) {
            return
        }
        val work = state.value.detail?.answerWork(offerId, id)
        val payload =
            buildJsonObject {
                put("operation_id", UUID.randomUUID().toString())
                put("offer_id", offerId)
                put("question_id", id)
                put("question_version", draft.questionVersion)
                put("expected_work_version", work?.text("revision")?.toIntOrNull() ?: 0)
                put("expected_version", draft.entityVersion)
                put("intent", intent)
                put("body", draft.body)
                (document?.id ?: draft.documentId)?.let { put("document_id", it) }
                put("document_version", version ?: draft.documentVersion)
            }
        execute(CareerPendingOperation("answer", payload))
    }

    fun retry() {
        state.value.cache.pending
            ?.let(::execute)
    }

    fun discardPending() {
        if (state.value.busy) return
        mutableState.update { it.copy(cache = it.cache.copy(pending = null)) }
        viewModelScope.launch { persistSafely() }
    }

    // Explicit comparison acknowledgement; never replace the user's current text.
    fun rebase() {
        val id = state.value.selected ?: return
        val draft = state.value.cache.drafts[id] ?: return
        val question = question(id) ?: return
        if (state.value.busy || state.value.cache.pending != null || state.value.stale) return
        val entity = documents(id).firstOrNull { it.id == draft.documentId }
        setDraft(
            id,
            draft.copy(
                entityVersion = entity?.version ?: draft.entityVersion,
                questionVersion = question.text("version").orEmpty(),
                questionText = question.text("text").orEmpty(),
            ),
        )
        viewModelScope.launch { persistSafely() }
    }

    private fun execute(operation: CareerPendingOperation) {
        if (state.value.busy) return
        mutableState.update {
            it.copy(
                busy = true,
                cache = it.cache.copy(pending = operation),
                error = null,
                message = null,
            )
        }
        viewModelScope.launch {
            try {
                persist()
                val result = repository.save(operation.payload)
                val id = operation.payload.text("question_id") ?: error("設問がありません")
                val work = result["work"] as? JsonObject ?: error("保存結果がありません")
                val adopted = work["adopted"] as? JsonObject ?: error("採用版がありません")
                val document = result["document"] as? JsonObject ?: error("文書がありません")
                val version = adopted.text("version")?.toIntOrNull() ?: error("版がありません")
                val draft = state.value.cache.drafts[id] ?: error("入力がありません")
                val body =
                    if (operation.payload.text("intent") == "save") {
                        operation.payload.text("body").orEmpty()
                    } else {
                        repository.document(adopted.text("id").orEmpty(), version).text("body") ?: error("本文がありません")
                    }
                setDraft(
                    id,
                    draft.copy(
                        body = body,
                        baseBody = body,
                        documentId = adopted.text("id"),
                        documentVersion = version,
                        entityVersion = document.text("version")?.toIntOrNull() ?: error("版がありません"),
                    ),
                )
                mutableState.update {
                    it.copy(
                        cache = it.cache.copy(pending = null),
                        message = "サーバーに保存しました。第${version}版",
                    )
                }
                persist()
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(cache = it.cache.copy(pending = operation), message = null, error = careerError(error))
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun persist() =
        persistence.withLock {
            check(localReady)
            val cache = state.value.cache
            local.saveEsCache(offerId, json.encodeToString(cache))
            mutableState.update { it.copy(localSaved = it.cache == cache) }
        }

    private suspend fun persistSafely(): Boolean =
        try {
            persist()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(localSaved = false, error = "端末に保存できません。画面を閉じず、本文をコピーしてください。") }
            false
        }

    fun question(id: String): JsonObject? =
        state.value.detail?.offers?.firstOrNull { it.id == offerId }?.questions()?.firstOrNull {
            it.text("id") ==
                id
        }

    fun documents(id: String): List<CareerEntity> =
        state.value.detail?.documents.orEmpty().filter {
            it.answersQuestion(offerId, id)
        }
}
