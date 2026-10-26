package com.m57.hermescontrol.career

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CareerGmailState(
    val accountEmail: String? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val saved: Int = 0,
    val hasMore: Boolean = true,
)

internal class CareerGmailViewModel(
    private val auth: CareerGmailAuthManager,
    private val gmail: CareerGmailClient,
    private val repository: CareerCompanyRepository,
    private val local: CareerLocalStore,
    private val companyId: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareerGmailState())
    val state = mutableState.asStateFlow()
    private var restart = false
    private var job: Job? = null

    init {
        viewModelScope.launch {
            mutableState.update { it.copy(accountEmail = auth.connectedEmail(), initialized = true) }
        }
    }

    fun collect(
        activity: Activity,
        requestResolution: (PendingIntent) -> Unit,
        restart: Boolean = false,
    ) {
        if (state.value.loading) return
        this.restart = restart
        mutableState.update {
            it.copy(
                loading = true,
                error = null,
                message = null,
                saved = 0,
                hasMore = if (restart) true else it.hasMore,
            )
        }
        job =
            viewModelScope.launch {
                handleAuthorization(auth.beginAuthorization(activity), requestResolution)
            }
    }

    fun completeAuthorization(
        activity: Activity,
        data: Intent?,
    ) {
        if (!state.value.loading) return
        job =
            viewModelScope.launch {
                handleAuthorization(auth.completeAuthorization(activity, data)) {}
            }
    }

    fun changeAccount(
        activity: Activity,
        requestResolution: (PendingIntent) -> Unit,
    ) {
        if (state.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null) }
            auth.disconnect(activity).onFailure { error ->
                mutableState.update { it.copy(loading = false, error = error.message ?: "Google連携を解除できませんでした。") }
                return@launch
            }
            mutableState.update { it.copy(accountEmail = null) }
            handleAuthorization(auth.beginAuthorization(activity), requestResolution)
        }
    }

    fun stop() {
        job?.cancel()
        mutableState.update { it.copy(loading = false, message = "取得を停止しました。保存済み分を残して、続きから再開できます。") }
    }

    private suspend fun handleAuthorization(
        result: CareerGmailAuthorizationResult,
        requestResolution: (PendingIntent) -> Unit,
    ) {
        when (result) {
            is CareerGmailAuthorizationResult.Authorized -> {
                if (companyId == null) {
                    mutableState.update {
                        it.copy(
                            accountEmail = result.accountEmail,
                            loading = false,
                            message = "接続しました。各企業の「メール」から取得できます。",
                        )
                    }
                } else {
                    importMail(result)
                }
            }

            is CareerGmailAuthorizationResult.NeedsResolution -> {
                requestResolution(result.pendingIntent)
            }

            is CareerGmailAuthorizationResult.Failure -> {
                mutableState.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    private suspend fun importMail(authorization: CareerGmailAuthorizationResult.Authorized) {
        try {
            val more =
                CareerGmailSync(
                    gmail,
                    local,
                    repository,
                ).collect(authorization, requireNotNull(companyId), restart) { saved, hasMore ->
                    mutableState.update {
                        it.copy(
                            saved = saved,
                            hasMore = hasMore,
                            message = "${saved}件取得済み…",
                        )
                    }
                }
            mutableState.update {
                it.copy(
                    accountEmail = authorization.accountEmail,
                    loading = false,
                    error = null,
                    hasMore = more,
                    message = if (more) "${it.saved}件確認しました。続きがあります。" else "メールを更新しました。",
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update {
                it.copy(
                    accountEmail = authorization.accountEmail,
                    loading = false,
                    error =
                        (
                            if (error is retrofit2.HttpException) {
                                if (error.code() ==
                                    404
                                ) {
                                    "企業ごとの取得に対応するサーバー更新が必要です。"
                                } else {
                                    "保存先に接続できませんでした（${error.code()}）。"
                                }
                            } else if (error is CareerGmailHttpException && error.status == 400) {
                                "Gmailの再開位置が期限切れの可能性があります。「最新から更新」で再確認してください。"
                            } else {
                                error.message ?: "メールを取得できませんでした。"
                            }
                        ) + " 保存済み分は保持しています。「続きを取得」で再試行できます。",
                )
            }
        }
    }
}

internal fun gmailApprovedSenders(rules: List<CareerMailSenderRule>): List<String> =
    rules.filter(CareerMailSenderRule::enabled).map { it.address.trim().lowercase() }.distinct()
