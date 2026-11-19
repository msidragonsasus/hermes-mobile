package com.m57.hermescontrol.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import retrofit2.HttpException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal class TodayPlanState(
    private val scope: String,
    private val repository: HomeRepository,
) {
    private val profile = AppScope.PROFILE
    private val api = ApiClient.hermesApi
    private val mutex = Mutex()
    private var refreshId: String? = null
    var plan by mutableStateOf<TodayPlan?>(null)
        private set
    var settings by mutableStateOf<TodaySettings?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private fun current() = scope == AppScope.connectionKey()

    suspend fun restore() {
        try {
            val payload = repository.dao.provider(scope, "today_home")?.metadata
            if (!payload.isNullOrBlank() && current()) plan = repository.json.decodeFromString<TodayPlan>(payload)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }

    private suspend fun accept(value: TodayPlan) {
        if (!current()) return
        plan = value
        repository.dao.cacheToday(scope, repository.json.encodeToString(value))
    }

    private suspend fun operation(action: suspend () -> Unit) {
        mutex.withLock {
            if (!current()) return
            busy = true
            try {
                check(AuthManager.isGatedMode())
                action()
                if (current()) error = null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current()) {
                    error =
                        when {
                            failure is HttpException && failure.code() == 404 -> "今日やることの連携にはサーバーの更新が必要です。"
                            failure is HttpException && failure.code() in setOf(401, 403) -> "接続の認証を確認してください。"
                            failure is HttpException && failure.code() == 409 -> "確認中です。終了後に設定を保存してください。"
                            else -> "最新情報を確認できません。保存済みの一覧を表示しています。"
                        }
                }
            } finally {
                busy = false
            }
        }
    }

    suspend fun load() =
        operation {
            val value = api.todayPlan(profile)
            val configuration = api.todaySettings(profile)
            if (!current()) return@operation
            settings = configuration
            accept(value)
            if (configuration.enabled && !value.isCurrent() && value.state != "running") {
                val day = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
                accept(api.refreshToday(profile, TodayRefresh("auto:$day", automatic = true)))
            }
        }

    suspend fun refresh() =
        operation {
            // Retain the operation ID after an ambiguous HTTP failure. Manual retry
            // reconciles the original operation instead of launching another model run.
            val id = refreshId ?: UUID.randomUUID().toString().also { refreshId = it }
            accept(api.refreshToday(profile, TodayRefresh(id)))
            if (current()) refreshId = null
        }

    suspend fun save(value: TodaySettings) =
        operation {
            val saved = api.saveTodaySettings(profile, value)
            if (!current()) return@operation
            settings = saved
            accept(TodayPlan())
            accept(api.todayPlan(profile))
            if (saved.enabled) {
                val day = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
                accept(api.refreshToday(profile, TodayRefresh("auto:$day", automatic = true)))
            }
        }

    /** The change in flight per task. Only that row waits; the rest of the list stays usable. */
    val pending = mutableStateMapOf<String, TodayChange>()

    /** Last failed change per task, shown on that row instead of the list-wide error. */
    val failures = mutableStateMapOf<String, TodayRowFailure>()

    /** Last change the server confirmed, shown inline (with undo where it restores exactly). */
    var recent by mutableStateOf<TodayRecent?>(null)
        private set

    suspend fun setDone(
        task: TodayTask,
        done: Boolean,
    ) = write(task, TodayChange.Done(done))

    suspend fun setInterest(
        task: TodayTask,
        interested: Boolean,
    ) = write(task, TodayChange.Interest(interested))

    /** Repeats the row's failed change, not whatever the row would do now. */
    suspend fun retry(task: TodayTask) {
        failures[task.id]?.let { write(task, it.change) }
    }

    /**
     * The one write path for a task. The row shows the request at once, but the plan changes
     * only from the server's snapshot, so a failed or unsupported write never reads as saved.
     * Shares [mutex] with polling, so an earlier poll cannot overwrite a later change, and drops
     * responses that arrive after the connection changed.
     */
    suspend fun write(
        task: TodayTask,
        change: TodayChange,
    ) {
        if (!current() || task.id in pending) return
        pending[task.id] = change
        failures.remove(task.id)
        var missing = false
        try {
            mutex.withLock {
                if (!current()) return
                check(AuthManager.isGatedMode())
                val value =
                    when (change) {
                        is TodayChange.Done -> {
                            api.completeTodayTask(profile, task.id, TodayCompletion(change.done))
                        }

                        is TodayChange.Interest -> {
                            api.setTodayInterest(profile, task.id, TodayInterest(change.interested))
                        }
                    }
                if (!current()) return
                accept(value)
                val wasActionable = task.actionability == TODAY_ACTIONABLE
                recent = if (change == TodayChange.Done(false)) null else TodayRecent(task.id, change, wasActionable)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (current()) {
                missing = failure is HttpException && failure.code() == 404 && failure.errorCode() == "task_missing"
                failures[task.id] = todayRowFailure(failure, change, missing)
            }
        } finally {
            pending.remove(task.id)
        }
        // The server no longer has this task; show its current list rather than a dead row.
        if (missing) load()
    }
}

internal sealed interface TodayChange {
    data class Done(
        val done: Boolean,
    ) : TodayChange

    /** true = やることに追加, false = 対象外. */
    data class Interest(
        val interested: Boolean,
    ) : TodayChange
}

/** [unavailable]: retrying cannot help (server or connection lacks the feature). */
internal data class TodayRowFailure(
    val change: TodayChange,
    val message: String,
    val unavailable: Boolean,
)

/** [wasActionable]: undoing 対象外 restores exactly only for a server-confirmed task. */
internal data class TodayRecent(
    val id: String,
    val change: TodayChange,
    val wasActionable: Boolean,
)

private fun todayRowFailure(
    failure: Exception,
    change: TodayChange,
    missing: Boolean,
): TodayRowFailure {
    val code = (failure as? HttpException)?.code() ?: 0
    // A route the server does not have: 404 without task_missing, or a method it does not accept.
    val unsupported = !missing && code in setOf(404, 405, 501)
    val message =
        when {
            missing -> {
                "この項目は最新の一覧にありません。"
            }

            unsupported && change is TodayChange.Interest -> {
                "このサーバーは「対象外」「やることに追加」の保存に未対応です。サーバーの更新後に使えます。"
            }

            unsupported -> {
                "このサーバーは完了の保存に未対応です。サーバーの更新後に使えます。"
            }

            failure is IllegalStateException -> {
                "この接続では保存できません。接続設定を確認してください。"
            }

            code in setOf(401, 403) -> {
                "保存できませんでした。接続の認証を確認してください。"
            }

            else -> {
                "保存できませんでした。もう一度お試しください。"
            }
        }
    return TodayRowFailure(change, message, unavailable = unsupported || failure is IllegalStateException)
}

private fun HttpException.errorCode(): String? =
    runCatching {
        // Retrofit buffers error bodies, so this reads memory, not the network.
        val body = response()?.errorBody()?.string().orEmpty()
        Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"").find(body)?.groupValues?.get(1)
    }.getOrNull()

@Composable
internal fun rememberTodayPlanState(
    scope: String,
    repository: HomeRepository,
): TodayPlanState {
    val state = remember(scope, repository) { TodayPlanState(scope, repository) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycle) {
        state.restore()
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state.load()
                delay(if (state.plan?.state == "running") 3_000 else 60_000)
            }
        }
    }
    return state
}
