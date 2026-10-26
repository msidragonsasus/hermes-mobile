package com.m57.hermescontrol.career

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.util.UUID

class CareerMyPageExecutionViewModel(
    private val repository: CareerMyPageExecutionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CareerMyPageExecutionUiState())
    val state = mutableState.asStateFlow()

    fun refresh() {
        if (state.value.loading || state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val settings = repository.settings()
                val capabilities = repository.capabilities(true)
                mutableState.update { it.copy(settings = settings, capabilities = capabilities, supported = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                val unsupported = error.code() in setOf(404, 405)
                mutableState.update {
                    it.copy(
                        supported = if (unsupported) false else it.supported,
                        error =
                            if (unsupported) {
                                "このサーバーはマイページのAI設定に対応していません。就活サーバーの更新が必要です。"
                            } else {
                                "設定を読み取れませんでした。接続を確認して再読込してください。"
                            },
                    )
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "設定を読み取れませんでした。接続を確認して再読込してください。") }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun save(settings: CareerMyPageExecutionSettings) {
        if (!state.value.supported || state.value.loading || state.value.saving || state.value.pending != null) return
        val error = settings.validationError(state.value.capabilities)
        if (error != null) {
            mutableState.update { it.copy(error = error, message = null) }
            return
        }
        val update =
            CareerMyPageExecutionUpdate(
                expected_version = state.value.settings?.version ?: return,
                idempotency_key = UUID.randomUUID().toString(),
                analysis = settings.analysis,
                learning = settings.learning,
                use_analysis_for_learning = settings.use_analysis_for_learning,
                budget = settings.budget,
                paid_enabled = settings.paid_enabled,
            )
        mutableState.update { it.copy(pending = update) }
        retry()
    }

    fun retry() {
        val update = state.value.pending ?: return
        if (state.value.saving) return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = null, message = null) }
            try {
                val saved = repository.save(update)
                mutableState.update {
                    it.copy(settings = saved, pending = null, message = "保存しました。次に受け付ける確認から反映されます。")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                val code = myPageHttpErrorCode(error)
                if (error.code() in 400..499 && error.code() != 408) {
                    mutableState.update {
                        it.copy(
                            pending = null,
                            supported = if (error.code() in setOf(404, 405)) false else it.supported,
                            error = myPageSettingsRejection(code, error.code()),
                        )
                    }
                } else {
                    mutableState.update { it.copy(error = "保存結果を確認できません。同じ変更として再確認してください。") }
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(error = "保存結果を確認できません。同じ変更として再確認してください。")
                }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}

/** Decode the server's category only; never surface or log arbitrary response details. */
private fun myPageHttpErrorCode(error: HttpException): String? =
    try {
        val response =
            Json.parseToJsonElement(
                error
                    .response()
                    ?.errorBody()
                    ?.string()
                    .orEmpty(),
            ) as? JsonObject
        (response?.get("detail") as? JsonObject)?.text("code")
    } catch (_: Exception) {
        null
    }

internal fun myPageSettingsRejection(
    code: String?,
    status: Int,
): String =
    when (code) {
        "version_conflict" -> {
            "別の操作で設定が変更されています。設定を再読込して選び直してください。"
        }

        "invalid_budget", "paid_budget_required" -> {
            "料金上限と有料APIの有効化を確認してください。設定は保存されていません。"
        }

        "model_unsupported", "reasoning_unsupported" -> {
            "モデルまたは推論強度を利用できません。候補を再読込して選び直してください。"
        }

        "private_content_forbidden", "private_content_policy_unverified" -> {
            "この接続には個別案内を送信できません。別の確認済み接続を選択してください。"
        }

        "pricing_unverified" -> {
            "単価を確認できません。料金確認済みの接続を選択してください。"
        }

        "authentication_required", "provider_unavailable", "auth_required" -> {
            "モデルの認証を確認してください。設定は保存されていません。"
        }

        else -> {
            when (status) {
                401 -> "Hermesへのログインを確認してください。設定は保存されていません。"
                403 -> "この設定を保存する権限を確認してください。設定は保存されていません。"
                404, 405 -> "このサーバーはマイページのAI設定に対応していません。就活サーバーの更新が必要です。"
                429 -> "利用上限に達しました。時間をおいて接続状態を再確認してください。"
                else -> "設定がサーバーに拒否されました。内容と接続状態を確認して保存してください。"
            }
        }
    }
