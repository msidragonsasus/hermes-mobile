package com.m57.hermescontrol.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    private val profile = AuthManager.activeProfileId.value ?: "default"
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

    private fun current() = scope == AuthManager.uiPreferenceScope()

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

    suspend fun complete(task: TodayTask) =
        operation { accept(api.completeTodayTask(profile, task.id, TodayCompletion(!task.done))) }
}

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
