package com.m57.hermescontrol.career

/** The UI and new-send guard share this policy; cached model options are not authorization. */
internal fun careerDraftEditable(state: CareerCoachUiState): Boolean =
    state.initialized && state.localWritable && !state.readOnly && state.snapshot?.archived != true &&
        !state.busy && state.pending == null

internal fun careerExecutionReady(state: CareerCoachUiState): Boolean {
    val execution = state.execution ?: return false
    val capabilities = state.capabilities ?: return false
    return state.capabilitiesChecked && capabilities.state == "available" &&
        "execution_config" in capabilities.features && capabilities.supports(execution)
}

internal fun careerCanSend(state: CareerCoachUiState): Boolean =
    careerDraftEditable(state) && careerExecutionReady(state) && state.draft.isNotBlank()

internal fun careerSendPreparation(state: CareerCoachUiState): String? =
    when {
        !state.initialized -> "保存済みの相談を読み込んでいます"
        !state.localWritable -> "端末内の相談を安全に保存できません。保存データを保持して送信を止めています"
        state.readOnly || state.snapshot?.archived == true -> "閲覧中の相談には送信できません"
        state.pending != null -> "前の依頼の送信結果を確認してください"
        state.busy -> "依頼を送信しています"
        !state.capabilitiesChecked -> "モデル候補を確認できていません。再確認してください"
        "execution_config" !in state.capabilities?.features.orEmpty() -> "モデル指定に対応した就活サーバーへ更新してください"
        state.execution == null -> "モデルと推論強度を選択してください"
        !careerExecutionReady(state) -> "対応するモデルと推論強度を選択してください"
        else -> null
    }

internal fun careerCurrentRun(state: CareerCoachUiState): CareerCoachRun? {
    val runs = state.snapshot?.runs.orEmpty()
    return runs.firstOrNull { it.state in setOf("running", "queued") }
        ?: runs.firstOrNull { it.state in setOf("waiting_user", "paused") }
}

internal fun careerCanStop(
    state: CareerCoachUiState,
    run: CareerCoachRun,
): Boolean =
    !state.readOnly && state.snapshot?.archived != true && !state.stale && !state.busy && state.pending == null &&
        run.state in setOf("queued", "running", "paused") && run.cancel_requested == 0

/** Latest records win by ID, but a latest-page response must not evict loaded older pages. */
internal fun mergeCareerConversation(
    previous: CareerCoachSnapshot?,
    latest: CareerCoachSnapshot,
    keepOlderCursor: Boolean,
): CareerCoachSnapshot {
    if (previous == null || previous.thread_id != latest.thread_id || previous.profile != latest.profile) return latest
    return latest.copy(
        messages = (previous.messages + latest.messages).associateBy { it.id }.values.sortedBy { it.seq },
        // Keep known terminal receipts alongside loaded history, never stale active controls.
        runs = latest.runs + previous.runs.filter { old -> !old.active && latest.runs.none { it.id == old.id } },
        older_before = if (keepOlderCursor) previous.older_before else latest.older_before,
    )
}
