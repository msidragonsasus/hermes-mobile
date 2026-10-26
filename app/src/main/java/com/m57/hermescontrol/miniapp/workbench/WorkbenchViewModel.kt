package com.m57.hermescontrol.miniapp.workbench

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

internal interface WorkbenchPendingStore {
    fun read(): WorkbenchPending?

    fun commit(pending: WorkbenchPending): Boolean

    fun clear(pending: WorkbenchPending): Boolean
}

internal class PreferencesWorkbenchPendingStore(
    private val preferences: SharedPreferences,
) : WorkbenchPendingStore {
    override fun read(): WorkbenchPending? = preferences.getString("pending", null)?.let(WorkbenchParser::pending)

    override fun commit(pending: WorkbenchPending): Boolean =
        read() == null && preferences.edit().putString("pending", WorkbenchParser.encode(pending)).commit()

    override fun clear(pending: WorkbenchPending): Boolean =
        read() == pending && preferences.edit().remove("pending").commit()

    companion object {
        fun forScope(
            context: Context,
            scope: String,
            company: String,
        ): PreferencesWorkbenchPendingStore {
            val key =
                MessageDigest.getInstance("SHA-256").digest("$scope\n$company".toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
            return PreferencesWorkbenchPendingStore(
                context.getSharedPreferences("workbench-$key", Context.MODE_PRIVATE),
            )
        }
    }
}

internal data class WorkbenchUiState(
    val snapshot: WorkbenchSnapshot? = null,
    val editor: EditorSnapshot? = null,
    val busy: Boolean = false,
    val pending: WorkbenchPending? = null,
    val blocked: Boolean = false,
    val changedConnection: Boolean = false,
    val disabled: Boolean = false,
    val message: String = "",
)

internal class WorkbenchViewModel(
    private val repository: WorkbenchRepository,
    private val disk: WorkbenchPendingStore,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkbenchUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var epoch = 0

    init {
        try {
            mutable.value = mutable.value.copy(pending = disk.read())
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(blocked = true, message = "前回の操作記録を読めません。新しい操作を停止しています。")
        }
    }

    private fun current(token: Int): Boolean {
        if (token != epoch || mutable.value.changedConnection) return false
        if (!repository.isCurrent()) {
            connectionChanged()
            return false
        }
        return true
    }

    fun load() {
        if (!repository.isCurrent()) {
            connectionChanged()
            return
        }
        if (mutable.value.busy || mutable.value.changedConnection) return
        val token = epoch
        mutable.value = mutable.value.copy(busy = true)
        job =
            viewModelScope.launch {
                try {
                    val snapshot = repository.snapshot()
                    if (current(token)) mutable.value = mutable.value.copy(snapshot = snapshot, disabled = false)
                    val pending = mutable.value.pending
                    if (pending != null && current(token)) accept(repository.recover(pending), pending, token)
                    if (current(token)) {
                        val editor = repository.editor()
                        if (current(token)) mutable.value = mutable.value.copy(editor = editor)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: WorkbenchUnavailable) {
                    if (current(token)) {
                        mutable.value =
                            mutable.value.copy(
                                disabled = error.disabled,
                                message = if (error.disabled) "この会社の改修はサーバーで有効になっていません。" else "接続を確認して、結果を読み直してください。",
                            )
                    }
                } catch (_: Exception) {
                    if (current(token)) mutable.value = mutable.value.copy(message = "読み込みを完了できませんでした。")
                } finally {
                    if (token == epoch) mutable.value = mutable.value.copy(busy = false)
                }
            }
    }

    fun send(
        action: String,
        fields: Map<String, JsonElement> = emptyMap(),
    ) {
        val state = mutable.value
        val snapshot = state.snapshot ?: return
        if (state.busy || state.pending != null || state.blocked || state.changedConnection ||
            !repository.isCurrent() ||
            action !in workbenchActions
        ) {
            return
        }
        if (state.editor?.job?.active == true && action != "cancel_editor") return
        val actualAction = if (action == "apply") "apply_reviewed" else action
        val payload =
            buildJsonObject {
                fields.forEach { (name, value) -> put(name, value) }
                put("protocol_version", 1)
                put("operation_id", newId())
                put("action", actualAction)
                put("expected_active", snapshot.active)
                put("revision", snapshot.revision)
                if (actualAction ==
                    "apply_reviewed"
                ) {
                    put(
                        "review_hash",
                        WorkbenchParser.digest(
                            JsonObject(
                                snapshot.files.mapValues {
                                    kotlinx.serialization.json.JsonPrimitive(it.value)
                                },
                            ),
                        ),
                    )
                }
            }
        val pending =
            WorkbenchPending(
                payload.getValue("operation_id").toString().trim('"'),
                WorkbenchParser.digest(payload),
                actualAction,
                snapshot.active,
                snapshot.revision,
            )
        if (!runCatching { disk.commit(pending) }.getOrDefault(false)) {
            mutable.value = state.copy(blocked = true, message = "操作記録を保存できないため送信を停止しました。")
            return
        }
        mutable.value = state.copy(pending = pending, busy = true, message = "操作を送信しています。")
        dispatch(payload, pending)
    }

    fun fence() {
        val state = mutable.value
        val pending = state.pending ?: return
        if (state.busy || state.blocked || state.changedConnection || !repository.isCurrent()) return
        mutable.value = state.copy(busy = true)
        dispatch(pending.fence(), pending)
    }

    private fun dispatch(
        payload: JsonObject,
        pending: WorkbenchPending,
    ) {
        val token = epoch
        job =
            viewModelScope.launch {
                try {
                    accept(repository.mutate(payload, pending), pending, token)
                    if (current(token)) {
                        val snapshot = repository.snapshot()
                        if (current(token)) mutable.value = mutable.value.copy(snapshot = snapshot)
                        if (current(token)) {
                            val editor = repository.editor()
                            if (current(token)) mutable.value = mutable.value.copy(editor = editor)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (current(token)) {
                        mutable.value =
                            mutable.value.copy(message = "結果が不明です。結果を確認するか、未受付の操作を取り消してください。")
                    }
                } finally {
                    if (token == epoch) mutable.value = mutable.value.copy(busy = false)
                }
            }
    }

    private fun accept(
        receipt: WorkbenchReceipt,
        pending: WorkbenchPending,
        token: Int,
    ) {
        if (!current(token) || mutable.value.pending != pending) return
        if (receipt.state == "unknown") {
            mutable.value = mutable.value.copy(message = "受付結果がまだ不明です。新しい操作は開始できません。")
            return
        }
        if (!runCatching { disk.clear(pending) }.getOrDefault(false)) {
            mutable.value = mutable.value.copy(blocked = true, message = "結果を取得しましたが操作記録を更新できません。")
            return
        }
        mutable.value =
            mutable.value.copy(
                pending = null,
                blocked = false,
                message =
                    when (receipt.state) {
                        "done" -> {
                            if (pending.action == "propose") "改修依頼を受け付けました。生成状況を確認してください。" else "操作が完了しました。"
                        }

                        "cancelled" -> {
                            "未受付の操作を取り消しました。"
                        }

                        else -> {
                            when (receipt.code) {
                                "edit_conflict" -> "別の変更があります。表示を更新して差分を確認してください。"
                                "test_failed", "invalid_candidate" -> "テストに通りませんでした。編集内容を確認してください。"
                                "apply_failed" -> "反映に失敗しました。稼働中の版を維持しています。"
                                "mypage_busy" -> "マイページ確認中です。完了後に操作してください。"
                                "test_required" -> "編集後のテストを先に実行してください。"
                                "review_changed" -> "確認した差分と現在の候補が異なります。読み直してください。"
                                "editor_busy" -> "専用Botが候補を生成中です。結果の確認か取消を先に行ってください。"
                                else -> "操作を受け付けられませんでした。表示を更新してください。"
                            }
                        }
                    },
            )
    }

    fun connectionChanged() {
        epoch++
        job?.cancel()
        mutable.value =
            mutable.value.copy(
                snapshot = null,
                editor = null,
                busy = false,
                changedConnection = true,
                message = "接続先が変わりました。元の接続先で結果を確認してください。",
            )
    }

    fun release() {
        epoch++
        job?.cancel()
        mutable.value =
            mutable.value.copy(
                snapshot = null,
                editor = null,
                busy = false,
                changedConnection = false,
                disabled = false,
                message = "",
            )
    }
}
