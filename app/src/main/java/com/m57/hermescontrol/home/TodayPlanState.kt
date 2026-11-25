package com.m57.hermescontrol.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
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
    private val api get() = ApiClient.hermesApi
    private val mutex = Mutex()
    private var autoRequestedDay: String? = null
    private var refreshId: String? = null
    private var busyActions = 0
    var plan by mutableStateOf<TodayPlan?>(null)
        private set
    var settings by mutableStateOf<TodaySettings?>(null)
        private set
    var failure by mutableStateOf<HomeFailure?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var cacheWriteFailed by mutableStateOf(false)
        private set

    private fun current() = scope == AppScope.connectionKey()

    private fun current(epoch: Long) = current() && epoch % 2L == 0L && epoch == AuthManager.connectionEpoch.value

    suspend fun restore() {
        try {
            val payload = repository.dao.provider(scope, "today_home")?.metadata
            if (!payload.isNullOrBlank() && current() && plan == null) {
                plan = repository.json.decodeFromString<TodayPlan>(payload)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
    }

    private suspend fun accept(
        value: TodayPlan,
        epoch: Long,
    ) {
        if (!current(epoch)) return
        plan = value
        // The server snapshot is authoritative. Offline caching cannot undo its acknowledgement.
        val failed =
            try {
                repository.dao.cacheToday(scope, repository.json.encodeToString(value))
                false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                true
            }
        if (current(epoch)) cacheWriteFailed = failed
    }

    private suspend fun operation(
        userInitiated: Boolean,
        publishFailure: () -> Boolean = { true },
        action: suspend (epoch: Long) -> Unit,
    ): HomeFailure? {
        val epoch = AuthManager.connectionEpoch.value
        if (!current(epoch)) return HomeFailure.Generic
        if (userInitiated) {
            busyActions++
            busy = true
        }
        try {
            return mutex.withLock {
                if (!current(epoch)) return@withLock HomeFailure.Generic
                if (!AuthManager.isGatedMode()) {
                    if (publishFailure()) failure = HomeFailure.NotGated
                    return@withLock HomeFailure.NotGated
                }
                if (!userInitiated) loading = true
                try {
                    action(epoch)
                    if (!current(epoch)) return@withLock HomeFailure.Generic
                    failure = null
                    null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val result = (error as? ClassifiedTodayFailure)?.failure ?: homeFailureOf(error)
                    if (current(epoch) && publishFailure()) failure = result
                    result
                } finally {
                    if (!userInitiated) loading = false
                }
            }
        } finally {
            if (userInitiated) {
                busyActions--
                busy = busyActions > 0
            }
        }
    }

    private fun todayJst() = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()

    private suspend fun autoRefresh(
        day: String,
        epoch: Long,
    ) {
        if (!current(epoch)) return
        autoRequestedDay = day
        val value = api.refreshToday(profile, TodayRefresh("auto:$day", automatic = true))
        accept(value, epoch)
    }

    suspend fun load(
        userInitiated: Boolean = false,
        allowAutomatic: Boolean = !userInitiated,
    ) {
        operation(userInitiated) { epoch ->
            val value = api.todayPlan(profile)
            if (!current(epoch)) return@operation
            val configuration = api.todaySettings(profile)
            if (!current(epoch)) return@operation
            settings = configuration
            accept(value, epoch)
            val day = todayJst()
            if (allowAutomatic && configuration.enabled && value.plan_day != day &&
                value.state != "running" && autoRequestedDay != day
            ) {
                autoRefresh(day, epoch)
            }
        }
    }

    suspend fun refresh() {
        if (busy || plan?.state == "running") return
        operation(userInitiated = true) { epoch ->
            // A retry after an ambiguous failure reconciles the original operation.
            val id = refreshId ?: UUID.randomUUID().toString().also { refreshId = it }
            try {
                val value = api.refreshToday(profile, TodayRefresh(id))
                if (current(epoch)) {
                    refreshId = null
                    accept(value, epoch)
                }
            } catch (error: HttpException) {
                if (!current(epoch)) return@operation
                val status = error.code()
                val code = error.errorCode()
                when (val result = homeHttpFailure(status, code)) {
                    HomeFailure.RunInProgress -> {
                        refreshId = null
                        val value = api.todayPlan(profile)
                        accept(value, epoch)
                    }

                    HomeFailure.SetupRequired -> {
                        refreshId = null
                        val value = api.todaySettings(profile)
                        if (current(epoch)) settings = value
                    }

                    else -> {
                        throw ClassifiedTodayFailure(result)
                    }
                }
            }
        }
    }

    suspend fun pollRun() {
        operation(userInitiated = false) { epoch ->
            if (plan?.state != "running") return@operation
            val value = api.todayPlan(profile)
            accept(value, epoch)
        }
    }

    suspend fun save(value: TodaySettings): TodaySaveResult {
        var committed = false
        val result =
            operation(userInitiated = true, publishFailure = { committed }) { epoch ->
                val saved = api.saveTodaySettings(profile, value)
                if (!current(epoch)) return@operation
                committed = true
                settings = saved
                refreshId = null
                recent = null
                failures.clear()
                accept(TodayPlan(), epoch)
                if (!current(epoch)) return@operation
                val refreshed = api.todayPlan(profile)
                if (!current(epoch)) return@operation
                accept(refreshed, epoch)
                if (saved.enabled) autoRefresh(todayJst(), epoch)
            }
        return when {
            committed || result == null -> TodaySaveResult.Ok
            result is HomeFailure.InvalidInput -> TodaySaveResult.InvalidInput(result.code)
            result == HomeFailure.RunInProgress -> TodaySaveResult.RunInProgress
            else -> TodaySaveResult.Failed(result)
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
        val epoch = AuthManager.connectionEpoch.value
        if (!current(epoch) || task.id in pending) return
        pending[task.id] = change
        failures.remove(task.id)
        var missing = false
        try {
            mutex.withLock {
                if (!current(epoch)) return
                if (!AuthManager.isGatedMode()) {
                    failures[task.id] = todayRowFailure(HomeFailure.NotGated, change, missing = false)
                    return
                }
                val value =
                    when (change) {
                        is TodayChange.Done -> {
                            api.completeTodayTask(profile, task.id, TodayCompletion(change.done))
                        }

                        is TodayChange.Interest -> {
                            api.setTodayInterest(profile, task.id, TodayInterest(change.interested))
                        }
                    }
                if (!current(epoch)) return
                accept(value, epoch)
                if (!current(epoch)) return
                val wasActionable = task.actionability == TODAY_ACTIONABLE
                recent = if (change == TodayChange.Done(false)) null else TodayRecent(task.id, change, wasActionable)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (current(epoch)) {
                val problem =
                    if (failure is HttpException) {
                        val code = failure.errorCode()
                        missing = failure.code() == 404 && code == "task_missing"
                        homeHttpFailure(failure.code(), code)
                    } else {
                        homeFailureOf(failure)
                    }
                failures[task.id] = todayRowFailure(problem, change, missing)
            }
        } finally {
            pending.remove(task.id)
        }
        // The server no longer has this task; show its current list rather than a dead row.
        if (missing) load(allowAutomatic = false)
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
    failure: HomeFailure,
    change: TodayChange,
    missing: Boolean,
): TodayRowFailure {
    val unsupported = !missing && failure is HomeFailure.ServerOutdated
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

            failure == HomeFailure.NotGated -> {
                "この接続では保存できません。接続設定を確認してください。"
            }

            failure == HomeFailure.ProfileUnavailable -> {
                "保存できませんでした。接続プロファイルを確認してもう一度お試しください。"
            }

            failure == HomeFailure.AuthRequired -> {
                "保存できませんでした。接続の認証を確認してください。"
            }

            else -> {
                "保存できませんでした。もう一度お試しください。"
            }
        }
    return TodayRowFailure(change, message, unavailable = unsupported || failure == HomeFailure.NotGated)
}

private class ClassifiedTodayFailure(
    val failure: HomeFailure,
) : RuntimeException()

internal sealed interface TodaySaveResult {
    data object Ok : TodaySaveResult

    data class InvalidInput(
        val code: String,
    ) : TodaySaveResult

    data object RunInProgress : TodaySaveResult

    data class Failed(
        val failure: HomeFailure,
    ) : TodaySaveResult
}

@Composable
internal fun rememberTodayPlanState(
    scope: String,
    repository: HomeRepository,
): TodayPlanState {
    val state = remember(scope, repository) { TodayPlanState(scope, repository) }
    LaunchedEffect(state) { state.restore() }
    return state
}
