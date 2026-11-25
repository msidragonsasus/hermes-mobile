package com.m57.hermescontrol.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.m57.hermescontrol.HermesControlApp
import com.m57.hermescontrol.career.CareerHomeProvider
import com.m57.hermescontrol.career.CareerLocalStore
import com.m57.hermescontrol.career.careerScope
import com.m57.hermescontrol.data.local.AppScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.domain.hermes.LinkState
import com.m57.hermescontrol.ui.common.NoticeTone
import com.m57.hermescontrol.worker.WorkerSourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.Instant

internal class HomeCareerSource(
    private val scope: String,
    private val app: HermesControlApp,
    private val repository: HomeRepository,
) {
    var failure by mutableStateOf<HomeFailure?>(null)
        private set
    var careerEnabled by mutableStateOf(false)
        private set
    var threadTitles by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    suspend fun load(careerVisible: Boolean) {
        val epoch = AuthManager.connectionEpoch.value

        fun current() =
            epoch % 2L == 0L && epoch == AuthManager.connectionEpoch.value && scope == AppScope.connectionKey()
        if (!current()) return
        try {
            val enabled = CareerLocalStore(app, careerScope()).enabled()
            if (!current()) return
            careerEnabled = enabled
            if (!careerEnabled || !careerVisible) {
                failure = null
                return
            }
            if (!AuthManager.isGatedMode()) {
                failure = HomeFailure.NotGated
                return
            }
            val page = CareerHomeProvider().read()
            if (!current()) return
            repository.dao.replace(
                scope,
                page.items.map { HomeRow(scope, "career", it.id, repository.json.encodeToString(it)) },
                repository.json.encodeToString(page.copy(items = emptyList())),
            )
            if (!current()) return
            failure = null
            if (page.items.any {
                    it.category == "action" &&
                        it.target.thread_id?.let { id -> id !in threadTitles } == true
                }
            ) {
                try {
                    val threads = ApiClient.hermesApi.careerThreads(AppScope.PROFILE)
                    if (current()) {
                        threadTitles = threads.threads.filter { it.title.isNotBlank() }.associate { it.id to it.title }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Thread labels are optional. Try again after the next successful Home read.
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current()) failure = homeFailureOf(error)
        }
    }
}

internal data class HomeReloadKey(
    val epoch: Long,
    val connected: Boolean,
    val careerVisible: Boolean,
    val manualToken: Int,
)

@Composable
internal fun HomeRefreshEffect(
    scope: String,
    reloadKey: HomeReloadKey,
    lifecycle: Lifecycle,
    today: TodayPlanState,
    loadCareer: suspend (Boolean) -> Unit,
    refreshWorker: suspend () -> Unit,
    onManualRefreshChanged: (Boolean) -> Unit,
    onPoll: (Instant) -> Unit,
) {
    var handledToken by remember(scope) { mutableIntStateOf(0) }
    LaunchedEffect(scope, reloadKey, lifecycle, today) {
        fun stable() = reloadKey.epoch % 2L == 0L && reloadKey.epoch == AuthManager.connectionEpoch.value
        if (!stable()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                snapshotFlow { today.plan?.state == "running" }.collect { running ->
                    if (running) {
                        while (stable() && today.plan?.state == "running") {
                            delay(3_000)
                            if (stable()) today.pollRun()
                        }
                    }
                }
            }
            while (stable()) {
                val manual = handledToken != reloadKey.manualToken
                if (manual) handledToken = reloadKey.manualToken
                onManualRefreshChanged(manual)
                try {
                    onPoll(Instant.now())
                    coroutineScope {
                        launch { today.load(userInitiated = manual) }
                        launch { loadCareer(reloadKey.careerVisible) }
                        launch { refreshWorker() }
                    }
                } finally {
                    onManualRefreshChanged(false)
                }
                delay(60_000)
            }
        }
    }
}

internal enum class HomeNoticeAction(
    val label: String,
) {
    ConnectionSettings("接続設定"),
    Retry("再試行"),
}

internal data class HomeStatusNotice(
    val tone: NoticeTone,
    val text: String,
    val action: HomeNoticeAction? = null,
    val coversToday: Boolean = false,
)

internal fun homeStatusOf(
    linkState: LinkState,
    storageError: String?,
    todayFailure: HomeFailure?,
    careerFailure: HomeFailure?,
    careerEnabled: Boolean,
    careerVisible: Boolean,
    workApprovalsStatus: WorkerSourceStatus,
    workRoutinesStatus: WorkerSourceStatus,
): HomeStatusNotice? {
    val career = careerFailure.takeIf { careerEnabled && careerVisible }
    val worker =
        listOf(
            workApprovalsStatus,
            workRoutinesStatus,
        ).mapNotNull { (it as? WorkerSourceStatus.Failed)?.failure }
    val failures = listOfNotNull(todayFailure, career) + worker
    if (storageError != null) return HomeStatusNotice(NoticeTone.Error, storageError)
    if (linkState == LinkState.AUTH_REQUIRED || HomeFailure.AuthRequired in failures) {
        return HomeStatusNotice(
            NoticeTone.Warning,
            "ログインが必要です。保存済みの情報を表示しています。",
            HomeNoticeAction.ConnectionSettings,
            coversToday = todayFailure == HomeFailure.AuthRequired || linkState == LinkState.AUTH_REQUIRED,
        )
    }
    if (HomeFailure.NotGated in failures) {
        return HomeStatusNotice(
            NoticeTone.Info,
            "この接続方式では今日やること・就活を読み込めません。",
            HomeNoticeAction.ConnectionSettings,
            coversToday = todayFailure == HomeFailure.NotGated,
        )
    }
    if (linkState == LinkState.OFFLINE || HomeFailure.Offline in failures) {
        return HomeStatusNotice(
            NoticeTone.Info,
            "オフラインです。保存済みの情報を表示しています。",
            HomeNoticeAction.Retry,
            coversToday = todayFailure == HomeFailure.Offline || linkState == LinkState.OFFLINE,
        )
    }
    if (linkState == LinkState.RECONNECTING) return HomeStatusNotice(NoticeTone.Info, "再接続しています…")

    if (HomeFailure.ProfileUnavailable in failures) {
        return HomeStatusNotice(
            NoticeTone.Warning,
            "接続プロファイルを確認できません。再試行するか接続設定を確認してください。",
            HomeNoticeAction.Retry,
            coversToday = todayFailure == HomeFailure.ProfileUnavailable,
        )
    }

    val todayOutdated = todayFailure is HomeFailure.ServerOutdated
    val careerOutdated = career is HomeFailure.ServerOutdated
    val workerOutdated = worker.any { it is HomeFailure.ServerOutdated }
    if (todayOutdated || careerOutdated || workerOutdated) {
        val text =
            when {
                todayOutdated && !careerOutdated && !workerOutdated -> "今日やることの連携にはサーバーの更新が必要です。"
                careerOutdated && !todayOutdated && !workerOutdated -> "就活のホーム連携にはサーバーの更新が必要です。"
                else -> "一部の連携にはサーバーの更新が必要です。"
            }
        return HomeStatusNotice(NoticeTone.Warning, text, coversToday = todayOutdated)
    }
    if (career == HomeFailure.Generic || career is HomeFailure.ServerUnavailable) {
        return HomeStatusNotice(
            NoticeTone.Warning,
            "就活情報を読み直せません。前回の情報を表示しています。",
            HomeNoticeAction.Retry,
        )
    }
    return null
}
