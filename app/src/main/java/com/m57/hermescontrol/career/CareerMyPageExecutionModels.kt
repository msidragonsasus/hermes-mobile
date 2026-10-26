package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable

@Serializable
data class CareerMyPageModelOption(
    val provider: String,
    val id: String,
    val label: String = id,
    val reasoning_efforts: List<String> = emptyList(),
    val auth_kind: String = "unknown",
    val connection_state: String = "unknown",
    val billing: String = "unknown",
    val private_content_allowed: Boolean = false,
    val selectable: Boolean = false,
    val reason: String? = null,
    val endpoint: String? = null,
)

@Serializable
data class CareerMyPageModelConnection(
    val provider: String,
    val auth_kind: String = "unknown",
    val state: String = "unknown",
    val endpoint: String? = null,
    val reason: String? = null,
)

@Serializable
data class CareerMyPageCapabilities(
    val models: List<CareerMyPageModelOption> = emptyList(),
    val connections: List<CareerMyPageModelConnection> = emptyList(),
    val provider_errors: List<CareerProviderError> = emptyList(),
    val catalog_state: String = "checking",
    val checked_at: String = "",
) {
    fun supports(execution: CareerExecution): Boolean =
        models.any {
            it.selectable && it.private_content_allowed && it.connection_state in setOf("available", "connected") &&
                it.provider == execution.provider && it.id == execution.model &&
                execution.reasoning_effort in it.reasoning_efforts
        }
}

@Serializable
data class CareerMyPageBudget(
    val per_run_usd: Double = 0.0,
    val per_day_usd: Double = 0.0,
)

@Serializable
data class CareerMyPageExecutionSettings(
    val version: Int = 0,
    val analysis: CareerExecution? = null,
    val learning: CareerExecution? = null,
    val use_analysis_for_learning: Boolean = true,
    val budget: CareerMyPageBudget = CareerMyPageBudget(),
    val paid_enabled: Boolean = false,
    val source: String = "unset",
) {
    fun validationError(capabilities: CareerMyPageCapabilities?): String? {
        if (analysis == null) return "本文を整理するモデルを選択してください。"
        if (capabilities?.supports(analysis) != true) return "選択した接続・モデルは利用できません。候補を再確認してください。"
        if (!use_analysis_for_learning && (learning == null || !capabilities.supports(learning))) {
            return "手順学習に使うモデルを選択してください。"
        }
        val selected = listOfNotNull(analysis, if (use_analysis_for_learning) analysis else learning)
        val paid =
            selected.any { execution ->
                capabilities.models.any {
                    it.provider == execution.provider && it.id == execution.model &&
                        it.billing == "paid"
                }
            }
        if (budget.per_run_usd < 0 || budget.per_day_usd < 0 ||
            budget.per_run_usd > 1000 || budget.per_day_usd > 1000 ||
            !budget.per_run_usd.isFinite() || !budget.per_day_usd.isFinite()
        ) {
            return "料金上限は0〜1000米ドルで入力してください。"
        }
        if (paid && (!paid_enabled || budget.per_run_usd <= 0 || budget.per_day_usd <= 0)) {
            return "有料APIには明示的な有効化と1実行・1日の料金上限が必要です。"
        }
        return null
    }
}

data class CareerMyPageExecutionUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val supported: Boolean = true,
    val settings: CareerMyPageExecutionSettings? = null,
    val capabilities: CareerMyPageCapabilities? = null,
    val error: String? = null,
    val message: String? = null,
    val pending: CareerMyPageExecutionUpdate? = null,
)

@Serializable
data class CareerMyPageExecutionUpdate(
    val expected_version: Int,
    val idempotency_key: String,
    val analysis: CareerExecution?,
    val learning: CareerExecution?,
    val use_analysis_for_learning: Boolean,
    val budget: CareerMyPageBudget,
    val paid_enabled: Boolean,
)
