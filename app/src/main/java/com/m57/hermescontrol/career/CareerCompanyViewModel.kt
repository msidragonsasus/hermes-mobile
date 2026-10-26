package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException

data class CareerCompanyState(
    val initialized: Boolean = false,
    val initializationError: String? = null,
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val stale: Boolean = true,
    val companies: CareerCompanies? = null,
    val detail: CareerCompanyDetail? = null,
    val connections: CareerConnections? = null,
    val error: String? = null,
    val message: String? = null,
    val notifications: Boolean = false,
    val esDraftOffers: Set<String> = emptySet(),
    val esStateKnown: Boolean = false,
    val connectionError: String? = null,
    val loginAccounts: List<ShukatsuLoginAccount> = emptyList(),
    val collecting: String? = null,
    val preferenceWrite: CompanyWriteState = CompanyWriteState(),
    val senderWrite: CompanyWriteState = CompanyWriteState(),
    val companyWrite: CompanyWriteState = CompanyWriteState(),
    val companyDeleted: Boolean = false,
    val orderWrite: CompanyWriteState = CompanyWriteState(),
)

class CareerCompanyViewModel(
    private val repository: CareerCompanyRepository,
    private val local: CareerLocalStore,
    private val companyId: String?,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(CareerCompanyState())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null
    private var initialization: Job? = null
    private var resumeAnalysisRequest: Pair<String, String>? = null
    private var pendingOrder: CompanyOrderRequest? = null

    init {
        retryInitialization()
    }

    fun retryInitialization() {
        if (state.value.initialized || initialization?.isActive == true) return
        mutableState.update { it.copy(initializationError = null) }
        initialization =
            viewModelScope.launch {
                try {
                    val enabled = local.enabled()
                    mutableState.update { it.copy(enabled = enabled) }
                    val cache = local.companyCache(companyId)
                    if (companyId == null) {
                        val companies = cache?.let { json.decodeFromString<CareerCompanies>(it) }
                        mutableState.update { it.copy(companies = companies) }
                    } else {
                        val detail = cache?.let { json.decodeFromString<CareerCompanyDetail>(it) }
                        mutableState.update { it.copy(detail = detail) }
                    }
                    val notifications = local.notificationsEnabled()
                    mutableState.update { it.copy(notifications = notifications) }
                    val accounts = companyId?.let { local.companyLoginAccounts(it) }.orEmpty()
                    mutableState.update { it.copy(initialized = true, loginAccounts = accounts) }
                    if (enabled) refresh()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(initializationError = "端末の保存情報を読み込めません。既存データは保持しています。再試行すると端末の保存情報を読み直します。")
                    }
                }
            }
    }

    private fun localStateReady(): Boolean = state.value.initialized && state.value.initializationError == null

    fun addCompany(
        name: String,
        requestId: String,
    ) {
        if (name.isBlank() || name.trim().length > 200 || companyId != null) return
        writeCompany(
            "save",
            buildJsonObject {
                put("operation_id", requestId)
                put("kind", "companies")
                put("id", "company:$requestId")
                put("expected_version", 0)
                put("data", buildJsonObject { put("name", name.trim()) })
            },
        )
    }

    fun deleteCompany(
        expectedVersion: Int,
        requestId: String,
    ) {
        val id = companyId ?: return
        writeCompany(
            "delete-company",
            buildJsonObject {
                put("operation_id", requestId)
                put("id", id)
                put("expected_version", expectedVersion)
                put("confirmed_by_user", true)
            },
        )
    }

    private fun writeCompany(
        action: String,
        payload: JsonObject,
    ) {
        if (!localStateReady() || !state.value.enabled || state.value.loading) return
        val requestId = payload.text("operation_id").orEmpty()
        val pending = CompanyWriteState(phase = CompanyWritePhase.SAVING, requestId = requestId)
        mutableState.update { it.copy(loading = true, error = null, companyWrite = pending) }
        loading =
            viewModelScope.launch {
                val deleting = action == "delete-company"
                var acknowledged = false
                try {
                    repository.saveCompany(action, payload)
                    acknowledged = true
                    mutableState.update {
                        it.copy(
                            companyDeleted = deleting,
                            companyWrite =
                                pending.copy(
                                    phase = CompanyWritePhase.SAVED,
                                    message = if (deleting) "企業を削除しました。" else "企業を追加しました。",
                                ),
                        )
                    }
                    if (deleting) {
                        local.invalidateCompanyCache(checkNotNull(companyId))
                    } else {
                        loadDetail()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val status = (error as? HttpException)?.code()
                    val rejected = status in 400..499 && status != 408
                    mutableState.update {
                        it.copy(
                            stale = true,
                            companyWrite =
                                pending.copy(
                                    phase =
                                        when {
                                            acknowledged -> CompanyWritePhase.SAVED
                                            status == 409 -> CompanyWritePhase.CONFLICT
                                            rejected -> CompanyWritePhase.FAILED
                                            else -> CompanyWritePhase.UNKNOWN
                                        },
                                    message =
                                        when {
                                            acknowledged -> "変更は保存されましたが、一覧の更新を完了できません。再読込してください。"
                                            status == 409 -> "企業情報が更新されています。再読込してから操作してください。"
                                            status == 404 && deleting -> "削除できません。企業が削除済みか、サーバーの更新が必要です。"
                                            rejected -> careerError(error)
                                            else -> "結果を確認できません。同じ内容で再試行できます。"
                                        },
                                ),
                        )
                    }
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }
    }

    fun saveCompanyOrder(
        ids: List<String>,
        expectedVersion: Int,
        requestId: String,
    ) {
        val current = state.value
        if (!localStateReady() || companyId != null || !current.enabled || current.loading ||
            loading?.isActive == true
        ) {
            return
        }
        val request = CompanyOrderRequest(ids.toList(), expectedVersion, requestId)
        if (current.orderWrite.phase == CompanyWritePhase.UNKNOWN && request != pendingOrder) return
        if (current.orderWrite.phase != CompanyWritePhase.UNKNOWN &&
            (
                current.stale || current.companies?.order_state == "unavailable" ||
                    expectedVersion != current.companies?.order_version ||
                    ids.toSet() !=
                    current.companies
                        ?.companies
                        .orEmpty()
                        .map { it.id }
                        .toSet() ||
                    ids.size != ids.toSet().size
            )
        ) {
            return
        }
        pendingOrder = request
        val pending =
            CompanyWriteState(phase = CompanyWritePhase.SAVING, requestVersion = expectedVersion, requestId = requestId)
        mutableState.update { it.copy(loading = true, error = null, orderWrite = pending) }
        loading =
            viewModelScope.launch {
                var acknowledged = false
                var acknowledgementMessage = "会社の順序を保存しました。"
                try {
                    val result = repository.saveCompanyOrder(request.ids, request.version, request.requestId)
                    // An explicit server acknowledgement remains authoritative even if refresh/cache fails.
                    acknowledged = true
                    val snapshot = state.value.companies
                    if ((snapshot?.order_version ?: -1) > result.version) {
                        acknowledgementMessage = "この順序は保存されましたが、その後に別の順序が保存されています。"
                    }
                    val saved =
                        snapshot?.takeIf { result.version > (it.order_version ?: -1) }?.copy(
                            companies =
                                orderedCompanies(
                                    state.value.companies
                                        ?.companies
                                        .orEmpty(),
                                    result.company_ids,
                                ),
                            order_version = result.version,
                        )
                    mutableState.update {
                        it.copy(
                            companies = saved ?: it.companies,
                            orderWrite =
                                pending.copy(
                                    phase = CompanyWritePhase.SAVED,
                                    message = acknowledgementMessage,
                                ),
                        )
                    }
                    if (saved != null) {
                        try {
                            local.saveCompanyCache(null, json.encodeToString(saved))
                        } catch (cacheError: Exception) {
                            if (cacheError is CancellationException) throw cacheError
                            acknowledgementMessage += " 端末への保存は未確認です。"
                            mutableState.update {
                                it.copy(
                                    orderWrite =
                                        pending.copy(
                                            phase = CompanyWritePhase.SAVED,
                                            message = acknowledgementMessage,
                                        ),
                                )
                            }
                        }
                    }
                    loadDetail()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val status = (error as? HttpException)?.code()
                    val rejected = status in 400..499 && status != 408
                    mutableState.update {
                        it.copy(
                            stale = true,
                            orderWrite =
                                pending.copy(
                                    phase =
                                        when {
                                            acknowledged -> CompanyWritePhase.SAVED
                                            status == 409 -> CompanyWritePhase.CONFLICT
                                            rejected -> CompanyWritePhase.FAILED
                                            else -> CompanyWritePhase.UNKNOWN
                                        },
                                    message =
                                        when {
                                            acknowledged -> "$acknowledgementMessage 一覧を再読込して確認してください。"
                                            status == 409 -> "会社一覧または順序が更新されました。再読込して並べ直してください。"
                                            status == 422 -> "保存済みの順序を確認できません。サーバーの保存データを確認してください。"
                                            rejected -> careerError(error)
                                            else -> "保存結果が未確認です。同じ内容で再試行するか、再読込して確認してください。"
                                        },
                                ),
                        )
                    }
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }
    }

    fun clearCompanyOrderWrite() {
        if (state.value.loading) return
        pendingOrder = null
        mutableState.update { it.copy(orderWrite = CompanyWriteState()) }
    }

    fun enable() {
        if (!localStateReady()) return
        viewModelScope.launch {
            local.setEnabled(true)
            mutableState.update { it.copy(enabled = true) }
            refresh()
        }
    }

    fun refresh() {
        if (!localStateReady() || !state.value.enabled || loading?.isActive == true) return
        loading =
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, error = null) }
                try {
                    loadDetail()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(stale = true, error = "保存情報を再読込できません。前回の情報を表示しています。")
                    }
                }
                try {
                    val connections = repository.connections()
                    mutableState.update { it.copy(connections = connections, connectionError = null) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(connectionError = "接続情報を確認できません。取得済みデータは保持しています。") }
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }
    }

    fun refreshAfterMailSaved() {
        if (!localStateReady()) return
        viewModelScope.launch {
            loading?.join()
            try {
                loadDetail()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(stale = true, error = "保存情報を再読込できません。") }
            }
        }
    }

    fun loadMoreMail() {
        if (!localStateReady()) return
        val id = companyId ?: return
        val cursor = state.value.detail?.mail_next_cursor ?: return
        if (loading?.isActive == true) return
        loading =
            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, error = null) }
                try {
                    val page = repository.mailPage(id, cursor)
                    val detail = state.value.detail ?: return@launch
                    val combined =
                        detail.copy(
                            mail =
                                (detail.mail + page.messages).distinctBy {
                                    it.account_id to
                                        it.message_id
                                },
                            mail_next_cursor = page.next_cursor,
                        )
                    mutableState.update { it.copy(detail = combined) }
                    local.saveCompanyCache(id, json.encodeToString(combined))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(error = "続きの表示に失敗しました。表示中のメールは保持しています。") }
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }
    }

    private suspend fun loadDetail() {
        val serialized =
            if (companyId == null) {
                val result = repository.companies()
                mutableState.update { it.copy(companies = result, stale = false) }
                json.encodeToString(result)
            } else {
                val result = repository.company(companyId)
                mutableState.update { it.copy(detail = result, stale = false) }
                json.encodeToString(result)
            }
        try {
            local.saveCompanyCache(companyId, serialized)
            val detail = state.value.detail
            val accounts =
                companyId?.let { local.companyLoginAccounts(it) }.orEmpty()
            val drafts =
                detail
                    ?.offers
                    .orEmpty()
                    .filter { offer ->
                        local.esCache(offer.id)?.let { json.decodeFromString<CareerEsCache>(it).drafts.isNotEmpty() } ==
                            true
                    }.map { it.id }
                    .toSet()
            mutableState.update { it.copy(esDraftOffers = drafts, esStateKnown = true, loginAccounts = accounts) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(message = "情報は読み込みましたが、端末への保存を完了できませんでした。") }
        }
    }

    fun collectMyPage() {
        val target = companyId ?: return
        mutate("mypage-check", buildJsonObject { put("company_id", target) }, "マイページの案内を確認しました。", true)
    }

    fun resumeMyPageAnalysis() {
        val mypage = state.value.detail?.mypage ?: return
        if (!mypage.analysis_resumable ||
            mypage.status.text("state") in setOf("queued", "running", "collecting", "analyzing")
        ) {
            return
        }
        val runId = mypage.status.text("run_id")?.takeIf { it.isNotBlank() } ?: return
        val requestKey =
            resumeAnalysisRequest?.takeIf { it.first == runId }?.second
                ?: java.util.UUID
                    .randomUUID()
                    .toString()
                    .also { resumeAnalysisRequest = runId to it }
        mutate(
            "mypage-resume-analysis",
            buildJsonObject {
                put("run_id", runId)
                put("idempotency_key", requestKey)
            },
            "保存済み本文の整理を受け付けました。",
            true,
        )
    }

    fun preferences(
        priority: String,
        stage: String,
        expectedVersion: Int,
    ) {
        val company = state.value.detail?.company ?: return
        saveManagement(
            "company-preferences",
            buildJsonObject {
                put("company_id", company.id)
                put("expected_version", expectedVersion)
                put("priority", priority)
                put("stage", stage)
            },
            "志望度・選考状況を保存しました。",
        )
    }

    fun collect() =
        mutate(
            "check",
            buildJsonObject { put("channel", "mail") },
            "情報取得を開始しました。Appを閉じてもサーバーで続行します。",
        )

    fun collectMail() =
        mutate(
            "check",
            buildJsonObject { put("channel", "mail") },
            "全企業の更新を受け付けました。画面を閉じてもサーバーで続行します。",
        )

    fun setMailSender(
        address: String,
        enabled: Boolean,
    ) {
        val target = companyId ?: return
        saveManagement(
            "mail-sender",
            buildJsonObject {
                put("company_id", target)
                put("address", address.trim())
                put("enabled", enabled)
            },
            if (enabled) "表示する送信元を追加しました。" else "この送信元からの取得を停止しました。保存済みメールは残ります。",
        )
    }

    /** Keep the write acknowledgement separate from the follow-up read and local cache. */
    private fun saveManagement(
        action: String,
        payload: JsonObject,
        successMessage: String,
    ) {
        if (!localStateReady() || state.value.loading) return
        val preferences = action == "company-preferences"
        val pending =
            CompanyWriteState(
                phase = CompanyWritePhase.SAVING,
                message = "保存中…",
                requestVersion = payload.text("expected_version")?.toIntOrNull(),
                address = payload.text("address"),
                requestId =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
            )

        fun publish(write: CompanyWriteState) {
            mutableState.update { if (preferences) it.copy(preferenceWrite = write) else it.copy(senderWrite = write) }
        }
        // Set synchronously so rapid taps cannot enqueue a second mutation.
        mutableState.update { it.copy(loading = true, error = null, message = null) }
        publish(pending)
        loading =
            viewModelScope.launch {
                var acknowledged = false
                try {
                    val result = repository.mutate(action, payload)
                    acknowledged = true
                    val current = state.value.detail ?: error("Missing company detail")
                    val updated =
                        if (preferences) {
                            val company =
                                json.decodeFromJsonElement(
                                    CareerEntity.serializer(),
                                    result.getValue("company"),
                                )
                            check(company.id == current.company.id)
                            current.copy(company = company)
                        } else {
                            val entity =
                                json.decodeFromJsonElement(
                                    CareerEntity.serializer(),
                                    result.getValue("sender"),
                                )
                            check(
                                entity.kind == "company_mail_senders" &&
                                    entity.data.text("company_id") == current.company.id,
                            )
                            val sender =
                                CareerMailSenderRule(
                                    id = entity.id,
                                    version = entity.version,
                                    company_id = current.company.id,
                                    address = checkNotNull(entity.data.text("emailAddress")),
                                    enabled = entity.data.text("enabled") == "true",
                                    source = (entity.data["source"] as? JsonObject)?.text("system") ?: "Hermes",
                                    updated_at = entity.updated_at,
                                )
                            current.copy(mail_senders = current.mail_senders.filterNot { it.id == sender.id } + sender)
                        }
                    mutableState.update { it.copy(detail = updated) }
                    publish(pending.copy(phase = CompanyWritePhase.SAVED, message = successMessage))
                    try {
                        local.saveCompanyCache(companyId, json.encodeToString(updated))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        publish(
                            pending.copy(phase = CompanyWritePhase.SAVED, message = "$successMessage 端末への保存は完了していません。"),
                        )
                    }
                    try {
                        loadDetail()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(stale = true) }
                        publish(
                            pending.copy(
                                phase = CompanyWritePhase.SAVED,
                                message = "$successMessage 最新情報の読み直しはできませんでした。",
                            ),
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val conflict = !acknowledged && preferences && (error as? HttpException)?.code() == 409
                    val rejected = !acknowledged && (error as? HttpException)?.code() in 400..499
                    val phase =
                        when {
                            conflict -> CompanyWritePhase.CONFLICT
                            rejected -> CompanyWritePhase.FAILED
                            else -> CompanyWritePhase.UNKNOWN
                        }
                    val message =
                        when {
                            conflict -> {
                                "別の変更が保存されています。入力を残しています。最新の記録を確認してください。"
                            }

                            !preferences && (error as? HttpException)?.code() == 409 -> {
                                "この送信元は別の企業に登録されています。登録先を確認してください。"
                            }

                            rejected -> {
                                "保存できませんでした。入力と接続設定を確認してください。"
                            }

                            acknowledged -> {
                                "保存は受け付けられましたが、保存内容を確認できません。入力を残しています。"
                            }

                            else -> {
                                "保存結果を確認できません。入力を残しています。保存情報を読み直して確認してください。"
                            }
                        }
                    publish(pending.copy(phase = phase, message = message))
                    try {
                        loadDetail()
                    } catch (readError: Exception) {
                        if (readError is CancellationException) throw readError
                        mutableState.update { it.copy(stale = true) }
                    }
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }
    }

    fun notifications(
        enabled: Boolean,
        onSaved: () -> Unit,
    ) {
        if (!localStateReady()) return
        viewModelScope.launch {
            try {
                local.setNotificationsEnabled(enabled)
                mutableState.update { it.copy(notifications = enabled) }
                onSaved()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = "通知設定を保存できませんでした。") }
            }
        }
    }

    fun configure(
        enabled: Boolean,
        account: String?,
    ) {
        if (!localStateReady()) return
        if (state.value.connections?.independent_channels != true) {
            mutableState.update { it.copy(error = "取得元ごとの設定には就活サーバーの更新が必要です。現在の設定は保持しています。") }
            return
        }
        mutate(
            "settings",
            buildJsonObject {
                put("channel", "mail")
                put("enabled", enabled)
                if (account != null) put("account_id", account)
            },
            if (enabled) "定期取得を有効にしました。" else "定期取得を停止しました。",
        )
    }

    private fun mutate(
        action: String,
        payload: JsonObject,
        message: String,
        reloadDetail: Boolean = false,
    ) {
        if (!localStateReady() || state.value.loading || loading?.isActive == true) return
        loading =
            viewModelScope.launch {
                var acceptedMyPageRun: String? = null
                val previousMailAttempt =
                    state.value.detail
                        ?.mail_status
                        ?.text("last_attempt_at")
                mutableState.update {
                    it.copy(
                        loading = true,
                        error = null,
                        message = null,
                        collecting =
                            if (action ==
                                "check"
                            ) {
                                "mail"
                            } else if (action in setOf("mypage-check", "mypage-resume-analysis")) {
                                "mypage"
                            } else {
                                null
                            },
                    )
                }
                try {
                    val result = repository.mutate(action, payload)
                    if (action in setOf("mypage-check", "mypage-resume-analysis")) {
                        acceptedMyPageRun = result.text("run_id")?.takeIf { it.isNotBlank() }
                        acceptedMyPageRun?.let { runId ->
                            mutableState.update { current ->
                                val detail = current.detail
                                val mypage = detail?.mypage
                                if (mypage == null) {
                                    current
                                } else {
                                    current.copy(
                                        detail =
                                            detail.copy(
                                                mypage =
                                                    mypage.copy(
                                                        analysis_resumable = false,
                                                        status =
                                                            buildJsonObject {
                                                                put("run_id", runId)
                                                                put("state", "queued")
                                                                mypage.status
                                                                    .text(
                                                                        "last_success_at",
                                                                    )?.let { put("last_success_at", it) }
                                                            },
                                                    ),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                    if (action == "mypage-resume-analysis") resumeAnalysisRequest = null
                    mutableState.update { it.copy(message = message) }
                    if (reloadDetail || action == "check") loadDetail()
                    if (action in setOf("mypage-check", "mypage-resume-analysis")) {
                        val status =
                            state.value.detail
                                ?.mypage
                                ?.status
                        mutableState.update {
                            it.copy(
                                message =
                                    if (result.text("run_id").isNullOrBlank()) {
                                        "確認依頼の受付状態を確認できません。最新の取得結果を読み直してください。"
                                    } else {
                                        "サーバーで確認を受け付けました。この画面を離れても続行します。"
                                    },
                                error =
                                    status
                                        ?.takeIf { current -> current.text("run_id") == result.text("run_id") }
                                        ?.let { current -> companyCollectionReason(current.text("reason")) },
                            )
                        }
                    }
                    if (action == "check" && companyId != null) {
                        var polls = 0
                        while (polls < 10 &&
                            (
                                state.value.detail
                                    ?.mail_status
                                    ?.text("last_attempt_at") == previousMailAttempt ||
                                    state.value.detail
                                        ?.mail_status
                                        ?.text("state") == "running"
                            )
                        ) {
                            delay(2000)
                            loadDetail()
                            polls++
                        }
                        if (polls ==
                            10
                        ) {
                            mutableState.update { it.copy(message = "メール取得を受け付けました。完了は未確認です。保存情報を再読込して確認できます。") }
                        }
                    }
                    try {
                        val connections = repository.connections()
                        mutableState.update { it.copy(connections = connections, connectionError = null) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.update { it.copy(connectionError = "接続情報を再読込できませんでした。") }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            error =
                                if (acceptedMyPageRun != null) {
                                    "確認依頼はサーバーで受け付けました。表示を更新できません。取得結果を読み直してください。"
                                } else {
                                    "操作結果を確認できませんでした。入力と前回の情報を保持しています。管理の取得状況を確認してください。"
                                },
                        )
                    }
                    if (companyId != null) {
                        try {
                            loadDetail()
                        } catch (readError: Exception) {
                            if (readError is CancellationException) throw readError
                            mutableState.update { it.copy(stale = true) }
                        }
                    }
                } finally {
                    mutableState.update { it.copy(loading = false, collecting = null) }
                }
            }
    }
}
